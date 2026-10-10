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

    /**
     * 批量翻译核心分发入口
     */
    public static void translateBatch(Context context, List<String> texts, TranslationCallback callback) {
        if (texts == null || texts.isEmpty()) {
            if (callback != null) callback.onSuccess(new ArrayList<>());
            return;
        }

        THREAD_POOL.execute(() -> {
            String engine = PiMetConfig.getTransEngine(context);
            try {
                List<String> results = executeTranslation(context, engine, texts);
                if (callback != null) {
                    MAIN_HANDLER.post(() -> callback.onSuccess(results));
                }
            } catch (Throwable t) {
                Log.w(TAG, "Translation error with engine: " + engine + ", message: " + t.getMessage());
                // 智能容灾：若非 free 模式且发生错误，尝试免配置快速通道兜底
                if (!PiMetConfig.TRANS_ENGINE_FREE.equals(engine)) {
                    try {
                        Log.i(TAG, "Falling back to free translation channel...");
                        List<String> fallbackResults = translateWithFreeChannel(texts);
                        if (callback != null) {
                            MAIN_HANDLER.post(() -> callback.onSuccess(fallbackResults));
                        }
                        return;
                    } catch (Throwable t2) {
                        Log.e(TAG, "Fallback free channel also failed", t2);
                    }
                }

                if (callback != null) {
                    final String errMsg = t.getMessage() != null ? t.getMessage() : "翻译服务无响应";
                    MAIN_HANDLER.post(() -> callback.onError(errMsg));
                }
            }
        });
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
            return translateWithOpenAiCompatible(baseUrl, apiKey, model, texts);
        } else if (PiMetConfig.TRANS_ENGINE_AI.equals(engine)) {
            String baseUrl = PiMetConfig.getAiBaseUrl(context);
            String apiKey = PiMetConfig.getAiApiKey(context);
            String model = PiMetConfig.getAiModel(context);
            if (TextUtils.isEmpty(apiKey)) {
                // 未配置 AI Key，自动回退到极速通道
                return translateWithFreeChannel(texts);
            }
            return translateWithOpenAiCompatible(baseUrl, apiKey, model, texts);
        } else {
            // 智能混合模式 (AUTO)
            // 检查是否有可用 AI Key (优先使用自定义或宿主配置的 AI)
            String customKey = PiMetConfig.getTransCustomApiKey(context);
            String aiKey = PiMetConfig.getAiApiKey(context);
            if (!TextUtils.isEmpty(customKey)) {
                try {
                    String baseUrl = PiMetConfig.getTransCustomBaseUrl(context);
                    String model = PiMetConfig.getTransCustomModel(context);
                    return translateWithOpenAiCompatible(baseUrl, customKey, model, texts);
                } catch (Throwable t) {
                    Log.w(TAG, "Auto engine custom AI failed, falling back to free channel: " + t.getMessage());
                    return translateWithFreeChannel(texts);
                }
            } else if (!TextUtils.isEmpty(aiKey)) {
                try {
                    String baseUrl = PiMetConfig.getAiBaseUrl(context);
                    String model = PiMetConfig.getAiModel(context);
                    return translateWithOpenAiCompatible(baseUrl, aiKey, model, texts);
                } catch (Throwable t) {
                    Log.w(TAG, "Auto engine host AI failed, falling back to free channel: " + t.getMessage());
                    return translateWithFreeChannel(texts);
                }
            } else {
                // 无需配置，直接使用极速通道
                return translateWithFreeChannel(texts);
            }
        }
    }

    /**
     * 免配置极速多通道翻译 (包含 Google 官方 Chrome 扩展通道与 MyMemory 双重容灾)
     */
    public static List<String> translateWithFreeChannel(List<String> texts) throws Exception {
        try {
            return translateWithGoogleDictChrome(texts);
        } catch (Throwable t) {
            Log.w(TAG, "Google Chrome translation channel error, trying MyMemory: " + t.getMessage());
            return translateWithMyMemory(texts);
        }
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
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

        int code = conn.getResponseCode();
        if (code != 200) {
            throw new Exception("HTTP " + code + ": " + conn.getResponseMessage());
        }

        String jsonResp = readStream(conn.getInputStream());
        conn.disconnect();

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
        for (String text : texts) {
            if (TextUtils.isEmpty(text.trim())) {
                result.add(text);
                continue;
            }
            try {
                String encoded = URLEncoder.encode(text, "UTF-8");
                String urlStr = "https://api.mymemory.translated.net/get?q=" + encoded + "&langpair=auto|zh-CN";
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)");

                if (conn.getResponseCode() == 200) {
                    String resp = readStream(conn.getInputStream());
                    JSONObject json = new JSONObject(resp);
                    JSONObject respData = json.optJSONObject("responseData");
                    if (respData != null && respData.has("translatedText")) {
                        String trans = respData.getString("translatedText");
                        result.add(!TextUtils.isEmpty(trans) ? trans : text);
                    } else {
                        result.add(text);
                    }
                } else {
                    result.add(text);
                }
                conn.disconnect();
            } catch (Throwable ignored) {
                result.add(text);
            }
        }
        return result;
    }

    /**
     * OpenAI 兼容格式大模型批处理翻译 (支持 DeepSeek, OpenAI, Ollama, SiliconFlow 等)
     */
    public static List<String> translateWithOpenAiCompatible(String baseUrl, String apiKey, String model, List<String> texts) throws Exception {
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
                    String effBase = TextUtils.isEmpty(baseUrl) ? PiMetConfig.getAiBaseUrl(context) : baseUrl;
                    String effKey = TextUtils.isEmpty(apiKey) ? PiMetConfig.getAiApiKey(context) : apiKey;
                    String effModel = TextUtils.isEmpty(model) ? PiMetConfig.getAiModel(context) : model;
                    res = translateWithOpenAiCompatible(effBase, effKey, effModel, singleList);
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
                "  try {\n" +
                "    if (window.__pimet_is_translating) return;\n" +
                "    window.__pimet_is_translating = true;\n" +
                "    window.__pimet_is_translated = true;\n" +
                "    window.__pimet_mode = '" + mode + "';\n" +
                "    window.__pimet_live = " + live + ";\n" +
                "    window.__pimet_batches = window.__pimet_batches || {};\n" +
                "    window.__pimet_all_nodes = window.__pimet_all_nodes || [];\n" +
                "    window.__pimet_seq = window.__pimet_seq || 0;\n" +
                "    window.__pimet_total = 0;\n" +
                "    window.__pimet_done = 0;\n" +
                "\n" +
                "    // 创建浮动状态指示胶囊\n" +
                "    function createOrUpdateCapsule(text, isDone) {\n" +
                "      var cap = document.getElementById('pimet-trans-capsule');\n" +
                "      if (!cap) {\n" +
                "        cap = document.createElement('div');\n" +
                "        cap.id = 'pimet-trans-capsule';\n" +
                "        cap.style.cssText = 'position:fixed; top:10px; right:12px; z-index:2147483647; background:" + bgHex + "; color:" + accentHex + "; border:1px solid " + borderHex + "; border-radius:18px; padding:6px 12px; font-size:11.5px; font-family:system-ui,-apple-system,sans-serif; box-shadow:0 3px 12px rgba(0,0,0,0.3); display:flex; align-items:center; gap:6px; cursor:pointer; user-select:none; backdrop-filter:blur(8px); transition:all 0.25s ease;';\n" +
                "        cap.onclick = function() {\n" +
                "          if (window.PiMetTranslator && window.PiMetTranslator.openSettingsDialog) {\n" +
                "            window.PiMetTranslator.openSettingsDialog();\n" +
                "          }\n" +
                "        };\n" +
                "        document.body.appendChild(cap);\n" +
                "      }\n" +
                "      cap.innerHTML = '<span>🌐</span> <span>' + text + '</span>';\n" +
                "      if (isDone) {\n" +
                "        cap.style.borderColor = '" + accentHex + "';\n" +
                "        setTimeout(function() {\n" +
                "          if (cap && cap.parentNode) {\n" +
                "            cap.style.opacity = '0.75';\n" +
                "            cap.innerHTML = '<span>🌐</span> <span>译文就绪 · 点此设置</span>';\n" +
                "          }\n" +
                "        }, 3500);\n" +
                "      }\n" +
                "    }\n" +
                "\n" +
                "    createOrUpdateCapsule('正在扫描网页文本...', false);\n" +
                "\n" +
                "    // 忽略标签集合\n" +
                "    var IGNORE_TAGS = {'SCRIPT':1, 'STYLE':1, 'NOSCRIPT':1, 'CODE':1, 'PRE':1, 'SVG':1, 'CANVAS':1, 'IFRAME':1, 'TEXTAREA':1, 'INPUT':1, 'OPTION':1};\n" +
                "\n" +
                "    // 收集待翻译节点\n" +
                "    function collectTextNodes(root) {\n" +
                "      var nodes = [];\n" +
                "      var walker = document.createTreeWalker(root || document.body, NodeFilter.SHOW_TEXT, {\n" +
                "        acceptNode: function(node) {\n" +
                "          if (!node || !node.nodeValue) return NodeFilter.FILTER_REJECT;\n" +
                "          var val = node.nodeValue.trim();\n" +
                "          if (val.length < 2) return NodeFilter.FILTER_REJECT;\n" +
                "          var parent = node.parentElement;\n" +
                "          if (!parent) return NodeFilter.FILTER_REJECT;\n" +
                "          if (IGNORE_TAGS[parent.tagName.toUpperCase()]) return NodeFilter.FILTER_REJECT;\n" +
                "          if (parent.closest && (parent.closest('#pimet-trans-capsule') || parent.closest('.pimet-bilingual-trans'))) return NodeFilter.FILTER_REJECT;\n" +
                "          if (node.__pimet_orig !== undefined) return NodeFilter.FILTER_REJECT;\n" +
                "          // 检查是否包含英文/日文/韩文/外文字符\n" +
                "          if (!/[a-zA-Z\\u00C0-\\u024F\\u3040-\\u30FF\\uAC00-\\uD7AF]/.test(val)) return NodeFilter.FILTER_REJECT;\n" +
                "          return NodeFilter.FILTER_ACCEPT;\n" +
                "        }\n" +
                "      });\n" +
                "      while (walker.nextNode()) {\n" +
                "        nodes.push(walker.currentNode);\n" +
                "      }\n" +
                "      return nodes;\n" +
                "    }\n" +
                "\n" +
                "    var candidateNodes = collectTextNodes(document.body);\n" +
                "    window.__pimet_total = candidateNodes.length;\n" +
                "    window.__pimet_done = 0;\n" +
                "\n" +
                "    if (candidateNodes.length === 0) {\n" +
                "      createOrUpdateCapsule('页面无需翻译或已是中文', true);\n" +
                "      window.__pimet_is_translating = false;\n" +
                "      if (window.PiMetTranslator && window.PiMetTranslator.onStateChanged) {\n" +
                "        window.PiMetTranslator.onStateChanged(true, 0, 0);\n" +
                "      }\n" +
                "      return;\n" +
                "    }\n" +
                "\n" +
                "    // 分批批处理 (每批 15 个节点，保证秒级呈现)\n" +
                "    var BATCH_SIZE = 15;\n" +
                "    for (var i = 0; i < candidateNodes.length; i += BATCH_SIZE) {\n" +
                "      var chunk = candidateNodes.slice(i, i + BATCH_SIZE);\n" +
                "      var batchId = 'pbatch_' + (++window.__pimet_seq);\n" +
                "      var texts = [];\n" +
                "      for (var j = 0; j < chunk.length; j++) {\n" +
                "        var n = chunk[j];\n" +
                "        n.__pimet_orig = n.nodeValue;\n" +
                "        texts.push(n.nodeValue.trim());\n" +
                "        window.__pimet_all_nodes.push(n);\n" +
                "      }\n" +
                "      window.__pimet_batches[batchId] = chunk;\n" +
                "      if (window.PiMetTranslator && window.PiMetTranslator.requestBatch) {\n" +
                "        window.PiMetTranslator.requestBatch(batchId, JSON.stringify(texts));\n" +
                "      }\n" +
                "    }\n" +
                "\n" +
                "    createOrUpdateCapsule('翻译中 (0/' + window.__pimet_total + ')', false);\n" +
                "\n" +
                "    // 接收原生 Android 桥接返回结果\n" +
                "    window.__pimet_receive_batch = function(batchId, transJsonStr, isError) {\n" +
                "      var chunk = window.__pimet_batches[batchId];\n" +
                "      delete window.__pimet_batches[batchId];\n" +
                "      if (!chunk || chunk.length === 0) return;\n" +
                "\n" +
                "      var transArr = [];\n" +
                "      if (!isError && transJsonStr) {\n" +
                "        try { transArr = JSON.parse(transJsonStr); } catch(e) {}\n" +
                "      }\n" +
                "\n" +
                "      for (var k = 0; k < chunk.length; k++) {\n" +
                "        var node = chunk[k];\n" +
                "        var trans = (k < transArr.length) ? transArr[k] : '';\n" +
                "        if (trans && trans.trim()) {\n" +
                "          node.__pimet_trans = trans;\n" +
                "          if (window.__pimet_mode === 'bilingual') {\n" +
                "            var sub = document.createElement('span');\n" +
                "            sub.className = 'pimet-bilingual-trans';\n" +
                "            sub.style.cssText = 'display:inline-block; margin-left:4px; font-size:0.88em; color:" + accentHex + "; opacity:0.88; font-weight:normal; vertical-align:baseline;';\n" +
                "            sub.textContent = '【' + trans + '】';\n" +
                "            if (node.nextSibling) {\n" +
                "              node.parentNode.insertBefore(sub, node.nextSibling);\n" +
                "            } else {\n" +
                "              node.parentNode.appendChild(sub);\n" +
                "            }\n" +
                "            node.__pimet_sub = sub;\n" +
                "          } else {\n" +
                "            node.nodeValue = trans;\n" +
                "          }\n" +
                "        }\n" +
                "      }\n" +
                "\n" +
                "      window.__pimet_done += chunk.length;\n" +
                "      var isComplete = (window.__pimet_done >= window.__pimet_total);\n" +
                "      if (isComplete) {\n" +
                "        window.__pimet_is_translating = false;\n" +
                "        createOrUpdateCapsule('翻译完成 (' + window.__pimet_done + '/' + window.__pimet_total + ')', true);\n" +
                "        if (window.PiMetTranslator && window.PiMetTranslator.onStateChanged) {\n" +
                "          window.PiMetTranslator.onStateChanged(true, window.__pimet_done, window.__pimet_total);\n" +
                "        }\n" +
                "      } else {\n" +
                "        createOrUpdateCapsule('翻译中 (' + window.__pimet_done + '/' + window.__pimet_total + ')', false);\n" +
                "      }\n" +
                "    };\n" +
                "\n" +
                "    // 原文秒级一键还原接口\n" +
                "    window.__pimet_restore = function() {\n" +
                "      if (window.__pimet_observer) {\n" +
                "        window.__pimet_observer.disconnect();\n" +
                "        window.__pimet_observer = null;\n" +
                "      }\n" +
                "      var all = window.__pimet_all_nodes || [];\n" +
                "      for (var m = 0; m < all.length; m++) {\n" +
                "        var item = all[m];\n" +
                "        if (item.__pimet_orig !== undefined) {\n" +
                "          item.nodeValue = item.__pimet_orig;\n" +
                "          delete item.__pimet_orig;\n" +
                "        }\n" +
                "        if (item.__pimet_sub && item.__pimet_sub.parentNode) {\n" +
                "          item.__pimet_sub.parentNode.removeChild(item.__pimet_sub);\n" +
                "          delete item.__pimet_sub;\n" +
                "        }\n" +
                "      }\n" +
                "      window.__pimet_all_nodes = [];\n" +
                "      window.__pimet_is_translated = false;\n" +
                "      window.__pimet_is_translating = false;\n" +
                "      var cap = document.getElementById('pimet-trans-capsule');\n" +
                "      if (cap) cap.remove();\n" +
                "      if (window.PiMetTranslator && window.PiMetTranslator.onStateChanged) {\n" +
                "        window.PiMetTranslator.onStateChanged(false, 0, 0);\n" +
                "      }\n" +
                "    };\n" +
                "\n" +
                "    // 动态新增 DOM 监听 (Live Mutation Observer)\n" +
                "    if (window.__pimet_live && !window.__pimet_observer && window.MutationObserver) {\n" +
                "      var timer = null;\n" +
                "      window.__pimet_observer = new MutationObserver(function(mutations) {\n" +
                "        if (window.__pimet_is_translating || !window.__pimet_is_translated) return;\n" +
                "        clearTimeout(timer);\n" +
                "        timer = setTimeout(function() {\n" +
                "          var newNodes = collectTextNodes(document.body);\n" +
                "          if (newNodes.length > 0) {\n" +
                "            window.__pimet_total += newNodes.length;\n" +
                "            for (var a = 0; a < newNodes.length; a += BATCH_SIZE) {\n" +
                "              var cChunk = newNodes.slice(a, a + BATCH_SIZE);\n" +
                "              var cBatchId = 'pbatch_' + (++window.__pimet_seq);\n" +
                "              var cTexts = [];\n" +
                "              for (var b = 0; b < cChunk.length; b++) {\n" +
                "                var cn = cChunk[b];\n" +
                "                cn.__pimet_orig = cn.nodeValue;\n" +
                "                cTexts.push(cn.nodeValue.trim());\n" +
                "                window.__pimet_all_nodes.push(cn);\n" +
                "              }\n" +
                "              window.__pimet_batches[cBatchId] = cChunk;\n" +
                "              if (window.PiMetTranslator && window.PiMetTranslator.requestBatch) {\n" +
                "                window.PiMetTranslator.requestBatch(cBatchId, JSON.stringify(cTexts));\n" +
                "              }\n" +
                "            }\n" +
                "          }\n" +
                "        }, 450);\n" +
                "      });\n" +
                "      window.__pimet_observer.observe(document.body, { childList: true, subtree: true });\n" +
                "    }\n" +
                "  } catch(e) {\n" +
                "    console.error('PiMet Translator init error', e);\n" +
                "  }\n" +
                "})();";
    }

    /**
     * 触发对 WebView 注入翻译脚本或切换还原
     */
    public static void toggleTranslation(WebView webView, Context context) {
        if (webView == null || context == null) return;
        String checkJs = "(function() { return (window.__pimet_is_translated === true); })();";
        webView.evaluateJavascript(checkJs, value -> {
            boolean isTranslated = "true".equals(value);
            if (isTranslated) {
                // 已翻译 -> 还原
                webView.evaluateJavascript("if (window.__pimet_restore) window.__pimet_restore();", null);
            } else {
                // 未翻译 -> 注入并启动
                String script = buildInjectionScript(context);
                webView.evaluateJavascript(script, null);
            }
        });
    }

    /**
     * 重新注入并刷新翻译 (在修改设置或引擎后调用)
     */
    public static void refreshTranslation(WebView webView, Context context) {
        if (webView == null || context == null) return;
        String js = "if (window.__pimet_restore) window.__pimet_restore();\n" +
                buildInjectionScript(context);
        webView.evaluateJavascript(js, null);
    }
}
