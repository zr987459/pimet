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

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final long SETTLE_MAX = 1500;

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
        long extra = action.equals("wait") && req.optString("text", "").isEmpty() ? clamp(req.optInt("ms", 1000), 0, 60000)
                : action.equals("wait_for") ? clamp(req.optInt("timeout_ms", 10000), 0, 30000)
                : action.equals("batch") ? Math.min(120000, Math.max(1, req.optJSONArray("steps") == null ? 1 : req.optJSONArray("steps").length()) * 12000L) : 0;
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
            case "tabs": {
                JSONArray tj = onMain(() -> {
                    JSONArray a = win.agentTabsJson();
                    try {
                        for (int i = 0; i < a.length(); i++) {
                            JSONObject o = a.optJSONObject(i);
                            if (o == null || o.has("container")) continue;
                            for (FloatingApps.Tab x : win.tabs()) {
                                if (x.id.equals(o.optString("id"))) { o.put("container", nz(win.containerOf(x))); break; }
                            }
                        }
                    } catch (Throwable ignored) {}
                    return a;
                }, 4000);
                return ok().put("tabs", tj == null ? new JSONArray() : tj).put("result", onMain(win::agentTabsText, 3000));
            }
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
            case "tab_switch":
            case "switch_tab": {
                String id = req.optString("tab", "");
                Boolean done = onMain(() -> win.agentSwitch(id), 3000);
                if (!Boolean.TRUE.equals(done)) return err("没有标签 " + id + "，用 tabs 查看");
                boolean bg = Boolean.TRUE.equals(onMain(() -> win.minimized, 2000));
                return ok().put("tab", id).put("result", "已切换到标签 " + id
                        + (bg ? "（浏览器窗口现在不在前台，用户打开浏览器时会看到它）" : "（用户现在看到的是它）"));
            }
            case "tab_close":
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
        // ---- 用户脚本 / 浏览器设置（窗口级，不占标签锁）
        JSONObject ext = doWindowExtra(win, action, req);
        if (ext != null) return ext;

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
                String before = tab.url;
                result = click(win, tab, req);
                settle(tab, seq, out);
                markChanged(out, tab, seq, before);
                break;
            }
            case "type":
            case "fill": {
                int seq = tab.navSeq;
                String before = tab.url;
                result = type(win, tab, req);
                settle(tab, seq, out);
                markChanged(out, tab, seq, before);
                break;
            }
            case "select": {
                checkUser(tab);
                int seq = tab.navSeq;
                String before = tab.url;
                JSONObject r = evalAgent(tab, "A.select(" + JSONObject.quote(fieldOf(req)) + "," + JSONObject.quote(req.optString("value", "")) + ")", 5000);
                if (!r.optBoolean("ok")) return err(agentErr(r, "选择失败"));
                result = r.optString("result");
                settle(tab, seq, out);
                markChanged(out, tab, seq, before);
                break;
            }
            case "hover": {
                String tgt = targetOf(req);
                if (tgt.isEmpty()) return err("hover 需要 target/ref/text");
                checkUser(tab);
                JSONObject loc = evalAgent(tab, "A.locate(" + JSONObject.quote(tgt) + "," + JSONObject.quote("") + ")", 5000);
                if (!loc.optBoolean("ok")) return err(agentErr(loc, "定位失败"));
                evalJson(tab, HOVER_JS, 3000);
                result = "已悬停 " + tgt + (loc.optString("label").isEmpty() ? "" : "（" + loc.optString("label") + "）");
                sleep(150);
                break;
            }
            case "scroll_into_view": {
                String tgt = targetOf(req);
                if (tgt.isEmpty()) return err("scroll_into_view 需要 target/ref/text");
                JSONObject loc = evalAgent(tab, "A.locate(" + JSONObject.quote(tgt) + "," + JSONObject.quote("") + ")", 5000);
                if (!loc.optBoolean("ok")) return err(agentErr(loc, "定位失败"));
                result = "已把 " + tgt + " 滚动到可见";
                wantSnapshot = req.optBoolean("snapshot", false);
                break;
            }
            case "press":
            case "key": {
                String key = req.optString("key", "Enter");
                int code = keyCode(key);
                if (code == 0) return err("不支持的按键 " + key + "（支持 Enter Tab Escape Backspace Delete Space ArrowUp/Down/Left/Right PageUp PageDown Home End）");
                int seq = tab.navSeq;
                String before = tab.url;
                pressKey(win, tab, key, code);
                result = "已按 " + key;
                settle(tab, seq, out);
                markChanged(out, tab, seq, before);
                break;
            }
            case "scroll": {
                String js = "A.scroll(" + JSONObject.quote(fieldOf(req)) + "," + JSONObject.quote(req.optString("direction", "down"))
                        + "," + req.optDouble("amount", 0.8) + ")";
                JSONObject r = evalAgent(tab, js, 5000);
                if (!r.optBoolean("ok")) return err(agentErr(r, "滚动失败"));
                result = r.optString("result");
                if (r.has("moved") && !r.optBoolean("moved", true)) result += "（页面没有移动）";
                else if (r.optBoolean("atEnd", false)) result += "（已到尽头）";
                sleep(250);
                break;
            }
            case "batch":
                return batch(win, tab, req, out);
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
            case "status": {
                result = "url: " + tab.url;
                wantSnapshot = false;
                try {
                    UserScriptManager m = mgr(win);
                    out.put("scripts", m.list().size());
                    out.put("scripts_matching", matchingIds(m, tab.url).size());
                } catch (AbortException a) {
                    throw a;
                } catch (Exception e) {
                    out.put("scripts", -1);
                }
                String uaNow = onMain(() -> tab.web == null ? "" : tab.web.getSettings().getUserAgentString(), 3000);
                String mode = onMain(win::userAgentMode, 3000);
                if (mode == null) mode = "default";
                Integer zoomNow = onMain(win::zoomPercent, 3000);
                int zoom = zoomNow == null ? 100 : zoomNow;
                out.put("ua_mode", mode).put("user_agent", uaNow == null ? "" : uaNow).put("zoom", zoom);
                boolean csup = ContainerManager.supported();
                String cont = onMain(() -> win.containerOf(tab), 3000);
                out.put("container", nz(cont)).put("containers_supported", csup);
                try {
                    BrowserCustomizer cz = cust(win);
                    JSONObject cfg = cz.config();
                    JSONArray pa = cfg.optJSONArray("panels");
                    out.put("ui_version", cfg.optInt("version", 1)).put("panels", pa == null ? 0 : pa.length());
                } catch (AbortException a) {
                    throw a;
                } catch (Exception e) {
                    out.put("ui_version", -1).put("panels", -1);
                }
                result += "；用户脚本 " + out.optInt("scripts") + " 个（当前页生效 " + out.optInt("scripts_matching") + " 个）；UA " + mode
                        + "；缩放 " + zoom + "%；容器 " + (nz(cont).isEmpty() ? "-" : cont) + (csup ? "" : "（系统不支持多容器）");
                break;
            }
            case "menu_commands": {
                JSONArray a = onMain(() -> win.menuCommands(tab), 3000);
                if (a == null) a = new JSONArray();
                return out.put("commands", a).put("url", tab.url)
                        .put("result", a.length() == 0 ? "当前页没有用户脚本注册的菜单命令" : "当前页有 " + a.length() + " 个菜单命令，用 menu_run{script,cmd} 执行");
            }
            case "menu_run": {
                final String sid = req.optString("script", "").trim();
                final String cid = req.optString("cmd", "").trim();
                if (sid.isEmpty() || cid.isEmpty()) return err("menu_run 需要 script 和 cmd（用 menu_commands 查看）");
                checkUser(tab);
                int seq = tab.navSeq;
                onMain(() -> { win.runMenuCommand(tab, sid, cid); return null; }, 5000);
                result = "已执行菜单命令 " + cid + "（脚本 " + sid + "）";
                settle(tab, seq, out);
                break;
            }
            case "console":
                return console(win, tab, req, out);
            case "find": {
                String text = req.optString("text", "");
                if (text.isEmpty()) return err("find 需要 text");
                JSONObject r = evalJson(tab, FIND_JS + "(" + JSONObject.quote(text) + ")", 5000);
                int n = r.optInt("n", 0);
                out.put("count", n).put("url", tab.url);
                if (r.has("samples")) out.put("samples", r.optJSONArray("samples"));
                return out.put("result", n == 0 ? "页面里没有找到「" + text + "」" : "页面里找到 " + n + " 处「" + text + "」");
            }
            case "wait_for": {
                String sel0 = req.optString("selector", "");
                String text0 = req.optString("text", "");
                if (sel0.isEmpty() && text0.isEmpty()) return err("wait_for 需要 selector 或 text");
                JSONObject w = waitFor(tab, req);
                out.put("found", w.optBoolean("found")).put("waited_ms", w.optInt("waited_ms"));
                result = w.optString("result");
                wantSnapshot = req.optBoolean("snapshot", false);
                break;
            }
            case "inject":
                return inject(win, tab, req, out);
            default:
                return err("未知操作 " + action);
        }

        if (result != null) out.put("result", result);
        if (wantSnapshot) {
            JSONObject snap = snapshot(tab, req.optInt("max_chars", action.equals("snapshot") ? 12000 : 6000), scopeOf(req));
            out.put("url", snap.optString("url", tab.url));
            out.put("title", snap.optString("title", tab.title));
            out.put("snapshot", snap.optString("text"));
            if (snap.has("refs")) out.put("refs", snap.opt("refs"));
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
            // 3 秒内导航都没开始（下载链接 / 跳外部 App）就不再干等
            if (tab.navSeq == seq && !tab.loading && SystemClock.uptimeMillis() - (end - maxMs) > 3000) return false;
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
            // 超时不算失败（慢站点/广告/长连接常见）：返回成功 + loaded:false，TS 会给出提示
            out.put("loaded", false);
            if (http >= 400) out.put("http_status", http);
            out.put("result", okText + "（" + timeoutOf() + "ms 内未完全加载完，可先 snapshot 看已加载的部分）");
            return null;
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
            if (r != null && (r.optBoolean("stable")
                    || (r.optInt("pending", 1) == 0 && SystemClock.uptimeMillis() - start >= 700))) break;
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

    private static JSONObject snapshot(FloatingApps.Tab tab, int maxChars, String scope) throws Exception {
        int mc = clamp(maxChars, 1500, 40000);
        // ego 式 scope：full（整页）| viewport（只当前视口）；兼容旧的 interactive -> viewport
        String sc = "viewport".equalsIgnoreCase(scope) || "interactive".equalsIgnoreCase(scope) ? "viewport" : "full";
        String opts = "{scope:" + JSONObject.quote(sc) + ",maxChars:" + mc + ",mode:" + JSONObject.quote(sc) + "}";
        long tmo = Math.max(1000, Math.min(10000, hardLeft()));
        JSONObject r;
        try {
            // M4 的 agent.js 支持 A.snapshot(opts 对象)；旧版只认 (maxChars, mode)，失败时回退
            r = evalAgent(tab, "A.snapshot(" + opts + ")", tmo);
            if (!r.has("text")) r = evalAgent(tab, "A.snapshot(" + mc + "," + JSONObject.quote(sc) + ")", tmo);
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
        String tgt = targetOf(req);
        if (tgt.isEmpty()) throw new IllegalArgumentException("click 需要 target / ref / text");
        checkUser(tab);
        JSONObject loc = evalAgent(tab, "A.locate(" + JSONObject.quote(tgt) + "," + JSONObject.quote("") + ")", 5000);
        if (!loc.optBoolean("ok")) throw new IllegalStateException(agentErr(loc, "定位失败"));
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
                long until = SystemClock.uptimeMillis() + 1000;
                while (true) {
                    sleep(60);
                    if (tab.navSeq != seq || tab.loading) { hit = -1; break; }
                    hit = readHit(tab);
                    if (hit != 0 || SystemClock.uptimeMillis() >= until) break;
                }
                evalRawSafe(tab, HIT_OFF_JS, 2000);
                if (hit == 0 && tab.navSeq == seq && !tab.loading) {
                    // 不自动补 JS 点击：触摸可能只是还没分发或被页面拦了监听，补点会变成双击
                    note = "（未确认触摸送达目标；页面若没反应，用 js=true 重试）";
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
        return "已点击 " + tgt + (label.isEmpty() ? "" : "（" + label + "）")
                + (!nativeOk && !useJs && note.isEmpty() ? "（用 JS 点击）" : "") + note;
    }

    /** 在目标元素所在窗口挂一次性捕获监听，记录触摸是否落在目标上：1 = 目标，2 = 其它元素，0 = 没收到 */
    private static final String HIT_ARM_JS = "(function(){try{var el=window.__pimetTarget;window.__pimetHit=0;if(window.__pimetHitOff)window.__pimetHitOff();"
            + "if(!el)return 0;var W=(el.ownerDocument&&el.ownerDocument.defaultView)||window;var ev=['pointerdown','touchstart','mousedown','click'];"
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
        String ref = fieldOf(req);
        String text = req.has("value") ? req.optString("value", "") : req.optString("text", "");
        boolean clear = req.optBoolean("clear", true);
        boolean submit = req.optBoolean("submit", false);
        checkUser(tab);
        JSONObject r = evalAgent(tab, "A.type(" + JSONObject.quote(ref) + "," + JSONObject.quote(text) + "," + clear + ")", 5000);
        if (!r.optBoolean("ok")) throw new IllegalStateException(agentErr(r, "输入失败"));
        String res = "已输入" + (ref.isEmpty() ? "" : "到 " + ref);
        // 校验：A.type 回读了 value（密码框只回 len）
        if (clear && r.has("value") && !r.isNull("value")) {
            String v = r.optString("value");
            String vt = v.trim(), tt = text.trim();
            boolean same = vt.equals(tt) || (vt.endsWith("…") && tt.startsWith(vt.substring(0, vt.length() - 1)));
            if (!same) {
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

    // ------------------------------------------------------------------ 用户脚本 / 浏览器设置（docs/userscripts.md 第 3 节）


    private static UserScriptManager mgr(FloatingApps win) throws Exception {
        UserScriptManager m = onMain(win::scripts, 3000);
        if (m == null) throw new IllegalStateException("用户脚本管理器不可用");
        return m;
    }

    private static Set<String> matchingIds(UserScriptManager m, String url) {
        Set<String> ids = new HashSet<>();
        if (url == null || url.isEmpty()) return ids;
        List<UserScriptManager.Script> l = m.matching(url, null);
        if (l != null) for (UserScriptManager.Script s : l) ids.add(s.id);
        return ids;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static JSONArray arr(List<String> l) {
        JSONArray a = new JSONArray();
        if (l != null) for (String x : l) a.put(x);
        return a;
    }

    private static JSONObject scriptBrief(UserScriptManager.Script s, Set<String> cur) throws Exception {
        return new JSONObject().put("id", s.id).put("name", nz(s.name)).put("version", nz(s.version)).put("enabled", s.enabled)
                .put("matchesCurrent", cur != null && cur.contains(s.id))
                .put("runAt", s.runAt == null || s.runAt.isEmpty() ? "document-idle" : s.runAt);
    }

    private static JSONObject scriptFull(UserScriptManager.Script s, Set<String> cur) throws Exception {
        JSONObject o = scriptBrief(s, cur);
        o.put("namespace", nz(s.namespace)).put("description", nz(s.description)).put("author", nz(s.author))
                .put("matches", arr(s.matches)).put("includes", arr(s.includes)).put("excludes", arr(s.excludes))
                .put("grants", arr(s.grants)).put("requires", arr(s.requires)).put("connects", arr(s.connects))
                .put("resources", s.resources == null ? new JSONObject() : new JSONObject(s.resources))
                .put("noframes", s.noframes).put("installUrl", nz(s.installUrl)).put("updateUrl", nz(s.updateUrl))
                .put("mtime", s.mtime).put("code", nz(s.code));
        return o;
    }

    /** 不加标签锁地看一眼请求指向的标签（只读 url 用） */
    private static FloatingApps.Tab peekTab(FloatingApps win, JSONObject req) throws Exception {
        final String spec = req.optString("tab", "");
        synchronized (WINDOW_LOCK) {
            return onMain(() -> win.agentTab(spec), 5000);
        }
    }

    private static String peekUrl(FloatingApps win, JSONObject req) {
        try {
            FloatingApps.Tab t = peekTab(win, req);
            return t == null ? "" : nz(t.url);
        } catch (AbortException a) {
            throw a;
        } catch (Exception e) {
            return "";
        }
    }

    /** JSON 值 → setValue 需要的 JSON 文本 */
    private static String jsonText(Object v) {
        if (v == null || v == JSONObject.NULL) return "null";
        if (v instanceof String) return JSONObject.quote((String) v);
        return v.toString();
    }

    /** 窗口级 action；不是这里处理的返回 null */
    private static JSONObject doWindowExtra(FloatingApps win, String action, JSONObject req) throws Exception {
        switch (action) {
            case "scripts": {
                UserScriptManager m = mgr(win);
                Set<String> cur = matchingIds(m, peekUrl(win, req));
                JSONArray a = new JSONArray();
                List<UserScriptManager.Script> l = m.list();
                int on = 0;
                if (l != null) for (UserScriptManager.Script s : l) {
                    a.put(scriptBrief(s, cur));
                    if (s.enabled) on++;
                }
                return ok().put("scripts", a).put("result", "共 " + a.length() + " 个用户脚本，启用 " + on + " 个，当前页生效 " + cur.size() + " 个");
            }
            case "script_get": {
                String id = req.optString("id", "").trim();
                if (id.isEmpty()) return err("script_get 需要 id（用 scripts 查看）");
                UserScriptManager m = mgr(win);
                UserScriptManager.Script s = m.get(id);
                if (s == null) return err("没有脚本 " + id + "，用 scripts 查看");
                return ok().put("script", scriptFull(s, matchingIds(m, peekUrl(win, req))));
            }
            case "script_install": {
                String code = req.optString("code", "");
                String url = req.optString("url", "").trim();
                if (code.isEmpty() == url.isEmpty()) return err("script_install 需要 code 或 url（二选一）");
                UserScriptManager m = mgr(win);
                UserScriptManager.Script s = code.isEmpty() ? m.installFromUrl(normalize(url)) : m.install(code, null);
                if (s == null) return err("安装失败");
                return ok().put("script", scriptBrief(s, matchingIds(m, peekUrl(win, req))))
                        .put("result", "已安装脚本「" + nz(s.name) + "」（id " + s.id + "），刷新页面后生效");
            }
            case "script_enable": {
                String id = req.optString("id", "").trim();
                if (id.isEmpty()) return err("script_enable 需要 id");
                boolean on = req.optBoolean("on", true);
                if (!mgr(win).setEnabled(id, on)) return err("没有脚本 " + id + "，用 scripts 查看");
                return ok().put("id", id).put("enabled", on).put("result", "已" + (on ? "启用" : "停用") + "脚本 " + id + "，刷新页面后生效");
            }
            case "script_remove": {
                String id = req.optString("id", "").trim();
                if (id.isEmpty()) return err("script_remove 需要 id");
                if (!mgr(win).remove(id)) return err("没有脚本 " + id + "，用 scripts 查看");
                return ok().put("id", id).put("result", "已删除脚本 " + id + "（含存储的值），刷新页面后生效");
            }
            case "script_update":
                return scriptUpdate(win, req);
            case "script_values": {
                String id = req.optString("id", "").trim();
                if (id.isEmpty()) return err("script_values 需要 id");
                UserScriptManager m = mgr(win);
                if (m.get(id) == null) return err("没有脚本 " + id + "，用 scripts 查看");
                int changed = 0;
                JSONObject set = req.optJSONObject("set");
                if (set != null) {
                    for (Iterator<String> it = set.keys(); it.hasNext(); ) {
                        String k = it.next();
                        m.setValue(id, k, jsonText(set.opt(k)));
                        changed++;
                    }
                }
                JSONArray del = req.optJSONArray("delete");
                if (del != null) {
                    for (int i = 0; i < del.length(); i++) {
                        String k = del.optString(i, "");
                        if (k.isEmpty()) continue;
                        m.deleteValue(id, k);
                        changed++;
                    }
                }
                JSONObject vals = m.getValues(id);
                return ok().put("id", id).put("values", vals == null ? new JSONObject() : vals)
                        .put("result", changed == 0 ? "脚本 " + id + " 的存储值" : "已修改 " + changed + " 项（页面里的 GM_getValue 快照在刷新后更新）");
            }
            case "config_get": {
                JSONObject c = onMain(win::getConfig, 3000);
                return ok().put("config", c == null ? new JSONObject() : c);
            }
            case "config_set": {
                final JSONObject patch = req.optJSONObject("patch");
                if (patch == null || patch.length() == 0) return err("config_set 需要 patch（JSON 对象，如 {\"theme\":\"dark\"}）");
                onMain(() -> { win.setConfig(patch); return null; }, 5000);
                JSONObject c = onMain(win::getConfig, 3000);
                return ok().put("config", c == null ? new JSONObject() : c).put("result", "已合并写入 config.json 并重新加载（" + patch.length() + " 项）");
            }
            case "ua": {
                String v = req.isNull("ua") ? "" : req.optString("ua", "").trim();
                final String ua = v.isEmpty() ? null : v;
                onMain(() -> { win.setUserAgent(ua); return null; }, 5000);
                return ok().put("ua", ua == null ? JSONObject.NULL : ua).put("result", ua == null ? "已恢复默认 UA" : "已设置自定义 UA，刷新页面后生效");
            }
            case "desktop": {
                final boolean on = req.optBoolean("on", true);
                onMain(() -> { win.setDesktopMode(on); return null; }, 5000);
                return ok().put("desktop", on).put("result", on ? "已切换为桌面版网站" : "已切换为移动版网站");
            }
            case "zoom": {
                if (!req.has("percent")) return err("zoom 需要 percent（25–300）");
                final int pct = clamp(req.optInt("percent", 100), 25, 300);
                onMain(() -> { win.setZoom(pct); return null; }, 5000);
                return ok().put("zoom", pct).put("result", "已把页面缩放设为 " + pct + "%");
            }
            case "cookies": {
                String u = normalize(req.optString("url", ""));
                if (u.isEmpty()) u = peekUrl(win, req);
                if (u.isEmpty() || u.startsWith("about:")) return err("cookies 需要 url");
                final String url = u;
                final String cont = req.optString("container", "").trim();
                int n = 0;
                Object set = req.opt("set");
                List<String> list = new ArrayList<>();
                if (set instanceof JSONArray) {
                    JSONArray a = (JSONArray) set;
                    for (int i = 0; i < a.length(); i++) if (!a.optString(i, "").isEmpty()) list.add(a.optString(i));
                } else if (set instanceof String && !((String) set).isEmpty()) {
                    list.add((String) set);
                }
                if (cont.isEmpty()) {
                    // 默认：当前标签的容器，走 M2 的 setCookie/getCookies
                    for (final String c : list) {
                        onMain(() -> { win.setCookie(url, c); return null; }, 3000);
                        n++;
                    }
                    String got = onMain(() -> win.getCookies(url), 3000);
                    return ok().put("url", url).put("container", JSONObject.NULL).put("cookies", nz(got))
                            .put("result", (n > 0 ? "已写入 " + n + " 条 cookie；" : "") + (got == null || got.isEmpty() ? "该网址没有 cookie" : "该网址的 cookie 如上"));
                }
                // 指定容器：用该容器的 CookieManager（不存在的容器不新建 Profile）
                if (ContainerManager.supported() && !containerExists(win, cont)) return err("没有容器 " + cont + "，用 containers 查看");
                final android.webkit.CookieManager cm = onMain(() -> win.containers() == null ? android.webkit.CookieManager.getInstance() : win.containers().cookies(cont), 3000);
                if (cm == null) return err("拿不到容器 " + cont + " 的 cookie 管理器");
                for (final String c : list) {
                    onMain(() -> { cm.setCookie(url, c); return null; }, 3000);
                    n++;
                }
                if (n > 0) onMain(() -> { cm.flush(); return null; }, 3000);
                String got = onMain(() -> cm.getCookie(url), 3000);
                return ok().put("url", url).put("container", cont).put("cookies", nz(got))
                        .put("result", "容器 " + cont + "：" + (n > 0 ? "已写入 " + n + " 条 cookie；" : "") + (got == null || got.isEmpty() ? "该网址没有 cookie" : "该网址的 cookie 如上"));
            }
            case "clear_data": {
                final String what = req.optString("what", "").trim().toLowerCase();
                if (!what.equals("cookies") && !what.equals("cache") && !what.equals("storage") && !what.equals("all")) {
                    return err("clear_data 的 what 只能是 cookies / cache / storage / all");
                }
                onMain(() -> { win.clearData(what); return null; }, 10000);
                return ok().put("result", "已清除 " + what);
            }
            case "tab_new":
            case "tab_open":
                return tabNew(win, req);
            case "containers": {
                JSONArray a = onMain(() -> win.containers() == null ? new JSONArray() : win.containers().list(), 3000);
                if (a == null) a = new JSONArray();
                // 填每个容器里的标签数
                JSONArray tabs = onMain(win::agentTabsJson, 3000);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject c = a.optJSONObject(i);
                    if (c == null) continue;
                    String name = c.optString("name");
                    int cnt = 0;
                    if (tabs != null) for (int j = 0; j < tabs.length(); j++) {
                        JSONObject t = tabs.optJSONObject(j);
                        if (t != null && name.equals(t.optString("container"))) cnt++;
                    }
                    c.put("tabs", cnt);
                }
                return ok().put("containers", a).put("supported", ContainerManager.supported())
                        .put("result", ContainerManager.supported() ? "共 " + a.length() + " 个容器" : "系统 WebView 不支持多容器，所有标签共享登录");
            }
            case "container_keep": {
                String name = req.optString("name", "").trim();
                if (name.isEmpty()) return err("container_keep 需要 name");
                if (!containerExists(win, name)) return err("没有容器 " + name + "，用 containers 查看");
                boolean keep = req.optBoolean("keep", true);
                onMain(() -> { if (win.containers() != null) win.containers().setKeep(name, keep); return null; }, 3000);
                return ok().put("name", name).put("keep", keep)
                        .put("result", "已" + (keep ? "保留" : "取消保留") + "容器 " + name + (keep ? "（关闭标签后不删数据）" : "（关闭标签后会清除数据）"));
            }
            case "container_rename": {
                String name = req.optString("name", "").trim();
                String label = req.optString("label", "").trim();
                if (name.isEmpty() || label.isEmpty()) return err("container_rename 需要 name 和 label");
                if (!containerExists(win, name)) return err("没有容器 " + name + "，用 containers 查看");
                onMain(() -> { if (win.containers() != null) win.containers().rename(name, label); return null; }, 3000);
                return ok().put("name", name).put("label", label).put("result", "已把容器 " + name + " 改名为「" + label + "」");
            }
            case "history": {
                HistoryStore h = onMain(win::history, 3000);
                if (h == null) return err("历史记录不可用");
                String q = req.optString("q", "").trim();
                int limit = clamp(req.optInt("limit", 50), 1, 500);
                int offset = Math.max(0, req.optInt("offset", 0));
                JSONArray a = q.isEmpty() ? h.recent(limit, offset) : h.search(q, limit);
                if (a == null) a = new JSONArray();
                return ok().put("history", a)
                        .put("result", (q.isEmpty() ? "最近 " : "匹配「" + q + "」的 ") + a.length() + " 条历史（新的在前）");
            }
            case "history_delete": {
                HistoryStore h = onMain(win::history, 3000);
                if (h == null) return err("历史记录不可用");
                final String url = req.optString("url", "").trim();
                if (url.isEmpty()) return err("history_delete 需要 url（可为前缀）");
                int n = h.delete(url); // HistoryStore 线程安全，会等加载完成：不放主线程
                return ok().put("deleted", n).put("result", "已删除 " + n + " 条匹配「" + url + "」的历史");
            }
            case "history_clear": {
                HistoryStore h = onMain(win::history, 3000);
                if (h == null) return err("历史记录不可用");
                if (req.has("since_ms")) {
                    final long since = req.optLong("since_ms", 0);
                    h.clearSince(since);
                    String what = since < 100_000_000_000L ? "最近 " + (since / 1000) + " 秒" : "时间戳 " + since + " 之后";
                    return ok().put("result", "已清除" + what + "的历史");
                }
                h.clear();
                return ok().put("result", "已清除全部历史");
            }
            default:
                return doUi(win, action, req);
        }
    }

    // ------------------------------------------------------------------ 自定义浏览器（beta.24：ui.json / 面板 / 规则）

    private static BrowserCustomizer cust(FloatingApps win) throws Exception {
        BrowserCustomizer c = onMain(win::customizer, 3000);
        if (c == null) throw new IllegalStateException("浏览器自定义配置不可用（customizer 未初始化）");
        return c;
    }

    /** 写入类操作的统一结果：成功 {ok:true, errors:[]}；校验失败 {success:false, ok:false, errors:[...]}（未写入） */
    private interface UiWrite { void run() throws Exception; }

    private static JSONObject uiWrite(String what, UiWrite w) throws Exception {
        try {
            w.run();
        } catch (AbortException a) {
            throw a;
        } catch (Exception e) {
            String m = e.getMessage() == null ? e.toString() : e.getMessage();
            JSONArray errs = new JSONArray();
            for (String line : m.split("[;；]|\\n")) if (!line.trim().isEmpty()) errs.put(line.trim());
            return err(what + "失败，配置未改动：" + m).put("ok", false).put("errors", errs);
        }
        return ok().put("ok", true).put("errors", new JSONArray()).put("result", what + "成功，已立即生效");
    }

    /** JSON Pointer 段转义（RFC 6901）：~ → ~0，/ → ~1 */
    private static String ptrSeg(String s) {
        return s.replace("~", "~0").replace("/", "~1");
    }

    /** ui.bak 目录：appsDir/ui.bak（由 panels/ 目录反推 appsDir） */
    private static File backupDir(BrowserCustomizer c) {
        File pf = c.panelFile("_");
        File apps = pf == null ? null : pf.getParentFile() == null ? null : pf.getParentFile().getParentFile();
        return apps == null ? null : new File(apps, "ui.bak");
    }

    private static JSONObject doUi(FloatingApps win, String action, JSONObject req) throws Exception {
        switch (action) {
            case "ui_get": {
                BrowserCustomizer c = cust(win);
                String ptr = req.optString("pointer", "").trim();
                Object v = ptr.isEmpty() || ptr.equals("/") ? c.config() : c.get(ptr);
                return ok().put("pointer", ptr).put("value", v == null ? JSONObject.NULL : v)
                        .put("result", v == null ? "配置里没有 " + ptr : (ptr.isEmpty() ? "完整配置（含默认值）" : ptr + " 的当前值"));
            }
            case "ui_schema":
                return ok().put("schema", nz(BrowserCustomizer.schemaDoc())).put("builtins", BrowserCustomizer.builtins())
                        .put("result", "ui.json 结构说明与内置 id 列表");
            case "ui_set": {
                String ptr = req.optString("pointer", "").trim();
                if (ptr.isEmpty() || !ptr.startsWith("/")) return err("ui_set 需要 pointer（JSON Pointer，如 /theme/accent；/toolbar/- 追加）");
                if (!req.has("value")) return err("ui_set 需要 value");
                BrowserCustomizer c = cust(win);
                Object v = req.opt("value");
                return uiWrite("设置 " + ptr, () -> c.set(ptr, v));
            }
            case "ui_patch": {
                JSONObject patch = req.optJSONObject("patch");
                if (patch == null) return err("ui_patch 需要 patch（JSON 对象，RFC 7386 合并补丁；null 表示删除）");
                BrowserCustomizer c = cust(win);
                return uiWrite("合并补丁", () -> c.patch(patch));
            }
            case "ui_remove": {
                String ptr = req.optString("pointer", "").trim();
                if (ptr.isEmpty() || !ptr.startsWith("/")) return err("ui_remove 需要 pointer");
                BrowserCustomizer c = cust(win);
                return uiWrite("删除 " + ptr, () -> c.remove(ptr));
            }
            case "ui_reset": {
                String ptr = req.optString("pointer", "").trim();
                BrowserCustomizer c = cust(win);
                return uiWrite(ptr.isEmpty() ? "恢复全部默认" : "恢复默认 " + ptr, () -> c.reset(ptr.isEmpty() ? null : ptr));
            }
            case "ui_backups": {
                BrowserCustomizer c = cust(win);
                File dir = backupDir(c);
                File[] fs = dir == null ? null : dir.listFiles((d, n) -> n.endsWith(".json"));
                JSONArray a = new JSONArray();
                if (fs != null) {
                    java.util.Arrays.sort(fs, (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
                    for (File f : fs) a.put(new JSONObject().put("name", f.getName()).put("t", f.lastModified()).put("size", f.length()));
                }
                return ok().put("backups", a).put("result", a.length() == 0 ? "还没有备份（每次写入前自动备份）" : "共 " + a.length() + " 份备份（新的在前），用 ui_restore{name} 恢复");
            }
            case "ui_restore": {
                String name = req.optString("name", "").trim();
                if (name.isEmpty()) return err("ui_restore 需要 name（ui_backups 列出）");
                if (name.contains("/") || name.contains("\\") || name.startsWith(".")) return err("备份名不合法：" + name);
                BrowserCustomizer c = cust(win);
                File dir = backupDir(c);
                File f = dir == null ? null : new File(dir, name.endsWith(".json") ? name : name + ".json");
                if (f == null || !f.isFile()) return err("没有备份 " + name + "（用 ui_backups 查看）");
                JSONObject snap;
                try {
                    snap = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8));
                } catch (Exception e) {
                    return err("备份文件损坏：" + friendly(e));
                }
                final JSONObject sn = snap;
                return uiWrite("从备份 " + f.getName() + " 恢复", () -> c.restore(sn));
            }
            case "ui_reload":
                onMain(() -> { win.reloadUi(); return null; }, 5000);
                return ok().put("result", "已按 ui.json 重建工具栏/菜单/悬浮球并重新应用主题");
            case "panels": {
                JSONArray pa = cust(win).config().optJSONArray("panels");
                if (pa == null) pa = new JSONArray();
                return ok().put("panels", pa).put("result", pa.length() == 0 ? "还没有自定义面板" : "共 " + pa.length() + " 个面板");
            }
            case "panel_write": {
                String id = req.optString("id", "").trim();
                String html = req.optString("html", "");
                if (id.isEmpty() || !id.matches("[A-Za-z0-9._-]+") || id.startsWith(".")) return err("panel_write 需要 id（只允许 A-Z a-z 0-9 . _ -）");
                if (html.isEmpty()) return err("panel_write 需要 html");
                String title = req.optString("title", id);
                String icon = req.optString("icon", "🧩");
                String where = req.optString("where", "menu").trim();
                if (!where.equals("menu") && !where.equals("toolbar") && !where.equals("ball") && !where.equals("none")) return err("where 只能是 menu / toolbar / ball / none");
                BrowserCustomizer c = cust(win);
                JSONObject r = uiWrite("写入面板 " + id, () -> c.writePanel(id, title, icon, html, where));
                if (r.optBoolean("ok")) r.put("id", id).put("result", "已写入面板 " + id + "（" + where + "），用 panel_open{id} 打开");
                return r;
            }
            case "panel_open": {
                String id = req.optString("id", "").trim();
                if (id.isEmpty()) return err("panel_open 需要 id");
                File f = cust(win).panelFile(id);
                if (f == null || !f.isFile()) return err("没有面板 " + id + "（panels 列出）");
                onMain(() -> { win.openPanel(id); return null; }, 5000);
                return ok().put("id", id).put("result", "已打开面板 " + id);
            }
            case "panel_delete": {
                String id = req.optString("id", "").trim();
                if (id.isEmpty()) return err("panel_delete 需要 id");
                boolean gone = cust(win).deletePanel(id);
                return gone ? ok().put("result", "已删除面板 " + id) : err("没有面板 " + id);
            }
            case "action_run": {
                JSONObject act = req.optJSONObject("action");
                if (act == null) return err("action_run 需要 action（如 {type:\"builtin\",id:\"reload\"}）");
                if (act.optString("type", "").isEmpty()) return err("action 缺少 type（builtin/open/js/script_menu/panel/askpi/seq）");
                onMain(() -> { win.runAction(act); return null; }, 8000);
                return ok().put("result", "已执行 " + act.optString("type") + (act.has("id") ? " " + act.optString("id") : ""));
            }
            case "site_set": {
                String pat = req.optString("pattern", "").trim();
                if (pat.isEmpty()) return err("site_set 需要 pattern（如 *.example.com）");
                Object st = req.opt("settings");
                BrowserCustomizer c = cust(win);
                String ptr = "/sites/" + ptrSeg(pat);
                if (st == null || st == JSONObject.NULL) return uiWrite("删除站点规则 " + pat, () -> c.remove(ptr));
                if (!(st instanceof JSONObject)) return err("settings 应是对象，如 {ua:\"desktop\",zoom:120}");
                JSONObject sites = new JSONObject().put(pat, st);
                // 合并补丁：只改给出的字段，其余保留
                return uiWrite("设置站点 " + pat, () -> c.patch(new JSONObject().put("sites", sites)));
            }
            case "site_get": {
                String u = normalize(req.optString("url", ""));
                if (u.isEmpty()) u = peekUrl(win, req);
                if (u.isEmpty()) return err("site_get 需要 url");
                JSONObject st = cust(win).siteSettings(u);
                return ok().put("url", u).put("settings", st == null ? new JSONObject() : st)
                        .put("result", st == null || st.length() == 0 ? "没有匹配该网址的站点设置" : "该网址生效的站点设置");
            }
            case "rules": {
                BrowserCustomizer c = cust(win);
                Object r = c.get("/rules");
                JSONObject rules = r instanceof JSONObject ? (JSONObject) r : new JSONObject();
                return ok().put("rules", rules).put("result", "拦截 " + lenOf(rules.optJSONArray("block")) + " 条，重定向 " + lenOf(rules.optJSONArray("redirect")) + " 条");
            }
            case "rule_add": {
                String kind = req.optString("kind", "").trim();
                Object rule = req.opt("rule");
                if (!kind.equals("block") && !kind.equals("redirect")) return err("rule_add 的 kind 只能是 block / redirect");
                if (rule == null) return err("rule_add 需要 rule");
                if (kind.equals("block") && !(rule instanceof String)) return err("block 规则是字符串，如 ||ads.example.com^");
                if (kind.equals("redirect") && !(rule instanceof JSONObject)) return err("redirect 规则是对象 {from:正则, to:替换}");
                BrowserCustomizer c = cust(win);
                if (!(c.get("/rules") instanceof JSONObject)) {
                    JSONObject r0 = uiWrite("初始化规则", () -> c.set("/rules", new JSONObject()));
                    if (!r0.optBoolean("ok")) return r0;
                }
                if (!(c.get("/rules/" + kind) instanceof JSONArray)) {
                    JSONObject r0 = uiWrite("初始化规则", () -> c.set("/rules/" + kind, new JSONArray()));
                    if (!r0.optBoolean("ok")) return r0;
                }
                return uiWrite("添加 " + kind + " 规则", () -> c.set("/rules/" + kind + "/-", rule));
            }
            case "rule_remove": {
                String kind = req.optString("kind", "").trim();
                if (!kind.equals("block") && !kind.equals("redirect")) return err("rule_remove 的 kind 只能是 block / redirect");
                BrowserCustomizer c = cust(win);
                Object a0 = c.get("/rules/" + kind);
                JSONArray a = a0 instanceof JSONArray ? (JSONArray) a0 : new JSONArray();
                int idx = -1;
                if (req.has("index")) {
                    idx = req.optInt("index", -1);
                } else if (req.has("rule")) {
                    Object want = req.opt("rule");
                    for (int i = 0; i < a.length(); i++) {
                        Object x = a.opt(i);
                        boolean same = want instanceof JSONObject && x instanceof JSONObject
                                ? ((JSONObject) want).optString("from").equals(((JSONObject) x).optString("from"))
                                : String.valueOf(want).equals(String.valueOf(x));
                        if (same) { idx = i; break; }
                    }
                } else {
                    return err("rule_remove 需要 index 或 rule");
                }
                if (idx < 0 || idx >= a.length()) return err("没有找到要删除的 " + kind + " 规则（rules 查看）");
                final int i = idx;
                return uiWrite("删除 " + kind + " 规则 #" + i, () -> c.remove("/rules/" + kind + "/" + i));
            }
            default:
                return null;
        }
    }

    private static int lenOf(JSONArray a) {
        return a == null ? 0 : a.length();
    }

    /** tab_new {url?, container?:"new"|"same"|"Default"|<name>, background?}；默认 container=new、background=true（Pi 在自己的后台标签里干活） */
    private static JSONObject tabNew(FloatingApps win, JSONObject req) throws Exception {
        String url = normalize(req.optString("url", ""));
        String container = req.optString("container", "new").trim();
        if (container.isEmpty()) container = "new";
        boolean background = req.optBoolean("background", true);
        final String spec = mapContainer(container);
        if (spec != null && !spec.equalsIgnoreCase("same") && ContainerManager.supported() && !containerExists(win, spec)) {
            return err("没有容器 " + spec + "，用 containers 查看（新容器用 container:\"new\"）");
        }
        final boolean bg = background;
        FloatingApps.Tab t;
        synchronized (WINDOW_LOCK) {
            // 先开 about:blank，再像 new_tab 一样等它落定后导航，避免把 about:blank 的加载当成目标页完成
            t = onMain(() -> win.newTabInContainer(null, spec, bg), 6000);
        }
        if (t == null) return err("新建标签失败");
        CUR.get().tab = t;
        String cont = onMain(() -> win.containerOf(t), 3000);
        JSONObject out = ok().put("tab", t.id).put("container", nz(cont));
        if (url.isEmpty()) return out.put("url", nz(t.url)).put("result", "已新建标签 " + t.id + "（容器 " + nz(cont) + "）");
        ReentrantLock lk = lockOf(t);
        acquire(lk, t);
        try {
            long e = SystemClock.uptimeMillis() + 2000;
            while (SystemClock.uptimeMillis() < e && (t.navSeq == 0 || t.loading || t.finishedSeq != t.navSeq)) sleep(50);
            int seq = t.navSeq;
            String before = t.url;
            onMain(() -> { win.agentLoad(t, url); return null; }, 5000);
            boolean loaded = navWait(t, seq, before, softLeft());
            JSONObject bad = applyNav(out, t, loaded, "已在容器 " + nz(cont) + " 新建标签 " + t.id + " 并打开 " + url);
            if (bad != null) return bad.put("tab", t.id).put("container", nz(cont));
            return out.put("url", nz(t.url)).put("title", nz(t.title));
        } finally {
            lk.unlock();
        }
    }

    /** 容器是否存在（Default 总是存在）；不存在的名字不要交给 ContainerManager，否则会凭空建 Profile */
    private static boolean containerExists(FloatingApps win, String name) throws Exception {
        if (name == null || name.isEmpty()) return false;
        if (ContainerManager.DEFAULT.equalsIgnoreCase(name)) return true;
        Boolean ok = onMain(() -> win.containers() != null && win.containers().meta(name) != null, 3000);
        return Boolean.TRUE.equals(ok);
    }

    /** agent.js 失败结果 → 错误文本；ref 失效时带上 unknown_ref 标记，TS 侧据此提示重新 snapshot */
    private static String agentErr(JSONObject r, String def) {
        String e = r == null ? def : r.optString("error", def);
        if (r != null && "unknown_ref".equals(r.optString("code")) && !e.contains("unknown_ref")) e = "unknown_ref：" + e + "（重新 snapshot）";
        return e;
    }

    /** 把 action 里的 container 规格映射成 newTabInContainer 的参数：new -> null（新容器）；其余原样（same/Default/<name> 由 M2 解释） */
    private static String mapContainer(String spec) {
        if (spec == null) return null;
        String s = spec.trim();
        return s.isEmpty() || s.equalsIgnoreCase("new") ? null : s;
    }

    /** id 为空 = 检查全部；有新版本就覆盖安装（网络操作，在当前后台线程执行） */
    private static JSONObject scriptUpdate(FloatingApps win, JSONObject req) throws Exception {
        UserScriptManager m = mgr(win);
        String id = req.optString("id", "").trim();
        List<UserScriptManager.Script> targets = new ArrayList<>();
        if (!id.isEmpty()) {
            UserScriptManager.Script s = m.get(id);
            if (s == null) return err("没有脚本 " + id + "，用 scripts 查看");
            targets.add(s);
        } else {
            List<UserScriptManager.Script> l = m.list();
            if (l != null) targets.addAll(l);
        }
        JSONArray updated = new JSONArray();
        JSONArray failed = new JSONArray();
        int checked = 0;
        for (UserScriptManager.Script s : targets) {
            checkStop();
            if (id.isEmpty() && nz(s.updateUrl).isEmpty() && nz(s.downloadUrl).isEmpty() && nz(s.installUrl).isEmpty()) continue;
            checked++;
            try {
                String code = m.checkUpdate(s.id);
                if (code == null) continue;
                UserScriptManager.Script n = m.install(code, s.installUrl);
                updated.put(new JSONObject().put("id", n == null ? s.id : n.id).put("name", nz(s.name))
                        .put("from", nz(s.version)).put("to", n == null ? "" : nz(n.version)));
            } catch (AbortException a) {
                throw a;
            } catch (Throwable t) {
                failed.put(new JSONObject().put("id", s.id).put("error", friendly(t)));
            }
        }
        String res = "检查了 " + checked + " 个脚本，更新 " + updated.length() + " 个" + (failed.length() > 0 ? "，失败 " + failed.length() + " 个" : "")
                + (updated.length() > 0 ? "，刷新页面后生效" : "");
        return ok().put("updated", updated).put("failed", failed).put("checked", checked).put("result", res);
    }

    private static JSONObject console(FloatingApps win, FloatingApps.Tab tab, JSONObject req, JSONObject out) throws Exception {
        int max = clamp(req.optInt("max", 100), 1, 300);
        boolean clear = req.optBoolean("clear", false);
        JSONArray logs = onMain(() -> win.consoleLogs(tab, max), 3000);
        if (logs == null) logs = new JSONArray();
        int errors = 0;
        for (int i = 0; i < logs.length(); i++) {
            JSONObject o = logs.optJSONObject(i);
            if (o != null && "error".equalsIgnoreCase(o.optString("level", ""))) errors++;
        }
        // 读完再清空（M2 的 clearConsoleLogs），之后只会看到新日志
        if (clear) onMain(() -> { win.clearConsoleLogs(tab); return null; }, 3000);
        return out.put("logs", logs).put("url", tab.url)
                .put("result", (logs.length() == 0 ? "控制台没有新日志" : "控制台日志 " + logs.length() + " 条（其中错误 " + errors + " 条）")
                        + (clear ? "；已清空（之后只返回新日志）" : ""));
    }

    /** inject{js, fn?, persist?:{name,match}}：不带 persist 直接在页面执行；带 persist 生成用户脚本并安装，当前页匹配就立即注入一次 */
    private static JSONObject inject(FloatingApps win, FloatingApps.Tab tab, JSONObject req, JSONObject out) throws Exception {
        String js = req.optString("js", "");
        if (js.isEmpty()) return err("inject 需要 js");
        JSONObject persist = req.optJSONObject("persist");
        if (persist == null) {
            checkUser(tab);
            String[] v = evalAsync(tab, js, req.optBoolean("fn", false), Math.max(1000, softLeft()));
            if (v[0] != null) return err(v[0]).put("url", tab.url);
            return out.put("url", tab.url).put("result", v[1]);
        }
        String host = "";
        try {
            host = nz(android.net.Uri.parse(nz(tab.url)).getHost());
        } catch (Throwable ignored) {}
        List<String> matches = new ArrayList<>();
        Object mo = persist.opt("match");
        if (mo instanceof JSONArray) {
            JSONArray a = (JSONArray) mo;
            for (int i = 0; i < a.length(); i++) if (!a.optString(i, "").trim().isEmpty()) matches.add(a.optString(i).trim());
        } else if (mo instanceof String && !((String) mo).trim().isEmpty()) {
            matches.add(((String) mo).trim());
        }
        if (matches.isEmpty()) {
            if (host.isEmpty()) return err("persist 需要 match（如 *://*.example.com/*），当前页没有可用的域名");
            matches.add("*://" + host + "/*");
        }
        String name = persist.optString("name", "").trim();
        if (name.isEmpty()) name = "Pi 脚本 " + (host.isEmpty() ? matches.get(0) : host);
        StringBuilder sb = new StringBuilder();
        sb.append("// ==UserScript==\n");
        sb.append("// @name         ").append(name.replace("\n", " ").replace("\r", " ")).append("\n");
        sb.append("// @namespace    pimet-agent\n");
        sb.append("// @version      ").append(persist.optString("version", "1.0").trim()).append("\n");
        sb.append("// @description  由 Pi 通过 browser inject 生成\n");
        for (String mt : matches) sb.append("// @match        ").append(mt.replace("\n", " ").replace("\r", " ")).append("\n");
        String runAt = persist.optString("run_at", persist.optString("runAt", "document-idle")).trim();
        sb.append("// @run-at       ").append(runAt.isEmpty() ? "document-idle" : runAt).append("\n");
        sb.append("// @grant        none\n");
        sb.append("// ==/UserScript==\n\n");
        sb.append(js).append("\n");
        UserScriptManager m = mgr(win);
        UserScriptManager.Script s = m.install(sb.toString(), null);
        if (s == null) return err("安装脚本失败");
        boolean ran = false;
        if (matchingIds(m, tab.url).contains(s.id)) {
            checkUser(tab);
            String code = m.buildInjection(s, tab.id);
            if (code != null && !code.isEmpty()) {
                tab.injectedIds.add(s.id); // 否则 PiMetGM 会拒绝这次注入里的 GM 调用
                evalRawSafe(tab, code, 5000);
                ran = true;
            }
        }
        return out.put("url", tab.url).put("script", scriptBrief(s, matchingIds(m, tab.url)))
                .put("result", "已安装为用户脚本「" + nz(s.name) + "」（id " + s.id + "，匹配 " + joinSp(matches) + "）"
                        + (ran ? "，并已在当前页执行一次" : "，当前页不匹配，打开匹配的网页时生效"));
    }

    private static String joinSp(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String x : l) b.append(b.length() == 0 ? "" : " ").append(x);
        return b.toString();
    }

    /** 执行返回 JSON 字符串的表达式，结果解析成对象（失败返回空对象） */
    private static JSONObject evalJson(FloatingApps.Tab tab, String expr, long timeoutMs) throws Exception {
        Object v = parseRaw(evalRaw(tab, "(function(){try{return JSON.stringify(" + expr + ")}catch(e){return JSON.stringify({error:String(e)})}})()", timeoutMs));
        if (!(v instanceof String)) return new JSONObject();
        Object o = new JSONTokener((String) v).nextValue();
        return o instanceof JSONObject ? (JSONObject) o : new JSONObject();
    }

    // ---- ego 式目标/快照辅助

    /** click/hover/scroll_into_view 的目标：target（@N / CSS / text= / [x,y]）优先，其次 ref，其次 text=... */
    private static String targetOf(JSONObject req) {
        String t = req.optString("target", "").trim();
        if (!t.isEmpty()) return t;
        String ref = req.optString("ref", "").trim();
        if (!ref.isEmpty()) return ref;
        String text = req.optString("text", "").trim();
        if (!text.isEmpty()) return text.startsWith("text=") ? text : "text=" + text;
        return "";
    }

    /** fill/type/select/scroll 的目标：target 优先（会去掉 ref 的歧义），否则 ref */
    private static String fieldOf(JSONObject req) {
        String t = req.optString("target", "").trim();
        return t.isEmpty() ? req.optString("ref", "").trim() : t;
    }

    /** 快照范围：scope（full | viewport）优先，兼容旧的 mode */
    private static String scopeOf(JSONObject req) {
        String sc = req.optString("scope", "").trim();
        if (!sc.isEmpty()) return sc;
        String m = req.optString("mode", "full").trim();
        return m.isEmpty() ? "full" : m;
    }

    /** 操作后标注 changed：发生了导航，或 URL 变了 */
    private static void markChanged(JSONObject out, FloatingApps.Tab tab, int seqBefore, String urlBefore) {
        boolean navd = tab.navSeq != seqBefore;
        boolean urlChg = !nz(urlBefore).equals(nz(tab.url));
        try { out.put("changed", navd || urlChg); } catch (Throwable ignored) {}
    }

    private static final String HOVER_JS = "(function(){try{var el=window.__pimetTarget;if(!el)return{ok:false};"
            + "var W=(el.ownerDocument&&el.ownerDocument.defaultView)||window;var r=el.getBoundingClientRect();"
            + "var x=r.left+r.width/2,y=r.top+r.height/2;['pointerover','mouseover','pointerenter','mouseenter','pointermove','mousemove'].forEach(function(t){"
            + "try{var C=t.indexOf('pointer')===0&&W.PointerEvent?W.PointerEvent:W.MouseEvent;el.dispatchEvent(new C(t,{bubbles:true,cancelable:true,composed:true,clientX:x,clientY:y,view:W}))}catch(e){}});return{ok:true}}catch(e){return{ok:false,error:String(e)}}})()";

    /** wait_for：等 selector 或 text 出现。返回 {found, waited_ms, result} */
    private static JSONObject waitFor(FloatingApps.Tab tab, JSONObject req) throws Exception {
        String sel = req.optString("selector", "");
        String text = req.optString("text", "");
        boolean visible = req.optBoolean("visible", false);
        long tmo = clamp(req.optInt("timeout_ms", 10000), 0, 30000);
        long t0 = SystemClock.uptimeMillis();
        long end = t0 + Math.min(tmo, softLeft());
        boolean found = false;
        boolean badSel = false;
        while (true) {
            checkStop();
            if (!sel.isEmpty()) {
                String raw = evalRawSafe(tab, WAIT_SEL_JS + "(" + JSONObject.quote(sel) + ")", 3000);
                int v = -2;
                try { v = raw == null ? -2 : Integer.parseInt(raw.trim()); } catch (Throwable ignored) {}
                if (v == -1) { badSel = true; break; }
                if (v == 2 || (v == 1 && !visible)) { found = true; break; }
            } else {
                JSONObject r;
                try {
                    r = evalAgent(tab, "A.hasText(" + JSONObject.quote(text) + ")", 3000);
                } catch (AbortException a) {
                    throw a;
                } catch (Exception e) {
                    r = new JSONObject();
                }
                if (r.optBoolean("v")) { found = true; break; }
            }
            if (SystemClock.uptimeMillis() >= end) break;
            sleep(300);
        }
        JSONObject out = new JSONObject();
        if (badSel) return out.put("found", false).put("waited_ms", (int) (SystemClock.uptimeMillis() - t0)).put("bad_selector", true).put("result", "选择器写法不对：" + sel);
        String what = sel.isEmpty() ? "「" + text + "」" : "元素 " + sel;
        return out.put("found", found).put("waited_ms", (int) (SystemClock.uptimeMillis() - t0))
                .put("result", found ? "页面已出现" + what : "等待超时（" + tmo + "ms），页面没有出现" + what);
    }

    /**
     * batch：一次执行多步页面操作，少一次次往返。steps:[{action,target?,value?,key?,ms?,...}]，stopOnError 默认 true。
     * 支持的 action：click/tap、type/fill、select、press/key、hover、scroll、scroll_into_view、wait（ms 或 text）、wait_for、snapshot。
     * 返回 {steps:[每步结果], stopped_at?, 末尾若有 snapshot 则把 snapshot/url/title/refs 提到顶层}。
     */
    private static JSONObject batch(FloatingApps win, FloatingApps.Tab tab, JSONObject req, JSONObject out) throws Exception {
        JSONArray steps = req.optJSONArray("steps");
        if (steps == null || steps.length() == 0) return err("batch 需要 steps（数组）");
        boolean stopOnError = req.optBoolean("stopOnError", true);
        JSONArray results = new JSONArray();
        int stoppedAt = -1;
        JSONObject lastSnap = null;
        for (int i = 0; i < steps.length(); i++) {
            checkStop();
            checkUser(tab);
            JSONObject st = steps.optJSONObject(i);
            if (st == null) { results.put(new JSONObject().put("i", i).put("ok", false).put("error", "步骤不是对象")); if (stopOnError) { stoppedAt = i; break; } else continue; }
            String act = st.optString("action", st.optString("op", "")).trim().toLowerCase();
            JSONObject sr = new JSONObject().put("i", i).put("action", act);
            try {
                int seq = tab.navSeq;
                String before = tab.url;
                switch (act) {
                    case "click": case "tap":
                        sr.put("result", click(win, tab, st)); settle(tab, seq, sr); markChanged(sr, tab, seq, before); break;
                    case "type": case "fill":
                        sr.put("result", type(win, tab, st)); settle(tab, seq, sr); markChanged(sr, tab, seq, before); break;
                    case "select": {
                        JSONObject r = evalAgent(tab, "A.select(" + JSONObject.quote(fieldOf(st)) + "," + JSONObject.quote(st.optString("value", "")) + ")", 5000);
                        if (!r.optBoolean("ok")) throw new IllegalStateException(agentErr(r, "选择失败"));
                        sr.put("result", r.optString("result")); settle(tab, seq, sr); markChanged(sr, tab, seq, before); break;
                    }
                    case "press": case "key": {
                        String key = st.optString("key", "Enter");
                        int code = keyCode(key);
                        if (code == 0) throw new IllegalStateException("不支持的按键 " + key);
                        pressKey(win, tab, key, code);
                        sr.put("result", "已按 " + key); settle(tab, seq, sr); markChanged(sr, tab, seq, before); break;
                    }
                    case "hover": {
                        String tgt = targetOf(st);
                        if (tgt.isEmpty()) throw new IllegalStateException("hover 需要 target");
                        JSONObject loc = evalAgent(tab, "A.locate(" + JSONObject.quote(tgt) + "," + JSONObject.quote("") + ")", 5000);
                        if (!loc.optBoolean("ok")) throw new IllegalStateException(agentErr(loc, "定位失败"));
                        evalJson(tab, HOVER_JS, 3000);
                        sr.put("result", "已悬停 " + tgt); sleep(120); break;
                    }
                    case "scroll": {
                        JSONObject r = evalAgent(tab, "A.scroll(" + JSONObject.quote(fieldOf(st)) + "," + JSONObject.quote(st.optString("direction", "down")) + "," + st.optDouble("amount", 0.8) + ")", 5000);
                        if (!r.optBoolean("ok")) throw new IllegalStateException(agentErr(r, "滚动失败"));
                        sr.put("result", r.optString("result")); sleep(200); break;
                    }
                    case "scroll_into_view": {
                        String tgt = targetOf(st);
                        if (tgt.isEmpty()) throw new IllegalStateException("scroll_into_view 需要 target");
                        JSONObject loc = evalAgent(tab, "A.locate(" + JSONObject.quote(tgt) + "," + JSONObject.quote("") + ")", 5000);
                        if (!loc.optBoolean("ok")) throw new IllegalStateException(agentErr(loc, "定位失败"));
                        sr.put("result", "已滚动到可见"); break;
                    }
                    case "wait": {
                        String text = st.optString("text", "");
                        if (!text.isEmpty()) { JSONObject w = waitFor(tab, new JSONObject().put("text", text).put("timeout_ms", st.optInt("timeout_ms", st.optInt("ms", 10000)))); sr.put("result", w.optString("result")).put("found", w.optBoolean("found")); }
                        else { sleep(clamp(st.optInt("ms", 1000), 0, 60000)); waitLoadedNow(tab, softLeft()); sr.put("result", "已等待"); }
                        break;
                    }
                    case "wait_for": {
                        JSONObject w = waitFor(tab, st);
                        sr.put("result", w.optString("result")).put("found", w.optBoolean("found")); break;
                    }
                    case "snapshot": case "read": {
                        JSONObject snap = snapshot(tab, st.optInt("max_chars", req.optInt("max_chars", 12000)), scopeOf(st));
                        sr.put("result", "快照").put("snapshot", snap.optString("text"));
                        lastSnap = snap; break;
                    }
                    default:
                        throw new IllegalStateException("batch 不支持的 action：" + act);
                }
                sr.put("ok", true).put("url", tab.url);
            } catch (AbortException a) {
                throw a;
            } catch (Throwable t) {
                sr.put("ok", false).put("error", friendly(t)).put("url", tab.url);
                results.put(sr);
                if (stopOnError) { stoppedAt = i; break; }
                continue;
            }
            results.put(sr);
        }
        out.put("steps", results).put("url", tab.url).put("title", tab.title);
        if (stoppedAt >= 0) out.put("stopped_at", stoppedAt);
        if (lastSnap != null) {
            out.put("snapshot", lastSnap.optString("text"));
            out.put("url", lastSnap.optString("url", tab.url));
            out.put("title", lastSnap.optString("title", tab.title));
            if (lastSnap.has("refs")) out.put("refs", lastSnap.opt("refs"));
        }
        out.put("result", "执行了 " + results.length() + "/" + steps.length() + " 步" + (stoppedAt >= 0 ? "，在第 " + stoppedAt + " 步出错停止" : ""));
        return out;
    }

    /** 页面内查找（忽略大小写，含同源 iframe）：{n, samples:[最多 5 段上下文]} */
    private static final String FIND_JS = "(function(q){q=String(q).toLowerCase();var n=0,S=[];"
            + "function cnt(d){try{var raw=(d.body&&d.body.innerText)||'';var t=raw.toLowerCase();var i=0;"
            + "while(q&&(i=t.indexOf(q,i))>=0){n++;if(S.length<5)S.push(raw.substring(Math.max(0,i-30),Math.min(raw.length,i+q.length+30)).replace(/\\s+/g,' '));i+=q.length;}"
            + "var fs=d.querySelectorAll('iframe');for(var k=0;k<fs.length;k++){try{if(fs[k].contentDocument)cnt(fs[k].contentDocument)}catch(e){}}}catch(e){}}"
            + "cnt(document);return {n:n,samples:S}})";
    /** wait_for 选择器：2 = 存在且可见，1 = 存在但不可见，0 = 不存在，-1 = 选择器无效 */
    private static final String WAIT_SEL_JS = "(function(s){var e;try{e=document.querySelector(s)}catch(x){return -1}if(!e)return 0;"
            + "try{var r=e.getBoundingClientRect(),st=getComputedStyle(e);return (r.width>0||r.height>0)&&st.visibility!=='hidden'&&st.display!=='none'?2:1}catch(x){return 1}})";

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
