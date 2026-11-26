package com.xm486.pimet.ui;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Random;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 原生硬件级输入引擎（主进程 {@link BrowserAgent} 与远程代理子进程 {@link ProxyWorkerRuntime} 共用）。
 * <p>
 * 提供 100% {@code event.isTrusted === true} 的触摸、拟人贝塞尔拖拽与逐字按键/IME 输入：
 * <ul>
 *   <li>{@link #drag}：二次贝塞尔微弯曲线 + ease-in-out cubic 变速 + ±1.2px 手指微颤 + 真实压力/接触面积波动 + 真实墙钟时间推进；</li>
 *   <li>{@link #nativeType}：真逐字按键（{@link KeyCharacterMap#VIRTUAL_KEYBOARD}）+ IME {@link InputConnection#commitText} + 词间/思考自然停顿；</li>
 *   <li>{@link #parseClickCoords} &amp; {@link #nativeTapOnMain}：统一坐标解析与原生硬件级触摸点击。</li>
 * </ul>
 */
public final class NativeInputHelper {

    private static final String VW_JS =
            "(function(){try{var vv=window.visualViewport;return (vv&&vv.width>0)?vv.width:(window.innerWidth||0);}catch(e){return 0;}})()";

    private static final Pattern COORD_PATTERN =
            Pattern.compile("^\\s*\\[\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\]\\s*$");

    private NativeInputHelper() {}

    public interface HostBridge {
        <T> T onMain(Callable<T> task, long timeoutMs) throws Exception;
        void sleep(long ms) throws Exception;
        void checkBeforeStep() throws Exception;
        boolean isWebViewReady(WebView w);
        void setAgentDispatching(boolean dispatching);
        String evalRawSafe(String js, long timeoutMs);
        boolean isDesktop();
    }

    public static final class DragResult {
        public final double fromX;
        public final double fromY;
        public final double toX;
        public final double toY;
        public final int steps;
        public final long durationMs;

        public DragResult(double fromX, double fromY, double toX, double toY, int steps, long durationMs) {
            this.fromX = fromX;
            this.fromY = fromY;
            this.toX = toX;
            this.toY = toY;
            this.steps = steps;
            this.durationMs = durationMs;
        }
    }

    public static final class TypeResult {
        public final int typed;
        public final int keyEvents;
        public final int imeCommits;
        public final long durationMs;

        public TypeResult(int typed, int keyEvents, int imeCommits, long durationMs) {
            this.typed = typed;
            this.keyEvents = keyEvents;
            this.imeCommits = imeCommits;
            this.durationMs = durationMs;
        }
    }

    /**
     * 解析显式坐标点击参数（支持 x/y 数值字段，或 target/ref 为 [x, y] JSON 数组 / "[x, y]" 字符串）。
     * 返回 double[]{cssX, cssY}，如果不是坐标模式返回 null。
     */
    public static double[] parseClickCoords(JSONObject req) {
        if (req == null) return null;
        if (req.has("x") && req.has("y") && !req.isNull("x") && !req.isNull("y")) {
            double x = req.optDouble("x", Double.NaN);
            double y = req.optDouble("y", Double.NaN);
            if (!Double.isNaN(x) && !Double.isNaN(y) && !Double.isInfinite(x) && !Double.isInfinite(y)) {
                return new double[]{x, y};
            }
        }
        Object raw = req.has("target") && !req.isNull("target") ? req.opt("target")
                : (req.has("ref") && !req.isNull("ref") ? req.opt("ref") : null);
        if (raw instanceof JSONArray) {
            JSONArray arr = (JSONArray) raw;
            if (arr.length() == 2) {
                double x = arr.optDouble(0, Double.NaN);
                double y = arr.optDouble(1, Double.NaN);
                if (!Double.isNaN(x) && !Double.isNaN(y) && !Double.isInfinite(x) && !Double.isInfinite(y)) {
                    return new double[]{x, y};
                }
            }
        } else if (raw != null) {
            String s = String.valueOf(raw).trim();
            Matcher m = COORD_PATTERN.matcher(s);
            if (m.matches()) {
                try {
                    double x = Double.parseDouble(m.group(1));
                    double y = Double.parseDouble(m.group(2));
                    if (!Double.isNaN(x) && !Double.isNaN(y) && !Double.isInfinite(x) && !Double.isInfinite(y)) {
                        return new double[]{x, y};
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }

    /** 读取页面当前 CSS 视口宽度（visualViewport.width / innerWidth），失败时回退容器默认视口宽度 */
    public static double queryViewportWidth(HostBridge bridge, WebView w) {
        String raw = bridge.evalRawSafe(VW_JS, 2000L);
        if (raw != null) {
            String s = raw.trim();
            if (!s.isEmpty() && !"null".equals(s)) {
                try {
                    double v = Double.parseDouble(s);
                    if (v > 0 && !Double.isNaN(v) && !Double.isInfinite(v)) {
                        return v;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        return ContainerEnv.viewportWidth(bridge.isDesktop());
    }

    /** 构造带真实手指参数与 TOOL_TYPE_FINGER 的 Touchscreen MotionEvent（调用方负责 recycle） */
    public static MotionEvent obtainTouchEvent(
            long downTime,
            long eventTime,
            int action,
            float x,
            float y,
            float pressure,
            float size,
            float touchMajor,
            float touchMinor
    ) {
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[1];
        MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
        pp.id = 0;
        pp.toolType = MotionEvent.TOOL_TYPE_FINGER;
        props[0] = pp;

        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
        MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
        pc.x = x;
        pc.y = y;
        pc.pressure = pressure;
        pc.size = size;
        pc.touchMajor = touchMajor;
        pc.touchMinor = touchMinor;
        coords[0] = pc;

        return MotionEvent.obtain(
                downTime,
                eventTime,
                action,
                1,
                props,
                coords,
                0,
                0,
                1.0f,
                1.0f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0
        );
    }

    /**
     * 主线程直接触发一次原生手指点击（供主进程与子进程 nativeTap 共用）。
     * 调用前应由调用方设置好 sAgentTouch（如主进程需要）并在 finally 重置。
     */
    public static boolean nativeTapOnMain(WebView w, double cssX, double cssY, double vw) {
        if (w == null || w.getWidth() <= 0 || w.getHeight() <= 0 || !(vw > 0)) return false;
        if (Double.isNaN(cssX) || Double.isNaN(cssY)) return false;
        float ratio = (float) (w.getWidth() / vw);
        float x = (float) (cssX * ratio);
        float y = (float) (cssY * ratio);
        if (x < 0 || y < 0 || x > w.getWidth() || y > w.getHeight()) return false;
        if (w.getWidth() > 2) x = clampFloat(x, 1f, w.getWidth() - 2f);
        if (w.getHeight() > 2) y = clampFloat(y, 1f, w.getHeight() - 2f);

        long down = SystemClock.uptimeMillis();
        MotionEvent d = obtainTouchEvent(down, down, MotionEvent.ACTION_DOWN, x, y, 0.56f, 0.11f, 22f, 19f);
        MotionEvent u = obtainTouchEvent(down, down + 60L, MotionEvent.ACTION_UP, x, y, 0f, 0.11f, 22f, 19f);
        boolean a, b;
        try {
            a = w.dispatchTouchEvent(d);
            b = w.dispatchTouchEvent(u);
        } finally {
            d.recycle();
            u.recycle();
        }
        return a || b;
    }

    /** 原生硬件级拟人贝塞尔拖拽（isTrusted === true） */
    public static DragResult drag(HostBridge bridge, WebView w, JSONObject req) throws Exception {
        if (req == null
                || !req.has("from_x") || req.isNull("from_x")
                || !req.has("from_y") || req.isNull("from_y")
                || !req.has("to_x") || req.isNull("to_x")
                || !req.has("to_y") || req.isNull("to_y")) {
            throw new IllegalArgumentException("drag 需要 from_x, from_y, to_x, to_y");
        }
        final double fromX = req.optDouble("from_x", Double.NaN);
        final double fromY = req.optDouble("from_y", Double.NaN);
        final double toX = req.optDouble("to_x", Double.NaN);
        final double toY = req.optDouble("to_y", Double.NaN);
        if (Double.isNaN(fromX) || Double.isNaN(fromY) || Double.isNaN(toX) || Double.isNaN(toY)
                || Double.isInfinite(fromX) || Double.isInfinite(fromY)
                || Double.isInfinite(toX) || Double.isInfinite(toY)) {
            throw new IllegalArgumentException("drag 坐标必须为有效数字");
        }

        double dist = Math.hypot(toX - fromX, toY - fromY);
        int defaultDuration = Math.max(360, Math.min(820, (int) Math.round(dist * 1.4 + 260.0)));
        int duration = (req.has("duration") && !req.isNull("duration"))
                ? clampInt(req.optInt("duration", defaultDuration), 150, 2500)
                : defaultDuration;
        int defaultSteps = Math.max(16, Math.min(48, duration / 18));
        int steps = (req.has("steps") && !req.isNull("steps"))
                ? clampInt(req.optInt("steps", defaultSteps), 2, 120)
                : defaultSteps;

        Random rand = new Random();
        int holdMs = (req.has("hold_ms") && !req.isNull("hold_ms"))
                ? clampInt(req.optInt("hold_ms", 75), 0, 1000)
                : (55 + rand.nextInt(41));

        bridge.checkBeforeStep();
        double vw = queryViewportWidth(bridge, w);
        if (!(vw > 0)) {
            throw new IllegalStateException("无法获取页面视口宽度");
        }

        final int[] wh = bridge.onMain(() -> {
            if (w == null || !bridge.isWebViewReady(w)) return null;
            int ww = w.getWidth();
            int hh = w.getHeight();
            if (ww <= 4 || hh <= 4) return null;
            return new int[]{ww, hh};
        }, 3000L);
        if (wh == null) {
            throw new IllegalStateException("WebView 未就绪或处于无窗口降级模式，无法执行原生拖拽");
        }

        final int viewW = wh[0];
        final int viewH = wh[1];
        float scale = (float) (viewW / vw);
        final float sx = clampFloat((float) (fromX * scale), 1f, viewW - 2f);
        final float sy = clampFloat((float) (fromY * scale), 1f, viewH - 2f);
        final float ex = clampFloat((float) (toX * scale), 1f, viewW - 2f);
        final float ey = clampFloat((float) (toY * scale), 1f, viewH - 2f);

        double dx = ex - sx;
        double dy = ey - sy;
        double physDist = Math.hypot(dx, dy);
        double cx;
        double cy;
        if (physDist > 1e-3) {
            double nx = -dy / physDist;
            double ny = dx / physDist;
            double sign = rand.nextBoolean() ? 1.0 : -1.0;
            double mag = physDist * (0.06 + rand.nextDouble() * 0.08);
            cx = (sx + ex) * 0.5 + nx * mag * sign;
            cy = (sy + ey) * 0.5 + ny * mag * sign;
        } else {
            cx = sx;
            cy = sy;
        }

        long wallStart = SystemClock.uptimeMillis();
        float pressure = 0.54f + rand.nextFloat() * 0.12f;
        float size = 0.10f + rand.nextFloat() * 0.03f;
        float touchMajor = 21f + rand.nextFloat() * 4f;
        float touchMinor = 18f + rand.nextFloat() * 4f;

        final float downP = pressure, downS = size, downMaj = touchMajor, downMin = touchMinor;
        final long downTime = bridge.onMain(() -> {
            if (!bridge.isWebViewReady(w)) throw new IllegalStateException("WebView 状态已变化，无法起始拖拽");
            long dt = SystemClock.uptimeMillis();
            MotionEvent ev = obtainTouchEvent(dt, dt, MotionEvent.ACTION_DOWN, sx, sy,
                    downP, downS, downMaj, downMin);
            bridge.setAgentDispatching(true);
            try {
                w.dispatchTouchEvent(ev);
            } finally {
                bridge.setAgentDispatching(false);
                ev.recycle();
            }
            return dt;
        }, 3000L);

        boolean upSent = false;
        float lastX = sx;
        float lastY = sy;
        try {
            if (holdMs > 0) {
                bridge.sleep(holdMs);
            }
            double baseStepMs = (double) duration / steps;
            for (int i = 1; i <= steps; i++) {
                long sleepMs = Math.max(4L, Math.round(baseStepMs * (0.8 + rand.nextDouble() * 0.4)));
                bridge.sleep(sleepMs);
                bridge.checkBeforeStep();

                double u = (double) i / steps;
                double t = (u < 0.5)
                        ? (4.0 * u * u * u)
                        : (1.0 - Math.pow(-2.0 * u + 2.0, 3.0) / 2.0);
                double omt = 1.0 - t;
                double bx = omt * omt * sx + 2.0 * omt * t * cx + t * t * ex;
                double by = omt * omt * sy + 2.0 * omt * t * cy + t * t * ey;
                if (i < steps) {
                    bx += (rand.nextDouble() * 2.0 - 1.0) * 1.2;
                    by += (rand.nextDouble() * 2.0 - 1.0) * 1.2;
                } else {
                    bx = ex;
                    by = ey;
                }
                final float mx = clampFloat((float) bx, 1f, viewW - 2f);
                final float my = clampFloat((float) by, 1f, viewH - 2f);
                lastX = mx;
                lastY = my;

                pressure = clampFloat(pressure + (rand.nextFloat() - 0.5f) * 0.05f, 0.48f, 0.72f);
                size = clampFloat(size + (rand.nextFloat() - 0.5f) * 0.015f, 0.08f, 0.14f);
                touchMajor = clampFloat(touchMajor + (rand.nextFloat() - 0.5f) * 2.0f, 18f, 28f);
                touchMinor = clampFloat(touchMinor + (rand.nextFloat() - 0.5f) * 2.0f, 16f, 24f);
                final float mp = pressure, ms = size, mmaj = touchMajor, mmin = touchMinor;

                bridge.onMain(() -> {
                    if (!bridge.isWebViewReady(w)) return null;
                    long now = Math.max(downTime, SystemClock.uptimeMillis());
                    MotionEvent ev = obtainTouchEvent(downTime, now, MotionEvent.ACTION_MOVE, mx, my,
                            mp, ms, mmaj, mmin);
                    bridge.setAgentDispatching(true);
                    try {
                        w.dispatchTouchEvent(ev);
                    } finally {
                        bridge.setAgentDispatching(false);
                        ev.recycle();
                    }
                    return null;
                }, 3000L);
            }

            bridge.sleep(35L + rand.nextInt(36));
            bridge.checkBeforeStep();

            final float upS = size, upMaj = touchMajor, upMin = touchMinor;
            bridge.onMain(() -> {
                if (!bridge.isWebViewReady(w)) return null;
                long now = Math.max(downTime, SystemClock.uptimeMillis());
                MotionEvent ev = obtainTouchEvent(downTime, now, MotionEvent.ACTION_UP, ex, ey,
                        0f, upS, upMaj, upMin);
                bridge.setAgentDispatching(true);
                try {
                    w.dispatchTouchEvent(ev);
                } finally {
                    bridge.setAgentDispatching(false);
                    ev.recycle();
                }
                return null;
            }, 3000L);
            upSent = true;
        } finally {
            if (!upSent && w != null) {
                final float cxEnd = lastX;
                final float cyEnd = lastY;
                try {
                    w.post(() -> {
                        try {
                            long now = Math.max(downTime, SystemClock.uptimeMillis());
                            MotionEvent ev = obtainTouchEvent(downTime, now, MotionEvent.ACTION_CANCEL,
                                    cxEnd, cyEnd, 0f, 0.1f, 20f, 18f);
                            bridge.setAgentDispatching(true);
                            try {
                                w.dispatchTouchEvent(ev);
                            } finally {
                                bridge.setAgentDispatching(false);
                                ev.recycle();
                            }
                        } catch (Throwable ignored) {}
                    });
                } catch (Throwable ignored) {}
            }
        }

        long actualMs = Math.max(1L, SystemClock.uptimeMillis() - wallStart);
        return new DragResult(fromX, fromY, toX, toY, steps, actualMs);
    }

    /** 原生硬件级逐字按键 / IME 输入（isTrusted === true） */
    public static TypeResult nativeType(HostBridge bridge, WebView w, JSONObject req) throws Exception {
        if (req == null || (!req.has("text") && !req.has("value"))) {
            throw new IllegalArgumentException("native_type 需要 text");
        }
        String text = req.has("text") ? req.optString("text", "") : req.optString("value", "");
        if (text == null) text = "";
        boolean clear = req.optBoolean("clear", false);
        int minDelay = clampInt(req.optInt("min_delay_ms", 55), 0, 2000);
        int maxDelay = clampInt(req.optInt("max_delay_ms", 145), minDelay, 5000);
        boolean submit = req.optBoolean("submit", false);

        bridge.checkBeforeStep();
        Boolean webReady = bridge.onMain(() -> {
            if (w == null) return false;
            if (!w.hasFocus()) w.requestFocus();
            return true;
        }, 3000L);
        if (!Boolean.TRUE.equals(webReady)) {
            throw new IllegalStateException("标签 WebView 不可用");
        }

        long wallStart = SystemClock.uptimeMillis();
        int keyEvents = 0;
        int imeCommits = 0;
        Random rand = new Random();

        if (clear) {
            bridge.checkBeforeStep();
            Integer selKeys = bridge.onMain(() -> {
                if (!w.hasFocus()) w.requestFocus();
                long now = SystemClock.uptimeMillis();
                bridge.setAgentDispatching(true);
                try {
                    int ctrlMeta = KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
                    w.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0,
                            ctrlMeta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
                    long upNow = Math.max(now, SystemClock.uptimeMillis());
                    w.dispatchKeyEvent(new KeyEvent(now, upNow, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, 0,
                            ctrlMeta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
                } finally {
                    bridge.setAgentDispatching(false);
                }
                return 2;
            }, 3000L);
            keyEvents += (selKeys != null ? selKeys : 0);
            bridge.sleep(40L);

            bridge.checkBeforeStep();
            Integer delKeys = bridge.onMain(() -> {
                if (!w.hasFocus()) w.requestFocus();
                long now = SystemClock.uptimeMillis();
                bridge.setAgentDispatching(true);
                try {
                    w.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, 0,
                            0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
                    long upNow = Math.max(now, SystemClock.uptimeMillis());
                    w.dispatchKeyEvent(new KeyEvent(now, upNow, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL, 0,
                            0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
                } finally {
                    bridge.setAgentDispatching(false);
                }
                return 2;
            }, 3000L);
            keyEvents += (delKeys != null ? delKeys : 0);
            bridge.sleep(60L);
        }

        KeyCharacterMap kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        int idx = 0;
        int len = text.length();
        while (idx < len) {
            bridge.checkBeforeStep();
            int cp = text.codePointAt(idx);
            int cc = Character.charCount(cp);
            final String chStr = text.substring(idx, idx + cc);
            idx += cc;

            KeyEvent[] rawEvents = null;
            if (cc == 1 && kcm != null) {
                rawEvents = kcm.getEvents(new char[]{chStr.charAt(0)});
            }

            if (rawEvents != null && rawEvents.length > 0) {
                final KeyEvent[] evs = rawEvents;
                Integer sent = bridge.onMain(() -> {
                    if (!w.hasFocus()) w.requestFocus();
                    long downT = SystemClock.uptimeMillis();
                    int cnt = 0;
                    bridge.setAgentDispatching(true);
                    try {
                        for (KeyEvent kev : evs) {
                            long now = Math.max(downT, SystemClock.uptimeMillis());
                            KeyEvent dispatched = cloneKeyEventWithTime(kev, downT, now);
                            w.dispatchKeyEvent(dispatched);
                            cnt++;
                        }
                    } finally {
                        bridge.setAgentDispatching(false);
                    }
                    return cnt;
                }, 3000L);
                keyEvents += (sent != null ? sent : 0);
            } else {
                int[] counts = bridge.onMain(() -> {
                    if (!w.hasFocus()) w.requestFocus();
                    int ke = 0;
                    int icCnt = 0;
                    bridge.setAgentDispatching(true);
                    try {
                        InputConnection ic = w.onCreateInputConnection(new EditorInfo());
                        boolean committed = ic != null && ic.commitText(chStr, 1);
                        if (committed) {
                            icCnt++;
                        } else {
                            w.dispatchKeyEvent(newStringKeyEvent(SystemClock.uptimeMillis(), chStr));
                            ke++;
                        }
                    } finally {
                        bridge.setAgentDispatching(false);
                    }
                    return new int[]{ke, icCnt};
                }, 3000L);
                if (counts != null) {
                    keyEvents += counts[0];
                    imeCommits += counts[1];
                }
            }

            int delay = (maxDelay <= minDelay) ? minDelay : (minDelay + rand.nextInt(maxDelay - minDelay + 1));
            char firstCh = chStr.charAt(0);
            if (firstCh == ' ' || firstCh == ',' || firstCh == '.' || firstCh == '，' || firstCh == '。') {
                delay += 40 + rand.nextInt(71);
            }
            if (rand.nextDouble() < 0.12) {
                delay += 80 + rand.nextInt(81);
            }
            if (delay > 0) {
                bridge.sleep(delay);
            }
        }

        if (submit) {
            bridge.checkBeforeStep();
            bridge.sleep(120L + rand.nextInt(101));
            bridge.checkBeforeStep();
            final long enterDown = bridge.onMain(() -> {
                if (!w.hasFocus()) w.requestFocus();
                long now = SystemClock.uptimeMillis();
                bridge.setAgentDispatching(true);
                try {
                    w.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0,
                            0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
                } finally {
                    bridge.setAgentDispatching(false);
                }
                return now;
            }, 3000L);
            keyEvents++;
            bridge.sleep(35L);
            bridge.onMain(() -> {
                long now = Math.max(enterDown, SystemClock.uptimeMillis());
                bridge.setAgentDispatching(true);
                try {
                    w.dispatchKeyEvent(new KeyEvent(enterDown, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0,
                            0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
                } finally {
                    bridge.setAgentDispatching(false);
                }
                return null;
            }, 3000L);
            keyEvents++;
        }

        long actualMs = Math.max(1L, SystemClock.uptimeMillis() - wallStart);
        return new TypeResult(len, keyEvents, imeCommits, actualMs);
    }

    @SuppressWarnings("deprecation")
    private static KeyEvent cloneKeyEventWithTime(KeyEvent src, long downTime, long eventTime) {
        if (src.getAction() == KeyEvent.ACTION_MULTIPLE
                && src.getKeyCode() == KeyEvent.KEYCODE_UNKNOWN
                && src.getCharacters() != null) {
            return new KeyEvent(eventTime, src.getCharacters(), KeyCharacterMap.VIRTUAL_KEYBOARD, src.getFlags());
        }
        return new KeyEvent(
                downTime,
                eventTime,
                src.getAction(),
                src.getKeyCode(),
                src.getRepeatCount(),
                src.getMetaState(),
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                src.getScanCode(),
                src.getFlags(),
                InputDevice.SOURCE_KEYBOARD
        );
    }

    @SuppressWarnings("deprecation")
    private static KeyEvent newStringKeyEvent(long eventTime, String chars) {
        return new KeyEvent(eventTime, chars, KeyCharacterMap.VIRTUAL_KEYBOARD, 0);
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float clampFloat(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
