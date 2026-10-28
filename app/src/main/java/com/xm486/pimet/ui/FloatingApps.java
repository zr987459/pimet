package com.xm486.pimet.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.MutableContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.xm486.pimet.AttachmentPickerActivity;
import com.xm486.pimet.PiBrowserActivity;
import com.xm486.pimet.ThemeManager;
import com.xm486.pimet.proot.ProotManager;

import org.json.JSONArray;
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
 * Pi 浏览器：独立的全屏浏览器窗口（{@link PiBrowserActivity}，单独的任务，在最近任务里是一张单独的卡片），
 * 界面仿手机 Chrome：⌂ · 地址栏 · 🧩 · 标签数 · ⋮，标签页网格、书签、下载、全屏、页面缩放、桌面版网站。
 * <p>
 * 标签和 WebView 由这个进程级单例持有，不随 Activity 销毁：用户按返回/切走后标签都还在，Pi 也能在后台继续操作。
 * Activity 不在前台时 {@link #minimized} = true，BrowserAgent 会退回 JS 点击、不截图（与以前悬浮窗最小化时一致）。
 * <p>
 * 容器 /root/pimet-browser/ 决定这个浏览器的样子和功能，Pi 可以随时改：config.json（扩展按钮、配色、首页）、
 * scripts/*.js（油猴风格用户脚本，按 @match 注入任意网站）、pages/&lt;id&gt;/index.html（自定义页面，可用 window.pimet）、home.html（自定义首页）。
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

    /** 应用级 Context（下载、文件、Toast 等） */
    final Context ctx;
    /** 所有视图（含 WebView）都用它创建：挂到 Activity 时 base = Activity（网页的下拉框/弹窗需要），离开时换回 Application */
    private final MutableContextWrapper vctx;
    /** 当前承载浏览器的 Activity；null = 没有界面（Pi 在后台用） */
    private Activity host;
    private final FrameLayout root;
    private final LinearLayout column;
    final FrameLayout webFrame;
    /** 当前显示的标签的 WebView（工具栏按钮、自定义按钮都作用于它） */
    WebView web;
    final List<Tab> tabs = new ArrayList<>();
    Tab active;
    private int tabSeq = 0;
    private int pageColor = Color.parseColor("#0D1117");
    /** 用户点了「停止 Pi」：之后所有 browser 请求都返回「已叫停」，直到用户点「允许 Pi」 */
    private static volatile boolean sPiStopped = false;
    /** BrowserAgent 正在分发模拟触摸 */
    static volatile boolean sAgentTouch = false;
    private final EditText urlField;
    private final File appsDir;   // /root/pimet-browser：config.json、scripts/、pages/、home.html
    private final File pagesDir;  // /root/pimet-browser/pages/<id>/index.html
    private final LinearLayout bar;
    private final LinearLayout urlPill;
    private final TextView siteIcon;
    private final TextView homeBtn, extBtn, tabCountBtn, menuBtn;
    private final ProgressBar progress;
    private final LinearLayout piBanner;
    private final TextView piBannerText, piBannerView, piBannerStop;
    private final LinearLayout customStrip;
    private final android.widget.HorizontalScrollView customScroll;
    /** 标签页网格（点标签数按钮打开） */
    private final FrameLayout switcher;
    private final LinearLayout switcherGrid;
    private final TextView switcherStop;
    /** 全屏时右上角的退出按钮 */
    private final TextView exitFullBtn;
    private long configMtime = -1;
    /** 浏览器是 Pi 先打开的（用户还没看任何网页）：第一个 Pi 标签直接放到前台 */
    private boolean freshForAgent = false;
    /** 用户亲手关掉了浏览器（⋮ → 关闭浏览器） */
    private static volatile boolean sUserClosed = false;
    private JSONObject config = new JSONObject();
    /** 浏览器窗口不在前台（没有 Activity 或 Activity 已 onStop）。BrowserAgent 读这个字段决定能否真实点击/截图 */
    volatile boolean minimized = true;
    private boolean fullscreen = false;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    // 配色（applyConfig 里按 Pi-Web 主题 + config.json theme 计算）
    private int cBar, cFg, cPill, cAddr, cAccent, cBorder, cDim;
    private boolean cDark;

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

    /** 工作台悬浮球 🌐：打开浏览器窗口（标签保留，像普通浏览器一样回到上次的页面） */
    public static void toggle(Context context) {
        show(context, null);
    }

    /** 打开浏览器窗口；url 不为空时在当前标签打开它 */
    public static void show(Context context, String url) {
        final Context app = context.getApplicationContext();
        MAIN.post(() -> {
            try {
                sUserClosed = false;
                boolean created = sInstance == null;
                if (created) sInstance = new FloatingApps(app);
                if (url != null && !url.isEmpty()) sInstance.load(url);
                else if (created) sInstance.goHome();
                openActivity(context);
            } catch (Throwable t) {
                Log.e(TAG, "show failed", t);
                Toast.makeText(app, "打开浏览器失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private static void openActivity(Context context) {
        Intent i = new Intent(context.getApplicationContext(), PiBrowserActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        context.getApplicationContext().startActivity(i);
    }

    // ------------------------------------------------------------------ Activity 承载

    /** PiBrowserActivity.onCreate：把浏览器界面放进 Activity */
    public static void attachTo(Activity a, ViewGroup container) {
        sUserClosed = false;
        boolean created = sInstance == null;
        if (created) sInstance = new FloatingApps(a.getApplicationContext());
        sInstance.attachHost(a, container);
        if (created) sInstance.goHome();
    }

    /** PiBrowserActivity.onStart / onStop */
    public static void hostVisible(Activity a, boolean visible) {
        FloatingApps f = sInstance;
        if (f == null || f.host != a) return;
        f.minimized = !visible;
        if (visible) {
            f.configMtime = -1; // 顺便按当前 Pi-Web 主题重新配色
            f.reloadConfigIfChanged();
            if (f.fullscreen) f.applyImmersive(true);
        }
        f.refreshTabs();
    }

    /** PiBrowserActivity.onDestroy：界面离开，标签和网页保留 */
    public static void detachFrom(Activity a) {
        FloatingApps f = sInstance;
        if (f != null) f.detachHost(a);
    }

    /** PiBrowserActivity 返回键；返回 false 表示交给 Activity（退到后台） */
    public static boolean handleBack(Activity a) {
        FloatingApps f = sInstance;
        if (f == null || f.host != a) return false;
        if (f.customView != null) { f.hideCustomView(); return true; }
        if (f.switcher.getVisibility() == View.VISIBLE) { f.closeSwitcher(); return true; }
        if (f.fullscreen) { f.setFullscreen(false); return true; }
        if (f.urlField.hasFocus()) { f.hideIme(); f.showUrl(f.active, f.currentUrl()); return true; }
        if (f.web != null && f.web.canGoBack()) { f.web.goBack(); return true; }
        if (f.active != null && !f.isLauncher(f.currentUrl()) && !f.active.pi) { f.goHome(); return true; }
        return false;
    }

    private void attachHost(Activity a, ViewGroup container) {
        host = a;
        vctx.setBaseContext(a);
        if (root.getParent() instanceof ViewGroup) ((ViewGroup) root.getParent()).removeView(root);
        container.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyConfig();
        if (fullscreen) applyImmersive(true);
    }

    private void detachHost(Activity a) {
        if (host != a) return;
        hideCustomView();
        hideIme();
        closeSwitcher();
        collapseBall();
        if (root.getParent() instanceof ViewGroup) ((ViewGroup) root.getParent()).removeView(root);
        host = null;
        vctx.setBaseContext(ctx);
        minimized = true;
        layoutOffscreen();
        refreshTabs();
    }

    /** 没有界面时也给视图树一次满屏布局，否则新建的 WebView 视口是 0×0，页面排版和 Pi 的坐标都会错 */
    private void layoutOffscreen() {
        if (root.getParent() != null) return;
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        root.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, dm.widthPixels, dm.heightPixels);
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
        this.ctx = app;
        this.vctx = new MutableContextWrapper(app);
        this.appsDir = new File(ProotManager.getRootfsDir(app), APPS_DIR_IN_CONTAINER.substring(1));
        this.pagesDir = new File(appsDir, "pages");
        if (!pagesDir.exists()) pagesDir.mkdirs();
        new Thread(() -> ensureScaffold(app), "pimet-apps-scaffold").start();

        root = new FrameLayout(vctx);
        column = new LinearLayout(vctx);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- 顶栏（仿 Chrome）：⌂ · [🔒 地址栏] · 🧩 · [标签数] · ⋮
        bar = new LinearLayout(vctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(6), dp(4), dp(6));

        homeBtn = barBtn("⌂", "首页", 22);
        homeBtn.setOnClickListener(v -> goHome());
        bar.addView(homeBtn, new LinearLayout.LayoutParams(dp(44), dp(44)));

        urlPill = new LinearLayout(vctx);
        urlPill.setOrientation(LinearLayout.HORIZONTAL);
        urlPill.setGravity(Gravity.CENTER_VERTICAL);
        urlPill.setPadding(dp(12), 0, dp(6), 0);
        siteIcon = new TextView(vctx);
        siteIcon.setText("🔍");
        siteIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        siteIcon.setGravity(Gravity.CENTER);
        siteIcon.setOnClickListener(v -> showSiteInfo());
        urlPill.addView(siteIcon, new LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.MATCH_PARENT));

        urlField = new EditText(vctx);
        urlField.setSingleLine(true);
        urlField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        urlField.setHint("搜索或输入网址");
        urlField.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlField.setSelectAllOnFocus(true);
        urlField.setBackground(null);
        urlField.setPadding(dp(8), 0, dp(4), 0);
        urlField.setEllipsize(TextUtils.TruncateAt.END);
        urlField.setOnFocusChangeListener((v, has) -> {
            if (has) {
                // 编辑时显示完整网址
                String u = currentUrl();
                if (!isLauncher(u) && !u.isEmpty() && !u.startsWith("file://")) urlField.setText(u);
                urlField.selectAll();
            } else {
                showUrl(active, currentUrl());
            }
        });
        urlField.setOnEditorActionListener((v, actionId, ev) -> {
            boolean go = actionId == EditorInfo.IME_ACTION_GO
                    || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN);
            if (go) {
                String target = normalizeUrl(urlField.getText().toString());
                hideIme();
                load(target);
                return true;
            }
            return false;
        });
        urlPill.addView(urlField, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        plp.setMargins(dp(2), 0, dp(4), 0);
        bar.addView(urlPill, plp);

        extBtn = barBtn("🧩", "扩展（config.json 里的按钮）", 18);
        extBtn.setOnClickListener(this::showExtensionsMenu);
        bar.addView(extBtn, new LinearLayout.LayoutParams(dp(40), dp(44)));

        tabCountBtn = new TextView(vctx);
        tabCountBtn.setGravity(Gravity.CENTER);
        tabCountBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tabCountBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tabCountBtn.setContentDescription("标签页");
        tabCountBtn.setOnClickListener(v -> openSwitcher());
        tabCountBtn.setOnLongClickListener(v -> {
            newUserTab();
            return true;
        });
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(24), dp(24));
        tlp.setMargins(dp(10), 0, dp(10), 0);
        bar.addView(tabCountBtn, tlp);

        menuBtn = barBtn("⋮", "更多", 22);
        menuBtn.setOnClickListener(this::showMainMenu);
        bar.addView(menuBtn, new LinearLayout.LayoutParams(dp(40), dp(44)));
        column.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 加载进度
        progress = new ProgressBar(vctx, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setIndeterminate(false);
        progress.setVisibility(View.INVISIBLE);
        column.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        // ---- Pi 状态条：Pi 在后台标签操作 / 已叫停时出现
        piBanner = new LinearLayout(vctx);
        piBanner.setOrientation(LinearLayout.HORIZONTAL);
        piBanner.setGravity(Gravity.CENTER_VERTICAL);
        piBanner.setPadding(dp(12), 0, dp(6), 0);
        piBannerText = new TextView(vctx);
        piBannerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        piBannerText.setSingleLine(true);
        piBannerText.setEllipsize(TextUtils.TruncateAt.END);
        piBanner.addView(piBannerText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        piBannerView = barBtn("查看", "切到 Pi 标签", 12);
        piBannerView.setOnClickListener(v -> {
            Tab pt = busyPiTab();
            if (pt == null) pt = lastPiTab();
            if (pt != null) {
                userTouched = true;
                switchTo(pt);
            }
        });
        piBanner.addView(piBannerView, new LinearLayout.LayoutParams(dp(52), dp(30)));
        piBannerStop = barBtn("停止", "叫停 Pi 的浏览器操作", 12);
        piBannerStop.setOnClickListener(v -> togglePiStopped());
        piBanner.addView(piBannerStop, new LinearLayout.LayoutParams(dp(72), dp(30)));
        piBanner.setVisibility(View.GONE);
        column.addView(piBanner, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));

        // ---- 扩展按钮栏（config.json 里 "showButtonBar": true 时显示在地址栏下方）
        customScroll = new android.widget.HorizontalScrollView(vctx);
        customScroll.setHorizontalScrollBarEnabled(false);
        customStrip = new LinearLayout(vctx);
        customStrip.setOrientation(LinearLayout.HORIZONTAL);
        customStrip.setGravity(Gravity.CENTER_VERTICAL);
        customStrip.setPadding(dp(8), 0, dp(8), dp(4));
        customScroll.addView(customStrip);
        customScroll.setVisibility(View.GONE);
        column.addView(customScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));

        // ---- 网页：每个标签一个 WebView，叠在同一个 webFrame 里
        webFrame = new FrameLayout(vctx);
        column.addView(webFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- 标签页网格
        switcher = new FrameLayout(vctx);
        switcher.setVisibility(View.GONE);
        switcher.setClickable(true);
        LinearLayout sw = new LinearLayout(vctx);
        sw.setOrientation(LinearLayout.VERTICAL);
        LinearLayout swHead = new LinearLayout(vctx);
        swHead.setOrientation(LinearLayout.HORIZONTAL);
        swHead.setGravity(Gravity.CENTER_VERTICAL);
        swHead.setPadding(dp(12), dp(6), dp(6), dp(6));
        TextView swNew = barBtn("＋", "新标签页", 22);
        swNew.setOnClickListener(v -> {
            closeSwitcher();
            newUserTab();
        });
        swHead.addView(swNew, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView swTitle = barBtn("标签页", "", 16);
        swTitle.setGravity(Gravity.CENTER);
        swHead.addView(swTitle, new LinearLayout.LayoutParams(0, dp(44), 1f));
        switcherStop = barBtn("⏹ 停止 Pi", "叫停 Pi 的浏览器操作", 13);
        switcherStop.setOnClickListener(v -> {
            togglePiStopped();
            rebuildSwitcher();
        });
        swHead.addView(switcherStop, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        TextView swDone = barBtn("完成", "关闭标签页列表", 15);
        swDone.setPadding(dp(12), 0, dp(12), 0);
        swDone.setOnClickListener(v -> closeSwitcher());
        swHead.addView(swDone, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        sw.addView(swHead, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ScrollView swScroll = new ScrollView(vctx);
        switcherGrid = new LinearLayout(vctx);
        switcherGrid.setOrientation(LinearLayout.VERTICAL);
        switcherGrid.setPadding(dp(10), dp(4), dp(10), dp(16));
        swScroll.addView(switcherGrid);
        sw.addView(swScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        switcher.addView(sw, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(switcher, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- 全屏时的退出按钮
        exitFullBtn = new TextView(vctx);
        exitFullBtn.setText("⤡");
        exitFullBtn.setTextColor(Color.WHITE);
        exitFullBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        exitFullBtn.setGravity(Gravity.CENTER);
        exitFullBtn.setContentDescription("退出全屏");
        GradientDrawable eb = new GradientDrawable();
        eb.setShape(GradientDrawable.OVAL);
        eb.setColor(Color.parseColor("#99000000"));
        exitFullBtn.setBackground(eb);
        exitFullBtn.setAlpha(0.75f);
        exitFullBtn.setVisibility(View.GONE);
        exitFullBtn.setOnClickListener(v -> setFullscreen(false));
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(dp(38), dp(38), Gravity.TOP | Gravity.END);
        elp.setMargins(0, dp(10), dp(10), 0);
        root.addView(exitFullBtn, elp);

        // ---- 悬浮球（全屏、刷新、翻译、主题、缩放、终端、问 Pi…）
        buildBall();

        switchTo(newTab(false));
        reloadConfigIfChanged();
        layoutOffscreen();
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
        /** 导航代数：onPageStarted / SPA 路由变化时 +1；主框架加载完成时 finishedSeq = navSeq。供 BrowserAgent 判断「新页面真的加载完了」 */
        volatile int navSeq = 0;
        volatile int finishedSeq = 0;
        /** 当前主框架最近一次加载错误（新导航开始时清空）；errorCode 0 = 无错误 */
        volatile int errorCode = 0;
        volatile String errorDesc = "";
        /** 主框架 HTTP 状态码（仅 >=400 时记录，0 = 未知/正常） */
        volatile int httpStatus = 0;
        /** 用户最后一次在这个标签里真实点击/按键的时间（SystemClock.uptimeMillis），0 = 从没有 */
        volatile long userInputAt = 0;
        /** 同一标签上的 Pi 操作串行；不同标签可以并行 */
        final Object lock = new Object();
        int busy = 0;            // 只在主线程读写
        boolean agentVisible;    // 后台标签被 Pi 操作时临时可见（压在当前标签下面）
        boolean desktop;         // 桌面版网站
        Bitmap thumb;            // 标签页网格里的缩略图
        volatile boolean translated; // 网页翻译已开启（悬浮球显示「原」）
        String autoAuthKey;      // 已自动用保存的账号密码登录过的 host|realm（失败时改为弹窗，避免死循环）
        long lastUsed = System.currentTimeMillis();

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
        if (host == null) layoutOffscreen();
        return t;
    }

    private void showHttpAuthDialog(Tab t, android.webkit.HttpAuthHandler handler, String hostName, String realm, boolean failed) {
        Activity h = host;
        if (h == null || h.isFinishing()) {
            handler.cancel();
            Toast.makeText(ctx, hostName + " 需要登录，打开浏览器后刷新", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout box = new LinearLayout(h);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        TextView tip = new TextView(h);
        tip.setText((failed ? "保存的账号密码不对，请重新输入。\n" : "") + hostName
                + (realm != null && !realm.isEmpty() ? "（" + realm + "）" : "") + " 要求登录");
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        box.addView(tip);
        EditText user = new EditText(h);
        user.setHint("用户名");
        user.setSingleLine(true);
        box.addView(user);
        EditText pass = new EditText(h);
        pass.setHint("密码");
        pass.setSingleLine(true);
        pass.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(pass);
        android.widget.CheckBox remember = new android.widget.CheckBox(h);
        remember.setText("记住账号密码");
        remember.setChecked(true);
        box.addView(remember);
        try {
            String[] saved = android.webkit.WebViewDatabase.getInstance(ctx).getHttpAuthUsernamePassword(hostName, realm);
            if (saved != null && saved.length >= 1 && saved[0] != null) user.setText(saved[0]);
        } catch (Throwable ignored) {}
        final boolean[] done = {false};
        AlertDialog d = new AlertDialog.Builder(h).setTitle("登录").setView(box)
                .setPositiveButton("登录", (di, w) -> {
                    done[0] = true;
                    String u = user.getText().toString(), p = pass.getText().toString();
                    if (remember.isChecked()) {
                        try { android.webkit.WebViewDatabase.getInstance(ctx).setHttpAuthUsernamePassword(hostName, realm, u, p); } catch (Throwable ignored) {}
                    }
                    t.autoAuthKey = hostName + "|" + realm; // 这次的账号若再被拒，下次直接弹窗
                    handler.proceed(u, p);
                })
                .setNegativeButton("取消", (di, w) -> { done[0] = true; handler.cancel(); })
                .create();
        d.setOnDismissListener(di -> { if (!done[0]) handler.cancel(); });
        d.show();
    }

    private void recoverTab(Tab t, WebView dead, boolean crashed) {
        if (t == null || t.closed || t.web != dead) return;
        if (sPendingFileWebView == dead) cancelPendingFileChooser();
        int idx = webFrame.indexOfChild(dead);
        try { webFrame.removeView(dead); } catch (Throwable ignored) {}
        try { dead.destroy(); } catch (Throwable ignored) {}
        t.loading = false;
        t.translated = false;
        t.web = createWebView(t);
        webFrame.addView(t.web, Math.max(0, idx), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (active == t) web = t.web;
        applyVisibility();
        String u = t.url;
        if (u != null && !u.isEmpty() && !u.startsWith("about:")) t.web.loadUrl(u);
        else goHome(t, false);
        if (active == t) {
            updateProgress();
            refreshBall();
        }
        Toast.makeText(ctx, crashed ? "网页崩溃了（可能内存不足），已重新加载" : "网页被系统回收，已重新加载", Toast.LENGTH_LONG).show();
    }

    private void switchTo(Tab t) {
        if (t == null || t.closed) return;
        if (active != null && active != t) snapThumb(active);
        active = t;
        web = t.web;
        t.lastUsed = System.currentTimeMillis();
        applyVisibility();
        updateProgress();
        refreshBall();
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

    /** 用户自己切过标签（之后 Pi 新建标签就不再自动切到前台） */
    private boolean userTouched = false;

    private Tab busyPiTab() {
        for (Tab x : tabs) if (x.pi && x.busy > 0) return x;
        return null;
    }

    /** 刷新标签数按钮、Pi 状态条、标签页网格（名字沿用旧版，到处都在调用） */
    private void refreshTabs() {
        if (tabCountBtn == null) return;
        int n = tabs.size();
        tabCountBtn.setText(n > 99 ? ":D" : String.valueOf(n));
        Tab busy = busyPiTab();
        GradientDrawable tb = new GradientDrawable();
        tb.setCornerRadius(dp(5));
        tb.setStroke(dp(2), busy != null ? cAccent : cFg);
        tabCountBtn.setBackground(tb);
        tabCountBtn.setTextColor(busy != null ? cAccent : cFg);
        if (busy != null && busyPulse == null) {
            busyPulse = android.animation.ObjectAnimator.ofFloat(tabCountBtn, "alpha", 1f, 0.4f);
            busyPulse.setDuration(600);
            busyPulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            busyPulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            busyPulse.start();
        } else if (busy == null && busyPulse != null) {
            busyPulse.cancel();
            busyPulse = null;
            tabCountBtn.setAlpha(1f);
        }

        // Pi 状态条：Pi 正在一个用户没在看的标签里操作，或者已经被叫停
        boolean showBanner = !fullscreen && (sPiStopped || (busy != null && busy != active));
        if (showBanner) {
            piBannerText.setText(sPiStopped ? "已叫停 Pi：它的浏览器操作都会被拒绝"
                    : "🤖 Pi 正在后台标签操作" + (busy.title.isEmpty() ? "" : "：" + busy.title));
            piBannerView.setVisibility(sPiStopped ? View.GONE : View.VISIBLE);
            piBannerStop.setText(sPiStopped ? "▶ 允许 Pi" : "⏹ 停止");
        }
        piBanner.setVisibility(showBanner ? View.VISIBLE : View.GONE);
        if (switcher.getVisibility() == View.VISIBLE) rebuildSwitcher();
    }

    private void togglePiStopped() {
        sPiStopped = !sPiStopped;
        if (sPiStopped) for (Tab x : tabs) if (x.pi && x.web != null) x.web.stopLoading();
        Toast.makeText(ctx, sPiStopped ? "已叫停 Pi：它之后的浏览器操作都会被拒绝" : "已允许 Pi 继续操作浏览器", Toast.LENGTH_SHORT).show();
        refreshTabs();
    }

    private void newUserTab() {
        userTouched = true;
        Tab t = newTab(false);
        switchTo(t);
        goHome(t, true);
    }

    // ---- 标签页网格

    private void openSwitcher() {
        hideIme();
        snapThumb(active);
        rebuildSwitcher();
        switcher.setVisibility(View.VISIBLE);
        switcher.bringToFront();
    }

    private void closeSwitcher() {
        if (switcher.getVisibility() != View.VISIBLE) return;
        switcher.setVisibility(View.GONE);
        switcherGrid.removeAllViews();
    }

    /** 给标签拍一张缩略图（只在界面可见时有效） */
    private void snapThumb(Tab t) {
        if (t == null || t.web == null || !t.web.isShown()) return;
        try {
            int w = t.web.getWidth(), h = t.web.getHeight();
            if (w <= 0 || h <= 0) return;
            float s = Math.min(1f, 360f / w);
            Bitmap b = Bitmap.createBitmap(Math.max(1, Math.round(w * s)), Math.max(1, Math.round(Math.min(h, w * 1.25f) * s)), Bitmap.Config.RGB_565);
            Canvas c = new Canvas(b);
            c.scale(s, s);
            c.translate(-t.web.getScrollX(), -t.web.getScrollY());
            t.web.draw(c);
            if (t.thumb != null) t.thumb.recycle();
            t.thumb = b;
        } catch (Throwable ignored) {}
    }

    private void rebuildSwitcher() {
        switcherGrid.removeAllViews();
        switcher.setBackgroundColor(cDark ? Color.parseColor("#FF111214") : Color.parseColor("#FFF1F3F4"));
        boolean anyPi = lastPiTab() != null;
        switcherStop.setVisibility(anyPi || sPiStopped ? View.VISIBLE : View.GONE);
        switcherStop.setText(sPiStopped ? "▶ 允许 Pi" : "⏹ 停止 Pi");
        switcherStop.setTextColor(sPiStopped ? Color.parseColor("#D73A49") : cFg);
        LinearLayout row = null;
        int i = 0;
        for (Tab x : tabs) {
            if (i % 2 == 0) {
                row = new LinearLayout(vctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                switcherGrid.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(232)));
            }
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            clp.setMargins(dp(6), dp(6), dp(6), dp(6));
            row.addView(tabCard(x), clp);
            i++;
        }
        if (i % 2 == 1 && row != null) {
            View pad = new View(vctx);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            clp.setMargins(dp(6), dp(6), dp(6), dp(6));
            row.addView(pad, clp);
        }
    }

    private View tabCard(Tab x) {
        LinearLayout card = new LinearLayout(vctx);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(x == active ? cAccent : (cDark ? Color.parseColor("#FF2A2B2F") : Color.WHITE));
        card.setBackground(bg);
        card.setPadding(dp(3), 0, dp(3), dp(3));
        card.setClipToOutline(true);

        LinearLayout head = new LinearLayout(vctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(8), 0, 0, 0);
        TextView title = new TextView(vctx);
        String tt = x.title;
        if (tt == null || tt.isEmpty() || isLauncher(x.url)) tt = isLauncher(x.url) || x.url.isEmpty() ? "新标签页" : x.url.replaceFirst("^https?://", "");
        title.setText((x.pi ? "🤖 " : "") + tt + (x.busy > 0 ? " ●" : ""));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        int headFg = x == active ? Color.WHITE : cFg;
        title.setTextColor(headFg);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = new TextView(vctx);
        close.setText("✕");
        close.setGravity(Gravity.CENTER);
        close.setTextColor(headFg);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        close.setContentDescription("关闭标签");
        close.setOnClickListener(v -> {
            closeTab(x);
            rebuildSwitcher();
        });
        head.addView(close, new LinearLayout.LayoutParams(dp(32), dp(32)));
        card.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));

        FrameLayout body = new FrameLayout(vctx);
        GradientDrawable bb = new GradientDrawable();
        bb.setCornerRadius(dp(11));
        bb.setColor(pageColor);
        body.setBackground(bb);
        body.setClipToOutline(true);
        if (x.thumb != null && !x.thumb.isRecycled()) {
            ImageView iv = new ImageView(vctx);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(x.thumb);
            body.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            TextView letter = new TextView(vctx);
            String host = Uri.parse(x.url == null ? "" : x.url).getHost();
            letter.setText(x.pi ? "🤖" : isLauncher(x.url) || host == null || host.isEmpty() ? "🌐" : host.replaceFirst("^www\\.", "").substring(0, 1).toUpperCase());
            letter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
            letter.setTextColor(cDim);
            letter.setGravity(Gravity.CENTER);
            body.addView(letter, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        card.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        card.setOnClickListener(v -> {
            userTouched = true;
            closeSwitcher();
            switchTo(x);
        });
        card.setContentDescription((x.pi ? "Pi 标签 " : "标签 ") + tt);
        return card;
    }

    // ---- 菜单

    private void showMainMenu(View anchor) {
        if (host == null) return;
        hideIme();
        PopupMenu pm = new PopupMenu(host, anchor);
        Menu m = pm.getMenu();
        String u = currentUrl();
        boolean webPage = u.startsWith("http://") || u.startsWith("https://");
        m.add(0, 1, 0, "新标签页");
        m.add(0, 2, 0, "前进").setEnabled(web != null && web.canGoForward());
        m.add(0, 3, 0, "刷新");
        m.add(0, 4, 0, isBookmarked(u) ? "★ 已加书签（点按移除）" : "☆ 添加书签").setEnabled(webPage);
        m.add(0, 5, 0, "书签");
        m.add(0, 6, 0, "下载内容");
        m.add(0, 7, 0, active != null && active.translated ? "显示原文" : "网页翻译").setEnabled(webPage);
        m.add(0, 8, 0, "页面缩放…");
        m.add(0, 9, 0, "桌面版网站").setCheckable(true).setChecked(active != null && active.desktop);
        m.add(0, 10, 0, fullscreen ? "退出全屏" : "全屏");
        m.add(0, 11, 0, "分享…").setEnabled(webPage);
        m.add(0, 12, 0, "复制链接").setEnabled(webPage);
        m.add(0, 13, 0, "用其他浏览器打开").setEnabled(webPage);
        if (lastPiTab() != null || sPiStopped) m.add(0, 14, 0, sPiStopped ? "▶ 允许 Pi 操作浏览器" : "⏹ 停止 Pi 操作浏览器");
        m.add(0, 16, 0, "Pi 工作台（新标签打开）");
        m.add(0, 17, 0, "回到 PiMet 工作台");
        m.add(0, 18, 0, "悬浮球").setCheckable(true).setChecked(ballEnabled());
        m.add(0, 15, 0, "关闭浏览器（关闭全部标签）");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: newUserTab(); break;
                case 2: if (web != null && web.canGoForward()) web.goForward(); break;
                case 3: reload(); break;
                case 4: toggleBookmark(); break;
                case 5: showBookmarks(); break;
                case 6: showDownloads(); break;
                case 7: toggleTranslate(); break;
                case 8: showZoomDialog(); break;
                case 9: toggleDesktop(); break;
                case 10: setFullscreen(!fullscreen); break;
                case 11: shareUrl(); break;
                case 12: copyUrl(); break;
                case 13: openInOtherBrowser(); break;
                case 14: togglePiStopped(); break;
                case 15: confirmCloseBrowser(); break;
                case 16: openPiWebTab(); break;
                case 17: openWorkbench(); break;
                case 18: setBallEnabled(!ballEnabled()); break;
                default: return false;
            }
            return true;
        });
        pm.show();
    }

    /** 🧩：列出 config.json 里的扩展按钮（由 Pi 编辑） */
    private void showExtensionsMenu(View anchor) {
        if (host == null) return;
        hideIme();
        PopupMenu pm = new PopupMenu(host, anchor);
        Menu m = pm.getMenu();
        JSONArray btns = config.optJSONArray("buttons");
        int n = 0;
        if (btns != null) {
            for (int i = 0; i < btns.length(); i++) {
                JSONObject b = btns.optJSONObject(i);
                if (b == null) continue;
                m.add(0, 100 + i, 0, buttonLabel(b, true));
                n++;
            }
        }
        if (n == 0) m.add(0, 1, 0, "还没有扩展按钮：跟 Pi 说「给 Pi 浏览器加一个…」").setEnabled(false);
        m.add(0, 2, 0, "🏠 Pi 做的页面");
        pm.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == 2) {
                goHome();
                return true;
            }
            if (id >= 100 && btns != null) {
                JSONObject b = btns.optJSONObject(id - 100);
                if (b != null) runCustomButton(b);
                return true;
            }
            return false;
        });
        pm.show();
    }

    private static String buttonLabel(JSONObject b, boolean withTitle) {
        String icon = b.optString("icon", "");
        String label = b.optString("label", "");
        String title = b.optString("title", "");
        String main = label.isEmpty() ? (withTitle && !title.isEmpty() ? title : "") : label;
        if (main.isEmpty()) return icon.isEmpty() ? "●" : icon;
        return icon.isEmpty() ? main : icon + "  " + main;
    }

    private void showSiteInfo() {
        if (host == null) return;
        String u = currentUrl();
        if (isLauncher(u) || u.isEmpty()) {
            urlField.requestFocus();
            InputMethodManager imm = (InputMethodManager) vctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(urlField, 0);
            return;
        }
        String msg = u.startsWith("https://") ? "连接是安全的（HTTPS）" : u.startsWith("http://") ? "连接不安全（HTTP）" : "本地页面";
        new AlertDialog.Builder(host).setTitle(Uri.parse(u).getHost() == null ? "页面信息" : Uri.parse(u).getHost())
                .setMessage(msg + "\n\n" + u).setPositiveButton("好", null).show();
    }

    // ---- 书签

    private JSONArray bookmarks() {
        try {
            return new JSONArray(prefs().getString("bookmarks", "[]"));
        } catch (Throwable t) {
            return new JSONArray();
        }
    }

    private boolean isBookmarked(String url) {
        if (url == null || url.isEmpty()) return false;
        JSONArray a = bookmarks();
        for (int i = 0; i < a.length(); i++) if (url.equals(a.optJSONObject(i) == null ? "" : a.optJSONObject(i).optString("url"))) return true;
        return false;
    }

    private void toggleBookmark() {
        String u = currentUrl();
        if (u.isEmpty() || isLauncher(u)) return;
        JSONArray a = bookmarks();
        JSONArray out = new JSONArray();
        boolean removed = false;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            if (u.equals(o.optString("url"))) removed = true;
            else out.put(o);
        }
        if (!removed) {
            try {
                String title = active != null && !active.title.isEmpty() ? active.title : u;
                out.put(new JSONObject().put("title", title).put("url", u));
            } catch (Throwable ignored) {}
        }
        prefs().edit().putString("bookmarks", out.toString()).apply();
        Toast.makeText(ctx, removed ? "已移除书签" : "已添加书签", Toast.LENGTH_SHORT).show();
    }

    private void showBookmarks() {
        if (host == null) return;
        JSONArray a = bookmarks();
        if (a.length() == 0) {
            new AlertDialog.Builder(host).setTitle("书签").setMessage("还没有书签。打开网页后在 ⋮ 菜单点「☆ 添加书签」。")
                    .setPositiveButton("好", null).show();
            return;
        }
        String[] items = new String[a.length()];
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            items[i] = o == null ? "" : o.optString("title", o.optString("url"));
        }
        AlertDialog d = new AlertDialog.Builder(host).setTitle("书签（长按删除）")
                .setItems(items, (dlg, which) -> {
                    JSONObject o = a.optJSONObject(which);
                    if (o != null) load(o.optString("url"));
                })
                .setNegativeButton("关闭", null).create();
        d.setOnShowListener(x -> d.getListView().setOnItemLongClickListener((p, v, pos, id) -> {
            JSONArray out = new JSONArray();
            for (int i = 0; i < a.length(); i++) if (i != pos) out.put(a.opt(i));
            prefs().edit().putString("bookmarks", out.toString()).apply();
            Toast.makeText(ctx, "已删除书签", Toast.LENGTH_SHORT).show();
            d.dismiss();
            if (isLauncher(currentUrl())) goHome();
            return true;
        }));
        d.show();
    }

    private void showDownloads() {
        if (host == null) return;
        new AlertDialog.Builder(host).setTitle("下载内容").setMessage(downloadsText(ctx))
                .setPositiveButton("打开系统下载", (d, w) -> {
                    try {
                        Intent i = new Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(i);
                    } catch (Throwable t) {
                        Toast.makeText(ctx, "无法打开系统下载管理", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("关闭", null).show();
    }

    // ---- 页面缩放 / 桌面版

    /**
     * 页面缩放和 Pi-Web 工作台同一套逻辑：改布局宽度让页面重排（25%–300%），不是放大镜。
     * Pi-Web 等本地页面与工作台共用一个值；外部网站另存一个值（所有外部网站共用）；桌面版网站不改动。
     */
    private int zoomFor(Tab t, String url) {
        if (WebPinchZoom.isLocal(url)) return WebPinchZoom.getZoom(ctx);
        if (t != null && t.desktop) return 100;
        return WebPinchZoom.clamp(prefs().getInt("page_zoom", 100));
    }

    private WebPinchZoom.Store zoomStore(Tab t) {
        return new WebPinchZoom.Store() {
            @Override
            public int get(String url) {
                return zoomFor(t, url);
            }

            @Override
            public void set(String url, int pct) {
                saveZoom(t, url, pct, t);
            }
        };
    }

    /** 保存缩放值并应用到同类页面的其它标签（skip 那个标签已由手势自己应用） */
    private void saveZoom(Tab from, String url, int pct, Tab skip) {
        boolean local = WebPinchZoom.isLocal(url);
        if (!local && from != null && from.desktop) {
            MAIN.post(() -> Toast.makeText(ctx, "桌面版网站不支持缩放，先在 ⋮ 里关掉桌面版", Toast.LENGTH_SHORT).show());
            return;
        }
        int z = WebPinchZoom.clamp(pct);
        if (local) com.xm486.pimet.PiMetConfig.setWebZoom(ctx, z);
        else prefs().edit().putInt("page_zoom", z).apply();
        MAIN.post(() -> {
            for (Tab t : tabs) {
                if (t == skip || t.web == null || WebPinchZoom.isLocal(t.url) != local) continue;
                applyZoom(t);
            }
            refreshBall();
        });
    }

    private void applyZoom(Tab t) {
        if (t == null || t.web == null) return;
        String u = t.web.getUrl();
        if (u == null) u = t.url;
        if (u == null || u.startsWith("about:")) return;
        t.web.getSettings().setTextZoom(100);
        WebPinchZoom.applyPercent(t.web, zoomFor(t, u));
    }

    /** 和工作台一样的缩放面板：预设 50/75/100/125/150、自定义 25–300、完成 */
    private void showZoomDialog() {
        if (host == null || active == null) return;
        final Tab tab = active;
        final String url = tab.url;
        final boolean local = WebPinchZoom.isLocal(url);
        ThemeManager.ThemePalette palette = ThemeManager.getEffectivePalette(host);
        final int minZ = WebPinchZoom.MIN_ZOOM, maxZ = WebPinchZoom.MAX_ZOOM;
        final int onPrimary = palette.isDark ? 0xFF0D1117 : 0xFFFFFFFF;

        LinearLayout layout = new LinearLayout(host);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(16), dp(24), dp(16));
        layout.setBackground(ThemeManager.createCardDrawable(host, palette, 14f));

        TextView title = new TextView(host);
        title.setText(local ? "工作台显示缩放" : "页面缩放");
        title.setTextSize(16f);
        title.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
        title.setTextColor(palette.text);
        layout.addView(title);

        TextView tvVal = new TextView(host);
        tvVal.setTextSize(13f);
        tvVal.setTextColor(palette.accent);
        tvVal.setPadding(0, dp(8), 0, dp(12));
        layout.addView(tvVal);

        LinearLayout presetRow = new LinearLayout(host);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        presetRow.setGravity(Gravity.CENTER);
        final int[] presets = {50, 75, 100, 125, 150};
        final List<TextView> presetViews = new ArrayList<>();
        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            int z = zoomFor(tab, url);
            tvVal.setText("当前 " + z + "% · 也可双指缩放" + (local ? "（与工作台同步）" : ""));
            for (int i = 0; i < presets.length; i++) {
                boolean on = presets[i] == z;
                presetViews.get(i).setTextColor(on ? onPrimary : palette.textMuted);
                presetViews.get(i).setBackground(on
                        ? ThemeManager.createPrimaryButtonDrawable(host, palette, 6f)
                        : ThemeManager.createSecondaryButtonDrawable(host, palette, 6f));
            }
        };
        for (int i = 0; i < presets.length; i++) {
            final int pv = presets[i];
            TextView b = new TextView(host);
            b.setText(pv + "%");
            b.setTextSize(12f);
            b.setSingleLine(true);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, dp(8), 0, dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = dp(6);
            b.setOnClickListener(v -> {
                saveZoom(tab, url, pv, null);
                MAIN.post(refresh[0]);
            });
            presetViews.add(b);
            presetRow.addView(b, lp);
        }
        layout.addView(presetRow);

        LinearLayout customRow = new LinearLayout(host);
        customRow.setOrientation(LinearLayout.HORIZONTAL);
        customRow.setGravity(Gravity.CENTER_VERTICAL);
        customRow.setPadding(0, dp(12), 0, 0);
        EditText et = new EditText(host);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(3)});
        et.setSingleLine(true);
        et.setHint("自定义 " + minZ + "–" + maxZ);
        et.setTextSize(13f);
        et.setTextColor(palette.text);
        et.setHintTextColor(palette.textDim);
        et.setBackground(ThemeManager.createSecondaryButtonDrawable(host, palette, 8f));
        et.setPadding(dp(12), dp(8), dp(12), dp(8));
        et.setImeOptions(EditorInfo.IME_ACTION_DONE);
        customRow.addView(et, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView pct = new TextView(host);
        pct.setText("%");
        pct.setTextSize(13f);
        pct.setTextColor(palette.textMuted);
        pct.setPadding(dp(6), 0, dp(8), 0);
        customRow.addView(pct);
        TextView apply = new TextView(host);
        apply.setText("应用");
        apply.setTextSize(12f);
        apply.setSingleLine(true);
        apply.setTextColor(palette.accent);
        apply.setBackground(ThemeManager.createSecondaryButtonDrawable(host, palette, 8f));
        apply.setPadding(dp(14), dp(8), dp(14), dp(8));
        customRow.addView(apply);
        layout.addView(customRow);

        final Runnable applyCustom = () -> {
            String txt = et.getText() == null ? "" : et.getText().toString().trim();
            int v;
            try {
                v = Integer.parseInt(txt);
            } catch (NumberFormatException e) {
                Toast.makeText(ctx, "请输入 " + minZ + "–" + maxZ + " 之间的数字", Toast.LENGTH_SHORT).show();
                return;
            }
            int z = WebPinchZoom.clamp(v);
            if (z != v) Toast.makeText(ctx, "已限制在 " + minZ + "–" + maxZ + "%，应用 " + z + "%", Toast.LENGTH_SHORT).show();
            saveZoom(tab, url, z, null);
            et.setText(String.valueOf(z));
            et.setSelection(et.getText().length());
            MAIN.post(refresh[0]);
        };
        apply.setOnClickListener(v -> applyCustom.run());
        et.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                applyCustom.run();
                return true;
            }
            return false;
        });
        refresh[0].run();

        TextView done = new TextView(host);
        done.setText("完成");
        done.setTextSize(13f);
        done.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
        done.setGravity(Gravity.CENTER);
        done.setTextColor(onPrimary);
        done.setBackground(ThemeManager.createPrimaryButtonDrawable(host, palette, 8f));
        done.setPadding(0, dp(12), 0, dp(12));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(16);
        layout.addView(done, dlp);

        AlertDialog dialog = new AlertDialog.Builder(host).setView(layout).create();
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        done.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private static volatile String sDefaultUa;

    private void toggleDesktop() {
        Tab t = active;
        if (t == null || t.web == null) return;
        WebSettings ws = t.web.getSettings();
        if (sDefaultUa == null) sDefaultUa = WebSettings.getDefaultUserAgent(ctx);
        t.desktop = !t.desktop;
        if (t.desktop) {
            String ver = "120.0.0.0";
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Chrome/([\\d.]+)").matcher(sDefaultUa);
            if (m.find()) ver = m.group(1);
            ws.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + ver + " Safari/537.36");
        } else {
            ws.setUserAgentString(null);
        }
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
 applyZoom(t);
        t.web.reload();
        Toast.makeText(ctx, t.desktop ? "已切换为桌面版网站" : "已切换为移动版网站", Toast.LENGTH_SHORT).show();
    }

    // ---- 分享 / 复制 / 外部浏览器

    private void shareUrl() {
        String u = currentUrl();
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, u);
        if (active != null && !active.title.isEmpty()) i.putExtra(Intent.EXTRA_SUBJECT, active.title);
        Intent c = Intent.createChooser(i, "分享网页");
        c.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { ctx.startActivity(c); } catch (Throwable ignored) {}
    }

    private void copyUrl() {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("url", currentUrl()));
        Toast.makeText(ctx, "已复制链接", Toast.LENGTH_SHORT).show();
    }

    private void openInOtherBrowser() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl()));
            Intent c = Intent.createChooser(i, "用其他浏览器打开");
            c.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(c);
        } catch (Throwable t) {
            Toast.makeText(ctx, "没有可用的浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmCloseBrowser() {
        if (host == null) return;
        new AlertDialog.Builder(host).setTitle("关闭浏览器？").setMessage("会关闭全部 " + tabs.size() + " 个标签（登录状态会保留）。")
                .setPositiveButton("关闭", (d, w) -> {
                    sUserClosed = true;
                    dismiss();
                })
                .setNegativeButton("取消", null).show();
    }

    /** 首页里的 pimet:// 链接（只有应用目录里的本地页面能用） */
    private void handlePimetLink(String u) {
        if (u.startsWith("pimet://bookmarks")) showBookmarks();
        else if (u.startsWith("pimet://downloads")) showDownloads();
        else if (u.startsWith("pimet://tabs")) openSwitcher();
        else if (u.startsWith("pimet://tab/")) {
            Tab t = findTab(u.substring("pimet://tab/".length()));
            if (t != null) {
                userTouched = true;
                switchTo(t);
            }
        }
    }


    @SuppressLint({"ClickableViewAccessibility", "SetJavaScriptEnabled", "JavascriptInterface"})
    private WebView createWebView(Tab t) {
        WebView w = new WebView(vctx) {
            // 记录用户真实触摸 / 按键（Pi 的合成触摸 sAgentTouch=true 不算），BrowserAgent 据此判断「用户接手了」
            @Override
            public boolean dispatchTouchEvent(android.view.MotionEvent e) {
                if (!sAgentTouch && e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) t.userInputAt = android.os.SystemClock.uptimeMillis();
                return super.dispatchTouchEvent(e);
            }

            @Override
            public boolean dispatchKeyEvent(android.view.KeyEvent e) {
                int kc = e.getKeyCode();
                boolean sysKey = kc == android.view.KeyEvent.KEYCODE_VOLUME_UP || kc == android.view.KeyEvent.KEYCODE_VOLUME_DOWN || kc == android.view.KeyEvent.KEYCODE_VOLUME_MUTE
                        || kc == android.view.KeyEvent.KEYCODE_POWER || kc == android.view.KeyEvent.KEYCODE_HEADSETHOOK || kc == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                        || kc == android.view.KeyEvent.KEYCODE_CAMERA;
                if (!sAgentTouch && !sysKey && e.getAction() == android.view.KeyEvent.ACTION_DOWN && e.getDeviceId() != android.view.KeyCharacterMap.VIRTUAL_KEYBOARD) t.userInputAt = android.os.SystemClock.uptimeMillis();
                return super.dispatchKeyEvent(e);
            }
        };
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
        // 双指布局缩放：和 Pi-Web 工作台同一套（所有网页重排适配屏宽，不横向溢出）
        WebPinchZoom.attach(w, (pct, committed) -> { if (committed) MAIN.post(this::refreshBall); }, zoomStore(t));
        ws.setTextZoom(100);
        w.setBackgroundColor(pageColor);
        w.addJavascriptInterface(new Bridge(t), "PiMetNative");
        w.addJavascriptInterface(new TranslatorBridge(t), "PiMetTranslator");
        w.addJavascriptInterface(new ThemeBridge(t), "PiMetTheme");
        w.setDownloadListener((url, ua, contentDisposition, mime, length) -> startDownload(t, url, ua, contentDisposition, mime));
        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String u = req.getUrl().toString();
                if (u.startsWith("pimet://")) {
                    if (!t.pi && isTrusted(t.url)) MAIN.post(() -> handlePimetLink(u));
                    return true;
                }
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
                t.navSeq++;
                t.errorCode = 0;
                t.errorDesc = "";
                t.httpStatus = 0;
                t.loading = true;
                t.translated = false;
                if (t == active) { updateProgress(); refreshBall(); }
                t.url = url != null ? url : "";
                showUrl(t, url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                t.autoAuthKey = null;
                t.loading = false;
                t.finishedSeq = t.navSeq;
                if (t == active) updateProgress();
                applyZoom(t);
                if (WebPinchZoom.isLocal(url)) {
                    // Pi-Web：主题同步、键盘误判修正（和工作台一致）
                    ThemeManager.syncThemeToWebView(view, ctx);
                    PiWebCompat.apply(view, url);
                }
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
            public void onPageCommitVisible(WebView view, String url) {
                applyZoom(t);
                if (WebPinchZoom.isLocal(url)) PiWebCompat.apply(view, url);
            }

            /** HTTP 基本认证（如 ttyd、路由器后台、Nginx auth_basic）：WebView 默认直接取消 → 白屏，这里弹登录框 */
            @Override
            public void onReceivedHttpAuthRequest(WebView view, android.webkit.HttpAuthHandler handler, String hostName, String realm) {
                String key = hostName + "|" + realm;
                if (handler.useHttpAuthUsernamePassword() && !key.equals(t.autoAuthKey)) {
                    try {
                        String[] saved = android.webkit.WebViewDatabase.getInstance(ctx).getHttpAuthUsernamePassword(hostName, realm);
                        if (saved != null && saved.length >= 2 && saved[0] != null && saved[1] != null) {
                            t.autoAuthKey = key;
                            handler.proceed(saved[0], saved[1]);
                            return;
                        }
                    } catch (Throwable ignored) {}
                }
                showHttpAuthDialog(t, handler, hostName, realm, key.equals(t.autoAuthKey));
            }

            /** 网页渲染进程崩溃/被系统回收（常见于 AI 网站压缩大图时内存不足）：不让整个 App 跟着退出，重建这个标签 */
            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                boolean crashed = detail != null && detail.didCrash();
                Log.w(TAG, "render process gone, crashed=" + crashed + " url=" + t.url);
                MAIN.post(() -> recoverTab(t, view, crashed));
                return true;
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                // SPA 路由（pushState / hash）不触发 onPageStarted：算一次已完成的导航
                if (!t.loading) { t.navSeq++; t.finishedSeq = t.navSeq; }
                t.url = url != null ? url : "";
                showUrl(t, url);
            }

            @Override
            public void onReceivedError(WebView view, android.webkit.WebResourceRequest request, android.webkit.WebResourceError error) {
                if (request == null || !request.isForMainFrame() || error == null) return;
                t.errorCode = error.getErrorCode() == 0 ? -1 : error.getErrorCode();
                CharSequence d = error.getDescription();
                t.errorDesc = d != null ? d.toString() : "";
            }

            @Override
            public void onReceivedHttpError(WebView view, android.webkit.WebResourceRequest request, android.webkit.WebResourceResponse resp) {
                if (request == null || !request.isForMainFrame() || resp == null) return;
                t.httpStatus = resp.getStatusCode();
            }
        });
        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                t.progress = newProgress;
                if (t == active) updateProgress();
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (t == active) showCustomView(view, callback);
                else callback.onCustomViewHidden();
            }

            @Override
            public void onHideCustomView() {
                hideCustomView();
            }

            // 没有界面（Pi 在后台用）时网页弹窗没有窗口可依附：直接按默认结果返回，不卡住页面
            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                if (host == null || t.pi) { result.confirm(); return true; }
                return false;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                if (host == null) { result.cancel(); return true; }
                return false;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
                if (host == null) { result.cancel(); return true; }
                return false;
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
                    Activity h = host;
                    if (h != null && !h.isFinishing()) {
                        // 在浏览器自己的任务里打开，选完图片回到浏览器（而不是跳回工作台）
                        intent.setFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                        h.startActivity(intent);
                    } else {
                        ctx.startActivity(intent);
                    }
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

    /** 在指定标签里打开首页（restore 参数保留旧签名，已无作用） */
    private void goHome(Tab t, boolean restore) {
        if (t == null || t.web == null) return;
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
        final HomeData hd = homeData(t);
        new Thread(() -> {
            File launcher = new File(appsDir, ".launcher.html");
            try {
                writeIfChanged(launcher, buildLauncherHtml(hd));
            } catch (Throwable e) {
                Log.w(TAG, "write launcher failed", e);
            }
            MAIN.post(() -> { if (t.web != null) t.web.loadUrl("file://" + launcher.getAbsolutePath()); });
        }, "pimet-apps-home").start();
    }

    /** 给 BrowserAgent 用：确保浏览器（标签和 WebView）存在，必须在主线程调用。不会把浏览器窗口弹到前台 */
    static FloatingApps ensureForAgent(Context context) {
        if (sInstance == null) {
            sInstance = new FloatingApps(context.getApplicationContext());
            sInstance.goHome(sInstance.active, false);
            sInstance.freshForAgent = true;
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

    /** 把浏览器窗口切到前台 */
    void agentRestore() {
        sUserClosed = false;
        openActivity(ctx);
    }

    /** 把浏览器窗口退到后台（标签保留） */
    void agentMinimize() {
        if (host != null) {
            try { host.moveTaskToBack(true); } catch (Throwable ignored) {}
        }
    }

    void agentClose() {
        dismiss();
    }

    private void load(String url) {
        loadIn(active, url, true);
    }

    private void loadIn(Tab t, String url, boolean restore) {
        if (t == null || t.web == null) return;
        if (url == null || url.isEmpty()) {
            goHome(t, restore);
            return;
        }
        t.web.loadUrl(url);
    }

    private void updateProgress() {
        Tab t = active;
        if (t == null || fullscreen) {
            progress.setVisibility(fullscreen ? View.GONE : View.INVISIBLE);
            return;
        }
        boolean show = t.loading && t.progress < 100;
        progress.setProgress(Math.max(5, t.progress));
        progress.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
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
        if (url == null || url.isEmpty() || isLauncher(url)) {
            siteIcon.setText("🔍");
            urlField.setText("");
            return;
        }
        String prefix = "file://" + pagesDir.getAbsolutePath() + "/";
        if (url.startsWith(prefix)) {
            String rel = url.substring(prefix.length());
            int slash = rel.indexOf('/');
            String id = slash > 0 ? rel.substring(0, slash) : rel;
            siteIcon.setText("🧩");
            urlField.setText("应用 · " + Uri.decode(id));
        } else if (url.startsWith("http://") || url.startsWith("https://")) {
            // 像 Chrome 一样平时只显示域名 + 路径，点进地址栏才显示完整网址
            siteIcon.setText(url.startsWith("https://") ? "🔒" : "ⓘ");
            String shown = url.replaceFirst("^https?://(www\\.)?", "");
            if (shown.endsWith("/")) shown = shown.substring(0, shown.length() - 1);
            urlField.setText(shown);
        } else {
            siteIcon.setText("ⓘ");
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
        // 默认跟随 Pi-Web 主题（浅色像 Chrome 浅色，深色像 Chrome 深色），config.json 的 theme 可覆盖
        ThemeManager.ThemePalette p = ThemeManager.getEffectivePalette(ctx);
        cDark = p.isDark;
        cBar = color(th, "background", p.bg);
        cFg = color(th, "text", p.text);
        cPill = color(th, "addressBar", cDark ? blend(p.bg, Color.WHITE, 0.10f) : Color.parseColor("#FFE9ECF2"));
        cAddr = color(th, "addressText", p.text);
        cAccent = color(th, "accent", p.accent);
        cBorder = color(th, "border", p.border);
        cDim = p.textDim;
        pageColor = color(th, "page", p.bg);

        root.setBackgroundColor(cBar);
        bar.setBackgroundColor(cBar);
        for (TextView t : new TextView[]{homeBtn, extBtn, menuBtn, siteIcon}) t.setTextColor(cFg);
        GradientDrawable ub = new GradientDrawable();
        ub.setColor(cPill);
        ub.setCornerRadius(dp(22));
        urlPill.setBackground(ub);
        urlField.setTextColor(cAddr);
        urlField.setHintTextColor(cDim);
        try {
            progress.setProgressTintList(android.content.res.ColorStateList.valueOf(cAccent));
            progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT));
        } catch (Throwable ignored) {}
        piBanner.setBackgroundColor(blend(cBar, cAccent, 0.14f));
        piBannerText.setTextColor(cFg);
        piBannerView.setTextColor(cAccent);
        piBannerStop.setTextColor(cAccent);
        for (Tab t : tabs) if (t.web != null) t.web.setBackgroundColor(pageColor);
        applyWindowColors();
        refreshTabs();

        customStrip.removeAllViews();
        JSONArray btns = config.optJSONArray("buttons");
        if (btns != null) {
            for (int i = 0; i < btns.length(); i++) {
                JSONObject b = btns.optJSONObject(i);
                if (b == null) continue;
                TextView t = new TextView(vctx);
                t.setText(buttonLabel(b, false));
                String label = b.optString("label", "");
                String icon = b.optString("icon", "");
                t.setContentDescription(b.optString("title", label));
                t.setTextColor(color(b, "color", cFg));
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                t.setGravity(Gravity.CENTER);
                t.setPadding(dp(10), 0, dp(10), 0);
                GradientDrawable cb = new GradientDrawable();
                cb.setColor(color(th, "button", cPill));
                cb.setCornerRadius(dp(14));
                t.setBackground(cb);
                t.setOnClickListener(v -> runCustomButton(b));
                t.setOnLongClickListener(v -> {
                    Toast.makeText(ctx, b.optString("title", label.isEmpty() ? icon : label), Toast.LENGTH_SHORT).show();
                    return true;
                });
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28));
                blp.setMargins(0, 0, dp(6), 0);
                customStrip.addView(t, blp);
            }
        }
        boolean showBar = config.optBoolean("showButtonBar", false);
        customScroll.setVisibility(showBar && customStrip.getChildCount() > 0 && !fullscreen ? View.VISIBLE : View.GONE);
        styleBall();
    }

    private static int blend(int a, int b, float t) {
        int r = Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t);
        int g = Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bl = Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return Color.rgb(r, g, bl);
    }

    private static boolean isLightColor(int c) {
        return (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) > 160;
    }

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

    /** 首页需要的数据（在主线程取好，再到后台线程生成 HTML） */
    private static final class HomeData {
        JSONArray bookmarks;
        String recentId, recentTitle, recentUrl, piWeb;
        boolean dark;
        String page, fg, card, pill, accent, dim;
    }

    private HomeData homeData(Tab forTab) {
        HomeData h = new HomeData();
        h.bookmarks = bookmarks();
        h.piWeb = piWebUrl();
        Tab recent = null;
        for (Tab x : tabs) {
            if (x == forTab || x.pi || x.url.isEmpty() || isLauncher(x.url)) continue;
            if (recent == null || x.lastUsed > recent.lastUsed) recent = x;
        }
        if (recent != null) {
            h.recentId = recent.id;
            h.recentTitle = recent.title.isEmpty() ? recent.url : recent.title;
            h.recentUrl = recent.url;
        }
        h.dark = cDark;
        h.page = hex(pageColor);
        h.fg = hex(cFg);
        h.card = hex(cDark ? blend(pageColor, Color.WHITE, 0.07f) : Color.WHITE);
        h.pill = hex(cPill);
        h.accent = hex(cAccent);
        h.dim = hex(cDim);
        return h;
    }

    private static String hex(int c) {
        return String.format("#%06X", 0xFFFFFF & c);
    }

    private String buildLauncherHtml(HomeData h) {
        List<AppInfo> apps = scanApps();
        StringBuilder tiles = new StringBuilder();
        tiles.append("<a class=\"tile\" href=\"").append(esc(h.piWeb)).append("\"><div class=\"ico\"><span class=\"e\">π</span></div><div class=\"tn\">Pi 工作台</div></a>");
        for (int i = 0; i < h.bookmarks.length(); i++) {
            JSONObject o = h.bookmarks.optJSONObject(i);
            if (o == null) continue;
            String url = o.optString("url");
            String title = o.optString("title", url);
            String hostName = Uri.parse(url).getHost();
            String letter = hostName == null || hostName.isEmpty() ? "★" : hostName.replaceFirst("^www\\.", "").substring(0, 1).toUpperCase();
            tiles.append("<a class=\"tile\" href=\"").append(esc(url)).append("\"><div class=\"ico\"><span class=\"l\">")
                    .append(esc(letter)).append("</span></div><div class=\"tn\">").append(esc(title)).append("</div></a>");
        }
        for (AppInfo a : apps) {
            String href = "pages/" + Uri.encode(a.id) + "/index.html";
            tiles.append("<a class=\"tile\" href=\"").append(href).append("\"><div class=\"ico\"><span class=\"e\">")
                    .append(esc(a.icon)).append("</span></div><div class=\"tn\">").append(esc(a.name)).append("</div></a>");
        }
        String grid = "<div class=\"panel\"><div class=\"grid\">" + tiles + "</div>"
                + (h.bookmarks.length() == 0 && apps.isEmpty()
                ? "<div class=\"hint\">打开网页后在 ⋮ 菜单点「☆ 添加书签」，常用网站会出现在这里。Pi 做的小页面也会出现在这里。</div>" : "")
                + "</div>";
        String recent = "";
        if (h.recentId != null) {
            String hostName = Uri.parse(h.recentUrl).getHost();
            recent = "<div class=\"panel\"><div class=\"ph\"><span>继续使用此标签页</span><a href=\"pimet://tabs\">查看更多</a></div>"
                    + "<a class=\"recent\" href=\"pimet://tab/" + esc(h.recentId) + "\"><div class=\"rico\">"
                    + esc(hostName == null || hostName.isEmpty() ? "🌐" : hostName.replaceFirst("^www\\.", "").substring(0, 1).toUpperCase())
                    + "</div><div class=\"rt\"><div class=\"rtt\">" + esc(h.recentTitle) + "</div><div class=\"ru\">"
                    + esc(h.recentUrl) + "</div></div></a></div>";
        }
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>新标签页</title><style>"
                + ":root{color-scheme:" + (h.dark ? "dark" : "light") + "}"
                + "*{box-sizing:border-box}body{margin:0;padding:18px 16px 40px;background:" + h.page + ";color:" + h.fg + ";"
                + "font-family:system-ui,-apple-system,'PingFang SC','Noto Sans CJK SC',sans-serif}"
                + "a{color:inherit;text-decoration:none}"
                + ".logo{text-align:center;font-size:30px;font-weight:700;letter-spacing:.5px;margin:6vh 0 18px}"
                + ".logo b{color:" + h.accent + "}"
                + "form{margin:0 0 6px}.search{display:flex;align-items:center;height:50px;border-radius:25px;background:" + h.pill + ";padding:0 18px}"
                + ".search input{flex:1;border:0;outline:0;background:transparent;color:inherit;font-size:16px;margin-left:10px}"
                + ".quick{display:flex;justify-content:space-around;margin:14px 0 18px;font-size:15px;opacity:.85}"
                + ".quick a{display:flex;align-items:center;gap:8px;padding:8px 14px}"
                + ".panel{background:" + h.card + ";border-radius:24px;padding:18px 10px;margin-bottom:16px}"
                + ".hint{padding:22px;text-align:center;color:" + h.dim + ";font-size:13px;line-height:1.8}"
                + ".grid{display:grid;grid-template-columns:repeat(4,1fr);gap:16px 4px}"
                + ".tile{display:flex;flex-direction:column;align-items:center;min-width:0}"
                + ".ico{width:56px;height:56px;border-radius:50%;background:" + h.pill + ";display:flex;align-items:center;justify-content:center}"
                + ".ico .l{width:34px;height:34px;border-radius:50%;background:" + h.dim + ";color:" + h.card + ";display:flex;align-items:center;justify-content:center;font-weight:600}"
                + ".ico .e{font-size:28px}"
                + ".tn{margin-top:8px;font-size:13px;max-width:100%;padding:0 4px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}"
                + ".ph{display:flex;justify-content:space-between;align-items:center;padding:0 10px 12px;font-size:17px;font-weight:600}"
                + ".ph a{color:" + h.accent + ";font-size:15px;font-weight:500}"
                + ".recent{display:flex;gap:12px;align-items:center;padding:4px 10px}"
                + ".rico{flex:none;width:44px;height:44px;border-radius:12px;background:" + h.pill + ";display:flex;align-items:center;justify-content:center;font-weight:600}"
                + ".rt{min-width:0}.rtt{font-size:15px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}"
                + ".ru{font-size:12px;color:" + h.dim + ";white-space:nowrap;overflow:hidden;text-overflow:ellipsis}"
                + ".foot{text-align:center;color:" + h.dim + ";font-size:12px;line-height:1.7;margin-top:20px}"
                + "</style></head><body>"
                + "<div class=\"logo\"><b>Pi</b> 浏览器</div>"
                + "<form id=\"f\"><div class=\"search\"><span>🔍</span><input id=\"q\" type=\"search\" placeholder=\"搜索或输入网址\" autocomplete=\"off\"></div></form>"
                + "<div class=\"quick\"><a href=\"pimet://bookmarks\">⭐ 书签</a><a href=\"pimet://downloads\">⬇️ 下载内容</a></div>"
                + grid + recent
                + "<div class=\"foot\">在工作台跟 Pi 说「用浏览器打开 xxx 帮我…」，它会在 🤖 Pi 标签里操作，你随时能看、能接手。<br>"
                + "扩展按钮、配色、首页也能让 Pi 改：「给 Pi 浏览器加一个…」</div>"
                + "<script>document.getElementById('f').onsubmit=function(e){e.preventDefault();"
                + "var s=document.getElementById('q').value.trim();if(!s)return;"
                + "if(/^[a-zA-Z][a-zA-Z0-9+.-]*:\\/\\//.test(s))location.href=s;"
                + "else if(/^(localhost|127\\.0\\.0\\.1|\\d+\\.\\d+\\.\\d+\\.\\d+)(:\\d+)?(\\/.*)?$/.test(s))location.href='http://'+s;"
                + "else if(s.indexOf(' ')<0&&s.indexOf('.')>0)location.href='https://'+s;"
                + "else location.href='https://www.bing.com/search?q='+encodeURIComponent(s);};</script>"
                + "</body></html>";
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

    // ------------------------------------------------------------------ 悬浮球（和工作台的悬浮球同一套功能）

    private LinearLayout ballWrap;
    private TextView ball;
    private LinearLayout ballMenu;
    private TextView ballFull, ballTranslate, ballZoom;

    private boolean ballEnabled() {
        return prefs().getBoolean("ball_on", true);
    }

    /**
     * 构造时调用：和工作台一模一样的悬浮球（🌐、44dp、默认右上 80dp，拖动时顶部留 64dp），
     * 菜单顺序同工作台：⛶ 全屏 · 🔄 刷新 · 译 · 🎨 主题 · 🌐 回工作台 · >_ 终端 · ↗ 外部浏览器，
     * 另加：缩放百分比 · 🤖 问 Pi · ✕ 收起。
     */
    private void buildBall() {
        ballWrap = new LinearLayout(vctx);
        ballWrap.setOrientation(LinearLayout.VERTICAL);
        ballWrap.setGravity(Gravity.CENTER_HORIZONTAL);
        ballWrap.setElevation(dp(20));
        ballWrap.setOutlineProvider(null);
        ball = new TextView(vctx);
        ball.setText("🌐");
        ball.setGravity(Gravity.CENTER);
        ball.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        ball.setContentDescription("悬浮球（拖动移动，点按展开）");
        ball.setElevation(dp(6));
        ball.setOutlineProvider(null);
        ball.setOnTouchListener(new BallDrag());
        ballWrap.addView(ball, new LinearLayout.LayoutParams(dp(44), dp(44)));

        ballMenu = new LinearLayout(vctx);
        ballMenu.setOrientation(LinearLayout.VERTICAL);
        ballMenu.setGravity(Gravity.CENTER_HORIZONTAL);
        ballMenu.setPadding(0, dp(8), 0, dp(8));
        ballMenu.setVisibility(View.GONE);
        ballFull = ballItem("⛶", "全屏", 16, v -> { setFullscreen(!fullscreen); collapseBall(); });
        ballItem("🔄", "刷新", 14, v -> { reload(); collapseBall(); });
        ballTranslate = ballItem("译", "网页翻译（长按选翻译引擎）", 14, v -> toggleTranslate());
        ballTranslate.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        ballTranslate.setOnLongClickListener(v -> {
            if (host != null && web != null) {
                try { com.xm486.pimet.translation.WebTranslationDialog.show(host, web); } catch (Throwable ignored) {}
            }
            return true;
        });
        ballItem("🎨", "切换主题", 14, v -> cycleTheme());
        TextView wb = ballItem("🌐", "回到工作台（浏览器留在后台）", 17, v -> { collapseBall(); openWorkbench(); });
        wb.setTag("keep:#D2A8FF");
        TextView term = ballItem(">_", "终端", 14, v -> {
            collapseBall();
            try { com.xm486.pimet.terminal.FloatingTerminal.toggle(ctx); } catch (Throwable t) {
                Toast.makeText(ctx, "打开终端失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        term.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        term.setTag("keep:#3FB950");
        ballItem("↗", "用其他浏览器打开", 15, v -> { collapseBall(); openInOtherBrowser(); });
        ballZoom = ballItem("100%", "页面缩放（也可双指缩放）", 11, v -> { collapseBall(); showZoomDialog(); });
        ballItem("🤖", "问 Pi", 15, this::showAskPiMenu);
        TextView close = ballItem("✕", "收起", 12, v -> collapseBall());
        close.getLayoutParams().height = dp(28);
        close.setTag("muted");
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(50), ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = dp(8);
        ballWrap.addView(ballMenu, mlp);

        root.addView(ballWrap, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ballWrap.setVisibility(ballEnabled() ? View.VISIBLE : View.GONE);
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) MAIN.post(this::placeBall);
        });
    }

    private TextView ballItem(String text, String desc, int sp, View.OnClickListener l) {
        TextView t = new TextView(vctx);
        t.setText(text);
        t.setContentDescription(desc);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setSingleLine(true);
        t.setOnClickListener(l);
        t.setOnLongClickListener(v -> {
            Toast.makeText(ctx, desc, Toast.LENGTH_SHORT).show();
            return true;
        });
        ballMenu.addView(t, new LinearLayout.LayoutParams(dp(40), dp(36)));
        return t;
    }

    /** 配色同工作台：球 = 面板底色 + 边框 + 强调色图标；菜单 = 卡片底 12dp 圆角 */
    private void styleBall() {
        if (ball == null) return;
        ThemeManager.ThemePalette p = ThemeManager.getEffectivePalette(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(p.bgPanel);
        bg.setStroke(Math.max(1, dp(1)), p.border);
        ball.setBackground(bg);
        ball.setTextColor(p.accent);
        ballMenu.setBackground(ThemeManager.createCardDrawable(ctx, p, 12f));
        for (int i = 0; i < ballMenu.getChildCount(); i++) {
            View v = ballMenu.getChildAt(i);
            if (!(v instanceof TextView)) continue;
            Object tag = v.getTag();
            if (tag instanceof String && ((String) tag).startsWith("keep:")) {
                ((TextView) v).setTextColor(Color.parseColor(((String) tag).substring(5)));
            } else if ("muted".equals(tag)) {
                ((TextView) v).setTextColor(p.textMuted);
            } else {
                ((TextView) v).setTextColor(p.text);
            }
        }
        ballFull.setTextColor(p.accent);
        refreshBall();
    }

    /** 刷新菜单里会变的按钮：全屏 ⛶/✕、译/原、缩放百分比 */
    private void refreshBall() {
        if (ballMenu == null) return;
        ThemeManager.ThemePalette p = ThemeManager.getEffectivePalette(ctx);
        ballFull.setText(fullscreen ? "✕" : "⛶");
        ballFull.setContentDescription(fullscreen ? "退出全屏" : "全屏");
        boolean tr = active != null && active.translated;
        ballTranslate.setText(tr ? "原" : "译");
        ballTranslate.setTextColor(tr ? p.accent : p.text);
        ballZoom.setText(zoomFor(active, active == null ? null : active.url) + "%");
        // 悬浮球开着时它就能退出全屏，右上角 ⤡ 不再需要
        exitFullBtn.setVisibility(fullscreen && !ballEnabled() ? View.VISIBLE : View.GONE);
    }

    private void collapseBall() {
        if (ballMenu != null) ballMenu.setVisibility(View.GONE);
        MAIN.post(this::placeBall);
    }

    private void toggleBallMenu() {
        boolean open = ballMenu.getVisibility() != View.VISIBLE;
        if (open) refreshBall();
        ballMenu.setVisibility(open ? View.VISIBLE : View.GONE);
        ballWrap.bringToFront();
        MAIN.post(this::placeBall);
    }

    private void setBallEnabled(boolean on) {
        prefs().edit().putBoolean("ball_on", on).apply();
        ballWrap.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) {
            ballWrap.bringToFront();
            MAIN.post(this::placeBall);
        }
        refreshBall();
    }

    /** 按保存的位置摆放悬浮球（默认同工作台：右上、距顶 80dp、距右 16dp），边界同工作台：顶 64dp、底 16dp、左右 8dp */
    private void placeBall() {
        if (ballWrap == null || root.getWidth() <= 0) return;
        float x = prefs().getFloat("ball_x", -1f), y = prefs().getFloat("ball_y", -1f);
        if (x < 0 || y < 0) {
            x = root.getWidth() - dp(44) - dp(3) - dp(16);
            y = dp(80);
        }
        moveBall(x, y);
    }

    private void moveBall(float x, float y) {
        int w = root.getWidth(), h = root.getHeight();
        int bw = Math.max(ballWrap.getWidth(), dp(50)), bh = Math.max(ballWrap.getHeight(), dp(44));
        float side = dp(8), top = dp(64), bottom = dp(16);
        ballWrap.setX(Math.max(side, Math.min(x, w - bw - side)));
        ballWrap.setY(Math.max(top, Math.min(y, h - bh - bottom)));
    }

    private final class BallDrag implements View.OnTouchListener {
        private float sx, sy, ox, oy;
        private boolean moved;

        @Override
        public boolean onTouch(View v, MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    sx = ev.getRawX();
                    sy = ev.getRawY();
                    ox = ballWrap.getX();
                    oy = ballWrap.getY();
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = ev.getRawX() - sx, dy = ev.getRawY() - sy;
                    if (!moved && Math.abs(dx) <= 8 && Math.abs(dy) <= 8) return true;
                    moved = true;
                    moveBall(ox + dx, oy + dy);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (moved) {
                        prefs().edit().putFloat("ball_x", ballWrap.getX()).putFloat("ball_y", ballWrap.getY()).apply();
                    } else if (ev.getActionMasked() == MotionEvent.ACTION_UP) {
                        toggleBallMenu();
                    }
                    return true;
                default:
                    return false;
            }
        }
    }

    // ---- 悬浮球功能

    private void toggleTranslate() {
        if (web == null || host == null) return;
        try {
            com.xm486.pimet.translation.WebTranslationManager.toggleTranslation(web, host);
        } catch (Throwable t) {
            Toast.makeText(ctx, "翻译失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** 🎨：和工作台悬浮球一样按顺序切换主题；Pi-Web 页面和浏览器配色一起跟着变，工作台回到前台时也会跟随 */
    private void cycleTheme() {
        List<String> all = ThemeManager.getAllThemes();
        int idx = all.indexOf(ThemeManager.getThemePreference(ctx));
        String next = all.get((idx + 1) % all.size());
        ThemeManager.setThemePreference(ctx, next);
        onThemeChanged();
        Toast.makeText(ctx, "已应用主题: " + ThemeManager.getThemeLabel(next), Toast.LENGTH_SHORT).show();
    }

    private void onThemeChanged() {
        applyConfig();
        for (Tab t : tabs) {
            if (t.web != null && WebPinchZoom.isLocal(t.url)) ThemeManager.syncThemeToWebView(t.web, ctx);
        }
        if (isLauncher(currentUrl())) goHome();
    }

    private void showAskPiMenu(View anchor) {
        if (host == null) return;
        PopupMenu pm = new PopupMenu(host, anchor);
        Menu m = pm.getMenu();
        m.add(0, 1, 0, "📝 让 Pi 总结本页");
        m.add(0, 2, 0, "✂️ 把选中的文字发给 Pi");
        m.add(0, 3, 0, "🤖 让 Pi 接手这个页面…");
        m.add(0, 4, 0, "💬 只把网址填进 Pi 输入框");
        pm.setOnMenuItemClickListener(item -> {
            collapseBall();
            switch (item.getItemId()) {
                case 1: askPiAbout("用 browser 工具读一下当前页面（{url}），给我简要总结。", true); break;
                case 2: askPiAbout("{selection}\n\n（来自 {title} {url}）", false); break;
                case 3: askPiAbout("用 browser 工具在 Pi 标签打开 {url}，然后帮我：", false); break;
                case 4: askPiAbout("{url}", false); break;
                default: return false;
            }
            return true;
        });
        pm.show();
    }

    /** 把模板（可用 {url} {title} {selection}）发到工作台的 Pi 输入框，然后回到工作台 */
    private void askPiAbout(String template, boolean send) {
        if (web == null) return;
        web.evaluateJavascript("JSON.stringify({t:document.title,s:String(window.getSelection?getSelection():'')})", v -> {
            String title = "", sel = "";
            try {
                JSONObject j = new JSONObject(new org.json.JSONTokener(v).nextValue().toString());
                title = j.optString("t");
                sel = j.optString("s");
            } catch (Throwable ignored) {}
            if (template.contains("{selection}") && sel.trim().isEmpty()) {
                Toast.makeText(ctx, "先在网页里长按选中一段文字", Toast.LENGTH_SHORT).show();
                return;
            }
            String text = template.replace("{url}", currentUrl()).replace("{title}", title).replace("{selection}", sel);
            if (sPiBridge == null) {
                Toast.makeText(ctx, "工作台未就绪：先打开 PiMet 工作台", Toast.LENGTH_SHORT).show();
                return;
            }
            sPiBridge.askPi(text, send);
            Toast.makeText(ctx, send ? "已发给 Pi" : "已填入 Pi 输入框", Toast.LENGTH_SHORT).show();
            openWorkbench();
        });
    }

    /** 回到 PiMet 工作台（浏览器留在后台，标签都在） */
    private void openWorkbench() {
        try {
            Intent i = new Intent(ctx, com.xm486.pimet.MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            ctx.startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(ctx, "打开工作台失败", Toast.LENGTH_SHORT).show();
        }
    }

    /** 在新标签里打开 Pi-Web 工作台网页（同一个本地服务） */
    private void openPiWebTab() {
        int port = com.xm486.pimet.pet.PetRegistry.getPiWebPort(ctx);
        userTouched = true;
        Tab t = newTab(false);
        switchTo(t);
        t.web.loadUrl("http://127.0.0.1:" + port + "/");
    }

    private String piWebUrl() {
        return "http://127.0.0.1:" + com.xm486.pimet.pet.PetRegistry.getPiWebPort(ctx) + "/";
    }

    // ---- Pi-Web 页面需要的原生桥（与工作台一致）。它们需要 Activity，所以按当前承载的 Activity 现取

    /** window.PiMetTranslator：网页翻译脚本分批请求翻译、打开引擎设置、回报状态 */
    private final class TranslatorBridge {
        private final Tab tab;
        private com.xm486.pimet.translation.TranslationBridge real;
        private Activity realHost;

        TranslatorBridge(Tab tab) {
            this.tab = tab;
        }

        private com.xm486.pimet.translation.TranslationBridge real() {
            Activity h = host;
            WebView w = tab.web;
            if (h == null || w == null) return null;
            if (real == null || realHost != h) {
                real = new com.xm486.pimet.translation.TranslationBridge(h, w);
                realHost = h;
            }
            return real;
        }

        @JavascriptInterface
        public void requestBatch(String batchId, String textsJson) {
            com.xm486.pimet.translation.TranslationBridge r = real();
            if (r != null) r.requestBatch(batchId, textsJson);
        }

        @JavascriptInterface
        public void openSettingsDialog() {
            com.xm486.pimet.translation.TranslationBridge r = real();
            if (r != null) r.openSettingsDialog();
        }

        @JavascriptInterface
        public void onStateChanged(boolean isTranslated, int doneCount, int totalCount) {
            tab.translated = isTranslated;
            MAIN.post(FloatingApps.this::refreshBall);
            com.xm486.pimet.translation.TranslationBridge r = real();
            if (r != null) r.onStateChanged(isTranslated, doneCount, totalCount);
        }

        @JavascriptInterface
        public void showToast(String message) {
            if (message != null) MAIN.post(() -> Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show());
        }
    }

    /** window.PiMetTheme：在 Pi-Web 设置里换主题 → PiMet 跟随（只认本地 Pi-Web 页面） */
    private final class ThemeBridge {
        private final Tab tab;

        ThemeBridge(Tab tab) {
            this.tab = tab;
        }

        @JavascriptInterface
        public void onWebTheme(String themeId) {
            if (!WebPinchZoom.isLocal(tab.url)) return;
            MAIN.post(() -> {
                Activity h = host;
                if (h == null) return;
                new ThemeManager.WebThemeBridge(h, FloatingApps.this::onThemeChanged).onWebTheme(themeId);
            });
        }
    }

    // ------------------------------------------------------------------ 窗口行为

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics()));
    }

    private TextView barBtn(String text, String desc, int sp) {
        TextView t = new TextView(vctx);
        t.setText(text);
        t.setContentDescription(desc);
        t.setTextColor(Color.parseColor("#C9D1D9"));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        return t;
    }

    private void hideIme() {
        InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
        if (urlField.hasFocus()) {
            urlField.clearFocus();
            root.requestFocus();
        }
    }

    /** 全屏：收起顶栏和系统状态栏/导航栏，只留网页；右上角 ⤡ 或返回键退出 */
    private void setFullscreen(boolean on) {
        if (fullscreen == on) return;
        fullscreen = on;
        hideIme();
        bar.setVisibility(on ? View.GONE : View.VISIBLE);
        progress.setVisibility(on ? View.GONE : (active != null && active.loading ? View.VISIBLE : View.INVISIBLE));
        refreshBall();
        exitFullBtn.bringToFront();
        if (ballWrap != null) ballWrap.bringToFront();
        applyConfig();
        applyImmersive(on);
        if (on) Toast.makeText(ctx, ballEnabled() ? "已全屏，点悬浮球里的 ✕ 或按返回键退出" : "已全屏，点右上角 ⤡ 或按返回键退出", Toast.LENGTH_SHORT).show();
    }

    /**
     * 系统栏状态（参数仅为兼容旧调用，实际按当前状态决定）：
     * - 网页视频全屏：真沉浸式，隐藏状态栏和导航栏，内容铺进刘海区；
     * - 浏览器全屏：和工作台全屏一样，保留顶部状态栏（时间、电量），只隐藏导航栏和顶栏，状态栏用页面底色；
     * - 普通：状态栏颜色跟顶栏。
     */
    @SuppressWarnings("deprecation")
    private void applyImmersive(boolean ignored) {
        if (host == null) return;
        Window w = host.getWindow();
        View decor = w.getDecorView();
        boolean video = customView != null;
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                android.view.WindowManager.LayoutParams lp = w.getAttributes();
                int mode = video ? android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                        : android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
                if (lp.layoutInDisplayCutoutMode != mode) {
                    lp.layoutInDisplayCutoutMode = mode;
                    w.setAttributes(lp);
                }
            } catch (Throwable ignored2) {}
        }
        if (video) {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            return;
        }
        decor.setSystemUiVisibility(fullscreen
                ? View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                : View.SYSTEM_UI_FLAG_VISIBLE);
        applyWindowColors();
    }

    /** 状态栏 / 导航栏颜色：普通时跟顶栏；全屏时顶栏隐藏，状态栏跟页面底色，图标深浅随之切换 */
    @SuppressWarnings("deprecation")
    private void applyWindowColors() {
        if (host == null || customView != null) return;
        try {
            Window w = host.getWindow();
            int status = fullscreen ? pageColor : cBar;
            w.setStatusBarColor(status);
            w.setNavigationBarColor(pageColor);
            View decor = w.getDecorView();
            int f = decor.getSystemUiVisibility();
            if (isLightColor(status)) f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            else f &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) {
                if (isLightColor(pageColor)) f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                else f &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            decor.setSystemUiVisibility(f);
        } catch (Throwable ignored) {}
    }

    /** 网页视频全屏（WebChromeClient.onShowCustomView） */
    private void showCustomView(View v, WebChromeClient.CustomViewCallback cb) {
        if (host == null) {
            cb.onCustomViewHidden();
            return;
        }
        if (customView != null) hideCustomView();
        customView = v;
        customViewCallback = cb;
        v.setBackgroundColor(Color.BLACK);
        root.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyImmersive(true);
    }

    private void hideCustomView() {
        if (customView == null) return;
        try { root.removeView(customView); } catch (Throwable ignored) {}
        customView = null;
        WebChromeClient.CustomViewCallback cb = customViewCallback;
        customViewCallback = null;
        if (cb != null) try { cb.onCustomViewHidden(); } catch (Throwable ignored) {}
        applyImmersive(fullscreen);
    }

    private void dismiss() {
        cancelPendingFileChooser();
        AttachmentPickerActivity.sCallback = null;
        hideCustomView();
        hideIme();
        Activity a = host;
        if (root.getParent() instanceof ViewGroup) {
            try { ((ViewGroup) root.getParent()).removeView(root); } catch (Throwable ignored) {}
        }
        host = null;
        vctx.setBaseContext(ctx);
        for (Tab t : tabs) {
            try {
                t.web.stopLoading();
                t.web.destroy();
            } catch (Throwable ignored) {}
            t.web = null;
            t.closed = true;
            if (t.thumb != null) t.thumb.recycle();
            t.thumb = null;
        }
        tabs.clear();
        if (sInstance == this) sInstance = null;
        if (a != null) {
            try { a.finishAndRemoveTask(); } catch (Throwable ignored) {}
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
