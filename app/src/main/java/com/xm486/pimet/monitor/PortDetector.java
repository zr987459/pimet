package com.xm486.pimet.monitor;

import android.content.Context;
import com.xm486.pimet.PiMetConfig;
import com.xm486.pimet.pet.PetRegistry;

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;

/**
 * 本地多服务端口与连通性检测器 (学自 DevPetM 架构)：
 * 针对 pi-web、Operit、ClawBench、RikkaHub 等目标执行毫秒级 TCP/HTTP 探针检测。
 */
public class PortDetector {

    public static class TargetStatus {
        public final String key;
        public final String displayName;
        public final int port;
        public final boolean alive;
        public final int latencyMs;
        public final String message;

        public TargetStatus(String key, String displayName, int port, boolean alive, int latencyMs, String message) {
            this.key = key;
            this.displayName = displayName;
            this.port = port;
            this.alive = alive;
            this.latencyMs = latencyMs;
            this.message = message;
        }
    }

    public interface DetectCallback {
        void onResult(TargetStatus status);
    }

    /**
     * 毫秒级轻量 TCP 端口探活
     */
    public static boolean isPortOpen(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 同步探测目标端口状态及 HTTP 响应
     */
    public static TargetStatus check(Context context, String targetKey) {
        int port;
        String name;
        String pingPath = "/";

        if (PetRegistry.TARGET_PIWEB.equals(targetKey)) {
            port = PiMetConfig.getWebPort(context);
            name = "pi-web";
            pingPath = "/";
        } else if (PetRegistry.TARGET_OPERIT.equals(targetKey)) {
            port = PetRegistry.getOperitPort(context);
            name = "Operit";
            pingPath = "/api/web/status";
        } else if (PetRegistry.TARGET_CLAWBENCH.equals(targetKey)) {
            port = PetRegistry.getClawbenchPort(context);
            name = "ClawBench";
            pingPath = "/login";
        } else if (PetRegistry.TARGET_RIKKA.equals(targetKey)) {
            port = PetRegistry.getIntPref(context, PetRegistry.KEY_RK_PORT, 8095);
            name = "RikkaHub";
            pingPath = "/api/hub/metrics";
        } else {
            port = 80;
            name = targetKey;
        }

        long t0 = System.currentTimeMillis();
        boolean tcpOk = isPortOpen("127.0.0.1", port, 400);
        int latency = (int) (System.currentTimeMillis() - t0);

        if (!tcpOk) {
            return new TargetStatus(targetKey, name, port, false, latency, "端口 " + port + " 未响应或服务未启动");
        }

        // 尝试轻量 HTTP 探测确认接口活跃
        try {
            URL url = new URL("http://127.0.0.1:" + port + pingPath);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(350);
            conn.setReadTimeout(350);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            conn.disconnect();
            return new TargetStatus(targetKey, name, port, true, latency, "HTTP " + code + " (延迟 " + latency + "ms)");
        } catch (Throwable ignored) {
            // TCP 通畅即可视为活跃
            return new TargetStatus(targetKey, name, port, true, latency, "端口开放 (延迟 " + latency + "ms)");
        }
    }

    /**
     * 异步探测目标状态
     */
    public static void checkAsync(Context context, String targetKey, DetectCallback callback) {
        new Thread(() -> {
            TargetStatus status = check(context, targetKey);
            if (callback != null) {
                callback.onResult(status);
            }
        }).start();
    }
}
