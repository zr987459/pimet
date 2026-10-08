package com.xm486.pimet.monitor;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * ClawBench 检测器（github.com/clawbench-dev/clawbench）：
 * 通过本机 Web 服务（默认 20000 端口）拿【详细状态】（思考中/工具执行中/回复中）。
 *
 * 两个通道协同：
 *  1. GET /api/ai/sessions/overview  认证（cookie），拿当前 running 会话 id 列表
 *  2. WS  /api/ai/events/ws          认证（cookie），subscribe 各 running 会话，
 *    收 chat_stream 事件的 event_type：
 *       thinking     → 思考中（THINKING）
 *       tool_use     → 工具执行中（TOOL_RUNNING）
 *  content      → 回复中（RESPONDING）
 *       stream_start/user_message → 工作中（WORKING，刚发起、还没出字）
 *       done/cancelled/error       → 回落 overview 的会话级状态
 *
 * 认证流程：密码不能直接当 cookie。先 POST /login {"password":"..."}，
 * 服务端比对后下发 Set-Cookie: clawbench_session=<随机 token>（7 天有效），
 * 缓存到 cachedCookie，overview 与 WS 握手都带上它；401 时清缓存重新登录。
 *
 * 状态优先级（同时有多个会话时取最紧急）：
 *   TOOL_RUNNING > THINKING > RESPONDING > WORKING > WAITING(待审批) > IDLE
 */
public class ClawBenchMonitor {

    private static final String TAG = "DevPetM.ClawBench";
    private static final long POLL_INTERVAL_MS = 3000L;
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final int GONE_THRESHOLD = 3;
    private static final int EVENT_HISTORY = 12;
    private static final String CLIENT_ID = "devpetm-pet";

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

    /** 连续探测失败次数 */
    private int goneCount = 0;
    /** 最近一次有效快照（断连时沿用，避免状态闪烁） */
    private OperitState.Snapshot lastSnapshot = new OperitState.Snapshot();
    /** 最近事件 */
    private final ArrayDeque<String> recentEvents = new ArrayDeque<>();
    /** 事件去重键 */
    private String lastEventKey = "";

    /** 缓存的登录 cookie（POST /login 下发，7 天有效） */
    private volatile String cachedCookie = "";

    /** WS 客户端（长连接，收 chat_stream 事件） */
    private OkHttpClient wsClient;
    private WebSocket webSocket;
    private volatile boolean wsConnecting = false;
    /** 当前已 subscribe 的会话 id（overview 变化时重新订阅） */
    private final Set<String> subscribedSessions = new HashSet<>();

    public ClawBenchMonitor(Context context, int port, Listener listener) {
        this.port = port;
        this.appContext = context.getApplicationContext();
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new HandlerThread("clawbench-monitor");
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
        closeWebSocket();
        if (wsClient != null) {
            wsClient.dispatcher().executorService().shutdown();
            wsClient = null;
        }
        synchronized (subscribedSessions) {
            subscribedSessions.clear();
        }
        Log.i(TAG, "monitor stopped");
    }

    public boolean isRunning() {
        return running;
    }

    private void tick() {
        if (!running) return;
        try {
            // 每轮重读密码（主页改完立即生效）
            String token = com.xm486.pimet.pet.PetRegistry.getClawbenchToken(appContext);
            OperitState.Snapshot snap = pollOverview(token);
            if (snap != null) {
                goneCount = 0;
                lastSnapshot = snap;
                // overview 拿到 running 会话 id → 维护 WS 订阅
                maintainWebSocket(snap.sessionIds);
            } else {
                // 连不上或 401：计数，达到阈值才降级为 UNKNOWN
                goneCount++;
                closeWebSocket();
                if (goneCount >= GONE_THRESHOLD) {
                    OperitState.Snapshot unknown = new OperitState.Snapshot();
                    unknown.state = OperitState.UNKNOWN;
                    unknown.agentName = "ClawBench";
                    unknown.operitRunning = false;
                    unknown.recentEvents = new ArrayList<>(
                            java.util.Collections.singletonList(
                                    goneCount >= GONE_THRESHOLD + 1
                                            ? "ClawBench 不可达或密码错误（401）"
                                            : "ClawBench 连接中…"));
                    lastSnapshot = unknown;
                }
            }
            mainHandler.post(() -> listener.onSnapshot(lastSnapshot));
        } catch (Throwable t) {
            Log.e(TAG, "tick failed", t);
        }
        if (running) {
            handler.postDelayed(this::tick, POLL_INTERVAL_MS);
        }
    }

    /** 请求 /api/ai/sessions/overview；成功返回快照，失败（连接异常/401）返回 null */
    private OperitState.Snapshot pollOverview(String password) {
        // 先用缓存的 cookie；401 或无 cookie 时用密码登录换新 cookie
        String cookie = getCachedCookie();
        if (cookie.isEmpty()) {
            cookie = login(password);
            if (cookie.isEmpty()) return null; // 登录失败（密码错/服务不可达）
        }
        HttpURLConnection conn = null;
        try {
            URL u = new URL("http://127.0.0.1:" + port + "/api/ai/sessions/overview");
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "DevPetM/1.0 (pet monitor)");
            conn.setRequestProperty("Cookie", "clawbench_session=" + cookie);
            int code = conn.getResponseCode();
            if (code == 401) {
                // cookie 过期：立即清缓存，下轮 tick 会用密码重新登录
                cachedCookie = "";
                mainHandler.post(() -> listener.onError(
                        "ClawBench 401：cookie 已过期，正在用密码重新登录"));
                return null;
            }
            if (code != 200) {
                return null;
            }
            byte[] body = readAll(conn);
            JSONObject root = new JSONObject(new String(body, StandardCharsets.UTF_8));
            return parseOverview(root);
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 用登录密码换 cookie：POST /login {"password":"..."} → Set-Cookie: clawbench_session=...
     * ClawBench 的 cookie 是密码验证后下发的随机 token（SHA-256 密码本身不能当 cookie）。
     * 成功返回 cookie 值并缓存；失败返回空串。
     */
    private String login(String password) {
        if (password == null || password.isEmpty()) {
            mainHandler.post(() -> listener.onError("ClawBench 未配置登录密码"));
            return "";
        }
        HttpURLConnection conn = null;
        try {
            URL u = new URL("http://127.0.0.1:" + port + "/login");
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "DevPetM/1.0 (pet monitor)");
            conn.setDoOutput(true);
            byte[] body = ("{\"password\":\"" + password + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            conn.getOutputStream().write(body);
            int code = conn.getResponseCode();
            if (code != 200) {
                if (code == 401) {
                    mainHandler.post(() -> listener.onError(
                            "ClawBench 登录失败：密码错误"));
                }
                return "";
            }
            // 从 Set-Cookie 头取 clawbench_session 的值
            String setCookie = conn.getHeaderField("Set-Cookie");
            if (setCookie == null) return "";
            String cookie = parseCookieValue(setCookie);
            if (!cookie.isEmpty()) {
                cachedCookie = cookie;
            }
            return cookie;
        } catch (Throwable t) {
            return "";
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 从 Set-Cookie 头解析 clawbench_session 的值 */
    private static String parseCookieValue(String setCookie) {
        for (String part : setCookie.split(";")) {
            String p = part.trim();
            if (p.startsWith("clawbench_session=")) {
                return p.substring("clawbench_session=".length());
            }
        }
        return "";
    }

    private String getCachedCookie() {
        return cachedCookie == null ? "" : cachedCookie;
    }

    // ---------------- WebSocket：订阅 running 会话，收 chat_stream 详细事件 ----------------

    /** overview 有 running 会话时建连/补订阅；会话列表变了重连 */
    private void maintainWebSocket(List<String> runningIds) {
        if (runningIds == null || runningIds.isEmpty()) {
            closeWebSocket();
            return;
        }
        // cookie 没拿到（登录失败）就不连 WS，下轮 tick 会重试登录
        if (getCachedCookie().isEmpty()) return;

        Set<String> now = new HashSet<>(runningIds);
        synchronized (subscribedSessions) {
            if (webSocket != null && subscribedSessions.equals(now)) {
                return; // 已连且订阅集合没变，不用动
            }
        }
        closeWebSocket();
        connectWebSocket(now);
    }

    private void connectWebSocket(Set<String> sessions) {
        if (wsConnecting || webSocket != null) return;
        wsConnecting = true;
        if (wsClient == null) {
            wsClient = new OkHttpClient.Builder()
                    .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .readTimeout(0, TimeUnit.MILLISECONDS) // 长连接不超时
                    .pingInterval(30, TimeUnit.SECONDS)    // 应答服务端 ping
                    .build();
        }
        String url = "http://127.0.0.1:" + port + "/api/ai/events/ws?client_id=" + CLIENT_ID;
        Request request = new Request.Builder()
                .url(url)
                .header("Cookie", "clawbench_session=" + getCachedCookie())
                .build();
        Log.d(TAG, "ws connect: " + sessions.size() + " sessions");
        wsClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                wsConnecting = false;
                webSocket = ws;
                // 连上后订阅所有 running 会话
                for (String sid : sessions) {
                    sendSubscribe(ws, sid);
                }
                synchronized (subscribedSessions) {
                    subscribedSessions.clear();
                    subscribedSessions.addAll(sessions);
                }
                Log.d(TAG, "ws open, subscribed " + sessions.size());
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                handleWsMessage(text);
            }

            @Override
            public void onMessage(WebSocket ws, ByteString bytes) {
                handleWsMessage(bytes.utf8());
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                wsConnecting = false;
                int code = response != null ? response.code() : 0;
                Log.w(TAG, "ws failed: " + code + " " + t);
                if (code == 401) {
                    cachedCookie = ""; // cookie 过期，下轮 tick 重新登录
                }
                webSocket = null;
                synchronized (subscribedSessions) {
                    subscribedSessions.clear();
                }
                // 下一轮 tick 会重建连接
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                wsConnecting = false;
                webSocket = null;
                synchronized (subscribedSessions) {
                    subscribedSessions.clear();
                }
            }
        });
    }

    private void sendSubscribe(WebSocket ws, String sessionId) {
        try {
            ws.send("{\"type\":\"subscribe\",\"session_id\":\"" + sessionId + "\"}");
        } catch (Throwable t) {
            Log.w(TAG, "sendSubscribe failed", t);
        }
    }

    private void closeWebSocket() {
        WebSocket ws = webSocket;
        webSocket = null;
        wsConnecting = false;
        if (ws != null) {
            try {
                ws.close(1000, "monitor stopping");
            } catch (Throwable ignored) {}
        }
        synchronized (subscribedSessions) {
            subscribedSessions.clear();
        }
    }

    /** 解析服务端 WS 消息：{"type":"event","event":"chat_stream","data":{...}} 或 ping */
    private void handleWsMessage(String text) {
        if (text == null || text.isEmpty()) return;
        try {
            JSONObject msg = new JSONObject(text);
            String type = msg.optString("type", "");
            if ("ping".equals(type)) {
                // 服务端心跳，OkHttp 的 pingInterval 会自动应答；这里也兜底回 pong
                WebSocket ws0 = webSocket;
                if (ws0 != null) {
                    ws0.send("{\"type\":\"pong\"}");
                }
                return;
            }
            if (!"event".equals(type)) return;
            String event = msg.optString("event", "");
            JSONObject data = msg.optJSONObject("data");
            if (data == null) return;

            switch (event) {
                case "chat_stream":
                    handleChatStream(data);
                    break;
                case "session_update":
                    handleSessionUpdate(data);
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            Log.w(TAG, "ws message parse failed", t);
        }
    }

    /** chat_stream：event_type → 详细状态（核心） */
    private void handleChatStream(JSONObject data) {
        String sessionId = data.optString("session_id", "");
        String eventType = data.optString("event_type", "");
        if (eventType.isEmpty()) return;

        OperitState detailed = mapEventType(eventType);
        if (detailed == null) return; // usage_update/metadata 等不改变状态

        Log.d(TAG, "chat_stream " + eventType + " session=" + shorten(sessionId));
        pushEvent("📨 " + eventLabel(eventType));
        applyDetailedState(detailed);
    }

    /** event_type → OperitState；返回 null 表示该事件不驱动状态变化 */
    private static OperitState mapEventType(String eventType) {
        switch (eventType) {
            case "stream_start":
            case "user_message":
                return OperitState.WORKING;       // 刚发起，还没出字
            case "thinking":
                return OperitState.THINKING;      // 思考中
            case "tool_use":
            case "tool_result":
                return OperitState.TOOL_RUNNING;  // 工具执行中
            case "content":
                return OperitState.RESPONDING;    // 回复中（流式输出）
            case "done":
            case "replay_done":
                return OperitState.IDLE;          // 本轮完成 → 回落 overview
            case "error":
                return OperitState.ERROR;
            default:
                return null; // usage_update / metadata / plan_update 等不改变状态
        }
    }

    private static String eventLabel(String eventType) {
        switch (eventType) {
            case "stream_start": return "开始生成";
            case "user_message": return "收到消息";
            case "thinking":     return "思考中";
            case "tool_use":     return "调用工具";
            case "tool_result":  return "工具结果";
            case "content":      return "回复中";
            case "done":         return "完成";
            case "error":        return "出错";
            default:             return eventType;
        }
    }

    /** session_update：会话级状态（permission_pending → 待审批；completed → 回落） */
    private void handleSessionUpdate(JSONObject data) {
        String status = data.optString("status", "");
        if (status.isEmpty()) return;
        if ("permission_pending".equals(status)) {
            pushEvent("🖐 待审批");
            applyDetailedState(OperitState.WAITING);
        } else if ("completed".equals(status) || "cancelled".equals(status)
                || "permission_resolved".equals(status)) {
            applyDetailedState(OperitState.IDLE);
        }
    }

    /**
     * 用 WS 的详细状态覆盖当前快照（WS 事件比 overview 轻轮询更实时）。
     * IDLE 不覆盖：交给下一轮 overview 自然回落，避免 done 与 overview 抢状态闪烁。
     */
    private void applyDetailedState(OperitState detailed) {
        if (detailed == OperitState.IDLE) return;
        OperitState.Snapshot snap = lastSnapshot;
        snap.state = detailed;
        snap.operitRunning = true;
        snap.lastActiveTime = System.currentTimeMillis();
        List<String> ev = new ArrayList<>(recentEvents);
        if (ev.isEmpty()) ev.add(stateBubble(detailed));
        snap.recentEvents = ev;
        mainHandler.post(() -> listener.onSnapshot(snap));
    }

    private static String stateBubble(OperitState s) {
        return s.getEmoji() + " " + s.getLabel();
    }

    // ---------------- overview 解析 ----------------

    /** 解析 overview JSON → 快照 */
    private OperitState.Snapshot parseOverview(JSONObject root) {
        OperitState.Snapshot snap = new OperitState.Snapshot();
        snap.agentName = "ClawBench";
        snap.operitRunning = true; // 复用该布尔位：ClawBench 服务可达

        boolean anyRunning = false, anyPending = false, anyUnread = false;
        String model = "";
        List<String> events = new ArrayList<>();
        List<String> runningIds = new ArrayList<>();

        JSONArray projects = root.optJSONArray("projects");
        if (projects != null) {
            for (int i = 0; i < projects.length(); i++) {
                JSONObject p = projects.optJSONObject(i);
                if (p == null) continue;
                String pName = p.optString("name", "");
                JSONArray sessions = p.optJSONArray("sessions");
                if (sessions == null) continue;
                for (int j = 0; j < sessions.length(); j++) {
                    JSONObject s = sessions.optJSONObject(j);
                    if (s == null) continue;
                    String id = s.optString("id", "");
                    String title = s.optString("title", id);
                    String backend = s.optString("backend", "");
                    String agentId = s.optString("agentId", "");
                    String m = s.optString("model", "");
                    boolean isRunning = s.optBoolean("running", false);
                    boolean isPending = s.optBoolean("pendingApproval",  false);
                    int unread = s.optInt("unreadCount", 0);
                    if (isRunning) {
                        anyRunning = true;
                        runningIds.add(id);
                    }
                    if (isPending) anyPending = true;
                    if (unread > 0) anyUnread = true;
                    if (isRunning && !m.isEmpty()) model = m;
                    if (model.isEmpty() && !m.isEmpty()) model = m;

                    String icon;
                    String desc;
                    if (isRunning) { icon = "🏃"; desc = "执行中"; }
                    else if (isPending) { icon = "🖐"; desc = "待审批"; }
                    else if (unread > 0) { icon = "✉️"; desc = "未读×" + unread; }
                    else { icon = "💤"; desc = "空闲"; }
                    StringBuilder line = new StringBuilder();
                    line.append(icon).append(' ').append(desc);
                    if (!backend.isEmpty() || !agentId.isEmpty()) {
                        line.append('·').append(backend).append('/').append(agentId);
                    }
                    if (!m.isEmpty()) line.append('·').append(m);
                    if (!title.isEmpty()) line.append('·').append(trunc(title, 24));
                    if (!pName.isEmpty()) line.append('·').append(trunc(pName, 16));
                    events.add(line.toString());
                }
            }
        }

        // 最紧急优先：running > pending > unread > idle
        if (anyRunning) snap.state = OperitState.WORKING;
        else if (anyPending) snap.state = OperitState.WAITING;
        else if (anyUnread) snap.state = OperitState.RESPONDING;
        else snap.state = OperitState.IDLE;

        snap.model = model;
        snap.lastActiveTime = System.currentTimeMillis();
        snap.sessionIds = runningIds;

        // 事件去重：仅当事件列表变化时入队
        String key = snap.state.getLabel() + "|" + model + "|" + String.join("\n", events);
        if (!key.equals(lastEventKey) && !events.isEmpty()) {
            for (String e : events) pushEvent(e);
            lastEventKey = key;
        }
        snap.recentEvents = new ArrayList<>(events);

        Log.d(TAG, "overview: " + snap.state.getLabel()
                + " running=" + anyRunning + " pending=" + anyPending
                + " unread=" + anyUnread + " model=" + model
                + " ws=" + (webSocket != null));
        return snap;
    }

    private void pushEvent(String line) {
        recentEvents.addLast(line);
        while (recentEvents.size() > EVENT_HISTORY) recentEvents.removeFirst();
    }

    private static String shorten(String s) {
        if (s == null) return "";
        return s.length() <= 8 ? s : s.substring(0, 8) + "…";
    }

    private static String trunc(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static byte[] readAll(HttpURLConnection conn) throws Exception {
        InputStream in = conn.getInputStream();
        if (in == null) return new byte[0];
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}