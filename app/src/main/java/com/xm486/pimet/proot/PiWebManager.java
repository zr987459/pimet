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
     * 启动或部署 Pi-Web
     */
    public static void startOrDeploy(Context context, StateListener listener) {
        new Thread(() -> {
            Handler mainHandler = new Handler(Looper.getMainLooper());
            try {
                int port = PiMetConfig.getWebPort(context);
                if (ProotManager.isPiWebPortAlive(port)) {
                    mainHandler.post(() -> {
                        if (listener != null) {
                            listener.onLog("\u001B[32m✔ Pi-Web 已在本地端口 " + port + " 运行中\u001B[0m\n");
                            listener.onStarted();
                        }
                    });
                    return;
                }

                // 检查 Rootfs 是否已就绪
                if (!ProotManager.isRootfsInstalled(context)) {
                    mainHandler.post(() -> {
                        if (listener != null) listener.onLog("正在准备 Linux 容器系统...\n");
                    });
                    ProotManager.installRootfs(context, new ProotManager.InstallCallback() {
                        @Override
                        public void onProgress(String message, int percent) {
                            mainHandler.post(() -> {
                                if (listener != null) listener.onLog("• " + message + "\n");
                            });
                        }

                        @Override
                        public void onSuccess() {
                            // 递归拉起 Pi-Web
                            startOrDeploy(context, listener);
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

                // 后台启动守护服务
                mainHandler.post(() -> {
                    if (listener != null) listener.onLog("\u001B[36m• 正在拉起 Pi-Web 服务 (PORT " + port + ")...\u001B[0m\n");
                });

                List<String> startCmd = Arrays.asList(
                        "/bin/bash", "-c",
                        "export PORT=" + port + "; if command -v pi-web >/dev/null 2>&1; then exec pi-web; elif [ -f /usr/local/lib/node_modules/@agegr/pi-web/bin/pi-web.js ]; then exec node /usr/local/lib/node_modules/@agegr/pi-web/bin/pi-web.js; else exec node /usr/lib/node_modules/@agegr/pi-web/bin/pi-web.js; fi"
                );

                stopPiWebSync(context);
                ProcessBuilder pbStart = ProotManager.buildProotProcess(context, "/root", startCmd);
                File logFile = new File(ProotManager.getRootfsDir(context), "root/pi-web.log");
                logFile.getParentFile().mkdirs();
                pbStart.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
                pbStart.redirectError(ProcessBuilder.Redirect.appendTo(logFile));
                daemonProcess = pbStart.start();

                // 端口健康轮询 (最多 15 秒)
                boolean alive = false;
                for (int i = 0; i < 15; i++) {
                    Thread.sleep(1000);
                    if (ProotManager.isPiWebPortAlive(port)) {
                        alive = true;
                        break;
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
                            listener.onError("Pi-Web 启动超时，请查看日志");
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
                daemonProcess.destroyForcibly();
            } catch (Throwable ignored) {}
            daemonProcess = null;
        }

        try {
            int port = PiMetConfig.getWebPort(context);
            // 杀死容器内残留的 pi-web 与 Node 监听实例
            List<String> killCmd = Arrays.asList(
                    "/bin/bash", "-c",
                    "pkill -9 -f pi-web 2>/dev/null; " +
                    "pkill -9 -f 'node.*pi-web' 2>/dev/null; " +
                    "fuser -k -9 " + port + "/tcp 2>/dev/null; " +
                    "kill -9 $(lsof -t -i:" + port + " 2>/dev/null) 2>/dev/null || true"
            );
            ProcessBuilder pb = ProotManager.buildProotProcess(context, "/root", killCmd);
            Process p = pb.start();
            p.waitFor();

            // 等待端口彻底释放
            for (int i = 0; i < 6; i++) {
                if (!ProotManager.isPiWebPortAlive(port)) break;
                Thread.sleep(300);
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
