package com.xm486.pimet.pet;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSource;

/**
 * Pi-Web (@agegr/pi-web) Agent 事件监控器：
 * 监听本地 Pi-Web 的 SSE 事件流，实时提取 Agent 思考、工具执行与任务完成状态，
 * 驱动桌宠做出相应的待机、思考、奔跑、欢呼动作与对话气泡提示。
 */
public class PiWebMonitor {

    private static final String TAG = "PiMet.PetMonitor";
    private static final long POLL_INTERVAL_MS = 4000L;
    private static final int EVENT_HISTORY = 10;

    public interface Listener {
        void onSnapshot(PetAgentState.Snapshot snapshot);
        void onError(String message);
    }

    private final int port;
    private final Context appContext;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private HandlerThread thread;
    private Handler workerHandler;
    private volatile boolean running = false;

    private int goneCount = 0;
    private PetAgentState.Snapshot lastSnapshot = newSnapshotIdle();
    private final ArrayDeque<String> recentEvents = new ArrayDeque<>();

    private OkHttpClient sseClient;
    private Call sseCall;
    private volatile boolean sseConnecting = false;
    private volatile String watchedSessionId = null;

    public PiWebMonitor(Context context, int port, Listener listener) {
        this.port = port;
        this.appContext = context.getApplicationContext();
        this.listener = listener;
    }

    private static PetAgentState.Snapshot newSnapshotIdle() {
        PetAgentState.Snapshot s = new PetAgentState.Snapshot();
        s.state = PetAgentState.IDLE;
        s.agentName = "pi-web";
        return s;
    }

    public synchronized void start() {
        if (running) return;
        running = true;

        thread = new HandlerThread("PiWebMonitorThread");
        thread.start();
        workerHandler = new Handler(thread.getLooper());

        sseClient = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .connectTimeout(3000, TimeUnit.MILLISECONDS)
                .build();

        workerHandler.post(pollRunnable);
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;

        if (workerHandler != null) {
            workerHandler.removeCallbacksAndMessages(null);
        }
        closeSse();

        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
        workerHandler = null;
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) return;

            try {
                pollOnce();
            } catch (Throwable t) {
                Log.w(TAG, "poll error: " + t.getMessage());
            }

            if (running && workerHandler != null) {
                workerHandler.postDelayed(this, POLL_INTERVAL_MS);
            }
        }
    };

    private void pollOnce() {
        String sessionsJson = fetchSessions();
        if (sessionsJson == null) {
            goneCount++;
            if (goneCount >= 3) {
                closeSse();
                watchedSessionId = null;
                lastSnapshot.isAlive = false;
                lastSnapshot.state = PetAgentState.IDLE;
                dispatchSnapshot(lastSnapshot);
            }
            return;
        }

        goneCount = 0;
        lastSnapshot.isAlive = true;

        String activeSession = pickMostRecentSession(sessionsJson);
        if (activeSession != null) {
            if (!activeSession.equals(watchedSessionId)) {
                closeSse();
                watchedSessionId = activeSession;
                connectSse(activeSession);
            } else if (sseCall == null && !sseConnecting) {
                connectSse(activeSession);
            }
        } else {
            closeSse();
            watchedSessionId = null;
            lastSnapshot.state = PetAgentState.IDLE;
            dispatchSnapshot(lastSnapshot);
        }
    }

    private String fetchSessions() {
        try {
            URL url = new URL("http://127.0.0.1:" + port + "/api/sessions");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() != 200) {
                conn.disconnect();
                return null;
            }
            try (InputStream in = conn.getInputStream();
                 java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                return bos.toString("UTF-8");
            }
        } catch (Throwable e) {
            return null;
        }
    }

    private String pickMostRecentSession(String jsonStr) {
        try {
            JSONArray arr = new JSONArray(jsonStr);
            if (arr.length() == 0) return null;
            long latestTime = -1;
            String bestId = null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String id = obj.optString("id", null);
                long mod = obj.optLong("modified", 0);
                if (id != null && mod >= latestTime) {
                    latestTime = mod;
                    bestId = id;
                }
            }
            return bestId != null ? bestId : (arr.length() > 0 ? arr.getJSONObject(0).optString("id", null) : null);
        } catch (Throwable e) {
            return null;
        }
    }

    private void connectSse(String sessionId) {
        if (sseConnecting) return;
        sseConnecting = true;

        Request request = new Request.Builder()
                .url("http://127.0.0.1:" + port + "/api/agent/" + sessionId + "/events")
                .header("Accept", "text/event-stream")
                .build();

        sseCall = sseClient.newCall(request);
        sseCall.enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                sseConnecting = false;
                sseCall = null;
            }

            @Override
            public void onResponse(Call call, Response response) {
                sseConnecting = false;
                if (!response.isSuccessful()) {
                    response.close();
                    sseCall = null;
                    return;
                }

                BufferedSource source = response.body().source();
                try {
                    while (running && !source.exhausted()) {
                        String line = source.readUtf8Line();
                        if (line == null) break;
                        line = line.trim();
                        if (line.startsWith("data:")) {
                            String data = line.substring(5).trim();
                            if (!data.isEmpty()) {
                                handleSseFrame(data);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    response.close();
                    sseCall = null;
                }
            }
        });
    }

    private void handleSseFrame(String jsonStr) {
        try {
            JSONObject obj = new JSONObject(jsonStr);
            String type = obj.optString("type", "");

            PetAgentState newState = null;
            String toolName = null;

            switch (type) {
                case "connected":
                    boolean isStreaming = obj.optBoolean("isStreaming", false);
                    newState = isStreaming ? PetAgentState.WORKING : PetAgentState.IDLE;
                    break;
                case "message_start":
                    newState = PetAgentState.WORKING;
                    break;
                case "message_update":
                    JSONObject evt = obj.optJSONObject("assistantMessageEvent");
                    if (evt != null) {
                        String evtType = evt.optString("type", "");
                        if (evtType.startsWith("thinking_")) {
                            newState = PetAgentState.THINKING;
                        } else if (evtType.startsWith("text_")) {
                            newState = PetAgentState.RESPONDING;
                        } else if (evtType.startsWith("toolcall_")) {
                            newState = PetAgentState.TOOL_RUNNING;
                            toolName = evt.optString("toolName", "");
                        }
                    }
                    break;
                case "tool_execution_update":
                    newState = PetAgentState.TOOL_RUNNING;
                    toolName = obj.optString("toolName", "");
                    break;
                case "tool_execution_end":
                    newState = PetAgentState.THINKING;
                    break;
                case "agent_end":
                case "session_shutdown":
                    newState = PetAgentState.IDLE;
                    break;
                case "startup_error":
                    newState = PetAgentState.ERROR;
                    break;
            }

            if (newState != null) {
                lastSnapshot.state = newState;
                if (toolName != null && !toolName.isEmpty()) {
                    lastSnapshot.lastTool = toolName;
                }
                lastSnapshot.lastActiveTime = System.currentTimeMillis();
                recordEvent(newState.getEmoji() + " " + newState.getLabel() + (toolName != null && !toolName.isEmpty() ? " (" + toolName + ")" : ""));
                dispatchSnapshot(lastSnapshot);
            }
        } catch (Throwable ignored) {}
    }

    private void recordEvent(String desc) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String item = "[" + time + "] " + desc;
        synchronized (recentEvents) {
            while (recentEvents.size() >= EVENT_HISTORY) {
                recentEvents.pollFirst();
            }
            recentEvents.addLast(item);
            lastSnapshot.recentEvents = new ArrayList<>(recentEvents);
        }
    }

    private void closeSse() {
        if (sseCall != null) {
            try {
                sseCall.cancel();
            } catch (Throwable ignored) {}
            sseCall = null;
        }
        sseConnecting = false;
    }

    private void dispatchSnapshot(PetAgentState.Snapshot snapshot) {
        mainHandler.post(() -> {
            if (running && listener != null) {
                listener.onSnapshot(snapshot);
            }
        });
    }
}
