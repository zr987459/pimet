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
 * Pi 在容器的 /root/pimet-apps/&lt;应用名&gt;/ 下写一个 index.html（可带 app.json 写名字和图标），
 * 这里就会出现一个应用，点开在窗口里运行。可以有任意多个。页面里能用 window.pimet 调容器命令、
 * 给 Pi 发消息等（只对这个目录下的本地页面开放）。窗口本身也能当普通浏览器打开任意网址。
 */
public final class FloatingApps {

    private static final String TAG = "PiMet.Apps";
    private static final String PREFS = "pimet_floating_apps";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    public static final String APPS_DIR_IN_CONTAINER = "/root/pimet-apps";
    private static FloatingApps sInstance;

    /** 由 MainActivity 注册：把文字放进 Pi-Web 输入框，send=true 时直接发送 */
    public interface PiBridge {
        void askPi(String text, boolean send);
    }

    private static PiBridge sPiBridge;

    public static void setPiBridge(PiBridge bridge) {
        sPiBridge = bridge;
    }

    private final Context ctx;
    private final WindowManager wm;
    private final WindowManager.LayoutParams lp;
    private final LinearLayout root;
    private final FrameLayout webFrame;
    private final WebView web;
    private final EditText urlField;
    private final File appsDir;
    private volatile String currentUrl = "";
    private boolean minimized = false, maximized = false;
    private int savedW, savedH, savedX, savedY;

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
                Toast.makeText(app, "需要悬浮窗权限才能打开应用窗", Toast.LENGTH_SHORT).show();
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
                Toast.makeText(app, "打开应用窗失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    /** 准备应用目录和给 Pi 的说明（技能），可在后台线程调用 */
    public static void ensureScaffold(Context context) {
        try {
            File rootfs = ProotManager.getRootfsDir(context);
            if (rootfs == null || !new File(rootfs, "bin").exists()) return;
            File apps = new File(rootfs, APPS_DIR_IN_CONTAINER.substring(1));
            if (!apps.exists()) apps.mkdirs();
            String guide = readAsset(context, "pimet-apps/SKILL.md");
            if (guide == null) return;
            File skillDir = new File(rootfs, "root/.pi/agent/skills/pimet-apps");
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
        this.wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        this.appsDir = new File(ProotManager.getRootfsDir(app), APPS_DIR_IN_CONTAINER.substring(1));
        if (!appsDir.exists()) appsDir.mkdirs();
        new Thread(() -> ensureScaffold(app), "pimet-apps-scaffold").start();

        DisplayMetrics dm = app.getResources().getDisplayMetrics();
        SharedPreferences sp = prefs();

        root = new LinearLayout(app);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F5141418"));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), Color.parseColor("#3A3F4B"));
        root.setBackground(bg);
        root.setClipToOutline(true);
        root.setPadding(dp(2), dp(2), dp(2), dp(2));

        // ---- 顶栏：拖动 · 后退 · 地址栏 · 刷新 · 应用列表 · 最大化 · 最小化 · 关闭
        LinearLayout bar = new LinearLayout(app);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(2), 0, dp(2), 0);

        TextView grip = barBtn("⠿", "拖动（点按最小化）");
        grip.setOnTouchListener(new DragMover());
        bar.addView(grip, new LinearLayout.LayoutParams(dp(26), dp(36)));
        addBar(bar, "‹", "后退", v -> goBack());

        urlField = new EditText(app);
        urlField.setSingleLine(true);
        urlField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        urlField.setTextColor(Color.parseColor("#E6EDF3"));
        urlField.setHintTextColor(Color.parseColor("#6E7681"));
        urlField.setHint("应用列表 · 输入网址");
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
        addBar(bar, "⊞", "应用列表", v -> goHome());
        addBar(bar, "⤢", "最大化", v -> toggleMaximize());
        addBar(bar, "—", "最小化", v -> toggleMinimize());
        addBar(bar, "✕", "关闭", v -> dismiss());
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));

        // ---- 网页
        webFrame = new FrameLayout(app);
        web = new WebView(app);
        WebSettings ws = web.getSettings();
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
        web.setBackgroundColor(Color.parseColor("#0D1117"));
        web.addJavascriptInterface(new Bridge(), "PiMetNative");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String u = req.getUrl().toString();
                if (u.startsWith("http://") || u.startsWith("https://") || u.startsWith("file://")
                        || u.startsWith("about:") || u.startsWith("data:") || u.startsWith("blob:")) {
                    return false;
                }
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);
                } catch (Throwable ignored) {}
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                currentUrl = url != null ? url : "";
                showUrl(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                currentUrl = url != null ? url : "";
                showUrl(url);
                if (isTrusted(url)) view.evaluateJavascript(HELPER_JS, null);
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                currentUrl = url != null ? url : "";
                showUrl(url);
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        webFrame.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 右下角拖动调整大小
        TextView resize = new TextView(app);
        resize.setText("◢");
        resize.setTextColor(Color.parseColor("#8B949E"));
        resize.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        resize.setGravity(Gravity.CENTER);
        resize.setContentDescription("拖动调整大小");
        resize.setOnTouchListener(new DragResizer());
        webFrame.addView(resize, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.BOTTOM | Gravity.END));
        root.addView(webFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

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

        // 点窗口内：可输入；点窗口外：让出焦点给下层
        web.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) setFocusable(true);
            return false;
        });
        root.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_OUTSIDE) setFocusable(false);
            return false;
        });

        wm.addView(root, lp);
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
        if (root.getParent() == null) wm.addView(root, lp);
        if (minimized) toggleMinimize();
        new Thread(() -> {
            File launcher = new File(appsDir, ".launcher.html");
            try {
                writeIfChanged(launcher, buildLauncherHtml());
            } catch (Throwable t) {
                Log.w(TAG, "write launcher failed", t);
            }
            MAIN.post(() -> web.loadUrl("file://" + launcher.getAbsolutePath()));
        }, "pimet-apps-home").start();
    }

    private void load(String url) {
        if (root.getParent() == null) wm.addView(root, lp);
        if (minimized) toggleMinimize();
        if (url == null || url.isEmpty()) {
            goHome();
            return;
        }
        web.loadUrl(url);
    }

    private void reload() {
        if (isLauncher(currentUrl)) goHome();
        else web.reload();
    }

    private void goBack() {
        if (web.canGoBack()) web.goBack();
        else if (!isLauncher(currentUrl)) goHome();
    }

    private boolean isLauncher(String url) {
        return url != null && url.endsWith("/.launcher.html");
    }

    private void showUrl(String url) {
        if (urlField.hasFocus()) return;
        if (url == null || isLauncher(url)) {
            urlField.setText("");
            return;
        }
        String prefix = appsUrlPrefix();
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
            return "file://" + new File(appsDir, id + "/index.html").getAbsolutePath();
        }
        if (s.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) return s;
        if (s.startsWith("localhost") || s.startsWith("127.0.0.1") || s.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+(:\\d+)?(/.*)?$")) {
            return "http://" + s;
        }
        if (s.matches("^:\\d+(/.*)?$")) return "http://127.0.0.1" + s;
        if (!s.contains(" ") && s.contains(".")) return "https://" + s;
        return "https://www.bing.com/search?q=" + Uri.encode(s);
    }

    // ------------------------------------------------------------------ 应用列表

    private static final class AppInfo {
        String id, name, icon, desc;
        long mtime;
    }

    private List<AppInfo> scanApps() {
        List<AppInfo> list = new ArrayList<>();
        File[] dirs = appsDir.listFiles();
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
            String href = Uri.encode(a.id) + "/index.html";
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
            body = "<div class=\"empty\"><div class=\"big\">🧩</div>"
                    + "<p>还没有应用。</p>"
                    + "<p>在工作台里跟 Pi 说，例如：<br><b>「用 pimet-apps 技能给我做一个记账应用」</b></p>"
                    + "<p class=\"dim\">Pi 会把页面写到 " + APPS_DIR_IN_CONTAINER + "/&lt;名字&gt;/index.html，做好后点 ⟳ 就能看到。</p></div>";
        } else {
            body = "<div class=\"grid\">" + cards + "</div>"
                    + "<p class=\"dim foot\">应用目录：" + APPS_DIR_IN_CONTAINER + " · 让 Pi 修改后点 ⟳ 刷新</p>";
        }
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Pi 应用</title><style>"
                + ":root{color-scheme:dark}body{margin:0;padding:16px;background:#0d1117;color:#e6edf3;"
                + "font-family:system-ui,-apple-system,'PingFang SC','Noto Sans CJK SC',sans-serif}"
                + "h1{font-size:17px;margin:0 0 14px;font-weight:600}"
                + ".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(96px,1fr));gap:10px}"
                + ".card{display:flex;flex-direction:column;align-items:center;text-align:center;padding:14px 6px;"
                + "border-radius:14px;background:#161b22;border:1px solid #30363d;color:inherit;text-decoration:none}"
                + ".card:active{background:#1f2630}.icon{font-size:30px;line-height:1.2}"
                + ".name{margin-top:6px;font-size:13px;font-weight:600;word-break:break-all}"
                + ".desc{margin-top:3px;font-size:11px;color:#8b949e;display:-webkit-box;-webkit-line-clamp:2;"
                + "-webkit-box-orient:vertical;overflow:hidden}"
                + ".empty{text-align:center;margin-top:12vh;line-height:1.7;font-size:14px}.big{font-size:44px}"
                + ".dim{color:#8b949e;font-size:12px}.foot{margin-top:18px;text-align:center}"
                + "</style></head><body><h1>Pi 应用</h1>" + body + "</body></html>";
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

    private final class Bridge {
        @JavascriptInterface
        public void exec(String cmd, String cbId, int timeoutSec) {
            if (!isTrusted(currentUrl)) {
                deliver(cbId, -1, "", "当前页面不在应用目录内，禁止执行命令");
                return;
            }
            final int timeout = Math.max(1, Math.min(timeoutSec, 600));
            new Thread(() -> runCommand(cmd, cbId, timeout), "pimet-apps-exec").start();
        }

        @JavascriptInterface
        public void askPi(String text, boolean send) {
            if (!isTrusted(currentUrl)) return;
            MAIN.post(() -> {
                if (sPiBridge != null) sPiBridge.askPi(text, send);
                else Toast.makeText(ctx, "工作台未就绪", Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface
        public void toast(String msg) {
            MAIN.post(() -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void openExternal(String url) {
            MAIN.post(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);
                } catch (Throwable ignored) {}
            });
        }

        @JavascriptInterface
        public void close() {
            MAIN.post(FloatingApps.this::dismiss);
        }
    }

    private void runCommand(String cmd, String cbId, int timeoutSec) {
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
            deliver(cbId, done ? proc.exitValue() : -1, out.toString("UTF-8"), e);
        } catch (Throwable t) {
            if (p != null) try { p.destroyForcibly(); } catch (Throwable ignored) {}
            deliver(cbId, -1, "", "执行失败: " + t.getMessage());
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

    private void deliver(String cbId, int code, String stdout, String stderr) {
        try {
            JSONObject res = new JSONObject();
            res.put("code", code);
            res.put("stdout", stdout);
            res.put("stderr", stderr);
            String js = "window.__pimetResolve&&window.__pimetResolve(" + JSONObject.quote(cbId) + "," + res + ")";
            MAIN.post(() -> web.evaluateJavascript(js, null));
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
        webFrame.setVisibility(minimized ? View.GONE : View.VISIBLE);
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

    private void dismiss() {
        hideIme();
        try {
            if (root.getParent() != null) wm.removeView(root);
        } catch (Throwable ignored) {}
        try {
            web.stopLoading();
            web.destroy();
        } catch (Throwable ignored) {}
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
