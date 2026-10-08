package com.xm486.pimet;

import android.content.Context;
import android.content.SharedPreferences;

public final class PiMetConfig {

    private static final String PREF_NAME = "pimet_settings";

    public static final String KEY_AUTO_START = "auto_start_web";
    public static final String KEY_WEB_PORT = "web_port";
    public static final String KEY_NPM_REGISTRY = "npm_registry";
    public static final String KEY_ROOTFS_MIRROR = "rootfs_mirror";
    public static final String KEY_KEEP_ALIVE = "keep_alive";

    public static final String NPM_MIRROR_TAOBAO = "https://registry.npmmirror.com";
    public static final String NPM_MIRROR_OFFICIAL = "https://registry.npmjs.org";

    public static final String ROOTFS_GHFAST = "https://ghfast.top/https://github.com/IPF-Sinon/DSH-Folk/releases/download/runtime-latest/rootfs.tar.gz";
    public static final String ROOTFS_OFFICIAL = "https://github.com/IPF-Sinon/DSH-Folk/releases/download/runtime-latest/rootfs.tar.gz";

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isAutoStartWeb(Context context) {
        return getPrefs(context).getBoolean(KEY_AUTO_START, true);
    }

    public static void setAutoStartWeb(Context context, boolean autoStart) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_START, autoStart).apply();
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
}
