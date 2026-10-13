package com.xm486.pimet.automation;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * PiMet 原生无障碍自动化服务。
 *
 * 所有操作都在调用方线程（AppBridge 的请求线程，非主线程）同步执行并带超时；
 * 每个操作返回 JSON：{success, message | error, ...}，失败原因原样返回给调用方，不静默失败。
 *
 * 能力：多窗口读屏（紧凑节点 JSON）、按文字/ID/描述/坐标点击与长按、输入文字（SET_TEXT，失败回退剪贴板粘贴）、
 * 回车提交、滑动/滚动、全局按键、启动应用、截图（Android 11+）、等待元素出现/消失、等待界面空闲。
 */
public class PiMetAccessibilityService extends AccessibilityService {

    private static final String TAG = "PiMet.Accessibility";
    private static volatile PiMetAccessibilityService instance;

    /** 最近一次界面事件时间（uptime ms），用于 wait_idle。 */
    private volatile long lastEventAt = 0;
    /** 最近一次窗口切换所在包名。 */
    private volatile String lastPackage = "";

    private static final int DEFAULT_MAX_NODES = 300;
    private static final int MAX_TEXT_LEN = 120;
    private static final int MAX_DEPTH = 80;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        lastEventAt = SystemClock.uptimeMillis();
        Log.i(TAG, "connected");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
        Log.i(TAG, "destroyed");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        lastEventAt = SystemClock.uptimeMillis();
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.getPackageName() != null) {
            lastPackage = event.getPackageName().toString();
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "interrupted");
    }

    public static PiMetAccessibilityService getInstance() {
        return instance;
    }

    /** 服务已连接（系统已绑定，可执行操作）。 */
    public static boolean isRunning() {
        return instance != null;
    }

    /** 系统设置里是否已打开本服务（打开 ≠ 已连接：被厂商杀掉或应用更新后可能是打开但未连接）。 */
    public static boolean isEnabledInSettings(Context context) {
        try {
            String enabled = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(enabled)) return false;
            ComponentName me = new ComponentName(context, PiMetAccessibilityService.class);
            String full = me.flattenToString();
            String shortName = me.flattenToShortString();
            for (String s : enabled.split(":")) {
                if (s.equalsIgnoreCase(full) || s.equalsIgnoreCase(shortName)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** 0 = 未开启，1 = 已开启但未连接，2 = 已连接可用。 */
    public static int getState(Context context) {
        if (isRunning()) return 2;
        return isEnabledInSettings(context) ? 1 : 0;
    }

    /** 给设置页与 AI 的一句话状态。 */
    public static String describeState(Context context) {
        switch (getState(context)) {
            case 2: return "已连接，可操控手机";
            case 1: return "已开启但未连接（关闭后重新打开该服务，或重启手机）";
            default: return "未开启无障碍服务";
        }
    }

    public static JSONObject statusJson(Context context) {
        JSONObject o = new JSONObject();
        try {
            int st = getState(context);
            o.put("success", st == 2);
            o.put("enabled", st >= 1);
            o.put("connected", st == 2);
            o.put("message", describeState(context));
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("screenshot_supported", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R);
            PiMetAccessibilityService s = instance;
            if (s != null) {
                Point p = s.screenSize();
                o.put("screen", new JSONArray(Arrays.asList(p.x, p.y)));
                o.put("package", s.currentPackage());
            }
        } catch (Throwable ignored) {}
        return o;
    }

    public static void openAccessibilitySettings(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to open accessibility settings", t);
        }
    }

    /** 打开本应用的「应用信息」页（Android 13+ 旁加载应用需在此「允许受限制的设置」后才能开启无障碍）。 */
    public static void openAppDetails(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to open app details", t);
        }
    }

    // ======================= 基础工具 =======================

    private static JSONObject ok(String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("success", true);
            if (msg != null) o.put("message", msg);
        } catch (Throwable ignored) {}
        return o;
    }

    private static JSONObject fail(String err) {
        JSONObject o = new JSONObject();
        try {
            o.put("success", false);
            o.put("error", err);
        } catch (Throwable ignored) {}
        return o;
    }

    /** 屏幕真实像素尺寸（含状态栏/导航栏，与手势和 getBoundsInScreen 同一坐标系）。 */
    public Point screenSize() {
        Point p = new Point();
        try {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Rect b = wm.getCurrentWindowMetrics().getBounds();
                p.set(b.width(), b.height());
            } else {
                //noinspection deprecation
                wm.getDefaultDisplay().getRealSize(p);
            }
        } catch (Throwable t) {
            p.set(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
        }
        return p;
    }

    public String currentPackage() {
        try {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null && r.getPackageName() != null) return r.getPackageName().toString();
        } catch (Throwable ignored) {}
        return lastPackage;
    }

    /**
     * 所有可交互窗口的根节点：活动窗口在前；默认跳过本应用自己的悬浮窗（桌宠、菜单），
     * 除非本应用就是当前前台（includeSelf 或窗口处于活动/焦点状态）。窗口切换瞬间取不到时重试。
     */
    private List<AccessibilityNodeInfo> getRoots(boolean includeSelf) {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        String self = getPackageName();
        for (int attempt = 0; attempt < 4 && roots.isEmpty(); attempt++) {
            if (attempt > 0) SystemClock.sleep(120);
            AccessibilityNodeInfo active = null;
            try {
                active = getRootInActiveWindow();
            } catch (Throwable ignored) {}
            if (active != null) roots.add(active);
            try {
                List<AccessibilityWindowInfo> windows = getWindows();
                if (windows != null) {
                    for (AccessibilityWindowInfo win : windows) {
                        if (win == null) continue;
                        if (win.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
                        AccessibilityNodeInfo r = win.getRoot();
                        if (r == null) continue;
                        if (active != null && r.equals(active)) continue;
                        boolean isSelf = r.getPackageName() != null && self.contentEquals(r.getPackageName());
                        if (isSelf && !includeSelf && !win.isActive() && !win.isFocused()) continue;
                        roots.add(r);
                    }
                }
            } catch (Throwable ignored) {}
        }
        return roots;
    }

    private static String clip(CharSequence cs) {
        if (cs == null) return null;
        String s = cs.toString().replace('\n', ' ').trim();
        if (s.isEmpty()) return null;
        return s.length() > MAX_TEXT_LEN ? s.substring(0, MAX_TEXT_LEN) + "…" : s;
    }

    private static String shortClass(CharSequence cls) {
        if (cls == null) return null;
        String s = cls.toString();
        int i = s.lastIndexOf('.');
        return i >= 0 ? s.substring(i + 1) : s;
    }

    // ======================= 读屏 =======================

    /** 兼容旧调用。 */
    public JSONObject inspectScreen() {
        return inspectScreen(null);
    }

    /**
     * 读屏：紧凑节点列表。每个节点含 text/desc/hint/id/class、bounds [l,t,r,b]、centerX/centerY，
     * 以及仅在为真时出现的 clickable/editable/scrollable/checked/focused。
     * 可点击但自身无文字的容器会合并子节点文字到 label，便于直接按文字点击。
     * opts: max_nodes（默认 300）、include_self（包含本应用悬浮窗）。
     */
    public JSONObject inspectScreen(JSONObject opts) {
        try {
            int maxNodes = opts != null ? Math.max(20, Math.min(1500, opts.optInt("max_nodes", DEFAULT_MAX_NODES))) : DEFAULT_MAX_NODES;
            boolean includeSelf = opts != null && opts.optBoolean("include_self", false);
            List<AccessibilityNodeInfo> roots = getRoots(includeSelf);
            if (roots.isEmpty()) {
                return fail("取不到当前屏幕的窗口节点（锁屏、安全页面如支付/密码页会屏蔽读取，或界面正在切换，请稍后重试）");
            }
            Point screen = screenSize();
            Rect screenRect = new Rect(0, 0, screen.x, screen.y);
            JSONArray out = new JSONArray();
            int[] total = {0};
            for (AccessibilityNodeInfo r : roots) {
                walk(r, out, screenRect, 0, maxNodes, total);
            }
            JSONObject res = new JSONObject();
            res.put("success", true);
            res.put("package", currentPackage());
            res.put("screen", new JSONArray(Arrays.asList(screen.x, screen.y)));
            res.put("nodeCount", out.length());
            if (total[0] > out.length()) {
                res.put("truncated", true);
                res.put("hint", "节点过多已截断，可用 find 按文字查找，或传 max_nodes 调大上限");
            }
            res.put("nodes", out);
            return res;
        } catch (Throwable t) {
            Log.e(TAG, "inspectScreen failed", t);
            return fail("读屏失败: " + t);
        }
    }

    private void walk(AccessibilityNodeInfo node, JSONArray out, Rect screen, int depth, int max, int[] total) {
        if (node == null || depth > MAX_DEPTH) return;
        try {
            if (!node.isVisibleToUser()) return;
            Rect b = new Rect();
            node.getBoundsInScreen(b);
            boolean onScreen = b.width() > 0 && b.height() > 0 && Rect.intersects(b, screen);
            if (onScreen) {
                String text = clip(node.getText());
                String desc = clip(node.getContentDescription());
                String hint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? clip(node.getHintText()) : null;
                if (hint != null && hint.equals(text)) hint = null;
                String id = node.getViewIdResourceName();
                boolean clickable = node.isClickable() || node.isLongClickable();
                boolean editable = node.isEditable();
                boolean scrollable = node.isScrollable();
                boolean checkable = node.isCheckable();
                if (text != null || desc != null || hint != null || clickable || editable || scrollable || checkable) {
                    total[0]++;
                    if (out.length() < max) {
                        JSONObject o = new JSONObject();
                        if (text != null) o.put("text", text);
                        if (desc != null) o.put("desc", desc);
                        if (hint != null) o.put("hint", hint);
                        if (text == null && desc == null && clickable) {
                            String label = collectLabel(node, 0, new StringBuilder()).toString().trim();
                            if (!label.isEmpty()) o.put("label", label.length() > 60 ? label.substring(0, 60) + "…" : label);
                        }
                        if (!TextUtils.isEmpty(id)) o.put("id", id);
                        String cls = shortClass(node.getClassName());
                        if (cls != null) o.put("class", cls);
                        if (clickable) o.put("clickable", true);
                        if (editable) o.put("editable", true);
                        if (scrollable) o.put("scrollable", true);
                        if (checkable) o.put("checked", node.isChecked());
                        if (editable && node.isFocused()) o.put("focused", true);
                        if (!node.isEnabled()) o.put("enabled", false);
                        o.put("bounds", new JSONArray(Arrays.asList(b.left, b.top, b.right, b.bottom)));
                        o.put("centerX", b.centerX());
                        o.put("centerY", b.centerY());
                        out.put(o);
                    }
                }
            }
            int n = node.getChildCount();
            for (int i = 0; i < n; i++) {
                AccessibilityNodeInfo c = node.getChild(i);
                if (c != null) walk(c, out, screen, depth + 1, max, total);
            }
        } catch (Throwable ignored) {
            // 节点在遍历中失效（界面刷新）——跳过该子树
        }
    }

    private StringBuilder collectLabel(AccessibilityNodeInfo node, int depth, StringBuilder sb) {
        if (node == null || depth > 6 || sb.length() > 60) return sb;
        try {
            int n = node.getChildCount();
            for (int i = 0; i < n && sb.length() <= 60; i++) {
                AccessibilityNodeInfo c = node.getChild(i);
                if (c == null) continue;
                CharSequence t = c.getText();
                if (TextUtils.isEmpty(t)) t = c.getContentDescription();
                if (!TextUtils.isEmpty(t)) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(t.toString().replace('\n', ' ').trim());
                }
                collectLabel(c, depth + 1, sb);
            }
        } catch (Throwable ignored) {}
        return sb;
    }

    // ======================= 查找 =======================

    /** 查找条件：text（文字/描述/提示，默认先精确后包含）、id（完整 com.x:id/y 或仅 y）、desc。 */
    public static final class Query {
        public String text, id, desc;
        public boolean exact;
        public int index;

        public static Query from(JSONObject j, String textKey) {
            Query q = new Query();
            q.text = emptyToNull(j.optString(textKey, ""));
            q.id = emptyToNull(j.optString("id", j.optString("target_id", "")));
            q.desc = emptyToNull(j.optString("desc", ""));
            q.exact = j.optBoolean("exact", false);
            q.index = Math.max(0, j.optInt("index", 0));
            return q;
        }

        public boolean isEmpty() {
            return text == null && id == null && desc == null;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            if (text != null) sb.append("text=\"").append(text).append("\" ");
            if (id != null) sb.append("id=").append(id).append(' ');
            if (desc != null) sb.append("desc=\"").append(desc).append("\" ");
            return sb.toString().trim();
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    /** 0 = 不匹配，1 = 包含匹配，2 = 精确匹配。 */
    private static int score(AccessibilityNodeInfo n, Query q) {
        int best = 2;
        if (q.id != null) {
            String vid = n.getViewIdResourceName();
            if (vid == null) return 0;
            if (!(vid.equals(q.id) || vid.endsWith(":id/" + q.id) || vid.endsWith("/" + q.id))) return 0;
        }
        if (q.desc != null) {
            int s = match(n.getContentDescription(), q.desc);
            if (s == 0) return 0;
            best = Math.min(best, s);
        }
        if (q.text != null) {
            int s = match(n.getText(), q.text);
            s = Math.max(s, match(n.getContentDescription(), q.text));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) s = Math.max(s, match(n.getHintText(), q.text));
            if (s == 0) return 0;
            best = Math.min(best, s);
        }
        return best;
    }

    private static int match(CharSequence cs, String want) {
        if (cs == null) return 0;
        String s = cs.toString().trim();
        if (s.isEmpty()) return 0;
        if (s.equals(want)) return 2;
        String a = s.toLowerCase(Locale.ROOT), b = want.toLowerCase(Locale.ROOT);
        if (a.equals(b)) return 2;
        return a.contains(b) ? 1 : 0;
    }

    /** 按条件查找可见节点：精确匹配在前，其后包含匹配；均按屏幕从上到下排序。 */
    public List<AccessibilityNodeInfo> findNodes(Query q, boolean includeSelf) {
        List<AccessibilityNodeInfo> exact = new ArrayList<>();
        List<AccessibilityNodeInfo> partial = new ArrayList<>();
        Point screen = screenSize();
        Rect screenRect = new Rect(0, 0, screen.x, screen.y);
        for (AccessibilityNodeInfo r : getRoots(includeSelf)) {
            collect(r, q, exact, partial, screenRect, 0);
        }
        List<AccessibilityNodeInfo> res = new ArrayList<>(exact);
        if (!q.exact) res.addAll(partial);
        return res;
    }

    private void collect(AccessibilityNodeInfo n, Query q, List<AccessibilityNodeInfo> exact,
                         List<AccessibilityNodeInfo> partial, Rect screen, int depth) {
        if (n == null || depth > MAX_DEPTH) return;
        try {
            if (!n.isVisibleToUser()) return;
            int s = score(n, q);
            if (s > 0) {
                Rect b = new Rect();
                n.getBoundsInScreen(b);
                if (b.width() > 0 && b.height() > 0 && Rect.intersects(b, screen)) {
                    (s == 2 ? exact : partial).add(n);
                }
            }
            int c = n.getChildCount();
            for (int i = 0; i < c; i++) collect(n.getChild(i), q, exact, partial, screen, depth + 1);
        } catch (Throwable ignored) {}
    }

    private static JSONObject describeNode(AccessibilityNodeInfo n) {
        JSONObject o = new JSONObject();
        try {
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            String t = clip(n.getText());
            String d = clip(n.getContentDescription());
            if (t != null) o.put("text", t);
            if (d != null) o.put("desc", d);
            if (n.getViewIdResourceName() != null) o.put("id", n.getViewIdResourceName());
            String cls = shortClass(n.getClassName());
            if (cls != null) o.put("class", cls);
            if (n.isClickable()) o.put("clickable", true);
            if (n.isEditable()) o.put("editable", true);
            o.put("bounds", new JSONArray(Arrays.asList(b.left, b.top, b.right, b.bottom)));
            o.put("centerX", b.centerX());
            o.put("centerY", b.centerY());
        } catch (Throwable ignored) {}
        return o;
    }

    public JSONObject find(Query q) {
        if (q.isEmpty()) return fail("find 需要 text / id / desc 之一");
        try {
            List<AccessibilityNodeInfo> list = findNodes(q, false);
            JSONArray arr = new JSONArray();
            for (int i = 0; i < list.size() && i < 30; i++) arr.put(describeNode(list.get(i)));
            JSONObject o = ok(list.isEmpty() ? "未找到 " + q : "找到 " + list.size() + " 个匹配");
            o.put("success", !list.isEmpty());
            if (list.isEmpty()) o.put("error", "未找到 " + q);
            o.put("count", list.size());
            o.put("nodes", arr);
            return o;
        } catch (Throwable t) {
            return fail("查找失败: " + t);
        }
    }

    // ======================= 点击 =======================

    /** 兼容旧调用。 */
    public boolean clickByText(String targetText) {
        Query q = new Query();
        q.text = emptyToNull(targetText);
        return q.text != null && clickNode(q, false).optBoolean("success");
    }

    /** 兼容旧调用。 */
    public boolean clickById(String viewId) {
        Query q = new Query();
        q.id = emptyToNull(viewId);
        return q.id != null && clickNode(q, false).optBoolean("success");
    }

    /**
     * 按条件点击：取第 index 个匹配；自身不可点则向上找可点击的父容器执行 ACTION_CLICK，
     * 都不行时在节点中心做手势点击。长按同理（ACTION_LONG_CLICK / 长按手势）。
     */
    public JSONObject clickNode(Query q, boolean longPress) {
        if (q.isEmpty()) return fail("缺少点击目标（text / id / desc 或 x,y）");
        List<AccessibilityNodeInfo> list = findNodes(q, false);
        if (list.isEmpty()) return fail("屏幕上未找到 " + q + "，可先 inspect 查看当前界面");
        if (q.index >= list.size()) return fail("只找到 " + list.size() + " 个匹配，index=" + q.index + " 超出范围");
        AccessibilityNodeInfo target = list.get(q.index);
        int action = longPress ? AccessibilityNodeInfo.ACTION_LONG_CLICK : AccessibilityNodeInfo.ACTION_CLICK;
        try {
            AccessibilityNodeInfo cur = target;
            int hops = 0;
            while (cur != null && hops < 8) {
                boolean can = longPress ? cur.isLongClickable() : cur.isClickable();
                if (can && cur.isEnabled() && cur.performAction(action)) {
                    JSONObject o = ok((longPress ? "已长按" : "已点击") + " " + q
                            + (hops > 0 ? "（第 " + hops + " 层可点击父容器）" : ""));
                    o.put("node", describeNode(target));
                    o.put("method", "action");
                    return o;
                }
                cur = cur.getParent();
                hops++;
            }
            Rect b = new Rect();
            target.getBoundsInScreen(b);
            Point s = screenSize();
            if (!b.intersect(0, 0, s.x, s.y) || b.width() <= 0 || b.height() <= 0) return fail("目标 " + q + " 不在屏幕可见区域，先滚动到可见再点");
            JSONObject g = longPress ? longClickAt(b.centerX(), b.centerY(), 800) : tapAt(b.centerX(), b.centerY());
            if (g.optBoolean("success")) {
                g.put("message", (longPress ? "已长按 " : "已点击 ") + q + "（坐标 " + b.centerX() + "," + b.centerY() + "）");
                g.put("node", describeNode(target));
                g.put("method", "gesture");
            }
            return g;
        } catch (Throwable t) {
            return fail("点击 " + q + " 失败: " + t);
        }
    }

    // ======================= 手势 =======================

    public boolean click(float x, float y) {
        return tapAt(x, y).optBoolean("success");
    }

    public boolean longClick(float x, float y, int durationMs) {
        return longClickAt(x, y, durationMs).optBoolean("success");
    }

    public boolean swipe(float fromX, float fromY, float toX, float toY, int durationMs) {
        return swipeGesture(fromX, fromY, toX, toY, durationMs).optBoolean("success");
    }

    private String checkPoint(float x, float y) {
        Point s = screenSize();
        if (x < 0 || y < 0 || x >= s.x || y >= s.y) {
            return "坐标 (" + (int) x + "," + (int) y + ") 超出屏幕范围 " + s.x + "x" + s.y + "（坐标为屏幕像素，与 inspect 的 bounds 相同）";
        }
        return null;
    }

    public JSONObject tapAt(float x, float y) {
        String err = checkPoint(x, y);
        if (err != null) return fail(err);
        Path p = new Path();
        p.moveTo(x, y);
        JSONObject r = dispatch(new GestureDescription.StrokeDescription(p, 0, 60), 60);
        if (r.optBoolean("success")) safePut(r, "message", "已点击坐标 (" + (int) x + "," + (int) y + ")");
        return r;
    }

    public JSONObject longClickAt(float x, float y, int durationMs) {
        String err = checkPoint(x, y);
        if (err != null) return fail(err);
        int dur = Math.max(500, Math.min(5000, durationMs > 0 ? durationMs : 800));
        Path p = new Path();
        p.moveTo(x, y);
        JSONObject r = dispatch(new GestureDescription.StrokeDescription(p, 0, dur), dur);
        if (r.optBoolean("success")) safePut(r, "message", "已长按坐标 (" + (int) x + "," + (int) y + ") " + dur + "ms");
        return r;
    }

    public JSONObject swipeGesture(float fromX, float fromY, float toX, float toY, int durationMs) {
        Point s = screenSize();
        fromX = clamp(fromX, 0, s.x - 1);
        toX = clamp(toX, 0, s.x - 1);
        fromY = clamp(fromY, 0, s.y - 1);
        toY = clamp(toY, 0, s.y - 1);
        int dur = Math.max(80, Math.min(3000, durationMs > 0 ? durationMs : 300));
        Path p = new Path();
        p.moveTo(fromX, fromY);
        p.lineTo(toX, toY);
        JSONObject r = dispatch(new GestureDescription.StrokeDescription(p, 0, dur), dur);
        if (r.optBoolean("success")) {
            safePut(r, "message", "滑动完成 (" + (int) fromX + "," + (int) fromY + ") → (" + (int) toX + "," + (int) toY + ")");
        }
        return r;
    }

    /** 方向滑动：up = 手指上滑（内容向下翻），down/left/right 同理。 */
    public JSONObject swipeDirection(String dir, float distanceRatio, int durationMs) {
        Point s = screenSize();
        float cx = s.x / 2f, cy = s.y / 2f;
        float ry = s.y * Math.max(0.1f, Math.min(0.45f, distanceRatio > 0 ? distanceRatio / 2f : 0.25f));
        float rx = s.x * Math.max(0.1f, Math.min(0.45f, distanceRatio > 0 ? distanceRatio / 2f : 0.3f));
        switch (dir) {
            case "up": return swipeGesture(cx, cy + ry, cx, cy - ry, durationMs);
            case "down": return swipeGesture(cx, cy - ry, cx, cy + ry, durationMs);
            case "left": return swipeGesture(cx + rx, cy, cx - rx, cy, durationMs);
            case "right": return swipeGesture(cx - rx, cy, cx + rx, cy, durationMs);
            default: return fail("direction 只能是 up / down / left / right");
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static void safePut(JSONObject o, String k, Object v) {
        try { o.put(k, v); } catch (Throwable ignored) {}
    }

    /** 同步下发手势：等待系统回调（手势时长 + 3s 超时）。不可在主线程调用。 */
    private JSONObject dispatch(GestureDescription.StrokeDescription stroke, int durationMs) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return fail("内部错误：手势不能在主线程同步等待");
        }
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>(null);
        boolean dispatched;
        try {
            dispatched = dispatchGesture(gesture, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription g) {
                    result.set("ok");
                    latch.countDown();
                }

                @Override
                public void onCancelled(GestureDescription g) {
                    result.set("cancelled");
                    latch.countDown();
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (Throwable t) {
            return fail("手势下发异常: " + t);
        }
        if (!dispatched) {
            return fail("系统拒绝了手势（无障碍服务可能已被系统断开，请在设置中关闭后重新打开）");
        }
        try {
            if (!latch.await(durationMs + 3000L, TimeUnit.MILLISECONDS)) {
                return fail("手势超时未完成");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail("手势等待被中断");
        }
        if ("ok".equals(result.get())) return ok(null);
        return fail("手势被取消（可能被新的触摸或系统弹窗打断）");
    }

    // ======================= 输入 =======================

    /** 兼容旧调用。 */
    public boolean inputText(String text, String targetTextOrId) {
        return input(text, targetTextOrId, false).optBoolean("success");
    }

    /**
     * 向输入框写入文字：目标可为 ID / 文字 / 提示文字；未指定则用当前焦点输入框，再退到第一个可编辑框。
     * 先 ACTION_SET_TEXT，失败则聚焦后经剪贴板粘贴。append=true 追加到现有内容后。
     */
    public JSONObject input(String text, String target, boolean append) {
        if (text == null) return fail("缺少要输入的 text");
        try {
            AccessibilityNodeInfo node = null;
            if (!TextUtils.isEmpty(target)) {
                Query q = new Query();
                q.id = target.contains(":id/") || target.matches("[A-Za-z0-9_]+") ? target : null;
                if (q.id != null) node = firstEditable(findNodes(q, false));
                if (node == null) {
                    q = new Query();
                    q.text = target;
                    for (AccessibilityNodeInfo n : findNodes(q, false)) {
                        AccessibilityNodeInfo e = n.isEditable() ? n : findEditable(n, 0);
                        if (e != null) { node = e; break; }
                    }
                }
                if (node == null) return fail("未找到可输入的目标「" + target + "」");
            }
            if (node == null) {
                for (AccessibilityNodeInfo r : getRoots(false)) {
                    AccessibilityNodeInfo f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                    if (f != null && f.isEditable()) { node = f; break; }
                }
            }
            if (node == null) {
                for (AccessibilityNodeInfo r : getRoots(false)) {
                    node = findEditable(r, 0);
                    if (node != null) break;
                }
            }
            if (node == null) return fail("当前界面没有可输入的输入框（可先点击输入框再输入）");

            String value = text;
            if (append && node.getText() != null && !isShowingHint(node)) value = node.getText() + text;

            if (!node.isFocused()) {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                JSONObject o = ok("已输入: " + text);
                o.put("node", describeNode(node));
                o.put("method", "set_text");
                return o;
            }
            // 回退：剪贴板粘贴（部分自绘输入框不支持 SET_TEXT）
            if (setClipboard(text)) {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                if (!append) {
                    Bundle sel = new Bundle();
                    CharSequence cur = node.getText();
                    sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0);
                    sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cur != null ? cur.length() : 0);
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
                }
                if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                    JSONObject o = ok("已通过剪贴板粘贴输入: " + text);
                    o.put("method", "paste");
                    return o;
                }
            }
            return fail("该输入框拒绝写入（SET_TEXT 与粘贴均失败），可尝试先点击输入框后再 input");
        } catch (Throwable t) {
            return fail("输入失败: " + t);
        }
    }

    private static boolean isShowingHint(AccessibilityNodeInfo n) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && n.isShowingHintText();
    }

    private static AccessibilityNodeInfo firstEditable(List<AccessibilityNodeInfo> list) {
        for (AccessibilityNodeInfo n : list) {
            if (n.isEditable()) return n;
            AccessibilityNodeInfo e = findEditable(n, 0);
            if (e != null) return e;
        }
        return null;
    }

    private static AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > MAX_DEPTH) return null;
        try {
            if (node.isEditable() && node.isVisibleToUser()) return node;
            int count = node.getChildCount();
            for (int i = 0; i < count; i++) {
                AccessibilityNodeInfo res = findEditable(node.getChild(i), depth + 1);
                if (res != null) return res;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean setClipboard(String text) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean okFlag = new AtomicBoolean(false);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("pimet", text));
                    okFlag.set(true);
                }
            } catch (Throwable ignored) {
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return okFlag.get();
    }

    /** 回车/提交：Android 11+ 对焦点输入框执行 IME 动作（搜索/发送/完成）。 */
    public JSONObject pressEnter() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return fail("Android 11 以下不支持无障碍回车，请点击界面上的搜索/发送按钮");
        }
        try {
            for (AccessibilityNodeInfo r : getRoots(false)) {
                AccessibilityNodeInfo f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (f != null && f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId())) {
                    return ok("已执行回车/提交");
                }
            }
            return fail("没有获得焦点的输入框，或该输入框不支持回车动作");
        } catch (Throwable t) {
            return fail("回车失败: " + t);
        }
    }

    // ======================= 滚动 =======================

    /**
     * 滚动：优先对可滚动控件执行 SCROLL_FORWARD/BACKWARD（down/right = 向后翻看更多内容），
     * 可用 text/id 指定在哪个列表里滚；找不到可滚动控件时退化为方向滑动。
     */
    public JSONObject scroll(String direction, Query within) {
        String dir = direction == null ? "down" : direction.toLowerCase(Locale.ROOT);
        boolean forward;
        switch (dir) {
            case "down": case "right": case "forward": forward = true; break;
            case "up": case "left": case "backward": forward = false; break;
            default: return fail("scroll 的 direction 只能是 up / down / left / right");
        }
        try {
            AccessibilityNodeInfo target = null;
            if (within != null && !within.isEmpty()) {
                for (AccessibilityNodeInfo n : findNodes(within, false)) {
                    AccessibilityNodeInfo cur = n;
                    int hops = 0;
                    while (cur != null && hops++ < 12 && !cur.isScrollable()) cur = cur.getParent();
                    if (cur != null && cur.isScrollable()) { target = cur; break; }
                }
            }
            if (target == null) {
                AccessibilityNodeInfo[] best = {null};
                int[] bestArea = {0};
                for (AccessibilityNodeInfo r : getRoots(false)) largestScrollable(r, best, bestArea, 0);
                target = best[0];
            }
            if (target != null) {
                boolean done;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && (dir.equals("left") || dir.equals("right") || dir.equals("up") || dir.equals("down"))) {
                    AccessibilityNodeInfo.AccessibilityAction a;
                    switch (dir) {
                        case "down": a = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN; break;
                        case "up": a = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP; break;
                        case "left": a = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT; break;
                        default: a = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT; break;
                    }
                    done = target.getActionList().contains(a) && target.performAction(a.getId());
                    if (!done) {
                        done = target.performAction(forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
                    }
                } else {
                    done = target.performAction(forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                            : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
                }
                if (done) {
                    SystemClock.sleep(250);
                    JSONObject o = ok("已向" + dirLabel(dir) + "滚动");
                    o.put("node", describeNode(target));
                    return o;
                }
            }
            // 退化为手势：查看下方内容 = 手指上滑
            String swipeDir = dir.equals("down") ? "up" : dir.equals("up") ? "down" : dir.equals("right") ? "left" : "right";
            JSONObject g = swipeDirection(swipeDir, 0.5f, 350);
            if (g.optBoolean("success")) g.put("message", "已用滑动手势向" + dirLabel(dir) + "翻页（未找到可滚动控件或控件拒绝滚动）");
            return g;
        } catch (Throwable t) {
            return fail("滚动失败: " + t);
        }
    }

    private static String dirLabel(String d) {
        switch (d) {
            case "up": return "上";
            case "left": return "左";
            case "right": return "右";
            default: return "下";
        }
    }

    private void largestScrollable(AccessibilityNodeInfo n, AccessibilityNodeInfo[] best, int[] bestArea, int depth) {
        if (n == null || depth > MAX_DEPTH) return;
        try {
            if (!n.isVisibleToUser()) return;
            if (n.isScrollable()) {
                Rect b = new Rect();
                n.getBoundsInScreen(b);
                int area = Math.max(0, b.width()) * Math.max(0, b.height());
                if (area > bestArea[0]) {
                    bestArea[0] = area;
                    best[0] = n;
                }
            }
            int c = n.getChildCount();
            for (int i = 0; i < c; i++) largestScrollable(n.getChild(i), best, bestArea, depth + 1);
        } catch (Throwable ignored) {}
    }

    // ======================= 全局按键 =======================

    public boolean performSystemKey(String key) {
        return systemKey(key).optBoolean("success");
    }

    public JSONObject systemKey(String key) {
        if (key == null || key.trim().isEmpty()) return fail("缺少 key（home / back / recents / notifications / quick_settings / lock / power / screenshot / enter）");
        String k = key.trim().toLowerCase(Locale.ROOT);
        int action;
        switch (k) {
            case "home": action = GLOBAL_ACTION_HOME; break;
            case "back": action = GLOBAL_ACTION_BACK; break;
            case "recents": case "recent": case "overview": action = GLOBAL_ACTION_RECENTS; break;
            case "notifications": case "notification": action = GLOBAL_ACTION_NOTIFICATIONS; break;
            case "quick_settings": case "quicksettings": action = GLOBAL_ACTION_QUICK_SETTINGS; break;
            case "power": case "power_dialog": action = GLOBAL_ACTION_POWER_DIALOG; break;
            case "split_screen": action = GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN; break;
            case "lock": case "lock_screen":
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return fail("锁屏需要 Android 9+");
                action = GLOBAL_ACTION_LOCK_SCREEN; break;
            case "screenshot":
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return fail("系统截图键需要 Android 9+");
                action = GLOBAL_ACTION_TAKE_SCREENSHOT; break;
            case "enter": case "submit": case "search":
                return pressEnter();
            default:
                return fail("不支持的按键: " + key + "（可用 home / back / recents / notifications / quick_settings / lock / power / screenshot / enter）");
        }
        boolean done = performGlobalAction(action);
        if (!done) return fail("系统未执行按键 " + k);
        SystemClock.sleep(150);
        return ok("已执行按键: " + k);
    }

    // ======================= 启动应用 =======================

    /** 按包名或应用名（精确优先，其次包含）启动应用。 */
    public JSONObject launchApp(String nameOrPackage) {
        if (TextUtils.isEmpty(nameOrPackage)) return fail("缺少应用名或包名（package / app）");
        String want = nameOrPackage.trim();
        try {
            PackageManager pm = getPackageManager();
            Intent launch = want.contains(".") ? pm.getLaunchIntentForPackage(want) : null;
            String label = want;
            if (launch == null) {
                Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> apps = pm.queryIntentActivities(q, 0);
                ResolveInfo exact = null, partial = null;
                String lw = want.toLowerCase(Locale.ROOT);
                for (ResolveInfo ri : apps) {
                    String l = String.valueOf(ri.loadLabel(pm)).trim();
                    String ll = l.toLowerCase(Locale.ROOT);
                    if (ll.equals(lw) || ri.activityInfo.packageName.equalsIgnoreCase(want)) { exact = ri; break; }
                    if (partial == null && (ll.contains(lw) || lw.contains(ll) && ll.length() >= 2)) partial = ri;
                }
                ResolveInfo pick = exact != null ? exact : partial;
                if (pick == null) return fail("未找到应用「" + want + "」（可传包名，如 com.tencent.mm）");
                launch = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClassName(pick.activityInfo.packageName, pick.activityInfo.name);
                label = String.valueOf(pick.loadLabel(pm)) + " (" + pick.activityInfo.packageName + ")";
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(launch);
            SystemClock.sleep(600);
            JSONObject o = ok("已启动 " + label);
            o.put("package", launch.getComponent() != null ? launch.getComponent().getPackageName() : launch.getPackage());
            return o;
        } catch (Throwable t) {
            return fail("启动应用失败: " + t);
        }
    }

    // ======================= 截图 =======================

    /**
     * 截图（Android 11+ 无障碍截图 API）：缩放到最长边 ≤ maxSide 后存为 JPEG 到 outFile。
     * 同一秒内连续截图会被系统拒绝，内部自动等 1.1s 重试一次。
     */
    public JSONObject screenshot(File outFile, int maxSide) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return fail("截图需要 Android 11 及以上（当前 API " + Build.VERSION.SDK_INT + "），可用 inspect 读取界面文字");
        }
        JSONObject r = screenshotOnce(outFile, maxSide);
        if (!r.optBoolean("success") && r.optInt("code", -1) == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
            SystemClock.sleep(1100);
            r = screenshotOnce(outFile, maxSide);
        }
        return r;
    }

    private JSONObject screenshotOnce(File outFile, int maxSide) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<JSONObject> res = new AtomicReference<>(fail("截图超时"));
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult sr) {
                    new Thread(() -> {
                        HardwareBuffer hb = null;
                        try {
                            hb = sr.getHardwareBuffer();
                            Bitmap hw = Bitmap.wrapHardwareBuffer(hb, sr.getColorSpace());
                            if (hw == null) throw new IllegalStateException("无法读取截图缓冲");
                            Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
                            hw.recycle();
                            int w = sw.getWidth(), h = sw.getHeight();
                            float scale = Math.min(1f, maxSide / (float) Math.max(w, h));
                            Bitmap outBmp = scale < 1f
                                    ? Bitmap.createScaledBitmap(sw, Math.round(w * scale), Math.round(h * scale), true)
                                    : sw;
                            File dir = outFile.getParentFile();
                            if (dir != null && !dir.exists()) dir.mkdirs();
                            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                                outBmp.compress(Bitmap.CompressFormat.JPEG, 80, fos);
                            }
                            JSONObject o = ok("截图已保存");
                            o.put("width", outBmp.getWidth());
                            o.put("height", outBmp.getHeight());
                            o.put("screen", new JSONArray(Arrays.asList(w, h)));
                            o.put("scale", scale);
                            if (outBmp != sw) outBmp.recycle();
                            sw.recycle();
                            res.set(o);
                        } catch (Throwable t) {
                            res.set(fail("截图处理失败: " + t));
                        } finally {
                            if (hb != null) try { hb.close(); } catch (Throwable ignored) {}
                            latch.countDown();
                        }
                    }, "pimet-shot").start();
                }

                @Override
                public void onFailure(int errorCode) {
                    JSONObject f = fail(screenshotError(errorCode));
                    safePut(f, "code", errorCode);
                    res.set(f);
                    latch.countDown();
                }
            });
        } catch (Throwable t) {
            return fail("截图失败: " + t);
        }
        try {
            latch.await(8, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return res.get();
    }

    private static String screenshotError(int code) {
        switch (code) {
            case ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS:
                return "无截图权限（需重新开启无障碍服务以授予截图能力）";
            case ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT:
                return "截图过于频繁（每秒最多一次）";
            case ERROR_TAKE_SCREENSHOT_SECURE_WINDOW:
                return "当前是安全页面（如支付、密码），系统禁止截图";
            case ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY:
                return "无效的显示屏";
            default:
                return "截图失败（系统错误码 " + code + "）";
        }
    }

    // ======================= 等待 =======================

    /** 等待元素出现（gone=true 则等待消失），超时返回失败。 */
    public JSONObject waitFor(Query q, boolean gone, long timeoutMs) {
        if (q.isEmpty()) return fail("wait_for 需要 text / id / desc 之一");
        long timeout = Math.max(200, Math.min(30000, timeoutMs));
        long end = SystemClock.uptimeMillis() + timeout;
        long start = SystemClock.uptimeMillis();
        while (true) {
            List<AccessibilityNodeInfo> list = findNodes(q, false);
            boolean present = !list.isEmpty();
            if (present != gone) {
                JSONObject o = ok((gone ? "已消失: " : "已出现: ") + q);
                safePut(o, "waited_ms", SystemClock.uptimeMillis() - start);
                if (present) safePut(o, "node", describeNode(list.get(0)));
                return o;
            }
            if (SystemClock.uptimeMillis() >= end) {
                return fail("等待 " + timeout + "ms 后仍" + (gone ? "存在 " : "未出现 ") + q);
            }
            SystemClock.sleep(250);
        }
    }

    /** 等待界面空闲：连续 idleMs 内无界面变化事件（最长 timeoutMs）。 */
    public JSONObject waitIdle(long idleMs, long timeoutMs) {
        long idle = Math.max(200, Math.min(5000, idleMs));
        long end = SystemClock.uptimeMillis() + Math.max(idle, Math.min(30000, timeoutMs));
        long start = SystemClock.uptimeMillis();
        while (SystemClock.uptimeMillis() < end) {
            if (SystemClock.uptimeMillis() - lastEventAt >= idle) {
                JSONObject o = ok("界面已空闲");
                safePut(o, "waited_ms", SystemClock.uptimeMillis() - start);
                return o;
            }
            SystemClock.sleep(100);
        }
        return fail("界面持续变化，等待空闲超时");
    }
}
