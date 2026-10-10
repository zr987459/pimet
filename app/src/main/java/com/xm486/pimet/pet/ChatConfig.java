package com.xm486.pimet.pet;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

/**
 * AI 对话配置：单一数据源（SharedPreferences）。
 *
 * 设置页和输入框 # 指令都走这里，不再有两份配置打架的问题。
 * 两种模式：
 *   - operit     本地模式：Operit external-chat 接口
 *   - custom_api OpenAI 兼容接口（/v1/chat/completions）
 *
 * 兼容：v1.0.6 的配置存在 &lt;外部目录&gt;/files/config.json，
 * 首次加载时自动迁移一次（老用户升级无感）。
 */
public class ChatConfig {

    public static final String MODE_OPERIT = "operit";
    public static final String MODE_CUSTOM_API = "custom_api";
    public static final String MODE_PIWEB = "piweb";
    public static final String MODE_CLAWBENCH = "clawbench";

    public static final String KEY_MODE = "chat_mode";
    public static final String KEY_OPERIT_URL = "chat_operit_url";
    public static final String KEY_OPERIT_BASE = "chat_operit_base";
    public static final String KEY_OPERIT_TOKEN = "chat_operit_token";
    public static final String KEY_OPERIT_GROUP = "chat_operit_group";
    public static final String KEY_API_URL = "chat_api_url";
    public static final String KEY_API_KEY = "chat_api_key";
    public static final String KEY_API_MODEL = "chat_api_model";
    public static final String KEY_API_PROMPT = "chat_api_prompt";
    public static final String KEY_PIWEB_PROMPT = "chat_piweb_prompt";
    public static final String KEY_PIWEB_SESSION_ID = "chat_piweb_session_id";
    public static final String KEY_CLAWBENCH_TOKEN = "chat_clawbench_token";
    public static final String KEY_API_PROVIDER = "chat_api_provider";
    public static final String KEY_OPERIT_TEMPLATE = "chat_operit_template";
    public static final String KEY_OPERIT_CHAT_ID = "chat_operit_chat_id";
    public static final String KEY_OPERIT_CHAT_OWNED = "chat_operit_chat_owned";
    public static final String KEY_TEMPERATURE = "chat_temperature";
    public static final String KEY_MAX_TOKENS = "chat_max_tokens";
    public static final String KEY_CONTEXT_ROUNDS = "chat_context_rounds";
    public static final String KEY_TIMEOUT = "chat_timeout_seconds";
    public static final String KEY_TARGET_SUB_AGENT = "chat_target_sub_agent";

    private static final String LEGACY_DIR =
            "/storage/emulated/0/Android/data/com.xm486.pimet/files";
    private static final String LEGACY_FILE = "config.json";

    public String mode = MODE_PIWEB;

    public String operitUrl = "http://127.0.0.1:8094/api/external-chat";
    /** Operit Web API 基址（专属对话 POST /api/web/chats 走这里） */
    public String operitBase = "http://127.0.0.1:8094";
    public String operitToken = "465ea3984db74e0790e8df63c6e85643";
    public String operitGroup = "";
    public String operitTemplate = "";
    /** 专属会话 ID：本地对话默认走自己的独立会话，不碰 App 里当前激活的对话 */
    public String operitChatId = "";
    /** 专属对话所有权标记：仅当经 Web API 成功创建后置 true（清掉旧版误存的主对话 id） */
    public boolean operitChatOwned = false;

    public String apiUrl = "https://uuapi.io/v1/chat/completions";
    public String apiKey = "sk-1207d5c5004cb8c4a00ce76af749dfe5cca00a296fad3ceb4fefc5b2997a259f";
    public String apiModel = "gemini-3.1-flash-lite";
    public String apiPrompt = "你是一个活泼傲娇的桌面宠物，说话简短软莙，单次回答控制在50字以内。";
    public String piWebPrompt = "";
    /** 桌宠绑定的子代理名称，默认 pet-companion（为空表示关闭子代理自动路由） */
    public String targetSubAgent = "pet-companion";
    /** Pi-Web 专属桌宠会话 ID，绝不串入或复用用户的当前编码会话 */
    public String piwebSessionId = "";
    public String clawbenchToken = "";
    public String apiProvider = "custom";
    public float temperature = 0.7f;
    public int maxTokens = 150;
    public int contextRounds = 4;
    public int timeoutSeconds = 30;

    /** 当前模式显示名（状态卡 / 设置页徽章用） */
    public String modeLabel() {
        if (MODE_OPERIT.equals(mode)) return "Operit";
        if (MODE_PIWEB.equals(mode)) return "pi-web";
        if (MODE_CLAWBENCH.equals(mode)) return "ClawBench";
        return "自定义 API";
    }

    /**
     * Operit Web API 基址（专属对话 POST /api/web/chats 用）：
     * 优先用显式配置的 operitBase；否则从 operitUrl 推导（剥掉 /api/external-chat）。
     */
    public String operitWebBase() {
        if (operitBase != null && !operitBase.trim().isEmpty())
            return operitBase.trim();
        String u = (operitUrl == null ? "" : operitUrl).trim();
        int idx = u.indexOf("/api/external-chat");
        if (idx > 0) return u.substring(0, idx);
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    /** 从 SharedPreferences 载入；首次使用时自动迁移旧 config.json */
    public static ChatConfig load(Context context) {
        SharedPreferences sp = PetRegistry.getPrefs(context);
        ChatConfig config = new ChatConfig();
        if (!sp.contains(KEY_MODE)) {
            // 老版本没有这些键 → 尝试迁移 config.json
            if (migrateFromLegacy(context, sp)) {
                // 迁移成功，继续从 sp 读
            } else {
                // 全新安装：把内置默认值写进去
                config.save(context);
                return config;
            }
        }
        config.mode = sp.getString(KEY_MODE, MODE_PIWEB);
        // Operit 服务端口由主页「Operit 端口」框（KEY_OPERIT_PORT）统一定，聊天链路跟随它，
        // 不再读 sp 里写死的旧地址（保证改端口后立即生效）
        int operitPort = PetRegistry.getOperitPort(context);
        config.operitUrl = "http://127.0.0.1:" + operitPort + "/api/external-chat";
        config.operitBase = "http://127.0.0.1:" + operitPort;
        config.operitToken = sp.getString(KEY_OPERIT_TOKEN, config.operitToken);
        config.operitGroup = sp.getString(KEY_OPERIT_GROUP, "");
        config.operitTemplate = sp.getString(KEY_OPERIT_TEMPLATE, "");
        config.operitChatId = sp.getString(KEY_OPERIT_CHAT_ID, "");
        config.operitChatOwned = sp.getBoolean(KEY_OPERIT_CHAT_OWNED, false);
        config.apiUrl = sp.getString(KEY_API_URL, config.apiUrl);
        config.apiKey = sp.getString(KEY_API_KEY, config.apiKey);
        config.apiModel = sp.getString(KEY_API_MODEL, config.apiModel);
        config.apiPrompt = sp.getString(KEY_API_PROMPT, config.apiPrompt);
        config.piWebPrompt = sp.getString(KEY_PIWEB_PROMPT, "");
        config.targetSubAgent = sp.getString(KEY_TARGET_SUB_AGENT, "pet-companion");
        config.piwebSessionId = sp.getString(KEY_PIWEB_SESSION_ID, "");
        config.clawbenchToken = sp.getString(KEY_CLAWBENCH_TOKEN, PetRegistry.getClawbenchToken(context));
        config.apiProvider = sp.getString(KEY_API_PROVIDER, "custom");
        return config;
    }

    /** 整体写回 */
    public void save(Context context) {
        SharedPreferences.Editor e = PetRegistry.getPrefs(context).edit();
        e.putString(KEY_MODE, mode);
        e.putString(KEY_OPERIT_URL, operitUrl);
        e.putString(KEY_OPERIT_BASE, operitBase);
        e.putString(KEY_OPERIT_TOKEN, operitToken);
        e.putString(KEY_OPERIT_GROUP, operitGroup);
        e.putString(KEY_OPERIT_TEMPLATE, operitTemplate);
        e.putString(KEY_OPERIT_CHAT_ID, operitChatId);
        e.putBoolean(KEY_OPERIT_CHAT_OWNED, operitChatOwned);
        e.putString(KEY_API_URL, apiUrl);
        e.putString(KEY_API_KEY, apiKey);
        e.putString(KEY_API_MODEL, apiModel);
        e.putString(KEY_API_PROMPT, apiPrompt);
        e.putString(KEY_PIWEB_PROMPT, piWebPrompt);
        e.putString(KEY_TARGET_SUB_AGENT, targetSubAgent);
        e.putString(KEY_PIWEB_SESSION_ID, piwebSessionId);
        e.putString(KEY_CLAWBENCH_TOKEN, clawbenchToken);
        e.putString(KEY_API_PROVIDER, apiProvider);
        e.putFloat(KEY_TEMPERATURE, temperature);
        e.putInt(KEY_MAX_TOKENS, maxTokens);
        e.putInt(KEY_CONTEXT_ROUNDS, contextRounds);
        e.putInt(KEY_TIMEOUT, timeoutSeconds);
        e.apply();
        if (clawbenchToken != null && !clawbenchToken.isEmpty()) {
            PetRegistry.setClawbenchToken(context, clawbenchToken);
        }
    }

    /** 清除所有会话历史与专属会话 ID */
    public void clearHistory(Context context) {
        operitChatId = "";
        operitChatOwned = false;
        piwebSessionId = "";
        SharedPreferences.Editor e = PetRegistry.getPrefs(context).edit();
        e.remove(KEY_OPERIT_CHAT_ID);
        e.remove(KEY_OPERIT_CHAT_OWNED);
        e.remove(KEY_PIWEB_SESSION_ID);
        e.apply();
    }

    /** 迁移旧版 config.json → SharedPreferences；成功返回 true */
    private static boolean migrateFromLegacy(Context context, SharedPreferences sp) {
        File file = new File(LEGACY_DIR, LEGACY_FILE);
        if (!file.exists()) return false;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            JSONObject root = new JSONObject(sb.toString());

            SharedPreferences.Editor e = sp.edit();
            String mode = root.optString("current_mode", MODE_CUSTOM_API);
            e.putString(KEY_MODE, mode);

            JSONObject operit = root.optJSONObject(MODE_OPERIT);
            if (operit != null) {
                e.putString(KEY_OPERIT_URL, operit.optString("url",
                        "http://127.0.0.1:8094/api/external-chat"));
                e.putString(KEY_OPERIT_TOKEN, operit.optString("token", ""));
                e.putString(KEY_OPERIT_GROUP, operit.optString("group", ""));
            }
            JSONObject api = root.optJSONObject(MODE_CUSTOM_API);
            if (api != null) {
                e.putString(KEY_API_URL, api.optString("url",
                        "https://uuapi.io/v1/chat/completions"));
                e.putString(KEY_API_KEY, api.optString("api_key", ""));
                e.putString(KEY_API_MODEL, api.optString("model", "gemini-3.1-flash-lite"));
                e.putString(KEY_API_PROMPT, api.optString("system_prompt",
                        "你是一个活泼傲娇的桌面宠物，说话简短软莙，单次回答控制在50字以内。"));
            }
            e.apply();
            return true;
        } catch (Throwable t) {
            android.util.Log.w("DevPetM.ChatConfig", "migrate failed", t);
            return false;
        }
    }
}
