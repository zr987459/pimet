package com.xm486.pimet.terminal;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shell 会话管理：封装底层 /system/bin/sh 进程，异步处理输入输出流，提供命令行交互能力。
 */
public class ShellSession {

    public interface SessionCallback {
        void onOutput(String text);
        void onProcessTerminated(int exitCode);
    }

    private final Context context;
    private final Handler mainHandler;
    private final ExecutorService executor;

    private Process process;
    private OutputStream processInput;
    private SessionCallback callback;
    private boolean isRunning = false;

    public ShellSession(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executor = Executors.newCachedThreadPool();
    }

    public void setCallback(SessionCallback callback) {
        this.callback = callback;
    }

    public synchronized boolean isRunning() {
        return isRunning;
    }

    /**
     * 启动一个交互式 Shell 进程
     */
    public synchronized void start() {
        if (isRunning) return;

        try {
            File filesDir = context.getFilesDir();
            File binDir = new File(filesDir, "usr/bin");
            File appBinDir = new File(filesDir, "bin");
            File tmpDir = new File(filesDir, "tmp");

            binDir.mkdirs();
            appBinDir.mkdirs();
            tmpDir.mkdirs();

            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-i");
            pb.directory(filesDir);
            pb.redirectErrorStream(true);

            Map<String, String> env = pb.environment();
            env.put("HOME", filesDir.getAbsolutePath());
            env.put("TMPDIR", tmpDir.getAbsolutePath());
            env.put("PREFIX", new File(filesDir, "usr").getAbsolutePath());

            StringBuilder pathBuilder = new StringBuilder();
            pathBuilder.append(appBinDir.getAbsolutePath()).append(":")
                       .append(binDir.getAbsolutePath()).append(":");

            // 自动感知并接入 Termux / Operit 工具链（若存在）
            String[] extBins = {
                "/data/data/com.termux/files/usr/bin",
                "/data/user/999/com.ai.assistance.operit/files/usr/bin",
                "/data/data/com.ai.assistance.operit/files/usr/bin"
            };
            for (String ext : extBins) {
                File dir = new File(ext);
                if (dir.exists() && dir.canRead()) {
                    pathBuilder.append(ext).append(":");
                }
            }

            String origPath = env.get("PATH");
            if (origPath == null || origPath.isEmpty()) {
                origPath = "/system/bin:/system/xbin";
            }
            pathBuilder.append(origPath);
            env.put("PATH", pathBuilder.toString());

            File termuxLib = new File("/data/data/com.termux/files/usr/lib");
            if (termuxLib.exists() && termuxLib.canRead()) {
                String origLd = env.get("LD_LIBRARY_PATH");
                env.put("LD_LIBRARY_PATH", termuxLib.getAbsolutePath() + (origLd != null ? ":" + origLd : ":/system/lib64"));
            }

            env.put("TERM", "xterm-256color");
            env.put("LANG", "en_US.UTF-8");

            process = pb.start();
            processInput = process.getOutputStream();
            isRunning = true;

            // 监听输出流
            executor.execute(this::readProcessOutput);

            // 监听退出
            executor.execute(() -> {
                try {
                    int exitCode = process.waitFor();
                    synchronized (ShellSession.this) {
                        isRunning = false;
                    }
                    postTerminated(exitCode);
                } catch (InterruptedException ignored) {
                    synchronized (ShellSession.this) {
                        isRunning = false;
                    }
                }
            });

        } catch (Throwable e) {
            isRunning = false;
            postOutput("\u001B[31m[错误] 启动 Shell 失败: " + e.getMessage() + "\u001B[0m\n");
        }
    }

    private void readProcessOutput() {
        InputStream in = process.getInputStream();
        byte[] buffer = new byte[2048];
        int len;
        try {
            while ((len = in.read(buffer)) != -1) {
                String text = new String(buffer, 0, len, StandardCharsets.UTF_8);
                postOutput(text);
            }
        } catch (Throwable ignored) {
        }
    }

    private void postOutput(String text) {
        mainHandler.post(() -> {
            if (callback != null) {
                callback.onOutput(text);
            }
        });
    }

    private void postTerminated(int exitCode) {
        mainHandler.post(() -> {
            if (callback != null) {
                callback.onProcessTerminated(exitCode);
            }
        });
    }

    /**
     * 向终端输入命令或字符
     */
    public synchronized void write(String input) {
        if (!isRunning || processInput == null) {
            // 如果进程未运行，自动重启
            start();
        }
        if (processInput == null) return;

        executor.execute(() -> {
            try {
                processInput.write(input.getBytes(StandardCharsets.UTF_8));
                processInput.flush();
            } catch (Throwable e) {
                postOutput("\u001B[31m[输入错误] " + e.getMessage() + "\u001B[0m\n");
            }
        });
    }

    /**
     * 发送完整命令并换行
     */
    public void exec(String command) {
        write(command + "\n");
    }

    /**
     * 中断或杀死当前进程
     */
    public synchronized void interrupt() {
        if (!isRunning) return;
        try {
            // 发送 Ctrl+C (ETX = 0x03)
            if (processInput != null) {
                processInput.write(3);
                processInput.flush();
            }
        } catch (Throwable ignored) {}

        executor.execute(() -> {
            try {
                Thread.sleep(500);
                if (isRunning && process != null) {
                    process.destroy();
                }
            } catch (Throwable ignored) {}
        });
    }

    /**
     * 强行销毁会话
     */
    public synchronized void destroy() {
        isRunning = false;
        if (process != null) {
            try {
                process.destroy();
            } catch (Throwable ignored) {}
            process = null;
        }
        try {
            executor.shutdownNow();
        } catch (Throwable ignored) {}
    }
}
