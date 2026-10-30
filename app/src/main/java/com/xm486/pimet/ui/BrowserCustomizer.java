package com.xm486.pimet.ui;

import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 浏览器自定义配置（appsDir/ui.json）：外观、工具栏、菜单、悬浮球、主页、搜索、站点规则、拦截/重定向、面板。
 *
 * - ui.json 只存用户/Pi 改过的部分；config() = 内置默认值 深度合并 ui.json（对象递归合并，数组和标量整体替换）。未知字段保留。
 * - 每次写入前先校验（validate），有错抛 {@link ValidationException} 不写盘；写盘前把当前 ui.json 备份到 ui.bak/，保留 20 份。
 * - 写入后、以及 ui.json 被外部修改时（FileObserver），在主线程回调 addListener 注册的监听。
 * - 全部方法线程安全；shouldBlock / redirect / siteSettings 读的是编译好的不可变快照，适合在网络线程高频调用。
 */
public final class BrowserCustomizer {

    /** 校验失败：errors 为逐条错误（中文，带 JSON 路径）。 */
    public static final class ValidationException extends Exception {
        private static final long serialVersionUID = 1L;
        public final List<String> errors;

        public ValidationException(List<String> errors) {
            super("配置校验失败：" + String.join("；", errors));
            this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
        }
    }

    private static final String TAG = "BrowserCustomizer";
    private static final int MAX_BACKUPS = 20;
    private static final Pattern COLOR = Pattern.compile("^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$");
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Set<String> ACTION_TYPES = new HashSet<>(Arrays.asList(
            "builtin", "open", "js", "script_menu", "panel", "askpi", "seq"));
    private static final Set<String> WHERE = new HashSet<>(Arrays.asList("menu", "toolbar", "ball", "none"));

    /** 内置 id → {icon,label,where} */
    private static final Map<String, String[]> BUILTINS = new LinkedHashMap<>();
    /** 别名（兼容写法）→ 内置 id */
    private static final Map<String, String> ALIASES = new LinkedHashMap<>();

    static {
        String all = "toolbar,menu,ball,longPress,gestures";
        b("back", "←", "后退", all);
        b("forward", "→", "前进", all);
        b("reload", "⟳", "刷新", all);
        b("stop", "✕", "停止加载", all);
        b("home", "🏠", "主页", all);
        b("url", "🔗", "地址栏", "toolbar");
        b("tabs", "🗂", "标签页", all);
        b("newtab", "＋", "新标签页", all);
        b("menu", "⋮", "菜单", "toolbar,ball,gestures");
        b("history", "🕘", "历史", all);
        b("scripts", "🧩", "脚本管理", all);
        b("cookies", "🍪", "Cookie", all);
        b("containers", "🎨", "容器", all);
        b("desktop", "🖥", "桌面模式", all);
        b("zoom", "🔍", "缩放", all);
        b("find", "🔎", "页内查找", all);
        b("share", "📤", "分享", all);
        b("fullscreen", "⛶", "全屏", all);
        b("translate", "🌐", "翻译", all);
        b("theme", "🌓", "主题", all);
        b("askpi", "π", "问 Pi", all);
        b("terminal", "⌨", "终端", all);
        b("external", "↗", "外部浏览器打开", all);
        b("downloads", "⬇", "下载", all);
        b("settings", "⚙", "自定义", all);
        b("close", "✖", "关闭浏览器", all);
        ALIASES.put("refresh", "reload");
        ALIASES.put("forwards", "forward");
        ALIASES.put("new_tab", "newtab");
        ALIASES.put("ask_pi", "askpi");
        ALIASES.put("customize", "settings");
    }

    private static void b(String id, String icon, String label, String where) {
        BUILTINS.put(id, new String[]{icon, label, where});
    }

    /** 内置 id（含别名）规范化；未知返回 null。 */
    public static String builtinId(String id) {
        if (id == null) return null;
        if (BUILTINS.containsKey(id)) return id;
        return ALIASES.get(id);
    }

    // ------------------------------------------------------------------ 状态

    private final Object lock = new Object();
    private final File appsDir, uiFile, bakDir, panelsDir;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final FileObserver observer;

    private JSONObject raw;             // ui.json 原文（内存）
    private JSONObject merged;          // 默认 + raw
    private String lastWritten;         // 自己最后写入的文本，用来忽略自己触发的 FileObserver
    private long revision;
    private volatile Compiled compiled = Compiled.EMPTY;
    private boolean notifyPending;

    public BrowserCustomizer(File appsDir) {
        this.appsDir = appsDir;
        this.uiFile = new File(appsDir, "ui.json");
        this.bakDir = new File(appsDir, "ui.bak");
        this.panelsDir = new File(appsDir, "panels");
        if (!appsDir.isDirectory()) //noinspection ResultOfMethodCallIgnored
            appsDir.mkdirs();
        synchronized (lock) {
            JSONObject r = readRawFile();
            raw = r == null ? new JSONObject() : r;
            JSONObject m = mergeDefaults(raw);
            if (!validateMerged(m).isEmpty()) {
                // 外部写坏了：仍然加载（未知/错误字段由 UI 端兜底），但把错误打到日志
                Log.w(TAG, "ui.json 校验有错：" + String.join("；", validateMerged(m)));
            }
            merged = m;
            compiled = Compiled.build(m);
        }
        observer = new FileObserver(appsDir.getAbsolutePath(),
                FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO | FileObserver.DELETE) {
            @Override
            public void onEvent(int event, String path) {
                if ("ui.json".equals(path)) onExternalChange();
            }
        };
        try { observer.startWatching(); } catch (Throwable t) { Log.w(TAG, "FileObserver 启动失败", t); }
    }

    /** 每次成功写入（或外部修改被接受）自增，可作 status 里的 ui_version。 */
    public long revision() {
        synchronized (lock) { return revision; }
    }

    // ------------------------------------------------------------------ 读

    /** 合并默认值后的完整配置（副本）。 */
    public JSONObject config() {
        synchronized (lock) { return (JSONObject) deepCopy(merged); }
    }

    /** ui.json 原文（副本）。 */
    public JSONObject raw() {
        synchronized (lock) { return (JSONObject) deepCopy(raw); }
    }

    /** JSON Pointer 取值（基于合并后的配置）；"" 或 "/" 返回整份；不存在返回 null。对象/数组返回副本。 */
    public Object get(String pointer) {
        synchronized (lock) {
            Object cur = merged;
            for (String tok : parsePointer(pointer)) {
                if (cur instanceof JSONObject) {
                    cur = ((JSONObject) cur).opt(tok);
                } else if (cur instanceof JSONArray) {
                    int i = parseIndex(tok);
                    JSONArray a = (JSONArray) cur;
                    cur = i >= 0 && i < a.length() ? a.opt(i) : null;
                } else {
                    return null;
                }
                if (cur == null) return null;
            }
            return deepCopy(cur);
        }
    }

    // ------------------------------------------------------------------ 写

    /** 校验后写盘；数组末尾用 "-" 追加，"/toolbar/2" 为替换第 3 项。value 为 null 表示写入 JSON null。 */
    public void set(String pointer, Object value) throws Exception {
        List<String> toks = parsePointer(pointer);
        Object v = normalizeValue(value);
        synchronized (lock) {
            if (toks.isEmpty()) {
                if (!(v instanceof JSONObject)) throw new IllegalArgumentException("根节点必须是 JSON 对象");
                commitLocked((JSONObject) deepCopy(v));
                return;
            }
            JSONObject nr = (JSONObject) deepCopy(raw);
            Object parent = parentForWrite(nr, toks, true);
            String last = toks.get(toks.size() - 1);
            if (parent instanceof JSONObject) {
                ((JSONObject) parent).put(last, v);
            } else {
                JSONArray a = (JSONArray) parent;
                if ("-".equals(last)) {
                    a.put(v);
                } else {
                    int i = parseIndex(last);
                    if (i < 0 || i > a.length()) throw new IllegalArgumentException("数组下标越界: " + pointer);
                    if (i == a.length()) a.put(v); else a.put(i, v);
                }
            }
            commitLocked(nr);
        }
    }

    /** RFC 7386 合并补丁（作用于 ui.json 原文；null 表示删除该字段 = 恢复默认）。 */
    public void patch(JSONObject mergePatch) throws Exception {
        if (mergePatch == null) return;
        synchronized (lock) {
            Object r = applyMergePatch(deepCopy(raw), mergePatch);
            if (!(r instanceof JSONObject)) throw new IllegalArgumentException("补丁结果必须是 JSON 对象");
            commitLocked((JSONObject) r);
        }
    }

    /** 删除一项。对象里只存在于默认值中的字段无法删除（会回落到默认），请用 set 覆盖。 */
    public void remove(String pointer) throws Exception {
        List<String> toks = parsePointer(pointer);
        if (toks.isEmpty()) throw new IllegalArgumentException("不能删除根节点，整份恢复默认请用 reset");
        synchronized (lock) {
            JSONObject nr = (JSONObject) deepCopy(raw);
            Object parent = parentForWrite(nr, toks, false);
            String last = toks.get(toks.size() - 1);
            if (parent instanceof JSONObject) {
                JSONObject p = (JSONObject) parent;
                if (p.has(last)) {
                    p.remove(last);
                } else if (get(pointer) != null) {
                    throw new IllegalArgumentException("“" + pointer + "”是内置默认项，不能删除；可以用 set 改值，或对数组项删除");
                } else {
                    throw new IllegalArgumentException("不存在: " + pointer);
                }
            } else {
                JSONArray a = (JSONArray) parent;
                int i = parseIndex(last);
                if (i < 0 || i >= a.length()) throw new IllegalArgumentException("数组下标越界: " + pointer);
                a.remove(i);
            }
            commitLocked(nr);
        }
    }

    /** 恢复默认：null / "" / "/" 恢复整份；否则只恢复该项（数组里的某一项恢复为默认数组同位置的值，没有则删除）。 */
    public void reset(String pointerOrNull) {
        try {
            synchronized (lock) {
                List<String> toks = parsePointer(pointerOrNull);
                if (toks.isEmpty()) {
                    JSONObject nr = new JSONObject();
                    nr.put("version", 1);
                    commitLocked(nr);
                    return;
                }
                JSONObject nr = (JSONObject) deepCopy(raw);
                Object parent = parentForWrite(nr, toks, false);
                String last = toks.get(toks.size() - 1);
                if (parent instanceof JSONObject) {
                    ((JSONObject) parent).remove(last);
                } else {
                    JSONArray a = (JSONArray) parent;
                    int i = parseIndex(last);
                    Object def = getFrom(defaults(), toks);
                    if (i >= 0 && i < a.length()) {
                        if (def != null) a.put(i, def); else a.remove(i);
                    }
                }
                pruneEmpty(nr);
                commitLocked(nr);
            }
        } catch (Exception e) {
            Log.w(TAG, "reset 失败: " + pointerOrNull, e);
        }
    }

    /** 返回错误列表（空 = 通过）。cfg 可以是完整配置，也可以是只含改动的部分（会先与默认值合并）。 */
    public List<String> validate(JSONObject cfg) {
        if (cfg == null) return Collections.singletonList("配置为空");
        return validateMerged(mergeDefaults(cfg));
    }

    // ------------------------------------------------------------------ 站点 / 拦截 / 重定向

    /** 合并所有匹配该 URL 的 sites 设置（越具体的规则越后合并、优先级越高）；无匹配返回空对象。 */
    public JSONObject siteSettings(String url) {
        JSONObject out = new JSONObject();
        if (url == null) return out;
        Compiled c = compiled;
        if (c.sites.isEmpty()) return out;
        String host = hostOf(url);
        for (SiteRule r : c.sites) {
            boolean hit = r.urlLevel ? r.re.matcher(url).matches() : (host != null && r.re.matcher(host).matches());
            if (!hit) continue;
            for (Iterator<String> it = r.settings.keys(); it.hasNext(); ) {
                String k = it.next();
                try { out.put(k, deepCopy(r.settings.opt(k))); } catch (JSONException ignored) {}
            }
        }
        return out;
    }

    /** rules.block：支持 ||domain^、|开头/结尾|、* 通配、^ 分隔符、/正则/、@@ 白名单、! 注释、$选项（忽略）。 */
    public boolean shouldBlock(String url) {
        if (url == null) return false;
        Compiled c = compiled;
        if (c.blockDomains.isEmpty() && c.block.isEmpty()) return false;
        if (!url.startsWith("http") && !url.startsWith("ws")) return false;
        for (Rule r : c.allow) if (r.test(url)) return false;
        if (!c.blockDomains.isEmpty()) {
            String h = hostOf(url);
            while (h != null && !h.isEmpty()) {
                if (c.blockDomains.contains(h)) return true;
                int dot = h.indexOf('.');
                if (dot < 0) break;
                h = h.substring(dot + 1);
            }
        }
        for (Rule r : c.block) if (r.test(url)) return true;
        return false;
    }

    /** rules.redirect：Java 正则（find），to 可用 $1..$n；无匹配或结果与原地址相同返回 null。 */
    public String redirect(String url) {
        if (url == null) return null;
        Compiled c = compiled;
        for (Redirect r : c.redirects) {
            try {
                Matcher m = r.from.matcher(url);
                if (!m.find()) continue;
                String out = m.replaceFirst(r.to);
                if (out.equals(url)) continue;
                return out;
            } catch (Throwable ignored) {
                // to 里引用了不存在的分组等
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 面板

    /** 面板 HTML 文件（config.panels 里的 file 优先，限制在 appsDir 内；否则 panels/&lt;id&gt;.html）。id 非法返回 null。 */
    public File panelFile(String id) {
        if (id == null || !ID.matcher(id).matches()) return null;
        synchronized (lock) {
            JSONObject p = findPanel(merged, id);
            if (p != null) {
                String f = p.optString("file", "");
                if (!f.isEmpty()) {
                    try {
                        File file = new File(appsDir, f);
                        String canon = file.getCanonicalPath();
                        if (canon.startsWith(appsDir.getCanonicalPath() + File.separator)) return file;
                    } catch (Exception ignored) {}
                }
            }
        }
        return new File(panelsDir, id + ".html");
    }

    /** 写 panels/&lt;id&gt;.html 并在 config.panels 中新增或更新该项。 */
    public void writePanel(String id, String title, String icon, String html, String where) throws Exception {
        if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("面板 id 只能含字母数字 _ -（1~64 位）");
        String w = where == null || where.isEmpty() ? "menu" : where;
        if (!WHERE.contains(w)) throw new IllegalArgumentException("where 只能是 menu|toolbar|ball|none");
        synchronized (lock) {
            JSONObject nr = (JSONObject) deepCopy(raw);
            JSONArray panels = nr.optJSONArray("panels");
            if (panels == null) {
                Object d = merged.opt("panels");
                panels = d instanceof JSONArray ? (JSONArray) deepCopy(d) : new JSONArray();
                nr.put("panels", panels);
            }
            JSONObject entry = null;
            for (int i = 0; i < panels.length(); i++) {
                JSONObject p = panels.optJSONObject(i);
                if (p != null && id.equals(p.optString("id"))) { entry = p; break; }
            }
            if (entry == null) { entry = new JSONObject(); entry.put("id", id); panels.put(entry); }
            entry.put("title", title == null || title.isEmpty() ? entry.optString("title", id) : title);
            if (icon != null && !icon.isEmpty()) entry.put("icon", icon);
            else if (!entry.has("icon")) entry.put("icon", "📄");
            entry.put("file", "panels/" + id + ".html");
            entry.put("where", w);
            List<String> errs = validateMerged(mergeDefaults(nr));
            if (!errs.isEmpty()) throw new ValidationException(errs);
            if (!panelsDir.isDirectory()) //noinspection ResultOfMethodCallIgnored
                panelsDir.mkdirs();
            writeAtomic(new File(panelsDir, id + ".html"), (html == null ? "" : html).getBytes(StandardCharsets.UTF_8));
            commitLocked(nr);
        }
    }

    /** 删除面板文件和 config.panels 项，并清掉指向它的按钮/菜单项；newTab 指向它时改回 homepage。 */
    public boolean deletePanel(String id) {
        if (id == null || !ID.matcher(id).matches()) return false;
        synchronized (lock) {
            boolean existed = false;
            File f = panelFile(id);
            if (f != null && f.isFile()) existed = f.delete();
            try {
                JSONObject nr = (JSONObject) deepCopy(raw);
                JSONArray panels = nr.optJSONArray("panels");
                if (panels == null && merged.opt("panels") instanceof JSONArray) {
                    panels = (JSONArray) deepCopy(merged.opt("panels"));
                    nr.put("panels", panels);
                }
                if (panels != null) {
                    for (int i = panels.length() - 1; i >= 0; i--) {
                        JSONObject p = panels.optJSONObject(i);
                        if (p != null && id.equals(p.optString("id"))) { panels.remove(i); existed = true; }
                    }
                }
                stripPanelRefs(nr, id);
                commitLocked(nr);
            } catch (Exception e) {
                Log.w(TAG, "deletePanel 失败: " + id, e);
            }
            return existed;
        }
    }

    // ------------------------------------------------------------------ 监听

    /** 写入后、以及 ui.json 被外部修改时在主线程回调。 */
    public void addListener(Runnable onChange) {
        if (onChange != null) listeners.add(onChange);
    }

    public void removeListener(Runnable onChange) {
        listeners.remove(onChange);
    }

    // ------------------------------------------------------------------ 备份

    /**
     * 立即把当前 ui.json 备份一份，返回 {name, t, config, backups:[{name,t,size}]}（backups 新的在前）。
     * 写入前的自动备份不需要调用它。
     */
    public JSONObject backup() {
        synchronized (lock) {
            JSONObject out = new JSONObject();
            try {
                String name = backupLocked(true);
                out.put("name", name == null ? JSONObject.NULL : name);
                out.put("t", System.currentTimeMillis());
                out.put("config", deepCopy(raw));
                out.put("backups", backups());
            } catch (Exception ignored) {}
            return out;
        }
    }

    /** 备份列表 [{name,t,size}]，新的在前。 */
    public JSONArray backups() {
        JSONArray out = new JSONArray();
        File[] fs = bakDir.listFiles((d, n) -> n.endsWith(".json") && !n.startsWith("."));
        if (fs == null) return out;
        Arrays.sort(fs, (a, b) -> b.getName().compareTo(a.getName()));
        for (File f : fs) {
            try {
                out.put(new JSONObject().put("name", f.getName()).put("t", f.lastModified()).put("size", f.length()));
            } catch (JSONException ignored) {}
        }
        return out;
    }

    /**
     * 恢复：snapshot 可以是
     * ① 一份完整的 ui.json 对象；② backup() 的返回值（取其中 config）；③ {"name":"&lt;备份文件名&gt;"}（从 ui.bak/ 读取）。
     * 恢复前同样会先备份当前配置；校验失败抛 ValidationException。
     */
    public void restore(JSONObject snapshot) throws Exception {
        if (snapshot == null) throw new IllegalArgumentException("snapshot 为空");
        JSONObject cfg;
        if (snapshot.length() <= 2 && snapshot.has("name") && !snapshot.has("version") && !snapshot.has("config")) {
            String n = snapshot.optString("name", "");
            if (!n.matches("[A-Za-z0-9_.-]+") || n.contains("..")) throw new IllegalArgumentException("备份名非法: " + n);
            File f = new File(bakDir, n.endsWith(".json") ? n : n + ".json");
            if (!f.isFile()) throw new IllegalArgumentException("备份不存在: " + n);
            cfg = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } else if (snapshot.opt("config") instanceof JSONObject) {
            cfg = snapshot.getJSONObject("config");
        } else {
            cfg = snapshot;
        }
        synchronized (lock) {
            commitLocked((JSONObject) deepCopy(cfg));
        }
    }

    // ------------------------------------------------------------------ 内置清单与说明

    /** [{id,icon,label,where:[toolbar,menu,ball,longPress,gestures]}]，以及 aliases 不列出。 */
    public static JSONArray builtins() {
        JSONArray out = new JSONArray();
        for (Map.Entry<String, String[]> e : BUILTINS.entrySet()) {
            try {
                JSONArray where = new JSONArray();
                for (String w : e.getValue()[2].split(",")) where.put(w);
                out.put(new JSONObject().put("id", e.getKey()).put("icon", e.getValue()[0])
                        .put("label", e.getValue()[1]).put("where", where));
            } catch (JSONException ignored) {}
        }
        return out;
    }

    /** 给 Pi 看的简明说明（纯文本）。 */
    public static String schemaDoc() {
        StringBuilder sb = new StringBuilder();
        sb.append("PiMet 浏览器自定义配置 ui.json（/root/pimet-browser/ui.json）\n");
        sb.append("只需写想改的字段，其余用内置默认值；对象递归合并，数组整体替换；未知字段保留。改完立即生效（热重载，不丢标签）。\n");
        sb.append("每次写入前自动校验，有错不写；并自动备份到 ui.bak/（保留 20 份），改坏了用 ui_restore 回滚。\n\n");
        sb.append("字段：\n");
        sb.append("- version: 1\n");
        sb.append("- theme: mode auto|light|dark；accent 颜色 #RGB/#RRGGBB/#AARRGGBB；toolbarBg/toolbarFg 颜色或 null（跟随主题）；toolbarPosition top|bottom；fontScale 0.5~3；tabStyle bar|list；css 注入所有页面的 CSS\n");
        sb.append("- home: homepage 主页（空串=内置主页，或 URL / about:blank）；newTab homepage|blank|panel:<面板id>|<URL>\n");
        sb.append("- search: default 默认引擎 id；engines [{id,name,url}]，url 用 %s 代表关键词；suggest true|false\n");
        sb.append("- toolbar / menu: 按钮数组，按顺序显示。项 = {id:<内置id>} 或自定义 {id,icon,label,action}；menu 可用 {type:\"divider\"} 分隔线；删掉某项即隐藏\n");
        sb.append("- ball: 悬浮球 enabled、snapEdge（贴边吸附）、items（同上的项数组）、size 24~128、opacity 0.1~1\n");
        sb.append("- longPress: link / image / text 三个数组，长按链接/图片/选中文字时追加的菜单项（项格式同上）\n");
        sb.append("- gestures: swipeLeft / swipeRight / doubleTapToolbar，值是一个 action\n");
        sb.append("- sites: {\"<模式>\": {ua desktop|mobile|<UA字符串>, zoom 25~500, js, images, darkMode, css, container}}。模式：example.com（含子域名）、*.example.com、或带协议/路径的 URL 通配 *://*.example.com/path*；多条命中时越具体越优先\n");
        sb.append("- rules.block: 拦截规则字符串数组（广告拦截语法子集）：||ads.example.com^ 拦截该域及子域；*://*/track* 通配整条 URL；|https://x 开头锚定；/正则/；@@规则 = 白名单；! 开头为注释\n");
        sb.append("- rules.redirect: [{from: Java 正则, to: 替换串（$1 $2 引用分组）}]，导航前改写地址\n");
        sb.append("- panels: [{id,title,icon,file:\"panels/<id>.html\",where menu|toolbar|ball|none}]；面板是 HTML+JS 浮层，可调用 window.PiMetUI.runAction(json) / openUrl(url) / close() / getConfig() / currentPage()\n");
        sb.append("- defaults: container new|Default|<容器名>（新标签用的容器）；desktopMode 默认桌面模式；downloadDir 下载目录\n\n");
        sb.append("action（按钮、手势、面板里用）：\n");
        sb.append("- {type:\"builtin\", id:\"reload\"} 执行内置功能\n");
        sb.append("- {type:\"open\", url:\"...\", newTab:false, container:\"same\"|\"new\"|\"Default\"|<容器名>} 打开网址\n");
        sb.append("- {type:\"js\", code:\"...\"} 在当前页执行 JS\n");
        sb.append("- {type:\"script_menu\", script:\"<脚本id>\", cmd:\"<菜单命令id>\"} 执行用户脚本菜单命令\n");
        sb.append("- {type:\"panel\", id:\"notes\"} 打开面板\n");
        sb.append("- {type:\"askpi\", prompt:\"总结这个页面：{url}\"} 问 Pi，占位符 {url} {title} {selection}\n");
        sb.append("- {type:\"seq\", steps:[action, ...]} 依次执行多个 action\n\n");
        sb.append("内置 id：");
        sb.append(String.join(", ", BUILTINS.keySet()));
        sb.append("（refresh 等同 reload）\n");
        sb.append("JSON Pointer 示例：/theme/accent、/toolbar/2（第 3 项）、/toolbar/- （追加）、/sites/example.com/zoom、/rules/block/-\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------ 默认值

    /** 内置默认配置（每次新建）。 */
    public static JSONObject defaults() {
        try {
            JSONObject d = new JSONObject();
            d.put("version", 1);
            d.put("theme", new JSONObject()
                    .put("mode", "auto").put("accent", "#2196F3")
                    .put("toolbarBg", JSONObject.NULL).put("toolbarFg", JSONObject.NULL)
                    .put("toolbarPosition", "top").put("fontScale", 1.0)
                    .put("tabStyle", "bar").put("css", ""));
            d.put("home", new JSONObject().put("homepage", "").put("newTab", "homepage"));
            d.put("search", new JSONObject()
                    .put("default", "bing")
                    .put("engines", new JSONArray()
                            .put(engine("bing", "必应", "https://www.bing.com/search?q=%s"))
                            .put(engine("google", "Google", "https://www.google.com/search?q=%s"))
                            .put(engine("baidu", "百度", "https://www.baidu.com/s?wd=%s"))
                            .put(engine("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=%s"))
                            .put(engine("sogou", "搜狗", "https://www.sogou.com/web?query=%s")))
                    .put("suggest", true));
            d.put("toolbar", ids("back", "forward", "url", "tabs", "menu"));
            JSONArray menu = ids("newtab", "history", "scripts", "cookies", "containers");
            menu.put(new JSONObject().put("type", "divider"));
            for (String s : new String[]{"desktop", "zoom", "find", "share", "translate", "theme", "downloads"}) menu.put(new JSONObject().put("id", s));
            menu.put(new JSONObject().put("type", "divider"));
            for (String s : new String[]{"askpi", "terminal", "external", "settings"}) menu.put(new JSONObject().put("id", s));
            d.put("menu", menu);
            d.put("ball", new JSONObject().put("enabled", true).put("snapEdge", true)
                    .put("items", ids("fullscreen", "reload", "translate", "askpi"))
                    .put("size", 44).put("opacity", 1.0));
            d.put("longPress", new JSONObject().put("link", new JSONArray()).put("image", new JSONArray()).put("text", new JSONArray()));
            d.put("gestures", new JSONObject());
            d.put("sites", new JSONObject());
            d.put("rules", new JSONObject().put("block", new JSONArray()).put("redirect", new JSONArray()));
            d.put("panels", new JSONArray());
            d.put("defaults", new JSONObject().put("container", "new").put("desktopMode", false)
                    .put("downloadDir", "/sdcard/Download"));
            return d;
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JSONObject engine(String id, String name, String url) throws JSONException {
        return new JSONObject().put("id", id).put("name", name).put("url", url);
    }

    private static JSONArray ids(String... ids) throws JSONException {
        JSONArray a = new JSONArray();
        for (String s : ids) a.put(new JSONObject().put("id", s));
        return a;
    }

    // ------------------------------------------------------------------ 校验

    private List<String> validateMerged(JSONObject c) {
        List<String> e = new ArrayList<>();
        Set<String> panelIds = new HashSet<>();

        // panels（先收集 id，供 action 引用检查）
        Object pv = c.opt("panels");
        if (pv instanceof JSONArray) {
            JSONArray ps = (JSONArray) pv;
            for (int i = 0; i < ps.length(); i++) {
                JSONObject p = ps.optJSONObject(i);
                String path = "/panels/" + i;
                if (p == null) { e.add(path + " 必须是对象"); continue; }
                String id = p.optString("id", "");
                if (!ID.matcher(id).matches()) e.add(path + "/id 只能含字母数字 _ -");
                else if (!panelIds.add(id)) e.add(path + "/id 重复: " + id);
                String w = p.optString("where", "menu");
                if (!WHERE.contains(w)) e.add(path + "/where 只能是 menu|toolbar|ball|none");
                String f = p.optString("file", "");
                if (!f.isEmpty() && (f.startsWith("/") || f.contains(".."))) e.add(path + "/file 必须是 appsDir 下的相对路径");
            }
        } else if (pv != null) e.add("/panels 必须是数组");

        // theme
        JSONObject t = objAt(c, "theme", e);
        if (t != null) {
            oneOf(t, "mode", "/theme/mode", e, "auto", "light", "dark");
            color(t, "accent", "/theme/accent", e, false);
            color(t, "toolbarBg", "/theme/toolbarBg", e, true);
            color(t, "toolbarFg", "/theme/toolbarFg", e, true);
            oneOf(t, "toolbarPosition", "/theme/toolbarPosition", e, "top", "bottom");
            oneOf(t, "tabStyle", "/theme/tabStyle", e, "bar", "list");
            number(t, "fontScale", "/theme/fontScale", e, 0.5, 3.0);
            string(t, "css", "/theme/css", e);
        }

        // home
        JSONObject h = objAt(c, "home", e);
        if (h != null) {
            string(h, "homepage", "/home/homepage", e);
            Object nt = h.opt("newTab");
            if (nt != null && nt != JSONObject.NULL) {
                if (!(nt instanceof String)) e.add("/home/newTab 必须是字符串");
                else {
                    String s = (String) nt;
                    if (s.startsWith("panel:")) {
                        if (!panelIds.contains(s.substring(6))) e.add("/home/newTab 引用了不存在的面板: " + s.substring(6));
                    } else if (!s.equals("homepage") && !s.equals("blank") && !looksLikeUrl(s)) {
                        e.add("/home/newTab 只能是 homepage|blank|panel:<id>|URL");
                    }
                }
            }
        }

        // search
        JSONObject s = objAt(c, "search", e);
        if (s != null) {
            Set<String> engineIds = new HashSet<>();
            Object ev = s.opt("engines");
            if (ev instanceof JSONArray) {
                JSONArray es = (JSONArray) ev;
                for (int i = 0; i < es.length(); i++) {
                    JSONObject en = es.optJSONObject(i);
                    String p = "/search/engines/" + i;
                    if (en == null) { e.add(p + " 必须是对象"); continue; }
                    String id = en.optString("id", "");
                    if (id.isEmpty()) e.add(p + "/id 不能为空");
                    else if (!engineIds.add(id)) e.add(p + "/id 重复: " + id);
                    String url = en.optString("url", "");
                    if (!url.contains("%s")) e.add(p + "/url 必须包含 %s");
                    else if (!looksLikeUrl(url)) e.add(p + "/url 不是有效网址");
                }
            } else if (ev != null) e.add("/search/engines 必须是数组");
            String def = s.optString("default", "");
            if (!def.isEmpty() && !engineIds.contains(def)) e.add("/search/default 引用了不存在的引擎: " + def);
            bool(s, "suggest", "/search/suggest", e);
        }

        // toolbar / menu
        items(c.opt("toolbar"), "/toolbar", e, panelIds, false);
        items(c.opt("menu"), "/menu", e, panelIds, true);

        // ball
        JSONObject b = objAt(c, "ball", e);
        if (b != null) {
            bool(b, "enabled", "/ball/enabled", e);
            bool(b, "snapEdge", "/ball/snapEdge", e);
            number(b, "size", "/ball/size", e, 24, 128);
            number(b, "opacity", "/ball/opacity", e, 0.1, 1.0);
            items(b.opt("items"), "/ball/items", e, panelIds, false);
        }

        // longPress
        JSONObject lp = objAt(c, "longPress", e);
        if (lp != null) {
            for (Iterator<String> it = lp.keys(); it.hasNext(); ) {
                String k = it.next();
                if (!k.equals("link") && !k.equals("image") && !k.equals("text")) e.add("/longPress/" + k + " 未知（只能是 link|image|text）");
                else items(lp.opt(k), "/longPress/" + k, e, panelIds, true);
            }
        }

        // gestures
        JSONObject g = objAt(c, "gestures", e);
        if (g != null) {
            for (Iterator<String> it = g.keys(); it.hasNext(); ) {
                String k = it.next();
                Object a = g.opt(k);
                if (a == JSONObject.NULL) continue;
                if (!k.equals("swipeLeft") && !k.equals("swipeRight") && !k.equals("doubleTapToolbar")) {
                    e.add("/gestures/" + k + " 未知（只能是 swipeLeft|swipeRight|doubleTapToolbar）");
                    continue;
                }
                action(a, "/gestures/" + k, e, panelIds, 0);
            }
        }

        // sites
        JSONObject st = objAt(c, "sites", e);
        if (st != null) {
            for (Iterator<String> it = st.keys(); it.hasNext(); ) {
                String k = it.next();
                String p = "/sites/" + escapePointer(k);
                if (SiteRule.compile(k) == null) e.add(p + " 模式无效");
                JSONObject v = st.optJSONObject(k);
                if (v == null) { e.add(p + " 必须是对象"); continue; }
                Object ua = v.opt("ua");
                if (ua != null && ua != JSONObject.NULL && !(ua instanceof String)) e.add(p + "/ua 必须是字符串");
                number(v, "zoom", p + "/zoom", e, 25, 500);
                bool(v, "js", p + "/js", e);
                bool(v, "images", p + "/images", e);
                bool(v, "darkMode", p + "/darkMode", e);
                bool(v, "desktopMode", p + "/desktopMode", e);
                string(v, "css", p + "/css", e);
                string(v, "container", p + "/container", e);
            }
        }

        // rules
        JSONObject r = objAt(c, "rules", e);
        if (r != null) {
            Object bl = r.opt("block");
            if (bl instanceof JSONArray) {
                JSONArray a = (JSONArray) bl;
                for (int i = 0; i < a.length(); i++) {
                    Object x = a.opt(i);
                    if (!(x instanceof String)) { e.add("/rules/block/" + i + " 必须是字符串"); continue; }
                    String err = Rule.check((String) x);
                    if (err != null) e.add("/rules/block/" + i + " " + err);
                }
            } else if (bl != null && bl != JSONObject.NULL) e.add("/rules/block 必须是数组");
            Object rd = r.opt("redirect");
            if (rd instanceof JSONArray) {
                JSONArray a = (JSONArray) rd;
                for (int i = 0; i < a.length(); i++) {
                    JSONObject x = a.optJSONObject(i);
                    String p = "/rules/redirect/" + i;
                    if (x == null) { e.add(p + " 必须是 {from,to}"); continue; }
                    String from = x.optString("from", "");
                    if (from.isEmpty()) e.add(p + "/from 不能为空");
                    else {
                        try {
                            Pattern pat = Pattern.compile(from);
                            int groups = pat.matcher("").groupCount();
                            Matcher gm = Pattern.compile("\\$(\\d)").matcher(x.optString("to", ""));
                            while (gm.find()) {
                                if (Integer.parseInt(gm.group(1)) > groups) e.add(p + "/to 引用了不存在的分组 $" + gm.group(1));
                            }
                        } catch (Exception ex) {
                            e.add(p + "/from 正则编译失败: " + ex.getMessage().split("\\n")[0]);
                        }
                    }
                    if (!(x.opt("to") instanceof String) || x.optString("to").isEmpty()) e.add(p + "/to 不能为空");
                }
            } else if (rd != null && rd != JSONObject.NULL) e.add("/rules/redirect 必须是数组");
        }

        // defaults
        JSONObject df = objAt(c, "defaults", e);
        if (df != null) {
            string(df, "container", "/defaults/container", e);
            bool(df, "desktopMode", "/defaults/desktopMode", e);
            string(df, "downloadDir", "/defaults/downloadDir", e);
        }
        return e;
    }

    private void items(Object v, String path, List<String> e, Set<String> panelIds, boolean dividerOk) {
        if (v == null || v == JSONObject.NULL) return;
        if (!(v instanceof JSONArray)) { e.add(path + " 必须是数组"); return; }
        JSONArray a = (JSONArray) v;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < a.length(); i++) {
            String p = path + "/" + i;
            Object o = a.opt(i);
            if (!(o instanceof JSONObject)) { e.add(p + " 必须是对象，如 {\"id\":\"reload\"}"); continue; }
            JSONObject it = (JSONObject) o;
            if ("divider".equals(it.optString("type"))) {
                if (!dividerOk) e.add(p + " 这里不支持分隔线");
                continue;
            }
            String id = it.optString("id", "");
            if (it.has("action")) {
                if (it.optString("label", "").isEmpty() && it.optString("icon", "").isEmpty()) e.add(p + " 自定义项需要 label 或 icon");
                action(it.opt("action"), p + "/action", e, panelIds, 0);
            } else if (id.isEmpty()) {
                e.add(p + " 缺少 id（内置项）或 action（自定义项）");
            } else if (builtinId(id) == null) {
                e.add(p + "/id 未知内置 id: " + id + "（自定义项请加 action）");
            }
            if (!id.isEmpty() && !seen.add(id)) e.add(p + "/id 重复: " + id);
        }
    }

    private void action(Object v, String path, List<String> e, Set<String> panelIds, int depth) {
        if (!(v instanceof JSONObject)) { e.add(path + " action 必须是对象"); return; }
        if (depth > 5) { e.add(path + " seq 嵌套过深"); return; }
        JSONObject a = (JSONObject) v;
        String type = a.optString("type", "");
        if (!ACTION_TYPES.contains(type)) { e.add(path + "/type 未知: " + type + "（builtin|open|js|script_menu|panel|askpi|seq）"); return; }
        switch (type) {
            case "builtin":
                if (builtinId(a.optString("id", "")) == null) e.add(path + "/id 未知内置 id: " + a.optString("id", ""));
                break;
            case "open":
                if (a.optString("url", "").isEmpty()) e.add(path + "/url 不能为空");
                bool(a, "newTab", path + "/newTab", e);
                string(a, "container", path + "/container", e);
                break;
            case "js":
                if (a.optString("code", "").isEmpty()) e.add(path + "/code 不能为空");
                break;
            case "script_menu":
                if (a.optString("script", "").isEmpty()) e.add(path + "/script 不能为空");
                if (a.optString("cmd", "").isEmpty()) e.add(path + "/cmd 不能为空");
                break;
            case "panel": {
                String id = a.optString("id", "");
                if (id.isEmpty()) e.add(path + "/id 不能为空");
                else if (!panelIds.contains(id)) e.add(path + "/id 引用了不存在的面板: " + id);
                break;
            }
            case "askpi":
                string(a, "prompt", path + "/prompt", e);
                break;
            case "seq": {
                JSONArray steps = a.optJSONArray("steps");
                if (steps == null || steps.length() == 0) { e.add(path + "/steps 必须是非空数组"); break; }
                for (int i = 0; i < steps.length(); i++) action(steps.opt(i), path + "/steps/" + i, e, panelIds, depth + 1);
                break;
            }
            default:
        }
    }

    private static JSONObject objAt(JSONObject c, String key, List<String> e) {
        Object v = c.opt(key);
        if (v == null || v == JSONObject.NULL) return null;
        if (!(v instanceof JSONObject)) { e.add("/" + key + " 必须是对象"); return null; }
        return (JSONObject) v;
    }

    private static void oneOf(JSONObject o, String k, String p, List<String> e, String... vals) {
        Object v = o.opt(k);
        if (v == null || v == JSONObject.NULL) return;
        if (!Arrays.asList(vals).contains(String.valueOf(v))) e.add(p + " 只能是 " + String.join("|", vals));
    }

    private static void color(JSONObject o, String k, String p, List<String> e, boolean nullable) {
        Object v = o.opt(k);
        if (v == null) return;
        if (v == JSONObject.NULL) { if (!nullable) e.add(p + " 不能为 null"); return; }
        if (!(v instanceof String) || !COLOR.matcher((String) v).matches()) e.add(p + " 颜色格式应为 #RGB/#RRGGBB/#AARRGGBB");
    }

    private static void number(JSONObject o, String k, String p, List<String> e, double min, double max) {
        Object v = o.opt(k);
        if (v == null || v == JSONObject.NULL) return;
        if (!(v instanceof Number)) { e.add(p + " 必须是数字"); return; }
        double d = ((Number) v).doubleValue();
        if (d < min || d > max || Double.isNaN(d)) e.add(p + " 超出范围 " + trimNum(min) + "~" + trimNum(max));
    }

    private static String trimNum(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static void bool(JSONObject o, String k, String p, List<String> e) {
        Object v = o.opt(k);
        if (v == null || v == JSONObject.NULL) return;
        if (!(v instanceof Boolean)) e.add(p + " 必须是 true/false");
    }

    private static void string(JSONObject o, String k, String p, List<String> e) {
        Object v = o.opt(k);
        if (v == null || v == JSONObject.NULL) return;
        if (!(v instanceof String)) e.add(p + " 必须是字符串");
    }

    private static boolean looksLikeUrl(String s) {
        return s.matches("(?i)^(https?|file|about|data|javascript):.*") || s.matches("^[\\w.-]+\\.[a-zA-Z]{2,}(/.*)?$");
    }

    // ------------------------------------------------------------------ 提交 / 文件

    private void commitLocked(JSONObject nr) throws Exception {
        if (!nr.has("version")) nr.put("version", 1);
        JSONObject m = mergeDefaults(nr);
        List<String> errs = validateMerged(m);
        if (!errs.isEmpty()) throw new ValidationException(errs);
        backupLocked(false);
        String text = nr.toString(2);
        writeAtomic(uiFile, text.getBytes(StandardCharsets.UTF_8));
        lastWritten = text;
        raw = nr;
        merged = m;
        compiled = Compiled.build(m);
        revision++;
        notifyListeners();
    }

    /** 备份当前 ui.json 到 ui.bak/；文件不存在且 force==false 时不备份。返回备份文件名。 */
    private String backupLocked(boolean force) {
        try {
            byte[] data;
            if (uiFile.isFile()) data = Files.readAllBytes(uiFile.toPath());
            else if (force) data = raw.toString(2).getBytes(StandardCharsets.UTF_8);
            else return null;
            if (!bakDir.isDirectory()) //noinspection ResultOfMethodCallIgnored
                bakDir.mkdirs();
            String base = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(new Date());
            File f = new File(bakDir, base + ".json");
            int n = 1;
            while (f.exists()) f = new File(bakDir, base + "-" + (n++) + ".json");
            writeAtomic(f, data);
            File[] fs = bakDir.listFiles((d, nm) -> nm.endsWith(".json") && !nm.startsWith("."));
            if (fs != null && fs.length > MAX_BACKUPS) {
                Arrays.sort(fs, (a, b) -> a.getName().compareTo(b.getName()));
                for (int i = 0; i < fs.length - MAX_BACKUPS; i++) //noinspection ResultOfMethodCallIgnored
                    fs[i].delete();
            }
            return f.getName();
        } catch (Throwable t) {
            Log.w(TAG, "备份 ui.json 失败", t);
            return null;
        }
    }

    private JSONObject readRawFile() {
        try {
            if (!uiFile.isFile()) return null;
            String s = new String(Files.readAllBytes(uiFile.toPath()), StandardCharsets.UTF_8).trim();
            if (s.isEmpty()) return new JSONObject();
            return new JSONObject(s);
        } catch (Throwable t) {
            Log.w(TAG, "读取 ui.json 失败", t);
            return null;
        }
    }

    /** FileObserver 线程：外部修改 ui.json。JSON 坏了或校验不过则保留内存中的上一份，不覆盖文件。 */
    private void onExternalChange() {
        synchronized (lock) {
            String text;
            try {
                text = uiFile.isFile() ? new String(Files.readAllBytes(uiFile.toPath()), StandardCharsets.UTF_8) : null;
            } catch (Throwable t) {
                return;
            }
            if (text != null && text.equals(lastWritten)) return;   // 自己写的
            JSONObject nr;
            try {
                nr = text == null || text.trim().isEmpty() ? new JSONObject() : new JSONObject(text);
            } catch (Throwable t) {
                Log.w(TAG, "外部修改的 ui.json 不是有效 JSON，忽略");
                return;
            }
            JSONObject m = mergeDefaults(nr);
            List<String> errs = validateMerged(m);
            if (!errs.isEmpty()) {
                Log.w(TAG, "外部修改的 ui.json 校验失败，忽略：" + String.join("；", errs));
                return;
            }
            if (nr.toString().equals(raw.toString())) return;
            lastWritten = text;
            raw = nr;
            merged = m;
            compiled = Compiled.build(m);
            revision++;
            notifyListeners();
        }
    }

    /** 持有 lock：合并 100ms 内的多次变化，主线程回调一次。 */
    private void notifyListeners() {
        if (notifyPending) return;
        notifyPending = true;
        main.postDelayed(() -> {
            synchronized (lock) { notifyPending = false; }
            for (Runnable r : listeners) {
                try { r.run(); } catch (Throwable t) { Log.w(TAG, "listener 出错", t); }
            }
        }, 100);
    }

    private static void writeAtomic(File target, byte[] data) throws Exception {
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        File tmp = new File(dir, "." + target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.getFD().sync();
        }
        if (!tmp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            if (!tmp.renameTo(target)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                throw new Exception("写入失败: " + target.getName());
            }
        }
    }

    // ------------------------------------------------------------------ JSON 工具

    private static JSONObject mergeDefaults(JSONObject r) {
        JSONObject d = defaults();
        deepMergeInto(d, r);
        return d;
    }

    /** 把 src 深度合并进 dst：对象递归，其余（数组、标量、null）整体替换。 */
    private static void deepMergeInto(JSONObject dst, JSONObject src) {
        for (Iterator<String> it = src.keys(); it.hasNext(); ) {
            String k = it.next();
            Object sv = src.opt(k);
            Object dv = dst.opt(k);
            try {
                if (sv instanceof JSONObject && dv instanceof JSONObject) deepMergeInto((JSONObject) dv, (JSONObject) sv);
                else dst.put(k, deepCopy(sv));
            } catch (JSONException ignored) {}
        }
    }

    /** RFC 7386。 */
    private static Object applyMergePatch(Object target, Object patch) throws JSONException {
        if (!(patch instanceof JSONObject)) return deepCopy(patch);
        JSONObject t = target instanceof JSONObject ? (JSONObject) target : new JSONObject();
        JSONObject p = (JSONObject) patch;
        for (Iterator<String> it = p.keys(); it.hasNext(); ) {
            String k = it.next();
            Object v = p.opt(k);
            if (v == JSONObject.NULL) t.remove(k);
            else t.put(k, applyMergePatch(t.opt(k), v));
        }
        return t;
    }

    static Object deepCopy(Object v) {
        try {
            if (v instanceof JSONObject) return new JSONObject(v.toString());
            if (v instanceof JSONArray) return new JSONArray(v.toString());
        } catch (JSONException ignored) {}
        return v;
    }

    /** 外部传入的值统一成 org.json 类型（Map/List/String JSON 文本保持为字符串）。 */
    private static Object normalizeValue(Object v) {
        if (v == null) return JSONObject.NULL;
        if (v instanceof JSONObject || v instanceof JSONArray) return deepCopy(v);
        if (v instanceof Map) return new JSONObject((Map<?, ?>) v);
        if (v instanceof java.util.Collection) return new JSONArray((java.util.Collection<?>) v);
        return v;
    }

    private static List<String> parsePointer(String pointer) {
        List<String> out = new ArrayList<>();
        if (pointer == null || pointer.isEmpty() || pointer.equals("/")) return out;
        String p = pointer.startsWith("/") ? pointer.substring(1) : pointer;
        for (String t : p.split("/", -1)) out.add(t.replace("~1", "/").replace("~0", "~"));
        return out;
    }

    private static String escapePointer(String k) {
        return k.replace("~", "~0").replace("/", "~1");
    }

    private static int parseIndex(String tok) {
        if (tok == null || !tok.matches("\\d{1,9}")) return -1;
        return Integer.parseInt(tok);
    }

    private static Object getFrom(Object root, List<String> toks) {
        Object cur = root;
        for (String tok : toks) {
            if (cur instanceof JSONObject) cur = ((JSONObject) cur).opt(tok);
            else if (cur instanceof JSONArray) {
                int i = parseIndex(tok);
                cur = i >= 0 && i < ((JSONArray) cur).length() ? ((JSONArray) cur).opt(i) : null;
            } else return null;
            if (cur == null) return null;
        }
        return deepCopy(cur);
    }

    /**
     * 在 ui.json 副本 nr 上找到最后一级的父容器；中间缺失的层级按合并后配置补齐
     * （默认值是数组时整份复制进来，是对象时建空对象，靠合并保留其余默认值）。
     */
    private Object parentForWrite(JSONObject nr, List<String> toks, boolean create) throws Exception {
        Object cur = nr;
        Object mcur = merged;
        for (int i = 0; i < toks.size() - 1; i++) {
            String tok = toks.get(i);
            String next = toks.get(i + 1);
            Object child, mchild;
            if (cur instanceof JSONObject) {
                JSONObject o = (JSONObject) cur;
                child = o.opt(tok);
                mchild = mcur instanceof JSONObject ? ((JSONObject) mcur).opt(tok) : null;
                if (child == null || child == JSONObject.NULL) {
                    if (mchild instanceof JSONArray) child = deepCopy(mchild);
                    else if (mchild instanceof JSONObject) child = new JSONObject();
                    else if (create) child = ("-".equals(next) || parseIndex(next) == 0) ? new JSONArray() : new JSONObject();
                    else throw new IllegalArgumentException("路径不存在: /" + String.join("/", toks.subList(0, i + 1)));
                    o.put(tok, child);
                }
            } else if (cur instanceof JSONArray) {
                JSONArray a = (JSONArray) cur;
                int idx = parseIndex(tok);
                if (idx < 0 || idx >= a.length()) throw new IllegalArgumentException("数组下标越界: /" + String.join("/", toks.subList(0, i + 1)));
                child = a.opt(idx);
                mchild = mcur instanceof JSONArray && idx < ((JSONArray) mcur).length() ? ((JSONArray) mcur).opt(idx) : null;
            } else {
                throw new IllegalArgumentException("路径中间不是对象/数组: /" + String.join("/", toks.subList(0, i)));
            }
            if (!(child instanceof JSONObject) && !(child instanceof JSONArray)) {
                throw new IllegalArgumentException("路径中间不是对象/数组: /" + String.join("/", toks.subList(0, i + 1)));
            }
            cur = child;
            mcur = mchild;
        }
        return cur;
    }

    /** reset 后去掉变空的对象（数组保留：空数组是有意义的覆盖）。 */
    private static void pruneEmpty(JSONObject o) {
        List<String> drop = new ArrayList<>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            Object v = o.opt(k);
            if (v instanceof JSONObject) {
                pruneEmpty((JSONObject) v);
                if (((JSONObject) v).length() == 0 && !k.equals("sites") && !k.equals("gestures")) drop.add(k);
            }
        }
        for (String k : drop) o.remove(k);
    }

    private static JSONObject findPanel(JSONObject cfg, String id) {
        JSONArray ps = cfg.optJSONArray("panels");
        if (ps == null) return null;
        for (int i = 0; i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p != null && id.equals(p.optString("id"))) return p;
        }
        return null;
    }

    /** 删除面板时清理引用（在 nr 上操作，缺失的数组按合并配置补齐后再改）。 */
    private void stripPanelRefs(JSONObject nr, String id) throws JSONException {
        String[][] lists = {{"toolbar"}, {"menu"}, {"ball", "items"}, {"longPress", "link"}, {"longPress", "image"}, {"longPress", "text"}};
        for (String[] path : lists) {
            Object cur = getFrom(merged, Arrays.asList(path));
            if (!(cur instanceof JSONArray)) continue;
            JSONArray a = (JSONArray) cur;
            boolean changed = false;
            for (int i = a.length() - 1; i >= 0; i--) {
                JSONObject it = a.optJSONObject(i);
                if (it != null && refsPanel(it.opt("action"), id)) { a.remove(i); changed = true; }
            }
            if (!changed) continue;
            JSONObject parent = nr;
            for (int i = 0; i < path.length - 1; i++) {
                JSONObject next = parent.optJSONObject(path[i]);
                if (next == null) { next = new JSONObject(); parent.put(path[i], next); }
                parent = next;
            }
            parent.put(path[path.length - 1], a);
        }
        JSONObject g = merged.optJSONObject("gestures");
        if (g != null) {
            for (Iterator<String> it = g.keys(); it.hasNext(); ) {
                String k = it.next();
                if (refsPanel(g.opt(k), id)) {
                    JSONObject ng = nr.optJSONObject("gestures");
                    if (ng == null) { ng = new JSONObject(); nr.put("gestures", ng); }
                    ng.put(k, JSONObject.NULL);
                }
            }
        }
        if (("panel:" + id).equals(get("/home/newTab"))) {
            JSONObject home = nr.optJSONObject("home");
            if (home == null) { home = new JSONObject(); nr.put("home", home); }
            home.put("newTab", "homepage");
        }
    }

    private static boolean refsPanel(Object action, String id) {
        if (!(action instanceof JSONObject)) return false;
        JSONObject a = (JSONObject) action;
        if ("panel".equals(a.optString("type")) && id.equals(a.optString("id"))) return true;
        if ("seq".equals(a.optString("type"))) {
            JSONArray s = a.optJSONArray("steps");
            if (s != null) for (int i = 0; i < s.length(); i++) if (refsPanel(s.opt(i), id)) return true;
        }
        return false;
    }

    /** 小写主机名（去掉用户信息和端口）；解析失败返回 null。 */
    static String hostOf(String url) {
        int s = url.indexOf("://");
        if (s < 0) return null;
        int start = s + 3;
        int end = url.length();
        for (int i = start; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') { end = i; break; }
        }
        String h = url.substring(start, end);
        int at = h.lastIndexOf('@');
        if (at >= 0) h = h.substring(at + 1);
        if (h.startsWith("[")) {
            int rb = h.indexOf(']');
            h = rb > 0 ? h.substring(0, rb + 1) : h;
        } else {
            int colon = h.indexOf(':');
            if (colon >= 0) h = h.substring(0, colon);
        }
        return h.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ 编译后的规则快照（不可变）

    private static final class Compiled {
        static final Compiled EMPTY = new Compiled();
        final Set<String> blockDomains = new HashSet<>();
        final List<Rule> block = new ArrayList<>();
        final List<Rule> allow = new ArrayList<>();
        final List<Redirect> redirects = new ArrayList<>();
        final List<SiteRule> sites = new ArrayList<>();

        static Compiled build(JSONObject cfg) {
            Compiled c = new Compiled();
            JSONObject rules = cfg.optJSONObject("rules");
            if (rules != null) {
                JSONArray bl = rules.optJSONArray("block");
                if (bl != null) {
                    for (int i = 0; i < bl.length(); i++) {
                        String s = bl.optString(i, "").trim();
                        if (s.isEmpty() || s.startsWith("!")) continue;
                        boolean allow = s.startsWith("@@");
                        if (allow) s = s.substring(2);
                        String dom = Rule.pureDomain(s);
                        if (dom != null && !allow) { c.blockDomains.add(dom); continue; }
                        Rule r = Rule.compile(s);
                        if (r != null) (allow ? c.allow : c.block).add(r);
                    }
                }
                JSONArray rd = rules.optJSONArray("redirect");
                if (rd != null) {
                    for (int i = 0; i < rd.length(); i++) {
                        JSONObject x = rd.optJSONObject(i);
                        if (x == null) continue;
                        try {
                            String to = x.optString("to", "");
                            if (to.isEmpty()) continue;
                            c.redirects.add(new Redirect(Pattern.compile(x.optString("from", "")), to));
                        } catch (Exception ignored) {}
                    }
                }
            }
            JSONObject sites = cfg.optJSONObject("sites");
            if (sites != null) {
                for (Iterator<String> it = sites.keys(); it.hasNext(); ) {
                    String k = it.next();
                    JSONObject v = sites.optJSONObject(k);
                    SiteRule r = SiteRule.compile(k);
                    if (r == null || v == null) continue;
                    r.settings = v;
                    c.sites.add(r);
                }
                Collections.sort(c.sites, (a, b) -> Integer.compare(a.specificity, b.specificity));
            }
            return c;
        }
    }

    private static final class Redirect {
        final Pattern from;
        final String to;

        Redirect(Pattern from, String to) { this.from = from; this.to = to; }
    }

    /** 一条拦截规则（广告拦截语法子集）。 */
    private static final class Rule {
        final Pattern re;
        final boolean full;   // true = matches 整条 URL；false = find

        Rule(Pattern re, boolean full) { this.re = re; this.full = full; }

        boolean test(String url) {
            return full ? re.matcher(url).matches() : re.matcher(url).find();
        }

        /** "||example.com^" / "||example.com" → example.com；否则 null。 */
        static String pureDomain(String s) {
            String t = stripOptions(s);
            if (!t.startsWith("||")) return null;
            t = t.substring(2);
            if (t.endsWith("^")) t = t.substring(0, t.length() - 1);
            if (t.endsWith("/")) t = t.substring(0, t.length() - 1);
            return t.matches("[A-Za-z0-9.-]+") && t.contains(".") ? t.toLowerCase(Locale.ROOT) : null;
        }

        /** 返回错误说明，或 null 表示规则可用。 */
        static String check(String s) {
            String t = s.trim();
            if (t.isEmpty() || t.startsWith("!")) return null;
            if (t.startsWith("@@")) t = t.substring(2);
            if (pureDomain(t) != null) return null;
            if (t.replace("*", "").replace("|", "").replace("^", "").trim().isEmpty()) return "规则过于宽泛（会拦截所有请求）";
            try {
                return compile(t) == null ? "规则无效" : null;
            } catch (Exception e) {
                return "规则无效: " + e.getMessage();
            }
        }

        static String stripOptions(String s) {
            int d = s.lastIndexOf('$');
            if (d > 0 && !(s.startsWith("/") && s.endsWith("/")) && s.substring(d + 1).matches("[\\w,=~.|-]*")) return s.substring(0, d);
            return s;
        }

        static Rule compile(String s) {
            String t = s.trim();
            if (t.length() > 2 && t.startsWith("/") && t.endsWith("/")) {
                try { return new Rule(Pattern.compile(t.substring(1, t.length() - 1), Pattern.CASE_INSENSITIVE), false); }
                catch (Exception e) { return null; }
            }
            t = stripOptions(t);
            if (t.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            boolean full = false;
            if (t.startsWith("||")) {
                sb.append("^[a-zA-Z][a-zA-Z0-9+.-]*://(?:[^/?#@]*@)?(?:[^/?#]*\\.)?");
                t = t.substring(2);
            } else if (t.startsWith("|")) {
                sb.append("^");
                t = t.substring(1);
            } else if (t.startsWith("*") || t.contains("://")) {
                full = true;
            }
            boolean endAnchor = t.endsWith("|");
            if (endAnchor) t = t.substring(0, t.length() - 1);
            for (char c : t.toCharArray()) {
                if (c == '*') sb.append(".*");
                else if (c == '^') sb.append("(?:[^A-Za-z0-9_.%-]|$)");
                else if ("\\.[]{}()+?$|".indexOf(c) >= 0) sb.append('\\').append(c);
                else sb.append(c);
            }
            if (endAnchor) sb.append("$");
            try {
                return new Rule(Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL), full);
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** sites 的一个模式：主机通配（example.com 含子域名、*.example.com、a*.b.com）或带协议/路径的 URL 通配。 */
    private static final class SiteRule {
        Pattern re;
        boolean urlLevel;
        int specificity;
        JSONObject settings;

        static SiteRule compile(String pattern) {
            String p = pattern == null ? "" : pattern.trim();
            if (p.isEmpty()) return null;
            SiteRule r = new SiteRule();
            r.specificity = p.replace("*", "").length();
            try {
                if (p.equals("*") || p.equals("<all_urls>")) {
                    r.re = Pattern.compile("^.*$");
                    r.specificity = 0;
                } else if (p.contains("://") || p.contains("/")) {
                    r.urlLevel = true;
                    StringBuilder sb = new StringBuilder("^");
                    for (char c : p.toCharArray()) {
                        if (c == '*') sb.append(".*");
                        else if ("\\.[]{}()+?^$|".indexOf(c) >= 0) sb.append('\\').append(c);
                        else sb.append(c);
                    }
                    if (!p.contains("://")) sb.insert(1, "[a-zA-Z][a-zA-Z0-9+.-]*://(?:[^/?#]*\\.)?");
                    r.re = Pattern.compile(sb.append("$").toString(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
                } else {
                    String h = p.toLowerCase(Locale.ROOT);
                    StringBuilder sb = new StringBuilder("^");
                    if (h.startsWith("*.")) { sb.append("(?:.*\\.)?"); h = h.substring(2); }
                    else if (!h.contains("*")) sb.append("(?:.*\\.)?");   // 纯域名含子域名
                    for (char c : h.toCharArray()) {
                        if (c == '*') sb.append("[^/]*");
                        else if ("\\.[]{}()+?^$|".indexOf(c) >= 0) sb.append('\\').append(c);
                        else sb.append(c);
                    }
                    r.re = Pattern.compile(sb.append("$").toString());
                }
                return r;
            } catch (Exception e) {
                return null;
            }
        }
    }
}
