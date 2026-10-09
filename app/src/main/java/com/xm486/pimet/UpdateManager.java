package com.xm486.pimet;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.xm486.pimet.proot.ProotManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 软件与组件平滑无感更新管理器 (UpdateManager)
 * 负责检测 Pi-Web 工作台 NPM 包更新以及 PiMet 客户端 GitHub Release 更新，
 * 采用原地增量更新与安全下载覆盖机制，绝不破坏用户现有配置、容器环境、插件或桌宠数据。
 */
public final class UpdateManager {
    private static final String TAG = "PiMet.UpdateManager";

    public static final String GITHUB_RELEASE_API = "https://api.github.com/repos/zr987459/pimet/releases/latest";
    public static final String NPM_PIWEB_API = "https://registry.npmmirror.com/@earendil-works/pi-web/latest";

    public interface Callback<T> {
        void onResult(boolean success, T data, String message);
    }

    public static class AppUpdateInfo {
        public String currentVersion;
        public String latestVersion;
        public String releaseTitle;
        public String changelog;
        public String downloadUrl;
        public boolean hasUpdate;
    }

    public static class PiWebUpdateInfo {
        public String currentVersion;
        public String latestVersion;
        public boolean hasUpdate;
    }

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * 异步检查客户端 (PiMet APK) 最新版本
     */
    public static void checkAppUpdate(Context context, Callback<AppUpdateInfo> callback) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(GITHUB_RELEASE_API);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setRequestProperty("User-Agent", "PiMet-Android-Client");
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");

                int code = conn.getResponseCode();
                if (code == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) sb.append(line);
                    }
                    JSONObject json = new JSONObject(sb.toString());
                    String tagName = json.optString("tag_name", "");
                    String title = json.optString("name", tagName);
                    String body = json.optString("body", "");

                    String cleanLatest = tagName.replace("v", "").replace("V", "").trim();
                    String currentVer = BuildConfig.VERSION_NAME;
                    String cleanCurrent = currentVer.replace("v", "").replace("V", "").trim();

                    String downloadUrl = null;
                    JSONArray assets = json.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject a = assets.optJSONObject(i);
                            if (a != null) {
                                String name = a.optString("name", "");
                                if (name.endsWith(".apk")) {
                                    downloadUrl = a.optString("browser_download_url", null);
                                    break;
                                }
                            }
                        }
                    }
                    if (downloadUrl == null) {
                        downloadUrl = json.optString("html_url", "https://github.com/zr987459/pimet/releases/latest");
                    }

                    boolean hasUpdate = PluginManager.isVersionNewer(cleanLatest, cleanCurrent);

                    AppUpdateInfo info = new AppUpdateInfo();
                    info.currentVersion = currentVer;
                    info.latestVersion = tagName;
                    info.releaseTitle = title;
                    info.changelog = body;
                    info.downloadUrl = downloadUrl;
                    info.hasUpdate = hasUpdate;

                    mainHandler.post(() -> callback.onResult(true, info, hasUpdate ? "发现新版本 " + tagName : "当前已是最新版本"));
                    return;
                } else {
                    mainHandler.post(() -> callback.onResult(false, null, "检测失败 (HTTP " + code + ")"));
                }
            } catch (Throwable t) {
                Log.w(TAG, "checkAppUpdate error", t);
                mainHandler.post(() -> callback.onResult(false, null, "网络异常: " + t.getMessage()));
            } finally {
                if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }).start();
    }

    /**
     * 打开外部浏览器或下载管理器下载 APK
     */
    public static void startApkDownload(Context context, String downloadUrl) {
        if (downloadUrl == null || downloadUrl.isEmpty()) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable t) {
            Log.e(TAG, "startApkDownload failed", t);
        }
    }

    /**
     * 异步检查 Pi-Web NPM 包最新版本
     */
    public static void checkPiWebUpdate(Context context, Callback<PiWebUpdateInfo> callback) {
        new Thread(() -> {
            String currentVer = getInstalledPiWebVersion(context);
            HttpURLConnection conn = null;
            try {
                URL url = new URL(NPM_PIWEB_API);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                if (conn.getResponseCode() == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) sb.append(line);
                    }
                    JSONObject json = new JSONObject(sb.toString());
                    String latestVer = json.optString("version", null);

                    PiWebUpdateInfo info = new PiWebUpdateInfo();
                    info.currentVersion = currentVer;
                    info.latestVersion = latestVer != null ? latestVer : "未知";
                    info.hasUpdate = latestVer != null && PluginManager.isVersionNewer(latestVer, currentVer);

                    mainHandler.post(() -> callback.onResult(true, info, info.hasUpdate ? "发现 Pi-Web 新版: " + latestVer : "Pi-Web 已是最新版"));
                    return;
                }
            } catch (Throwable t) {
                Log.w(TAG, "checkPiWebUpdate error", t);
            } finally {
                if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
            }

            PiWebUpdateInfo fallback = new PiWebUpdateInfo();
            fallback.currentVersion = currentVer;
            fallback.latestVersion = "检测超时";
            fallback.hasUpdate = false;
            mainHandler.post(() -> callback.onResult(false, fallback, "无法连接 npm 源"));
        }).start();
    }

    /**
     * 原地无感升级 Pi-Web（保留所有会话、文件与配置）
     */
    public static void updatePiWebInPlace(Context context, Callback<String> callback) {
        new Thread(() -> {
            try {
                String cmd = "npm install -g @earendil-works/pi-web@latest --registry=https://registry.npmmirror.com";
                int code = ProotManager.executeCommandSync(context, cmd);
                if (code == 0) {
                    mainHandler.post(() -> callback.onResult(true, "升级成功", "Pi-Web 工作台已无感更新至最新版本！"));
                } else {
                    mainHandler.post(() -> callback.onResult(false, null, "升级退出码: " + code + "，请查看控制台日志"));
                }
            } catch (Throwable t) {
                Log.e(TAG, "updatePiWebInPlace error", t);
                mainHandler.post(() -> callback.onResult(false, null, "更新失败: " + t.getMessage()));
            }
        }).start();
    }

    /**
     * 获取容器内已安装的 Pi-Web 版本号
     */
    public static String getInstalledPiWebVersion(Context context) {
        File rootfs = ProotManager.getRootfsDir(context);
        if (rootfs != null && rootfs.exists()) {
            File[] pkgFiles = new File[]{
                    new File(rootfs, "usr/local/lib/node_modules/@earendil-works/pi-web/package.json"),
                    new File(rootfs, "root/.pi/agent/npm/node_modules/@earendil-works/pi-web/package.json"),
                    new File(rootfs, "usr/lib/node_modules/@earendil-works/pi-web/package.json")
            };
            for (File pf : pkgFiles) {
                if (pf.exists()) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(new FileInputStream(pf), StandardCharsets.UTF_8))) {
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) sb.append(line);
                        JSONObject json = new JSONObject(sb.toString());
                        String ver = json.optString("version", null);
                        if (ver != null && !ver.isEmpty()) return ver;
                    } catch (Throwable ignored) {}
                }
            }
        }
        return "1.0.0";
    }
}
