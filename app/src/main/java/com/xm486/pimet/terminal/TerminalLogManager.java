package com.xm486.pimet.terminal;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 终端日志管理器：持久化记录控制台输出、支持多组件日志读取与导出。
 */
public class TerminalLogManager {

    private static final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
    private static final long MAX_LOG_SIZE = 2 * 1024 * 1024; // 2MB 轮转上限

    public static File getLogDir(Context context) {
        File dir = new File(context.getFilesDir(), "logs");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File getTerminalLogFile(Context context) {
        return new File(getLogDir(context), "terminal.log");
    }

    public static synchronized void appendLog(Context context, String text) {
        if (text == null || text.isEmpty()) return;
        try {
            File logFile = getTerminalLogFile(context);
            if (logFile.exists() && logFile.length() > MAX_LOG_SIZE) {
                File old = new File(getLogDir(context), "terminal_old.log");
                old.delete();
                logFile.renameTo(old);
            }
            try (FileOutputStream fos = new FileOutputStream(logFile, true)) {
                // 简单过滤掉 ANSI 颜色转义码以保存纯文本
                String clean = text.replaceAll("\u001B\\[[0-9;]*[A-Za-z]", "");
                fos.write(clean.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
        } catch (Throwable ignored) {}
    }

    public static String readLogTail(File file, int maxLines) {
        if (file == null || !file.exists() || !file.canRead()) {
            return "（日志文件不存在或尚无内容: " + (file != null ? file.getName() : "null") + "）";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(maxLines);
            while ((line = reader.readLine()) != null) {
                if (queue.size() >= maxLines) {
                    queue.pollFirst();
                }
                queue.addLast(line);
            }
            for (String l : queue) {
                sb.append(l).append("\n");
            }
        } catch (Throwable e) {
            sb.append("读取日志异常: ").append(e.getMessage());
        }
        return sb.toString();
    }
}
