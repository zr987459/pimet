package com.xm486.pimet.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.util.Log;
import android.view.SurfaceControlViewHost;

import androidx.annotation.RequiresApi;

import com.xm486.pimet.bridge.AppBridgeManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 6 槽位独立代理子进程池（{@code :proxy_worker_1..6}）：
 * <ul>
 *   <li>每个槽位绑定一个容器及其独立 {@code WebView.setDataDirectorySuffix("pw_" + containerId)} 与 {@code ProxyController}；</li>
 *   <li>6 槽位状态机：{@code DEAD → INITIALIZING → READY_IDLE ⇄ READY_BUSY → RECYCLING → DEAD}；</li>
 *   <li>绝不跨容器混用运行中槽位；6 槽全被活跃标签占用时明确拒绝并报错；</li>
 *   <li>所有回环 HTTP RPC（{@code 127.0.0.1:port}）强制使用 {@link Proxy#NO_PROXY}，并支持 &gt;1MB 大报文与 {@code /cancel} 毫秒级打断。</li>
 * </ul>
 */
public final class ProxyWorkerPool {

    private static final String TAG = "ProxyWorkerPool";
    private static final SecureRandom RNG = new SecureRandom();
    @SuppressWarnings("unchecked")
    static final Class<? extends ProxyWorkerService>[] SERVICE_CLASSES = new Class[]{
            ProxyWorkerService1.class,
            ProxyWorkerService2.class,
            ProxyWorkerService3.class,
            ProxyWorkerService4.class,
            ProxyWorkerService5.class,
            ProxyWorkerService6.class
    };

    public enum SlotState {
        DEAD,
        INITIALIZING,
        READY_IDLE,
        READY_BUSY,
        RECYCLING
    }

    public static final class WorkerSlot {
        public final int slot;
        final Class<? extends ProxyWorkerService> serviceClass;
        public volatile SlotState state = SlotState.DEAD;
        public volatile int pid;
        public volatile int workerPort;
        public volatile String workerToken = "";
        public volatile String boundContainerId = "";
        public volatile String boundLabel = "";
        public volatile ContainerEnv.Profile boundProfile = ContainerEnv.NATIVE;
        public volatile String proxySummary = "直连";
        public volatile int localProxyPort;
        public volatile long lastUsedAt;
        ServiceConnection serviceConnection;
        Messenger messenger;
        IBinder.DeathRecipient deathRecipient;
        IBinder rawBinder;
        final Set<String> tabs = Collections.synchronizedSet(new LinkedHashSet<>());
        volatile boolean boundKeep = false;
        CountDownLatch deathLatch;
        int pendingAcquires = 0;
        long tabsGen = 0L;

        WorkerSlot(int slot, Class<? extends ProxyWorkerService> serviceClass) {
            this.slot = slot;
            this.serviceClass = serviceClass;
        }

        public boolean isReady() {
            SlotState s = state;
            return s == SlotState.READY_IDLE || s == SlotState.READY_BUSY;
        }
    }

    private static final class InflightCall {
        final String reqId;
        final String tabId;
        final WorkerSlot slot;
        volatile HttpURLConnection conn;
        volatile boolean cancelled;

        InflightCall(String reqId, String tabId, WorkerSlot slot) {
            this.reqId = reqId;
            this.tabId = tabId;
            this.slot = slot;
        }
    }

    public interface AttachSurfaceCallback {
        void onResult(boolean ok, Object surfacePackage, Bundle stateBundle, String error);
    }

    private final Context appContext;
    private final File appsDir;
    private final ContainerManager containerManager;
    private volatile String customUa;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ConcurrentHashMap<Long, AttachSurfaceCallback> pendingAttachCallbacks = new ConcurrentHashMap<>();
    private final Messenger surfaceReplyMessenger = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            handleSurfaceReplyMessage(msg);
        }
    });
    private final Object poolLock = new Object();
    private final WorkerSlot[] slots;
    private final ConcurrentHashMap<String, WorkerSlot> tabIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, JSONObject> cachedTabMeta = new ConcurrentHashMap<>();
    private final List<String> tabOrder = Collections.synchronizedList(new ArrayList<>());
    private final ConcurrentHashMap<String, InflightCall> inflightCalls = new ConcurrentHashMap<>();
    private final ExecutorService asyncPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "pimet-pw-pool");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    public ProxyWorkerPool(Context ctx, File appsDir, ContainerManager containerManager) {
        if (ctx == null) throw new IllegalArgumentException("ctx == null");
        Context app = ctx.getApplicationContext();
        this.appContext = app != null ? app : ctx;
        this.appsDir = appsDir != null ? appsDir : new File(this.appContext.getFilesDir(), "rootfs/root/pimet-browser");
        this.containerManager = containerManager;
        this.slots = new WorkerSlot[SERVICE_CLASSES.length];
        for (int i = 0; i < SERVICE_CLASSES.length; i++) {
            this.slots[i] = new WorkerSlot(i + 1, SERVICE_CLASSES[i]);
        }
    }

    public void setCustomUa(String customUa) {
        this.customUa = (customUa != null && !customUa.trim().isEmpty()) ? customUa.trim() : null;
    }

    // ------------------------------------------------------------------ 槽位获取与回收

    /**
     * 为指定容器获取独立代理子进程槽位。<b>严禁在主线程阻塞调用！</b>
     */
    public WorkerSlot acquireSlotForContainer(
            String containerId,
            String label,
            ContainerEnv.Profile profile,
            long timeoutMs
    ) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("acquireSlotForContainer 严禁在主线程阻塞调用");
        }
        if (shuttingDown.get()) {
            throw new IllegalStateException("代理进程池已关闭");
        }
        final String cid = (containerId == null || containerId.trim().isEmpty())
                ? ContainerManager.DEFAULT : containerId.trim();
        final String lbl = (label == null || label.trim().isEmpty()) ? cid : label.trim();
        final ContainerEnv.Profile prof = profile != null ? profile : ContainerEnv.NATIVE;
        final long budget = Math.max(3000L, timeoutMs);
        final long deadline = System.currentTimeMillis() + budget;

        while (true) {
            if (shuttingDown.get()) throw new IllegalStateException("代理进程池已关闭");

            WorkerSlot reuseSlot = null;
            WorkerSlot deadSlot = null;
            WorkerSlot lruIdleSlot = null;
            boolean waitTransient = false;
            final boolean kept = isContainerKept(cid);

            synchronized (poolLock) {
                // 1) 优先复用同容器运行中槽位
                for (WorkerSlot s : slots) {
                    if (cid.equals(s.boundContainerId) && s.isReady()) {
                        reuseSlot = s;
                        break;
                    }
                    if (cid.equals(s.boundContainerId)
                            && (s.state == SlotState.INITIALIZING || s.state == SlotState.RECYCLING)) {
                        waitTransient = true;
                    }
                }
                if (reuseSlot != null) {
                    reuseSlot.lastUsedAt = System.currentTimeMillis();
                    reuseSlot.boundLabel = lbl;
                    reuseSlot.boundKeep = kept;
                    reuseSlot.pendingAcquires++;
                } else if (!waitTransient) {
                    // 2) 优先占用 DEAD 槽位
                    for (WorkerSlot s : slots) {
                        if (s.state == SlotState.DEAD) {
                            deadSlot = s;
                            s.state = SlotState.INITIALIZING;
                            s.boundContainerId = cid;
                            s.boundLabel = lbl;
                            s.boundProfile = prof;
                            s.boundKeep = kept;
                            s.lastUsedAt = System.currentTimeMillis();
                            s.pendingAcquires++;
                            break;
                        }
                    }
                    // 3) 无 DEAD 槽位时，回收最久未用的 READY_IDLE（tabs.isEmpty() && pendingAcquires == 0）槽位
                    if (deadSlot == null) {
                        long oldest = Long.MAX_VALUE;
                        for (WorkerSlot s : slots) {
                            if (s.state == SlotState.READY_IDLE
                                    && s.tabs.isEmpty()
                                    && s.pendingAcquires == 0
                                    && s.lastUsedAt <= oldest) {
                                oldest = s.lastUsedAt;
                                lruIdleSlot = s;
                            }
                        }
                        if (lruIdleSlot != null) {
                            lruIdleSlot.state = SlotState.RECYCLING;
                        } else {
                            // 4) 若有其他槽正在 INITIALIZING / RECYCLING，可短暂等待；否则全槽已满（READY_BUSY）
                            boolean anyBusyTransient = false;
                            for (WorkerSlot s : slots) {
                                if (s.state == SlotState.INITIALIZING || s.state == SlotState.RECYCLING) {
                                    anyBusyTransient = true;
                                    break;
                                }
                            }
                            if (!anyBusyTransient) {
                                throw new IllegalStateException(buildFullOccupiedError());
                            }
                            waitTransient = true;
                        }
                    }
                }
            }

            if (reuseSlot != null) {
                try {
                    maybeHotUpdateProfile(reuseSlot, lbl, prof);
                    return reuseSlot;
                } catch (Throwable t) {
                    releaseAcquire(reuseSlot);
                    if (!isSlotAlive(reuseSlot)) {
                        forceResetToDead(reuseSlot, isContainerKept(cid));
                        continue;
                    }
                    if (t instanceof Exception) throw (Exception) t;
                    throw new RuntimeException(t);
                }
            }
            if (deadSlot != null) {
                long left = Math.max(2000L, deadline - System.currentTimeMillis());
                try {
                    initDeadSlot(deadSlot, cid, lbl, prof, left);
                    return deadSlot;
                } catch (Throwable t) {
                    releaseAcquire(deadSlot);
                    forceResetToDead(deadSlot, false);
                    if (t instanceof Exception) throw (Exception) t;
                    throw new RuntimeException(t);
                }
            }
            if (lruIdleSlot != null) {
                recycleSlot(lruIdleSlot);
                continue;
            }
            if (waitTransient) {
                if (System.currentTimeMillis() >= deadline) {
                    throw new IllegalStateException("等待代理进程槽位就绪超时（容器 " + cid + "）");
                }
                try {
                    Thread.sleep(80L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("获取代理进程槽位被中断");
                }
            }
        }
    }

    public void releaseAcquire(WorkerSlot s) {
        if (s == null) return;
        synchronized (poolLock) {
            if (s.pendingAcquires > 0) {
                s.pendingAcquires--;
            }
            s.lastUsedAt = System.currentTimeMillis();
        }
    }

    private void maybeHotUpdateProfile(WorkerSlot slot, String label, ContainerEnv.Profile nextProf) throws Exception {
        ContainerEnv.Profile cur = slot.boundProfile;
        boolean same = cur != null
                && cur.proxySignature().equals(nextProf.proxySignature())
                && ContainerEnv.toJson(cur).toString().equals(ContainerEnv.toJson(nextProf).toString());
        if (same) return;
        JSONObject req = new JSONObject();
        req.put("action", "update_container_profile");
        req.put("label", label);
        req.put("profileJson", ContainerEnv.toJson(nextProf).toString());
        JSONObject resp = callRpc(slot, req, "hotprof-" + System.nanoTime(), 12_000L);
        if (!resp.optBoolean("success", false)) {
            throw new IllegalStateException(resp.optString("error", "热更新子进程容器环境失败"));
        }
        slot.boundProfile = nextProf;
        slot.boundLabel = label;
        slot.proxySummary = nextProf.hasActiveProxy() ? nextProf.proxy.summary() : "直连";
        JSONObject ap = resp.optJSONObject("activeProxy");
        if (ap != null) {
            slot.localProxyPort = ap.optInt("localPort", slot.localProxyPort);
        }
    }

    private void initDeadSlot(
            WorkerSlot s,
            String containerId,
            String label,
            ContainerEnv.Profile profile,
            long timeoutMs
    ) throws Exception {
        final String token = generateWorkerToken();
        final CountDownLatch connLatch = new CountDownLatch(1);
        final CountDownLatch ackLatch = new CountDownLatch(1);
        final AtomicBoolean ackOk = new AtomicBoolean(false);
        final AtomicReference<String> ackErr = new AtomicReference<>("");
        final AtomicReference<IBinder> binderRef = new AtomicReference<>();
        final AtomicBoolean aborted = new AtomicBoolean(false);

        final Handler replyHandler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                if (msg == null || msg.what != ProxyWorkerBridge.MSG_INIT_ACK) return;
                if (aborted.get()) return;
                Bundle b = msg.getData();
                boolean ok = b != null && b.getBoolean(ProxyWorkerBridge.KEY_OK, false);
                s.workerPort = b != null ? b.getInt(ProxyWorkerBridge.KEY_PORT, 0) : 0;
                s.pid = b != null ? b.getInt(ProxyWorkerBridge.KEY_PID, 0) : 0;
                s.proxySummary = b != null ? b.getString(ProxyWorkerBridge.KEY_PROXY_SUMMARY, "直连") : "直连";
                s.localProxyPort = b != null ? b.getInt(ProxyWorkerBridge.KEY_LOCAL_PROXY_PORT, 0) : 0;
                ackOk.set(ok);
                ackErr.set(b != null ? b.getString(ProxyWorkerBridge.KEY_ERROR, "") : "MSG_INIT_ACK 缺少 Bundle");
                ackLatch.countDown();
            }
        };
        final Messenger replyTo = new Messenger(replyHandler);
        final CountDownLatch deathLatch = new CountDownLatch(1);
        final ServiceConnection conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                if (aborted.get()) {
                    try { appContext.unbindService(this); } catch (Throwable ignored) {}
                    return;
                }
                synchronized (poolLock) {
                    if (aborted.get()) {
                        try { appContext.unbindService(this); } catch (Throwable ignored) {}
                        return;
                    }
                    binderRef.set(service);
                    s.rawBinder = service;
                    s.messenger = new Messenger(service);
                    s.deathLatch = deathLatch;
                    IBinder.DeathRecipient dr = () -> {
                        deathLatch.countDown();
                        onSlotBinderDied(s);
                    };
                    s.deathRecipient = dr;
                    try {
                        service.linkToDeath(dr, 0);
                    } catch (Throwable ignored) {}
                }
                connLatch.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                deathLatch.countDown();
                if (!aborted.get()) {
                    onSlotBinderDied(s);
                }
            }
        };

        synchronized (poolLock) {
            s.workerToken = token;
            s.serviceConnection = conn;
            s.deathLatch = deathLatch;
            s.pid = 0;
            s.workerPort = 0;
            s.localProxyPort = 0;
            s.proxySummary = profile.hasActiveProxy() ? profile.proxy.summary() : "直连";
        }

        boolean completed = false;
        try {
            Intent intent = new Intent(appContext, s.serviceClass);
            boolean bound = appContext.bindService(intent, conn, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
            if (!bound) {
                throw new IllegalStateException("bindService(" + s.serviceClass.getSimpleName() + ") 返回 false");
            }

            long connWait = Math.min(timeoutMs, 8000L);
            if (!connLatch.await(connWait, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("连接代理子进程 #" + s.slot + " 超时");
            }
            if (shuttingDown.get() || s.state != SlotState.INITIALIZING) {
                throw new IllegalStateException("代理槽位 #" + s.slot + " 初始化被取消");
            }

            Message initMsg = Message.obtain(null, ProxyWorkerBridge.MSG_INIT);
            initMsg.replyTo = replyTo;
            Bundle bundle = new Bundle();
            bundle.putInt(ProxyWorkerBridge.KEY_SLOT, s.slot);
            bundle.putString(ProxyWorkerBridge.KEY_CONTAINER_ID, containerId);
            bundle.putString(ProxyWorkerBridge.KEY_LABEL, label);
            bundle.putString(ProxyWorkerBridge.KEY_PROFILE_JSON, ContainerEnv.toJson(profile).toString());
            bundle.putString(ProxyWorkerBridge.KEY_WORKER_TOKEN, token);
            bundle.putString(ProxyWorkerBridge.KEY_APPS_DIR, appsDir.getAbsolutePath());
            AppBridgeManager abm = AppBridgeManager.getInstance(appContext);
            bundle.putInt(ProxyWorkerBridge.KEY_MAIN_CALLBACK_PORT, AppBridgeManager.BRIDGE_PORT);
            bundle.putString(ProxyWorkerBridge.KEY_MAIN_TOKEN, abm.getWorkerMainToken());
            if (customUa != null && !customUa.isEmpty()) {
                bundle.putString(ProxyWorkerBridge.KEY_CUSTOM_UA, customUa);
            }
            initMsg.setData(bundle);

            Messenger m = s.messenger;
            if (m == null) throw new IllegalStateException("子进程 #" + s.slot + " Messenger 不可用");
            m.send(initMsg);

            long ackWait = Math.max(2000L, Math.min(timeoutMs, 15_000L));
            if (!ackLatch.await(ackWait, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("等待代理子进程 #" + s.slot + " 初始化回执超时");
            }
            if (!ackOk.get() || s.workerPort <= 0) {
                String err = ackErr.get();
                throw new IllegalStateException(err != null && !err.isEmpty() ? err : "代理子进程 #" + s.slot + " 初始化失败");
            }

            synchronized (poolLock) {
                if (shuttingDown.get()) throw new IllegalStateException("代理进程池已关闭");
                s.state = s.tabs.isEmpty() ? SlotState.READY_IDLE : SlotState.READY_BUSY;
                s.lastUsedAt = System.currentTimeMillis();
            }
            completed = true;
        } finally {
            if (!completed) {
                aborted.set(true);
            }
        }
    }

    private void recycleSlot(WorkerSlot s) {
        final String oldContainerId;
        final boolean slotBoundKeep;
        final int oldPid;
        final ServiceConnection conn;
        final IBinder binder;
        final IBinder.DeathRecipient dr;
        final CountDownLatch dl;
        final Messenger msgr;

        synchronized (poolLock) {
            oldContainerId = s.boundContainerId;
            slotBoundKeep = s.boundKeep;
            oldPid = s.pid;
            conn = s.serviceConnection;
            binder = s.rawBinder;
            dr = s.deathRecipient;
            dl = s.deathLatch;
            msgr = s.messenger;
            s.state = SlotState.RECYCLING;
        }
        final boolean oldKeep = slotBoundKeep || isContainerKept(oldContainerId);

        clearTabIndexForSlot(s);

        // 1) 优雅通知 shutdown (1.5s 超时)
        if (s.workerPort > 0 && !s.workerToken.isEmpty()) {
            try {
                JSONObject req = new JSONObject().put("action", "shutdown");
                callRpc(s, req, "shutdown-" + s.slot + "-" + System.nanoTime(), 1500L);
            } catch (Throwable ignored) {}
        }
        if (msgr != null) {
            try {
                msgr.send(Message.obtain(null, ProxyWorkerBridge.MSG_SHUTDOWN));
            } catch (Throwable ignored) {}
        }

        // 2) unbindService
        if (conn != null) {
            try {
                appContext.unbindService(conn);
            } catch (Throwable ignored) {}
        }

        // 3) 等待 Binder linkToDeath 回调，最多 2000ms；超时强杀 pid
        boolean died = false;
        if (dl != null) {
            try {
                died = dl.await(2000L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        if (!died && oldPid > 0 && oldPid != Process.myPid()) {
            try {
                Process.killProcess(oldPid);
            } catch (Throwable ignored) {}
            if (dl != null) {
                try { dl.await(300L, TimeUnit.MILLISECONDS); } catch (Throwable ignored) {}
            }
        }
        if (binder != null && dr != null) {
            try { binder.unlinkToDeath(dr, 0); } catch (Throwable ignored) {}
        }

        // 4) 若原容器 keep == false，同步清理该容器隔离目录，彻底删净后再将槽位置为 DEAD
        if (!oldKeep && oldContainerId != null && !oldContainerId.isEmpty()
                && !ContainerManager.DEFAULT.equalsIgnoreCase(oldContainerId)) {
            ContainerManager.deleteWorkerDataDirSync(appContext, oldContainerId);
        }

        // 5) 归位 DEAD
        resetSlotFieldsToDead(s);
    }

    private boolean isSlotAlive(WorkerSlot s) {
        if (s == null || !s.isReady()) return false;
        IBinder b = s.rawBinder;
        if (b != null) {
            try {
                if (!b.isBinderAlive()) return false;
            } catch (Throwable ignored) {}
        }
        return s.workerPort > 0;
    }

    private void forceResetToDead(WorkerSlot s, boolean keepData) {
        final String oldContainerId;
        final boolean slotBoundKeep;
        final int oldPid;
        final ServiceConnection conn;
        final IBinder binder;
        final IBinder.DeathRecipient dr;
        final Messenger msgr;
        synchronized (poolLock) {
            oldContainerId = s.boundContainerId;
            slotBoundKeep = s.boundKeep;
            oldPid = s.pid;
            conn = s.serviceConnection;
            binder = s.rawBinder;
            dr = s.deathRecipient;
            msgr = s.messenger;
            s.state = SlotState.RECYCLING;
        }
        final boolean oldKeep = keepData || slotBoundKeep || isContainerKept(oldContainerId);
        clearTabIndexForSlot(s);
        if (msgr != null) {
            try {
                msgr.send(Message.obtain(null, ProxyWorkerBridge.MSG_SHUTDOWN));
            } catch (Throwable ignored) {}
        }
        if (conn != null) {
            try { appContext.unbindService(conn); } catch (Throwable ignored) {}
        }
        if (oldPid > 0 && oldPid != Process.myPid()) {
            try { Process.killProcess(oldPid); } catch (Throwable ignored) {}
        }
        if (binder != null && dr != null) {
            try { binder.unlinkToDeath(dr, 0); } catch (Throwable ignored) {}
        }
        if (!oldKeep && oldContainerId != null && !oldContainerId.isEmpty()
                && !ContainerManager.DEFAULT.equalsIgnoreCase(oldContainerId)) {
            ContainerManager.deleteWorkerDataDir(appContext, oldContainerId);
        }
        resetSlotFieldsToDead(s);
    }

    private void resetSlotFieldsToDead(WorkerSlot s) {
        synchronized (poolLock) {
            s.state = SlotState.DEAD;
            s.pid = 0;
            s.workerPort = 0;
            s.workerToken = "";
            s.boundContainerId = "";
            s.boundLabel = "";
            s.boundProfile = ContainerEnv.NATIVE;
            s.proxySummary = "直连";
            s.localProxyPort = 0;
            s.lastUsedAt = 0L;
            s.serviceConnection = null;
            s.messenger = null;
            s.deathRecipient = null;
            s.rawBinder = null;
            s.deathLatch = null;
            s.boundKeep = false;
            s.pendingAcquires = 0;
            s.tabsGen++;
            s.tabs.clear();
        }
    }

    private void onSlotBinderDied(WorkerSlot s) {
        boolean shouldReset = false;
        synchronized (poolLock) {
            if (s.state == SlotState.READY_IDLE || s.state == SlotState.READY_BUSY) {
                shouldReset = true;
            }
        }
        if (shouldReset) {
            Log.w(TAG, "proxy worker slot #" + s.slot + " binder died unexpectedly (container=" + s.boundContainerId + ")");
            clearTabIndexForSlot(s);
            resetSlotFieldsToDead(s);
        }
    }

    private void clearTabIndexForSlot(WorkerSlot s) {
        for (Map.Entry<String, WorkerSlot> e : tabIndex.entrySet()) {
            if (e.getValue() == s) {
                String tid = e.getKey();
                if (tabIndex.remove(tid, s)) {
                    cachedTabMeta.remove(tid);
                    tabOrder.remove(tid);
                }
            }
        }
    }

    private String buildFullOccupiedError() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < slots.length; i++) {
            WorkerSlot s = slots[i];
            if (i > 0) sb.append("、");
            sb.append("#").append(s.slot).append(":")
                    .append(s.boundContainerId.isEmpty() ? "-" : s.boundContainerId)
                    .append("(").append(s.tabs.size()).append("个标签)");
        }
        return SERVICE_CLASSES.length + " 个独立代理进程槽位已全被活跃容器占用（" + sb + "），请先用 tab_close 关闭闲置代理标签，绝不混用容器数据";
    }

    private boolean isContainerKept(String containerId) {
        if (containerId == null || containerId.isEmpty()) return false;
        if (ContainerManager.DEFAULT.equalsIgnoreCase(containerId)) return true;
        if (containerManager == null) return false;
        try {
            JSONObject m = containerManager.meta(containerId);
            return m != null && m.optBoolean("keep", false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 数据面 HTTP RPC (强制 Proxy.NO_PROXY)

    public JSONObject callRpc(WorkerSlot slot, JSONObject req, String reqId, long timeoutMs) throws Exception {
        if (slot == null) throw new IllegalArgumentException("slot == null");
        int port = slot.workerPort;
        String token = slot.workerToken;
        if (port <= 0 || token == null || token.isEmpty()) {
            throw new IllegalStateException("代理工作进程 #" + slot.slot + " 尚未就绪");
        }
        String rid = (reqId != null && !reqId.trim().isEmpty())
                ? reqId.trim()
                : req.optString("req_id", req.optString("reqId", "")).trim();
        if (rid.isEmpty()) rid = "r" + System.nanoTime();
        String tabId = req.optString("tab", req.optString("tabId", "")).trim();

        JSONObject payload = new JSONObject(req.toString());
        if (!payload.has("req_id")) payload.put("req_id", rid);
        byte[] reqBytes = payload.toString().getBytes(StandardCharsets.UTF_8);

        int tmo = (int) Math.max(1000L, Math.min(timeoutMs > 0 ? timeoutMs : 25_000L, 180_000L));
        URL url = new URL("http://127.0.0.1:" + port + ProxyWorkerBridge.PATH_RPC);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
        InflightCall call = new InflightCall(rid, tabId, slot);
        call.conn = conn;
        inflightCalls.put(rid, call);
        try {
            conn.setConnectTimeout(Math.min(3000, tmo));
            conn.setReadTimeout(tmo);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty(ProxyWorkerBridge.HEADER_WORKER_TOKEN, token);
            conn.setRequestProperty("Connection", "close");
            conn.setFixedLengthStreamingMode(reqBytes.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(reqBytes);
                os.flush();
            }

            if (call.cancelled) {
                throw new IOException("操作已取消");
            }

            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            byte[] respBytes = readAllBytes(in);
            String respStr = new String(respBytes, StandardCharsets.UTF_8).trim();
            if (respStr.isEmpty()) {
                throw new IOException("代理工作进程 #" + slot.slot + " 返回空响应 (HTTP " + code + ")");
            }
            JSONObject out = new JSONObject(respStr);
            slot.lastUsedAt = System.currentTimeMillis();
            updateCachedTabFromRpc(slot, tabId, out);
            return out;
        } catch (Throwable t) {
            if (call.cancelled) {
                throw new IOException("操作已取消", t);
            }
            if (!isSlotAlive(slot) || t instanceof ConnectException) {
                if (t instanceof ConnectException) {
                    Log.w(TAG, "slot #" + slot.slot + " connection failed, resetting dead slot", t);
                    forceResetToDead(slot, isContainerKept(slot.boundContainerId));
                }
            }
            if (t instanceof Exception) throw (Exception) t;
            throw new IOException(t);
        } finally {
            inflightCalls.remove(rid, call);
            try { conn.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private void updateCachedTabFromRpc(WorkerSlot slot, String reqTabId, JSONObject resp) {
        if (slot == null || resp == null || !resp.optBoolean("success", false)) return;
        String tid = resp.optString("tab", reqTabId == null ? "" : reqTabId).trim();
        if (tid.isEmpty() || !slot.tabs.contains(tid)) return;
        try {
            JSONObject prev = cachedTabMeta.get(tid);
            JSONObject o = prev != null ? new JSONObject(prev.toString()) : new JSONObject();
            o.put("id", tid);
            if (!o.has("kind")) o.put("kind", "pi");
            if (resp.has("url")) o.put("url", resp.optString("url", ""));
            if (resp.has("title")) o.put("title", resp.optString("title", ""));
            if (resp.has("loading")) o.put("loading", resp.optBoolean("loading", false));
            if (resp.has("progress")) o.put("progress", resp.optInt("progress", 100));
            if (resp.has("canGoBack")) o.put("canGoBack", resp.optBoolean("canGoBack", false));
            if (resp.has("canGoForward")) o.put("canGoForward", resp.optBoolean("canGoForward", false));
            if (resp.has("desktop")) o.put("desktop", resp.optBoolean("desktop", false));
            if (resp.has("viewport")) o.put("viewport", resp.optString("viewport", ""));
            o.put("container", slot.boundContainerId);
            o.put("containerLabel", slot.boundLabel);
            o.put("remote", true);
            o.put("workerSlot", slot.slot);
            o.put("workerPid", slot.pid);
            o.put("proxyFrozen", false);
            o.put("proxySummary", slot.proxySummary);
            cachedTabMeta.put(tid, o);
        } catch (Throwable ignored) {}
    }

    public boolean updateTabStateFromEvent(JSONObject ev) {
        if (ev == null) return false;
        String tid = ev.optString("tabId", "").trim();
        if (tid.isEmpty()) return false;
        WorkerSlot slot = tabIndex.get(tid);
        if (slot == null || !slot.isReady()) return false;
        int evSlot = ev.optInt("slot", -1);
        if (evSlot > 0 && slot.slot != evSlot) return false;
        String evCid = ev.optString("containerId", "").trim();
        if (!evCid.isEmpty() && !evCid.equals(slot.boundContainerId)) return false;
        try {
            JSONObject prev = cachedTabMeta.get(tid);
            JSONObject o = prev != null ? new JSONObject(prev.toString()) : new JSONObject();
            o.put("id", tid);
            if (!o.has("kind")) o.put("kind", "pi");
            if (ev.has("url")) o.put("url", ev.optString("url", ""));
            if (ev.has("title")) o.put("title", ev.optString("title", ""));
            if (ev.has("loading")) o.put("loading", ev.optBoolean("loading", false));
            if (ev.has("progress")) o.put("progress", ev.optInt("progress", 100));
            if (ev.has("canGoBack")) o.put("canGoBack", ev.optBoolean("canGoBack", false));
            if (ev.has("canGoForward")) o.put("canGoForward", ev.optBoolean("canGoForward", false));
            if (ev.has("desktop")) {
                boolean desk = ev.optBoolean("desktop", false);
                o.put("desktop", desk);
                o.put("viewport", ContainerEnv.viewportWidth(desk) + "x" + ContainerEnv.viewportHeight(desk));
            }
            o.put("container", slot.boundContainerId);
            o.put("containerLabel", slot.boundLabel);
            o.put("remote", true);
            o.put("workerSlot", slot.slot);
            o.put("workerPid", slot.pid);
            o.put("proxyFrozen", false);
            o.put("proxySummary", slot.proxySummary);
            cachedTabMeta.put(tid, o);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public JSONObject getCachedTabMeta(String tabId) {
        if (tabId == null || tabId.isEmpty()) return null;
        JSONObject o = cachedTabMeta.get(tabId);
        if (o == null) return null;
        try {
            return new JSONObject(o.toString());
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------ SurfaceControlViewHost 跨进程控制信令

    public boolean sendAttachSurface(
            String tabId,
            long attachSeq,
            IBinder hostToken,
            int displayId,
            int width,
            int height,
            AttachSurfaceCallback cb
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || shuttingDown.get()) return false;
        if (tabId == null || tabId.isEmpty() || hostToken == null || width <= 0 || height <= 0) return false;
        WorkerSlot slot = tabIndex.get(tabId);
        if (slot == null || !slot.isReady()) return false;
        Messenger m = slot.messenger;
        if (m == null) return false;

        if (cb != null) {
            pendingAttachCallbacks.put(attachSeq, cb);
        }
        Message msg = Message.obtain(null, ProxyWorkerBridge.MSG_ATTACH_SURFACE);
        msg.replyTo = surfaceReplyMessenger;
        Bundle b = new Bundle();
        b.putString(ProxyWorkerBridge.KEY_TAB_ID, tabId);
        b.putLong(ProxyWorkerBridge.KEY_ATTACH_SEQ, attachSeq);
        b.putBinder(ProxyWorkerBridge.KEY_HOST_TOKEN, hostToken);
        b.putInt(ProxyWorkerBridge.KEY_DISPLAY_ID, displayId);
        b.putInt(ProxyWorkerBridge.KEY_WIDTH, width);
        b.putInt(ProxyWorkerBridge.KEY_HEIGHT, height);
        msg.setData(b);
        try {
            m.send(msg);
            return true;
        } catch (Throwable t) {
            pendingAttachCallbacks.remove(attachSeq);
            Log.w(TAG, "sendAttachSurface failed slot=#" + slot.slot + " tab=" + tabId, t);
            return false;
        }
    }

    public void cancelPendingAttach(long attachSeq) {
        if (attachSeq > 0L) {
            pendingAttachCallbacks.remove(attachSeq);
        }
    }

    public void sendResizeSurface(String tabId, long attachSeq, int width, int height) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || shuttingDown.get()) return;
        if (tabId == null || tabId.isEmpty() || width <= 0 || height <= 0) return;
        WorkerSlot slot = tabIndex.get(tabId);
        if (slot == null || !slot.isReady()) return;
        Messenger m = slot.messenger;
        if (m == null) return;
        Message msg = Message.obtain(null, ProxyWorkerBridge.MSG_RESIZE_SURFACE);
        Bundle b = new Bundle();
        b.putString(ProxyWorkerBridge.KEY_TAB_ID, tabId);
        b.putLong(ProxyWorkerBridge.KEY_ATTACH_SEQ, attachSeq);
        b.putInt(ProxyWorkerBridge.KEY_WIDTH, width);
        b.putInt(ProxyWorkerBridge.KEY_HEIGHT, height);
        msg.setData(b);
        try {
            m.send(msg);
        } catch (Throwable t) {
            Log.w(TAG, "sendResizeSurface failed slot=#" + slot.slot + " tab=" + tabId, t);
        }
    }

    public void sendDetachSurface(String tabId, long attachSeq) {
        if (tabId == null || tabId.isEmpty()) return;
        sendDetachSurface(tabIndex.get(tabId), tabId, attachSeq);
    }

    public void sendDetachSurface(WorkerSlot slot, String tabId, long attachSeq) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || slot == null || !slot.isReady()) return;
        if (tabId == null || tabId.isEmpty()) return;
        Messenger m = slot.messenger;
        if (m == null) return;
        Message msg = Message.obtain(null, ProxyWorkerBridge.MSG_DETACH_SURFACE);
        Bundle b = new Bundle();
        b.putString(ProxyWorkerBridge.KEY_TAB_ID, tabId);
        b.putLong(ProxyWorkerBridge.KEY_ATTACH_SEQ, attachSeq);
        msg.setData(b);
        try {
            m.send(msg);
        } catch (Throwable t) {
            Log.w(TAG, "sendDetachSurface failed slot=#" + slot.slot + " tab=" + tabId, t);
        }
    }

    private void handleSurfaceReplyMessage(Message msg) {
        if (msg == null || msg.what != ProxyWorkerBridge.MSG_ATTACH_SURFACE_ACK) return;
        Bundle b = msg.getData();
        if (b != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            SurfacePkgApi30.setClassLoader(b);
        }
        long seq = b != null ? b.getLong(ProxyWorkerBridge.KEY_ATTACH_SEQ, 0L) : 0L;
        String tabId = b != null ? b.getString(ProxyWorkerBridge.KEY_TAB_ID, "") : "";
        boolean ok = b != null && b.getBoolean(ProxyWorkerBridge.KEY_OK, false);
        String error = b != null ? b.getString(ProxyWorkerBridge.KEY_ERROR, "") : "ACK 缺少 Bundle";
        Object pkg = (b != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                ? SurfacePkgApi30.getPackage(b, ProxyWorkerBridge.KEY_SURFACE_PACKAGE)
                : null;

        AttachSurfaceCallback cb = pendingAttachCallbacks.remove(seq);
        if (cb == null) {
            if (pkg != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                SurfacePkgApi30.release(pkg);
            }
            if (!tabId.isEmpty() && seq > 0L) {
                sendDetachSurface(tabId, seq);
            }
            return;
        }
        if (ok && pkg != null) {
            updateCachedTabFromAck(tabId, b);
            cb.onResult(true, pkg, b, "");
        } else {
            if (pkg != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                SurfacePkgApi30.release(pkg);
            }
            String errMsg = (error != null && !error.isEmpty()) ? error : (ok ? "SurfacePackage 为空" : "远程嵌入失败");
            cb.onResult(false, null, b, errMsg);
        }
    }

    private void updateCachedTabFromAck(String tabId, Bundle b) {
        if (tabId == null || tabId.isEmpty() || b == null) return;
        WorkerSlot slot = tabIndex.get(tabId);
        if (slot == null || !slot.isReady()) return;
        try {
            JSONObject prev = cachedTabMeta.get(tabId);
            JSONObject o = prev != null ? new JSONObject(prev.toString()) : new JSONObject();
            o.put("id", tabId);
            if (!o.has("kind")) o.put("kind", "pi");
            if (b.containsKey(ProxyWorkerBridge.KEY_URL)) o.put("url", b.getString(ProxyWorkerBridge.KEY_URL, ""));
            if (b.containsKey(ProxyWorkerBridge.KEY_TITLE)) o.put("title", b.getString(ProxyWorkerBridge.KEY_TITLE, ""));
            if (b.containsKey(ProxyWorkerBridge.KEY_LOADING)) o.put("loading", b.getBoolean(ProxyWorkerBridge.KEY_LOADING, false));
            if (b.containsKey(ProxyWorkerBridge.KEY_PROGRESS)) o.put("progress", b.getInt(ProxyWorkerBridge.KEY_PROGRESS, 100));
            if (b.containsKey(ProxyWorkerBridge.KEY_CAN_GO_BACK)) o.put("canGoBack", b.getBoolean(ProxyWorkerBridge.KEY_CAN_GO_BACK, false));
            if (b.containsKey(ProxyWorkerBridge.KEY_CAN_GO_FORWARD)) o.put("canGoForward", b.getBoolean(ProxyWorkerBridge.KEY_CAN_GO_FORWARD, false));
            if (b.containsKey(ProxyWorkerBridge.KEY_DESKTOP)) {
                boolean desk = b.getBoolean(ProxyWorkerBridge.KEY_DESKTOP, false);
                o.put("desktop", desk);
                o.put("viewport", ContainerEnv.viewportWidth(desk) + "x" + ContainerEnv.viewportHeight(desk));
            }
            o.put("container", slot.boundContainerId);
            o.put("containerLabel", slot.boundLabel);
            o.put("remote", true);
            o.put("workerSlot", slot.slot);
            o.put("workerPid", slot.pid);
            o.put("proxyFrozen", false);
            o.put("proxySummary", slot.proxySummary);
            cachedTabMeta.put(tabId, o);
        } catch (Throwable ignored) {}
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static final class SurfacePkgApi30 {
        static void setClassLoader(Bundle b) {
            if (b != null) {
                b.setClassLoader(SurfaceControlViewHost.SurfacePackage.class.getClassLoader());
            }
        }

        static Object getPackage(Bundle b, String key) {
            if (b == null) return null;
            return b.getParcelable(key);
        }

        static void release(Object pkg) {
            if (pkg instanceof SurfaceControlViewHost.SurfacePackage) {
                try {
                    ((SurfaceControlViewHost.SurfacePackage) pkg).release();
                } catch (Throwable ignored) {}
            }
        }
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        if (in == null) return new byte[0];
        ByteArrayOutputStream bo = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) {
            bo.write(buf, 0, n);
        }
        return bo.toByteArray();
    }

    public void cancel(String reqId, String tabId) {
        String rid = reqId == null ? "" : reqId.trim();
        String tid = tabId == null ? "" : tabId.trim();
        List<WorkerSlot> targets = new ArrayList<>();

        if (!rid.isEmpty()) {
            InflightCall call = inflightCalls.get(rid);
            if (call != null) {
                call.cancelled = true;
                HttpURLConnection c = call.conn;
                if (c != null) {
                    try { c.disconnect(); } catch (Throwable ignored) {}
                }
                if (!targets.contains(call.slot)) targets.add(call.slot);
            }
        }
        if (!tid.isEmpty()) {
            WorkerSlot byTab = tabIndex.get(tid);
            boolean isAlias = (byTab == null) && ("pi".equalsIgnoreCase(tid) || "user".equalsIgnoreCase(tid));
            for (InflightCall call : inflightCalls.values()) {
                boolean hit = tid.equalsIgnoreCase(call.tabId) || (isAlias && aliasMatchesTabId(tid, call.tabId));
                if (hit) {
                    call.cancelled = true;
                    HttpURLConnection c = call.conn;
                    if (c != null) {
                        try { c.disconnect(); } catch (Throwable ignored) {}
                    }
                    if (!targets.contains(call.slot)) targets.add(call.slot);
                }
            }
            if (byTab != null && !targets.contains(byTab)) {
                targets.add(byTab);
            } else if (isAlias) {
                synchronized (poolLock) {
                    for (WorkerSlot s : slots) {
                        if (s.state == SlotState.READY_BUSY && s.workerPort > 0 && slotMatchesAliasLocked(s, tid)) {
                            if (!targets.contains(s)) targets.add(s);
                        }
                    }
                }
                if (!targets.isEmpty()) {
                    for (InflightCall call : inflightCalls.values()) {
                        if (targets.contains(call.slot) && !call.cancelled) {
                            call.cancelled = true;
                            HttpURLConnection c = call.conn;
                            if (c != null) {
                                try { c.disconnect(); } catch (Throwable ignored) {}
                            }
                        }
                    }
                }
            }
        }

        if (targets.isEmpty() && !rid.isEmpty()) {
            for (WorkerSlot s : slots) {
                if (s.isReady() && s.workerPort > 0) targets.add(s);
            }
        }

        for (WorkerSlot s : targets) {
            if (s.workerPort <= 0 || s.workerToken.isEmpty()) continue;
            asyncPool.execute(() -> postCancelNoLock(s, rid, tid));
        }
    }

    private boolean aliasMatchesTabId(String alias, String tabId) {
        if ("pi".equalsIgnoreCase(alias)) {
            if (tabId == null || tabId.isEmpty()) return true;
            JSONObject meta = cachedTabMeta.get(tabId);
            return meta == null || "pi".equalsIgnoreCase(meta.optString("kind", "pi"));
        }
        if ("user".equalsIgnoreCase(alias)) {
            if (tabId == null || tabId.isEmpty()) return false;
            JSONObject meta = cachedTabMeta.get(tabId);
            return meta != null && "user".equalsIgnoreCase(meta.optString("kind", ""));
        }
        return false;
    }

    private boolean slotMatchesAliasLocked(WorkerSlot s, String alias) {
        if (s == null) return false;
        for (String tid : s.tabs) {
            if (aliasMatchesTabId(alias, tid)) return true;
        }
        return false;
    }

    private static void postCancelNoLock(WorkerSlot slot, String reqId, String tabId) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://127.0.0.1:" + slot.workerPort + ProxyWorkerBridge.PATH_CANCEL);
            conn = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(2000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            JSONObject body = new JSONObject();
            if (!reqId.isEmpty()) body.put("req_id", reqId);
            if (!tabId.isEmpty()) body.put("tab", tabId);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty(ProxyWorkerBridge.HEADER_WORKER_TOKEN, slot.workerToken);
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
            }
            conn.getResponseCode();
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
        }
    }

    // ------------------------------------------------------------------ 标签索引与查询

    public void registerRemoteTab(String tabId, WorkerSlot slot) {
        if (tabId == null || tabId.isEmpty() || slot == null) return;
        tabIndex.put(tabId, slot);
        if (!tabOrder.contains(tabId)) {
            tabOrder.add(tabId);
        }
        synchronized (poolLock) {
            slot.tabs.add(tabId);
            slot.tabsGen++;
            slot.lastUsedAt = System.currentTimeMillis();
            if (slot.state == SlotState.READY_IDLE) {
                slot.state = SlotState.READY_BUSY;
            }
        }
        try {
            JSONObject o = new JSONObject();
            o.put("id", tabId);
            o.put("kind", "pi");
            o.put("url", "");
            o.put("title", "");
            o.put("container", slot.boundContainerId);
            o.put("containerLabel", slot.boundLabel);
            o.put("remote", true);
            o.put("workerSlot", slot.slot);
            o.put("workerPid", slot.pid);
            o.put("proxyFrozen", false);
            o.put("proxySummary", slot.proxySummary);
            cachedTabMeta.putIfAbsent(tabId, o);
        } catch (Throwable ignored) {}
    }

    public void unregisterRemoteTab(String tabId) {
        if (tabId == null || tabId.isEmpty()) return;
        cachedTabMeta.remove(tabId);
        tabOrder.remove(tabId);
        WorkerSlot slot = tabIndex.remove(tabId);
        if (slot == null) {
            synchronized (poolLock) {
                for (WorkerSlot s : slots) {
                    if (s.tabs.remove(tabId)) {
                        slot = s;
                        break;
                    }
                }
            }
        } else {
            slot.tabs.remove(tabId);
        }
        if (slot != null) {
            synchronized (poolLock) {
                slot.tabsGen++;
                slot.lastUsedAt = System.currentTimeMillis();
                if (slot.tabs.isEmpty() && slot.state == SlotState.READY_BUSY) {
                    slot.state = SlotState.READY_IDLE;
                }
            }
        }
    }

    public String lastRemoteTabId() {
        synchronized (tabOrder) {
            for (int i = tabOrder.size() - 1; i >= 0; i--) {
                String id = tabOrder.get(i);
                if (tabIndex.containsKey(id)) return id;
            }
        }
        return null;
    }

    public int remoteTabCount() {
        return tabIndex.size();
    }

    public List<JSONObject> listCachedRemoteTabs() {
        List<JSONObject> out = new ArrayList<>();
        synchronized (tabOrder) {
            for (String tid : tabOrder) {
                WorkerSlot s = tabIndex.get(tid);
                if (s == null || !s.isReady()) continue;
                JSONObject o = cachedTabMeta.get(tid);
                if (o != null) {
                    try {
                        out.add(new JSONObject(o.toString()));
                    } catch (Throwable ignored) {}
                }
            }
        }
        return out;
    }

    public void refreshRemoteTabsAsync(Runnable onUpdatedOnMain) {
        if (shuttingDown.get()) return;
        boolean anyActive = false;
        synchronized (poolLock) {
            for (WorkerSlot s : slots) {
                if (s.isReady() && s.workerPort > 0) {
                    anyActive = true;
                    break;
                }
            }
        }
        if (!anyActive) return;
        asyncPool.execute(() -> {
            try {
                listAllRemoteTabs();
            } catch (Throwable ignored) {}
            if (onUpdatedOnMain != null && !shuttingDown.get()) {
                mainHandler.post(onUpdatedOnMain);
            }
        });
    }

    public WorkerSlot slotForTab(String tabId) {
        if (tabId == null || tabId.isEmpty()) return null;
        return tabIndex.get(tabId);
    }

    public WorkerSlot slotForContainer(String containerId) {
        if (containerId == null || containerId.isEmpty()) return null;
        synchronized (poolLock) {
            for (WorkerSlot s : slots) {
                if (containerId.equals(s.boundContainerId) && s.isReady()) {
                    return s;
                }
            }
        }
        return null;
    }

    public boolean isRemoteTab(String tabId) {
        return tabId != null && tabIndex.containsKey(tabId);
    }

    public int remoteTabCountForContainer(String containerId) {
        if (containerId == null || containerId.isEmpty()) return 0;
        synchronized (poolLock) {
            int sum = 0;
            for (WorkerSlot s : slots) {
                if (containerId.equals(s.boundContainerId) && s.isReady()) {
                    sum += s.tabs.size();
                }
            }
            return sum;
        }
    }

    public boolean isContainerBusy(String containerId) {
        if (containerId == null || containerId.isEmpty()) return false;
        synchronized (poolLock) {
            for (WorkerSlot s : slots) {
                if (containerId.equals(s.boundContainerId) && s.state != SlotState.DEAD) {
                    if (!s.tabs.isEmpty() || s.pendingAcquires > 0 || s.state == SlotState.INITIALIZING) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /** 当 keep==false 的临时容器释放后，若对应槽位空闲则先优雅退出进程再删目录，绝不在进程运行时硬删文件 */
    public void releaseIdleTempSlotAsync(String containerId) {
        if (shuttingDown.get() || containerId == null || containerId.trim().isEmpty()) return;
        final String cid = containerId.trim();
        if (ContainerManager.DEFAULT.equalsIgnoreCase(cid) || isContainerKept(cid)) return;
        try {
            asyncPool.execute(() -> {
                WorkerSlot idleTarget = null;
                boolean anySlotUsingCid = false;
                synchronized (poolLock) {
                    for (WorkerSlot s : slots) {
                        if (cid.equals(s.boundContainerId) && s.state != SlotState.DEAD) {
                            anySlotUsingCid = true;
                            if (s.state == SlotState.READY_IDLE && s.tabs.isEmpty() && s.pendingAcquires == 0) {
                                s.state = SlotState.RECYCLING;
                                idleTarget = s;
                            }
                            break;
                        }
                    }
                }
                if (idleTarget != null) {
                    recycleSlot(idleTarget);
                } else if (!anySlotUsingCid) {
                    ContainerManager.deleteWorkerDataDirSync(appContext, cid);
                }
            });
        } catch (Throwable ignored) {}
    }

    public void broadcastConfigSync() {
        broadcastSimpleAction("sync_config");
    }

    public void broadcastScriptsSync() {
        broadcastSimpleAction("sync_scripts");
    }

    private void broadcastSimpleAction(String action) {
        if (shuttingDown.get()) return;
        List<WorkerSlot> ready = new ArrayList<>();
        synchronized (poolLock) {
            for (WorkerSlot s : slots) {
                if (s.isReady() && s.workerPort > 0) ready.add(s);
            }
        }
        for (WorkerSlot s : ready) {
            asyncPool.execute(() -> {
                try {
                    callRpc(s, new JSONObject().put("action", action), action + "-" + s.slot + "-" + System.nanoTime(), 1500L);
                } catch (Throwable ignored) {}
            });
        }
    }

    public List<JSONObject> listAllRemoteTabs() {
        List<WorkerSlot> activeSlots = new ArrayList<>();
        List<Long> startGens = new ArrayList<>();
        synchronized (poolLock) {
            for (WorkerSlot s : slots) {
                if (s.isReady() && s.workerPort > 0) {
                    activeSlots.add(s);
                    startGens.add(s.tabsGen);
                }
            }
        }
        if (activeSlots.isEmpty()) return Collections.emptyList();

        List<JSONObject> out = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(activeSlots.size());
        for (int idx = 0; idx < activeSlots.size(); idx++) {
            final WorkerSlot s = activeSlots.get(idx);
            final long startGen = startGens.get(idx);
            asyncPool.execute(() -> {
                try {
                    if (!isSlotAlive(s)) {
                        forceResetToDead(s, isContainerKept(s.boundContainerId));
                        return;
                    }
                    JSONObject resp = callRpc(s, new JSONObject().put("action", "tabs"), "tabs-" + s.slot + "-" + System.nanoTime(), 2000L);
                    if (resp.optBoolean("success", false)) {
                        JSONArray arr = resp.optJSONArray("tabs");
                        Set<String> seen = new LinkedHashSet<>();
                        List<JSONObject> parsedTabs = new ArrayList<>();
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject t = arr.optJSONObject(i);
                                if (t == null) continue;
                                String tid = t.optString("id", "");
                                if (!tid.isEmpty()) {
                                    seen.add(tid);
                                    parsedTabs.add(t);
                                }
                                out.add(t);
                            }
                        }
                        synchronized (poolLock) {
                            for (JSONObject t : parsedTabs) {
                                String tid = t.optString("id", "");
                                if (tid.isEmpty()) continue;
                                tabIndex.put(tid, s);
                                if (!tabOrder.contains(tid)) tabOrder.add(tid);
                                cachedTabMeta.put(tid, new JSONObject(t.toString()));
                            }
                            if (s.tabsGen == startGen) {
                                for (String prevId : new ArrayList<>(s.tabs)) {
                                    if (!seen.contains(prevId)) {
                                        tabIndex.remove(prevId, s);
                                        cachedTabMeta.remove(prevId);
                                        tabOrder.remove(prevId);
                                    }
                                }
                                s.tabs.clear();
                                s.tabs.addAll(seen);
                            } else {
                                s.tabs.addAll(seen);
                            }
                            if (s.tabs.isEmpty() && s.state == SlotState.READY_BUSY) {
                                s.state = SlotState.READY_IDLE;
                            } else if (!s.tabs.isEmpty() && s.state == SlotState.READY_IDLE) {
                                s.state = SlotState.READY_BUSY;
                            }
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "listAllRemoteTabs slot #" + s.slot + " failed", t);
                    if (!isSlotAlive(s) || t instanceof ConnectException) {
                        forceResetToDead(s, isContainerKept(s.boundContainerId));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await(2200L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new ArrayList<>(out);
    }

    public JSONArray workersStatusJson() {
        JSONArray arr = new JSONArray();
        synchronized (poolLock) {
            for (WorkerSlot s : slots) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("slot", s.slot);
                    o.put("state", s.state.name());
                    o.put("pid", s.pid);
                    o.put("container", s.boundContainerId);
                    o.put("label", s.boundLabel);
                    o.put("tabs", s.tabs.size());
                    o.put("proxySummary", s.proxySummary);
                    o.put("localProxyPort", s.localProxyPort);
                    arr.put(o);
                } catch (Throwable ignored) {}
            }
        }
        return arr;
    }

    public void shutdownAll() {
        if (!shuttingDown.compareAndSet(false, true)) return;
        for (InflightCall call : inflightCalls.values()) {
            call.cancelled = true;
            HttpURLConnection c = call.conn;
            if (c != null) {
                try { c.disconnect(); } catch (Throwable ignored) {}
            }
        }
        inflightCalls.clear();
        Runnable runCleanup = () -> {
            for (WorkerSlot s : slots) {
                if (s.state != SlotState.DEAD) {
                    try {
                        recycleSlot(s);
                    } catch (Throwable t) {
                        forceResetToDead(s, false);
                    }
                }
            }
            try {
                asyncPool.shutdownNow();
            } catch (Throwable ignored) {}
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Thread t = new Thread(runCleanup, "pimet-pw-shutdown-all");
            t.setDaemon(true);
            t.start();
        } else {
            runCleanup.run();
        }
    }

    private static String generateWorkerToken() {
        byte[] bytes = new byte[32];
        RNG.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return sb.toString();
    }
}
