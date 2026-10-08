package com.xm486.pimet.monitor;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import com.xm486.pimet.pet.ChatConfig;
import com.xm486.pimet.pet.PetRegistry;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Operit 监控轮询器（HTTP API 版，取代 Shizuku + logcat 解析）。
 *
 * 通过 Operit 本地 REST API（默认 8094 端口）轮询实时状态：
 *   GET /api/web/bootstrap         → 当前对话 ID
 *   GET /api/web/chats             → 各对话 active_streaming 标记
 *   GET /api/web/chats/{id}/messages → 最新 assistant 消息的 content_blocks
 *
 * 状态映射（按最新未关闭块的 tag_name）：
 *   think / thinking   → THINKING（思考中）
 *   tool / tool_result → TOOL_RUNNING（工具执行中）
 *   search             → WORKING（搜索中）
 *   error              → ERROR（出错）
 *   其他               → RESPONDING（回复中）
 *   active_streaming=false → IDLE（空闲）
 *   连续 N 次请求失败   → UNKNOWN（Operit 未运行）
 *
 * 轮询间隔 2s，不再依赖 Shizuku。
 */
public class OperitMonitor {
    private static final String TAG = "DevPetM.Monitor";
    private static final long POLL_INTERVAL_MS = 2000L;
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final int GONE_THRESHOLD = 3;

    /** 状态变化监听（回调在主线程） */
    public interface Listener {
        void onSnapshot(OperitState.Snapshot snapshot);
        void onError(String message);
    }

    private final Listener listener;
    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler handler;
    private volatile boolean running = false;

    /** 连续探测失败次数 */
    private int goneCount = 0;
    /** 最近一次有效快照（断连时沿用旧快照，避免状态闪烁） */
    private OperitState.Snapshot lastSnapshot = new OperitState.Snapshot();

    public OperitMonitor(Listener listener, Context context) {
        this.listener = listener;
        this.appContext = context.getApplicationContext();
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new HandlerThread("operit-monitor");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(this::tick);
        Log.i(TAG, "monitor started (HTTP API mode)");
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
            handler = null;
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
        Log.i(TAG, "monitor stopped");
    }

    public boolean isRunning() {
        return running;
    }

    private void tick() {
        if (!running) return;
        try {
            ChatConfig config = ChatConfig.load(appContext);
            // 监控端口跟随主页「Operit 端口」框（KEY_OPERIT_PORT），不再写死 8094
            int port = PetRegistry.getOperitPort(appContext);
            String base = "http://127.0.0.1:" + port;
            String token = config.operitToken;

            OperitState.Snapshot snap = pollApi(base, token);
            if (snap != null) {
                goneCount = 0;
                lastSnapshot = snap;
            } else {
                goneCount++;
                if (goneCount >= GONE_THRESHOLD) {
                    lastSnapshot = new OperitState.Snapshot();
                    lastSnapshot.state = OperitState.UNKNOWN;
                    lastSnapshot.operitRunning = false;
                }
            }
            notifySnapshot();
        } catch (Throwable t) {
            Log.e(TAG, "tick failed", t);
            notifyError(t.getMessage());
        } finally {
            if (running && handler != null) {
                handler.postDelayed(this::tick, POLL_INTERVAL_MS);
            }
        }
    }

    /** 通过 HTTP API 获取 Operit 实时状态，失败返回 null */
    private OperitState.Snapshot pollApi(String base, String authToken) {
        try {
            // 1. 获取当前对话 ID
            String bootstrapJson = httpGet(base + "/api/web/bootstrap", authToken);
            if (bootstrapJson == null) return null;
            JSONObject bootstrap = new JSONObject(bootstrapJson);
            String currentChatId = bootstrap.optString("current_chat_id", "");

            // 2. 检查 active_streaming
            boolean activeStreaming = false;
            String chatsJson = httpGet(base + "/api/web/chats", authToken);
            if (chatsJson != null) {
                JSONArray chats = new JSONArray(chatsJson);
                for (int i = 0; i < chats.length(); i++) {
                    JSONObject chat = chats.optJSONObject(i);
                    if (chat != null && chat.optBoolean("active_streaming", false)) {
                        activeStreaming = true;
                        break;
                    }
                }
            }

            // 3. 获取最新流式消息的 content_blocks（仅流式进行中才需要）
            // 注意：/messages 返回的是 WebChatMessagesPage 包装对象
            //   {"messages":[...], "has_more_before":..., ...}，不是裸数组
            // 目标对话优先取 current_chat_id；若它没在流式（比如 DevPetM 专属会话在生成、
            // App 当前对话空闲），则找那个 active_streaming 的对话去读块标签
            String lastBlockTag = "";
            if (activeStreaming) {
                // 目标对话：current_chat_id；若它没在流式（如 DevPetM 专属会话在生成），
                // 找那个 active_streaming 的对话去读块标签
                String targetChatId = currentChatId;
                if (chatsJson != null) {
                    JSONArray chats2 = new JSONArray(chatsJson);
                    for (int i = 0; i < chats2.length(); i++) {
                        JSONObject chat = chats2.optJSONObject(i);
                        if (chat != null && chat.optBoolean("active_streaming", false)) {
                            targetChatId = chat.optString("id", targetChatId);
                            break;
                        }
                    }
                }
                if (!targetChatId.isEmpty()) {
                    String msgsJson = httpGet(
                            base + "/api/web/chats/" + targetChatId + "/messages", authToken);
                    if (msgsJson != null) {
                        JSONObject page = new JSONObject(msgsJson);
                        JSONArray arr = page.optJSONArray("messages");
                        if (arr != null) {
                            // 从最新往回找第一条带 content_blocks 的消息（不依赖 sender 字段值）
                            for (int i = arr.length() - 1; i >= 0; i--) {
                                JSONObject msg = arr.optJSONObject(i);
                                if (msg == null) continue;
                                JSONArray blocks = msg.optJSONArray("content_blocks");
                                if (blocks == null || blocks.length() == 0) continue;
                                // 取该消息里最后一个未关闭块（或最后一块）的 tag_name
                                for (int j = blocks.length() - 1; j >= 0; j--) {
                                    JSONObject block = blocks.optJSONObject(j);
                                    if (block == null) continue;
                                    boolean closed = block.optBoolean("closed", true);
                                    if (!closed || j == blocks.length() - 1) {
                                        lastBlockTag = block.optString("tag_name", "");
                                        break;
                                    }
                                }
                                break;
                            }
                        }
                    }
                }
            }

            // 4. 构建快照
            OperitState.Snapshot snap = new OperitState.Snapshot();
            snap.agentName = "Operit";
            snap.operitRunning = true;
            snap.lastActiveTime = System.currentTimeMillis();

            if (activeStreaming) {
                String tag = lastBlockTag == null ? "" : lastBlockTag.toLowerCase();
                if (tag.startsWith("think")) {
                    snap.state = OperitState.THINKING;
                } else if (tag.startsWith("tool")) {
                    snap.state = OperitState.TOOL_RUNNING;
                } else if (tag.equals("search")) {
                    snap.state = OperitState.WORKING;
                } else if (tag.startsWith("error")) {
                    snap.state = OperitState.ERROR;
                } else {
                    snap.state = OperitState.RESPONDING;
                }
            } else {
                snap.state = OperitState.IDLE;
            }
            return snap;
        } catch (Throwable t) {
            Log.w(TAG, "pollApi exception: " + t.getMessage());
            return null;
        }
    }

    /** 简单 HTTP GET（后台线程调用，不阻塞主线程） */
    private String httpGet(String url, String authToken) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            if (authToken != null && !authToken.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + authToken);
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                Log.w(TAG, "HTTP " + code + " from " + url);
                return null;
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            return sb.toString();
        } catch (Throwable t) {
            Log.d(TAG, "httpGet: " + t.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 从 external-chat URL 提取 base（去掉 /api/external-chat 路径） */
    private String extractBaseUrl(String operitUrl) {
        if (operitUrl == null) return "http://127.0.0.1:8094";
        int idx = operitUrl.indexOf("/api/");
        return idx > 0 ? operitUrl.substring(0, idx) : operitUrl;
    }

    private void notifySnapshot() {
        final OperitState.Snapshot s = lastSnapshot;
        if (listener != null) {
            mainHandler.post(() -> listener.onSnapshot(s));
        }
    }

    private void notifyError(final String message) {
        if (listener != null) {
            mainHandler.post(() -> listener.onError(message));
        }
    }
}
