package com.xm486.pimet.monitor;

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

/**
 * RikkaHub 检测器：通过本地 Web API 轮询会话状态。
 *
 * 通道：
 *  - GET /api/conversations         → 会话列表，找 isGenerating=true 的会话
 *  - GET /api/conversations/{id}    → 会话详情，解析最后一条消息的 parts / usage / modelId
 *
 * 状态细分（依据最后一条 assistant 消息「最后一个 part」的类型）：
 *  - tool + approvalState=pending → WAITING（等待用户审批）
 *  - tool（其他状态）             → TOOL_RUNNING
 *  - reasoning 且未 finished      → THINKING
 *  - text 且生成中                → RESPONDING
 *  - 生成中但无以上特征           → WORKING
 *  - 未生成                       → IDLE
 *
 * 需要用户在 RikkaHub 设置中开启「Web 访问」（localhost only）。
 */
public class RikkaHubMonitor {

    private static final String TAG = "DevPetM.Rikka";
    private static final long POLL_INTERVAL_MS = 1000L;
    /** 最近事件容量 */
    private static final int EVENT_HISTORY = 12;

    public interface Listener {
        void onSnapshot(OperitState.Snapshot snapshot);
        void onError(String message);
    }

    private final int port;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private HandlerThread thread;
    private Handler handler;
    private volatile boolean running = false;

    /** RikkaHub 是否可达（web server 开启） */
    private volatile boolean reachable = false;

    // ---- 累计信息 ----
    /** 累计 token（本次监控周期内，按会话去重累加 usage） */
    private long inputTokens = 0;
    private long outputTokens = 0;
    /** 已统计过 usage 的消息 id（避免重复累加） */
    private final java.util.Set<String> countedMsgIds = new java.util.HashSet<>();
    /** 最近事件（工具调用/思考/回复等） */
    private final java.util.ArrayDeque<String> recentEvents = new java.util.ArrayDeque<>();
    private String currentModel = "";
    private String lastTool = "";
    /** 最近一次的状态描述（用于事件去重） */
    private String lastEventKey = "";

    public RikkaHubMonitor(int port, Listener listener) {
        this.port = port;
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new HandlerThread("rikkahub-monitor");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(this::tick);
        Log.i(TAG, "rikkahub monitor started, port=" + port);
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
        Log.i(TAG, "rikkahub monitor stopped");
    }

    public boolean isRunning() {
        return running;
    }

    /** 最近一次轮询是否可达 */
    public boolean isReachable() {
        return reachable;
    }

    private void tick() {
        if (!running) return;
        try {
            // 1) 拉取会话列表，找生成中的会话
            String listBody = httpGet("/api/conversations");
            JSONArray arr = new JSONArray(listBody);
            String activeId = null;
            String latestId = null;
            long latestUpdate = -1;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject c = arr.optJSONObject(i);
                if (c == null) continue;
                if (c.optBoolean("isGenerating", false) && activeId == null) {
                    activeId = c.optString("id", null);
                }
                long up = c.optLong("updateAt", 0);
                if (up > latestUpdate) {
                    latestUpdate = up;
                    latestId = c.optString("id", null);
                }
            }
            reachable = true;

            // 2) 拉取详情（生成中的会话，或最近更新的会话）解析细节
            String targetId = activeId != null ? activeId : latestId;
            OperitState state = (activeId != null) ? OperitState.WORKING : OperitState.IDLE;
            if (targetId != null) {
                state = parseConversation(targetId, activeId != null, state);
            }
            notifySnapshot(state);
        } catch (Throwable t) {
            // 请求失败：RikkaHub 未运行 / Web 未开启 / 端口不对
            reachable = false;
            notifyError("RikkaHub: " + t.getMessage());
            notifySnapshot(OperitState.UNKNOWN);
        } finally {
            if (running && handler != null) {
                handler.postDelayed(this::tick, POLL_INTERVAL_MS);
            }
        }
    }

    /**
     * 解析会话详情：细分状态 + 提取模型/token/工具/事件。
     *
     * @param generating 该会话是否正在生成
     * @param fallback   无法细分时的兜底状态
     */
    private OperitState parseConversation(String convId, boolean generating,
                                          OperitState fallback) {
        try {
            String body = httpGet("/api/conversations/" + convId);
            JSONObject conv = new JSONObject(body);
            JSONArray nodes = conv.optJSONArray("messages");
            if (nodes == null || nodes.length() == 0) return fallback;

            // 遍历所有节点累计 token（按消息 id 去重）
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject node = nodes.optJSONObject(i);
                if (node == null) continue;
                JSONArray msgs = node.optJSONArray("messages");
                if (msgs == null) continue;
                for (int j = 0; j < msgs.length(); j++) {
                    JSONObject m = msgs.optJSONObject(j);
                    if (m == null) continue;
                    accumulateUsage(m);
                }
            }

            // 取最后一个节点的最后一条消息细分状态
            JSONObject lastNode = nodes.optJSONObject(nodes.length() - 1);
            if (lastNode == null) return fallback;
            JSONArray msgs = lastNode.optJSONArray("messages");
            if (msgs == null || msgs.length() == 0) return fallback;
            JSONObject last = msgs.optJSONObject(msgs.length() - 1);
            if (last == null) return fallback;

            // 模型名：直接用消息里的 modelId 尾部（UUID 无法映射时不展示误导名）
            String modelId = last.optString("modelId", "");
            if (!modelId.isEmpty() && !"null".equals(modelId)) {
                currentModel = shortModel(modelId);
            }

            if (!generating) {
                return OperitState.IDLE;
            }
            return classifyParts(last);
        } catch (Throwable t) {
            Log.w(TAG, "parseConversation failed", t);
            return fallback;
        }
    }

    /** modelId 是 UUID 时不展示（避免误导），否则原样显示模型名 */
    private String shortModel(String modelId) {
        // UUID 形如 8-4-4-4-12，含 '-' 且长度 36：RikkaHub 存的是模型引用 UUID，无意义
        if (modelId.length() == 36 && modelId.chars().filter(c -> c == '-').count() == 4) {
            return "";
        }
        return modelId;
    }

    /** 累计 usage（promptTokens / completionTokens），按消息 id 去重 */
    private void accumulateUsage(JSONObject msg) {
        JSONObject usage = msg.optJSONObject("usage");
        if (usage == null) return;
        String id = msg.optString("id", "");
        if (id.isEmpty() || countedMsgIds.contains(id)) return;
        countedMsgIds.add(id);
        inputTokens += usage.optLong("promptTokens", 0);
        outputTokens += usage.optLong("completionTokens", 0);
    }

    /**
     * 依据最后一条消息「最后一个 part」的类型细分状态：
     * 生成过程中 parts 顺序追加（reasoning→text→tool…），最后一个 part
     * 代表当前正在进行的阶段，比"任意 part 存在性"更准确。
     *  tool(pending)→WAITING、tool→TOOL_RUNNING、
     *  reasoning→THINKING、text→RESPONDING、其他→WORKING
     */
    private OperitState classifyParts(JSONObject msg) {
        JSONArray parts = msg.optJSONArray("parts");
        if (parts == null || parts.length() == 0) return OperitState.WORKING;

        // 从后往前找第一个"有意义"的 part（跳过空 text）
        JSONObject lastPart = null;
        String lastType = "";
        for (int i = parts.length() - 1; i >= 0; i--) {
            JSONObject p = parts.optJSONObject(i);
            if (p == null) continue;
            String type = p.optString("type", "");
            if ("text".equals(type) && p.optString("text", "").isEmpty()) {
                continue; // 空 text 跳过
            }
            lastPart = p;
            lastType = type;
            break;
        }
        if (lastPart == null) return OperitState.WORKING;

        OperitState state;
        if ("tool".equals(lastType) || "tool_call".equals(lastType)) {
            String toolName = lastPart.optString("toolName", "");
            if (!toolName.isEmpty()) lastTool = toolName;
            if ("pending".equals(approvalOf(lastPart))) {
                state = OperitState.WAITING;
                addEvent("⏳ 等待工具审批：" + lastTool);
            } else {
                state = OperitState.TOOL_RUNNING;
                addEvent("🔧 工具调用：" + lastTool);
            }
        } else if ("reasoning".equals(lastType)) {
            state = OperitState.THINKING;
            addEvent("🤔 思考中…");
        } else if ("text".equals(lastType)) {
            state = OperitState.RESPONDING;
            addEvent("💬 回复中…");
        } else {
            state = OperitState.WORKING;
            addEvent("💪 处理请求…");
        }
        return state;
    }

    /** 提取 approvalState 的类型名（兼容对象/字符串两种序列化） */
    private String approvalOf(JSONObject part) {
        Object obj = part.opt("approvalState");
        if (obj == null) return "";
        if (obj instanceof String) return (String) obj;
        if (obj instanceof JSONObject) {
            JSONObject o = (JSONObject) obj;
            String type = o.optString("type", "");
            if (!type.isEmpty()) return type;
            // kotlinx 可能用 "class discriminator" 之外的键名
            java.util.Iterator<String> it = o.keys();
            if (it.hasNext()) return o.optString(it.next(), "");
        }
        return "";
    }

    /** 记录事件（同一状态不重复记） */
    private void addEvent(String ev) {
        if (ev.equals(lastEventKey)) return;
        lastEventKey = ev;
        recentEvents.addLast(ev);
        while (recentEvents.size() > EVENT_HISTORY) {
            recentEvents.removeFirst();
        }
    }

    /** 统一 HTTP GET */
    private String httpGet(String path) throws Exception {
        URL url = new URL("http://127.0.0.1:" + port + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(4000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new Exception("HTTP " + code + " " + path);
            }
            return readAll(conn.getInputStream());
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private void notifySnapshot(final OperitState state) {
        final OperitState.Snapshot s = new OperitState.Snapshot();
        s.state = state;
        s.agentName = "RikkaHub";
        s.operitRunning = reachable;
        s.model = currentModel;
        s.provider = currentModel.isEmpty() ? "" : "RikkaHub";
        s.inputTokens = inputTokens;
        s.outputTokens = outputTokens;
        s.lastTool = lastTool;
        s.lastActiveTime = System.currentTimeMillis();
        s.recentEvents = new java.util.ArrayList<>(recentEvents);
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
