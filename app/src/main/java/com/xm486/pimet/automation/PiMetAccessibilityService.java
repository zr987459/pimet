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

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PiMet 原生无障碍自动化服务 (PiMetAccessibilityService)
 * 专为 AI Agent (桌宠/子代理/Pi Extension) 提供手机全局操控：
 * 1. 结构化读取当前屏幕所有可见控件、文本与坐标 (JSON)
 * 2. 模拟手指手势：点击 (tap)、滑动 (swipe)、长按 (longPress)
 * 3. 焦点文字输入 (setText)
 * 4. 全局物理操作：Home、Back、Recents、下拉通知栏、截屏
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
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 无需繁重事件监听，按需读取当前窗口
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "PiMet Accessibility Service interrupted.");
    }

    public static PiMetAccessibilityService getInstance() {
        return instance;
    }

    /** 检查无障碍服务是否已激活 */
    public static boolean isRunning() {
        return instance != null;
    }

    /** 引导用户打开系统无障碍设置页 */
    public static void openAccessibilitySettings(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to open accessibility settings", t);
        }
    }

    // ---------------- 屏幕控件读取 (Inspection) ----------------

    /** 遍历前台活动窗口所有节点，生成层级 JSON 报告 */
    public JSONObject inspectScreen() {
        JSONObject res = new JSONObject();
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                res.put("success", false);
                res.put("error", "No active window or accessibility node found");
                return res;
            }

            JSONArray nodesArray = new JSONArray();
            Rect screenBounds = new Rect();
            parseNodeRecursive(root, nodesArray, screenBounds, 0);

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
        if (node == null || depth > 20) return;

        try {
            node.getBoundsInScreen(tempRect);
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            CharSequence cls = node.getClassName();
            String viewId = node.getViewIdResourceName();
            boolean isClickable = node.isClickable();
            boolean isEditable = node.isEditable();
            boolean isVisible = node.isVisibleToUser();

            // 仅收录可见或有语义/可交互的节点以节省 Token
            if (isVisible && (isClickable || isEditable || !TextUtils.isEmpty(text) || !TextUtils.isEmpty(desc))) {
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

    // ---------------- 手势模拟 (Gestures) ----------------

    /** 点击屏幕指定绝对坐标 (x, y) */
    public boolean click(float x, float y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;

        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 50);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke);
        return dispatchGestureSync(builder.build());
    }

    /** 长按屏幕指定绝对坐标 (x, y) */
    public boolean longClick(float x, float y, int durationMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;

        Path path = new Path();
        path.moveTo(x, y);
        int dur = Math.max(500, Math.min(3000, durationMs > 0 ? durationMs : 800));
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, dur);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke);
        return dispatchGestureSync(builder.build());
    }

    /** 模拟物理滑动 (从 x1, y1 平滑滑动至 x2, y2) */
    public boolean swipe(float fromX, float fromY, float toX, float toY, int durationMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;

        Path path = new Path();
        path.moveTo(fromX, fromY);
        path.lineTo(toX, toY);
        int dur = Math.max(100, Math.min(2000, durationMs > 0 ? durationMs : 300));
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

    // ---------------- 文字输入 (Text Input) ----------------

    /** 向当前焦点输入框填入文字，或按文本匹配目标输入框后注入文字 */
    public boolean inputText(String text, String targetTextOrId) {
        if (text == null) return false;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;

        AccessibilityNodeInfo targetNode = null;
        try {
            if (!TextUtils.isEmpty(targetTextOrId)) {
                // 先尝试按 View ID 查找
                for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByViewId(targetTextOrId)) {
                    if (n != null && n.isEditable()) {
                        targetNode = n;
                        break;
                    }
                }
                // 再尝试按 Text 匹配
                if (targetNode == null) {
                    for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByText(targetTextOrId)) {
                        if (n != null && n.isEditable()) {
                            targetNode = n;
                            break;
                        }
                    }
                }
            }

            // 找不到特定目标则寻找当前 Focus 的 Editable 节点
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
        } catch (Throwable t) {
            Log.e(TAG, "inputText error", t);
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

    // ---------------- 全局按键控制 (Global Actions) ----------------

    /** 执行全局系统按键 */
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
