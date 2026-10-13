package com.xm486.pimet.bridge;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.xm486.pimet.MainActivity;
import com.xm486.pimet.PiMetConfig;
import com.xm486.pimet.automation.PiMetAccessibilityService;
import com.xm486.pimet.pet.PetOverlayService;
import com.xm486.pimet.pet.PetRegistry;
import com.xm486.pimet.proot.ProotManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 宿主与 AI Agent 之间的双向双轨控制桥 (AppBridgeManager)
 * 允许 PRoot 容器内的 AI (通过 Pi Extension 工具或直接发指令) 自由控制外部 Android 界面与桌宠：
 *  - 尺寸缩放 (size: 32~160dp)
 *  - 形象/皮肤切换 (character: cat_maid, eva, gup, paimon, custom)
 *  - 动作播放 (action: jumping, dancing, waving)
 *  - 气泡说话 (bubble_text)
 *  - 物理参数 (bounce, friction)
 *  - 全局系统桌宠开关 (global_pet_enabled: true/false)
 *  - 选项卡切换 (switch_tab: launch, web, terminal, settings)
 *  - 服务端口重配 (ports: piweb, operit, clawbench, rikka)
 */
public class AppBridgeManager {

    private static final String TAG = "PiMet.AppBridge";
    public static final int BRIDGE_PORT = 30143;

    private static volatile AppBridgeManager instance;
    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private MainActivity attachedActivity;

    private ServerSocket serverSocket;
    private Thread serverThread;
    private Thread fileWatchThread;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private long lastFileTimestamp = 0;

    private AppBridgeManager(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public static AppBridgeManager getInstance(Context context) {
        if (instance == null) {
            synchronized (AppBridgeManager.class) {
                if (instance == null) {
                    instance = new AppBridgeManager(context);
                }
            }
        }
        return instance;
    }

    public void attachActivity(MainActivity activity) {
        this.attachedActivity = activity;
    }

    public void detachActivity(MainActivity activity) {
        if (this.attachedActivity == activity) {
            this.attachedActivity = null;
        }
    }

    public synchronized void start() {
        if (isRunning.get()) return;
        isRunning.set(true);
        startHttpServer();
        startFileWatcher();
    }

    public synchronized void stop() {
        isRunning.set(false);
        try {
            if (serverSocket != null) {
                serverSocket.close();
                serverSocket = null;
            }
        } catch (Throwable ignored) {}
        if (serverThread != null) {
            serverThread.interrupt();
            serverThread = null;
        }
        if (fileWatchThread != null) {
            fileWatchThread.interrupt();
            fileWatchThread = null;
        }
    }

    /**
     * 极轻量级原生 ServerSocket HTTP 服务 (127.0.0.1:30143)
     * 支持 Agent 内部毫秒级 RPC 调用 POST /api/app-control
     */
    private void startHttpServer() {
        serverThread = new Thread(() -> {
            // 端口被占用（如进程重启时旧连接未释放）时每 2 秒重试，而不是永久停摆
            while (isRunning.get()) {
                ServerSocket ss = null;
                try {
                    ss = new ServerSocket();
                    ss.setReuseAddress(true);
                    ss.bind(new InetSocketAddress("127.0.0.1", BRIDGE_PORT));
                    serverSocket = ss;
                    Log.i(TAG, "AppBridge HTTP server listening on 127.0.0.1:" + BRIDGE_PORT);
                    while (isRunning.get() && !ss.isClosed()) {
                        Socket client = ss.accept();
                        handleHttpClient(client);
                    }
                } catch (Throwable t) {
                    if (isRunning.get()) {
                        Log.w(TAG, "AppBridge HTTP server error, retrying: " + t.getMessage());
                    }
                } finally {
                    if (ss != null) try { ss.close(); } catch (Throwable ignored) {}
                }
                if (!isRunning.get()) break;
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "PiMet-AppBridge-Server");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    /** 请求线程池：每个连接一个守护线程，手机操作内部再串行化。 */
    private final java.util.concurrent.ExecutorService clientPool =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "PiMet-AppBridge-Client");
                t.setDaemon(true);
                return t;
            });

    /** 手机操作串行锁：避免多个请求的手势/输入交错。 */
    private static final Object PHONE_LOCK = new Object();

    private static final int MAX_BODY = 1024 * 1024;

    private void handleHttpClient(Socket socket) {
        clientPool.execute(() -> {
            try (Socket s = socket) {
                s.setSoTimeout(5000);
                java.io.InputStream in = new java.io.BufferedInputStream(s.getInputStream());
                // 头部按字节读取到空行；Content-Length 是字节数（中文 UTF-8 一字 3 字节），正文必须按字节读
                String requestLine = readLineBytes(in);
                if (requestLine == null) return;
                int contentLength = 0;
                String token = null;
                String hl;
                while ((hl = readLineBytes(in)) != null && !hl.isEmpty()) {
                    int c = hl.indexOf(':');
                    if (c <= 0) continue;
                    String name = hl.substring(0, c).trim().toLowerCase(java.util.Locale.ROOT);
                    String val = hl.substring(c + 1).trim();
                    if ("content-length".equals(name)) {
                        try { contentLength = Integer.parseInt(val); } catch (Exception ignored) {}
                    } else if ("x-pimet-token".equals(name)) {
                        token = val;
                    }
                }
                if (contentLength < 0 || contentLength > MAX_BODY) contentLength = 0;
                byte[] bodyBytes = new byte[contentLength];
                int got = 0;
                while (got < contentLength) {
                    int r = in.read(bodyBytes, got, contentLength - got);
                    if (r == -1) break;
                    got += r;
                }
                String body = new String(bodyBytes, 0, got, StandardCharsets.UTF_8);

                JSONObject resp;
                String[] parts = requestLine.split(" ");
                String method = parts.length > 0 ? parts[0] : "";
                String path = parts.length > 1 ? parts[1] : "/";
                if ("GET".equalsIgnoreCase(method) && path.startsWith("/api/phone-status")) {
                    resp = PiMetAccessibilityService.statusJson(appContext);
                } else if (TextUtils.isEmpty(body)) {
                    resp = new JSONObject().put("success", true).put("result", "OK");
                } else {
                    JSONObject json;
                    try {
                        json = new JSONObject(body);
                    } catch (Throwable t) {
                        json = null;
                        resp = new JSONObject().put("success", false).put("error", "JSON 解析失败: " + t.getMessage());
                        writeJson(s, resp);
                        return;
                    }
                    if (isPhoneControl(json)) {
                        if (token == null) token = json.optString("token", null);
                        if (!getBridgeToken().equals(token)) {
                            resp = new JSONObject().put("success", false)
                                    .put("error", "手机自动化请求缺少有效令牌（X-PiMet-Token，见容器内 ~/.pi/agent/.pimet_bridge_token），请重启 PiMet 后重试");
                        } else {
                            resp = executePhoneControl(json);
                        }
                    } else {
                        resp = new JSONObject().put("success", true).put("result", executeCommand(json));
                    }
                }
                writeJson(s, resp);
            } catch (Throwable t) {
                Log.w(TAG, "bridge client error: " + t);
            }
        });
    }

    private static boolean isPhoneControl(JSONObject json) {
        return "phone_control".equals(json.optString("action")) || json.has("phone_action");
    }

    private static void writeJson(Socket s, JSONObject resp) throws java.io.IOException {
        byte[] respBytes = resp.toString().getBytes(StandardCharsets.UTF_8);
        OutputStream out = s.getOutputStream();
        out.write(("HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: " + respBytes.length + "\r\n" +
                "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(respBytes);
        out.flush();
    }

    /** 按字节读一行（去掉 \r\n），流结束返回 null；头部行限长 8KB。 */
    private static String readLineBytes(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(128);
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

    // ---------------- 令牌：仅容器内（应用私有目录）可读，防止设备上其他应用借本地端口操控手机 ----------------

    private volatile String bridgeToken;

    public String getBridgeToken() {
        String t = bridgeToken;
        if (t != null) return t;
        synchronized (this) {
            if (bridgeToken == null) {
                android.content.SharedPreferences sp = appContext.getSharedPreferences("pimet_bridge", Context.MODE_PRIVATE);
                String saved = sp.getString("token", null);
                if (TextUtils.isEmpty(saved)) {
                    saved = java.util.UUID.randomUUID().toString().replace("-", "");
                    sp.edit().putString("token", saved).apply();
                }
                bridgeToken = saved;
            }
            return bridgeToken;
        }
    }

    /** 把令牌写进容器 ~/.pi/agent/.pimet_bridge_token，供扩展读取（容器未安装时跳过）。 */
    private void writeTokenFile() {
        try {
            File rootfs = ProotManager.getRootfsDir(appContext);
            if (!rootfs.exists()) return;
            File f = new File(rootfs, "root/.pi/agent/.pimet_bridge_token");
            String tok = getBridgeToken();
            if (f.isFile() && tok.equals(readFile(f) != null ? readFile(f).trim() : null)) return;
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
                fos.write(tok.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            Log.w(TAG, "write token failed: " + t);
        }
    }

    /**
     * 文件 IPC 轮询监听 (/root/.pi/agent/app_control.json)
     * HTTP 不通时扩展才会写这个文件（只用于桌宠/界面控制，手机自动化只走 HTTP）。
     * 启动时文件若是 10 秒前的旧指令则忽略，避免每次打开应用重放上次的指令。
     */
    private void startFileWatcher() {
        fileWatchThread = new Thread(() -> {
            boolean first = true;
            int tick = 0;
            while (isRunning.get()) {
                try {
                    Thread.sleep(600);
                    File rootfs = ProotManager.getRootfsDir(appContext);
                    if (!rootfs.exists()) continue;
                    if (tick++ % 50 == 0) writeTokenFile(); // 约 30 秒确认一次令牌文件（容器重置后补写）
                    File bridgeFile = new File(rootfs, "root/.pi/agent/app_control.json");
                    if (!bridgeFile.exists()) {
                        first = false;
                        continue;
                    }
                    long curTs = bridgeFile.lastModified();
                    if (first) {
                        first = false;
                        if (System.currentTimeMillis() - curTs > 10_000) {
                            lastFileTimestamp = curTs;
                            continue;
                        }
                    }
                    if (curTs > lastFileTimestamp) {
                        lastFileTimestamp = curTs;
                        String content = readFile(bridgeFile);
                        if (!TextUtils.isEmpty(content)) {
                            JSONObject json = new JSONObject(content);
                            if (!isPhoneControl(json)) executeCommand(json);
                        }
                    }
                } catch (InterruptedException ie) {
                    break;
                } catch (Throwable t) {
                    Log.w(TAG, "file watcher: " + t);
                }
            }
        }, "PiMet-AppBridge-FileWatcher");
        fileWatchThread.setDaemon(true);
        fileWatchThread.start();
    }

    /** 容器内截图目录（宿主路径）与对应的容器内路径。 */
    private File screenshotDir() {
        return new File(ProotManager.getRootfsDir(appContext), "root/.pi/agent/screenshots");
    }

    private static void pruneScreenshots(File dir, int keep) {
        File[] fs = dir.listFiles((d, n) -> n.startsWith("screen-") && n.endsWith(".jpg"));
        if (fs == null || fs.length <= keep) return;
        java.util.Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = keep; i < fs.length; i++) //noinspection ResultOfMethodCallIgnored
            fs[i].delete();
    }

    /**
     * 处理手机自动化控制指令 (phone_control)。
     * 操作名（phone_action / sub_action）：status、inspect(read_screen/screen)、find、tap(click)、long_press(long_click)、
     * swipe、scroll、input(type)、key(press_key)、back/home/recents、enter、launch(open_app)、screenshot、wait_for(wait)、wait_idle。
     * 每个结果都有 success，失败时 error 说明原因。
     */
    public JSONObject executePhoneControl(JSONObject json) {
        try {
            String subAction = json.optString("phone_action", json.optString("sub_action", "")).trim();
            if (TextUtils.isEmpty(subAction)) {
                if (json.has("click") || json.has("tap")) subAction = "tap";
                else if (json.has("swipe")) subAction = "swipe";
                else if (json.has("inspect") || json.has("screen")) subAction = "inspect";
                else if (json.has("input") || json.has("text")) subAction = "input";
                else if (json.has("key") || json.has("press")) subAction = "key";
            }
            String act = subAction.toLowerCase(java.util.Locale.ROOT);
            if ("status".equals(act)) return PiMetAccessibilityService.statusJson(appContext);

            PiMetAccessibilityService service = PiMetAccessibilityService.getInstance();
            if (service == null) {
                JSONObject res = new JSONObject();
                res.put("success", false);
                boolean enabled = PiMetAccessibilityService.isEnabledInSettings(appContext);
                res.put("error", enabled
                        ? "PiMet 无障碍服务已开启但未连接（常见于应用更新后或被系统清理）。请到 系统设置 → 无障碍 → PiMet 手机自动化，关闭后重新打开。"
                        : "PiMet 无障碍服务未开启。请到 系统设置 → 无障碍 → 已下载的应用 → PiMet 手机自动化 开启（开关灰色时：应用信息 → 右上角 ⋮ → 允许受限制的设置）。");
                res.put("need_permission", true);
                return res;
            }

            synchronized (PHONE_LOCK) {
                switch (act) {
                    case "inspect":
                    case "read_screen":
                    case "screen":
                    case "dump":
                        return service.inspectScreen(json);

                    case "find":
                        return service.find(PiMetAccessibilityService.Query.from(json, json.has("text") ? "text" : "target"));

                    case "tap":
                    case "click":
                    case "long_press":
                    case "long_click": {
                        boolean longPress = act.startsWith("long");
                        PiMetAccessibilityService.Query q = PiMetAccessibilityService.Query.from(json, "text");
                        if (q.text == null) q.text = emptyToNull(json.optString("click_text", json.optString("target", "")));
                        boolean hasXY = json.has("x") && json.has("y");
                        if (!q.isEmpty()) {
                            JSONObject r = service.clickNode(q, longPress);
                            if (r.optBoolean("success") || !hasXY) return r;
                        }
                        if (!hasXY) {
                            return err("缺少点击目标：传 target/text（按文字）、id、desc，或 x,y 坐标");
                        }
                        float x = (float) json.optDouble("x", -1);
                        float y = (float) json.optDouble("y", -1);
                        return longPress ? service.longClickAt(x, y, json.optInt("duration", 800)) : service.tapAt(x, y);
                    }

                    case "swipe": {
                        String dir = json.optString("direction", "").toLowerCase(java.util.Locale.ROOT);
                        int dur = json.optInt("duration", 300);
                        if (!TextUtils.isEmpty(dir)) {
                            return service.swipeDirection(dir, (float) json.optDouble("distance", 0.5), dur);
                        }
                        double fromX = json.optDouble("from_x", json.optDouble("x1", -1));
                        double fromY = json.optDouble("from_y", json.optDouble("y1", -1));
                        double toX = json.optDouble("to_x", json.optDouble("x2", -1));
                        double toY = json.optDouble("to_y", json.optDouble("y2", -1));
                        if (fromX < 0 || fromY < 0 || toX < 0 || toY < 0) {
                            return err("滑动需要 direction（up/down/left/right）或 from_x/from_y/to_x/to_y 坐标");
                        }
                        return service.swipeGesture((float) fromX, (float) fromY, (float) toX, (float) toY, dur);
                    }

                    case "scroll": {
                        PiMetAccessibilityService.Query within = PiMetAccessibilityService.Query.from(json, "target");
                        return service.scroll(json.optString("direction", "down"), within);
                    }

                    case "input":
                    case "type":
                    case "set_text": {
                        String text = json.optString("text", null);
                        String target = json.optString("target", json.optString("target_id", json.optString("id", "")));
                        JSONObject r = service.input(text, target, json.optBoolean("append", false));
                        if (r.optBoolean("success") && json.optBoolean("submit", false)) {
                            JSONObject e = service.pressEnter();
                            r.put("submit", e);
                        }
                        return r;
                    }

                    case "key":
                    case "press_key":
                    case "global":
                        return service.systemKey(json.optString("key", json.optString("press", "")));

                    case "back":
                    case "home":
                    case "recents":
                    case "notifications":
                    case "quick_settings":
                    case "lock":
                        return service.systemKey(act);

                    case "enter":
                    case "submit":
                        return service.pressEnter();

                    case "launch":
                    case "open_app":
                    case "launch_app":
                    case "open": {
                        String app = json.optString("package", json.optString("app", json.optString("target", json.optString("text", ""))));
                        return service.launchApp(app);
                    }

                    case "screenshot": {
                        File dir = screenshotDir();
                        String name = "screen-" + System.currentTimeMillis() + ".jpg";
                        int maxSide = Math.max(480, Math.min(2400, json.optInt("max_side", 1280)));
                        JSONObject r = service.screenshot(new File(dir, name), maxSide);
                        if (r.optBoolean("success")) {
                            r.put("path", "/root/.pi/agent/screenshots/" + name);
                            r.put("message", "截图已保存到容器内 /root/.pi/agent/screenshots/" + name + "，可用 read 工具查看；坐标按 scale 换算回屏幕像素");
                            pruneScreenshots(dir, 5);
                        }
                        return r;
                    }

                    case "wait_for":
                    case "wait": {
                        PiMetAccessibilityService.Query q = PiMetAccessibilityService.Query.from(json, json.has("text") ? "text" : "target");
                        if (q.isEmpty()) {
                            // 纯等待：wait(timeout) 等同于等待界面空闲
                            return service.waitIdle(json.optLong("idle", 500), json.optLong("timeout", 3000));
                        }
                        return service.waitFor(q, json.optBoolean("gone", false), json.optLong("timeout", 5000));
                    }

                    case "wait_idle":
                        return service.waitIdle(json.optLong("idle", 500), json.optLong("timeout", 5000));

                    default:
                        return err("未知自动化操作: " + subAction + "（可用 inspect / find / tap / long_press / swipe / scroll / input / key / launch / screenshot / wait_for / wait_idle / status）");
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "executePhoneControl error", t);
            return err("执行失败: " + t);
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private static JSONObject err(String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("success", false);
            o.put("error", msg);
        } catch (Throwable ignored) {}
        return o;
    }

    /**
     * 执行由 AI Agent 或自然语言解析器下发的宿主控制指令
     */
    public String executeCommand(JSONObject json) {
        StringBuffer feedback = new StringBuffer();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        Runnable task = () -> {
          try {
            try {
                // 1. 桌宠尺寸调节
                if (json.has("size")) {
                    int size = json.optInt("size", -1);
                    if (size >= 32 && size <= 160) {
                        PetRegistry.setIntPref(appContext, PetRegistry.KEY_PET_SIZE, size);
                        if (attachedActivity != null) {
                            attachedActivity.applyPetParams();
                        }
                        feedback.append("已调整桌宠尺寸为 ").append(size).append("dp; ");
                    }
                }

                // 2. 角色形象皮肤切换
                if (json.has("character")) {
                    String charDir = json.optString("character").trim();
                    if (!TextUtils.isEmpty(charDir)) {
                        PetRegistry.setPetDir(appContext, charDir);
                        if (attachedActivity != null && attachedActivity.floatingPetView != null) {
                            attachedActivity.floatingPetView.setPetDir(charDir);
                            attachedActivity.showPetBubble("主人，我已经换装成「" + charDir + "」啦！✨");
                        }
                        feedback.append("已切换形象为 ").append(charDir).append("; ");
                    }
                }

                // 3. 动作播放
                if (json.has("action")) {
                    String action = json.optString("action").trim();
                    if (!TextUtils.isEmpty(action) && !action.equals("control_desktop_pet") && !action.equals("control_app_ui") && !action.equals("phone_control")) {
                        if (attachedActivity != null && attachedActivity.floatingPetView != null) {
                            attachedActivity.floatingPetView.playOneShot(action);
                        }
                        if (PetOverlayService.isRunning && PetOverlayService.getInstance() != null) {
                            PetOverlayService svc = PetOverlayService.getInstance();
                            if (svc.getPetView() != null) svc.getPetView().playOneShot(action);
                        }
                        feedback.append("已播放动作 ").append(action).append("; ");
                    }
                }

                // 4. 对话气泡说话
                if (json.has("bubble_text")) {
                    String bubble = json.optString("bubble_text").trim();
                    if (!TextUtils.isEmpty(bubble)) {
                        if (attachedActivity != null) {
                            attachedActivity.showPetBubble(bubble);
                        }
                        if (PetOverlayService.isRunning && PetOverlayService.getInstance() != null) {
                            PetOverlayService.getInstance().showProactiveBubble(bubble);
                        }
                        feedback.append("已展示气泡: ").append(bubble).append("; ");
                    }
                }

                // 5. 物理参数
                if (json.has("bounce")) {
                    int bounce = Math.max(0, Math.min(100, json.optInt("bounce", 65)));
                    PetRegistry.setIntPref(appContext, PetRegistry.KEY_BOUNCE, bounce);
                    feedback.append("物理弹性已设为 ").append(bounce).append("%; ");
                }
                if (json.has("friction")) {
                    int friction = Math.max(0, Math.min(100, json.optInt("friction", 92)));
                    PetRegistry.setIntPref(appContext, PetRegistry.KEY_FRICTION, friction);
                    feedback.append("空气摩擦力已设为 ").append(friction).append("%; ");
                }

                // 6. 全局桌宠开关
                if (json.has("global_pet_enabled")) {
                    boolean enable = json.optBoolean("global_pet_enabled");
                    toggleGlobalPetService(enable);
                    feedback.append(enable ? "已开启全局系统桌宠; " : "已关闭全局系统桌宠; ");
                }

                // 7. 切换应用界面选项卡
                if (json.has("switch_tab")) {
                    String tab = json.optString("switch_tab").toLowerCase().trim();
                    int tabIdx = -1;
                    if ("launch".equals(tab) || "0".equals(tab) || "home".equals(tab)) tabIdx = 0;
                    else if ("web".equals(tab) || "1".equals(tab) || "piweb".equals(tab)) tabIdx = 1;
                    else if ("plugins".equals(tab) || "2".equals(tab)) tabIdx = 2;
                    else if ("settings".equals(tab) || "3".equals(tab)) tabIdx = 3;
                    else if ("terminal".equals(tab)) {
                        if (attachedActivity != null) attachedActivity.openTerminalInWorkbench();
                        feedback.append("已展开终端界面; ");
                    }

                    if (tabIdx >= 0 && attachedActivity != null) {
                        attachedActivity.switchTab(tabIdx);
                        feedback.append("已切换至选项卡: ").append(tab).append("; ");
                    }
                }

                // 8. 服务端口重置
                if (json.has("ports")) {
                    JSONObject ports = json.optJSONObject("ports");
                    if (ports != null) {
                        if (ports.has("piweb")) {
                            int p = ports.optInt("piweb");
                            if (p > 0) PetRegistry.setPiWebPort(appContext, p);
                        }
                        if (ports.has("operit")) {
                            int p = ports.optInt("operit");
                            if (p > 0) PetRegistry.setOperitPort(appContext, p);
                        }
                        if (ports.has("clawbench")) {
                            int p = ports.optInt("clawbench");
                            if (p > 0) PetRegistry.setClawbenchPort(appContext, p);
                        }
                        if (ports.has("rikka")) {
                            int p = ports.optInt("rikka");
                            if (p > 0) PetRegistry.setRikkaPort(appContext, p);
                        }
                        if (attachedActivity != null) {
                            attachedActivity.refreshSettingsPortFields();
                        }
                        feedback.append("服务端口配置已更新; ");
                    }
                }

            } catch (Throwable t) {
                Log.w(TAG, "executeCommand main thread error", t);
                feedback.append("执行出错: ").append(t.getMessage()).append("; ");
            }
          } finally {
            done.countDown();
          }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            // 在主线程执行界面操作，等待其完成以便把真实结果返回给 Agent（最多 3 秒）
            mainHandler.post(task);
            try {
                done.await(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        return feedback.length() == 0 ? "指令已处理" : feedback.toString();
    }

    private void toggleGlobalPetService(boolean enable) {
        if (enable) {
            if (!PetOverlayService.isRunning()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(appContext)) {
                    Toast.makeText(appContext, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show();
                } else {
                    Intent intent = new Intent(appContext, PetOverlayService.class);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        appContext.startForegroundService(intent);
                    } else {
                        appContext.startService(intent);
                    }
                    if (attachedActivity != null) attachedActivity.updatePetDisplay(false);
                }
            }
        } else {
            if (PetOverlayService.isRunning()) {
                Intent intent = new Intent(appContext, PetOverlayService.class);
                appContext.stopService(intent);
                if (attachedActivity != null) attachedActivity.updatePetDisplay(true);
            }
        }
    }

    /**
     * 在对话框中自然语言即时指令识别
     * 如果用户输入了明确的控制指令，直接执行并返回提示文案
     */
    public String tryHandleNaturalLanguage(String input) {
        if (TextUtils.isEmpty(input)) return null;
        String text = input.trim();

        try {
            // 1. 尺寸调整 (如 "放大桌宠", "缩小桌宠", "桌宠大小96")
            if (text.contains("放大桌宠") || text.contains("桌宠变大") || text.contains("变大一点")) {
                int cur = PetRegistry.getIntPref(appContext, PetRegistry.KEY_PET_SIZE, PetRegistry.DEFAULT_PET_SIZE);
                int next = Math.min(150, cur + 16);
                JSONObject cmd = new JSONObject().put("size", next).put("bubble_text", "我变大啦~ (" + next + "dp)");
                executeCommand(cmd);
                return "好的主人，已将桌宠放大至 " + next + " dp！✨";
            }
            if (text.contains("缩小桌宠") || text.contains("桌宠变小") || text.contains("变小一点")) {
                int cur = PetRegistry.getIntPref(appContext, PetRegistry.KEY_PET_SIZE, PetRegistry.DEFAULT_PET_SIZE);
                int next = Math.max(36, cur - 16);
                JSONObject cmd = new JSONObject().put("size", next).put("bubble_text", "我缩小啦~ (" + next + "dp)");
                executeCommand(cmd);
                return "好的主人，已将桌宠缩小至 " + next + " dp！✨";
            }

            // 2. 动作互动
            if (text.contains("跳舞") || text.contains("跳个舞")) {
                JSONObject cmd = new JSONObject().put("action", "dancing").put("bubble_text", "开心地跳起舞来啦~ 💃");
                executeCommand(cmd);
                return "收到！桌宠正在为您跳舞 💃";
            }
            if (text.contains("翻跟斗") || text.contains("跳一下") || text.contains("蹦一下")) {
                JSONObject cmd = new JSONObject().put("action", "jumping").put("bubble_text", "耶！翻了个大跟斗~ ✨");
                executeCommand(cmd);
                return "好嘞！翻了个漂亮的跟斗 🦘";
            }
            if (text.contains("招手") || text.contains("挥手") || text.contains("打招呼")) {
                JSONObject cmd = new JSONObject().put("action", "waving").put("bubble_text", "主人好呀~ 👋 ฅ'ω'ฅ");
                executeCommand(cmd);
                return "桌宠向您热情挥手打招呼 👋";
            }

            // 3. 换装形象
            if (text.contains("女仆") || text.contains("猫娘")) {
                JSONObject cmd = new JSONObject().put("character", "cat_maid").put("bubble_text", "主人，女仆猫娘前来为您服务~ ฅ'ω'ฅ");
                executeCommand(cmd);
                return "已将桌宠形象切换为「猫娘女仆」！🐾";
            }
            if (text.contains("小初") || text.contains("eva")) {
                JSONObject cmd = new JSONObject().put("character", "eva").put("bubble_text", "小初已就绪，随时为您服务！💙");
                executeCommand(cmd);
                return "已将桌宠形象切换为「小初」！";
            }
            if (text.contains("派蒙") || text.contains("paimon")) {
                JSONObject cmd = new JSONObject().put("character", "paimon").put("bubble_text", "应急食品派蒙报到！🍴");
                executeCommand(cmd);
                return "已将桌宠形象切换为「派蒙」！";
            }

            // 4. 全局桌宠
            if (text.contains("开启全局桌宠") || text.contains("打开全局桌宠") || text.contains("开启悬浮桌宠")) {
                JSONObject cmd = new JSONObject().put("global_pet_enabled", true);
                executeCommand(cmd);
                return "已开启系统全局桌面桌宠！🌐";
            }
            if (text.contains("关闭全局桌宠") || text.contains("隐藏全局桌宠")) {
                JSONObject cmd = new JSONObject().put("global_pet_enabled", false);
                executeCommand(cmd);
                return "已关闭系统全局桌面桌宠。";
            }

            // 5. 界面导航
            if (text.contains("切换到终端") || text.contains("打开终端")) {
                JSONObject cmd = new JSONObject().put("switch_tab", "terminal");
                executeCommand(cmd);
                return "已为您切换展开 Linux 终端！💻";
            }
            if (text.contains("切换到工作台") || text.contains("打开工作台") || text.contains("进入工作台")) {
                JSONObject cmd = new JSONObject().put("switch_tab", "web");
                executeCommand(cmd);
                return "已为您切换至 Pi-Web 全功能工作台！🚀";
            }
            if (text.contains("切换到设置") || text.contains("打开设置")) {
                JSONObject cmd = new JSONObject().put("switch_tab", "settings");
                executeCommand(cmd);
                return "已为您切换至系统设置页面！⚙️";
            }

        } catch (Throwable ignored) {}

        return null;
    }

    private static String readFile(File file) {
        try (FileInputStream fis = new FileInputStream(file);
             InputStreamReader isr = new InputStreamReader(fis, StandardCharsets.UTF_8);
             BufferedReader reader = new BufferedReader(isr)) {
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = reader.readLine()) != null) {
                sb.append(l).append("\n");
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
