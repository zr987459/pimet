package com.xm486.pimet.proot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PRoot 交互式终端会话：连接容器内部的标准 /bin/bash 登录 Shell
 */
public final class ProotSession {

    private static final String TAG = "ProotSession";

    public interface OutputListener {
        void onOutput(String text);
        void onExit(int code);
    }

    private final Context context;
    private final OutputListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private Process process;
    private OutputStream stdin;
    private volatile boolean isRunning = false;

    public ProotSession(Context context, OutputListener listener) {
        this.context = context;
        this.listener = listener;
    }

    public synchronized void start() {
        if (isRunning) return;

        executor.execute(() -> {
            try {
                if (!ProotManager.isRootfsInstalled(context)) {
                    postOutput("\u001B[33m• Linux 容器系统尚未部署，请先在【控制中心】点击一键部署！\u001B[0m\r\n");
                    return;
                }

                ProcessBuilder pb = ProotManager.buildProotProcess(context, "/root", null);
                pb.redirectErrorStream(true);
                process = pb.start();
                stdin = process.getOutputStream();
                isRunning = true;

                postOutput("\u001B[1;36m====================================================\u001B[0m\r\n");
                postOutput("\u001B[1;32m🐧 已进入 PRoot 容器环境 (Ubuntu 24.04 / Node.js 24)\u001B[0m\r\n");
                postOutput("\u001B[90m• 用户: root  目录: /root  端口: 30141\u001B[0m\r\n");
                postOutput("\u001B[1;36m====================================================\u001B[0m\r\n\r\n");

                // 读取输出
                executor.execute(this::readOutput);

                int exitCode = process.waitFor();
                isRunning = false;
                mainHandler.post(() -> {
                    if (listener != null) listener.onExit(exitCode);
                });
            } catch (Throwable t) {
                Log.e(TAG, "ProotSession start error", t);
                postOutput("\u001B[31m✘ 会话启动失败: " + t.getMessage() + "\u001B[0m\r\n");
                isRunning = false;
            }
        });
    }

    private void readOutput() {
        byte[] buffer = new byte[4096];
        try (InputStream in = process.getInputStream()) {
            int read;
            while (isRunning && (read = in.read(buffer)) != -1) {
                String text = new String(buffer, 0, read, StandardCharsets.UTF_8);
                postOutput(text);
            }
        } catch (Throwable ignored) {}
    }

    private void postOutput(String text) {
        mainHandler.post(() -> {
            if (listener != null) listener.onOutput(text);
        });
    }

    public synchronized void write(String cmd) {
        if (!isRunning || stdin == null) return;
        executor.execute(() -> {
            try {
                stdin.write(cmd.getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            } catch (Throwable ignored) {}
        });
    }

    public synchronized void sendCtrlC() {
        if (!isRunning || stdin == null) return;
        executor.execute(() -> {
            try {
                stdin.write(3); // ETX
                stdin.flush();
            } catch (Throwable ignored) {}
        });
    }

    public synchronized void close() {
        isRunning = false;
        if (process != null) {
            process.destroy();
            process = null;
        }
        executor.shutdownNow();
    }
}
