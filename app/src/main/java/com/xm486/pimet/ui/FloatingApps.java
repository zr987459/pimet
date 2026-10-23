package com.xm486.pimet.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.xm486.pimet.AttachmentPickerActivity;
import com.xm486.pimet.proot.ProotManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Pi 应用窗：悬浮的「定制网页」窗口。
 * <p>
 * 容器 /root/pimet-browser/ 决定这个浏览器的样子和功能，Pi 可以随时改：config.json（工具栏按钮、配色、首页）、
 * scripts/*.js（油猴风格用户脚本，按 @match 注入任意网站）、pages/&lt;id&gt;/index.html（自定义页面，可用 window.pimet）、home.html（自定义首页）。
 * 这里就会出现一个应用，点开在窗口里运行。可以有任意多个。页面里能用 window.pimet 调容器命令、
 * 给 Pi 发消息等（只对这个目录下的本地页面开放）。窗口本身也能当普通浏览器打开任意网址。
 */
public final class FloatingApps {

    private static final String TAG = "PiMet.Apps";
    private static final String PREFS = "pimet_floating_apps";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    public static final String APPS_DIR_IN_CONTAINER = "/root/pimet-browser";
    private static FloatingApps sInstance;

    /** 由 MainActivity 注册：把文字放进 Pi-Web 输入框，send=true 时直接发送 */
    public interface PiBridge {
        void askPi(String text, boolean send);
    }

    private static PiBridge sPiBridge;

    public static void setPiBridge(PiBridge bridge) {
        sPiBridge = bridge;
    }

    final Context ctx;
    private final WindowManager wm;
    private final WindowManager.LayoutParams lp;
    private final LinearLayout root;
    final FrameLayout webFrame;
    /** 当前显示的标签的 WebView（工具栏按钮、自定义按钮都作用于它） */
    WebView web;
    final List<Tab> tabs = new ArrayList<>();
    Tab active;
    private int tabSeq = 0;
    private final LinearLayout tabStrip;
    private final android.widget.HorizontalScrollView tabScroll;
    private final TextView resizeHandle;
    private int pageColor = Color.parseColor("#0D1117");
    /** 用户点了「停止 Pi」：之后所有 browser 请求都返回「已叫停」，直到用户点「允许 Pi」 */
    private static volatile boolean sPiStopped = false;
    /** BrowserAgent 正在分发模拟触摸：不要因此把悬浮窗设为可获得焦点（会抢走下层 App 的输入法） */
    static volatile boolean sAgentTouch = false;
    private final EditText urlField;
    private final File appsDir;   // /root/pimet-browser：config.json、scripts/、pages/、home.html
    private final File pagesDir;  // /root/pimet-browser/pages/<id>/index.html
    private final GradientDrawable rootBg;
    private final LinearLayout bar;
    private final LinearLayout customStrip;
    private final android.widget.HorizontalScrollView customScroll;
    private long configMtime = -1;
    /** 窗口是 Pi 刚打开的（用户还没看任何网页）：第一个 Pi 标签直接放到前台 */
    private boolean freshForAgent = false;
    /** 顶栏左侧的拖动柄；窗口最小化且 Pi 在后台操作时显示成闪烁的 🤖 */
    private TextView grip;
    /** 用户亲手点过 ✕：之后 Pi 再需要窗口时，悄悄建好并保持最小化，不再整窗弹出来 */
    private static volatile boolean sUserClosed = false;
    private JSONObject config = new JSONObject();
    boolean minimized = false;
    private boolean maximized = false;
    private int savedW, savedH, savedX, savedY;

    /** WebView <input type=file> 正在等待的文件选择回调。 */
    private static volatile ValueCallback<Uri[]> sPendingFileCallback;
    private static volatile WebView sPendingFileWebView;

    /** 取消并清空当前挂起的 WebView 文件选择回调，避免旧请求悬挂。 */
    private static void cancelPendingFileChooser() {
        ValueCallback<Uri[]> cb = sPendingFileCallback;
        sPendingFileCallback = null;
        sPendingFileWebView = null;
        AttachmentPickerActivity.sWebCallback = null;
        if (cb != null) {
            try {
                cb.onReceiveValue(null);
            } catch (Throwable ignored) {}
        }
    }

    public static void toggle(Context context) {
        MAIN.post(() -> {
            if (sInstance != null) sInstance.dismiss();
            else show(context, null);
        });
    }

    /** 打开应用窗；url 为空时显示应用列表 */
    public static void show(Context context, String url) {
        final Context app = context.getApplicationContext();
        MAIN.post(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(app)) {
                Toast.makeText(app, "需要悬浮窗权限才能打开浏览器窗口", Toast.LENGTH_SHORT).show();
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + app.getPackageName()));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    app.startActivity(i);
                } catch (Throwable ignored) {}
                return;
            }
            try {
                if (sInstance == null) sInstance = new FloatingApps(app);
                if (url == null || url.isEmpty()) sInstance.goHome();
                else sInstance.load(url);
            } catch (Throwable t) {
                Log.e(TAG, "show failed", t);
                Toast.makeText(app, "打开浏览器窗口失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    /** 准备应用目录和给 Pi 的说明（技能），可在后台线程调用 */
    public static void ensureScaffold(Context context) {
        try {
            File rootfs = ProotManager.getRootfsDir(context);
            if (rootfs == null || !new File(rootfs, "bin").exists()) return;
            File apps = new File(rootfs, APPS_DIR_IN_CONTAINER.substring(1));
            new File(apps, "pages").mkdirs();
            new File(apps, "scripts").mkdirs();
            new File(apps, "sites").mkdirs();   // 站点经验 notes.md 与可复用工具 tools/*.mjs
            File cfg = new File(apps, "config.json");
            if (!cfg.exists()) writeIfChanged(cfg, DEFAULT_CONFIG);
            // 旧版（beta.1）技能目录改名
            File old = new File(rootfs, "root/.pi/agent/skills/pimet-apps");
            if (old.isDirectory()) {
                File[] fs = old.listFiles();
                if (fs != null) for (File f : fs) f.delete();
                old.delete();
            }
            String guide = readAsset(context, "pimet-browser/SKILL.md");
            if (guide == null) return;
            File skillDir = new File(rootfs, "root/.pi/agent/skills/pimet-browser");
            skillDir.mkdirs();
            writeIfChanged(new File(skillDir, "SKILL.md"), guide);
            // 应用目录里也放一份，方便直接查看
            String body = guide.replaceFirst("(?s)^---.*?---\\s*", "");
            writeIfChanged(new File(apps, "README.md"), body);
        } catch (Throwable t) {
            Log.w(TAG, "ensureScaffold failed", t);
        }
    }

    // ------------------------------------------------------------------ 构建

    @SuppressLint({"ClickableViewAccessibility", "SetJavaScriptEnabled", "JavascriptInterface"})
    private FloatingApps(Context app) {
        this(app, false);
    }

    /**
     * quiet=true：给 Pi 用、但用户之前亲手关掉了窗口。先以全透明、不可触摸的方式加到屏幕上，让 WebView 完成一次布局
     * （否则页面视口是 0×0），随后立刻收成最小化的小条，再恢复不透明。用户只会看到一条最小化的标题栏。
     */
    private FloatingApps(Context app, boolean quiet) {
        this.ctx = app;
        this.wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        this.appsDir = new File(ProotManager.getRootfsDir(app), APPS_DIR_IN_CONTAINER.substring(1));
        this.pagesDir = new File(appsDir, "pages");
        if (!pagesDir.exists()) pagesDir.mkdirs();
        new Thread(() -> ensureScaffold(app), "pimet-apps-scaffold").start();

        DisplayMetrics dm = app.getResources().getDisplayMetrics();
        SharedPreferences sp = prefs();

        root = new LinearLayout(app);
        root.setOrientation(LinearLayout.VERTICAL);
        rootBg = new GradientDrawable();
        rootBg.setColor(Color.parseColor("#F5141418"));
        rootBg.setCornerRadius(dp(12));
        rootBg.setStroke(dp(1), Color.parseColor("#3A3F4B"));
        root.setBackground(rootBg);
        root.setClipToOutline(true);
        root.setPadding(dp(2), dp(2), dp(2), dp(2));

        // ---- 顶栏：拖动 · 后退 · 地址栏 · 刷新 · 应用列表 · 最大化 · 最小化 · 关闭
        bar = new LinearLayout(app);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(2), 0, dp(2), 0);

        grip = barBtn("⠿", "拖动（点按最小化）");
        grip.setOnTouchListener(new DragMover());
        bar.addView(grip, new LinearLayout.LayoutParams(dp(26), dp(36)));
        addBar(bar, "‹", "后退", v -> goBack());

        urlField = new EditText(app);
        urlField.setSingleLine(true);
        urlField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        urlField.setTextColor(Color.parseColor("#E6EDF3"));
        urlField.setHintTextColor(Color.parseColor("#6E7681"));
        urlField.setHint("输入网址或搜索");
        urlField.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlField.setSelectAllOnFocus(true);
        urlField.setPadding(dp(8), 0, dp(8), 0);
        GradientDrawable ub = new GradientDrawable();
        ub.setColor(Color.parseColor("#22272E"));
        ub.setCornerRadius(dp(8));
        urlField.setBackground(ub);
        urlField.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) setFocusable(true);
            return false;
        });
        urlField.setOnEditorActionListener((v, actionId, ev) -> {
            boolean go = actionId == EditorInfo.IME_ACTION_GO
                    || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN);
            if (go) {
                load(normalizeUrl(urlField.getText().toString()));
                hideIme();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(0, dp(28), 1f);
        ulp.setMargins(dp(2), 0, dp(2), 0);
        bar.addView(urlField, ulp);

        addBar(bar, "⟳", "刷新", v -> reload());
        addBar(bar, "⌂", "首页", v -> goHome());
        addBar(bar, "📐", "窗口大小", v -> cycleWindowSize());
        addBar(bar, "⤢", "最大化", v -> toggleMaximize());
        addBar(bar, "—", "最小化", v -> toggleMinimize());
        addBar(bar, "✕", "关闭", v -> {
            sUserClosed = true;
            dismiss();
        });
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));

        // ---- 标签栏：用户标签 / 🤖 Pi 标签 / ＋ / 接管 / 停止 Pi
        tabScroll = new android.widget.HorizontalScrollView(app);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabStrip = new LinearLayout(app);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        tabStrip.setGravity(Gravity.CENTER_VERTICAL);
        tabStrip.setPadding(dp(4), 0, dp(4), dp(2));
        tabScroll.addView(tabStrip);
        root.addView(tabScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));

        // ---- 自定义按钮栏（config.json 的 buttons，由 Pi 编辑）
        customScroll = new android.widget.HorizontalScrollView(app);
        customScroll.setHorizontalScrollBarEnabled(false);
        customStrip = new LinearLayout(app);
        customStrip.setOrientation(LinearLayout.HORIZONTAL);
        customStrip.setGravity(Gravity.CENTER_VERTICAL);
        customStrip.setPadding(dp(4), 0, dp(4), dp(2));
        customScroll.addView(customStrip);
        customScroll.setVisibility(View.GONE);
        root.addView(customScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));

        // ---- 网页：每个标签一个 WebView，叠在同一个 webFrame 里
        webFrame = new FrameLayout(app);
        // 右下角拖动调整大小
        resizeHandle = new TextView(app);
        resizeHandle.setText("◢");
        resizeHandle.setTextColor(Color.parseColor("#8B949E"));
        resizeHandle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        resizeHandle.setGravity(Gravity.CENTER);
        resizeHandle.setContentDescription("拖动调整大小");
        resizeHandle.setOnTouchListener(new DragResizer());
        webFrame.addView(resizeHandle, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.BOTTOM | Gravity.END));
        root.addView(webFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        switchTo(newTab(false));

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(
                sp.getInt("w", Math.round(dm.widthPixels * 0.96f)),
                sp.getInt("h", Math.round(dm.heightPixels * 0.62f)),
                type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = sp.getInt("x", Math.round(dm.widthPixels * 0.02f));
        lp.y = sp.getInt("y", Math.round(dm.heightPixels * 0.08f));
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        clampToScreen();

        // 点窗口外：让出焦点给下层（点窗口内见 createWebView）
        root.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_OUTSIDE) setFocusable(false);
            return false;
        });

        reloadConfigIfChanged();
        if (quiet) {
            lp.alpha = 0f;
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        wm.addView(root, lp);
        if (quiet) {
            MAIN.postDelayed(() -> {
                if (sInstance != this) return;
                if (!minimized) toggleMinimize();
                lp.alpha = 1f;
                lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                update();
            }, 450);
        }
    }

    // ------------------------------------------------------------------ 标签页

    /**
     * 一个标签 = 一个 WebView。用户标签是用户自己浏览用的；Pi 标签（🤖 Pi）是 Pi 默认操作的，不打扰用户正在看的页面。
     * 所有标签共用全局 CookieManager，所以 Pi 能用到用户的登录状态。
     * <p>
     * 可见性策略（后台标签怎么做真实触摸 / 截图）：
     * 平时只有当前标签 VISIBLE，其余 INVISIBLE —— INVISIBLE 保持布局尺寸，页面里的 innerWidth/坐标都正确，Chromium 也会暂停后台页面的渲染。
     * Pi 操作某个后台标签期间（agentBegin → agentEnd 之后 1.5 秒），把它临时设为 VISIBLE 并放在当前标签的下面：
     * 当前标签的 WebView 背景不透明，完全盖住它，用户看不到任何变化，触摸也只落在最上层；
     * 而它在渲染，所以宿主可以直接给它的 WebView 分发真实触摸事件、用 draw() 截图。
     * 这样既不用把 Pi 标签切到前台抢用户的画面，也不必退回 JS 点击（JS 点击只在窗口最小化时作为兜底）。
     * 后台标签不抢输入焦点：按键改用 JS 键盘事件（见 BrowserAgent.pressKey）。
     */
    static final class Tab {
        final String id;
        final boolean pi;
        WebView web;
        volatile String url = "";
        volatile String title = "";
        volatile boolean loading = false;
        volatile int progress = 100;
        volatile boolean closed = false;
        /** 同一标签上的 Pi 操作串行；不同标签可以并行 */
        final Object lock = new Object();
        int busy = 0;            // 只在主线程读写
        boolean agentVisible;    // 后台标签被 Pi 操作时临时可见（压在当前标签下面）

        Tab(String id, boolean pi) {
            this.id = id;
            this.pi = pi;
        }
    }

    private Tab newTab(boolean pi) {
        Tab t = new Tab("t" + (++tabSeq), pi);
        t.web = createWebView(t);
        t.web.setVisibility(View.INVISIBLE);
        webFrame.addView(t.web, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tabs.add(t);
        return t;
    }

    private void switchTo(Tab t) {
        if (t == null || t.closed) return;
        active = t;
        web = t.web;
        applyVisibility();
        String u = t.url;
        if (!urlField.hasFocus()) {
            urlField.setText("");
            showUrl(t, u);
        }
        refreshTabs();
    }

    boolean isActive(Tab t) {
        return t == active;
    }

    /** 当前标签在最上层；被 Pi 操作的后台标签 VISIBLE 但压在下面；其余 INVISIBLE */
    private void applyVisibility() {
        for (Tab x : tabs) {
            if (x.web == null) continue;
            x.web.setVisibility(x == active || x.agentVisible ? View.VISIBLE : View.INVISIBLE);
        }
        for (Tab x : tabs) if (x != active && x.agentVisible && x.web != null) x.web.bringToFront();
        if (active != null && active.web != null) active.web.bringToFront();
        resizeHandle.bringToFront();
        webFrame.invalidate();
    }

    private void closeTab(Tab t) {
        if (t == null || t.closed) return;
        WebView pendingWv = sPendingFileWebView;
        if (pendingWv != null && pendingWv == t.web) {
            cancelPendingFileChooser();
        }
        if (tabs.size() <= 1) {
            goHome(t, false);
            return;
        }
        int idx = tabs.indexOf(t);
        tabs.remove(t);
        t.closed = true;
        try {
            webFrame.removeView(t.web);
            t.web.stopLoading();
            t.web.destroy();
        } catch (Throwable ignored) {}
        t.web = null;
        if (active == t) {
            Tab next = null;
            for (Tab x : tabs) if (!x.pi) { next = x; break; }
            if (next == null) next = tabs.get(Math.max(0, Math.min(idx, tabs.size()) - 1));
            switchTo(next);
        } else {
            refreshTabs();
        }
    }

    private Tab lastPiTab() {
        for (int i = tabs.size() - 1; i >= 0; i--) if (tabs.get(i).pi) return tabs.get(i);
        return null;
    }

    private Tab findTab(String id) {
        for (Tab x : tabs) if (x.id.equals(id)) return x;
        return null;
    }

    /** BrowserAgent：按请求里的 tab 找标签。空 / "pi" = Pi 标签（没有就建）；"user" = 用户当前的标签；其它 = 标签 id */
    Tab agentTab(String spec) {
        spec = spec == null ? "" : spec.trim();
        if (spec.isEmpty() || spec.equalsIgnoreCase("pi")) {
            Tab t = lastPiTab();
            return t != null ? t : agentNewPiTab();
        }
        if (spec.equalsIgnoreCase("user")) {
            if (active != null && !active.pi) return active;
            for (Tab x : tabs) if (!x.pi) return x;
            Tab t = newTab(false);
            goHome(t, false);
            refreshTabs();
            return t;
        }
        return findTab(spec);
    }

    Tab agentNewPiTab() {
        Tab t = newTab(true);
        t.web.loadUrl("about:blank");
        // 窗口是 Pi 刚打开的（用户还没在看别的网页），就直接把 Pi 标签放到前台
        if (freshForAgent || active == null || (!active.pi && (active.url.isEmpty() || isLauncher(active.url)) && !userTouched)) {
            freshForAgent = false;
            switchTo(t);
        } else {
            refreshTabs();
        }
        return t;
    }

    boolean agentSwitch(String id) {
        Tab t = "user".equalsIgnoreCase(id) ? agentTab("user") : "pi".equalsIgnoreCase(id) ? lastPiTab() : findTab(id);
        if (t == null) return false;
        if (minimized) toggleMinimize();
        switchTo(t);
        return true;
    }

    /** 返回 null 表示成功，否则是错误信息。Pi 只能关 Pi 标签，不能关用户的标签 */
    String agentCloseTab(String id) {
        Tab t = id == null || id.isEmpty() || "pi".equalsIgnoreCase(id) ? lastPiTab() : findTab(id);
        if (t == null) return "没有标签 " + id;
        if (!t.pi) return "不能关闭用户的标签 " + t.id;
        closeTab(t);
        return null;
    }

    org.json.JSONArray agentTabsJson() throws Exception {
        org.json.JSONArray a = new org.json.JSONArray();
        for (Tab x : tabs) {
            a.put(new JSONObject().put("id", x.id).put("kind", x.pi ? "pi" : "user").put("active", x == active)
                    .put("busy", x.busy > 0).put("url", x.url).put("title", x.title));
        }
        return a;
    }

    String agentTabsText() {
        StringBuilder sb = new StringBuilder();
        for (Tab x : tabs) {
            sb.append(x.id).append(x.pi ? " 🤖Pi" : " 用户").append(x == active ? " [当前显示]" : "")
                    .append(x.busy > 0 ? " [操作中]" : "").append("  ").append(x.title.isEmpty() ? "" : x.title + "  ").append(x.url).append('\n');
        }
        return sb.toString().trim();
    }

    /** Pi 开始操作某个标签（主线程） */
    void agentBegin(Tab t) {
        if (t.closed) return;
        t.busy++;
        if (t != active && !t.agentVisible) {
            t.agentVisible = true;
            applyVisibility();
        }
        if (t.busy == 1) refreshTabs();
    }

    /** Pi 结束一次操作；连续操作之间不闪烁：1.5 秒内没有新操作才恢复 INVISIBLE、去掉「操作中」 */
    void agentEnd(Tab t) {
        t.busy = Math.max(0, t.busy - 1);
        if (t.busy > 0) return;
        MAIN.postDelayed(() -> {
            if (t.busy > 0 || t.closed) return;
            if (t.agentVisible) {
                t.agentVisible = false;
                applyVisibility();
            }
            refreshTabs();
        }, 1500);
    }

    private android.animation.ObjectAnimator busyPulse;

    private android.animation.ObjectAnimator gripPulse;

    /** 最小化时，Pi 在后台操作就把拖动柄换成闪烁的 🤖，让用户知道它在干活 */
    private void refreshGrip() {
        if (grip == null) return;
        boolean busy = false;
        for (Tab x : tabs) if (x.busy > 0) busy = true;
        boolean show = minimized && busy;
        grip.setText(show ? "🤖" : "⠿");
        if (show && gripPulse == null) {
            gripPulse = android.animation.ObjectAnimator.ofFloat(grip, "alpha", 1f, 0.35f);
            gripPulse.setDuration(600);
            gripPulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            gripPulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            gripPulse.start();
        } else if (!show && gripPulse != null) {
            gripPulse.cancel();
            gripPulse = null;
            grip.setAlpha(1f);
        }
    }

    private void refreshTabs() {
        refreshGrip();
        if (tabStrip == null) return;
        if (busyPulse != null) {
            busyPulse.cancel();
            busyPulse = null;
        }
        tabStrip.removeAllViews();
        JSONObject th = config.optJSONObject("theme");
        if (th == null) th = new JSONObject();
        int fg = color(th, "text", Color.parseColor("#C9D1D9"));
        int btnBg = color(th, "button", Color.parseColor("#22272E"));
        int piCount = 0;
        for (Tab x : tabs) if (x.pi) piCount++;
        for (Tab x : tabs) {
            String label;
            if (x.pi) {
                label = "🤖 Pi" + (piCount > 1 ? " " + x.id : "");
            } else {
                String tt = x.title;
                if (tt == null || tt.isEmpty() || isLauncher(x.url)) tt = isLauncher(x.url) || x.url.isEmpty() ? "首页" : x.url.replaceFirst("^https?://", "");
                if (tt.length() > 10) tt = tt.substring(0, 10) + "…";
                label = "🌐 " + tt;
            }
            TextView chip = chip(x.busy > 0 ? label + " ●" : label, x == active ? Color.WHITE : fg,
                    x == active ? Color.parseColor(x.pi ? "#1F6FEB" : "#30363D") : btnBg);
            chip.setOnClickListener(v -> {
                userTouched = true;
                switchTo(x);
            });
            chip.setOnLongClickListener(v -> {
                closeTab(x);
                Toast.makeText(ctx, "已关闭标签", Toast.LENGTH_SHORT).show();
                return true;
            });
            chip.setContentDescription((x.pi ? "Pi 标签 " : "标签 ") + x.id + "（长按关闭）");
            if (x.pi && x.busy > 0) {
                busyPulse = android.animation.ObjectAnimator.ofFloat(chip, "alpha", 1f, 0.45f);
                busyPulse.setDuration(600);
                busyPulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                busyPulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                busyPulse.start();
            }
            tabStrip.addView(chip, chipLp());
        }
        TextView plus = chip("＋", fg, btnBg);
        plus.setContentDescription("新标签");
        plus.setOnClickListener(v -> {
            userTouched = true;
            Tab t = newTab(false);
            switchTo(t);
            goHome(t, true);
        });
        tabStrip.addView(plus, chipLp());
        Tab pt = lastPiTab();
        if (pt != null && active != pt) {
            TextView take = chip("🖐 接管", fg, btnBg);
            take.setContentDescription("把 Pi 标签切到前台");
            take.setOnClickListener(v -> {
                userTouched = true;
                switchTo(pt);
            });
            tabStrip.addView(take, chipLp());
        }
        if (pt != null || sPiStopped) {
            TextView stop = sPiStopped ? chip("▶ 允许 Pi", Color.WHITE, Color.parseColor("#B62324")) : chip("⏹ 停止 Pi", fg, btnBg);
            stop.setContentDescription(sPiStopped ? "重新允许 Pi 操作浏览器" : "叫停 Pi 的浏览器操作");
            stop.setOnClickListener(v -> {
                sPiStopped = !sPiStopped;
                if (sPiStopped) for (Tab x : tabs) if (x.pi && x.web != null) x.web.stopLoading();
                Toast.makeText(ctx, sPiStopped ? "已叫停 Pi：它之后的浏览器操作都会被拒绝" : "已允许 Pi 继续操作浏览器", Toast.LENGTH_SHORT).show();
                refreshTabs();
            });
            tabStrip.addView(stop, chipLp());
        }
    }

    /** 用户自己点过标签栏（之后 Pi 新建标签就不再自动切到前台） */
    private boolean userTouched = false;

    private TextView chip(String text, int fg, int bg) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(fg);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(dp(9), 0, dp(9), 0);
        GradientDrawable cb = new GradientDrawable();
        cb.setColor(bg);
        cb.setCornerRadius(dp(12));
        t.setBackground(cb);
        return t;
    }

    private LinearLayout.LayoutParams chipLp() {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24));
        l.setMargins(0, 0, dp(6), 0);
        return l;
    }

    @SuppressLint({"ClickableViewAccessibility", "SetJavaScriptEnabled", "JavascriptInterface"})
    private WebView createWebView(Tab t) {
        WebView w = new WebView(ctx);
        WebSettings ws = w.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setSupportZoom(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE); // 应用由 Pi 随时改写，总是读最新文件
        w.setBackgroundColor(pageColor);
        w.addJavascriptInterface(new Bridge(t), "PiMetNative");
        w.setDownloadListener((url, ua, contentDisposition, mime, length) -> startDownload(t, url, ua, contentDisposition, mime));
        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String u = req.getUrl().toString();
                if (u.startsWith("http://") || u.startsWith("https://") || u.startsWith("file://")
                        || u.startsWith("about:") || u.startsWith("data:") || u.startsWith("blob:")) {
                    return false;
                }
                // Pi 标签里的网页不能自己拉起外部 App
                if (t.pi) return true;
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);
                } catch (Throwable ignored) {}
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                t.loading = true;
                t.url = url != null ? url : "";
                showUrl(t, url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                t.loading = false;
                t.url = url != null ? url : "";
                showUrl(t, url);
                String title = view.getTitle();
                if (title != null && !title.equals(t.title)) {
                    t.title = title;
                    refreshTabs();
                }
                if (isTrusted(url)) view.evaluateJavascript(HELPER_JS, null);
                reloadConfigIfChanged();
                injectUserScripts(view, url);
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                t.url = url != null ? url : "";
                showUrl(t, url);
            }
        });
        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                t.progress = newProgress;
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                t.title = title != null ? title : "";
                if (!t.pi) refreshTabs();
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams params) {
                cancelPendingFileChooser();

                if (filePathCallback == null) return false;

                final ValueCallback<Uri[]> mine = filePathCallback;
                sPendingFileCallback = mine;
                sPendingFileWebView = webView;

                String acceptTypes = null;
                boolean allowMultiple = false;
                if (params != null) {
                    try {
                        String[] types = params.getAcceptTypes();
                        if (types != null && types.length > 0) {
                            StringBuilder sb = new StringBuilder();
                            for (String tp : types) {
                                if (tp == null) continue;
                                String v = tp.trim();
                                if (v.isEmpty()) continue;
                                if (sb.length() > 0) sb.append(',');
                                sb.append(v);
                            }
                            acceptTypes = sb.toString();
                        }
                    } catch (Throwable ignored) {}
                    try {
                        allowMultiple = params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE;
                    } catch (Throwable ignored) {}
                }

                final Intent intent = AttachmentPickerActivity.newWebChooserIntent(acceptTypes, allowMultiple);
                intent.setClass(ctx, AttachmentPickerActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);

                AttachmentPickerActivity.sWebCallback = uris -> {
                    MAIN.post(() -> {
                        if (sPendingFileCallback != mine) return;
                        sPendingFileCallback = null;
                        sPendingFileWebView = null;
                        AttachmentPickerActivity.sWebCallback = null;
                        try {
                            mine.onReceiveValue(uris);
                        } catch (Throwable ignored) {}
                    });
                };

                try {
                    ctx.startActivity(intent);
                    return true;
                } catch (Throwable ex) {
                    Log.w(TAG, "start file chooser activity failed", ex);
                    cancelPendingFileChooser();
                    return false;
                }
            }
        });
        try {
            android.webkit.CookieManager.getInstance().setAcceptCookie(true);
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        } catch (Throwable ignored) {}
        w.setLongClickable(true);
        w.setHapticFeedbackEnabled(true);
        // 点窗口内：可输入
        w.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_DOWN && !sAgentTouch) setFocusable(true);
            return false;
        });
        return w;
    }

    // ------------------------------------------------------------------ 下载

    /**
     * 网页触发的下载（用户点的、或 Pi 在 Pi 标签里点的）。
     * http(s)：交给系统 DownloadManager，带上该网址的 Cookie / UA / Referer（登录后才能下的文件也行），存到 下载/PiMet/。
     * blob: / data:：DownloadManager 下不了，在触发下载的那个页面里用 JS 读出来，分块经 JS 桥传回宿主再写文件。
     * JS 桥的 saveChunk 只接受宿主刚生成的一次性 nonce，普通网页无法借它往手机里写文件。
     */
    static final class DownloadRecord {
        String name, url, path, status;
        long dmId = -1;
        long time = System.currentTimeMillis();
    }

    static final List<DownloadRecord> sDownloads = Collections.synchronizedList(new ArrayList<>());

    private static final class PendingBlob {
        String name, mime;
        File tmp;
        FileOutputStream out;
        DownloadRecord rec;
    }

    private final java.util.Map<String, PendingBlob> pendingBlobs = new java.util.concurrent.ConcurrentHashMap<>();

    private static void remember(DownloadRecord r) {
        sDownloads.add(0, r);
        while (sDownloads.size() > 30) sDownloads.remove(sDownloads.size() - 1);
    }

    private void startDownload(Tab t, String url, String ua, String contentDisposition, String mime) {
        String name = android.webkit.URLUtil.guessFileName(url, contentDisposition, mime);
        if (name == null || name.isEmpty()) name = "download";
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        DownloadRecord rec = new DownloadRecord();
        rec.name = name;
        rec.url = url.length() > 300 ? url.substring(0, 300) + "…" : url;
        remember(rec);
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            startBlobDownload(t, url, name, mime, rec);
            return;
        }
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            rec.status = "不支持的下载地址";
            Toast.makeText(ctx, "无法下载：不支持的地址", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            android.app.DownloadManager dm = (android.app.DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
            android.app.DownloadManager.Request req = new android.app.DownloadManager.Request(Uri.parse(url));
            String cookie = android.webkit.CookieManager.getInstance().getCookie(url);
            if (cookie != null && !cookie.isEmpty()) req.addRequestHeader("Cookie", cookie);
            if (ua != null && !ua.isEmpty()) req.addRequestHeader("User-Agent", ua);
            if (t.url.startsWith("http")) req.addRequestHeader("Referer", t.url);
            if (mime != null && !mime.isEmpty()) req.setMimeType(mime);
            req.setTitle(name);
            req.setDescription("PiMet 浏览器下载");
            req.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.allowScanningByMediaScanner();
            String where;
            try {
                req.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, "PiMet/" + name);
                where = "下载/PiMet/" + name;
            } catch (Throwable noPublic) {
                // 没有存储权限时退回应用自己的目录（不需要权限）
                req.setDestinationInExternalFilesDir(ctx, android.os.Environment.DIRECTORY_DOWNLOADS, name);
                File d = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
                where = d != null ? new File(d, name).getAbsolutePath() : name;
            }
            rec.dmId = dm.enqueue(req);
            rec.path = where;
            rec.status = "下载中";
            Toast.makeText(ctx, "开始下载：" + name, Toast.LENGTH_SHORT).show();
        } catch (Throwable e) {
            Log.w(TAG, "download failed", e);
            rec.status = "失败: " + e.getMessage();
            Toast.makeText(ctx, "下载失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void startBlobDownload(Tab t, String url, String name, String mime, DownloadRecord rec) {
        String nonce = UUID_NONCE();
        PendingBlob pb = new PendingBlob();
        pb.name = name;
        pb.mime = mime;
        pb.rec = rec;
        try {
            pb.tmp = File.createTempFile("pimet-dl", ".part", ctx.getCacheDir());
            pb.out = new FileOutputStream(pb.tmp);
        } catch (Throwable e) {
            rec.status = "失败: " + e.getMessage();
            return;
        }
        pendingBlobs.put(nonce, pb);
        rec.status = "下载中";
        Toast.makeText(ctx, "开始下载：" + name, Toast.LENGTH_SHORT).show();
        String js = "(function(u,n){fetch(u).then(function(r){return r.blob()}).then(function(b){"
                + "var CH=393216,off=0;function next(){var part=b.slice(off,off+CH);var fr=new FileReader();"
                + "fr.onload=function(){var s=String(fr.result),i=s.indexOf(',');off+=CH;var last=off>=b.size;"
                + "PiMetNative.saveChunk(n,i>=0?s.slice(i+1):'',last,'');if(!last)setTimeout(next,0);};"
                + "fr.onerror=function(){PiMetNative.saveChunk(n,'',true,'读取失败')};fr.readAsDataURL(part);}"
                + "if(b.size===0){PiMetNative.saveChunk(n,'',true,'');}else next();"
                + "}).catch(function(e){PiMetNative.saveChunk(n,'',true,'读取失败: '+e+'（网页可能已释放这个 blob）')});})("
                + JSONObject.quote(url) + "," + JSONObject.quote(nonce) + ")";
        t.web.evaluateJavascript(js, null);
    }

    private static String UUID_NONCE() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    /** JS 桥回调（任意线程）：写入一块；last=true 时收尾 */
    private void onBlobChunk(String nonce, String b64, boolean last, String error) {
        PendingBlob pb = pendingBlobs.get(nonce);
        if (pb == null) return; // 不是宿主发起的下载，忽略
        try {
            if (error == null && b64 != null && !b64.isEmpty()) pb.out.write(android.util.Base64.decode(b64, android.util.Base64.DEFAULT));
        } catch (Throwable e) {
            error = "写入失败: " + e.getMessage();
        }
        if (!last && error == null) return;
        pendingBlobs.remove(nonce);
        try { pb.out.close(); } catch (Throwable ignored) {}
        String err = error;
        String where = null;
        if (err == null) {
            try {
                where = publishFile(pb.tmp, pb.name, pb.mime);
            } catch (Throwable e) {
                err = "保存失败: " + e.getMessage();
            }
        }
        pb.tmp.delete();
        pb.rec.status = err == null ? "完成" : "失败: " + err;
        pb.rec.path = where;
        final String msg = err == null ? "已下载：" + where : "下载失败：" + err;
        MAIN.post(() -> Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show());
    }

    /** 把临时文件放进 下载/PiMet/：Android 10+ 走 MediaStore（无需权限），以下版本有权限写公共目录，否则写应用目录 */
    private String publishFile(File src, String name, String mime) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
            if (mime != null && !mime.isEmpty()) cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime);
            cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/PiMet");
            Uri uri = ctx.getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri != null) {
                try (InputStream in = new FileInputStream(src); java.io.OutputStream o = ctx.getContentResolver().openOutputStream(uri)) {
                    copy(in, o);
                }
                return "下载/PiMet/" + name;
            }
        }
        File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "PiMet");
        boolean canPublic = Build.VERSION.SDK_INT < 23 || ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (!canPublic || (!dir.isDirectory() && !dir.mkdirs())) {
            dir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) dir = new File(ctx.getFilesDir(), "downloads");
            dir.mkdirs();
        }
        File dst = new File(dir, name);
        for (int i = 1; dst.exists() && i < 100; i++) {
            int dot = name.lastIndexOf('.');
            dst = new File(dir, dot > 0 ? name.substring(0, dot) + "(" + i + ")" + name.substring(dot) : name + "(" + i + ")");
        }
        try (InputStream in = new FileInputStream(src); java.io.OutputStream o = new FileOutputStream(dst)) {
            copy(in, o);
        }
        return dst.getAbsolutePath();
    }

    private static void copy(InputStream in, java.io.OutputStream o) throws Exception {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) != -1) o.write(buf, 0, n);
    }

    /** 给 BrowserAgent 的 downloads 动作：最近的下载及状态 */
    static String downloadsText(Context ctx) {
        StringBuilder sb = new StringBuilder();
        android.app.DownloadManager dm = (android.app.DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
        synchronized (sDownloads) {
            for (DownloadRecord r : sDownloads) {
                String st = r.status;
                if (r.dmId >= 0 && dm != null) {
                    try (android.database.Cursor c = dm.query(new android.app.DownloadManager.Query().setFilterById(r.dmId))) {
                        if (c != null && c.moveToFirst()) {
                            int s = c.getInt(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS));
                            long got = c.getLong(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                            long all = c.getLong(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                            st = s == android.app.DownloadManager.STATUS_SUCCESSFUL ? "完成"
                                    : s == android.app.DownloadManager.STATUS_FAILED ? "失败(原因码 " + c.getInt(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_REASON)) + ")"
                                    : "下载中 " + got / 1024 + "KB" + (all > 0 ? "/" + all / 1024 + "KB" : "");
                        }
                    } catch (Throwable ignored) {}
                }
                sb.append(r.name).append("  ").append(st).append(r.path != null ? "  → " + r.path : "").append("\n    来源: ").append(r.url).append('\n');
            }
        }
        return sb.length() == 0 ? "还没有下载记录" : sb.toString().trim();
    }

    // ------------------------------------------------------------------ 导航

    private String appsUrlPrefix() {
        return "file://" + appsDir.getAbsolutePath() + "/";
    }

    /** 只有应用目录下的本地页面能用 window.pimet（执行命令等） */
    private boolean isTrusted(String url) {
        if (url == null) return false;
        String prefix = appsUrlPrefix();
        if (!url.startsWith(prefix)) return false;
        // 防止 ../ 跳出目录
        try {
            String path = Uri.parse(url).getPath();
            if (path == null) return false;
            String canon = new File(path).getCanonicalPath();
            return canon.startsWith(appsDir.getCanonicalPath() + File.separator);
        } catch (Throwable t) {
            return false;
        }
    }

    private void goHome() {
        goHome(active, true);
    }

    /** 在指定标签里打开首页；restore=true 时顺便把窗口从最小化恢复 */
    private void goHome(Tab t, boolean restore) {
        if (root.getParent() == null) wm.addView(root, lp);
        if (restore && minimized) toggleMinimize();
        File custom = new File(appsDir, "home.html");
        String homeCfg = config.optString("home", "");
        if (!homeCfg.isEmpty()) {
            loadIn(t, homeCfg.startsWith("/") ? "file://" + new File(ProotManager.getRootfsDir(ctx), homeCfg.substring(1)).getAbsolutePath() : homeCfg, restore);
            return;
        }
        if (custom.isFile()) {
            t.web.loadUrl("file://" + custom.getAbsolutePath());
            return;
        }
        new Thread(() -> {
            File launcher = new File(appsDir, ".launcher.html");
            try {
                writeIfChanged(launcher, buildLauncherHtml());
            } catch (Throwable e) {
                Log.w(TAG, "write launcher failed", e);
            }
            MAIN.post(() -> { if (t.web != null) t.web.loadUrl("file://" + launcher.getAbsolutePath()); });
        }, "pimet-apps-home").start();
    }

    /** 给 BrowserAgent 用：确保窗口已创建并显示，必须在主线程调用 */
    static FloatingApps ensureForAgent(Context context) {
        if (sInstance == null) {
            boolean quiet = sUserClosed;
            sInstance = new FloatingApps(context.getApplicationContext(), quiet);
            sInstance.goHome(sInstance.active, false);
            sInstance.freshForAgent = !quiet;
        } else if (sInstance.root.getParent() == null) {
            sInstance.wm.addView(sInstance.root, sInstance.lp);
        }
        return sInstance;
    }

    static FloatingApps current() {
        return sInstance;
    }

    static boolean isPiStopped() {
        return sPiStopped;
    }

    String url() {
        return currentUrl();
    }

    private String currentUrl() {
        return active != null ? active.url : "";
    }

    /** Pi 的操作从不把用户收起（最小化）的窗口弹出来；要给用户看时用 show / switch_tab */
    void agentLoad(Tab t, String url) {
        loadIn(t, url, false);
    }

    void agentHome(Tab t) {
        goHome(t, false);
    }

    void agentRestore() {
        if (minimized) toggleMinimize();
    }

    void agentMinimize() {
        if (!minimized) toggleMinimize();
    }

    void agentClose() {
        dismiss();
    }

    private void load(String url) {
        loadIn(active, url, true);
    }

    private void loadIn(Tab t, String url, boolean restore) {
        if (root.getParent() == null) wm.addView(root, lp);
        if (restore && minimized) toggleMinimize();
        if (url == null || url.isEmpty()) {
            goHome(t, restore);
            return;
        }
        t.web.loadUrl(url);
    }

    private void reload() {
        if (isLauncher(currentUrl())) goHome();
        else web.reload();
    }

    private void goBack() {
        if (web.canGoBack()) web.goBack();
        else if (!isLauncher(currentUrl())) goHome();
    }

    private boolean isLauncher(String url) {
        return url != null && url.endsWith("/.launcher.html");
    }

    private void showUrl(Tab t, String url) {
        if (t != active || urlField.hasFocus()) return;
        if (url == null || isLauncher(url)) {
            urlField.setText("");
            return;
        }
        String prefix = "file://" + pagesDir.getAbsolutePath() + "/";
        if (url.startsWith(prefix)) {
            String rel = url.substring(prefix.length());
            int slash = rel.indexOf('/');
            String id = slash > 0 ? rel.substring(0, slash) : rel;
            urlField.setText("应用 · " + Uri.decode(id));
        } else {
            urlField.setText(url);
        }
    }

    private String normalizeUrl(String input) {
        String s = input == null ? "" : input.trim();
        if (s.isEmpty()) return "";
        if (s.startsWith("应用 · ")) {
            String id = s.substring("应用 · ".length()).trim();
            return "file://" + new File(pagesDir, id + "/index.html").getAbsolutePath();
        }
        if (s.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) return s;
        if (s.startsWith("localhost") || s.startsWith("127.0.0.1") || s.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+(:\\d+)?(/.*)?$")) {
            return "http://" + s;
        }
        if (s.matches("^:\\d+(/.*)?$")) return "http://127.0.0.1" + s;
        if (!s.contains(" ") && s.contains(".")) return "https://" + s;
        return "https://www.bing.com/search?q=" + Uri.encode(s);
    }

    // ------------------------------------------------------------------ 可定制：config.json / 用户脚本

    static final String DEFAULT_CONFIG = "{\n"
            + "  \"buttons\": [\n"
            + "    {\"icon\": \"📝\", \"title\": \"让 Pi 总结本页\", \"askPi\": \"用 browser 工具读一下当前页面（{url}），给我简要总结。\"}\n"
            + "  ],\n"
            + "  \"theme\": {},\n"
            + "  \"home\": \"\"\n"
            + "}\n";

    /** 给 BrowserAgent / 外部调用：立即重新读取配置 */
    void reloadConfigNow() {
        configMtime = -1;
        reloadConfigIfChanged();
    }

    private void reloadConfigIfChanged() {
        File f = new File(appsDir, "config.json");
        long m = f.isFile() ? f.lastModified() : 0;
        if (m == configMtime) return;
        configMtime = m;
        JSONObject c = new JSONObject();
        if (m > 0) {
            try {
                c = new JSONObject(readFile(f));
            } catch (Throwable t) {
                Toast.makeText(ctx, "浏览器配置 config.json 格式错误：" + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
        config = c;
        applyConfig();
    }

    private static int color(JSONObject o, String key, int def) {
        String v = o.optString(key, "");
        if (v.isEmpty()) return def;
        try {
            return Color.parseColor(v);
        } catch (Throwable t) {
            return def;
        }
    }

    private void applyConfig() {
        JSONObject th = config.optJSONObject("theme");
        if (th == null) th = new JSONObject();
        rootBg.setColor(color(th, "background", Color.parseColor("#F5141418")));
        rootBg.setStroke(dp(1), color(th, "border", Color.parseColor("#3A3F4B")));
        int fg = color(th, "text", Color.parseColor("#C9D1D9"));
        for (int i = 0; i < bar.getChildCount(); i++) {
            View v = bar.getChildAt(i);
            if (v instanceof TextView && !(v instanceof EditText)) ((TextView) v).setTextColor(fg);
        }
        GradientDrawable ub = new GradientDrawable();
        ub.setColor(color(th, "addressBar", Color.parseColor("#22272E")));
        ub.setCornerRadius(dp(8));
        urlField.setBackground(ub);
        urlField.setTextColor(color(th, "addressText", Color.parseColor("#E6EDF3")));
        pageColor = color(th, "page", Color.parseColor("#0D1117"));
        for (Tab t : tabs) if (t.web != null) t.web.setBackgroundColor(pageColor);
        refreshTabs();

        customStrip.removeAllViews();
        org.json.JSONArray btns = config.optJSONArray("buttons");
        if (btns != null) {
            for (int i = 0; i < btns.length(); i++) {
                JSONObject b = btns.optJSONObject(i);
                if (b == null) continue;
                TextView t = new TextView(ctx);
                String icon = b.optString("icon", "");
                String label = b.optString("label", "");
                t.setText(label.isEmpty() ? (icon.isEmpty() ? "●" : icon) : (icon.isEmpty() ? label : icon + " " + label));
                t.setContentDescription(b.optString("title", label));
                t.setTextColor(color(b, "color", fg));
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                t.setGravity(Gravity.CENTER);
                t.setPadding(dp(10), 0, dp(10), 0);
                GradientDrawable cb = new GradientDrawable();
                cb.setColor(color(th, "button", Color.parseColor("#22272E")));
                cb.setCornerRadius(dp(14));
                t.setBackground(cb);
                t.setOnClickListener(v -> runCustomButton(b));
                t.setOnLongClickListener(v -> {
                    Toast.makeText(ctx, b.optString("title", label.isEmpty() ? icon : label), Toast.LENGTH_SHORT).show();
                    return true;
                });
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(26));
                blp.setMargins(0, 0, dp(6), 0);
                customStrip.addView(t, blp);
            }
        }
        customScroll.setVisibility(customStrip.getChildCount() > 0 && !minimized ? View.VISIBLE : View.GONE);
    }

    /** 自定义按钮：script（在当前页执行 JS）| open（打开网址/本地页面）| askPi（把文字发给 Pi，可用 {url} {title} {selection}） */
    private void runCustomButton(JSONObject b) {
        String script = b.optString("script", "");
        String open = b.optString("open", "");
        String ask = b.optString("askPi", "");
        if (!script.isEmpty()) {
            web.evaluateJavascript("(function(){try{" + script + "\n}catch(e){alert('按钮脚本出错：'+e)}})()", null);
        }
        if (!open.isEmpty()) {
            if (open.startsWith("pages/") || open.startsWith("home.html")) load("file://" + new File(appsDir, open).getAbsolutePath());
            else if (open.startsWith("/")) load("file://" + new File(ProotManager.getRootfsDir(ctx), open.substring(1)).getAbsolutePath());
            else load(normalizeUrl(open));
        }
        if (!ask.isEmpty()) {
            web.evaluateJavascript("JSON.stringify({t:document.title,s:String(window.getSelection?getSelection():'')})", v -> {
                String title = "", sel = "";
                try {
                    JSONObject j = new JSONObject(new org.json.JSONTokener(v).nextValue().toString());
                    title = j.optString("t");
                    sel = j.optString("s");
                } catch (Throwable ignored) {}
                String text = ask.replace("{url}", currentUrl()).replace("{title}", title).replace("{selection}", sel);
                boolean send = b.optBoolean("send", true);
                if (sPiBridge != null) {
                    sPiBridge.askPi(text, send);
                    Toast.makeText(ctx, send ? "已发给 Pi" : "已填入 Pi 输入框", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(ctx, "工作台未就绪", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private static final class UserScript {
        String name;
        long mtime;
        String code;
        List<java.util.regex.Pattern> include = new ArrayList<>();
        List<java.util.regex.Pattern> exclude = new ArrayList<>();
    }

    private final java.util.Map<String, UserScript> scriptCache = new java.util.HashMap<>();

    /** 油猴风格的匹配：* 任意字符；以 / 包围则为正则 */
    private static java.util.regex.Pattern globToPattern(String g) {
        g = g.trim();
        if (g.length() > 2 && g.startsWith("/") && g.endsWith("/")) {
            return java.util.regex.Pattern.compile(g.substring(1, g.length() - 1));
        }
        if (g.equals("<all_urls>")) g = "*";
        StringBuilder sb = new StringBuilder("^");
        for (char c : g.toCharArray()) {
            if (c == '*') sb.append(".*");
            else if ("\\.[]{}()+-?^$|".indexOf(c) >= 0) sb.append('\\').append(c);
            else sb.append(c);
        }
        return java.util.regex.Pattern.compile(sb.append("$").toString());
    }

    private UserScript loadScript(File f) {
        UserScript cached = scriptCache.get(f.getName());
        if (cached != null && cached.mtime == f.lastModified()) return cached;
        UserScript u = new UserScript();
        u.name = f.getName();
        u.mtime = f.lastModified();
        try {
            u.code = readFile(f);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?m)^\\s*//\\s*@(match|include|exclude)\\s+(\\S+)").matcher(u.code);
            while (m.find()) {
                try {
                    if (m.group(1).equals("exclude")) u.exclude.add(globToPattern(m.group(2)));
                    else u.include.add(globToPattern(m.group(2)));
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            u.code = null;
        }
        scriptCache.put(f.getName(), u);
        return u;
    }

    private void injectUserScripts(WebView view, String url) {
        if (url == null || isLauncher(url)) return;
        File dir = new File(appsDir, "scripts");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".js") && !n.startsWith("."));
        if (files == null || files.length == 0) return;
        Arrays.sort(files);
        for (File f : files) {
            UserScript u = loadScript(f);
            if (u.code == null || u.include.isEmpty()) continue;
            boolean hit = false;
            for (java.util.regex.Pattern p : u.include) if (p.matcher(url).matches()) { hit = true; break; }
            if (!hit) continue;
            for (java.util.regex.Pattern p : u.exclude) if (p.matcher(url).matches()) { hit = false; break; }
            if (!hit) continue;
            String guard = "__pimet_us_" + u.name.replaceAll("[^A-Za-z0-9_]", "_");
            view.evaluateJavascript("(function(){if(window." + guard + ")return;window." + guard + "=1;try{\n" + u.code
                    + "\n}catch(e){console.error('[PiMet 用户脚本 " + u.name.replace("'", "") + "]',e)}})()", null);
        }
    }

    // ------------------------------------------------------------------ 应用列表

    private static final class AppInfo {
        String id, name, icon, desc;
        long mtime;
    }

    private List<AppInfo> scanApps() {
        List<AppInfo> list = new ArrayList<>();
        File[] dirs = pagesDir.listFiles();
        if (dirs == null) return list;
        for (File d : dirs) {
            if (!d.isDirectory() || d.getName().startsWith(".")) continue;
            File index = new File(d, "index.html");
            if (!index.exists()) continue;
            AppInfo a = new AppInfo();
            a.id = d.getName();
            a.name = d.getName();
            a.icon = "🧩";
            a.desc = "";
            a.mtime = index.lastModified();
            File meta = new File(d, "app.json");
            if (meta.exists()) {
                try {
                    JSONObject j = new JSONObject(readFile(meta));
                    a.name = j.optString("name", a.name);
                    a.icon = j.optString("icon", a.icon);
                    a.desc = j.optString("description", "");
                } catch (Throwable ignored) {}
            }
            list.add(a);
        }
        Collections.sort(list, (x, y) -> Long.compare(y.mtime, x.mtime));
        return list;
    }

    private String buildLauncherHtml() {
        List<AppInfo> apps = scanApps();
        StringBuilder cards = new StringBuilder();
        for (AppInfo a : apps) {
            String href = "pages/" + Uri.encode(a.id) + "/index.html";
            cards.append("<a class=\"card\" href=\"").append(href).append("\">")
                    .append("<div class=\"icon\">").append(esc(a.icon)).append("</div>")
                    .append("<div class=\"name\">").append(esc(a.name)).append("</div>");
            if (!TextUtils.isEmpty(a.desc)) {
                cards.append("<div class=\"desc\">").append(esc(a.desc)).append("</div>");
            }
            cards.append("</a>");
        }
        String body;
        if (apps.isEmpty()) {
            body = "";
        } else {
            body = "<h2>Pi 做的页面</h2><div class=\"grid\">" + cards + "</div>"
                    + "<p class=\"dim foot\">目录：" + APPS_DIR_IN_CONTAINER + " · 修改后点 ⟳ 刷新</p>";
        }
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Pi 浏览器</title><style>"
                + ":root{color-scheme:dark}body{margin:0;padding:16px;background:#0d1117;color:#e6edf3;"
                + "font-family:system-ui,-apple-system,'PingFang SC','Noto Sans CJK SC',sans-serif}"
                + "h1{font-size:18px;margin:6px 0 8px;font-weight:600}h2{font-size:14px;margin:22px 0 10px;color:#c9d1d9}"
                + ".intro{text-align:center;margin-top:8vh;line-height:1.7;padding:0 8px}"
                + ".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(96px,1fr));gap:10px}"
                + ".card{display:flex;flex-direction:column;align-items:center;text-align:center;padding:14px 6px;"
                + "border-radius:14px;background:#161b22;border:1px solid #30363d;color:inherit;text-decoration:none}"
                + ".card:active{background:#1f2630}.icon{font-size:30px;line-height:1.2}"
                + ".name{margin-top:6px;font-size:13px;font-weight:600;word-break:break-all}"
                + ".desc{margin-top:3px;font-size:11px;color:#8b949e;display:-webkit-box;-webkit-line-clamp:2;"
                + "-webkit-box-orient:vertical;overflow:hidden}"
                + ".empty{text-align:center;margin-top:12vh;line-height:1.7;font-size:14px}.big{font-size:44px}"
                + ".dim{color:#8b949e;font-size:12px}.foot{margin-top:18px;text-align:center}"
                + "</style></head><body>"
                + "<div class=\"intro\"><div class=\"big\">🌐</div><h1>Pi 浏览器</h1>"
                + "<p class=\"dim\">在顶部地址栏输入网址或搜索词。Pi 也能操控这个窗口：在工作台跟 Pi 说"
                + "「用浏览器打开 xxx 帮我…」，它会打开网页、阅读、点击、输入，你在这里能看到每一步。</p>"
                + "<p class=\"dim\">这个浏览器本身也能让 Pi 改：工具栏按钮、配色、首页、给某个网站加功能的脚本、自己的小页面。"
                + "跟 Pi 说「给 Pi 浏览器加一个…」就行。</p></div>"
                + body + "</body></html>";
    }

    // ------------------------------------------------------------------ JS 桥

    /** 注入到受信页面的 window.pimet 封装 */
    private static final String HELPER_JS = "(function(){if(window.pimet&&window.pimet.__ok)return;"
            + "var cbs={},seq=0;"
            + "window.__pimetResolve=function(id,res){var c=cbs[id];if(!c)return;delete cbs[id];c(res);};"
            + "window.pimet={__ok:true,"
            + "exec:function(cmd,opts){return new Promise(function(r){var id='c'+(++seq);cbs[id]=r;"
            + "PiMetNative.exec(String(cmd),id,(opts&&opts.timeout)||60);});},"
            + "askPi:function(text,send){PiMetNative.askPi(String(text),send!==false);},"
            + "toast:function(m){PiMetNative.toast(String(m));},"
            + "openExternal:function(u){PiMetNative.openExternal(String(u));},"
            + "close:function(){PiMetNative.close();}};"
            + "try{window.dispatchEvent(new Event('pimetready'));}catch(e){}})();";

    /** 每个标签一个桥；按该标签自己当前的网址判断是否可信（只有应用目录里的本地页面） */
    private final class Bridge {
        private final Tab tab;

        Bridge(Tab tab) {
            this.tab = tab;
        }

        @JavascriptInterface
        public void exec(String cmd, String cbId, int timeoutSec) {
            if (!isTrusted(tab.url)) {
                deliver(tab, cbId, -1, "", "当前页面不在应用目录内，禁止执行命令");
                return;
            }
            final int timeout = Math.max(1, Math.min(timeoutSec, 600));
            new Thread(() -> runCommand(tab, cmd, cbId, timeout), "pimet-apps-exec").start();
        }

        @JavascriptInterface
        public void askPi(String text, boolean send) {
            if (!isTrusted(tab.url)) return;
            MAIN.post(() -> {
                if (sPiBridge != null) sPiBridge.askPi(text, send);
                else Toast.makeText(ctx, "工作台未就绪", Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface
        public void toast(String msg) {
            if (!isTrusted(tab.url)) return;
            MAIN.post(() -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void openExternal(String url) {
            if (!isTrusted(tab.url)) return;
            MAIN.post(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);
                } catch (Throwable ignored) {}
            });
        }

        /** blob/data 下载的分块回传；只认宿主生成的一次性 nonce（见 startBlobDownload） */
        @JavascriptInterface
        public void saveChunk(String nonce, String b64, boolean last, String error) {
            onBlobChunk(nonce, b64, last, error == null || error.isEmpty() ? null : error);
        }

        @JavascriptInterface
        public void close() {
            if (!isTrusted(tab.url)) return;
            MAIN.post(FloatingApps.this::dismiss);
        }
    }

    private void runCommand(Tab tab, String cmd, String cbId, int timeoutSec) {
        Process p = null;
        try {
            List<String> guest = Arrays.asList("/bin/bash", "-lc", cmd);
            ProcessBuilder pb = ProotManager.buildProotProcess(ctx, "/root", guest);
            pb.redirectErrorStream(false);
            p = pb.start();
            final Process proc = p;
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final ByteArrayOutputStream err = new ByteArrayOutputStream();
            Thread to = pump(proc.getInputStream(), out);
            Thread te = pump(proc.getErrorStream(), err);
            boolean done = proc.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!done) {
                proc.destroyForcibly();
            }
            to.join(1000);
            te.join(1000);
            String e = err.toString("UTF-8");
            if (!done) e = e + (e.isEmpty() ? "" : "\n") + "命令超时（" + timeoutSec + " 秒）已终止";
            deliver(tab, cbId, done ? proc.exitValue() : -1, out.toString("UTF-8"), e);
        } catch (Throwable t) {
            if (p != null) try { p.destroyForcibly(); } catch (Throwable ignored) {}
            deliver(tab, cbId, -1, "", "执行失败: " + t.getMessage());
        }
    }

    private static final int MAX_OUTPUT = 2 * 1024 * 1024;

    private static Thread pump(InputStream in, ByteArrayOutputStream sink) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[8192];
            int n;
            try {
                while ((n = in.read(buf)) != -1) {
                    if (sink.size() < MAX_OUTPUT) sink.write(buf, 0, Math.min(n, MAX_OUTPUT - sink.size()));
                }
            } catch (Throwable ignored) {}
        }, "pimet-apps-pump");
        t.start();
        return t;
    }

    private void deliver(Tab tab, String cbId, int code, String stdout, String stderr) {
        try {
            JSONObject res = new JSONObject();
            res.put("code", code);
            res.put("stdout", stdout);
            res.put("stderr", stderr);
            String js = "window.__pimetResolve&&window.__pimetResolve(" + JSONObject.quote(cbId) + "," + res + ")";
            MAIN.post(() -> { if (tab.web != null) tab.web.evaluateJavascript(js, null); });
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------ 窗口行为

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void savePos() {
        if (maximized || minimized) return;
        prefs().edit().putInt("x", lp.x).putInt("y", lp.y).putInt("w", lp.width).putInt("h", lp.height).apply();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics()));
    }

    private TextView barBtn(String text, String desc) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setContentDescription(desc);
        t.setTextColor(Color.parseColor("#C9D1D9"));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private void addBar(LinearLayout bar, String text, String desc, View.OnClickListener l) {
        TextView t = barBtn(text, desc);
        t.setOnClickListener(l);
        bar.addView(t, new LinearLayout.LayoutParams(dp(30), dp(36)));
    }

    private void setFocusable(boolean focusable) {
        int f = lp.flags;
        if (focusable) lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        else lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (f != lp.flags) update();
    }

    private void hideIme() {
        InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
        urlField.clearFocus();
    }

    private void update() {
        try {
            if (root.getParent() != null) wm.updateViewLayout(root, lp);
        } catch (Throwable t) {
            Log.w(TAG, "update failed", t);
        }
    }

    private void clampToScreen() {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        lp.width = Math.max(dp(240), Math.min(lp.width, dm.widthPixels));
        if (!minimized) lp.height = Math.max(dp(180), Math.min(lp.height, dm.heightPixels));
        lp.x = Math.max(-lp.width + dp(60), Math.min(lp.x, dm.widthPixels - dp(60)));
        lp.y = Math.max(0, Math.min(lp.y, dm.heightPixels - dp(40)));
    }

    private void toggleMinimize() {
        minimized = !minimized;
        refreshGrip();
        webFrame.setVisibility(minimized ? View.GONE : View.VISIBLE);
        tabScroll.setVisibility(minimized ? View.GONE : View.VISIBLE);
        customScroll.setVisibility(!minimized && customStrip.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        if (minimized) {
            savedH = lp.height;
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            hideIme();
            setFocusable(false);
        } else {
            lp.height = savedH > 0 ? savedH : prefs().getInt("h", dp(420));
        }
        update();
    }

    private void toggleMaximize() {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        if (minimized) toggleMinimize();
        if (!maximized) {
            savedW = lp.width;
            savedH = lp.height;
            savedX = lp.x;
            savedY = lp.y;
            lp.x = 0;
            lp.y = 0;
            lp.width = dm.widthPixels;
            lp.height = dm.heightPixels;
            maximized = true;
        } else {
            maximized = false;
            lp.width = savedW;
            lp.height = savedH;
            lp.x = savedX;
            lp.y = savedY;
        }
        update();
    }

    private int windowSizePreset = 1;

    private void cycleWindowSize() {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        if (minimized) toggleMinimize();
        if (maximized) maximized = false;

        windowSizePreset = (windowSizePreset + 1) % 3;
        float hRatio;
        String desc;
        switch (windowSizePreset) {
            case 0:
                hRatio = 0.55f;
                desc = "半屏 (55%)";
                break;
            case 2:
                hRatio = 0.92f;
                desc = "满幅 (92%)";
                break;
            case 1:
            default:
                hRatio = 0.72f;
                desc = "标准大屏 (72%)";
                break;
        }

        lp.width = Math.round(dm.widthPixels * 0.96f);
        lp.height = Math.round(dm.heightPixels * hRatio);
        lp.x = Math.round(dm.widthPixels * 0.02f);
        lp.y = Math.round(dm.heightPixels * 0.04f);
        clampToScreen();
        update();
        savePos();
        Toast.makeText(ctx, "📐 悬浮窗口已设为: " + desc, Toast.LENGTH_SHORT).show();
    }

    private void dismiss() {
        cancelPendingFileChooser();
        AttachmentPickerActivity.sCallback = null;
        hideIme();
        try {
            if (root.getParent() != null) wm.removeView(root);
        } catch (Throwable ignored) {}
        for (Tab t : tabs) {
            try {
                t.web.stopLoading();
                t.web.destroy();
            } catch (Throwable ignored) {}
            t.web = null;
            t.closed = true;
        }
        tabs.clear();
        if (sInstance == this) sInstance = null;
    }

    private final class DragMover implements View.OnTouchListener {
        private float sx, sy;
        private int ox, oy;

        @Override
        public boolean onTouch(View v, MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    sx = ev.getRawX();
                    sy = ev.getRawY();
                    ox = lp.x;
                    oy = lp.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    lp.x = ox + Math.round(ev.getRawX() - sx);
                    lp.y = oy + Math.round(ev.getRawY() - sy);
                    clampToScreen();
                    update();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (Math.abs(ev.getRawX() - sx) < dp(4) && Math.abs(ev.getRawY() - sy) < dp(4)) {
                        toggleMinimize();
                    }
                    savePos();
                    return true;
                default:
                    return false;
            }
        }
    }

    private final class DragResizer implements View.OnTouchListener {
        private float sx, sy;
        private int ow, oh;

        @Override
        public boolean onTouch(View v, MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (maximized) maximized = false;
                    sx = ev.getRawX();
                    sy = ev.getRawY();
                    ow = lp.width;
                    oh = lp.height;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    lp.width = ow + Math.round(ev.getRawX() - sx);
                    lp.height = oh + Math.round(ev.getRawY() - sy);
                    clampToScreen();
                    update();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    savePos();
                    return true;
                default:
                    return false;
            }
        }
    }

    // ------------------------------------------------------------------ 工具

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String readFile(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) bo.write(buf, 0, n);
        return bo.toString("UTF-8");
    }

    private static String readAsset(Context ctx, String name) {
        try (InputStream in = ctx.getAssets().open(name)) {
            return readAll(in);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeIfChanged(File f, String content) throws Exception {
        if (f.exists()) {
            try {
                if (content.equals(readFile(f))) return;
            } catch (Throwable ignored) {}
        }
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileOutputStream fo = new FileOutputStream(f)) {
            fo.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
