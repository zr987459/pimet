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
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress("127.0.0.1", BRIDGE_PORT));
                Log.i(TAG, "AppBridge HTTP server listening on 127.0.0.1:" + BRIDGE_PORT);

                while (isRunning.get() && !serverSocket.isClosed()) {
                    Socket client = serverSocket.accept();
                    handleHttpClient(client);
                }
            } catch (Throwable t) {
                if (isRunning.get()) {
                    Log.w(TAG, "AppBridge HTTP server terminated: " + t.getMessage());
                }
            }
        }, "PiMet-AppBridge-Server");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private void handleHttpClient(Socket socket) {
        new Thread(() -> {
            try {
                socket.setSoTimeout(3000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String line = reader.readLine();
                if (line == null) {
                    socket.close();
                    return;
                }

                int contentLength = 0;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    String lower = line.toLowerCase();
                    if (lower.startsWith("content-length:")) {
                        try {
                            contentLength = Integer.parseInt(line.substring(15).trim());
                        } catch (Exception ignored) {}
                    }
                }

                String body = "";
                if (contentLength > 0) {
                    char[] buf = new char[contentLength];
                    int readTotal = 0;
                    while (readTotal < contentLength) {
                        int r = reader.read(buf, readTotal, contentLength - readTotal);
                        if (r == -1) break;
                        readTotal += r;
                    }
                    body = new String(buf, 0, readTotal);
                }

                String replyMsg = "OK";
                JSONObject replyJson = null;
                if (!TextUtils.isEmpty(body)) {
                    try {
                        JSONObject json = new JSONObject(body);
                        if ("phone_control".equals(json.optString("action")) || json.has("phone_action")) {
                            replyJson = executePhoneControl(json);
                        } else {
                            replyMsg = executeCommand(json);
                        }
                    } catch (Throwable t) {
                        replyMsg = "JSON Error: " + t.getMessage();
                    }
                }

                JSONObject resp = new JSONObject();
                if (replyJson != null) {
                    resp = replyJson;
                } else {
                    resp.put("success", true);
                    resp.put("result", replyMsg);
                }
                String respStr = resp.toString();

                byte[] respBytes = respStr.getBytes(StandardCharsets.UTF_8);
                OutputStream out = socket.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: " + respBytes.length + "\r\n" +
                        "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(respBytes);
                out.flush();
                socket.close();
            } catch (Throwable ignored) {}
        }).start();
    }

    /**
     * 文件 IPC 轮询监听 (/root/.pi/agent/app_control.json)
     * 作为 HTTP 连接的坚实备用通道
     */
    private void startFileWatcher() {
        fileWatchThread = new Thread(() -> {
            while (isRunning.get()) {
                try {
                    Thread.sleep(600);
                    File rootfs = ProotManager.getRootfsDir(appContext);
                    if (!rootfs.exists()) continue;
                    File bridgeFile = new File(rootfs, "root/.pi/agent/app_control.json");
                    if (bridgeFile.exists()) {
                        long curTs = bridgeFile.lastModified();
                        if (curTs > lastFileTimestamp) {
                            lastFileTimestamp = curTs;
                            String content = readFile(bridgeFile);
                            if (!TextUtils.isEmpty(content)) {
                                JSONObject json = new JSONObject(content);
                                executeCommand(json);
                            }
                        }
                    }
                } catch (InterruptedException ie) {
                    break;
                } catch (Throwable ignored) {}
            }
        }, "PiMet-AppBridge-FileWatcher");
        fileWatchThread.setDaemon(true);
        fileWatchThread.start();
    }

    /**
     * 处理手机自动化控制指令 (phone_control)
     */
    public JSONObject executePhoneControl(JSONObject json) {
        JSONObject res = new JSONObject();
        try {
            if (!PiMetAccessibilityService.isRunning()) {
                res.put("success", false);
                res.put("error", "PiMet 无障碍服务未开启。请先在安卓系统设置 -> 无障碍 -> 开启 PiMet 手机自动化服务！");
                res.put("need_permission", true);
                return res;
            }

            PiMetAccessibilityService service = PiMetAccessibilityService.getInstance();
            if (service == null) {
                res.put("success", false);
                res.put("error", "AccessibilityService 实例不可用");
                return res;
            }

            String subAction = json.optString("phone_action", json.optString("sub_action", "")).trim();
            if (TextUtils.isEmpty(subAction)) {
                if (json.has("click") || json.has("tap")) subAction = "tap";
                else if (json.has("swipe")) subAction = "swipe";
                else if (json.has("inspect") || json.has("screen")) subAction = "inspect";
                else if (json.has("input") || json.has("text")) subAction = "input";
                else if (json.has("key") || json.has("press")) subAction = "key";
            }

            switch (subAction.toLowerCase()) {
                case "inspect":
                case "read_screen":
                case "screen": {
                    return service.inspectScreen();
                }

                case "tap":
                case "click": {
                    String targetText = json.optString("text", json.optString("click_text", json.optString("target", "")));
                    String targetId = json.optString("target_id", json.optString("id", ""));

                    // 1. 优先尝试语义级/文字级精准点击 (自动溯源可点击父容器)
                    if (!TextUtils.isEmpty(targetText)) {
                        boolean ok = service.clickByText(targetText);
                        if (ok) {
                            res.put("success", true);
                            res.put("message", "已通过语义节点溯源成功点击目标: [" + targetText + "]");
                            return res;
                        }
                    }
                    if (!TextUtils.isEmpty(targetId)) {
                        boolean ok = service.clickById(targetId);
                        if (ok) {
                            res.put("success", true);
                            res.put("message", "已通过 ID 节点溯源成功点击: " + targetId);
                            return res;
                        }
                    }

                    // 2. 坐标点击降级
                    float x = (float) json.optDouble("x", -1);
                    float y = (float) json.optDouble("y", -1);
                    if (x < 0 || y < 0) {
                        res.put("success", false);
                        res.put("error", "未找到匹配文本且未提供有效的坐标参数 (x=" + x + ", y=" + y + ")");
                        return res;
                    }
                    boolean ok = service.click(x, y);
                    res.put("success", ok);
                    res.put("message", ok ? "已成功点击坐标 (" + x + ", " + y + ")" : "手势下发失败或被取消");
                    return res;
                }

                case "long_press":
                case "long_click": {
                    float x = (float) json.optDouble("x", -1);
                    float y = (float) json.optDouble("y", -1);
                    int dur = json.optInt("duration", 800);
                    if (x < 0 || y < 0) {
                        res.put("success", false);
                        res.put("error", "坐标参数错误: x=" + x + ", y=" + y);
                        return res;
                    }
                    boolean ok = service.longClick(x, y, dur);
                    res.put("success", ok);
                    res.put("message", ok ? "已成功长按坐标 (" + x + ", " + y + ")" : "长按手势下发失败");
                    return res;
                }

                case "swipe": {
                    float fromX = (float) json.optDouble("from_x", json.optDouble("x1", -1));
                    float fromY = (float) json.optDouble("from_y", json.optDouble("y1", -1));
                    float toX = (float) json.optDouble("to_x", json.optDouble("x2", -1));
                    float toY = (float) json.optDouble("to_y", json.optDouble("y2", -1));
                    int dur = json.optInt("duration", 300);

                    // 便捷方向参数: direction: up / down / left / right
                    String dir = json.optString("direction", "").toLowerCase();
                    if (!TextUtils.isEmpty(dir)) {
                        int sw = appContext.getResources().getDisplayMetrics().widthPixels;
                        int sh = appContext.getResources().getDisplayMetrics().heightPixels;
                        float cx = sw / 2.0f;
                        float cy = sh / 2.0f;
                        if ("up".equals(dir)) {
                            fromX = cx; toX = cx; fromY = cy + (sh * 0.25f); toY = cy - (sh * 0.25f);
                        } else if ("down".equals(dir)) {
                            fromX = cx; toX = cx; fromY = cy - (sh * 0.25f); toY = cy + (sh * 0.25f);
                        } else if ("left".equals(dir)) {
                            fromY = cy; toY = cy; fromX = cx + (sw * 0.3f); toX = cx - (sw * 0.3f);
                        } else if ("right".equals(dir)) {
                            fromY = cy; toY = cy; fromX = cx - (sw * 0.3f); toX = cx + (sw * 0.3f);
                        }
                    }

                    if (fromX < 0 || fromY < 0 || toX < 0 || toY < 0) {
                        res.put("success", false);
                        res.put("error", "滑动坐标参数错误");
                        return res;
                    }
                    boolean ok = service.swipe(fromX, fromY, toX, toY, dur);
                    res.put("success", ok);
                    res.put("message", ok ? "滑动完成 (" + fromX + "," + fromY + ") -> (" + toX + "," + toY + ")" : "滑动失败");
                    return res;
                }

                case "input":
                case "type": {
                    String text = json.optString("text", "");
                    String target = json.optString("target", json.optString("target_id", ""));
                    boolean ok = service.inputText(text, target);
                    res.put("success", ok);
                    res.put("message", ok ? "已成功注入文字: " + text : "未能找到可输入文字的输入框");
                    return res;
                }

                case "key":
                case "press_key": {
                    String key = json.optString("key", "");
                    boolean ok = service.performSystemKey(key);
                    res.put("success", ok);
                    res.put("message", ok ? "已执行按键: " + key : "按键执行失败或不受支持");
                    return res;
                }

                default:
                    res.put("success", false);
                    res.put("error", "未知自动化操作: " + subAction);
                    return res;
            }

        } catch (Throwable t) {
            Log.e(TAG, "executePhoneControl error", t);
            try {
                res.put("success", false);
                res.put("error", t.getMessage());
            } catch (Throwable ignored) {}
        }
        return res;
    }

    /**
     * 执行由 AI Agent 或自然语言解析器下发的宿主控制指令
     */
    public String executeCommand(JSONObject json) {
        StringBuilder feedback = new StringBuilder();
        mainHandler.post(() -> {
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
                    if (!TextUtils.isEmpty(action) && !action.equals("control_desktop_pet") && !action.equals("control_app_ui")) {
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
            }
        });

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
