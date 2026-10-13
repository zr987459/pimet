package com.xm486.pimet.terminal;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.xm486.pimet.proot.ProotManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 全局终端会话中心：基于 Termux terminal-emulator 的真 PTY 会话（运行在 PRoot 容器里的 bash）。
 * 悬浮终端窗口与 Pi 的 terminal 工具共享同一批会话：AI 在后台开的会话，用户随时能在悬浮窗里看到并接管。
 */
public final class TerminalHub {

    private static final String TAG = "PiMet.TermHub";
    private static final int TRANSCRIPT_ROWS = 5000;
    private static final int HEADLESS_COLS = 100;
    private static final int HEADLESS_ROWS = 32;

    public static final class Entry {
        public final String id;
        public String name;
        public final TerminalSession session;
        public final long createdAt = System.currentTimeMillis();
        public volatile long lastOutputAt = SystemClock.uptimeMillis();
        public volatile boolean finished = false;
        public volatile int exitCode = 0;
        public final boolean byAi;

        Entry(String id, String name, TerminalSession session, boolean byAi) {
            this.id = id;
            this.name = name;
            this.session = session;
            this.byAi = byAi;
        }
    }

    /** 悬浮窗等界面监听会话变化 */
    public interface Listener {
        void onTextChanged(Entry e);

        void onSessionsChanged();

        void onBell(Entry e);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Entry> ENTRIES = new CopyOnWriteArrayList<>();
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static int seq = 0;
    private static int runSeq = 0;
    private static Context appCtx;

    private TerminalHub() {
    }

    public static void addListener(Listener l) {
        if (l != null && !LISTENERS.contains(l)) LISTENERS.add(l);
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    public static List<Entry> entries() {
        return new ArrayList<>(ENTRIES);
    }

    public static Entry get(String id) {
        if (TextUtils.isEmpty(id)) return ENTRIES.isEmpty() ? null : ENTRIES.get(ENTRIES.size() - 1);
        for (Entry e : ENTRIES) if (e.id.equals(id) || e.name.equals(id)) return e;
        return null;
    }

    public static Entry find(TerminalSession s) {
        for (Entry e : ENTRIES) if (e.session == s) return e;
        return null;
    }

    // ---------------------------------------------------------------- 主线程工具

    static <T> T onMain(Callable<T> c, long timeoutMs) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return c.call();
        final AtomicReference<T> ref = new AtomicReference<>();
        final AtomicReference<Exception> err = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        MAIN.post(() -> {
            try {
                ref.set(c.call());
            } catch (Exception ex) {
                err.set(ex);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw new IllegalStateException("主线程超时");
        if (err.get() != null) throw err.get();
        return ref.get();
    }

    // ---------------------------------------------------------------- 会话管理

    private static final TerminalSessionClient CLIENT = new TerminalSessionClient() {
        @Override
        public void onTextChanged(TerminalSession s) {
            Entry e = find(s);
            if (e == null) return;
            e.lastOutputAt = SystemClock.uptimeMillis();
            for (Listener l : LISTENERS) l.onTextChanged(e);
        }

        @Override
        public void onTitleChanged(TerminalSession s) {
            for (Listener l : LISTENERS) l.onSessionsChanged();
        }

        @Override
        public void onSessionFinished(TerminalSession s) {
            Entry e = find(s);
            if (e != null) {
                e.finished = true;
                try {
                    e.exitCode = s.getExitStatus();
                } catch (Throwable ignored) {
                }
            }
            for (Listener l : LISTENERS) l.onSessionsChanged();
        }

        @Override
        public void onCopyTextToClipboard(TerminalSession s, String text) {
            copyToClipboard(text);
        }

        @Override
        public void onPasteTextFromClipboard(TerminalSession s) {
            paste(s);
        }

        @Override
        public void onBell(TerminalSession s) {
            Entry e = find(s);
            if (e != null) for (Listener l : LISTENERS) l.onBell(e);
        }

        @Override
        public void onColorsChanged(TerminalSession s) {
            Entry e = find(s);
            if (e != null) for (Listener l : LISTENERS) l.onTextChanged(e);
        }

        @Override
        public void onTerminalCursorStateChange(boolean state) {
        }

        @Override
        public Integer getTerminalCursorStyle() {
            return TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK;
        }

        @Override
        public void logError(String tag, String message) {
            Log.e(tag, message);
        }

        @Override
        public void logWarn(String tag, String message) {
            Log.w(tag, message);
        }

        @Override
        public void logInfo(String tag, String message) {
        }

        @Override
        public void logDebug(String tag, String message) {
        }

        @Override
        public void logVerbose(String tag, String message) {
        }

        @Override
        public void logStackTraceWithMessage(String tag, String message, Exception e) {
            Log.w(tag, message, e);
        }

        @Override
        public void logStackTrace(String tag, Exception e) {
            Log.w(tag, e);
        }
    };

    public static TerminalSessionClient client() {
        return CLIENT;
    }

    /**
     * 新建会话（任意线程调用）。command 为空时启动登录 bash；否则在 bash 里执行该命令，结束后回到交互 shell。
     */
    public static Entry create(Context ctx, String name, String cwd, String command, boolean byAi) throws Exception {
        appCtx = ctx.getApplicationContext();
        final String dir = TextUtils.isEmpty(cwd) ? "/root" : cwd;
        List<String> guest = null;
        if (!TextUtils.isEmpty(command)) {
            guest = new ArrayList<>();
            guest.add("/bin/bash");
            guest.add("-lc");
            guest.add(command + "; exec /bin/bash -l");
        }
        ProcessBuilder pb = ProotManager.buildProotProcess(appCtx, dir, guest);
        final List<String> argv = pb.command();
        final List<String> envList = new ArrayList<>();
        for (Map.Entry<String, String> kv : pb.environment().entrySet()) {
            envList.add(kv.getKey() + "=" + kv.getValue());
        }
        envList.add("COLORTERM=truecolor");
        envList.add("PIMET_TERMINAL=1");
        final String hostCwd = appCtx.getFilesDir().getAbsolutePath();
        return onMain(() -> {
            seq++;
            String id = "t" + seq;
            String nm = TextUtils.isEmpty(name) ? (byAi ? "AI-" + seq : "终端" + seq) : name;
            TerminalSession s = new TerminalSession(argv.get(0), hostCwd,
                    argv.toArray(new String[0]), envList.toArray(new String[0]), TRANSCRIPT_ROWS, CLIENT);
            Entry e = new Entry(id, nm, s, byAi);
            ENTRIES.add(e);
            // 无界面时也要先起进程：用默认尺寸初始化，悬浮窗挂上后会按真实尺寸重排
            s.updateSize(HEADLESS_COLS, HEADLESS_ROWS);
            for (Listener l : LISTENERS) l.onSessionsChanged();
            return e;
        }, 5000);
    }

    public static void close(Entry e) {
        if (e == null) return;
        MAIN.post(() -> {
            try {
                e.session.finishIfRunning();
            } catch (Throwable ignored) {
            }
            ENTRIES.remove(e);
            for (Listener l : LISTENERS) l.onSessionsChanged();
        });
    }

    // ---------------------------------------------------------------- 输入

    public static void write(Entry e, String text) {
        if (e == null || text == null) return;
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        MAIN.post(() -> e.session.write(b, 0, b.length));
    }

    public static void paste(TerminalSession s) {
        if (appCtx == null || s == null) return;
        try {
            ClipboardManager cm = (ClipboardManager) appCtx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return;
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            CharSequence t = clip.getItemAt(0).coerceToText(appCtx);
            if (!TextUtils.isEmpty(t) && s.getEmulator() != null) s.getEmulator().paste(t.toString());
        } catch (Throwable ignored) {
        }
    }

    public static void copyToClipboard(String text) {
        if (appCtx == null || text == null) return;
        try {
            ClipboardManager cm = (ClipboardManager) appCtx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("terminal", text));
        } catch (Throwable ignored) {
        }
    }

    /** 按键名 → 终端字节序列 */
    public static String keySeq(String key) {
        if (key == null) return "";
        String k = key.trim().toLowerCase(Locale.ROOT).replace("+", "-").replace("_", "-");
        switch (k) {
            case "enter": case "return": return "\r";
            case "tab": return "\t";
            case "shift-tab": case "backtab": return "\033[Z";
            case "esc": case "escape": return "\033";
            case "backspace": case "bs": return "\177";
            case "delete": case "del": return "\033[3~";
            case "up": return "\033[A";
            case "down": return "\033[B";
            case "right": return "\033[C";
            case "left": return "\033[D";
            case "home": return "\033[H";
            case "end": return "\033[F";
            case "pageup": case "pgup": return "\033[5~";
            case "pagedown": case "pgdn": return "\033[6~";
            case "insert": return "\033[2~";
            case "space": return " ";
            case "f1": return "\033OP";
            case "f2": return "\033OQ";
            case "f3": return "\033OR";
            case "f4": return "\033OS";
            case "f5": return "\033[15~";
            case "f6": return "\033[17~";
            case "f7": return "\033[18~";
            case "f8": return "\033[19~";
            case "f9": return "\033[20~";
            case "f10": return "\033[21~";
            case "f11": return "\033[23~";
            case "f12": return "\033[24~";
            default:
                break;
        }
        if ((k.startsWith("ctrl-") || k.startsWith("c-")) && k.length() > k.indexOf('-') + 1) {
            char c = k.charAt(k.indexOf('-') + 1);
            if (c >= 'a' && c <= 'z') return String.valueOf((char) (c - 'a' + 1));
            switch (c) {
                case '[': return "\033";
                case '\\': return "\034";
                case ']': return "\035";
                case '^': return "\036";
                case '_': return "\037";
                case '@': case ' ': return "\000";
                default: return "";
            }
        }
        if ((k.startsWith("alt-") || k.startsWith("m-")) && k.length() > k.indexOf('-') + 1) {
            return "\033" + key.substring(key.indexOf('-') + 1);
        }
        return key.length() == 1 ? key : "";
    }

    // ---------------------------------------------------------------- 读取

    /** 当前屏幕（可见区域）文本 */
    public static String screenText(Entry e) throws Exception {
        return onMain(() -> {
            TerminalEmulator em = e.session.getEmulator();
            if (em == null) return "";
            TerminalBuffer b = em.getScreen();
            return rtrimLines(b.getSelectedText(0, 0, em.mColumns, em.mRows - 1, true));
        }, 3000);
    }

    /** 包含回滚历史的全部文本 */
    public static String transcript(Entry e) throws Exception {
        return onMain(() -> {
            TerminalEmulator em = e.session.getEmulator();
            if (em == null) return "";
            return rtrimLines(em.getScreen().getTranscriptText());
        }, 3000);
    }

    private static String rtrimLines(String s) {
        if (s == null) return "";
        String[] lines = s.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            int end = l.length();
            while (end > 0 && Character.isWhitespace(l.charAt(end - 1))) end--;
            sb.append(l, 0, end).append('\n');
        }
        String r = sb.toString();
        int end = r.length();
        while (end > 0 && r.charAt(end - 1) == '\n') end--;
        return r.substring(0, end);
    }

    private static String tail(String s, int lines) {
        if (s == null) return "";
        if (lines <= 0) return s;
        int idx = s.length();
        for (int i = 0; i < lines && idx > 0; i++) {
            idx = s.lastIndexOf('\n', idx - 1);
            if (idx < 0) {
                idx = 0;
                break;
            }
        }
        String r = idx <= 0 ? s : s.substring(idx + 1);
        return r;
    }

    private static String clip(String s, int maxChars) {
        if (s == null) return "";
        if (s.length() <= maxChars) return s;
        return "…(已截断前 " + (s.length() - maxChars) + " 个字符)\n" + s.substring(s.length() - maxChars);
    }

    private static void waitIdle(Entry e, long idleMs, long deadline) {
        while (SystemClock.uptimeMillis() < deadline) {
            if (SystemClock.uptimeMillis() - e.lastOutputAt >= idleMs) return;
            SystemClock.sleep(60);
        }
    }

    // ---------------------------------------------------------------- 供 AI 工具调用

    /** 处理 Pi terminal 工具请求（在桥接线程上执行） */
    public static JSONObject handle(Context ctx, JSONObject json) {
        try {
            appCtx = ctx.getApplicationContext();
            String op = json.optString("terminal_action", json.optString("op", "list")).toLowerCase(Locale.ROOT);
            String id = json.optString("id", json.optString("session", ""));
            int maxChars = Math.max(500, Math.min(60000, json.optInt("max_chars", 12000)));
            switch (op) {
                case "list": {
                    JSONObject r = ok();
                    JSONArray arr = new JSONArray();
                    for (Entry e : ENTRIES) arr.put(describe(e));
                    r.put("sessions", arr);
                    r.put("floating_visible", FloatingTerminal.isShowing());
                    return r;
                }
                case "create":
                case "new":
                case "open": {
                    Entry e = create(ctx, json.optString("name", ""), json.optString("cwd", "/root"),
                            json.optString("command", ""), true);
                    if (json.optBoolean("show", false)) FloatingTerminal.show(ctx, e.id);
                    long deadline = SystemClock.uptimeMillis() + 6000;
                    waitIdle(e, 500, deadline);
                    JSONObject r = ok();
                    r.put("session", describe(e));
                    r.put("screen", clip(screenText(e), maxChars));
                    return r;
                }
                case "show":
                case "focus": {
                    Entry e = get(id);
                    FloatingTerminal.show(ctx, e == null ? null : e.id);
                    return ok().put("result", "已在悬浮终端中显示" + (e == null ? "" : " " + e.id));
                }
                case "hide": {
                    FloatingTerminal.hide();
                    return ok().put("result", "已隐藏悬浮终端（会话继续在后台运行）");
                }
                case "close":
                case "kill": {
                    Entry e = get(id);
                    if (e == null) return fail("会话不存在: " + id);
                    close(e);
                    return ok().put("result", "已关闭会话 " + e.id);
                }
                default:
                    break;
            }

            Entry e0 = get(id);
            if (e0 == null) {
                if (op.equals("run") || op.equals("exec") || op.equals("send") || op.equals("write")) {
                    e0 = create(ctx, "", "/root", "", true);
                    waitIdle(e0, 500, SystemClock.uptimeMillis() + 6000);
                } else {
                    return fail(TextUtils.isEmpty(id) ? "当前没有终端会话，先 create" : "会话不存在: " + id);
                }
            }
            final Entry e = e0;
            if (e.finished) return fail("会话 " + e.id + " 已退出（exit " + e.exitCode + "），请 close 后重新 create");

            switch (op) {
                case "run":
                case "exec": {
                    String cmd = json.optString("command", "");
                    if (TextUtils.isEmpty(cmd)) return fail("run 需要 command");
                    long timeout = Math.max(500, Math.min(600000, json.optLong("timeout", 30000)));
                    int n;
                    synchronized (TerminalHub.class) {
                        n = ++runSeq;
                    }
                    String tag = "__PIMET_END_" + n + "_";
                    // 结束标记：命令结束后打印退出码，用于精确判断完成并截取本次输出
                    String marker = "printf '\\n" + tag + "%s__\\n' \"$?\"";
                    String c0 = cmd.replace("\r", "").trim();
                    // 单行命令：标记接在同一行，交互程序退出后才打印；多行脚本：标记另起一行
                    write(e, (c0.contains("\n") ? c0 + "\n " + marker : c0 + " ; " + marker) + "\r");
                    Pattern done = Pattern.compile("(?m)^" + Pattern.quote(tag) + "(\\d+)__$");
                    long deadline = SystemClock.uptimeMillis() + timeout;
                    String text = "";
                    Integer code = null;
                    while (SystemClock.uptimeMillis() < deadline) {
                        SystemClock.sleep(120);
                        text = transcript(e);
                        Matcher m = done.matcher(text);
                        if (m.find()) {
                            code = Integer.parseInt(m.group(1));
                            String before = text.substring(0, m.start());
                            int echo = before.lastIndexOf(tag + "%s__");
                            if (echo >= 0) {
                                int nl = before.indexOf('\n', echo);
                                before = nl >= 0 ? before.substring(nl + 1) : "";
                            }
                            text = rtrimLines(before);
                            break;
                        }
                    }
                    JSONObject r = ok();
                    r.put("session", e.id);
                    if (code != null) {
                        r.put("exit_code", code);
                        r.put("output", clip(text, maxChars));
                    } else {
                        r.put("running", true);
                        r.put("note", "命令在 " + timeout + "ms 内未结束（可能仍在运行或在等待输入），可用 read 查看、send_keys 发 ctrl-c 中断或继续输入");
                        r.put("screen", clip(screenText(e), maxChars));
                    }
                    return r;
                }
                case "send":
                case "write":
                case "input": {
                    String text = json.optString("text", json.optString("command", ""));
                    boolean enter = json.optBoolean("enter", true);
                    write(e, text + (enter ? "\r" : ""));
                    long wait = Math.max(0, Math.min(30000, json.optLong("wait", 800)));
                    long deadline = SystemClock.uptimeMillis() + wait;
                    SystemClock.sleep(Math.min(150, wait));
                    waitIdle(e, 300, deadline);
                    JSONObject r = ok();
                    r.put("session", e.id);
                    r.put("screen", clip(screenText(e), maxChars));
                    return r;
                }
                case "keys":
                case "send_keys":
                case "key": {
                    Object k = json.opt("keys");
                    if (k == null) k = json.opt("key");
                    List<String> keys = new ArrayList<>();
                    if (k instanceof JSONArray) {
                        JSONArray a = (JSONArray) k;
                        for (int i = 0; i < a.length(); i++) keys.add(a.optString(i));
                    } else if (k != null) {
                        for (String p : k.toString().split("[\\s,]+")) if (!p.isEmpty()) keys.add(p);
                    }
                    if (keys.isEmpty()) return fail("需要 keys，如 [\"ctrl-c\"]、\"up enter\"");
                    StringBuilder sb = new StringBuilder();
                    for (String key : keys) {
                        String s = keySeq(key);
                        if (s.isEmpty()) return fail("无法识别的按键: " + key);
                        sb.append(s);
                    }
                    write(e, sb.toString());
                    long wait = Math.max(0, Math.min(30000, json.optLong("wait", 600)));
                    long deadline = SystemClock.uptimeMillis() + wait;
                    SystemClock.sleep(Math.min(150, wait));
                    waitIdle(e, 300, deadline);
                    JSONObject r = ok();
                    r.put("session", e.id);
                    r.put("screen", clip(screenText(e), maxChars));
                    return r;
                }
                case "read":
                case "screen":
                case "output": {
                    String mode = json.optString("mode", "screen");
                    int lines = json.optInt("lines", 0);
                    long wait = Math.max(0, Math.min(60000, json.optLong("wait", 0)));
                    if (wait > 0) waitIdle(e, 500, SystemClock.uptimeMillis() + wait);
                    String text = "history".equals(mode) || "transcript".equals(mode) || lines > 0
                            ? transcript(e) : screenText(e);
                    if (lines > 0) text = tail(text, lines);
                    JSONObject r = ok();
                    r.put("session", describe(e));
                    r.put("text", clip(text, maxChars));
                    return r;
                }
                case "wait_for": {
                    String pat = json.optString("pattern", json.optString("text", ""));
                    if (TextUtils.isEmpty(pat)) return fail("wait_for 需要 pattern（正则）");
                    long timeout = Math.max(200, Math.min(600000, json.optLong("timeout", 30000)));
                    Pattern p = Pattern.compile(pat, Pattern.MULTILINE);
                    long deadline = SystemClock.uptimeMillis() + timeout;
                    while (SystemClock.uptimeMillis() < deadline) {
                        String s = screenText(e);
                        if (p.matcher(s).find()) {
                            return ok().put("matched", true).put("screen", clip(s, maxChars));
                        }
                        SystemClock.sleep(200);
                    }
                    return ok().put("matched", false).put("screen", clip(screenText(e), maxChars));
                }
                case "rename": {
                    String nm = json.optString("name", "");
                    if (!TextUtils.isEmpty(nm)) e.name = nm;
                    for (Listener l : LISTENERS) l.onSessionsChanged();
                    return ok().put("session", describe(e));
                }
                case "clear": {
                    write(e, "clear\r");
                    MAIN.post(() -> {
                        try {
                            e.session.getEmulator().clearScrollCounter();
                        } catch (Throwable ignored) {
                        }
                    });
                    return ok().put("result", "已清屏");
                }
                default:
                    return fail("未知 terminal 操作: " + op
                            + "（可用 list / create / run / send / keys / read / wait_for / show / hide / rename / clear / close）");
            }
        } catch (Throwable t) {
            Log.e(TAG, "handle error", t);
            return fail("终端操作失败: " + t);
        }
    }

    private static JSONObject describe(Entry e) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", e.id);
        o.put("name", e.name);
        o.put("running", !e.finished);
        if (e.finished) o.put("exit_code", e.exitCode);
        o.put("by_ai", e.byAi);
        try {
            String title = e.session.getTitle();
            if (!TextUtils.isEmpty(title)) o.put("title", title);
            TerminalEmulator em = e.session.getEmulator();
            if (em != null) o.put("size", em.mColumns + "x" + em.mRows);
        } catch (Throwable ignored) {
        }
        o.put("idle_ms", SystemClock.uptimeMillis() - e.lastOutputAt);
        return o;
    }

    private static JSONObject ok() throws Exception {
        return new JSONObject().put("success", true);
    }

    private static JSONObject fail(String msg) {
        try {
            return new JSONObject().put("success", false).put("error", msg);
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
