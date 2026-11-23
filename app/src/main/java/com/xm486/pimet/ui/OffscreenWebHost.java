package com.xm486.pimet.ui;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.FrameLayout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 离屏真实视口 WebView 宿主：
 * <ul>
 *   <li>通过 {@link VirtualDisplay} (1280x900 @ 160dpi) + {@link ImageReader} + {@link Presentation}
 *       为后台标签与独立代理子进程提供真实硬件加速与非零视口（手机 390x844 / 桌面 1280x800）；</li>
 *   <li>专用守护线程及时 drain {@link ImageReader}，防止 BufferQueue 阻塞；</li>
 *   <li>无 {@code SYSTEM_ALERT_WINDOW} 权限或 {@link Presentation} 受限时，自动降级为
 *       {@code DisplayContext} + 无窗口 {@link FrameLayout} 显式 {@code measure}/{@code layout}，
 *       依然保证非零视口与可截图。</li>
 * </ul>
 */
public final class OffscreenWebHost {

    private static final String TAG = "OffscreenWebHost";

    public static final int MOBILE_W = 390;
    public static final int MOBILE_H = 844;
    public static final int DESKTOP_W = 1280;
    public static final int DESKTOP_H = 800;
    public static final int VD_W = 1280;
    public static final int VD_H = 900;
    public static final int VD_DPI = 160;

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final HandlerThread drainThread;
    private final Handler drainHandler;
    private final ImageReader imageReader;
    private final VirtualDisplay virtualDisplay;
    private final Context hostContext;
    private final FrameLayout root;
    private Presentation presentation;
    private boolean overlayFallback;
    private volatile boolean destroyed;
    private final AtomicLong visualSeq = new AtomicLong(1L);

    public OffscreenWebHost(Context ctx) {
        if (ctx == null) throw new IllegalArgumentException("ctx == null");
        Context app = ctx.getApplicationContext();
        this.appContext = app != null ? app : ctx;

        HandlerThread ht = new HandlerThread("pimet-vd-drain");
        ht.start();
        this.drainThread = ht;
        this.drainHandler = new Handler(ht.getLooper());

        ImageReader ir = null;
        VirtualDisplay vd = null;
        Context displayCtx = null;
        try {
            ir = ImageReader.newInstance(VD_W, VD_H, PixelFormat.RGBA_8888, 2);
            ir.setOnImageAvailableListener(reader -> {
                Image img = null;
                try {
                    img = reader.acquireLatestImage();
                } catch (Throwable ignored) {
                } finally {
                    if (img != null) {
                        try {
                            img.close();
                        } catch (Throwable ignored) {}
                    }
                }
            }, drainHandler);

            DisplayManager dm = (DisplayManager) appContext.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) {
                vd = dm.createVirtualDisplay(
                        "pimet-offscreen-web",
                        VD_W,
                        VD_H,
                        VD_DPI,
                        ir.getSurface(),
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                                | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                );
            }
            if (vd != null && vd.getDisplay() != null) {
                displayCtx = appContext.createDisplayContext(vd.getDisplay());
            }
        } catch (Throwable t) {
            Log.w(TAG, "createVirtualDisplay failed, falling back", t);
        }
        this.imageReader = ir;
        this.virtualDisplay = vd;

        Presentation pres = null;
        FrameLayout rootLayout = null;
        Context resolvedCtx = displayCtx != null ? displayCtx : appContext;
        boolean fallback = false;

        if (vd != null && vd.getDisplay() != null) {
            boolean isAct = isActivityContext(ctx);
            boolean overlayAllowed = isAct || Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    || Settings.canDrawOverlays(appContext);
            if (overlayAllowed) {
                try {
                    Context presParentCtx = isAct ? ctx : appContext;
                    pres = new Presentation(presParentCtx, vd.getDisplay());
                    Context presCtx = pres.getContext();
                    rootLayout = new FrameLayout(presCtx);
                    pres.setContentView(rootLayout, new ViewGroup.LayoutParams(VD_W, VD_H));
                    Window w = pres.getWindow();
                    if (w != null) {
                        if (!isAct && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                        }
                        w.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
                        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                        w.setLayout(VD_W, VD_H);
                        w.setGravity(Gravity.TOP | Gravity.START);
                    }
                    pres.show();
                    resolvedCtx = presCtx;
                } catch (Throwable t) {
                    Log.w(TAG, "Presentation.show() failed, falling back to DisplayContext", t);
                    if (pres != null) {
                        try {
                            pres.dismiss();
                        } catch (Throwable ignored) {}
                        pres = null;
                    }
                    rootLayout = null;
                    fallback = true;
                }
            } else {
                fallback = true;
            }
        } else {
            fallback = true;
        }

        if (fallback || rootLayout == null) {
            fallback = true;
            resolvedCtx = displayCtx != null ? displayCtx : appContext;
            rootLayout = new FrameLayout(resolvedCtx);
            layoutFallbackRoot(rootLayout);
        }

        this.presentation = pres;
        this.overlayFallback = fallback;
        this.hostContext = resolvedCtx;
        this.root = rootLayout;
    }

    public Context getHostContext() {
        return hostContext;
    }

    public boolean isOverlayFallback() {
        return overlayFallback;
    }

    public void mount(WebView web, boolean desktop) {
        if (web == null || destroyed) return;
        ViewParent parent = web.getParent();
        if (parent instanceof ViewGroup && parent != root) {
            ((ViewGroup) parent).removeView(web);
        }
        int w = desktop ? DESKTOP_W : MOBILE_W;
        int h = desktop ? DESKTOP_H : MOBILE_H;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.START);
        if (web.getParent() == root) {
            web.setLayoutParams(lp);
        } else {
            root.addView(web, lp);
        }
        web.setVisibility(View.VISIBLE);
        try {
            web.onResume();
        } catch (Throwable ignored) {}
        if (overlayFallback) {
            layoutFallbackChild(web, w, h);
        } else {
            root.requestLayout();
        }
    }

    public void updateViewport(WebView web, boolean desktop) {
        if (web == null || destroyed) return;
        int w = desktop ? DESKTOP_W : MOBILE_W;
        int h = desktop ? DESKTOP_H : MOBILE_H;
        ViewGroup.LayoutParams cur = web.getLayoutParams();
        if (cur instanceof FrameLayout.LayoutParams) {
            cur.width = w;
            cur.height = h;
            ((FrameLayout.LayoutParams) cur).gravity = Gravity.TOP | Gravity.START;
            web.setLayoutParams(cur);
        } else {
            web.setLayoutParams(new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.START));
        }
        web.requestLayout();
        if (overlayFallback) {
            layoutFallbackChild(web, w, h);
        }
    }

    public void unmount(WebView web) {
        if (web == null) return;
        if (web.getParent() == root) {
            root.removeView(web);
        }
    }

    /**
     * 抓取 WebView 画面：可在后台线程或主线程调用。
     * Presentation 可用且已挂载到窗口时优先使用 {@link PixelCopy}，失败或降级模式回退 {@code web.draw(canvas)}。
     */
    public Bitmap captureScreenshot(WebView web, long timeoutMs) {
        if (web == null || destroyed) return null;
        long budget = Math.max(500L, timeoutMs);
        long start = System.currentTimeMillis();

        if (Looper.myLooper() == Looper.getMainLooper()) {
            return captureOnMain(web, budget);
        }

        final AtomicBoolean canPixelCopy = new AtomicBoolean(false);
        final AtomicInteger widthRef = new AtomicInteger(0);
        final AtomicInteger heightRef = new AtomicInteger(0);
        final CountDownLatch prepLatch = new CountDownLatch(1);
        final CountDownLatch visualLatch = new CountDownLatch(1);

        mainHandler.post(() -> {
            try {
                if (destroyed) return;
                if (web.getParent() == root) web.bringToFront();
                web.setVisibility(View.VISIBLE);
                try {
                    web.onResume();
                } catch (Throwable ignored) {}

                int[] wh = resolveTargetSize(web);
                if (web.getWidth() <= 0 || web.getHeight() <= 0) {
                    layoutFallbackChild(web, wh[0], wh[1]);
                }
                widthRef.set(web.getWidth() > 0 ? web.getWidth() : wh[0]);
                heightRef.set(web.getHeight() > 0 ? web.getHeight() : wh[1]);

                boolean pxOk = !overlayFallback
                        && presentation != null
                        && presentation.getWindow() != null
                        && Build.VERSION.SDK_INT >= 26
                        && web.getParent() == root
                        && web.isAttachedToWindow();
                canPixelCopy.set(pxOk);
                if (pxOk) {
                    try {
                        web.postVisualStateCallback(visualSeq.getAndIncrement(), new WebView.VisualStateCallback() {
                            @Override
                            public void onComplete(long requestId) {
                                visualLatch.countDown();
                            }
                        });
                    } catch (Throwable t) {
                        visualLatch.countDown();
                    }
                } else {
                    visualLatch.countDown();
                }
            } finally {
                prepLatch.countDown();
            }
        });

        try {
            if (!prepLatch.await(Math.min(budget, 2000L), TimeUnit.MILLISECONDS)) {
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }

        if (destroyed) return null;

        if (canPixelCopy.get()) {
            try {
                visualLatch.await(Math.min(500L, budget), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            long left = Math.max(300L, budget - (System.currentTimeMillis() - start));
            Bitmap pxBmp = requestPixelCopyFromBg(web, widthRef.get(), heightRef.get(), left);
            if (pxBmp != null) return pxBmp;
        }

        final AtomicReference<Bitmap> drawRef = new AtomicReference<>();
        final CountDownLatch drawLatch = new CountDownLatch(1);
        mainHandler.post(() -> {
            try {
                if (!destroyed) drawRef.set(drawWebView(web));
            } finally {
                drawLatch.countDown();
            }
        });
        long left = Math.max(500L, budget - (System.currentTimeMillis() - start));
        try {
            drawLatch.await(left, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return drawRef.get();
    }

    private Bitmap captureOnMain(WebView web, long budget) {
        if (web.getParent() == root) web.bringToFront();
        web.setVisibility(View.VISIBLE);
        try {
            web.onResume();
        } catch (Throwable ignored) {}
        int[] wh = resolveTargetSize(web);
        if (web.getWidth() <= 0 || web.getHeight() <= 0) {
            layoutFallbackChild(web, wh[0], wh[1]);
        }
        if (!overlayFallback
                && presentation != null
                && presentation.getWindow() != null
                && Build.VERSION.SDK_INT >= 26
                && web.getParent() == root
                && web.isAttachedToWindow()) {
            Bitmap bmp = requestPixelCopyDirect(presentation.getWindow(), web, web.getWidth(), web.getHeight(), budget);
            if (bmp != null) return bmp;
        }
        return drawWebView(web);
    }

    private Bitmap requestPixelCopyFromBg(WebView web, int w, int h, long timeoutMs) {
        if (w <= 0 || h <= 0 || Build.VERSION.SDK_INT < 26) return null;
        final AtomicReference<Bitmap> bmpRef = new AtomicReference<>();
        final AtomicInteger codeRef = new AtomicInteger(-1);
        final CountDownLatch reqLatch = new CountDownLatch(1);
        final CountDownLatch copyLatch = new CountDownLatch(1);
        final AtomicBoolean requested = new AtomicBoolean(false);

        mainHandler.post(() -> {
            try {
                if (destroyed || presentation == null) return;
                Window wnd = presentation.getWindow();
                if (wnd == null || wnd.peekDecorView() == null || !web.isAttachedToWindow()) return;
                int vw = web.getWidth() > 0 ? web.getWidth() : w;
                int vh = web.getHeight() > 0 ? web.getHeight() : h;
                if (vw <= 0 || vh <= 0) return;
                Rect srcRect = computeWindowRect(web, vw, vh);
                if (srcRect.width() <= 0 || srcRect.height() <= 0) return;
                Bitmap bmp = Bitmap.createBitmap(srcRect.width(), srcRect.height(), Bitmap.Config.ARGB_8888);
                bmpRef.set(bmp);
                PixelCopy.request(wnd, srcRect, bmp, result -> {
                    codeRef.set(result);
                    copyLatch.countDown();
                }, drainHandler);
                requested.set(true);
            } catch (Throwable t) {
                Log.w(TAG, "PixelCopy.request failed", t);
            } finally {
                reqLatch.countDown();
            }
        });

        try {
            if (!reqLatch.await(Math.min(timeoutMs, 1500L), TimeUnit.MILLISECONDS)) {
                return null;
            }
            if (!requested.get()) {
                Bitmap b = bmpRef.get();
                if (b != null) b.recycle();
                return null;
            }
            if (!copyLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }

        Bitmap bmp = bmpRef.get();
        if (codeRef.get() != PixelCopy.SUCCESS || bmp == null) {
            if (bmp != null) bmp.recycle();
            return null;
        }
        return bmp;
    }

    private Bitmap requestPixelCopyDirect(Window wnd, WebView web, int w, int h, long timeoutMs) {
        if (wnd == null || w <= 0 || h <= 0 || Build.VERSION.SDK_INT < 26) return null;
        if (wnd.peekDecorView() == null) return null;
        Rect srcRect = computeWindowRect(web, w, h);
        if (srcRect.width() <= 0 || srcRect.height() <= 0) return null;
        final Bitmap bmp = Bitmap.createBitmap(srcRect.width(), srcRect.height(), Bitmap.Config.ARGB_8888);
        final AtomicInteger codeRef = new AtomicInteger(-1);
        final CountDownLatch copyLatch = new CountDownLatch(1);
        try {
            PixelCopy.request(wnd, srcRect, bmp, result -> {
                codeRef.set(result);
                copyLatch.countDown();
            }, drainHandler);
            if (!copyLatch.await(Math.min(timeoutMs, 2000L), TimeUnit.MILLISECONDS)) {
                return null;
            }
        } catch (Throwable t) {
            bmp.recycle();
            return null;
        }
        if (codeRef.get() != PixelCopy.SUCCESS) {
            bmp.recycle();
            return null;
        }
        return bmp;
    }

    private static Rect computeWindowRect(WebView web, int w, int h) {
        int[] loc = new int[2];
        web.getLocationInWindow(loc);
        int left = Math.max(0, Math.min(VD_W - 1, loc[0]));
        int top = Math.max(0, Math.min(VD_H - 1, loc[1]));
        int right = Math.max(left, Math.min(VD_W, left + w));
        int bottom = Math.max(top, Math.min(VD_H, top + h));
        return new Rect(left, top, right, bottom);
    }

    private Bitmap drawWebView(WebView web) {
        if (web == null) return null;
        try {
            int[] wh = resolveTargetSize(web);
            int w = web.getWidth() > 0 ? web.getWidth() : wh[0];
            int h = web.getHeight() > 0 ? web.getHeight() : wh[1];
            if (w <= 0 || h <= 0) return null;
            if (web.getWidth() != w || web.getHeight() != h) {
                layoutFallbackChild(web, w, h);
            }
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.eraseColor(Color.WHITE);
            Canvas c = new Canvas(bmp);
            c.translate(-web.getScrollX(), -web.getScrollY());
            web.draw(c);
            return bmp;
        } catch (Throwable t) {
            Log.w(TAG, "drawWebView failed", t);
            return null;
        }
    }

    private static int[] resolveTargetSize(WebView web) {
        ViewGroup.LayoutParams lp = web.getLayoutParams();
        int w = (lp != null && lp.width > 0) ? lp.width : (web.getWidth() > 0 ? web.getWidth() : MOBILE_W);
        int h = (lp != null && lp.height > 0) ? lp.height : (web.getHeight() > 0 ? web.getHeight() : MOBILE_H);
        return new int[]{w, h};
    }

    private static void layoutFallbackRoot(FrameLayout root) {
        root.measure(
                View.MeasureSpec.makeMeasureSpec(VD_W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(VD_H, View.MeasureSpec.EXACTLY)
        );
        root.layout(0, 0, VD_W, VD_H);
    }

    private void layoutFallbackChild(WebView web, int w, int h) {
        layoutFallbackRoot(root);
        if (web != null) {
            web.measure(
                    View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            );
            web.layout(0, 0, w, h);
        }
    }

    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        try {
            root.removeAllViews();
        } catch (Throwable ignored) {}
        if (presentation != null) {
            try {
                presentation.dismiss();
            } catch (Throwable ignored) {}
            presentation = null;
        }
        if (virtualDisplay != null) {
            try {
                virtualDisplay.release();
            } catch (Throwable ignored) {}
        }
        if (imageReader != null) {
            try {
                imageReader.setOnImageAvailableListener(null, null);
            } catch (Throwable ignored) {}
            try {
                imageReader.close();
            } catch (Throwable ignored) {}
        }
        try {
            drainThread.quitSafely();
        } catch (Throwable ignored) {}
    }

    private static boolean isActivityContext(Context ctx) {
        Context c = ctx;
        int guard = 0;
        while (c != null && guard++ < 10) {
            if (c instanceof Activity) return true;
            if (c instanceof ContextWrapper) c = ((ContextWrapper) c).getBaseContext();
            else break;
        }
        return false;
    }
}
