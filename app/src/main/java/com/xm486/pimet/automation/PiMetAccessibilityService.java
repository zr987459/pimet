package com.xm486.pimet.automation;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PiMet 原生无障碍自动化服务 (升级版 v1.5.0)
 * 具备：
 * 1. 多窗口全量穿透读取 (getWindows() 融合所有前台应用与弹窗，彻底杜绝漏字)
 * 2. 智能语义节点回溯精准点击 (clickByText / clickById，自动找可点击父框架)
 * 3. 增强版物理手势模拟 (100ms 黄金按压时长，避免高刷屏丢帧与防误触拦截)
 * 4. 文本输入与系统全局按键
 */
public class PiMetAccessibilityService extends AccessibilityService {

    private static final String TAG = "PiMet.Accessibility";
    private static volatile PiMetAccessibilityService instance;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "PiMet Accessibility Service connected successfully.");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (instance == this) {
            instance = null;
        }
        Log.i(TAG, "PiMet Accessibility Service destroyed.");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {
        Log.w(TAG, "PiMet Accessibility Service interrupted.");
    }

    public static PiMetAccessibilityService getInstance() {
        return instance;
    }

    public static boolean isRunning() {
        return instance != null;
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

    // ---------------- 屏幕控件读取 (多窗口全量穿透识别) ----------------

    /**
     * 遍历系统所有活动窗口 (包括主界面、弹窗 Dialog、键盘等)，全量抓取文字与框架
     */
    public JSONObject inspectScreen() {
        JSONObject res = new JSONObject();
        try {
            List<AccessibilityNodeInfo> roots = new ArrayList<>();

            // 1. 尝试多窗口穿透获取 (Android 5.0+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    List<AccessibilityWindowInfo> windows = getWindows();
                    if (windows != null && !windows.isEmpty()) {
                        for (AccessibilityWindowInfo win : windows) {
                            if (win != null) {
                                AccessibilityNodeInfo r = win.getRoot();
                                if (r != null) roots.add(r);
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }

            // 2. 兜底主活动窗口
            if (roots.isEmpty()) {
                AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
                if (activeRoot != null) roots.add(activeRoot);
            }

            if (roots.isEmpty()) {
                res.put("success", false);
                res.put("error", "未能获取到当前屏幕的前台窗口节点");
                return res;
            }

            JSONArray nodesArray = new JSONArray();
            Rect screenBounds = new Rect();

            for (AccessibilityNodeInfo r : roots) {
                parseNodeRecursive(r, nodesArray, screenBounds, 0);
            }

            res.put("success", true);
            res.put("nodeCount", nodesArray.length());
            res.put("nodes", nodesArray);

        } catch (Throwable t) {
            Log.e(TAG, "inspectScreen failed", t);
            try {
                res.put("success", false);
                res.put("error", t.getMessage());
            } catch (Throwable ignored) {}
        }
        return res;
    }

    private void parseNodeRecursive(AccessibilityNodeInfo node, JSONArray out, Rect tempRect, int depth) {
        if (node == null || depth > 25) return;

        try {
            node.getBoundsInScreen(tempRect);
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            CharSequence cls = node.getClassName();
            String viewId = node.getViewIdResourceName();
            boolean isClickable = node.isClickable();
            boolean isEditable = node.isEditable();
            boolean isVisible = node.isVisibleToUser();

            // 只要有文字、有描述、或者是输入框/可点击组件，均收录
            boolean hasContent = !TextUtils.isEmpty(text) || !TextUtils.isEmpty(desc);
            if (isVisible && (hasContent || isClickable || isEditable)) {
                // 如果当前节点不可点击，但在其直接子节点有内容时向上合并
                JSONObject obj = new JSONObject();
                if (!TextUtils.isEmpty(text)) obj.put("text", text.toString());
                if (!TextUtils.isEmpty(desc)) obj.put("desc", desc.toString());
                if (!TextUtils.isEmpty(viewId)) obj.put("id", viewId);
                if (cls != null) obj.put("class", cls.toString());

                obj.put("clickable", isClickable);
                obj.put("editable", isEditable);
                obj.put("bounds", new JSONArray(new int[]{tempRect.left, tempRect.top, tempRect.right, tempRect.bottom}));
                obj.put("centerX", tempRect.centerX());
                obj.put("centerY", tempRect.centerY());

                out.put(obj);
            }

            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    parseNodeRecursive(child, out, tempRect, depth + 1);
                    child.recycle();
                }
            }
        } catch (Throwable ignored) {}
    }

    // ---------------- 智能双轨点击 (坐标点击 + 语义节点向上回溯点击) ----------------

    /**
     * 语义级精准点击：根据文字内容查找控件，并向上溯源找到最外层可点击框架执行原生 ACTION_CLICK
     */
    public boolean clickByText(String targetText) {
        if (TextUtils.isEmpty(targetText)) return false;

        List<AccessibilityNodeInfo> roots = getAllWindowRoots();
        for (AccessibilityNodeInfo root : roots) {
            try {
                List<AccessibilityNodeInfo> matched = root.findAccessibilityNodeInfosByText(targetText);
                if (matched != null && !matched.isEmpty()) {
                    for (AccessibilityNodeInfo node : matched) {
                        if (performSmartClickOnNode(node)) {
                            return true;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    /**
     * 语义级精准点击：根据 View ID 查找并点击
     */
    public boolean clickById(String viewId) {
        if (TextUtils.isEmpty(viewId)) return false;

        List<AccessibilityNodeInfo> roots = getAllWindowRoots();
        for (AccessibilityNodeInfo root : roots) {
            try {
                List<AccessibilityNodeInfo> matched = root.findAccessibilityNodeInfosByViewId(viewId);
                if (matched != null && !matched.isEmpty()) {
                    for (AccessibilityNodeInfo node : matched) {
                        if (performSmartClickOnNode(node)) {
                            return true;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    /**
     * 智能执行节点点击：如果自身不可点击，则逐层向上追溯其父容器（Parent），找到第一个 isClickable 的控件触发原生点击；
     * 如果整条链均不可直接 ACTION_CLICK，则以其最外层边界中心坐标执行手势点击兜底！
     */
    private boolean performSmartClickOnNode(AccessibilityNodeInfo target) {
        if (target == null) return false;

        AccessibilityNodeInfo curr = target;
        while (curr != null) {
            if (curr.isClickable()) {
                boolean clicked = curr.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (clicked) return true;
            }
            curr = curr.getParent();
        }

        // 无法直接通过 Accessibility Action 点击，降级到取真实几何中心坐标模拟手势点击
        Rect rect = new Rect();
        target.getBoundsInScreen(rect);
        if (rect.width() > 0 && rect.height() > 0) {
            return click(rect.centerX(), rect.centerY());
        }
        return false;
    }

    private List<AccessibilityNodeInfo> getAllWindowRoots() {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                List<AccessibilityWindowInfo> windows = getWindows();
                if (windows != null) {
                    for (AccessibilityWindowInfo win : windows) {
                        if (win != null) {
                            AccessibilityNodeInfo r = win.getRoot();
                            if (r != null) roots.add(r);
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (roots.isEmpty()) {
            AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
            if (activeRoot != null) roots.add(activeRoot);
        }
        return roots;
    }

    // ---------------- 物理手势模拟 (针对高刷与防误触调优) ----------------

    /** 物理点击屏幕绝对坐标 (x, y) - 持续 100ms 确保高刷屏与定制 ROM 判定为有效点击 */
    public boolean click(float x, float y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;

        Path path = new Path();
        path.moveTo(x, y);
        // 时长调整为 100ms，契合高刷与防误触策略
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 100);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke);
        return dispatchGestureSync(builder.build());
    }

    /** 物理长按屏幕坐标 */
    public boolean longClick(float x, float y, int durationMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;

        Path path = new Path();
        path.moveTo(x, y);
        int dur = Math.max(600, Math.min(3000, durationMs > 0 ? durationMs : 1000));
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, dur);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke);
        return dispatchGestureSync(builder.build());
    }

    /** 物理滑动 */
    public boolean swipe(float fromX, float fromY, float toX, float toY, int durationMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;

        Path path = new Path();
        path.moveTo(fromX, fromY);
        path.lineTo(toX, toY);
        int dur = Math.max(120, Math.min(2000, durationMs > 0 ? durationMs : 350));
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, dur);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke);
        return dispatchGestureSync(builder.build());
    }

    private boolean dispatchGestureSync(GestureDescription gesture) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);

        boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                success.set(true);
                latch.countDown();
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                success.set(false);
                latch.countDown();
            }
        }, null);

        if (!dispatched) return false;

        try {
            latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}

        return success.get();
    }

    // ---------------- 文本输入与按键 ----------------

    public boolean inputText(String text, String targetTextOrId) {
        if (text == null) return false;

        List<AccessibilityNodeInfo> roots = getAllWindowRoots();
        for (AccessibilityNodeInfo root : roots) {
            try {
                AccessibilityNodeInfo targetNode = null;
                if (!TextUtils.isEmpty(targetTextOrId)) {
                    for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByViewId(targetTextOrId)) {
                        if (n != null && n.isEditable()) { targetNode = n; break; }
                    }
                    if (targetNode == null) {
                        for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByText(targetTextOrId)) {
                            if (n != null && n.isEditable()) { targetNode = n; break; }
                        }
                    }
                }

                if (targetNode == null) {
                    targetNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                }
                if (targetNode == null) {
                    targetNode = findFirstEditable(root);
                }

                if (targetNode != null) {
                    Bundle args = new Bundle();
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                    return targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private AccessibilityNodeInfo findFirstEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable() && node.isVisibleToUser()) return node;

        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo res = findFirstEditable(child);
            if (res != null) return res;
        }
        return null;
    }

    public boolean performSystemKey(String key) {
        if (key == null) return false;
        String k = key.trim().toLowerCase();
        switch (k) {
            case "home":
                return performGlobalAction(GLOBAL_ACTION_HOME);
            case "back":
                return performGlobalAction(GLOBAL_ACTION_BACK);
            case "recents":
            case "recent":
                return performGlobalAction(GLOBAL_ACTION_RECENTS);
            case "notifications":
            case "notification":
                return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
            case "quick_settings":
                return performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
            case "screenshot":
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    return performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT);
                }
                return false;
            default:
                Log.w(TAG, "Unknown system key: " + key);
                return false;
        }
    }
}
