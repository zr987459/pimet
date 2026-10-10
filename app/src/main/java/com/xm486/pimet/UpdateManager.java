package com.xm486.pimet;

import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.xm486.pimet.proot.PiWebManager;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 软件平滑更新管理器 (UpdateManager)
 * 负责检测与执行：
 * 1. Pi-Web 工作台 (@agegr/pi-web) 平滑原地增量升级与版本检测。
 * 2. PiMet 客户端 GitHub Release 自动测速优选线路、本地直接下载与复制下载直链。
 * 3. 完整保留所有现有容器、配置、会话与桌宠数据。
 */
public final class UpdateManager {
    private static final String TAG = "PiMet.UpdateManager";

    public static final String GITHUB_RELEASE_API = "https://api.github.com/repos/zr987459/pimet/releases/latest";

    public interface Callback<T> {
        void onResult(boolean success, T data, String message);
    }

    public static class AppUpdateInfo {
        public String currentVersion;
        public String latestVersion;
        public String releaseTitle;
        public String changelog;
        public String rawDownloadUrl;
        public String bestDownloadUrl;
        public String bestRouteName;
        public long routeLatencyMs;
        public boolean hasUpdate;
    }

    public static class PiWebUpdateInfo {
        public String currentVersion;
        public String latestVersion;
        public String registryUsed;
        public boolean hasUpdate;
    }

    private static class RouteCandidate {
        final String name;
        final String url;
        long latency = Long.MAX_VALUE;
        boolean reachable = false;

        RouteCandidate(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private UpdateManager() {}

    // ==========================================
    // 1. Pi-Web 工作台 (@agegr/pi-web) 平滑增量升级
    // ==========================================

    /**
     * 获取容器内已安装的 @agegr/pi-web 版本
     */
    public static String getInstalledPiWebVersion(Context context) {
        try {
            File rootfs = ProotManager.getRootfsDir(context);
            File[] candidates = new File[]{
                    new File(rootfs, "usr/local/lib/node_modules/@agegr/pi-web/package.json"),
                    new File(rootfs, "usr/lib/node_modules/@agegr/pi-web/package.json"),
                    new File(rootfs, "root/.pi/agent/npm/node_modules/@agegr/pi-web/package.json")
            };
            for (File f : candidates) {
                if (f.exists() && f.canRead()) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) sb.append(line);
                        JSONObject json = new JSONObject(sb.toString());
                        String ver = json.optString("version", null);
                        if (ver != null && !ver.isEmpty()) return ver;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return "0.11.0";
    }

    /**
     * 异步检测 Pi-Web 工作台 (@agegr/pi-web) 是否存在新版本
     */
    public static void checkPiWebUpdate(Context context, Callback<PiWebUpdateInfo> callback) {
        new Thread(() -> {
            String[] registries = new String[]{
                    "https://registry.npmmirror.com/@agegr/pi-web/latest",
                    "https://registry.npmjs.org/@agegr/pi-web/latest"
            };
            JSONObject json = null;
            String usedUrl = null;

            for (String regUrl : registries) {
                HttpURLConnection conn = null;
                try {
                    URL u = new URL(regUrl);
                    conn = (HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(4000);
                    conn.setReadTimeout(4000);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 PiMet");
                    int code = conn.getResponseCode();
                    if (code == 200) {
                        StringBuilder sb = new StringBuilder();
                        try (BufferedReader r = new BufferedReader(
                                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                            String l;
                            while ((l = r.readLine()) != null) sb.append(l);
                        }
                        json = new JSONObject(sb.toString());
                        usedUrl = regUrl;
                        break;
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
                }
            }

            if (json != null) {
                String latestVer = json.optString("version", "");
                String installed = getInstalledPiWebVersion(context);
                boolean hasUpdate = PluginManager.isVersionNewer(latestVer, installed);

                PiWebUpdateInfo info = new PiWebUpdateInfo();
                info.currentVersion = installed;
                info.latestVersion = latestVer;
                info.registryUsed = usedUrl != null && usedUrl.contains("npmmirror") ? "国内加速镜像 (npmmirror)" : "npm 官方源";
                info.hasUpdate = hasUpdate;

                MAIN_HANDLER.post(() -> callback.onResult(true, info,
                        hasUpdate ? "发现 Pi-Web 新版本 v" + latestVer : "当前 Pi-Web 已是最新版本 (v" + installed + ")"));
            } else {
                MAIN_HANDLER.post(() -> callback.onResult(false, null, "检测 Pi-Web 失败，未能连接镜像源"));
            }
        }).start();
    }

    /**
     * 在 Linux 容器内原地平滑增量升级 Pi-Web (@agegr/pi-web)
     */
    public static void updatePiWebInPlace(Context context, Callback<String> callback) {
        new Thread(() -> {
            try {
                String registry = PiMetConfig.getNpmRegistry(context);
                if (registry == null || registry.isEmpty()) registry = "https://registry.npmmirror.com/";
                String normalizedRegistry = registry.endsWith("/") ? registry : (registry + "/");

                String cmd = "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH; " +
                        "npm install -g --loglevel=error --no-audit --no-fund @agegr/pi-web@latest --registry=" + normalizedRegistry;

                ProotManager.executeCommandSync(context, cmd, 120000);
                ProotManager.optimizePiWebOffline(context);

                String newVer = getInstalledPiWebVersion(context);

                // 若 Pi-Web 服务正在后台运行，平滑重启以加载新代码
                int webPort = PiMetConfig.getWebPort(context);
                if (ProotManager.isPiWebPortAlive(webPort)) {
                    PiWebManager.restart(context, null);
                }

                MAIN_HANDLER.post(() -> callback.onResult(true, newVer, "✔ Pi-Web 已成功平滑升级至 v" + newVer));
            } catch (Throwable t) {
                Log.e(TAG, "updatePiWebInPlace failed", t);
                MAIN_HANDLER.post(() -> callback.onResult(false, null, "升级过程出现异常: " + t.getMessage()));
            }
        }).start();
    }

    // ==========================================
    // 2. PiMet 客户端应用更新与测速优选
    // ==========================================

    /**
     * 异步检查客户端 (PiMet APK) 最新版本，并在检测时自动测速与优选最快下载线路
     */
    public static void checkAppUpdate(Context context, Callback<AppUpdateInfo> callback) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL apiUrl = new URL(GITHUB_RELEASE_API);
                conn = (HttpURLConnection) apiUrl.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty("User-Agent", "PiMet-Android-Client/" + PiMetConfig.getAppVersion(context));
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");

                int code = conn.getResponseCode();
                JSONObject json = null;

                if (code == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) sb.append(line);
                    }
                    json = new JSONObject(sb.toString());
                } else {
                    String tag = fetchLatestTagViaRedirect();
                    if (tag != null && !tag.isEmpty()) {
                        json = new JSONObject();
                        json.put("tag_name", tag);
                        json.put("name", "PiMet " + tag);
                        json.put("body", "常规体验与底层稳定性优化");
                        JSONArray assets = new JSONArray();
                        JSONObject apkAsset = new JSONObject();
                        apkAsset.put("name", "PiMet-" + tag + ".apk");
                        apkAsset.put("browser_download_url",
                                "https://github.com/zr987459/pimet/releases/download/" + tag + "/PiMet-" + tag + ".apk");
                        assets.put(apkAsset);
                        json.put("assets", assets);
                    }
                }

                if (json != null) {
                    String tagName = json.optString("tag_name", "");
                    String title = json.optString("name", tagName);
                    String body = json.optString("body", "");

                    String cleanLatest = tagName.replace("v", "").replace("V", "").trim();
                    String currentVer = PiMetConfig.getAppVersion(context);
                    String cleanCurrent = currentVer.replace("v", "").replace("V", "").trim();

                    String rawDownloadUrl = null;
                    JSONArray assets = json.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject a = assets.optJSONObject(i);
                            if (a != null) {
                                String name = a.optString("name", "");
                                if (name.endsWith(".apk")) {
                                    rawDownloadUrl = a.optString("browser_download_url", null);
                                    break;
                                }
                            }
                        }
                    }
                    if (rawDownloadUrl == null) {
                        rawDownloadUrl = "https://github.com/zr987459/pimet/releases/download/" + tagName + "/PiMet-" + tagName + ".apk";
                    }

                    boolean hasUpdate = PluginManager.isVersionNewer(cleanLatest, cleanCurrent);

                    AppUpdateInfo info = new AppUpdateInfo();
                    info.currentVersion = currentVer;
                    info.latestVersion = tagName;
                    info.releaseTitle = title;
                    info.changelog = body;
                    info.rawDownloadUrl = rawDownloadUrl;
                    info.hasUpdate = hasUpdate;

                    // 自动网络测速并优选下载线路
                    selectFastestRoute(info, rawDownloadUrl);

                    MAIN_HANDLER.post(() -> callback.onResult(true, info,
                            hasUpdate ? "发现新版本 " + tagName : "当前已是最新版本"));
                    return;
                } else {
                    MAIN_HANDLER.post(() -> callback.onResult(false, null, "检测更新失败，未能获取新版信息"));
                }
            } catch (Throwable t) {
                Log.w(TAG, "checkAppUpdate error", t);
                MAIN_HANDLER.post(() -> callback.onResult(false, null, "网络连接异常: " + t.getMessage()));
            } finally {
                if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }).start();
    }

    /**
     * 探测各候选线路网络延迟，自动挑选最快可用节点
     */
    private static void selectFastestRoute(AppUpdateInfo info, String rawUrl) {
        List<RouteCandidate> candidates = new ArrayList<>();
        candidates.add(new RouteCandidate("国内加速线路 1 (ghfast)", "https://ghfast.top/" + rawUrl));
        candidates.add(new RouteCandidate("国内加速线路 2 (ghproxy)", "https://ghproxy.net/" + rawUrl));
        candidates.add(new RouteCandidate("国内加速线路 3 (mirror.ghproxy)", "https://mirror.ghproxy.com/" + rawUrl));
        candidates.add(new RouteCandidate("GitHub 官方直连", rawUrl));

        RouteCandidate best = null;
        for (RouteCandidate c : candidates) {
            long latency = probeRouteLatency(c.url);
            if (latency >= 0) {
                c.latency = latency;
                c.reachable = true;
                if (best == null || c.latency < best.latency) {
                    best = c;
                }
            }
        }

        if (best != null && best.reachable) {
            info.bestDownloadUrl = best.url;
            info.bestRouteName = best.name;
            info.routeLatencyMs = best.latency;
        } else {
            info.bestDownloadUrl = "https://ghfast.top/" + rawUrl;
            info.bestRouteName = "国内加速节点 (默认)";
            info.routeLatencyMs = -1;
        }
    }

    private static long probeRouteLatency(String urlStr) {
        HttpURLConnection conn = null;
        long start = System.currentTimeMillis();
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(2200);
            conn.setReadTimeout(2200);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");

            int code = conn.getResponseCode();
            long cost = System.currentTimeMillis() - start;
            if (code == 200 || code == 206 || code == 301 || code == 302) {
                return cost;
            }
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
        }
        return -1;
    }

    private static String fetchLatestTagViaRedirect() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://github.com/zr987459/pimet/releases/latest");
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            String location = conn.getHeaderField("Location");
            if (location != null && location.contains("/tag/")) {
                return location.substring(location.lastIndexOf("/tag/") + 5).trim();
            }
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
        }
        return null;
    }

    /**
     * 在本地使用系统 DownloadManager 后台下载 APK
     */
    public static void downloadLocally(Context context, String downloadUrl, String version) {
        if (downloadUrl == null || downloadUrl.isEmpty()) return;
        try {
            DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                String verStr = (version != null && !version.isEmpty()) ? version : "latest";
                String fileName = "PiMet-" + verStr + ".apk";

                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl));
                request.setTitle("PiMet 安装包更新 (" + verStr + ")");
                request.setDescription("正在下载新版本安装包...");
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
                request.setMimeType("application/vnd.android.package-archive");
                request.allowScanningByMediaScanner();

                dm.enqueue(request);

                Toast.makeText(context, "📥 已加入系统下载队列，下载完成后可点击通知直接安装！", Toast.LENGTH_LONG).show();
                return;
            }
        } catch (Throwable t) {
            Log.w(TAG, "downloadLocally DownloadManager failed, fallback to browser", t);
        }

        startApkDownload(context, downloadUrl);
    }

    /**
     * 复制下载链接至剪贴板
     */
    public static void copyDownloadLink(Context context, String downloadUrl) {
        if (downloadUrl == null || downloadUrl.isEmpty()) return;
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                ClipData clip = ClipData.newPlainText("PiMet APK Download Link", downloadUrl);
                cm.setPrimaryClip(clip);
                Toast.makeText(context, "✔ 已复制安装包下载链接，可粘贴至浏览器或外部下载器！", Toast.LENGTH_LONG).show();
            }
        } catch (Throwable t) {
            Log.e(TAG, "copyDownloadLink error", t);
        }
    }

    /**
     * 打开外部浏览器或下载工具下载 APK
     */
    public static void startApkDownload(Context context, String downloadUrl) {
        if (downloadUrl == null || downloadUrl.isEmpty()) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable t) {
            Log.e(TAG, "startApkDownload failed", t);
            Toast.makeText(context, "无法打开浏览器，请使用复制链接选项", Toast.LENGTH_SHORT).show();
        }
    }
}
