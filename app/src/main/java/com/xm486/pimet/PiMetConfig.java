package com.xm486.pimet;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.xm486.pimet.proot.ProotManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class PiMetConfig {

    private static final String PREF_NAME = "pimet_settings";

    public static String getAppVersion(Context context) {
        if (context != null) {
            try {
                return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            } catch (Throwable ignored) {}
        }
        return "1.3.0";
    }

    public static final String KEY_AUTO_START = "auto_start_web";
    public static final String KEY_WEB_PORT = "web_port";
    public static final int DEFAULT_WEB_PORT = 30141;
    public static final String KEY_NPM_REGISTRY = "npm_registry";
    public static final String KEY_ROOTFS_MIRROR = "rootfs_mirror";
    public static final String KEY_KEEP_ALIVE = "keep_alive";
    public static final String KEY_WEB_ZOOM = "web_zoom";
    public static final String KEY_TERM_FONT_SIZE = "term_font_size";
    public static final String KEY_CUSTOM_SHORTCUTS = "custom_shortcuts";
    public static final String KEY_TOP_TOOLBAR_ORDER = "top_toolbar_order";
    public static final String KEY_KEYBOARD_PINNED = "keyboard_pinned";

    // AI 模型与 API Key 配置键
    public static final String KEY_AI_PROVIDER = "ai_provider";
    public static final String KEY_AI_API_KEY = "ai_api_key";
    public static final String KEY_AI_BASE_URL = "ai_base_url";
    public static final String KEY_AI_MODEL = "ai_model";

    // 网页翻译配置键
    public static final String KEY_TRANS_ENGINE = "trans_engine";
    public static final String KEY_TRANS_CUSTOM_BASE_URL = "trans_custom_base_url";
    public static final String KEY_TRANS_CUSTOM_API_KEY = "trans_custom_api_key";
    public static final String KEY_TRANS_CUSTOM_MODEL = "trans_custom_model";
    public static final String KEY_TRANS_DISPLAY_MODE = "trans_display_mode";
    public static final String KEY_TRANS_LIVE = "trans_live";

    public static final String TRANS_ENGINE_AUTO = "auto";
    public static final String TRANS_ENGINE_AI = "ai";
    public static final String TRANS_ENGINE_FREE = "free";
    public static final String TRANS_ENGINE_CUSTOM = "custom";

    public static final String TRANS_MODE_REPLACE = "replace";
    public static final String TRANS_MODE_BILINGUAL = "bilingual";

    public static final String NPM_MIRROR_TAOBAO = "https://registry.npmmirror.com";
    public static final String NPM_MIRROR_OFFICIAL = "https://registry.npmjs.org";

    public static final String ROOTFS_GHFAST = "https://ghfast.top/https://github.com/IPF-Sinon/DSH-Folk/releases/download/runtime-latest/rootfs.tar.gz";
    public static final String ROOTFS_OFFICIAL = "https://github.com/IPF-Sinon/DSH-Folk/releases/download/runtime-latest/rootfs.tar.gz";

    // 服务商常量
    public static final String PROVIDER_DEEPSEEK = "deepseek";
    public static final String PROVIDER_OPENAI = "openai";
    public static final String PROVIDER_CLAUDE = "anthropic";
    public static final String PROVIDER_OPENROUTER = "openrouter";
    public static final String PROVIDER_CUSTOM = "custom";

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isAutoStartWeb(Context context) {
        return getPrefs(context).getBoolean(KEY_AUTO_START, true);
    }

    public static void setAutoStartWeb(Context context, boolean autoStart) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_START, autoStart).apply();
    }

    public static int getWebZoom(Context context) {
        return getPrefs(context).getInt(KEY_WEB_ZOOM, 100);
    }

    public static void setWebZoom(Context context, int zoom) {
        getPrefs(context).edit().putInt(KEY_WEB_ZOOM, zoom).apply();
    }

    public static float getTermFontSize(Context context) {
        return getPrefs(context).getFloat(KEY_TERM_FONT_SIZE, 12.0f);
    }

    public static void setTermFontSize(Context context, float size) {
        getPrefs(context).edit().putFloat(KEY_TERM_FONT_SIZE, size).apply();
    }

    public static String getCustomShortcutsJson(Context context) {
        return getPrefs(context).getString(KEY_CUSTOM_SHORTCUTS, "");
    }

    public static void setCustomShortcutsJson(Context context, String json) {
        getPrefs(context).edit().putString(KEY_CUSTOM_SHORTCUTS, json).apply();
    }

    public static String getTopToolbarOrder(Context context) {
        return getPrefs(context).getString(KEY_TOP_TOOLBAR_ORDER, "");
    }

    public static void setTopToolbarOrder(Context context, String order) {
        getPrefs(context).edit().putString(KEY_TOP_TOOLBAR_ORDER, order).apply();
    }

    public static boolean isKeyboardPinned(Context context) {
        return getPrefs(context).getBoolean(KEY_KEYBOARD_PINNED, true);
    }

    public static void setKeyboardPinned(Context context, boolean pinned) {
        getPrefs(context).edit().putBoolean(KEY_KEYBOARD_PINNED, pinned).apply();
    }

    public static int getWebPort(Context context) {
        return getPrefs(context).getInt(KEY_WEB_PORT, DEFAULT_WEB_PORT);
    }

    public static void setWebPort(Context context, int port) {
        getPrefs(context).edit().putInt(KEY_WEB_PORT, port).apply();
    }

    public static String getNpmRegistry(Context context) {
        return getPrefs(context).getString(KEY_NPM_REGISTRY, NPM_MIRROR_TAOBAO);
    }

    public static void setNpmRegistry(Context context, String registry) {
        getPrefs(context).edit().putString(KEY_NPM_REGISTRY, registry).apply();
    }

    public static String getRootfsUrl(Context context) {
        return getPrefs(context).getString(KEY_ROOTFS_MIRROR, ROOTFS_GHFAST);
    }

    public static void setRootfsUrl(Context context, String url) {
        getPrefs(context).edit().putString(KEY_ROOTFS_MIRROR, url).apply();
    }

    public static boolean isKeepAliveEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_KEEP_ALIVE, true);
    }

    public static void setKeepAliveEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(KEY_KEEP_ALIVE, enabled).apply();
    }

    // AI 模型配置 getter / setter
    public static String getAiProvider(Context context) {
        return getPrefs(context).getString(KEY_AI_PROVIDER, PROVIDER_DEEPSEEK);
    }

    public static void setAiProvider(Context context, String provider) {
        getPrefs(context).edit().putString(KEY_AI_PROVIDER, provider).apply();
    }

    public static String getAiApiKey(Context context) {
        return getPrefs(context).getString(KEY_AI_API_KEY, "");
    }

    public static void setAiApiKey(Context context, String apiKey) {
        getPrefs(context).edit().putString(KEY_AI_API_KEY, apiKey).apply();
    }

    public static String getAiBaseUrl(Context context) {
        return getPrefs(context).getString(KEY_AI_BASE_URL, "https://api.deepseek.com");
    }

    public static void setAiBaseUrl(Context context, String baseUrl) {
        getPrefs(context).edit().putString(KEY_AI_BASE_URL, baseUrl).apply();
    }

    public static String getAiModel(Context context) {
        return getPrefs(context).getString(KEY_AI_MODEL, "deepseek-chat");
    }

    public static void setAiModel(Context context, String model) {
        getPrefs(context).edit().putString(KEY_AI_MODEL, model).apply();
    }

    // 网页翻译配置 getter / setter
    public static String getTransEngine(Context context) {
        return getPrefs(context).getString(KEY_TRANS_ENGINE, TRANS_ENGINE_AUTO);
    }

    public static void setTransEngine(Context context, String engine) {
        getPrefs(context).edit().putString(KEY_TRANS_ENGINE, engine).apply();
    }

    public static String getTransCustomBaseUrl(Context context) {
        return getPrefs(context).getString(KEY_TRANS_CUSTOM_BASE_URL, "https://api.deepseek.com/v1");
    }

    public static void setTransCustomBaseUrl(Context context, String url) {
        getPrefs(context).edit().putString(KEY_TRANS_CUSTOM_BASE_URL, url).apply();
    }

    public static String getTransCustomApiKey(Context context) {
        return getPrefs(context).getString(KEY_TRANS_CUSTOM_API_KEY, "");
    }

    public static void setTransCustomApiKey(Context context, String apiKey) {
        getPrefs(context).edit().putString(KEY_TRANS_CUSTOM_API_KEY, apiKey).apply();
    }

    public static String getTransCustomModel(Context context) {
        return getPrefs(context).getString(KEY_TRANS_CUSTOM_MODEL, "deepseek-chat");
    }

    public static void setTransCustomModel(Context context, String model) {
        getPrefs(context).edit().putString(KEY_TRANS_CUSTOM_MODEL, model).apply();
    }

    public static String getTransDisplayMode(Context context) {
        return getPrefs(context).getString(KEY_TRANS_DISPLAY_MODE, TRANS_MODE_REPLACE);
    }

    public static void setTransDisplayMode(Context context, String mode) {
        getPrefs(context).edit().putString(KEY_TRANS_DISPLAY_MODE, mode).apply();
    }

    public static boolean isTransLiveEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_TRANS_LIVE, false);
    }

    public static void setTransLiveEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(KEY_TRANS_LIVE, enabled).apply();
    }

    /**
     * 将模型环境变量动态注入到 PRoot 启动环境
     * 严格隔离不同服务商的环境变量，杜绝跨服务商污染与 OpenAI 选项意外弹出
     */
    public static void injectEnvironment(Context context, Map<String, String> env) {
        String provider = getAiProvider(context);
        String apiKey = getAiApiKey(context);
        String baseUrl = getAiBaseUrl(context);
        String model = getAiModel(context);

        // 彻底清理互斥的环境变量，防止打架
        env.remove("OPENAI_API_KEY");
        env.remove("OPENAI_BASE_URL");
        env.remove("DEEPSEEK_API_KEY");
        env.remove("DEEPSEEK_BASE_URL");
        env.remove("ANTHROPIC_API_KEY");
        env.remove("OPENROUTER_API_KEY");
        env.remove("PI_MODEL");

        if (!TextUtils.isEmpty(apiKey)) {
            switch (provider) {
                case PROVIDER_DEEPSEEK:
                    env.put("DEEPSEEK_API_KEY", apiKey);
                    if (!TextUtils.isEmpty(baseUrl) && !baseUrl.contains("api.deepseek.com")) {
                        env.put("DEEPSEEK_BASE_URL", baseUrl);
                    }
                    break;
                case PROVIDER_OPENAI:
                    // 仅当用户明确配置为官方 OpenAI 时注入
                    env.put("OPENAI_API_KEY", apiKey);
                    if (!TextUtils.isEmpty(baseUrl)) {
                        env.put("OPENAI_BASE_URL", baseUrl);
                    }
                    break;
                case PROVIDER_CLAUDE:
                    env.put("ANTHROPIC_API_KEY", apiKey);
                    break;
                case PROVIDER_OPENROUTER:
                    env.put("OPENROUTER_API_KEY", apiKey);
                    break;
                case PROVIDER_CUSTOM:
                    // 自定义模型由 models.json 接管，绝不注入 OPENAI_API_KEY 避免 Web 端莫名多出 OpenAI
                    break;
            }
        }

        if (!TextUtils.isEmpty(model)) {
            env.put("PI_MODEL", model);
        }
    }

    /**
     * 反向同步：从容器内部 (~/.pi/agent/) 读取已生效的配置到 App 外部界面
     * 返回 true 表示检测到配置变更并已更新本地设置
     */
    public static boolean syncFromContainer(Context context) {
        try {
            File rootfs = ProotManager.getRootfsDir(context);
            if (!rootfs.exists()) return false;
            File agentDir = new File(rootfs, "root/.pi/agent");
            if (!agentDir.exists()) return false;

            File settingsFile = new File(agentDir, "settings.json");
            File authFile = new File(agentDir, "auth.json");
            File modelsFile = new File(agentDir, "models.json");

            String provider = null;
            String model = null;
            String apiKey = null;
            String baseUrl = null;

            if (settingsFile.exists()) {
                try {
                    JSONObject settings = new JSONObject(readFile(settingsFile));
                    if (settings.has("defaultProvider")) {
                        provider = settings.optString("defaultProvider");
                    }
                    if (settings.has("defaultModel")) {
                        model = settings.optString("defaultModel");
                    }
                } catch (Throwable ignored) {}
            }

            if (authFile.exists()) {
                try {
                    JSONObject auth = new JSONObject(readFile(authFile));
                    if (provider != null && auth.has(provider)) {
                        JSONObject pObj = auth.optJSONObject(provider);
                        if (pObj != null) apiKey = pObj.optString("key");
                    } else {
                        for (String p : new String[]{"deepseek", "anthropic", "openrouter", "openai", "custom"}) {
                            if (auth.has(p)) {
                                JSONObject pObj = auth.optJSONObject(p);
                                if (pObj != null && !TextUtils.isEmpty(pObj.optString("key"))) {
                                    if (provider == null) provider = p;
                                    apiKey = pObj.optString("key");
                                    break;
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }

            if (modelsFile.exists()) {
                try {
                    JSONObject models = new JSONObject(readFile(modelsFile));
                    JSONObject providers = models.optJSONObject("providers");
                    if (providers != null && providers.has("custom")) {
                        JSONObject custom = providers.optJSONObject("custom");
                        if (custom != null) {
                            baseUrl = custom.optString("baseUrl");
                            if (apiKey == null && custom.has("apiKey")) {
                                apiKey = custom.optString("apiKey");
                            }
                            if ("custom".equals(provider)) {
                                JSONArray mArr = custom.optJSONArray("models");
                                if (mArr != null && mArr.length() > 0) {
                                    model = mArr.getJSONObject(0).optString("id");
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }

            boolean changed = false;
            if (!TextUtils.isEmpty(provider)) {
                if ("anthropic".equals(provider)) provider = PROVIDER_CLAUDE;
                setAiProvider(context, provider);
                changed = true;
            }
            if (!TextUtils.isEmpty(apiKey)) {
                setAiApiKey(context, apiKey);
                changed = true;
            }
            if (!TextUtils.isEmpty(baseUrl)) {
                setAiBaseUrl(context, baseUrl);
                changed = true;
            }
            if (!TextUtils.isEmpty(model)) {
                setAiModel(context, model);
                changed = true;
            }
            return changed;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 同步持久化写入容器内部的 /root/.bashrc, ~/.pi/agent/auth.json, models.json 和 settings.json
     * 采用增量合并与防打架机制，避免覆盖容器内其它配置，更杜绝污染出错误的 openai 选项
     */
    public static void syncToContainer(Context context) {
        new Thread(() -> {
            try {
                File rootfs = ProotManager.getRootfsDir(context);
                if (!rootfs.exists()) return;

                File rootHome = new File(rootfs, "root");
                rootHome.mkdirs();

                String provider = getAiProvider(context);
                String apiKey = getAiApiKey(context);
                String baseUrl = getAiBaseUrl(context);
                String model = getAiModel(context);

                // 1. 安全净化并写入 /root/.bashrc，不滥用全局环境变量，杜绝打架
                File bashrc = new File(rootHome, ".bashrc");
                StringBuilder bashContent = new StringBuilder();
                bashContent.append("# PiMet Auto-Generated Environment\n");
                bashContent.append("export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n");
                bashContent.append("export TERM=xterm-256color\n");
                bashContent.append("export PI_CODING_AGENT_DIR=/root/.pi/agent\n");
                // 仅当用户选中的就是 OpenAI 时，才允许在终端会话导出 OPENAI_API_KEY，其它模型绝不导出
                if (PROVIDER_OPENAI.equals(provider) && !TextUtils.isEmpty(apiKey)) {
                    bashContent.append("export OPENAI_API_KEY=\"").append(apiKey).append("\"\n");
                    if (!TextUtils.isEmpty(baseUrl)) {
                        bashContent.append("export OPENAI_BASE_URL=\"").append(baseUrl).append("\"\n");
                    }
                } else if (PROVIDER_DEEPSEEK.equals(provider) && !TextUtils.isEmpty(apiKey)) {
                    bashContent.append("export DEEPSEEK_API_KEY=\"").append(apiKey).append("\"\n");
                } else if (PROVIDER_CLAUDE.equals(provider) && !TextUtils.isEmpty(apiKey)) {
                    bashContent.append("export ANTHROPIC_API_KEY=\"").append(apiKey).append("\"\n");
                } else if (PROVIDER_OPENROUTER.equals(provider) && !TextUtils.isEmpty(apiKey)) {
                    bashContent.append("export OPENROUTER_API_KEY=\"").append(apiKey).append("\"\n");
                }
                if (!TextUtils.isEmpty(model)) {
                    bashContent.append("export PI_MODEL=\"").append(model).append("\"\n");
                }
                writeFile(bashrc, bashContent.toString());

                File agentDir = new File(rootHome, ".pi/agent");
                agentDir.mkdirs();

                // 2. 合并写入 /root/.pi/agent/auth.json (增量更新，不覆盖用户已有的其它凭据)
                File authJsonFile = new File(agentDir, "auth.json");
                JSONObject authJson = new JSONObject();
                if (authJsonFile.exists()) {
                    try {
                        String existing = readFile(authJsonFile);
                        if (!TextUtils.isEmpty(existing)) {
                            authJson = new JSONObject(existing);
                        }
                    } catch (Throwable ignored) {}
                }

                // 若当前服务商不是 OpenAI，则清除可能残留的虚假 openai 键，防止 Web 界面莫名多出 openai
                if (!PROVIDER_OPENAI.equals(provider) && authJson.has("openai")) {
                    JSONObject openaiObj = authJson.optJSONObject("openai");
                    if (openaiObj != null && apiKey.equals(openaiObj.optString("key"))) {
                        authJson.remove("openai");
                    }
                }

                String actualProvider = provider;
                if (PROVIDER_CLAUDE.equals(provider)) actualProvider = "anthropic";
                if (!TextUtils.isEmpty(apiKey)) {
                    JSONObject entry = new JSONObject();
                    entry.put("type", "api_key");
                    entry.put("key", apiKey);
                    authJson.put(actualProvider, entry);
                }
                writeFile(authJsonFile, authJson.toString(2));

                // 3. 自定义模型标准写入 /root/.pi/agent/models.json (规范接入，杜绝伪装成 OpenAI)
                File modelsFile = new File(agentDir, "models.json");
                JSONObject modelsJson = new JSONObject();
                if (modelsFile.exists()) {
                    try {
                        String existing = readFile(modelsFile);
                        if (!TextUtils.isEmpty(existing)) {
                            modelsJson = new JSONObject(existing);
                        }
                    } catch (Throwable ignored) {}
                }
                if (PROVIDER_CUSTOM.equals(provider) && !TextUtils.isEmpty(baseUrl)) {
                    JSONObject providersObj = modelsJson.optJSONObject("providers");
                    if (providersObj == null) {
                        providersObj = new JSONObject();
                        modelsJson.put("providers", providersObj);
                    }
                    JSONObject customObj = new JSONObject();
                    customObj.put("baseUrl", baseUrl);
                    customObj.put("api", "openai-completions");
                    if (!TextUtils.isEmpty(apiKey)) {
                        customObj.put("apiKey", apiKey);
                    }
                    JSONArray modelsArray = new JSONArray();
                    JSONObject mObj = new JSONObject();
                    mObj.put("id", !TextUtils.isEmpty(model) ? model : "custom-model");
                    mObj.put("name", !TextUtils.isEmpty(model) ? model : "Custom Model");
                    modelsArray.put(mObj);
                    customObj.put("models", modelsArray);
                    providersObj.put("custom", customObj);
                    writeFile(modelsFile, modelsJson.toString(2));
                }

                // 4. 合并写入 /root/.pi/agent/settings.json (保留用户其它偏好)
                File settingsFile = new File(agentDir, "settings.json");
                JSONObject settingsJson = new JSONObject();
                if (settingsFile.exists()) {
                    try {
                        String existing = readFile(settingsFile);
                        if (!TextUtils.isEmpty(existing)) {
                            settingsJson = new JSONObject(existing);
                        }
                    } catch (Throwable ignored) {}
                }
                settingsJson.put("defaultProvider", actualProvider);
                if (!TextUtils.isEmpty(model)) {
                    settingsJson.put("defaultModel", model);
                }
                writeFile(settingsFile, settingsJson.toString(2));

                // 5. 写入 /usr/local/bin/pi-chat 智能交互终端会话脚本
                File binDir = new File(rootfs, "usr/local/bin");
                binDir.mkdirs();
                File piChatScript = new File(binDir, "pi-chat");
                StringBuilder chatScript = new StringBuilder();
                chatScript.append("#!/bin/bash\n");
                chatScript.append("export TERM=xterm-256color\n");
                chatScript.append("export PI_CODING_AGENT_DIR=/root/.pi/agent\n\n");
                chatScript.append("echo -e \"\\033[1;36m╔══════════════════════════════════════════════════════╗\\033[0m\"\n");
                chatScript.append("echo -e \"\\033[1;36m║\\033[0m  \\033[1;32m🤖 Pi AI 交互对话终端模式已启动\\033[0m                    \\033[1;36m║\\033[0m\"\n");
                chatScript.append("echo -e \"\\033[1;36m║\\033[0m  \\033[90m• 输入你的问题或代码需求，按回车直接发送\\033[0m            \\033[1;36m║\\033[0m\"\n");
                chatScript.append("echo -e \"\\033[1;36m║\\033[0m  \\033[90m• 连续对话已开启，支持上下文多轮追问\\033[0m                \\033[1;36m║\\033[0m\"\n");
                chatScript.append("echo -e \"\\033[1;36m║\\033[0m  \\033[90m• 输入 'exit'、'quit' 或按 Ctrl+C 退出对话模式\\033[0m      \\033[1;36m║\\033[0m\"\n");
                chatScript.append("echo -e \"\\033[1;36m╚══════════════════════════════════════════════════════╝\\033[0m\"\n");
                chatScript.append("echo \"\"\n\n");
                chatScript.append("is_first=1\n");
                chatScript.append("while true; do\n");
                chatScript.append("    echo -en \"\\033[1;35mAI> \\033[0m\"\n");
                chatScript.append("    read -r userInput || break\n");
                chatScript.append("    if [[ \"$userInput\" == \"exit\" || \"$userInput\" == \"quit\" || \"$userInput\" == \"q\" ]]; then\n");
                chatScript.append("        echo -e \"\\033[33m已安全退出 AI 对话，返回 Linux 控制台。\\033[0m\"\n");
                chatScript.append("        break\n");
                chatScript.append("    fi\n");
                chatScript.append("    if [[ -z \"$userInput\" ]]; then\n");
                chatScript.append("        continue\n");
                chatScript.append("    fi\n");
                chatScript.append("    echo -e \"\\033[90m• 正在思考中...\\033[0m\"\n");
                chatScript.append("    if [[ $is_first -eq 1 ]]; then\n");
                chatScript.append("        pi -p \"$userInput\"\n");
                chatScript.append("        is_first=0\n");
                chatScript.append("    else\n");
                chatScript.append("        pi -p --continue \"$userInput\"\n");
                chatScript.append("    fi\n");
                chatScript.append("    echo \"\"\n");
                chatScript.append("done\n");

                writeFile(piChatScript, chatScript.toString());
                piChatScript.setExecutable(true, false);

                // 6. 若设备存在 Root 二进制文件，写入 /usr/local/bin/su-exec 宿主提权代理
                if (DevicePrivilegeManager.isRootBinaryPresent()) {
                    File suExecScript = new File(binDir, "su-exec");
                    String suContent = "#!/bin/bash\n/system/bin/su -c \"$@\"\n";
                    writeFile(suExecScript, suContent);
                    suExecScript.setExecutable(true, false);
                }
            } catch (Throwable ignored) {}
        }).start();
    }

    private static String readFile(File file) {
        try (FileInputStream fis = new FileInputStream(file);
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[2048];
            int n;
            while ((n = fis.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeFile(File file, String content) {
        try (FileOutputStream fos = new FileOutputStream(file, false)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        } catch (Throwable ignored) {}
    }
}
