package com.xm486.pimet.translation;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.WebView;

import com.xm486.pimet.PiMetConfig;
import com.xm486.pimet.ThemeManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 网页通用全能翻译引擎架构 (WebTranslationManager)
 * 支持：
 * 1. 智能混合模式 (优先 AI，智能降级至免配置极速通道)
 * 2. 宿主/Pi 已配 AI 引擎 (DeepSeek / OpenAI 等)
 * 3. 免配置极速在线通道 (Google Chrome Extension / MyMemory 多重容灾通道)
 * 4. 自定义专属 API (用户自行输入 BaseURL / Key / Model)
 * 5. 原文直接替换模式 vs 双语对照呈现模式
 * 6. 原生 Android 桥接绕过所有 CORS / CSP 限制
 */
public class WebTranslationManager {

    private static final String TAG = "WebTranslationManager";
    public static final ExecutorService THREAD_POOL = Executors.newFixedThreadPool(4);
    /** AI 引擎（自定义 API / Pi 的模型 / 智能混合）单独的线程池：最多 6 批同时请求 */
    private static final ExecutorService AI_POOL = Executors.newFixedThreadPool(6);
    static final int AI_BATCH_MAX_ITEMS = 40;
    static final int AI_BATCH_MAX_CHARS = 2500;
    private static final int AI_READ_TIMEOUT_MS = 45000;

    /** (引擎+模型+原文) → 译文 的内存 LRU，同页刷新 / 重复文本直接命中 */
    private static final int CACHE_MAX = 3000;
    private static final Map<String, String> CACHE = new LinkedHashMap<String, String>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > CACHE_MAX;
        }
    };
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    public interface TranslationCallback {
        void onSuccess(List<String> translatedTexts);
        void onError(String error);
    }

    public interface TestCallback {
        void onResult(boolean success, long latencyMs, String original, String translated, String message);
    }

    /** 智能模式下 Pi 的模型刚失败过：这段时间内跳过它（避免一页几十批每批都白等 45 秒） */
    private static volatile long sPiModelBackoffUntil = 0L;
    private static volatile String sPiModelBackoffReason = "";
    private static final long PI_MODEL_BACKOFF_MS = 3 * 60 * 1000L;
    private static final int PI_MODEL_READ_TIMEOUT_MS = AI_READ_TIMEOUT_MS;

    /**
     * 批量翻译核心分发入口
     */
    public static void translateBatch(Context context, List<String> texts, TranslationCallback callback) {
        if (texts == null || texts.isEmpty()) {
            if (callback != null) callback.onSuccess(new ArrayList<>());
            return;
        }
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        final String engine = PiMetConfig.getTransEngine(app);
        ExecutorService pool = PiMetConfig.TRANS_ENGINE_FREE.equals(engine) ? THREAD_POOL : AI_POOL;
        pool.execute(() -> {
            // 先查缓存：整批命中就不发请求，部分命中只发没命中的
            String prefix = cachePrefix(app, engine);
            List<String> results = new ArrayList<>(texts.size());
            List<Integer> missIdx = new ArrayList<>();
            List<String> missTexts = new ArrayList<>();
            synchronized (CACHE) {
                for (int i = 0; i < texts.size(); i++) {
                    String hit = CACHE.get(prefix + texts.get(i));
                    results.add(hit);
                    if (hit == null) {
                        missIdx.add(i);
                        missTexts.add(texts.get(i));
                    }
                }
            }
            if (missTexts.isEmpty()) {
                if (callback != null) MAIN_HANDLER.post(() -> callback.onSuccess(results));
                return;
            }
            try {
                List<String> got = executeTranslation(app, engine, missTexts);
                fill(results, missIdx, missTexts, got, prefix);
                if (callback != null) MAIN_HANDLER.post(() -> callback.onSuccess(results));
            } catch (Throwable t) {
                Log.w(TAG, "Translation error with engine: " + engine + ", message: " + t.getMessage());
                String err = describe(t);
                // 指定了 AI / 自定义引擎但失败：再用免配置通道兜底一次（兜底结果不进缓存）
                if (!PiMetConfig.TRANS_ENGINE_FREE.equals(engine) && !PiMetConfig.TRANS_ENGINE_AUTO.equals(engine)) {
                    try {
                        List<String> fallback = translateWithFreeChannel(missTexts);
                        fill(results, missIdx, missTexts, fallback, null);
                        if (callback != null) MAIN_HANDLER.post(() -> callback.onSuccess(results));
                        return;
                    } catch (Throwable t2) {
                        err = err + "；极速通道也失败：" + describe(t2);
                    }
                }
                final String errMsg = err;
                if (callback != null) MAIN_HANDLER.post(() -> callback.onError(errMsg));
            }
        });
    }

    private static void fill(List<String> results, List<Integer> idx, List<String> src, List<String> got, String prefix) {
        for (int k = 0; k < idx.size(); k++) {
            String orig = src.get(k);
            String tr = got != null && k < got.size() && got.get(k) != null ? got.get(k) : orig;
            results.set(idx.get(k), tr);
            if (prefix != null && !TextUtils.isEmpty(tr) && !tr.equals(orig)) {
                synchronized (CACHE) {
                    CACHE.put(prefix + orig, tr);
                }
            }
        }
    }

    /** 缓存键前缀：引擎 + 模型（后台线程调用：Pi 模型要读配置） */
    private static String cachePrefix(Context context, String engine) {
        String model = "";
        try {
            if (PiMetConfig.TRANS_ENGINE_CUSTOM.equals(engine)) {
                model = PiMetConfig.getTransCustomBaseUrl(context) + "#" + PiMetConfig.getTransCustomModel(context);
            } else if (PiMetConfig.TRANS_ENGINE_AI.equals(engine)) {
                PiModelCatalog.PiModel m = PiModelCatalog.selected(context);
                model = m == null ? "" : m.ref();
            } else if (PiMetConfig.TRANS_ENGINE_AUTO.equals(engine)) {
                PiModelCatalog.PiModel m = PiModelCatalog.selected(context);
                model = (TextUtils.isEmpty(PiMetConfig.getTransCustomApiKey(context)) ? ""
                        : PiMetConfig.getTransCustomBaseUrl(context) + "#" + PiMetConfig.getTransCustomModel(context))
                        + "|" + (m == null ? "" : m.ref());
            }
        } catch (Throwable ignored) {}
        return engine + "\u0001" + model + "\u0001";
    }

    /** 推理/思考模型（名字以 -high 结尾）提示 */
    static String slowModelHint(String model) {
        return model != null && model.toLowerCase(java.util.Locale.ROOT).endsWith("-high")
                ? "；推理模型较慢，建议选 -low / flash 版本" : "";
    }

    /** 异常 → 一句给用户看的原因 */
    static String describe(Throwable t) {
        if (t == null) return "未知错误";
        if (t instanceof java.net.UnknownHostException) return "无法解析域名（" + t.getMessage() + "），请检查网络";
        if (t instanceof java.net.SocketTimeoutException) return "连接超时";
        if (t instanceof java.net.ConnectException) return "无法连接翻译服务";
        if (t instanceof javax.net.ssl.SSLException) return "TLS 握手失败";
        String m = t.getMessage();
        if (TextUtils.isEmpty(m)) m = t.getClass().getSimpleName();
        return m.length() > 160 ? m.substring(0, 160) + "…" : m;
    }

    /**
     * 根据引擎策略执行翻译
     */
    private static List<String> executeTranslation(Context context, String engine, List<String> texts) throws Exception {
        if (PiMetConfig.TRANS_ENGINE_FREE.equals(engine)) {
            return translateWithFreeChannel(texts);
        } else if (PiMetConfig.TRANS_ENGINE_CUSTOM.equals(engine)) {
            String baseUrl = PiMetConfig.getTransCustomBaseUrl(context);
            String apiKey = PiMetConfig.getTransCustomApiKey(context);
            String model = PiMetConfig.getTransCustomModel(context);
            if (TextUtils.isEmpty(apiKey) && TextUtils.isEmpty(baseUrl)) throw new Exception("自定义 API 未填写 Base URL / Key");
            return translateWithOpenAiCompatible(baseUrl, apiKey, model, texts);
        } else if (PiMetConfig.TRANS_ENGINE_AI.equals(engine)) {
            PiModelCatalog.PiModel m = PiModelCatalog.selected(context);
            if (m == null) throw new Exception("没读到 Pi 的模型配置（~/.pi/agent/models.json、settings.json）");
            try {
                return translateWithPiModel(m, texts);
            } catch (Throwable t) {
                throw new Exception("Pi 模型 " + m.ref() + "：" + describe(t) + keyHint(m));
            }
        }
        // 智能混合模式 (AUTO)：自定义 API（填了 Key）→ Pi 的模型（能读到）→ 免费通道；全部失败时把每一步原因一起报
        StringBuilder errs = new StringBuilder();
        String customKey = PiMetConfig.getTransCustomApiKey(context);
        if (!TextUtils.isEmpty(customKey)) {
            try {
                return translateWithOpenAiCompatible(PiMetConfig.getTransCustomBaseUrl(context), customKey,
                        PiMetConfig.getTransCustomModel(context), texts);
            } catch (Throwable t) {
                errs.append("自定义 API：").append(describe(t));
            }
        }
        PiModelCatalog.PiModel m = PiModelCatalog.selected(context);
        if (m != null) {
            if (System.currentTimeMillis() < sPiModelBackoffUntil) {
                if (errs.length() > 0) errs.append("；");
                errs.append("Pi 模型 ").append(m.ref()).append("：").append(sPiModelBackoffReason).append("（3 分钟内暂不重试）");
            } else {
                try {
                    return translateWithPiModel(m, texts);
                } catch (Throwable t) {
                    String r = describe(t) + keyHint(m);
                    sPiModelBackoffReason = r;
                    sPiModelBackoffUntil = System.currentTimeMillis() + PI_MODEL_BACKOFF_MS;
                    if (errs.length() > 0) errs.append("；");
                    errs.append("Pi 模型 ").append(m.ref()).append("：").append(r);
                }
            }
        }
        try {
            return translateWithFreeChannel(texts);
        } catch (Throwable t) {
            if (errs.length() > 0) errs.append("；");
            errs.append("免费通道：").append(describe(t));
        }
        throw new Exception(errs.toString());
    }

    private static String keyHint(PiModelCatalog.PiModel m) {
        return m != null && m.keyMissing ? "（" + m.keySource + "）" : "";
    }

    /** 用 Pi 里配置的 provider/model 翻译：按 api 类型走 OpenAI Chat Completions 或 Anthropic Messages */
    public static List<String> translateWithPiModel(PiModelCatalog.PiModel m, List<String> texts) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        String endpoint;
        boolean anthropic = m.isAnthropic();
        if (anthropic) {
            endpoint = anthropicEndpoint(m.baseUrl);
            if (!TextUtils.isEmpty(m.apiKey)) headers.put("x-api-key", m.apiKey.trim());
            headers.put("anthropic-version", "2023-06-01");
        } else {
            endpoint = openAiEndpointWithV1(m.baseUrl);
            if (!TextUtils.isEmpty(m.apiKey)) headers.put("Authorization", "Bearer " + m.apiKey.trim());
        }
        headers.putAll(m.headers);
        try {
            return llmTranslateOnce(endpoint, headers, anthropic, m.id, PI_MODEL_READ_TIMEOUT_MS, texts);
        } catch (javax.net.ssl.SSLException e) {
            if (endpoint.regionMatches(true, 0, "https://", 0, 8)) {
                return llmTranslateOnce("http://" + endpoint.substring(8), headers, anthropic, m.id, PI_MODEL_READ_TIMEOUT_MS, texts);
            }
            throw e;
        }
    }

    /** {baseUrl}/chat/completions；baseUrl 末尾没有版本段（/v1、/v4…）时补 /v1 */
    static String openAiEndpointWithV1(String baseUrl) {
        String b = trimSlash(baseUrl);
        if (b.endsWith("/chat/completions")) return b;
        if (!b.matches("(?i).*/v\\d+[a-z0-9]*$")) b = b + "/v1";
        return b + "/chat/completions";
    }

    /** {baseUrl}/v1/messages；已带 /v1 不重复 */
    static String anthropicEndpoint(String baseUrl) {
        String b = trimSlash(baseUrl);
        if (b.endsWith("/messages")) return b;
        if (!b.endsWith("/v1")) b = b + "/v1";
        return b + "/messages";
    }

    private static String trimSlash(String s) {
        String b = s == null ? "" : s.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    /**
     * 免配置极速多通道翻译 (Google GTX、Google Dict Chrome 扩展通道与 MyMemory 三重容灾)
     * 全部失败时抛出异常（以前会悄悄返回原文，页面看起来就是“点了没反应”）
     */
    public static List<String> translateWithFreeChannel(List<String> texts) throws Exception {
        if (texts == null || texts.isEmpty()) return new ArrayList<>();
        String e1, e2;
        try {
            return googleInChunks(texts, false);
        } catch (Throwable t1) {
            e1 = describe(t1);
            Log.w(TAG, "Google GTX translation channel error, trying Google Dict: " + e1);
        }
        try {
            return googleInChunks(texts, true);
        } catch (Throwable t2) {
            e2 = describe(t2);
            Log.w(TAG, "Google Chrome translation channel error, trying MyMemory: " + e2);
        }
        try {
            return translateWithMyMemory(texts);
        } catch (Throwable t3) {
            throw new Exception("免费翻译通道均不可用（Google：" + e1 + " / " + e2 + "；MyMemory：" + describe(t3) + "）");
        }
    }

    /** Google 两个通道都是 GET，URL 太长会 400/413：按长度切成几次请求 */
    private static List<String> googleInChunks(List<String> texts, boolean dictChrome) throws Exception {
        final int maxChars = 1800;
        List<String> out = new ArrayList<>();
        List<String> part = new ArrayList<>();
        int len = 0;
        for (String s : texts) {
            int l = s == null ? 0 : s.length();
            if (!part.isEmpty() && len + l > maxChars) {
                out.addAll(dictChrome ? translateWithGoogleDictChrome(part) : translateWithGoogleGtx(part));
                part = new ArrayList<>();
                len = 0;
            }
            part.add(s == null ? "" : s);
            len += l + 1;
        }
        if (!part.isEmpty()) out.addAll(dictChrome ? translateWithGoogleDictChrome(part) : translateWithGoogleGtx(part));
        return out;
    }

    /**
     * Google GTX 极速多行整批翻译通道 (延迟极低，毫秒级返回)
     */
    private static List<String> translateWithGoogleGtx(List<String> texts) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < texts.size(); i++) {
            if (i > 0) sb.append("\n");
            sb.append(texts.get(i).replace("\r", " ").replace("\n", " "));
        }
        String encoded = URLEncoder.encode(sb.toString(), "UTF-8");
        String urlStr = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=zh-CN&dt=t&q=" + encoded;

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(4000);
        conn.setReadTimeout(4500);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new Exception("HTTP " + code + ": " + conn.getResponseMessage());
        }

        String jsonResp = readStream(conn.getInputStream());
        conn.disconnect();
        if (!jsonResp.startsWith("[")) throw new Exception("Google 返回了非 JSON（可能被限流）");

        JSONArray root = new JSONArray(jsonResp);
        if (root.length() == 0 || root.isNull(0)) {
            throw new Exception("Empty Google GTX response");
        }

        JSONArray items = root.getJSONArray(0);
        StringBuilder transSb = new StringBuilder();
        for (int i = 0; i < items.length(); i++) {
            JSONArray pair = items.optJSONArray(i);
            if (pair != null && pair.length() > 0 && !pair.isNull(0)) {
                transSb.append(pair.getString(0));
            }
        }

        String[] lines = transSb.toString().split("\n", -1);
        List<String> result = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            if (i < lines.length && !TextUtils.isEmpty(lines[i].trim())) {
                result.add(lines[i]);
            } else {
                result.add(texts.get(i));
            }
        }
        return result;
    }

    /**
     * Google Chrome Extension 专用极速翻译通道 (一次请求支持全段多行并行翻译，延迟极低)
     */
    private static List<String> translateWithGoogleDictChrome(List<String> texts) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < texts.size(); i++) {
            if (i > 0) sb.append("\n");
            // 替换文本中的换行，确保行数一一对应
            String line = texts.get(i).replace("\r", " ").replace("\n", " ");
            sb.append(line);
        }

        String query = sb.toString();
        String encoded = URLEncoder.encode(query, "UTF-8");
        String urlStr = "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=auto&tl=zh-CN&q=" + encoded;

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(3500);
        conn.setReadTimeout(4000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new Exception("HTTP " + code + ": " + conn.getResponseMessage());
        }

        String jsonResp = readStream(conn.getInputStream());
        conn.disconnect();
        if (!jsonResp.startsWith("[")) throw new Exception("Google 返回了非 JSON（可能被限流）");

        JSONArray arr = new JSONArray(jsonResp);
        if (arr.length() == 0) {
            throw new Exception("Empty translation response");
        }

        String translatedAll = "";
        Object first = arr.get(0);
        if (first instanceof JSONArray) {
            JSONArray firstArr = (JSONArray) first;
            if (firstArr.length() > 0) {
                translatedAll = firstArr.getString(0);
            }
        } else if (first instanceof String) {
            translatedAll = (String) first;
        }

        String[] lines = translatedAll.split("\n", -1);
        List<String> result = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            if (i < lines.length && !TextUtils.isEmpty(lines[i])) {
                result.add(lines[i]);
            } else {
                result.add(texts.get(i));
            }
        }
        return result;
    }

    /**
     * MyMemory 备用翻译通道 (单句并发容灾)
     */
    private static List<String> translateWithMyMemory(List<String> texts) throws Exception {
        List<String> result = new ArrayList<>();
        int ok = 0, tried = 0;
        String lastErr = null;
        long deadline = System.currentTimeMillis() + 15000L;
        for (String text : texts) {
            if (TextUtils.isEmpty(text.trim()) || System.currentTimeMillis() > deadline) {
                result.add(text);
                continue;
            }
            tried++;
            HttpURLConnection conn = null;
            try {
                String encoded = URLEncoder.encode(text, "UTF-8");
                String urlStr = "https://api.mymemory.translated.net/get?q=" + encoded + "&langpair=en|zh-CN";
                conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(2500);
                conn.setReadTimeout(2500);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)");
                int code = conn.getResponseCode();
                if (code != 200) {
                    lastErr = "HTTP " + code;
                    result.add(text);
                    if (code == 403 || code == 429) break;
                    continue;
                }
                JSONObject json = new JSONObject(readStream(conn.getInputStream()));
                if (json.optBoolean("quotaFinished", false) || json.optInt("responseStatus", 200) != 200) {
                    lastErr = "今日免费额度已用完";
                    result.add(text);
                    break;
                }
                JSONObject respData = json.optJSONObject("responseData");
                String trans = respData != null ? respData.optString("translatedText", "") : "";
                if (!TextUtils.isEmpty(trans)) {
                    result.add(trans);
                    ok++;
                } else {
                    result.add(text);
                }
            } catch (Throwable t) {
                lastErr = describe(t);
                result.add(text);
                if (ok == 0 && tried >= 2) break; // 连不上就别逐条等超时了
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        if (ok == 0) throw new Exception(lastErr != null ? lastErr : "无译文");
        while (result.size() < texts.size()) result.add(texts.get(result.size()));
        return result;
    }

    /**
     * OpenAI 兼容格式大模型批处理翻译 (支持 DeepSeek, OpenAI, Ollama, SiliconFlow 等)
     */
    public static List<String> translateWithOpenAiCompatible(String baseUrl, String apiKey, String model, List<String> texts) throws Exception {
        try {
            return openAiCompatibleOnce(baseUrl, apiKey, model, texts);
        } catch (javax.net.ssl.SSLException e) {
            // 服务器在 https 端口上其实是明文 HTTP（如 https://IP:443 → Unable to parse TLS packet header）：自动改用 http:// 重试
            String b = baseUrl == null ? "" : baseUrl.trim();
            if (b.regionMatches(true, 0, "https://", 0, 8)) {
                List<String> r = openAiCompatibleOnce("http://" + b.substring(8), apiKey, model, texts);
                Log.w(TAG, "TLS failed, http:// worked for " + b);
                return r;
            }
            throw e;
        }
    }

    private static List<String> openAiCompatibleOnce(String baseUrl, String apiKey, String model, List<String> texts) throws Exception {
        if (TextUtils.isEmpty(baseUrl)) {
            baseUrl = "https://api.deepseek.com/v1";
        }
        baseUrl = baseUrl.trim();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String endpoint = baseUrl.endsWith("/chat/completions") ? baseUrl : baseUrl + "/chat/completions";

        if (TextUtils.isEmpty(model)) {
            model = "deepseek-chat";
        }

        Map<String, String> headers = new LinkedHashMap<>();
        if (!TextUtils.isEmpty(apiKey)) headers.put("Authorization", "Bearer " + apiKey.trim());
        return llmTranslateOnce(endpoint, headers, false, model, AI_READ_TIMEOUT_MS, texts);
    }

    private static final String SYSTEM_PROMPT = "You are an accurate web page translation engine. Translate each string in the input JSON array into Simplified Chinese (zh-CN).\n" +
            "Requirements:\n" +
            "1. Maintain exact array length and element order.\n" +
            "2. Preserve code tokens, URLs, numbers, HTML placeholders, and formatting symbols.\n" +
            "3. Output ONLY a valid JSON array of strings without markdown code blocks (no ```json) and without any extra text.";

    /** 发一次大模型翻译请求（OpenAI Chat Completions 或 Anthropic Messages），返回与 texts 等长的译文 */
    private static List<String> llmTranslateOnce(String endpoint, Map<String, String> headers, boolean anthropic,
                                                 String model, int readTimeoutMs, List<String> texts) throws Exception {
        JSONArray inputArr = new JSONArray();
        int chars = 0;
        for (String t : texts) {
            inputArr.put(t);
            chars += t == null ? 0 : t.length();
        }
        // 输出预算按输入字数 ×3 估算；不开 thinking / reasoning（推理模型太慢）
        int maxTokens = Math.max(anthropic ? 1024 : 512, Math.min(8192, chars * 3 + 64));

        JSONObject req = new JSONObject();
        req.put("model", model);
        req.put("temperature", 0);
        req.put("max_tokens", maxTokens);
        JSONArray messages = new JSONArray();
        if (anthropic) {
            req.put("system", SYSTEM_PROMPT);
        } else {
            messages.put(new JSONObject().put("role", "system").put("content", SYSTEM_PROMPT));
        }
        messages.put(new JSONObject().put("role", "user").put("content", inputArr.toString()));
        req.put("messages", messages);

        try {
            return llmPost(endpoint, headers, anthropic, readTimeoutMs, req, texts);
        } catch (HttpStatusException e) {
            // 少数接口不认 temperature / max_tokens（如部分 OpenAI 新模型）：去掉这两个参数再试一次
            String b = e.body == null ? "" : e.body.toLowerCase(java.util.Locale.ROOT);
            if (!anthropic && e.code == 400 && (b.contains("max_tokens") || b.contains("temperature"))) {
                req.remove("max_tokens");
                req.remove("temperature");
                return llmPost(endpoint, headers, false, readTimeoutMs, req, texts);
            }
            throw e;
        }
    }

    private static final class HttpStatusException extends Exception {
        final int code;
        final String body;

        HttpStatusException(int code, String body, String msg) {
            super(msg);
            this.code = code;
            this.body = body;
        }
    }

    private static List<String> llmPost(String endpoint, Map<String, String> headers, boolean anthropic,
                                        int readTimeoutMs, JSONObject req, List<String> texts) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            for (Map.Entry<String, String> h : headers.entrySet()) {
                if (!TextUtils.isEmpty(h.getKey()) && h.getValue() != null) conn.setRequestProperty(h.getKey(), h.getValue());
            }
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(req.toString().getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String errBody = "";
                try {
                    errBody = readStream(conn.getErrorStream());
                } catch (Throwable ignored) {}
                throw new HttpStatusException(code, errBody, "HTTP " + code + " " + endpoint + "：" + (TextUtils.isEmpty(errBody) ? conn.getResponseMessage() : errBody));
            }
            String respStr = readStream(conn.getInputStream());
            JSONObject resp = new JSONObject(respStr);
            String content;
            if (anthropic) {
                JSONArray parts = resp.optJSONArray("content");
                if (parts == null || parts.length() == 0) throw new Exception("模型响应里没有 content");
                content = null;
                for (int i = 0; i < parts.length() && content == null; i++) {
                    JSONObject part = parts.optJSONObject(i);
                    if (part != null && "text".equals(part.optString("type", "text")) && part.has("text")) content = part.optString("text");
                }
                if (content == null) content = parts.getJSONObject(0).optString("text", "");
            } else {
                JSONArray choices = resp.optJSONArray("choices");
                if (choices == null || choices.length() == 0) throw new Exception("模型响应里没有 choices");
                JSONObject msgObj = choices.getJSONObject(0).optJSONObject("message");
                if (msgObj == null || !msgObj.has("content")) throw new Exception("模型响应里没有 message.content");
                content = msgObj.optString("content", "");
            }
            return parseModelArray(content, texts);
        } finally {
            conn.disconnect();
        }
    }

    /** 从模型输出里取 JSON 字符串数组（容忍 ```json 包裹和前后多余文字） */
    private static List<String> parseModelArray(String raw, List<String> texts) throws Exception {
        String content = raw == null ? "" : raw.trim();
        if (content.startsWith("```")) {
            int firstNewline = content.indexOf('\n');
            if (firstNewline != -1) content = content.substring(firstNewline + 1);
            if (content.endsWith("```")) content = content.substring(0, content.length() - 3);
            content = content.trim();
        }
        int startBracket = content.indexOf('[');
        int endBracket = content.lastIndexOf(']');
        if (startBracket == -1 || endBracket <= startBracket) {
            throw new Exception("模型没有返回 JSON 数组：" + (content.length() > 80 ? content.substring(0, 80) + "…" : content));
        }
        JSONArray outArr = new JSONArray(content.substring(startBracket, endBracket + 1));
        List<String> results = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            results.add(i < outArr.length() ? outArr.optString(i, texts.get(i)) : texts.get(i));
        }
        return results;
    }

    /**
     * 测试翻译引擎连通性与延迟
     */
    public static void testTranslation(Context context, String engine, String baseUrl, String apiKey, String model, TestCallback callback) {
        testTranslation(context, engine, baseUrl, apiKey, model, null, callback);
    }

    private static String testHint(Context context, String engine, String model, String piModelRef) {
        try {
            if (PiMetConfig.TRANS_ENGINE_CUSTOM.equals(engine)) return slowModelHint(model);
            if (PiMetConfig.TRANS_ENGINE_AI.equals(engine) || PiMetConfig.TRANS_ENGINE_AUTO.equals(engine)) {
                PiModelCatalog.PiModel m = PiModelCatalog.find(context, piModelRef != null ? piModelRef : PiModelCatalog.savedRef(context));
                String h = m == null ? "" : slowModelHint(m.id);
                if (h.isEmpty() && PiMetConfig.TRANS_ENGINE_AUTO.equals(engine)) h = slowModelHint(PiMetConfig.getTransCustomModel(context));
                return h;
            }
        } catch (Throwable ignored) {}
        return "";
    }

    /** piModelRef：「Pi 的模型」引擎要测的 provider/model（null = 已保存的选择 / Pi 默认） */
    public static void testTranslation(Context context, String engine, String baseUrl, String apiKey, String model,
                                       String piModelRef, TestCallback callback) {
        THREAD_POOL.execute(() -> {
            long start = System.currentTimeMillis();
            final String sample = "Hello, welcome to PiMet universal web translation test!";
            List<String> singleList = new ArrayList<>();
            singleList.add(sample);

            try {
                List<String> res;
                if (PiMetConfig.TRANS_ENGINE_FREE.equals(engine)) {
                    res = translateWithFreeChannel(singleList);
                } else if (PiMetConfig.TRANS_ENGINE_CUSTOM.equals(engine)) {
                    res = translateWithOpenAiCompatible(baseUrl, apiKey, model, singleList);
                } else if (PiMetConfig.TRANS_ENGINE_AI.equals(engine)) {
                    PiModelCatalog.PiModel m = PiModelCatalog.find(context, piModelRef != null ? piModelRef : PiModelCatalog.savedRef(context));
                    if (m == null) throw new Exception("没读到 Pi 的模型配置（~/.pi/agent/models.json、settings.json）");
                    try {
                        res = translateWithPiModel(m, singleList);
                    } catch (Throwable t) {
                        throw new Exception(m.ref() + "：" + describe(t) + keyHint(m));
                    }
                } else {
                    res = executeTranslation(context, engine, singleList);
                }

                long latency = System.currentTimeMillis() - start;
                String translated = (res != null && !res.isEmpty()) ? res.get(0) : "未得到有效译文";
                final String okMsg = "连接测试成功" + testHint(context, engine, model, piModelRef);

                MAIN_HANDLER.post(() -> {
                    if (callback != null) {
                        callback.onResult(true, latency, sample, translated, okMsg);
                    }
                });
            } catch (Throwable t) {
                long latency = System.currentTimeMillis() - start;
                final String err = (t.getMessage() != null ? t.getMessage() : t.toString()) + testHint(context, engine, model, piModelRef);
                MAIN_HANDLER.post(() -> {
                    if (callback != null) {
                        callback.onResult(false, latency, sample, "", "测试失败: " + err);
                    }
                });
            }
        });
    }

    /**
     * 读取输入流内容
     */
    private static String readStream(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        reader.close();
        return sb.toString().trim();
    }

    /**
     * 生成注入到 WebView 的高可靠 DOM 遍历、分批批处理与动态监听 JavaScript
     */
    public static String buildInjectionScript(Context context) {
        String mode = PiMetConfig.getTransDisplayMode(context);
        boolean live = PiMetConfig.isTransLiveEnabled(context);
        ThemeManager.ThemePalette palette = ThemeManager.getEffectivePalette(context);

        String bgHex = String.format("#%06X", (0xFFFFFF & palette.bgPanel));
        String borderHex = String.format("#%06X", (0xFFFFFF & palette.border));
        String textHex = String.format("#%06X", (0xFFFFFF & palette.text));
        String accentHex = String.format("#%06X", (0xFFFFFF & palette.accent));
        boolean aiEngine = !PiMetConfig.TRANS_ENGINE_FREE.equals(PiMetConfig.getTransEngine(context));
        int batchMax = aiEngine ? AI_BATCH_MAX_ITEMS : 15;
        int charMax = aiEngine ? AI_BATCH_MAX_CHARS : 100000;

        return "(function() {\n" +
                "  var W = window;\n" +
                "  try {\n" +
                "    if (!document.body) return 'nobody';\n" +
                "    var B = W.PiMetTranslator;\n" +
                "    if (!B || typeof B.requestBatch !== 'function') return 'nobridge';\n" +
                "    if (W.__pimet_is_translating && W.__pimet_is_translated) return 'busy';\n" +
                "    if (W.__pimet_restore && ((W.__pimet_all_nodes && W.__pimet_all_nodes.length) || (W.__pimet_pending_els && W.__pimet_pending_els.length) || W.__pimet_io)) { try { W.__pimet_restore(true); } catch (e0) {} }\n" +
                "    W.__pimet_is_translating = true;\n" +
                "    W.__pimet_is_translated = true;\n" +
                "    W.__pimet_mode = '" + mode + "';\n" +
                "    W.__pimet_live = " + live + ";\n" +
                "    W.__pimet_batches = {};\n" +
                "    W.__pimet_all_nodes = [];\n" +
                "    W.__pimet_seq = W.__pimet_seq || 0;\n" +
                "    W.__pimet_total = 0;\n" +
                "    W.__pimet_sent = 0;\n" +
                "    W.__pimet_done = 0;\n" +
                "    W.__pimet_pending = 0;\n" +
                "    W.__pimet_pending_els = [];\n" +
                "    W.__pimet_failed = 0;\n" +
                "    W.__pimet_last_error = '';\n" +
                "\n" +
                "    function capsule(text, isDone) {\n" +
                "      var cap = document.getElementById('pimet-trans-capsule');\n" +
                "      if (!cap) {\n" +
                "        cap = document.createElement('div');\n" +
                "        cap.id = 'pimet-trans-capsule';\n" +
                "        cap.setAttribute('style', 'position:fixed;top:10px;right:12px;z-index:2147483647;background:" + bgHex + ";color:" + accentHex + ";border:1px solid " + borderHex + ";border-radius:18px;padding:6px 12px;font-size:11.5px;font-family:system-ui,-apple-system,sans-serif;box-shadow:0 3px 12px rgba(0,0,0,0.3);display:flex;align-items:center;gap:6px;cursor:pointer;user-select:none;max-width:80vw;');\n" +
                "        cap.onclick = function() { try { W.PiMetTranslator.openSettingsDialog(); } catch (e) {} };\n" +
                "        (document.body || document.documentElement).appendChild(cap);\n" +
                "      }\n" +
                "      cap.textContent = '\\u{1F310} ' + text;\n" +
                "      cap.style.opacity = '1';\n" +
                "      if (W.__pimet_cap_timer) { clearTimeout(W.__pimet_cap_timer); W.__pimet_cap_timer = null; }\n" +
                "      if (isDone) {\n" +
                "        W.__pimet_cap_timer = setTimeout(function() {\n" +
                "          if (cap && cap.parentNode) { cap.style.opacity = '0.75'; cap.textContent = '\\u{1F310} ' + text + ' \\u00B7 点此设置'; }\n" +
                "        }, 3500);\n" +
                "      }\n" +
                "    }\n" +
                "    W.__pimet_capsule = capsule;\n" +
                "\n" +
                "    var IGNORE_TAGS = {SCRIPT:1, STYLE:1, NOSCRIPT:1, CODE:1, PRE:1, SVG:1, CANVAS:1, IFRAME:1, TEXTAREA:1, INPUT:1, OPTION:1, TEMPLATE:1};\n" +
                "    function isIgnored(parent) {\n" +
                "      if (!parent || !parent.tagName) return true;\n" +
                "      if (IGNORE_TAGS[String(parent.tagName).toUpperCase()]) return true;\n" +
                "      if (parent.closest && parent.closest('#pimet-trans-capsule, .pimet-bilingual-trans, .xterm, .terminal, .monaco-editor, .ace_editor, pre, code, [contenteditable=\"\"], [contenteditable=\"true\"]')) return true;\n" +
                "      return false;\n" +
                "    }\n" +
                "    function collectTextNodes(root) {\n" +
                "      var nodes = [];\n" +
                "      var walker = document.createTreeWalker(root || document.body, NodeFilter.SHOW_TEXT, {\n" +
                "        acceptNode: function(node) {\n" +
                "          if (!node || !node.nodeValue) return NodeFilter.FILTER_REJECT;\n" +
                "          if (node.__pimet_orig !== undefined || node.__pimet_done || node.__pimet_q) return NodeFilter.FILTER_REJECT;\n" +
                "          var val = node.nodeValue.trim();\n" +
                "          if (val.length < 2) return NodeFilter.FILTER_REJECT;\n" +
                "          if (isIgnored(node.parentElement)) return NodeFilter.FILTER_REJECT;\n" +
                "          if (!/[a-zA-Z\\u00C0-\\u024F\\u0400-\\u04FF\\u3040-\\u30FF\\uAC00-\\uD7AF]/.test(val)) return NodeFilter.FILTER_REJECT;\n" +
                "          return NodeFilter.FILTER_ACCEPT;\n" +
                "        }\n" +
                "      });\n" +
                "      while (walker.nextNode()) nodes.push(walker.currentNode);\n" +
                "      return nodes;\n" +
                "    }\n" +
                "    var BATCH_MAX = " + batchMax + ", CHAR_MAX = " + charMax + ";\n" +
                "    function sendBatch(chunk) {\n" +
                "      var batchId = 'pbatch_' + (++W.__pimet_seq);\n" +
                "      var texts = [];\n" +
                "      for (var j = 0; j < chunk.length; j++) {\n" +
                "        chunk[j].__pimet_orig = chunk[j].nodeValue;\n" +
                "        delete chunk[j].__pimet_q;\n" +
                "        texts.push(chunk[j].nodeValue.trim());\n" +
                "        W.__pimet_all_nodes.push(chunk[j]);\n" +
                "      }\n" +
                "      W.__pimet_batches[batchId] = chunk;\n" +
                "      W.__pimet_sent += chunk.length;\n" +
                "      W.__pimet_is_translating = true;\n" +
                "      armWatchdog();\n" +
                "      W.PiMetTranslator.requestBatch(batchId, JSON.stringify(texts));\n" +
                "    }\n" +
                "    function send(nodes) {\n" +
                "      var chunk = [], chars = 0;\n" +
                "      for (var i = 0; i < nodes.length; i++) {\n" +
                "        var n = nodes[i];\n" +
                "        if (!n || n.__pimet_orig !== undefined || n.__pimet_done || !n.nodeValue) continue;\n" +
                "        var l = n.nodeValue.trim().length;\n" +
                "        if (chunk.length && (chunk.length >= BATCH_MAX || chars + l > CHAR_MAX)) { sendBatch(chunk); chunk = []; chars = 0; }\n" +
                "        chunk.push(n); chars += l;\n" +
                "      }\n" +
                "      if (chunk.length) sendBatch(chunk);\n" +
                "      progress();\n" +
                "    }\n" +
                "    function nodeTop(n) {\n" +
                "      var el = n.parentElement;\n" +
                "      if (!el || !el.getBoundingClientRect) return null;\n" +
                "      var r = el.getBoundingClientRect();\n" +
                "      if (r.width === 0 && r.height === 0) return null;\n" +
                "      return r;\n" +
                "    }\n" +
                "    function isNear(r) {\n" +
                "      var h = W.innerHeight || document.documentElement.clientHeight || 800;\n" +
                "      return r && r.bottom >= -h && r.top <= 2 * h;\n" +
                "    }\n" +
                "    var queue = [], qTimer = null;\n" +
                "    function flushQueue() {\n" +
                "      qTimer = null;\n" +
                "      if (!W.__pimet_is_translated || !queue.length) return;\n" +
                "      var q = queue; queue = [];\n" +
                "      q.sort(function(a, b) { var ra = nodeTop(a), rb = nodeTop(b); return (ra ? ra.top : 1e9) - (rb ? rb.top : 1e9); });\n" +
                "      send(q);\n" +
                "    }\n" +
                "    function enqueue(nodes) {\n" +
                "      for (var i = 0; i < nodes.length; i++) { if (nodes[i].__pimet_q) { nodes[i].__pimet_q = false; W.__pimet_pending--; queue.push(nodes[i]); } }\n" +
                "      if (!qTimer) qTimer = setTimeout(flushQueue, 120);\n" +
                "    }\n" +
                "    function onNear(el) {\n" +
                "      var list = el.__pimet_pending; el.__pimet_pending = null;\n" +
                "      if (W.__pimet_io) W.__pimet_io.unobserve(el);\n" +
                "      if (list) enqueue(list);\n" +
                "    }\n" +
                "    if (!W.__pimet_io && W.IntersectionObserver) {\n" +
                "      W.__pimet_io = new IntersectionObserver(function(entries) {\n" +
                "        for (var i = 0; i < entries.length; i++) if (entries[i].isIntersecting) onNear(entries[i].target);\n" +
                "      }, {rootMargin: '100% 0px 100% 0px'});\n" +
                "    }\n" +
                "    if (!W.__pimet_io && !W.__pimet_scroll_fn) {\n" +
                "      var sTimer = null;\n" +
                "      W.__pimet_scroll_fn = function() {\n" +
                "        if (sTimer) return;\n" +
                "        sTimer = setTimeout(function() {\n" +
                "          sTimer = null;\n" +
                "          var els = W.__pimet_pending_els || [], keep = [];\n" +
                "          for (var i = 0; i < els.length; i++) { var el = els[i]; if (!el.__pimet_pending) continue; var r = el.getBoundingClientRect(); if (isNear(r) && (r.width || r.height)) onNear(el); else keep.push(el); }\n" +
                "          W.__pimet_pending_els = keep;\n" +
                "        }, 250);\n" +
                "      };\n" +
                "      W.addEventListener('scroll', W.__pimet_scroll_fn, {passive: true, capture: true});\n" +
                "    }\n" +
                "    function schedule(nodes) {\n" +
                "      var near = [];\n" +
                "      for (var i = 0; i < nodes.length; i++) {\n" +
                "        var n = nodes[i], r = nodeTop(n);\n" +
                "        if (isNear(r)) { near.push({n: n, t: r.top}); continue; }\n" +
                "        var el = n.parentElement;\n" +
                "        if (!el) continue;\n" +
                "        n.__pimet_q = true;\n" +
                "        W.__pimet_pending++;\n" +
                "        if (!el.__pimet_pending) { el.__pimet_pending = []; W.__pimet_pending_els.push(el); if (W.__pimet_io) W.__pimet_io.observe(el); }\n" +
                "        el.__pimet_pending.push(n);\n" +
                "      }\n" +
                "      near.sort(function(a, b) { return a.t - b.t; });\n" +
                "      var arr = [];\n" +
                "      for (var k = 0; k < near.length; k++) arr.push(near[k].n);\n" +
                "      if (arr.length) send(arr); else progress();\n" +
                "    }\n" +
                "    function armWatchdog() {\n" +
                "      if (W.__pimet_watchdog) clearTimeout(W.__pimet_watchdog);\n" +
                "      W.__pimet_watchdog = setTimeout(function() {\n" +
                "        W.__pimet_watchdog = null;\n" +
                "        if (W.__pimet_is_translating && W.__pimet_done < W.__pimet_sent) {\n" +
                "          capsule('翻译超时 (已翻 ' + (W.__pimet_done - W.__pimet_failed) + '/' + W.__pimet_total + ')，剩余部分仍在后台翻译', true);\n" +
                "          try { W.PiMetTranslator.showToast('网页翻译超时：翻译服务响应太慢，可长按「译」换成极速通道'); } catch (e5) {}\n" +
                "        }\n" +
                "      }, 60000);\n" +
                "    }\n" +
                "    function progress() {\n" +
                "      var ok = W.__pimet_done - W.__pimet_failed;\n" +
                "      if (W.__pimet_done >= W.__pimet_sent) {\n" +
                "        W.__pimet_is_translating = false;\n" +
                "        if (W.__pimet_watchdog) { clearTimeout(W.__pimet_watchdog); W.__pimet_watchdog = null; }\n" +
                "        if (W.__pimet_sent > 0 && W.__pimet_failed >= W.__pimet_sent) {\n" +
                "          capsule('翻译失败：' + (W.__pimet_last_error || '翻译服务无响应'), true);\n" +
                "          try { W.PiMetTranslator.onStateChanged(false, 0, W.__pimet_total); } catch (e1) {}\n" +
                "        } else if (W.__pimet_pending > 0) {\n" +
                "          capsule('已翻 ' + ok + '/' + W.__pimet_total + ' · 滚动继续' + (W.__pimet_failed ? ' · ' + W.__pimet_failed + ' 条失败' : ''), true);\n" +
                "          try { W.PiMetTranslator.onStateChanged(true, W.__pimet_done, W.__pimet_total); } catch (e2) {}\n" +
                "        } else {\n" +
                "          capsule('翻译完成 (已翻 ' + ok + '/' + W.__pimet_total + ')' + (W.__pimet_failed ? ' · ' + W.__pimet_failed + ' 条失败' : ''), true);\n" +
                "          try { W.PiMetTranslator.onStateChanged(true, W.__pimet_done, W.__pimet_total); } catch (e2) {}\n" +
                "        }\n" +
                "        if (W.__pimet_live && W.__pimet_observer && W.__pimet_is_translated) W.__pimet_observer.observe(document.body, {childList: true, subtree: true});\n" +
                "      } else {\n" +
                "        capsule('翻译中 · 已翻 ' + ok + '/' + W.__pimet_total, false);\n" +
                "      }\n" +
                "    }\n" +
                "\n" +
                "    W.__pimet_receive_batch = function(batchId, transJsonStr, isError, errMsg) {\n" +
                "      var chunk = W.__pimet_batches[batchId];\n" +
                "      delete W.__pimet_batches[batchId];\n" +
                "      if (!W.__pimet_is_translated || !chunk || chunk.length === 0) return;\n" +
                "      if (W.__pimet_observer) W.__pimet_observer.disconnect();\n" +
                "      var transArr = [];\n" +
                "      if (!isError && transJsonStr) { try { transArr = JSON.parse(transJsonStr); } catch (e3) { isError = true; errMsg = '译文解析失败'; } }\n" +
                "      if (isError) { W.__pimet_failed += chunk.length; if (errMsg) W.__pimet_last_error = String(errMsg); }\n" +
                "      for (var k = 0; k < chunk.length; k++) {\n" +
                "        var node = chunk[k];\n" +
                "        node.__pimet_done = true;\n" +
                "        var trans = (!isError && k < transArr.length) ? transArr[k] : '';\n" +
                "        if (isError) { delete node.__pimet_orig; continue; }\n" +
                "        if (trans && String(trans).trim() && String(trans).trim() !== String(node.__pimet_orig).trim()) {\n" +
                "          if (W.__pimet_mode === 'bilingual' && node.parentNode) {\n" +
                "            var sub = document.createElement('span');\n" +
                "            sub.className = 'pimet-bilingual-trans';\n" +
                "            sub.setAttribute('style', 'display:inline-block;margin-left:4px;font-size:0.88em;color:" + accentHex + ";opacity:0.88;font-weight:normal;vertical-align:baseline;');\n" +
                "            sub.textContent = '\\u3010' + trans + '\\u3011';\n" +
                "            node.parentNode.insertBefore(sub, node.nextSibling);\n" +
                "            node.__pimet_sub = sub;\n" +
                "          } else {\n" +
                "            var o = String(node.__pimet_orig);\n" +
                "            node.nodeValue = o.match(/^\\s*/)[0] + trans + o.match(/\\s*$/)[0];\n" +
                "          }\n" +
                "        }\n" +
                "      }\n" +
                "      W.__pimet_done += chunk.length;\n" +
                "      progress();\n" +
                "    };\n" +
                "\n" +
                "    W.__pimet_restore = function(silent) {\n" +
                "      W.__pimet_is_translated = false;\n" +
                "      W.__pimet_is_translating = false;\n" +
                "      W.__pimet_batches = {};\n" +
                "      if (W.__pimet_observer) { W.__pimet_observer.disconnect(); W.__pimet_observer = null; }\n" +
                "      if (W.__pimet_io) { W.__pimet_io.disconnect(); W.__pimet_io = null; }\n" +
                "      if (W.__pimet_scroll_fn) { W.removeEventListener('scroll', W.__pimet_scroll_fn, {capture: true}); W.__pimet_scroll_fn = null; }\n" +
                "      var pe = W.__pimet_pending_els || [];\n" +
                "      for (var pi = 0; pi < pe.length; pi++) { var pl = pe[pi].__pimet_pending; if (pl) { for (var pj = 0; pj < pl.length; pj++) delete pl[pj].__pimet_q; } pe[pi].__pimet_pending = null; }\n" +
                "      W.__pimet_pending_els = [];\n" +
                "      W.__pimet_pending = 0;\n" +
                "      if (W.__pimet_watchdog) { clearTimeout(W.__pimet_watchdog); W.__pimet_watchdog = null; }\n" +
                "      var all = W.__pimet_all_nodes || [];\n" +
                "      for (var m = 0; m < all.length; m++) {\n" +
                "        var item = all[m];\n" +
                "        if (item.__pimet_orig !== undefined) { item.nodeValue = item.__pimet_orig; }\n" +
                "        delete item.__pimet_orig;\n" +
                "        delete item.__pimet_done;\n" +
                "        if (item.__pimet_sub && item.__pimet_sub.parentNode) item.__pimet_sub.parentNode.removeChild(item.__pimet_sub);\n" +
                "        delete item.__pimet_sub;\n" +
                "      }\n" +
                "      W.__pimet_all_nodes = [];\n" +
                "      var cap = document.getElementById('pimet-trans-capsule');\n" +
                "      if (cap) cap.remove();\n" +
                "      if (!silent) { try { W.PiMetTranslator.onStateChanged(false, 0, 0); } catch (e4) {} }\n" +
                "    };\n" +
                "\n" +
                "    var candidates = collectTextNodes(document.body);\n" +
                "    W.__pimet_total = candidates.length;\n" +
                "    if (candidates.length === 0) {\n" +
                "      W.__pimet_is_translating = false;\n" +
                "      W.__pimet_is_translated = false;\n" +
                "      capsule('页面无需翻译或已是中文', true);\n" +
                "      return 'none';\n" +
                "    }\n" +
                "    capsule('翻译中 · 已翻 0/' + W.__pimet_total, false);\n" +
                "    schedule(candidates);\n" +
                "\n" +
                "    if (W.__pimet_live && !W.__pimet_observer && W.MutationObserver) {\n" +
                "      var timer = null, liveRuns = 0;\n" +
                "      W.__pimet_observer = new MutationObserver(function() {\n" +
                "        if (W.__pimet_is_translating || !W.__pimet_is_translated || liveRuns >= 30) return;\n" +
                "        clearTimeout(timer);\n" +
                "        timer = setTimeout(function() {\n" +
                "          if (W.__pimet_is_translating || !W.__pimet_is_translated || liveRuns >= 30) return;\n" +
                "          var more = collectTextNodes(document.body);\n" +
                "          if (more.length > 0) {\n" +
                "            liveRuns++;\n" +
                "            W.__pimet_total += more.length;\n" +
                "            schedule(more);\n" +
                "          }\n" +
                "        }, 1200);\n" +
                "      });\n" +
                "    }\n" +
                "    return 'ok:' + candidates.length;\n" +
                "  } catch (e) {\n" +
                "    W.__pimet_is_translating = false;\n" +
                "    W.__pimet_is_translated = false;\n" +
                "    try { console.error('PiMet Translator init error', e); } catch (e6) {}\n" +
                "    return 'err:' + (e && e.message ? e.message : String(e));\n" +
                "  }\n" +
                "})();\n";
    }

    /**
     * 触发对 WebView 注入翻译脚本或切换还原；任何一步失败都用 Toast 说明原因
     */
    public static void toggleTranslation(WebView webView, Context context) {
        if (context == null) return;
        if (webView == null) {
            toast(context, "网页翻译失败：当前没有打开的网页");
            return;
        }
        try {
            if (!webView.getSettings().getJavaScriptEnabled()) {
                toast(context, "网页翻译失败：此页面关闭了 JavaScript");
                return;
            }
            String url = webView.getUrl();
            if (TextUtils.isEmpty(url) || url.startsWith("about:") || url.startsWith("data:")) {
                toast(context, "网页翻译失败：页面还没加载完成");
                return;
            }
            String checkJs = "(function(){return window.__pimet_is_translated===true;})()";
            webView.evaluateJavascript(checkJs, value -> {
                if (value == null) {
                    toast(context, "网页翻译失败：WebView 未执行脚本（页面可能还在加载）");
                    return;
                }
                if ("true".equals(value)) {
                    webView.evaluateJavascript("if (window.__pimet_restore) window.__pimet_restore();", null);
                    toast(context, "已还原网页原文");
                } else {
                    inject(webView, context, true);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "toggleTranslation failed", t);
            toast(context, "网页翻译失败：" + describe(t));
        }
    }

    /** 注入翻译脚本，按脚本返回的状态给出提示 */
    private static void inject(WebView webView, Context context, boolean announce) {
        String script = buildInjectionScript(context);
        webView.evaluateJavascript(script, value -> {
            String s = value == null ? "" : value;
            if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
                try {
                    s = new JSONArray("[" + s + "]").getString(0);
                } catch (Throwable ignored) {
                    s = s.substring(1, s.length() - 1);
                }
            }
            if (s.startsWith("ok:")) {
                if (announce) toast(context, "正在翻译网页（" + s.substring(3) + " 段）...");
            } else if ("none".equals(s)) {
                toast(context, "页面没有需要翻译的外文");
            } else if ("busy".equals(s)) {
                toast(context, "上一次翻译还在进行中");
            } else if ("nobridge".equals(s)) {
                toast(context, "网页翻译失败：翻译接口未注入到此页面（window.PiMetTranslator 不存在），请刷新页面后重试");
            } else if ("nobody".equals(s)) {
                toast(context, "网页翻译失败：页面还没加载完成");
            } else if (s.startsWith("err:")) {
                toast(context, "网页翻译失败：脚本出错 " + s.substring(4));
            } else {
                toast(context, "网页翻译失败：脚本没有执行（返回 " + (value == null ? "null" : value) + "）");
            }
        });
    }

    static void toast(Context context, String msg) {
        if (context == null || msg == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            final Context c0 = context;
            MAIN_HANDLER.post(() -> toast(c0, msg));
            return;
        }
        Context c = context;
        if (c instanceof android.app.Activity && ((android.app.Activity) c).isFinishing()) {
            c = c.getApplicationContext();
        }
        android.widget.Toast.makeText(c, msg, android.widget.Toast.LENGTH_LONG).show();
    }

    /**
     * 重新注入并刷新翻译 (在修改设置或引擎后调用)
     */
    public static void refreshTranslation(WebView webView, Context context) {
        if (webView == null || context == null) return;
        webView.evaluateJavascript("if (window.__pimet_restore) window.__pimet_restore(true);", null);
        inject(webView, context, false);
    }
}
