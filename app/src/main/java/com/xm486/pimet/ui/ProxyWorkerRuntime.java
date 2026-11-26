package com.xm486.pimet.ui;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.MutableContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.SystemClock;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.annotation.RequiresApi;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 独立代理子进程（{@code :proxy_worker_1..4}）浏览器运行时与本地 JSON-RPC 服务端。
 * <p>
 * 严格初始化顺序：
 * <ol>
 *   <li>进程内首次调用 {@link WebView#setDataDirectorySuffix(String)}（API 28+）；在此之前绝不触碰任何 WebView 相关类；</li>
 *   <li>初始化 {@link OffscreenWebHost} 与本进程独享 {@link ContainerProxyManager}；</li>
 *   <li>初始化只读配置快照（{@link BrowserCustomizer} / {@link UserScriptManager}）；</li>
 *   <li>启动回环 {@code 127.0.0.1:0} JSON-RPC 服务（{@code POST /cancel} 无锁快路径 + {@code POST /rpc} 按 tab 分锁）。</li>
 * </ol>
 */
public final class ProxyWorkerRuntime {

    private static final String TAG = "ProxyWorkerRuntime";
    private static final AtomicBoolean sSuffixSet = new AtomicBoolean(false);
    private static volatile String sBoundSuffix = "";

    private static final String NEED_AGENT = "__PIMET_NEED_AGENT__";
    private static final long GRACE_MS = 8000L;
    private static final long LOCK_WAIT_MAX = 15000L;
    private static final long SETTLE_MAX = 1500L;
    private static final int MAX_RPC_BODY = 4 * 1024 * 1024;
    private static final int MAX_CONSOLE = 300;
    private static final int MAX_FRAME_SESSIONS = 64;
    private static final int GM_MAX_BODY = 30 * 1024 * 1024;

    private static volatile String sAgentJs;
    private static volatile String sAgentVer;
    private static volatile String sDefaultUa;

    /**
     * 必须在任何 {@link WebView} / {@link CookieManager} / {@link WebSettings} / {@link ContainerEnv#resolveChromeVersion}
     * 调用之前调用。同进程幂等，仅第一次生效。
     */
    public static String ensureDataDirectorySuffix(String suffix) {
        String s = (suffix == null || suffix.trim().isEmpty()) ? "pw_default" : suffix.trim();
        if (sSuffixSet.compareAndSet(false, true)) {
            if (Build.VERSION.SDK_INT >= 28) {
                WebView.setDataDirectorySuffix(s);
            }
            sBoundSuffix = s;
        }
        return sBoundSuffix;
    }

    public static String boundSuffix() {
        return sBoundSuffix;
    }

    // ------------------------------------------------------------------ 实例字段

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final int slot;
    private final String containerId;
    private volatile String label;
    private volatile ContainerEnv.Profile profile;
    private final String workerToken;
    private final File appsDir;
    private final int mainCallbackPort;
    private final String mainToken;
    private volatile String customUa;

    private final OffscreenWebHost offscreenHost;
    private final ContainerProxyManager proxyManager;
    private volatile BrowserCustomizer customizer;
    private volatile UserScriptManager scriptMgr;

    private final Object tabTableLock = new Object();
    private final Map<String, WorkerTab> tabs = new LinkedHashMap<>();
    private int tabSeq = 0;

    private final ConcurrentHashMap<String, Req> inflight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> preCancelled = new ConcurrentHashMap<>();
    private final ThreadLocal<Req> curReq = new ThreadLocal<>();

    private final ServerSocket serverSocket;
    private final int rpcPort;
    private final Thread acceptThread;
    private final ExecutorService rpcPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "pimet-pw-rpc");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService eventPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pimet-pw-events");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // ------------------------------------------------------------------ 内部类型

    private static final class FrameSession {
        final String frameId;
        final boolean isMainFrame;
        volatile String url;
        volatile JavaScriptReplyProxy replyProxy;

        FrameSession(String frameId, boolean isMainFrame, String url, JavaScriptReplyProxy replyProxy) {
            this.frameId = frameId;
            this.isMainFrame = isMainFrame;
            this.url = url == null ? "" : url;
            this.replyProxy = replyProxy;
        }
    }

    private static final class WorkerTab {
        final String id;
        final boolean pi;
        final ReentrantLock opLock = new ReentrantLock();
        final String frameSecret = UUID.randomUUID().toString();
        final ConcurrentHashMap<String, FrameSession> frames = new ConcurrentHashMap<>();
        final LinkedHashMap<String, LinkedHashMap<String, String>> menus = new LinkedHashMap<>();
        final ArrayDeque<JSONObject> console = new ArrayDeque<>();

        MutableContextWrapper tabCtx;
        WebView web;
        Object surfaceHost;
        FrameLayout surfaceContainer;
        volatile boolean surfaceAttached = false;
        long surfaceAttachSeq = 0L;
        int surfaceW = 0, surfaceH = 0;
        float surfaceDensity = 1f;
        IBinder hostToken;
        IBinder.DeathRecipient hostDeathRecipient;
        View.OnLayoutChangeListener surfaceLayoutListener;
        int lastViewportSyncW = 0, lastViewportSyncH = 0;
        boolean lastViewportSyncDesktop = false;
        boolean lastViewportSyncAttached = false;
        volatile String url = "";
        volatile String title = "";
        volatile boolean loading = false;
        volatile int progress = 0;
        volatile boolean canGoBack = false;
        volatile boolean canGoForward = false;
        volatile long lastFocusNotifyAt = 0L;
        long lastProgressReportAt = 0L;
        volatile boolean closed = false;
        volatile int navSeq = 0;
        volatile int finishedSeq = 0;
        volatile int errorCode = 0;
        volatile String errorDesc = "";
        volatile int httpStatus = 0;
        volatile boolean desktop = false;
        volatile JSONObject site = new JSONObject();
        String siteUaMode;
        boolean siteDark;
        ScriptHandler envDocStartHandler;
        ScriptHandler frameDocStartHandler;
        String lastAppliedEnvKey;

        WorkerTab(String id, boolean pi, boolean desktop) {
            this.id = id;
            this.pi = pi;
            this.desktop = desktop;
        }
    }

    private static final class Req {
        final String id;
        final int timeout;
        final long start;
        final long softDeadline;
        final long hardDeadline;
        volatile boolean cancelled;
        volatile String tabSpec = "";
        volatile WorkerTab tab;

        Req(String id, int timeout, long extraMs) {
            this.id = id;
            this.timeout = timeout;
            this.start = SystemClock.uptimeMillis();
            this.softDeadline = start + timeout + extraMs;
            this.hardDeadline = softDeadline + GRACE_MS;
        }
    }

    private static class AbortException extends RuntimeException {
        AbortException(String m) { super(m); }
    }

    public interface InitCallback {
        void onReady(boolean ok, String error);
    }

    // ------------------------------------------------------------------ 构造与生命周期

    public ProxyWorkerRuntime(
            Context ctx,
            int slot,
            String containerId,
            String label,
            String profileJson,
            String workerToken,
            String appsDirPath,
            int mainCallbackPort,
            String mainToken,
            String customUa,
            InitCallback initCallback
    ) throws IOException {
        if (ctx == null) throw new IllegalArgumentException("ctx == null");
        String cid = (containerId == null || containerId.trim().isEmpty()) ? ContainerManager.DEFAULT : containerId.trim();
        ensureDataDirectorySuffix(ContainerManager.workerDataDirSuffix(cid));

        Context app = ctx.getApplicationContext();
        this.appContext = app != null ? app : ctx;
        this.slot = slot;
        this.containerId = cid;
        this.label = (label == null || label.trim().isEmpty()) ? cid : label.trim();
        this.workerToken = workerToken == null ? "" : workerToken;
        this.appsDir = (appsDirPath != null && !appsDirPath.trim().isEmpty())
                ? new File(appsDirPath.trim())
                : new File(this.appContext.getFilesDir(), "rootfs/root/pimet-browser");
        this.mainCallbackPort = mainCallbackPort;
        this.mainToken = mainToken == null ? "" : mainToken;
        this.customUa = (customUa != null && !customUa.trim().isEmpty()) ? customUa.trim() : null;
        this.profile = parseProfileJson(profileJson);

        ensureAgentJs(this.appContext);
        this.offscreenHost = new OffscreenWebHost(this.appContext);
        this.proxyManager = new ContainerProxyManager(this.appContext, null);
        this.customizer = new BrowserCustomizer(this.appsDir);
        this.scriptMgr = new UserScriptManager(this.appsDir);

        this.serverSocket = new ServerSocket(0, 64, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
        this.rpcPort = this.serverSocket.getLocalPort();
        this.acceptThread = new Thread(this::acceptLoop, "pimet-pw-accept-" + this.rpcPort);
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();

        applyInitialProxy(initCallback);
    }

    private void applyInitialProxy(InitCallback cb) {
        final ContainerEnv.Profile prof = this.profile;
        Runnable finish = () -> {
            if (closed.get()) {
                if (cb != null) cb.onReady(false, "Runtime已关闭");
                return;
            }
            if (prof != null && prof.hasActiveProxy()) {
                if (!proxyManager.isProxySupported()) {
                    if (cb != null) cb.onReady(false, "当前系统 WebView 不支持 ProxyController (PROXY_OVERRIDE)");
                    return;
                }
                if (!proxyManager.matchesActive(prof.proxySignature())) {
                    String err = proxyManager.getLastError();
                    if (cb != null) cb.onReady(false, (err != null && !err.isEmpty()) ? err : "初始化容器代理失败");
                    return;
                }
            }
            if (cb != null) cb.onReady(true, "");
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            proxyManager.applyForContainer(containerId, label, prof, Collections.emptyList(), finish);
        } else {
            main.post(() -> proxyManager.applyForContainer(containerId, label, prof, Collections.emptyList(), finish));
        }
    }

    public int getPort() {
        return rpcPort;
    }

    public int getPid() {
        return Process.myPid();
    }

    public String getSuffix() {
        return boundSuffix();
    }

    public boolean isProxySupported() {
        return proxyManager.isProxySupported();
    }

    public String getProxySummary() {
        return proxyManager.getActiveSummary();
    }

    public int getLocalProxyPort() {
        return proxyManager.getActiveLocalPort();
    }

    public void shutdown() {
        if (!closed.compareAndSet(false, true)) return;
        for (Req r : inflight.values()) {
            r.cancelled = true;
        }
        try {
            serverSocket.close();
        } catch (Throwable ignored) {}
        try {
            acceptThread.interrupt();
        } catch (Throwable ignored) {}

        CountDownLatch uiLatch = new CountDownLatch(1);
        Runnable cleanupUi = () -> {
            try {
                List<WorkerTab> all;
                synchronized (tabTableLock) {
                    all = new ArrayList<>(tabs.values());
                    tabs.clear();
                }
                for (WorkerTab t : all) {
                    detachSurfaceInternal(t, 0L);
                    destroyTabOnMain(t);
                }
                offscreenHost.destroy();
            } catch (Throwable t) {
                Log.w(TAG, "shutdown UI cleanup error", t);
            } finally {
                uiLatch.countDown();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cleanupUi.run();
        } else {
            main.post(cleanupUi);
            try {
                uiLatch.await(3000L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        try {
            proxyManager.shutdown();
        } catch (Throwable ignored) {}
        try {
            rpcPool.shutdownNow();
        } catch (Throwable ignored) {}
        try {
            eventPool.shutdownNow();
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------ HTTP JSON-RPC 服务端

    private void acceptLoop() {
        while (!closed.get() && !serverSocket.isClosed()) {
            Socket client = null;
            try {
                client = serverSocket.accept();
                if (closed.get()) {
                    closeQuietly(client);
                    break;
                }
                final Socket s = client;
                rpcPool.execute(() -> handleHttpClient(s));
            } catch (Throwable t) {
                if (client != null) closeQuietly(client);
                if (closed.get() || serverSocket.isClosed()) break;
            }
        }
    }

    private void handleHttpClient(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(5000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String requestLine = readLineBytes(in);
            if (requestLine == null) return;
            long contentLength = 0;
            boolean badLength = false;
            String reqToken = null;
            String hl;
            while ((hl = readLineBytes(in)) != null && !hl.isEmpty()) {
                int c = hl.indexOf(':');
                if (c <= 0) continue;
                String name = hl.substring(0, c).trim().toLowerCase(Locale.ROOT);
                String val = hl.substring(c + 1).trim();
                if ("content-length".equals(name)) {
                    try {
                        contentLength = Long.parseLong(val);
                    } catch (NumberFormatException e) {
                        badLength = true;
                    }
                } else if (ProxyWorkerBridge.HEADER_WORKER_TOKEN.equalsIgnoreCase(name)) {
                    reqToken = val;
                }
            }

            String[] parts = requestLine.trim().split("\\s+");
            String method = parts.length > 0 ? parts[0].toUpperCase(Locale.ROOT) : "";
            String rawPath = parts.length > 1 ? parts[1] : "/";
            int qIdx = rawPath.indexOf('?');
            String path = qIdx >= 0 ? rawPath.substring(0, qIdx) : rawPath;

            if (!tokenOk(reqToken)) {
                safeWrite(s, 401, err("未授权：缺少或无效的 X-Worker-Token"));
                return;
            }
            if (badLength || contentLength < 0 || contentLength > MAX_RPC_BODY) {
                safeWrite(s, contentLength > MAX_RPC_BODY ? 413 : 400, err("请求体大小非法"));
                return;
            }
            int len = (int) contentLength;
            byte[] bodyBytes = new byte[len];
            int got = 0;
            try {
                while (got < len) {
                    int n = in.read(bodyBytes, got, len - got);
                    if (n == -1) break;
                    got += n;
                }
            } catch (IOException ignored) {}
            if (got < len) {
                safeWrite(s, 400, err("请求体不完整"));
                return;
            }
            String bodyStr = new String(bodyBytes, 0, got, StandardCharsets.UTF_8).trim();
            JSONObject reqJson;
            try {
                reqJson = bodyStr.isEmpty() ? new JSONObject() : new JSONObject(bodyStr);
            } catch (Throwable t) {
                safeWrite(s, 400, err("JSON 解析失败: " + t.getMessage()));
                return;
            }

            if ("POST".equals(method) && ProxyWorkerBridge.PATH_CANCEL.equals(path)) {
                String reqId = reqJson.optString("req_id", reqJson.optString("reqId", ""));
                String tab = reqJson.optString("tab", "");
                cancel(reqId, tab);
                JSONObject res = new JSONObject();
                res.put("ok", true);
                res.put("success", true);
                safeWrite(s, 200, res);
                return;
            }

            if ("POST".equals(method) && ProxyWorkerBridge.PATH_RPC.equals(path)) {
                JSONObject resp = handleRpcWatched(s, in, reqJson);
                safeWrite(s, 200, resp);
                return;
            }

            safeWrite(s, 404, err("未知路径: " + path));
        } catch (Throwable t) {
            Log.w(TAG, "handleHttpClient error", t);
        }
    }

    private boolean tokenOk(String reqToken) {
        if (reqToken == null || workerToken.isEmpty()) return false;
        return MessageDigest.isEqual(
                workerToken.getBytes(StandardCharsets.UTF_8),
                reqToken.getBytes(StandardCharsets.UTF_8)
        );
    }

    private JSONObject handleRpcWatched(Socket s, InputStream in, JSONObject reqJson) {
        final String reqId = reqJson.optString("req_id", reqJson.optString("reqId", "")).trim();
        final String tab = reqJson.optString("tab", "").trim();
        final AtomicBoolean done = new AtomicBoolean(false);
        Thread watcher = null;
        if (!reqId.isEmpty()) {
            watcher = new Thread(() -> {
                try {
                    s.setSoTimeout(500);
                    while (!done.get() && !closed.get()) {
                        try {
                            int b = in.read();
                            if (b == -1) break;
                        } catch (java.net.SocketTimeoutException te) {
                            // still connected
                        }
                    }
                } catch (Throwable ignored) {}
                if (!done.get()) {
                    cancel(reqId, tab);
                }
            }, "pimet-pw-watch");
            watcher.setDaemon(true);
            watcher.start();
        }
        try {
            return handleRpc(reqJson);
        } finally {
            done.set(true);
            if (watcher != null) watcher.interrupt();
        }
    }

    // ------------------------------------------------------------------ 取消与请求上下文

    public void cancel(String reqId, String tab) {
        long now = SystemClock.uptimeMillis();
        for (Iterator<Map.Entry<String, Long>> it = preCancelled.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue() > 60000L) it.remove();
        }
        if (reqId != null && !reqId.trim().isEmpty()) {
            String rid = reqId.trim();
            Req r = inflight.get(rid);
            if (r != null) r.cancelled = true;
            else preCancelled.put(rid, now);
        }
        if (tab != null && !tab.trim().isEmpty()) {
            String t = tab.trim();
            for (Req r : inflight.values()) {
                if (matchesTab(r, t)) r.cancelled = true;
            }
        }
    }

    private boolean matchesTab(Req r, String t) {
        String spec = r.tabSpec == null || r.tabSpec.trim().isEmpty() ? "" : r.tabSpec.trim();
        if (!spec.isEmpty() && spec.equalsIgnoreCase(t)) return true;
        WorkerTab tb = r.tab;
        if (tb == null) return false;
        if (tb.id.equals(t)) return true;
        if ("pi".equalsIgnoreCase(t)) return tb.pi;
        if ("user".equalsIgnoreCase(t)) return !tb.pi;
        return false;
    }

    private boolean aborted(Req r) {
        return closed.get() || (r != null && (r.cancelled || SystemClock.uptimeMillis() > r.hardDeadline));
    }

    private void checkStop() {
        if (closed.get()) throw new AbortException("代理工作进程已关闭");
        Req r = curReq.get();
        if (r == null) return;
        if (r.cancelled) throw new AbortException("操作已取消");
        if (SystemClock.uptimeMillis() > r.hardDeadline) {
            throw new AbortException("操作超时（超过 " + r.timeout + "ms），已放弃");
        }
    }

    private void sleepAbortable(long ms) {
        long end = SystemClock.uptimeMillis() + ms;
        while (true) {
            checkStop();
            long left = end - SystemClock.uptimeMillis();
            if (left <= 0) return;
            SystemClock.sleep(Math.min(left, 100L));
        }
    }

    private long softLeft() {
        Req r = curReq.get();
        return r == null ? 20000L : Math.max(0L, r.softDeadline - SystemClock.uptimeMillis());
    }

    private long hardLeft() {
        Req r = curReq.get();
        return r == null ? 20000L : Math.max(0L, r.hardDeadline - SystemClock.uptimeMillis());
    }

    private int timeoutOf() {
        Req r = curReq.get();
        return r == null ? 0 : r.timeout;
    }

    // ------------------------------------------------------------------ RPC 分发

    public JSONObject handleRpc(JSONObject req) {
        String action = actionOf(req);
        if ("cancel".equals(action)) {
            cancel(req.optString("req_id", req.optString("reqId", "")), req.optString("tab", ""));
            try {
                return ok().put("ok", true).put("result", "已取消");
            } catch (Throwable t) {
                return new JSONObject();
            }
        }
        int timeout = clamp(req.optInt("timeout", 20000), 1000, 120000);
        String reqId = req.optString("req_id", req.optString("reqId", "")).trim();
        if (reqId.isEmpty()) reqId = "w" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long extra = "wait".equals(action) && req.optString("text", "").isEmpty()
                ? clamp(req.optInt("ms", 1000), 0, 60000)
                : ("wait_for".equals(action) ? clamp(req.optInt("timeout_ms", 10000), 0, 30000)
                : ("drag".equals(action) ? clamp(req.optInt("duration", 1000), 150, 2500) + clamp(req.optInt("hold_ms", 100), 0, 1000) + 1000L
                : ("native_type".equals(action) ? estimateNativeTypeExtraMs(req)
                : ("batch".equals(action)
                ? Math.min(120000L, Math.max(1, req.optJSONArray("steps") == null ? 1 : req.optJSONArray("steps").length()) * 12000L)
                : 0L))));
        Req r = new Req(reqId, timeout, extra);
        r.tabSpec = req.optString("tab", "");
        if (preCancelled.remove(reqId) != null) r.cancelled = true;
        inflight.put(reqId, r);
        curReq.set(r);
        JSONObject out;
        try {
            checkStop();
            out = dispatchAction(req, action);
        } catch (AbortException a) {
            out = err(a.getMessage());
        } catch (Throwable t) {
            out = err("浏览器操作失败：" + friendly(t));
        } finally {
            inflight.remove(reqId, r);
            curReq.remove();
        }
        finishOut(out, r);
        return out;
    }

    private static String actionOf(JSONObject req) {
        String ba = req.optString("browser_action", "").trim();
        if (!ba.isEmpty()) return ba.toLowerCase(Locale.ROOT);
        String op = req.optString("op", "").trim();
        if (!op.isEmpty()) return op.toLowerCase(Locale.ROOT);
        String act = req.optString("action", "").trim();
        if (!act.isEmpty() && !"browser".equalsIgnoreCase(act)) return act.toLowerCase(Locale.ROOT);
        return "snapshot";
    }

    private void finishOut(JSONObject out, Req r) {
        try {
            out.put("elapsed_ms", (int) (SystemClock.uptimeMillis() - r.start));
            if (!out.optBoolean("success", false)) {
                WorkerTab t = r.tab;
                if (t != null) {
                    if (!out.has("url")) out.put("url", t.url);
                    if (!out.has("title")) out.put("title", t.title);
                    if (!out.has("tab")) out.put("tab", t.id);
                }
            }
        } catch (Throwable ignored) {}
    }

    private JSONObject dispatchAction(JSONObject req, String action) throws Exception {
        switch (action) {
            case "tab_new":
            case "tab_open":
            case "new_tab":
                return doTabNew(req);
            case "tab_close":
            case "close_tab":
                return doTabClose(req);
            case "tabs":
                return doTabs();
            case "desktop":
                return doDesktop(req);
            case "ua":
                return doUa(req);
            case "zoom":
                return doZoom(req);
            case "cookies":
                return doCookies(req);
            case "clear_data":
                return doClearData(req);
            case "status":
                return doStatus(req);
            case "update_container_profile":
            case "container_profile":
            case "set_container_profile":
                return doUpdateContainerProfile(req);
            case "sync_config":
                return doSyncConfig();
            case "sync_scripts":
                return doSyncScripts();
            case "shutdown":
                main.post(this::shutdown);
                return ok().put("result", "工作进程正在关闭");
            default:
                break;
        }

        final String tabSpec = req.optString("tab", req.optString("tabId", "")).trim();
        WorkerTab tab = findTab(tabSpec);
        if (tab == null) {
            return err("工作进程中没有标签 " + tabSpec + "（容器 " + containerId + "）");
        }
        curReq.get().tab = tab;
        acquire(tab.opLock, tab);
        try {
            JSONObject out = doTabAction(tab, action, req);
            if (out.optBoolean("success") && !out.has("tab")) out.put("tab", tab.id);
            return out;
        } finally {
            tab.opLock.unlock();
        }
    }

    // ------------------------------------------------------------------ 进程级 / 标签管理动作

    private JSONObject doTabNew(JSONObject req) throws Exception {
        String rawId = req.optString("tabId", req.optString("tab", "")).trim();
        String url = normalize(req.optString("url", ""));
        boolean pi = req.optBoolean("pi", true);
        boolean wantDesktop = req.has("desktop")
                ? req.optBoolean("desktop", false)
                : (profile != null && profile.desktop);

        WorkerTab existing = rawId.isEmpty() ? null : findExactTab(rawId);
        if (existing != null) {
            return err("标签 " + rawId + " 已存在");
        }

        final WorkerTab tab = onMain(() -> {
            String tid = rawId;
            synchronized (tabTableLock) {
                if (tid.isEmpty()) {
                    do {
                        tid = "rt" + (++tabSeq);
                    } while (tabs.containsKey(tid));
                } else if (tabs.containsKey(tid)) {
                    throw new IllegalStateException("标签 " + tid + " 已存在");
                }
            }
            WorkerTab nt = new WorkerTab(tid, pi, wantDesktop);
            nt.tabCtx = new MutableContextWrapper(offscreenHost.getHostContext());
            nt.web = createWorkerWebView(nt);
            offscreenHost.mount(nt.web, nt.desktop);
            synchronized (tabTableLock) {
                tabs.put(tid, nt);
            }
            return nt;
        }, 6000L);

        curReq.get().tab = tab;
        JSONObject out = ok()
                .put("tab", tab.id)
                .put("container", containerId)
                .put("profile", ContainerEnv.toSummaryJson(profile))
                .put("remote", true)
                .put("workerSlot", slot)
                .put("workerPid", getPid())
                .put("desktop", tab.desktop)
                .put("viewport", viewportStr(tab.desktop));
        putProxyStatus(out);

        if (url.isEmpty()) {
            onMain(() -> {
                if (tab.web != null && !tab.closed) tab.web.loadUrl("about:blank");
                return null;
            }, 4000L);
            return out.put("url", nz(tab.url)).put("title", nz(tab.title))
                    .put("result", "已在容器 " + containerId + "（工作槽 #" + slot + "）新建标签 " + tab.id);
        }

        acquire(tab.opLock, tab);
        try {
            int seq = tab.navSeq;
            String before = tab.url;
            onMain(() -> {
                loadUrlWithEnv(tab, url);
                return null;
            }, 5000L);
            boolean loaded = navWait(tab, seq, before, softLeft());
            JSONObject bad = applyNav(out, tab, loaded,
                    "已在容器 " + containerId + "（" + ContainerEnv.summary(profile) + "）新建标签 " + tab.id + " 并打开 " + url);
            if (bad != null) {
                putProxyStatus(bad);
                return bad.put("tab", tab.id)
                        .put("container", containerId)
                        .put("profile", ContainerEnv.toSummaryJson(profile))
                        .put("remote", true)
                        .put("workerSlot", slot)
                        .put("workerPid", getPid())
                        .put("viewport", viewportStr(tab.desktop));
            }
            return out.put("url", nz(tab.url)).put("title", nz(tab.title));
        } finally {
            tab.opLock.unlock();
        }
    }

    private JSONObject doTabClose(JSONObject req) throws Exception {
        String spec = req.optString("tab", req.optString("tabId", "")).trim();
        WorkerTab target = findTab(spec);
        if (target == null) {
            return err("没有标签 " + spec);
        }
        acquire(target.opLock, target);
        try {
            synchronized (tabTableLock) {
                tabs.remove(target.id);
            }
            onMain(() -> {
                destroyTabOnMain(target);
                return null;
            }, 4000L);
            return ok().put("tab", target.id).put("result", "已关闭标签 " + target.id);
        } finally {
            target.opLock.unlock();
        }
    }

    private JSONObject doTabs() throws Exception {
        List<WorkerTab> snap = snapshotTabs();
        JSONArray arr = new JSONArray();
        StringBuilder sb = new StringBuilder();
        for (WorkerTab t : snap) {
            JSONObject o = new JSONObject();
            o.put("id", t.id);
            o.put("kind", t.pi ? "pi" : "user");
            o.put("url", nz(t.url));
            o.put("title", nz(t.title));
            o.put("loading", t.loading);
            o.put("progress", t.progress);
            o.put("canGoBack", t.canGoBack);
            o.put("canGoForward", t.canGoForward);
            o.put("desktop", effectiveDesktopOf(t));
            o.put("busy", t.opLock.isLocked());
            o.put("container", containerId);
            o.put("containerLabel", label);
            o.put("remote", true);
            o.put("workerSlot", slot);
            o.put("workerPid", getPid());
            o.put("viewport", viewportStr(effectiveDesktopOf(t)));
            o.put("proxyFrozen", false);
            o.put("proxySignature", profile != null ? profile.proxySignature() : "DIRECT");
            o.put("proxySummary", proxyManager.getActiveSummary());
            arr.put(o);

            sb.append(t.id).append(t.pi ? " 🤖Pi" : " 用户")
                    .append(" [远程槽#").append(slot).append("]")
                    .append(" {").append(label).append("}  ")
                    .append(nz(t.title).isEmpty() ? "" : t.title + "  ")
                    .append(nz(t.url)).append('\n');
        }
        JSONObject out = ok()
                .put("tabs", arr)
                .put("result", sb.toString().trim());
        putProxyStatus(out);
        return out;
    }

    private JSONObject doDesktop(JSONObject req) throws Exception {
        boolean on = req.optBoolean("on", true);
        String tabSpec = req.optString("tab", req.optString("tabId", "")).trim();
        if (!tabSpec.isEmpty()) {
            WorkerTab one = findTab(tabSpec);
            if (one == null) return err("没有标签 " + tabSpec);
            acquire(one.opLock, one);
            try {
                onMain(() -> {
                    if (!one.closed && one.web != null) {
                        one.desktop = on;
                        one.siteUaMode = null;
                        applyTabEnv(one, one.web, false);
                        syncWorkerDesktopViewport(one);
                        String u = one.url;
                        if (u != null && !u.isEmpty() && !u.startsWith("about:")) {
                            one.web.reload();
                        }
                        reportTabStateToMain(one);
                    }
                    return null;
                }, 5000L);
            } finally {
                one.opLock.unlock();
            }
            return ok().put("tab", one.id)
                    .put("desktop", on)
                    .put("viewport", viewportStr(on))
                    .put("result", on ? "已切换为桌面版网站" : "已切换为移动版网站");
        }
        List<WorkerTab> targets = acquireTabs(snapshotTabs());
        try {
            onMain(() -> {
                for (WorkerTab t : targets) {
                    if (t == null || t.closed || t.web == null) continue;
                    t.desktop = on;
                    t.siteUaMode = null;
                    applyTabEnv(t, t.web, false);
                    syncWorkerDesktopViewport(t);
                    String u = t.url;
                    if (u != null && !u.isEmpty() && !u.startsWith("about:")) {
                        t.web.reload();
                    }
                    reportTabStateToMain(t);
                }
                return null;
            }, 5000L);
        } finally {
            unlockTabs(targets);
        }
        return ok().put("desktop", on)
                .put("viewport", viewportStr(on))
                .put("result", on ? "已切换为桌面版网站" : "已切换为移动版网站");
    }

    private JSONObject doUa(JSONObject req) throws Exception {
        String v = req.isNull("ua") ? "" : req.optString("ua", "").trim();
        final String nextUa = v.isEmpty() ? null : v;
        String tabSpec = req.optString("tab", req.optString("tabId", "")).trim();
        if (!tabSpec.isEmpty()) {
            WorkerTab one = findTab(tabSpec);
            if (one == null) return err("没有标签 " + tabSpec);
            acquire(one.opLock, one);
            try {
                onMain(() -> {
                    if (!one.closed && one.web != null) {
                        one.desktop = false;
                        one.siteUaMode = nextUa;
                        applyTabEnv(one, one.web, false);
                        syncWorkerDesktopViewport(one);
                        String u = one.url;
                        if (u != null && !u.isEmpty() && !u.startsWith("about:")) {
                            one.web.reload();
                        }
                    }
                    return null;
                }, 5000L);
            } finally {
                one.opLock.unlock();
            }
            return ok().put("tab", one.id)
                    .put("ua", nextUa == null ? JSONObject.NULL : nextUa)
                    .put("result", nextUa == null ? "已恢复默认 UA" : "已设置自定义 UA，刷新页面后生效");
        }
        this.customUa = nextUa;
        List<WorkerTab> all = acquireTabs(snapshotTabs());
        try {
            onMain(() -> {
                for (WorkerTab t : all) {
                    if (t == null || t.closed || t.web == null) continue;
                    t.desktop = false;
                    t.siteUaMode = null;
                    applyTabEnv(t, t.web, false);
                    syncWorkerDesktopViewport(t);
                    String u = t.url;
                    if (u != null && !u.isEmpty() && !u.startsWith("about:")) {
                        t.web.reload();
                    }
                }
                return null;
            }, 5000L);
        } finally {
            unlockTabs(all);
        }
        return ok().put("ua", nextUa == null ? JSONObject.NULL : nextUa)
                .put("result", nextUa == null ? "已恢复默认 UA" : "已设置自定义 UA，刷新页面后生效");
    }

    private JSONObject doZoom(JSONObject req) throws Exception {
        if (!req.has("percent")) return err("zoom 需要 percent（25–300）");
        final int pct = clamp(req.optInt("percent", 100), 25, 300);
        String tabSpec = req.optString("tab", req.optString("tabId", "")).trim();
        if (!tabSpec.isEmpty()) {
            WorkerTab one = findTab(tabSpec);
            if (one == null) return err("没有标签 " + tabSpec);
            acquire(one.opLock, one);
            try {
                onMain(() -> {
                    if (!one.closed && one.web != null) {
                        String u = one.url;
                        if (u != null && !u.startsWith("about:")) {
                            one.web.getSettings().setTextZoom(100);
                            WebPinchZoom.applyPercent(one.web, pct);
                        }
                    }
                    return null;
                }, 5000L);
            } finally {
                one.opLock.unlock();
            }
            return ok().put("tab", one.id).put("zoom", pct).put("result", "已把页面缩放设为 " + pct + "%");
        }
        List<WorkerTab> targets = acquireTabs(snapshotTabs());
        try {
            onMain(() -> {
                for (WorkerTab t : targets) {
                    if (t == null || t.closed || t.web == null) continue;
                    String u = t.url;
                    if (u != null && !u.startsWith("about:")) {
                        t.web.getSettings().setTextZoom(100);
                        WebPinchZoom.applyPercent(t.web, pct);
                    }
                }
                return null;
            }, 5000L);
        } finally {
            unlockTabs(targets);
        }
        return ok().put("zoom", pct).put("result", "已把页面缩放设为 " + pct + "%");
    }

    private JSONObject doCookies(JSONObject req) throws Exception {
        String u = normalize(req.optString("url", ""));
        if (u.isEmpty()) {
            WorkerTab t = findTab(req.optString("tab", req.optString("tabId", "")));
            if (t != null) u = nz(t.url);
        }
        if (u.isEmpty() || u.startsWith("about:")) return err("cookies 需要 url");
        final String url = u;
        List<String> list = new ArrayList<>();
        Object set = req.opt("set");
        if (set instanceof JSONArray) {
            JSONArray a = (JSONArray) set;
            for (int i = 0; i < a.length(); i++) {
                String s = a.optString(i, "");
                if (!s.isEmpty()) list.add(s);
            }
        } else if (set instanceof String && !((String) set).isEmpty()) {
            list.add((String) set);
        }
        final int written = list.size();
        String got = onMain(() -> {
            CookieManager cm = CookieManager.getInstance();
            for (String c : list) {
                cm.setCookie(url, c);
            }
            if (!list.isEmpty()) cm.flush();
            return cm.getCookie(url);
        }, 4000L);
        return ok().put("url", url)
                .put("container", containerId)
                .put("cookies", nz(got))
                .put("result", "容器 " + containerId + "：" + (written > 0 ? "已写入 " + written + " 条 cookie；" : "")
                        + (got == null || got.isEmpty() ? "该网址没有 cookie" : "该网址的 cookie 如上"));
    }

    private JSONObject doClearData(JSONObject req) throws Exception {
        final String what = req.optString("what", "all").trim().toLowerCase(Locale.ROOT);
        if (!"cookies".equals(what) && !"cache".equals(what) && !"storage".equals(what) && !"all".equals(what)) {
            return err("clear_data 的 what 只能是 cookies / cache / storage / all");
        }
        final boolean all = "all".equals(what);
        final List<WorkerTab> snap = acquireTabs(snapshotTabs());
        try {
            onMain(() -> {
                if (all || "cookies".equals(what)) {
                    CookieManager cm = CookieManager.getInstance();
                    cm.removeAllCookies(null);
                    cm.flush();
                }
                if (all || "cache".equals(what)) {
                    for (WorkerTab t : snap) {
                        if (t != null && t.web != null && !t.closed) t.web.clearCache(true);
                    }
                }
                if (all || "storage".equals(what)) {
                    WebStorage.getInstance().deleteAllData();
                }
                return null;
            }, 8000L);
        } finally {
            unlockTabs(snap);
        }
        return ok().put("result", "已清除 " + what);
    }

    private JSONObject doStatus(JSONObject req) throws Exception {
        String tabSpec = req.optString("tab", req.optString("tabId", "")).trim();
        WorkerTab tab = tabSpec.isEmpty() ? firstTab() : findTab(tabSpec);
        JSONObject out = ok();
        boolean desktopNow = tab != null ? tab.desktop : (profile != null && profile.desktop);
        String urlNow = tab != null ? nz(tab.url) : "";

        out.put("remote", true);
        out.put("workerSlot", slot);
        out.put("workerPid", getPid());
        out.put("suffix", boundSuffix());
        out.put("viewport", viewportStr(desktopNow));
        out.put("overlayFallback", offscreenHost.isOverlayFallback());
        out.put("container", containerId);
        out.put("containers_supported", true);
        out.put("profile", ContainerEnv.toSummaryJson(profile));
        out.put("proxyFrozen", false);
        out.put("proxySignature", profile != null ? profile.proxySignature() : "DIRECT");
        out.put("proxySummary", proxyManager.getActiveSummary());
        putProxyStatus(out);

        if (tab != null) {
            out.put("tab", tab.id);
            out.put("url", urlNow);
            String uaNow = onMain(() -> tab.web == null ? "" : tab.web.getSettings().getUserAgentString(), 3000L);
            out.put("user_agent", nz(uaNow));
            out.put("ua_mode", tab.desktop ? "desktop" : (customUa != null ? "custom" : "default"));
        } else {
            out.put("url", "");
            out.put("ua_mode", desktopNow ? "desktop" : (customUa != null ? "custom" : "default"));
        }

        try {
            UserScriptManager m = scriptMgr;
            out.put("scripts", m.list().size());
            out.put("scripts_matching", matchingIds(m, urlNow).size());
        } catch (Throwable e) {
            out.put("scripts", -1);
        }

        out.put("result", "远程代理工作进程 #" + slot + " (pid=" + getPid() + ", 视口=" + viewportStr(desktopNow)
                + ", 代理=" + proxyManager.getActiveSummary() + ", 容器=" + containerId + ")");
        return out;
    }

    private JSONObject doUpdateContainerProfile(JSONObject req) throws Exception {
        if (req.has("label") && !req.isNull("label")) {
            String l = req.optString("label", "").trim();
            if (!l.isEmpty()) this.label = l;
        }
        ContainerEnv.Profile oldProfile = this.profile;
        String oldSig = oldProfile != null ? oldProfile.proxySignature() : "DIRECT";

        ContainerEnv.Profile nextProfile;
        if (req.has("profileJson") && !req.isNull("profileJson")) {
            nextProfile = parseProfileJson(req.optString("profileJson", ""));
        } else {
            Object merged = mergeProfileAndProxy(req);
            nextProfile = merged == null ? oldProfile : ContainerEnv.fromSpec(merged, oldProfile);
        }
        if (nextProfile == null) nextProfile = ContainerEnv.NATIVE;

        String newSig = nextProfile.proxySignature();
        boolean proxySwitched = false;
        if (!newSig.equals(oldSig)
                || (nextProfile.hasActiveProxy() && (!proxyManager.matchesActive(newSig) || proxyManager.getActiveLocalPort() <= 0))) {
            if (nextProfile.hasActiveProxy() && !proxyManager.isProxySupported()) {
                return err("当前系统 WebView 不支持 ProxyController (PROXY_OVERRIDE)");
            }
            CountDownLatch latch = new CountDownLatch(1);
            final ContainerEnv.Profile fp = nextProfile;
            main.post(() -> proxyManager.applyForContainer(containerId, label, fp, Collections.emptyList(), latch::countDown));
            long waitMs = Math.min(12000L, Math.max(1500L, hardLeft()));
            long end = SystemClock.uptimeMillis() + waitMs;
            while (!latch.await(100L, TimeUnit.MILLISECONDS)) {
                checkStop();
                if (SystemClock.uptimeMillis() >= end) {
                    return err("切换容器代理超时");
                }
            }
            checkStop();
            if (!proxyManager.matchesActive(newSig)) {
                String errMsg = proxyManager.getLastError();
                return err(errMsg != null && !errMsg.isEmpty() ? errMsg : "切换容器代理失败");
            }
            proxySwitched = true;
        }

        this.profile = nextProfile;
        boolean reload = req.optBoolean("reload", false);
        List<WorkerTab> all = acquireTabs(snapshotTabs());
        final ContainerEnv.Profile finalProf = nextProfile;
        int affected;
        try {
            affected = onMain(() -> {
                int cnt = 0;
                for (WorkerTab t : all) {
                    if (t == null || t.closed || t.web == null) continue;
                    t.desktop = finalProf.desktop;
                    t.siteUaMode = null;
                    applyTabEnv(t, t.web, true);
                    syncWorkerDesktopViewport(t);
                    cnt++;
                    if (reload) {
                        String u = t.url;
                        if (u != null && !u.isEmpty() && !u.startsWith("about:")) {
                            t.web.reload();
                        }
                    }
                }
                return cnt;
            }, 6000L);
        } finally {
            unlockTabs(all);
        }

        JSONObject out = ok()
                .put("name", containerId)
                .put("label", label)
                .put("profile", ContainerEnv.toSummaryJson(nextProfile))
                .put("proxySwitched", proxySwitched)
                .put("tabs_affected", affected)
                .put("reloaded", reload)
                .put("result", "已热更新工作槽 #" + slot + " 容器 " + containerId + " 环境：" + ContainerEnv.summary(nextProfile)
                        + " · 代理：" + proxyManager.getActiveSummary()
                        + (reload && affected > 0 ? "（已刷新 " + affected + " 个标签）" : ""));
        putProxyStatus(out);
        return out;
    }

    private JSONObject doSyncConfig() throws Exception {
        this.customizer = new BrowserCustomizer(this.appsDir);
        List<WorkerTab> all = acquireTabs(snapshotTabs());
        try {
            onMain(() -> {
                for (WorkerTab t : all) {
                    if (t == null || t.closed || t.web == null) continue;
                    applySiteSettings(t, t.url, false);
                    injectUiCss(t, t.web);
                }
                return null;
            }, 4000L);
        } finally {
            unlockTabs(all);
        }
        return ok().put("result", "工作槽 #" + slot + " 已同步浏览器配置");
    }

    private JSONObject doSyncScripts() throws Exception {
        this.scriptMgr = new UserScriptManager(this.appsDir);
        List<WorkerTab> all = acquireTabs(snapshotTabs());
        try {
            onMain(() -> {
                for (WorkerTab t : all) {
                    if (t == null || t.closed || t.web == null) continue;
                    installFrameDocStartScript(t, t.web);
                }
                return null;
            }, 4000L);
        } finally {
            unlockTabs(all);
        }
        return ok().put("result", "工作槽 #" + slot + " 已同步用户脚本");
    }

    // ------------------------------------------------------------------ 单标签 RPC 执行

    private JSONObject doTabAction(WorkerTab tab, String action, JSONObject req) throws Exception {
        boolean wantSnapshot = req.optBoolean("snapshot", true);
        boolean wantSettle = req.optBoolean("settle", true);
        JSONObject out = ok();
        String result;

        switch (action) {
            case "open":
            case "navigate":
            case "goto": {
                String url = normalize(req.optString("url", ""));
                if (url.isEmpty()) return err("缺少 url");
                int seq = tab.navSeq;
                String before = tab.url;
                onMain(() -> {
                    loadUrlWithEnv(tab, url);
                    return null;
                }, 5000L);
                boolean loaded = navWait(tab, seq, before, softLeft());
                JSONObject bad = applyNav(out, tab, loaded, "已打开 " + url);
                if (bad != null) return bad;
                result = null;
                break;
            }
            case "home": {
                int seq = tab.navSeq;
                String before = tab.url;
                onMain(() -> {
                    if (tab.web != null && !tab.closed) {
                        File customHome = new File(appsDir, "home.html");
                        if (customHome.isFile()) tab.web.loadUrl("file://" + customHome.getAbsolutePath());
                        else tab.web.loadUrl("about:blank");
                    }
                    return null;
                }, 5000L);
                boolean loaded = navWait(tab, seq, before, softLeft());
                JSONObject bad = applyNav(out, tab, loaded, "已回到首页");
                if (bad != null) return bad;
                result = null;
                break;
            }
            case "back":
            case "forward":
            case "reload": {
                Boolean can = onMain(() -> {
                    if (tab.web == null) return false;
                    if ("back".equals(action)) return tab.web.canGoBack();
                    if ("forward".equals(action)) return tab.web.canGoForward();
                    return Boolean.TRUE;
                }, 3000L);
                if (!Boolean.TRUE.equals(can)) {
                    return err("back".equals(action) ? "没有可以后退的页面（已经是这个标签的第一页）" : "没有可以前进的页面");
                }
                int seq = tab.navSeq;
                String before = tab.url;
                onMain(() -> {
                    if (tab.web == null) return null;
                    if ("back".equals(action)) tab.web.goBack();
                    else if ("forward".equals(action)) tab.web.goForward();
                    else tab.web.reload();
                    return null;
                }, 5000L);
                boolean loaded = navWait(tab, seq, before, softLeft());
                JSONObject bad = applyNav(out, tab, loaded,
                        "back".equals(action) ? "已后退" : ("forward".equals(action) ? "已前进" : "已刷新"));
                if (bad != null) return bad;
                result = null;
                break;
            }
            case "snapshot":
                waitLoadedNow(tab, Math.min(softLeft(), 8000L));
                wantSnapshot = true;
                result = null;
                break;
            case "read": {
                waitLoadedNow(tab, Math.min(softLeft(), 8000L));
                JSONObject r = readPage(tab, req, 12000);
                out.put("url", r.optString("url", tab.url));
                out.put("title", r.optString("title", tab.title));
                out.put("text", r.optString("text", ""));
                out.put("length", r.optInt("length", r.optString("text", "").length()));
                out.put("truncated", r.optBoolean("truncated", false));
                if (r.has("links")) out.put("links", r.optJSONArray("links"));
                wantSnapshot = req.optBoolean("snapshot", false);
                result = "已提取正文（" + out.optInt("length") + " 字" + (out.optBoolean("truncated") ? "，已截断" : "") + "）";
                break;
            }
            case "eval":
            case "js": {
                String script = req.optString("script", "");
                if (script.isEmpty()) return err("缺少 script");
                String[] v = evalAsync(tab, script, req.optBoolean("fn", false), Math.max(1000L, softLeft()));
                if (v[0] != null) return err(v[0]).put("url", tab.url);
                return out.put("url", tab.url).put("result", v[1]);
            }
            case "click":
            case "tap": {
                int seq = tab.navSeq;
                String before = tab.url;
                result = click(tab, req, out);
                if (wantSettle) settle(tab, seq, out);
                markChanged(out, tab, seq, before);
                break;
            }
            case "drag": {
                int seq = tab.navSeq;
                String before = tab.url;
                NativeInputHelper.DragResult dr = NativeInputHelper.drag(hostBridge(tab), tab.web, req);
                out.put("ok", true)
                        .put("action", "drag")
                        .put("trusted", true)
                        .put("from", new JSONArray().put(dr.fromX).put(dr.fromY))
                        .put("to", new JSONArray().put(dr.toX).put(dr.toY))
                        .put("steps", dr.steps)
                        .put("duration_ms", dr.durationMs);
                result = "已原生拖拽 [" + fmtCoord(dr.fromX) + ", " + fmtCoord(dr.fromY) + "] → ["
                        + fmtCoord(dr.toX) + ", " + fmtCoord(dr.toY) + "]（" + dr.steps + " 步，" + dr.durationMs + "ms）";
                if (wantSettle) settle(tab, seq, out);
                markChanged(out, tab, seq, before);
                break;
            }
            case "native_type": {
                int seq = tab.navSeq;
                String before = tab.url;
                NativeInputHelper.TypeResult tr = NativeInputHelper.nativeType(hostBridge(tab), tab.web, req);
                out.put("ok", true)
                        .put("action", "native_type")
                        .put("trusted", true)
                        .put("typed", tr.typed)
                        .put("key_events", tr.keyEvents)
                        .put("ime_commits", tr.imeCommits)
                        .put("duration_ms", tr.durationMs);
                result = "已原生逐字输入 " + tr.typed + " 字符（key_events=" + tr.keyEvents
                        + ", ime_commits=" + tr.imeCommits + ", " + tr.durationMs + "ms）";
                if (wantSettle) settle(tab, seq, out);
                markChanged(out, tab, seq, before);
                break;
            }
            case "fill":
            case "type": {
                int seq = tab.navSeq;
                String before = tab.url;
                result = type(tab, req);
                if (wantSettle) settle(tab, seq, out);
                markChanged(out, tab, seq, before);
                break;
            }
            case "select": {
                int seq = tab.navSeq;
                String before = tab.url;
                JSONObject r = evalAgent(tab,
                        "A.select(" + JSONObject.quote(fieldOf(req)) + "," + JSONObject.quote(req.optString("value", "")) + ")",
                        5000L);
                if (!r.optBoolean("ok")) return err(agentErr(r, "选择失败"));
                result = r.optString("result");
                if (wantSettle) settle(tab, seq, out);
                markChanged(out, tab, seq, before);
                break;
            }
            case "hover": {
                String tgt = targetOf(req);
                if (tgt.isEmpty()) return err("hover 需要 target/ref/text");
                JSONObject loc = evalAgent(tab, "A.locate(" + JSONObject.quote(tgt) + ",\"\")", 5000L);
                if (!loc.optBoolean("ok")) return err(agentErr(loc, "定位失败"));
                evalJson(tab, HOVER_JS, 3000L);
                result = "已悬停 " + tgt + (loc.optString("label").isEmpty() ? "" : "（" + loc.optString("label") + "）");
                if (wantSettle) sleepAbortable(150L);
                break;
            }
            case "scroll_into_view": {
                String tgt = targetOf(req);
                if (tgt.isEmpty()) return err("scroll_into_view 需要 target/ref/text");
                JSONObject loc = evalAgent(tab, "A.locate(" + JSONObject.quote(tgt) + ",\"\")", 5000L);
                if (!loc.optBoolean("ok")) return err(agentErr(loc, "定位失败"));
                result = "已把 " + tgt + " 滚动到可见";
                wantSnapshot = req.optBoolean("snapshot", false);
                break;
            }
            case "press":
            case "key": {
                String key = req.optString("key", "Enter");
                int code = keyCode(key);
                if (code == 0) {
                    return err("不支持的按键 " + key + "（支持 Enter Tab Escape Backspace Delete Space ArrowUp/Down/Left/Right PageUp PageDown Home End）");
                }
                int seq = tab.navSeq;
                String before = tab.url;
                pressKey(tab, key, code);
                result = "已按 " + key;
                if (wantSettle) settle(tab, seq, out);
                markChanged(out, tab, seq, before);
                break;
            }
            case "scroll": {
                String js = "A.scroll(" + JSONObject.quote(fieldOf(req)) + ","
                        + JSONObject.quote(req.optString("direction", "down")) + "," + req.optDouble("amount", 0.8) + ")";
                JSONObject r = evalAgent(tab, js, 5000L);
                if (!r.optBoolean("ok")) return err(agentErr(r, "滚动失败"));
                result = r.optString("result");
                if (r.has("moved") && !r.optBoolean("moved", true)) result += "（页面没有移动）";
                else if (r.optBoolean("atEnd", false)) result += "（已到尽头）";
                if (wantSettle) sleepAbortable(250L);
                break;
            }
            case "batch":
                return batch(tab, req, out);
            case "wait": {
                String text = req.optString("text", "");
                if (!text.isEmpty()) {
                    long end = SystemClock.uptimeMillis() + softLeft();
                    boolean found = false;
                    while (true) {
                        checkStop();
                        JSONObject r;
                        try {
                            r = evalAgent(tab, "A.hasText(" + JSONObject.quote(text) + ")", 3000L);
                        } catch (AbortException a) {
                            throw a;
                        } catch (Exception e) {
                            r = new JSONObject();
                        }
                        if (r.optBoolean("v")) { found = true; break; }
                        if (SystemClock.uptimeMillis() >= end) break;
                        sleepAbortable(400L);
                    }
                    out.put("found", found);
                    result = found ? "页面已出现「" + text + "」" : "等待超时，页面没有出现「" + text + "」";
                } else {
                    sleepAbortable(clamp(req.optInt("ms", 1000), 0, 60000));
                    waitLoadedNow(tab, softLeft());
                    result = "已等待";
                }
                break;
            }
            case "wait_for": {
                String sel0 = req.optString("selector", "");
                String text0 = req.optString("text", "");
                if (sel0.isEmpty() && text0.isEmpty()) return err("wait_for 需要 selector 或 text");
                JSONObject w = waitFor(tab, req);
                out.put("found", w.optBoolean("found")).put("waited_ms", w.optInt("waited_ms"));
                result = w.optString("result");
                wantSnapshot = req.optBoolean("snapshot", false);
                break;
            }
            case "find": {
                String text = req.optString("text", "");
                if (text.isEmpty()) return err("find 需要 text");
                JSONObject r = evalJson(tab, FIND_JS + "(" + JSONObject.quote(text) + ")", 5000L);
                int n = r.optInt("n", 0);
                out.put("count", n).put("url", tab.url);
                if (r.has("samples")) out.put("samples", r.optJSONArray("samples"));
                return out.put("result", n == 0 ? "页面里没有找到「" + text + "」" : "页面里找到 " + n + " 处「" + text + "」");
            }
            case "console":
                return doConsole(tab, req, out);
            case "inject":
                return doInject(tab, req, out);
            case "menu_commands": {
                JSONArray a = menuCommands(tab);
                return out.put("commands", a).put("url", tab.url)
                        .put("result", a.length() == 0 ? "当前页没有用户脚本注册的菜单命令" : "当前页有 " + a.length() + " 个菜单命令，用 menu_run{script,cmd} 执行");
            }
            case "menu_run": {
                final String sid = req.optString("script", "").trim();
                final String cid = req.optString("cmd", "").trim();
                if (sid.isEmpty() || cid.isEmpty()) return err("menu_run 需要 script 和 cmd（用 menu_commands 查看）");
                int seq = tab.navSeq;
                onMain(() -> {
                    runMenuCommand(tab, sid, cid);
                    return null;
                }, 5000L);
                result = "已执行菜单命令 " + cid + "（脚本 " + sid + "）";
                if (wantSettle) settle(tab, seq, out);
                break;
            }
            case "screenshot":
                return doScreenshot(tab, req, out);
            case "mirror_frame":
                return doMirrorFrame(tab, req, out);
            case "mirror_tap":
                return doMirrorTap(tab, req, out);
            default:
                return err("未知操作 " + action);
        }

        if (result != null) out.put("result", result);
        if (wantSnapshot) {
            JSONObject snap = snapshot(tab, req.optInt("max_chars", "snapshot".equals(action) ? 12000 : 6000), scopeOf(req));
            out.put("url", snap.optString("url", tab.url));
            out.put("title", snap.optString("title", tab.title));
            out.put("snapshot", snap.optString("text"));
            if (snap.has("refs")) out.put("refs", snap.opt("refs"));
        } else {
            out.put("url", tab.url);
            out.put("title", tab.title);
        }
        return out;
    }

    // ------------------------------------------------------------------ 截图与远程镜像

    private JSONObject doScreenshot(WorkerTab tab, JSONObject req, JSONObject out) throws Exception {
        if (tab.web == null || tab.closed) return err("标签已关闭");
        long tmo = Math.max(1000L, Math.min(8000L, hardLeft()));
        Bitmap bmp = captureTabBitmap(tab, tmo);
        if (bmp == null) return err("离屏截图失败");

        File shotDir = new File(appsDir, "screenshots");
        if (!shotDir.isDirectory()) //noinspection ResultOfMethodCallIgnored
            shotDir.mkdirs();
        File pngFile = new File(shotDir, "shot-" + tab.id + "-" + System.currentTimeMillis() + ".png");
        try (FileOutputStream fos = new FileOutputStream(pngFile)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.getFD().sync();
        } catch (Throwable t) {
            Log.w(TAG, "write screenshot png failed", t);
        }
        pruneOldFiles(shotDir, 20);

        int w = bmp.getWidth();
        int h = bmp.getHeight();
        String jpegB64 = encodeJpegAndRecycle(bmp, 900, 70);
        return out.put("url", nz(tab.url))
                .put("title", nz(tab.title))
                .put("path", pngFile.getAbsolutePath())
                .put("image", jpegB64)
                .put("mime", "image/jpeg")
                .put("width", w)
                .put("height", h)
                .put("viewport", viewportStr(tab.desktop))
                .put("remote", true);
    }

    private JSONObject doMirrorFrame(WorkerTab tab, JSONObject req, JSONObject out) throws Exception {
        if (tab.web == null || tab.closed) return err("标签已关闭");
        int maxSide = clamp(req.optInt("max_side", 720), 240, 1280);
        int quality = clamp(req.optInt("quality", 55), 20, 90);
        Bitmap bmp = captureTabBitmap(tab, 3000L);
        if (bmp == null) return err("抓取远程预览帧失败");

        int viewW = bmp.getWidth();
        int viewH = bmp.getHeight();
        float scale = Math.min(1f, (float) maxSide / Math.max(viewW, viewH));
        Bitmap scaled = bmp;
        if (scale < 1f) {
            scaled = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(viewW * scale)), Math.max(1, Math.round(viewH * scale)), true);
            if (scaled != bmp) bmp.recycle();
        }
        int outW = scaled.getWidth();
        int outH = scaled.getHeight();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, bo);
        scaled.recycle();
        String b64 = Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);

        return out.put("tab", tab.id)
                .put("url", nz(tab.url))
                .put("title", nz(tab.title))
                .put("loading", tab.loading)
                .put("desktop", tab.desktop)
                .put("viewport", viewportStr(tab.desktop))
                .put("viewW", viewW)
                .put("viewH", viewH)
                .put("width", outW)
                .put("height", outH)
                .put("image", b64)
                .put("mime", "image/jpeg")
                .put("ts", System.currentTimeMillis());
    }

    private JSONObject doMirrorTap(WorkerTab tab, JSONObject req, JSONObject out) throws Exception {
        if (tab.web == null || tab.closed) return err("标签已关闭");
        boolean hasNorm = req.has("nx") && req.has("ny");
        final double rawX = hasNorm ? req.optDouble("nx", Double.NaN) : req.optDouble("x", Double.NaN);
        final double rawY = hasNorm ? req.optDouble("ny", Double.NaN) : req.optDouble("y", Double.NaN);
        final boolean normalized = hasNorm || req.optBoolean("normalized", false);
        if (Double.isNaN(rawX) || Double.isNaN(rawY)) {
            return err("mirror_tap 需要 x/y（或 nx/ny）坐标");
        }

        float[] tapped = onMain(() -> {
            WebView w = tab.web;
            if (w == null || tab.closed) return null;
            int vw = w.getWidth() > 0 ? w.getWidth() : ContainerEnv.viewportWidth(tab.desktop);
            int vh = w.getHeight() > 0 ? w.getHeight() : ContainerEnv.viewportHeight(tab.desktop);
            float px = (float) (normalized ? rawX * vw : rawX);
            float py = (float) (normalized ? rawY * vh : rawY);
            px = Math.max(0f, Math.min(vw - 1f, px));
            py = Math.max(0f, Math.min(vh - 1f, py));

            if ((tab.surfaceAttached || !offscreenHost.isOverlayFallback()) && w.isAttachedToWindow()) {
                long down = SystemClock.uptimeMillis();
                MotionEvent d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, px, py, 0);
                MotionEvent u = MotionEvent.obtain(down, down + 55, MotionEvent.ACTION_UP, px, py, 0);
                d.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                u.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                try {
                    w.dispatchTouchEvent(d);
                    w.dispatchTouchEvent(u);
                } finally {
                    d.recycle();
                    u.recycle();
                }
            } else {
                // 无窗口降级模式：按 CSS 坐标点击命中元素
                int targetCssW = ContainerEnv.viewportWidth(tab.desktop);
                double cssX = px * ((double) targetCssW / Math.max(1, vw));
                double cssY = py * ((double) ContainerEnv.viewportHeight(tab.desktop) / Math.max(1, vh));
                String js = "(function(x,y){try{var el=document.elementFromPoint(x,y);if(!el)return 0;"
                        + "try{el.focus();}catch(e){}"
                        + "['pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(t){"
                        + "try{var C=t.indexOf('pointer')===0&&window.PointerEvent?PointerEvent:MouseEvent;"
                        + "el.dispatchEvent(new C(t,{bubbles:true,cancelable:true,composed:true,clientX:x,clientY:y,view:window}));}catch(e){}});"
                        + "return 1;}catch(e){return 0;}})(" + cssX + "," + cssY + ")";
                w.evaluateJavascript(js, null);
            }
            return new float[]{px, py};
        }, 4000L);

        if (tapped == null) return err("mirror_tap 执行失败");
        return out.put("tab", tab.id)
                .put("x", tapped[0])
                .put("y", tapped[1])
                .put("url", nz(tab.url))
                .put("result", "已点击 (" + Math.round(tapped[0]) + ", " + Math.round(tapped[1]) + ")");
    }

    private static String encodeJpegAndRecycle(Bitmap src, int maxSide, int quality) {
        Bitmap out = src;
        int w = src.getWidth(), h = src.getHeight();
        float scale = Math.min(1f, (float) maxSide / Math.max(w, h));
        if (scale < 1f) {
            out = Bitmap.createScaledBitmap(src, Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), true);
            if (out != src) src.recycle();
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        out.compress(Bitmap.CompressFormat.JPEG, quality, bo);
        out.recycle();
        return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
    }

    private static void pruneOldFiles(File dir, int keep) {
        try {
            File[] fs = dir.listFiles((d, n) -> n.startsWith("shot-") && n.endsWith(".png"));
            if (fs == null || fs.length <= keep) return;
            Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (int i = keep; i < fs.length; i++) {
                //noinspection ResultOfMethodCallIgnored
                fs[i].delete();
            }
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------ WebView 构建与反检测环境

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface", "ClickableViewAccessibility"})
    private WebView createWorkerWebView(WorkerTab t) {
        Context webCtx = t.tabCtx != null ? t.tabCtx : offscreenHost.getHostContext();
        WebView w = new WebView(webCtx) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) {
                if (ev != null && ev.getActionMasked() == MotionEvent.ACTION_DOWN && t.surfaceAttached) {
                    long now = SystemClock.uptimeMillis();
                    if (now - t.lastFocusNotifyAt >= 300L) {
                        t.lastFocusNotifyAt = now;
                        reportTabFocusToMain(t);
                    }
                }
                return super.dispatchTouchEvent(ev);
            }
        };
        WebSettings ws = w.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setSupportZoom(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setSupportMultipleWindows(false);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
        ws.setTextZoom(100);

        if (sDefaultUa == null) {
            try { sDefaultUa = WebSettings.getDefaultUserAgent(appContext); } catch (Throwable ignored) {}
        }

        // 绝不注册 PiMetNative / PiMetTranslator / PiMetTheme / PiMetGM 全局 JS 接口！
        setupWorkerFrameMessaging(t, w);
        applyTabEnv(t, w, true);

        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setAcceptThirdPartyCookies(w, true);
        } catch (Throwable ignored) {}

        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String u = req != null && req.getUrl() != null ? req.getUrl().toString() : "";
                if (req != null && req.isForMainFrame() && (u.startsWith("http://") || u.startsWith("https://"))) {
                    String r = uiRedirect(u);
                    if (r != null) {
                        applySiteSettings(t, r, true);
                        loadUrlWithEnv(t, r);
                        return true;
                    }
                    applySiteSettings(t, u, true);
                }
                return !(u.startsWith("http://") || u.startsWith("https://")
                        || u.startsWith("file://") || u.startsWith("about:")
                        || u.startsWith("data:") || u.startsWith("blob:"));
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                String u = req != null && req.getUrl() != null ? req.getUrl().toString() : null;
                if (uiBlocked(u)) {
                    return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
                }
                if (u != null && (u.startsWith("http://") || u.startsWith("https://")) && !isLoopbackUrl(u)) {
                    String sig = profile != null ? profile.proxySignature() : "DIRECT";
                    if (proxyManager.isSwitching() || !proxyManager.matchesActive(sig)) {
                        return new WebResourceResponse(
                                "text/plain",
                                "utf-8",
                                503,
                                "Proxy Mismatch Frozen",
                                Collections.singletonMap("Cache-Control", "no-store"),
                                new ByteArrayInputStream(new byte[0])
                        );
                    }
                }
                return null;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                t.navSeq++;
                t.errorCode = 0;
                t.errorDesc = "";
                t.httpStatus = 0;
                t.loading = true;
                t.progress = 10;
                t.url = url != null ? url : "";
                t.canGoBack = view.canGoBack();
                t.canGoForward = view.canGoForward();
                applySiteSettings(t, url, false);
                for (Iterator<Map.Entry<String, FrameSession>> it = t.frames.entrySet().iterator(); it.hasNext(); ) {
                    Map.Entry<String, FrameSession> e = it.next();
                    FrameSession fs = e.getValue();
                    if (fs == null || !fs.isMainFrame) it.remove();
                }
                synchronized (t.menus) { t.menus.clear(); }
                clearConsoleLogs(t);
                reportTabStateToMain(t);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                t.loading = false;
                t.progress = 100;
                t.finishedSeq = t.navSeq;
                t.url = url != null ? url : "";
                t.canGoBack = view.canGoBack();
                t.canGoForward = view.canGoForward();
                String title = view.getTitle();
                if (title != null) t.title = title;
                if (t.surfaceAttached) {
                    syncWorkerDesktopViewport(t);
                } else if (effectiveDesktopOf(t) && (profile == null || !profile.isIsolated())
                        && url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    view.evaluateJavascript(AntiDetectJs.buildNativeDesktopScript(ContainerEnv.DESKTOP_VIEWPORT_W, 1.0), null);
                }
                injectUiCss(t, view);
                reportHistoryToMain(t.url, t.title);
                reportTabStateToMain(t);
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                if (t.surfaceAttached) {
                    syncWorkerDesktopViewport(t);
                }
                injectUiCss(t, view);
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                if (!t.loading) {
                    t.navSeq++;
                    t.finishedSeq = t.navSeq;
                }
                t.url = url != null ? url : "";
                t.canGoBack = view.canGoBack();
                t.canGoForward = view.canGoForward();
                if (t.surfaceAttached) {
                    syncWorkerDesktopViewport(t);
                }
                reportTabStateToMain(t);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request == null || !request.isForMainFrame() || error == null) return;
                t.errorCode = error.getErrorCode() == 0 ? -1 : error.getErrorCode();
                CharSequence d = error.getDescription();
                t.errorDesc = d != null ? d.toString() : "";
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse resp) {
                if (request == null || !request.isForMainFrame() || resp == null) return;
                t.httpStatus = resp.getStatusCode();
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                Log.w(TAG, "worker render process gone tab=" + t.id + " url=" + t.url);
                main.post(() -> recoverTabOnMain(t, view));
                return true;
            }
        });

        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                t.progress = newProgress;
                long now = SystemClock.uptimeMillis();
                if (newProgress == 100 || now - t.lastProgressReportAt >= 120L) {
                    t.lastProgressReportAt = now;
                    reportTabStateToMain(t);
                }
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                t.title = title != null ? title : "";
                if (!t.loading) {
                    reportHistoryToMain(t.url, t.title);
                }
                reportTabStateToMain(t);
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                result.confirm();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                result.cancel();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
                result.cancel();
                return true;
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                if (cm != null) {
                    String lv;
                    switch (cm.messageLevel()) {
                        case ERROR: lv = "error"; break;
                        case WARNING: lv = "warn"; break;
                        case DEBUG: lv = "debug"; break;
                        case TIP: lv = "info"; break;
                        default: lv = "log"; break;
                    }
                    addConsole(t, lv, cm.message(), cm.sourceId(), cm.lineNumber());
                }
                return super.onConsoleMessage(cm);
            }
        });

        return w;
    }

    private void recoverTabOnMain(WorkerTab t, WebView dead) {
        if (t == null || t.closed || t.web != dead) return;
        detachSurfaceInternal(t, 0L);
        if (t.envDocStartHandler != null) {
            try { t.envDocStartHandler.remove(); } catch (Throwable ignored) {}
            t.envDocStartHandler = null;
        }
        if (t.frameDocStartHandler != null) {
            try { t.frameDocStartHandler.remove(); } catch (Throwable ignored) {}
            t.frameDocStartHandler = null;
        }
        t.lastAppliedEnvKey = null;
        t.frames.clear();
        offscreenHost.unmount(dead);
        try { dead.destroy(); } catch (Throwable ignored) {}
        t.loading = false;
        t.progress = 0;
        if (t.tabCtx == null) {
            t.tabCtx = new MutableContextWrapper(offscreenHost.getHostContext());
        } else {
            t.tabCtx.setBaseContext(offscreenHost.getHostContext());
        }
        t.web = createWorkerWebView(t);
        offscreenHost.mount(t.web, effectiveDesktopOf(t));
        reportTabStateToMain(t);
        String u = t.url;
        if (u != null && !u.isEmpty() && !u.startsWith("about:")) {
            loadUrlWithEnv(t, u);
        }
    }

    private void destroyTabOnMain(WorkerTab t) {
        if (t == null) return;
        detachSurfaceInternal(t, 0L);
        t.closed = true;
        if (t.envDocStartHandler != null) {
            try { t.envDocStartHandler.remove(); } catch (Throwable ignored) {}
            t.envDocStartHandler = null;
        }
        if (t.frameDocStartHandler != null) {
            try { t.frameDocStartHandler.remove(); } catch (Throwable ignored) {}
            t.frameDocStartHandler = null;
        }
        t.frames.clear();
        if (t.web != null) {
            offscreenHost.unmount(t.web);
            try { t.web.stopLoading(); } catch (Throwable ignored) {}
            if (t.tabCtx != null) {
                try { t.tabCtx.setBaseContext(appContext); } catch (Throwable ignored) {}
            }
            try { t.web.destroy(); } catch (Throwable ignored) {}
            t.web = null;
        }
    }

    private void applyTabEnv(WorkerTab t, WebView w, boolean forceReinstall) {
        if (t == null || w == null) return;
        ContainerEnv.Profile p = this.profile != null ? this.profile : ContainerEnv.NATIVE;
        boolean effectiveDesktop = t.desktop;
        String overrideUa = this.customUa;
        if (t.siteUaMode != null && !t.siteUaMode.isEmpty()) {
            if ("desktop".equalsIgnoreCase(t.siteUaMode)) {
                effectiveDesktop = true;
                overrideUa = null;
            } else if ("mobile".equalsIgnoreCase(t.siteUaMode)) {
                effectiveDesktop = false;
            } else {
                overrideUa = t.siteUaMode;
            }
        }

        if (p.isIsolated()) {
            String effectiveUa = ContainerEnv.buildEffectiveUa(appContext, p, effectiveDesktop, overrideUa);
            ContainerEnv.applyToWebSettings(appContext, w.getSettings(), p, effectiveDesktop, overrideUa);
            String key = p.preset + "|" + p.timezone + "|" + p.locale + "|" + p.acceptLanguage
                    + "|" + p.device + "|" + effectiveDesktop + "|" + p.seed + "|" + p.webrtc + "|" + effectiveUa;
            if (forceReinstall || t.envDocStartHandler == null || !key.equals(t.lastAppliedEnvKey)) {
                if (t.envDocStartHandler != null) {
                    try { t.envDocStartHandler.remove(); } catch (Throwable ignored) {}
                    t.envDocStartHandler = null;
                }
                if (crossFrameSupported()) {
                    String antiJs = AntiDetectJs.build(appContext, p, effectiveDesktop, effectiveUa, appsUrlPrefix());
                    if (antiJs != null && !antiJs.isEmpty()) {
                        try {
                            t.envDocStartHandler = WebViewCompat.addDocumentStartJavaScript(w, antiJs, Collections.singleton("*"));
                        } catch (Throwable e) {
                            Log.w(TAG, "addDocumentStartJavaScript (anti-detect) failed", e);
                        }
                    }
                    installFrameDocStartScript(t, w);
                }
                t.lastAppliedEnvKey = key;
            }
        } else {
            if (t.envDocStartHandler != null) {
                try { t.envDocStartHandler.remove(); } catch (Throwable ignored) {}
                t.envDocStartHandler = null;
            }
            String key = "NATIVE|" + effectiveDesktop + "|" + (overrideUa == null ? "" : overrideUa);
            if (overrideUa != null && !overrideUa.isEmpty()) {
                w.getSettings().setUserAgentString(overrideUa);
            } else if (effectiveDesktop) {
                w.getSettings().setUserAgentString(desktopUa());
            } else {
                w.getSettings().setUserAgentString(null);
            }
            if (forceReinstall || !key.equals(t.lastAppliedEnvKey)) {
                if (crossFrameSupported()) {
                    if (effectiveDesktop) {
                        try {
                            t.envDocStartHandler = WebViewCompat.addDocumentStartJavaScript(
                                    w, AntiDetectJs.buildNativeDesktopScript(ContainerEnv.DESKTOP_VIEWPORT_W, 1.0), Collections.singleton("*"));
                        } catch (Throwable ignored) {}
                    }
                    installFrameDocStartScript(t, w);
                }
                t.lastAppliedEnvKey = key;
            }
        }
    }

    private void loadUrlWithEnv(WorkerTab t, String url) {
        if (t == null || t.web == null || url == null) return;
        Map<String, String> extra = ContainerEnv.extraHeaders(profile);
        if (extra == null || extra.isEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) {
            t.web.loadUrl(url);
            return;
        }
        t.web.loadUrl(url, new LinkedHashMap<>(extra));
    }

    private void setupWorkerFrameMessaging(WorkerTab t, WebView w) {
        if (!crossFrameSupported()) return;
        try {
            WebViewCompat.addWebMessageListener(
                    w,
                    "__pimetFrameMsg",
                    Collections.singleton("*"),
                    (view, message, sourceOrigin, isMainFrame, replyProxy) ->
                            handleFrameMessage(t, view, message, isMainFrame, replyProxy)
            );
        } catch (Throwable e) {
            Log.w(TAG, "setupWorkerFrameMessaging failed", e);
        }
    }

    private void installFrameDocStartScript(WorkerTab t, WebView w) {
        if (t == null || w == null || !crossFrameSupported()) return;
        if (t.frameDocStartHandler != null) {
            try { t.frameDocStartHandler.remove(); } catch (Throwable ignored) {}
            t.frameDocStartHandler = null;
        }
        String isolatedGm = "";
        try {
            isolatedGm = scriptMgr.buildIsolatedRuntime();
        } catch (Throwable ignored) {}
        if (isolatedGm == null || isolatedGm.isEmpty()) return;

        String js = String.join("\n",
                "(function(){",
                "  var W = window;",
                "  var pm = W.__pimetFrameMsg;",
                "  try { delete W.__pimetFrameMsg; } catch (e) {}",
                "  if (!pm) return;",
                "  var SECRET = " + JSONObject.quote(t.frameSecret) + ";",
                "  var APPS_PREFIX = " + JSONObject.quote(appsUrlPrefix()) + ";",
                "  var FRAME_ID = 'f_' + Math.random().toString(36).slice(2) + '_' + Date.now().toString(36);",
                "  var href = ''; try { href = String((W.location && W.location.href) || ''); } catch (e) {}",
                "  var isAppsPage = !!(APPS_PREFIX && href.indexOf(APPS_PREFIX) === 0);",
                "  function postFrame(obj) {",
                "    if (!obj || typeof obj !== 'object') return;",
                "    try {",
                "      obj.secret = SECRET;",
                "      obj.frameId = FRAME_ID;",
                "      pm.postMessage(JSON.stringify(obj));",
                "    } catch (e) {}",
                "  }",
                "  var gmCtrl = null;",
                "  try { if (!isAppsPage) gmCtrl = (" + isolatedGm + ")(postFrame); } catch (e) {}",
                "  pm.addEventListener('message', function(ev) {",
                "    var msg; try { msg = typeof ev.data === 'string' ? JSON.parse(ev.data) : ev.data; } catch (e) { return; }",
                "    if (!msg || typeof msg !== 'object') return;",
                "    if ((msg.type === 'gm_cb' || msg.type === 'gm_menu') && gmCtrl) {",
                "      try { gmCtrl.onMessage(msg); } catch (e) {}",
                "    }",
                "  });",
                "  postFrame({ type: 'hello', url: href });",
                "})();"
        );
        try {
            t.frameDocStartHandler = WebViewCompat.addDocumentStartJavaScript(w, js, Collections.singleton("*"));
        } catch (Throwable e) {
            Log.w(TAG, "addDocumentStartJavaScript (worker gm) failed", e);
        }
    }

    private void handleFrameMessage(WorkerTab t, WebView view, WebMessageCompat message,
                                    boolean isMainFrame, JavaScriptReplyProxy replyProxy) {
        if (t == null || t.closed || t.web != view || message == null || replyProxy == null) return;
        if (message.getType() != WebMessageCompat.TYPE_STRING) return;
        String raw = message.getData();
        if (raw == null || raw.isEmpty()) return;
        JSONObject msg;
        try {
            msg = new JSONObject(raw);
        } catch (Throwable e) {
            return;
        }
        if (!t.frameSecret.equals(msg.optString("secret", ""))) return;
        String frameId = msg.optString("frameId", "");
        if (frameId.isEmpty()) return;
        String type = msg.optString("type", "");
        if ("hello".equals(type)) {
            if (isMainFrame) {
                for (Iterator<Map.Entry<String, FrameSession>> it = t.frames.entrySet().iterator(); it.hasNext(); ) {
                    if (!frameId.equals(it.next().getKey())) it.remove();
                }
            } else if (!t.frames.containsKey(frameId) && t.frames.size() >= MAX_FRAME_SESSIONS) {
                return;
            }
            t.frames.put(frameId, new FrameSession(frameId, isMainFrame, msg.optString("url", ""), replyProxy));
            return;
        }
        if (!"gm_call".equals(type)) return;

        FrameSession fs = t.frames.get(frameId);
        if (fs == null) return;
        fs.replyProxy = replyProxy;

        String op = msg.optString("op", "");
        String scriptId = msg.optString("scriptId", "");
        UserScriptManager.Script s = null;
        if (!scriptId.isEmpty()) {
            try { s = scriptMgr.get(scriptId); } catch (Throwable ignored) {}
            if (s == null || !s.enabled || (!fs.isMainFrame && s.noframes)) return;
            String checkUrl = (fs.url != null && !fs.url.isEmpty()) ? fs.url : t.url;
            if (!scriptMgr.urlMatches(s, checkUrl)) return;
        }

        switch (op) {
            case "setValue": {
                if (s == null) return;
                String key = msg.optString("key", "");
                if (!key.isEmpty()) {
                    reportGmValueToMain("gm_set_value", s.id, key, msg.optString("valueJson", "null"));
                }
                break;
            }
            case "deleteValue": {
                if (s == null) return;
                String key = msg.optString("key", "");
                if (!key.isEmpty()) {
                    reportGmValueToMain("gm_delete_value", s.id, key, null);
                }
                break;
            }
            case "xhr": {
                if (s == null) return;
                JSONObject reqObj;
                try { reqObj = new JSONObject(msg.optString("reqJson", "{}")); } catch (Throwable e) { reqObj = new JSONObject(); }
                String callId = reqObj.optString("callId", "");
                if (callId.isEmpty()) callId = "x" + System.nanoTime();
                final String cid = callId;
                final JSONObject r = reqObj;
                final UserScriptManager.Script script = s;
                final String pageUrl = t.url;
                rpcPool.execute(() -> deliverGm(t, cid, doGmXhr(t, script, pageUrl, r)));
                break;
            }
            case "setClipboard": {
                String text = msg.optString("text", "");
                main.post(() -> {
                    try {
                        ClipboardManager cm = (ClipboardManager) appContext.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("PiMet", text));
                    } catch (Throwable ignored) {}
                });
                break;
            }
            case "registerMenu": {
                if (s == null) return;
                String cmdId = msg.optString("cmdId", "");
                String cap = msg.optString("caption", "");
                if (cmdId.isEmpty()) return;
                synchronized (t.menus) {
                    LinkedHashMap<String, String> m = t.menus.get(s.id);
                    if (m == null) { m = new LinkedHashMap<>(); t.menus.put(s.id, m); }
                    m.put(cmdId, cap.isEmpty() ? cmdId : cap);
                }
                break;
            }
            case "unregisterMenu": {
                if (s == null) return;
                String cmdId = msg.optString("cmdId", "");
                if (cmdId.isEmpty()) return;
                synchronized (t.menus) {
                    LinkedHashMap<String, String> m = t.menus.get(s.id);
                    if (m != null) {
                        m.remove(cmdId);
                        if (m.isEmpty()) t.menus.remove(s.id);
                    }
                }
                break;
            }
            case "log": {
                if (s == null) return;
                addConsole(t, "log", msg.optString("text", ""), "GM:" + (s.name != null ? s.name : s.id), 0);
                break;
            }
            default:
                break;
        }
    }

    private void deliverGm(WorkerTab t, String callId, JSONObject res) {
        if (t == null || t.closed) return;
        try {
            final String frameMsg = new JSONObject()
                    .put("type", "gm_cb")
                    .put("callId", callId)
                    .put("resultJson", res.toString())
                    .toString();
            main.post(() -> {
                if (t.closed) return;
                for (FrameSession fs : t.frames.values()) {
                    if (fs.replyProxy != null) {
                        try { fs.replyProxy.postMessage(frameMsg); } catch (Throwable ignored) {}
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    private JSONObject doGmXhr(WorkerTab t, UserScriptManager.Script s, String pageUrl, JSONObject req) {
        JSONObject res = new JSONObject();
        HttpURLConnection c = null;
        try {
            res.put("readyState", 4);
            res.put("status", 0);
            String raw = req.optString("url", "");
            URL url = pageUrl != null && pageUrl.startsWith("http") ? new URL(new URL(pageUrl), raw) : new URL(raw);
            String proto = url.getProtocol();
            if (!"http".equals(proto) && !"https".equals(proto)) {
                throw new IllegalArgumentException("只支持 http/https：" + raw);
            }
            String method = req.optString("method", "GET").trim().toUpperCase(Locale.ROOT);
            if (method.isEmpty()) method = "GET";
            int timeout = req.optInt("timeout", 0);
            if (timeout <= 0) timeout = 30000;
            JSONObject headers = req.optJSONObject("headers");
            boolean anonymous = req.optBoolean("anonymous", false);
            byte[] body = null;
            if (req.has("dataBase64") && !req.isNull("dataBase64")) {
                body = Base64.decode(req.optString("dataBase64", ""), Base64.DEFAULT);
            } else if (req.has("data") && !req.isNull("data")) {
                body = String.valueOf(req.opt("data")).getBytes(StandardCharsets.UTF_8);
            }
            String rt = req.optString("responseType", "text").toLowerCase(Locale.ROOT);
            CookieManager cm = CookieManager.getInstance();

            for (int hop = 0; hop < 6; hop++) {
                c = openWorkerConnection(url);
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(Math.min(timeout, 30000));
                c.setReadTimeout(timeout);
                setHttpMethod(c, method);
                boolean hasCookie = false, hasUa = false, hasAl = false;
                if (headers != null) {
                    Iterator<String> it = headers.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        String v = headers.optString(k, "");
                        if (k.equalsIgnoreCase("cookie")) hasCookie = true;
                        if (k.equalsIgnoreCase("user-agent")) hasUa = true;
                        if (k.equalsIgnoreCase("accept-language")) hasAl = true;
                        c.setRequestProperty(k, v);
                    }
                }
                if (!anonymous && !hasCookie) {
                    String ck = cm.getCookie(url.toString());
                    if (ck != null && !ck.isEmpty()) c.setRequestProperty("Cookie", ck);
                }
                if (!hasUa) {
                    if (profile != null && profile.isIsolated()) {
                        c.setRequestProperty("User-Agent", ContainerEnv.buildEffectiveUa(appContext, profile, t.desktop, customUa));
                    } else if (sDefaultUa != null) {
                        c.setRequestProperty("User-Agent", sDefaultUa);
                    }
                }
                if (!anonymous && !hasAl && profile != null && profile.isIsolated() && !profile.acceptLanguage.isEmpty()) {
                    c.setRequestProperty("Accept-Language", profile.acceptLanguage);
                }
                if (body != null && !"GET".equals(method) && !"HEAD".equals(method)) {
                    c.setDoOutput(true);
                    c.setFixedLengthStreamingMode(body.length);
                    try (OutputStream os = c.getOutputStream()) {
                        os.write(body);
                    }
                }
                int code = c.getResponseCode();
                if (!anonymous) {
                    List<String> sc = c.getHeaderFields().get("Set-Cookie");
                    if (sc != null) {
                        for (String v : sc) cm.setCookie(url.toString(), v);
                    }
                }
                String loc = c.getHeaderField("Location");
                if (code >= 300 && code < 400 && code != 304 && loc != null && !loc.isEmpty()) {
                    url = new URL(url, loc);
                    c.disconnect();
                    c = null;
                    if (code == 303 || ((code == 301 || code == 302) && "POST".equals(method))) {
                        method = "GET";
                        body = null;
                    }
                    continue;
                }
                break;
            }
            if (c == null) throw new IOException("重定向次数过多");
            int code = c.getResponseCode();
            res.put("status", code);
            res.put("statusText", c.getResponseMessage() == null ? "" : c.getResponseMessage());
            res.put("finalUrl", c.getURL().toString());
            StringBuilder hs = new StringBuilder();
            for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                if (e.getKey() == null) continue;
                for (String v : e.getValue()) {
                    hs.append(e.getKey().toLowerCase(Locale.ROOT)).append(": ").append(v).append("\r\n");
                }
            }
            res.put("responseHeaders", hs.toString());
            byte[] data = new byte[0];
            if (!"HEAD".equals(method)) {
                InputStream in = null;
                try { in = code >= 400 ? c.getErrorStream() : c.getInputStream(); } catch (Throwable ignored) {}
                if (in != null) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    try {
                        while ((n = in.read(buf)) != -1) {
                            if (bo.size() + n > GM_MAX_BODY) throw new IOException("响应超过 30MB");
                            bo.write(buf, 0, n);
                        }
                    } finally {
                        try { in.close(); } catch (Throwable ignored) {}
                    }
                    data = bo.toByteArray();
                }
            }
            if ("arraybuffer".equals(rt) || "blob".equals(rt)) {
                res.put("responseBase64", Base64.encodeToString(data, Base64.NO_WRAP));
                String ct = c.getContentType();
                if (ct != null) res.put("contentType", ct);
            } else {
                res.put("responseText", new String(data, StandardCharsets.UTF_8));
            }
        } catch (Throwable e) {
            try {
                res.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            } catch (Throwable ignored) {}
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) {}
        }
        return res;
    }

    private HttpURLConnection openWorkerConnection(URL url) throws IOException {
        if (ContainerProxyManager.isLoopbackHost(url.getHost())) {
            return (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
        }
        String sig = profile != null ? profile.proxySignature() : "DIRECT";
        if (proxyManager.isSwitching() || !proxyManager.matchesActive(sig)) {
            throw new IOException("代理正在切换中，已拦截外网请求");
        }
        int lp = proxyManager.getActiveLocalPort();
        if (proxyManager.isDirect() || lp <= 0) {
            return (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
        }
        Proxy p = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", lp));
        return (HttpURLConnection) url.openConnection(p);
    }

    private static void setHttpMethod(HttpURLConnection c, String method) throws Exception {
        try {
            c.setRequestMethod(method);
        } catch (java.net.ProtocolException pe) {
            java.lang.reflect.Field f = HttpURLConnection.class.getDeclaredField("method");
            f.setAccessible(true);
            f.set(c, method);
        }
    }

    // ------------------------------------------------------------------ 主进程事件回调

    private void reportHistoryToMain(String url, String title) {
        if (mainCallbackPort <= 0 || url == null || url.isEmpty()) return;
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return;
        JSONObject payload = new JSONObject();
        try {
            payload.put("event", "history_add");
            payload.put("slot", slot);
            payload.put("containerId", containerId);
            payload.put("url", url);
            payload.put("title", title == null ? "" : title);
        } catch (Throwable ignored) {
            return;
        }
        postWorkerEventAsync(payload);
    }

    private void reportGmValueToMain(String event, String scriptId, String key, String valueJson) {
        if (mainCallbackPort <= 0 || scriptId == null || key == null) return;
        JSONObject payload = new JSONObject();
        try {
            payload.put("event", event);
            payload.put("slot", slot);
            payload.put("containerId", containerId);
            payload.put("scriptId", scriptId);
            payload.put("key", key);
            if (valueJson != null) payload.put("valueJson", valueJson);
        } catch (Throwable ignored) {
            return;
        }
        postWorkerEventAsync(payload);
    }

    private void postWorkerEventAsync(JSONObject payload) {
        if (closed.get() || mainCallbackPort <= 0 || payload == null) return;
        try {
            eventPool.execute(() -> {
                HttpURLConnection conn = null;
                try {
                    URL u = new URL("http://127.0.0.1:" + mainCallbackPort + ProxyWorkerBridge.PATH_WORKER_EVENT);
                    conn = (HttpURLConnection) u.openConnection(Proxy.NO_PROXY);
                    conn.setConnectTimeout(1500);
                    conn.setReadTimeout(2000);
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    if (!mainToken.isEmpty()) {
                        conn.setRequestProperty(ProxyWorkerBridge.HEADER_MAIN_TOKEN, mainToken);
                    }
                    conn.setFixedLengthStreamingMode(body.length);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(body);
                    }
                    conn.getResponseCode();
                } catch (Throwable ignored) {
                } finally {
                    if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
    }

    private void reportTabStateToMain(WorkerTab t) {
        if (closed.get() || mainCallbackPort <= 0 || t == null || t.closed) return;
        if (Looper.myLooper() == Looper.getMainLooper() && t.web != null) {
            try {
                t.canGoBack = t.web.canGoBack();
                t.canGoForward = t.web.canGoForward();
            } catch (Throwable ignored) {}
        }
        JSONObject payload = new JSONObject();
        try {
            payload.put("event", "tab_state");
            payload.put("slot", slot);
            payload.put("containerId", containerId);
            payload.put("tabId", t.id);
            payload.put("url", nz(t.url));
            payload.put("title", nz(t.title));
            payload.put("loading", t.loading);
            payload.put("progress", t.progress);
            payload.put("canGoBack", t.canGoBack);
            payload.put("canGoForward", t.canGoForward);
            payload.put("desktop", effectiveDesktopOf(t));
        } catch (Throwable ignored) {
            return;
        }
        postWorkerEventAsync(payload);
    }

    private void reportTabFocusToMain(WorkerTab t) {
        if (closed.get() || mainCallbackPort <= 0 || t == null || t.closed) return;
        JSONObject payload = new JSONObject();
        try {
            payload.put("event", "tab_focus");
            payload.put("slot", slot);
            payload.put("containerId", containerId);
            payload.put("tabId", t.id);
        } catch (Throwable ignored) {
            return;
        }
        postWorkerEventAsync(payload);
    }

    // ------------------------------------------------------------------ SurfaceControlViewHost 原生嵌入（API 30+）与视口/截图

    private boolean effectiveDesktopOf(WorkerTab t) {
        if (t == null) return false;
        if (t.siteUaMode != null && !t.siteUaMode.isEmpty()) {
            if ("desktop".equalsIgnoreCase(t.siteUaMode)) return true;
            if ("mobile".equalsIgnoreCase(t.siteUaMode)) return false;
        }
        return t.desktop;
    }

    private void syncWorkerDesktopViewport(WorkerTab t) {
        if (t == null || t.closed || t.web == null) return;
        boolean effDesktop = effectiveDesktopOf(t);
        if (t.surfaceAttached) {
            int wPx = t.web.getWidth() > 0 ? t.web.getWidth() : t.surfaceW;
            int hPx = t.web.getHeight() > 0 ? t.web.getHeight() : t.surfaceH;
            t.lastViewportSyncW = wPx;
            t.lastViewportSyncH = hPx;
            t.lastViewportSyncDesktop = effDesktop;
            t.lastViewportSyncAttached = true;
            String u = t.url;
            if (u == null || !(u.startsWith("http://") || u.startsWith("https://"))) return;
            double cssW = (wPx > 0 ? wPx : ContainerEnv.DESKTOP_VIEWPORT_W) / Math.max(1.0, t.surfaceDensity);
            if (effDesktop) {
                double scale = cssW / (double) ContainerEnv.DESKTOP_VIEWPORT_W;
                String js = (profile != null && profile.isIsolated())
                        ? AntiDetectJs.buildDesktopViewportMetaScript(ContainerEnv.DESKTOP_VIEWPORT_W, scale, true)
                        : AntiDetectJs.buildNativeDesktopScript(ContainerEnv.DESKTOP_VIEWPORT_W, scale, true);
                t.web.evaluateJavascript(js, null);
            } else {
                t.web.evaluateJavascript(AntiDetectJs.buildForegroundMobileViewportScript(cssW), null);
            }
        } else {
            t.lastViewportSyncW = 0;
            t.lastViewportSyncH = 0;
            t.lastViewportSyncDesktop = effDesktop;
            t.lastViewportSyncAttached = false;
            offscreenHost.updateViewport(t.web, effDesktop);
            String u = t.url;
            if (u != null && (u.startsWith("http://") || u.startsWith("https://"))) {
                if (effDesktop && (profile == null || !profile.isIsolated())) {
                    t.web.evaluateJavascript(
                            AntiDetectJs.buildNativeDesktopScript(ContainerEnv.DESKTOP_VIEWPORT_W, 1.0, false), null);
                } else {
                    t.web.evaluateJavascript(AntiDetectJs.buildClearViewportOverrideScript(), null);
                }
            }
        }
    }

    private Bitmap captureTabBitmap(WorkerTab tab, long timeoutMs) throws Exception {
        if (tab == null || tab.web == null || tab.closed) return null;
        if (!tab.surfaceAttached) {
            return offscreenHost.captureScreenshot(tab.web, timeoutMs);
        }
        Bitmap fgBmp = onMain(() -> {
            WebView w = tab.web;
            if (w == null || tab.closed || !tab.surfaceAttached) return null;
            int vw = w.getWidth();
            int vh = w.getHeight();
            if (vw <= 0 || vh <= 0) return null;
            int maxDim = Math.max(vw, vh);
            float scale = maxDim > 1440 ? (1440f / maxDim) : 1f;
            int bw = Math.max(1, Math.round(vw * scale));
            int bh = Math.max(1, Math.round(vh * scale));
            Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            bmp.eraseColor(Color.WHITE);
            Canvas c = new Canvas(bmp);
            if (scale < 1f) c.scale(scale, scale);
            c.translate(-w.getScrollX(), -w.getScrollY());
            w.draw(c);
            return bmp;
        }, Math.max(1000L, Math.min(timeoutMs, 5000L)));
        if (fgBmp != null) return fgBmp;
        if (!tab.surfaceAttached) {
            return offscreenHost.captureScreenshot(tab.web, timeoutMs);
        }
        return null;
    }

    public void handleAttachSurface(Bundle b, Messenger replyTo) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> handleAttachSurfaceOnMain(b, replyTo));
        } else {
            handleAttachSurfaceOnMain(b, replyTo);
        }
    }

    private void handleAttachSurfaceOnMain(Bundle b, Messenger replyTo) {
        String tabId = b != null ? b.getString(ProxyWorkerBridge.KEY_TAB_ID, "") : "";
        long attachSeq = b != null ? b.getLong(ProxyWorkerBridge.KEY_ATTACH_SEQ, 0L) : 0L;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            sendAttachSurfaceAck(replyTo, false, tabId, attachSeq, null, null, "Android API < 30");
            return;
        }
        if (closed.get()) {
            sendAttachSurfaceAck(replyTo, false, tabId, attachSeq, null, null, "worker closed");
            return;
        }
        if (b == null || tabId.isEmpty()) {
            sendAttachSurfaceAck(replyTo, false, tabId, attachSeq, null, null, "missing tabId");
            return;
        }
        WorkerTab tab = findExactTab(tabId);
        if (tab == null || tab.closed || tab.web == null) {
            sendAttachSurfaceAck(replyTo, false, tabId, attachSeq, null, null, "tab not found: " + tabId);
            return;
        }
        IBinder hostToken = b.getBinder(ProxyWorkerBridge.KEY_HOST_TOKEN);
        int displayId = b.getInt(ProxyWorkerBridge.KEY_DISPLAY_ID, Display.DEFAULT_DISPLAY);
        int w = b.getInt(ProxyWorkerBridge.KEY_WIDTH, 0);
        int h = b.getInt(ProxyWorkerBridge.KEY_HEIGHT, 0);
        if (hostToken == null || w <= 0 || h <= 0) {
            sendAttachSurfaceAck(replyTo, false, tabId, attachSeq, null, null, "invalid hostToken or size");
            return;
        }

        // 排他清理：先把同进程内其他已嵌入表面的标签回挂离屏
        for (WorkerTab other : snapshotTabs()) {
            if (other != null && other != tab && (other.surfaceAttached || other.surfaceHost != null)) {
                detachSurfaceInternal(other, 0L);
            }
        }
        if (tab.surfaceHost != null || tab.surfaceAttached) {
            detachSurfaceInternal(tab, 0L);
        }

        try {
            DisplayManager dm = (DisplayManager) appContext.getSystemService(Context.DISPLAY_SERVICE);
            Display display = dm != null ? dm.getDisplay(displayId) : null;
            if (display == null && dm != null) {
                display = dm.getDisplay(Display.DEFAULT_DISPLAY);
            }
            if (display == null) {
                sendAttachSurfaceAck(replyTo, false, tabId, attachSeq, null, null, "display unavailable");
                return;
            }
            Context displayCtx = appContext.createDisplayContext(display);
            int themeRes = android.R.style.Theme_DeviceDefault;
            try {
                themeRes = ThemeHelper.getThemeResId(ThemeHelper.getTheme(appContext));
            } catch (Throwable ignored) {}
            Context themedCtx = new ContextThemeWrapper(displayCtx, themeRes);
            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            float density = metrics.density > 0f ? metrics.density : themedCtx.getResources().getDisplayMetrics().density;
            if (density <= 0f) density = 1f;
            tab.surfaceDensity = density;

            if (tab.tabCtx == null) {
                tab.tabCtx = new MutableContextWrapper(themedCtx);
            } else {
                tab.tabCtx.setBaseContext(themedCtx);
            }
            offscreenHost.unmount(tab.web);
            ViewParent vp = tab.web.getParent();
            if (vp instanceof ViewGroup) {
                ((ViewGroup) vp).removeView(tab.web);
            }

            FrameLayout box = new FrameLayout(themedCtx);
            tab.web.setFocusable(true);
            tab.web.setFocusableInTouchMode(true);
            tab.web.setVisibility(View.VISIBLE);
            box.addView(tab.web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            Object hostObj = SurfaceHostApi30.createAndSetView(themedCtx, display, hostToken, box, w, h);
            tab.surfaceHost = hostObj;
            tab.surfaceContainer = box;
            tab.surfaceAttached = true;
            tab.surfaceAttachSeq = attachSeq;
            tab.surfaceW = w;
            tab.surfaceH = h;
            tab.hostToken = hostToken;

            IBinder.DeathRecipient dr = () -> main.post(() -> detachSurfaceInternal(tab, attachSeq));
            tab.hostDeathRecipient = dr;
            hostToken.linkToDeath(dr, 0);

            if (tab.surfaceLayoutListener != null) {
                try {
                    tab.web.removeOnLayoutChangeListener(tab.surfaceLayoutListener);
                } catch (Throwable ignored) {}
            }
            View.OnLayoutChangeListener layoutListener = (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (tab.closed || !tab.surfaceAttached || tab.web != v) return;
                int nw = right - left;
                int nh = bottom - top;
                if (nw <= 0 || nh <= 0) return;
                boolean effD = effectiveDesktopOf(tab);
                if (nw != tab.lastViewportSyncW || nh != tab.lastViewportSyncH
                        || effD != tab.lastViewportSyncDesktop || !tab.lastViewportSyncAttached) {
                    tab.surfaceW = nw;
                    tab.surfaceH = nh;
                    syncWorkerDesktopViewport(tab);
                }
            };
            tab.surfaceLayoutListener = layoutListener;
            tab.web.addOnLayoutChangeListener(layoutListener);

            try {
                tab.web.onResume();
            } catch (Throwable ignored) {}
            tab.web.requestLayout();
            syncWorkerDesktopViewport(tab);
            try {
                tab.canGoBack = tab.web.canGoBack();
                tab.canGoForward = tab.web.canGoForward();
            } catch (Throwable ignored) {}

            Object pkg = SurfaceHostApi30.getSurfacePackage(hostObj);
            if (pkg == null) {
                detachSurfaceInternal(tab, attachSeq);
                sendAttachSurfaceAck(replyTo, false, tabId, attachSeq, null, null, "SurfacePackage is null");
                return;
            }
            boolean sent = sendAttachSurfaceAck(replyTo, true, tabId, attachSeq, tab, pkg, "");
            if (!sent) {
                detachSurfaceInternal(tab, attachSeq);
                return;
            }
            reportTabStateToMain(tab);
        } catch (Throwable t) {
            Log.w(TAG, "handleAttachSurface failed tab=" + tabId, t);
            detachSurfaceInternal(tab, attachSeq);
            sendAttachSurfaceAck(replyTo, false, tabId, attachSeq, null, null,
                    t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        }
    }

    public void handleResizeSurface(Bundle b) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> handleResizeSurfaceOnMain(b));
        } else {
            handleResizeSurfaceOnMain(b);
        }
    }

    private void handleResizeSurfaceOnMain(Bundle b) {
        if (b == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R || closed.get()) return;
        String tabId = b.getString(ProxyWorkerBridge.KEY_TAB_ID, "");
        long attachSeq = b.getLong(ProxyWorkerBridge.KEY_ATTACH_SEQ, 0L);
        int w = b.getInt(ProxyWorkerBridge.KEY_WIDTH, 0);
        int h = b.getInt(ProxyWorkerBridge.KEY_HEIGHT, 0);
        if (tabId.isEmpty() || w <= 0 || h <= 0) return;
        WorkerTab tab = findExactTab(tabId);
        if (tab == null || tab.closed || !tab.surfaceAttached || tab.surfaceAttachSeq != attachSeq || tab.surfaceHost == null) {
            return;
        }
        try {
            SurfaceHostApi30.relayout(tab.surfaceHost, w, h);
            tab.surfaceW = w;
            tab.surfaceH = h;
            if (tab.web != null) tab.web.requestLayout();
            syncWorkerDesktopViewport(tab);
        } catch (Throwable t) {
            Log.w(TAG, "handleResizeSurface failed tab=" + tabId, t);
        }
    }

    public void handleDetachSurface(Bundle b) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> handleDetachSurfaceOnMain(b));
        } else {
            handleDetachSurfaceOnMain(b);
        }
    }

    private void handleDetachSurfaceOnMain(Bundle b) {
        if (b == null) return;
        String tabId = b.getString(ProxyWorkerBridge.KEY_TAB_ID, "");
        long expectedSeq = b.getLong(ProxyWorkerBridge.KEY_ATTACH_SEQ, 0L);
        if (tabId.isEmpty()) return;
        WorkerTab tab = findExactTab(tabId);
        if (tab != null) {
            detachSurfaceInternal(tab, expectedSeq);
        }
    }

    public void handleDispatchTouch(Bundle b) {
        if (b == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (!main.post(() -> handleDispatchTouchOnMain(b))) {
                try {
                    b.setClassLoader(MotionEvent.class.getClassLoader());
                    MotionEvent ev = b.getParcelable(ProxyWorkerBridge.KEY_MOTION_EVENT);
                    if (ev != null) ev.recycle();
                } catch (Throwable ignored) {}
            }
        } else {
            handleDispatchTouchOnMain(b);
        }
    }

    private void handleDispatchTouchOnMain(Bundle b) {
        if (b == null || closed.get()) return;
        MotionEvent ev = null;
        try {
            b.setClassLoader(MotionEvent.class.getClassLoader());
            ev = b.getParcelable(ProxyWorkerBridge.KEY_MOTION_EVENT);
            if (ev == null) return;

            String tabId = b.getString(ProxyWorkerBridge.KEY_TAB_ID, "");
            long attachSeq = b.getLong(ProxyWorkerBridge.KEY_ATTACH_SEQ, 0L);
            int hostW = b.getInt(ProxyWorkerBridge.KEY_WIDTH, 0);
            int hostH = b.getInt(ProxyWorkerBridge.KEY_HEIGHT, 0);

            WorkerTab tab = !tabId.isEmpty() ? findExactTab(tabId) : findAttachedSurfaceTab();
            if (tab == null || tab.closed || tab.web == null || !tab.surfaceAttached) {
                return;
            }
            if (attachSeq > 0L && tab.surfaceAttachSeq != attachSeq) {
                return;
            }

            int webW = tab.web.getWidth() > 0 ? tab.web.getWidth() : tab.surfaceW;
            int webH = tab.web.getHeight() > 0 ? tab.web.getHeight() : tab.surfaceH;
            if (hostW > 0 && hostH > 0 && webW > 0 && webH > 0 && (hostW != webW || hostH != webH)) {
                Matrix m = new Matrix();
                m.setScale((float) webW / (float) hostW, (float) webH / (float) hostH);
                ev.transform(m);
            }

            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && !tab.web.hasFocus()) {
                tab.web.requestFocus();
            }
            tab.web.dispatchTouchEvent(ev);
        } catch (Throwable t) {
            Log.w(TAG, "handleDispatchTouchOnMain failed", t);
        } finally {
            if (ev != null) {
                try {
                    ev.recycle();
                } catch (Throwable ignored) {}
            }
        }
    }

    private WorkerTab findAttachedSurfaceTab() {
        synchronized (tabTableLock) {
            for (WorkerTab t : tabs.values()) {
                if (!t.closed && t.surfaceAttached) return t;
            }
        }
        return null;
    }

    private void detachSurfaceInternal(WorkerTab tab, long expectedSeq) {
        if (tab == null) return;
        if (expectedSeq != 0L && tab.surfaceAttachSeq != expectedSeq) return;
        if (!tab.surfaceAttached && tab.surfaceHost == null) return;

        if (tab.hostToken != null && tab.hostDeathRecipient != null) {
            try {
                tab.hostToken.unlinkToDeath(tab.hostDeathRecipient, 0);
            } catch (Throwable ignored) {}
        }
        tab.hostToken = null;
        tab.hostDeathRecipient = null;

        if (tab.web != null && tab.surfaceLayoutListener != null) {
            try {
                tab.web.removeOnLayoutChangeListener(tab.surfaceLayoutListener);
            } catch (Throwable ignored) {}
        }
        tab.surfaceLayoutListener = null;
        tab.lastViewportSyncW = 0;
        tab.lastViewportSyncH = 0;
        tab.lastViewportSyncAttached = false;

        if (tab.surfaceContainer != null && tab.web != null && tab.web.getParent() == tab.surfaceContainer) {
            try {
                tab.surfaceContainer.removeView(tab.web);
            } catch (Throwable ignored) {}
        }
        if (tab.surfaceHost != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            SurfaceHostApi30.releaseHost(tab.surfaceHost);
        }
        tab.surfaceHost = null;
        tab.surfaceContainer = null;
        tab.surfaceAttached = false;
        tab.surfaceAttachSeq = 0L;
        tab.surfaceW = 0;
        tab.surfaceH = 0;

        if (!tab.closed && tab.web != null) {
            if (tab.tabCtx != null) {
                tab.tabCtx.setBaseContext(offscreenHost.getHostContext());
            }
            offscreenHost.mount(tab.web, effectiveDesktopOf(tab));
            syncWorkerDesktopViewport(tab);
        }
    }

    private boolean sendAttachSurfaceAck(
            Messenger replyTo,
            boolean ok,
            String tabId,
            long attachSeq,
            WorkerTab tab,
            Object pkg,
            String error
    ) {
        try {
            if (replyTo == null) return false;
            Message ack = Message.obtain(null, ProxyWorkerBridge.MSG_ATTACH_SURFACE_ACK);
            Bundle out = new Bundle();
            out.putBoolean(ProxyWorkerBridge.KEY_OK, ok);
            out.putString(ProxyWorkerBridge.KEY_TAB_ID, tabId == null ? "" : tabId);
            out.putLong(ProxyWorkerBridge.KEY_ATTACH_SEQ, attachSeq);
            out.putString(ProxyWorkerBridge.KEY_ERROR, error == null ? "" : error);
            if (ok && tab != null) {
                out.putString(ProxyWorkerBridge.KEY_URL, nz(tab.url));
                out.putString(ProxyWorkerBridge.KEY_TITLE, nz(tab.title));
                out.putBoolean(ProxyWorkerBridge.KEY_LOADING, tab.loading);
                out.putInt(ProxyWorkerBridge.KEY_PROGRESS, tab.progress);
                out.putBoolean(ProxyWorkerBridge.KEY_CAN_GO_BACK, tab.canGoBack);
                out.putBoolean(ProxyWorkerBridge.KEY_CAN_GO_FORWARD, tab.canGoForward);
                out.putBoolean(ProxyWorkerBridge.KEY_DESKTOP, effectiveDesktopOf(tab));
            }
            if (pkg != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                SurfaceHostApi30.putPackage(out, ProxyWorkerBridge.KEY_SURFACE_PACKAGE, pkg);
            }
            ack.setData(out);
            replyTo.send(ack);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "sendAttachSurfaceAck failed", t);
            return false;
        } finally {
            if (pkg != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                SurfaceHostApi30.releasePackage(pkg);
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static final class SurfaceHostApi30 {
        static Object createAndSetView(Context themedCtx, Display display, IBinder hostToken, View view, int w, int h) {
            SurfaceControlViewHost host = new SurfaceControlViewHost(themedCtx, display, hostToken);
            host.setView(view, w, h);
            return host;
        }

        static Object getSurfacePackage(Object hostObj) {
            if (hostObj instanceof SurfaceControlViewHost) {
                return ((SurfaceControlViewHost) hostObj).getSurfacePackage();
            }
            return null;
        }

        static void relayout(Object hostObj, int w, int h) {
            if (hostObj instanceof SurfaceControlViewHost) {
                ((SurfaceControlViewHost) hostObj).relayout(w, h);
            }
        }

        static void releaseHost(Object hostObj) {
            if (hostObj instanceof SurfaceControlViewHost) {
                try {
                    ((SurfaceControlViewHost) hostObj).release();
                } catch (Throwable ignored) {}
            }
        }

        static void releasePackage(Object pkg) {
            if (pkg instanceof SurfaceControlViewHost.SurfacePackage) {
                try {
                    ((SurfaceControlViewHost.SurfacePackage) pkg).release();
                } catch (Throwable ignored) {}
            }
        }

        static void putPackage(Bundle b, String key, Object pkg) {
            if (b != null && pkg instanceof SurfaceControlViewHost.SurfacePackage) {
                b.putParcelable(key, (SurfaceControlViewHost.SurfacePackage) pkg);
            }
        }
    }

    // ------------------------------------------------------------------ 导航等待 / 稳定判定

    private boolean navWait(WorkerTab tab, int seq, String before, long maxMs) {
        long end = SystemClock.uptimeMillis() + maxMs;
        String b = before == null ? "" : before;
        while (true) {
            checkStop();
            boolean navDone = tab.navSeq != seq && tab.finishedSeq == tab.navSeq && !tab.loading;
            boolean urlDone = !tab.loading && !b.equals(tab.url == null ? "" : tab.url);
            if (navDone || urlDone) {
                SystemClock.sleep(100L);
                return true;
            }
            if (SystemClock.uptimeMillis() >= end) return false;
            if (tab.navSeq == seq && !tab.loading && SystemClock.uptimeMillis() - (end - maxMs) > 3000L) {
                return false;
            }
            SystemClock.sleep(100L);
        }
    }

    private boolean waitLoadedNow(WorkerTab tab, long maxMs) {
        long end = SystemClock.uptimeMillis() + maxMs;
        while (true) {
            checkStop();
            if (!tab.loading && tab.finishedSeq == tab.navSeq) return true;
            if (SystemClock.uptimeMillis() >= end) return false;
            SystemClock.sleep(100L);
        }
    }

    private JSONObject applyNav(JSONObject out, WorkerTab tab, boolean loaded, String okText) throws Exception {
        int http = tab.httpStatus;
        if (tab.errorCode != 0) {
            String le = (tab.errorDesc == null || tab.errorDesc.isEmpty() ? "加载失败" : tab.errorDesc)
                    + "（错误码 " + tab.errorCode + "）";
            JSONObject e = err("页面打开失败：" + le + "。检查网址或网络后重试")
                    .put("loaded", false)
                    .put("load_error", le);
            if (http >= 400) e.put("http_status", http);
            return e;
        }
        if (!loaded) {
            out.put("loaded", false);
            if (http >= 400) out.put("http_status", http);
            out.put("result", okText + "（" + timeoutOf() + "ms 内未完全加载完，可先 snapshot 看已加载的部分）");
            return null;
        }
        out.put("loaded", true);
        if (http >= 400) out.put("http_status", http);
        out.put("result", okText + (http >= 400 ? "（注意：服务器返回 HTTP " + http + "）" : ""));
        return null;
    }

    private void settle(WorkerTab tab, int seqBefore, JSONObject out) throws Exception {
        long start = SystemClock.uptimeMillis();
        boolean fallback = false;
        int firstMut = -2;
        boolean sawMutDuringSettle = false;
        if (!tab.loading && tab.navSeq == seqBefore) {
            try {
                JSONObject mk = evalAgent(tab, "(typeof A.markAction==='function')?A.markAction():{}", 1000L);
                if (mk != null && mk.has("mutCount")) firstMut = mk.optInt("mutCount", -2);
            } catch (AbortException a) {
                throw a;
            } catch (Exception ignored) {}
        }
        sleepAbortable(60L);
        if (!tab.loading && tab.navSeq == seqBefore) {
            while (SystemClock.uptimeMillis() - start < SETTLE_MAX) {
                checkStop();
                if (tab.loading || tab.navSeq != seqBefore) break;
                JSONObject r;
                try {
                    r = evalAgent(tab, "(typeof A.waitStable==='function')?A.waitStable(180," + SETTLE_MAX + "):{nofn:1}", 2000L);
                } catch (AbortException a) {
                    throw a;
                } catch (Exception e) {
                    r = null;
                }
                if (r != null && r.optBoolean("nofn")) {
                    fallback = true;
                    break;
                }
                if (r != null) {
                    int mc = r.optInt("mutCount", -1);
                    if (firstMut == -2 && mc >= 0) firstMut = mc;
                    if (firstMut >= 0 && mc > firstMut) sawMutDuringSettle = true;
                    long elapsed = SystemClock.uptimeMillis() - start;
                    int pending = r.optInt("pending", 1);
                    int mutAge = r.optInt("mutAge", r.optInt("idleMs", 0));
                    boolean noDomMut = !sawMutDuringSettle && (firstMut >= 0 && mc == firstMut) && elapsed >= 120 && mutAge >= 120;
                    boolean stableReached = r.optBoolean("stable") && elapsed >= 120 && (!sawMutDuringSettle || mutAge >= 180);
                    if (stableReached || (pending == 0 && noDomMut) || (pending == 0 && elapsed >= 700)) break;
                }
                sleepAbortable(60L);
            }
        }
        if (fallback) {
            long spent = SystemClock.uptimeMillis() - start;
            if (spent < 350L) sleepAbortable(350L - spent);
        }
        finishNavIfNeeded(tab, seqBefore, out, fallback);
    }

    private void settleLight(WorkerTab tab, int seqBefore, JSONObject out) throws Exception {
        sleepAbortable(30L);
        finishNavIfNeeded(tab, seqBefore, out, false);
    }

    private void finishNavIfNeeded(WorkerTab tab, int seqBefore, JSONObject out, boolean fallback) throws Exception {
        if (tab.loading || tab.finishedSeq != tab.navSeq || tab.navSeq != seqBefore) {
            boolean full = tab.loading || tab.finishedSeq != tab.navSeq;
            boolean loaded = waitLoadedNow(tab, softLeft());
            if (full) {
                out.put("loaded", loaded);
                if (tab.httpStatus >= 400) out.put("http_status", tab.httpStatus);
                if (tab.errorCode != 0) {
                    out.put("load_error", (tab.errorDesc == null || tab.errorDesc.isEmpty() ? "加载失败" : tab.errorDesc)
                            + "（错误码 " + tab.errorCode + "）");
                }
            }
        } else if (fallback) {
            sleepAbortable(150L);
        }
    }

    // ------------------------------------------------------------------ agent.js / 点击 / 输入 / 按键

    private static void ensureAgentJs(Context ctx) {
        if (sAgentJs != null) return;
        synchronized (ProxyWorkerRuntime.class) {
            if (sAgentJs != null) return;
            try (InputStream in = ctx.getAssets().open("pimet-browser/agent.js")) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) bo.write(buf, 0, n);
                String src = new String(bo.toByteArray(), StandardCharsets.UTF_8);
                String ver = "a" + sha1Hex(src).substring(0, 12);
                sAgentVer = ver;
                sAgentJs = src.replace("__PIMET_AGENT_V__", ver);
            } catch (Throwable t) {
                throw new IllegalStateException("缺少 assets/pimet-browser/agent.js：" + friendly(t));
            }
        }
    }

    private static String sha1Hex(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return sb.toString();
    }

    private JSONObject evalAgent(WorkerTab tab, String expr, long timeoutMs) throws Exception {
        String qv = JSONObject.quote(sAgentVer);
        String probe = "(function(){try{var A=window.__pimetAgent;if(!A||(A.v!==" + qv + "&&A.__pv!==" + qv + "))return "
                + JSONObject.quote(NEED_AGENT) + ";return JSON.stringify(" + expr + ")}catch(e){return JSON.stringify({ok:false,error:String(e)})}})()";
        String raw = evalRaw(tab, probe, timeoutMs);
        Object v = parseRaw(raw);
        if (NEED_AGENT.equals(v)) {
            String full = "(function(){try{" + sAgentJs + "\n;try{window.__pimetAgent.__pv=" + qv + "}catch(x){}"
                    + "var A=window.__pimetAgent;return JSON.stringify(" + expr + ")}catch(e){return JSON.stringify({ok:false,error:String(e)})}})()";
            raw = evalRaw(tab, full, timeoutMs);
            v = parseRaw(raw);
        }
        if (!(v instanceof String)) {
            return new JSONObject().put("ok", false).put("error", "页面没有返回结果（可能正在加载）");
        }
        Object o = new JSONTokener((String) v).nextValue();
        if (o instanceof JSONObject) return (JSONObject) o;
        return new JSONObject().put("ok", false).put("error", "页面返回了意外的结果");
    }

    private static Object parseRaw(String raw) {
        if (raw == null || "null".equals(raw) || raw.isEmpty()) return null;
        try {
            return new JSONTokener(raw).nextValue();
        } catch (Throwable t) {
            return null;
        }
    }

    private JSONObject snapshot(WorkerTab tab, int maxChars, String scope) throws Exception {
        int mc = clamp(maxChars, 1500, 40000);
        String sc = ("viewport".equalsIgnoreCase(scope) || "interactive".equalsIgnoreCase(scope)) ? "viewport" : "full";
        String opts = "{scope:" + JSONObject.quote(sc) + ",maxChars:" + mc + ",mode:" + JSONObject.quote(sc) + "}";
        long tmo = Math.max(1000L, Math.min(10000L, hardLeft()));
        JSONObject r;
        try {
            r = evalAgent(tab, "A.snapshot(" + opts + ")", tmo);
            if (!r.has("text")) r = evalAgent(tab, "A.snapshot(" + mc + "," + JSONObject.quote(sc) + ")", tmo);
        } catch (AbortException a) {
            throw a;
        } catch (Exception e) {
            r = new JSONObject().put("ok", false).put("error", friendly(e));
        }
        if (!r.has("text")) {
            r.put("url", tab.url).put("title", tab.title)
                    .put("text", "网址: " + tab.url + "\n（页面还没准备好，无法读取快照"
                            + (r.has("error") ? "：" + r.optString("error") : "") + "）");
        }
        return r;
    }

    private JSONObject readPage(WorkerTab tab, JSONObject req, int defaultMaxChars) throws Exception {
        int mc = clamp(req.optInt("max_chars", req.optInt("maxChars", defaultMaxChars)), 200, 40000);
        String sel = req.optString("selector", "").trim();
        boolean incLinks = req.has("include_links") ? req.optBoolean("include_links", true) : req.optBoolean("includeLinks", true);
        StringBuilder opts = new StringBuilder("{maxChars:").append(mc).append(",includeLinks:").append(incLinks);
        if (!sel.isEmpty()) opts.append(",selector:").append(JSONObject.quote(sel));
        opts.append("}");
        long tmo = Math.max(1000L, Math.min(10000L, hardLeft()));
        JSONObject r = evalAgent(tab, "A.read(" + opts + ")", tmo);
        if (!r.optBoolean("ok")) {
            throw new IllegalStateException(agentErr(r, "提取正文失败"));
        }
        return r;
    }

    private static long estimateNativeTypeExtraMs(JSONObject req) {
        String text = req.has("text") ? req.optString("text", "") : req.optString("value", "");
        int maxDelay = clamp(req.optInt("max_delay_ms", 145), 0, 5000);
        long est = (long) text.length() * (Math.max(55, maxDelay) + 45L) + 5000L;
        return Math.min(120000L, Math.max(0L, est));
    }

    private static String fmtCoord(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-6) return String.valueOf((long) Math.rint(v));
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private NativeInputHelper.HostBridge hostBridge(WorkerTab tab) {
        return new NativeInputHelper.HostBridge() {
            @Override
            public <T> T onMain(java.util.concurrent.Callable<T> task, long timeoutMs) throws Exception {
                return ProxyWorkerRuntime.this.onMain(task::call, timeoutMs);
            }

            @Override
            public void sleep(long ms) throws Exception {
                sleepAbortable(ms);
            }

            @Override
            public void checkBeforeStep() throws Exception {
                checkStop();
            }

            @Override
            public boolean isWebViewReady(WebView w) {
                return w != null && !tab.closed && tab.web == w
                        && (tab.surfaceAttached || !offscreenHost.isOverlayFallback())
                        && w.isAttachedToWindow();
            }

            @Override
            public void setAgentDispatching(boolean dispatching) {
                // 子进程无主进程 userInputAt 取舍，surface 仅发 focus 通知
            }

            @Override
            public String evalRawSafe(String js, long timeoutMs) {
                return ProxyWorkerRuntime.this.evalRawSafe(tab, js, timeoutMs);
            }

            @Override
            public boolean isDesktop() {
                return effectiveDesktopOf(tab);
            }
        };
    }

    private String click(WorkerTab tab, JSONObject req, JSONObject out) throws Exception {
        double[] directCoords = NativeInputHelper.parseClickCoords(req);
        if (directCoords != null) {
            boolean useJs = req.optBoolean("js", false);
            boolean nativeOk = false;
            if (!useJs && (tab.surfaceAttached || !offscreenHost.isOverlayFallback())) {
                double vw = NativeInputHelper.queryViewportWidth(hostBridge(tab), tab.web);
                nativeOk = Boolean.TRUE.equals(onMain(() -> nativeTap(tab, directCoords[0], directCoords[1], vw), 4000L));
            }
            if (!nativeOk) {
                String coordStr = "[" + directCoords[0] + "," + directCoords[1] + "]";
                JSONObject loc = evalAgent(tab, "A.locate(" + JSONObject.quote(coordStr) + ",\"\")", 5000L);
                if (!loc.optBoolean("ok")) throw new IllegalStateException(agentErr(loc, "定位失败"));
                JSONObject jr = evalAgent(tab, "A.jsClick()", 5000L);
                if (jr.has("ok") && !jr.optBoolean("ok")) {
                    throw new IllegalStateException(jr.optString("error", "JS 点击失败"));
                }
            }
            if (out != null) {
                try { out.put("trusted", nativeOk); } catch (Throwable ignored) {}
            }
            return "已点击坐标 [" + fmtCoord(directCoords[0]) + ", " + fmtCoord(directCoords[1]) + "]"
                    + (nativeOk ? "" : (useJs ? "（JS 点击）" : "（用 JS 点击）"));
        }

        String tgt = targetOf(req);
        if (tgt.isEmpty()) throw new IllegalArgumentException("click 需要 target / ref / text 或 x/y");
        JSONObject loc = evalAgent(tab, "A.locate(" + JSONObject.quote(tgt) + ",\"\")", 5000L);
        if (!loc.optBoolean("ok")) throw new IllegalStateException(agentErr(loc, "定位失败"));
        String labelStr = loc.optString("label");
        boolean useJs = req.optBoolean("js", false);
        boolean nativeOk = false;
        String note = "";
        String coveredBy = loc.optString("coveredBy", "");
        if (!useJs && (tab.surfaceAttached || !offscreenHost.isOverlayFallback())) {
            boolean scrolled = loc.optBoolean("scrolled", true);
            boolean hasCoords = !Double.isNaN(loc.optDouble("x", Double.NaN))
                    && !Double.isNaN(loc.optDouble("y", Double.NaN))
                    && loc.optDouble("vw", 0) > 0;
            if (scrolled || !hasCoords) {
                sleepAbortable(100L);
                JSONObject rl = evalAgent(tab, "(typeof A.relocate==='function')?A.relocate():{nofn:1}", 3000L);
                if (!rl.optBoolean("nofn")) {
                    if (!rl.optBoolean("ok")) {
                        throw new IllegalStateException(rl.optString("error", "元素位置已变化、重新定位失败，请重新 snapshot"));
                    }
                    loc = rl;
                    if (rl.has("coveredBy")) coveredBy = rl.optString("coveredBy", "");
                }
            }
            final double x = loc.optDouble("x"), y = loc.optDouble("y"), vw = loc.optDouble("vw");
            boolean armed = "1".equals(evalRawSafe(tab, HIT_ARM_JS, 2000L));
            int seq = tab.navSeq;
            nativeOk = Boolean.TRUE.equals(onMain(() -> nativeTap(tab, x, y, vw), 4000L));
            if (nativeOk && armed) {
                int hit = 0;
                long until = SystemClock.uptimeMillis() + 450L;
                while (true) {
                    sleepAbortable(30L);
                    if (tab.navSeq != seq || tab.loading) { hit = -1; break; }
                    hit = readHit(tab);
                    if (hit != 0 || SystemClock.uptimeMillis() >= until) break;
                }
                evalRawSafe(tab, HIT_OFF_JS, 2000L);
                if (hit == 0 && tab.navSeq == seq && !tab.loading) {
                    note = "（未确认触摸送达目标；页面若没反应，用 js=true 重试）";
                } else if (hit == 2) {
                    note = "；注意：触摸落在了" + (coveredBy.isEmpty() ? "遮挡物" : "遮挡物「" + coveredBy + "」")
                            + "上，目标没有收到点击，必要时加 js=true 重试";
                }
            } else if (armed) {
                evalRawSafe(tab, HIT_OFF_JS, 2000L);
            }
        }
        if (!nativeOk) {
            JSONObject jr = evalAgent(tab, "A.jsClick()", 5000L);
            if (jr.has("ok") && !jr.optBoolean("ok")) {
                throw new IllegalStateException(jr.optString("error", "JS 点击失败"));
            }
        }
        if (out != null) {
            try { out.put("trusted", nativeOk); } catch (Throwable ignored) {}
        }
        return "已点击 " + tgt + (labelStr.isEmpty() ? "" : "（" + labelStr + "）")
                + (!nativeOk && !useJs && note.isEmpty() ? "（用 JS 点击）" : "") + note;
    }

    private boolean nativeTap(WorkerTab tab, double cssX, double cssY, double vw) {
        WebView w = tab.web;
        if (w == null || tab.closed || (!tab.surfaceAttached && offscreenHost.isOverlayFallback()) || !w.isAttachedToWindow()) return false;
        return NativeInputHelper.nativeTapOnMain(w, cssX, cssY, vw);
    }

    private void pressKey(WorkerTab tab, String key, int code) throws Exception {
        JSONObject r = evalAgent(tab, "A.key(" + JSONObject.quote(key) + ")", 5000L);
        if (r.has("ok") && !r.optBoolean("ok")) {
            if (tab.surfaceAttached || !offscreenHost.isOverlayFallback()) {
                onMain(() -> {
                    if (tab.web == null || !tab.web.isAttachedToWindow()) return null;
                    long now = SystemClock.uptimeMillis();
                    tab.web.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
                    tab.web.dispatchKeyEvent(new KeyEvent(now, now + 30, KeyEvent.ACTION_UP, code, 0));
                    return null;
                }, 4000L);
            } else {
                throw new IllegalStateException(r.optString("error", "按键失败"));
            }
        }
    }

    private String type(WorkerTab tab, JSONObject req) throws Exception {
        String ref = fieldOf(req);
        String text = req.has("value") ? req.optString("value", "") : req.optString("text", "");
        boolean clear = req.optBoolean("clear", true);
        boolean submit = req.optBoolean("submit", false);
        JSONObject r = evalAgent(tab, "A.type(" + JSONObject.quote(ref) + "," + JSONObject.quote(text) + "," + clear + ")", 5000L);
        if (!r.optBoolean("ok")) throw new IllegalStateException(agentErr(r, "输入失败"));
        String res = "已输入" + (ref.isEmpty() ? "" : "到 " + ref);
        if (clear && r.has("value") && !r.isNull("value")) {
            String v = r.optString("value");
            String vt = v.trim(), tt = text.trim();
            boolean same = vt.equals(tt) || (vt.endsWith("…") && tt.startsWith(vt.substring(0, vt.length() - 1)));
            if (!same) {
                res += "（注意：输入框现在的值是「" + (v.length() > 60 ? v.substring(0, 60) + "…" : v) + "」，可能被页面格式化或限制了长度）";
            }
        } else if (clear && r.has("len") && r.optInt("len", -1) != text.length()) {
            res += "（注意：输入框现在有 " + r.optInt("len") + " 个字符，和输入的 " + text.length() + " 个不一致）";
        }
        if (submit) {
            if (r.optBoolean("form")) {
                JSONObject s = evalAgent(tab, "A.submit()", 5000L);
                if (s.has("ok") && !s.optBoolean("ok")) pressKey(tab, "Enter", KeyEvent.KEYCODE_ENTER);
            } else {
                pressKey(tab, "Enter", KeyEvent.KEYCODE_ENTER);
            }
            res += "并提交";
        }
        return res;
    }

    private JSONObject batch(WorkerTab tab, JSONObject req, JSONObject out) throws Exception {
        JSONArray steps = req.optJSONArray("steps");
        if (steps == null || steps.length() == 0) return err("batch 需要 steps（数组）");
        boolean stopOnError = req.optBoolean("stopOnError", true);
        boolean batchSettle = req.optBoolean("settle", true);
        JSONArray results = new JSONArray();
        int stoppedAt = -1;
        JSONObject lastSnap = null;
        JSONObject lastRead = null;
        for (int i = 0; i < steps.length(); i++) {
            checkStop();
            JSONObject st = steps.optJSONObject(i);
            if (st == null) {
                results.put(new JSONObject().put("i", i).put("ok", false).put("error", "步骤不是对象"));
                if (stopOnError) { stoppedAt = i; break; }
                continue;
            }
            String act = st.optString("action", st.optString("op", "")).trim().toLowerCase(Locale.ROOT);
            boolean stepSettle = st.has("settle") ? st.optBoolean("settle", true) : batchSettle;
            boolean isLast = i == steps.length() - 1;
            JSONObject sr = new JSONObject().put("i", i).put("action", act);
            try {
                int seq = tab.navSeq;
                String before = tab.url;
                switch (act) {
                    case "click":
                    case "tap":
                        sr.put("result", click(tab, st, sr));
                        if (stepSettle) settle(tab, seq, sr);
                        markChanged(sr, tab, seq, before);
                        break;
                    case "drag": {
                        NativeInputHelper.DragResult dr = NativeInputHelper.drag(hostBridge(tab), tab.web, st);
                        sr.put("trusted", true)
                                .put("from", new JSONArray().put(dr.fromX).put(dr.fromY))
                                .put("to", new JSONArray().put(dr.toX).put(dr.toY))
                                .put("steps", dr.steps)
                                .put("duration_ms", dr.durationMs)
                                .put("result", "已原生拖拽 [" + fmtCoord(dr.fromX) + ", " + fmtCoord(dr.fromY) + "] → ["
                                        + fmtCoord(dr.toX) + ", " + fmtCoord(dr.toY) + "]（" + dr.steps + " 步，" + dr.durationMs + "ms）");
                        if (stepSettle) settle(tab, seq, sr);
                        markChanged(sr, tab, seq, before);
                        break;
                    }
                    case "native_type": {
                        NativeInputHelper.TypeResult tr = NativeInputHelper.nativeType(hostBridge(tab), tab.web, st);
                        sr.put("trusted", true)
                                .put("typed", tr.typed)
                                .put("key_events", tr.keyEvents)
                                .put("ime_commits", tr.imeCommits)
                                .put("duration_ms", tr.durationMs)
                                .put("result", "已原生逐字输入 " + tr.typed + " 字符（key_events=" + tr.keyEvents
                                        + ", ime_commits=" + tr.imeCommits + ", " + tr.durationMs + "ms）");
                        if (stepSettle) {
                            if (!st.has("settle") && !isLast && !st.optBoolean("submit", false)) settleLight(tab, seq, sr);
                            else settle(tab, seq, sr);
                        }
                        markChanged(sr, tab, seq, before);
                        break;
                    }
                    case "type":
                    case "fill":
                        sr.put("result", type(tab, st));
                        if (stepSettle) {
                            if (!st.has("settle") && !isLast && !st.optBoolean("submit", false)) settleLight(tab, seq, sr);
                            else settle(tab, seq, sr);
                        }
                        markChanged(sr, tab, seq, before);
                        break;
                    case "select": {
                        JSONObject r = evalAgent(tab,
                                "A.select(" + JSONObject.quote(fieldOf(st)) + "," + JSONObject.quote(st.optString("value", "")) + ")",
                                5000L);
                        if (!r.optBoolean("ok")) throw new IllegalStateException(agentErr(r, "选择失败"));
                        sr.put("result", r.optString("result"));
                        if (stepSettle) {
                            if (!st.has("settle") && !isLast) settleLight(tab, seq, sr);
                            else settle(tab, seq, sr);
                        }
                        markChanged(sr, tab, seq, before);
                        break;
                    }
                    case "press":
                    case "key": {
                        String key = st.optString("key", "Enter");
                        int code = keyCode(key);
                        if (code == 0) throw new IllegalStateException("不支持的按键 " + key);
                        pressKey(tab, key, code);
                        sr.put("result", "已按 " + key);
                        if (stepSettle) settle(tab, seq, sr);
                        markChanged(sr, tab, seq, before);
                        break;
                    }
                    case "hover": {
                        String tgt = targetOf(st);
                        if (tgt.isEmpty()) throw new IllegalStateException("hover 需要 target");
                        JSONObject loc = evalAgent(tab, "A.locate(" + JSONObject.quote(tgt) + ",\"\")", 5000L);
                        if (!loc.optBoolean("ok")) throw new IllegalStateException(agentErr(loc, "定位失败"));
                        evalJson(tab, HOVER_JS, 3000L);
                        sr.put("result", "已悬停 " + tgt);
                        if (stepSettle) sleepAbortable(120L);
                        break;
                    }
                    case "scroll": {
                        JSONObject r = evalAgent(tab, "A.scroll(" + JSONObject.quote(fieldOf(st)) + ","
                                + JSONObject.quote(st.optString("direction", "down")) + "," + st.optDouble("amount", 0.8) + ")", 5000L);
                        if (!r.optBoolean("ok")) throw new IllegalStateException(agentErr(r, "滚动失败"));
                        sr.put("result", r.optString("result"));
                        if (stepSettle) sleepAbortable(200L);
                        break;
                    }
                    case "scroll_into_view": {
                        String tgt = targetOf(st);
                        if (tgt.isEmpty()) throw new IllegalStateException("scroll_into_view 需要 target");
                        JSONObject loc = evalAgent(tab, "A.locate(" + JSONObject.quote(tgt) + ",\"\")", 5000L);
                        if (!loc.optBoolean("ok")) throw new IllegalStateException(agentErr(loc, "定位失败"));
                        sr.put("result", "已滚动到可见");
                        break;
                    }
                    case "wait": {
                        String text = st.optString("text", "");
                        if (!text.isEmpty()) {
                            JSONObject w = waitFor(tab, new JSONObject()
                                    .put("text", text)
                                    .put("timeout_ms", st.optInt("timeout_ms", st.optInt("ms", 10000))));
                            sr.put("result", w.optString("result")).put("found", w.optBoolean("found"));
                        } else {
                            sleepAbortable(clamp(st.optInt("ms", 1000), 0, 60000));
                            waitLoadedNow(tab, softLeft());
                            sr.put("result", "已等待");
                        }
                        break;
                    }
                    case "wait_for": {
                        JSONObject w = waitFor(tab, st);
                        sr.put("result", w.optString("result")).put("found", w.optBoolean("found"));
                        break;
                    }
                    case "snapshot": {
                        JSONObject snap = snapshot(tab, st.optInt("max_chars", req.optInt("max_chars", 12000)), scopeOf(st));
                        sr.put("result", "快照").put("snapshot", snap.optString("text"));
                        lastSnap = snap;
                        break;
                    }
                    case "read": {
                        JSONObject r = readPage(tab, st, req.optInt("max_chars", req.optInt("maxChars", 12000)));
                        int len = r.optInt("length", r.optString("text", "").length());
                        boolean trunc = r.optBoolean("truncated", false);
                        sr.put("result", "已提取正文（" + len + " 字" + (trunc ? "，已截断" : "") + "）")
                                .put("text", r.optString("text", ""))
                                .put("length", len)
                                .put("truncated", trunc);
                        if (r.has("links")) sr.put("links", r.optJSONArray("links"));
                        lastRead = r;
                        break;
                    }
                    default:
                        throw new IllegalStateException("batch 不支持的 action：" + act);
                }
                sr.put("ok", true).put("url", tab.url);
            } catch (AbortException a) {
                throw a;
            } catch (Throwable t) {
                sr.put("ok", false).put("error", friendly(t)).put("url", tab.url);
                results.put(sr);
                if (stopOnError) { stoppedAt = i; break; }
                continue;
            }
            results.put(sr);
        }
        out.put("steps", results).put("url", tab.url).put("title", tab.title);
        if (stoppedAt >= 0) out.put("stopped_at", stoppedAt);
        if (lastSnap != null) {
            out.put("snapshot", lastSnap.optString("text"));
            out.put("url", lastSnap.optString("url", tab.url));
            out.put("title", lastSnap.optString("title", tab.title));
            if (lastSnap.has("refs")) out.put("refs", lastSnap.opt("refs"));
        }
        if (lastRead != null) {
            out.put("text", lastRead.optString("text", ""));
            out.put("length", lastRead.optInt("length", lastRead.optString("text", "").length()));
            out.put("truncated", lastRead.optBoolean("truncated", false));
            if (lastRead.has("links")) out.put("links", lastRead.optJSONArray("links"));
            if (lastSnap == null) {
                out.put("url", lastRead.optString("url", tab.url));
                out.put("title", lastRead.optString("title", tab.title));
            }
        }
        out.put("result", "执行了 " + results.length() + "/" + steps.length() + " 步"
                + (stoppedAt >= 0 ? "，在第 " + stoppedAt + " 步出错停止" : ""));
        return out;
    }

    // ------------------------------------------------------------------ console / inject / waitFor / eval

    private JSONObject doConsole(WorkerTab tab, JSONObject req, JSONObject out) throws Exception {
        int max = clamp(req.optInt("max", 100), 1, 300);
        boolean clear = req.optBoolean("clear", false);
        JSONArray logs = consoleLogs(tab, max);
        int errors = 0;
        for (int i = 0; i < logs.length(); i++) {
            JSONObject o = logs.optJSONObject(i);
            if (o != null && "error".equalsIgnoreCase(o.optString("level", ""))) errors++;
        }
        if (clear) clearConsoleLogs(tab);
        return out.put("logs", logs).put("url", tab.url)
                .put("result", (logs.length() == 0 ? "控制台没有新日志" : "控制台日志 " + logs.length() + " 条（其中错误 " + errors + " 条）")
                        + (clear ? "；已清空（之后只返回新日志）" : ""));
    }

    private JSONObject doInject(WorkerTab tab, JSONObject req, JSONObject out) throws Exception {
        String js = req.optString("js", "");
        if (js.isEmpty()) return err("inject 需要 js");
        JSONObject persist = req.optJSONObject("persist");
        if (persist == null) {
            String[] v = evalAsync(tab, js, req.optBoolean("fn", false), Math.max(1000L, softLeft()));
            if (v[0] != null) return err(v[0]).put("url", tab.url);
            return out.put("url", tab.url).put("result", v[1]);
        }
        String host = "";
        try {
            host = nz(Uri.parse(nz(tab.url)).getHost());
        } catch (Throwable ignored) {}
        List<String> matches = new ArrayList<>();
        Object mo = persist.opt("match");
        if (mo instanceof JSONArray) {
            JSONArray a = (JSONArray) mo;
            for (int i = 0; i < a.length(); i++) {
                String m = a.optString(i, "").trim();
                if (!m.isEmpty()) matches.add(m);
            }
        } else if (mo instanceof String && !((String) mo).trim().isEmpty()) {
            matches.add(((String) mo).trim());
        }
        if (matches.isEmpty()) {
            if (host.isEmpty()) return err("persist 需要 match（如 *://*.example.com/*），当前页没有可用的域名");
            matches.add("*://" + host + "/*");
        }
        String name = persist.optString("name", "").trim();
        if (name.isEmpty()) name = "Pi 脚本 " + (host.isEmpty() ? matches.get(0) : host);
        StringBuilder sb = new StringBuilder();
        sb.append("// ==UserScript==\n");
        sb.append("// @name         ").append(name.replace("\n", " ").replace("\r", " ")).append("\n");
        sb.append("// @namespace    pimet-agent\n");
        sb.append("// @version      ").append(persist.optString("version", "1.0").trim()).append("\n");
        sb.append("// @description  由 Pi 通过 browser inject 生成\n");
        for (String mt : matches) sb.append("// @match        ").append(mt.replace("\n", " ").replace("\r", " ")).append("\n");
        String runAt = persist.optString("run_at", persist.optString("runAt", "document-idle")).trim();
        sb.append("// @run-at       ").append(runAt.isEmpty() ? "document-idle" : runAt).append("\n");
        sb.append("// @grant        none\n");
        sb.append("// ==/UserScript==\n\n");
        sb.append(js).append("\n");

        UserScriptManager m = scriptMgr;
        UserScriptManager.Script s = m.install(sb.toString(), null);
        if (s == null) return err("安装脚本失败");
        boolean ran = false;
        if (matchingIds(m, tab.url).contains(s.id)) {
            String code = m.buildIsolatedOneShot(s, tab.id);
            if (code != null && !code.isEmpty()) {
                evalRawSafe(tab, code, 5000L);
                ran = true;
            }
        }
        onMain(() -> {
            if (tab.web != null && !tab.closed) installFrameDocStartScript(tab, tab.web);
            return null;
        }, 3000L);

        JSONObject brief = new JSONObject()
                .put("id", s.id)
                .put("name", nz(s.name))
                .put("version", nz(s.version))
                .put("enabled", s.enabled)
                .put("matchesCurrent", matchingIds(m, tab.url).contains(s.id))
                .put("runAt", s.runAt == null || s.runAt.isEmpty() ? "document-idle" : s.runAt);
        return out.put("url", tab.url)
                .put("script", brief)
                .put("result", "已安装为用户脚本「" + nz(s.name) + "」（id " + s.id + "，匹配 " + String.join(" ", matches) + "）"
                        + (ran ? "，并已在当前页执行一次" : "，当前页不匹配，打开匹配的网页时生效"));
    }

    private JSONObject waitFor(WorkerTab tab, JSONObject req) throws Exception {
        String sel = req.optString("selector", "");
        String text = req.optString("text", "");
        boolean visible = req.optBoolean("visible", false);
        long tmo = clamp(req.optInt("timeout_ms", 10000), 0, 30000);
        long t0 = SystemClock.uptimeMillis();
        long end = t0 + Math.min(tmo, softLeft());
        boolean found = false;
        boolean badSel = false;
        while (true) {
            checkStop();
            if (!sel.isEmpty()) {
                String raw = evalRawSafe(tab, WAIT_SEL_JS + "(" + JSONObject.quote(sel) + ")", 3000L);
                int v = -2;
                try { v = raw == null ? -2 : Integer.parseInt(raw.trim()); } catch (Throwable ignored) {}
                if (v == -1) { badSel = true; break; }
                if (v == 2 || (v == 1 && !visible)) { found = true; break; }
            } else {
                JSONObject r;
                try {
                    r = evalAgent(tab, "A.hasText(" + JSONObject.quote(text) + ")", 3000L);
                } catch (AbortException a) {
                    throw a;
                } catch (Exception e) {
                    r = new JSONObject();
                }
                if (r.optBoolean("v")) { found = true; break; }
            }
            if (SystemClock.uptimeMillis() >= end) break;
            sleepAbortable(300L);
        }
        JSONObject out = new JSONObject();
        if (badSel) {
            return out.put("found", false)
                    .put("waited_ms", (int) (SystemClock.uptimeMillis() - t0))
                    .put("bad_selector", true)
                    .put("result", "选择器写法不对：" + sel);
        }
        String what = sel.isEmpty() ? "「" + text + "」" : "元素 " + sel;
        return out.put("found", found)
                .put("waited_ms", (int) (SystemClock.uptimeMillis() - t0))
                .put("result", found ? "页面已出现" + what : "等待超时（" + tmo + "ms），页面没有出现" + what);
    }

    private String evalRaw(WorkerTab tab, String js, long timeoutMs) throws Exception {
        final Req req = curReq.get();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> ref = new AtomicReference<>();
        AtomicBoolean claimed = new AtomicBoolean(false);
        main.post(() -> {
            if (!claimed.compareAndSet(false, true)) return;
            if (aborted(req) || tab.web == null || tab.closed) {
                latch.countDown();
                return;
            }
            try {
                tab.web.evaluateJavascript(js, v -> {
                    ref.set(v);
                    latch.countDown();
                });
            } catch (Throwable t) {
                latch.countDown();
            }
        });
        long wait = Math.max(1L, Math.min(timeoutMs, req == null ? timeoutMs : hardLeft()));
        if (!awaitAbortable(latch, wait, claimed)) {
            claimed.set(true);
            checkStop();
            throw new IllegalStateException("页面脚本执行超时");
        }
        checkStop();
        return ref.get();
    }

    private String evalRawSafe(WorkerTab tab, String js, long timeoutMs) {
        try {
            return evalRaw(tab, js, timeoutMs);
        } catch (AbortException a) {
            throw a;
        } catch (Exception e) {
            return null;
        }
    }

    private JSONObject evalJson(WorkerTab tab, String expr, long timeoutMs) throws Exception {
        Object v = parseRaw(evalRaw(tab, "(function(){try{return JSON.stringify(" + expr + ")}catch(e){return JSON.stringify({error:String(e)})}})()", timeoutMs));
        if (!(v instanceof String)) return new JSONObject();
        Object o = new JSONTokener((String) v).nextValue();
        return o instanceof JSONObject ? (JSONObject) o : new JSONObject();
    }

    private String[] evalAsync(WorkerTab tab, String script, boolean fn, long timeoutMs) throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "");
        String started;
        if (fn) {
            started = startEval(tab, id, script);
        } else {
            String expr = stripTrailingSemis(script);
            started = expr.isEmpty() ? null : startEval(tab, id, "return (" + expr + "\n);");
            if (started == null || "null".equals(started)) started = startEval(tab, id, script);
        }
        if (started == null || "null".equals(started)) {
            return new String[]{"脚本没有运行（语法错误，或页面正在加载）。请检查代码；需要返回值时写 return 语句，如 return document.title;", null};
        }
        long end = SystemClock.uptimeMillis() + timeoutMs;
        String qid = JSONObject.quote(id);
        try {
            while (SystemClock.uptimeMillis() < end) {
                checkStop();
                String raw = evalRaw(tab,
                        "(function(){var m=window.__pimetEval||{},r=m[" + qid + "];if(!r)return JSON.stringify({done:false});delete m["
                                + qid + "];return JSON.stringify({done:true,ok:r.ok,v:r.v});})()",
                        3000L);
                Object pv = parseRaw(raw);
                JSONObject r = pv instanceof String ? new JSONObject((String) pv) : new JSONObject();
                if (r.optBoolean("done")) {
                    String v = r.optString("v");
                    if (v.length() > 40000) v = v.substring(0, 40000) + "…（已截断）";
                    return r.optBoolean("ok") ? new String[]{null, v} : new String[]{"脚本出错: " + v, null};
                }
                sleepAbortable(150L);
            }
        } catch (AbortException a) {
            cleanupEval(tab, qid);
            throw a;
        }
        cleanupEval(tab, qid);
        return new String[]{"脚本超时（" + timeoutMs + "ms）未返回（页面跳转也会导致结果丢失）", null};
    }

    private String startEval(WorkerTab tab, String id, String body) throws Exception {
        String js = "(function(){window.__pimetEval=window.__pimetEval||{};var id=" + JSONObject.quote(id) + ";"
                + "(async function(){" + body + "\n})().then(function(v){var s;try{s=typeof v==='string'?v:JSON.stringify(v);}catch(e){s=String(v);}"
                + "window.__pimetEval[id]={ok:true,v:s===undefined?'undefined':s};},function(e){window.__pimetEval[id]={ok:false,v:String(e&&e.stack||e)};});return 1;})()";
        return evalRaw(tab, js, 5000L);
    }

    private void cleanupEval(WorkerTab tab, String qid) {
        main.post(() -> {
            try {
                if (tab.web != null && !tab.closed) {
                    tab.web.evaluateJavascript("(function(){try{var m=window.__pimetEval;if(m)delete m[" + qid + "]}catch(e){}})()", null);
                }
            } catch (Throwable ignored) {}
        });
    }

    private int readHit(WorkerTab tab) {
        String raw = evalRawSafe(tab, HIT_READ_JS, 2000L);
        if (raw == null) return -1;
        try {
            return Integer.parseInt(raw.trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ 配置/站点规则/菜单/控制台

    private String uiRedirect(String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return null;
        try {
            String r = customizer.redirect(url);
            if (r == null || r.equals(url) || !(r.startsWith("http://") || r.startsWith("https://"))) return null;
            return r;
        } catch (Throwable e) {
            return null;
        }
    }

    private boolean uiBlocked(String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return false;
        BrowserCustomizer c = customizer;
        if (c == null) return false;
        try {
            return c.shouldBlock(url);
        } catch (Throwable e) {
            return false;
        }
    }

    @SuppressLint("RequiresFeature")
    private void applySiteSettings(WorkerTab t, String url, boolean beforeNav) {
        if (t == null || t.web == null) return;
        JSONObject s = null;
        if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
            try { s = customizer.siteSettings(url); } catch (Throwable ignored) {}
        }
        if (s == null) s = new JSONObject();
        t.site = s;
        WebSettings ws = t.web.getSettings();
        boolean js = s.optBoolean("js", true);
        if (ws.getJavaScriptEnabled() != js) ws.setJavaScriptEnabled(js);
        boolean img = s.optBoolean("images", true);
        if (ws.getLoadsImagesAutomatically() != img) {
            ws.setLoadsImagesAutomatically(img);
            ws.setBlockNetworkImage(!img);
        }
        boolean dark = s.optBoolean("darkMode", false);
        if (dark != t.siteDark) {
            t.siteDark = dark;
            try {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(ws, dark);
                }
            } catch (Throwable ignored) {}
        }
        String mode = s.optString("ua", "");
        if (!mode.isEmpty()) {
            if (!mode.equals(t.siteUaMode)) {
                t.siteUaMode = mode;
                boolean nextDesktop = "desktop".equalsIgnoreCase(mode) ? true : ("mobile".equalsIgnoreCase(mode) ? false : t.desktop);
                if (nextDesktop != t.desktop) {
                    t.desktop = nextDesktop;
                }
                applyTabEnv(t, t.web, false);
                syncWorkerDesktopViewport(t);
            }
        } else if (t.siteUaMode != null) {
            t.siteUaMode = null;
            boolean defDesktop = profile != null && profile.desktop;
            if (t.desktop != defDesktop) {
                t.desktop = defDesktop;
            }
            applyTabEnv(t, t.web, false);
            syncWorkerDesktopViewport(t);
        }
        if (!beforeNav && s.has("zoom")) {
            WebPinchZoom.applyPercent(t.web, WebPinchZoom.clamp(s.optInt("zoom", 100)));
        }
    }

    private void injectUiCss(WorkerTab t, WebView view) {
        if (view == null || customizer == null) return;
        JSONObject cfg = customizer.config();
        JSONObject th = cfg.optJSONObject("theme");
        String css = th != null ? th.optString("css", "") : "";
        if (t != null && t.site != null) {
            String sc = t.site.optString("css", "");
            if (!sc.isEmpty()) css = css.isEmpty() ? sc : css + "\n" + sc;
        }
        String q = JSONObject.quote(css);
        view.evaluateJavascript("(function(){try{var c=" + q + ";var s=document.getElementById('__pimet_ui_css');"
                + "if(!c){if(s)s.remove();return;}"
                + "if(!s){s=document.createElement('style');s.id='__pimet_ui_css';(document.head||document.documentElement).appendChild(s);}"
                + "if(s.textContent!==c)s.textContent=c;}catch(e){}})()", null);
    }

    private static void addConsole(WorkerTab t, String level, String text, String source, int line) {
        if (t == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("time", System.currentTimeMillis());
            o.put("level", level);
            o.put("text", text == null ? "" : (text.length() > 4000 ? text.substring(0, 4000) + "…" : text));
            if (source != null && !source.isEmpty()) o.put("source", source);
            if (line > 0) o.put("line", line);
            synchronized (t.console) {
                t.console.addLast(o);
                while (t.console.size() > MAX_CONSOLE) t.console.removeFirst();
            }
        } catch (Throwable ignored) {}
    }

    private JSONArray consoleLogs(WorkerTab t, int max) {
        JSONArray a = new JSONArray();
        if (t == null) return a;
        int m = max <= 0 ? 100 : max;
        synchronized (t.console) {
            int skip = Math.max(0, t.console.size() - m);
            int i = 0;
            for (JSONObject o : t.console) {
                if (i++ < skip) continue;
                a.put(o);
            }
        }
        return a;
    }

    private void clearConsoleLogs(WorkerTab t) {
        if (t == null) return;
        synchronized (t.console) {
            t.console.clear();
        }
    }

    private JSONArray menuCommands(WorkerTab t) {
        JSONArray a = new JSONArray();
        if (t == null) return a;
        synchronized (t.menus) {
            for (Map.Entry<String, LinkedHashMap<String, String>> e : t.menus.entrySet()) {
                String name = e.getKey();
                try {
                    UserScriptManager.Script s = scriptMgr.get(e.getKey());
                    if (s != null && s.name != null) name = s.name;
                } catch (Throwable ignored) {}
                for (Map.Entry<String, String> c : e.getValue().entrySet()) {
                    try {
                        a.put(new JSONObject()
                                .put("script", e.getKey())
                                .put("scriptName", name)
                                .put("cmd", c.getKey())
                                .put("caption", c.getValue()));
                    } catch (Throwable ignored) {}
                }
            }
        }
        return a;
    }

    private void runMenuCommand(WorkerTab t, String scriptId, String cmdId) {
        if (t == null || t.closed || t.web == null || scriptId == null || cmdId == null) return;
        try {
            String frameMsg = new JSONObject()
                    .put("type", "gm_menu")
                    .put("scriptId", scriptId)
                    .put("cmdId", cmdId)
                    .toString();
            for (FrameSession fs : t.frames.values()) {
                if (fs.replyProxy != null) {
                    try { fs.replyProxy.postMessage(frameMsg); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    private Set<String> matchingIds(UserScriptManager m, String url) {
        Set<String> ids = new HashSet<>();
        if (m == null || url == null || url.isEmpty()) return ids;
        List<UserScriptManager.Script> l = m.matching(url, null);
        if (l != null) {
            for (UserScriptManager.Script s : l) ids.add(s.id);
        }
        return ids;
    }

    // ------------------------------------------------------------------ 辅助方法与线程调度

    private void putProxyStatus(JSONObject out) {
        try {
            JSONObject st = proxyManager.statusJson(null);
            out.put("proxySupported", st.optBoolean("proxySupported", false));
            String lastProxyError = st.optString("lastProxyError", "");
            if (!lastProxyError.isEmpty()) {
                out.put("lastProxyError", lastProxyError);
            }
            JSONObject ap = st.optJSONObject("activeProxy");
            if (ap != null) {
                out.put("activeProxy", ap);
                out.put("frozenTabs", 0);
            }
        } catch (Throwable ignored) {}
    }

    private WorkerTab findTab(String spec) {
        String s = spec == null ? "" : spec.trim();
        synchronized (tabTableLock) {
            if (s.isEmpty() || "pi".equalsIgnoreCase(s)) {
                WorkerTab lastPi = null;
                for (WorkerTab t : tabs.values()) {
                    if (t.pi && !t.closed) lastPi = t;
                }
                return lastPi != null ? lastPi : firstOpenTabLocked();
            }
            if ("user".equalsIgnoreCase(s)) {
                for (WorkerTab t : tabs.values()) {
                    if (!t.pi && !t.closed) return t;
                }
                return firstOpenTabLocked();
            }
            WorkerTab exact = tabs.get(s);
            return (exact != null && !exact.closed) ? exact : null;
        }
    }

    private WorkerTab findExactTab(String id) {
        if (id == null || id.isEmpty()) return null;
        synchronized (tabTableLock) {
            WorkerTab t = tabs.get(id);
            return (t != null && !t.closed) ? t : null;
        }
    }

    private WorkerTab firstTab() {
        synchronized (tabTableLock) {
            return firstOpenTabLocked();
        }
    }

    private WorkerTab firstOpenTabLocked() {
        for (WorkerTab t : tabs.values()) {
            if (!t.closed) return t;
        }
        return null;
    }

    private List<WorkerTab> snapshotTabs() {
        synchronized (tabTableLock) {
            return new ArrayList<>(tabs.values());
        }
    }

    private void acquire(ReentrantLock lk, WorkerTab t) throws InterruptedException {
        long end = SystemClock.uptimeMillis() + Math.min(hardLeft(), LOCK_WAIT_MAX);
        while (true) {
            checkStop();
            long left = end - SystemClock.uptimeMillis();
            if (lk.tryLock(Math.max(0L, Math.min(200L, left)), TimeUnit.MILLISECONDS)) return;
            if (SystemClock.uptimeMillis() >= end) {
                throw new AbortException("标签 " + t.id + " 正忙（上一个操作还没结束），稍后重试或先 cancel");
            }
        }
    }

    private List<WorkerTab> acquireTabs(List<WorkerTab> candidates) {
        if (candidates == null || candidates.isEmpty()) return Collections.emptyList();
        long end = SystemClock.uptimeMillis() + Math.min(hardLeft(), LOCK_WAIT_MAX);
        while (true) {
            checkStop();
            List<WorkerTab> locked = new ArrayList<>(candidates.size());
            boolean allOk = true;
            for (WorkerTab t : candidates) {
                if (t == null || t.closed) continue;
                if (t.opLock.tryLock()) {
                    locked.add(t);
                } else {
                    allOk = false;
                    break;
                }
            }
            if (allOk) return locked;
            unlockTabs(locked);
            long left = end - SystemClock.uptimeMillis();
            if (left <= 0) {
                throw new AbortException("工作进程中有标签正忙（上一个操作还没结束），稍后重试或先 cancel");
            }
            sleepAbortable(Math.min(40L, left));
        }
    }

    private static void unlockTabs(List<WorkerTab> locked) {
        if (locked == null) return;
        for (int i = locked.size() - 1; i >= 0; i--) {
            WorkerTab t = locked.get(i);
            if (t != null && t.opLock.isHeldByCurrentThread()) {
                t.opLock.unlock();
            }
        }
    }

    interface MainCall<T> {
        T call() throws Exception;
    }

    private <T> T onMain(MainCall<T> c, long timeoutMs) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return c.call();
        final Req req = curReq.get();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        AtomicBoolean claimed = new AtomicBoolean(false);
        AtomicBoolean skipped = new AtomicBoolean(false);
        main.post(() -> {
            if (!claimed.compareAndSet(false, true)) return;
            try {
                if (aborted(req)) skipped.set(true);
                else ref.set(c.call());
            } catch (Throwable t) {
                err.set(t);
            }
            latch.countDown();
        });
        long wait = req == null ? timeoutMs : Math.max(1L, Math.min(timeoutMs, hardLeft()));
        if (!awaitAbortable(latch, wait, claimed)) {
            if (claimed.compareAndSet(false, true)) {
                checkStop();
                throw new IllegalStateException("界面线程超时");
            }
            if (!latch.await(5000L, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("界面线程超时");
            }
        }
        if (skipped.get()) {
            checkStop();
            throw new AbortException("操作已取消");
        }
        if (err.get() != null) {
            Throwable t = err.get();
            if (t instanceof Exception) throw (Exception) t;
            throw new RuntimeException(t);
        }
        return ref.get();
    }

    private boolean awaitAbortable(CountDownLatch latch, long ms, AtomicBoolean claimed) throws InterruptedException {
        final Req req = curReq.get();
        long end = SystemClock.uptimeMillis() + ms;
        while (true) {
            long left = end - SystemClock.uptimeMillis();
            if (left <= 0) return latch.getCount() == 0;
            if (latch.await(Math.min(100L, left), TimeUnit.MILLISECONDS)) return true;
            if (aborted(req)) {
                claimed.compareAndSet(false, true);
                checkStop();
            }
        }
    }

    private static ContainerEnv.Profile parseProfileJson(String profileJson) {
        if (profileJson == null || profileJson.trim().isEmpty()) return ContainerEnv.NATIVE;
        try {
            return ContainerEnv.fromJson(new JSONObject(profileJson));
        } catch (Throwable t) {
            Log.w(TAG, "parseProfileJson failed, falling back to NATIVE", t);
            return ContainerEnv.NATIVE;
        }
    }

    private static Object mergeProfileAndProxy(JSONObject req) throws Exception {
        boolean hasProfile = req.has("profile") && !req.isNull("profile");
        boolean hasProxy = req.has("proxy");
        if (!hasProfile && !hasProxy) return null;
        if (!hasProxy) return req.opt("profile");

        Object proxyVal = req.opt("proxy");
        if (proxyVal == null) proxyVal = JSONObject.NULL;
        if (!hasProfile) return new JSONObject().put("proxy", proxyVal);
        Object profVal = req.opt("profile");
        if (profVal instanceof JSONObject) {
            JSONObject merged = new JSONObject(profVal.toString());
            merged.put("proxy", proxyVal);
            return merged;
        }
        if (profVal instanceof String) {
            String ps = ((String) profVal).trim();
            JSONObject merged = new JSONObject();
            if (!ps.isEmpty()) merged.put("preset", ps);
            merged.put("proxy", proxyVal);
            return merged;
        }
        throw new IllegalArgumentException("profile 必须是预设名字符串或 JSON 对象");
    }

    private String desktopUa() {
        if (sDefaultUa == null) {
            try { sDefaultUa = WebSettings.getDefaultUserAgent(appContext); } catch (Throwable ignored) {}
        }
        String ver = "120.0.0.0";
        if (sDefaultUa != null) {
            Matcher m = Pattern.compile("Chrome/([\\d.]+)").matcher(sDefaultUa);
            if (m.find()) ver = m.group(1);
        }
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + ver + " Safari/537.36";
    }

    private String appsUrlPrefix() {
        return "file://" + appsDir.getAbsolutePath() + "/";
    }

    private static boolean crossFrameSupported() {
        try {
            return WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                    && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER);
        } catch (Throwable e) {
            return false;
        }
    }

    private static boolean isLoopbackUrl(String u) {
        if (u == null || !(u.startsWith("http://") || u.startsWith("https://"))) return false;
        try {
            return ContainerProxyManager.isLoopbackHost(Uri.parse(u).getHost());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String viewportStr(boolean desktop) {
        return ContainerEnv.viewportWidth(desktop) + "x" + ContainerEnv.viewportHeight(desktop);
    }

    private static String normalize(String s) {
        s = s == null ? "" : s.trim();
        if (s.isEmpty()) return "";
        if (s.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) return s;
        if (s.startsWith("localhost") || s.startsWith("127.0.0.1") || s.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+(:\\d+)?(/.*)?$")) {
            return "http://" + s;
        }
        if (s.startsWith("/")) return "file://" + s;
        if (!s.contains(" ") && s.contains(".")) return "https://" + s;
        return "https://www.bing.com/search?q=" + Uri.encode(s);
    }

    private static String targetOf(JSONObject req) {
        String t = req.optString("target", "").trim();
        if (!t.isEmpty()) return t;
        String ref = req.optString("ref", "").trim();
        if (!ref.isEmpty()) return ref;
        String text = req.optString("text", "").trim();
        if (!text.isEmpty()) return text.startsWith("text=") ? text : "text=" + text;
        return "";
    }

    private static String fieldOf(JSONObject req) {
        String t = req.optString("target", "").trim();
        return t.isEmpty() ? req.optString("ref", "").trim() : t;
    }

    private static String scopeOf(JSONObject req) {
        String sc = req.optString("scope", "").trim();
        if (!sc.isEmpty()) return sc;
        String m = req.optString("mode", "full").trim();
        return m.isEmpty() ? "full" : m;
    }

    private static void markChanged(JSONObject out, WorkerTab tab, int seqBefore, String urlBefore) {
        boolean navd = tab.navSeq != seqBefore;
        boolean urlChg = !nz(urlBefore).equals(nz(tab.url));
        try { out.put("changed", navd || urlChg); } catch (Throwable ignored) {}
    }

    private static String agentErr(JSONObject r, String def) {
        String e = r == null ? def : r.optString("error", def);
        if (r != null && "unknown_ref".equals(r.optString("code")) && !e.contains("unknown_ref")) {
            e = "unknown_ref：" + e + "（重新 snapshot）";
        }
        return e;
    }

    private static String stripTrailingSemis(String s) {
        String t = s == null ? "" : s.trim();
        while (t.endsWith(";")) t = t.substring(0, t.length() - 1).trim();
        return t;
    }

    private static int keyCode(String key) {
        switch (key.trim().toLowerCase(Locale.ROOT)) {
            case "enter": case "return": return KeyEvent.KEYCODE_ENTER;
            case "tab": return KeyEvent.KEYCODE_TAB;
            case "escape": case "esc": return KeyEvent.KEYCODE_ESCAPE;
            case "backspace": return KeyEvent.KEYCODE_DEL;
            case "delete": return KeyEvent.KEYCODE_FORWARD_DEL;
            case "space": case " ": return KeyEvent.KEYCODE_SPACE;
            case "arrowup": case "up": return KeyEvent.KEYCODE_DPAD_UP;
            case "arrowdown": case "down": return KeyEvent.KEYCODE_DPAD_DOWN;
            case "arrowleft": case "left": return KeyEvent.KEYCODE_DPAD_LEFT;
            case "arrowright": case "right": return KeyEvent.KEYCODE_DPAD_RIGHT;
            case "pageup": return KeyEvent.KEYCODE_PAGE_UP;
            case "pagedown": return KeyEvent.KEYCODE_PAGE_DOWN;
            case "home": return KeyEvent.KEYCODE_MOVE_HOME;
            case "end": return KeyEvent.KEYCODE_MOVE_END;
            default: return 0;
        }
    }

    private static String friendly(Throwable t) {
        String m = t == null ? "" : t.getMessage();
        if (m == null || m.trim().isEmpty()) {
            return (t instanceof InterruptedException) ? "操作被中断" : "内部错误";
        }
        return m;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static JSONObject ok() throws Exception {
        return new JSONObject().put("success", true);
    }

    private static JSONObject err(String msg) {
        try {
            return new JSONObject().put("success", false).put("error", msg);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    private static String readLineBytes(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int b;
        boolean any = false;
        while ((b = in.read()) != -1) {
            any = true;
            if (b == '\n') break;
            if (b != '\r' && buf.size() < 8192) buf.write(b);
        }
        if (!any) return null;
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void safeWrite(Socket s, int status, JSONObject resp) {
        try {
            byte[] bytes = resp.toString().getBytes(StandardCharsets.UTF_8);
            String reason = status == 200 ? "OK" : (status == 400 ? "Bad Request" : (status == 401 ? "Unauthorized" : (status == 413 ? "Payload Too Large" : "Not Found")));
            OutputStream out = s.getOutputStream();
            out.write(("HTTP/1.1 " + status + " " + reason + "\r\n"
                    + "Content-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + bytes.length + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(bytes);
            out.flush();
        } catch (Throwable ignored) {}
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try { s.close(); } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------ 内联页面探测脚本（与 BrowserAgent 一致）

    private static final String HIT_ARM_JS = "(function(){try{var el=window.__pimetTarget;window.__pimetHit=0;if(window.__pimetHitOff)window.__pimetHitOff();"
            + "if(!el)return 0;var W=(el.ownerDocument&&el.ownerDocument.defaultView)||window;var ev=['pointerdown','touchstart','mousedown','click'];"
            + "function h(e){var p=e.composedPath?e.composedPath():[];var t=e.target;"
            + "if(t===el||p.indexOf(el)>=0||(t&&t.nodeType===1&&el.contains&&el.contains(t)))window.__pimetHit=1;else if(!window.__pimetHit)window.__pimetHit=2;}"
            + "for(var i=0;i<ev.length;i++)W.addEventListener(ev[i],h,true);"
            + "window.__pimetHitOff=function(){for(var i=0;i<ev.length;i++){try{W.removeEventListener(ev[i],h,true)}catch(x){}}window.__pimetHitOff=null};"
            + "setTimeout(function(){if(window.__pimetHitOff)window.__pimetHitOff()},3000);return 1}catch(e){return 0}})()";
    private static final String HIT_OFF_JS = "(function(){if(window.__pimetHitOff)window.__pimetHitOff();return 1})()";
    private static final String HIT_READ_JS = "(function(){var h=window.__pimetHit;return h===undefined?-1:h})()";

    private static final String HOVER_JS = "(function(){try{var el=window.__pimetTarget;if(!el)return{ok:false};"
            + "var W=(el.ownerDocument&&el.ownerDocument.defaultView)||window;var r=el.getBoundingClientRect();"
            + "var x=r.left+r.width/2,y=r.top+r.height/2;['pointerover','mouseover','pointerenter','mouseenter','pointermove','mousemove'].forEach(function(t){"
            + "try{var C=t.indexOf('pointer')===0&&W.PointerEvent?W.PointerEvent:W.MouseEvent;el.dispatchEvent(new C(t,{bubbles:true,cancelable:true,composed:true,clientX:x,clientY:y,view:W}))}catch(e){}});return{ok:true}}catch(e){return{ok:false,error:String(e)}}})()";

    private static final String FIND_JS = "(function(q){q=String(q).toLowerCase();var n=0,S=[];"
            + "function cnt(d){try{var raw=(d.body&&d.body.innerText)||'';var t=raw.toLowerCase();var i=0;"
            + "while(q&&(i=t.indexOf(q,i))>=0){n++;if(S.length<5)S.push(raw.substring(Math.max(0,i-30),Math.min(raw.length,i+q.length+30)).replace(/\\s+/g,' '));i+=q.length;}"
            + "var fs=d.querySelectorAll('iframe');for(var k=0;k<fs.length;k++){try{if(fs[k].contentDocument)cnt(fs[k].contentDocument)}catch(e){}}}catch(e){}}"
            + "cnt(document);return {n:n,samples:S}})";

    private static final String WAIT_SEL_JS = "(function(s){var e;try{e=document.querySelector(s)}catch(x){return -1}if(!e)return 0;"
            + "try{var r=e.getBoundingClientRect(),st=getComputedStyle(e);return (r.width>0||r.height>0)&&st.visibility!=='hidden'&&st.display!=='none'?2:1}catch(x){return 1}})";
}
