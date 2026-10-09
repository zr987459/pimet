package com.xm486.pimet.proot;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.util.Log;
import android.util.Pair;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 核心 PRoot 容器管理器：负责 Linux Rootfs 生命周期、PRoot 运行时封装与指令组装。
 * 参考 DSH-Folk 架构标准，在 Android 10+ 限制下安全拉起标准 Linux (Glibc) 环境。
 */
public final class ProotManager {

    private static final String TAG = "ProotManager";

    public static final int PI_WEB_PORT = 30141;

    // 官方与镜像 Rootfs 线路
    private static final String[] ROOTFS_URLS_ARM64 = {
            "https://ghfast.top/https://github.com/IPF-Sinon/DSH-Folk/releases/download/runtime-latest/rootfs.tar.gz",
            "https://ghproxy.net/https://github.com/IPF-Sinon/DSH-Folk/releases/download/runtime-latest/rootfs.tar.gz",
            "https://mirror.ghproxy.com/https://github.com/IPF-Sinon/DSH-Folk/releases/download/runtime-latest/rootfs.tar.gz",
            "https://github.com/IPF-Sinon/DSH-Folk/releases/download/runtime-latest/rootfs.tar.gz"
    };

    private static final String[] ROOTFS_URLS_X86_64 = {
            "https://ghfast.top/https://github.com/IPF-Sinon/DSH-Folk/releases/download/runtime-latest/rootfs-x86_64.tar.gz",
            "https://ghproxy.net/https://github.com/IPF-Sinon/DSH-Folk/releases/download/runtime-latest/rootfs-x86_64.tar.gz",
            "https://mirror.ghproxy.com/https://github.com/IPF-Sinon/DSH-Folk/releases/download/runtime-latest/rootfs-x86_64.tar.gz",
            "https://github.com/IPF-Sinon/DSH-Folk/releases/download/runtime-latest/rootfs-x86_64.tar.gz"
    };

    public interface InstallCallback {
        void onProgress(String message, int percent);
        void onSuccess();
        void onError(String error);
    }

    private ProotManager() {}

    /**
     * 获取原生 lib 目录 (系统赋予了可执行权限的路径)
     */
    public static File getNativeLibDir(Context context) {
        return new File(context.getApplicationInfo().nativeLibraryDir);
    }

    /**
     * 探测 APK 内置的 libproot.so 是否成功释放
     */
    public static boolean isNativeProotAvailable(Context context) {
        File proot = new File(getNativeLibDir(context), "libproot.so");
        return proot.exists() && proot.canExecute();
    }

    /**
     * Rootfs 根目录 (/data/data/<pkg>/files/rootfs)
     */
    public static File getRootfsDir(Context context) {
        return new File(context.getFilesDir(), "rootfs");
    }

    /**
     * 容器临时目录
     */
    public static File getTmpDir(Context context) {
        File tmp = new File(context.getFilesDir(), "tmp");
        if (!tmp.exists()) tmp.mkdirs();
        return tmp;
    }

    /**
     * 判定 Rootfs 是否已完整部署 (含 bash 和 node)
     */
    public static boolean isRootfsInstalled(Context context) {
        File rootfs = getRootfsDir(context);
        File bash = new File(rootfs, "bin/bash");
        File node = new File(rootfs, "usr/local/bin/node");
        if (!node.exists()) {
            node = new File(rootfs, "usr/bin/node");
        }
        return bash.exists() && node.exists();
    }

    /**
     * 判定 Pi 官方命令行核心与 Pi-Web 是否已在容器内就绪
     */
    public static boolean isPiInstalled(Context context) {
        File rootfs = getRootfsDir(context);
        File piBin1 = new File(rootfs, "usr/local/bin/pi");
        File piBin2 = new File(rootfs, "usr/bin/pi");
        return piBin1.exists() || piBin2.exists();
    }

    public static boolean isPiWebInstalled(Context context) {
        File rootfs = getRootfsDir(context);
        File piWebJs1 = new File(rootfs, "usr/local/lib/node_modules/@agegr/pi-web/bin/pi-web.js");
        File piWebJs2 = new File(rootfs, "usr/lib/node_modules/@agegr/pi-web/bin/pi-web.js");
        File piWebBin1 = new File(rootfs, "usr/local/bin/pi-web");
        File piWebBin2 = new File(rootfs, "usr/bin/pi-web");
        boolean webReady = (piWebJs1.exists() && piWebJs1.length() > 50) ||
               (piWebJs2.exists() && piWebJs2.length() > 50) ||
               piWebBin1.exists() || piWebBin2.exists();
        return webReady && isPiInstalled(context);
    }

    /**
     * 端口存活检测
     */
    public static boolean isPiWebPortAlive(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 400);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isPiWebPortAlive() {
        return isPiWebPortAlive(PI_WEB_PORT);
    }

    /**
     * HTTP 接口深度探活：确保 Node.js/Next.js 真正开始响应 HTTP 请求，
     * 避免仅 TCP 握手成功但服务仍在启动编译时 WebView 过早加载触发 Service Worker offline.html 缓存。
     */
    public static boolean isPiWebHttpReady(int port) {
        try {
            URL url = new URL("http://127.0.0.1:" + port + "/manifest.webmanifest");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(600);
            conn.setReadTimeout(1000);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            return code >= 200 && code < 400;
        } catch (Throwable ignored) {
            try {
                URL url2 = new URL("http://127.0.0.1:" + port + "/");
                HttpURLConnection conn2 = (HttpURLConnection) url2.openConnection();
                conn2.setConnectTimeout(600);
                conn2.setReadTimeout(1000);
                conn2.setRequestMethod("GET");
                conn2.setInstanceFollowRedirects(false);
                int code2 = conn2.getResponseCode();
                return code2 >= 200 && code2 < 400;
            } catch (Throwable ignored2) {
                return false;
            }
        }
    }

    public static boolean isPiWebHttpReady() {
        return isPiWebHttpReady(PI_WEB_PORT);
    }

    /**
     * 针对 pi-web Service Worker 离线机制的深层防御：
     * 1. 延长 sw.js 内 NAVIGATION_TIMEOUT_MS（从苛刻的 2.5s 延长至 15s），适配手机移动端冷启动
     * 2. 在 offline.html 注入静默自动重连探活代码，一旦后端拉起立即自动刷新，杜绝用户反复手动点 Try again
     */
    public static void optimizePiWebOffline(Context context) {
        try {
            File rootfs = getRootfsDir(context);
            File[] swFiles = new File[]{
                    new File(rootfs, "usr/local/lib/node_modules/@agegr/pi-web/public/sw.js"),
                    new File(rootfs, "usr/lib/node_modules/@agegr/pi-web/public/sw.js")
            };
            for (File sw : swFiles) {
                if (sw.exists() && sw.canWrite()) {
                    String content = readFileToString(sw);
                    if (content != null && content.contains("NAVIGATION_TIMEOUT_MS = 2500;")) {
                        content = content.replace("NAVIGATION_TIMEOUT_MS = 2500;", "NAVIGATION_TIMEOUT_MS = 15000;");
                        writeStringToFile(sw, content);
                    }
                }
            }

            File[] offlineFiles = new File[]{
                    new File(rootfs, "usr/local/lib/node_modules/@agegr/pi-web/public/offline.html"),
                    new File(rootfs, "usr/lib/node_modules/@agegr/pi-web/public/offline.html")
            };
            for (File off : offlineFiles) {
                if (off.exists() && off.canWrite()) {
                    String content = readFileToString(off);
                    if (content != null && !content.contains("auto-reconnect-timer")) {
                        String inject = "<script id=\"auto-reconnect-timer\">\n" +
                                "setInterval(function() {\n" +
                                "  fetch('/manifest.webmanifest', { cache: 'no-store' })\n" +
                                "    .then(function(r) { if (r.ok) location.reload(); })\n" +
                                "    .catch(function() {});\n" +
                                "}, 1200);\n" +
                                "</script>\n</body>";
                        content = content.replace("</body>", inject);
                        writeStringToFile(off, content);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "optimizePiWebOffline failed", t);
        }
    }

    private static void writeStringToFile(File file, String str) {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(str.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        } catch (Throwable ignored) {}
    }

    public static long getDirectorySize(File dir) {
        if (dir == null || !dir.exists()) return 0;
        if (!dir.isDirectory()) return dir.length();
        long size = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    size += getDirectorySize(file);
                } else {
                    size += file.length();
                }
            }
        }
        return size;
    }

    public static String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        if (digitGroups >= units.length) digitGroups = units.length - 1;
        return String.format(java.util.Locale.US, "%.1f %s", bytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    public static boolean deleteRecursive(File f) {
        if (f == null || !f.exists()) return true;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        return f.delete();
    }

    /**
     * 全自动在线拉取并解压 Linux 容器根文件系统
     */
    public static void installRootfs(Context context, InstallCallback callback) {
        new Thread(() -> {
            try {
                if (isRootfsInstalled(context)) {
                    if (callback != null) callback.onSuccess();
                    return;
                }

                File tmpDir = getTmpDir(context);
                File rootfsDir = getRootfsDir(context);
                rootfsDir.mkdirs();

                boolean isX86 = false;
                for (String abi : Build.SUPPORTED_ABIS) {
                    if (abi.contains("x86_64")) {
                        isX86 = true;
                        break;
                    }
                }
                String[] rawUrls = isX86 ? ROOTFS_URLS_X86_64 : ROOTFS_URLS_ARM64;
                if (callback != null) callback.onProgress("⚡ 正在测速优选下载镜像线路...", 3);
                String[] urls = testAndSortCandidateUrls(rawUrls, msg -> {
                    if (callback != null) callback.onProgress(msg, 5);
                });
                File targetTarball = new File(tmpDir, "rootfs.tar.gz");

                boolean downloadSuccess = false;
                for (String urlStr : urls) {
                    try {
                        if (callback != null) callback.onProgress("连接容器镜像源: " + urlStr, 5);
                        downloadWithResume(urlStr, targetTarball, (percent) -> {
                            if (callback != null) {
                                callback.onProgress("正在下载 Linux 容器系统 (" + percent + "%)...", percent * 70 / 100);
                            }
                        });
                        if (targetTarball.exists() && targetTarball.length() > 100 * 1024 * 1024) {
                            downloadSuccess = true;
                            break;
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Mirror failed: " + urlStr, t);
                    }
                }

                if (!downloadSuccess || !targetTarball.exists()) {
                    if (callback != null) callback.onError("下载 Linux 容器镜像失败，请检查网络后重试");
                    return;
                }

                if (callback != null) callback.onProgress("正在解压 Linux 容器根文件系统...", 75);
                TarGzipExtractor.extractRootfs(targetTarball, rootfsDir);

                // 创建标准挂载点和用户主目录
                new File(rootfsDir, "dev").mkdirs();
                new File(rootfsDir, "proc").mkdirs();
                new File(rootfsDir, "sys").mkdirs();
                new File(rootfsDir, "tmp").mkdirs();
                new File(rootfsDir, "root").mkdirs();

                // 解压完成后删除大压缩包，节省手机存储
                targetTarball.delete();

                if (callback != null) {
                    callback.onProgress("容器就绪！", 100);
                    callback.onSuccess();
                }
            } catch (Throwable e) {
                Log.e(TAG, "Install rootfs failed", e);
                if (callback != null) callback.onError("解压容器失败: " + e.getMessage());
            }
        }).start();
    }

    /**
     * 复制并准备 PRoot 所需动态链接库与环境配置 (参考 DSH-Folk ensureRuntimeFiles)
     */
    public static synchronized void ensureRuntimeFiles(Context context) {
        File nativeLibDir = getNativeLibDir(context);
        File libDir = new File(context.getFilesDir(), "lib");
        libDir.mkdirs();
        getTmpDir(context).mkdirs();

        // Android APK 中只能打包 lib*.so 形式的文件名，
        // 而 proot 按照 ELF SONAME "libtalloc.so.2" 与 "libandroid-shmem.so" 进行动态绑定，
        // 必须在运行时将 nativeLibraryDir 的 .so 复制并映射为对应 SONAME。
        copyExec(new File(nativeLibDir, "libtalloc.so"), new File(libDir, "libtalloc.so.2"));
        copyExec(new File(nativeLibDir, "libandroidshmem.so"), new File(libDir, "libandroid-shmem.so"));

        // 确保外部存储与互通挂载点在 rootfs 内部存在
        File rootfsDir = getRootfsDir(context);
        File sdcardMount = new File(rootfsDir, "sdcard");
        if (!sdcardMount.exists()) sdcardMount.mkdirs();
        File storageMount = new File(rootfsDir, "storage/emulated/0");
        if (!storageMount.exists()) storageMount.mkdirs();
        File sharedMount = new File(rootfsDir, "root/shared");
        if (!sharedMount.exists()) sharedMount.mkdirs();

        ensureContainerDns(context);
        ensureContainerGroups(context);
    }

    public interface SpeedLogCallback {
        void onLog(String message);
    }

    public static String[] testAndSortCandidateUrls(String[] urls, SpeedLogCallback logCallback) {
        if (urls == null || urls.length <= 1) return urls;

        List<Pair<String, Long>> results = new ArrayList<>();
        for (String u : urls) {
            long latency = testUrlLatency(u);
            results.add(new Pair<>(u, latency >= 0 ? latency : 999999L));
        }

        Collections.sort(results, (a, b) -> Long.compare(a.second, b.second));
        String[] sorted = new String[urls.length];
        for (int i = 0; i < results.size(); i++) {
            sorted[i] = results.get(i).first;
        }
        if (logCallback != null && results.get(0).second < 999999L) {
            logCallback.onLog("⚡ 优选最快镜像: " + results.get(0).first + " (" + results.get(0).second + "ms)");
        }
        return sorted;
    }

    public static long testUrlLatency(String urlStr) {
        HttpURLConnection conn = null;
        try {
            long start = System.currentTimeMillis();
            URL u = new URL(urlStr);
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(1800);
            conn.setReadTimeout(1800);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code >= 200 && code < 400) {
                return System.currentTimeMillis() - start;
            }
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {}
            }
        }
        return -1;
    }

    private static void copyExec(File src, File dst) {
        if (!src.isFile()) return;
        if (dst.isFile() && dst.length() == src.length()) return;
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
            out.flush();
            dst.setReadable(true, false);
            dst.setExecutable(true, false);
        } catch (Throwable t) {
            Log.e(TAG, "copyExec failed: " + src + " -> " + dst, t);
        }
    }

    public static void ensureContainerDns(Context context) {
        try {
            File rootfs = getRootfsDir(context);
            File etc = new File(rootfs, "etc");
            if (!etc.isDirectory()) return;

            File rc = new File(etc, "resolv.conf");
            // 必须先删除旧的软链接或残留文件，防止在 guest 模式下软链接穿透或写失败
            try {
                if (rc.exists() || !rc.isFile()) {
                    rc.delete();
                }
            } catch (Throwable ignored) {}

            StringBuilder dnsContent = new StringBuilder();
            dnsContent.append("# Generated by PiMet - DNS & IPv4 Compatibility\n");
            // 解决 Node/Glibc 在国内网络环境下 IPv6 AAAA 查询超时与 ECONNABORTED 问题 (参考 DSHA ResolverConfig)
            dnsContent.append("options timeout:2 attempts:3 single-request-reopen no-aaaa\n");

            // 优先写入 Android 宿主当前 Wi-Fi/移动网络分配的物理 DNS
            try {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    android.net.Network active = cm.getActiveNetwork();
                    if (active != null) {
                        android.net.LinkProperties lp = cm.getLinkProperties(active);
                        if (lp != null) {
                            for (java.net.InetAddress addr : lp.getDnsServers()) {
                                String host = addr.getHostAddress();
                                if (host != null && !host.contains(":")) {
                                    dnsContent.append("nameserver ").append(host).append("\n");
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}

            // 极速公共 DNS 兜底
            dnsContent.append("nameserver 223.5.5.5\n");
            dnsContent.append("nameserver 119.29.29.29\n");
            dnsContent.append("nameserver 180.76.76.76\n");
            dnsContent.append("nameserver 8.8.8.8\n");
            dnsContent.append("nameserver 1.1.1.1\n");

            try (FileOutputStream fos = new FileOutputStream(rc)) {
                fos.write(dnsContent.toString().getBytes(StandardCharsets.UTF_8));
            }

            File hosts = new File(etc, "hosts");
            if (!hosts.exists() || hosts.length() == 0) {
                try (FileOutputStream fos = new FileOutputStream(hosts)) {
                    fos.write(("127.0.0.1\tlocalhost\n" +
                               "::1\tlocalhost ip6-localhost ip6-loopback\n").getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensureContainerDns failed", t);
        }
    }

    /**
     * 在容器内生成强锁定的 .npmrc 配置，杜绝任何阶段回退到外网 registry.npmjs.org
     */
    public static void ensureNpmConfig(Context context, String registry) {
        try {
            File rootfs = getRootfsDir(context);
            File rootHome = new File(rootfs, "root");
            rootHome.mkdirs();
            File npmrc = new File(rootHome, ".npmrc");

            String normalizedRegistry = registry.endsWith("/") ? registry : (registry + "/");

            StringBuilder sb = new StringBuilder();
            sb.append("registry=").append(normalizedRegistry).append("\n");
            sb.append("@earendil-works:registry=").append(normalizedRegistry).append("\n");
            sb.append("@agegr:registry=").append(normalizedRegistry).append("\n");
            sb.append("disturl=https://npmmirror.com/mirrors/node\n");
            sb.append("sass_binary_site=https://npmmirror.com/mirrors/node-sass\n");
            sb.append("electron_mirror=https://npmmirror.com/mirrors/electron/\n");
            sb.append("puppeteer_download_host=https://npmmirror.com/mirrors\n");
            sb.append("fetch-retries=5\n");
            sb.append("fetch-retry-mintimeout=20000\n");
            sb.append("fetch-retry-maxtimeout=120000\n");
            sb.append("fetch-timeout=300000\n");
            sb.append("audit=false\n");
            sb.append("fund=false\n");
            sb.append("progress=true\n");

            try (FileOutputStream fos = new FileOutputStream(npmrc)) {
                fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensureNpmConfig failed", t);
        }
    }

    private static void ensureContainerGroups(Context context) {
        try {
            File rootfs = getRootfsDir(context);
            File groupFile = new File(rootfs, "etc/group");
            if (!groupFile.exists() || groupFile.length() == 0) return;

            File procStatus = new File("/proc/self/status");
            if (!procStatus.exists()) return;

            String content = readFileToString(procStatus);
            String groupsLine = null;
            for (String line : content.split("\n")) {
                if (line.startsWith("Groups:")) {
                    groupsLine = line.substring(7).trim();
                    break;
                }
            }
            if (groupsLine == null || groupsLine.isEmpty()) return;

            String groupContent = readFileToString(groupFile);
            StringBuilder sb = new StringBuilder();
            for (String gidStr : groupsLine.split("\\s+")) {
                gidStr = gidStr.trim();
                if (gidStr.isEmpty()) continue;
                if (!groupContent.contains(":" + gidStr + ":")) {
                    String name = "3003".equals(gidStr) ? "inet" : "aid_" + gidStr;
                    sb.append(name).append(":x:").append(gidStr).append(":\n");
                }
            }

            if (sb.length() > 0) {
                try (FileOutputStream fos = new FileOutputStream(groupFile, true)) {
                    if (!groupContent.endsWith("\n")) {
                        fos.write("\n".getBytes(StandardCharsets.UTF_8));
                    }
                    fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (Throwable ignored) {}
    }

    private static String readFileToString(File file) {
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 组装进入 PRoot 容器的标准执行进程 ProcessBuilder
     */
    public static ProcessBuilder buildProotProcess(Context context, String workDir, List<String> guestCmd) {
        ensureRuntimeFiles(context);

        File nativeLibDir = getNativeLibDir(context);
        File libDir = new File(context.getFilesDir(), "lib");
        File rootfsDir = getRootfsDir(context);
        File tmpDir = getTmpDir(context);

        File proot = new File(nativeLibDir, "libproot.so");
        File prootLoader = new File(nativeLibDir, "libprootloader.so");
        File prootLoader32 = new File(nativeLibDir, "libprootloader32.so");

        List<String> argv = new ArrayList<>();
        argv.add(proot.getAbsolutePath());
        argv.add("--link2symlink");
        argv.add("-L");
        argv.add("--kill-on-exit");
        argv.add("-0");
        argv.add("-r");
        argv.add(rootfsDir.getAbsolutePath());

        // 标准内核与伪设备挂载
        argv.add("-b"); argv.add("/dev");
        argv.add("-b"); argv.add("/dev/urandom:/dev/random");
        argv.add("-b"); argv.add("/proc");
        argv.add("-b"); argv.add("/sys");
        argv.add("-b"); argv.add("/proc/self/fd:/dev/fd");

        // 手机公共存储挂载
        File sdcard = new File("/storage/emulated/0");
        if (sdcard.exists()) {
            argv.add("-b");
            argv.add("/storage/emulated/0:/sdcard");
            argv.add("-b");
            argv.add("/storage/emulated/0:/storage/emulated/0");
        }

        // 应用独立公共目录挂载（随时随地免权限互通）
        File appExternalDir = context.getExternalFilesDir(null);
        if (appExternalDir != null && appExternalDir.exists()) {
            argv.add("-b");
            argv.add(appExternalDir.getAbsolutePath() + ":/root/shared");
        }

        // 工作目录
        argv.add("-w");
        argv.add(workDir != null ? workDir : "/root");

        // 最终要执行的 guest 命令
        if (guestCmd != null && !guestCmd.isEmpty()) {
            argv.addAll(guestCmd);
        } else {
            argv.add("/bin/bash");
            argv.add("-l");
        }

        ProcessBuilder pb = new ProcessBuilder(argv);
        Map<String, String> env = pb.environment();
        env.put("PROOT_TMP_DIR", tmpDir.getAbsolutePath());
        if (prootLoader.exists()) env.put("PROOT_LOADER", prootLoader.getAbsolutePath());
        if (prootLoader32.exists()) env.put("PROOT_LOADER_32", prootLoader32.getAbsolutePath());
        env.put("LD_LIBRARY_PATH", libDir.getAbsolutePath() + ":" + nativeLibDir.getAbsolutePath());

        // Guest 环境
        env.put("HOME", "/root");
        env.put("USER", "root");
        env.put("LOGNAME", "root");
        env.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        env.put("TERM", "xterm-256color");
        env.put("LANG", "C.UTF-8");
        env.put("TMPDIR", "/tmp");
        env.put("DEBIAN_FRONTEND", "noninteractive");
        env.put("PORT", String.valueOf(com.xm486.pimet.PiMetConfig.getWebPort(context)));
        com.xm486.pimet.PiMetConfig.injectEnvironment(context, env);

        return pb;
    }

    private interface DownloadProgress {
        void onProgress(int p);
    }

    private static void downloadWithResume(String urlStr, File target, DownloadProgress cb) throws Exception {
        long existingLength = target.exists() ? target.length() : 0;
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);

        if (existingLength > 0) {
            conn.setRequestProperty("Range", "bytes=" + existingLength + "-");
        }

        int code = conn.getResponseCode();
        if (code >= 300 && code < 400) {
            String redirectUrl = conn.getHeaderField("Location");
            if (redirectUrl != null) {
                conn.disconnect();
                downloadWithResume(redirectUrl, target, cb);
                return;
            }
        }

        if (code == 416) {
            conn.disconnect();
            if (cb != null) cb.onProgress(100);
            return;
        }

        boolean append = false;
        long totalLength = 0;
        if (code == 206) {
            append = true;
            totalLength = existingLength + conn.getContentLengthLong();
        } else if (code == 200) {
            append = false;
            existingLength = 0;
            totalLength = conn.getContentLengthLong();
        } else {
            conn.disconnect();
            throw new Exception("HTTP " + code);
        }

        byte[] buf = new byte[65536];
        long downloaded = existingLength;
        int lastPercent = 0;
        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             FileOutputStream fos = new FileOutputStream(target, append)) {
            int read;
            while ((read = in.read(buf)) != -1) {
                fos.write(buf, 0, read);
                downloaded += read;
                if (totalLength > 0) {
                    int percent = (int) (downloaded * 100 / totalLength);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        if (cb != null) cb.onProgress(percent);
                    }
                }
            }
            fos.flush();
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 将外部选择的 Uri 文件或图片拷贝到 PRoot 容器指定目录 (例如 /root)
     */
    public static String copyUriToContainer(Context context, Uri uri, String destSubPath) {
        if (context == null || uri == null) return null;
        ContentResolver resolver = context.getContentResolver();
        String displayName = null;
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex != -1) {
                    displayName = cursor.getString(nameIndex);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to resolve display name for uri: " + uri, t);
        }

        if (displayName == null || displayName.trim().isEmpty()) {
            displayName = "file_" + System.currentTimeMillis();
        }

        File rootfsDir = getRootfsDir(context);
        File targetDir = new File(rootfsDir, destSubPath != null ? destSubPath : "root");
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }
        File destFile = new File(targetDir, displayName);

        try (InputStream in = resolver.openInputStream(uri);
             FileOutputStream out = new FileOutputStream(destFile)) {
            if (in == null) return null;
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
            }
            return displayName;
        } catch (Throwable t) {
            Log.e(TAG, "copyUriToContainer error", t);
            return null;
        }
    }
}
