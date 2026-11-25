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
    /** 主进程 → 子进程：将指定标签的 WebView 嵌入主进程 SurfaceView（API 30+ SurfaceControlViewHost） */
    public static final int MSG_ATTACH_SURFACE = 4;
    /** 子进程 → 主进程：返回 SurfacePackage 与当前标签状态 */
    public static final int MSG_ATTACH_SURFACE_ACK = 5;
    /** 主进程 → 子进程：调整已嵌入 SurfaceControlViewHost 尺寸 */
    public static final int MSG_RESIZE_SURFACE = 6;
    /** 主进程 → 子进程：解除 SurfaceControlViewHost 嵌入并回挂 OffscreenWebHost */
    public static final int MSG_DETACH_SURFACE = 7;

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

    // ---- SurfaceControlViewHost 跨进程原生嵌入 Bundle Keys
    public static final String KEY_TAB_ID = "tabId";
    public static final String KEY_ATTACH_SEQ = "attachSeq";
    public static final String KEY_HOST_TOKEN = "hostToken";
    public static final String KEY_DISPLAY_ID = "displayId";
    public static final String KEY_WIDTH = "width";
    public static final String KEY_HEIGHT = "height";
    public static final String KEY_SURFACE_PACKAGE = "surfacePackage";

    // ---- 实时状态同步 Bundle Keys
    public static final String KEY_URL = "url";
    public static final String KEY_TITLE = "title";
    public static final String KEY_LOADING = "loading";
    public static final String KEY_PROGRESS = "progress";
    public static final String KEY_CAN_GO_BACK = "canGoBack";
    public static final String KEY_CAN_GO_FORWARD = "canGoForward";
    public static final String KEY_DESKTOP = "desktop";

    // ---- 本地回环 HTTP RPC & 主进程内部回调协议常量
    public static final String HEADER_WORKER_TOKEN = "X-Worker-Token";
    public static final String HEADER_MAIN_TOKEN = "X-Main-Token";
    public static final String PATH_RPC = "/rpc";
    public static final String PATH_CANCEL = "/cancel";
    public static final String PATH_WORKER_EVENT = "/internal/worker-event";
}
