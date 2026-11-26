package com.xm486.pimet.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 单活容器代理管理器：
 * <ul>
 *   <li>进程内 WebView ProxyController 为全局生效，本管理器确保任意时刻只有一套代理（或直连）生效；</li>
 *   <li>切换期间冻结所有/冲突标签（tab.web.onPause()，严禁 WebView.pauseTimers()），并配合 shouldInterceptRequest 熔断防串线；</li>
 *   <li>每次切换新代理签名使用全新本地随机回环端口，强制 Chromium 刷新连接池；</li>
 *   <li>全程异步回调，绝不在主线程阻塞等待；任意覆盖切换或 shutdown 均保证排空全部 onReady 回调。</li>
 * </ul>
 */
@SuppressLint("RequiresFeature")
public final class ContainerProxyManager {

    private static final String TAG = "ContainerProxyMgr";

    public static final class TestResult {
        public final boolean ok;
        public final int statusCode;
        public final long latencyMs;
        public final String endpoint;
        public final String error;

        public TestResult(boolean ok, int statusCode, long latencyMs, String endpoint, String error) {
            this.ok = ok;
            this.statusCode = statusCode;
            this.latencyMs = latencyMs;
            this.endpoint = endpoint == null ? "" : endpoint;
            this.error = error == null ? "" : error;
        }
    }

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Executor mainExecutor = r -> {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else main.post(r);
    };
    private final ExecutorService bg = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "pimet-proxy-mgr");
        t.setDaemon(true);
        return t;
    });
    private final Runnable onStateChanged;
    private final boolean proxySupported;

    private static final class PendingCallback {
        final Runnable target;
        final AtomicBoolean ran = new AtomicBoolean(false);

        PendingCallback(Runnable target) {
            this.target = target;
        }

        void runOnce() {
            if (target != null && ran.compareAndSet(false, true)) {
                try {
                    target.run();
                } catch (Throwable ignored) {}
            }
        }
    }

    private final Object pendingLock = new Object();
    private final List<PendingCallback> pendingCallbacks = new ArrayList<>();
    private volatile boolean shutDown = false;
    private volatile String activeContainer = ContainerManager.DEFAULT;
    private volatile String activeLabel = "共享";
    private volatile String activeSignature = "DIRECT";
    private volatile String activeSummary = "直连";
    private volatile int activeLocalPort = 0;
    private volatile boolean switching = false;
    private volatile String lastError = "";
    private volatile ContainerProxyTunnel activeTunnel;
    private long switchGen = 0L;

    public ContainerProxyManager(Context ctx, Runnable onStateChanged) {
        this.appContext = ctx != null ? ctx.getApplicationContext() : null;
        this.onStateChanged = onStateChanged;
        boolean sup = false;
        try {
            sup = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE);
        } catch (Throwable ignored) {}
        this.proxySupported = sup;
    }

    public boolean isProxySupported() {
        return proxySupported;
    }

    public boolean isSwitching() {
        return switching;
    }

    public boolean isDirect() {
        return "DIRECT".equals(activeSignature);
    }

    public int getActiveLocalPort() {
        return activeLocalPort;
    }

    public String getActiveSummary() {
        return activeSummary;
    }

    public String getActiveContainer() {
        return activeContainer;
    }

    public String getActiveLabel() {
        return activeLabel;
    }

    public String getActiveSignature() {
        return activeSignature;
    }

    public String getLastError() {
        return lastError;
    }

    public boolean matchesActive(String tabProxySig) {
        if (switching) return false;
        String sig = (tabProxySig == null || tabProxySig.isEmpty()) ? "DIRECT" : tabProxySig;
        return activeSignature.equals(sig);
    }

    /** 必须在主线程调用：将全局代理切换为指定容器的代理（或直连），异步完成后回调 onReady（保证 100% 最终执行） */
    public void applyForContainer(String containerName, String label, ContainerEnv.Profile profile,
                                  Collection<FloatingApps.Tab> tabs, Runnable onReady) {
        final PendingCallback cb = onReady != null ? new PendingCallback(onReady) : null;
        if (shutDown) {
            lastError = "代理管理器已关闭";
            if (cb != null) mainExecutor.execute(cb::runOnce);
            return;
        }

        final String cName = (containerName == null || containerName.isEmpty()) ? ContainerManager.DEFAULT : containerName;
        final String cLabel = (label == null || label.isEmpty()) ? cName : label;
        final ContainerEnv.ProxySpec spec = profile != null ? profile.proxy : null;
        final boolean wantProxy = spec != null && spec.isActive();
        final String targetSig = wantProxy ? spec.signature() : "DIRECT";
        final String targetSummary = wantProxy ? spec.summary() : "直连";

        ContainerProxyTunnel curTunnel = activeTunnel;
        if (!switching && targetSig.equals(activeSignature)
                && (!wantProxy || (curTunnel != null && curTunnel.isOpen() && activeLocalPort > 0))) {
            activeContainer = cName;
            activeLabel = cLabel;
            activeSummary = targetSummary;
            lastError = "";
            syncTabsFreezeState(tabs);
            notifyChanged();
            if (cb != null) mainExecutor.execute(cb::runOnce);
            return;
        }

        final long gen;
        final List<PendingCallback> superseded;
        synchronized (pendingLock) {
            if (shutDown) {
                lastError = "代理管理器已关闭";
                if (cb != null) mainExecutor.execute(cb::runOnce);
                return;
            }
            superseded = pendingCallbacks.isEmpty() ? null : new ArrayList<>(pendingCallbacks);
            pendingCallbacks.clear();
            gen = ++switchGen;
            switching = true;
            lastError = "";
            if (cb != null) pendingCallbacks.add(cb);
        }
        if (superseded != null) {
            for (PendingCallback oldCb : superseded) {
                main.post(oldCb::runOnce);
            }
        }

        freezeAllTabs(tabs);
        notifyChanged();

        if (wantProxy) {
            if (!proxySupported) {
                if (!shutDown && gen == switchGen) {
                    switching = false;
                    lastError = "当前系统 WebView 不支持 ProxyController (PROXY_OVERRIDE)";
                    syncTabsFreezeState(tabs);
                    notifyChanged();
                }
                finishCallback(cb);
                return;
            }
            try {
                bg.execute(() -> {
                    final ContainerProxyTunnel newTunnel;
                    try {
                        newTunnel = new ContainerProxyTunnel(spec);
                    } catch (Throwable t) {
                        Log.w(TAG, "start local proxy tunnel failed", t);
                        main.post(() -> {
                            if (!shutDown && gen == switchGen) {
                                switching = false;
                                lastError = "启动本地代理隧道失败: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
                                syncTabsFreezeState(tabs);
                                notifyChanged();
                            }
                            finishCallback(cb);
                        });
                        return;
                    }
                    main.post(() -> {
                        if (shutDown || gen != switchGen) {
                            closeTunnelBg(newTunnel);
                            finishCallback(cb);
                            return;
                        }
                        try {
                            ProxyConfig cfg = new ProxyConfig.Builder()
                                    .addProxyRule("http://127.0.0.1:" + newTunnel.getPort())
                                    .addBypassRule("127.0.0.1")
                                    .addBypassRule("localhost")
                                    .addBypassRule("::1")
                                    .addBypassRule("<local>")
                                    .build();
                            ProxyController.getInstance().setProxyOverride(cfg, mainExecutor, () -> {
                                if (shutDown || gen != switchGen) {
                                    closeTunnelBg(newTunnel);
                                    finishCallback(cb);
                                    return;
                                }
                                ContainerProxyTunnel oldTunnel = activeTunnel;
                                activeTunnel = newTunnel;
                                activeContainer = cName;
                                activeLabel = cLabel;
                                activeSignature = targetSig;
                                activeSummary = targetSummary;
                                activeLocalPort = newTunnel.getPort();
                                switching = false;
                                lastError = "";
                                if (oldTunnel != null && oldTunnel != newTunnel) {
                                    closeTunnelBg(oldTunnel);
                                }
                                syncTabsFreezeState(tabs);
                                notifyChanged();
                                finishCallback(cb);
                            });
                        } catch (Throwable t) {
                            Log.w(TAG, "setProxyOverride failed", t);
                            closeTunnelBg(newTunnel);
                            if (!shutDown && gen == switchGen) {
                                switching = false;
                                lastError = "应用 WebView 代理失败: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
                                syncTabsFreezeState(tabs);
                                notifyChanged();
                            }
                            finishCallback(cb);
                        }
                    });
                });
            } catch (RejectedExecutionException rex) {
                if (!shutDown && gen == switchGen) {
                    switching = false;
                    lastError = "代理管理器已关闭";
                    syncTabsFreezeState(tabs);
                    notifyChanged();
                }
                finishCallback(cb);
            }
        } else {
            if (!proxySupported) {
                if (!shutDown && gen == switchGen) {
                    ContainerProxyTunnel oldTunnel = activeTunnel;
                    activeTunnel = null;
                    activeContainer = cName;
                    activeLabel = cLabel;
                    activeSignature = "DIRECT";
                    activeSummary = "直连";
                    activeLocalPort = 0;
                    switching = false;
                    lastError = "";
                    if (oldTunnel != null) closeTunnelBg(oldTunnel);
                    syncTabsFreezeState(tabs);
                    notifyChanged();
                }
                finishCallback(cb);
                return;
            }
            try {
                ProxyController.getInstance().clearProxyOverride(mainExecutor, () -> {
                    if (shutDown || gen != switchGen) {
                        finishCallback(cb);
                        return;
                    }
                    ContainerProxyTunnel oldTunnel = activeTunnel;
                    activeTunnel = null;
                    activeContainer = cName;
                    activeLabel = cLabel;
                    activeSignature = "DIRECT";
                    activeSummary = "直连";
                    activeLocalPort = 0;
                    switching = false;
                    lastError = "";
                    if (oldTunnel != null) closeTunnelBg(oldTunnel);
                    syncTabsFreezeState(tabs);
                    notifyChanged();
                    finishCallback(cb);
                });
            } catch (Throwable t) {
                Log.w(TAG, "clearProxyOverride failed", t);
                if (!shutDown && gen == switchGen) {
                    ContainerProxyTunnel oldTunnel = activeTunnel;
                    activeTunnel = null;
                    activeContainer = cName;
                    activeLabel = cLabel;
                    activeSignature = "DIRECT";
                    activeSummary = "直连";
                    activeLocalPort = 0;
                    switching = false;
                    lastError = "";
                    if (oldTunnel != null) closeTunnelBg(oldTunnel);
                    syncTabsFreezeState(tabs);
                    notifyChanged();
                }
                finishCallback(cb);
            }
        }
    }

    private void finishCallback(PendingCallback cb) {
        if (cb == null) return;
        synchronized (pendingLock) {
            pendingCallbacks.remove(cb);
        }
        mainExecutor.execute(cb::runOnce);
    }

    private void closeTunnelBg(ContainerProxyTunnel t) {
        if (t == null) return;
        try {
            bg.execute(t::close);
        } catch (Throwable ignored) {
            try { t.close(); } catch (Throwable ignored2) {}
        }
    }

    private void freezeAllTabs(Collection<FloatingApps.Tab> tabs) {
        if (tabs == null) return;
        for (FloatingApps.Tab t : new ArrayList<>(tabs)) {
            if (t == null || t.closed || t.web == null) continue;
            if (!t.proxyFrozen) {
                t.proxyFrozen = true;
                try { t.web.onPause(); } catch (Throwable ignored) {}
            }
        }
    }

    private void syncTabsFreezeState(Collection<FloatingApps.Tab> tabs) {
        if (tabs == null) return;
        for (FloatingApps.Tab t : new ArrayList<>(tabs)) {
            if (t == null || t.closed || t.web == null) continue;
            boolean match = matchesActive(t.proxySignature);
            if (match) {
                if (t.proxyFrozen) {
                    t.proxyFrozen = false;
                    try { t.web.onResume(); } catch (Throwable ignored) {}
                }
            } else {
                if (!t.proxyFrozen) {
                    t.proxyFrozen = true;
                    try { t.web.onPause(); } catch (Throwable ignored) {}
                }
            }
        }
    }

    private void notifyChanged() {
        if (onStateChanged != null) {
            try { onStateChanged.run(); } catch (Throwable ignored) {}
        }
    }

    public static boolean isLoopbackHost(String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]") && h.length() > 2) {
            h = h.substring(1, h.length() - 1);
        }
        return "127.0.0.1".equals(h) || "localhost".equals(h) || "::1".equals(h) || "0:0:0:0:0:0:0:1".equals(h);
    }

    /** 供 doGmXhr / httpGetText 使用：本机直连；外网校验冻结与单活代理匹配后走当前隧道或直连 */
    public HttpURLConnection openConnection(FloatingApps.Tab tab, URL url) throws IOException {
        if (url == null) throw new IOException("URL 不能为空");
        if (isLoopbackHost(url.getHost())) {
            return (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
        }
        String tabSig = (tab != null && tab.proxySignature != null && !tab.proxySignature.isEmpty())
                ? tab.proxySignature
                : "DIRECT";
        if (switching || !matchesActive(tabSig)) {
            throw new IOException("标签当前处于代理冻结状态（全局：" + activeSummary + "），已拦截直连以防串线");
        }
        if (isDirect() || activeLocalPort <= 0) {
            return (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
        }
        Proxy p = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", activeLocalPort));
        return (HttpURLConnection) url.openConnection(p);
    }

    /** 独立测试代理连通性：起临时隧道测试 204，绝不影响全局 WebView 代理 */
    public void testProxyAsync(ContainerEnv.ProxySpec spec, Consumer<TestResult> cb) {
        if (spec == null || !spec.isActive()) {
            main.post(() -> cb.accept(new TestResult(false, 0, 0L, "", "代理未启用或主机/端口无效")));
            return;
        }
        try {
            bg.execute(() -> {
                long t0 = System.currentTimeMillis();
                ContainerProxyTunnel tmp = null;
                try {
                    tmp = new ContainerProxyTunnel(spec);
                    Proxy p = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", tmp.getPort()));
                    String[] urls = {
                            "https://cp.cloudflare.com/generate_204",
                            "https://www.gstatic.com/generate_204"
                    };
                    int status = 0;
                    String usedUrl = urls[0];
                    Throwable lastErr = null;
                    for (String u : urls) {
                        usedUrl = u;
                        HttpURLConnection c = null;
                        try {
                            c = (HttpURLConnection) new URL(u).openConnection(p);
                            c.setConnectTimeout(10_000);
                            c.setReadTimeout(10_000);
                            c.setInstanceFollowRedirects(false);
                            c.setRequestMethod("GET");
                            status = c.getResponseCode();
                            try (InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream()) {
                                if (in != null) {
                                    byte[] buf = new byte[512];
                                    while (in.read(buf) != -1) {}
                                }
                            } catch (Throwable ignored) {}
                            if (status >= 200 && status < 400) {
                                lastErr = null;
                                break;
                            }
                            lastErr = new IOException("HTTP " + status);
                        } catch (Throwable t) {
                            lastErr = t;
                        } finally {
                            if (c != null) try { c.disconnect(); } catch (Throwable ignored) {}
                        }
                    }
                    long ms = System.currentTimeMillis() - t0;
                    if (lastErr == null) {
                        final int st = status;
                        final String uu = usedUrl;
                        main.post(() -> cb.accept(new TestResult(true, st, ms, uu, "")));
                    } else {
                        final String errMsg = lastErr.getMessage() == null ? lastErr.getClass().getSimpleName() : lastErr.getMessage();
                        final int st = status;
                        final String uu = usedUrl;
                        main.post(() -> cb.accept(new TestResult(false, st, ms, uu, errMsg)));
                    }
                } catch (Throwable t) {
                    long ms = System.currentTimeMillis() - t0;
                    final String errMsg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                    main.post(() -> cb.accept(new TestResult(false, 0, ms, "", errMsg)));
                } finally {
                    if (tmp != null) tmp.close();
                }
            });
        } catch (RejectedExecutionException rex) {
            main.post(() -> cb.accept(new TestResult(false, 0, 0L, "", "代理管理器已关闭")));
        }
    }

    public JSONObject statusJson(FloatingApps win) {
        JSONObject out = new JSONObject();
        try {
            out.put("proxySupported", proxySupported);
            JSONObject ap = new JSONObject();
            ap.put("container", activeContainer);
            ap.put("label", activeLabel);
            ap.put("signature", activeSignature);
            ap.put("summary", activeSummary);
            ap.put("direct", isDirect());
            ap.put("localPort", activeLocalPort);
            ap.put("switching", switching);
            int frozen = 0;
            if (win != null) {
                for (FloatingApps.Tab t : new ArrayList<>(win.tabs())) {
                    if (t != null && !t.closed && (t.proxyFrozen || !matchesActive(t.proxySignature))) frozen++;
                }
            }
            ap.put("frozenTabs", frozen);
            if (!lastError.isEmpty()) ap.put("lastError", lastError);
            ContainerProxyTunnel tun = activeTunnel;
            String tunnelErr = tun != null ? tun.getLastHandshakeError() : "";
            String proxyErr = !lastError.isEmpty() ? lastError : tunnelErr;
            if (!proxyErr.isEmpty()) {
                ap.put("lastProxyError", proxyErr);
                out.put("lastProxyError", proxyErr);
            }
            out.put("activeProxy", ap);
        } catch (Exception ignored) {}
        return out;
    }

    public void shutdown() {
        final List<PendingCallback> toDrain;
        synchronized (pendingLock) {
            shutDown = true;
            switchGen++;
            switching = false;
            toDrain = pendingCallbacks.isEmpty() ? null : new ArrayList<>(pendingCallbacks);
            pendingCallbacks.clear();
        }
        final ContainerProxyTunnel t = activeTunnel;
        activeTunnel = null;
        activeContainer = ContainerManager.DEFAULT;
        activeLabel = "共享";
        activeLocalPort = 0;
        activeSignature = "DIRECT";
        activeSummary = "直连";
        lastError = "";

        if (toDrain != null) {
            for (PendingCallback cb : toDrain) {
                main.post(cb::runOnce);
            }
        }

        final AtomicBoolean finalized = new AtomicBoolean(false);
        final Runnable[] timeoutRef = new Runnable[1];
        final Runnable finalizeResources = () -> {
            if (!finalized.compareAndSet(false, true)) return;
            if (timeoutRef[0] != null) {
                try { main.removeCallbacks(timeoutRef[0]); } catch (Throwable ignored) {}
            }
            if (t != null) {
                closeTunnelBg(t);
            }
            try {
                bg.shutdown();
            } catch (Throwable ignored) {}
        };
        timeoutRef[0] = finalizeResources;

        if (!proxySupported) {
            finalizeResources.run();
            return;
        }

        // 先清除 ProxyController 恢复直连，再关闭本地隧道与线程池；1500ms 短超时兜底防止回调丢失
        try {
            main.postDelayed(finalizeResources, 1500L);
            mainExecutor.execute(() -> {
                try {
                    ProxyController.getInstance().clearProxyOverride(mainExecutor, finalizeResources);
                } catch (Throwable ignored) {
                    finalizeResources.run();
                }
            });
        } catch (Throwable ignored) {
            finalizeResources.run();
        }
    }
}
