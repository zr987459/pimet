package com.xm486.pimet.terminal;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.List;

/**
 * 纯 Java 原生 HTTP 断点续传下载器：
 * 不依赖系统 curl/wget 命令，支持多线路镜像自动重试与故障降级、30x 重定向、Range 头部续传与进度通知。
 */
public class Downloader {

    public interface DownloadListener {
        void onProgress(long downloaded, long total, int percent);
        void onSuccess(File file);
        void onError(String message);
        void onSwitchCandidate(String nextUrl, String reason);
    }

    public static void downloadResumable(String urlStr, File destFile, DownloadListener listener) {
        downloadWithCandidates(Collections.singletonList(urlStr), destFile, listener);
    }

    public static void downloadWithCandidates(List<String> urls, File destFile, DownloadListener listener) {
        new Thread(() -> {
            boolean success = false;
            String lastError = "无可用下载链接";

            for (int i = 0; i < urls.size(); i++) {
                String currentUrl = urls.get(i);
                if (i > 0 && listener != null) {
                    listener.onSwitchCandidate(currentUrl, "前置线路受阻，自动切换至候选线路 " + (i + 1) + "/" + urls.size());
                }

                try {
                    boolean ok = attemptDownload(currentUrl, destFile, listener);
                    if (ok) {
                        success = true;
                        break;
                    }
                } catch (Throwable e) {
                    lastError = e.getMessage() != null ? e.getMessage() : "下载异常";
                }
            }

            if (!success && listener != null) {
                listener.onError("所有候选镜像线路均尝试完毕，最终错误: " + lastError);
            }
        }).start();
    }

    private static boolean attemptDownload(String urlStr, File destFile, DownloadListener listener) throws Exception {
        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream out = null;

        try {
            long existingSize = destFile.exists() ? destFile.length() : 0;

            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(25000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android)");

            if (existingSize > 0) {
                conn.setRequestProperty("Range", "bytes=" + existingSize + "-");
            }

            int responseCode = conn.getResponseCode();

            // 循环处理 301/302/307 重定向
            if (responseCode == HttpURLConnection.HTTP_MOVED_PERM
                    || responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                    || responseCode == 307
                    || responseCode == 308) {
                String newUrl = conn.getHeaderField("Location");
                conn.disconnect();
                if (newUrl != null && !newUrl.isEmpty()) {
                    return attemptDownload(newUrl, destFile, listener);
                }
            }

            boolean append = false;
            long totalSize = 0;

            if (responseCode == 206) {
                append = true;
                totalSize = existingSize + conn.getContentLengthLong();
            } else if (responseCode == 200) {
                append = false;
                existingSize = 0;
                totalSize = conn.getContentLengthLong();
            } else if (responseCode == 416) {
                destFile.setExecutable(true);
                if (listener != null) listener.onSuccess(destFile);
                return true;
            } else {
                return false;
            }

            in = conn.getInputStream();
            out = new FileOutputStream(destFile, append);

            byte[] buffer = new byte[8192];
            int bytesRead;
            long downloaded = existingSize;
            long lastNotify = 0;

            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                downloaded += bytesRead;

                long now = System.currentTimeMillis();
                if (now - lastNotify > 500 || downloaded == totalSize) {
                    lastNotify = now;
                    int percent = totalSize > 0 ? (int) ((downloaded * 100) / totalSize) : -1;
                    if (listener != null) {
                        listener.onProgress(downloaded, totalSize, percent);
                    }
                }
            }

            out.flush();
            destFile.setExecutable(true);

            if (listener != null) {
                listener.onSuccess(destFile);
            }
            return true;

        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { if (out != null) out.close(); } catch (Throwable ignored) {}
            if (conn != null) conn.disconnect();
        }
    }
}
