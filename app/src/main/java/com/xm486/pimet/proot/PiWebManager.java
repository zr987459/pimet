package com.xm486.pimet.proot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.xm486.pimet.PiMetConfig;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Pi-Web 与 Pi 核心管理类：负责在 PRoot 容器内拉取 npm 模块与守护后台进程
 */
public final class PiWebManager {

    private static final String TAG = "PiWebManager";
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static volatile Process daemonProcess;

    public interface StateListener {
        void onLog(String log);
        default void onProgress(String message, int percent) {}
        void onStarted();
        void onError(String error);
    }

    private PiWebManager() {}

    /**
     * 重启 Pi-Web（安全杀掉旧进程并释放端口后拉起新实例）
     */
    public static void restart(Context context, StateListener listener) {
        startInternal(context, true, listener);
    }

    /**
     * 启动或部署 Pi-Web
     */
    public static void startOrDeploy(Context context, StateListener listener) {
        startInternal(context, false, listener);
    }

    private static void startInternal(Context context, boolean isRestart, StateListener listener) {
        Handler mainHandler = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                if (!ProotManager.isRootfsInstalled(context)) {
                    mainHandler.post(() -> {
                        if (listener != null) listener.onLog("正在准备 Linux 容器系统...\n");
                    });
                    ProotManager.installRootfs(context, new ProotManager.InstallCallback() {
                        @Override
                        public void onProgress(String message, int percent) {
                            mainHandler.post(() -> {
                                if (listener != null) listener.onProgress(message, percent);
                            });
                        }

                        @Override
                        public void onSuccess() {
                            // 递归拉起 Pi-Web
                            startInternal(context, isRestart, listener);
                        }

                        @Override
                        public void onError(String error) {
                            mainHandler.post(() -> {
                                if (listener != null) listener.onError("初始化容器失败: " + error);
                            });
                        }
                    });
                    return;
                }

                int port = PiMetConfig.getWebPort(context);

                // 若非强制重启模式且服务已就绪响应 HTTP，直接成功返回
                if (!isRestart && ProotManager.isPiWebHttpReady(port)) {
                    mainHandler.post(() -> {
                        if (listener != null) {
                            listener.onLog("\u001B[32m✔ Pi-Web 已在本地端口 " + port + " 正常运行中\u001B[0m\n");
                            listener.onStarted();
                        }
                    });
                    return;
                }

                String registry = PiMetConfig.getNpmRegistry(context);
                ProotManager.ensureContainerDns(context);
                ProotManager.ensureNpmConfig(context, registry);

                // 如果未安装 pi-web，在标准 Linux 容器内执行 npm install
                if (!ProotManager.isPiWebInstalled(context)) {
                    mainHandler.post(() -> {
                        if (listener != null) listener.onLog("\u001B[33m• 正在从镜像源安装 Pi-Web 与 Pi 核心套件 (" + registry + ")...\u001B[0m\n");
                    });

                    String normalizedRegistry = registry.endsWith("/") ? registry : (registry + "/");

                    List<String> installCmd = Arrays.asList(
                            "/bin/bash", "-c",
                            "export npm_config_registry=\"" + normalizedRegistry + "\" && " +
                            "export npm_config_fetch_retries=5 && " +
                            "export npm_config_fetch_timeout=300000 && " +
                            "npm config set registry \"" + normalizedRegistry + "\" --global 2>/dev/null || true; " +
                            "npm install -g --loglevel=info --ignore-scripts --no-audit --no-fund @earendil-works/pi-coding-agent @agegr/pi-web"
                    );

                    ProcessBuilder pb = ProotManager.buildProotProcess(context, "/root", installCmd);
                    pb.redirectErrorStream(true);
                    Process process = pb.start();

                    try (InputStream in = process.getInputStream()) {
                        byte[] buffer = new byte[1024];
                        int read;
                        StringBuilder lineBuf = new StringBuilder();
                        while ((read = in.read(buffer)) != -1) {
                            String chunk = new String(buffer, 0, read, StandardCharsets.UTF_8);
                            for (int i = 0; i < chunk.length(); i++) {
                                char c = chunk.charAt(i);
                                if (c == '\n' || c == '\r') {
                                    if (lineBuf.length() > 0) {
                                        final String line = lineBuf.toString();
                                        lineBuf.setLength(0);
                                        mainHandler.post(() -> {
                                            if (listener != null) {
                                                listener.onLog(line + "\n");
                                                if (line.contains("http fetch") || line.contains("reify") || line.contains("idealTree")) {
                                                    String clean = line.replace("npm ", "").trim();
                                                    if (clean.length() > 50) clean = clean.substring(0, 50) + "...";
                                                    listener.onProgress(clean, -1);
                                                }
                                            }
                                        });
                                    }
                                } else {
                                    lineBuf.append(c);
                                }
                            }
                        }
                        if (lineBuf.length() > 0) {
                            final String remaining = lineBuf.toString();
                            mainHandler.post(() -> {
                                if (listener != null) listener.onLog(remaining + "\n");
                            });
                        }
                    }

                    int exitCode = process.waitFor();
                    if (exitCode != 0) {
                        mainHandler.post(() -> {
                            if (listener != null) listener.onError("npm 安装失败，退出码: " + exitCode);
                        });
                        return;
                    }
                }

                // 防御 Service Worker 离线误判与过早超时
                ProotManager.optimizePiWebOffline(context);

                // 彻底停止旧服务并确保端口释放
                mainHandler.post(() -> {
                    if (listener != null) {
                        listener.onLog(isRestart ? "\u001B[33m• 正在终止旧服务并彻底释放端口 " + port + "...\u001B[0m\n" : "\u001B[36m• 正在准备端口 " + port + "...\u001B[0m\n");
                        listener.onProgress(isRestart ? "正在清理旧实例与端口..." : "正在释放端口...", -1);
                    }
                });

                stopPiWebSync(context);

                // 后台启动全新守护服务
                mainHandler.post(() -> {
                    if (listener != null) {
                        listener.onLog("\u001B[36m• 正在拉起全新 Pi-Web 守护进程 (PORT " + port + ")...\u001B[0m\n");
                        listener.onProgress("正在拉起守护进程...", -1);
                    }
                });

                List<String> startCmd = Arrays.asList(
                        "/bin/bash", "-c",
                        "export PORT=" + port + "; " +
                        "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH; " +
                        "if command -v pi-web >/dev/null 2>&1; then exec pi-web --no-open -p " + port + " -H 127.0.0.1; " +
                        "elif [ -f /usr/local/lib/node_modules/@agegr/pi-web/bin/pi-web.js ]; then exec node /usr/local/lib/node_modules/@agegr/pi-web/bin/pi-web.js --no-open -p " + port + " -H 127.0.0.1; " +
                        "else exec node /usr/lib/node_modules/@agegr/pi-web/bin/pi-web.js --no-open -p " + port + " -H 127.0.0.1; fi"
                );

                ProcessBuilder pbStart = ProotManager.buildProotProcess(context, "/root", startCmd);
                File logFile = new File(ProotManager.getRootfsDir(context), "root/pi-web.log");
                logFile.getParentFile().mkdirs();
                pbStart.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
                pbStart.redirectError(ProcessBuilder.Redirect.appendTo(logFile));
                daemonProcess = pbStart.start();

                // 端口与 HTTP 接口深度探活 (最多 30 秒，兼容各配置设备冷启动)
                boolean alive = false;
                for (int i = 0; i < 30; i++) {
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                    if (ProotManager.isPiWebHttpReady(port)) {
                        alive = true;
                        break;
                    } else if (ProotManager.isPiWebPortAlive(port)) {
                        // 端口已通但 HTTP 握手仍在编译启动中，缓冲等待
                        try { Thread.sleep(800); } catch (InterruptedException ignored) {}
                        if (ProotManager.isPiWebHttpReady(port)) {
                            alive = true;
                            break;
                        }
                    }

                    // 如果守护进程已经提前崩溃退出，立即中断等待
                    if (daemonProcess != null && !daemonProcess.isAlive()) {
                        break;
                    }

                    if (listener != null && i % 3 == 0) {
                        final int elapsed = i + 1;
                        mainHandler.post(() -> listener.onProgress("正在加载并初始化 Web 运行环境 (已耗时 " + elapsed + "s)...", -1));
                    }
                }

                if (alive) {
                    mainHandler.post(() -> {
                        if (listener != null) {
                            listener.onLog("\u001B[32m🎉 Pi-Web 服务已成功启动！端口: " + port + "\u001B[0m\n");
                            listener.onStarted();
                        }
                    });
                } else {
                    mainHandler.post(() -> {
                        if (listener != null) {
                            listener.onLog("\u001B[31m✘ 端口尚未连通，最近日志:\u001B[0m\n" + readLastLog(context));
                            listener.onError("Pi-Web 启动超时，请查看诊断日志");
                        }
                    });
                }

            } catch (Throwable t) {
                Log.e(TAG, "Start Pi-Web error", t);
                mainHandler.post(() -> {
                    if (listener != null) listener.onError("启动异常: " + t.getMessage());
                });
            }
        }).start();
    }

    /**
     * 强力终止 Pi-Web 服务（同步版本，用于重新拉起前彻底清场）
     */
    public static void stopPiWebSync(Context context) {
        if (daemonProcess != null) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    daemonProcess.destroyForcibly();
                } else {
                    daemonProcess.destroy();
                }
            } catch (Throwable ignored) {}
            daemonProcess = null;
        }

        try {
            int port = PiMetConfig.getWebPort(context);

            // 在容器内部暴力杀死所有相关的 node 与 pi-web 实例
            List<String> killCmd = Arrays.asList(
                    "/bin/bash", "-c",
                    "kill -9 $(pidof node) 2>/dev/null || true; " +
                    "pkill -9 -f 'node.*pi-web' 2>/dev/null || true; " +
                    "pkill -9 -f pi-web 2>/dev/null || true; " +
                    "pkill -9 -f node 2>/dev/null || true; " +
                    "fuser -k -9 " + port + "/tcp 2>/dev/null || true"
            );
            ProcessBuilder pb = ProotManager.buildProotProcess(context, "/root", killCmd);
            pb.redirectOutput(new File("/dev/null"));
            pb.redirectError(new File("/dev/null"));
            Process p = pb.start();
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    p.waitFor(2000, java.util.concurrent.TimeUnit.MILLISECONDS);
                } else {
                    p.waitFor();
                }
            } catch (Throwable ignored) {}
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    p.destroyForcibly();
                } else {
                    p.destroy();
                }
            } catch (Throwable ignored) {}

            // 严密等待端口彻底释放（最多 4 秒，每 200ms 检测一次）
            for (int i = 0; i < 20; i++) {
                if (!ProotManager.isPiWebPortAlive(port)) break;
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                if (i == 7) {
                    try {
                        ProcessBuilder pbRetry = ProotManager.buildProotProcess(context, "/root",
                                Arrays.asList("/bin/bash", "-c", "kill -9 $(pidof node) 2>/dev/null || true; pkill -9 -f node 2>/dev/null || true"));
                        Process pr = pbRetry.start();
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            pr.waitFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS);
                        } else {
                            pr.waitFor();
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 强力终止 Pi-Web 服务（异步版本，不阻塞 UI 线程）
     */
    public static void stopPiWeb(Context context, Runnable callback) {
        new Thread(() -> {
            stopPiWebSync(context);
            if (callback != null) {
                MAIN_HANDLER.post(callback);
            }
        }).start();
    }

    public static synchronized void stopPiWeb() {
        if (daemonProcess != null) {
            try {
                daemonProcess.destroyForcibly();
            } catch (Throwable ignored) {}
            daemonProcess = null;
        }
    }

    public static boolean isProcessAlive() {
        return daemonProcess != null && daemonProcess.isAlive();
    }

    public static void clearLog(Context context) {
        File logFile = new File(ProotManager.getRootfsDir(context), "root/pi-web.log");
        if (logFile.exists()) {
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(logFile)) {
                fos.write(new byte[0]);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * 读取容器内的 /root/pi-web.log 最后几行
     */
    public static String readLastLog(Context context) {
        File logFile = new File(ProotManager.getRootfsDir(context), "root/pi-web.log");
        if (!logFile.exists()) return "暂无日志文件";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (Throwable ignored) {}
        return sb.toString();
    }
}
