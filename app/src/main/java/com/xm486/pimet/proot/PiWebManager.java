package com.xm486.pimet.proot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;

/**
 * Pi-Web 与 Pi 核心管理类：负责在 PRoot 容器内拉取 npm 模块与守护后台进程
 */
public final class PiWebManager {

    private static final String TAG = "PiWebManager";

    public interface StateListener {
        void onLog(String log);
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
                if (ProotManager.isPiWebPortAlive()) {
                    mainHandler.post(() -> {
                        if (listener != null) {
                            listener.onLog("\u001B[32m✔ Pi-Web 已在本地端口 30141 运行中\u001B[0m\n");
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

                // 如果未安装 pi-web，在标准 Linux 容器内执行 npm install
                if (!ProotManager.isPiWebInstalled(context)) {
                    mainHandler.post(() -> {
                        if (listener != null) listener.onLog("\u001B[33m• 正在从镜像源安装 Pi-Web 与 Pi 核心套件...\u001B[0m\n");
                    });

                    List<String> installCmd = Arrays.asList(
                            "/bin/bash", "-c",
                            "npm install -g --ignore-scripts --no-audit --no-fund --registry=https://registry.npmmirror.com @earendil-works/pi-coding-agent@1.0.0 @agegr/pi-web"
                    );

                    ProcessBuilder pb = ProotManager.buildProotProcess(context, "/root", installCmd);
                    pb.redirectErrorStream(true);
                    Process process = pb.start();

                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            final String l = line;
                            mainHandler.post(() -> {
                                if (listener != null) listener.onLog(l + "\n");
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
                    if (listener != null) listener.onLog("\u001B[36m• 正在拉起 Pi-Web 服务 (PORT 30141)...\u001B[0m\n");
                });

                List<String> startCmd = Arrays.asList(
                        "/bin/bash", "-c",
                        "PORT=30141 nohup node /usr/local/lib/node_modules/@agegr/pi-web/bin/pi-web.js > /root/pi-web.log 2>&1 &"
                );

                ProcessBuilder pbStart = ProotManager.buildProotProcess(context, "/root", startCmd);
                Process p = pbStart.start();
                p.waitFor();

                // 端口健康轮询 (最多 15 秒)
                boolean alive = false;
                for (int i = 0; i < 15; i++) {
                    Thread.sleep(1000);
                    if (ProotManager.isPiWebPortAlive()) {
                        alive = true;
                        break;
                    }
                }

                if (alive) {
                    mainHandler.post(() -> {
                        if (listener != null) {
                            listener.onLog("\u001B[32m🎉 Pi-Web 服务已成功启动！端口: 30141\u001B[0m\n");
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
