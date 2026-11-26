package com.xm486.pimet.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.MutableContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
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

import androidx.annotation.RequiresApi;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

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
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pi 浏览器：独立的全屏浏览器窗口（{@link PiBrowserActivity}，单独的任务，在最近任务里是一张单独的卡片），
 * 界面仿手机 Chrome：⌂ · 地址栏 · 🧩 · 标签数 · ⋮，标签页网格、书签、下载、全屏、页面缩放、桌面版网站。
 * <p>
 * 标签和 WebView 由这个进程级单例持有，不随 Activity 销毁：用户按返回/切走后标签都还在，Pi 也能在后台继续操作。
 * Activity 不在前台时 {@link #minimized} = true，BrowserAgent 会退回 JS 点击、不截图（与以前悬浮窗最小化时一致）。
 * <p>
 * 容器 /root/pimet-browser/ 决定这个浏览器的样子和功能，Pi 可以随时改：config.json（扩展按钮、配色、首页）、
 * scripts/&lt;id&gt;.user.js（油猴用户脚本，由 {@link UserScriptManager} 管理，按 run-at 注入，GM API 经 window.PiMetGM）、pages/&lt;id&gt;/index.html（自定义页面，可用 window.pimet）、home.html（自定义首页）。
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
    private final LinearLayout proxyBanner;
    private final TextView proxyBannerText, proxyBannerBtn;
    private ContainerProxyManager proxyMgr;
    private OffscreenWebHost offscreenHost;
    private volatile ProxyWorkerPool workerPool;
    private volatile String activeRemoteTabId = null;
    private LinearLayout remoteMirrorContainer;
    private TextView mirrorHeaderTv;
    private TextView mirrorRefreshBtn;
    private SurfaceView remoteSurfaceView;
    private ImageView mirrorImageView;
    private final AtomicLong remoteAttachSeqGen = new AtomicLong(0L);
    private volatile long activeRemoteAttachSeq = 0L;
    private volatile String attachedRemoteTabId = null;
    private volatile String attachingRemoteTabId = null;
    private volatile int attachingRemoteW = 0, attachingRemoteH = 0;
    private volatile boolean remoteSurfaceReady = false;
    private volatile boolean remoteFallbackActive = false;
    private Runnable remoteAttachTimeoutTask;
    private volatile int mirrorRemoteW = 0, mirrorRemoteH = 0;
    private final AtomicBoolean mirrorFetching = new AtomicBoolean(false);
    private final ExecutorService mirrorExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pimet-remote-mirror");
        t.setDaemon(true);
        return t;
    });
    private long lastSwitcherRemoteRefreshMs = 0L;
    private final Runnable mirrorPollTask = new Runnable() {
        @Override
        public void run() {
            if (activeRemoteTabId == null || minimized || host == null) return;
            if (!remoteFallbackActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return;
            fetchRemoteMirrorFrame(activeRemoteTabId);
            MAIN.postDelayed(this, 700L);
        }
    };
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
        attachTo(a, container, null);
    }

    public static void attachTo(Activity a, ViewGroup container, Intent intent) {
        sUserClosed = false;
        boolean created = sInstance == null;
        if (created) sInstance = new FloatingApps(a.getApplicationContext());
        sInstance.attachHost(a, container);
        String intentUrl = extractIntentUrl(sInstance, intent);
        if (intentUrl != null) {
            openResolvedIntentUrl(sInstance, intentUrl, intent.getStringExtra(PiBrowserActivity.EXTRA_CONTAINER));
        } else if (created) {
            sInstance.goHome();
        }
    }

    private static String extractIntentUrl(FloatingApps f, Intent intent) {
        if (f == null || intent == null) return null;
        String url = intent.getStringExtra(PiBrowserActivity.EXTRA_OPEN_URL);
        if (url == null || url.trim().isEmpty()) url = intent.getDataString();
        if (url == null) return null;
        String target = url.trim();
        boolean okScheme = target.startsWith("http://") || target.startsWith("https://") || f.isTrusted(target);
        return okScheme ? target : null;
    }

    private static void openResolvedIntentUrl(FloatingApps f, String target, String reqContainer) {
        if (f == null || target == null) return;
        f.userTouched = true;
        for (Tab t : f.tabs) {
            if (!t.pi && !t.closed && target.equals(t.url)
                    && (reqContainer == null || reqContainer.isEmpty() || reqContainer.equals(t.container))) {
                f.switchTo(t);
                return;
            }
        }
        Tab cur = f.active;
        if (cur != null && !cur.pi && !cur.closed && f.isHomeUrl(cur.url)
                && (reqContainer == null || reqContainer.isEmpty() || reqContainer.equals(cur.container))) {
            f.loadIn(cur, target, true);
            return;
        }
        String c = (reqContainer != null && !reqContainer.isEmpty())
                ? f.resolveContainer(reqContainer, cur)
                : f.resolveContainer(f.uiDefaultContainer(), cur);
        Tab nt = f.newTab(false, c);
        f.switchTo(nt);
        f.loadIn(nt, target, true);
    }

    /** 处理桌面快捷方式等外部 Intent 打开指定网页（仅放行 http/https 与可信本地页面） */
    public static void openFromIntent(Activity a, Intent intent) {
        if (intent == null) return;
        final String reqContainer = intent.getStringExtra(PiBrowserActivity.EXTRA_CONTAINER);
        MAIN.post(() -> {
            FloatingApps f = sInstance;
            String target = extractIntentUrl(f, intent);
            if (target != null) openResolvedIntentUrl(f, target, reqContainer);
        });
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
            f.applyVisibility();
            if (f.activeRemoteTabId != null && !f.isRemoteSurfaceObscured()) {
                f.resumeRemoteSurfaceIfNeeded();
            }
        } else {
            f.suspendRemoteSurfaceForOverlay();
            f.applyVisibility();
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
        if (f.panelOpen()) { f.closePanel(); return true; }
        if (f.switcher.getVisibility() == View.VISIBLE) { f.closeSwitcher(); return true; }
        if (f.activeRemoteTabId != null) {
            if (f.fullscreen) { f.setFullscreen(false); return true; }
            if (f.urlField.hasFocus()) { f.hideIme(); f.showUrl(f.active, f.currentUrl()); return true; }
            f.goBack();
            return true;
        }
        if (f.fullscreen) { f.setFullscreen(false); return true; }
        if (f.urlField.hasFocus()) { f.hideIme(); f.showUrl(f.active, f.currentUrl()); return true; }
        if (f.active != null && f.web != null && !f.isHomeUrl(f.currentUrl()) && f.web.canGoBack()) { f.goBackTab(f.active); return true; }
        if (f.active != null && !f.isHomeUrl(f.currentUrl()) && !f.active.pi) { f.goHome(); return true; }
        return false;
    }

    private void attachHost(Activity a, ViewGroup container) {
        host = a;
        minimized = false;
        vctx.setBaseContext(a);
        if (root.getParent() instanceof ViewGroup) ((ViewGroup) root.getParent()).removeView(root);
        container.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyConfig();
        applyVisibility();
        if (fullscreen) applyImmersive(true);
    }

    private void detachHost(Activity a) {
        if (host != a) return;
        minimized = true;
        suspendRemoteSurfaceForOverlay();
        hideCustomView();
        hideIme();
        closeSwitcher();
        collapseBall();
        if (root.getParent() instanceof ViewGroup) ((ViewGroup) root.getParent()).removeView(root);
        host = null;
        vctx.setBaseContext(ctx);
        applyVisibility();
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
            File skillDir = new File(rootfs, "root/.pi/agent/skills/pimet-browser");
            String guide = null;
            String[] assets = null;
            try {
                assets = context.getAssets().list("pimet-browser");
            } catch (Throwable ignored) {}
            if (assets != null && assets.length > 0) {
                for (String name : assets) {
                    if (name == null || !name.endsWith(".md")) continue;
                    String content = readAsset(context, "pimet-browser/" + name);
                    if (content == null) continue;
                    skillDir.mkdirs();
                    writeIfChanged(new File(skillDir, name), content);
                    if ("SKILL.md".equals(name)) guide = content;
                }
            }
            if (guide == null) {
                guide = readAsset(context, "pimet-browser/SKILL.md");
                if (guide == null) return;
                skillDir.mkdirs();
                writeIfChanged(new File(skillDir, "SKILL.md"), guide);
            }
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
        // ui.json（BrowserCustomizer）：所有界面按它渲染，变化时热重载
        loadUiCfg();

        root = new FrameLayout(vctx);
        column = new LinearLayout(vctx);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- 顶栏（仿 Chrome）：⌂ · [🔒 地址栏] · 🧩 · [标签数] · ⋮
        barGestures = new GestureDetector(vctx, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                onToolbarDoubleTap();
                return true;
            }
        });
        bar = new LinearLayout(vctx) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent e) {
                try { barGestures.onTouchEvent(e); } catch (Throwable ignored) {}
                return super.dispatchTouchEvent(e);
            }
        };
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
                if (activeRemoteTabId != null) {
                    urlField.clearFocus();
                    navigateRemoteFromUi(activeRemoteTabId, target);
                } else {
                    load(target);
                }
                return true;
            }
            return false;
        });
        urlPill.addView(urlField, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        plp.setMargins(dp(2), 0, dp(4), 0);
        bar.addView(urlPill, plp);

        extBtn = barBtn("🧩", "脚本管理（用户脚本、扩展按钮）", 18);
        extBtn.setOnClickListener(v -> showScriptsPanel());
        bar.addView(extBtn, new LinearLayout.LayoutParams(dp(40), dp(44)));

        tabCountBtn = new TextView(vctx);
        tabCountBtn.setGravity(Gravity.CENTER);
        tabCountBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tabCountBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tabCountBtn.setContentDescription("标签页");
        tabCountBtn.setOnClickListener(v -> openSwitcher());
        tabCountBtn.setOnLongClickListener(v -> {
            showNewTabChooser(v);
            return true;
        });
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(24), dp(24));
        tlp.setMargins(dp(10), 0, dp(10), 0);
        bar.addView(tabCountBtn, tlp);

        menuBtn = barBtn("⋮", "更多", 22);
        menuBtn.setOnClickListener(this::showUiMenu);
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

        // ---- 容器代理状态条：代理生效中 / 切换中 / 当前标签冻结时出现
        proxyBanner = new LinearLayout(vctx);
        proxyBanner.setOrientation(LinearLayout.HORIZONTAL);
        proxyBanner.setGravity(Gravity.CENTER_VERTICAL);
        proxyBanner.setPadding(dp(12), 0, dp(6), 0);
        proxyBannerText = new TextView(vctx);
        proxyBannerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        proxyBannerText.setSingleLine(true);
        proxyBannerText.setEllipsize(TextUtils.TruncateAt.END);
        proxyBanner.addView(proxyBannerText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        proxyBannerBtn = barBtn("返回主标签", "退出远程代理预览，返回主进程标签", 12);
        proxyBannerBtn.setPadding(dp(8), 0, dp(8), 0);
        proxyBannerBtn.setOnClickListener(v -> exitRemoteMirror());
        proxyBanner.addView(proxyBannerBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)));
        proxyBanner.setVisibility(View.GONE);
        column.addView(proxyBanner, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));

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
        swNew.setOnLongClickListener(v -> {
            showNewTabChooser(v);
            return true;
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

        // 上次进程留下的未保留容器（这时还没有任何标签）
        try {
            containers().cleanupOrphans(Collections.<String>emptyList());
        } catch (Throwable e) {
            Log.w(TAG, "cleanup containers failed", e);
        }
        switchTo(newTab(false));
        reloadConfigIfChanged();
        layoutOffscreen();
    }

    // ------------------------------------------------------------------ 标签页



    /**
     * 一个标签 = 一个 WebView。用户标签是用户自己浏览用的；Pi 标签（🤖 Pi）是 Pi 默认操作的，不打扰用户正在看的页面。
     * 每个标签有自己的容器（WebView Profile，独立 Cookie/Storage）：新建标签默认新容器，也可选当前容器或共享容器 Default（见 ContainerManager）。
     * <p>
     * 可见性策略（后台标签怎么做真实触摸 / 截图）：
     * 平时只有当前标签 VISIBLE，其余 INVISIBLE —— INVISIBLE 保持布局尺寸，页面里的 innerWidth/坐标都正确，Chromium 也会暂停后台页面的渲染。
     * Pi 操作某个后台标签期间（agentBegin → agentEnd 之后 1.5 秒），把它临时设为 VISIBLE 并放在当前标签的下面：
     * 当前标签的 WebView 背景不透明，完全盖住它，用户看不到任何变化，触摸也只落在最上层；
     * 而它在渲染，所以宿主可以直接给它的 WebView 分发真实触摸事件、用 draw() 截图。
     * 这样既不用把 Pi 标签切到前台抢用户的画面，也不必退回 JS 点击（JS 点击只在窗口最小化时作为兜底）。
     * 后台标签不抢输入焦点：按键改用 JS 键盘事件（见 BrowserAgent.pressKey）。
     */
    static final class FrameSession {
        final String frameId;
        final boolean isMainFrame;
        volatile String url;
        volatile JavaScriptReplyProxy replyProxy;
        volatile int transDone;
        volatile int transTotal;
        volatile int transFailed;
        volatile boolean transActive;
        volatile int transPending;
        volatile boolean transStarted;
        volatile String lastError = "";
        final long createdAt = System.currentTimeMillis();

        FrameSession(String frameId, boolean isMainFrame, String url, JavaScriptReplyProxy replyProxy) {
            this.frameId = frameId;
            this.isMainFrame = isMainFrame;
            this.url = url == null ? "" : url;
            this.replyProxy = replyProxy;
        }
    }

    static final class Tab {
        final String id;
        final boolean pi;
        MutableContextWrapper tabCtx;
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
        volatile Bitmap favicon; // 当前网页图标（桌面快捷方式用）
        volatile boolean translated; // 网页翻译已开启（悬浮球显示「原」）
        String autoAuthKey;      // 已自动用保存的账号密码登录过的 host|realm（失败时改为弹窗，避免死循环）
        long lastUsed = System.currentTimeMillis();
        /** 当前页面已注入的用户脚本 id（新导航开始时清空；PiMetGM 据此校验调用方） */
        final java.util.Set<String> injectedIds = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
        /** 当前页面注册的 GM 菜单命令：脚本 id → (命令 id → 标题) */
        final java.util.LinkedHashMap<String, java.util.LinkedHashMap<String, String>> menus = new java.util.LinkedHashMap<>();
        /** 当前页面的控制台日志（最多 300 条） */
        final java.util.ArrayDeque<JSONObject> console = new java.util.ArrayDeque<>();

        /** 当前页面匹配的 ui.json sites 设置（onPageStarted / 导航前刷新） */
        volatile JSONObject site;
        /** 站点规则改过 UA（null = 没改）、开了深色 */
        String siteUa;
        String siteUaMode;
        boolean siteDark;
        /** 容器（WebView Profile）名；ContainerManager.DEFAULT = 共享容器 */
        volatile String container = ContainerManager.DEFAULT;
        /** 该容器的 CookieManager（主线程建 WebView 时取好，后台线程直接用） */
        volatile android.webkit.CookieManager cookies;
        /** Per-Container 环境配置与 DocumentStart 句柄 */
        volatile ContainerEnv.Profile envProfile = ContainerEnv.NATIVE;
        volatile String proxySignature = "DIRECT";
        volatile String proxySummary = "直连";
        volatile boolean proxyFrozen = false;
        volatile String pendingRecoverUrl = null;
        ScriptHandler envDocStartHandler;
        boolean bridgesAttached = false;
        String lastAppliedEnvKey;
        volatile boolean transAnnouncePending = false;

        boolean isIsolated() {
            return envProfile != null && envProfile.isIsolated();
        }

        /** 每个标签独立的跨框架消息鉴权密钥（随机 UUID，仅存于 document-start 闭包与 Java 端，绝不暴露给页面或用户脚本） */
        final String frameSecret = UUID.randomUUID().toString();
        /** 跨源翻译与 __pimetFrameMsg 桥接的 document-start 注入句柄（createWebView 时注册一次，不受用户脚本影响） */
        ScriptHandler transDocStartHandler;
        /** 子框架用户脚本的独立 document-start 注入句柄列表（每个启用且 !noframes 的脚本单独注册） */
        final List<ScriptHandler> scriptDocStartHandlers = new ArrayList<>();
        /** 当前文档各框架代理：frameId -> FrameSession */
        final ConcurrentHashMap<String, FrameSession> frames = new ConcurrentHashMap<>();
        /** 已通过 frameSecret 鉴权并注入到子框架的用户脚本 id */
        final Set<String> subframeInjectedIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
        /** 当前页面是否处于跨框架翻译激活态（供延迟加载的跨源 iframe 在 hello 时自动激活翻译） */
        volatile boolean translatingAllFrames = false;
        volatile String transMode = "replace";
        volatile boolean transAuto = false;
        volatile int transBatchMax = 15;
        volatile int transCharMax = 1800;
        volatile String transAccent = "#58A6FF";
        /** 主框架翻译统计（与各子框架 FrameSession 汇总更新顶层胶囊） */
        volatile int mainTransDone = 0;
        volatile int mainTransTotal = 0;
        volatile int mainTransFailed = 0;
        volatile boolean mainTransActive = false;
        volatile int mainTransPending = 0;
        volatile boolean mainTransHasDomIframes = false;
        volatile String mainTransError = "";

        Tab(String id, boolean pi) {
            this.id = id;
            this.pi = pi;
        }

        void resetMainTransStats() {
            mainTransDone = 0;
            mainTransTotal = 0;
            mainTransFailed = 0;
            mainTransActive = false;
            mainTransPending = 0;
            mainTransHasDomIframes = false;
            mainTransError = "";
        }

        boolean hasNonMainFrames() {
            for (FrameSession fs : frames.values()) {
                if (!fs.isMainFrame) return true;
            }
            return false;
        }
    }

    synchronized String nextTabId() {
        return "t" + (++tabSeq);
    }

    /** 新标签 = 新容器 */
    private Tab newTab(boolean pi) {
        return newTab(pi, resolveContainer("new", null));
    }

    /** container 为容器名（已解析）；WebView 创建后、第一次 loadUrl 之前绑定容器 */
    private Tab newTab(boolean pi, String container) {
        return newTab(pi, container, null);
    }

    private Tab newTab(boolean pi, String container, String explicitId) {
        String tid = (explicitId != null && !explicitId.isEmpty()) ? explicitId : nextTabId();
        Tab t = new Tab(tid, pi);
        t.container = container == null || container.isEmpty() ? ContainerManager.DEFAULT : container;
        try {
            t.envProfile = containers().getProfile(t.container);
        } catch (Throwable e) {
            t.envProfile = ContainerEnv.NATIVE;
        }
        t.proxySignature = "DIRECT";
        t.proxySummary = "直连";
        t.proxyFrozen = false;
        boolean defDesktop = !pi && uiObj("defaults").optBoolean("desktopMode", false);
        t.desktop = (t.envProfile != null && t.envProfile.desktop) || defDesktop;
        t.tabCtx = new MutableContextWrapper(offscreenHost().getHostContext());
        t.web = createWebView(t);
        offscreenHost().mount(t.web, effectiveDesktopOf(t));
        tabs.add(t);
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
        cleanupTabFrames(t, dead);
        offscreenHost().unmount(dead);
        try { webFrame.removeView(dead); } catch (Throwable ignored) {}
        try { dead.destroy(); } catch (Throwable ignored) {}
        releaseContainer(t.container); // 旧 WebView 的 apply 计数还回去；下面 createWebView 会对新 WebView 再 apply
        t.loading = false;
        t.translated = false;
        try { t.envProfile = containers().getProfile(t.container); } catch (Throwable ignored) {}
        if (t.tabCtx == null) {
            t.tabCtx = new MutableContextWrapper(offscreenHost().getHostContext());
        } else {
            t.tabCtx.setBaseContext(isTabInForeground(t) && host != null ? host : offscreenHost().getHostContext());
        }
        t.web = createWebView(t);
        if (active == t) web = t.web;
        applyVisibility();
        String u = t.url;
        if (u != null && !u.isEmpty() && !u.startsWith("about:")) {
            loadUrlWithEnv(t, u, null);
        } else {
            goHome(t, false);
        }
        if (active == t) {
            updateProgress();
            refreshBall();
        }
        Toast.makeText(ctx, crashed ? "网页崩溃了（可能内存不足），已重新加载" : "网页被系统回收，已重新加载", Toast.LENGTH_LONG).show();
    }

    private void switchTo(Tab t) {
        if (t == null || t.closed) return;
        if (activeRemoteTabId != null) {
            suspendRemoteSurfaceForOverlay();
            activeRemoteTabId = null;
            remoteFallbackActive = false;
            hideRemoteMirror();
        }
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
        syncUrlPill();
        refreshTabs();
    }

    boolean isActive(Tab t) {
        return t == active && activeRemoteTabId == null;
    }

    private boolean isTabInForeground(Tab t) {
        return t != null && !t.closed && t == active && activeRemoteTabId == null && !minimized && host != null;
    }

    boolean isTabOnOffscreenHost(Tab t) {
        return t != null && t.web != null && t.web.getParent() != webFrame;
    }

    boolean isTabNativeTapReady(Tab t) {
        if (t == null || t.closed || t.web == null) return false;
        if (t.web.getWidth() <= 0 || t.web.getHeight() <= 0) return false;
        if (!t.web.isAttachedToWindow()) return false;
        if (t.web.getParent() == webFrame) {
            return !minimized && activeRemoteTabId == null && t == active;
        }
        return !offscreenHost().isOverlayFallback();
    }

    private boolean effectiveDesktopOf(Tab t) {
        if (t == null) return false;
        if (t.siteUaMode != null && !t.siteUaMode.isEmpty()) {
            if ("desktop".equalsIgnoreCase(t.siteUaMode)) return true;
            if ("mobile".equalsIgnoreCase(t.siteUaMode)) return false;
        }
        return t.desktop;
    }

    private void syncLocalWebMount(Tab t) {
        if (t == null || t.closed || t.web == null) return;
        boolean fg = isTabInForeground(t);
        boolean effDesktop = effectiveDesktopOf(t);
        if (fg) {
            offscreenHost().unmount(t.web);
            if (t.tabCtx != null) t.tabCtx.setBaseContext(host != null ? host : vctx);
            if (t.web.getParent() != webFrame) {
                if (t.web.getParent() instanceof ViewGroup) {
                    ((ViewGroup) t.web.getParent()).removeView(t.web);
                }
                webFrame.addView(t.web, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            t.web.setVisibility(View.VISIBLE);
            try { t.web.onResume(); } catch (Throwable ignored) {}
            t.web.bringToFront();
            syncDesktopViewport(t, effDesktop, true);
        } else {
            if (t.web.getParent() == webFrame) {
                webFrame.removeView(t.web);
            }
            if (t.tabCtx != null) t.tabCtx.setBaseContext(offscreenHost().getHostContext());
            offscreenHost().mount(t.web, effDesktop);
            syncDesktopViewport(t, effDesktop, false);
        }
    }

    /** 统一挂载规则：当前前台激活且未最小化且未处于远程镜像的标签在 webFrame，其余本地标签一律在 OffscreenWebHost */
    private void applyVisibility() {
        for (Tab x : new ArrayList<>(tabs)) {
            syncLocalWebMount(x);
        }
        if (activeRemoteTabId != null && remoteMirrorContainer != null) {
            remoteMirrorContainer.setVisibility(View.VISIBLE);
            remoteMirrorContainer.bringToFront();
        } else if (remoteMirrorContainer != null) {
            remoteMirrorContainer.setVisibility(View.GONE);
        }
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
        cleanupTabFrames(t, t.web);
        if (t.web != null) {
            offscreenHost().unmount(t.web);
            try { webFrame.removeView(t.web); } catch (Throwable ignored) {}
            try { t.web.stopLoading(); } catch (Throwable ignored) {}
            if (t.tabCtx != null) {
                try { t.tabCtx.setBaseContext(ctx); } catch (Throwable ignored) {}
            }
            try { t.web.destroy(); } catch (Throwable ignored) {}
            t.web = null;
        }
        releaseContainer(t.container);
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

    boolean hasLocalPiTab() {
        return lastPiTab() != null;
    }

    Tab agentTabExact(String spec) {
        if (spec == null || spec.trim().isEmpty()) return null;
        String s = spec.trim();
        if ("pi".equalsIgnoreCase(s)) return lastPiTab();
        if ("user".equalsIgnoreCase(s)) {
            if (active != null && !active.pi) return active;
            for (Tab x : tabs) if (!x.pi) return x;
            return null;
        }
        return findTab(s);
    }

    ProxyWorkerPool peekWorkerPool() {
        return workerPool;
    }

    void refreshTabsPublic() {
        refreshTabs();
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
            boolean effDesk = effectiveDesktopOf(x);
            a.put(new JSONObject().put("id", x.id).put("kind", x.pi ? "pi" : "user")
                    .put("active", x == active && activeRemoteTabId == null)
                    .put("busy", x.busy > 0).put("url", x.url).put("title", x.title)
                    .put("desktop", effDesk)
                    .put("viewport", ContainerEnv.viewportWidth(effDesk) + "x" + ContainerEnv.viewportHeight(effDesk))
                    .put("remote", false)
                    .put("container", x.container).put("containerLabel", containerLabel(x.container))
                    .put("proxyFrozen", false)
                    .put("proxySignature", "DIRECT")
                    .put("proxySummary", "直连"));
        }
        return a;
    }

    String agentTabsText() {
        StringBuilder sb = new StringBuilder();
        for (Tab x : tabs) {
            sb.append(x.id).append(x.pi ? " 🤖Pi" : " 用户").append(x == active && activeRemoteTabId == null ? " [当前显示]" : "")
                    .append(x.busy > 0 ? " [操作中]" : "").append(" {").append(containerLabel(x.container)).append("}  ").append(x.title.isEmpty() ? "" : x.title + "  ").append(x.url).append('\n');
        }
        return sb.toString().trim();
    }

    /** Pi 开始操作某个标签（主线程）：后台标签已在 OffscreenWebHost 保持真实视口与渲染，无需切入 webFrame */
    void agentBegin(Tab t) {
        if (t.closed) return;
        t.busy++;
        if (t.busy == 1) refreshTabs();
    }

    /** Pi 结束一次操作 */
    void agentEnd(Tab t) {
        t.busy = Math.max(0, t.busy - 1);
        if (t.busy > 0) return;
        MAIN.postDelayed(() -> {
            if (t.busy > 0 || t.closed) return;
            refreshTabs();
        }, 600);
    }

    private android.animation.ObjectAnimator busyPulse;

    /** 用户自己切过标签（之后 Pi 新建标签就不再自动切到前台） */
    private boolean userTouched = false;

    private Tab busyPiTab() {
        for (Tab x : tabs) if (x.pi && x.busy > 0) return x;
        return null;
    }

    /** 刷新标签数按钮、Pi 状态条、标签页网格 */
    private void refreshTabs() {
        if (tabCountBtn == null) return;
        int remoteCnt = workerPool != null ? workerPool.remoteTabCount() : 0;
        int n = tabs.size() + remoteCnt;
        tabCountBtn.setText(n > 99 ? ":D" : String.valueOf(n));
        Tab busy = busyPiTab();
        GradientDrawable tb = new GradientDrawable();
        tb.setCornerRadius(dp(5));
        int idle = activeRemoteTabId != null
                ? cAccent
                : (active != null && ContainerManager.supported() && !ContainerManager.DEFAULT.equals(active.container) ? containerColor(active.container) : cFg);
        tb.setStroke(dp(2), busy != null ? cAccent : idle);
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
        boolean showBanner = !fullscreen && (sPiStopped || (busy != null && (busy != active || activeRemoteTabId != null)));
        if (showBanner) {
            piBannerText.setText(sPiStopped ? "已叫停 Pi：它的浏览器操作都会被拒绝"
                    : "🤖 Pi 正在后台标签操作" + (busy.title.isEmpty() ? "" : "：" + busy.title));
            piBannerView.setVisibility(sPiStopped ? View.GONE : View.VISIBLE);
            piBannerStop.setText(sPiStopped ? "▶ 允许 Pi" : "⏹ 停止");
        }
        piBanner.setVisibility(showBanner ? View.VISIBLE : View.GONE);

        // 主进程固定直连、永不冻结；仅关闭旧单活冻结条
        if (proxyBanner != null) {
            proxyBanner.setVisibility(View.GONE);
        }

        if (switcher.getVisibility() == View.VISIBLE) rebuildSwitcher();
    }

    private void togglePiStopped() {
        sPiStopped = !sPiStopped;
        if (sPiStopped) for (Tab x : tabs) if (x.pi && x.web != null) x.web.stopLoading();
        Toast.makeText(ctx, sPiStopped ? "已叫停 Pi：它之后的浏览器操作都会被拒绝" : "已允许 Pi 继续操作浏览器", Toast.LENGTH_SHORT).show();
        refreshTabs();
    }

    /** 新标签页：容器按 defaults.container，内容按 home.newTab */
    private void newUserTab() {
        userTouched = true;
        Tab t = newTab(false, resolveContainer(uiDefaultContainer(), active));
        switchTo(t);
        openNewTabContent(t);
    }

    // ---- 标签页网格

    private void openSwitcher() {
        hideIme();
        suspendRemoteSurfaceForOverlay();
        snapThumb(active);
        rebuildSwitcher();
        switcher.setVisibility(View.VISIBLE);
        switcher.bringToFront();
    }

    private void closeSwitcher() {
        if (switcher.getVisibility() != View.VISIBLE) return;
        switcher.setVisibility(View.GONE);
        switcherGrid.removeAllViews();
        resumeRemoteSurfaceIfNeeded();
    }

    /** 给标签拍一张缩略图 */
    private void snapThumb(Tab t) {
        if (t == null || t.web == null || t.closed) return;
        try {
            Bitmap raw;
            if (!isTabOnOffscreenHost(t) && t.web.isShown()) {
                int w = t.web.getWidth(), h = t.web.getHeight();
                if (w <= 0 || h <= 0) return;
                float s = Math.min(1f, 360f / w);
                raw = Bitmap.createBitmap(Math.max(1, Math.round(w * s)), Math.max(1, Math.round(Math.min(h, w * 1.25f) * s)), Bitmap.Config.RGB_565);
                Canvas c = new Canvas(raw);
                c.scale(s, s);
                c.translate(-t.web.getScrollX(), -t.web.getScrollY());
                t.web.draw(c);
            } else {
                raw = offscreenHost().captureScreenshot(t.web, 1200L);
                if (raw == null) return;
                int w = raw.getWidth(), h = raw.getHeight();
                if (w > 360) {
                    float s = 360f / w;
                    int th = Math.max(1, Math.round(Math.min(h, w * 1.25f) * s));
                    Bitmap scaled = Bitmap.createScaledBitmap(raw, 360, th, true);
                    if (scaled != raw) raw.recycle();
                    raw = scaled;
                }
            }
            if (t.thumb != null) t.thumb.recycle();
            t.thumb = raw;
        } catch (Throwable ignored) {}
    }

    private void rebuildSwitcher() {
        switcherGrid.removeAllViews();
        switcher.setBackgroundColor(cDark ? Color.parseColor("#FF111214") : Color.parseColor("#FFF1F3F4"));
        boolean anyPi = lastPiTab() != null || (workerPool != null && workerPool.remoteTabCount() > 0);
        switcherStop.setVisibility(anyPi || sPiStopped ? View.VISIBLE : View.GONE);
        switcherStop.setText(sPiStopped ? "▶ 允许 Pi" : "⏹ 停止 Pi");
        switcherStop.setTextColor(sPiStopped ? Color.parseColor("#D73A49") : cFg);

        long nowMs = System.currentTimeMillis();
        if (workerPool != null && workerPool.remoteTabCount() > 0 && nowMs - lastSwitcherRemoteRefreshMs > 1000L) {
            lastSwitcherRemoteRefreshMs = nowMs;
            workerPool.refreshRemoteTabsAsync(() -> {
                if (switcher.getVisibility() == View.VISIBLE) rebuildSwitcher();
            });
        }

        List<JSONObject> remoteTabs = workerPool != null ? workerPool.listCachedRemoteTabs() : Collections.emptyList();
        TextView proxyStatusRow = new TextView(vctx);
        proxyStatusRow.setText("🌐 主进程直连不冻结 · 独立代理进程标签 " + remoteTabs.size() + " 个");
        proxyStatusRow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        proxyStatusRow.setTextColor(remoteTabs.isEmpty() ? cDim : cAccent);
        proxyStatusRow.setPadding(dp(8), dp(2), dp(8), dp(6));
        switcherGrid.addView(proxyStatusRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if ("list".equalsIgnoreCase(uiObj("theme").optString("tabStyle", "bar"))) {
            for (Tab x : tabs) {
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
                rlp.setMargins(dp(6), dp(4), dp(6), dp(4));
                switcherGrid.addView(tabListRow(x), rlp);
            }
            for (JSONObject rt : remoteTabs) {
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
                rlp.setMargins(dp(6), dp(4), dp(6), dp(4));
                switcherGrid.addView(remoteTabListRow(rt), rlp);
            }
            return;
        }
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
        for (JSONObject rt : remoteTabs) {
            if (i % 2 == 0) {
                row = new LinearLayout(vctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                switcherGrid.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(232)));
            }
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            clp.setMargins(dp(6), dp(6), dp(6), dp(6));
            row.addView(remoteTabCard(rt), clp);
            i++;
        }
        if (i % 2 == 1 && row != null) {
            View pad = new View(vctx);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            clp.setMargins(dp(6), dp(6), dp(6), dp(6));
            row.addView(pad, clp);
        }
    }

    private View remoteTabListRow(JSONObject rt) {
        final String tid = rt.optString("id", "");
        final int slotNum = rt.optInt("workerSlot", 0);
        final String pSummary = rt.optString("proxySummary", "代理");
        final String contLabel = rt.optString("containerLabel", rt.optString("container", ""));
        final String urlStr = rt.optString("url", "");
        String tt = rt.optString("title", "");
        if (tt.isEmpty()) tt = urlStr.isEmpty() ? "远程代理标签 " + tid : urlStr.replaceFirst("^https?://", "");
        boolean sel = tid.equals(activeRemoteTabId);

        LinearLayout row = new LinearLayout(vctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), 0, dp(4), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(sel ? cAccent : (cDark ? Color.parseColor("#FF2A2B2F") : Color.WHITE));
        row.setBackground(bg);
        int fg = sel ? Color.WHITE : cFg;

        View strip = new View(vctx);
        GradientDrawable sd = new GradientDrawable();
        sd.setCornerRadius(dp(2));
        sd.setColor(Color.parseColor("#3FB950"));
        strip.setBackground(sd);
        LinearLayout.LayoutParams stl = new LinearLayout.LayoutParams(dp(4), dp(32));
        stl.rightMargin = dp(10);
        row.addView(strip, stl);

        LinearLayout txt = new LinearLayout(vctx);
        txt.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(vctx);
        title.setText("⚡ 代理进程 #" + slotNum + " · " + tt);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setTextColor(fg);
        txt.addView(title);
        TextView sub = new TextView(vctx);
        sub.setText(contLabel + " · " + pSummary + (urlStr.isEmpty() ? "" : "  " + urlStr.replaceFirst("^https?://", "")));
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        sub.setTextColor(sel ? Color.WHITE : cDim);
        txt.addView(sub);
        row.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(vctx);
        close.setText("✕");
        close.setGravity(Gravity.CENTER);
        close.setTextColor(fg);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        close.setOnClickListener(v -> closeRemoteTabFromUi(tid));
        row.addView(close, new LinearLayout.LayoutParams(dp(40), dp(40)));
        row.setOnClickListener(v -> {
            closeSwitcher();
            switchToRemote(tid);
        });
        return row;
    }

    private View remoteTabCard(JSONObject rt) {
        final String tid = rt.optString("id", "");
        final int slotNum = rt.optInt("workerSlot", 0);
        final String pSummary = rt.optString("proxySummary", "代理");
        final String contLabel = rt.optString("containerLabel", rt.optString("container", ""));
        final String urlStr = rt.optString("url", "");
        String tt = rt.optString("title", "");
        if (tt.isEmpty()) tt = urlStr.isEmpty() ? "远程代理标签 " + tid : urlStr.replaceFirst("^https?://", "");
        boolean sel = tid.equals(activeRemoteTabId);

        LinearLayout card = new LinearLayout(vctx);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(sel ? cAccent : (cDark ? Color.parseColor("#FF2A2B2F") : Color.WHITE));
        card.setBackground(bg);
        card.setPadding(dp(3), 0, dp(3), dp(3));
        card.setClipToOutline(true);

        LinearLayout head = new LinearLayout(vctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(8), 0, 0, 0);
        TextView badge = new TextView(vctx);
        badge.setText("⚡ 代理进程 #" + slotNum);
        badge.setSingleLine(true);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        badge.setTextColor(sel ? Color.WHITE : Color.parseColor("#3FB950"));
        badge.setPadding(0, 0, dp(6), 0);
        head.addView(badge);

        TextView title = new TextView(vctx);
        title.setText(tt);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        int headFg = sel ? Color.WHITE : cFg;
        title.setTextColor(headFg);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(vctx);
        close.setText("✕");
        close.setGravity(Gravity.CENTER);
        close.setTextColor(headFg);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        close.setOnClickListener(v -> closeRemoteTabFromUi(tid));
        head.addView(close, new LinearLayout.LayoutParams(dp(32), dp(32)));
        card.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));

        LinearLayout body = new LinearLayout(vctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER);
        body.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable bb = new GradientDrawable();
        bb.setCornerRadius(dp(11));
        bb.setColor(pageColor);
        body.setBackground(bb);
        TextView iconTv = new TextView(vctx);
        iconTv.setText("⚡");
        iconTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        iconTv.setGravity(Gravity.CENTER);
        body.addView(iconTv);
        TextView infoTv = new TextView(vctx);
        infoTv.setText(contLabel + "\n" + pSummary + (urlStr.isEmpty() ? "" : "\n" + urlStr.replaceFirst("^https?://", "")));
        infoTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        infoTv.setTextColor(cDim);
        infoTv.setGravity(Gravity.CENTER);
        infoTv.setMaxLines(4);
        infoTv.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(infoTv);
        card.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        card.setOnClickListener(v -> {
            closeSwitcher();
            switchToRemote(tid);
        });
        return card;
    }

    private void closeRemoteTabFromUi(String tabId) {
        if (tabId == null || tabId.isEmpty() || workerPool == null) return;
        final ProxyWorkerPool.WorkerSlot slot = workerPool.slotForTab(tabId);
        final String cid = slot != null ? slot.boundContainerId : null;
        if (tabId.equals(activeRemoteTabId)) {
            exitRemoteMirror();
        } else if (tabId.equals(attachedRemoteTabId) || tabId.equals(attachingRemoteTabId)) {
            detachCurrentRemoteSurface(true);
        }
        workerPool.unregisterRemoteTab(tabId);
        refreshTabs();
        if (slot != null) {
            mirrorExec.execute(() -> {
                try {
                    workerPool.callRpc(slot, new JSONObject().put("action", "tab_close").put("tab", tabId), "ui-close-" + tabId, 5000L);
                } catch (Throwable ignored) {}
                MAIN.post(() -> {
                    if (cid != null && !cid.isEmpty() && !ContainerManager.DEFAULT.equals(cid)) {
                        if (mainTabCountForContainer(cid) == 0 && workerPool.remoteTabCountForContainer(cid) == 0) {
                            releaseContainer(cid);
                        }
                    }
                    refreshTabs();
                    if (switcher.getVisibility() == View.VISIBLE) rebuildSwitcher();
                });
            });
        }
    }

    private View tabListRow(Tab x) {
        LinearLayout row = new LinearLayout(vctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), 0, dp(4), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        boolean isShown = x == active && activeRemoteTabId == null;
        bg.setColor(isShown ? cAccent : (cDark ? Color.parseColor("#FF2A2B2F") : Color.WHITE));
        row.setBackground(bg);
        int fg = isShown ? Color.WHITE : cFg;
        if (ContainerManager.supported()) {
            View strip = new View(vctx);
            GradientDrawable sd = new GradientDrawable();
            sd.setCornerRadius(dp(2));
            sd.setColor(containerColor(x.container));
            strip.setBackground(sd);
            LinearLayout.LayoutParams stl = new LinearLayout.LayoutParams(dp(4), dp(32));
            stl.rightMargin = dp(10);
            row.addView(strip, stl);
        }
        LinearLayout txt = new LinearLayout(vctx);
        txt.setOrientation(LinearLayout.VERTICAL);
        String tt = x.title;
        if (tt == null || tt.isEmpty() || isLauncher(x.url)) tt = isLauncher(x.url) || x.url.isEmpty() ? "新标签页" : x.url.replaceFirst("^https?://", "");
        TextView title = new TextView(vctx);
        title.setText((x.pi ? "🤖 " : "") + tt + (x.busy > 0 ? " ●" : ""));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setTextColor(fg);
        txt.addView(title);
        TextView sub = new TextView(vctx);
        String host = isLauncher(x.url) ? "" : x.url.replaceFirst("^https?://", "");
        String badge = ContainerEnv.badgeEmoji(x.envProfile);
        sub.setText((ContainerManager.supported() ? badge + " " + containerLabel(x.container) + "  " : "") + host);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        sub.setTextColor(isShown ? Color.WHITE : cDim);
        txt.addView(sub);
        row.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = new TextView(vctx);
        close.setText("✕");
        close.setGravity(Gravity.CENTER);
        close.setTextColor(fg);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        close.setContentDescription("关闭标签");
        close.setOnClickListener(v -> {
            closeTab(x);
            rebuildSwitcher();
        });
        row.addView(close, new LinearLayout.LayoutParams(dp(40), dp(40)));
        row.setOnClickListener(v -> {
            userTouched = true;
            closeSwitcher();
            switchTo(x);
        });
        row.setOnLongClickListener(v -> {
            showTabMenu(x, v);
            return true;
        });
        row.setContentDescription((x.pi ? "Pi 标签 " : "标签 ") + tt);
        return row;
    }

    private View tabCard(Tab x) {
        LinearLayout card = new LinearLayout(vctx);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        boolean isShown = x == active && activeRemoteTabId == null;
        bg.setColor(isShown ? cAccent : (cDark ? Color.parseColor("#FF2A2B2F") : Color.WHITE));
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
        if (ContainerManager.supported()) {
            View strip = new View(vctx);
            GradientDrawable sd = new GradientDrawable();
            sd.setCornerRadius(dp(2));
            sd.setColor(containerColor(x.container));
            strip.setBackground(sd);
            LinearLayout.LayoutParams stl = new LinearLayout.LayoutParams(dp(4), dp(16));
            stl.rightMargin = dp(6);
            head.addView(strip, stl);
            TextView badge = new TextView(vctx);
            badge.setText(ContainerEnv.badgeEmoji(x.envProfile) + " " + containerLabel(x.container)
                    + (containerKeep(x.container) && !ContainerManager.DEFAULT.equals(x.container) ? " 📌" : ""));
            badge.setSingleLine(true);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            badge.setTextColor(isShown ? Color.WHITE : containerColor(x.container));
            badge.setPadding(0, 0, dp(6), 0);
            head.addView(badge);
        }
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        int headFg = isShown ? Color.WHITE : cFg;
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
        card.setOnLongClickListener(v -> {
            showTabMenu(x, v);
            return true;
        });
        card.setContentDescription((x.pi ? "Pi 标签 " : "标签 ") + tt + "，容器 " + containerLabel(x.container));
        return card;
    }

    // ---- 菜单

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
            revealUrlPill();
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

    private void addToDesktopDialog() {
        if (host == null || active == null) return;
        final Tab t = active;
        final String u = currentUrl();
        boolean allowed = !isHomeUrl(u) && (u.startsWith("http://") || u.startsWith("https://") || isTrusted(u));
        if (!allowed) {
            Toast.makeText(ctx, "先打开一个网页再添加到桌面", Toast.LENGTH_SHORT).show();
            return;
        }
        String defTitle = t.title != null ? t.title.trim() : "";
        if (defTitle.isEmpty() || "Pi 浏览器".equals(defTitle)) {
            String h = Uri.parse(u).getHost();
            defTitle = (h != null && !h.isEmpty()) ? h.replaceFirst("^www\\.", "") : u;
        }
        if (defTitle.length() > 24) defTitle = defTitle.substring(0, 24);

        final EditText et = new EditText(host);
        et.setSingleLine(true);
        et.setHint("桌面图标名称");
        et.setText(defTitle);
        et.selectAll();
        FrameLayout wrap = new FrameLayout(host);
        wrap.setPadding(dp(20), dp(10), dp(20), 0);
        wrap.addView(et);

        new AlertDialog.Builder(host)
                .setTitle("添加到桌面")
                .setMessage("在手机桌面创建独立图标，点击直接用 Pi 浏览器打开此网页。")
                .setView(wrap)
                .setPositiveButton("添加", (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty()) {
                        String h = Uri.parse(u).getHost();
                        name = (h != null && !h.isEmpty()) ? h : "网页捷径";
                    }
                    createDesktopShortcut(t, u, name);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void createDesktopShortcut(Tab t, String url, String title) {
        try {
            ShortcutManager sm = ctx.getSystemService(ShortcutManager.class);
            if (sm == null || !sm.isRequestPinShortcutSupported()) {
                Toast.makeText(ctx, "当前系统桌面不支持创建快捷方式", Toast.LENGTH_LONG).show();
                return;
            }
            Bitmap iconBmp = buildShortcutIcon(t, url, title);
            Intent launch = new Intent(ctx, PiBrowserActivity.class);
            launch.setAction(Intent.ACTION_VIEW);
            launch.setData(Uri.parse(url));
            launch.putExtra(PiBrowserActivity.EXTRA_OPEN_URL, url);
            if (t.container != null && !t.container.isEmpty()) {
                launch.putExtra(PiBrowserActivity.EXTRA_CONTAINER, t.container);
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            String sid = "pimet_web_" + Long.toHexString(System.currentTimeMillis());
            ShortcutInfo info = new ShortcutInfo.Builder(ctx, sid)
                    .setShortLabel(title)
                    .setLongLabel(title)
                    .setIcon(Icon.createWithBitmap(iconBmp))
                    .setIntent(launch)
                    .build();

            Intent cbIntent = sm.createShortcutResultIntent(info);
            int flags = Build.VERSION.SDK_INT >= 23
                    ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                    : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pi = PendingIntent.getBroadcast(ctx, (int) (System.currentTimeMillis() & 0x7fffffff), cbIntent, flags);
            boolean sent = sm.requestPinShortcut(info, pi != null ? pi.getIntentSender() : null);
            Toast.makeText(ctx,
                    sent ? "已请求添加到桌面；如未出现请确认已开启「桌面快捷方式」权限" : "桌面拒绝创建快捷方式，请检查权限",
                    Toast.LENGTH_LONG).show();
        } catch (Throwable e) {
            Log.w(TAG, "createDesktopShortcut failed", e);
            Toast.makeText(ctx, "添加到桌面失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private Bitmap buildShortcutIcon(Tab t, String url, String title) {
        final int size = 192;
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Bitmap fav = (t != null && t.favicon != null && !t.favicon.isRecycled())
                ? t.favicon
                : ((t != null && t.web != null) ? t.web.getFavicon() : null);
        if (fav != null && !fav.isRecycled() && fav.getWidth() >= 16 && fav.getHeight() >= 16) {
            p.setColor(Color.WHITE);
            c.drawRoundRect(new RectF(8, 8, size - 8, size - 8), 40f, 40f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f);
            p.setColor(Color.parseColor("#E2E8F0"));
            c.drawRoundRect(new RectF(8, 8, size - 8, size - 8), 40f, 40f, p);
            p.setStyle(Paint.Style.FILL);
            int pad = 32;
            Rect dst = new Rect(pad, pad, size - pad, size - pad);
            c.drawBitmap(fav, null, dst, p);
            return out;
        }

        int[] palette = {
                Color.parseColor("#2563EB"), Color.parseColor("#00BA76"), Color.parseColor("#7C3AED"),
                Color.parseColor("#EA580C"), Color.parseColor("#0891B2"), Color.parseColor("#DB2777"),
                Color.parseColor("#4F46E5"), Color.parseColor("#059669")
        };
        String hostName = Uri.parse(url).getHost();
        String seed = (hostName != null && !hostName.isEmpty()) ? hostName : title;
        int col = palette[Math.abs(seed.hashCode()) % palette.length];
        p.setColor(col);
        c.drawRoundRect(new RectF(8, 8, size - 8, size - 8), 40f, 40f, p);

        String ch = title != null && !title.trim().isEmpty() ? title.trim().substring(0, 1).toUpperCase() : "W";
        if (hostName != null && !hostName.isEmpty() && ch.matches("[A-Za-z0-9]")) {
            ch = hostName.replaceFirst("^www\\.", "").substring(0, 1).toUpperCase();
        }
        p.setColor(Color.WHITE);
        p.setTextSize(88f);
        p.setFakeBoldText(true);
        p.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = p.getFontMetrics();
        float cy = size / 2f - (fm.ascent + fm.descent) / 2f;
        c.drawText(ch, size / 2f, cy, p);
        return out;
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
        if (t != null && t.site != null && t.site.has("zoom")) return WebPinchZoom.clamp(t.site.optInt("zoom", 100));
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
        if (activeRemoteTabId != null) {
            final String rid = activeRemoteTabId;
            final boolean targetOn = !remoteIsDesktop();
            try {
                dispatchRemoteAction(
                        rid,
                        new JSONObject().put("action", "desktop").put("tab", rid).put("on", targetOn),
                        "ui-desktop",
                        12000L,
                        true);
                Toast.makeText(ctx, targetOn ? "已切换为桌面版网站" : "已切换为移动版网站", Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {}
            return;
        }
        setTabDesktop(active, active == null || !active.desktop, true);
    }

    private void toggleDesktop(Tab t, boolean toast) {
        if (t == null) return;
        setTabDesktop(t, !t.desktop, toast);
    }

    void setTabDesktop(Tab t, boolean on, boolean toast) {
        if (t == null || t.web == null) return;
        WebSettings ws = t.web.getSettings();
        if (sDefaultUa == null) sDefaultUa = WebSettings.getDefaultUserAgent(ctx);
        t.desktop = on;
        t.siteUaMode = null;
        t.siteUa = null;
        applyContainerEnv(t, t.web, false);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        syncDesktopViewport(t, effectiveDesktopOf(t), !isTabOnOffscreenHost(t));
        applyZoom(t);
        t.web.reload();
        if (toast) Toast.makeText(ctx, t.desktop ? "已切换为桌面版网站" : "已切换为移动版网站", Toast.LENGTH_SHORT).show();
    }

    private void syncDesktopViewport(Tab t, boolean effectiveDesktop, boolean injectOnPage) {
        if (t == null || t.closed || t.web == null) return;
        if (isTabOnOffscreenHost(t)) {
            offscreenHost().updateViewport(t.web, effectiveDesktop);
            String u = t.url;
            if (u != null && (u.startsWith("http://") || u.startsWith("https://"))) {
                if (effectiveDesktop && !t.isIsolated()) {
                    t.web.evaluateJavascript(
                            AntiDetectJs.buildNativeDesktopScript(ContainerEnv.DESKTOP_VIEWPORT_W, 1.0, false), null);
                } else {
                    t.web.evaluateJavascript(AntiDetectJs.buildClearViewportOverrideScript(), null);
                }
            }
            return;
        }
        if (!injectOnPage) return;
        String u = t.url;
        if (u == null || !(u.startsWith("http://") || u.startsWith("https://"))) return;
        if (effectiveDesktop) {
            float density = ctx.getResources().getDisplayMetrics().density;
            int wPx = t.web.getWidth() > 0 ? t.web.getWidth() : ctx.getResources().getDisplayMetrics().widthPixels;
            double cssW = wPx / Math.max(1f, density);
            double scale = cssW / ContainerEnv.DESKTOP_VIEWPORT_W;
            String js = t.isIsolated()
                    ? AntiDetectJs.buildDesktopViewportMetaScript(ContainerEnv.DESKTOP_VIEWPORT_W, scale, true)
                    : AntiDetectJs.buildNativeDesktopScript(ContainerEnv.DESKTOP_VIEWPORT_W, scale, true);
            t.web.evaluateJavascript(js, null);
        } else {
            t.web.evaluateJavascript(AntiDetectJs.buildClearViewportOverrideScript(), null);
        }
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
        int remoteCnt = workerPool != null ? workerPool.remoteTabCount() : 0;
        int total = tabs.size() + remoteCnt;
        AlertDialog.Builder b = new AlertDialog.Builder(host)
                .setTitle("关闭浏览器？")
                .setMessage("会关闭全部 " + total + " 个标签（登录状态会保留）。")
                .setPositiveButton("关闭", (d, w) -> {
                    sUserClosed = true;
                    dismiss();
                })
                .setNegativeButton("取消", null);
        if (activeRemoteTabId != null) {
            final String rid = activeRemoteTabId;
            b.setNeutralButton("仅关闭此远程标签", (d, w) -> closeRemoteTabFromUi(rid));
        }
        b.show();
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
        final GestureDetector swipe = makeSwipeDetector(t);
        Context webCtx = t.tabCtx != null ? t.tabCtx : vctx;
        WebView w = new WebView(webCtx) {
            // 记录用户真实触摸 / 按键（Pi 的合成触摸 sAgentTouch=true 不算），BrowserAgent 据此判断「用户接手了」
            @Override
            public boolean dispatchTouchEvent(android.view.MotionEvent e) {
                if (!sAgentTouch && e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                    t.userInputAt = android.os.SystemClock.uptimeMillis();
                    lastTouchX = e.getX();
                    lastTouchY = e.getY();
                }
                // ui.json gestures：左右滑（只观察，不吞事件）
                if (!sAgentTouch && !t.pi) {
                    try { swipe.onTouchEvent(e); } catch (Throwable ignored) {}
                }
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
        try {
            containers().apply(w, t.container);
        } catch (Throwable e) {
            Log.w(TAG, "apply container " + t.container + " failed", e);
        }
        try {
            t.envProfile = containers().getProfile(t.container);
        } catch (Throwable e) {
            t.envProfile = ContainerEnv.NATIVE;
        }
        t.proxySignature = "DIRECT";
        t.proxySummary = "直连";
        t.proxyFrozen = false;
        t.cookies = cookiesFor(t.container);
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
        // 用户标签里 target=_blank / window.open 开新标签（继承容器）；Pi 标签保持在本标签打开
        ws.setSupportMultipleWindows(!t.pi);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE); // 应用由 Pi 随时改写，总是读最新文件
        // 双指布局缩放：和 Pi-Web 工作台同一套（所有网页重排适配屏宽，不横向溢出）
        WebPinchZoom.attach(w, (pct, committed) -> { if (committed) MAIN.post(this::refreshBall); }, zoomStore(t));
        ws.setTextZoom(100);
        if (sDefaultUa == null) {
            try { sDefaultUa = WebSettings.getDefaultUserAgent(ctx); } catch (Throwable ignored) {}
        }
        w.setBackgroundColor(pageColor);
        com.xm486.pimet.translation.WebTranslationManager.registerDelegate(w, new TabTranslationDelegate(t));
        setupCrossFrameSupport(t, w);
        applyContainerEnv(t, w, true);
        w.setDownloadListener((url, ua, contentDisposition, mime, length) -> {
            if (!t.pi && isUserJsUrl(url) && promptInstallFromUrl(url)) return;
            startDownload(t, url, ua, contentDisposition, mime);
        });
        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String u = req.getUrl().toString();
                if (u.startsWith("pimet://")) {
                    if (!t.pi && isTrusted(t.url)) MAIN.post(() -> handlePimetLink(u));
                    return true;
                }
                if (!t.pi && req.isForMainFrame() && isUserJsUrl(u) && promptInstallFromUrl(u)) return true;
                if (req.isForMainFrame() && (u.startsWith("http://") || u.startsWith("https://"))) {
                    String r = uiRedirect(u);
                    if (r != null) {
                        applySiteSettings(t, r, true);
                        loadUrlWithEnv(t, r, null);
                        return true;
                    }
                    applySiteSettings(t, u, true);
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

            /** rules.block 拦截（主进程始终直连，永不冻结） */
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                String u = req != null && req.getUrl() != null ? req.getUrl().toString() : null;
                if (uiBlocked(u)) return new android.webkit.WebResourceResponse("text/plain", "utf-8", new java.io.ByteArrayInputStream(new byte[0]));
                return null;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                t.navSeq++;
                t.errorCode = 0;
                t.errorDesc = "";
                t.httpStatus = 0;
                t.loading = true;
                t.translated = false;
                t.translatingAllFrames = false;
                t.resetMainTransStats();
                t.favicon = favicon;
                if (t == active) { updateProgress(); refreshBall(); }
                t.url = url != null ? url : "";
                showUrl(t, url);
                applySiteSettings(t, url, false);
                t.injectedIds.clear();
                t.subframeInjectedIds.clear();
                for (Iterator<Map.Entry<String, FrameSession>> it = t.frames.entrySet().iterator(); it.hasNext(); ) {
                    Map.Entry<String, FrameSession> e = it.next();
                    FrameSession fs = e.getValue();
                    if (fs == null || !fs.isMainFrame) it.remove();
                }
                synchronized (t.menus) { t.menus.clear(); }
                clearConsoleLogs(t);
                if (!t.pi && isUserJsUrl(url) && promptInstallFromUrl(url)) {
                    view.stopLoading();
                    return;
                }
                injectScripts(t, view, url, "document-start");
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                t.autoAuthKey = null;
                t.loading = false;
                t.finishedSeq = t.navSeq;
                if (t == active) updateProgress();
                applyZoom(t);
                if (!t.pi && !WebPinchZoom.isLocal(url)) {
                    try { com.xm486.pimet.translation.WebTranslationManager.maybeAutoTranslate(view, ctx, url); } catch (Throwable ignored) {}
                }
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
                recordHistory(t, t.url, t.title);
                if (isTrusted(url)) view.evaluateJavascript(HELPER_JS, null);
                reloadConfigIfChanged();
                syncDesktopViewport(t, effectiveDesktopOf(t), true);
                injectUiCss(t, view);
                injectScripts(t, view, url, "document-end");
                injectScripts(t, view, url, "document-idle");
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                applyZoom(t);
                syncDesktopViewport(t, effectiveDesktopOf(t), true);
                if (WebPinchZoom.isLocal(url)) PiWebCompat.apply(view, url);
                injectUiCss(t, view);
                injectScripts(t, view, url, "document-start");
                injectScripts(t, view, url, "document-end");
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
            public void onReceivedIcon(WebView view, Bitmap icon) {
                if (icon != null && !icon.isRecycled()) t.favicon = icon;
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
            public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
                if (cm != null) {
                    String lv;
                    switch (cm.messageLevel()) {
                        case ERROR: lv = "error"; break;
                        case WARNING: lv = "warn"; break;
                        case DEBUG: lv = "debug"; break;
                        case TIP: lv = "info"; break;
                        default: lv = "log"; break;
                    }
                    addConsole(t, lv, cm.message(), cm.sourceId(), cm.lineNumber());
                }
                return super.onConsoleMessage(cm);
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                t.title = title != null ? title : "";
                if (!t.pi) refreshTabs();
                if (!t.loading) recordHistory(t, t.url, t.title);
            }

            /** target=_blank / window.open：在新标签打开，继承来源标签的容器 */
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                if (t.pi || resultMsg == null || !(resultMsg.obj instanceof WebView.WebViewTransport)) return false;
                Tab nt = newTab(false, t.container);
                ((WebView.WebViewTransport) resultMsg.obj).setWebView(nt.web);
                resultMsg.sendToTarget();
                if (host != null && t == active) {
                    userTouched = true;
                    switchTo(nt);
                } else {
                    refreshTabs();
                }
                return true;
            }

            @Override
            public void onCloseWindow(WebView window) {
                for (Tab x : new ArrayList<>(tabs)) {
                    if (x.web == window && !x.pi) {
                        closeTab(x);
                        break;
                    }
                }
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
            tabCookies(t).setAcceptCookie(true);
            tabCookies(t).setAcceptThirdPartyCookies(w, true);
        } catch (Throwable ignored) {}
        w.setLongClickable(true);
        w.setHapticFeedbackEnabled(true);
        // ui.json longPress：链接 / 图片 / 文字长按菜单（没配置时 WebView 默认行为）
        w.setOnLongClickListener(v -> onWebLongPress(t, w));
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
            String cookie = tabCookies(t).getCookie(url);
            if (cookie != null && !cookie.isEmpty()) req.addRequestHeader("Cookie", cookie);
            if (ua != null && !ua.isEmpty()) req.addRequestHeader("User-Agent", ua);
            if (t.url.startsWith("http")) req.addRequestHeader("Referer", t.url);
            if (mime != null && !mime.isEmpty()) req.setMimeType(mime);
            req.setTitle(name);
            req.setDescription("PiMet 浏览器下载");
            req.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.allowScanningByMediaScanner();
            String where = null;
            // ui.json defaults.downloadDir（用户写了才生效；写不进去时退回默认位置）
            String ddir = uiHas("defaults", "downloadDir") ? uiObj("defaults").optString("downloadDir", "").trim() : "";
            if (!ddir.isEmpty()) {
                try {
                    File dd = new File(ddir);
                    if (!dd.isDirectory()) dd.mkdirs();
                    File out = new File(dd, name);
                    req.setDestinationUri(Uri.fromFile(out));
                    where = out.getAbsolutePath();
                } catch (Throwable e) {
                    where = null;
                }
            }
            if (where == null) {
                try {
                    req.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, "PiMet/" + name);
                    where = "下载/PiMet/" + name;
                } catch (Throwable noPublic) {
                    // 没有存储权限时退回应用自己的目录（不需要权限）
                    req.setDestinationInExternalFilesDir(ctx, android.os.Environment.DIRECTORY_DOWNLOADS, name);
                    File d = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
                    where = d != null ? new File(d, name).getAbsolutePath() : name;
                }
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
        if (t.isIsolated()) {
            FrameSession targetFs = findBlobFrameSession(t, url);
            if (targetFs != null && targetFs.replyProxy != null) {
                try {
                    String payload = new JSONObject()
                            .put("type", "blob_download")
                            .put("nonce", nonce)
                            .put("url", url)
                            .toString();
                    targetFs.replyProxy.postMessage(payload);
                    return;
                } catch (Throwable e) {
                    onBlobChunk(nonce, "", true, "下载通道发送失败: " + e.getMessage());
                    return;
                }
            }
            onBlobChunk(nonce, "", true, "当前页面下载通道未就绪");
            return;
        }
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

    private FrameSession findBlobFrameSession(Tab t, String url) {
        if (t == null || t.frames.isEmpty()) return null;
        String blobOrigin = null;
        if (url != null && url.startsWith("blob:")) {
            try {
                java.net.URI u = java.net.URI.create(url.substring(5));
                if (u.getScheme() != null && u.getHost() != null) {
                    blobOrigin = u.getScheme() + "://" + u.getAuthority();
                }
            } catch (Throwable ignored) {}
        }
        FrameSession mainFs = null;
        FrameSession originFs = null;
        for (FrameSession fs : t.frames.values()) {
            if (fs == null || fs.replyProxy == null) continue;
            if (fs.isMainFrame && mainFs == null) mainFs = fs;
            if (blobOrigin != null && fs.url != null && fs.url.startsWith(blobOrigin)) {
                if (fs.isMainFrame) return fs;
                if (originFs == null) originFs = fs;
            }
        }
        return originFs != null ? originFs : mainFs;
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
        if (activeRemoteTabId != null) {
            sendRemoteHome(activeRemoteTabId);
            return;
        }
        goHome(active, true);
    }

    /** 在指定标签里打开首页（restore 参数保留旧签名，已无作用） */
    private void goHome(Tab t, boolean restore) {
        if (t == null || t.web == null) return;
        String hp = uiHomepage();
        if (hp != null) {
            if (hp.startsWith("about:")) t.web.loadUrl(hp);
            else loadIn(t, resolveOpenUrl(hp), restore);
            return;
        }
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

    public static FloatingApps current() {
        return sInstance;
    }

    static boolean isPiStopped() {
        return sPiStopped;
    }

    String url() {
        return currentUrl();
    }

    private String currentUrl() {
        if (activeRemoteTabId != null && workerPool != null) {
            JSONObject meta = workerPool.getCachedTabMeta(activeRemoteTabId);
            if (meta != null) return meta.optString("url", "");
            for (JSONObject o : workerPool.listCachedRemoteTabs()) {
                if (activeRemoteTabId.equals(o.optString("id"))) {
                    return o.optString("url", "");
                }
            }
            return "";
        }
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
        if (!t.pi && isUserJsUrl(url) && promptInstallFromUrl(url)) return;
        String r = uiRedirect(url);
        if (r != null) url = r;
        if (openInSiteContainer(t, url)) return;
        applySiteSettings(t, url, true);
        loadUrlWithEnv(t, url, null);
    }

    private void updateProgress() {
        if (fullscreen) {
            progress.setVisibility(View.GONE);
            return;
        }
        if (activeRemoteTabId != null) {
            JSONObject meta = activeRemoteMeta();
            boolean loading = meta != null && meta.optBoolean("loading", false);
            int prog = meta != null ? meta.optInt("progress", 100) : 100;
            boolean show = loading && prog < 100;
            progress.setProgress(Math.max(5, prog));
            progress.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
            return;
        }
        Tab t = active;
        if (t == null) {
            progress.setVisibility(View.INVISIBLE);
            return;
        }
        boolean show = t.loading && t.progress < 100;
        progress.setProgress(Math.max(5, t.progress));
        progress.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
    }

    private void reload() {
        if (activeRemoteTabId != null) {
            final String rid = activeRemoteTabId;
            try {
                dispatchRemoteAction(rid, new JSONObject().put("action", "reload").put("tab", rid), "ui-reload", 12000L, true);
            } catch (Throwable ignored) {}
            return;
        }
        String u = currentUrl();
        if (isHomeUrl(u)) goHome();
        else if (active != null && active.web != null) {
            active.web.reload();
        }
    }

    private void goBack() {
        if (activeRemoteTabId != null) {
            if (remoteCanGoBack()) {
                sendRemoteBack(activeRemoteTabId);
            } else {
                openSwitcher();
            }
            return;
        }
        if (active != null && web != null && !isHomeUrl(currentUrl()) && web.canGoBack()) goBackTab(active);
        else if (!isHomeUrl(currentUrl())) goHome();
        else agentMinimize();
    }

    private void goBackTab(Tab t) {
        if (t == null || t.closed || t.web == null || !t.web.canGoBack()) return;
        t.web.goBack();
    }

    private void goForwardTab(Tab t) {
        if (activeRemoteTabId != null) {
            if (remoteCanGoForward()) {
                sendRemoteForward(activeRemoteTabId);
            }
            return;
        }
        if (t == null || t.closed || t.web == null || !t.web.canGoForward()) return;
        t.web.goForward();
    }

    private boolean isLauncher(String url) {
        if (url == null) return false;
        String clean = url.split("[?#]", 2)[0];
        return clean.endsWith("/.launcher.html");
    }

    private boolean isHomeUrl(String u) {
        if (u == null || u.isEmpty()) return true;
        if (isLauncher(u)) return true;
        String clean = u.split("[?#]", 2)[0];
        String hp = uiHomepage();
        if (hp != null) {
            String rhp = resolveOpenUrl(hp).split("[?#]", 2)[0];
            if (clean.equals(hp.split("[?#]", 2)[0]) || clean.equals(rhp)) return true;
        }
        File custom = new File(appsDir, "home.html");
        if (custom.isFile() && clean.equals("file://" + custom.getAbsolutePath())) return true;
        return false;
    }

    private boolean isBottomToolbar() {
        return "bottom".equalsIgnoreCase(uiObj("theme").optString("toolbarPosition", "top"));
    }

    private Boolean lastBarHome = null;
    private final java.util.IdentityHashMap<View, LinearLayout.LayoutParams> barOrigLp = new java.util.IdentityHashMap<>();

    /**
     * 底栏双状态自适应：
     * 1. 没网页状态（主页）：urlPill 彻底 GONE，底栏按钮 weight=1f 均匀铺满全屏，绝无空洞
     * 2. 有网页状态：urlPill 撑满中间(weight=1f)，底栏按钮恢复固定紧凑宽度
     */
    private void syncUrlPill() {
        if (urlPill == null || bar == null) return;
        boolean onHome = activeRemoteTabId == null
                && active != null && isHomeUrl(active.url)
                && (urlField == null || !urlField.hasFocus());

        if (!isBottomToolbar()) {
            int vis = onHome ? View.INVISIBLE : View.VISIBLE;
            if (urlPill.getVisibility() != vis) urlPill.setVisibility(vis);
            return;
        }

        int want = onHome ? View.GONE : View.VISIBLE;
        if (lastBarHome != null && lastBarHome.booleanValue() == onHome && urlPill.getVisibility() == want) {
            return; // 幂等守卫
        }
        lastBarHome = onHome; // 先写守卫防焦点回调重入

        if (onHome && urlField != null && urlField.hasFocus()) {
            hideIme();
        }

        applyBarLayout(onHome);
    }

    private void applyBarLayout(boolean home) {
        if (bar == null) return;
        for (int i = 0; i < bar.getChildCount(); i++) {
            View c = bar.getChildAt(i);
            if (c == urlPill) continue;
            LinearLayout.LayoutParams cur = (LinearLayout.LayoutParams) c.getLayoutParams();
            if (cur == null) continue;
            if (home) {
                if (!barOrigLp.containsKey(c)) barOrigLp.put(c, new LinearLayout.LayoutParams(cur));
                c.setLayoutParams(new LinearLayout.LayoutParams(0, cur.height, 1f));
            } else {
                LinearLayout.LayoutParams o = barOrigLp.remove(c);
                if (o != null) c.setLayoutParams(o);
            }
        }
        if (urlPill != null) {
            urlPill.setVisibility(home ? View.GONE : View.VISIBLE);
            LinearLayout.LayoutParams ulp = (LinearLayout.LayoutParams) urlPill.getLayoutParams();
            if (ulp != null) {
                ulp.width = 0;
                ulp.weight = home ? 0f : 1f;
                urlPill.setLayoutParams(ulp);
            }
        }
        bar.requestLayout();
    }

    /** 要主动聚焦地址栏前先显示出来（主页上它是隐藏的，隐藏的 View 拿不到焦点） */
    private void revealUrlPill() {
        if (urlPill == null || bar == null) return;
        if (isBottomToolbar()) {
            lastBarHome = false;
            applyBarLayout(false);
        } else {
            if (urlPill.getVisibility() != View.VISIBLE) urlPill.setVisibility(View.VISIBLE);
        }
    }

    private void showUrl(Tab t, String url) {
        if (activeRemoteTabId != null) {
            syncUrlPill();
            if (!urlField.hasFocus()) {
                siteIcon.setText("⚡");
                String shown = url == null ? "" : url.replaceFirst("^https?://(www\\.)?", "");
                if (shown.endsWith("/")) shown = shown.substring(0, shown.length() - 1);
                urlField.setText(shown);
            }
            return;
        }
        if (t == active) syncUrlPill();
        if (t != active || urlField.hasFocus()) return;
        if (url == null || url.isEmpty() || isHomeUrl(url)) {
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
        return searchUrl(s);
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
        ThemeManager.ThemePalette p = uiPalette();
        cDark = p.isDark;
        cBar = color(th, "background", p.bg);
        cFg = color(th, "text", p.text);
        cPill = color(th, "addressBar", cDark ? blend(p.bg, Color.WHITE, 0.10f) : Color.parseColor("#FFE9ECF2"));
        cAddr = color(th, "addressText", p.text);
        cAccent = color(th, "accent", p.accent);
        cBorder = color(th, "border", p.border);
        cDim = p.textDim;
        pageColor = color(th, "page", p.bg);
        applyUiTheme();

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
        renderToolbar();
        renderBall();
        styleBall();
        if (switcher.getVisibility() == View.VISIBLE) rebuildSwitcher();
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

    // ------------------------------------------------------------------ 油猴用户脚本（beta.22，逻辑在 UserScriptManager）

    private UserScriptManager scriptMgr;

    /** 用户脚本管理器（M1 实现，线程安全）。BrowserAgent 与 AppBridgeManager 共用 */
    public synchronized UserScriptManager scripts() {
        if (scriptMgr == null) {
            scriptMgr = new UserScriptManager(appsDir);
            scriptMgr.setOnChangeListener(() -> MAIN.post(() -> {
                refreshAllDocStartScripts();
                if (workerPool != null) workerPool.broadcastScriptsSync();
            }));
        }
        return scriptMgr;
    }

    // ------------------------------------------------------------------ 跨源 iframe 消息通道、翻译与油猴支持（androidx.webkit）

    private static final int MAX_FRAME_SESSIONS = 64;
    private long lastFrameTransErrToastAt = 0L;
    private String lastFrameTransErrToast = null;

    private static boolean crossFrameSupported() {
        try {
            return WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                    && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER);
        } catch (Throwable e) {
            return false;
        }
    }

    private void cleanupTabFrames(Tab t, WebView w) {
        if (t == null) return;
        if (t.envDocStartHandler != null) {
            try { t.envDocStartHandler.remove(); } catch (Throwable ignored) {}
            t.envDocStartHandler = null;
        }
        t.lastAppliedEnvKey = null;
        if (t.transDocStartHandler != null) {
            try { t.transDocStartHandler.remove(); } catch (Throwable ignored) {}
            t.transDocStartHandler = null;
        }
        clearScriptDocStartHandlers(t);
        t.frames.clear();
        t.subframeInjectedIds.clear();
        t.translatingAllFrames = false;
        t.transAnnouncePending = false;
        t.resetMainTransStats();
        if (w != null) com.xm486.pimet.translation.WebTranslationManager.registerDelegate(w, null);
    }

    private void clearScriptDocStartHandlers(Tab t) {
        if (t == null) return;
        synchronized (t.scriptDocStartHandlers) {
            for (ScriptHandler h : t.scriptDocStartHandlers) {
                if (h != null) {
                    try { h.remove(); } catch (Throwable ignored) {}
                }
            }
            t.scriptDocStartHandlers.clear();
        }
    }

    private void setupCrossFrameSupport(Tab t, WebView w) {
        if (t == null || w == null || !crossFrameSupported()) return;
        try {
            WebViewCompat.addWebMessageListener(
                    w,
                    "__pimetFrameMsg",
                    Collections.singleton("*"),
                    (view, message, sourceOrigin, isMainFrame, replyProxy) ->
                            handleFrameMessage(t, view, message, isMainFrame, replyProxy)
            );
        } catch (Throwable e) {
            Log.w(TAG, "setupCrossFrameSupport failed, falling back to main-frame only", e);
        }
    }

    /** 应用/刷新容器环境：UA、UserAgentMetadata、X-Requested-With 消除、JS 接口隔离与 DocumentStart 反指纹 */
    private void applyContainerEnv(Tab t, WebView w, boolean forceReinstall) {
        if (t == null || w == null) return;
        try {
            t.envProfile = containers().getProfile(t.container);
        } catch (Throwable e) {
            t.envProfile = ContainerEnv.NATIVE;
        }
        t.proxySignature = "DIRECT";
        t.proxySummary = "直连";
        t.proxyFrozen = false;

        boolean effectiveDesktop = effectiveDesktopOf(t);
        String overrideUa = customUa();
        if (t.siteUaMode != null && !t.siteUaMode.isEmpty()
                && !"desktop".equalsIgnoreCase(t.siteUaMode)
                && !"mobile".equalsIgnoreCase(t.siteUaMode)) {
            overrideUa = t.siteUaMode;
        }

        if (t.isIsolated()) {
            if (t.bridgesAttached) {
                try {
                    w.removeJavascriptInterface("PiMetNative");
                    w.removeJavascriptInterface("PiMetTranslator");
                    w.removeJavascriptInterface("PiMetTheme");
                    w.removeJavascriptInterface("PiMetGM");
                } catch (Throwable ignored) {}
                t.bridgesAttached = false;
            }
            String effectiveUa = ContainerEnv.buildEffectiveUa(ctx, t.envProfile, effectiveDesktop, overrideUa);
            ContainerEnv.applyToWebSettings(ctx, w.getSettings(), t.envProfile, effectiveDesktop, overrideUa);
            String key = t.envProfile.preset + "|" + t.envProfile.timezone + "|" + t.envProfile.locale
                    + "|" + t.envProfile.acceptLanguage + "|" + t.envProfile.device + "|" + effectiveDesktop
                    + "|" + t.envProfile.seed + "|" + t.envProfile.webrtc + "|" + effectiveUa;
            if (forceReinstall || t.envDocStartHandler == null || !key.equals(t.lastAppliedEnvKey)) {
                if (t.envDocStartHandler != null) {
                    try { t.envDocStartHandler.remove(); } catch (Throwable ignored) {}
                    t.envDocStartHandler = null;
                }
                if (t.transDocStartHandler != null) {
                    try { t.transDocStartHandler.remove(); } catch (Throwable ignored) {}
                    t.transDocStartHandler = null;
                }
                clearScriptDocStartHandlers(t);
                if (crossFrameSupported()) {
                    String antiJs = AntiDetectJs.build(ctx, t.envProfile, effectiveDesktop, effectiveUa, appsUrlPrefix());
                    if (antiJs != null && !antiJs.isEmpty()) {
                        try {
                            t.envDocStartHandler = WebViewCompat.addDocumentStartJavaScript(w, antiJs, Collections.singleton("*"));
                        } catch (Throwable e) {
                            Log.w(TAG, "addDocumentStartJavaScript (anti-detect) failed", e);
                        }
                    }
                    installTransDocStartScript(t, w);
                }
                t.lastAppliedEnvKey = key;
            }
        } else {
            String key = "NATIVE|" + effectiveDesktop + "|" + (overrideUa == null ? "" : overrideUa);
            if (overrideUa != null && !overrideUa.isEmpty()) {
                w.getSettings().setUserAgentString(overrideUa);
            } else if (effectiveDesktop) {
                w.getSettings().setUserAgentString(desktopUa());
            } else {
                w.getSettings().setUserAgentString(null);
            }
            if (!t.bridgesAttached) {
                w.addJavascriptInterface(new Bridge(t), "PiMetNative");
                w.addJavascriptInterface(new TranslatorBridge(t), "PiMetTranslator");
                w.addJavascriptInterface(new ThemeBridge(t), "PiMetTheme");
                w.addJavascriptInterface(new GMBridge(t), "PiMetGM");
                t.bridgesAttached = true;
            }
            if (forceReinstall || !key.equals(t.lastAppliedEnvKey)) {
                if (t.envDocStartHandler != null) {
                    try { t.envDocStartHandler.remove(); } catch (Throwable ignored) {}
                    t.envDocStartHandler = null;
                }
                if (crossFrameSupported()) {
                    if (effectiveDesktop) {
                        try {
                            t.envDocStartHandler = WebViewCompat.addDocumentStartJavaScript(
                                    w, AntiDetectJs.buildNativeDesktopScript(ContainerEnv.DESKTOP_VIEWPORT_W, 1.0), Collections.singleton("*"));
                        } catch (Throwable ignored) {}
                    }
                }
                t.lastAppliedEnvKey = key;
            }
            if (forceReinstall) {
                installTransDocStartScript(t, w);
                installScriptDocStartScripts(t, w);
            }
        }
        syncDesktopViewport(t, effectiveDesktop, false);
    }

    /** 刷新指定容器下所有打开标签的环境（供 UI 与 BrowserAgent 调用，主进程恒直连） */
    int applyContainerEnvForContainer(String containerName, boolean reloadTabs) {
        if (containerName == null) return 0;
        int affected = 0;
        List<Tab> matched = new ArrayList<>();
        for (Tab t : new ArrayList<>(tabs)) {
            if (t == null || t.closed || t.web == null) continue;
            if (!containerName.equals(t.container)) continue;
            try {
                t.envProfile = containers().getProfile(containerName);
                t.desktop = (t.envProfile != null && t.envProfile.desktop) || (!t.pi && uiObj("defaults").optBoolean("desktopMode", false));
                t.siteUaMode = null;
                t.siteUa = null;
            } catch (Throwable ignored) {}
            applyContainerEnv(t, t.web, true);
            syncDesktopViewport(t, effectiveDesktopOf(t), !isTabOnOffscreenHost(t));
            matched.add(t);
            affected++;
        }
        if (reloadTabs) {
            for (Tab t : matched) {
                if (t.closed || t.web == null) continue;
                String u = t.url;
                if (u != null && !u.isEmpty() && !u.startsWith("about:")) {
                    t.web.reload();
                }
            }
        }
        refreshTabs();
        if (switcher.getVisibility() == View.VISIBLE) rebuildSwitcher();
        return affected;
    }

    /** 仅用于 testProxyAsync 代理连通性测试与 statusJson 聚合；主进程绝不再调用 ProxyController.setProxyOverride */
    synchronized ContainerProxyManager proxyManager() {
        if (proxyMgr == null) {
            proxyMgr = new ContainerProxyManager(ctx, () -> MAIN.post(this::refreshTabs));
        }
        return proxyMgr;
    }

    synchronized OffscreenWebHost offscreenHost() {
        if (offscreenHost == null) {
            offscreenHost = new OffscreenWebHost(ctx);
        }
        return offscreenHost;
    }

    synchronized ProxyWorkerPool workerPool() {
        if (workerPool == null) {
            workerPool = new ProxyWorkerPool(ctx, appsDir, containers());
            workerPool.setCustomUa(customUa());
        }
        return workerPool;
    }

    JSONObject proxyStatusJson() {
        JSONObject out = new JSONObject();
        try {
            out.put("proxySupported", proxyManager().isProxySupported());
            out.put("mainProcessDirect", true);
            out.put("frozenTabs", 0);
            JSONObject ap = new JSONObject();
            ap.put("container", ContainerManager.DEFAULT);
            ap.put("label", "主进程直连");
            ap.put("signature", "DIRECT");
            ap.put("summary", "直连");
            ap.put("direct", true);
            ap.put("localPort", 0);
            ap.put("switching", false);
            ap.put("frozenTabs", 0);
            ap.put("mainProcessDirect", true);
            out.put("activeProxy", ap);
            out.put("workers", workerPool().workersStatusJson());
        } catch (Throwable ignored) {}
        return out;
    }

    /**
     * 抓取主进程标签画面（可在后台线程或主线程调用）：
     * 后台或最小化时走 OffscreenWebHost.captureScreenshot（解除 minimized 拒拍限制）；
     * 前台可见时优先 PixelCopy，失败回退 web.draw。
     */
    Bitmap captureScreenshot(Tab t, long timeoutMs) {
        if (t == null || t.closed || t.web == null) return null;
        long budget = Math.max(800L, timeoutMs);
        final AtomicBoolean onOffscreen = new AtomicBoolean(false);
        final AtomicReference<Activity> actRef = new AtomicReference<>();
        final CountDownLatch chk = new CountDownLatch(1);
        Runnable checkRun = () -> {
            onOffscreen.set(minimized || activeRemoteTabId != null || t != active || isTabOnOffscreenHost(t));
            actRef.set(host);
            chk.countDown();
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            checkRun.run();
        } else {
            MAIN.post(checkRun);
            try { chk.await(1500L, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        }
        if (onOffscreen.get()) {
            return offscreenHost().captureScreenshot(t.web, budget);
        }
        Activity a = actRef.get();
        if (a != null && !a.isFinishing() && Build.VERSION.SDK_INT >= 26) {
            Bitmap px = pixelCopyForeground(t.web, a, Math.min(3000L, budget));
            if (px != null) return px;
        }
        final AtomicReference<Bitmap> drawRef = new AtomicReference<>();
        final CountDownLatch drawLatch = new CountDownLatch(1);
        Runnable drawRun = () -> {
            try {
                int w = t.web.getWidth(), h = t.web.getHeight();
                if (w > 0 && h > 0) {
                    Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    b.eraseColor(Color.WHITE);
                    Canvas c = new Canvas(b);
                    c.translate(-t.web.getScrollX(), -t.web.getScrollY());
                    t.web.draw(c);
                    drawRef.set(b);
                }
            } catch (Throwable ignored) {
            } finally {
                drawLatch.countDown();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            drawRun.run();
        } else {
            MAIN.post(drawRun);
            try { drawLatch.await(budget, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        }
        return drawRef.get();
    }

    private Bitmap pixelCopyForeground(WebView wv, Activity a, long timeoutMs) {
        if (wv == null || a == null || Build.VERSION.SDK_INT < 26) return null;
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger code = new AtomicInteger(-1);
        final AtomicReference<Bitmap> bmpRef = new AtomicReference<>();
        final AtomicBoolean posted = new AtomicBoolean(false);
        Runnable reqRun = () -> {
            try {
                Window w = a.getWindow();
                if (w == null || w.peekDecorView() == null) return;
                int vw = wv.getWidth(), vh = wv.getHeight();
                if (vw <= 0 || vh <= 0 || !wv.isShown()) return;
                int[] loc = new int[2];
                wv.getLocationInWindow(loc);
                Bitmap b = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888);
                bmpRef.set(b);
                PixelCopy.request(w, new Rect(loc[0], loc[1], loc[0] + vw, loc[1] + vh), b, r -> {
                    code.set(r);
                    latch.countDown();
                }, MAIN);
                posted.set(true);
            } catch (Throwable ignored) {}
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            reqRun.run();
            if (!posted.get()) return null;
        } else {
            final CountDownLatch prep = new CountDownLatch(1);
            MAIN.post(() -> { reqRun.run(); prep.countDown(); });
            try { prep.await(1500L, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
            if (!posted.get()) {
                Bitmap b = bmpRef.get();
                if (b != null) b.recycle();
                return null;
            }
        }
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        Bitmap b = bmpRef.get();
        if (code.get() != PixelCopy.SUCCESS || b == null) {
            if (b != null) b.recycle();
            return null;
        }
        return b;
    }

    // ------------------------------------------------------------------ 远程代理标签原生嵌入（API 30+）与降级预览

    @RequiresApi(Build.VERSION_CODES.R)
    private static final class SurfaceViewApi30 {
        static IBinder getHostToken(SurfaceView sv) {
            return sv != null ? sv.getHostToken() : null;
        }

        static void setChildSurfacePackage(SurfaceView sv, Object pkg) {
            if (sv != null && pkg instanceof SurfaceControlViewHost.SurfacePackage) {
                sv.setChildSurfacePackage((SurfaceControlViewHost.SurfacePackage) pkg);
            }
        }

        static void releasePackage(Object pkg) {
            if (pkg instanceof SurfaceControlViewHost.SurfacePackage) {
                try {
                    ((SurfaceControlViewHost.SurfacePackage) pkg).release();
                } catch (Throwable ignored) {}
            }
        }
    }

    public void onRemoteTabState(JSONObject ev) {
        if (ev == null) return;
        final ProxyWorkerPool pool = workerPool;
        if (pool == null) return;
        if (!pool.updateTabStateFromEvent(ev)) return;
        final String tid = ev.optString("tabId", "").trim();
        final String evUrl = ev.has("url") ? ev.optString("url", "") : null;
        MAIN.post(() -> {
            if (tid.equals(activeRemoteTabId)) {
                if (!urlField.hasFocus()) {
                    String u = evUrl;
                    if (u == null) {
                        JSONObject meta = pool.getCachedTabMeta(tid);
                        u = meta != null ? meta.optString("url", "") : "";
                    }
                    showUrl(active, u);
                }
                updateProgress();
            }
            refreshTabs();
        });
    }

    public void onRemoteTabFocused(String tabId) {
        if (tabId == null || tabId.isEmpty()) return;
        MAIN.post(() -> {
            if (tabId.equals(activeRemoteTabId) && urlField != null && urlField.hasFocus()) {
                urlField.clearFocus();
            }
        });
    }

    private boolean isRemoteSurfaceObscured() {
        return (switcher != null && switcher.getVisibility() == View.VISIBLE)
                || panelOpen()
                || customView != null
                || (ballMenu != null && ballMenu.getVisibility() == View.VISIBLE)
                || minimized
                || host == null;
    }

    private void cancelRemoteAttachTimeout() {
        if (remoteAttachTimeoutTask != null) {
            MAIN.removeCallbacks(remoteAttachTimeoutTask);
            remoteAttachTimeoutTask = null;
        }
    }

    private void suspendRemoteSurfaceForOverlay() {
        stopMirrorPolling();
        detachCurrentRemoteSurface(true);
        if (remoteSurfaceView != null) {
            remoteSurfaceView.setVisibility(View.GONE);
        }
    }

    private void resumeRemoteSurfaceIfNeeded() {
        if (activeRemoteTabId == null || isRemoteSurfaceObscured() || workerPool == null) {
            return;
        }
        ensureRemoteMirrorView();
        if (remoteMirrorContainer != null) {
            remoteMirrorContainer.setVisibility(View.VISIBLE);
            remoteMirrorContainer.bringToFront();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && remoteSurfaceView != null) {
            remoteFallbackActive = false;
            if (mirrorImageView != null) mirrorImageView.setVisibility(View.GONE);
            if (mirrorRefreshBtn != null) mirrorRefreshBtn.setVisibility(View.GONE);
            remoteSurfaceView.setVisibility(View.VISIBLE);
            attachCurrentRemoteSurface();
        } else {
            enableRemoteMirrorFallback(activeRemoteTabId);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void ensureRemoteMirrorView() {
        if (remoteMirrorContainer != null) return;
        remoteMirrorContainer = new LinearLayout(vctx);
        remoteMirrorContainer.setOrientation(LinearLayout.VERTICAL);
        remoteMirrorContainer.setClickable(true);
        remoteMirrorContainer.setBackgroundColor(pageColor);

        LinearLayout header = new LinearLayout(vctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(10), dp(4), dp(8), dp(4));
        header.setBackgroundColor(blend(cBar, cAccent, 0.14f));

        mirrorHeaderTv = new TextView(vctx);
        mirrorHeaderTv.setText("⚡ 代理进程");
        mirrorHeaderTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mirrorHeaderTv.setTextColor(cFg);
        mirrorHeaderTv.setSingleLine(true);
        mirrorHeaderTv.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(mirrorHeaderTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mirrorRefreshBtn = barBtn("刷新画面", "刷新远程代理标签画面", 12);
        mirrorRefreshBtn.setTextColor(cAccent);
        mirrorRefreshBtn.setPadding(dp(8), 0, dp(8), 0);
        mirrorRefreshBtn.setOnClickListener(v -> refreshRemoteMirrorNow());
        mirrorRefreshBtn.setVisibility(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? View.GONE : View.VISIBLE);
        header.addView(mirrorRefreshBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)));

        TextView backBtn = barBtn("返回主标签", "返回主进程标签", 12);
        backBtn.setTextColor(cAccent);
        backBtn.setPadding(dp(8), 0, dp(4), 0);
        backBtn.setOnClickListener(v -> exitRemoteMirror());
        header.addView(backBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)));

        remoteMirrorContainer.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));

        FrameLayout stage = new FrameLayout(vctx);
        stage.setBackgroundColor(Color.BLACK);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            remoteSurfaceView = new SurfaceView(vctx);
            // 第一层保险：置于宿主窗口之上，使标准 Android 11-15 InputDispatcher 直接把触摸投递给子进程 SurfaceControlViewHost
            remoteSurfaceView.setZOrderOnTop(true);
            remoteSurfaceView.setFocusable(true);
            remoteSurfaceView.setFocusableInTouchMode(true);
            // 第二层保险：仅当某些国产 ROM 仍将触摸路由给宿主 SurfaceView 时触发，与第一层 100% 互斥、零双触发
            remoteSurfaceView.setOnTouchListener((v, ev) -> {
                if (ev == null) return false;
                final String tid = activeRemoteTabId;
                final long seq = activeRemoteAttachSeq;
                final ProxyWorkerPool pool = workerPool;
                if (tid == null || pool == null || !remoteSurfaceReady || remoteFallbackActive
                        || seq <= 0L || isRemoteSurfaceObscured()) {
                    return false;
                }
                if (ev.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                    return false;
                }
                if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    if (urlField != null && urlField.hasFocus()) {
                        hideIme();
                    }
                    if (!v.hasFocus()) {
                        v.requestFocus();
                    }
                    Activity h = host;
                    if (h != null && h.getWindow() != null) {
                        try {
                            h.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                        } catch (Throwable ignored) {}
                    }
                }
                return pool.sendDispatchTouch(tid, seq, ev, v.getWidth(), v.getHeight());
            });
            remoteSurfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder holder) {
                    attachCurrentRemoteSurface();
                }

                @Override
                public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                    if (activeRemoteTabId == null || isRemoteSurfaceObscured() || workerPool == null) return;
                    if (remoteSurfaceReady && activeRemoteTabId.equals(attachedRemoteTabId)) {
                        if (width > 0 && height > 0 && activeRemoteAttachSeq > 0L) {
                            workerPool.sendResizeSurface(activeRemoteTabId, activeRemoteAttachSeq, width, height);
                        }
                    } else if (!remoteFallbackActive) {
                        attachCurrentRemoteSurface();
                    }
                }

                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {
                    detachCurrentRemoteSurface(true);
                }
            });
            stage.addView(remoteSurfaceView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        mirrorImageView = new ImageView(vctx);
        mirrorImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mirrorImageView.setBackgroundColor(Color.BLACK);
        mirrorImageView.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_UP && activeRemoteTabId != null) {
                sendRemoteMirrorTap(activeRemoteTabId, ev.getX(), ev.getY());
            }
            return true;
        });
        mirrorImageView.setVisibility(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? View.GONE : View.VISIBLE);
        stage.addView(mirrorImageView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        remoteMirrorContainer.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        remoteMirrorContainer.setVisibility(View.GONE);
        webFrame.addView(remoteMirrorContainer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void attachCurrentRemoteSurface() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (activeRemoteTabId != null) enableRemoteMirrorFallback(activeRemoteTabId);
            return;
        }
        final String tabId = activeRemoteTabId;
        if (tabId == null || workerPool == null || remoteSurfaceView == null || isRemoteSurfaceObscured()) {
            return;
        }
        if (remoteSurfaceView.getVisibility() != View.VISIBLE) {
            return;
        }
        SurfaceHolder holder = remoteSurfaceView.getHolder();
        if (holder == null || holder.getSurface() == null || !holder.getSurface().isValid()) {
            return;
        }
        int w = remoteSurfaceView.getWidth();
        int h = remoteSurfaceView.getHeight();
        if (w <= 0 || h <= 0) {
            Rect frame = holder.getSurfaceFrame();
            if (frame != null) {
                w = frame.width();
                h = frame.height();
            }
        }
        if (w <= 0 || h <= 0) return;

        if (remoteSurfaceReady && tabId.equals(attachedRemoteTabId)) {
            return;
        }
        if (activeRemoteAttachSeq > 0L && tabId.equals(attachingRemoteTabId)
                && attachingRemoteW == w && attachingRemoteH == h) {
            return;
        }

        IBinder hostToken = SurfaceViewApi30.getHostToken(remoteSurfaceView);
        if (hostToken == null) {
            remoteSurfaceView.post(this::attachCurrentRemoteSurface);
            return;
        }
        Display disp = remoteSurfaceView.getDisplay();
        int displayId = disp != null ? disp.getDisplayId() : Display.DEFAULT_DISPLAY;

        long prevSeq = activeRemoteAttachSeq;
        if (prevSeq > 0L) {
            workerPool.cancelPendingAttach(prevSeq);
            String prevTid = attachedRemoteTabId != null ? attachedRemoteTabId : attachingRemoteTabId;
            if (prevTid != null && !prevTid.isEmpty()) {
                workerPool.sendDetachSurface(prevTid, prevSeq);
            }
        }
        cancelRemoteAttachTimeout();

        final long seq = remoteAttachSeqGen.incrementAndGet();
        activeRemoteAttachSeq = seq;
        attachingRemoteTabId = tabId;
        attachingRemoteW = w;
        attachingRemoteH = h;
        remoteSurfaceReady = false;
        remoteFallbackActive = false;

        final Runnable timeoutTask = () -> {
            if (seq == activeRemoteAttachSeq && tabId.equals(activeRemoteTabId) && !remoteSurfaceReady) {
                Log.w(TAG, "remote attach surface timeout tab=" + tabId + " seq=" + seq);
                if (workerPool != null) {
                    workerPool.cancelPendingAttach(seq);
                    workerPool.sendDetachSurface(tabId, seq);
                }
                enableRemoteMirrorFallback(tabId);
            }
        };
        remoteAttachTimeoutTask = timeoutTask;
        MAIN.postDelayed(timeoutTask, 2500L);

        boolean sent = workerPool.sendAttachSurface(tabId, seq, hostToken, displayId, w, h,
                (ok, surfacePackage, stateBundle, error) -> MAIN.post(() -> {
                    if (seq == activeRemoteAttachSeq) {
                        cancelRemoteAttachTimeout();
                    }
                    if (!tabId.equals(activeRemoteTabId) || seq != activeRemoteAttachSeq || isRemoteSurfaceObscured()) {
                        if (surfacePackage != null) {
                            SurfaceViewApi30.releasePackage(surfacePackage);
                        }
                        if (workerPool != null) {
                            workerPool.sendDetachSurface(tabId, seq);
                        }
                        return;
                    }
                    if (ok && surfacePackage != null && remoteSurfaceView != null) {
                        SurfaceViewApi30.setChildSurfacePackage(remoteSurfaceView, surfacePackage);
                        attachedRemoteTabId = tabId;
                        attachingRemoteTabId = null;
                        remoteSurfaceReady = true;
                        remoteFallbackActive = false;
                        stopMirrorPolling();
                        if (mirrorImageView != null) mirrorImageView.setVisibility(View.GONE);
                        if (mirrorRefreshBtn != null) mirrorRefreshBtn.setVisibility(View.GONE);
                        remoteSurfaceView.setVisibility(View.VISIBLE);
                        ProxyWorkerPool.WorkerSlot slot = workerPool != null ? workerPool.slotForTab(tabId) : null;
                        if (mirrorHeaderTv != null && slot != null) {
                            mirrorHeaderTv.setText("⚡ 代理进程 #" + slot.slot + " · " + slot.proxySummary);
                        }
                        syncRemoteStateBundle(tabId, stateBundle);
                    } else {
                        Log.w(TAG, "attach surface failed tab=" + tabId + " seq=" + seq + " err=" + error);
                        if (surfacePackage != null) {
                            SurfaceViewApi30.releasePackage(surfacePackage);
                        }
                        enableRemoteMirrorFallback(tabId);
                    }
                }));

        if (!sent) {
            cancelRemoteAttachTimeout();
            attachingRemoteTabId = null;
            enableRemoteMirrorFallback(tabId);
        }
    }

    private void detachCurrentRemoteSurface(boolean sendToWorker) {
        cancelRemoteAttachTimeout();
        long seq = activeRemoteAttachSeq;
        String tid = attachedRemoteTabId != null
                ? attachedRemoteTabId
                : (attachingRemoteTabId != null ? attachingRemoteTabId : activeRemoteTabId);
        activeRemoteAttachSeq = 0L;
        attachedRemoteTabId = null;
        attachingRemoteTabId = null;
        attachingRemoteW = 0;
        attachingRemoteH = 0;
        remoteSurfaceReady = false;
        if (seq > 0L && workerPool != null) {
            workerPool.cancelPendingAttach(seq);
            if (sendToWorker && tid != null && !tid.isEmpty()) {
                workerPool.sendDetachSurface(tid, seq);
            }
        }
    }

    private void enableRemoteMirrorFallback(String tabId) {
        if (tabId == null || !tabId.equals(activeRemoteTabId)) return;
        detachCurrentRemoteSurface(false);
        remoteFallbackActive = true;
        remoteSurfaceReady = false;
        if (remoteSurfaceView != null) {
            remoteSurfaceView.setVisibility(View.GONE);
        }
        if (mirrorImageView != null) {
            mirrorImageView.setVisibility(View.VISIBLE);
        }
        if (mirrorRefreshBtn != null) {
            mirrorRefreshBtn.setVisibility(View.VISIBLE);
        }
        refreshRemoteMirrorNow();
        startMirrorPolling();
    }

    private void syncRemoteStateBundle(String tabId, Bundle b) {
        if (tabId == null || !tabId.equals(activeRemoteTabId)) return;
        String url = b != null ? b.getString(ProxyWorkerBridge.KEY_URL, "") : "";
        if (url.isEmpty()) {
            JSONObject meta = activeRemoteMeta();
            if (meta != null) url = meta.optString("url", "");
        }
        if (!urlField.hasFocus()) {
            showUrl(active, url);
        }
        updateProgress();
        refreshTabs();
    }

    private JSONObject activeRemoteMeta() {
        String rid = activeRemoteTabId;
        ProxyWorkerPool pool = workerPool;
        if (rid == null || pool == null) return null;
        return pool.getCachedTabMeta(rid);
    }

    private boolean remoteCanGoBack() {
        JSONObject meta = activeRemoteMeta();
        return meta != null && meta.optBoolean("canGoBack", false);
    }

    private boolean remoteCanGoForward() {
        JSONObject meta = activeRemoteMeta();
        return meta != null && meta.optBoolean("canGoForward", false);
    }

    private boolean remoteIsDesktop() {
        JSONObject meta = activeRemoteMeta();
        return meta != null && meta.optBoolean("desktop", false);
    }

    boolean switchToRemote(String remoteTabId) {
        if (remoteTabId == null || remoteTabId.isEmpty() || workerPool == null) return false;
        ProxyWorkerPool.WorkerSlot slot = workerPool.slotForTab(remoteTabId);
        if (slot == null) return false;
        if (activeRemoteTabId != null && !remoteTabId.equals(activeRemoteTabId)) {
            detachCurrentRemoteSurface(true);
        }
        ensureRemoteMirrorView();
        activeRemoteTabId = remoteTabId;
        remoteFallbackActive = false;
        applyVisibility();
        updateProgress();
        if (mirrorHeaderTv != null) {
            mirrorHeaderTv.setText("⚡ 代理进程 #" + slot.slot + " · " + slot.proxySummary);
        }
        JSONObject meta = workerPool.getCachedTabMeta(remoteTabId);
        String cachedUrl = meta != null ? meta.optString("url", "") : "";
        if (!urlField.hasFocus()) {
            urlField.setText("");
            showUrl(active, cachedUrl);
        }
        refreshTabs();
        if (!isRemoteSurfaceObscured()) {
            resumeRemoteSurfaceIfNeeded();
        } else {
            suspendRemoteSurfaceForOverlay();
        }
        return true;
    }

    void exitRemoteMirror() {
        if (activeRemoteTabId == null) return;
        suspendRemoteSurfaceForOverlay();
        activeRemoteTabId = null;
        remoteFallbackActive = false;
        hideRemoteMirror();
        applyVisibility();
        updateProgress();
        if (!urlField.hasFocus() && active != null) {
            urlField.setText("");
            showUrl(active, active.url);
        }
        refreshTabs();
    }

    private void hideRemoteMirror() {
        if (remoteSurfaceView != null) {
            remoteSurfaceView.setVisibility(View.GONE);
        }
        if (remoteMirrorContainer != null) {
            remoteMirrorContainer.setVisibility(View.GONE);
        }
    }

    private void startMirrorPolling() {
        MAIN.removeCallbacks(mirrorPollTask);
        if (activeRemoteTabId != null && !minimized && host != null
                && (remoteFallbackActive || Build.VERSION.SDK_INT < Build.VERSION_CODES.R)) {
            MAIN.postDelayed(mirrorPollTask, 700L);
        }
    }

    private void stopMirrorPolling() {
        MAIN.removeCallbacks(mirrorPollTask);
    }

    private void refreshRemoteMirrorNow() {
        String rid = activeRemoteTabId;
        if (rid != null) fetchRemoteMirrorFrame(rid);
    }

    private void fetchRemoteMirrorFrame(String remoteTabId) {
        if (remoteTabId == null || workerPool == null) return;
        ProxyWorkerPool.WorkerSlot slot = workerPool.slotForTab(remoteTabId);
        if (slot == null) {
            MAIN.post(this::exitRemoteMirror);
            return;
        }
        if (!mirrorFetching.compareAndSet(false, true)) return;
        mirrorExec.execute(() -> {
            try {
                JSONObject req = new JSONObject()
                        .put("action", "mirror_frame")
                        .put("tab", remoteTabId)
                        .put("max_side", 720)
                        .put("quality", 55);
                JSONObject resp = workerPool.callRpc(slot, req, "mirror-" + remoteTabId, 3500L);
                if (resp.optBoolean("success", false)) {
                    String b64 = resp.optString("image", "");
                    String rUrl = resp.optString("url", "");
                    int vw = resp.optInt("viewW", 0);
                    int vh = resp.optInt("viewH", 0);
                    Bitmap bmp = null;
                    if (!b64.isEmpty()) {
                        byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                        bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    }
                    final Bitmap fBmp = bmp;
                    MAIN.post(() -> {
                        if (!remoteTabId.equals(activeRemoteTabId)) {
                            if (fBmp != null) fBmp.recycle();
                            return;
                        }
                        if (vw > 0 && vh > 0) {
                            mirrorRemoteW = vw;
                            mirrorRemoteH = vh;
                        }
                        if (fBmp != null && mirrorImageView != null) {
                            mirrorImageView.setImageBitmap(fBmp);
                        }
                        if (mirrorHeaderTv != null) {
                            mirrorHeaderTv.setText("⚡ 代理进程 #" + slot.slot + " · " + slot.proxySummary
                                    + (vw > 0 ? " (" + vw + "x" + vh + ")" : ""));
                        }
                        if (!urlField.hasFocus() && !rUrl.isEmpty()) {
                            showUrl(active, rUrl);
                        }
                    });
                }
            } catch (Throwable ignored) {
            } finally {
                mirrorFetching.set(false);
            }
        });
    }

    private void sendRemoteMirrorTap(String remoteTabId, float viewX, float viewY) {
        if (mirrorImageView == null || workerPool == null) return;
        ProxyWorkerPool.WorkerSlot slot = workerPool.slotForTab(remoteTabId);
        if (slot == null) return;
        android.graphics.drawable.Drawable dr = mirrorImageView.getDrawable();
        if (dr == null) return;
        int dw = dr.getIntrinsicWidth();
        int dh = dr.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) return;

        float[] pts = new float[]{viewX, viewY};
        Matrix inv = new Matrix();
        if (!mirrorImageView.getImageMatrix().invert(inv)) return;
        inv.mapPoints(pts);
        double nx = Math.max(0.0, Math.min(1.0, pts[0] / dw));
        double ny = Math.max(0.0, Math.min(1.0, pts[1] / dh));

        mirrorExec.execute(() -> {
            try {
                JSONObject req = new JSONObject()
                        .put("action", "mirror_tap")
                        .put("tab", remoteTabId)
                        .put("nx", nx)
                        .put("ny", ny)
                        .put("normalized", true);
                workerPool.callRpc(slot, req, "mtap-" + remoteTabId, 4000L);
            } catch (Throwable ignored) {}
            MAIN.postDelayed(() -> {
                if (remoteTabId.equals(activeRemoteTabId) && (!remoteSurfaceReady || remoteFallbackActive)) {
                    fetchRemoteMirrorFrame(remoteTabId);
                }
            }, 180L);
        });
    }

    private void dispatchRemoteAction(String remoteTabId, JSONObject req, String reqPrefix, long timeoutMs, boolean refreshMirrorOnFallback) {
        if (remoteTabId == null || remoteTabId.isEmpty() || workerPool == null) return;
        final ProxyWorkerPool.WorkerSlot slot = workerPool.slotForTab(remoteTabId);
        if (slot == null) return;
        mirrorExec.execute(() -> {
            try {
                workerPool.callRpc(slot, req, reqPrefix + "-" + remoteTabId + "-" + System.nanoTime(), timeoutMs);
            } catch (Throwable ignored) {}
            MAIN.post(() -> {
                if (remoteTabId.equals(activeRemoteTabId)) {
                    if (!urlField.hasFocus()) {
                        showUrl(active, currentUrl());
                    }
                    updateProgress();
                    refreshTabs();
                }
            });
            if (refreshMirrorOnFallback && (!remoteSurfaceReady || remoteFallbackActive)) {
                fetchRemoteMirrorFrame(remoteTabId);
            }
        });
    }

    private void sendRemoteHome(String remoteTabId) {
        try {
            dispatchRemoteAction(remoteTabId, new JSONObject().put("action", "home").put("tab", remoteTabId), "ui-home", 12000L, true);
        } catch (Throwable ignored) {}
    }

    private void sendRemoteBack(String remoteTabId) {
        try {
            dispatchRemoteAction(remoteTabId, new JSONObject().put("action", "back").put("tab", remoteTabId), "ui-back", 12000L, true);
        } catch (Throwable ignored) {}
    }

    private void sendRemoteForward(String remoteTabId) {
        try {
            dispatchRemoteAction(remoteTabId, new JSONObject().put("action", "forward").put("tab", remoteTabId), "ui-forward", 12000L, true);
        } catch (Throwable ignored) {}
    }

    private void navigateRemoteFromUi(String remoteTabId, String targetUrl) {
        if (remoteTabId == null || targetUrl == null || targetUrl.isEmpty() || workerPool == null) return;
        try {
            dispatchRemoteAction(
                    remoteTabId,
                    new JSONObject()
                            .put("action", "open")
                            .put("tab", remoteTabId)
                            .put("url", targetUrl)
                            .put("snapshot", false),
                    "mnav",
                    20000L,
                    true);
        } catch (Throwable ignored) {}
    }

    private static boolean isLoopbackUrl(String u) {
        if (u == null) return false;
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return false;
        try {
            return ContainerProxyManager.isLoopbackHost(Uri.parse(u).getHost());
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isLocalUrl(String u) {
        if (u == null || u.isEmpty()) return true;
        if (isHomeUrl(u) || isLoopbackUrl(u)) return true;
        return u.startsWith("file://") || u.startsWith("about:") || u.startsWith("data:") || u.startsWith("blob:") || WebPinchZoom.isLocal(u);
    }

    private void loadUrlWithEnv(Tab t, String url, Map<String, String> baseHeaders) {
        if (t == null || t.web == null || url == null) return;
        t.pendingRecoverUrl = null;
        Map<String, String> extra = ContainerEnv.extraHeaders(t.envProfile);
        if ((baseHeaders == null || baseHeaders.isEmpty()) && (extra == null || extra.isEmpty())) {
            t.web.loadUrl(url);
            return;
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            Map<String, String> merged = new java.util.LinkedHashMap<>();
            if (extra != null) merged.putAll(extra);
            if (baseHeaders != null) merged.putAll(baseHeaders);
            t.web.loadUrl(url, merged);
        } else {
            t.web.loadUrl(url);
        }
    }

    private void installTransDocStartScript(Tab t, WebView w) {
        if (t == null || t.closed || w == null || !crossFrameSupported()) return;
        if (t.transDocStartHandler != null) {
            try { t.transDocStartHandler.remove(); } catch (Throwable ignored) {}
            t.transDocStartHandler = null;
        }
        try {
            String js = buildTransDocumentStartScript(t);
            t.transDocStartHandler = WebViewCompat.addDocumentStartJavaScript(w, js, Collections.singleton("*"));
        } catch (Throwable e) {
            Log.w(TAG, "addDocumentStartJavaScript (trans) failed", e);
        }
    }

    private void installScriptDocStartScripts(Tab t, WebView w) {
        if (t == null || t.closed || w == null || !crossFrameSupported()) return;
        clearScriptDocStartHandlers(t);
        if (t.isIsolated()) return;
        List<UserScriptManager.SubframeScriptEntry> entries;
        try {
            entries = scripts().buildSubframeScriptEntries(t.frameSecret);
        } catch (Throwable e) {
            Log.w(TAG, "buildSubframeScriptEntries failed", e);
            return;
        }
        if (entries == null || entries.isEmpty()) return;
        String trustedPrefix = JSONObject.quote(appsUrlPrefix());
        for (UserScriptManager.SubframeScriptEntry entry : entries) {
            if (entry == null || entry.js == null || entry.js.isEmpty()) continue;
            try {
                String wrapped = "if (window !== window.top && String((window.location && window.location.href) || '').indexOf("
                        + trustedPrefix + ") !== 0) {\n" + entry.js + "\n}";
                ScriptHandler h = WebViewCompat.addDocumentStartJavaScript(w, wrapped, Collections.singleton("*"));
                if (h != null) {
                    synchronized (t.scriptDocStartHandlers) {
                        t.scriptDocStartHandlers.add(h);
                    }
                }
            } catch (Throwable e) {
                Log.w(TAG, "addDocumentStartJavaScript (script " + entry.id + ") failed", e);
            }
        }
    }

    void refreshAllDocStartScripts() {
        if (!crossFrameSupported()) return;
        for (Tab t : new ArrayList<>(tabs)) {
            if (t != null && !t.closed && t.web != null) {
                if (t.isIsolated()) {
                    installTransDocStartScript(t, t.web);
                } else {
                    installScriptDocStartScripts(t, t.web);
                }
            }
        }
    }

    private String buildTransDocumentStartScript(Tab t) {
        boolean isolated = t != null && t.isIsolated();
        String subframeTrans = com.xm486.pimet.translation.TranslationScript.buildSubframeRuntime();
        String mainTrans = isolated ? com.xm486.pimet.translation.TranslationScript.buildMainRuntime() : "null";
        String isolatedGm = "";
        if (isolated) {
            try {
                isolatedGm = scripts().buildIsolatedRuntime();
            } catch (Throwable e) {
                Log.w(TAG, "buildIsolatedRuntime failed", e);
            }
        }
        String secretQuoted = JSONObject.quote(t.frameSecret);
        String appsPrefixQuoted = JSONObject.quote(appsUrlPrefix());
        String[] head = {
                "(function(){",
                "  var W = window;",
                "  var pm = W.__pimetFrameMsg;",
                "  try { delete W.__pimetFrameMsg; } catch (e) {}",
                "  var SECRET = " + secretQuoted + ";",
                "  var APPS_PREFIX = " + appsPrefixQuoted + ";",
                "  var ISOLATED = " + isolated + ";",
                "  var IS_TOP = (W === W.top);",
                "  var FRAME_ID = 'f_' + Math.random().toString(36).slice(2) + '_' + Date.now().toString(36);",
                "  var href = ''; try { href = String((W.location && W.location.href) || ''); } catch (e) {}",
                "  var isAppsPage = !!(APPS_PREFIX && href.indexOf(APPS_PREFIX) === 0);",
                "  var isLocalPage = !!(href.indexOf('file://') === 0 || /^https?:\\/\\/(127\\.0\\.0\\.1|localhost)(:\\d+)?(\\/|$)/i.test(href));",
                "  function postFrame(obj) {",
                "    if (!pm || !obj || typeof obj !== 'object') return;",
                "    try {",
                "      obj.secret = SECRET;",
                "      obj.frameId = FRAME_ID;",
                "      pm.postMessage(JSON.stringify(obj));",
                "    } catch (e) {}",
                "  }",
                "  if (ISOLATED && IS_TOP && isAppsPage) {",
                "    W.PiMetNative = {",
                "      exec: function(cmd, cbId, timeoutSec) { postFrame({ type: 'native_exec', cmd: String(cmd || ''), cbId: String(cbId || ''), timeoutSec: timeoutSec | 0 }); },",
                "      askPi: function(text, send) { postFrame({ type: 'native_askpi', text: String(text || ''), send: !!send }); },",
                "      toast: function(msg) { postFrame({ type: 'native_toast', msg: String(msg || '') }); },",
                "      openExternal: function(url) { postFrame({ type: 'native_open_external', url: String(url || '') }); },",
                "      close: function() { postFrame({ type: 'native_close' }); }",
                "    };",
                "  }",
                "  if (ISOLATED && IS_TOP && isLocalPage) {",
                "    W.PiMetTheme = {",
                "      onWebTheme: function(themeId) { postFrame({ type: 'web_theme', themeId: String(themeId || '') }); }",
                "    };",
                "  }",
                "  var mainTransCtrl = (ISOLATED && IS_TOP && !isAppsPage) ? (" + mainTrans + ")(postFrame) : null;",
                "  var subTransCtrl = (!IS_TOP && !isAppsPage) ? (" + subframeTrans + ")(postFrame) : null;",
                (isolatedGm != null && !isolatedGm.isEmpty()
                        ? "  var gmCtrl = null; try { if (ISOLATED && !isAppsPage) gmCtrl = (" + isolatedGm + ")(postFrame); } catch (e) { try { console.error('[PiMet GM init]', e); } catch (e2) {} }"
                        : "  var gmCtrl = null;"),
                "  if (pm) {",
                "    pm.addEventListener('message', function(ev) {",
                "      var msg;",
                "      try { msg = typeof ev.data === 'string' ? JSON.parse(ev.data) : ev.data; } catch (e) { return; }",
                "      if (!msg || typeof msg !== 'object') return;",
                "      if (msg.type === 'blob_download') {",
                "        (function(dlNonce, dlUrl) {",
                "          if (!dlNonce || !dlUrl) return;",
                "          fetch(dlUrl).then(function(r) { return r.blob(); }).then(function(b) {",
                "            var CH = 262144, off = 0;",
                "            function next() {",
                "              var part = b.slice(off, off + CH);",
                "              var fr = new FileReader();",
                "              fr.onload = function() {",
                "                var s = String(fr.result || ''), idx = s.indexOf(',');",
                "                var b64 = idx >= 0 ? s.slice(idx + 1) : '';",
                "                off += CH;",
                "                var last = off >= b.size;",
                "                postFrame({ type: 'blob_chunk', nonce: dlNonce, b64: b64, last: last, err: '' });",
                "                if (!last) setTimeout(next, 0);",
                "              };",
                "              fr.onerror = function() { postFrame({ type: 'blob_chunk', nonce: dlNonce, b64: '', last: true, err: '读取失败' }); };",
                "              fr.readAsDataURL(part);",
                "            }",
                "            if (b.size === 0) postFrame({ type: 'blob_chunk', nonce: dlNonce, b64: '', last: true, err: '' });",
                "            else next();",
                "          }).catch(function(e) {",
                "            postFrame({ type: 'blob_chunk', nonce: dlNonce, b64: '', last: true, err: '读取失败: ' + e + '（网页可能已释放这个 blob）' });",
                "          });",
                "        })(String(msg.nonce || ''), String(msg.url || ''));",
                "        return;",
                "      }",
                "      if (msg.type === 'gm_cb' || msg.type === 'gm_menu') {",
                "        if (gmCtrl) { try { gmCtrl.onMessage(msg); } catch (e) {} return; }",
                "        if (!ISOLATED) {",
                "          if (msg.type === 'gm_cb' && typeof W.__pimetGMcb === 'function') { try { W.__pimetGMcb(msg.callId, msg.resultJson); } catch (e) {} }",
                "          else if (msg.type === 'gm_menu' && typeof W.__pimetGMmenu === 'function') { try { W.__pimetGMmenu(msg.scriptId, msg.cmdId); } catch (e) {} }",
                "        }",
                "        return;",
                "      }",
                "      if (IS_TOP && msg.type === 'trans_summary') {",
                "        if (mainTransCtrl) { try { mainTransCtrl.applySummary(msg); } catch (e) {} }",
                "        else if (typeof W.__pimet_apply_summary === 'function') { try { W.__pimet_apply_summary(msg); } catch (e) {} }",
                "        return;",
                "      }",
                "      if (IS_TOP && mainTransCtrl) {",
                "        if (msg.type === 'trans_start') { mainTransCtrl.start(msg); return; }",
                "        if (msg.type === 'trans_restore') { mainTransCtrl.restore(true); return; }",
                "        if (msg.type === 'trans_retry') { mainTransCtrl.retry(); return; }",
                "        if (msg.type === 'trans_batch_result') {",
                "          mainTransCtrl.receiveBatch(msg.batchId, msg.translationsJson, !!msg.isError, msg.errMsg || '');",
                "          return;",
                "        }",
                "      }",
                "      if (!IS_TOP && subTransCtrl) {",
                "        if (msg.type === 'trans_start') { subTransCtrl.start(msg); return; }",
                "        if (msg.type === 'trans_restore') { subTransCtrl.restore(true); return; }",
                "        if (msg.type === 'trans_retry') { subTransCtrl.retry(); return; }",
                "        if (msg.type === 'trans_batch_result') {",
                "          subTransCtrl.receiveBatch(msg.batchId, msg.translationsJson, !!msg.isError, msg.errMsg || '');",
                "          return;",
                "        }",
                "      }",
                "    });",
                "    postFrame({ type: 'hello', url: href });",
                "  }",
                "})();"
        };
        return String.join("\n", head);
    }

    private void handleFrameMessage(Tab t, WebView view, WebMessageCompat message,
                                    boolean isMainFrame, JavaScriptReplyProxy replyProxy) {
        if (t == null || t.closed || t.web != view || message == null || replyProxy == null) return;
        if (message.getType() != WebMessageCompat.TYPE_STRING) return;
        String raw = message.getData();
        if (raw == null || raw.isEmpty()) return;

        JSONObject msg;
        try {
            msg = new JSONObject(raw);
        } catch (Throwable e) {
            return;
        }
        String secret = msg.optString("secret", "");
        if (!t.frameSecret.equals(secret)) return;
        String type = msg.optString("type", "");
        String frameId = msg.optString("frameId", "");
        if (frameId.isEmpty()) return;

        switch (type) {
            case "hello": {
                String url = msg.optString("url", "");
                if (isMainFrame) {
                    for (Iterator<Map.Entry<String, FrameSession>> it = t.frames.entrySet().iterator(); it.hasNext(); ) {
                        Map.Entry<String, FrameSession> e = it.next();
                        if (!frameId.equals(e.getKey())) it.remove();
                    }
                    t.subframeInjectedIds.clear();
                    t.translatingAllFrames = false;
                    t.translated = false;
                    t.resetMainTransStats();
                    refreshBall();
                } else {
                    if (!t.frames.containsKey(frameId) && t.frames.size() >= MAX_FRAME_SESSIONS) {
                        return;
                    }
                }
                FrameSession fs = new FrameSession(frameId, isMainFrame, url, replyProxy);
                t.frames.put(frameId, fs);
                if (!isMainFrame && t.translatingAllFrames && !t.pi && !isLauncher(t.url) && !isTrusted(t.url)) {
                    sendTransStartToFrame(t, fs);
                    updateAggregatedTranslationUi(t);
                }
                break;
            }
            case "native_exec": {
                if (!isMainFrame || !isTrusted(t.url)) return;
                String cmd = msg.optString("cmd", "");
                String cbId = msg.optString("cbId", "");
                int timeout = Math.max(1, Math.min(msg.optInt("timeoutSec", 60), 600));
                new Thread(() -> runCommand(t, cmd, cbId, timeout), "pimet-apps-exec").start();
                break;
            }
            case "native_askpi": {
                if (!isMainFrame || !isTrusted(t.url)) return;
                String text = msg.optString("text", "");
                boolean send = msg.optBoolean("send", true);
                MAIN.post(() -> {
                    if (sPiBridge != null) sPiBridge.askPi(text, send);
                    else Toast.makeText(ctx, "工作台未就绪", Toast.LENGTH_SHORT).show();
                });
                break;
            }
            case "native_toast": {
                if (!isMainFrame || !isTrusted(t.url)) return;
                String m = msg.optString("msg", "");
                MAIN.post(() -> Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show());
                break;
            }
            case "native_open_external": {
                if (!isMainFrame || !isTrusted(t.url)) return;
                String u = msg.optString("url", "");
                MAIN.post(() -> {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(i);
                    } catch (Throwable ignored) {}
                });
                break;
            }
            case "native_close": {
                if (!isMainFrame || !isTrusted(t.url)) return;
                MAIN.post(FloatingApps.this::dismiss);
                break;
            }
            case "web_theme": {
                if (!isMainFrame || !WebPinchZoom.isLocal(t.url)) return;
                String themeId = msg.optString("themeId", "");
                MAIN.post(() -> {
                    Activity h = host;
                    if (h != null) new ThemeManager.WebThemeBridge(h, FloatingApps.this::onThemeChanged).onWebTheme(themeId);
                });
                break;
            }
            case "trans_open_settings": {
                if (!isMainFrame) return;
                MAIN.post(() -> {
                    Activity h = host;
                    if (h != null && !h.isFinishing() && t.web != null) {
                        com.xm486.pimet.translation.WebTranslationDialog.show(h, t.web);
                    }
                });
                break;
            }
            case "trans_main_state": {
                if (!isMainFrame) return;
                boolean isTranslated = msg.optBoolean("translated", false);
                MAIN.post(() -> {
                    if (!isTranslated) {
                        if (t.web != null) com.xm486.pimet.translation.WebTranslationManager.clearSession(t.web);
                        restoreTabSubframes(t);
                    } else {
                        t.translated = true;
                        refreshBall();
                    }
                });
                break;
            }
            case "trans_main_progress": {
                if (!isMainFrame) return;
                t.mainTransDone = Math.max(0, msg.optInt("done", 0));
                t.mainTransTotal = Math.max(0, msg.optInt("total", 0));
                t.mainTransFailed = Math.max(0, msg.optInt("failed", 0));
                t.mainTransActive = msg.optBoolean("active", false);
                t.mainTransPending = Math.max(0, msg.optInt("pending", 0));
                t.mainTransError = msg.optString("lastError", "");
                t.mainTransHasDomIframes = msg.optBoolean("hasDomIframes", false);
                MAIN.post(() -> updateAggregatedTranslationUi(t));
                break;
            }
            case "trans_main_retry": {
                if (!isMainFrame) return;
                MAIN.post(() -> retryTabSubframes(t));
                break;
            }
            case "trans_main_init_result": {
                if (!isMainFrame) return;
                final String status = msg.optString("status", "");
                final boolean autoFlag = msg.optBoolean("auto", false);
                final boolean announce = t.transAnnouncePending;
                t.transAnnouncePending = false;
                MAIN.post(() -> {
                    if (t.closed) return;
                    if (status.startsWith("ok:")) {
                        if (announce) com.xm486.pimet.translation.WebTranslationManager.toast(ctx, "正在翻译网页（" + status.substring(3) + " 段）...");
                    } else if ("subframes".equals(status)) {
                        if (announce) com.xm486.pimet.translation.WebTranslationManager.toast(ctx, "正在翻译网页子框架...");
                    } else if ("zh".equals(status)) {
                        if (t.hasNonMainFrames()) {
                            if (announce) com.xm486.pimet.translation.WebTranslationManager.toast(ctx, "正在翻译网页子框架...");
                        } else {
                            t.translatingAllFrames = false;
                            t.translated = false;
                            refreshBall();
                            if (!autoFlag) com.xm486.pimet.translation.WebTranslationManager.toast(ctx, "本页已是中文");
                        }
                    } else if ("busy".equals(status)) {
                        if (!autoFlag) com.xm486.pimet.translation.WebTranslationManager.toast(ctx, "上一次翻译还在进行中");
                    } else if ("nobody".equals(status) && !autoFlag) {
                        com.xm486.pimet.translation.WebTranslationManager.toast(ctx, "网页翻译失败：页面还没加载完成");
                    }
                });
                break;
            }
            case "trans_batch": {
                final FrameSession fs = t.frames.get(frameId);
                if (fs == null) return;
                fs.replyProxy = replyProxy;
                final String batchId = msg.optString("batchId", "");
                JSONArray arr = msg.optJSONArray("texts");
                if (arr == null) return;
                List<String> texts = new ArrayList<>(arr.length());
                for (int i = 0; i < arr.length(); i++) {
                    texts.add(arr.optString(i, ""));
                }
                com.xm486.pimet.translation.WebTranslationManager.translateBatch(
                        ctx,
                        texts,
                        new com.xm486.pimet.translation.WebTranslationManager.TranslationCallback() {
                            @Override
                            public void onSuccess(List<String> translatedTexts) {
                                if (t.closed || t.web == null || t.frames.get(frameId) != fs) return;
                                try {
                                    JSONArray resArr = new JSONArray();
                                    for (String s : translatedTexts) resArr.put(s);
                                    String payload = new JSONObject()
                                            .put("type", "trans_batch_result")
                                            .put("batchId", batchId)
                                            .put("translationsJson", resArr.toString())
                                            .put("isError", false)
                                            .put("errMsg", "")
                                            .toString();
                                    if (fs.replyProxy != null) fs.replyProxy.postMessage(payload);
                                } catch (Throwable ignored) {}
                            }

                            @Override
                            public void onError(String error) {
                                if (t.closed || t.web == null || t.frames.get(frameId) != fs) return;
                                final String errMsg = error == null || error.isEmpty() ? "翻译服务无响应" : error;
                                try {
                                    String payload = new JSONObject()
                                            .put("type", "trans_batch_result")
                                            .put("batchId", batchId)
                                            .put("translationsJson", "[]")
                                            .put("isError", true)
                                            .put("errMsg", errMsg)
                                            .toString();
                                    if (fs.replyProxy != null) fs.replyProxy.postMessage(payload);
                                } catch (Throwable ignored) {}
                                long now = System.currentTimeMillis();
                                if (!errMsg.equals(lastFrameTransErrToast) || now - lastFrameTransErrToastAt > 8000L) {
                                    lastFrameTransErrToast = errMsg;
                                    lastFrameTransErrToastAt = now;
                                    com.xm486.pimet.translation.WebTranslationManager.toast(ctx, "网页翻译失败：" + errMsg);
                                }
                            }
                        }
                );
                break;
            }
            case "trans_state": {
                FrameSession fs = t.frames.get(frameId);
                if (fs == null) return;
                fs.replyProxy = replyProxy;
                fs.transDone = Math.max(0, msg.optInt("done", 0));
                fs.transTotal = Math.max(0, msg.optInt("total", 0));
                fs.transFailed = Math.max(0, msg.optInt("failed", 0));
                fs.transActive = msg.optBoolean("active", false);
                fs.transPending = Math.max(0, msg.optInt("pending", 0));
                fs.lastError = msg.optString("lastError", "");
                fs.transStarted = true;
                updateAggregatedTranslationUi(t);
                break;
            }
            case "gm_call": {
                if (!t.isIsolated() || isLauncher(t.url) || isTrusted(t.url)) return;
                FrameSession fs = t.frames.get(frameId);
                if (fs == null) return;
                fs.replyProxy = replyProxy;
                handleIsolatedGmCall(t, fs, msg);
                break;
            }
            case "blob_chunk": {
                if (!t.isIsolated()) return;
                String nonce = msg.optString("nonce", "");
                if (nonce.isEmpty()) return;
                String b64 = msg.optString("b64", "");
                boolean last = msg.optBoolean("last", false);
                String err = msg.optString("err", "");
                onBlobChunk(nonce, b64, last, err.isEmpty() ? null : err);
                break;
            }
            default:
                break;
        }
    }

    private void handleIsolatedGmCall(Tab t, FrameSession fs, JSONObject msg) {
        String op = msg.optString("op", "");
        String scriptId = msg.optString("scriptId", "");
        UserScriptManager.Script s = null;
        if (!scriptId.isEmpty()) {
            try {
                s = scripts().get(scriptId);
            } catch (Throwable ignored) {}
            if (s == null || !s.enabled || (!fs.isMainFrame && s.noframes)) return;
            String checkUrl = (fs.url != null && !fs.url.isEmpty()) ? fs.url : t.url;
            if (!scripts().urlMatches(s, checkUrl)) return;
        } else {
            // 无 scriptId 的操作（openInTab / setClipboard / download）：至少有一个启用且匹配当前文档的脚本
            boolean any = false;
            try {
                String checkUrl = (fs.url != null && !fs.url.isEmpty()) ? fs.url : t.url;
                List<UserScriptManager.Script> matched = scripts().matching(checkUrl, null);
                if (matched != null) {
                    for (UserScriptManager.Script ms : matched) {
                        if (ms != null && ms.enabled && (fs.isMainFrame || !ms.noframes)) { any = true; break; }
                    }
                }
            } catch (Throwable ignored) {}
            if (!any) return;
        }

        switch (op) {
            case "setValue": {
                if (s == null) return;
                String key = msg.optString("key", "");
                if (key.isEmpty()) return;
                try { scripts().setValue(s.id, key, msg.optString("valueJson", "null")); } catch (Throwable ignored) {}
                break;
            }
            case "deleteValue": {
                if (s == null) return;
                String key = msg.optString("key", "");
                if (key.isEmpty()) return;
                try { scripts().deleteValue(s.id, key); } catch (Throwable ignored) {}
                break;
            }
            case "xhr": {
                if (s == null) return;
                JSONObject reqObj;
                try { reqObj = new JSONObject(msg.optString("reqJson", "{}")); } catch (Throwable e) { reqObj = new JSONObject(); }
                String callId = reqObj.optString("callId", "");
                if (callId.isEmpty()) callId = "x" + System.nanoTime();
                final String cid = callId;
                final JSONObject r = reqObj;
                final UserScriptManager.Script script = s;
                final String pageUrl = t.url;
                new Thread(() -> deliverGm(t, cid, doGmXhr(t, script, pageUrl, r)), "pimet-gm-xhr").start();
                break;
            }
            case "openInTab": {
                String rawUrl = msg.optString("url", "");
                boolean bg = msg.optBoolean("background", false);
                final String target;
                try {
                    target = t.url != null && !t.url.isEmpty() ? new java.net.URL(new java.net.URL(t.url), rawUrl).toString() : rawUrl;
                } catch (Throwable e) { return; }
                if (!(target.startsWith("http://") || target.startsWith("https://"))) return;
                MAIN.post(() -> {
                    Tab nt = newTab(false, t.container);
                    loadIn(nt, target, false);
                    if (!bg && host != null) switchTo(nt);
                    refreshTabs();
                });
                break;
            }
            case "setClipboard": {
                String text = msg.optString("text", "");
                MAIN.post(() -> {
                    try {
                        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("PiMet", text));
                    } catch (Throwable ignored) {}
                });
                break;
            }
            case "notify": {
                if (s == null) return;
                String title = msg.optString("title", "");
                String text = msg.optString("text", "");
                String head = !title.isEmpty() ? title : (s.name != null ? s.name : s.id);
                String out = text.isEmpty() ? head : head + "：" + text;
                MAIN.post(() -> Toast.makeText(ctx, out, Toast.LENGTH_LONG).show());
                break;
            }
            case "registerMenu": {
                if (s == null) return;
                String cmdId = msg.optString("cmdId", "");
                String cap = msg.optString("caption", "");
                if (cmdId.isEmpty()) return;
                synchronized (t.menus) {
                    java.util.LinkedHashMap<String, String> m = t.menus.get(s.id);
                    if (m == null) { m = new java.util.LinkedHashMap<>(); t.menus.put(s.id, m); }
                    m.put(cmdId, cap.isEmpty() ? cmdId : cap);
                }
                break;
            }
            case "unregisterMenu": {
                if (s == null) return;
                String cmdId = msg.optString("cmdId", "");
                if (cmdId.isEmpty()) return;
                synchronized (t.menus) {
                    java.util.LinkedHashMap<String, String> m = t.menus.get(s.id);
                    if (m != null) {
                        m.remove(cmdId);
                        if (m.isEmpty()) t.menus.remove(s.id);
                    }
                }
                break;
            }
            case "download": {
                String rawUrl = msg.optString("url", "");
                String name = msg.optString("name", "");
                final String target;
                try {
                    target = t.url != null && !t.url.isEmpty() && !rawUrl.startsWith("blob:") && !rawUrl.startsWith("data:")
                            ? new java.net.URL(new java.net.URL(t.url), rawUrl).toString() : rawUrl;
                } catch (Throwable e) { return; }
                String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
                String cd = safe.isEmpty() ? null : "attachment; filename=\"" + safe + "\"";
                MAIN.post(() -> {
                    String ua = t.web != null ? t.web.getSettings().getUserAgentString() : null;
                    startDownload(t, target, ua, cd, null);
                });
                break;
            }
            case "log": {
                if (s == null) return;
                addConsole(t, "log", msg.optString("text", ""), "GM:" + (s.name != null ? s.name : s.id), 0);
                break;
            }
            default:
                break;
        }
    }

    private void sendTransStartToFrame(Tab t, FrameSession fs) {
        if (t == null || fs == null || fs.isMainFrame || fs.replyProxy == null) return;
        fs.transDone = 0;
        fs.transTotal = 0;
        fs.transFailed = 0;
        fs.transActive = false;
        fs.transPending = 0;
        fs.transStarted = false;
        fs.lastError = "";
        try {
            String payload = new JSONObject()
                    .put("type", "trans_start")
                    .put("mode", t.transMode)
                    .put("auto", t.transAuto)
                    .put("batchMax", t.transBatchMax)
                    .put("charMax", t.transCharMax)
                    .put("accent", t.transAccent)
                    .toString();
            fs.replyProxy.postMessage(payload);
        } catch (Throwable ignored) {}
    }

    private void restoreTabSubframes(Tab t) {
        if (t == null) return;
        t.translatingAllFrames = false;
        t.translated = false;
        t.resetMainTransStats();
        refreshBall();
        try {
            String payload = new JSONObject().put("type", "trans_restore").toString();
            for (FrameSession fs : t.frames.values()) {
                if (!fs.isMainFrame || t.isIsolated()) {
                    fs.transDone = 0;
                    fs.transTotal = 0;
                    fs.transFailed = 0;
                    fs.transActive = false;
                    fs.transPending = 0;
                    fs.transStarted = false;
                    fs.lastError = "";
                    if (fs.replyProxy != null) {
                        try { fs.replyProxy.postMessage(payload); } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private void retryTabSubframes(Tab t) {
        if (t == null || t.closed) return;
        try {
            String payload = new JSONObject().put("type", "trans_retry").toString();
            for (FrameSession fs : t.frames.values()) {
                if (!fs.isMainFrame && fs.replyProxy != null) {
                    try { fs.replyProxy.postMessage(payload); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    private void updateAggregatedTranslationUi(Tab t) {
        if (t == null || t.closed) return;
        if (!t.translatingAllFrames && !t.translated) return;
        int done = t.mainTransDone;
        int total = t.mainTransTotal;
        int failed = t.mainTransFailed;
        boolean activeFlag = t.mainTransActive;
        int pending = t.mainTransPending;
        String lastError = t.mainTransError != null ? t.mainTransError : "";

        int subframeCount = 0;
        boolean anySubframeReported = false;
        FrameSession mainFs = null;
        for (FrameSession fs : t.frames.values()) {
            if (fs.isMainFrame) {
                mainFs = fs;
                continue;
            }
            subframeCount++;
            if (fs.transStarted) anySubframeReported = true;
            done += fs.transDone;
            total += fs.transTotal;
            failed += fs.transFailed;
            pending += fs.transPending;
            if (fs.transActive) activeFlag = true;
            if (lastError.isEmpty() && fs.lastError != null && !fs.lastError.isEmpty()) {
                lastError = fs.lastError;
            }
        }
        boolean hasSubframes = subframeCount > 0 || t.mainTransHasDomIframes;
        if (!hasSubframes) return;

        try {
            String summaryJson = new JSONObject()
                    .put("type", "trans_summary")
                    .put("done", done)
                    .put("total", total)
                    .put("failed", failed)
                    .put("active", activeFlag)
                    .put("pending", pending)
                    .put("hasSubframes", true)
                    .put("anySubframeReported", anySubframeReported)
                    .put("lastError", lastError)
                    .toString();
            if (mainFs != null && mainFs.replyProxy != null) {
                mainFs.replyProxy.postMessage(summaryJson);
            } else if (!t.isIsolated() && t.web != null) {
                t.web.evaluateJavascript("if(window.__pimet_apply_summary)window.__pimet_apply_summary(" + summaryJson + ");", null);
            }
        } catch (Throwable ignored) {}
    }

    private final class TabTranslationDelegate implements com.xm486.pimet.translation.WebTranslationManager.FrameTranslationDelegate {
        private final Tab tab;

        TabTranslationDelegate(Tab tab) {
            this.tab = tab;
        }

        @Override
        public boolean hasNonMainFrames() {
            return tab != null && tab.hasNonMainFrames();
        }

        @Override
        public boolean isTranslated() {
            return tab != null && (tab.translated || tab.translatingAllFrames);
        }

        @Override
        public boolean isIsolatedMode() {
            return tab != null && tab.isIsolated();
        }

        @Override
        public void startIsolatedTranslation(String mode, boolean auto, int batchMax, int charMax,
                                             String accent, String bg, String border, String textColor,
                                             boolean announce) {
            if (tab == null || tab.closed) return;
            tab.translatingAllFrames = true;
            tab.translated = true;
            tab.transMode = mode;
            tab.transAuto = auto;
            tab.transBatchMax = batchMax;
            tab.transCharMax = charMax;
            tab.transAccent = accent;
            tab.transAnnouncePending = announce;
            tab.resetMainTransStats();
            refreshBall();
            boolean hasSub = tab.hasNonMainFrames();
            FrameSession mainFs = null;
            for (FrameSession fs : tab.frames.values()) {
                if (fs.isMainFrame) mainFs = fs;
                else sendTransStartToFrame(tab, fs);
            }
            if (mainFs != null && mainFs.replyProxy != null) {
                try {
                    String payload = new JSONObject()
                            .put("type", "trans_start")
                            .put("mode", mode)
                            .put("auto", auto)
                            .put("batchMax", batchMax)
                            .put("charMax", charMax)
                            .put("accent", accent)
                            .put("bg", bg)
                            .put("border", border)
                            .put("text", textColor)
                            .put("hasSubframes", hasSub)
                            .toString();
                    mainFs.replyProxy.postMessage(payload);
                } catch (Throwable ignored) {}
            }
            updateAggregatedTranslationUi(tab);
        }

        @Override
        public void restoreIsolatedTranslation(boolean announce) {
            restoreTabSubframes(tab);
        }

        @Override
        public void onTranslationStarted(String mode, boolean auto, int batchMax, int charMax, String accent) {
            if (tab == null || tab.closed) return;
            tab.translatingAllFrames = true;
            tab.translated = true;
            tab.transMode = mode;
            tab.transAuto = auto;
            tab.transBatchMax = batchMax;
            tab.transCharMax = charMax;
            tab.transAccent = accent;
            refreshBall();
            for (FrameSession fs : tab.frames.values()) {
                if (!fs.isMainFrame) sendTransStartToFrame(tab, fs);
            }
            updateAggregatedTranslationUi(tab);
        }

        @Override
        public void onTranslationRestored() {
            restoreTabSubframes(tab);
        }
    }

    private static boolean injectableUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://"));
    }

    /**
     * 按 run-at 注入匹配的脚本。document-start 在 onPageStarted 注入（并在 onPageCommitVisible 再补一次，防止落在旧文档里），
     * document-end 在 onPageCommitVisible（onPageFinished 再补一次），document-idle 在 onPageFinished。
     * buildInjection 自带防重复守卫，所以补的那一次在已经执行过的文档里不会重复运行。
     * 隔离容器的用户脚本由统一 DocumentStart 闭包运行，此处跳过。
     */
    private void injectScripts(Tab t, WebView view, String url, String runAt) {
        if (t == null || t.isIsolated() || view == null || !injectableUrl(url) || isLauncher(url) || isTrusted(url)) return;
        try {
            List<UserScriptManager.Script> list = scripts().matching(url, runAt);
            if (list == null || list.isEmpty()) return;
            for (UserScriptManager.Script s : list) {
                if (s == null || s.id == null) continue;
                String js = scripts().buildInjection(s, t.id);
                if (js == null || js.isEmpty()) continue;
                t.injectedIds.add(s.id);
                view.evaluateJavascript(js, null);
            }
        } catch (Throwable e) {
            Log.w(TAG, "inject user scripts failed (" + runAt + ")", e);
            addConsole(t, "error", "[PiMet] 用户脚本注入失败: " + e.getMessage(), "pimet", 0);
        }
    }

    private int matchingCount(String url) {
        if (!injectableUrl(url) || isLauncher(url)) return 0;
        try {
            List<UserScriptManager.Script> l = scripts().matching(url, null);
            return l == null ? 0 : l.size();
        } catch (Throwable e) {
            return 0;
        }
    }

    private java.util.Set<String> matchingIds(String url) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        if (!injectableUrl(url) || isLauncher(url)) return ids;
        try {
            List<UserScriptManager.Script> l = scripts().matching(url, null);
            if (l != null) for (UserScriptManager.Script s : l) if (s != null && s.id != null) ids.add(s.id);
        } catch (Throwable ignored) {}
        return ids;
    }

    // ---- 控制台（每页最多 300 条，onConsoleMessage + GM_log）

    private static final int MAX_CONSOLE = 300;

    private static void addConsole(Tab t, String level, String text, String source, int line) {
        if (t == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("time", System.currentTimeMillis());
            o.put("level", level);
            o.put("text", text == null ? "" : (text.length() > 4000 ? text.substring(0, 4000) + "…" : text));
            if (source != null && !source.isEmpty()) o.put("source", source);
            if (line > 0) o.put("line", line);
            synchronized (t.console) {
                t.console.addLast(o);
                while (t.console.size() > MAX_CONSOLE) t.console.removeFirst();
            }
        } catch (Throwable ignored) {}
    }

    /** 给 BrowserAgent：该标签当前页面最近 max 条控制台日志（console.* 和 JS 错误），旧的在前 */
    JSONArray consoleLogs(Tab t, int max) {
        JSONArray a = new JSONArray();
        if (t == null) return a;
        if (max <= 0) max = 100;
        synchronized (t.console) {
            int skip = Math.max(0, t.console.size() - max);
            int i = 0;
            for (JSONObject o : t.console) {
                if (i++ < skip) continue;
                a.put(o);
            }
        }
        return a;
    }

    /** 给 BrowserAgent：清空该标签的控制台日志（console 的 clear 参数） */
    void clearConsoleLogs(Tab t) {
        if (t == null) return;
        synchronized (t.console) {
            t.console.clear();
        }
    }

    // ---- GM 菜单命令（每个标签当前页面注册的）

    /** 给 BrowserAgent：[{script, scriptName, cmd, caption}] */
    JSONArray menuCommands(Tab t) {
        JSONArray a = new JSONArray();
        if (t == null) return a;
        synchronized (t.menus) {
            for (java.util.Map.Entry<String, java.util.LinkedHashMap<String, String>> e : t.menus.entrySet()) {
                String name = e.getKey();
                try {
                    UserScriptManager.Script s = scripts().get(e.getKey());
                    if (s != null && s.name != null) name = s.name;
                } catch (Throwable ignored) {}
                for (java.util.Map.Entry<String, String> c : e.getValue().entrySet()) {
                    try {
                        JSONObject o = new JSONObject();
                        o.put("script", e.getKey());
                        o.put("scriptName", name);
                        o.put("cmd", c.getKey());
                        o.put("caption", c.getValue());
                        a.put(o);
                    } catch (Throwable ignored) {}
                }
            }
        }
        return a;
    }

    /** 给 BrowserAgent / 面板：执行一个 GM 菜单命令（主线程） */
    void runMenuCommand(Tab t, String scriptId, String cmdId) {
        if (t == null || t.closed || t.web == null || scriptId == null || cmdId == null) return;
        if (!t.isIsolated()) {
            t.web.evaluateJavascript("window.__pimetGMmenu&&window.__pimetGMmenu(" + JSONObject.quote(scriptId) + "," + JSONObject.quote(cmdId) + ")", null);
        }
        try {
            String frameMsg = new JSONObject()
                    .put("type", "gm_menu")
                    .put("scriptId", scriptId)
                    .put("cmdId", cmdId)
                    .toString();
            for (FrameSession fs : t.frames.values()) {
                if ((t.isIsolated() || !fs.isMainFrame) && fs.replyProxy != null) {
                    try { fs.replyProxy.postMessage(frameMsg); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    // ---- window.PiMetGM：GM 垫片里需要原生的部分

    private final class GMBridge {
        private final Tab tab;

        GMBridge(Tab tab) {
            this.tab = tab;
        }

        /** 子框架执行用户脚本前的同步鉴权：校验 frameSecret 与脚本状态后登记到 subframeInjectedIds */
        @JavascriptInterface
        public boolean authorizeSubframe(String secret, String id) {
            if (secret == null || !secret.equals(tab.frameSecret) || id == null) return false;
            if (isLauncher(tab.url) || isTrusted(tab.url)) return false;
            try {
                UserScriptManager.Script s = scripts().get(id);
                if (s == null || !s.enabled || s.noframes) return false;
                tab.subframeInjectedIds.add(id);
                return true;
            } catch (Throwable e) {
                return false;
            }
        }

        /** 为已鉴权的子框架同步拉取最新 GM 存储值快照 */
        @JavascriptInterface
        public String getValuesJson(String id) {
            if (ok(id) == null) return null;
            try {
                return scripts().getValues(id).toString();
            } catch (Throwable e) {
                return null;
            }
        }

        /** 脚本必须存在、启用，并且已注入到这个标签的当前页面（主框架或已鉴权子框架） */
        private UserScriptManager.Script ok(String id) {
            if (id == null || (!tab.injectedIds.contains(id) && !tab.subframeInjectedIds.contains(id))) return null;
            try {
                UserScriptManager.Script s = scripts().get(id);
                return s != null && s.enabled ? s : null;
            } catch (Throwable e) {
                return null;
            }
        }

        /** 没有脚本 id 的方法：要求当前页面至少注入了一个仍然启用的脚本 */
        private boolean anyOk() {
            for (String id : tab.injectedIds) if (ok(id) != null) return true;
            for (String id : tab.subframeInjectedIds) if (ok(id) != null) return true;
            return false;
        }

        @JavascriptInterface
        public void setValue(String id, String key, String json) {
            if (ok(id) == null || key == null) return;
            try {
                scripts().setValue(id, key, json);
            } catch (Throwable e) {
                Log.w(TAG, "GM setValue failed", e);
            }
        }

        @JavascriptInterface
        public void deleteValue(String id, String key) {
            if (ok(id) == null || key == null) return;
            try {
                scripts().deleteValue(id, key);
            } catch (Throwable e) {
                Log.w(TAG, "GM deleteValue failed", e);
            }
        }

        /** 异步请求；返回 callId（取 req.callId，没有就生成），结果回调 window.__pimetGMcb(callId, resultJson字符串) */
        @JavascriptInterface
        public String xhr(String id, String reqJson) {
            JSONObject req;
            try {
                req = new JSONObject(reqJson == null ? "{}" : reqJson);
            } catch (Throwable e) {
                req = new JSONObject();
            }
            String callId = req.optString("callId", "");
            if (callId.isEmpty()) callId = "x" + System.nanoTime();
            final String cid = callId;
            final JSONObject r = req;
            UserScriptManager.Script s = ok(id);
            if (s == null) {
                JSONObject res = new JSONObject();
                try {
                    res.put("status", 0);
                    res.put("readyState", 4);
                    res.put("error", "脚本未启用或未在本页注入");
                } catch (Throwable ignored) {}
                deliverGm(tab, cid, res);
                return cid;
            }
            final String pageUrl = tab.url;
            new Thread(() -> deliverGm(tab, cid, doGmXhr(tab, s, pageUrl, r)), "pimet-gm-xhr").start();
            return cid;
        }

        @JavascriptInterface
        public void openInTab(String url, boolean background) {
            if (!anyOk() || url == null) return;
            final String target;
            try {
                target = tab.url != null && !tab.url.isEmpty() ? new java.net.URL(new java.net.URL(tab.url), url).toString() : url;
            } catch (Throwable e) {
                return;
            }
            if (!(target.startsWith("http://") || target.startsWith("https://"))) return;
            MAIN.post(() -> {
                Tab nt = newTab(false, tab.container);
                loadIn(nt, target, false);
                if (!background && host != null) switchTo(nt);
                refreshTabs();
            });
        }

        @JavascriptInterface
        public void setClipboard(String text) {
            if (!anyOk()) return;
            MAIN.post(() -> {
                try {
                    ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("PiMet", text == null ? "" : text));
                } catch (Throwable ignored) {}
            });
        }

        @JavascriptInterface
        public void notify(String id, String title, String text) {
            UserScriptManager.Script s = ok(id);
            if (s == null) return;
            String head = title != null && !title.isEmpty() ? title : (s.name != null ? s.name : id);
            String msg = text == null || text.isEmpty() ? head : head + "：" + text;
            MAIN.post(() -> Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show());
        }

        @JavascriptInterface
        public void registerMenu(String id, String cmdId, String caption) {
            if (ok(id) == null || cmdId == null) return;
            synchronized (tab.menus) {
                java.util.LinkedHashMap<String, String> m = tab.menus.get(id);
                if (m == null) {
                    m = new java.util.LinkedHashMap<>();
                    tab.menus.put(id, m);
                }
                m.put(cmdId, caption == null || caption.isEmpty() ? cmdId : caption);
            }
        }

        @JavascriptInterface
        public void unregisterMenu(String id, String cmdId) {
            if (ok(id) == null || cmdId == null) return;
            synchronized (tab.menus) {
                java.util.LinkedHashMap<String, String> m = tab.menus.get(id);
                if (m != null) {
                    m.remove(cmdId);
                    if (m.isEmpty()) tab.menus.remove(id);
                }
            }
        }

        @JavascriptInterface
        public void download(String url, String name) {
            if (!anyOk() || url == null) return;
            final String target;
            try {
                target = tab.url != null && !tab.url.isEmpty() && !url.startsWith("blob:") && !url.startsWith("data:")
                        ? new java.net.URL(new java.net.URL(tab.url), url).toString() : url;
            } catch (Throwable e) {
                return;
            }
            String safe = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|]", "_");
            String cd = safe.isEmpty() ? null : "attachment; filename=\"" + safe + "\"";
            MAIN.post(() -> {
                String ua = tab.web != null ? tab.web.getSettings().getUserAgentString() : null;
                startDownload(tab, target, ua, cd, null);
            });
        }

        @JavascriptInterface
        public void log(String id, String text) {
            UserScriptManager.Script s = ok(id);
            if (s == null) return;
            addConsole(tab, "log", text, "GM:" + (s.name != null ? s.name : id), 0);
        }
    }

    private void deliverGm(Tab t, String callId, JSONObject res) {
        if (t == null || t.closed) return;
        final String resStr = res.toString();
        final boolean isolated = t.isIsolated();
        final String js = isolated ? null : ("window.__pimetGMcb&&window.__pimetGMcb(" + JSONObject.quote(callId) + "," + JSONObject.quote(resStr) + ")");
        String framePayload = null;
        try {
            framePayload = new JSONObject()
                    .put("type", "gm_cb")
                    .put("callId", callId)
                    .put("resultJson", resStr)
                    .toString();
        } catch (Throwable ignored) {}
        final String frameMsg = framePayload;
        MAIN.post(() -> {
            if (t.closed) return;
            if (!isolated && t.web != null && js != null) t.web.evaluateJavascript(js, null);
            if (frameMsg != null) {
                for (FrameSession fs : t.frames.values()) {
                    if ((isolated || !fs.isMainFrame) && fs.replyProxy != null) {
                        try { fs.replyProxy.postMessage(frameMsg); } catch (Throwable ignored) {}
                    }
                }
            }
        });
    }

    /** @connect 检查：未声明则允许（并记一条提示）；声明了则 host 必须命中（* / self / 域名及其子域 / IP） */
    private static boolean connectAllowed(Tab t, UserScriptManager.Script s, String host, String pageUrl) {
        if (isLocalHost(host)) {
            // 本机/局域网（含 127.0.0.1:30143 等 PiMet 自己的端口）：必须在 @connect 里显式写出这个主机，* 和未声明都不算
            if (s.connects != null) {
                for (String c : s.connects) {
                    if (c != null && c.trim().equalsIgnoreCase(host)) return true;
                }
            }
            return false;
        }
        if (s.connects == null || s.connects.isEmpty()) {
            addConsole(t, "warn", "[PiMet] 脚本 " + s.name + " 未声明 @connect，已允许请求 " + host, "pimet", 0);
            return true;
        }
        String pageHost = "";
        try {
            pageHost = Uri.parse(pageUrl).getHost();
        } catch (Throwable ignored) {}
        if (pageHost == null) pageHost = "";
        host = host.toLowerCase(java.util.Locale.ROOT);
        for (String c : s.connects) {
            if (c == null) continue;
            c = c.trim().toLowerCase(java.util.Locale.ROOT);
            if (c.isEmpty()) continue;
            if (c.equals("*")) return true;
            if (c.equals("self") && host.equals(pageHost.toLowerCase(java.util.Locale.ROOT))) return true;
            if (c.startsWith("*.")) c = c.substring(2);
            if (host.equals(c) || host.endsWith("." + c)) return true;
        }
        return false;
    }

    /** localhost / 环回 / 内网 / 链路本地地址（只看字面，不做 DNS） */
    static boolean isLocalHost(String host) {
        if (host == null) return true;
        String h = host.trim().toLowerCase(java.util.Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        if (h.isEmpty() || h.equals("localhost") || h.endsWith(".localhost") || h.equals("0.0.0.0") || h.equals("::") || h.equals("::1")) return true;
        if (h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.") || h.startsWith("169.254.")) return true;
        if (h.contains(":") && (h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd"))) return true;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^172\\.(\\d+)\\.").matcher(h);
        if (m.find()) {
            int b = Integer.parseInt(m.group(1));
            if (b >= 16 && b <= 31) return true;
        }
        return false;
    }

    private static final int GM_MAX_BODY = 30 * 1024 * 1024;

    /** GM_xmlhttpRequest 的原生实现（后台线程）：跨域、headers/method/data、responseType text|json|arraybuffer|blob（后两者 base64） */
    private JSONObject doGmXhr(Tab t, UserScriptManager.Script s, String pageUrl, JSONObject req) {
        JSONObject res = new JSONObject();
        java.net.HttpURLConnection c = null;
        try {
            res.put("readyState", 4);
            res.put("status", 0);
            String raw = req.optString("url", "");
            java.net.URL url = pageUrl != null && pageUrl.startsWith("http") ? new java.net.URL(new java.net.URL(pageUrl), raw) : new java.net.URL(raw);
            String proto = url.getProtocol();
            if (!"http".equals(proto) && !"https".equals(proto)) throw new IllegalArgumentException("只支持 http/https：" + raw);
            if (!connectAllowed(t, s, url.getHost(), pageUrl)) {
                addConsole(t, "error", "[PiMet] 脚本 " + s.name + " 的请求被 @connect 拦截：" + url.getHost(), "pimet", 0);
                res.put("error", "@connect 不允许访问 " + url.getHost());
                return res;
            }
            String method = req.optString("method", "GET").trim().toUpperCase(java.util.Locale.ROOT);
            if (method.isEmpty()) method = "GET";
            int timeout = req.optInt("timeout", 0);
            if (timeout <= 0) timeout = 30000;
            JSONObject headers = req.optJSONObject("headers");
            boolean anonymous = req.optBoolean("anonymous", false);
            byte[] body = null;
            if (req.has("dataBase64") && !req.isNull("dataBase64")) body = android.util.Base64.decode(req.optString("dataBase64", ""), android.util.Base64.DEFAULT);
            else if (req.has("data") && !req.isNull("data")) body = String.valueOf(req.opt("data")).getBytes(StandardCharsets.UTF_8);
            String rt = req.optString("responseType", "text").toLowerCase(java.util.Locale.ROOT);

            for (int hop = 0; hop < 6; hop++) {
                c = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(Math.min(timeout, 30000));
                c.setReadTimeout(timeout);
                setMethod(c, method);
                boolean hasCookie = false, hasUa = false, hasAl = false;
                if (headers != null) {
                    java.util.Iterator<String> it = headers.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        String v = headers.optString(k, "");
                        if (k.equalsIgnoreCase("cookie")) hasCookie = true;
                        if (k.equalsIgnoreCase("user-agent")) hasUa = true;
                        if (k.equalsIgnoreCase("accept-language")) hasAl = true;
                        c.setRequestProperty(k, v);
                    }
                }
                if (!anonymous && !hasCookie) {
                    String ck = tabCookies(t).getCookie(url.toString());
                    if (ck != null && !ck.isEmpty()) c.setRequestProperty("Cookie", ck);
                }
                if (!hasUa) {
                    if (t.isIsolated()) {
                        String overrideUa = (t.siteUaMode != null && !"desktop".equalsIgnoreCase(t.siteUaMode) && !"mobile".equalsIgnoreCase(t.siteUaMode))
                                ? t.siteUaMode : customUa();
                        c.setRequestProperty("User-Agent", ContainerEnv.buildEffectiveUa(ctx, t.envProfile, t.desktop, overrideUa));
                    } else if (sDefaultUa != null) {
                        c.setRequestProperty("User-Agent", sDefaultUa);
                    }
                }
                if (!anonymous && !hasAl && t.isIsolated() && t.envProfile != null && !t.envProfile.acceptLanguage.isEmpty()) {
                    c.setRequestProperty("Accept-Language", t.envProfile.acceptLanguage);
                }
                if (body != null && !method.equals("GET") && !method.equals("HEAD")) {
                    c.setDoOutput(true);
                    c.setFixedLengthStreamingMode(body.length);
                    try (java.io.OutputStream os = c.getOutputStream()) {
                        os.write(body);
                    }
                }
                int code = c.getResponseCode();
                if (!anonymous) {
                    java.util.List<String> sc = c.getHeaderFields().get("Set-Cookie");
                    if (sc != null) for (String v : sc) tabCookies(t).setCookie(url.toString(), v);
                }
                String loc = c.getHeaderField("Location");
                if (code >= 300 && code < 400 && code != 304 && loc != null && !loc.isEmpty()) {
                    url = new java.net.URL(url, loc);
                    c.disconnect();
                    c = null;
                    String rp = url.getProtocol();
                    if (!"http".equals(rp) && !"https".equals(rp)) throw new IllegalArgumentException("重定向到不支持的协议：" + url);
                    if (!connectAllowed(t, s, url.getHost(), pageUrl)) {
                        addConsole(t, "error", "[PiMet] 脚本 " + s.name + " 的请求重定向被 @connect 拦截：" + url.getHost(), "pimet", 0);
                        res.put("error", "@connect 不允许访问 " + url.getHost());
                        return res;
                    }
                    if (code == 303 || ((code == 301 || code == 302) && method.equals("POST"))) {
                        method = "GET";
                        body = null;
                    }
                    continue;
                }
                break;
            }
            if (c == null) throw new java.io.IOException("重定向次数过多");
            int code = c.getResponseCode();
            res.put("status", code);
            String msg = c.getResponseMessage();
            res.put("statusText", msg == null ? "" : msg);
            res.put("finalUrl", c.getURL().toString());
            StringBuilder hs = new StringBuilder();
            for (java.util.Map.Entry<String, java.util.List<String>> e : c.getHeaderFields().entrySet()) {
                if (e.getKey() == null) continue;
                for (String v : e.getValue()) hs.append(e.getKey().toLowerCase(java.util.Locale.ROOT)).append(": ").append(v).append("\r\n");
            }
            res.put("responseHeaders", hs.toString());
            byte[] data = new byte[0];
            if (!method.equals("HEAD")) {
                InputStream in = null;
                try {
                    in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                } catch (Throwable ignored) {}
                if (in != null) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    try {
                        while ((n = in.read(buf)) != -1) {
                            if (bo.size() + n > GM_MAX_BODY) throw new java.io.IOException("响应超过 30MB");
                            bo.write(buf, 0, n);
                        }
                    } finally {
                        try { in.close(); } catch (Throwable ignored) {}
                    }
                    data = bo.toByteArray();
                }
            }
            if (rt.equals("arraybuffer") || rt.equals("blob")) {
                res.put("responseBase64", android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP));
                String ct = c.getContentType();
                if (ct != null) res.put("contentType", ct);
            } else {
                java.nio.charset.Charset cs = StandardCharsets.UTF_8;
                String ct = c.getContentType();
                if (ct != null) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)charset=\\s*\"?([\\w.:-]+)").matcher(ct);
                    if (m.find()) {
                        try { cs = java.nio.charset.Charset.forName(m.group(1)); } catch (Throwable ignored) {}
                    }
                }
                res.put("responseText", new String(data, cs));
            }
        } catch (java.net.SocketTimeoutException e) {
            try {
                res.put("timeout", true);
                res.put("error", "timeout");
            } catch (Throwable ignored) {}
        } catch (Throwable e) {
            try {
                res.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            } catch (Throwable ignored) {}
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) {}
        }
        return res;
    }

    /** HttpURLConnection 不认 PATCH 等方法：用反射写 method 字段 */
    private static void setMethod(java.net.HttpURLConnection c, String method) throws Exception {
        try {
            c.setRequestMethod(method);
        } catch (java.net.ProtocolException pe) {
            java.lang.reflect.Field f = java.net.HttpURLConnection.class.getDeclaredField("method");
            f.setAccessible(true);
            f.set(c, method);
        }
    }

    // ---- .user.js 安装拦截

    private String lastInstallUrl;
    private long lastInstallAt;

    static boolean isUserJsUrl(String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return false;
        try {
            String p = Uri.parse(url).getPath();
            return p != null && p.toLowerCase(java.util.Locale.ROOT).endsWith(".user.js");
        } catch (Throwable e) {
            return false;
        }
    }

    /** 打开 .user.js：下载 → 解析头 → 弹「安装脚本」确认框。没有界面时返回 false（照常加载） */
    private boolean promptInstallFromUrl(String url) {
        if (host == null || host.isFinishing() || url == null) return false;
        long now = System.currentTimeMillis();
        if (url.equals(lastInstallUrl) && now - lastInstallAt < 3000) return true;
        lastInstallUrl = url;
        lastInstallAt = now;
        Toast.makeText(ctx, "正在读取用户脚本…", Toast.LENGTH_SHORT).show();
        final Tab fetchTab = active;
        final android.webkit.CookieManager ck = tabCookies(fetchTab);
        new Thread(() -> {
            try {
                String code = httpGetText(fetchTab, url, ck);
                UserScriptManager.Script s = UserScriptManager.parse(code);
                MAIN.post(() -> showInstallDialog(code, s, url));
            } catch (Throwable e) {
                MAIN.post(() -> Toast.makeText(ctx, "读取脚本失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "pimet-us-fetch").start();
        return true;
    }

    private static String joinList(List<String> l) {
        if (l == null || l.isEmpty()) return "（无）";
        return TextUtils.join("\n", l);
    }

    private void showInstallDialog(String code, UserScriptManager.Script s, String url) {
        if (host == null || host.isFinishing()) return;
        if (s == null || code == null || !code.contains("==UserScript==")) {
            Toast.makeText(ctx, "不是有效的用户脚本（缺少 ==UserScript== 头）", Toast.LENGTH_LONG).show();
            return;
        }
        UserScriptManager.Script old = null;
        try {
            for (UserScriptManager.Script x : scripts().list()) {
                if (x != null && TextUtils.equals(x.name, s.name) && TextUtils.equals(x.namespace, s.namespace)) old = x;
            }
        } catch (Throwable ignored) {}
        StringBuilder sb = new StringBuilder();
        sb.append("版本：").append(s.version == null || s.version.isEmpty() ? "?" : s.version);
        if (old != null) sb.append("（已安装 ").append(old.version == null ? "?" : old.version).append("，将覆盖）");
        if (s.author != null && !s.author.isEmpty()) sb.append("\n作者：").append(s.author);
        if (s.description != null && !s.description.isEmpty()) sb.append("\n\n").append(s.description);
        List<String> rules = new ArrayList<>();
        if (s.matches != null) rules.addAll(s.matches);
        if (s.includes != null) rules.addAll(s.includes);
        sb.append("\n\n运行于：\n").append(joinList(rules));
        if (s.excludes != null && !s.excludes.isEmpty()) sb.append("\n\n排除：\n").append(joinList(s.excludes));
        sb.append("\n\n权限 @grant：\n").append(joinList(s.grants));
        if (s.connects != null && !s.connects.isEmpty()) sb.append("\n\n可访问 @connect：\n").append(joinList(s.connects));
        if (s.requires != null && !s.requires.isEmpty()) sb.append("\n\n依赖 @require：\n").append(joinList(s.requires));
        sb.append("\n\n来源：").append(url == null || url.isEmpty() ? "本地" : url);
        new AlertDialog.Builder(host)
                .setTitle((old != null ? "更新脚本：" : "安装脚本：") + (s.name == null ? "未命名" : s.name))
                .setMessage(sb.toString())
                .setPositiveButton(old != null ? "更新" : "安装", (d, w) -> installCode(code, url))
                .setNeutralButton("查看源码", (d, w) -> showCodeViewer(s.name, code, () -> showInstallDialog(code, s, url)))
                .setNegativeButton("取消", null)
                .show();
    }

    private void installCode(String code, String url) {
        new Thread(() -> {
            try {
                UserScriptManager.Script r = scripts().install(code, url);
                MAIN.post(() -> {
                    Toast.makeText(ctx, "已安装：" + (r != null && r.name != null ? r.name : "脚本") + "，刷新页面后生效", Toast.LENGTH_LONG).show();
                    if (active != null && active.web != null && !isLauncher(active.url) && !isUserJsUrl(active.url)) active.web.reload();
                });
            } catch (Throwable e) {
                MAIN.post(() -> Toast.makeText(ctx, "安装失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "pimet-us-install").start();
    }

    /** 下载文本（带浏览器 Cookie 与容器代理，跟随跨协议重定向，最大 5MB），后台线程调用 */
    private String httpGetText(Tab t, String u, android.webkit.CookieManager cookies) throws Exception {
        java.net.URL url = new java.net.URL(u);
        for (int hop = 0; hop < 6; hop++) {
            java.net.HttpURLConnection c = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
            try {
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                String ck = cookies != null ? cookies.getCookie(url.toString()) : null;
                if (ck != null && !ck.isEmpty()) c.setRequestProperty("Cookie", ck);
                if (sDefaultUa != null) c.setRequestProperty("User-Agent", sDefaultUa);
                int code = c.getResponseCode();
                String loc = c.getHeaderField("Location");
                if (code >= 300 && code < 400 && loc != null) {
                    url = new java.net.URL(url, loc);
                    continue;
                }
                if (code >= 400) throw new java.io.IOException("HTTP " + code);
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        if (bo.size() + n > 5 * 1024 * 1024) throw new java.io.IOException("脚本超过 5MB");
                        bo.write(buf, 0, n);
                    }
                    return bo.toString("UTF-8");
                }
            } finally {
                c.disconnect();
            }
        }
        throw new java.io.IOException("重定向次数过多");
    }

    // ---- 🧩 脚本管理面板

    private AlertDialog scriptsPanel;

    private TextView panelText(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(host);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
        return t;
    }

    private TextView panelButton(String s, ThemeManager.ThemePalette p, View.OnClickListener l) {
        TextView b = new TextView(host);
        b.setText(s);
        b.setTextSize(12f);
        b.setSingleLine(true);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(p.accent);
        b.setBackground(ThemeManager.createSecondaryButtonDrawable(host, p, 8f));
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setOnClickListener(l);
        return b;
    }

    private TextView panelSection(String s, ThemeManager.ThemePalette p) {
        TextView t = panelText(s, 12f, p.textMuted, true);
        t.setPadding(0, dp(16), 0, dp(6));
        return t;
    }

    private View panelRow(String title, String sub, ThemeManager.ThemePalette p, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackground(ThemeManager.createSecondaryButtonDrawable(host, p, 8f));
        row.addView(panelText(title, 14f, p.text, false));
        if (sub != null && !sub.isEmpty()) row.addView(panelText(sub, 11f, p.textDim, false));
        row.setOnClickListener(l);
        return row;
    }

    private static LinearLayout.LayoutParams rowLp(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    /** 🧩：底部弹出「脚本管理」面板（脚本列表、本页菜单命令、扩展按钮、Pi 做的页面） */
    private void showScriptsPanel() {
        if (host == null || host.isFinishing()) return;
        hideIme();
        if (scriptsPanel != null && scriptsPanel.isShowing()) scriptsPanel.dismiss();
        final Tab tab = active;
        final String url = currentUrl();
        ThemeManager.ThemePalette p = ThemeManager.getEffectivePalette(host);

        LinearLayout layout = new LinearLayout(host);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(18), dp(14), dp(18), dp(18));

        LinearLayout head = new LinearLayout(host);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = panelText("🧩 脚本管理", 16f, p.text, true);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView reloadBtn = panelButton("↻ 刷新本页", p, v -> {
            if (scriptsPanel != null) scriptsPanel.dismiss();
            reload();
        });
        reloadBtn.setVisibility(View.GONE);
        head.addView(reloadBtn);
        layout.addView(head);

        final java.util.Set<String> hitIds = matchingIds(url);
        TextView count = panelText(injectableUrl(url) && !isLauncher(url)
                ? "当前页面匹配 " + hitIds.size() + " 个脚本" : "当前页面不运行用户脚本", 12f, p.accent, false);
        count.setPadding(0, dp(4), 0, dp(10));
        layout.addView(count);

        LinearLayout actions = new LinearLayout(host);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(panelButton("＋ 新建", p, v -> showScriptEditor(null, newScriptTemplate(url))));
        TextView fromUrl = panelButton("从网址安装", p, v -> askInstallUrl());
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = dp(8);
        actions.addView(fromUrl, alp);
        LinearLayout.LayoutParams alp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp2.leftMargin = dp(8);
        actions.addView(panelButton("检查更新", p, v -> checkUpdates(null)), alp2);
        layout.addView(actions);

        // 本页菜单命令
        JSONArray menus = menuCommands(tab);
        if (menus.length() > 0) {
            layout.addView(panelSection("本页菜单命令", p));
            for (int i = 0; i < menus.length(); i++) {
                JSONObject m = menus.optJSONObject(i);
                if (m == null) continue;
                final String sid = m.optString("script"), cid = m.optString("cmd");
                layout.addView(panelRow("▶ " + m.optString("caption"), m.optString("scriptName"), p, v -> {
                    if (scriptsPanel != null) scriptsPanel.dismiss();
                    runMenuCommand(tab, sid, cid);
                }), rowLp(dp(6)));
            }
        }

        // 用户脚本
        layout.addView(panelSection("用户脚本", p));
        List<UserScriptManager.Script> all = new ArrayList<>();
        try {
            List<UserScriptManager.Script> l = scripts().list();
            if (l != null) all.addAll(l);
        } catch (Throwable e) {
            layout.addView(panelText("读取脚本失败：" + e.getMessage(), 12f, p.textDim, false));
        }
        if (all.isEmpty()) {
            layout.addView(panelText("还没有脚本：点「＋ 新建」，打开 .user.js 链接，或跟 Pi 说「写个脚本…」", 12f, p.textDim, false));
        }
        for (UserScriptManager.Script s : all) {
            if (s == null || s.id == null) continue;
            LinearLayout row = new LinearLayout(host);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(8), dp(6), dp(8));
            row.setBackground(ThemeManager.createSecondaryButtonDrawable(host, p, 8f));
            LinearLayout info = new LinearLayout(host);
            info.setOrientation(LinearLayout.VERTICAL);
            info.addView(panelText(s.name == null || s.name.isEmpty() ? s.id : s.name, 14f, s.enabled ? p.text : p.textDim, false));
            String sub = "v" + (s.version == null || s.version.isEmpty() ? "?" : s.version);
            if (hitIds.contains(s.id)) sub += "  ·  ● 本页生效";
            TextView subTv = panelText(sub, 11f, hitIds.contains(s.id) ? p.accent : p.textDim, false);
            info.addView(subTv);
            row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            android.widget.Switch sw = new android.widget.Switch(host);
            sw.setChecked(s.enabled);
            sw.setOnCheckedChangeListener((b, on) -> {
                final String sid = s.id;
                new Thread(() -> {
                    boolean ok;
                    try {
                        ok = scripts().setEnabled(sid, on);
                    } catch (Throwable e) {
                        ok = false;
                    }
                    final boolean done = ok;
                    MAIN.post(() -> {
                        if (!done) {
                            Toast.makeText(ctx, "切换失败", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        Toast.makeText(ctx, (on ? "已启用" : "已停用") + "，刷新页面后生效", Toast.LENGTH_SHORT).show();
                        reloadBtn.setVisibility(View.VISIBLE);
                    });
                }, "pimet-us-toggle").start();
            });
            row.addView(sw);
            row.setOnClickListener(v -> showScriptDetail(s.id));
            layout.addView(row, rowLp(dp(6)));
        }

        // 扩展按钮（config.json）
        layout.addView(panelSection("扩展按钮", p));
        JSONArray btns = config.optJSONArray("buttons");
        int n = 0;
        if (btns != null) {
            for (int i = 0; i < btns.length(); i++) {
                final JSONObject b = btns.optJSONObject(i);
                if (b == null) continue;
                layout.addView(panelRow(buttonLabel(b, true), null, p, v -> {
                    if (scriptsPanel != null) scriptsPanel.dismiss();
                    runCustomButton(b);
                }), rowLp(dp(6)));
                n++;
            }
        }
        if (n == 0) layout.addView(panelText("还没有扩展按钮：跟 Pi 说「给 Pi 浏览器加一个…」", 12f, p.textDim, false));
        layout.addView(panelRow("🏠 Pi 做的页面", null, p, v -> {
            if (scriptsPanel != null) scriptsPanel.dismiss();
            goHome();
        }), rowLp(dp(6)));

        ScrollView sv = new ScrollView(host);
        sv.addView(layout);
        FrameLayout wrap = new FrameLayout(host);
        wrap.setBackground(ThemeManager.createCardDrawable(host, p, 14f));
        wrap.addView(sv);

        AlertDialog d = new AlertDialog.Builder(host).setView(wrap).create();
        scriptsPanel = d;
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setGravity(Gravity.BOTTOM);
        }
        d.show();
        if (w != null) w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void refreshScriptsPanelIfOpen() {
        if (scriptsPanel != null && scriptsPanel.isShowing()) showScriptsPanel();
    }

    private static String newScriptTemplate(String url) {
        String match = "*://*/*";
        try {
            String h = Uri.parse(url).getHost();
            if (h != null && !h.isEmpty() && (url.startsWith("http://") || url.startsWith("https://"))) match = "*://" + h + "/*";
        } catch (Throwable ignored) {}
        return "// ==UserScript==\n"
                + "// @name         新脚本\n"
                + "// @namespace    pimet\n"
                + "// @version      0.1\n"
                + "// @description  \n"
                + "// @match        " + match + "\n"
                + "// @grant        none\n"
                + "// @run-at       document-idle\n"
                + "// ==/UserScript==\n"
                + "\n"
                + "(function () {\n"
                + "    'use strict';\n"
                + "    \n"
                + "})();\n";
    }

    private void askInstallUrl() {
        if (host == null) return;
        ThemeManager.ThemePalette p = ThemeManager.getEffectivePalette(host);
        EditText et = new EditText(host);
        et.setSingleLine(true);
        et.setHint("https://…/xxx.user.js");
        et.setTextColor(p.text);
        et.setHintTextColor(p.textDim);
        FrameLayout f = new FrameLayout(host);
        f.setPadding(dp(20), dp(8), dp(20), 0);
        f.addView(et);
        new AlertDialog.Builder(host).setTitle("从网址安装脚本").setView(f)
                .setPositiveButton("读取", (d, w) -> {
                    String u = et.getText().toString().trim();
                    if (!(u.startsWith("http://") || u.startsWith("https://"))) {
                        Toast.makeText(ctx, "请输入 http(s) 网址", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    lastInstallUrl = null;
                    promptInstallFromUrl(u);
                })
                .setNegativeButton("取消", null).show();
    }

    /** 检查更新：id == null 表示全部 */
    private void checkUpdates(String onlyId) {
        Toast.makeText(ctx, "正在检查更新…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            List<UserScriptManager.Script> todo = new ArrayList<>();
            try {
                if (onlyId != null) {
                    UserScriptManager.Script s = scripts().get(onlyId);
                    if (s != null) todo.add(s);
                } else {
                    todo.addAll(scripts().list());
                }
            } catch (Throwable ignored) {}
            final List<String> names = new ArrayList<>();
            final List<String> codes = new ArrayList<>();
            final List<String> urls = new ArrayList<>();
            int failed = 0;
            for (UserScriptManager.Script s : todo) {
                try {
                    String code = scripts().checkUpdate(s.id);
                    if (code != null) {
                        names.add((s.name == null ? s.id : s.name) + "（v" + (s.version == null ? "?" : s.version) + " → v" + verOf(code) + "）");
                        codes.add(code);
                        urls.add(s.installUrl);
                    }
                } catch (Throwable e) {
                    failed++;
                }
            }
            final int fails = failed;
            MAIN.post(() -> {
                if (host == null || host.isFinishing()) return;
                if (codes.isEmpty()) {
                    Toast.makeText(ctx, fails > 0 ? "没有可用更新（" + fails + " 个检查失败）" : "都是最新版本", Toast.LENGTH_SHORT).show();
                    return;
                }
                new AlertDialog.Builder(host).setTitle(codes.size() + " 个脚本有更新")
                        .setMessage(TextUtils.join("\n", names))
                        .setPositiveButton("全部更新", (d, w) -> new Thread(() -> {
                            int ok = 0;
                            for (int i = 0; i < codes.size(); i++) {
                                try {
                                    scripts().install(codes.get(i), urls.get(i));
                                    ok++;
                                } catch (Throwable ignored) {}
                            }
                            final int okN = ok;
                            MAIN.post(() -> {
                                Toast.makeText(ctx, "已更新 " + okN + " 个脚本，刷新页面后生效", Toast.LENGTH_LONG).show();
                                refreshScriptsPanelIfOpen();
                            });
                        }, "pimet-us-update").start())
                        .setNegativeButton("取消", null).show();
            });
        }, "pimet-us-check").start();
    }

    private static String verOf(String code) {
        try {
            UserScriptManager.Script s = UserScriptManager.parse(code);
            return s != null && s.version != null ? s.version : "?";
        } catch (Throwable e) {
            return "?";
        }
    }

    /** 脚本详情：描述、匹配规则、编辑源码、存储值、菜单命令、检查更新、删除 */
    private void showScriptDetail(String id) {
        if (host == null || host.isFinishing()) return;
        UserScriptManager.Script s;
        try {
            s = scripts().get(id);
        } catch (Throwable e) {
            s = null;
        }
        if (s == null) {
            Toast.makeText(ctx, "脚本不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        final UserScriptManager.Script sc = s;
        StringBuilder sb = new StringBuilder();
        sb.append("版本：").append(s.version == null || s.version.isEmpty() ? "?" : s.version).append(s.enabled ? "  ·  已启用" : "  ·  已停用");
        if (s.author != null && !s.author.isEmpty()) sb.append("\n作者：").append(s.author);
        if (s.namespace != null && !s.namespace.isEmpty()) sb.append("\n命名空间：").append(s.namespace);
        sb.append("\n运行时机：").append(s.runAt == null || s.runAt.isEmpty() ? "document-idle" : s.runAt);
        if (s.description != null && !s.description.isEmpty()) sb.append("\n\n").append(s.description);
        if (s.matches != null && !s.matches.isEmpty()) sb.append("\n\n@match：\n").append(joinList(s.matches));
        if (s.includes != null && !s.includes.isEmpty()) sb.append("\n\n@include：\n").append(joinList(s.includes));
        if (s.excludes != null && !s.excludes.isEmpty()) sb.append("\n\n@exclude：\n").append(joinList(s.excludes));
        sb.append("\n\n@grant：\n").append(joinList(s.grants));
        if (s.connects != null && !s.connects.isEmpty()) sb.append("\n\n@connect：\n").append(joinList(s.connects));
        if (s.installUrl != null && !s.installUrl.isEmpty()) sb.append("\n\n来源：").append(s.installUrl);
        JSONArray menus = menuCommands(active);
        final List<String> cmdIds = new ArrayList<>();
        final List<String> cmdCaps = new ArrayList<>();
        for (int i = 0; i < menus.length(); i++) {
            JSONObject m = menus.optJSONObject(i);
            if (m != null && id.equals(m.optString("script"))) {
                cmdIds.add(m.optString("cmd"));
                cmdCaps.add(m.optString("caption"));
            }
        }
        if (!cmdIds.isEmpty()) sb.append("\n\n本页菜单命令：\n").append(TextUtils.join("\n", cmdCaps));

        final String[] items = new String[]{"✏️ 编辑源码", "🗄 查看存储值", cmdIds.isEmpty() ? "☰ 菜单命令（本页没有）" : "☰ 执行菜单命令", "⟳ 检查更新"};
        ThemeManager.ThemePalette p = ThemeManager.getEffectivePalette(host);
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        TextView info = panelText(sb.toString(), 12f, p.text, false);
        info.setTextIsSelectable(true);
        box.addView(info);
        final AlertDialog[] dlg = new AlertDialog[1];
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            box.addView(panelRow(items[i], null, p, v -> {
                switch (idx) {
                    case 0:
                        if (dlg[0] != null) dlg[0].dismiss();
                        showScriptEditor(sc, sc.code);
                        break;
                    case 1:
                        if (dlg[0] != null) dlg[0].dismiss();
                        showScriptValues(sc);
                        break;
                    case 2:
                        if (cmdIds.isEmpty()) return;
                        new AlertDialog.Builder(host).setTitle("菜单命令")
                                .setItems(cmdCaps.toArray(new String[0]), (d, w) -> {
                                    if (dlg[0] != null) dlg[0].dismiss();
                                    if (scriptsPanel != null) scriptsPanel.dismiss();
                                    runMenuCommand(active, sc.id, cmdIds.get(w));
                                }).show();
                        break;
                    case 3:
                        checkUpdates(sc.id);
                        break;
                    default:
                        break;
                }
            }), rowLp(dp(6)));
        }
        ScrollView sv = new ScrollView(host);
        sv.addView(box);
        dlg[0] = new AlertDialog.Builder(host).setTitle(s.name == null || s.name.isEmpty() ? s.id : s.name).setView(sv)
                .setNegativeButton("删除", (d, w) -> confirmRemoveScript(sc))
                .setPositiveButton("关闭", null)
                .show();
    }

    private void confirmRemoveScript(UserScriptManager.Script s) {
        if (host == null) return;
        new AlertDialog.Builder(host).setTitle("删除脚本？")
                .setMessage("「" + (s.name == null ? s.id : s.name) + "」的源码和存储的数据都会被删除。")
                .setPositiveButton("删除", (d, w) -> new Thread(() -> {
                    boolean ok;
                    try {
                        ok = scripts().remove(s.id);
                    } catch (Throwable e) {
                        ok = false;
                    }
                    final boolean done = ok;
                    MAIN.post(() -> {
                        Toast.makeText(ctx, done ? "已删除，刷新页面后生效" : "删除失败", Toast.LENGTH_SHORT).show();
                        refreshScriptsPanelIfOpen();
                    });
                }, "pimet-us-remove").start())
                .setNegativeButton("取消", null).show();
    }

    /** 全屏源码编辑：s == null 表示新建；保存 = install(code, 原 installUrl)，按 namespace+name 覆盖同一脚本 */
    private void showScriptEditor(UserScriptManager.Script s, String code) {
        if (host == null || host.isFinishing()) return;
        ThemeManager.ThemePalette p = ThemeManager.getEffectivePalette(host);
        android.app.Dialog d = new android.app.Dialog(host, android.R.style.Theme_DeviceDefault_NoActionBar);
        LinearLayout col = new LinearLayout(host);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(p.bg);
        LinearLayout top = new LinearLayout(host);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(12), dp(8), dp(12), dp(8));
        TextView cancel = panelButton("取消", p, v -> d.dismiss());
        top.addView(cancel);
        TextView tt = panelText(s == null ? "新建脚本" : "编辑：" + (s.name == null ? s.id : s.name), 15f, p.text, true);
        tt.setSingleLine(true);
        tt.setEllipsize(TextUtils.TruncateAt.END);
        tt.setGravity(Gravity.CENTER);
        top.addView(tt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        EditText et = new EditText(host);
        TextView save = panelButton("保存", p, v -> {
            String c = et.getText().toString();
            if (!c.contains("==UserScript==")) {
                Toast.makeText(ctx, "缺少有效的 ==UserScript== 头", Toast.LENGTH_SHORT).show();
                return;
            }
            final String inst = s != null ? s.installUrl : null;
            new Thread(() -> {
                try {
                    UserScriptManager.Script r = scripts().install(c, inst);
                    MAIN.post(() -> {
                        d.dismiss();
                        Toast.makeText(ctx, "已保存" + (r != null && r.name != null ? "：" + r.name : "") + "，刷新页面后生效", Toast.LENGTH_SHORT).show();
                        refreshScriptsPanelIfOpen();
                    });
                } catch (Throwable e) {
                    MAIN.post(() -> Toast.makeText(ctx, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }, "pimet-us-save").start();
        });
        top.addView(save);
        col.addView(top);
        et.setText(code == null ? "" : code);
        et.setTypeface(android.graphics.Typeface.MONOSPACE);
        et.setTextSize(12f);
        et.setTextColor(p.text);
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setHorizontallyScrolling(true);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        et.setBackground(null);
        et.setPadding(dp(12), dp(8), dp(12), dp(8));
        col.addView(et, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        d.setContentView(col);
        d.show();
    }

    private void showCodeViewer(String name, String code, Runnable back) {
        if (host == null) return;
        TextView tv = new TextView(host);
        tv.setText(code);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextSize(11f);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(16), dp(8), dp(16), dp(8));
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(host);
        hs.addView(tv);
        ScrollView sv = new ScrollView(host);
        sv.addView(hs);
        new AlertDialog.Builder(host).setTitle(name == null ? "源码" : name).setView(sv)
                .setPositiveButton("返回", (d, w) -> { if (back != null) back.run(); })
                .show();
    }

    private void showScriptValues(UserScriptManager.Script s) {
        if (host == null) return;
        new Thread(() -> {
            JSONObject vals;
            try {
                vals = scripts().getValues(s.id);
            } catch (Throwable e) {
                vals = null;
            }
            final JSONObject v = vals == null ? new JSONObject() : vals;
            MAIN.post(() -> {
                if (host == null || host.isFinishing()) return;
                String text;
                try {
                    text = v.length() == 0 ? "（没有存储值）" : v.toString(2);
                } catch (Throwable e) {
                    text = v.toString();
                }
                TextView tv = new TextView(host);
                tv.setText(text);
                tv.setTypeface(android.graphics.Typeface.MONOSPACE);
                tv.setTextSize(11f);
                tv.setTextIsSelectable(true);
                tv.setPadding(dp(16), dp(8), dp(16), dp(8));
                ScrollView sv = new ScrollView(host);
                sv.addView(tv);
                AlertDialog.Builder b = new AlertDialog.Builder(host).setTitle("存储值：" + (s.name == null ? s.id : s.name)).setView(sv)
                        .setPositiveButton("关闭", null);
                if (v.length() > 0) {
                    b.setNegativeButton("清空", (d, w) -> new Thread(() -> {
                        java.util.Iterator<String> it = v.keys();
                        List<String> keys = new ArrayList<>();
                        while (it.hasNext()) keys.add(it.next());
                        for (String k : keys) {
                            try { scripts().deleteValue(s.id, k); } catch (Throwable ignored) {}
                        }
                        MAIN.post(() -> Toast.makeText(ctx, "已清空，刷新页面后生效", Toast.LENGTH_SHORT).show());
                    }, "pimet-us-clear").start());
                }
                b.show();
            });
        }, "pimet-us-values").start();
    }

    // ------------------------------------------------------------------ 给 BrowserAgent：浏览器设置（主线程调用）

    /** 当前 config.json（副本） */
    JSONObject getConfig() {
        reloadConfigIfChanged();
        try {
            return new JSONObject(config.toString());
        } catch (Throwable e) {
            return new JSONObject();
        }
    }

    /** 合并写入 config.json（对象递归合并，值为 null 表示删除该键）并立即生效 */
    void setConfig(JSONObject patch) {
        if (patch == null) return;
        File f = new File(appsDir, "config.json");
        try {
            JSONObject cur = f.isFile() ? new JSONObject(readFile(f)) : new JSONObject();
            mergeJson(cur, patch);
            File tmp = new File(appsDir, ".config.json.tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                o.write(cur.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(f)) {
                writeIfChanged(f, cur.toString(2));
                tmp.delete();
            }
        } catch (Throwable e) {
            throw new RuntimeException("写入 config.json 失败: " + e.getMessage(), e);
        }
        reloadConfigNow();
        refreshScriptsPanelIfOpen();
    }

    private static void mergeJson(JSONObject dst, JSONObject patch) throws Exception {
        java.util.Iterator<String> it = patch.keys();
        while (it.hasNext()) {
            String k = it.next();
            Object v = patch.opt(k);
            if (v == null || v == JSONObject.NULL) {
                dst.remove(k);
            } else if (v instanceof JSONObject && dst.opt(k) instanceof JSONObject) {
                mergeJson(dst.getJSONObject(k), (JSONObject) v);
            } else {
                dst.put(k, v);
            }
        }
    }

    private String customUa() {
        String ua = prefs().getString("custom_ua", "");
        return ua == null || ua.isEmpty() ? null : ua;
    }

    /** 自定义 UA（指定标签或全部标签） */
    void setUserAgent(String uaOrNull) {
        String ua = uaOrNull == null ? "" : uaOrNull.trim();
        prefs().edit().putString("custom_ua", ua).apply();
        if (workerPool != null) workerPool.setCustomUa(ua.isEmpty() ? null : ua);
        for (Tab t : tabs) {
            if (t.web == null) continue;
            t.desktop = false;
            t.siteUaMode = null;
            t.siteUa = null;
            applyContainerEnv(t, t.web, false);
            applyZoom(t);
            t.web.reload();
        }
    }

    void setTabUserAgent(Tab t, String uaOrNull) {
        if (t == null || t.closed || t.web == null) return;
        String ua = uaOrNull == null ? "" : uaOrNull.trim();
        t.desktop = false;
        t.siteUaMode = ua.isEmpty() ? null : ua;
        t.siteUa = ua.isEmpty() ? null : ua;
        applyContainerEnv(t, t.web, false);
        applyZoom(t);
        t.web.reload();
    }

    /** 给 BrowserAgent 的 status：default | desktop | custom */
    String userAgentMode() {
        Tab t = active;
        if (t != null && t.desktop) return "desktop";
        return customUa() != null ? "custom" : "default";
    }

    /** 桌面版网站（所有标签） */
    void setDesktopMode(boolean on) {
        if (on) prefs().edit().putString("custom_ua", "").apply();
        for (Tab t : new ArrayList<>(tabs)) {
            if (t.web == null || t.desktop == on) continue;
            setTabDesktop(t, on, false);
        }
    }

    /** 网页缩放百分比（网站页面，和 ⋮ → 页面缩放 同一个设置） */
    void setZoom(int percent) {
        saveZoom(null, "https://zoom.invalid/", percent, null);
    }

    void setTabZoom(Tab t, int percent) {
        if (t == null || t.closed || t.web == null) return;
        int z = WebPinchZoom.clamp(percent);
        String u = t.url;
        if (u != null && !u.startsWith("about:")) {
            t.web.getSettings().setTextZoom(100);
            WebPinchZoom.applyPercent(t.web, z);
        }
    }

    String activeRemoteTabId() {
        return activeRemoteTabId;
    }

    int mainTabCountForContainer(String containerName) {
        if (containerName == null || containerName.isEmpty()) return 0;
        int cnt = 0;
        for (Tab x : tabs) {
            if (x != null && !x.closed && containerName.equals(x.container)) cnt++;
        }
        return cnt;
    }

    boolean isEffectiveDesktop(Tab t) {
        return effectiveDesktopOf(t);
    }

    /** 给 BrowserAgent 的 status：当前网页缩放百分比 */
    int zoomPercent() {
        return WebPinchZoom.clamp(prefs().getInt("page_zoom", 100));
    }

    /** 清除浏览数据：cookies | cache | storage | all */
    void clearData(String what) {
        clearData(what, null);
    }

    /** container：null = 当前标签的容器（Cookie 按容器清；cache/storage 目前是整个 WebView 的） */
    void clearData(String what, String container) {
        String w = what == null ? "all" : what.trim().toLowerCase(java.util.Locale.ROOT);
        boolean all = w.equals("all");
        if (all || w.equals("cookies")) {
            android.webkit.CookieManager cm = cookiesFor(containerOrActive(container));
            cm.removeAllCookies(null);
            cm.flush();
        }
        if (all || w.equals("cache")) {
            for (Tab t : tabs) if (t.web != null) t.web.clearCache(true);
        }
        if (all || w.equals("storage")) {
            android.webkit.WebStorage.getInstance().deleteAllData();
        }
    }

    String getCookies(String url) {
        return getCookies(url, null);
    }

    /** container：null = 当前标签的容器 */
    String getCookies(String url, String container) {
        String c = cookiesFor(containerOrActive(container)).getCookie(url);
        return c == null ? "" : c;
    }

    private String containerOrActive(String container) {
        if (container != null && !container.trim().isEmpty()) return container.trim();
        return active != null && active.container != null ? active.container : ContainerManager.DEFAULT;
    }

    /** cookie 为 Set-Cookie 格式（如 "a=1; Path=/; Max-Age=3600"） */
    void setCookie(String url, String cookie) {
        setCookie(url, cookie, null);
    }

    /** container：null = 当前标签的容器 */
    void setCookie(String url, String cookie, String container) {
        android.webkit.CookieManager cm = cookiesFor(containerOrActive(container));
        cm.setCookie(url, cookie);
        cm.flush();
    }

    // ------------------------------------------------------------------ 容器标签 + 历史（beta.23，逻辑在 ContainerManager / HistoryStore）

    private ContainerManager containerMgr;
    private HistoryStore historyStore;
    private static boolean sWarnedNoProfile = false;

    /** 容器管理（M1 实现）。BrowserAgent 也用它 */
    synchronized ContainerManager containers() {
        if (containerMgr == null) {
            containerMgr = new ContainerManager(ctx, appsDir);
            containerMgr.setExternalInUseCheck(cid ->
                    mainTabCountForContainer(cid) > 0
                            || (workerPool != null && workerPool.isContainerBusy(cid)));
            containerMgr.setOnReleasedListener(cid -> {
                if (workerPool != null) workerPool.releaseIdleTempSlotAsync(cid);
            });
        }
        return containerMgr;
    }

    /** 浏览历史（M1 实现）。BrowserAgent 与 AppBridgeManager 共用 */
    public synchronized HistoryStore history() {
        if (historyStore == null) historyStore = new HistoryStore(appsDir);
        return historyStore;
    }

    /** 全部标签（主线程读） */
    List<Tab> tabs() {
        return tabs;
    }

    String containerOf(Tab t) {
        return t == null ? null : t.container;
    }

    /** 解析容器参数：null / "" / "new" = 新容器；"same" = 当前标签的容器；"Default" = 共享容器；其它 = 容器名。不支持多 Profile 时一律 Default */
    private String resolveContainer(String spec, Tab from) {
        if (!ContainerManager.supported()) {
            if (!sWarnedNoProfile) {
                sWarnedNoProfile = true;
                Toast.makeText(ctx, "系统 WebView 版本太低，标签共享登录", Toast.LENGTH_LONG).show();
            }
            return ContainerManager.DEFAULT;
        }
        String s = spec == null ? "" : spec.trim();
        if (s.isEmpty() || s.equalsIgnoreCase("new")) {
            try {
                return containers().newContainer();
            } catch (Throwable e) {
                Log.w(TAG, "newContainer failed", e);
                return ContainerManager.DEFAULT;
            }
        }
        if (s.equalsIgnoreCase("same")) {
            Tab f = from != null ? from : active;
            return f != null && f.container != null ? f.container : ContainerManager.DEFAULT;
        }
        if (s.equalsIgnoreCase(ContainerManager.DEFAULT)) return ContainerManager.DEFAULT;
        return s;
    }

    /**
     * 给 BrowserAgent（tab_new）：在指定容器里开一个 Pi 标签。container：null / "new" = 新容器，"same" = 当前标签的容器，"Default" = 共享，其它 = 容器名。
     * background = true 时不切到前台。主线程调用。
     */
    Tab newTabInContainer(String url, String container, boolean background) {
        return newTabInContainer(url, container, background, null);
    }

    Tab newTabInContainer(String url, String container, boolean background, String explicitId) {
        Tab t = newTab(true, resolveContainer(container, active), explicitId);
        if (url == null || url.isEmpty()) t.web.loadUrl("about:blank");
        else loadIn(t, url, false);
        if (!background) switchTo(t);
        else refreshTabs();
        return t;
    }

    /** 用户新建标签：container 规则同 resolveContainer；代理容器绝不在主进程直连打开 */
    private void newUserTabIn(String containerSpec) {
        userTouched = true;
        String resolved;
        if ("same".equalsIgnoreCase(containerSpec) && activeRemoteTabId != null && workerPool != null) {
            ProxyWorkerPool.WorkerSlot activeSlot = workerPool.slotForTab(activeRemoteTabId);
            resolved = activeSlot != null && !activeSlot.boundContainerId.isEmpty()
                    ? activeSlot.boundContainerId
                    : resolveContainer(containerSpec, active);
        } else {
            resolved = resolveContainer(containerSpec, active);
        }
        ContainerEnv.Profile ep = ContainerEnv.NATIVE;
        try {
            if (!ContainerManager.DEFAULT.equalsIgnoreCase(resolved)) {
                ep = containers().getProfile(resolved);
            }
        } catch (Throwable ignored) {}
        if (ep != null && ep.hasActiveProxy()) {
            final String fCont = resolved;
            final ContainerEnv.Profile fEp = ep;
            final String fLabel = containerLabel(fCont);
            final String tabId = nextTabId();
            final ProxyWorkerPool pool = workerPool();
            Toast.makeText(ctx, "正在独立代理进程中打开容器「" + fLabel + "」…", Toast.LENGTH_SHORT).show();
            mirrorExec.execute(() -> {
                ProxyWorkerPool.WorkerSlot slot = null;
                try {
                    slot = pool.acquireSlotForContainer(fCont, fLabel, fEp, 20_000L);
                    JSONObject rpcReq = new JSONObject()
                            .put("action", "tab_new")
                            .put("tabId", tabId)
                            .put("tab", tabId)
                            .put("pi", false)
                            .put("desktop", fEp.desktop);
                    JSONObject resp = pool.callRpc(slot, rpcReq, "ui-tabnew-" + tabId, 20_000L);
                    if (resp.optBoolean("success", false)) {
                        pool.registerRemoteTab(tabId, slot);
                        MAIN.post(() -> {
                            switchToRemote(tabId);
                            refreshTabs();
                        });
                    } else {
                        final String errMsg = resp.optString("error", "创建远程代理标签失败");
                        MAIN.post(() -> Toast.makeText(ctx, errMsg, Toast.LENGTH_LONG).show());
                    }
                } catch (Throwable t) {
                    final String errMsg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                    MAIN.post(() -> Toast.makeText(ctx, "打开代理标签失败：" + errMsg, Toast.LENGTH_LONG).show());
                } finally {
                    if (slot != null) pool.releaseAcquire(slot);
                }
            });
            return;
        }
        Tab t = newTab(false, resolved);
        switchTo(t);
        openNewTabContent(t);
    }

    /** 标签关闭后：没有其他标签还在用这个容器就交给 ContainerManager 释放（keep 的、Default 由它自己判断不删） */
    private void releaseContainer(String name) {
        if (name == null || ContainerManager.DEFAULT.equals(name) || !ContainerManager.supported()) return;
        // 每个 WebView apply 一次就 release 一次：引用计数由 ContainerManager 管（还有别的标签在用时它不会删）
        try {
            containers().release(name);
        } catch (Throwable e) {
            Log.w(TAG, "release container failed", e);
        }
    }

    /** 容器显示信息：[标签, 颜色]；Default 显示为「共享」 */
    String containerLabel(String name) {
        if (name == null || ContainerManager.DEFAULT.equals(name)) return "共享";
        try {
            JSONObject m = containers().meta(name);
            if (m != null) {
                String l = m.optString("label", "");
                if (!l.isEmpty()) return l;
            }
        } catch (Throwable ignored) {}
        return name;
    }

    private int containerColor(String name) {
        if (name == null || ContainerManager.DEFAULT.equals(name)) return cDim;
        try {
            JSONObject m = containers().meta(name);
            if (m != null) return color(m, "color", cDim);
        } catch (Throwable ignored) {}
        return cDim;
    }

    private boolean containerKeep(String name) {
        if (name == null) return false;
        if (ContainerManager.DEFAULT.equals(name)) return true;
        try {
            JSONObject m = containers().meta(name);
            return m != null && m.optBoolean("keep", false);
        } catch (Throwable e) {
            return false;
        }
    }

    /** 该容器的 CookieManager（主线程取；不支持多 Profile 时是全局的） */
    private android.webkit.CookieManager cookiesFor(String container) {
        try {
            android.webkit.CookieManager cm = containers().cookies(container == null ? ContainerManager.DEFAULT : container);
            if (cm != null) return cm;
        } catch (Throwable ignored) {}
        return android.webkit.CookieManager.getInstance();
    }

    private static android.webkit.CookieManager tabCookies(Tab t) {
        android.webkit.CookieManager cm = t != null ? t.cookies : null;
        return cm != null ? cm : android.webkit.CookieManager.getInstance();
    }

    /** 长按「＋」/ 标签数：选在哪个容器开新标签 */
    private void showNewTabChooser(View anchor) {
        if (host == null) return;
        PopupMenu pm = new PopupMenu(host, anchor);
        Menu m = pm.getMenu();
        boolean sup = ContainerManager.supported();
        m.add(0, 1, 0, "新标签页（新容器）");
        if (sup && active != null && active.container != null) m.add(0, 2, 0, "新标签页（当前容器：" + containerLabel(active.container) + "）");
        if (sup) m.add(0, 3, 0, "新标签页（共享容器 Default）");
        pm.setOnMenuItemClickListener(item -> {
            closeSwitcher();
            switch (item.getItemId()) {
                case 2: newUserTabIn("same"); break;
                case 3: newUserTabIn(ContainerManager.DEFAULT); break;
                default: newUserTabIn("new"); break;
            }
            return true;
        });
        pm.show();
    }

    /** 标签卡片长按：保留容器、重命名、伪装环境、在同容器新建、关闭 */
    private void showTabMenu(Tab x, View anchor) {
        if (host == null || x == null) return;
        PopupMenu pm = new PopupMenu(host, anchor);
        Menu m = pm.getMenu();
        boolean sup = ContainerManager.supported();
        boolean isDefault = x.container == null || ContainerManager.DEFAULT.equals(x.container);
        if (sup && !isDefault) {
            m.add(0, 1, 0, "保留此容器（关闭后不删数据）").setCheckable(true).setChecked(containerKeep(x.container));
            m.add(0, 2, 0, "重命名容器…");
            m.add(0, 5, 0, "伪装环境：" + ContainerEnv.summary(x.envProfile));
            m.add(0, 6, 0, "🌐 代理设置：" + x.proxySummary);
        }
        if (sup) m.add(0, 3, 0, "在此容器新建标签");
        m.add(0, 4, 0, "关闭标签");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: {
                    boolean keep = !containerKeep(x.container);
                    try {
                        containers().setKeep(x.container, keep);
                    } catch (Throwable ignored) {}
                    Toast.makeText(ctx, keep ? "已保留容器：关闭标签后登录和数据都还在" : "已取消保留：最后一个标签关闭时删除容器数据", Toast.LENGTH_SHORT).show();
                    rebuildSwitcher();
                    break;
                }
                case 2:
                    renameContainer(x.container);
                    break;
                case 5:
                    showContainerProfileDialog(x.container);
                    break;
                case 6:
                    showContainerProxyDialog(x.container);
                    break;
                case 3:
                    closeSwitcher();
                    userTouched = true;
                    Tab nt = newTab(false, x.container);
                    switchTo(nt);
                    goHome(nt, true);
                    break;
                case 4:
                    closeTab(x);
                    if (switcher.getVisibility() == View.VISIBLE) rebuildSwitcher();
                    break;
                default:
                    return false;
            }
            return true;
        });
        pm.show();
    }

    private void showContainerProfileDialog(String containerName) {
        if (host == null || containerName == null || ContainerManager.DEFAULT.equals(containerName)) return;
        final List<String> presets = ContainerEnv.presetIds();
        final List<String> labels = new ArrayList<>();
        ContainerEnv.Profile cur = ContainerEnv.NATIVE;
        try { cur = containers().getProfile(containerName); } catch (Throwable ignored) {}
        for (String pid : presets) {
            String lbl = ContainerEnv.presetDisplayLabel(pid);
            if (pid.equals(cur.preset)) lbl += "  ✓";
            labels.add(lbl);
        }
        labels.add("🎲 随机更换设备指纹与 Seed");
        new AlertDialog.Builder(host)
                .setTitle("伪装环境 · " + containerLabel(containerName) + "\n" + ContainerEnv.summary(cur))
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    try {
                        ContainerEnv.Profile next;
                        if (which == presets.size()) {
                            next = containers().randomizeProfile(containerName);
                        } else {
                            next = containers().setProfile(containerName, presets.get(which));
                        }
                        int n = applyContainerEnvForContainer(containerName, false);
                        final ProxyWorkerPool.WorkerSlot activeWorker =
                                workerPool != null ? workerPool.slotForContainer(containerName) : null;
                        if (activeWorker != null) {
                            final ContainerEnv.Profile fNext = next;
                            final String fLabel = containerLabel(containerName);
                            mirrorExec.execute(() -> {
                                try {
                                    JSONObject rpc = new JSONObject()
                                            .put("action", "update_container_profile")
                                            .put("label", fLabel)
                                            .put("profileJson", ContainerEnv.toJson(fNext).toString());
                                    JSONObject resp = workerPool.callRpc(activeWorker, rpc, "ui-prof-" + containerName, 12000L);
                                    if (resp.optBoolean("success", false)) {
                                        activeWorker.boundProfile = fNext;
                                        activeWorker.boundLabel = fLabel;
                                        MAIN.post(this::refreshTabs);
                                    }
                                } catch (Throwable ignored) {}
                            });
                        }
                        if (n > 0) {
                            new AlertDialog.Builder(host)
                                    .setTitle("已更新容器环境")
                                    .setMessage("当前环境：" + ContainerEnv.summary(next)
                                            + "\n\n注意：该容器已有打开的页面，旧页面 JS 上下文可能残留旧指纹或旧会话 Cookie；建议立即刷新，或新建容器访问目标站更安全。")
                                    .setPositiveButton("立即刷新该容器标签", (d2, w2) -> applyContainerEnvForContainer(containerName, true))
                                    .setNegativeButton("稍后手动刷新", null)
                                    .show();
                        } else {
                            Toast.makeText(ctx, "已切换为：" + ContainerEnv.summary(next)
                                    + (activeWorker != null ? "（已同步至代理工作进程）" : ""), Toast.LENGTH_SHORT).show();
                        }
                    } catch (Throwable e) {
                        Toast.makeText(ctx, "设置失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showContainerProxyDialog(String containerName) {
        if (host == null || host.isFinishing()) return;
        if (containerName == null || ContainerManager.DEFAULT.equalsIgnoreCase(containerName)) {
            Toast.makeText(ctx, "Default 共享容器始终为直连，禁止配置代理", Toast.LENGTH_SHORT).show();
            return;
        }
        ContainerEnv.Profile curProf = ContainerEnv.NATIVE;
        try { curProf = containers().getProfile(containerName); } catch (Throwable ignored) {}
        final ContainerEnv.Profile cur = curProf;
        final ContainerEnv.ProxySpec curProxy = cur.proxy;
        final String origUpstreamDisp = (curProxy != null && curProxy.upstream != null)
                ? curProxy.upstream.endpointDisplay() : "";

        ThemeManager.ThemePalette p = ThemeManager.getEffectivePalette(host);
        ScrollView scroll = new ScrollView(host);
        LinearLayout col = new LinearLayout(host);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(14), dp(18), dp(8));

        if (!proxyManager().isProxySupported()) {
            TextView warn = panelText("⚠ 当前系统 WebView 不支持 PROXY_OVERRIDE，代理设置无法生效", 12f, Color.parseColor("#D29922"), false);
            col.addView(warn);
        }

        android.widget.CheckBox enableCb = new android.widget.CheckBox(host);
        enableCb.setText("启用代理");
        enableCb.setTextColor(p.text);
        enableCb.setChecked(curProxy == null || curProxy.enabled);
        col.addView(enableCb);

        android.widget.RadioGroup typeGroup = new android.widget.RadioGroup(host);
        typeGroup.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.RadioButton rbHttp = new android.widget.RadioButton(host);
        rbHttp.setText("HTTP");
        rbHttp.setTextColor(p.text);
        android.widget.RadioButton rbSocks5 = new android.widget.RadioButton(host);
        rbSocks5.setText("SOCKS5");
        rbSocks5.setTextColor(p.text);
        typeGroup.addView(rbHttp);
        typeGroup.addView(rbSocks5);
        if (curProxy != null && "socks5".equals(curProxy.type)) rbSocks5.setChecked(true);
        else rbHttp.setChecked(true);
        col.addView(typeGroup);

        EditText hostEt = new EditText(host);
        hostEt.setSingleLine(true);
        hostEt.setHint("主机 / IP（如 127.0.0.1）");
        hostEt.setTextColor(p.text);
        hostEt.setHintTextColor(p.textDim);
        if (curProxy != null) hostEt.setText(curProxy.host);
        col.addView(hostEt);

        EditText portEt = new EditText(host);
        portEt.setSingleLine(true);
        portEt.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        portEt.setHint("端口（1..65535）");
        portEt.setTextColor(p.text);
        portEt.setHintTextColor(p.textDim);
        if (curProxy != null && curProxy.port > 0) portEt.setText(String.valueOf(curProxy.port));
        col.addView(portEt);

        EditText userEt = new EditText(host);
        userEt.setSingleLine(true);
        userEt.setHint("用户名（可选）");
        userEt.setTextColor(p.text);
        userEt.setHintTextColor(p.textDim);
        if (curProxy != null) userEt.setText(curProxy.username);
        col.addView(userEt);

        // 绝不回显明文密码：密码框永远初始为空
        EditText passEt = new EditText(host);
        passEt.setSingleLine(true);
        passEt.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passEt.setText("");
        passEt.setHint((curProxy != null && !curProxy.password.isEmpty()) ? "密码（已保存，留空保留原密码）" : "密码（可选）");
        passEt.setTextColor(p.text);
        passEt.setHintTextColor(p.textDim);
        col.addView(passEt);

        EditText upstreamEt = new EditText(host);
        upstreamEt.setSingleLine(true);
        upstreamEt.setHint("前置代理 upstream（可选，如 http://127.0.0.1:7890）");
        upstreamEt.setTextColor(p.text);
        upstreamEt.setHintTextColor(p.textDim);
        if (!origUpstreamDisp.isEmpty()) upstreamEt.setText(origUpstreamDisp);
        col.addView(upstreamEt);

        LinearLayout testRow = new LinearLayout(host);
        testRow.setOrientation(LinearLayout.HORIZONTAL);
        testRow.setGravity(Gravity.CENTER_VERTICAL);
        testRow.setPadding(0, dp(8), 0, dp(4));
        TextView testBtn = panelButton("测试连通性", p, null);
        TextView testStatusTv = panelText("", 12f, p.textDim, false);
        testStatusTv.setPadding(dp(10), 0, 0, 0);
        testRow.addView(testBtn);
        testRow.addView(testStatusTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(testRow);

        scroll.addView(col);

        final java.util.concurrent.Callable<ContainerEnv.ProxySpec> buildSpec = () -> {
            boolean enabled = enableCb.isChecked();
            String type = rbSocks5.isChecked() ? "socks5" : "http";
            String h = hostEt.getText().toString().trim();
            String portStr = portEt.getText().toString().trim();
            int port = 0;
            if (!portStr.isEmpty()) {
                try { port = Integer.parseInt(portStr); } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("端口格式无效");
                }
            }
            String u = userEt.getText().toString();
            String pwIn = passEt.getText().toString();
            String pw;
            if (!pwIn.isEmpty()) {
                pw = pwIn;
            } else if (curProxy != null && !curProxy.password.isEmpty() && (!u.isEmpty() || curProxy.username.isEmpty())) {
                pw = curProxy.password;
            } else {
                pw = "";
            }
            String upText = upstreamEt.getText().toString().trim();
            ContainerEnv.ProxyEndpoint up;
            if (upText.isEmpty()) {
                up = null;
            } else if (!origUpstreamDisp.isEmpty() && upText.equals(origUpstreamDisp) && curProxy != null) {
                up = curProxy.upstream;
            } else {
                up = ContainerEnv.parseProxyEndpoint(upText);
            }
            if (enabled) {
                if (h.isEmpty()) throw new IllegalArgumentException("请输入代理主机");
                if (port < 1 || port > 65535) throw new IllegalArgumentException("端口必须在 1..65535 之间");
            } else if (h.isEmpty() && port == 0 && up == null) {
                return null;
            }
            return new ContainerEnv.ProxySpec(enabled, type, h, port, u, pw, up);
        };

        testBtn.setOnClickListener(v -> {
            ContainerEnv.ProxySpec candidate;
            try {
                candidate = buildSpec.call();
            } catch (Throwable t) {
                testStatusTv.setTextColor(Color.parseColor("#F85149"));
                testStatusTv.setText("❌ " + t.getMessage());
                return;
            }
            if (candidate == null || !candidate.isActive()) {
                testStatusTv.setTextColor(Color.parseColor("#D29922"));
                testStatusTv.setText("请先勾选启用并填写有效主机端口");
                return;
            }
            testStatusTv.setTextColor(p.textDim);
            testStatusTv.setText("⏳ 测试中…");
            proxyManager().testProxyAsync(candidate, res -> {
                if (res.ok) {
                    testStatusTv.setTextColor(Color.parseColor("#3FB950"));
                    testStatusTv.setText("✅ HTTP " + res.statusCode + " · " + res.latencyMs + "ms");
                } else {
                    testStatusTv.setTextColor(Color.parseColor("#F85149"));
                    testStatusTv.setText("❌ " + res.error + " (" + res.latencyMs + "ms)");
                }
            });
        });

        AlertDialog dlg = new AlertDialog.Builder(host)
                .setTitle("🌐 代理设置 · " + containerLabel(containerName))
                .setView(scroll)
                .setPositiveButton("保存", null)
                .setNeutralButton("清除代理（直连）", (d, w) -> {
                    try {
                        JSONObject spec = new JSONObject().put("proxy", JSONObject.NULL);
                        containers().setProfile(containerName, spec);
                        applyContainerEnvForContainer(containerName, false);
                        Toast.makeText(ctx, "已清除代理（直连）", Toast.LENGTH_SHORT).show();
                    } catch (Throwable t) {
                        Toast.makeText(ctx, "清除失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.setOnShowListener(di -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                ContainerEnv.ProxySpec candidate = buildSpec.call();
                int mainDirectCount = 0;
                for (Tab x : tabs) {
                    if (x != null && !x.closed && containerName.equals(x.container)) mainDirectCount++;
                }
                if (candidate != null && candidate.isActive() && mainDirectCount > 0) {
                    new AlertDialog.Builder(host)
                            .setTitle("主进程严禁切换代理")
                            .setMessage("容器「" + containerLabel(containerName) + "」当前在主进程有 " + mainDirectCount
                                    + " 个直连标签。主进程固定保持直连不切代理。\n\n请先关闭该容器的直连标签再设置代理，或新建容器标签以在独立代理子进程运行。")
                            .setPositiveButton("知道了", null)
                            .show();
                    return;
                }
                JSONObject spec = new JSONObject()
                        .put("proxy", candidate == null ? JSONObject.NULL : candidate.toStorageJson());
                ContainerEnv.Profile next = containers().setProfile(containerName, spec);
                applyContainerEnvForContainer(containerName, false);
                final ProxyWorkerPool.WorkerSlot activeWorker = workerPool != null ? workerPool.slotForContainer(containerName) : null;
                if (activeWorker != null) {
                    mirrorExec.execute(() -> {
                        try {
                            JSONObject rpc = new JSONObject()
                                    .put("action", "update_container_profile")
                                    .put("label", containerLabel(containerName))
                                    .put("profileJson", ContainerEnv.toJson(next).toString());
                            workerPool.callRpc(activeWorker, rpc, "ui-proxy-" + containerName, 12000L);
                        } catch (Throwable ignored) {}
                    });
                }
                Toast.makeText(ctx, "已保存代理：" + (next.hasActiveProxy() ? next.proxy.summary() : "直连"), Toast.LENGTH_SHORT).show();
                dlg.dismiss();
            } catch (Throwable t) {
                Toast.makeText(ctx, "保存失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        }));
        dlg.show();
    }

    private void renameContainer(String name) {
        if (host == null || name == null) return;
        EditText et = new EditText(host);
        et.setSingleLine(true);
        et.setText(containerLabel(name));
        et.selectAll();
        FrameLayout f = new FrameLayout(host);
        f.setPadding(dp(20), dp(8), dp(20), 0);
        f.addView(et);
        new AlertDialog.Builder(host).setTitle("重命名容器").setView(f)
                .setPositiveButton("确定", (d, w) -> {
                    String l = et.getText().toString().trim();
                    if (l.isEmpty()) return;
                    try {
                        containers().rename(name, l);
                    } catch (Throwable ignored) {}
                    refreshTabs();
                    if (switcher.getVisibility() == View.VISIBLE) rebuildSwitcher();
                })
                .setNegativeButton("取消", null).show();
    }

    /** 记一条历史（launcher、应用目录页面、非 http(s)/file 不记） */
    private void recordHistory(Tab t, String url, String title) {
        if (t == null || url == null || url.isEmpty() || isHomeUrl(url) || isTrusted(url)) return;
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return;
        try {
            history().add(url, title == null ? "" : title, t.container);
        } catch (Throwable e) {
            Log.w(TAG, "history add failed", e);
        }
    }

    // ---- 🕘 历史面板

    private static final int HISTORY_PAGE = 100;

    private void showHistoryPanel() {
        if (host == null || host.isFinishing()) return;
        hideIme();
        ThemeManager.ThemePalette p = ThemeManager.getEffectivePalette(host);
        LinearLayout col = new LinearLayout(host);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(14), dp(18), dp(12));

        LinearLayout head = new LinearLayout(host);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(panelText("🕘 历史", 16f, p.text, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final AlertDialog[] dlg = new AlertDialog[1];
        final Runnable[] reload = new Runnable[1];
        head.addView(panelButton("清除…", p, v -> new AlertDialog.Builder(host).setTitle("清除历史")
                .setItems(new String[]{"最近一小时", "今天", "全部"}, (d, w) -> {
                    new Thread(() -> {
                        try {
                            if (w == 0) history().clearSince(System.currentTimeMillis() - 3600_000L);
                            else if (w == 1) history().clearSince(startOfDay(System.currentTimeMillis()));
                            else history().clear();
                        } catch (Throwable ignored) {}
                        MAIN.post(() -> {
                            Toast.makeText(ctx, "已清除", Toast.LENGTH_SHORT).show();
                            if (reload[0] != null) reload[0].run();
                        });
                    }, "pimet-history-clear").start();
                }).show()));
        col.addView(head);

        EditText search = new EditText(host);
        search.setSingleLine(true);
        search.setHint("搜索历史");
        search.setTextSize(14f);
        search.setTextColor(p.text);
        search.setHintTextColor(p.textDim);
        search.setBackground(ThemeManager.createSecondaryButtonDrawable(host, p, 8f));
        search.setPadding(dp(12), dp(8), dp(12), dp(8));
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(10);
        slp.bottomMargin = dp(6);
        col.addView(search, slp);

        LinearLayout list = new LinearLayout(host);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(host);
        sv.addView(list);
        DisplayMetrics dm = host.getResources().getDisplayMetrics();
        col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (dm.heightPixels * 0.62f)));

        final int[] gen = {0};
        final int[] offset = {0};
        final String[] lastDay = {null};
        final TextView[] more = new TextView[1];

        final Runnable[] loadPage = new Runnable[1];
        loadPage[0] = () -> {
            final int myGen = gen[0];
            final String q = search.getText().toString().trim();
            final int off = offset[0];
            new Thread(() -> {
                JSONArray a;
                try {
                    a = q.isEmpty() ? history().recent(HISTORY_PAGE, off) : history().search(q, 500);
                } catch (Throwable e) {
                    a = new JSONArray();
                }
                final JSONArray items = a == null ? new JSONArray() : a;
                MAIN.post(() -> {
                    if (myGen != gen[0] || host == null) return;
                    if (more[0] != null) {
                        list.removeView(more[0]);
                        more[0] = null;
                    }
                    if (off == 0 && items.length() == 0) {
                        list.addView(panelText(q.isEmpty() ? "还没有浏览历史" : "没有找到「" + q + "」", 12f, p.textDim, false));
                        return;
                    }
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject h = items.optJSONObject(i);
                        if (h == null) continue;
                        long ts = h.optLong("t", 0);
                        String day = dayLabel(ts);
                        if (!day.equals(lastDay[0])) {
                            lastDay[0] = day;
                            list.addView(panelSection(day, p));
                        }
                        list.addView(historyRow(h, p, dlg), rowLp(dp(4)));
                    }
                    offset[0] = off + items.length();
                    if (q.isEmpty() && items.length() >= HISTORY_PAGE) {
                        more[0] = panelButton("加载更多", p, v -> loadPage[0].run());
                        list.addView(more[0], rowLp(dp(8)));
                    }
                });
            }, "pimet-history-load").start();
        };
        reload[0] = () -> {
            gen[0]++;
            offset[0] = 0;
            lastDay[0] = null;
            more[0] = null;
            list.removeAllViews();
            loadPage[0].run();
        };
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                final int g = ++gen[0];
                MAIN.postDelayed(() -> { if (g == gen[0]) reload[0].run(); }, 250);
            }
        });

        FrameLayout wrap = new FrameLayout(host);
        wrap.setBackground(ThemeManager.createCardDrawable(host, p, 14f));
        wrap.addView(col);
        AlertDialog d = new AlertDialog.Builder(host).setView(wrap).create();
        dlg[0] = d;
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setGravity(Gravity.BOTTOM);
        }
        d.show();
        if (w != null) w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        reload[0].run();
    }

    private View historyRow(JSONObject h, ThemeManager.ThemePalette p, AlertDialog[] dlg) {
        final String url = h.optString("url", "");
        String title = h.optString("title", "");
        long ts = h.optLong("t", 0);
        String time = ts > 0 ? new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(new java.util.Date(ts)) : "";
        String sub = time + "  " + url.replaceFirst("^https?://", "");
        View row = panelRow(title.isEmpty() ? url : title, sub, p, v -> {
            if (dlg[0] != null) dlg[0].dismiss();
            load(url);
        });
        row.setOnLongClickListener(v -> {
            new AlertDialog.Builder(host).setTitle(title.isEmpty() ? url : title)
                    .setItems(new String[]{"在新标签打开（当前容器）", "在新标签打开（新容器）", "复制链接", "删除这条记录"}, (d, w) -> {
                        switch (w) {
                            case 0:
                            case 1: {
                                if (dlg[0] != null) dlg[0].dismiss();
                                userTouched = true;
                                Tab nt = newTab(false, resolveContainer(w == 0 ? "same" : "new", active));
                                switchTo(nt);
                                loadIn(nt, url, true);
                                break;
                            }
                            case 2:
                                try {
                                    ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("url", url));
                                    Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show();
                                } catch (Throwable ignored) {}
                                break;
                            default:
                                new Thread(() -> {
                                    try { history().delete(url); } catch (Throwable ignored) {}
                                }, "pimet-history-del").start();
                                row.setVisibility(View.GONE);
                                break;
                        }
                    }).show();
            return true;
        });
        return row;
    }

    private static long startOfDay(long ms) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ms);
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static String dayLabel(long ts) {
        if (ts <= 0) return "更早";
        long today = startOfDay(System.currentTimeMillis());
        if (ts >= today) return "今天";
        if (ts >= today - 86400_000L) return "昨天";
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(new java.util.Date(ts));
    }

    // ------------------------------------------------------------------ 自定义浏览器（beta.24，配置逻辑在 BrowserCustomizer / ui.json）

    private volatile BrowserCustomizer customizer;
    /** 合并默认值后的配置、ui.json 原文（主线程缓存，reloadUi 时刷新） */
    private JSONObject uiCfg = new JSONObject();
    private JSONObject uiRaw = new JSONObject();
    private boolean uiListening = false;
    private GestureDetector barGestures;
    /** 弹出长按菜单用的锚点（放在手指位置） */
    private View popupAnchor;
    private float lastTouchX, lastTouchY;

    /** 给 BrowserAgent：配置读写 */
    synchronized BrowserCustomizer customizer() {
        if (customizer == null) customizer = new BrowserCustomizer(appsDir);
        return customizer;
    }

    private void loadUiCfg() {
        try {
            JSONObject c = customizer().config();
            uiCfg = c != null ? c : new JSONObject();
        } catch (Throwable e) {
            Log.w(TAG, "ui config failed", e);
            uiCfg = new JSONObject();
        }
        try {
            JSONObject r = customizer().raw();
            uiRaw = r != null ? r : new JSONObject();
        } catch (Throwable e) {
            uiRaw = new JSONObject();
        }
        if (!uiListening) {
            uiListening = true;
            try {
                customizer().addListener(() -> MAIN.post(() -> {
                    reloadUi();
                    if (workerPool != null) workerPool.broadcastConfigSync();
                }));
            } catch (Throwable e) {
                Log.w(TAG, "ui listener failed", e);
            }
        }
    }

    /** 给 BrowserAgent / 配置变化：热重载（重建工具栏、菜单、悬浮球、主题、注入 css），不丢标签、不重载网页 */
    void reloadUi() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(this::reloadUi);
            return;
        }
        loadUiCfg();
        applyConfig();
        for (Tab t : tabs) {
            if (t.web == null) continue;
            applySiteSettings(t, t.url, false);
            injectUiCss(t, t.web);
        }
    }

    private JSONObject uiObj(String key) {
        JSONObject o = uiCfg.optJSONObject(key);
        return o != null ? o : new JSONObject();
    }

    /** 用户在 ui.json 里明确写了这一项（否则沿用浏览器原来的样子） */
    private boolean uiHas(String key) {
        return uiRaw.has(key) && uiRaw.opt(key) != JSONObject.NULL;
    }

    private boolean uiHas(String key, String sub) {
        JSONObject o = uiRaw.optJSONObject(key);
        return o != null && o.has(sub) && o.opt(sub) != JSONObject.NULL;
    }

    private float fontScale() {
        double f = uiObj("theme").optDouble("fontScale", 1.0);
        if (Double.isNaN(f) || f <= 0) f = 1.0;
        return (float) Math.max(0.6, Math.min(2.0, f));
    }

    /** 主题模式：auto 跟随 Pi-Web 主题；light / dark 固定 */
    private ThemeManager.ThemePalette uiPalette() {
        String mode = uiObj("theme").optString("mode", "auto");
        if ("light".equalsIgnoreCase(mode)) return ThemeManager.PALETTE_LIGHT;
        if ("dark".equalsIgnoreCase(mode)) return ThemeManager.PALETTE_DARK;
        return ThemeManager.getEffectivePalette(ctx);
    }

    /** applyConfig 里调用：ui.json 的主题覆盖 + 重建工具栏 / 悬浮球 / 工具栏位置 */
    private void applyUiTheme() {
        JSONObject th = uiObj("theme");
        if (uiHas("theme") && uiRaw.optJSONObject("theme") != null && uiRaw.optJSONObject("theme").has("accent")) cAccent = color(th, "accent", cAccent);
        int tbBg = color(th, "toolbarBg", cBar);
        int tbFg = color(th, "toolbarFg", cFg);
        cBar = tbBg;
        cFg = tbFg;
        root.setBackgroundColor(cBar);
        bar.setBackgroundColor(cBar);
    }

    // ---- 内置功能表（图标 / 名称）。BrowserCustomizer.builtins() 有的以它为准

    private static final String[][] BUILTIN_FALLBACK = {
            {"back", "←", "后退"}, {"forward", "→", "前进"}, {"reload", "🔄", "刷新"}, {"refresh", "🔄", "刷新"}, {"stop", "✕", "停止"},
            {"home", "⌂", "首页"}, {"url", "🔍", "地址栏"}, {"tabs", "▢", "标签页"}, {"newtab", "＋", "新标签页"}, {"menu", "⋮", "更多"},
            {"history", "🕘", "历史"}, {"scripts", "🧩", "脚本管理"}, {"cookies", "🍪", "Cookie"}, {"containers", "🗂", "容器"},
            {"desktop", "🖥", "桌面版网站"}, {"zoom", "🔎", "页面缩放"}, {"find", "🔍", "页内查找"}, {"share", "↗", "分享"},
            {"fullscreen", "⛶", "全屏"}, {"translate", "译", "网页翻译"}, {"theme", "🎨", "切换主题"}, {"askpi", "🤖", "问 Pi"},
            {"terminal", ">_", "终端"}, {"external", "↗", "用其他浏览器打开"}, {"downloads", "⬇", "下载内容"}, {"settings", "⚙", "自定义"},
            {"close", "⏻", "关闭浏览器"},
            {"bookmark", "☆", "添加书签"}, {"bookmarks", "★", "书签"}, {"shortcut", "📲", "添加到桌面"}, {"copy", "⧉", "复制链接"}, {"pistop", "⏹", "停止 Pi 操作浏览器"},
            {"piweb", "🤖", "Pi 工作台（新标签打开）"}, {"workbench", "🌐", "回到 PiMet 工作台"}, {"ball", "●", "悬浮球"},
            {"newtab_container", "＋", "新标签页（选择容器）…"},
            {"open_newtab", "＋", "在新标签打开"}, {"open_background", "⇲", "在后台标签打开"}, {"copy_link", "⧉", "复制链接地址"},
            {"share_link", "↗", "分享链接"}, {"download", "⬇", "下载"}, {"copy_text", "⧉", "复制文字"}, {"search_text", "🔍", "搜索选中文字"}
    };

    private java.util.Map<String, String[]> builtinMap;

    private String[] builtinInfo(String id) {
        if (builtinMap == null) {
            builtinMap = new java.util.HashMap<>();
            for (String[] b : BUILTIN_FALLBACK) builtinMap.put(b[0], new String[]{b[1], b[2]});
            try {
                JSONArray a = BrowserCustomizer.builtins();
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null || o.optString("id", "").isEmpty()) continue;
                    String[] old = builtinMap.get(o.optString("id"));
                    builtinMap.put(o.optString("id"), new String[]{o.optString("icon", old != null ? old[0] : "●"), o.optString("label", old != null ? old[1] : o.optString("id"))});
                }
            } catch (Throwable ignored) {}
        }
        String[] r = builtinMap.get(id);
        return r != null ? r : new String[]{"●", id};
    }

    private String itemIcon(JSONObject it) {
        String icon = it.optString("icon", "");
        if (!icon.isEmpty()) return icon;
        return builtinInfo(it.optString("id", "")).length > 0 ? builtinInfo(it.optString("id", ""))[0] : "●";
    }

    private String itemLabel(JSONObject it) {
        String l = it.optString("label", "");
        if (!l.isEmpty()) return l;
        return builtinInfo(it.optString("id", ""))[1];
    }

    private static JSONArray ids(String... ids) {
        JSONArray a = new JSONArray();
        for (String id : ids) {
            try {
                a.put(new JSONObject().put("id", id));
            } catch (Throwable ignored) {}
        }
        return a;
    }

    /** where 指定位置的自定义面板，转换成 {id, icon, label, action:{type:panel}} */
    private List<JSONObject> panelItems(String where) {
        List<JSONObject> l = new ArrayList<>();
        JSONArray ps = uiCfg.optJSONArray("panels");
        for (int i = 0; ps != null && i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p == null || !where.equalsIgnoreCase(p.optString("where", "none"))) continue;
            try {
                l.add(new JSONObject().put("id", "panel:" + p.optString("id")).put("icon", p.optString("icon", "📄"))
                        .put("label", p.optString("title", p.optString("id")))
                        .put("action", new JSONObject().put("type", "panel").put("id", p.optString("id"))));
            } catch (Throwable ignored) {}
        }
        return l;
    }

    // ---- 工具栏

    private JSONArray toolbarItems() {
        JSONArray a = uiCfg.optJSONArray("toolbar");
        if (a == null) a = ids("home", "url", "scripts", "tabs", "menu");
        return a;
    }

    /** 按配置重建工具栏（按钮顺序/隐藏/自定义按钮、上下位置、字体缩放） */
    private void renderToolbar() {
        barOrigLp.clear();
        lastBarHome = null;
        float fs = fontScale();
        bar.removeAllViews();
        boolean hasMenu = false;
        List<JSONObject> items = new ArrayList<>();
        JSONArray a = toolbarItems();
        for (int i = 0; i < a.length(); i++) {
            JSONObject it = a.optJSONObject(i);
            if (it == null) {
                String s = a.optString(i, "");
                if (s.isEmpty()) continue;
                try { it = new JSONObject().put("id", s); } catch (Throwable e) { continue; }
            }
            if (it.optBoolean("hidden", false)) continue;
            items.add(it);
        }
        items.addAll(panelItems("toolbar"));
        for (JSONObject it : items) {
            String id = it.optString("id", "");
            if (!it.has("action") && ("menu".equals(id) || "settings".equals(id))) hasMenu = true;
        }
        // 兜底：工具栏和悬浮球都没有「⋮ / ⚙」时补一个菜单键，避免改坏后再也打不开「⚙ 自定义」
        if (!hasMenu && !ballHas("menu") && !ballHas("settings")) {
            try { items.add(new JSONObject().put("id", "menu")); } catch (Throwable ignored) {}
        }
        for (JSONObject it : items) {
            String id = it.optString("id", "");
            boolean custom = it.has("action");
            View v;
            LinearLayout.LayoutParams lp;
            if (!custom && "url".equals(id)) {
                v = urlPill;
                lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
                lp.setMargins(dp(2), 0, dp(4), 0);
                urlField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15 * fs);
                siteIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13 * fs);
            } else if (!custom && "tabs".equals(id)) {
                v = tabCountBtn;
                tabCountBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12 * fs);
                lp = new LinearLayout.LayoutParams(dp(24 * fs), dp(24 * fs));
                lp.setMargins(dp(10), 0, dp(10), 0);
            } else if (!custom && "menu".equals(id)) {
                v = menuBtn;
                menuBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22 * fs);
                lp = new LinearLayout.LayoutParams(dp(40), dp(44));
            } else if (!custom && "home".equals(id)) {
                v = homeBtn;
                homeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22 * fs);
                lp = new LinearLayout.LayoutParams(dp(44), dp(44));
            } else if (!custom && "scripts".equals(id)) {
                v = extBtn;
                extBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18 * fs);
                lp = new LinearLayout.LayoutParams(dp(40), dp(44));
            } else {
                TextView b = barBtn(itemIcon(it), itemLabel(it), Math.round(18 * fs));
                b.setTextColor(cFg);
                final JSONObject fit = it;
                b.setOnClickListener(x -> runItem(fit, active, x, null));
                b.setOnLongClickListener(x -> {
                    Toast.makeText(ctx, itemLabel(fit), Toast.LENGTH_SHORT).show();
                    return true;
                });
                v = b;
                lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
                b.setMinWidth(dp(36));
                b.setPadding(dp(6), 0, dp(6), 0);
            }
            if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
            bar.addView(v, lp);
        }
        // 位置：top | bottom
        boolean bottom = isBottomToolbar();
        int idx = column.indexOfChild(bar);
        int want = bottom ? column.getChildCount() - 1 : 0;
        if (idx >= 0 && idx != want) {
            ViewGroup.LayoutParams blp = bar.getLayoutParams();
            column.removeView(bar);
            if (bottom) column.addView(bar, blp);
            else column.addView(bar, 0, blp);
        }
        lastBarHome = null;
        syncUrlPill();
    }

    // ---- 悬浮球

    private JSONObject ballCfg() {
        return uiObj("ball");
    }

    private JSONArray ballItemsCfg() {
        JSONArray a = ballCfg().optJSONArray("items");
        if (a == null) a = ids("fullscreen", "refresh", "translate", "theme", "workbench", "terminal", "external", "zoom", "askpi");
        return a;
    }

    private boolean ballHas(String id) {
        JSONArray a = ballItemsCfg();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            String x = o != null ? (o.has("action") ? "" : o.optString("id", "")) : a.optString(i, "");
            if (id.equals(x)) return ballEnabled();
        }
        return false;
    }

    /** 按配置重建悬浮球菜单（items / size / opacity） */
    private void renderBall() {
        if (ballMenu == null) return;
        ballMenu.removeAllViews();
        ballFull = null;
        ballTranslate = null;
        ballZoom = null;
        List<JSONObject> items = new ArrayList<>();
        JSONArray a = ballItemsCfg();
        for (int i = 0; i < a.length(); i++) {
            JSONObject it = a.optJSONObject(i);
            if (it == null) {
                String s = a.optString(i, "");
                if (s.isEmpty()) continue;
                try { it = new JSONObject().put("id", s); } catch (Throwable e) { continue; }
            }
            if (!it.optBoolean("hidden", false)) items.add(it);
        }
        items.addAll(panelItems("ball"));
        for (JSONObject it : items) {
            String id = it.has("action") ? "" : it.optString("id", "");
            final JSONObject fit = it;
            switch (id) {
                case "fullscreen":
                    ballFull = ballItem("⛶", "全屏", 16, v -> { setFullscreen(!fullscreen); collapseBall(); });
                    break;
                case "refresh":
                case "reload":
                    ballItem("🔄", "刷新", 14, v -> { reload(); collapseBall(); });
                    break;
                case "translate":
                    ballTranslate = ballItem("译", "网页翻译（长按选翻译引擎）", 14, v -> toggleTranslate());
                    ballTranslate.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    ballTranslate.setOnLongClickListener(v -> {
                        if (host != null && web != null) {
                            try { com.xm486.pimet.translation.WebTranslationDialog.show(host, web); } catch (Throwable ignored) {}
                        }
                        return true;
                    });
                    break;
                case "theme":
                    ballItem("🎨", "切换主题", 14, v -> cycleTheme());
                    break;
                case "workbench": {
                    TextView wb = ballItem("🌐", "回到工作台（浏览器留在后台）", 17, v -> { collapseBall(); openWorkbench(); });
                    wb.setTag("keep:#D2A8FF");
                    break;
                }
                case "terminal": {
                    TextView term = ballItem(">_", "终端", 14, v -> { collapseBall(); runBuiltin("terminal", active, v, null); });
                    term.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
                    term.setTag("keep:#3FB950");
                    break;
                }
                case "zoom":
                    ballZoom = ballItem("100%", "页面缩放（也可双指缩放）", 11, v -> { collapseBall(); showZoomDialog(); });
                    break;
                case "askpi":
                    ballItem("🤖", "问 Pi", 15, this::showAskPiMenu);
                    break;
                default: {
                    String icon = itemIcon(fit);
                    ballItem(icon, itemLabel(fit), icon.codePointCount(0, icon.length()) > 2 ? 11 : 15, v -> {
                        boolean popup = !fit.has("action") && ("menu".equals(fit.optString("id")) || "askpi".equals(fit.optString("id")) || "newtab_container".equals(fit.optString("id")));
                        if (!popup) collapseBall();
                        runItem(fit, active, v, null);
                    });
                    break;
                }
            }
        }
        TextView close = ballItem("✕", "收起", 12, v -> collapseBall());
        close.getLayoutParams().height = dp(28);
        close.setTag("muted");
        JSONObject bc = ballCfg();
        int size = Math.max(28, Math.min(80, bc.optInt("size", 44)));
        ViewGroup.LayoutParams blp = ball.getLayoutParams();
        if (blp != null && blp.width != dp(size)) {
            blp.width = dp(size);
            blp.height = dp(size);
            ball.setLayoutParams(blp);
        }
        ball.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * size / 44f);
        double op = bc.optDouble("opacity", 1.0);
        if (Double.isNaN(op)) op = 1.0;
        ballWrap.setAlpha((float) Math.max(0.2, Math.min(1.0, op)));
        ballWrap.setVisibility(ballEnabled() ? View.VISIBLE : View.GONE);
    }

    private boolean ballSnap() {
        return ballCfg().optBoolean("snapEdge", true);
    }

    // ---- 菜单（⋮）

    private JSONArray menuItemsCfg() {
        JSONArray a = uiCfg.optJSONArray("menu");
        if (a == null) {
            a = ids("newtab", "newtab_container", "history", "forward", "reload", "downloads", "translate", "zoom", "desktop",
                    "fullscreen", "share", "copy", "external", "settings");
        }
        return a;
    }

    private void showUiMenu(View anchor) {
        if (host == null) return;
        hideIme();
        PopupMenu pm = new PopupMenu(host, anchor != null ? anchor : menuBtn);
        Menu m = pm.getMenu();
        String u = currentUrl();
        boolean webPage = u.startsWith("http://") || u.startsWith("https://");
        final List<JSONObject> byItem = new ArrayList<>();
        int group = 0;
        boolean hasSettings = false;
        List<JSONObject> items = new ArrayList<>();
        JSONArray a = menuItemsCfg();
        for (int i = 0; i < a.length(); i++) {
            JSONObject it = a.optJSONObject(i);
            if (it == null) {
                String s = a.optString(i, "");
                if (s.isEmpty()) continue;
                try { it = new JSONObject().put("id", s); } catch (Throwable e) { continue; }
            }
            items.add(it);
        }
        items.addAll(panelItems("menu"));
        java.util.Set<String> present = new java.util.HashSet<>();
        for (JSONObject it : items) if (!it.has("action")) present.add(BrowserCustomizer.builtinId(it.optString("id")) != null ? BrowserCustomizer.builtinId(it.optString("id")) : it.optString("id"));
        hasSettings = present.contains("settings");
        // ui.json 表达不了的 App 自带功能（书签、Pi 开关、回工作台…）固定放在最后一组，配置怎么改都还能找到
        try {
            items.add(new JSONObject().put("type", "divider"));
            for (String x : new String[]{"bookmark", "shortcut", "bookmarks", "pistop", "piweb", "workbench", "ball"}) {
                if (!present.contains(x)) items.add(new JSONObject().put("id", x));
            }
            if (!hasSettings) items.add(new JSONObject().put("id", "settings"));
            if (!present.contains("close")) items.add(new JSONObject().put("id", "close"));
        } catch (Throwable ignored) {}
        for (JSONObject it : items) {
            if ("divider".equals(it.optString("type")) || "divider".equals(it.optString("id"))) {
                group++;
                continue;
            }
            if (it.optBoolean("hidden", false)) continue;
            String id = it.has("action") ? "" : it.optString("id", "");
            String title = it.optString("label", "");
            boolean enabled = true, checkable = false, checked = false, show = true;
            switch (id) {
                case "forward": enabled = activeRemoteTabId != null ? remoteCanGoForward() : (web != null && web.canGoForward()); break;
                case "back": enabled = activeRemoteTabId != null ? remoteCanGoBack() : (web != null && web.canGoBack()); break;
                case "bookmark": title = isBookmarked(u) ? "★ 已加书签（点按移除）" : "☆ 添加书签"; enabled = webPage; break;
                case "shortcut": enabled = activeRemoteTabId == null && !isHomeUrl(u) && (webPage || isTrusted(u)); break;
                case "translate": if (title.isEmpty()) title = active != null && active.translated ? "显示原文" : "网页翻译"; enabled = activeRemoteTabId == null && webPage; break;
                case "desktop": checkable = true; checked = activeRemoteTabId != null ? remoteIsDesktop() : (active != null && active.desktop); break;
                case "fullscreen": if (title.isEmpty()) title = fullscreen ? "退出全屏" : "全屏"; break;
                case "share": case "copy": case "external": enabled = webPage; break;
                case "pistop": show = lastPiTab() != null || sPiStopped; if (title.isEmpty()) title = sPiStopped ? "▶ 允许 Pi 操作浏览器" : "⏹ 停止 Pi 操作浏览器"; break;
                case "ball": checkable = true; checked = ballEnabled(); break;
                case "newtab_container": show = ContainerManager.supported(); break;
                case "settings": if (title.isEmpty()) title = "⚙ 自定义"; break;
                case "history": if (title.isEmpty()) title = "🕘 历史"; break;
                case "close": if (title.isEmpty()) title = "关闭浏览器（关闭全部标签）"; break;
                default: break;
            }
            if (!show) continue;
            if (title.isEmpty()) {
                String icon = it.optString("icon", "");
                title = (icon.isEmpty() ? "" : icon + "  ") + itemLabel(it);
            }
            android.view.MenuItem mi = m.add(group, byItem.size() + 1, byItem.size(), title);
            mi.setEnabled(enabled);
            if (checkable) mi.setCheckable(true).setChecked(checked);
            byItem.add(it);
        }
        try {
            if (Build.VERSION.SDK_INT >= 28) m.setGroupDividerEnabled(true);
        } catch (Throwable ignored) {}
        final View anc = anchor != null ? anchor : menuBtn;
        pm.setOnMenuItemClickListener(item -> {
            int i = item.getItemId() - 1;
            if (i < 0 || i >= byItem.size()) return false;
            runItem(byItem.get(i), active, anc, null);
            return true;
        });
        pm.show();
    }

    // ---- 执行：按钮项、action、内置功能

    /** 一个工具栏/菜单/悬浮球/长按项：有 action 就执行 action，否则按内置 id */
    private void runItem(JSONObject it, Tab tab, View anchor, java.util.Map<String, String> vars) {
        if (it == null) return;
        JSONObject act = it.optJSONObject("action");
        if (act != null) runAction(act, tab, anchor, vars);
        else runBuiltin(it.optString("id", ""), tab, anchor, vars);
    }

    /** 给 BrowserAgent：在当前标签执行一个 action */
    void runAction(JSONObject action) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> runAction(action, active));
            return;
        }
        runAction(action, active);
    }

    /** action 执行器（type 见 docs/browser-v24.md）。主线程 */
    void runAction(JSONObject action, Tab tab) {
        runAction(action, tab, null, null);
    }

    private void runAction(JSONObject a, Tab tab, View anchor, java.util.Map<String, String> vars) {
        if (a == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> runAction(a, tab, anchor, vars));
            return;
        }
        Tab t = tab != null && !tab.closed ? tab : active;
        String type = a.optString("type", "builtin");
        try {
            switch (type) {
                case "builtin":
                    runBuiltin(a.optString("id", ""), t, anchor, vars);
                    break;
                case "open": {
                    String raw = a.optString("url", "");
                    if (raw.isEmpty()) return;
                    String url = resolveOpenUrl(fill(raw, t, vars, true));
                    if (a.optBoolean("newTab", false)) {
                        userTouched = true;
                        Tab nt = newTab(false, resolveContainer(a.optString("container", "same"), t));
                        if (!a.optBoolean("background", false)) switchTo(nt);
                        else refreshTabs();
                        loadIn(nt, url, true);
                    } else {
                        loadIn(t, url, true);
                    }
                    break;
                }
                case "js": {
                    String code = fill(a.optString("code", ""), t, vars, false);
                    if (t != null && t.web != null && !code.isEmpty()) {
                        t.web.evaluateJavascript("(function(){try{" + code + "\n}catch(e){console.error('PiMet action: '+e)}})()", null);
                    }
                    break;
                }
                case "script_menu":
                    runMenuCommand(t, a.optString("script", ""), a.optString("cmd", ""));
                    break;
                case "panel":
                    openPanel(a.optString("id", ""));
                    break;
                case "askpi": {
                    String prompt = fill(a.optString("prompt", "{url}"), t, vars, false, "link", "src");
                    askPiAbout(prompt, a.optBoolean("send", true));
                    break;
                }
                case "seq": {
                    JSONArray steps = a.optJSONArray("steps");
                    for (int i = 0; steps != null && i < steps.length(); i++) runAction(steps.optJSONObject(i), t, anchor, vars);
                    break;
                }
                default:
                    Toast.makeText(ctx, "未知的 action 类型：" + type, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable e) {
            Log.w(TAG, "runAction failed", e);
            Toast.makeText(ctx, "执行失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** 占位符 {url}{title}{selection}{link}{src}；encode=true 时（open 的 url）非整段替换的值做 URL 编码 */
    private String fill(String s, Tab t, java.util.Map<String, String> vars, boolean encode, String... only) {
        if (s == null || s.indexOf('{') < 0) return s == null ? "" : s;
        java.util.Map<String, String> v = new java.util.HashMap<>();
        if (t != null) {
            v.put("url", t.url);
            v.put("title", t.title);
        }
        if (vars != null) v.putAll(vars);
        java.util.Set<String> keys = new java.util.HashSet<>(only.length > 0 ? Arrays.asList(only) : v.keySet());
        String out = s;
        for (String k : keys) {
            String val = v.get(k);
            if (val == null) continue;
            String ph = "{" + k + "}";
            if (!out.contains(ph)) continue;
            if (encode && !out.equals(ph)) val = Uri.encode(val);
            else if (!encode && only.length == 0) {
                // js 代码里：按字符串内容转义（放在引号里用）
                String q = JSONObject.quote(val);
                val = q.substring(1, q.length() - 1);
            }
            out = out.replace(ph, val);
        }
        return out;
    }

    private String resolveOpenUrl(String open) {
        if (open.startsWith("pages/") || open.startsWith("panels/") || open.startsWith("home.html")) return "file://" + new File(appsDir, open).getAbsolutePath();
        if (open.startsWith("/")) return "file://" + new File(ProotManager.getRootfsDir(ctx), open.substring(1)).getAbsolutePath();
        if (open.startsWith("about:") || open.startsWith("file://")) return open;
        return normalizeUrl(open);
    }

    /** 内置功能（toolbar / menu / ball / longPress / gestures 共用） */
    void runBuiltin(String id, Tab t, View anchor, java.util.Map<String, String> vars) {
        if (t == null) t = active;
        String link = vars != null ? vars.get("link") : null;
        String src = vars != null ? vars.get("src") : null;
        String target = link != null && !link.isEmpty() ? link : src;
        switch (id == null ? "" : id) {
            case "back":
                if (activeRemoteTabId != null) goBack();
                else if (t != null && t.web != null && t.web.canGoBack()) goBackTab(t);
                else goBack();
                break;
            case "forward": goForwardTab(t); break;
            case "reload": case "refresh": reload(); break;
            case "stop": if (t != null && t.web != null) t.web.stopLoading(); break;
            case "home":
                if (activeRemoteTabId != null) sendRemoteHome(activeRemoteTabId);
                else goHome(t, true);
                break;
            case "url":
                // 工具栏里没有地址栏（ui.json 去掉了 url）时用对话框输入；urlField 本身总在 urlPill 里
                if (urlPill.getParent() == null) { askUrl(); break; }
                revealUrlPill();
                urlField.requestFocus();
                InputMethodManager imm = (InputMethodManager) vctx.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(urlField, 0);
                break;
            case "tabs": openSwitcher(); break;
            case "newtab": newUserTab(); break;
            case "newtab_container": showNewTabChooser(anchor != null ? anchor : menuBtn); break;
            case "menu": showUiMenu(anchor); break;
            case "history": showHistoryPanel(); break;
            case "scripts": showScriptsPanel(); break;
            case "cookies": showCookiesPanel(); break;
            case "containers": showContainersPanel(); break;
            case "desktop": toggleDesktop(); break;
            case "zoom": showZoomDialog(); break;
            case "find": showFindDialog(); break;
            case "share": shareUrl(); break;
            case "fullscreen": setFullscreen(!fullscreen); break;
            case "translate": toggleTranslate(); break;
            case "theme": cycleTheme(); break;
            case "askpi": showAskPiMenu(anchor != null ? anchor : menuBtn); break;
            case "terminal":
                try { com.xm486.pimet.terminal.FloatingTerminal.toggle(ctx); } catch (Throwable e) {
                    Toast.makeText(ctx, "打开终端失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
                break;
            case "external": openInOtherBrowser(); break;
            case "downloads": showDownloads(); break;
            case "settings": showCustomizeDialog(); break;
            case "close": confirmCloseBrowser(); break;
            case "bookmark": toggleBookmark(); break;
            case "bookmarks": showBookmarks(); break;
            case "shortcut": addToDesktopDialog(); break;
            case "copy": copyUrl(); break;
            case "pistop": togglePiStopped(); break;
            case "piweb": openPiWebTab(); break;
            case "workbench": openWorkbench(); break;
            case "ball": setBallEnabled(!ballEnabled()); break;
            // 长按菜单专用（{link}/{src}/{selection}）
            case "open_newtab":
            case "open_background": {
                if (target == null || target.isEmpty()) break;
                userTouched = true;
                Tab nt = newTab(false, t != null ? t.container : resolveContainer("new", null));
                loadIn(nt, target, true);
                if ("open_newtab".equals(id)) switchTo(nt);
                else {
                    refreshTabs();
                    Toast.makeText(ctx, "已在后台标签打开", Toast.LENGTH_SHORT).show();
                }
                break;
            }
            case "copy_link":
            case "copy_text": {
                String s = "copy_text".equals(id) ? (vars != null ? vars.get("selection") : null) : target;
                if (s == null || s.isEmpty()) break;
                ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("text", s));
                Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show();
                break;
            }
            case "share_link": {
                if (target == null || target.isEmpty() || host == null) break;
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, target);
                try { host.startActivity(Intent.createChooser(i, "分享")); } catch (Throwable ignored) {}
                break;
            }
            case "download": {
                if (target == null || target.isEmpty() || t == null) break;
                startDownload(t, target, t.web != null ? t.web.getSettings().getUserAgentString() : null, null, null);
                break;
            }
            case "search_text": {
                String q = vars != null ? vars.get("selection") : null;
                if (q == null || q.trim().isEmpty()) break;
                userTouched = true;
                Tab nt = newTab(false, t != null ? t.container : resolveContainer("new", null));
                switchTo(nt);
                loadIn(nt, searchUrl(q.trim()), true);
                break;
            }
            default:
                if (id != null && id.startsWith("panel:")) {
                    openPanel(id.substring(6));
                    break;
                }
                Toast.makeText(ctx, "未知的内置功能：" + id, Toast.LENGTH_SHORT).show();
        }
    }

    /** 地址栏不在工具栏上时：弹一个输入框 */
    private void askUrl() {
        if (host == null) return;
        EditText et = new EditText(host);
        et.setSingleLine(true);
        et.setHint("搜索或输入网址");
        String u = currentUrl();
        if (!u.isEmpty() && !isLauncher(u) && !u.startsWith("file://")) et.setText(u);
        et.selectAll();
        FrameLayout f = new FrameLayout(host);
        f.setPadding(dp(20), dp(8), dp(20), 0);
        f.addView(et);
        new AlertDialog.Builder(host).setTitle("打开").setView(f)
                .setPositiveButton("前往", (d, w) -> {
                    String s = normalizeUrl(et.getText().toString());
                    if (!s.isEmpty()) load(s);
                })
                .setNegativeButton("取消", null).show();
    }

    // ---- 搜索引擎

    /** 地址栏输入不是网址时：按 search.default 引擎搜索（%s 替换为编码后的关键词） */
    private String searchUrl(String q) {
        return searchTemplate().replace("%s", Uri.encode(q));
    }

    /** search.default 引擎的网址模板（含 %s）；找不到时退回 bing */
    private String searchTemplate() {
        JSONObject s = uiObj("search");
        String def = s.optString("default", "bing");
        JSONArray es = s.optJSONArray("engines");
        String tpl = null;
        for (int i = 0; es != null && i < es.length(); i++) {
            JSONObject e = es.optJSONObject(i);
            if (e != null && def.equals(e.optString("id"))) {
                tpl = e.optString("url", null);
                break;
            }
        }
        if (tpl == null || !tpl.contains("%s")) tpl = "https://www.bing.com/search?q=%s";
        return tpl;
    }

    // ---- 主页 / 新标签页

    /** home.homepage：用户在 ui.json 里写了才生效（空或 launcher = 内置启动页） */
    private String uiHomepage() {
        if (!uiHas("home", "homepage")) return null;
        String h = uiObj("home").optString("homepage", "").trim();
        if (h.isEmpty() || h.equalsIgnoreCase("launcher") || h.equalsIgnoreCase("default")) return null;
        return h;
    }

    /** 新标签页内容：homepage | blank | panel:<id> | <URL> */
    private void openNewTabContent(Tab t) {
        String nt = uiObj("home").optString("newTab", "homepage").trim();
        if (nt.isEmpty() || nt.equalsIgnoreCase("homepage")) {
            goHome(t, true);
        } else if (nt.equalsIgnoreCase("blank")) {
            t.web.loadUrl("about:blank");
        } else if (nt.startsWith("panel:")) {
            t.web.loadUrl("about:blank");
            openPanel(nt.substring(6));
        } else {
            loadIn(t, resolveOpenUrl(nt), true);
        }
    }

    private String uiDefaultContainer() {
        String c = uiObj("defaults").optString("container", "new");
        return c.isEmpty() ? "new" : c;
    }

    // ---- 站点规则 / 拦截 / 重定向

    /** 导航前：rules.redirect 改写；返回 null 表示不改 */
    private String uiRedirect(String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return null;
        try {
            String r = customizer().redirect(url);
            // 只允许改写成 http(s)：不让规则把网页导到 file:// / javascript: / intent: 等
            if (r == null || r.equals(url) || !(r.startsWith("http://") || r.startsWith("https://"))) return null;
            return r;
        } catch (Throwable e) {
            return null;
        }
    }

    private boolean uiBlocked(String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return false;
        BrowserCustomizer c = customizer;
        if (c == null) return false;
        try {
            return c.shouldBlock(url);
        } catch (Throwable e) {
            return false;
        }
    }

    private String desktopUa() {
        if (sDefaultUa == null) {
            try { sDefaultUa = WebSettings.getDefaultUserAgent(ctx); } catch (Throwable ignored) {}
        }
        String ver = "120.0.0.0";
        if (sDefaultUa != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Chrome/([\\d.]+)").matcher(sDefaultUa);
            if (m.find()) ver = m.group(1);
        }
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + ver + " Safari/537.36";
    }

    /** sites：UA / JS / 图片 / 深色（缩放在 zoomFor，css 在 injectUiCss）。离开匹配站点时恢复 */
    private void applySiteSettings(Tab t, String url, boolean beforeNav) {
        if (t == null || t.web == null) return;
        JSONObject s = null;
        if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
            try { s = customizer().siteSettings(url); } catch (Throwable ignored) {}
        }
        if (s == null) s = new JSONObject();
        t.site = s;
        WebSettings ws = t.web.getSettings();
        boolean js = s.optBoolean("js", true);
        if (ws.getJavaScriptEnabled() != js) ws.setJavaScriptEnabled(js);
        boolean img = s.optBoolean("images", true);
        if (ws.getLoadsImagesAutomatically() != img) {
            ws.setLoadsImagesAutomatically(img);
            ws.setBlockNetworkImage(!img);
        }
        boolean dark = s.optBoolean("darkMode", false);
        if (dark != t.siteDark) {
            t.siteDark = dark;
            setWebDark(ws, dark);
        }
        String mode = s.optString("ua", "");
        if (!mode.isEmpty()) {
            if (!mode.equals(t.siteUaMode)) {
                t.siteUaMode = mode;
                t.siteUa = mode;
                if ("desktop".equalsIgnoreCase(mode)) t.desktop = true;
                else if ("mobile".equalsIgnoreCase(mode)) t.desktop = false;
                applyContainerEnv(t, t.web, false);
                syncDesktopViewport(t, effectiveDesktopOf(t), !beforeNav && !isTabOnOffscreenHost(t));
            }
        } else if (t.siteUaMode != null) {
            t.siteUaMode = null;
            t.siteUa = null;
            t.desktop = (t.envProfile != null && t.envProfile.desktop)
                    || (uiObj("defaults").optBoolean("desktopMode", false) && !t.pi);
            applyContainerEnv(t, t.web, false);
            syncDesktopViewport(t, effectiveDesktopOf(t), !beforeNav && !isTabOnOffscreenHost(t));
        }
        if (!beforeNav) applyZoom(t);
    }

    private static void setWebDark(WebSettings ws, boolean on) {
        try {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.ALGORITHMIC_DARKENING)) {
                androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(ws, on);
            }
        } catch (Throwable ignored) {}
    }

    /** theme.css（所有页面）+ sites.css（匹配站点）注入；空时移除 */
    private void injectUiCss(Tab t, WebView view) {
        if (view == null) return;
        String css = uiObj("theme").optString("css", "");
        if (t != null && t.site != null) {
            String sc = t.site.optString("css", "");
            if (!sc.isEmpty()) css = css.isEmpty() ? sc : css + "\n" + sc;
        }
        String q = JSONObject.quote(css);
        view.evaluateJavascript("(function(){try{var c=" + q + ";var s=document.getElementById('__pimet_ui_css');"
                + "if(!c){if(s)s.remove();return;}"
                + "if(!s){s=document.createElement('style');s.id='__pimet_ui_css';(document.head||document.documentElement).appendChild(s);}"
                + "if(s.textContent!==c)s.textContent=c;}catch(e){}})()", null);
    }

    /** 用户标签打开网址前：站点指定了容器且与当前标签不同 → 在那个容器的新标签打开。返回 true 表示已处理 */
    private boolean openInSiteContainer(Tab t, String url) {
        if (t == null || t.pi || !ContainerManager.supported() || url == null) return false;
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return false;
        JSONObject s;
        try { s = customizer().siteSettings(url); } catch (Throwable e) { return false; }
        String c = s != null ? s.optString("container", "") : "";
        if (c.isEmpty()) return false;
        String name = resolveContainer(c, t);
        if (name == null || name.equals(t.container)) return false;
        try {
            ContainerEnv.Profile targetProf = containers().getProfile(name);
            if (targetProf != null && targetProf.hasActiveProxy()) {
                Toast.makeText(ctx, "容器「" + containerLabel(name) + "」已配置代理，主进程固定直连，请通过 Pi 代理标签打开", Toast.LENGTH_LONG).show();
                return true;
            }
        } catch (Throwable ignored) {}
        boolean blank = t.url == null || t.url.isEmpty() || isLauncher(t.url) || t.url.startsWith("about:");
        Tab nt = newTab(false, name);
        switchTo(nt);
        applySiteSettings(nt, url, true);
        loadUrlWithEnv(nt, url, null);
        if (blank && tabs.size() > 1) closeTab(t);
        Toast.makeText(ctx, "已在容器「" + containerLabel(name) + "」打开", Toast.LENGTH_SHORT).show();
        return true;
    }

    // ---- 手势 / 长按

    private GestureDetector makeSwipeDetector(Tab t) {
        return new GestureDetector(vctx, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null || e2.getPointerCount() > 1) return false;
                float dx = e2.getX() - e1.getX(), dy = e2.getY() - e1.getY();
                if (Math.abs(dx) < dp(120) || Math.abs(dx) < Math.abs(dy) * 2.5f || Math.abs(vx) < 1500) return false;
                JSONObject a = uiObj("gestures").optJSONObject(dx < 0 ? "swipeLeft" : "swipeRight");
                if (a == null || a.length() == 0) return false;
                MAIN.post(() -> runAction(a, t, null, null));
                return true;
            }
        });
    }

    private void onToolbarDoubleTap() {
        JSONObject a = uiObj("gestures").optJSONObject("doubleTapToolbar");
        if (a != null && a.length() > 0) runAction(a, active, null, null);
    }

    private JSONArray longPressItems(String kind) {
        JSONObject lp = uiObj("longPress");
        JSONArray a = lp.optJSONArray(kind);
        return a != null && a.length() > 0 ? a : null;
    }

    /** 网页长按：配置了 longPress.link/image/text 才接管，否则交给 WebView 默认处理 */
    private boolean onWebLongPress(Tab t, WebView w) {
        if (t == null || t.pi || host == null) return false;
        WebView.HitTestResult r = w.getHitTestResult();
        int type = r != null ? r.getType() : WebView.HitTestResult.UNKNOWN_TYPE;
        String extra = r != null ? r.getExtra() : null;
        java.util.Map<String, String> vars = new java.util.HashMap<>();
        String kind;
        if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
            kind = "link";
            vars.put("link", extra == null ? "" : extra);
        } else if (type == WebView.HitTestResult.IMAGE_TYPE || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
            kind = "image";
            vars.put("src", extra == null ? "" : extra);
            vars.put("link", extra == null ? "" : extra);
        } else if (type == WebView.HitTestResult.EDIT_TEXT_TYPE) {
            return false;
        } else {
            JSONArray items = longPressItems("text");
            if (items == null) return false;
            // 先让 WebView 选中文字，选好后再弹菜单
            MAIN.postDelayed(() -> {
                if (t.web == null) return;
                t.web.evaluateJavascript("String(window.getSelection?getSelection():'')", v -> {
                    String sel = "";
                    try { sel = String.valueOf(new org.json.JSONTokener(v).nextValue()); } catch (Throwable ignored) {}
                    if (sel.trim().isEmpty() || "null".equals(sel)) return;
                    java.util.Map<String, String> tv = new java.util.HashMap<>();
                    tv.put("selection", sel);
                    showItemsPopup(items, t, tv);
                });
            }, 650);
            return false;
        }
        JSONArray items = longPressItems(kind);
        if (items == null) return false;
        showItemsPopup(items, t, vars);
        return true;
    }

    private void showItemsPopup(JSONArray items, Tab t, java.util.Map<String, String> vars) {
        if (host == null) return;
        if (popupAnchor == null) {
            popupAnchor = new View(vctx);
            root.addView(popupAnchor, new FrameLayout.LayoutParams(1, 1));
        }
        int[] loc = new int[2];
        root.getLocationInWindow(loc);
        int[] wl = new int[2];
        if (t.web != null) t.web.getLocationInWindow(wl);
        popupAnchor.setX(wl[0] - loc[0] + lastTouchX);
        popupAnchor.setY(wl[1] - loc[1] + lastTouchY);
        PopupMenu pm = new PopupMenu(host, popupAnchor);
        Menu m = pm.getMenu();
        final List<JSONObject> list = new ArrayList<>();
        int group = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) {
                String s = items.optString(i, "");
                if (s.isEmpty()) continue;
                try { it = new JSONObject().put("id", s); } catch (Throwable e) { continue; }
            }
            if ("divider".equals(it.optString("type")) || "divider".equals(it.optString("id"))) { group++; continue; }
            if (it.optBoolean("hidden", false)) continue;
            String icon = it.optString("icon", "");
            m.add(group, list.size() + 1, list.size(), (icon.isEmpty() ? "" : icon + "  ") + itemLabel(it));
            list.add(it);
        }
        if (list.isEmpty()) return;
        pm.setOnMenuItemClickListener(mi -> {
            int i = mi.getItemId() - 1;
            if (i >= 0 && i < list.size()) runItem(list.get(i), t, popupAnchor, vars);
            return true;
        });
        pm.show();
    }

    // ---- 自定义面板（panels/<id>.html，浮层 WebView，window.PiMetUI）

    private FrameLayout panelLayer;
    private WebView panelWeb;
    private volatile boolean panelTrusted = false;

    /** 给 BrowserAgent：打开面板（主线程） */
    void openPanel(String id) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> openPanel(id));
            return;
        }
        if (id == null || id.isEmpty()) return;
        File f = null;
        try { f = customizer().panelFile(id); } catch (Throwable ignored) {}
        if (f == null || !f.isFile()) {
            Toast.makeText(ctx, "面板不存在：" + id, Toast.LENGTH_SHORT).show();
            return;
        }
        String title = id, icon = "📄";
        JSONArray ps = uiCfg.optJSONArray("panels");
        for (int i = 0; ps != null && i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p != null && id.equals(p.optString("id"))) {
                title = p.optString("title", id);
                icon = p.optString("icon", icon);
            }
        }
        closePanel(false);
        suspendRemoteSurfaceForOverlay();
        ThemeManager.ThemePalette pal = uiPalette();
        panelLayer = new FrameLayout(vctx);
        panelLayer.setBackgroundColor(Color.parseColor("#66000000"));
        panelLayer.setClickable(true);
        panelLayer.setOnClickListener(v -> closePanel());
        LinearLayout card = new LinearLayout(vctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(pal.bgPanel);
        bg.setCornerRadii(new float[]{dp(16), dp(16), dp(16), dp(16), 0, 0, 0, 0});
        card.setBackground(bg);
        card.setClipToOutline(true);
        LinearLayout head = new LinearLayout(vctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), 0, dp(4), 0);
        TextView tt = new TextView(vctx);
        tt.setText(icon + "  " + title);
        tt.setTextColor(pal.text);
        tt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tt.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tt.setSingleLine(true);
        tt.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(tt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView x = barBtn("✕", "关闭面板", 16);
        x.setTextColor(pal.text);
        x.setOnClickListener(v -> closePanel());
        head.addView(x, new LinearLayout.LayoutParams(dp(44), dp(44)));
        card.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        WebView w = new WebView(vctx);
        try {
            if (ContainerManager.supported()) containers().apply(w, ContainerManager.DEFAULT);
        } catch (Throwable ignored) {}
        WebSettings ws = w.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
        w.setBackgroundColor(pal.bgPanel);
        final String base = "file://" + appsDir.getAbsolutePath() + "/";
        w.addJavascriptInterface(new UiBridge(), "PiMetUI");
        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String u = req.getUrl().toString();
                if (u.startsWith(base)) return false;
                // 面板里点外部链接：在当前标签打开（面板本身不离开本地文件）
                if (u.startsWith("http://") || u.startsWith("https://")) MAIN.post(() -> load(u));
                return true;
            }

            /** 面板只能加载 appsDir 下的本地文件：PiMetUI 会注入到所有 frame，远程 iframe / 脚本不能借它调接口 */
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                String u = req != null && req.getUrl() != null ? req.getUrl().toString() : "";
                if (u.startsWith(base) && !u.contains("/../") || u.startsWith("data:") || u.startsWith("blob:") || u.startsWith("about:")) return null;
                return new android.webkit.WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", null, new java.io.ByteArrayInputStream(new byte[0]));
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                panelTrusted = url != null && url.startsWith(base);
            }
        });
        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
                if (cm != null) Log.d(TAG, "panel " + cm.message());
                return true;
            }
        });
        card.addView(w, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        panelWeb = w;
        DisplayMetrics dm = vctx.getResources().getDisplayMetrics();
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (dm.heightPixels * 0.72f), Gravity.BOTTOM);
        panelLayer.addView(card, clp);
        root.addView(panelLayer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (ballWrap != null) ballWrap.bringToFront();
        panelTrusted = true;
        w.loadUrl("file://" + f.getAbsolutePath());
    }

    private void closePanel() {
        closePanel(true);
    }

    private void closePanel(boolean resumeRemote) {
        panelTrusted = false;
        if (panelWeb != null) {
            try {
                panelWeb.stopLoading();
                panelWeb.destroy();
            } catch (Throwable ignored) {}
            panelWeb = null;
        }
        if (panelLayer != null) {
            try { root.removeView(panelLayer); } catch (Throwable ignored) {}
            panelLayer = null;
        }
        if (resumeRemote) {
            resumeRemoteSurfaceIfNeeded();
        }
    }

    private boolean panelOpen() {
        return panelLayer != null;
    }

    /** window.PiMetUI：只给本地面板页面用 */
    private final class UiBridge {
        @JavascriptInterface
        public void runAction(String json) {
            if (!panelTrusted || json == null) return;
            MAIN.post(() -> {
                try {
                    FloatingApps.this.runAction(new JSONObject(json), active, null, null);
                } catch (Throwable e) {
                    Toast.makeText(ctx, "面板 action 格式错误：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void openUrl(String url) {
            if (!panelTrusted || url == null || url.trim().isEmpty()) return;
            MAIN.post(() -> load(resolveOpenUrl(url.trim())));
        }

        @JavascriptInterface
        public void close() {
            MAIN.post(FloatingApps.this::closePanel);
        }

        @JavascriptInterface
        public String getConfig() {
            if (!panelTrusted) return "{}";
            try {
                return customizer().config().toString();
            } catch (Throwable e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public String currentPage() {
            if (!panelTrusted) return "{}";
            Tab t = active;
            try {
                return new JSONObject().put("url", t != null ? t.url : "").put("title", t != null ? t.title : "").toString();
            } catch (Throwable e) {
                return "{}";
            }
        }
    }

    // ---- 🍪 / 🗂 / 页内查找

    private void showCookiesPanel() {
        if (host == null || active == null) return;
        final Tab t = active;
        final String u = t.url;
        if (!(u.startsWith("http://") || u.startsWith("https://"))) {
            Toast.makeText(ctx, "当前页面没有 Cookie", Toast.LENGTH_SHORT).show();
            return;
        }
        String ck = tabCookies(t).getCookie(u);
        String[] parts = ck == null || ck.isEmpty() ? new String[0] : ck.split(";\\s*");
        StringBuilder sb = new StringBuilder();
        sb.append("容器：").append(containerLabel(t.container)).append('\n').append(Uri.parse(u).getHost()).append("\n\n");
        if (parts.length == 0) sb.append("（没有 Cookie）");
        for (String p : parts) sb.append(p.length() > 120 ? p.substring(0, 120) + "…" : p).append('\n');
        new AlertDialog.Builder(host).setTitle("🍪 Cookie（" + parts.length + "）").setMessage(sb.toString().trim())
                .setPositiveButton("关闭", null)
                .setNeutralButton("清除此站", (d, w) -> {
                    android.webkit.CookieManager cm = tabCookies(t);
                    String h = Uri.parse(u).getHost();
                    for (String p : parts) {
                        int eq = p.indexOf('=');
                        String name = eq > 0 ? p.substring(0, eq).trim() : p.trim();
                        if (name.isEmpty()) continue;
                        cm.setCookie(u, name + "=; Max-Age=0; Path=/");
                        if (h != null) {
                            cm.setCookie(u, name + "=; Max-Age=0; Path=/; Domain=" + h);
                            int dot = h.indexOf('.');
                            if (dot > 0 && h.indexOf('.', dot + 1) > 0) cm.setCookie(u, name + "=; Max-Age=0; Path=/; Domain=" + h.substring(dot));
                        }
                    }
                    cm.flush();
                    Toast.makeText(ctx, "已清除此站 Cookie，刷新后生效", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("清除此容器全部", (d, w) -> {
                    android.webkit.CookieManager cm = tabCookies(t);
                    cm.removeAllCookies(null);
                    cm.flush();
                    Toast.makeText(ctx, "已清除容器「" + containerLabel(t.container) + "」的全部 Cookie", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void showContainersPanel() {
        if (host == null) return;
        if (!ContainerManager.supported()) {
            Toast.makeText(ctx, "系统 WebView 版本太低，标签共享登录", Toast.LENGTH_SHORT).show();
            return;
        }
        JSONArray list;
        try { list = containers().list(); } catch (Throwable e) { list = new JSONArray(); }
        final List<String> names = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject c = list.optJSONObject(i);
            if (c == null) continue;
            String n = c.optString("name");
            int cnt = mainTabCountForContainer(n) + (workerPool != null ? workerPool.remoteTabCountForContainer(n) : 0);
            names.add(n);
            ContainerEnv.Profile ep = ContainerEnv.NATIVE;
            try { ep = containers().getProfile(n); } catch (Throwable ignored) {}
            String ps = ep.hasActiveProxy() ? ep.proxy.summary() : "直连";
            labels.add(containerLabel(n) + (c.optBoolean("keep") && !ContainerManager.DEFAULT.equals(n) ? " 📌" : "")
                    + "\n  " + ContainerEnv.summary(ep) + " · 🌐 " + ps + " · " + cnt + " 个标签");
        }
        names.add("new");
        labels.add("＋ 新容器");
        AlertDialog dlg = new AlertDialog.Builder(host)
                .setTitle("🗂 容器（主进程直连 · 独立代理进程池）")
                .setItems(labels.toArray(new String[0]), (d, w) -> {
                    String cname = names.get(w);
                    if (!"new".equals(cname) && !ContainerManager.DEFAULT.equals(cname)) {
                        try {
                            if (containers().getProfile(cname).hasActiveProxy()) {
                                showContainerActionsMenu(cname);
                                return;
                            }
                        } catch (Throwable ignored) {}
                    }
                    newUserTabIn(cname);
                })
                .setNegativeButton("关闭", null)
                .create();
        dlg.setOnShowListener(di -> {
            android.widget.ListView lv = dlg.getListView();
            if (lv != null) {
                lv.setOnItemLongClickListener((parent, view, pos, id) -> {
                    if (pos < 0 || pos >= names.size()) return false;
                    String cname = names.get(pos);
                    if ("new".equals(cname)) return false;
                    if (ContainerManager.DEFAULT.equals(cname)) {
                        Toast.makeText(ctx, "Default 共享容器始终为直连，禁止配置代理或环境", Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    dlg.dismiss();
                    showContainerActionsMenu(cname);
                    return true;
                });
            }
        });
        dlg.show();
    }

    private void showContainerActionsMenu(String containerName) {
        if (host == null || containerName == null || ContainerManager.DEFAULT.equals(containerName)) return;
        ContainerEnv.Profile ep = ContainerEnv.NATIVE;
        try { ep = containers().getProfile(containerName); } catch (Throwable ignored) {}
        String ps = ep.hasActiveProxy() ? ep.proxy.summary() : "直连";
        boolean keep = containerKeep(containerName);
        String[] items = {
                "🌐 代理设置：" + ps,
                "🛡 伪装环境：" + ContainerEnv.summary(ep),
                (keep ? "📌 取消保留容器" : "📌 保留此容器"),
                "✏️ 重命名容器…",
                "＋ 在此容器开新标签"
        };
        new AlertDialog.Builder(host)
                .setTitle("容器 · " + containerLabel(containerName))
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: showContainerProxyDialog(containerName); break;
                        case 1: showContainerProfileDialog(containerName); break;
                        case 2:
                            try { containers().setKeep(containerName, !keep); } catch (Throwable ignored) {}
                            Toast.makeText(ctx, !keep ? "已保留容器" : "已取消保留", Toast.LENGTH_SHORT).show();
                            break;
                        case 3: renameContainer(containerName); break;
                        case 4: newUserTabIn(containerName); break;
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showFindDialog() {
        if (host == null || web == null) return;
        final WebView w = web;
        EditText et = new EditText(host);
        et.setSingleLine(true);
        et.setHint("在页面中查找");
        FrameLayout f = new FrameLayout(host);
        f.setPadding(dp(20), dp(8), dp(20), 0);
        f.addView(et);
        AlertDialog d = new AlertDialog.Builder(host).setTitle("页内查找").setView(f)
                .setPositiveButton("下一个", null).setNeutralButton("上一个", null).setNegativeButton("关闭", null).create();
        w.setFindListener((activeIdx, count, done) -> {
            if (done) d.setTitle(count == 0 ? "页内查找：没有找到" : "页内查找：" + (activeIdx + 1) + " / " + count);
        });
        final String[] last = {""};
        d.setOnShowListener(x -> {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String q = et.getText().toString();
                if (q.isEmpty()) return;
                if (!q.equals(last[0])) { last[0] = q; w.findAllAsync(q); } else w.findNext(true);
            });
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                String q = et.getText().toString();
                if (q.isEmpty()) return;
                if (!q.equals(last[0])) { last[0] = q; w.findAllAsync(q); } else w.findNext(false);
            });
        });
        d.setOnDismissListener(x -> {
            try {
                w.clearMatches();
                w.setFindListener(null);
            } catch (Throwable ignored) {}
        });
        d.show();
    }

    // ---- ⚙ 自定义（编辑 ui.json）

    private void showCustomizeDialog() {
        if (host == null || host.isFinishing()) return;
        hideIme();
        ThemeManager.ThemePalette p = ThemeManager.getEffectivePalette(host);
        LinearLayout col = new LinearLayout(host);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(14), dp(18), dp(12));
        col.addView(panelText("⚙ 自定义浏览器（ui.json）", 16f, p.text, true));
        col.addView(panelText("改完保存立即生效；也可以直接让 Pi 用 browser 工具改。", 12f, p.textDim, false));
        EditText et = new EditText(host);
        et.setTypeface(android.graphics.Typeface.MONOSPACE);
        et.setTextSize(12f);
        et.setTextColor(p.text);
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setHorizontallyScrolling(true);
        et.setBackground(ThemeManager.createSecondaryButtonDrawable(host, p, 8f));
        et.setPadding(dp(10), dp(8), dp(10), dp(8));
        String txt;
        try {
            JSONObject r = customizer().raw();
            txt = (r != null ? r : new JSONObject()).toString(2);
        } catch (Throwable e) {
            txt = "{}";
        }
        et.setText(txt);
        DisplayMetrics dm = host.getResources().getDisplayMetrics();
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (dm.heightPixels * 0.55f));
        elp.topMargin = dp(10);
        col.addView(et, elp);
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(10);
        col.addView(row, rlp);
        FrameLayout wrap = new FrameLayout(host);
        wrap.setBackground(ThemeManager.createCardDrawable(host, p, 14f));
        wrap.addView(col);
        AlertDialog d = new AlertDialog.Builder(host).setView(wrap).create();
        View.OnClickListener save = v -> {
            JSONObject cfg;
            try {
                cfg = new JSONObject(et.getText().toString());
            } catch (Throwable e) {
                new AlertDialog.Builder(host).setTitle("JSON 格式错误").setMessage(String.valueOf(e.getMessage())).setPositiveButton("好", null).show();
                return;
            }
            List<String> errs;
            try { errs = customizer().validate(cfg); } catch (Throwable e) { errs = Collections.singletonList(String.valueOf(e.getMessage())); }
            if (errs != null && !errs.isEmpty()) {
                new AlertDialog.Builder(host).setTitle("配置有错误，未保存").setMessage(TextUtils.join("\n", errs)).setPositiveButton("好", null).show();
                return;
            }
            final JSONObject toSave = cfg;
            uiWriteAsync("已保存，立即生效", "保存失败", () -> customizer().restore(toSave), d::dismiss);
        };
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        blp.setMargins(0, 0, dp(6), 0);
        row.addView(panelButton("保存", p, save), blp);
        row.addView(panelButton("恢复默认", p, v -> new AlertDialog.Builder(host).setTitle("恢复默认外观？")
                .setMessage("ui.json 会恢复成默认值（会先自动备份）。")
                .setPositiveButton("恢复", (dd, w) -> {
                    uiWriteAsync("已恢复默认", "恢复失败", () -> customizer().reset(null), d::dismiss);
                }).setNegativeButton("取消", null).show()), new LinearLayout.LayoutParams(blp));
        row.addView(panelButton("从备份恢复", p, v -> showUiBackups(d)), new LinearLayout.LayoutParams(blp));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(panelButton("关闭", p, v -> d.dismiss()), clp);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        d.show();
        if (w != null) w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private interface UiOp { void run() throws Exception; }

    /** ui.json 写入（含自动备份）在后台线程做；成功后回主线程提示并热重载 */
    private void uiWriteAsync(String okMsg, String failTitle, UiOp op, Runnable onOk) {
        new Thread(() -> {
            Throwable err = null;
            try { op.run(); } catch (Throwable e) { err = e; }
            final Throwable fe = err;
            MAIN.post(() -> {
                if (fe == null) {
                    if (okMsg != null) Toast.makeText(ctx, okMsg, Toast.LENGTH_SHORT).show();
                    if (onOk != null) onOk.run();
                    reloadUi();
                } else if (host != null && !host.isFinishing()) {
                    new AlertDialog.Builder(host).setTitle(failTitle).setMessage(String.valueOf(fe.getMessage())).setPositiveButton("好", null).show();
                } else {
                    Toast.makeText(ctx, failTitle + "：" + fe.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }, "pimet-ui-write").start();
    }

    /** ui.bak/<时间>.json，新的在前 */
    private void showUiBackups(AlertDialog parent) {
        if (host == null) return;
        File dir = new File(appsDir, "ui.bak");
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (fs == null || fs.length == 0) {
            Toast.makeText(ctx, "还没有备份", Toast.LENGTH_SHORT).show();
            return;
        }
        Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        String[] labels = new String[fs.length];
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
        for (int i = 0; i < fs.length; i++) labels[i] = f.format(new java.util.Date(fs[i].lastModified())) + "  " + fs[i].getName();
        new AlertDialog.Builder(host).setTitle("从备份恢复")
                .setItems(labels, (d, w) -> {
                    final String bn = fs[w].getName();
                    uiWriteAsync("已恢复备份", "恢复失败", () -> customizer().restore(new JSONObject().put("name", bn)),
                            () -> { if (parent != null) parent.dismiss(); });
                })
                .setNegativeButton("取消", null).show();
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
        /** 主页搜索框用的引擎模板（ui.json search.default，含 %s） */
        String searchTpl;
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
        h.searchTpl = searchTemplate();
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
                + "else location.href=" + JSONObject.quote(h.searchTpl == null ? "https://www.bing.com/search?q=%s" : h.searchTpl) + ".split('%s').join(encodeURIComponent(s));};</script>"
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
        if (uiHas("ball", "enabled")) return uiObj("ball").optBoolean("enabled", true);
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
        if (ballFull != null) ballFull.setTextColor(p.accent);
        refreshBall();
    }

    /** 刷新菜单里会变的按钮：全屏 ⛶/✕、译/原、缩放百分比 */
    private void refreshBall() {
        if (ballMenu == null) return;
        ThemeManager.ThemePalette p = ThemeManager.getEffectivePalette(ctx);
        if (ballFull != null) {
            ballFull.setText(fullscreen ? "✕" : "⛶");
            ballFull.setContentDescription(fullscreen ? "退出全屏" : "全屏");
        }
        boolean tr = active != null && active.translated;
        if (ballTranslate != null) {
            ballTranslate.setText(tr ? "原" : "译");
            ballTranslate.setTextColor(tr ? p.accent : p.text);
        }
        if (ballZoom != null) ballZoom.setText(zoomFor(active, active == null ? null : active.url) + "%");
        // 悬浮球开着时它就能退出全屏，右上角 ⤡ 不再需要
        exitFullBtn.setVisibility(fullscreen && !ballEnabled() ? View.VISIBLE : View.GONE);
    }

    private void collapseBall() {
        if (ballMenu == null || ballWrap == null) return;
        boolean wasOpen = ballMenu.getVisibility() == View.VISIBLE;
        ballMenu.setVisibility(View.GONE);
        if (!wasOpen) return;
        resumeRemoteSurfaceIfNeeded();
        final float fromX = ballWrap.getX();
        afterBallLayout(() -> slideBallFrom(fromX));
    }

    /** 菜单显示/隐藏后等重新布局完再摆放（否则 moveBall 用旧高度夹 Y，靠底部时球会往上跳） */
    private void afterBallLayout(Runnable r) {
        if (ballWrap == null) return;
        if (!ballWrap.isLayoutRequested()) {
            MAIN.post(r);
            return;
        }
        ballWrap.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                try { ballWrap.getViewTreeObserver().removeOnGlobalLayoutListener(this); } catch (Throwable ignored) {}
                r.run();
            }
        });
    }

    /** 按当前菜单状态摆放后，从 fromX 滑过去（半藏 ↔ 完整显示） */
    private void slideBallFrom(float fromX) {
        placeBall();
        float toX = ballWrap.getX();
        if (Math.abs(toX - fromX) > 1) {
            ballWrap.setX(fromX);
            ballWrap.animate().x(toX).setDuration(180).start();
        }
    }

    private void toggleBallMenu() {
        boolean open = ballMenu.getVisibility() != View.VISIBLE;
        if (open) refreshBall();
        ballMenu.setVisibility(open ? View.VISIBLE : View.GONE);
        ballWrap.bringToFront();
        if (open) {
            suspendRemoteSurfaceForOverlay();
        } else {
            resumeRemoteSurfaceIfNeeded();
        }
        final float fromX = ballWrap.getX();
        afterBallLayout(() -> slideBallFrom(fromX));
    }

    private void setBallEnabled(boolean on) {
        prefs().edit().putBoolean("ball_on", on).apply();
        if (uiHas("ball", "enabled")) {
            final BrowserCustomizer cz = customizer();
            new Thread(() -> {
                try {
                    cz.set("/ball/enabled", on);
                } catch (Throwable e) {
                    Log.w(TAG, "set ball.enabled failed", e);
                }
            }, "pimet-ui-write").start(); // 写盘不放主线程；写完 addListener 会触发热重载
        }
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
        ballWrap.animate().cancel(); // 旋转/布局变化时停掉还在跑的滑动动画，免得它把位置改回旧值
        float x = prefs().getFloat("ball_x", -1f), y = prefs().getFloat("ball_y", -1f);
        if (x < 0 || y < 0) {
            x = root.getWidth() - dp(44) - dp(3) - dp(16);
            y = dp(80);
        }
        moveBall(x, y);
        // 贴边半藏：菜单收起时只露出约 45%，点一下先滑出再展开
        String side = prefs().getString("ball_side", "");
        if (ballSnap() && !side.isEmpty() && (ballMenu == null || ballMenu.getVisibility() != View.VISIBLE)) {
            ballWrap.setX(ballPeekX(side));
        }
    }

    private float ballPeekX(String side) {
        int bw = Math.max(ballWrap.getWidth(), dp(50));
        return "L".equals(side) ? -bw * 0.55f : root.getWidth() - bw * 0.45f;
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
                    ballWrap.animate().cancel();
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
                    if (moved && !ballSnap()) {
                        prefs().edit().putFloat("ball_x", ballWrap.getX()).putFloat("ball_y", ballWrap.getY()).putString("ball_side", "").apply();
                    } else if (moved) {
                        // 松手吸附最近的左/右边缘并半藏（菜单展开时完整显示）
                        int bw = Math.max(ballWrap.getWidth(), dp(50));
                        String sd = (ballWrap.getX() + bw / 2f < root.getWidth() / 2f) ? "L" : "R";
                        boolean menuOpen = ballMenu != null && ballMenu.getVisibility() == View.VISIBLE;
                        float tx = menuOpen ? ("L".equals(sd) ? dp(8) : root.getWidth() - bw - dp(8)) : ballPeekX(sd);
                        final float ty = ballWrap.getY();
                        ballWrap.animate().x(tx).setDuration(220).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                        prefs().edit().putFloat("ball_x", "L".equals(sd) ? dp(8) : root.getWidth() - bw - dp(8)).putFloat("ball_y", ty).putString("ball_side", sd).apply();
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
        if (web == null || host == null) {
            Toast.makeText(ctx, web == null ? "网页翻译失败：当前没有打开的网页" : "网页翻译失败：浏览器窗口还没准备好", Toast.LENGTH_SHORT).show();
            return;
        }
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
            if (!isTranslated) {
                MAIN.post(() -> {
                    if (tab.web != null) com.xm486.pimet.translation.WebTranslationManager.clearSession(tab.web);
                    restoreTabSubframes(tab);
                });
            } else {
                tab.translated = true;
                MAIN.post(FloatingApps.this::refreshBall);
            }
            com.xm486.pimet.translation.TranslationBridge r = real();
            if (r != null) r.onStateChanged(isTranslated, doneCount, totalCount);
        }

        @JavascriptInterface
        public void onProgress(int done, int total, int failed, boolean activeFlag,
                               int pending, String lastError, boolean hasDomIframes) {
            tab.mainTransDone = Math.max(0, done);
            tab.mainTransTotal = Math.max(0, total);
            tab.mainTransFailed = Math.max(0, failed);
            tab.mainTransActive = activeFlag;
            tab.mainTransPending = Math.max(0, pending);
            tab.mainTransError = lastError == null ? "" : lastError;
            tab.mainTransHasDomIframes = hasDomIframes;
            MAIN.post(() -> updateAggregatedTranslationUi(tab));
        }

        @JavascriptInterface
        public void onRetry() {
            MAIN.post(() -> retryTabSubframes(tab));
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
        updateProgress();
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
        if (customView != null) hideCustomView(false);
        customView = v;
        customViewCallback = cb;
        suspendRemoteSurfaceForOverlay();
        v.setBackgroundColor(Color.BLACK);
        root.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyImmersive(true);
    }

    private void hideCustomView() {
        hideCustomView(true);
    }

    private void hideCustomView(boolean resumeRemote) {
        if (customView == null) return;
        try { root.removeView(customView); } catch (Throwable ignored) {}
        customView = null;
        WebChromeClient.CustomViewCallback cb = customViewCallback;
        customViewCallback = null;
        if (cb != null) try { cb.onCustomViewHidden(); } catch (Throwable ignored) {}
        applyImmersive(fullscreen);
        if (resumeRemote) {
            resumeRemoteSurfaceIfNeeded();
        }
    }

    private void dismiss() {
        minimized = true;
        suspendRemoteSurfaceForOverlay();
        activeRemoteTabId = null;
        remoteFallbackActive = false;
        closePanel(false);
        cancelPendingFileChooser();
        AttachmentPickerActivity.sCallback = null;
        hideCustomView(false);
        hideIme();
        Activity a = host;
        if (root.getParent() instanceof ViewGroup) {
            try { ((ViewGroup) root.getParent()).removeView(root); } catch (Throwable ignored) {}
        }
        host = null;
        vctx.setBaseContext(ctx);
        for (Tab t : tabs) {
            if (offscreenHost != null && t.web != null) {
                offscreenHost.unmount(t.web);
            }
            if (t.tabCtx != null) {
                try { t.tabCtx.setBaseContext(ctx); } catch (Throwable ignored) {}
            }
            try {
                if (t.web != null) {
                    t.web.stopLoading();
                    t.web.destroy();
                }
            } catch (Throwable ignored) {}
            t.web = null;
            t.closed = true;
            if (t.thumb != null) t.thumb.recycle();
            t.thumb = null;
        }
        List<String> used = new ArrayList<>();
        for (Tab t : tabs) if (t.container != null) used.add(t.container);
        tabs.clear();
        for (String c : used) releaseContainer(c); // 每个标签各 release 一次，与 apply 次数对应
        if (offscreenHost != null) {
            try { offscreenHost.destroy(); } catch (Throwable ignored) {}
            offscreenHost = null;
        }
        if (workerPool != null) {
            try { workerPool.shutdownAll(); } catch (Throwable ignored) {}
            workerPool = null;
        }
        try { mirrorExec.shutdownNow(); } catch (Throwable ignored) {}
        if (proxyMgr != null) {
            proxyMgr.shutdown();
            proxyMgr = null;
        }
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
