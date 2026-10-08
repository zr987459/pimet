package com.xm486.pimet.monitor;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSource;

/**
 * pi-web（@agegr/pi-web）检测器：通过本机 Web 服务拿【详细状态】。
 *
 * pi-web 是 Next.js 应用，所有接口无需认证（无 cookie/登录），比 ClawBench 简单：
 *
 *  1. GET /api/sessions           列出全部会话（id/modified/messageCount），找最近活跃会话
 *  2. GET /api/agent/{id}/events  SSE 长连接（text/event-stream），实时推 agent 事件：
 *
 *     data: {"type":"connected","sessionId":"...","isStreaming":false,...}
 *     data: {"type":"message_start","message":{...}}
 *     data: {"type":"message_update","assistantMessageEvent":{"type":"thinking_delta",...}}
 *     data: {"type":"message_update","assistantMessageEvent":{"type":"text_delta",...}}
 *     data: {"type":"tool_execution_update","toolCallId":"...","toolName":"..."}
 *     data: {"type":"tool_execution_end",...}
 *     data: {"type":"agent_end",...}
 *     data: {"type":"session_shutdown",...}
 *     :（每 30s 的心跳注释，忽略）
 *
 *  assistantMessageEvent.type 词表（@earendil-works/pi-ai AssistantMessageFrame）：
 *     start / text_start / text_delta / text_end
 *     thinking_start / thinking_delta / thinking_end
 *     toolcall_start / toolcall_checkpoint / toolcall_delta / toolcall_end
 *
 * 状态映射（详细状态，对齐 ClawBench 的体验）：
 *     connected(isStreaming=true) / message_start / start  → 工作中（WORKING，刚发起）
 *     thinking_start / thinking_delta / thinking_end       → 思考中（THINKING）
 *     text_start / text_delta / text_end                   → 回复中（RESPONDING）
 *     toolcall_* / tool_execution_update                   → 工具执行中（TOOL_RUNNING）
 *     tool_execution_end                                   → 回落思考中（THINKING，等下一段）
 *     agent_end / session_shutdown / connected(isStreaming=false) → 空闲（IDLE）
 *     startup_error                                        → 出错（ERROR）
 *
 * 会话选择：默认订阅最近 modified 的会话。pi-web 是单用户应用，
 * 同时只会有一个会话在跑，盯最活跃那个即可。
 */
public class PiWebMonitor {

    private static final String TAG = "DevPetM.PiWeb";
    private static final long POLL_INTERVAL_MS = 5000L;
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int SSE_RECONNECT_MS = 3000;
    private static final int GONE_THRESHOLD = 3;
    private static final int EVENT_HISTORY = 12;

    public interface Listener {
        void onSnapshot(OperitState.Snapshot snapshot);
        void onError(String message);
    }

    private final int port;
    private final Context appContext;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private HandlerThread thread;
    private Handler handler;
    private volatile boolean running = false;

    /** 连续探测失败次数（/api/sessions 连不上时计数） */
    private int goneCount = 0;
    /** 最近一次有效快照（断连时沿用，避免状态闪烁） */
    private OperitState.Snapshot lastSnapshot = newSnapshotIdle();
    /** 最近事件 */
    private final ArrayDeque<String> recentEvents = new ArrayDeque<>();

    /** SSE 客户端（长连接） */
    private OkHttpClient sseClient;
    private okhttp3.Call sseCall;
    private volatile boolean sseConnecting = false;
    /** 当前订阅的会话 id */
    private volatile String watchedSessionId = null;
    /** 当前 SSE 流是否已收到 connected 帧（未收到说明流没真正建立） */
    private volatile boolean sseReady = false;

    public PiWebMonitor(Context context, int port, Listener listener) {
        this.port = port;
        this.appContext = context.getApplicationContext();
        this.listener = listener;
    }

    private static OperitState.Snapshot newSnapshotIdle() {
        OperitState.Snapshot s = new OperitState.Snapshot();
        s.state = OperitState.IDLE;
        s.agentName = "pi-web";
        return s;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new HandlerThread("piweb-monitor");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(this::tick);
        Log.i(TAG, "monitor started, port=" + port);
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
        closeSse();
        if (sseClient != null) {
            sseClient.dispatcher().executorService().shutdown();
            sseClient = null;
        }
        watchedSessionId = null;
        Log.i(TAG, "monitor stopped");
    }

    public boolean isRunning() {
        return running;
    }

    // ---------------- 主循环：会话发现 + SSE 维护 ----------------

    private void tick() {
        if (!running) return;
        try {
            String sessionId = pickSession();
            if (sessionId != null) {
                goneCount = 0;
                // 会话变了或 SSE 没连上：重连
                boolean changed = !sessionId.equals(watchedSessionId);
                if (changed || (!sseReady && !sseConnecting)) {
                    watchedSessionId = sessionId;
                    closeSse();
                    connectSse(sessionId);
                }
                // SSE 正常时啥也不做（长连接自己推事件，tick 只负责发现会话和兜底重连）
            } else {
                // 连不上：计数，达到阈值才降级
                goneCount++;
                closeSse();
                watchedSessionId = null;
                if (goneCount >= GONE_THRESHOLD) {
                    OperitState.Snapshot unknown = new OperitState.Snapshot();
                    unknown.state = OperitState.UNKNOWN;
                    unknown.agentName = "pi-web";
                    unknown.operitRunning = false;
                    unknown.recentEvents = new ArrayList<>(Collections.singletonList(
                            goneCount >= GONE_THRESHOLD + 1
                                    ? "pi-web 不可达（端口 " + port + "）"
                                    : "pi-web 连接中…"));
                    lastSnapshot = unknown;
                    mainHandler.post(() -> listener.onSnapshot(lastSnapshot));
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "tick failed", t);
        }
        if (running) {
            handler.postDelayed(this::tick, POLL_INTERVAL_MS);
        }
    }

    /**
     * GET /api/sessions → 取最近活跃会话 id。
     * 会话对象：{id, modified, messageCount, firstMessage, ...}
     * 按 modified 降序取第一个（messageCount=0 的空会话跳过）。
     * 返回 null 表示服务不可达或没有会话。
     */
    private String pickSession() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://127.0.0.1:" + port + "/api/sessions");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(3000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Cache-Control", "no-cache");
            int code = conn.getResponseCode();
            if (code != 200) return null;
            String body = new String(readAll(conn), java.nio.charset.StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(body);
            JSONArray arr = root.optJSONArray("sessions");
            if (arr == null || arr.length() == 0) return null;

            String bestId = null;
            long bestModified = -1;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s == null) continue;
                String id = s.optString("id", "");
                if (id.isEmpty()) continue;
                int count = s.optInt("messageCount", 0);
                if (count == 0) continue; // 空会话（刚 new 出来还没说话）
                long modified = parseTimestamp(s.optString("modified", ""));
                if (modified > bestModified) {
                    bestModified = modified;
                    bestId = id;
                }
            }
            return bestId;
        } catch (Throwable t) {
            Log.d(TAG, "pickSession failed: " + t.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static final SimpleDateFormat ISO_FMT =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
    static {
        ISO_FMT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    /** 解析 pi-web 的时间戳（ISO 8601，如 2026-10-05T03:03:25.277Z）；失败返回 0 */
    private static long parseTimestamp(String iso) {
        if (iso == null || iso.isEmpty()) return 0;
        try {
            Date d = ISO_FMT.parse(iso);
            return d != null ? d.getTime() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    // ---------------- SSE 长连接 ----------------

    private void connectSse(String sessionId) {
        if (!running) return;
        sseConnecting = true;
        sseReady = false;
        if (sseClient == null) {
            sseClient = new OkHttpClient.Builder()
                    .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .readTimeout(0, TimeUnit.MILLISECONDS)  // SSE 长连接，不超时
                    .retryOnConnectionFailure(true)
                    .build();
        }
        Request request = new Request.Builder()
                .url("http://127.0.0.1:" + port + "/api/agent/" + sessionId + "/events")
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .build();
        okhttp3.Call call = sseClient.newCall(request);
        sseCall = call;

        call.enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call c, IOException e) {
                sseConnecting = false;
                sseReady = false;
                if (!running) return;
                Log.w(TAG, "SSE connect failed: " + e.getMessage());
                // 重连交给 tick：sseReady=false 时下轮 tick 会重连
            }

            @Override
            public void onResponse(okhttp3.Call c, Response response) {
                sseConnecting = false;
                if (!running) {
                    response.close();
                    return;
                }
                if (!response.isSuccessful()) {
                    Log.w(TAG, "SSE responded " + response.code());
                    response.close();
                    return;
                }
                sseReady = true;
                BufferedSource source = response.body() != null ? response.body().source() : null;
                if (source == null) {
                    response.close();
                    return;
                }
                Log.i(TAG, "SSE connected, session=" + shorten(sessionId));
                try {
                    while (running && !source.exhausted()) {
                        String line = source.readUtf8Line();
                        if (line == null) break;
                        if (line.isEmpty() || line.startsWith(":")) continue; // 事件边界/心跳注释
                        if (line.startsWith("data:")) {
                            String json = line.substring(5).trim();
                            handleSseEvent(json);
                        }
                    }
                } catch (IOException e) {
                    if (running) Log.w(TAG, "SSE read failed: " + e.getMessage());
                } finally {
                    response.close();
                    sseReady = false;
                }
            }
        });
    }

    private void closeSse() {
        sseReady = false;
        sseConnecting = false;
        okhttp3.Call c = sseCall;
        if (c != null) {
            c.cancel();
            sseCall = null;
        }
    }

    // ---------------- SSE 事件 → 状态 ----------------

    private void handleSseEvent(String json) {
        if (json == null || json.isEmpty()) return;
        try {
            JSONObject msg = new JSONObject(json);
            String type = msg.optString("type", "");
            OperitState mapped = mapEventType(msg, type);
            if (mapped == null) return; // 不驱动状态变化的事件
            pushEvent(eventLabel(type));
            applyDetailedState(mapped, msg, type);
        } catch (Throwable t) {
            Log.w(TAG, "sse parse failed", t);
        }
    }

    /** 事件 → OperitState；返回 null 表示该事件不驱动状态变化 */
    private static OperitState mapEventType(JSONObject msg, String type) {
        switch (type) {
            case "connected": {
                boolean streaming = msg.optBoolean("isStreaming", false);
                return streaming ? OperitState.WORKING : OperitState.IDLE;
            }
            case "message_start":
            case "extension_ui_request":
                return null; // message_start 不带内容类型，先等 thinking/text 帧再定状态
            case "message_update": {
                JSONObject ev = msg.optJSONObject("assistantMessageEvent");
                if (ev == null) return null;
                String t = ev.optString("type", "");
                if (t.startsWith("thinking_")) return OperitState.THINKING;
                if (t.startsWith("text_")) return OperitState.RESPONDING;
                if (t.startsWith("toolcall_")) return OperitState.TOOL_RUNNING;
                return null;
            }
            case "tool_execution_update":
                return OperitState.TOOL_RUNNING;
            case "tool_execution_end":
                return OperitState.THINKING; // 工具跑完，agent 回到思考/继续生成
            case "agent_end":
            case "session_shutdown":
                return OperitState.IDLE;
            case "startup_error":
                return OperitState.ERROR;
            default:
                return null;
        }
    }

    private static String eventLabel(String type) {
        switch (type) {
            case "connected":         return "🔌 已连接";
            case "message_start":     return "📨 开始生成";
            case "message_update":    return "💬 生成中";
            case "tool_execution_update": return "🔧 执行工具";
            case "tool_execution_end":  return "✅ 工具完成";
            case "agent_end":         return "🎉 回复完成";
            case "session_shutdown":  return "👋 会话结束";
            case "startup_error":     return "❌ 启动失败";
            default:                  return type;
        }
    }

    /** 用 SSE 的详细状态覆盖当前快照并推给 UI */
    private void applyDetailedState(OperitState detailed, JSONObject msg, String type) {
        OperitState.Snapshot snap = lastSnapshot;
        // IDLE 只在明确结束时落（agent_end/session_shutdown/connected 非流式），
        // 避免工具间隙的短暂回落造成状态闪烁
        snap.state = detailed;
        snap.operitRunning = detailed != OperitState.IDLE;
        snap.agentName = "pi-web";
        snap.lastActiveTime = System.currentTimeMillis();

        // 工具名记录到 lastTool
        if (detailed == OperitState.TOOL_RUNNING) {
            String toolName = msg.optString("toolName", "");
            if (toolName.isEmpty() && type.equals("message_update")) {
                JSONObject ev = msg.optJSONObject("assistantMessageEvent");
                if (ev != null) {
                    JSONObject tc = ev.optJSONObject("toolCall");
                    if (tc != null) toolName = tc.optString("name", "");
                }
            }
            if (!toolName.isEmpty()) snap.lastTool = toolName;
        }

        List<String> ev = new ArrayList<>(recentEvents);
        if (ev.isEmpty()) ev.add(stateBubble(detailed));
        snap.recentEvents = ev;
        mainHandler.post(() -> listener.onSnapshot(snap));
    }

    private static String stateBubble(OperitState s) {
        return s.getEmoji() + " " + s.getLabel();
    }

    // ---------------- 工具方法 ----------------

    private void pushEvent(String line) {
        recentEvents.addLast(line);
        while (recentEvents.size() > EVENT_HISTORY) recentEvents.removeFirst();
    }

    private static String shorten(String s) {
        return s != null && s.length() > 8 ? s.substring(0, 8) : (s != null ? s : "");
    }

    private static byte[] readAll(HttpURLConnection conn) throws Exception {
        try (InputStream in = conn.getInputStream();
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}
