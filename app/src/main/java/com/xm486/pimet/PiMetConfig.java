package com.xm486.pimet;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.xm486.pimet.proot.ProotManager;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class PiMetConfig {

    private static final String PREF_NAME = "pimet_settings";

    public static final String KEY_AUTO_START = "auto_start_web";
    public static final String KEY_WEB_PORT = "web_port";
    public static final String KEY_NPM_REGISTRY = "npm_registry";
    public static final String KEY_ROOTFS_MIRROR = "rootfs_mirror";
    public static final String KEY_KEEP_ALIVE = "keep_alive";
    public static final String KEY_WEB_ZOOM = "web_zoom";
    public static final String KEY_TERM_FONT_SIZE = "term_font_size";

    // AI 模型与 API Key 配置键
    public static final String KEY_AI_PROVIDER = "ai_provider";
    public static final String KEY_AI_API_KEY = "ai_api_key";
    public static final String KEY_AI_BASE_URL = "ai_base_url";
    public static final String KEY_AI_MODEL = "ai_model";

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

    public static int getWebPort(Context context) {
        return getPrefs(context).getInt(KEY_WEB_PORT, 30141);
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

    /**
     * 将模型环境变量动态注入到 PRoot 启动环境
     */
    public static void injectEnvironment(Context context, Map<String, String> env) {
        String provider = getAiProvider(context);
        String apiKey = getAiApiKey(context);
        String baseUrl = getAiBaseUrl(context);
        String model = getAiModel(context);

        if (!TextUtils.isEmpty(apiKey)) {
            switch (provider) {
                case PROVIDER_DEEPSEEK:
                    env.put("DEEPSEEK_API_KEY", apiKey);
                    break;
                case PROVIDER_OPENAI:
                case PROVIDER_CUSTOM:
                    env.put("OPENAI_API_KEY", apiKey);
                    break;
                case PROVIDER_CLAUDE:
                    env.put("ANTHROPIC_API_KEY", apiKey);
                    break;
                case PROVIDER_OPENROUTER:
                    env.put("OPENROUTER_API_KEY", apiKey);
                    break;
            }
        }

        if (!TextUtils.isEmpty(baseUrl)) {
            env.put("OPENAI_BASE_URL", baseUrl);
            env.put("DEEPSEEK_BASE_URL", baseUrl);
        }

        if (!TextUtils.isEmpty(model)) {
            env.put("PI_MODEL", model);
        }
    }

    /**
     * 同步持久化写入容器内部的 /root/.bashrc 和 ~/.pi/agent/auth.json
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

                // 1. 写入 /root/.bashrc
                File bashrc = new File(rootHome, ".bashrc");
                StringBuilder bashContent = new StringBuilder();
                bashContent.append("# PiMet Auto-Generated AI Credentials\n");
                bashContent.append("export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n");
                if (!TextUtils.isEmpty(apiKey)) {
                    if (PROVIDER_DEEPSEEK.equals(provider)) {
                        bashContent.append("export DEEPSEEK_API_KEY=\"").append(apiKey).append("\"\n");
                    } else if (PROVIDER_CLAUDE.equals(provider)) {
                        bashContent.append("export ANTHROPIC_API_KEY=\"").append(apiKey).append("\"\n");
                    } else if (PROVIDER_OPENROUTER.equals(provider)) {
                        bashContent.append("export OPENROUTER_API_KEY=\"").append(apiKey).append("\"\n");
                    } else {
                        bashContent.append("export OPENAI_API_KEY=\"").append(apiKey).append("\"\n");
                    }
                }
                if (!TextUtils.isEmpty(baseUrl)) {
                    bashContent.append("export OPENAI_BASE_URL=\"").append(baseUrl).append("\"\n");
                }
                if (!TextUtils.isEmpty(model)) {
                    bashContent.append("export PI_MODEL=\"").append(model).append("\"\n");
                }

                try (FileOutputStream fos = new FileOutputStream(bashrc, false)) {
                    fos.write(bashContent.toString().getBytes(StandardCharsets.UTF_8));
                }

                // 2. 写入 /root/.pi/agent/auth.json
                File agentDir = new File(rootHome, ".pi/agent");
                agentDir.mkdirs();
                File authJson = new File(agentDir, "auth.json");
                StringBuilder authContent = new StringBuilder("{\n");
                if (!TextUtils.isEmpty(apiKey)) {
                    authContent.append("  \"").append(provider).append("\": \"").append(apiKey).append("\"\n");
                }
                authContent.append("}\n");

                try (FileOutputStream fos = new FileOutputStream(authJson, false)) {
                    fos.write(authContent.toString().getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) {}
        }).start();
    }
}
