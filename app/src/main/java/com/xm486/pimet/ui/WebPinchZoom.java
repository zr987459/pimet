package com.xm486.pimet.ui;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.xm486.pimet.PiMetConfig;
import com.xm486.pimet.ThemeManager;

import java.util.Locale;

/**
 * 工作台网页「布局缩放」（双指 + 悬浮球按钮共用同一个缩放值 web_zoom）。
 *
 * <p>与普通的放大镜式缩放不同，这里缩放的是页面的<b>布局尺寸</b>：
 * 通过改写本地页面的 viewport，令布局视口宽度 = 屏宽(CSS px) / 缩放比，页面缩放比固定为同一值。
 * 于是：</p>
 * <ul>
 *   <li>缩小（最低 25%）：页面按更宽的布局重新排版，整体变小、能看到更多内容，仍恰好铺满屏宽，两侧无空白；</li>
 *   <li>放大（最高 300%）：页面按更窄的布局重新排版，文字与控件变大并自动换行，永远不会横向溢出屏幕，无需左右拖动。</li>
 * </ul>
 * <p>原生放大镜式缩放在本地页面被禁用（min = max = 当前比例、user-scalable=no），
 * 双指手势由 {@link ScaleGestureDetector} 接管；单指滚动/点击照常交给网页。
 * 媒体查询、vw/vh、fixed 定位都按新的布局视口计算，因此整页一致缩放。</p>
 * <p>仅作用于本地服务页面 (127.0.0.1 / localhost)，外部网站保持其原生行为。</p>
 */
public final class WebPinchZoom {

    public static final int MIN_ZOOM = 25;
    public static final int MAX_ZOOM = 300;

    /** 缩放值变化回调（双指过程中 committed=false，松手后 committed=true）。 */
    public interface Listener {
        void onZoomChanged(int percent, boolean committed);
    }

    private static final String FIX_JS = "(function(){try{" +
            "if(!window.__pimetZoomFix){" +
            " window.__pimetZoomFix=function(){" +
            "  var C=window.__pimetZoomVp; if(!C) return;" +
            "  var h=document.head||document.documentElement; if(!h) return;" +
            "  var ms=document.querySelectorAll('meta[name=\"viewport\"]');" +
            "  var m=ms.length?ms[0]:null;" +
            "  for(var i=1;i<ms.length;i++){ if(ms[i].parentNode) ms[i].parentNode.removeChild(ms[i]); }" +
            "  if(!m){ m=document.createElement('meta'); m.setAttribute('name','viewport'); h.appendChild(m); }" +
            "  if(m.getAttribute('content')!==C) m.setAttribute('content',C);" +
            "  if(!document.getElementById('__pimet_pinch_css')){" +
            "    var s=document.createElement('style'); s.id='__pimet_pinch_css';" +
            "    s.textContent='html,body{max-width:100%;overflow-x:clip;}';" +
            "    h.appendChild(s);" +
            "  }" +
            " };" +
            " if(window.MutationObserver && document.head){" +
            "  new MutationObserver(function(){window.__pimetZoomFix();})" +
            "   .observe(document.head,{childList:true,subtree:true,attributes:true,attributeFilter:['content']});" +
            " }" +
            "}" +
            "window.__pimetZoomFix();" +
            "}catch(e){}})();";

    private final WebView webView;
    private final Listener listener;
    private final ScaleGestureDetector detector;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean pinching = false;
    private float liveZoom;          // 当前手势中的缩放（百分比，浮点）
    private int lastAppliedPct = -1;
    private long lastApplyAt = 0;
    private boolean applyPending = false;
    private TextView indicator;

    private final Runnable hideIndicator = new Runnable() {
        @Override
        public void run() {
            if (indicator != null) {
                indicator.animate().alpha(0f).setDuration(180).withEndAction(() -> {
                    if (indicator != null) indicator.setVisibility(View.GONE);
                }).start();
            }
        }
    };

    private final Runnable throttledApply = new Runnable() {
        @Override
        public void run() {
            applyPending = false;
            applyLive();
        }
    };

    private WebPinchZoom(WebView webView, Listener listener) {
        this.webView = webView;
        this.listener = listener;
        this.liveZoom = getZoom(webView.getContext());
        this.detector = new ScaleGestureDetector(webView.getContext(),
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector d) {
                        if (!pinching) return true;
                        float f = d.getScaleFactor();
                        if (Float.isNaN(f) || f <= 0f) return true;
                        liveZoom = clampF(liveZoom * f);
                        scheduleApply();
                        return true;
                    }
                });
        this.detector.setQuickScaleEnabled(false);
    }

    /** 安装双指布局缩放手势（同时完成 WebSettings 配置）。 */
    public static WebPinchZoom attach(WebView webView, Listener listener) {
        if (webView == null) return null;
        configure(webView);
        WebPinchZoom z = new WebPinchZoom(webView, listener);
        webView.setOnTouchListener((v, e) -> z.onTouch(e));
        webView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if ((r - l) != (or - ol) && (r - l) > 0) apply(webView, webView.getUrl());
        });
        return z;
    }

    /**
     * WebSettings：外部网站仍可原生双指缩放；本地页面由 viewport 锁定比例、手势由本类接管。
     * 文字缩放固定 100%，避免与布局缩放叠加。
     */
    public static void configure(WebView webView) {
        if (webView == null) return;
        WebSettings ws = webView.getSettings();
        ws.setSupportZoom(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setTextZoom(100);
    }

    /** 读取共享缩放值（兼容旧版文字缩放值：直接沿用并夹在新范围内）。 */
    public static int getZoom(Context ctx) {
        return clamp(PiMetConfig.getWebZoom(ctx));
    }

    public static int clamp(int pct) {
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, pct));
    }

    private static float clampF(float pct) {
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, pct));
    }

    /** 保存并立即应用缩放值（悬浮球按钮调用）。 */
    public static int setZoom(WebView webView, int pct) {
        int z = clamp(pct);
        if (webView == null) return z;
        PiMetConfig.setWebZoom(webView.getContext(), z);
        webView.getSettings().setTextZoom(100);
        apply(webView, webView.getUrl());
        return z;
    }

    /** 页面可见/加载完成后调用：按保存的缩放值重设本地页面的布局视口。 */
    public static void apply(WebView webView, String url) {
        if (webView == null || !isLocal(url)) return;
        applyPct(webView, getZoom(webView.getContext()));
    }

    private static void applyPct(WebView webView, float pct) {
        float density = webView.getResources().getDisplayMetrics().density;
        int px = webView.getWidth();
        if (px <= 0) px = webView.getResources().getDisplayMetrics().widthPixels;
        float cssW = px / density;
        int layoutW = Math.max(1, Math.round(cssW * 100f / pct));
        // 实际页面比例按取整后的布局宽度反算，保证恰好铺满屏宽、不多出 1px 横向滚动
        float scale = cssW / layoutW;
        String s = String.format(Locale.US, "%.4f", scale);
        String vp = "width=" + layoutW + ",initial-scale=" + s + ",minimum-scale=" + s
                + ",maximum-scale=" + s + ",user-scalable=no";
        webView.evaluateJavascript("window.__pimetZoomVp='" + vp + "';" + FIX_JS, null);
    }

    public static boolean isLocal(String url) {
        if (url == null) return false;
        return url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")
                || url.startsWith("https://127.0.0.1") || url.startsWith("https://localhost");
    }

    // ---------------- 手势 ----------------

    private boolean onTouch(MotionEvent e) {
        if (!pinching && !isLocal(webView.getUrl())) return false;
        detector.onTouchEvent(e);
        int a = e.getActionMasked();
        if (!pinching && a == MotionEvent.ACTION_POINTER_DOWN && e.getPointerCount() >= 2) {
            pinching = true;
            liveZoom = getZoom(webView.getContext());
            lastAppliedPct = Math.round(liveZoom);
            // 取消网页里已开始的单指手势（滚动/长按），之后的事件全部由缩放接管
            MotionEvent cancel = MotionEvent.obtain(e);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            webView.onTouchEvent(cancel);
            cancel.recycle();
            showIndicator(Math.round(liveZoom));
            return true;
        }
        if (pinching) {
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                pinching = false;
                commit();
            }
            return true;
        }
        return false;
    }

    private void scheduleApply() {
        int pct = Math.round(liveZoom);
        showIndicator(pct);
        if (listener != null) listener.onZoomChanged(pct, false);
        long now = System.currentTimeMillis();
        if (now - lastApplyAt >= 60) {
            handler.removeCallbacks(throttledApply);
            applyPending = false;
            applyLive();
        } else if (!applyPending) {
            applyPending = true;
            handler.postDelayed(throttledApply, 60 - (now - lastApplyAt));
        }
    }

    private void applyLive() {
        int pct = Math.round(liveZoom);
        lastApplyAt = System.currentTimeMillis();
        if (pct == lastAppliedPct) return;
        lastAppliedPct = pct;
        applyPct(webView, pct);
    }

    private void commit() {
        handler.removeCallbacks(throttledApply);
        applyPending = false;
        int pct = Math.round(liveZoom);
        // 吸附到 5% 整数档；靠近 100% 时吸附回 100%
        if (Math.abs(pct - 100) <= 4) pct = 100;
        else pct = Math.round(pct / 5f) * 5;
        pct = clamp(pct);
        liveZoom = pct;
        PiMetConfig.setWebZoom(webView.getContext(), pct);
        lastAppliedPct = pct;
        applyPct(webView, pct);
        showIndicator(pct);
        if (listener != null) listener.onZoomChanged(pct, true);
        handler.removeCallbacks(hideIndicator);
        handler.postDelayed(hideIndicator, 700);
    }

    // ---------------- 百分比提示 ----------------

    private void showIndicator(int pct) {
        Context ctx = webView.getContext();
        if (indicator == null) {
            if (!(ctx instanceof Activity)) return;
            View content = ((Activity) ctx).findViewById(android.R.id.content);
            if (!(content instanceof ViewGroup)) return;
            ThemeManager.ThemePalette palette = ThemeManager.getEffectivePalette(ctx);
            float d = ctx.getResources().getDisplayMetrics().density;
            indicator = new TextView(ctx);
            indicator.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            indicator.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
            indicator.setTextColor(palette.text);
            indicator.setBackground(ThemeManager.createCardDrawable(ctx, palette, 10f));
            indicator.setPadding((int) (14 * d), (int) (6 * d), (int) (14 * d), (int) (6 * d));
            indicator.setSingleLine(true);
            indicator.setClickable(false);
            indicator.setFocusable(false);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            lp.topMargin = (int) (56 * d);
            ((ViewGroup) content).addView(indicator, lp);
        } else {
            ThemeManager.ThemePalette palette = ThemeManager.getEffectivePalette(ctx);
            indicator.setTextColor(palette.text);
            indicator.setBackground(ThemeManager.createCardDrawable(ctx, palette, 10f));
        }
        handler.removeCallbacks(hideIndicator);
        indicator.animate().cancel();
        indicator.setAlpha(1f);
        indicator.setVisibility(View.VISIBLE);
        indicator.bringToFront();
        indicator.setText(pct + "%");
    }
}
