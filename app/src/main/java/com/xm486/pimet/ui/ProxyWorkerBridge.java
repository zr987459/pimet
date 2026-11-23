package com.xm486.pimet.ui;

/**
 * 主进程与 {@code :proxy_worker_1..4} 代理子进程之间的纯 Java {@link android.os.Messenger} + {@link android.os.Bundle} 信令常量。
 * 严禁使用 AIDL。
 */
public final class ProxyWorkerBridge {

    private ProxyWorkerBridge() {}

    /** 主进程 → 子进程：初始化容器代理、WebView DataDirectorySuffix、OffscreenWebHost 与本地 JSON-RPC 服务 */
    public static final int MSG_INIT = 1;
    /** 子进程 → 主进程：初始化完成回执 */
    public static final int MSG_INIT_ACK = 2;
    /** 主进程 → 子进程：释放资源并退出子进程 */
    public static final int MSG_SHUTDOWN = 3;

    // ---- MSG_INIT Bundle Keys
    public static final String KEY_SLOT = "slot";
    public static final String KEY_CONTAINER_ID = "containerId";
    public static final String KEY_LABEL = "label";
    public static final String KEY_PROFILE_JSON = "profileJson";
    public static final String KEY_WORKER_TOKEN = "workerToken";
    public static final String KEY_APPS_DIR = "appsDir";
    public static final String KEY_MAIN_CALLBACK_PORT = "mainCallbackPort";
    public static final String KEY_MAIN_TOKEN = "mainToken";
    public static final String KEY_CUSTOM_UA = "customUa";

    // ---- MSG_INIT_ACK Bundle Keys
    public static final String KEY_OK = "ok";
    public static final String KEY_PORT = "port";
    public static final String KEY_PID = "pid";
    public static final String KEY_SUFFIX = "suffix";
    public static final String KEY_ERROR = "error";
    public static final String KEY_PROXY_SUPPORTED = "proxySupported";
    public static final String KEY_PROXY_SUMMARY = "proxySummary";
    public static final String KEY_LOCAL_PROXY_PORT = "localProxyPort";

    // ---- 本地回环 HTTP RPC & 主进程内部回调协议常量
    public static final String HEADER_WORKER_TOKEN = "X-Worker-Token";
    public static final String HEADER_MAIN_TOKEN = "X-Main-Token";
    public static final String PATH_RPC = "/rpc";
    public static final String PATH_CANCEL = "/cancel";
    public static final String PATH_WORKER_EVENT = "/internal/worker-event";
}
