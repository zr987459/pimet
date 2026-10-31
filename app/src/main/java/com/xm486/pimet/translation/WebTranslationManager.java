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
import java.util.List;
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
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    public interface TranslationCallback {
        void onSuccess(List<String> translatedTexts);
        void onError(String error);
    }

    public interface TestCallback {
        void onResult(boolean success, long latencyMs, String original, String translated, String message);
    }

    /** Pi 的 AI 刚失败过时，这段时间内智能模式不再先试它（避免每批都白等超时） */
    private static volatile long sHostAiBackoffUntil = 0L;
    private static final long HOST_AI_BACKOFF_MS = 10 * 60 * 1000L;

    /**
     * 批量翻译核心分发入口
     */
    public static void translateBatch(Context context, List<String> texts, TranslationCallback callback) {
        if (texts == null || texts.isEmpty()) {
            if (callback != null) callback.onSuccess(new ArrayList<>());
            return;
        }
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        THREAD_POOL.execute(() -> {
            String engine = PiMetConfig.getTransEngine(app);
            try {
                List<String> results = executeTranslation(app, engine, texts);
                if (callback != null) MAIN_HANDLER.post(() -> callback.onSuccess(results));
            } catch (Throwable t) {
                Log.w(TAG, "Translation error with engine: " + engine + ", message: " + t.getMessage());
                String err = describe(t);
                // 指定了 AI / 自定义引擎但失败：再用免配置通道兜底一次
                if (!PiMetConfig.TRANS_ENGINE_FREE.equals(engine) && !PiMetConfig.TRANS_ENGINE_AUTO.equals(engine)) {
                    try {
                        List<String> fallback = translateWithFreeChannel(texts);
                        if (callback != null) MAIN_HANDLER.post(() -> callback.onSuccess(fallback));
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

    /** Pi（宿主）AI 配置换算成 OpenAI 兼容端点；Claude（Anthropic 接口）等不兼容时返回 null */
    static String[] hostAiEndpoint(Context context) {
        String key = PiMetConfig.getAiApiKey(context);
        if (TextUtils.isEmpty(key)) return null;
        String provider = PiMetConfig.getAiProvider(context);
        String base = PiMetConfig.getAiBaseUrl(context);
        String model = PiMetConfig.getAiModel(context);
        boolean deepseekBase = TextUtils.isEmpty(base) || base.contains("api.deepseek.com");
        if (PiMetConfig.PROVIDER_CLAUDE.equals(provider)) return null;
        if (PiMetConfig.PROVIDER_OPENROUTER.equals(provider)) {
            base = deepseekBase ? "https://openrouter.ai/api/v1" : base;
        } else if (PiMetConfig.PROVIDER_OPENAI.equals(provider)) {
            base = deepseekBase ? "https://api.openai.com/v1" : base;
        } else if (PiMetConfig.PROVIDER_CUSTOM.equals(provider)) {
            if (deepseekBase && !TextUtils.isEmpty(model) && !model.toLowerCase(java.util.Locale.ROOT).contains("deepseek")) return null;
        } else if (TextUtils.isEmpty(base)) {
            base = "https://api.deepseek.com";
        }
        if (model != null && model.contains("/") && !PiMetConfig.PROVIDER_OPENROUTER.equals(provider) && !PiMetConfig.PROVIDER_CUSTOM.equals(provider)) {
            model = model.substring(model.lastIndexOf('/') + 1);
        }
        return new String[]{base, key, model};
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
            String[] ep = hostAiEndpoint(context);
            if (ep == null) {
                if (TextUtils.isEmpty(PiMetConfig.getAiApiKey(context))) {
                    return translateWithFreeChannel(texts); // 未配置 AI Key，自动回退到极速通道
                }
                throw new Exception("Pi 当前模型不是 OpenAI 兼容接口（如 Claude），无法用于网页翻译");
            }
            return translateWithOpenAiCompatible(ep[0], ep[1], ep[2], texts);
        }
        // 智能混合模式 (AUTO)：自定义 API（填了 Key）→ 免配置极速通道 → Pi 的 AI
        // 以前优先用 Pi 的 AI：Pi 同步来的往往是 Claude / OpenRouter / 推理模型，端点不对或一批要几十秒，网页看起来就是“没翻译”
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
        try {
            return translateWithFreeChannel(texts);
        } catch (Throwable t) {
            if (errs.length() > 0) errs.append("；");
            errs.append(describe(t));
        }
        String[] ep = hostAiEndpoint(context);
        if (ep != null && System.currentTimeMillis() >= sHostAiBackoffUntil) {
            try {
                return translateWithOpenAiCompatible(ep[0], ep[1], ep[2], texts);
            } catch (Throwable t) {
                sHostAiBackoffUntil = System.currentTimeMillis() + HOST_AI_BACKOFF_MS;
                errs.append("；Pi 的 AI：").append(describe(t));
            }
        }
        throw new Exception(errs.toString());
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

        JSONArray inputArr = new JSONArray();
        for (String t : texts) {
            inputArr.put(t);
        }

        JSONObject requestJson = new JSONObject();
        requestJson.put("model", model);
        requestJson.put("temperature", 0.1);

        JSONArray messages = new JSONArray();

        JSONObject sysMsg = new JSONObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", "You are an accurate web page translation engine. Translate each string in the input JSON array into Simplified Chinese (zh-CN).\n" +
                "Requirements:\n" +
                "1. Maintain exact array length and element order.\n" +
                "2. Preserve code tokens, URLs, numbers, HTML placeholders, and formatting symbols.\n" +
                "3. Output ONLY a valid JSON array of strings without markdown code blocks (no ```json) and without any extra text.");
        messages.put(sysMsg);

        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", inputArr.toString());
        messages.put(userMsg);

        requestJson.put("messages", messages);

        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(18000);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        if (!TextUtils.isEmpty(apiKey)) {
            conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
        }
        conn.setDoOutput(true);

        byte[] bodyBytes = requestJson.toString().getBytes(StandardCharsets.UTF_8);
        OutputStream os = conn.getOutputStream();
        os.write(bodyBytes);
        os.flush();
        os.close();

        int code = conn.getResponseCode();
        if (code != 200) {
            String errBody = "";
            try {
                errBody = readStream(conn.getErrorStream());
            } catch (Throwable ignored) {}
            throw new Exception("HTTP " + code + ": " + (TextUtils.isEmpty(errBody) ? conn.getResponseMessage() : errBody));
        }

        String respStr = readStream(conn.getInputStream());
        conn.disconnect();

        JSONObject respJson = new JSONObject(respStr);
        JSONArray choices = respJson.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new Exception("No choices in model response");
        }

        JSONObject firstChoice = choices.getJSONObject(0);
        JSONObject msgObj = firstChoice.optJSONObject("message");
        if (msgObj == null || !msgObj.has("content")) {
            throw new Exception("No content in model message");
        }

        String content = msgObj.getString("content").trim();
        // 清理 markdown 代码块包裹 (```json ... ```)
        if (content.startsWith("```")) {
            int firstNewline = content.indexOf('\n');
            if (firstNewline != -1) {
                content = content.substring(firstNewline + 1);
            }
            if (content.endsWith("```")) {
                content = content.substring(0, content.length() - 3);
            }
            content = content.trim();
        }

        // 提取 JSON Array
        int startBracket = content.indexOf('[');
        int endBracket = content.lastIndexOf(']');
        if (startBracket == -1 || endBracket == -1 || endBracket <= startBracket) {
            throw new Exception("Model did not return a valid JSON array: " + content);
        }

        String jsonArraySub = content.substring(startBracket, endBracket + 1);
        JSONArray outArr = new JSONArray(jsonArraySub);

        List<String> results = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            if (i < outArr.length()) {
                results.add(outArr.optString(i, texts.get(i)));
            } else {
                results.add(texts.get(i));
            }
        }
        return results;
    }

    /**
     * 测试翻译引擎连通性与延迟
     */
    public static void testTranslation(Context context, String engine, String baseUrl, String apiKey, String model, TestCallback callback) {
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
                    String[] ep = hostAiEndpoint(context);
                    if (ep == null) throw new Exception(TextUtils.isEmpty(PiMetConfig.getAiApiKey(context))
                            ? "Pi 还没有配置 AI Key" : "Pi 当前模型不是 OpenAI 兼容接口（如 Claude），无法用于网页翻译");
                    res = translateWithOpenAiCompatible(ep[0], ep[1], ep[2], singleList);
                } else {
                    res = executeTranslation(context, engine, singleList);
                }

                long latency = System.currentTimeMillis() - start;
                String translated = (res != null && !res.isEmpty()) ? res.get(0) : "未得到有效译文";

                MAIN_HANDLER.post(() -> {
                    if (callback != null) {
                        callback.onResult(true, latency, sample, translated, "连接测试成功");
                    }
                });
            } catch (Throwable t) {
                long latency = System.currentTimeMillis() - start;
                final String err = t.getMessage() != null ? t.getMessage() : t.toString();
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

        return "(function() {\n" +
                "  var W = window;\n" +
                "  try {\n" +
                "    if (!document.body) return 'nobody';\n" +
                "    var B = W.PiMetTranslator;\n" +
                "    if (!B || typeof B.requestBatch !== 'function') return 'nobridge';\n" +
                "    if (W.__pimet_is_translating && W.__pimet_is_translated) return 'busy';\n" +
                "    if (W.__pimet_restore && W.__pimet_all_nodes && W.__pimet_all_nodes.length) { try { W.__pimet_restore(true); } catch (e0) {} }\n" +
                "    W.__pimet_is_translating = true;\n" +
                "    W.__pimet_is_translated = true;\n" +
                "    W.__pimet_mode = '" + mode + "';\n" +
                "    W.__pimet_live = " + live + ";\n" +
                "    W.__pimet_batches = {};\n" +
                "    W.__pimet_all_nodes = [];\n" +
                "    W.__pimet_seq = W.__pimet_seq || 0;\n" +
                "    W.__pimet_total = 0;\n" +
                "    W.__pimet_done = 0;\n" +
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
                "          if (node.__pimet_orig !== undefined || node.__pimet_done) return NodeFilter.FILTER_REJECT;\n" +
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
                "    var BATCH_SIZE = 15;\n" +
                "    function send(nodes) {\n" +
                "      for (var i = 0; i < nodes.length; i += BATCH_SIZE) {\n" +
                "        var chunk = nodes.slice(i, i + BATCH_SIZE);\n" +
                "        var batchId = 'pbatch_' + (++W.__pimet_seq);\n" +
                "        var texts = [];\n" +
                "        for (var j = 0; j < chunk.length; j++) {\n" +
                "          chunk[j].__pimet_orig = chunk[j].nodeValue;\n" +
                "          texts.push(chunk[j].nodeValue.trim());\n" +
                "          W.__pimet_all_nodes.push(chunk[j]);\n" +
                "        }\n" +
                "        W.__pimet_batches[batchId] = chunk;\n" +
                "        W.PiMetTranslator.requestBatch(batchId, JSON.stringify(texts));\n" +
                "      }\n" +
                "    }\n" +
                "    function progress() {\n" +
                "      if (W.__pimet_done >= W.__pimet_total) {\n" +
                "        W.__pimet_is_translating = false;\n" +
                "        if (W.__pimet_watchdog) { clearTimeout(W.__pimet_watchdog); W.__pimet_watchdog = null; }\n" +
                "        if (W.__pimet_failed >= W.__pimet_total && W.__pimet_total > 0) {\n" +
                "          capsule('翻译失败：' + (W.__pimet_last_error || '翻译服务无响应'), true);\n" +
                "          W.__pimet_is_translated = false;\n" +
                "          try { W.PiMetTranslator.onStateChanged(false, 0, W.__pimet_total); } catch (e1) {}\n" +
                "        } else {\n" +
                "          capsule('翻译完成 (' + (W.__pimet_done - W.__pimet_failed) + '/' + W.__pimet_total + ')' + (W.__pimet_failed ? ' · ' + W.__pimet_failed + ' 条失败' : ''), true);\n" +
                "          try { W.PiMetTranslator.onStateChanged(true, W.__pimet_done, W.__pimet_total); } catch (e2) {}\n" +
                "        }\n" +
                "        if (W.__pimet_live && W.__pimet_observer && W.__pimet_is_translated) W.__pimet_observer.observe(document.body, {childList: true, subtree: true});\n" +
                "      } else {\n" +
                "        capsule('翻译中 (' + W.__pimet_done + '/' + W.__pimet_total + ')', false);\n" +
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
                "    capsule('翻译中 (0/' + W.__pimet_total + ')', false);\n" +
                "    send(candidates);\n" +
                "\n" +
                "    if (W.__pimet_watchdog) clearTimeout(W.__pimet_watchdog);\n" +
                "    W.__pimet_watchdog = setTimeout(function() {\n" +
                "      W.__pimet_watchdog = null;\n" +
                "      if (W.__pimet_is_translating) {\n" +
                "        W.__pimet_is_translating = false;\n" +
                "        capsule('翻译超时 (' + (W.__pimet_done - W.__pimet_failed) + '/' + W.__pimet_total + ')，剩余部分仍在后台翻译', true);\n" +
                "        try { W.PiMetTranslator.showToast('网页翻译超时：翻译服务响应太慢，可长按「译」换成极速通道'); } catch (e5) {}\n" +
                "      }\n" +
                "    }, 60000);\n" +
                "\n" +
                "    if (W.__pimet_live && !W.__pimet_observer && W.MutationObserver) {\n" +
                "      var timer = null, liveRuns = 0;\n" +
                "      W.__pimet_observer = new MutationObserver(function() {\n" +
                "        if (W.__pimet_is_translating || !W.__pimet_is_translated || liveRuns >= 3) return;\n" +
                "        clearTimeout(timer);\n" +
                "        timer = setTimeout(function() {\n" +
                "          if (W.__pimet_is_translating || !W.__pimet_is_translated || liveRuns >= 3) return;\n" +
                "          var more = collectTextNodes(document.body);\n" +
                "          if (more.length > 0) {\n" +
                "            liveRuns++;\n" +
                "            W.__pimet_is_translating = true;\n" +
                "            W.__pimet_total += more.length;\n" +
                "            send(more);\n" +
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
