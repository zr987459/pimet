package com.xm486.pimet.ui;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 独立代理子进程 Service 基类（绑定到 {@code :proxy_worker_1..4}）。
 * 通过纯 Java {@link Messenger} + {@link Bundle} 接收主进程 {@code MSG_INIT} / {@code MSG_SHUTDOWN} 控制信令。
 */
public abstract class ProxyWorkerService extends Service {

    private static final String TAG = "ProxyWorkerService";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean killScheduled = new AtomicBoolean(false);
    private Messenger messenger;
    private ProxyWorkerRuntime runtime;
    private boolean shuttingDown = false;

    @Override
    public void onCreate() {
        super.onCreate();
        this.messenger = new Messenger(new IncomingHandler(Looper.getMainLooper()));
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (messenger == null) {
            messenger = new Messenger(new IncomingHandler(Looper.getMainLooper()));
        }
        return messenger.getBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        shuttingDown = true;
        releaseRuntime();
        stopSelf();
        scheduleKillSelf();
        return false;
    }

    @Override
    public void onDestroy() {
        shuttingDown = true;
        releaseRuntime();
        super.onDestroy();
        scheduleKillSelf();
    }

    private void scheduleKillSelf() {
        if (!killScheduled.compareAndSet(false, true)) return;
        mainHandler.postDelayed(() -> {
            try {
                Process.killProcess(Process.myPid());
            } catch (Throwable ignored) {}
        }, 120L);
    }

    private void releaseRuntime() {
        ProxyWorkerRuntime rt = runtime;
        runtime = null;
        if (rt != null) {
            try {
                rt.shutdown();
            } catch (Throwable t) {
                Log.w(TAG, "runtime.shutdown failed", t);
            }
        }
    }

    private void handleInit(Message msg) {
        final Messenger replyTo = msg.replyTo;
        Bundle data = msg.getData();
        if (data == null) {
            sendInitAck(replyTo, false, 0, Process.myPid(), ProxyWorkerRuntime.boundSuffix(),
                    "MSG_INIT 缺少 Bundle", false, "直连", 0);
            return;
        }
        final int slot = data.getInt(ProxyWorkerBridge.KEY_SLOT, 0);
        final String containerId = data.getString(ProxyWorkerBridge.KEY_CONTAINER_ID, ContainerManager.DEFAULT);
        final String label = data.getString(ProxyWorkerBridge.KEY_LABEL, "");
        final String profileJson = data.getString(ProxyWorkerBridge.KEY_PROFILE_JSON, "");
        final String workerToken = data.getString(ProxyWorkerBridge.KEY_WORKER_TOKEN, "");
        final String appsDir = data.getString(ProxyWorkerBridge.KEY_APPS_DIR, "");
        final int mainCallbackPort = data.getInt(ProxyWorkerBridge.KEY_MAIN_CALLBACK_PORT, 0);
        final String mainToken = data.getString(ProxyWorkerBridge.KEY_MAIN_TOKEN, "");
        final String customUa = data.getString(ProxyWorkerBridge.KEY_CUSTOM_UA, "");

        // 生死红线：在触碰任何 WebView/CookieManager/WebSettings/ContainerEnv 之前先绑定数据目录后缀
        final String suffix = ContainerManager.workerDataDirSuffix(containerId);
        ProxyWorkerRuntime.ensureDataDirectorySuffix(suffix);
        if (!suffix.equals(ProxyWorkerRuntime.boundSuffix())) {
            String bound = ProxyWorkerRuntime.boundSuffix();
            Log.e(TAG, "DataDirectorySuffix mismatch: want=" + suffix + " bound=" + bound + ", killing process");
            sendInitAck(replyTo, false, 0, Process.myPid(), bound,
                    "工作进程数据目录后缀冲突（期望 " + suffix + "，当前 " + bound + "）",
                    false, "直连", 0);
            try {
                Process.killProcess(Process.myPid());
            } catch (Throwable ignored) {}
            return;
        }
        if (shuttingDown) {
            sendInitAck(replyTo, false, 0, Process.myPid(), suffix,
                    "工作进程正在退出", false, "直连", 0);
            try {
                Process.killProcess(Process.myPid());
            } catch (Throwable ignored) {}
            return;
        }

        if (runtime != null) {
            sendInitAck(replyTo, true, runtime.getPort(), runtime.getPid(), runtime.getSuffix(),
                    "", runtime.isProxySupported(), runtime.getProxySummary(), runtime.getLocalProxyPort());
            return;
        }

        try {
            final boolean[] syncDone = {false};
            final boolean[] syncOk = {false};
            final String[] syncErr = {""};
            final ProxyWorkerRuntime[] holder = new ProxyWorkerRuntime[1];
            ProxyWorkerRuntime created = new ProxyWorkerRuntime(
                    getApplicationContext(),
                    slot,
                    containerId,
                    label,
                    profileJson,
                    workerToken,
                    appsDir,
                    mainCallbackPort,
                    mainToken,
                    customUa,
                    (ok, error) -> {
                        ProxyWorkerRuntime rt = holder[0];
                        if (rt == null) {
                            syncDone[0] = true;
                            syncOk[0] = ok;
                            syncErr[0] = error;
                            return;
                        }
                        finishInitAck(replyTo, suffix, rt, ok, error);
                    }
            );
            holder[0] = created;
            runtime = created;
            if (syncDone[0]) {
                finishInitAck(replyTo, suffix, created, syncOk[0], syncErr[0]);
            }
        } catch (Throwable t) {
            Log.e(TAG, "ProxyWorkerRuntime init failed", t);
            releaseRuntime();
            sendInitAck(replyTo, false, 0, Process.myPid(), suffix,
                    t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage(),
                    false, "直连", 0);
            shuttingDown = true;
            stopSelf();
            scheduleKillSelf();
        }
    }

    private void finishInitAck(Messenger replyTo, String suffix, ProxyWorkerRuntime rt, boolean ok, String error) {
        if (!ok) {
            boolean proxySupported = rt != null && rt.isProxySupported();
            String proxySummary = rt != null ? rt.getProxySummary() : "直连";
            if (rt != null) {
                try { rt.shutdown(); } catch (Throwable ignored) {}
            }
            if (runtime == rt) runtime = null;
            sendInitAck(replyTo, false, 0, Process.myPid(), suffix,
                    error == null ? "工作进程初始化失败" : error,
                    proxySupported, proxySummary, 0);
            shuttingDown = true;
            stopSelf();
            scheduleKillSelf();
            return;
        }
        runtime = rt;
        sendInitAck(replyTo, true, rt.getPort(), rt.getPid(), rt.getSuffix(),
                "", rt.isProxySupported(), rt.getProxySummary(), rt.getLocalProxyPort());
    }

    private void handleShutdown() {
        if (shuttingDown) return;
        shuttingDown = true;
        releaseRuntime();
        stopSelf();
        scheduleKillSelf();
    }

    private void handleAttachSurfaceMsg(Message msg) {
        Bundle data = msg.getData();
        ProxyWorkerRuntime rt = runtime;
        if (rt == null || shuttingDown) {
            sendAttachSurfaceErrorAck(msg.replyTo, data,
                    shuttingDown ? "worker shutting down" : "runtime not ready");
            return;
        }
        rt.handleAttachSurface(data, msg.replyTo);
    }

    private void handleResizeSurfaceMsg(Message msg) {
        ProxyWorkerRuntime rt = runtime;
        if (rt != null && !shuttingDown) {
            rt.handleResizeSurface(msg.getData());
        }
    }

    private void handleDetachSurfaceMsg(Message msg) {
        ProxyWorkerRuntime rt = runtime;
        if (rt != null) {
            rt.handleDetachSurface(msg.getData());
        }
    }

    private static void sendAttachSurfaceErrorAck(Messenger replyTo, Bundle req, String error) {
        if (replyTo == null) return;
        Message ack = Message.obtain(null, ProxyWorkerBridge.MSG_ATTACH_SURFACE_ACK);
        Bundle b = new Bundle();
        b.putBoolean(ProxyWorkerBridge.KEY_OK, false);
        b.putString(ProxyWorkerBridge.KEY_ERROR, error == null ? "runtime not ready" : error);
        if (req != null) {
            b.putString(ProxyWorkerBridge.KEY_TAB_ID, req.getString(ProxyWorkerBridge.KEY_TAB_ID, ""));
            b.putLong(ProxyWorkerBridge.KEY_ATTACH_SEQ, req.getLong(ProxyWorkerBridge.KEY_ATTACH_SEQ, 0L));
        }
        ack.setData(b);
        try {
            replyTo.send(ack);
        } catch (Throwable t) {
            Log.w(TAG, "sendAttachSurfaceErrorAck failed", t);
        }
    }

    private static void sendInitAck(
            Messenger replyTo,
            boolean ok,
            int port,
            int pid,
            String suffix,
            String error,
            boolean proxySupported,
            String proxySummary,
            int localProxyPort
    ) {
        if (replyTo == null) return;
        Message ack = Message.obtain(null, ProxyWorkerBridge.MSG_INIT_ACK);
        Bundle b = new Bundle();
        b.putBoolean(ProxyWorkerBridge.KEY_OK, ok);
        b.putInt(ProxyWorkerBridge.KEY_PORT, port);
        b.putInt(ProxyWorkerBridge.KEY_PID, pid);
        b.putString(ProxyWorkerBridge.KEY_SUFFIX, suffix == null ? "" : suffix);
        b.putString(ProxyWorkerBridge.KEY_ERROR, error == null ? "" : error);
        b.putBoolean(ProxyWorkerBridge.KEY_PROXY_SUPPORTED, proxySupported);
        b.putString(ProxyWorkerBridge.KEY_PROXY_SUMMARY, proxySummary == null ? "直连" : proxySummary);
        b.putInt(ProxyWorkerBridge.KEY_LOCAL_PROXY_PORT, localProxyPort);
        ack.setData(b);
        try {
            replyTo.send(ack);
        } catch (Throwable t) {
            Log.w(TAG, "sendInitAck failed", t);
        }
    }

    private final class IncomingHandler extends Handler {
        IncomingHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg == null) return;
            switch (msg.what) {
                case ProxyWorkerBridge.MSG_INIT:
                    handleInit(msg);
                    break;
                case ProxyWorkerBridge.MSG_SHUTDOWN:
                    handleShutdown();
                    break;
                case ProxyWorkerBridge.MSG_ATTACH_SURFACE:
                    handleAttachSurfaceMsg(msg);
                    break;
                case ProxyWorkerBridge.MSG_RESIZE_SURFACE:
                    handleResizeSurfaceMsg(msg);
                    break;
                case ProxyWorkerBridge.MSG_DETACH_SURFACE:
                    handleDetachSurfaceMsg(msg);
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }
}
