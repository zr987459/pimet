package com.xm486.pimet.ui;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.Window;
import android.webkit.WebView;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 让容器里的 Pi 操控「Pi 浏览器」（经 AppBridge 127.0.0.1:30143，需令牌，令牌在 AppBridgeManager 里校验）。
 * <p>
 * 思路借鉴 Playwright MCP / browser-use：每次返回页面的「快照」，把可交互元素编号成 [e1] [e2]…，
 * Pi 再用编号点击、输入；点击优先走真实触摸事件（isTrusted），用户能看到每一步并随时接手。
 * <p>
 * 协议约定见 docs/pi-browser-opt.md（宿主行为一节）：
 * <ul>
 *   <li>每个请求有 req_id 和 deadline；{@link #cancel(String, String)} 可随时取消某个请求或某个标签上的全部请求（不拿锁）。</li>
 *   <li>同一标签串行（ReentrantLock.tryLock，最多等 15 秒），不同标签并行。</li>
 *   <li>提交到主线程的操作在执行前检查取消/超时，超时后绝不再执行（不会有「幽灵点击」）。</li>
 *   <li>导航等待看 Tab.navSeq / finishedSeq，超时不报成功；结果带 loaded / http_status / load_error / elapsed_ms。</li>
 *   <li>agent.js 先发探针，版本不对才注入整份源码；版本号 = 源码哈希（替换占位 __PIMET_AGENT_V__）。</li>
 *   <li>用户接手检测：请求开始后用户在该标签里真实触摸/按键，原子步骤前立即停下并返回 user_takeover。</li>
 * </ul>
 */
public final class BrowserAgent {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** 窗口级操作（关闭窗口、开关标签）用的锁；页面操作用各标签自己的锁 */
    private static final Object WINDOW_LOCK = new Object();
    private static volatile String sAgentJs;
    private static volatile String sAgentVer;

    static final String STOPPED_MSG = "用户已叫停 Pi 的浏览器操作。请立即停止当前的浏览器任务，告诉用户进展，等用户在浏览器窗口标签栏点「▶ 允许 Pi」后再继续。";
    private static final String NEED_AGENT = "__PIMET_NEED_AGENT__";
    /** 请求超时后留给收尾（快照、回传错误）的时间 */
    private static final long GRACE_MS = 8000;
    private static final long LOCK_WAIT_MAX = 15000;
    private static final long SETTLE_MAX = 3000;

    private BrowserAgent() {}

    // ------------------------------------------------------------------ 请求上下文 / 取消

    private static final class Req {
        final String id;
        final int timeout;
        final long start;
        /** 等待类操作（加载、wait）的截止时间 */
        final long softDeadline;
        /** 超过就直接放弃（抛异常、放锁） */
        final long hardDeadline;
        volatile boolean cancelled;
        volatile String tabSpec = "";
        volatile FloatingApps.Tab tab;

        Req(String id, int timeout, long extraMs) {
            this.id = id;
            this.timeout = timeout;
            this.start = SystemClock.uptimeMillis();
            this.softDeadline = start + timeout + extraMs;
            this.hardDeadline = softDeadline + GRACE_MS;
        }
    }

    private static final ConcurrentHashMap<String, Req> INFLIGHT = new ConcurrentHashMap<>();
    /** cancel 比请求本身先到的 req_id（60 秒内有效） */
    private static final ConcurrentHashMap<String, Long> PRE_CANCELLED = new ConcurrentHashMap<>();
    private static final ThreadLocal<Req> CUR = new ThreadLocal<>();
    private static final Map<FloatingApps.Tab, ReentrantLock> LOCKS = new WeakHashMap<>();

    private static class AbortException extends RuntimeException {
        AbortException(String m) { super(m); }
    }

    private static final class StoppedException extends AbortException {
        StoppedException() { super(STOPPED_MSG); }
    }

    private static final class TakeoverException extends AbortException {
        TakeoverException(String tabId) {
            super("用户正在操作标签 " + tabId + "，Pi 已暂停本次操作。请等用户操作完或询问用户后再继续");
        }
    }

    /**
     * 取消在途请求：reqId 非空取消该请求；tab 非空（标签 id / "pi" / "user"）取消该标签上的全部请求。
     * 不拿任何锁，可在任意线程调用（AppBridgeManager 检测到客户端断开时也调它）。
     */
    public static void cancel(String reqId, String tab) {
        long now = SystemClock.uptimeMillis();
        for (Iterator<Map.Entry<String, Long>> it = PRE_CANCELLED.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue() > 60000) it.remove();
        }
        if (reqId != null && !reqId.trim().isEmpty()) {
            Req r = INFLIGHT.get(reqId.trim());
            if (r != null) r.cancelled = true;
            else PRE_CANCELLED.put(reqId.trim(), now);
        }
        if (tab != null && !tab.trim().isEmpty()) {
            String t = tab.trim();
            for (Req r : INFLIGHT.values()) if (matchesTab(r, t)) r.cancelled = true;
        }
    }

    private static boolean matchesTab(Req r, String t) {
        String spec = r.tabSpec == null || r.tabSpec.trim().isEmpty() ? "pi" : r.tabSpec.trim();
        if (spec.equalsIgnoreCase(t)) return true;
        FloatingApps.Tab tb = r.tab;
        if (tb == null) return false;
        if (tb.id.equals(t)) return true;
        if (t.equalsIgnoreCase("pi")) return tb.pi;
        if (t.equalsIgnoreCase("user")) return !tb.pi;
        return false;
    }

    private static boolean aborted(Req r) {
        return FloatingApps.isPiStopped() || (r != null && (r.cancelled || SystemClock.uptimeMillis() > r.hardDeadline));
    }

    /** 用户叫停 / 请求被取消 / 超过 deadline → 抛异常，调用栈上的锁随之释放 */
    private static void checkStop() {
        if (FloatingApps.isPiStopped()) throw new StoppedException();
        Req r = CUR.get();
        if (r == null) return;
        if (r.cancelled) throw new AbortException("操作已取消");
        if (SystemClock.uptimeMillis() > r.hardDeadline) throw new AbortException("操作超时（超过 " + r.timeout + "ms），已放弃");
    }

    /** 原子步骤前：请求开始后用户在这个标签里真实触摸/按键过 → 停下 */
    private static void checkUser(FloatingApps.Tab tab) {
        checkStop();
        Req r = CUR.get();
        if (r != null && tab != null && tab.userInputAt > r.start) throw new TakeoverException(tab.id);
    }

    /** 可被取消打断的 sleep */
    private static void sleep(long ms) {
        long end = SystemClock.uptimeMillis() + ms;
        while (true) {
            checkStop();
            long left = end - SystemClock.uptimeMillis();
            if (left <= 0) return;
            SystemClock.sleep(Math.min(left, 100));
        }
    }

    private static long softLeft() {
        Req r = CUR.get();
        return r == null ? 20000 : Math.max(0, r.softDeadline - SystemClock.uptimeMillis());
    }

    private static long hardLeft() {
        Req r = CUR.get();
        return r == null ? 20000 : Math.max(0, r.hardDeadline - SystemClock.uptimeMillis());
    }

    // ------------------------------------------------------------------ 入口

    public static JSONObject handle(Context ctx, JSONObject req) {
        String action = actionOf(req);
        if (action.equals("cancel")) {
            cancel(req.optString("req_id", ""), req.optString("tab", ""));
            try {
                return ok().put("result", "已取消");
            } catch (Throwable t) {
                return new JSONObject();
            }
        }
        int timeout = clamp(req.optInt("timeout", 20000), 1000, 120000);
        String reqId = req.optString("req_id", "").trim();
        if (reqId.isEmpty()) reqId = "h" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        // wait 的 ms 不占 timeout（与 TS 侧客户端超时 = timeout + ms + 30s 对齐）
        long extra = action.equals("wait") && req.optString("text", "").isEmpty() ? clamp(req.optInt("ms", 1000), 0, 60000) : 0;
        Req r = new Req(reqId, timeout, extra);
        r.tabSpec = req.optString("tab", "");
        if (PRE_CANCELLED.remove(reqId) != null) r.cancelled = true;
        INFLIGHT.put(reqId, r);
        CUR.set(r);
        JSONObject out;
        try {
            checkStop();
            out = doHandle(ctx, req, action, timeout);
        } catch (TakeoverException u) {
            out = err(u.getMessage());
            try { out.put("user_takeover", true); } catch (Throwable ignored) {}
        } catch (AbortException a) {
            out = err(a.getMessage());
        } catch (Throwable t) {
            out = err("浏览器操作失败：" + friendly(t));
        } finally {
            INFLIGHT.remove(reqId, r);
            CUR.remove();
        }
        finish(out, r);
        return out;
    }

    private static String actionOf(JSONObject req) {
        return req.optString("browser_action", req.optString("op", "snapshot")).trim().toLowerCase();
    }

    /** 统一补 elapsed_ms；失败时补 url、title */
    private static void finish(JSONObject out, Req r) {
        try {
            out.put("elapsed_ms", (int) (SystemClock.uptimeMillis() - r.start));
            if (!out.optBoolean("success", false)) {
                FloatingApps.Tab t = r.tab;
                if (t != null) {
                    if (!out.has("url")) out.put("url", t.url);
                    if (!out.has("title")) out.put("title", t.title);
                    if (!out.has("tab")) out.put("tab", t.id);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static String friendly(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.trim().isEmpty()) {
            return t instanceof InterruptedException ? "操作被中断" : "内部错误";
        }
        return m;
    }

    private static JSONObject doHandle(Context ctx, JSONObject req, String action, int timeout) throws Exception {
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
                    t = onMain(win::agentNewPiTab, 5000);
                }
                if (t == null) return err("新建标签失败");
                CUR.get().tab = t;
                JSONObject out = ok().put("tab", t.id);
                if (url.isEmpty()) return out.put("url", t.url).put("result", "已新建 Pi 标签 " + t.id);
                ReentrantLock lk = lockOf(t);
                acquire(lk, t);
                try {
                    // 新标签先加载 about:blank，等它落定，免得把它当成目标页面的加载完成
                    long e = SystemClock.uptimeMillis() + 2000;
                    while (SystemClock.uptimeMillis() < e && (t.navSeq == 0 || t.loading || t.finishedSeq != t.navSeq)) sleep(50);
                    int seq = t.navSeq;
                    String before = t.url;
                    onMain(() -> { win.agentLoad(t, url); return null; }, 5000);
                    boolean loaded = navWait(t, seq, before, softLeft());
                    JSONObject bad = applyNav(out, t, loaded, "已新建 Pi 标签 " + t.id + " 并打开 " + url);
                    if (bad != null) return bad.put("tab", t.id);
                    return out.put("url", t.url).put("title", t.title);
                } finally {
                    lk.unlock();
                }
            }
            case "switch_tab": {
                String id = req.optString("tab", "");
                Boolean done = onMain(() -> win.agentSwitch(id), 3000);
                if (!Boolean.TRUE.equals(done)) return err("没有标签 " + id + "，用 tabs 查看");
                boolean bg = Boolean.TRUE.equals(onMain(() -> win.minimized, 2000));
                return ok().put("tab", id).put("result", "已切换到标签 " + id
                        + (bg ? "（浏览器窗口现在不在前台，用户打开浏览器时会看到它）" : "（用户现在看到的是它）"));
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
                return ok().put("result", "浏览器窗口已切到前台（全屏，用户现在看到的是浏览器）");
            case "hide":
            case "minimize":
                onMain(() -> { win.agentMinimize(); return null; }, 3000);
                return ok().put("result", "浏览器窗口已退到后台（仍可继续操作，但只能用 JS 点击，也不能截图）");
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
        CUR.get().tab = tab;
        ReentrantLock lk = lockOf(tab);
        acquire(lk, tab);
        boolean begun = false;
        try {
            onMain(() -> { win.agentBegin(tab); return null; }, 3000);
            begun = true;
            JSONObject out = doTab(win, tab, action, req, timeout);
            if (out.optBoolean("success")) out.put("tab", tab.id);
            return out;
        } finally {
            if (begun) MAIN.post(() -> win.agentEnd(tab));
            lk.unlock();
        }
    }

    private static ReentrantLock lockOf(FloatingApps.Tab t) {
        synchronized (LOCKS) {
            ReentrantLock l = LOCKS.get(t);
            if (l == null) {
                l = new ReentrantLock();
                LOCKS.put(t, l);
            }
            return l;
        }
    }

    /** tryLock(min(剩余时间, 15s))；等待期间可被取消/叫停打断 */
    private static void acquire(ReentrantLock lk, FloatingApps.Tab t) throws InterruptedException {
        long end = SystemClock.uptimeMillis() + Math.min(hardLeft(), LOCK_WAIT_MAX);
        while (true) {
            checkStop();
            long left = end - SystemClock.uptimeMillis();
            if (lk.tryLock(Math.max(0, Math.min(200, left)), TimeUnit.MILLISECONDS)) return;
            if (SystemClock.uptimeMillis() >= end) {
                throw new AbortException("标签 " + t.id + " 正忙（上一个操作还没结束），稍后重试或先 cancel");
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
                int seq = tab.navSeq;
                String before = tab.url;
                onMain(() -> { win.agentLoad(tab, url); return null; }, 5000);
                boolean loaded = navWait(tab, seq, before, softLeft());
                JSONObject bad = applyNav(out, tab, loaded, "已打开 " + url);
                if (bad != null) return bad;
                result = null;
                break;
            }
            case "home": {
                int seq = tab.navSeq;
                String before = tab.url;
                onMain(() -> { win.agentHome(tab); return null; }, 5000);
                boolean loaded = navWait(tab, seq, before, softLeft());
                JSONObject bad = applyNav(out, tab, loaded, "已回到首页");
                if (bad != null) return bad;
                result = null;
                break;
            }
            case "back":
            case "forward":
            case "reload": {
                Boolean can = onMain(() -> action.equals("back") ? tab.web.canGoBack()
                        : action.equals("forward") ? tab.web.canGoForward() : Boolean.TRUE, 3000);
                if (!Boolean.TRUE.equals(can)) {
                    return err(action.equals("back") ? "没有可以后退的页面（已经是这个标签的第一页）" : "没有可以前进的页面");
                }
                int seq = tab.navSeq;
                String before = tab.url;
                onMain(() -> {
                    WebView w = tab.web;
                    if (action.equals("back")) w.goBack();
                    else if (action.equals("forward")) w.goForward();
                    else w.reload();
                    return null;
                }, 5000);
                boolean loaded = navWait(tab, seq, before, softLeft());
                JSONObject bad = applyNav(out, tab, loaded, action.equals("back") ? "已后退" : action.equals("forward") ? "已前进" : "已刷新");
                if (bad != null) return bad;
                result = null;
                break;
            }
            case "snapshot":
            case "read":
                waitLoadedNow(tab, Math.min(softLeft(), 8000));
                wantSnapshot = true;
                result = null;
                break;
            case "click":
            case "tap": {
                int seq = tab.navSeq;
                result = click(win, tab, req);
                settle(tab, seq, out);
                break;
            }
            case "type":
            case "fill": {
                int seq = tab.navSeq;
                result = type(win, tab, req);
                settle(tab, seq, out);
                break;
            }
            case "select": {
                checkUser(tab);
                int seq = tab.navSeq;
                JSONObject r = evalAgent(tab, "A.select(" + JSONObject.quote(req.optString("ref", "")) + "," + JSONObject.quote(req.optString("value", "")) + ")", 5000);
                if (!r.optBoolean("ok")) return err(r.optString("error", "选择失败"));
                result = r.optString("result");
                settle(tab, seq, out);
                break;
            }
            case "press":
            case "key": {
                String key = req.optString("key", "Enter");
                int code = keyCode(key);
                if (code == 0) return err("不支持的按键 " + key + "（支持 Enter Tab Escape Backspace Delete Space ArrowUp/Down/Left/Right PageUp PageDown Home End）");
                int seq = tab.navSeq;
                pressKey(win, tab, key, code);
                result = "已按 " + key;
                settle(tab, seq, out);
                break;
            }
            case "scroll": {
                String js = "A.scroll(" + JSONObject.quote(req.optString("ref", "")) + "," + JSONObject.quote(req.optString("direction", "down"))
                        + "," + req.optDouble("amount", 0.8) + ")";
                JSONObject r = evalAgent(tab, js, 5000);
                if (!r.optBoolean("ok")) return err(r.optString("error", "滚动失败"));
                result = r.optString("result");
                if (r.has("moved") && !r.optBoolean("moved", true)) result += "（页面没有移动）";
                else if (r.optBoolean("atEnd", false)) result += "（已到尽头）";
                sleep(250);
                break;
            }
            case "wait": {
                String text = req.optString("text", "");
                if (!text.isEmpty()) {
                    long end = SystemClock.uptimeMillis() + softLeft();
                    boolean found = false;
                    while (true) {
                        checkStop();
                        JSONObject r;
                        try {
                            r = evalAgent(tab, "A.hasText(" + JSONObject.quote(text) + ")", 3000);
                        } catch (AbortException a) {
                            throw a;
                        } catch (Exception e) {
                            r = new JSONObject();
                        }
                        if (r.optBoolean("v")) { found = true; break; }
                        if (SystemClock.uptimeMillis() >= end) break;
                        sleep(400);
                    }
                    out.put("found", found);
                    result = found ? "页面已出现「" + text + "」" : "等待超时，页面没有出现「" + text + "」";
                } else {
                    sleep(clamp(req.optInt("ms", 1000), 0, 60000));
                    waitLoadedNow(tab, softLeft());
                    result = "已等待";
                }
                break;
            }
            case "eval":
            case "js": {
                String script = req.optString("script", "");
                if (script.isEmpty()) return err("缺少 script");
                String[] v = evalAsync(tab, script, req.optBoolean("fn", false), Math.max(1000, softLeft()));
                if (v[0] != null) return err(v[0]).put("url", tab.url);
                return out.put("url", tab.url).put("result", v[1]);
            }
            case "screenshot": {
                if (Boolean.TRUE.equals(onMain(() -> win.minimized, 2000))) {
                    return err("浏览器窗口现在不在前台（用户在用别的界面），截不了图。不要擅自把浏览器切到前台：能用 snapshot / eval 读页面就用它们；确实需要看画面时先问用户，或用 show 打开后再截图。");
                }
                boolean active = Boolean.TRUE.equals(onMain(() -> win.isActive(tab), 2000));
                // 后台标签在操作期间是「可见但压在当前标签下面」，刚变可见时要等一帧画出来
                waitFrame(tab, active ? 300 : 800);
                String b64 = active ? pixelCopy(tab.web, 3000) : null;
                if (b64 == null) b64 = onMain(() -> capture(tab.web), 8000);
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
            out.put("url", snap.optString("url", tab.url));
            out.put("title", snap.optString("title", tab.title));
            out.put("snapshot", snap.optString("text"));
        } else {
            out.put("url", tab.url);
            out.put("title", tab.title);
        }
        return out;
    }

    // ------------------------------------------------------------------ 导航等待

    /**
     * 等这次导航真正完成：navSeq 变了且主框架加载完（finishedSeq == navSeq 且不在加载），
     * 或 URL 已变化且不在加载。返回 false = 超时（不报成功）。
     */
    private static boolean navWait(FloatingApps.Tab tab, int seq, String before, long maxMs) {
        long end = SystemClock.uptimeMillis() + maxMs;
        String b = before == null ? "" : before;
        while (true) {
            checkStop();
            boolean navDone = tab.navSeq != seq && tab.finishedSeq == tab.navSeq && !tab.loading;
            boolean urlDone = !tab.loading && !b.equals(tab.url == null ? "" : tab.url);
            if (navDone || urlDone) {
                SystemClock.sleep(100);
                return true;
            }
            if (SystemClock.uptimeMillis() >= end) return false;
            SystemClock.sleep(100);
        }
    }

    /** 当前若有加载在进行就等它完成；返回是否已加载完 */
    private static boolean waitLoadedNow(FloatingApps.Tab tab, long maxMs) {
        long end = SystemClock.uptimeMillis() + maxMs;
        while (true) {
            checkStop();
            if (!tab.loading && tab.finishedSeq == tab.navSeq) return true;
            if (SystemClock.uptimeMillis() >= end) return false;
            SystemClock.sleep(100);
        }
    }

    /** 把导航结果写进 out；加载失败 / 超时返回错误对象（调用方直接返回），成功返回 null */
    private static JSONObject applyNav(JSONObject out, FloatingApps.Tab tab, boolean loaded, String okText) throws Exception {
        int http = tab.httpStatus;
        if (tab.errorCode != 0) {
            String le = (tab.errorDesc == null || tab.errorDesc.isEmpty() ? "加载失败" : tab.errorDesc) + "（错误码 " + tab.errorCode + "）";
            JSONObject e = err("页面打开失败：" + le + "。检查网址或网络后重试").put("loaded", false).put("load_error", le);
            if (http >= 400) e.put("http_status", http);
            return e;
        }
        if (!loaded) {
            JSONObject e = err("页面在 " + timeoutOf() + "ms 内没有加载完（当前 " + tab.url + "）。可以先 snapshot 看已加载的部分，或加大 timeout 重试")
                    .put("loaded", false);
            if (http >= 400) e.put("http_status", http);
            return e;
        }
        out.put("loaded", true);
        if (http >= 400) out.put("http_status", http);
        out.put("result", okText + (http >= 400 ? "（注意：服务器返回 HTTP " + http + "）" : ""));
        return null;
    }

    private static int timeoutOf() {
        Req r = CUR.get();
        return r == null ? 0 : r.timeout;
    }

    /**
     * 操作后等页面稳定：轮询 A.waitStable(300, 3000)，最多 3 秒；A.waitStable 不存在时退回固定等待。
     * 若操作触发了整页导航，再等加载完成并写 loaded / http_status / load_error。
     */
    private static void settle(FloatingApps.Tab tab, int seqBefore, JSONObject out) throws Exception {
        long start = SystemClock.uptimeMillis();
        sleep(120);
        boolean fallback = false;
        while (SystemClock.uptimeMillis() - start < SETTLE_MAX) {
            checkStop();
            if (tab.loading) break;
            JSONObject r;
            try {
                r = evalAgent(tab, "(typeof A.waitStable==='function')?A.waitStable(300," + SETTLE_MAX + "):{nofn:1}", 2000);
            } catch (AbortException a) {
                throw a;
            } catch (Exception e) {
                r = null;
            }
            if (r != null && r.optBoolean("nofn")) { fallback = true; break; }
            if (r != null && r.optBoolean("stable")) break;
            sleep(150);
        }
        if (fallback) {
            long spent = SystemClock.uptimeMillis() - start;
            if (spent < 500) sleep(500 - spent);
        }
        if (tab.loading || tab.finishedSeq != tab.navSeq || tab.navSeq != seqBefore) {
            boolean full = tab.loading || tab.finishedSeq != tab.navSeq;
            boolean loaded = waitLoadedNow(tab, softLeft());
            if (full) {
                out.put("loaded", loaded);
                if (tab.httpStatus >= 400) out.put("http_status", tab.httpStatus);
                if (tab.errorCode != 0) {
                    out.put("load_error", (tab.errorDesc == null || tab.errorDesc.isEmpty() ? "加载失败" : tab.errorDesc) + "（错误码 " + tab.errorCode + "）");
                }
            }
        } else if (fallback) {
            sleep(200);
        }
    }

    // ------------------------------------------------------------------ 页面辅助脚本

    private static void ensureAgentJs(Context ctx) {
        if (sAgentJs != null) return;
        synchronized (BrowserAgent.class) {
            if (sAgentJs != null) return;
            try (InputStream in = ctx.getAssets().open("pimet-browser/agent.js")) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) bo.write(buf, 0, n);
                String src = new String(bo.toByteArray(), StandardCharsets.UTF_8);
                String ver = "a" + sha1Hex(src).substring(0, 12);
                sAgentVer = ver;
                sAgentJs = src.replace("__PIMET_AGENT_V__", ver);
            } catch (Throwable t) {
                throw new IllegalStateException("缺少 assets/pimet-browser/agent.js：" + friendly(t));
            }
        }
    }

    private static String sha1Hex(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    /**
     * 执行 expr（可用变量 A = window.__pimetAgent），结果按 JSON 解析。
     * 先发探针：页面里已有同版本 agent 就直接执行；否则再发整份源码 + expr。
     */
    private static JSONObject evalAgent(FloatingApps.Tab tab, String expr, long timeoutMs) throws Exception {
        String qv = JSONObject.quote(sAgentVer);
        String probe = "(function(){try{var A=window.__pimetAgent;if(!A||(A.v!==" + qv + "&&A.__pv!==" + qv + "))return " + JSONObject.quote(NEED_AGENT)
                + ";return JSON.stringify(" + expr + ")}catch(e){return JSON.stringify({ok:false,error:String(e)})}})()";
        String raw = evalRaw(tab, probe, timeoutMs);
        Object v = parseRaw(raw);
        if (NEED_AGENT.equals(v)) {
            String full = "(function(){try{" + sAgentJs + "\n;try{window.__pimetAgent.__pv=" + qv + "}catch(x){}"
                    + "var A=window.__pimetAgent;return JSON.stringify(" + expr + ")}catch(e){return JSON.stringify({ok:false,error:String(e)})}})()";
            raw = evalRaw(tab, full, timeoutMs);
            v = parseRaw(raw);
        }
        if (!(v instanceof String)) {
            return new JSONObject().put("ok", false).put("error", "页面没有返回结果（可能正在加载）");
        }
        Object o = new JSONTokener((String) v).nextValue();
        if (o instanceof JSONObject) return (JSONObject) o;
        return new JSONObject().put("ok", false).put("error", "页面返回了意外的结果");
    }

    /** evaluateJavascript 的返回值是 JSON 编码的；null / 解析失败返回 null */
    private static Object parseRaw(String raw) {
        if (raw == null || raw.equals("null") || raw.isEmpty()) return null;
        try {
            return new JSONTokener(raw).nextValue();
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 快照

    private static JSONObject snapshot(FloatingApps.Tab tab, int maxChars, String mode) throws Exception {
        int mc = clamp(maxChars, 1500, 40000);
        String m = "interactive".equalsIgnoreCase(mode) ? "interactive" : "full";
        JSONObject r;
        try {
            r = evalAgent(tab, "A.snapshot(" + mc + "," + JSONObject.quote(m) + ")", Math.max(1000, Math.min(10000, hardLeft())));
        } catch (AbortException a) {
            throw a;
        } catch (Exception e) {
            r = new JSONObject().put("ok", false).put("error", friendly(e));
        }
        if (!r.has("text")) {
            r.put("url", tab.url).put("title", tab.title).put("text", "网址: " + tab.url + "\n（页面还没准备好，无法读取快照"
                    + (r.has("error") ? "：" + r.optString("error") : "") + "）");
        }
        return r;
    }

    // ------------------------------------------------------------------ 点击 / 输入 / 按键

    private static String click(FloatingApps win, FloatingApps.Tab tab, JSONObject req) throws Exception {
        String ref = req.optString("ref", "");
        String text = req.optString("text", "");
        if (ref.isEmpty() && text.isEmpty()) throw new IllegalArgumentException("click 需要 ref 或 text");
        checkUser(tab);
        JSONObject loc = evalAgent(tab, "A.locate(" + JSONObject.quote(ref) + "," + JSONObject.quote(text) + ")", 5000);
        if (!loc.optBoolean("ok")) throw new IllegalStateException(loc.optString("error", "定位失败"));
        String label = loc.optString("label");
        boolean active = Boolean.TRUE.equals(onMain(() -> win.isActive(tab), 2000));
        if (!active) waitFrame(tab, 600); // 后台标签刚变可见，等它画出一帧再触摸
        else sleep(120);
        boolean useJs = req.optBoolean("js", false);
        boolean nativeOk = false;
        String note = "";
        String coveredBy = loc.optString("coveredBy", "");
        if (!useJs) {
            // 触摸前重新量一次（scrollIntoView 之后可能还有动画/布局变化）
            JSONObject rl = evalAgent(tab, "(typeof A.relocate==='function')?A.relocate():{nofn:1}", 3000);
            if (!rl.optBoolean("nofn")) {
                if (!rl.optBoolean("ok")) throw new IllegalStateException(rl.optString("error", "元素位置已变化、重新定位失败，请重新 snapshot"));
                loc = rl;
                if (rl.has("coveredBy")) coveredBy = rl.optString("coveredBy", "");
            }
            final double x = loc.optDouble("x"), y = loc.optDouble("y"), vw = loc.optDouble("vw");
            checkUser(tab);
            boolean armed = "1".equals(evalRawSafe(tab, HIT_ARM_JS, 2000));
            int seq = tab.navSeq;
            nativeOk = Boolean.TRUE.equals(onMain(() -> nativeTap(win, tab, x, y, vw), 4000));
            if (nativeOk && armed) {
                // 触摸事件在渲染进程里异步分发：最多等 500ms 看目标是否收到
                int hit = 0;
                long until = SystemClock.uptimeMillis() + 500;
                while (true) {
                    sleep(60);
                    if (tab.navSeq != seq || tab.loading) { hit = -1; break; }
                    hit = readHit(tab);
                    if (hit != 0 || SystemClock.uptimeMillis() >= until) break;
                }
                evalRawSafe(tab, HIT_OFF_JS, 2000);
                if (hit == 0 && tab.navSeq == seq && !tab.loading) {
                    nativeOk = false;
                    note = "（真实触摸没有送达目标，已改用 JS 点击）";
                } else if (hit == 2) {
                    note = "；注意：触摸落在了" + (coveredBy.isEmpty() ? "遮挡物" : "遮挡物「" + coveredBy + "」") + "上，目标没有收到点击，必要时加 js=true 重试";
                }
            } else if (armed) {
                evalRawSafe(tab, HIT_OFF_JS, 2000);
            }
        }
        if (!nativeOk) {
            checkUser(tab);
            JSONObject jr = evalAgent(tab, "A.jsClick()", 5000);
            if (jr.has("ok") && !jr.optBoolean("ok")) throw new IllegalStateException(jr.optString("error", "JS 点击失败"));
        }
        return "已点击 " + (ref.isEmpty() ? "「" + text + "」" : ref) + (label.isEmpty() ? "" : "（" + label + "）")
                + (!nativeOk && !useJs && note.isEmpty() ? "（用 JS 点击）" : "") + note;
    }

    /** 在目标元素所在窗口挂一次性捕获监听，记录触摸是否落在目标上：1 = 目标，2 = 其它元素，0 = 没收到 */
    private static final String HIT_ARM_JS = "(function(){try{var el=window.__pimetTarget;window.__pimetHit=0;if(window.__pimetHitOff)window.__pimetHitOff();"
            + "if(!el)return 0;var W=(el.ownerDocument&&el.ownerDocument.defaultView)||window;var ev=['pointerdown','touchstart','mousedown'];"
            + "function h(e){var p=e.composedPath?e.composedPath():[];var t=e.target;"
            + "if(t===el||p.indexOf(el)>=0||(t&&t.nodeType===1&&el.contains&&el.contains(t)))window.__pimetHit=1;else if(!window.__pimetHit)window.__pimetHit=2;}"
            + "for(var i=0;i<ev.length;i++)W.addEventListener(ev[i],h,true);"
            + "window.__pimetHitOff=function(){for(var i=0;i<ev.length;i++){try{W.removeEventListener(ev[i],h,true)}catch(x){}}window.__pimetHitOff=null};"
            + "setTimeout(function(){if(window.__pimetHitOff)window.__pimetHitOff()},3000);return 1}catch(e){return 0}})()";
    private static final String HIT_OFF_JS = "(function(){if(window.__pimetHitOff)window.__pimetHitOff();return 1})()";
    private static final String HIT_READ_JS = "(function(){var h=window.__pimetHit;return h===undefined?-1:h})()";

    /** 1 = 落在目标，2 = 落在别处，0 = 没收到，-1 = 未知（页面已跳转等） */
    private static int readHit(FloatingApps.Tab tab) {
        String raw = evalRawSafe(tab, HIT_READ_JS, 2000);
        if (raw == null) return -1;
        try {
            return Integer.parseInt(raw.trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    /** evalRaw，但除取消/叫停外的失败都返回 null */
    private static String evalRawSafe(FloatingApps.Tab tab, String js, long timeoutMs) {
        try {
            return evalRaw(tab, js, timeoutMs);
        } catch (AbortException a) {
            throw a;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 真实触摸：CSS 坐标换算到 WebView 像素。坐标已由 agent.js 加上 iframe 偏移、按 visualViewport 换算。
     * 后台标签在操作期间是 VISIBLE 但压在当前标签下面（见 FloatingApps.agentBegin），触摸事件直接分发给它的 WebView；
     * 浏览器不在前台时 WebView 不在屏幕上，返回 false 退回 JS 点击。
     */
    private static boolean nativeTap(FloatingApps win, FloatingApps.Tab tab, double cssX, double cssY, double vw) {
        WebView web = tab.web;
        if (win.minimized || web == null || web.getWidth() <= 0 || web.getHeight() <= 0 || !(vw > 0) || !web.isShown()) return false;
        if (Double.isNaN(cssX) || Double.isNaN(cssY)) return false;
        float ratio = (float) (web.getWidth() / vw);
        float x = (float) (cssX * ratio), y = (float) (cssY * ratio);
        if (x < 0 || y < 0 || x > web.getWidth() || y > web.getHeight()) return false;
        long down = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent u = MotionEvent.obtain(down, down + 60, MotionEvent.ACTION_UP, x, y, 0);
        d.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        u.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        boolean a, b;
        FloatingApps.sAgentTouch = true;
        try {
            a = web.dispatchTouchEvent(d);
            b = web.dispatchTouchEvent(u);
        } finally {
            FloatingApps.sAgentTouch = false;
            d.recycle();
            u.recycle();
        }
        return a || b;
    }

    /** 当前标签发系统按键；后台标签不抢焦点（会打断用户输入），改用 JS 键盘事件 */
    private static void pressKey(FloatingApps win, FloatingApps.Tab tab, String key, int code) throws Exception {
        checkUser(tab);
        boolean active = Boolean.TRUE.equals(onMain(() -> win.isActive(tab) && !win.minimized, 2000));
        if (active) {
            onMain(() -> {
                FloatingApps.sAgentTouch = true;
                try {
                    tab.web.requestFocus();
                    long now = SystemClock.uptimeMillis();
                    tab.web.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
                    tab.web.dispatchKeyEvent(new KeyEvent(now, now + 30, KeyEvent.ACTION_UP, code, 0));
                } finally {
                    FloatingApps.sAgentTouch = false;
                }
                return null;
            }, 5000);
        } else {
            JSONObject r = evalAgent(tab, "A.key(" + JSONObject.quote(key) + ")", 5000);
            if (r.has("ok") && !r.optBoolean("ok")) throw new IllegalStateException(r.optString("error", "按键失败"));
        }
    }

    private static String type(FloatingApps win, FloatingApps.Tab tab, JSONObject req) throws Exception {
        String ref = req.optString("ref", "");
        String text = req.optString("text", "");
        boolean clear = req.optBoolean("clear", true);
        boolean submit = req.optBoolean("submit", false);
        checkUser(tab);
        JSONObject r = evalAgent(tab, "A.type(" + JSONObject.quote(ref) + "," + JSONObject.quote(text) + "," + clear + ")", 5000);
        if (!r.optBoolean("ok")) throw new IllegalStateException(r.optString("error", "输入失败"));
        String res = "已输入" + (ref.isEmpty() ? "" : "到 " + ref);
        // 校验：A.type 回读了 value（密码框只回 len）
        if (clear && r.has("value") && !r.isNull("value")) {
            String v = r.optString("value");
            if (!v.equals(text)) {
                res += "（注意：输入框现在的值是「" + (v.length() > 60 ? v.substring(0, 60) + "…" : v) + "」，可能被页面格式化或限制了长度）";
            }
        } else if (clear && r.has("len") && r.optInt("len", -1) != text.length()) {
            res += "（注意：输入框现在有 " + r.optInt("len") + " 个字符，和输入的 " + text.length() + " 个不一致）";
        }
        if (submit) {
            checkUser(tab);
            if (r.optBoolean("form")) {
                JSONObject s = evalAgent(tab, "A.submit()", 5000);
                if (s.has("ok") && !s.optBoolean("ok")) pressKey(win, tab, "Enter", KeyEvent.KEYCODE_ENTER);
            } else {
                pressKey(win, tab, "Enter", KeyEvent.KEYCODE_ENTER);
            }
            res += "并提交";
        }
        return res;
    }

    // ------------------------------------------------------------------ JS 执行

    /** 在主线程执行 evaluateJavascript 并等回调；可被取消打断；超时后不会再发出脚本 */
    private static String evalRaw(FloatingApps.Tab tab, String js, long timeoutMs) throws Exception {
        final Req req = CUR.get();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> ref = new AtomicReference<>();
        AtomicBoolean claimed = new AtomicBoolean(false);
        MAIN.post(() -> {
            if (!claimed.compareAndSet(false, true)) return;
            if (aborted(req) || tab.web == null || tab.closed) {
                latch.countDown();
                return;
            }
            try {
                tab.web.evaluateJavascript(js, v -> { ref.set(v); latch.countDown(); });
            } catch (Throwable t) {
                latch.countDown();
            }
        });
        long wait = Math.max(1, Math.min(timeoutMs, req == null ? timeoutMs : hardLeft()));
        if (!awaitAbortable(latch, wait, claimed)) {
            claimed.set(true);
            checkStop();
            throw new IllegalStateException("页面脚本执行超时");
        }
        checkStop();
        return ref.get();
    }

    /**
     * 支持 await 的任意脚本：结果放进 window.__pimetEval[id]，轮询取回。返回 {错误或 null, 结果}。
     * fn=true：script 是完整函数体（自带 return）；否则先按表达式试，语法不通再按语句体执行。
     */
    private static String[] evalAsync(FloatingApps.Tab tab, String script, boolean fn, long timeoutMs) throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "");
        String started;
        if (fn) {
            started = startEval(tab, id, script);
        } else {
            String expr = stripTrailingSemis(script);
            started = expr.isEmpty() ? null : startEval(tab, id, "return (" + expr + "\n);");
            if (started == null || started.equals("null")) started = startEval(tab, id, script);
        }
        if (started == null || started.equals("null")) {
            return new String[]{"脚本没有运行（语法错误，或页面正在加载）。请检查代码；需要返回值时写 return 语句，如 return document.title;", null};
        }
        long end = SystemClock.uptimeMillis() + timeoutMs;
        String qid = JSONObject.quote(id);
        try {
            while (SystemClock.uptimeMillis() < end) {
                checkStop();
                String raw = evalRaw(tab, "(function(){var m=window.__pimetEval||{},r=m[" + qid + "];if(!r)return JSON.stringify({done:false});delete m["
                        + qid + "];return JSON.stringify({done:true,ok:r.ok,v:r.v});})()", 3000);
                Object pv = parseRaw(raw);
                JSONObject r = pv instanceof String ? new JSONObject((String) pv) : new JSONObject();
                if (r.optBoolean("done")) {
                    String v = r.optString("v");
                    if (v.length() > 40000) v = v.substring(0, 40000) + "…（已截断）";
                    return r.optBoolean("ok") ? new String[]{null, v} : new String[]{"脚本出错: " + v, null};
                }
                sleep(150);
            }
        } catch (AbortException a) {
            cleanupEval(tab, qid);
            throw a;
        }
        cleanupEval(tab, qid);
        return new String[]{"脚本超时（" + timeoutMs + "ms）未返回（页面跳转也会导致结果丢失）", null};
    }

    private static String startEval(FloatingApps.Tab tab, String id, String body) throws Exception {
        String js = "(function(){window.__pimetEval=window.__pimetEval||{};var id=" + JSONObject.quote(id) + ";"
                + "(async function(){" + body + "\n})().then(function(v){var s;try{s=typeof v==='string'?v:JSON.stringify(v);}catch(e){s=String(v);}"
                + "window.__pimetEval[id]={ok:true,v:s===undefined?'undefined':s};},function(e){window.__pimetEval[id]={ok:false,v:String(e&&e.stack||e)};});return 1;})()";
        return evalRaw(tab, js, 5000);
    }

    /** 超时/取消后清掉页面里残留的结果槽（尽力而为，不受取消影响） */
    private static void cleanupEval(FloatingApps.Tab tab, String qid) {
        MAIN.post(() -> {
            try {
                if (tab.web != null && !tab.closed) {
                    tab.web.evaluateJavascript("(function(){try{var m=window.__pimetEval;if(m)delete m[" + qid + "]}catch(e){}})()", null);
                }
            } catch (Throwable ignored) {}
        });
    }

    private static String stripTrailingSemis(String s) {
        String t = s == null ? "" : s.trim();
        while (t.endsWith(";")) t = t.substring(0, t.length() - 1).trim();
        return t;
    }

    // ------------------------------------------------------------------ 截图

    /** 等 WebView 画出下一帧（后台标签刚变可见时需要）；最多 maxMs */
    private static void waitFrame(FloatingApps.Tab tab, long maxMs) throws Exception {
        CountDownLatch l = new CountDownLatch(1);
        AtomicBoolean claimed = new AtomicBoolean(false);
        MAIN.post(() -> {
            if (!claimed.compareAndSet(false, true)) return;
            try {
                tab.web.postVisualStateCallback(1, new WebView.VisualStateCallback() {
                    @Override
                    public void onComplete(long requestId) {
                        l.countDown();
                    }
                });
            } catch (Throwable t) {
                l.countDown();
            }
        });
        awaitAbortable(l, maxMs, claimed);
    }

    /** 浏览器在前台且是当前标签：用 PixelCopy 从窗口取像素（能拍到硬件加速的视频/canvas）。失败返回 null */
    private static String pixelCopy(WebView web, long timeoutMs) throws Exception {
        CountDownLatch l = new CountDownLatch(1);
        AtomicInteger code = new AtomicInteger(-1);
        AtomicReference<Bitmap> bmp = new AtomicReference<>();
        Boolean posted;
        try {
            posted = onMain(() -> {
                Activity a = activityOf(web.getContext());
                if (a == null || a.isFinishing()) return false;
                Window w = a.getWindow();
                if (w == null || w.peekDecorView() == null) return false;
                int vw = web.getWidth(), vh = web.getHeight();
                if (vw <= 0 || vh <= 0 || !web.isShown()) return false;
                int[] loc = new int[2];
                web.getLocationInWindow(loc);
                Bitmap b = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888);
                bmp.set(b);
                PixelCopy.request(w, new Rect(loc[0], loc[1], loc[0] + vw, loc[1] + vh), b, r -> {
                    code.set(r);
                    l.countDown();
                }, MAIN);
                return true;
            }, 3000);
        } catch (AbortException a) {
            throw a;
        } catch (Exception e) {
            return null;
        }
        if (!Boolean.TRUE.equals(posted)) {
            Bitmap b = bmp.get();
            if (b != null) b.recycle();
            return null;
        }
        if (!l.await(timeoutMs, TimeUnit.MILLISECONDS)) return null; // 位图可能还在被写，交给 GC
        Bitmap b = bmp.get();
        if (code.get() != PixelCopy.SUCCESS || b == null) {
            if (b != null) b.recycle();
            return null;
        }
        return encode(b);
    }

    private static Activity activityOf(Context c) {
        int guard = 0;
        while (c != null && guard++ < 10) {
            if (c instanceof Activity) return (Activity) c;
            if (c instanceof ContextWrapper) c = ((ContextWrapper) c).getBaseContext();
            else break;
        }
        return null;
    }

    /** 回退：web.draw 到位图（后台标签、PixelCopy 失败时）。主线程调用 */
    private static String capture(WebView web) {
        int w = web.getWidth(), h = web.getHeight();
        if (w <= 0 || h <= 0 || !web.isShown()) return null;
        float scale = Math.min(1f, 900f / Math.max(w, h));
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.scale(scale, scale);
        c.translate(-web.getScrollX(), -web.getScrollY());
        web.draw(c);
        return encode(bmp);
    }

    /** 缩到长边 ≤900 → JPEG 70 → base64；会回收传入的位图 */
    private static String encode(Bitmap src) {
        Bitmap out = src;
        int w = src.getWidth(), h = src.getHeight();
        float scale = Math.min(1f, 900f / Math.max(w, h));
        if (scale < 1f) {
            out = Bitmap.createScaledBitmap(src, Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), true);
            if (out != src) src.recycle();
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        out.compress(Bitmap.CompressFormat.JPEG, 70, bo);
        out.recycle();
        return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
    }

    // ------------------------------------------------------------------ 工具

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

    /**
     * 在主线程执行并等结果。Runnable 开头检查取消/叫停/超时，已放弃的请求绝不执行；
     * 等待超时时先「认领」掉 Runnable，保证超时后它不会再跑（杜绝幽灵点击）。
     */
    static <T> T onMain(MainCall<T> c, long timeoutMs) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return c.call();
        final Req req = CUR.get();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        AtomicBoolean claimed = new AtomicBoolean(false);
        AtomicBoolean skipped = new AtomicBoolean(false);
        MAIN.post(() -> {
            if (!claimed.compareAndSet(false, true)) return;
            try {
                if (aborted(req)) skipped.set(true);
                else ref.set(c.call());
            } catch (Throwable t) {
                err.set(t);
            }
            latch.countDown();
        });
        long wait = req == null ? timeoutMs : Math.max(1, Math.min(timeoutMs, hardLeft()));
        if (!awaitAbortable(latch, wait, claimed)) {
            if (claimed.compareAndSet(false, true)) {
                checkStop();
                throw new IllegalStateException("界面线程超时");
            }
            // 已经开始执行：等它跑完，避免返回后它还在改界面
            if (!latch.await(5000, TimeUnit.MILLISECONDS)) throw new IllegalStateException("界面线程超时");
        }
        if (skipped.get()) {
            checkStop();
            throw new AbortException("操作已取消");
        }
        if (err.get() != null) {
            Throwable t = err.get();
            if (t instanceof Exception) throw (Exception) t;
            throw new RuntimeException(t);
        }
        return ref.get();
    }

    /**
     * 分片等待 latch；期间若请求被取消/叫停/超时且 Runnable 还没开始，就认领它并抛异常。
     * 返回 false = 等满 ms 仍未完成。
     */
    private static boolean awaitAbortable(CountDownLatch latch, long ms, AtomicBoolean claimed) throws InterruptedException {
        final Req req = CUR.get();
        long end = SystemClock.uptimeMillis() + ms;
        while (true) {
            long left = end - SystemClock.uptimeMillis();
            if (left <= 0) return latch.getCount() == 0;
            if (latch.await(Math.min(100, left), TimeUnit.MILLISECONDS)) return true;
            if (aborted(req)) {
                claimed.compareAndSet(false, true);
                checkStop();
            }
        }
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
