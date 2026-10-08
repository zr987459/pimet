package com.xm486.pimet.proot;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
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
            "https://github.com/IPF-Sinon/DSH-Folk/releases/download/runtime-latest/rootfs.tar.gz"
    };

    private static final String[] ROOTFS_URLS_X86_64 = {
            "https://ghfast.top/https://github.com/IPF-Sinon/DSH-Folk/releases/download/runtime-latest/rootfs-x86_64.tar.gz",
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
     * 判定 Pi-Web 是否已在容器内就绪
     */
    public static boolean isPiWebInstalled(Context context) {
        File rootfs = getRootfsDir(context);
        File piWebJs = new File(rootfs, "usr/local/lib/node_modules/@agegr/pi-web/bin/pi-web.js");
        File piWebBin = new File(rootfs, "usr/local/bin/pi-web");
        return (piWebJs.exists() && piWebJs.length() > 50) || (piWebBin.exists());
    }

    /**
     * 端口 30141 存活检测
     */
    public static boolean isPiWebPortAlive() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", PI_WEB_PORT), 400);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
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
                String[] urls = isX86 ? ROOTFS_URLS_X86_64 : ROOTFS_URLS_ARM64;
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
     * 组装进入 PRoot 容器的标准执行进程 ProcessBuilder
     */
    public static ProcessBuilder buildProotProcess(Context context, String workDir, List<String> guestCmd) {
        File nativeLibDir = getNativeLibDir(context);
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

        // 手机公共存储挂载 (如果可访问)
        File sdcard = new File("/storage/emulated/0");
        if (sdcard.exists() && sdcard.canRead()) {
            argv.add("-b");
            argv.add("/storage/emulated/0:/sdcard");
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
        env.put("LD_LIBRARY_PATH", nativeLibDir.getAbsolutePath());

        // Guest 环境
        env.put("HOME", "/root");
        env.put("USER", "root");
        env.put("LOGNAME", "root");
        env.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        env.put("TERM", "xterm-256color");
        env.put("LANG", "C.UTF-8");
        env.put("PORT", String.valueOf(PI_WEB_PORT));

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
}
