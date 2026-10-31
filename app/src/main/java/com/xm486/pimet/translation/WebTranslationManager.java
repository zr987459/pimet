package com.xm486.pimet.translation;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.WebView;

import com.xm486.pimet.PiMetConfig;

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
 * 网页翻译 (WebTranslationManager)
 * - 有序引擎链（免费通道 / Pi 的模型 / 自定义 API，见 TranslationEngines），带熔断自动切换
 * - 免费通道四条线：Google GTX → Google Dict → MyMemory → Bing
 * - 译文缓存：内存 LRU + 磁盘 SQLite（TranslationCache，与引擎无关）
 * - 注入脚本见 TranslationScript；站点规则见 TranslationSites
 */
public class WebTranslationManager {

    private static final String TAG = "WebTranslationManager";
    /** 免费通道在前时用的线程池（最多 4 批并发） */
    public static final ExecutorService THREAD_POOL = Executors.newFixedThreadPool(4);
    /** AI 引擎在前时用的线程池（最多 6 批并发） */
    private static final ExecutorService AI_POOL = Executors.newFixedThreadPool(6);
    static final int AI_BATCH_MAX_ITEMS = 40;
    static final int AI_BATCH_MAX_CHARS = 2500;
    private static final int AI_READ_TIMEOUT_MS = 45000;
    private static final int PI_MODEL_READ_TIMEOUT_MS = AI_READ_TIMEOUT_MS;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    public interface TranslationCallback {
        void onSuccess(List<String> translatedTexts);

        void onError(String error);
    }

    /**
     * 批量翻译入口：先查缓存（内存 + 磁盘），只把没命中的交给引擎链
     */
    public static void translateBatch(Context context, List<String> texts, TranslationCallback callback) {
        if (texts == null || texts.isEmpty()) {
            if (callback != null) callback.onSuccess(new ArrayList<>());
            return;
        }
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        final TranslationEngines.Config cfg = TranslationEngines.current(app);
        ExecutorService pool = TranslationEngines.FREE.equals(cfg.first()) ? THREAD_POOL : AI_POOL;
        pool.execute(() -> {
            List<String> results = new ArrayList<>(texts);
            List<Integer> missIdx = new ArrayList<>();
            List<String> missTexts = new ArrayList<>();
            java.util.Map<Integer, String> hits = TranslationCache.lookup(app, texts);
            for (int i = 0; i < texts.size(); i++) {
                String hit = hits.get(i);
                if (hit != null) {
                    results.set(i, hit);
                } else {
                    missIdx.add(i);
                    missTexts.add(texts.get(i));
                }
            }
            if (missTexts.isEmpty()) {
                if (callback != null) MAIN_HANDLER.post(() -> callback.onSuccess(results));
                return;
            }
            try {
                List<String> got = TranslationEngines.translate(app, cfg, missTexts);
                for (int k = 0; k < missIdx.size(); k++) {
                    String tr = got != null && k < got.size() && got.get(k) != null ? got.get(k) : missTexts.get(k);
                    results.set(missIdx.get(k), tr);
                }
                TranslationCache.put(app, missTexts, got);
                if (callback != null) MAIN_HANDLER.post(() -> callback.onSuccess(results));
            } catch (Throwable t) {
                Log.w(TAG, "Translation chain failed: " + t.getMessage());
                final String errMsg = describe(t, 400);
                if (callback != null) MAIN_HANDLER.post(() -> callback.onError(errMsg));
            }
        });
    }


    /** 异常 → 一句给用户看的原因 */
    static String describe(Throwable t) {
        return describe(t, 160);
    }

    static String describe(Throwable t, int max) {
        if (t == null) return "未知错误";
        if (t instanceof java.net.UnknownHostException) return "无法解析域名（" + t.getMessage() + "），请检查网络";
        if (t instanceof java.net.SocketTimeoutException) return "连接超时";
        if (t instanceof java.net.ConnectException) return "无法连接翻译服务";
        if (t instanceof javax.net.ssl.SSLException) return "TLS 握手失败";
        String m = t.getMessage();
        if (TextUtils.isEmpty(m)) m = t.getClass().getSimpleName();
        return m.length() > max ? m.substring(0, max) + "…" : m;
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
        String[] lines = {"free.gtx", "free.dict", "free.mymemory", "free.bing"};
        String[] names = {"Google", "Google Dict", "MyMemory", "Bing"};
        StringBuilder errs = new StringBuilder();
        boolean triedAny = false;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < lines.length; i++) {
                boolean cooling = TranslationEngines.isCooling(lines[i]);
                // 第一轮跳过冷却中的线路；四条都在冷却时第二轮硬试一次
                if (pass == 0 && cooling) continue;
                if (pass == 1 && !cooling) continue;
                triedAny = true;
                try {
                    List<String> r = runFreeLine(i, texts);
                    TranslationEngines.recordSuccess(lines[i]);
                    return r;
                } catch (Throwable t) {
                    TranslationEngines.recordFailure(lines[i], t);
                    if (errs.length() > 0) errs.append(" / ");
                    errs.append(names[i]).append("：").append(describe(t, 80));
                    Log.w(TAG, names[i] + " translation channel error: " + t.getMessage());
                }
            }
            if (triedAny) break;
        }
        throw new Exception("免费通道均不可用（" + errs + "）");
    }

    private static List<String> runFreeLine(int i, List<String> texts) throws Exception {
        switch (i) {
            case 0:
                return googleInChunks(texts, false);
            case 1:
                return googleInChunks(texts, true);
            case 2:
                return translateWithMyMemory(texts);
            default:
                return translateWithBing(texts);
        }
    }

    // ---- Bing 免费网页接口（www.bing.com/translator 页面里的 IG / token，约 1 小时有效）

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static String sBingIg, sBingIid, sBingKey, sBingToken, sBingCookie;
    private static long sBingExpireAt;

    private static synchronized void bingAuth(boolean force) throws Exception {
        if (!force && sBingToken != null && System.currentTimeMillis() < sBingExpireAt) return;
        HttpURLConnection conn = (HttpURLConnection) new URL("https://www.bing.com/translator").openConnection();
        try {
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", UA);
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            StringBuilder ck = new StringBuilder();
            java.util.List<String> sc = conn.getHeaderFields().get("Set-Cookie");
            if (sc != null) {
                for (String c : sc) {
                    int semi = c.indexOf(';');
                    if (ck.length() > 0) ck.append("; ");
                    ck.append(semi > 0 ? c.substring(0, semi) : c);
                }
            }
            String html = readStream(conn.getInputStream());
            java.util.regex.Matcher ig = java.util.regex.Pattern.compile("IG:\"([A-Fa-f0-9]+)\"").matcher(html);
            java.util.regex.Matcher iid = java.util.regex.Pattern.compile("data-iid=\"([^\"]+)\"").matcher(html);
            java.util.regex.Matcher ap = java.util.regex.Pattern.compile("params_AbusePreventionHelper\\s*=\\s*\\[(\\d+),\"([^\"]+)\",(\\d+)").matcher(html);
            if (!ig.find() || !ap.find()) throw new Exception("Bing 页面结构变了，拿不到 token");
            sBingIg = ig.group(1);
            sBingIid = iid.find() ? iid.group(1) : "translator.5023";
            sBingKey = ap.group(1);
            sBingToken = ap.group(2);
            long ttl = 3600000L;
            try {
                ttl = Long.parseLong(ap.group(3));
            } catch (Throwable ignored) {}
            sBingExpireAt = System.currentTimeMillis() + Math.max(60000L, ttl - 5 * 60000L);
            sBingCookie = ck.toString();
        } finally {
            conn.disconnect();
        }
    }

    private static List<String> translateWithBing(List<String> texts) throws Exception {
        final int maxChars = 900;
        List<String> out = new ArrayList<>();
        List<String> part = new ArrayList<>();
        int len = 0;
        for (String s0 : texts) {
            String s = s0 == null ? "" : s0;
            if (!part.isEmpty() && len + s.length() > maxChars) {
                out.addAll(bingOnce(part, false));
                part = new ArrayList<>();
                len = 0;
            }
            part.add(s);
            len += s.length() + 1;
        }
        if (!part.isEmpty()) out.addAll(bingOnce(part, false));
        return out;
    }

    private static List<String> bingOnce(List<String> texts, boolean retried) throws Exception {
        bingAuth(retried);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < texts.size(); i++) {
            if (i > 0) sb.append("\n");
            sb.append(texts.get(i).replace("\r", " ").replace("\n", " "));
        }
        String body = "fromLang=auto-detect&to=zh-Hans&text=" + URLEncoder.encode(sb.toString(), "UTF-8")
                + "&token=" + URLEncoder.encode(sBingToken, "UTF-8") + "&key=" + URLEncoder.encode(sBingKey, "UTF-8");
        HttpURLConnection conn = (HttpURLConnection) new URL("https://www.bing.com/ttranslatev3?isVertical=1&IG=" + sBingIg + "&IID=" + sBingIid + ".1").openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Referer", "https://www.bing.com/translator");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            if (!TextUtils.isEmpty(sBingCookie)) conn.setRequestProperty("Cookie", sBingCookie);
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes(StandardCharsets.UTF_8));
            os.close();
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            String resp = readStream(conn.getInputStream()).trim();
            if (!resp.startsWith("[")) {
                // token 过期时返回 {"statusCode":205} 之类：刷新一次 token 再试
                if (!retried) return bingOnce(texts, true);
                throw new Exception("Bing 返回异常：" + (resp.length() > 60 ? resp.substring(0, 60) : resp));
            }
            JSONArray arr = new JSONArray(resp);
            JSONArray trs = arr.getJSONObject(0).getJSONArray("translations");
            String all = trs.getJSONObject(0).optString("text", "");
            String[] lines = all.split("\n", -1);
            List<String> result = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                result.add(i < lines.length && !TextUtils.isEmpty(lines[i].trim()) ? lines[i] : texts.get(i));
            }
            return result;
        } finally {
            conn.disconnect();
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

    /** 生成注入页面的翻译脚本（手动触发） */
    public static String buildInjectionScript(Context context) {
        return TranslationScript.build(context, false);
    }

    /** 用户在某个 WebView 上手动开启翻译的站点：同一站点内跳转新页面时自动续翻（像浏览器的“翻译此网站”会话） */
    private static final java.util.Map<WebView, String> SESSION = new java.util.WeakHashMap<>();

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
                    SESSION.remove(webView);
                    toast(context, "已还原网页原文");
                } else {
                    String host = TranslationSites.hostOf(url);
                    if (host != null) SESSION.put(webView, host);
                    inject(webView, context, true, false);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "toggleTranslation failed", t);
            toast(context, "网页翻译失败：" + describe(t));
        }
    }

    /**
     * 页面加载完成时调用（浏览器与工作台的 WebViewClient.onPageFinished）：
     * 站点规则为「总是翻译」，或本 WebView 刚在同一站点手动开过翻译，就自动翻译；「从不翻译」的站点不翻。主线程调用。
     */
    public static void maybeAutoTranslate(WebView webView, Context context, String url) {
        if (webView == null || context == null) return;
        try {
            String host = TranslationSites.hostOf(url);
            if (host == null) return;
            int rule = TranslationSites.ruleFor(context, host);
            if (rule == TranslationSites.RULE_NEVER) {
                SESSION.remove(webView);
                return;
            }
            boolean session = host.equals(SESSION.get(webView));
            if (rule != TranslationSites.RULE_ALWAYS && !session) return;
            if (!webView.getSettings().getJavaScriptEnabled()) return;
            // onPageFinished 可能对同一页触发多次：脚本自己会先还原再重来，这里稍等页面渲染
            MAIN_HANDLER.postDelayed(() -> {
                String now = webView.getUrl();
                if (now == null || !host.equals(TranslationSites.hostOf(now))) return;
                webView.evaluateJavascript("(function(){return window.__pimet_is_translated===true;})()", v -> {
                    if (!"true".equals(v)) inject(webView, context, false, true);
                });
            }, 400);
        } catch (Throwable t) {
            Log.w(TAG, "maybeAutoTranslate failed: " + t.getMessage());
        }
    }

    /** 注入翻译脚本，按脚本返回的状态给出提示（auto=自动翻译时不打扰） */
    private static void inject(WebView webView, Context context, boolean announce, boolean auto) {
        String script = TranslationScript.build(context, auto);
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
            } else if ("zh".equals(s)) {
                if (!auto) toast(context, "本页已是中文");
            } else if ("none".equals(s)) {
                // 脚本已挂上增量监听，新内容出现会自动翻译
            } else if ("busy".equals(s)) {
                if (!auto) toast(context, "上一次翻译还在进行中");
            } else if (auto) {
                Log.w(TAG, "auto translate skipped: " + s);
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
        inject(webView, context, false, false);
    }
}
