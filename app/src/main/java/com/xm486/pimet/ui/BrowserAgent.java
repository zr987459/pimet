package com.xm486.pimet.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 让容器里的 Pi 操控「Pi 浏览器」悬浮窗（经 AppBridge 127.0.0.1:30143，需令牌，令牌在 AppBridgeManager 里校验）。
 * <p>
 * 思路借鉴 Playwright MCP / browser-use：每次返回页面的「快照」，把可交互元素编号成 [e1] [e2]…，
 * Pi 再用编号点击、输入；点击优先走真实触摸事件（isTrusted），用户在窗口里能看到每一步并随时接手。
 * <p>
 * 标签页：默认操作 Pi 专属标签（没有就创建），请求带 tab:"user" 才操作用户正在看的标签，也可以带标签 id。
 * 每个标签一把锁，不同标签上的任务可以并行；同一标签上的操作串行。
 * 用户在窗口里点「停止 Pi」后，所有 browser 请求都返回「已叫停」，直到用户点「允许 Pi」。
 * <p>
 * 页面端的辅助函数（快照、定位、输入…）在 assets/pimet-browser/agent.js，每次调用前注入（带版本号，已注入则跳过）。
 */
public final class BrowserAgent {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** 窗口级操作（关闭窗口、开关标签）用的锁；页面操作用各标签自己的锁 */
    private static final Object WINDOW_LOCK = new Object();
    private static volatile String sAgentJs;

    static final String STOPPED_MSG = "用户已叫停 Pi 的浏览器操作。请立即停止当前的浏览器任务，告诉用户进展，等用户在浏览器窗口标签栏点「▶ 允许 Pi」后再继续。";

    private BrowserAgent() {}

    public static JSONObject handle(Context ctx, JSONObject req) {
        try {
            if (FloatingApps.isPiStopped()) return err(STOPPED_MSG);
            return doHandle(ctx, req);
        } catch (StoppedException s) {
            return err(STOPPED_MSG);
        } catch (Throwable t) {
            return err("浏览器操作失败: " + t);
        }
    }

    private static final class StoppedException extends RuntimeException {}

    private static void checkStop() {
        if (FloatingApps.isPiStopped()) throw new StoppedException();
    }

    private static JSONObject doHandle(Context ctx, JSONObject req) throws Exception {
        String action = req.optString("browser_action", req.optString("op", "snapshot")).trim().toLowerCase();
        int timeout = clamp(req.optInt("timeout", 20000), 1000, 120000);
        ensureAgentJs(ctx);

        if (action.equals("close")) {
            synchronized (WINDOW_LOCK) {
                onMain(() -> {
                    FloatingApps f = FloatingApps.current();
                    if (f != null) f.agentClose();
                    return null;
                }, 5000);
            }
            return ok().put("result", "已关闭浏览器窗口");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(ctx)) {
            return err("PiMet 没有悬浮窗权限，无法打开浏览器窗口。请让用户在系统设置里给 PiMet 开启「显示在其他应用上层」。");
        }
        final FloatingApps win;
        synchronized (WINDOW_LOCK) {
            win = onMain(() -> FloatingApps.ensureForAgent(ctx), 8000);
        }
        if (win == null) return err("浏览器窗口创建失败");

        // ---- 窗口级 / 标签管理
        switch (action) {
            case "tabs":
                return ok().put("tabs", onMain(win::agentTabsJson, 3000)).put("result", onMain(win::agentTabsText, 3000));
            case "new_tab": {
                String url = normalize(req.optString("url", ""));
                FloatingApps.Tab t;
                synchronized (WINDOW_LOCK) {
                    t = onMain(() -> win.agentNewPiTab(), 5000);
                }
                if (!url.isEmpty()) {
                    synchronized (t.lock) {
                        onMain(() -> { win.agentLoad(t, url); return null; }, 5000);
                        SystemClock.sleep(300);
                        waitLoaded(t, timeout);
                    }
                }
                return ok().put("tab", t.id).put("url", t.url).put("result", "已新建 Pi 标签 " + t.id + (url.isEmpty() ? "" : " 并打开 " + url));
            }
            case "switch_tab": {
                String id = req.optString("tab", "");
                Boolean done = onMain(() -> win.agentSwitch(id), 3000);
                if (!Boolean.TRUE.equals(done)) return err("没有标签 " + id + "，用 tabs 查看");
                return ok().put("tab", id).put("result", "已切换到标签 " + id + "（用户现在看到的是它）");
            }
            case "close_tab": {
                String id = req.optString("tab", "pi");
                String r;
                synchronized (WINDOW_LOCK) {
                    r = onMain(() -> win.agentCloseTab(id), 3000);
                }
                if (r != null) return err(r);
                return ok().put("result", "已关闭标签 " + id);
            }
            case "show":
                onMain(() -> { win.agentRestore(); return null; }, 3000);
                return ok().put("result", "浏览器窗口已显示");
            case "hide":
            case "minimize":
                onMain(() -> { win.agentMinimize(); return null; }, 3000);
                return ok().put("result", "浏览器窗口已最小化（仍可继续操作，但只能用 JS 点击，也不能截图）");
            case "downloads":
                return ok().put("result", FloatingApps.downloadsText(ctx));
            case "reload_ui":
            case "reload_config":
                onMain(() -> { win.reloadConfigNow(); return null; }, 3000);
                return ok().put("result", "已重新加载浏览器配置（config.json）；用户脚本会在下次打开/刷新网页时生效");
            default:
                break;
        }

        // ---- 页面操作：先找到标签，再按标签加锁
        final String tabSpec = req.optString("tab", "");
        final FloatingApps.Tab tab;
        synchronized (WINDOW_LOCK) {
            tab = onMain(() -> win.agentTab(tabSpec), 5000);
        }
        if (tab == null) return err("没有标签 " + tabSpec + "，用 tabs 查看（\"user\" = 用户当前的标签，省略 = Pi 标签）");
        synchronized (tab.lock) {
            onMain(() -> { win.agentBegin(tab); return null; }, 3000);
            try {
                JSONObject out = doTab(win, tab, action, req, timeout);
                if (out.optBoolean("success")) out.put("tab", tab.id);
                return out;
            } finally {
                MAIN.post(() -> win.agentEnd(tab));
            }
        }
    }

    private static JSONObject doTab(FloatingApps win, FloatingApps.Tab tab, String action, JSONObject req, int timeout) throws Exception {
        boolean wantSnapshot = req.optBoolean("snapshot", true);
        String result;
        JSONObject out = ok();
        switch (action) {
            case "open":
            case "navigate":
            case "goto": {
                String url = normalize(req.optString("url", ""));
                if (url.isEmpty()) return err("缺少 url");
                onMain(() -> { win.agentLoad(tab, url); return null; }, 5000);
                SystemClock.sleep(300);
                waitLoaded(tab, timeout);
                result = "已打开 " + url;
                break;
            }
            case "home":
                onMain(() -> { win.agentHome(tab); return null; }, 5000);
                SystemClock.sleep(500);
                waitLoaded(tab, timeout);
                result = "已回到首页";
                break;
            case "back":
            case "forward":
            case "reload": {
                onMain(() -> {
                    WebView w = tab.web;
                    if (action.equals("back")) { if (w.canGoBack()) w.goBack(); }
                    else if (action.equals("forward")) { if (w.canGoForward()) w.goForward(); }
                    else w.reload();
                    return null;
                }, 5000);
                SystemClock.sleep(400);
                waitLoaded(tab, timeout);
                result = action.equals("back") ? "已后退" : action.equals("forward") ? "已前进" : "已刷新";
                break;
            }
            case "snapshot":
            case "read":
                waitLoaded(tab, Math.min(timeout, 8000));
                wantSnapshot = true;
                result = null;
                break;
            case "click":
            case "tap": {
                result = click(win, tab, req);
                settle(tab, timeout);
                break;
            }
            case "type":
            case "fill": {
                result = type(win, tab, req);
                settle(tab, timeout);
                break;
            }
            case "select": {
                JSONObject r = evalAgent(tab, "A.select(" + JSONObject.quote(req.optString("ref", "")) + "," + JSONObject.quote(req.optString("value", "")) + ")", 5000);
                if (!r.optBoolean("ok")) return err(r.optString("error", "选择失败"));
                result = r.optString("result");
                settle(tab, timeout);
                break;
            }
            case "press":
            case "key": {
                String key = req.optString("key", "Enter");
                int code = keyCode(key);
                if (code == 0) return err("不支持的按键 " + key + "（支持 Enter Tab Escape Backspace Delete Space ArrowUp/Down/Left/Right PageUp PageDown Home End）");
                pressKey(win, tab, key, code);
                result = "已按 " + key;
                settle(tab, timeout);
                break;
            }
            case "scroll": {
                String js = "A.scroll(" + JSONObject.quote(req.optString("ref", "")) + "," + JSONObject.quote(req.optString("direction", "down"))
                        + "," + req.optDouble("amount", 0.8) + ")";
                JSONObject r = evalAgent(tab, js, 5000);
                if (!r.optBoolean("ok")) return err(r.optString("error", "滚动失败"));
                result = r.optString("result");
                SystemClock.sleep(350);
                break;
            }
            case "wait": {
                String text = req.optString("text", "");
                if (!text.isEmpty()) {
                    long end = SystemClock.uptimeMillis() + timeout;
                    boolean found = false;
                    while (SystemClock.uptimeMillis() < end) {
                        checkStop();
                        JSONObject r = evalAgent(tab, "A.hasText(" + JSONObject.quote(text) + ")", 3000);
                        if (r.optBoolean("v")) { found = true; break; }
                        SystemClock.sleep(400);
                    }
                    out.put("found", found);
                    result = found ? "页面已出现「" + text + "」" : "等待超时，页面没有出现「" + text + "」";
                } else {
                    long end = SystemClock.uptimeMillis() + clamp(req.optInt("ms", 1000), 0, 60000);
                    while (SystemClock.uptimeMillis() < end) {
                        checkStop();
                        SystemClock.sleep(Math.min(200, Math.max(1, end - SystemClock.uptimeMillis())));
                    }
                    waitLoaded(tab, timeout);
                    result = "已等待";
                }
                break;
            }
            case "eval":
            case "js": {
                String script = req.optString("script", "");
                if (script.isEmpty()) return err("缺少 script");
                String[] v = evalAsync(tab, script, timeout);
                if (v[0] != null) return err(v[0]).put("url", tab.url);
                return out.put("url", tab.url).put("result", v[1]);
            }
            case "screenshot": {
                if (Boolean.TRUE.equals(onMain(() -> win.minimized, 2000))) {
                    return err("浏览器窗口被用户收起（最小化）了，截不了图。不要擅自弹出窗口：能用 snapshot / eval 读页面就用它们；确实需要看画面时先问用户，或用 show 打开后再截图。");
                }
                // 后台标签在操作期间是「可见但压在当前标签下面」，刚变可见时要等一帧画出来
                SystemClock.sleep(onMain(() -> win.isActive(tab), 2000) ? 250 : 600);
                String b64 = onMain(() -> capture(tab.web), 8000);
                if (b64 == null) return err("截图失败（窗口不可见？）");
                return out.put("url", tab.url).put("image", b64).put("mime", "image/jpeg");
            }
            case "status":
                result = "url: " + tab.url;
                wantSnapshot = false;
                break;
            default:
                return err("未知操作 " + action);
        }

        if (result != null) out.put("result", result);
        if (wantSnapshot) {
            JSONObject snap = snapshot(tab, req.optInt("max_chars", 6000), req.optString("mode", "full"));
            out.put("url", snap.optString("url"));
            out.put("title", snap.optString("title"));
            out.put("snapshot", snap.optString("text"));
        } else {
            out.put("url", tab.url);
            out.put("title", tab.title);
        }
        return out;
    }

    // ------------------------------------------------------------------ 页面辅助脚本

    private static void ensureAgentJs(Context ctx) {
        if (sAgentJs != null) return;
        try (InputStream in = ctx.getAssets().open("pimet-browser/agent.js")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bo.write(buf, 0, n);
            sAgentJs = bo.toString("UTF-8");
        } catch (Throwable t) {
            throw new IllegalStateException("缺少 assets/pimet-browser/agent.js: " + t);
        }
    }

    /** 注入 agent.js 后执行 expr（可用变量 A = window.__pimetAgent），结果按 JSON 解析 */
    private static JSONObject evalAgent(FloatingApps.Tab tab, String expr, long timeoutMs) throws Exception {
        String js = "(function(){try{" + sAgentJs + "\n;var A=window.__pimetAgent;return JSON.stringify(" + expr
                + ")}catch(e){return JSON.stringify({ok:false,error:String(e)})}})()";
        String raw = evalRaw(tab, js, timeoutMs);
        if (raw == null || raw.equals("null")) return new JSONObject().put("ok", false).put("error", "页面没有返回结果（可能正在加载）");
        String s = new org.json.JSONTokener(raw).nextValue().toString();
        return new JSONObject(s);
    }

    // ------------------------------------------------------------------ 快照

    private static JSONObject snapshot(FloatingApps.Tab tab, int maxChars, String mode) throws Exception {
        int mc = clamp(maxChars, 1500, 40000);
        String m = "interactive".equalsIgnoreCase(mode) ? "interactive" : "full";
        JSONObject r = evalAgent(tab, "A.snapshot(" + mc + "," + JSONObject.quote(m) + ")", 10000);
        if (!r.has("text")) {
            r.put("url", tab.url).put("title", "").put("text", "网址: " + tab.url + "\n（页面还没准备好，无法读取快照"
                    + (r.has("error") ? "：" + r.optString("error") : "") + "）");
        }
        return r;
    }

    // ------------------------------------------------------------------ 点击 / 输入 / 按键

    private static String click(FloatingApps win, FloatingApps.Tab tab, JSONObject req) throws Exception {
        String ref = req.optString("ref", "");
        String text = req.optString("text", "");
        if (ref.isEmpty() && text.isEmpty()) throw new IllegalArgumentException("click 需要 ref 或 text");
        JSONObject loc = evalAgent(tab, "A.locate(" + JSONObject.quote(ref) + "," + JSONObject.quote(text) + ")", 5000);
        if (!loc.optBoolean("ok")) throw new IllegalStateException(loc.optString("error", "定位失败"));
        SystemClock.sleep(120);
        boolean useJs = req.optBoolean("js", false);
        boolean nativeOk = false;
        if (!useJs) {
            nativeOk = Boolean.TRUE.equals(onMain(() -> nativeTap(win, tab, loc.optDouble("x"), loc.optDouble("y"), loc.optDouble("vw")), 4000));
        }
        if (!nativeOk) evalAgent(tab, "A.jsClick()", 5000);
        String label = loc.optString("label");
        return "已点击 " + (ref.isEmpty() ? "「" + text + "」" : ref) + (label.isEmpty() ? "" : "（" + label + "）")
                + (!nativeOk && !useJs ? "（用 JS 点击）" : "")
                + (loc.optBoolean("covered") && nativeOk ? "；注意：该位置被其它元素遮挡，可能点到了遮挡物，必要时加 js=true 重试" : "");
    }

    /**
     * 真实触摸：CSS 坐标换算到 WebView 像素。坐标已由 agent.js 加上 iframe 偏移、按 visualViewport 换算。
     * 后台标签在操作期间是 VISIBLE 但压在当前标签下面（见 FloatingApps.agentBegin），触摸事件直接分发给它的 WebView，
     * 用户看不到也不受影响；窗口最小化时 WebView 不在屏幕上，返回 false 退回 JS 点击。
     */
    private static boolean nativeTap(FloatingApps win, FloatingApps.Tab tab, double cssX, double cssY, double vw) {
        WebView web = tab.web;
        if (win.minimized || web == null || web.getWidth() <= 0 || web.getHeight() <= 0 || vw <= 0 || !web.isShown()) return false;
        float ratio = (float) (web.getWidth() / vw);
        float x = (float) (cssX * ratio), y = (float) (cssY * ratio);
        if (x < 0 || y < 0 || x > web.getWidth() || y > web.getHeight()) return false;
        long down = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent u = MotionEvent.obtain(down, down + 60, MotionEvent.ACTION_UP, x, y, 0);
        d.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        u.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        FloatingApps.sAgentTouch = true;
        try {
            web.dispatchTouchEvent(d);
            web.dispatchTouchEvent(u);
        } finally {
            FloatingApps.sAgentTouch = false;
        }
        d.recycle();
        u.recycle();
        return true;
    }

    /** 当前标签发系统按键；后台标签不抢焦点（会打断用户输入），改用 JS 键盘事件 */
    private static void pressKey(FloatingApps win, FloatingApps.Tab tab, String key, int code) throws Exception {
        boolean active = Boolean.TRUE.equals(onMain(() -> win.isActive(tab) && !win.minimized, 2000));
        if (active) {
            onMain(() -> {
                tab.web.requestFocus();
                long now = SystemClock.uptimeMillis();
                tab.web.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
                tab.web.dispatchKeyEvent(new KeyEvent(now, now + 30, KeyEvent.ACTION_UP, code, 0));
                return null;
            }, 5000);
        } else {
            evalAgent(tab, "A.key(" + JSONObject.quote(key) + ")", 5000);
        }
    }

    private static String type(FloatingApps win, FloatingApps.Tab tab, JSONObject req) throws Exception {
        String ref = req.optString("ref", "");
        String text = req.optString("text", "");
        boolean clear = req.optBoolean("clear", true);
        boolean submit = req.optBoolean("submit", false);
        JSONObject r = evalAgent(tab, "A.type(" + JSONObject.quote(ref) + "," + JSONObject.quote(text) + "," + clear + ")", 5000);
        if (!r.optBoolean("ok")) throw new IllegalStateException(r.optString("error", "输入失败"));
        String res = "已输入" + (ref.isEmpty() ? "" : "到 " + ref);
        if (submit) {
            if (r.optBoolean("form")) evalAgent(tab, "A.submit()", 5000);
            else pressKey(win, tab, "Enter", KeyEvent.KEYCODE_ENTER);
            res += "并提交";
        }
        return res;
    }

    // ------------------------------------------------------------------ JS 执行

    private static String evalRaw(FloatingApps.Tab tab, String js, long timeoutMs) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> ref = new AtomicReference<>();
        MAIN.post(() -> {
            try {
                tab.web.evaluateJavascript(js, v -> { ref.set(v); latch.countDown(); });
            } catch (Throwable t) {
                latch.countDown();
            }
        });
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw new IllegalStateException("页面脚本执行超时");
        return ref.get();
    }

    /** 支持 await 的任意脚本：结果放进 window.__pimetEval[id]，轮询取回。返回 {错误或 null, 结果} */
    private static String[] evalAsync(FloatingApps.Tab tab, String script, long timeoutMs) throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "");
        String body = script.contains("return") ? script : "return (" + script + ");";
        String js = "(function(){window.__pimetEval=window.__pimetEval||{};var id=" + JSONObject.quote(id) + ";"
                + "(async function(){" + body + "\n})().then(function(v){var s;try{s=typeof v==='string'?v:JSON.stringify(v);}catch(e){s=String(v);}"
                + "window.__pimetEval[id]={ok:true,v:s===undefined?'undefined':s};},function(e){window.__pimetEval[id]={ok:false,v:String(e&&e.stack||e)};});return 1;})()";
        String started = evalRaw(tab, js, 5000);
        if (started == null || started.equals("null")) {
            return new String[]{"脚本没有运行（语法错误，或页面正在加载）。请检查代码；需要 return 时写成语句，如 return document.title;", null};
        }
        long end = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < end) {
            checkStop();
            String raw = evalRaw(tab, "(function(){var m=window.__pimetEval||{},r=m[" + JSONObject.quote(id) + "];if(!r)return JSON.stringify({done:false});delete m["
                    + JSONObject.quote(id) + "];return JSON.stringify({done:true,ok:r.ok,v:r.v});})()", 3000);
            JSONObject r = raw == null || raw.equals("null") ? new JSONObject()
                    : new JSONObject(new org.json.JSONTokener(raw).nextValue().toString());
            if (r.optBoolean("done")) {
                String v = r.optString("v");
                if (v.length() > 40000) v = v.substring(0, 40000) + "…（已截断）";
                return r.optBoolean("ok") ? new String[]{null, v} : new String[]{"脚本出错: " + v, null};
            }
            SystemClock.sleep(150);
        }
        return new String[]{"脚本超时（" + timeoutMs + "ms）未返回（页面跳转也会导致结果丢失）", null};
    }

    // ------------------------------------------------------------------ 等待 / 截图 / 工具

    private static void waitLoaded(FloatingApps.Tab tab, long timeoutMs) {
        long end = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < end) {
            checkStop();
            if (!tab.loading && tab.progress >= 100) break;
            SystemClock.sleep(150);
        }
        SystemClock.sleep(250);
    }

    /** 操作后给页面一点反应时间；若触发了跳转则等加载完 */
    private static void settle(FloatingApps.Tab tab, long timeoutMs) {
        SystemClock.sleep(500);
        if (tab.loading || tab.progress < 100) waitLoaded(tab, timeoutMs);
        else SystemClock.sleep(200);
    }

    private static String capture(WebView web) {
        int w = web.getWidth(), h = web.getHeight();
        if (w <= 0 || h <= 0 || !web.isShown()) return null;
        float scale = Math.min(1f, 900f / Math.max(w, h));
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), Bitmap.Config.RGB_565);
        Canvas c = new Canvas(bmp);
        c.scale(scale, scale);
        c.translate(-web.getScrollX(), -web.getScrollY());
        web.draw(c);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 70, bo);
        bmp.recycle();
        return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
    }

    private static int keyCode(String key) {
        switch (key.trim().toLowerCase()) {
            case "enter": case "return": return KeyEvent.KEYCODE_ENTER;
            case "tab": return KeyEvent.KEYCODE_TAB;
            case "escape": case "esc": return KeyEvent.KEYCODE_ESCAPE;
            case "backspace": return KeyEvent.KEYCODE_DEL;
            case "delete": return KeyEvent.KEYCODE_FORWARD_DEL;
            case "space": case " ": return KeyEvent.KEYCODE_SPACE;
            case "arrowup": case "up": return KeyEvent.KEYCODE_DPAD_UP;
            case "arrowdown": case "down": return KeyEvent.KEYCODE_DPAD_DOWN;
            case "arrowleft": case "left": return KeyEvent.KEYCODE_DPAD_LEFT;
            case "arrowright": case "right": return KeyEvent.KEYCODE_DPAD_RIGHT;
            case "pageup": return KeyEvent.KEYCODE_PAGE_UP;
            case "pagedown": return KeyEvent.KEYCODE_PAGE_DOWN;
            case "home": return KeyEvent.KEYCODE_MOVE_HOME;
            case "end": return KeyEvent.KEYCODE_MOVE_END;
            default: return 0;
        }
    }

    static String normalize(String s) {
        s = s == null ? "" : s.trim();
        if (s.isEmpty()) return "";
        if (s.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) return s;
        if (s.startsWith("localhost") || s.startsWith("127.0.0.1") || s.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+(:\\d+)?(/.*)?$")) return "http://" + s;
        if (s.startsWith("/")) return "file://" + s;
        if (!s.contains(" ") && s.contains(".")) return "https://" + s;
        return "https://www.bing.com/search?q=" + android.net.Uri.encode(s);
    }

    interface MainCall<T> { T call() throws Exception; }

    static <T> T onMain(MainCall<T> c, long timeoutMs) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return c.call();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        MAIN.post(() -> {
            try { ref.set(c.call()); } catch (Throwable t) { err.set(t); }
            latch.countDown();
        });
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw new IllegalStateException("界面线程超时");
        if (err.get() != null) {
            Throwable t = err.get();
            if (t instanceof Exception) throw (Exception) t;
            throw new RuntimeException(t);
        }
        return ref.get();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static JSONObject ok() throws Exception {
        return new JSONObject().put("success", true);
    }

    private static JSONObject err(String msg) {
        try {
            return new JSONObject().put("success", false).put("error", msg);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }
}
