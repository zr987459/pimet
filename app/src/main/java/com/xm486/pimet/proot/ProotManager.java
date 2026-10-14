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
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
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

    // 官方与镜像 Rootfs 线路 (涵盖国内多条高速直连 CDN 节点与官方直连源)
    public static final String[] ROOTFS_MIRROR_PREFIXES = {
            "https://gh-proxy.com/",
            "https://ghfast.top/",
            "https://ghproxy.net/",
            "https://github.com/"
    };

    public static final String[] ROOTFS_MIRROR_NAMES = {
            "gh-proxy (国内高速CDN 1)",
            "ghfast (国内高速CDN 2)",
            "ghproxy.net (国内镜像 3)",
            "GitHub (官方直连/梯子)"
    };

    public static final String GITHUB_ROOTFS_ARM64_PATH = "https://github.com/zr987459/pimet/releases/download/runtime-latest/rootfs.tar.gz";
    public static final String GITHUB_ROOTFS_X86_64_PATH = "https://github.com/zr987459/pimet/releases/download/runtime-latest/rootfs-x86_64.tar.gz";

    public static String getFullRootfsUrl(String mirrorPrefix, boolean isX86) {
        String path = isX86 ? GITHUB_ROOTFS_X86_64_PATH : GITHUB_ROOTFS_ARM64_PATH;
        if ("https://github.com/".equals(mirrorPrefix)) {
            return path;
        }
        return mirrorPrefix + path;
    }

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
     * 端口存活检测 (超时设为 1200ms，适配低端设备与冷启动高负载)
     */
    public static boolean isPiWebPortAlive(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1200);
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
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(2000);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            return code >= 200 && code < 400;
        } catch (Throwable ignored) {
            try {
                URL url2 = new URL("http://127.0.0.1:" + port + "/");
                HttpURLConnection conn2 = (HttpURLConnection) url2.openConnection();
                conn2.setConnectTimeout(1500);
                conn2.setReadTimeout(2000);
                conn2.setRequestMethod("GET");
                conn2.setInstanceFollowRedirects(false);
                int code2 = conn2.getResponseCode();
                return code2 >= 200 && code2 < 400;
            } catch (Throwable ignored2) {
                return false;
            }
        }
    }

    /**
     * 预热并确认首页能出首字节：pi-web 的 Service Worker 对页面导航只等 8 秒，冷启动时首页 SSR 常超过 8 秒，
     * WebView 直接加载就会先闪「Pi Web is offline」。这里由原生先请求一次首页（最多等 25 秒），
     * 首页能响应后再让 WebView 加载，就不会再看到离线页。
     */
    public static boolean warmPiWebPage(int port) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://127.0.0.1:" + port + "/");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(25000);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            return code >= 200 && code < 400;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
        }
    }

    public static boolean isPiWebHttpReady() {
        return isPiWebHttpReady(PI_WEB_PORT);
    }

    /**
     * 兼容修复：上游 @earendil-works/pi-codemode 1.2.0 (2026-10-13) 移除了 MCP_TYPESCRIPT_PREAMBLE 导出，
     * 而 pi-web 依赖的 pi-coding-agent 1.1.x 仍在引用，导致 Next.js 服务端报
     * "does not provide an export named 'MCP_TYPESCRIPT_PREAMBLE'"。
     * 仅当 pi-web 内的 pi-coding-agent 仍引用该导出、且 pi-codemode 缺失该导出时，才把 pi-web 内的 pi-codemode 降回 1.1.0。
     * 返回值：0 无需修复；10 已修复；其他为失败。
     */
    public static int fixPiCodemodeCompat(Context context, String registry) {
        String reg = (registry == null || registry.isEmpty()) ? "https://registry.npmmirror.com/" : registry;
        if (!reg.endsWith("/")) reg = reg + "/";
        String script = "REG=\"" + reg + "\"\n" +
                "P=\"\"; for b in /usr/local/lib/node_modules/@agegr/pi-web /usr/lib/node_modules/@agegr/pi-web; do [ -d \"$b\" ] && P=\"$P $b\"; done\n" +
                "[ -z \"$P\" ] && exit 0\n" +
                "CA=$(find $P -type d -path '*node_modules/@earendil-works/pi-coding-agent' 2>/dev/null)\n" +
                "[ -z \"$CA\" ] && exit 0\n" +
                "grep -rqs --include=*.js MCP_TYPESCRIPT_PREAMBLE $CA || exit 0\n" +
                "NEED=\"\"\n" +
                "for d in $(find $P -type d -path '*node_modules/@earendil-works/pi-codemode' 2>/dev/null); do\n" +
                "  grep -qs MCP_TYPESCRIPT_PREAMBLE \"$d/dist/declarations.js\" || NEED=\"$NEED $d\"\n" +
                "done\n" +
                "[ -z \"$NEED\" ] && exit 0\n" +
                "cd /tmp && rm -f earendil-works-pi-codemode-1.1.0.tgz\n" +
                "npm pack @earendil-works/pi-codemode@1.1.0 --registry=\"$REG\" >/dev/null 2>&1 || exit 2\n" +
                "for d in $NEED; do\n" +
                "  rm -rf \"$d\" && mkdir -p \"$d\" && tar xzf /tmp/earendil-works-pi-codemode-1.1.0.tgz -C \"$d\" --strip-components=1 || exit 3\n" +
                "done\n" +
                "exit 10\n";
        return executeCommandSync(context, script);
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

        // 1. 优先尝试系统底层 du 命令 (Toybox du，毫秒级快速统计真实磁盘占用且防符号链接死循环)
        Process process = null;
        try {
            // stderr 丢弃：大量「无权限」报错若不读取会塞满管道导致 du 卡死
            ProcessBuilder pb = new ProcessBuilder("du", "-sk", dir.getAbsolutePath());
            pb.redirectError(ProcessBuilder.Redirect.to(new File("/dev/null")));
            process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length > 0) {
                        long kb = Long.parseLong(parts[0]);
                        if (kb > 0) {
                            return kb * 1024L;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (process != null) {
                try { process.destroy(); } catch (Throwable ignored) {}
            }
        }

        // 2. 备选方案：防符号链接循环、防无限递归的安全 Java 递归计算
        return calculateDirSizeSafe(dir, 0);
    }

    private static long calculateDirSizeSafe(File dir, int depth) {
        if (dir == null || !dir.exists() || depth > 18) return 0;
        if (isSymbolicLink(dir)) {
            return 0;
        }
        if (!dir.isDirectory()) {
            return dir.length();
        }

        long total = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (isSymbolicLink(file)) {
                    total += file.length();
                } else if (file.isDirectory()) {
                    total += calculateDirSizeSafe(file, depth + 1);
                } else {
                    total += file.length();
                }
            }
        }
        return total;
    }

    public static boolean isSymbolicLink(File file) {
        if (file == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                return Files.isSymbolicLink(file.toPath());
            }
            StructStat stat = Os.lstat(file.getAbsolutePath());
            return OsConstants.S_ISLNK(stat.st_mode);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        if (digitGroups >= units.length) digitGroups = units.length - 1;
        if (digitGroups < 0) digitGroups = 0;
        return String.format(java.util.Locale.US, "%.1f %s", bytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    /** 递归删除，绝不跟随符号链接（容器内指向 /sdcard 等宿主路径的链接只删链接本身）。 */
    public static boolean deleteRecursive(File f) {
        return com.xm486.pimet.util.SafeFiles.deleteTree(f);
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
                List<String> rawUrlList = new ArrayList<>();
                String chosenMirror = com.xm486.pimet.PiMetConfig.getRootfsUrl(context);
                if (!com.xm486.pimet.PiMetConfig.ROOTFS_MIRROR_AUTO.equals(chosenMirror)) {
                    // 用户在设置中手动指定了固定镜像源，直接优先置顶该源
                    rawUrlList.add(getFullRootfsUrl(chosenMirror, isX86));
                }
                for (String pfx : ROOTFS_MIRROR_PREFIXES) {
                    String full = getFullRootfsUrl(pfx, isX86);
                    if (!rawUrlList.contains(full)) {
                        rawUrlList.add(full);
                    }
                }
                String[] rawUrls = rawUrlList.toArray(new String[0]);

                String[] urls;
                if (com.xm486.pimet.PiMetConfig.ROOTFS_MIRROR_AUTO.equals(chosenMirror)) {
                    if (callback != null) callback.onProgress("⚡ 正在对各路镜像进行真吞吐量竞速优选...", 3);
                    urls = testAndSortCandidateUrls(rawUrls, msg -> {
                        if (callback != null) callback.onProgress(msg, 5);
                    });
                } else {
                    urls = rawUrls;
                }
                File targetTarball = new File(tmpDir, "rootfs.tar.gz");

                // 国内直连路线：GitHub 各镜像都慢（< 1 MB/s，常见于国内不开代理）时，
                // 改为从阿里云/清华/中科大拉 Ubuntu 底包 + npmmirror 拉 Node，在手机上现装，全程不经过 GitHub。
                boolean autoMode = com.xm486.pimet.PiMetConfig.ROOTFS_MIRROR_AUTO.equals(chosenMirror);
                boolean domesticTried = false;
                if (autoMode && lastBestMirrorSpeedBps < 1024L * 1024L) {
                    domesticTried = true;
                    if (callback != null) callback.onProgress("GitHub 线路太慢，切换国内源现装 Linux 容器...", 5);
                    if (buildDomesticRootfs(context, isX86, rootfsDir, tmpDir, callback)) {
                        if (callback != null) { callback.onProgress("容器就绪！", 100); callback.onSuccess(); }
                        return;
                    }
                    deleteRecursive(rootfsDir);
                    rootfsDir.mkdirs();
                    if (callback != null) callback.onProgress("国内源安装未完成，回退 GitHub 镜像...", 5);
                }

                boolean downloadSuccess = false;
                for (String urlStr : urls) {
                    try {
                        if (callback != null) callback.onProgress("连接容器镜像源: " + urlStr, 5);
                        downloadWithResume(urlStr, targetTarball, (percent) -> {
                            if (callback != null) {
                                callback.onProgress("正在下载 Linux 容器系统 (" + percent + "%)", percent * 70 / 100);
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

                if ((!downloadSuccess || !targetTarball.exists()) && !domesticTried) {
                    targetTarball.delete();
                    if (callback != null) callback.onProgress("GitHub 镜像下载失败，改用国内源现装 Linux 容器...", 5);
                    if (buildDomesticRootfs(context, isX86, rootfsDir, tmpDir, callback)) {
                        if (callback != null) { callback.onProgress("容器就绪！", 100); callback.onSuccess(); }
                        return;
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

    // ================= 国内源现装容器 =================
    private static final String[] UBUNTU_BASE_MIRRORS = {
            "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release/",
            "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/",
            "https://mirrors.ustc.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/",
            "https://mirrors.huaweicloud.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release/"
    };
    private static final String[] UBUNTU_BASE_VERSIONS = {"24.04.5", "24.04.4", "24.04.3"};
    private static final String NODE_FALLBACK_VERSION = "v24.9.0";

    /**
     * 不经过 GitHub：Ubuntu 24.04 base（国内镜像）+ Node 24（npmmirror）+ apt 换阿里云装 git。
     * pi 与 pi-web 由后续流程通过 npmmirror 安装。成功返回 true。
     */
    public static boolean buildDomesticRootfs(Context context, boolean isX86, File rootfsDir, File tmpDir, InstallCallback cb) {
        File base = new File(tmpDir, "ubuntu-base.tar.gz");
        File nodeTar = new File(tmpDir, "node.tar.gz");
        try {
            rootfsDir.mkdirs();
            tmpDir.mkdirs();
            String arch = isX86 ? "amd64" : "arm64";

            // 1) Ubuntu 底包：先探测哪个版本存在，再对各镜像竞速
            List<String> baseUrls = new ArrayList<>();
            String ver = null;
            for (String v : UBUNTU_BASE_VERSIONS) {
                String probe = UBUNTU_BASE_MIRRORS[0] + "ubuntu-base-" + v + "-base-" + arch + ".tar.gz";
                if (headOk(probe) || headOk(UBUNTU_BASE_MIRRORS[1] + "ubuntu-base-" + v + "-base-" + arch + ".tar.gz")) { ver = v; break; }
            }
            if (ver == null) ver = UBUNTU_BASE_VERSIONS[0];
            for (String m : UBUNTU_BASE_MIRRORS) baseUrls.add(m + "ubuntu-base-" + ver + "-base-" + arch + ".tar.gz");
            if (cb != null) cb.onProgress("⚡ 正在挑选最快的国内镜像...", 6);
            String[] sortedBase = testAndSortCandidateUrls(baseUrls.toArray(new String[0]), null);
            if (!downloadFromAny(sortedBase, base, 10 * 1024 * 1024, "Ubuntu 底包", 8, 30, cb)) return false;

            if (cb != null) cb.onProgress("正在解压 Ubuntu 底包...", 32);
            TarGzipExtractor.extractRootfs(base, rootfsDir);
            base.delete();
            for (String d : new String[]{"dev", "proc", "sys", "tmp", "root", "usr/local"}) new File(rootfsDir, d).mkdirs();

            // 2) Node.js 24（npmmirror）
            String nodeVer = latestNodeV24();
            String nodeArch = isX86 ? "x64" : "arm64";
            String nodeName = "node-" + nodeVer + "-linux-" + nodeArch + ".tar.gz";
            String[] nodeUrls = {
                    "https://registry.npmmirror.com/-/binary/node/" + nodeVer + "/" + nodeName,
                    "https://cdn.npmmirror.com/binaries/node/" + nodeVer + "/" + nodeName,
                    "https://mirrors.huaweicloud.com/nodejs/" + nodeVer + "/" + nodeName,
                    "https://mirrors.tuna.tsinghua.edu.cn/nodejs-release/" + nodeVer + "/" + nodeName,
                    "https://mirrors.aliyun.com/nodejs-release/" + nodeVer + "/" + nodeName
            };
            if (!downloadFromAny(nodeUrls, nodeTar, 10 * 1024 * 1024, "Node.js " + nodeVer, 40, 62, cb)) return false;
            if (cb != null) cb.onProgress("正在安装 Node.js...", 64);
            try (InputStream in = new FileInputStream(nodeTar)) {
                TarGzipExtractor.extractAuto(in, new File(rootfsDir, "usr/local"), 1);
            }
            nodeTar.delete();
            for (String b : new String[]{"usr/local/bin/node"}) {
                File f = new File(rootfsDir, b);
                if (!f.exists()) return false;
                f.setExecutable(true, false);
            }

            // 3) apt 换阿里云源，补装 git / ca-certificates / curl（失败不致命）
            writeDomesticAptConfig(rootfsDir, isX86, true);
            ensureContainerDns(context);
            // git/curl 等基础工具放到后台装，不挡容器就绪和 Pi-Web 安装（pi-web 本身不依赖它们）
            final Context appCtx = context.getApplicationContext();
            final File rootfsFinal = rootfsDir;
            final boolean x86Final = isX86;
            new Thread(() -> {
                try {
                    int rc = runInContainer(appCtx,
                            "export DEBIAN_FRONTEND=noninteractive; apt-get update -o Acquire::Retries=2 && " +
                            "apt-get install -y --no-install-recommends -o Acquire::Retries=2 git ca-certificates curl xz-utils procps && " +
                            "apt-get clean && rm -rf /var/lib/apt/lists/*",
                            20 * 60 * 1000L, null);
                    Log.i(TAG, "background apt install rc=" + rc);
                    writeDomesticAptConfig(rootfsFinal, x86Final, false);
                    if (rc == 0) new File(rootfsFinal, "etc/pimet-base-tools-ok").createNewFile();
                } catch (Throwable t) {
                    Log.w(TAG, "background apt failed", t);
                }
            }, "pimet-apt-bg").start();

            new File(rootfsDir, "etc/pimet-domestic-rootfs").createNewFile();
            if (cb != null) cb.onProgress("国内源容器安装完成", 95);
            return isRootfsInstalled(context);
        } catch (Throwable t) {
            Log.w(TAG, "buildDomesticRootfs failed", t);
            if (cb != null) cb.onProgress("国内源安装出错: " + t.getMessage(), 5);
            return false;
        } finally {
            base.delete();
            nodeTar.delete();
        }
    }

    private static boolean downloadFromAny(String[] urls, File target, long minBytes, String label,
                                           int pFrom, int pTo, InstallCallback cb) {
        for (String u : urls) {
            if (u == null) continue;
            try {
                if (cb != null) cb.onProgress("下载 " + label + ": " + hostOf(u), pFrom);
                target.delete();
                downloadWithResume(u, target, percent -> {
                    if (cb != null) cb.onProgress("正在下载 " + label + " (" + percent + "%)", pFrom + percent * (pTo - pFrom) / 100);
                });
                if (target.exists() && target.length() >= minBytes) return true;
            } catch (Throwable t) {
                Log.w(TAG, "download failed: " + u, t);
            }
        }
        return false;
    }

    private static String hostOf(String u) {
        try { return new URL(u).getHost(); } catch (Throwable t) { return u; }
    }

    private static boolean headOk(String u) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            return code >= 200 && code < 400;
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) {}
        }
    }

    /** 从 npmmirror 取 Node 24 最新版本号，失败用内置版本 */
    private static String latestNodeV24() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("https://registry.npmmirror.com/-/binary/node/latest-v24.x/").openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) sb.append(l);
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("node-(v24\\.\\d+\\.\\d+)-linux").matcher(sb);
            String best = null;
            int[] bestN = null;
            while (m.find()) {
                String v = m.group(1);
                String[] p = v.substring(1).split("\\.");
                int[] n = {Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])};
                if (bestN == null || n[1] > bestN[1] || (n[1] == bestN[1] && n[2] > bestN[2])) { best = v; bestN = n; }
            }
            return best != null ? best : NODE_FALLBACK_VERSION;
        } catch (Throwable t) {
            return NODE_FALLBACK_VERSION;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private static void writeDomesticAptConfig(File rootfsDir, boolean isX86, boolean minimal) {
        try {
            File srcDir = new File(rootfsDir, "etc/apt/sources.list.d");
            srcDir.mkdirs();
            boolean haveCa = new File(rootfsDir, "etc/ssl/certs/ca-certificates.crt").exists();
            String scheme = haveCa ? "https" : "http";
            String repo = isX86 ? "ubuntu" : "ubuntu-ports";
            String uri = scheme + "://mirrors.aliyun.com/" + repo + "/";
            // minimal：首装只拉 main 索引（约 1–2MB），git/curl/ca-certificates 都在 main；装完再写回完整源
            String suites = minimal ? "noble noble-updates" : "noble noble-updates noble-security";
            String comps = minimal ? "main" : "main restricted universe multiverse";
            String content = "Types: deb\nURIs: " + uri + "\nSuites: " + suites + "\n" +
                    "Components: " + comps + "\nSigned-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n";
            writeText(new File(srcDir, "ubuntu.sources"), content);
            File legacy = new File(rootfsDir, "etc/apt/sources.list");
            if (legacy.exists()) writeText(legacy, "# PiMet: see /etc/apt/sources.list.d/ubuntu.sources\n");
            File conf = new File(rootfsDir, "etc/apt/apt.conf.d");
            conf.mkdirs();
            writeText(new File(conf, "99pimet"), "APT::Sandbox::User \"root\";\nAcquire::Languages \"none\";\nAcquire::GzipIndexes \"true\";\nAcquire::http::Timeout \"20\";\nAcquire::https::Timeout \"20\";\nDPkg::Options { \"--force-confdef\"; \"--force-confold\"; };\n");
        } catch (Throwable t) {
            Log.w(TAG, "writeDomesticAptConfig failed", t);
        }
    }

    private static void writeText(File f, String text) throws java.io.IOException {
        try (FileOutputStream fos = new FileOutputStream(f, false)) {
            fos.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private interface LineSink { void onLine(String line); }

    /** 在容器内执行命令并持续读取输出（避免缓冲区写满卡死），带超时 */
    private static int runInContainer(Context context, String cmd, long timeoutMs, LineSink sink) {
        Process p = null;
        try {
            ProcessBuilder pb = buildProotProcess(context, "/root", Arrays.asList("/bin/bash", "-c", cmd));
            pb.redirectErrorStream(true);
            p = pb.start();
            final Process proc = p;
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = r.readLine()) != null) {
                        if (sink != null) sink.onLine(l);
                    }
                } catch (Throwable ignored) {}
            }, "proot-out");
            reader.start();
            if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroy();
                return -2;
            }
            reader.join(2000);
            return p.exitValue();
        } catch (Throwable t) {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
            return -1;
        }
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

        // 确保用户工作区目录 root/pi-cwd 存在并自愈历史工程目录，杜绝 Directory does not exist 异常
        File piCwd = new File(rootfsDir, "root/pi-cwd");
        if (!piCwd.exists()) piCwd.mkdirs();
        com.xm486.pimet.BackupManager.ensureReferencedProjectDirsExist(rootfsDir);

        ensureContainerDns(context);
        ensureContainerGroups(context);
    }

    public interface SpeedLogCallback {
        void onLog(String message);
    }

    public static class MirrorSpeedResult {
        public String url;
        public long speedBps; // 每秒字节数
        public long latencyMs; // 握手延迟
        public boolean success;

        public MirrorSpeedResult(String url, long speedBps, long latencyMs, boolean success) {
            this.url = url;
            this.speedBps = speedBps;
            this.latencyMs = latencyMs;
            this.success = success;
        }
    }

    /**
     * 并发对所有镜像进行微片段真实吞吐量测速 (下载 128KB 真实数据，测出真实 MB/s)
     */
    public static MirrorSpeedResult testUrlRealThroughput(String urlStr) {
        HttpURLConnection conn = null;
        try {
            long start = System.currentTimeMillis();
            URL u = new URL(urlStr);
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36");
            conn.setRequestProperty("Range", "bytes=0-524287"); // 512 KB，测带宽而不只是延迟
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(4000);
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();
            if (code == 200 || code == 206) {
                long latency = System.currentTimeMillis() - start;
                try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
                    byte[] buf = new byte[16384];
                    int totalRead = 0;
                    int r;
                    while ((r = in.read(buf)) != -1) {
                        totalRead += r;
                    }
                    long totalDuration = System.currentTimeMillis() - start;
                    if (totalDuration <= 0) totalDuration = 1;
                    long speedBps = (totalRead * 1000L) / totalDuration;
                    return new MirrorSpeedResult(urlStr, speedBps, latency, true);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {}
            }
        }
        return new MirrorSpeedResult(urlStr, 0, 99999L, false);
    }

    /** 最近一次镜像竞速的最高吞吐量（B/s），0 表示全部失败 */
    public static volatile long lastBestMirrorSpeedBps = 0;

    public static String[] testAndSortCandidateUrls(String[] urls, SpeedLogCallback logCallback) {
        if (urls == null || urls.length <= 1) return urls;

        List<java.util.concurrent.Future<MirrorSpeedResult>> futures = new ArrayList<>();
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(Math.min(urls.length, 6));

        for (String u : urls) {
            futures.add(executor.submit(() -> testUrlRealThroughput(u)));
        }
        executor.shutdown();

        List<MirrorSpeedResult> speedResults = new ArrayList<>();
        for (java.util.concurrent.Future<MirrorSpeedResult> f : futures) {
            try {
                speedResults.add(f.get());
            } catch (Throwable ignored) {}
        }

        // 优先按吞吐量从大到小排序，若均无速度则按延迟从小到大排序
        Collections.sort(speedResults, (a, b) -> {
            if (a.success != b.success) return a.success ? -1 : 1;
            if (a.speedBps != b.speedBps) return Long.compare(b.speedBps, a.speedBps);
            return Long.compare(a.latencyMs, b.latencyMs);
        });

        String[] sorted = new String[urls.length];
        for (int i = 0; i < speedResults.size(); i++) {
            sorted[i] = speedResults.get(i).url;
        }

        lastBestMirrorSpeedBps = (!speedResults.isEmpty() && speedResults.get(0).success) ? speedResults.get(0).speedBps : 0;
        if (logCallback != null && !speedResults.isEmpty() && speedResults.get(0).success) {
            MirrorSpeedResult top = speedResults.get(0);
            double kb = top.speedBps / 1024.0;
            String speedStr = kb > 1024 ? String.format(java.util.Locale.US, "%.1f MB/s", kb / 1024.0) : String.format(java.util.Locale.US, "%.0f KB/s", kb);
            logCallback.onLog("⚡ 优选最高吞吐量镜像: " + top.url + " (" + speedStr + " / " + top.latencyMs + "ms)");
        }
        return sorted;
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
     * 在 PRoot 容器内同步执行命令
     */
    public static int executeCommandSync(Context context, String cmd) {
        try {
            List<String> command = Arrays.asList("/bin/bash", "-c", cmd);
            ProcessBuilder pb = buildProotProcess(context, "/root", command);
            Process p = pb.start();
            return p.waitFor();
        } catch (Throwable ignored) {
            return -1;
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
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(12000);

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
        int lastPercent = -1;
        long lastProgressTime = System.currentTimeMillis();
        long startTime = System.currentTimeMillis();
        long intervalStart = startTime;
        long intervalDownloaded = 0;

        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             FileOutputStream fos = new FileOutputStream(target, append)) {
            int read;
            while ((read = in.read(buf)) != -1) {
                fos.write(buf, 0, read);
                downloaded += read;
                intervalDownloaded += read;
                long now = System.currentTimeMillis();

                // 动态速度检测与低速熔断机制：若已开始下载超过 6 秒且最近速率持续低于 120 KB/s，主动断开熔断换线！
                if (now - intervalStart >= 5000) {
                    long speedBps = (intervalDownloaded * 1000L) / (now - intervalStart);
                    if (now - startTime > 6000 && speedBps < 120 * 1024) { // 低于 120 KB/s
                        Log.w(TAG, "Download speed too low (" + (speedBps / 1024) + " KB/s), aborting to fallback mirror...");
                        throw new Exception("下载速率过低 (" + (speedBps / 1024) + " KB/s)，自动熔断切换备用镜像线路");
                    }
                    intervalStart = now;
                    intervalDownloaded = 0;
                }

                if (totalLength > 0) {
                    int percent = (int) (downloaded * 100 / totalLength);
                    if (percent != lastPercent || (now - lastProgressTime > 300)) {
                        lastPercent = percent;
                        lastProgressTime = now;
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
