package com.xm486.pimet.ui;

import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 油猴（Tampermonkey）式用户脚本管理：纯逻辑，不碰 UI。
 *
 * 数据存放（appsDir = /root/pimet-browser/）：
 *   scripts/&lt;id&gt;.user.js   脚本源码（兼容旧的 scripts/*.js，id = 文件名去掉 .js）
 *   scripts.json             {"&lt;id&gt;": {"enabled", "installUrl", "installedAt", "updatedAt"}}，无记录默认启用
 *   script-data/&lt;id&gt;.json  GM_setValue 存的值
 *   script-cache/&lt;sha1(url)&gt; @require / @resource 下载缓存
 *
 * 全部公开方法线程安全；installFromUrl / checkUpdate 走网络，只能在后台线程调用。
 * buildInjection 只读缓存；缓存缺失时在后台线程补下载，下次注入生效。
 */
public final class UserScriptManager {

    public static final class Script {
        public String id, name, namespace, version, description, author, icon, runAt;
        public List<String> matches, includes, excludes, grants, requires, connects;
        public Map<String, String> resources;
        public boolean noframes, enabled;
        public String installUrl, updateUrl, downloadUrl, code;
        public long mtime;

        Script() {
            matches = new ArrayList<>();
            includes = new ArrayList<>();
            excludes = new ArrayList<>();
            grants = new ArrayList<>();
            requires = new ArrayList<>();
            connects = new ArrayList<>();
            resources = new LinkedHashMap<>();
            runAt = RUN_IDLE;
            enabled = true;
        }

        Script copy() {
            Script c = new Script();
            c.id = id; c.name = name; c.namespace = namespace; c.version = version;
            c.description = description; c.author = author; c.icon = icon; c.runAt = runAt;
            c.matches = new ArrayList<>(matches); c.includes = new ArrayList<>(includes);
            c.excludes = new ArrayList<>(excludes); c.grants = new ArrayList<>(grants);
            c.requires = new ArrayList<>(requires); c.connects = new ArrayList<>(connects);
            c.resources = new LinkedHashMap<>(resources);
            c.noframes = noframes; c.enabled = enabled;
            c.installUrl = installUrl; c.updateUrl = updateUrl; c.downloadUrl = downloadUrl;
            c.code = code; c.mtime = mtime;
            return c;
        }
    }

    public static final String RUN_START = "document-start";
    public static final String RUN_END = "document-end";
    public static final String RUN_IDLE = "document-idle";

    private static final Pattern ID_OK = Pattern.compile("[A-Za-z0-9_-]+");
    private static final int MAX_DOWNLOAD = 10 * 1024 * 1024;
    private static final int MAX_RESOURCE_EMBED = 2 * 1024 * 1024;

    private final Object lock = new Object();
    private final File appsDir, scriptsDir, stateFile, dataDir, cacheDir;

    /** 解析缓存：文件名 → (mtime, 解析结果) */
    private final Map<String, Script> parsed = new HashMap<>();
    /** 正则缓存：规则串 → Pattern（null 表示无效规则） */
    private final Map<String, Pattern> patternCache = new HashMap<>();
    /** 正在后台补下载的依赖 URL */
    private final java.util.Set<String> fetching = Collections.synchronizedSet(new java.util.HashSet<>());

    public UserScriptManager(File appsDir) {
        this.appsDir = appsDir;
        this.scriptsDir = new File(appsDir, "scripts");
        this.stateFile = new File(appsDir, "scripts.json");
        this.dataDir = new File(appsDir, "script-data");
        this.cacheDir = new File(appsDir, "script-cache");
    }

    // ------------------------------------------------------------------ 列表 / 查询

    /** 全部脚本（按 name 排序），带 enabled。 */
    public List<Script> list() {
        synchronized (lock) {
            List<Script> out = new ArrayList<>();
            JSONObject state = readState();
            for (Map.Entry<String, File> e : scanFiles().entrySet()) {
                Script s = load(e.getKey(), e.getValue(), state);
                if (s != null) out.add(s);
            }
            Collections.sort(out, (a, b) -> {
                int c = a.name.compareToIgnoreCase(b.name);
                return c != 0 ? c : a.id.compareTo(b.id);
            });
            return out;
        }
    }

    public Script get(String id) {
        if (id == null) return null;
        synchronized (lock) {
            File f = scanFiles().get(id);
            return f == null ? null : load(id, f, readState());
        }
    }

    /** 启用且匹配该 URL、runAt 的脚本；runAt == null 表示全部。 */
    public List<Script> matching(String url, String runAt) {
        List<Script> out = new ArrayList<>();
        if (url == null || url.isEmpty()) return out;
        String want = runAt == null ? null : normalizeRunAt(runAt);
        for (Script s : list()) {
            if (!s.enabled || s.code == null) continue;
            if (want != null && !want.equals(s.runAt)) continue;
            if (urlMatches(s, url)) out.add(s);
        }
        return out;
    }

    /** 脚本是否作用于该 URL（不看 enabled）。 */
    public boolean urlMatches(Script s, String url) {
        if (s == null || url == null) return false;
        int hash = url.indexOf('#');
        String u = hash >= 0 ? url.substring(0, hash) : url;
        synchronized (lock) {
            for (String ex : s.excludes) if (globMatches(ex, u)) return false;
            for (String m : s.matches) if (matchPatternMatches(m, u)) return true;
            for (String in : s.includes) if (globMatches(in, u)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ 解析

    /** 解析 ==UserScript== 头；没有头时兼容旧格式（全文的 // @match/@include/@exclude 行）。name 缺省为 null，由调用方用 id 补。 */
    public static Script parse(String code) {
        Script s = new Script();
        s.code = code == null ? "" : code;
        String[] lines = s.code.split("\\r?\\n|\\r", -1);
        int start = -1, end = -1;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (start < 0 && t.matches("//\\s*==UserScript==\\s*")) start = i;
            else if (start >= 0 && t.matches("//\\s*==/UserScript==\\s*")) { end = i; break; }
        }
        Pattern meta = Pattern.compile("^\\s*//\\s*@([A-Za-z][\\w:.-]*)(?:\\s+(.*?))?\\s*$");
        String locName = null, locDesc = null;
        if (start >= 0 && end > start) {
            for (int i = start + 1; i < end; i++) {
                Matcher m = meta.matcher(lines[i]);
                if (!m.matches()) continue;
                String key = m.group(1).toLowerCase(Locale.ROOT);
                String val = m.group(2) == null ? "" : m.group(2).trim();
                switch (key) {
                    case "name": if (!val.isEmpty()) s.name = val; break;
                    case "namespace": s.namespace = val; break;
                    case "version": s.version = val; break;
                    case "description": s.description = val; break;
                    case "author": s.author = val; break;
                    case "icon": case "iconurl": case "defaulticon": case "icon64": case "icon64url":
                        if (s.icon == null && !val.isEmpty()) s.icon = val; break;
                    case "run-at": s.runAt = normalizeRunAt(val); break;
                    case "match": if (!val.isEmpty()) s.matches.add(val); break;
                    case "include": if (!val.isEmpty()) s.includes.add(val); break;
                    case "exclude": case "exclude-match": if (!val.isEmpty()) s.excludes.add(val); break;
                    case "grant": if (!val.isEmpty()) s.grants.add(val); break;
                    case "require": if (!val.isEmpty()) s.requires.add(val); break;
                    case "connect": if (!val.isEmpty()) s.connects.add(val); break;
                    case "resource": {
                        String[] p = val.split("\\s+", 2);
                        if (p.length == 2) s.resources.put(p[0], p[1].trim());
                        break;
                    }
                    case "noframes": s.noframes = true; break;
                    case "updateurl": s.updateUrl = val; break;
                    case "downloadurl": s.downloadUrl = val; break;
                    case "installurl": if (s.downloadUrl == null) s.downloadUrl = val; break;
                    default:
                        if (key.startsWith("name:") && locName == null && !val.isEmpty()) locName = val;
                        else if (key.startsWith("description:") && locDesc == null) locDesc = val;
                }
            }
            if (s.name == null) s.name = locName;
            if (s.description == null) s.description = locDesc;
        } else {
            Matcher m = Pattern.compile("(?m)^\\s*//\\s*@(match|include|exclude)\\s+(\\S+)").matcher(s.code);
            while (m.find()) {
                String k = m.group(1);
                if (k.equals("exclude")) s.excludes.add(m.group(2));
                else if (k.equals("match")) s.matches.add(m.group(2));
                else s.includes.add(m.group(2));
            }
        }
        return s;
    }

    private static String normalizeRunAt(String v) {
        if (v == null) return RUN_IDLE;
        String t = v.trim().toLowerCase(Locale.ROOT);
        if (t.equals(RUN_START)) return RUN_START;
        if (t.equals(RUN_END) || t.equals("document-body")) return RUN_END;
        return RUN_IDLE;
    }

    // ------------------------------------------------------------------ 安装 / 启停 / 删除

    /** 保存/覆盖（按 namespace+name 找同一脚本），返回脚本。 */
    public Script install(String code, String installUrl) throws Exception {
        if (code == null || code.trim().isEmpty()) throw new IllegalArgumentException("脚本内容为空");
        Script p = parse(code);
        if (p.name == null || p.name.trim().isEmpty()) p.name = nameFromUrl(installUrl);
        String id;
        synchronized (lock) {
            JSONObject state = readState();
            Map<String, File> files = scanFiles();
            id = null;
            for (Map.Entry<String, File> e : files.entrySet()) {
                Script s = load(e.getKey(), e.getValue(), state);
                if (s != null && eq(s.namespace, p.namespace) && eq(s.name, p.name)) { id = s.id; break; }
            }
            long now = System.currentTimeMillis();
            if (id == null) id = newId(p.name, p.namespace, files);
            if (!scriptsDir.isDirectory() && !scriptsDir.mkdirs()) throw new Exception("无法创建脚本目录");
            File target = new File(scriptsDir, id + ".user.js");
            writeAtomic(target, code.getBytes(StandardCharsets.UTF_8));
            File legacy = new File(scriptsDir, id + ".js");
            if (legacy.isFile()) //noinspection ResultOfMethodCallIgnored
                legacy.delete();
            JSONObject rec = state.optJSONObject(id);
            if (rec == null) {
                rec = new JSONObject();
                rec.put("enabled", true);
                rec.put("installedAt", now);
            } else if (!rec.has("installedAt")) {
                rec.put("installedAt", now);
            }
            if (installUrl != null && !installUrl.isEmpty()) rec.put("installUrl", installUrl);
            rec.put("updatedAt", now);
            state.put(id, rec);
            writeState(state);
        }
        Script s = get(id);
        if (s != null && !onMainThread()) {
            try { prefetchDependencies(s); } catch (Throwable ignored) {}
        }
        return s;
    }

    /** 下载后 install。 */
    public Script installFromUrl(String url) throws Exception {
        String code = new String(download(url), StandardCharsets.UTF_8);
        if (!code.contains("==UserScript==")) throw new Exception("不是有效的用户脚本（缺少 ==UserScript== 头）");
        return install(code, url);
    }

    public boolean setEnabled(String id, boolean on) {
        synchronized (lock) {
            if (id == null || !scanFiles().containsKey(id)) return false;
            try {
                JSONObject state = readState();
                JSONObject rec = state.optJSONObject(id);
                if (rec == null) rec = new JSONObject();
                rec.put("enabled", on);
                state.put(id, rec);
                writeState(state);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    /** 删除源码、状态、数据。 */
    public boolean remove(String id) {
        if (id == null) return false;
        synchronized (lock) {
            File f = scanFiles().get(id);
            boolean existed = f != null;
            for (String suffix : new String[]{".user.js", ".js"}) {
                File x = new File(scriptsDir, id + suffix);
                if (x.isFile()) //noinspection ResultOfMethodCallIgnored
                    x.delete();
                parsed.remove(x.getName());
            }
            File data = dataFile(id);
            if (data.isFile()) //noinspection ResultOfMethodCallIgnored
                data.delete();
            try {
                JSONObject state = readState();
                if (state.has(id)) { state.remove(id); writeState(state); existed = true; }
            } catch (Exception ignored) {}
            return existed;
        }
    }

    /** 有新版本返回新代码，否则 null（比较 @version）。 */
    public String checkUpdate(String id) throws Exception {
        Script s = get(id);
        if (s == null) throw new Exception("脚本不存在: " + id);
        String fullUrl = firstNonEmpty(s.downloadUrl, s.installUrl);
        String upd = firstNonEmpty(s.updateUrl, fullUrl);
        if (upd == null) return null;
        String text = new String(download(upd), StandardCharsets.UTF_8);
        Script remote = parse(text);
        if (compareVersion(remote.version, s.version) <= 0) return null;
        int endMeta = text.indexOf("==/UserScript==");
        boolean hasBody = !upd.endsWith(".meta.js") && endMeta >= 0
                && !text.substring(endMeta + 15).trim().isEmpty();
        if (hasBody) return text;
        String codeUrl = fullUrl;
        if (codeUrl == null && upd.endsWith(".meta.js")) codeUrl = upd.substring(0, upd.length() - 8) + ".user.js";
        if (codeUrl == null || codeUrl.equals(upd)) return null;
        String code = new String(download(codeUrl), StandardCharsets.UTF_8);
        return compareVersion(parse(code).version, s.version) > 0 ? code : null;
    }

    /** 版本比较：按 . - _ 分段，数字段按数值，其余按字符串；a&gt;b 返回正数。 */
    static int compareVersion(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        String[] x = a.trim().split("[.\\-_+]"), y = b.trim().split("[.\\-_+]");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            String p = i < x.length ? x[i] : "0", q = i < y.length ? y[i] : "0";
            if (p.isEmpty()) p = "0";
            if (q.isEmpty()) q = "0";
            boolean pn = p.matches("\\d+"), qn = q.matches("\\d+");
            int c;
            if (pn && qn) {
                c = new java.math.BigInteger(p).compareTo(new java.math.BigInteger(q));
            } else if (pn) {
                c = 1;   // 1.0 > 1.0-beta
            } else if (qn) {
                c = -1;
            } else {
                c = p.compareToIgnoreCase(q);
            }
            if (c != 0) return c;
        }
        return 0;
    }

    // ------------------------------------------------------------------ GM 存储

    public JSONObject getValues(String id) {
        synchronized (lock) {
            try {
                File f = dataFile(id);
                if (!f.isFile()) return new JSONObject();
                return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            } catch (Exception e) {
                return new JSONObject();
            }
        }
    }

    /** jsonValue 是 JSON 文本（JSON.stringify 的结果）；解析失败按普通字符串存。 */
    public void setValue(String id, String key, String jsonValue) {
        if (id == null || key == null) return;
        synchronized (lock) {
            try {
                JSONObject o = getValues(id);
                if (jsonValue == null || jsonValue.equals("undefined")) {
                    o.remove(key);
                } else {
                    Object v;
                    try {
                        v = new JSONTokener(jsonValue).nextValue();
                    } catch (Exception e) {
                        v = jsonValue;
                    }
                    o.put(key, v == null ? JSONObject.NULL : v);
                }
                writeValues(id, o);
            } catch (Exception ignored) {}
        }
    }

    public void deleteValue(String id, String key) {
        if (id == null || key == null) return;
        synchronized (lock) {
            try {
                JSONObject o = getValues(id);
                if (o.has(key)) { o.remove(key); writeValues(id, o); }
            } catch (Exception ignored) {}
        }
    }

    private void writeValues(String id, JSONObject o) throws Exception {
        if (!dataDir.isDirectory()) //noinspection ResultOfMethodCallIgnored
            dataDir.mkdirs();
        writeAtomic(dataFile(id), o.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ 注入

    /** 返回可直接 evaluateJavascript 的完整 JS：GM 垫片 + @require 缓存内容 + 脚本，带防重复注入守卫。 */
    public String buildInjection(Script s, String tabKey) {
        if (s == null || s.code == null) return "void 0";
        String id = s.id == null ? "" : s.id;
        String qid = JSONObject.quote(id);
        String name = s.name == null ? id : s.name;

        // 依赖：只读缓存，缺失的在后台补下载
        StringBuilder req = new StringBuilder();
        List<String> missing = new ArrayList<>();
        for (String u : s.requires) {
            String text = cachedText(u);
            if (text == null) { missing.add(u); continue; }
            req.append(text).append("\n;\n");
        }
        JSONObject res = new JSONObject();
        for (Map.Entry<String, String> e : s.resources.entrySet()) {
            byte[] b = cachedBytes(e.getValue());
            if (b == null) { missing.add(e.getValue()); continue; }
            if (b.length > MAX_RESOURCE_EMBED) continue;
            try {
                res.put(e.getKey(), new JSONObject()
                        .put("b", Base64.getEncoder().encodeToString(b))
                        .put("m", guessMime(e.getValue()))
                        .put("u", e.getValue()));
            } catch (Exception ignored) {}
        }
        if (!missing.isEmpty()) fetchInBackground(missing);

        JSONObject info = new JSONObject();
        try {
            JSONObject sc = new JSONObject();
            sc.put("name", name);
            sc.put("namespace", s.namespace == null ? "" : s.namespace);
            sc.put("version", s.version == null ? "" : s.version);
            sc.put("description", s.description == null ? "" : s.description);
            sc.put("author", s.author == null ? "" : s.author);
            sc.put("icon", s.icon == null ? "" : s.icon);
            sc.put("matches", new JSONArray(s.matches));
            sc.put("includes", new JSONArray(s.includes));
            sc.put("excludes", new JSONArray(s.excludes));
            sc.put("grant", new JSONArray(s.grants));
            sc.put("connects", new JSONArray(s.connects));
            sc.put("requires", new JSONArray(s.requires));
            JSONArray resArr = new JSONArray();
            for (Map.Entry<String, String> e : s.resources.entrySet()) {
                resArr.put(new JSONObject().put("name", e.getKey()).put("url", e.getValue()));
            }
            sc.put("resources", resArr);
            sc.put("run-at", s.runAt);
            sc.put("runAt", s.runAt);
            sc.put("noframes", s.noframes);
            sc.put("id", id);
            info.put("script", sc);
            info.put("scriptHandler", "PiMet");
            info.put("version", "1.0");
            info.put("scriptMetaStr", metaBlock(s.code));
            info.put("tabKey", tabKey == null ? "" : tabKey);
        } catch (Exception ignored) {}

        String guardKey = JSONObject.quote(id + "|" + (tabKey == null ? "" : tabKey));
        StringBuilder js = new StringBuilder(s.code.length() + req.length() + 8192);
        js.append("(function(){\n");
        js.append("var W=window;var D=W.__pimetUSdone||(W.__pimetUSdone={});var GK=").append(guardKey)
                .append(";if(D[GK])return;D[GK]=1;\n");
        js.append(RUNTIME);
        js.append("var ID=").append(qid).append(";\n");
        js.append("var V=").append(getValues(id).toString()).append(";\n");
        js.append("var R=").append(res.toString()).append(";\n");
        js.append("var INFO=").append(info.toString()).append(";\n");
        if (!missing.isEmpty()) {
            js.append("try{console.warn('[PiMet 用户脚本 '+INFO.script.name+'] 依赖尚未缓存，刷新页面后生效：'+")
                    .append(JSONObject.quote(String.join(", ", missing))).append(")}catch(e){}\n");
        }
        js.append(SHIM);
        js.append("(function(GM_info,unsafeWindow,GM_getValue,GM_setValue,GM_deleteValue,GM_listValues,")
                .append("GM_addValueChangeListener,GM_removeValueChangeListener,GM_addStyle,GM_addElement,")
                .append("GM_xmlhttpRequest,GM_openInTab,GM_setClipboard,GM_notification,GM_registerMenuCommand,")
                .append("GM_unregisterMenuCommand,GM_getResourceText,GM_getResourceURL,GM_download,GM_log,GM){\n");
        js.append("try{\n");
        js.append(req);
        js.append(s.code);
        js.append("\n}catch(e){console.error('[PiMet 用户脚本 '+GM_info.script.name+']',e)}\n");
        js.append("}).call(W,API.GM_info,W,API.GM_getValue,API.GM_setValue,API.GM_deleteValue,API.GM_listValues,")
                .append("API.GM_addValueChangeListener,API.GM_removeValueChangeListener,API.GM_addStyle,API.GM_addElement,")
                .append("API.GM_xmlhttpRequest,API.GM_openInTab,API.GM_setClipboard,API.GM_notification,API.GM_registerMenuCommand,")
                .append("API.GM_unregisterMenuCommand,API.GM_getResourceText,API.GM_getResourceURL,API.GM_download,API.GM_log,API.GM);\n");
        js.append("})();");
        return js.toString();
    }

    /** 每个文档一份的共享运行时：回调分发 __pimetGMcb / __pimetGMmenu。 */
    private static final String RUNTIME =
            "var RT=W.__pimetGMrt;if(!RT){RT=W.__pimetGMrt={cbs:{},menus:{},seq:0};\n"
            + "W.__pimetGMcb=function(callId,result){var f=RT.cbs[callId];if(!f)return;delete RT.cbs[callId];"
            + "var r=result;if(typeof r==='string'){try{r=JSON.parse(r)}catch(e){r={error:String(r)}}}try{f(r||{})}catch(e){console.error(e)}};\n"
            + "W.__pimetGMmenu=function(id,cmdId){var m=RT.menus[id];var f=m&&m[cmdId];if(f){try{f()}catch(e){console.error('[PiMet 菜单命令]',e)}}};\n"
            + "}\n";

    /** 每个脚本独立作用域的 GM API；依赖外层的 W, RT, ID, V, R, INFO。 */
    private static final String SHIM =
            "var B=W.PiMetGM||null;\n"
            + "function nb(m){if(!B||typeof B[m]!=='function'){return false}return true}\n"
            + "function cp(v){if(v===undefined||v===null||typeof v!=='object')return v;try{return JSON.parse(JSON.stringify(v))}catch(e){return v}}\n"
            + "function abs(u){try{return new URL(String(u),location.href).href}catch(e){return String(u)}}\n"
            + "function b64ToBytes(b){var s=atob(b||'');var a=new Uint8Array(s.length);for(var i=0;i<s.length;i++)a[i]=s.charCodeAt(i);return a}\n"
            + "function bytesToB64(buf){var a=new Uint8Array(buf);var s='';for(var i=0;i<a.length;i+=32768){s+=String.fromCharCode.apply(null,a.subarray(i,i+32768))}return btoa(s)}\n"
            + "var listeners={},lseq=0;\n"
            + "var API={};\n"
            + "API.GM_info=INFO;\n"
            + "API.GM_getValue=function(k,d){k=String(k);return Object.prototype.hasOwnProperty.call(V,k)?cp(V[k]):d};\n"
            + "API.GM_setValue=function(k,v){k=String(k);var old=V[k];if(v===undefined){return API.GM_deleteValue(k)}var j;try{j=JSON.stringify(v)}catch(e){j=JSON.stringify(String(v))}"
            + "V[k]=JSON.parse(j);if(nb('setValue'))B.setValue(ID,k,j);fire(k,old,V[k])};\n"
            + "API.GM_deleteValue=function(k){k=String(k);var old=V[k];delete V[k];if(nb('deleteValue'))B.deleteValue(ID,k);fire(k,old,undefined)};\n"
            + "API.GM_listValues=function(){return Object.keys(V)};\n"
            + "function fire(k,o,n){for(var i in listeners){var l=listeners[i];if(l.k===k){try{l.f(k,o,n,false)}catch(e){console.error(e)}}}}\n"
            + "API.GM_addValueChangeListener=function(k,f){var i=++lseq;listeners[i]={k:String(k),f:f};return i};\n"
            + "API.GM_removeValueChangeListener=function(i){delete listeners[i]};\n"
            + "API.GM_addStyle=function(css){var e=document.createElement('style');e.textContent=String(css);"
            + "(document.head||document.documentElement||document).appendChild(e);return e};\n"
            + "API.GM_addElement=function(a,b,c){var parent,tag,attrs;if(typeof a==='string'){tag=a;attrs=b}else{parent=a;tag=b;attrs=c}"
            + "var e=document.createElement(tag);attrs=attrs||{};for(var k in attrs){if(k==='textContent'||k==='innerHTML'||k==='innerText'){e[k]=attrs[k]}else{e.setAttribute(k,attrs[k])}}"
            + "if(!parent){parent=/^(script|style|link|meta)$/i.test(tag)?(document.head||document.documentElement):(document.body||document.documentElement)}"
            + "parent.appendChild(e);return e};\n"
            + "API.GM_xmlhttpRequest=function(d){d=d||{};var callId=ID+':'+(++RT.seq)+':'+Date.now();var aborted=false;"
            + "var rt=(d.responseType||'').toLowerCase();var data=d.data;var dataB64=null;"
            + "if(data instanceof ArrayBuffer){dataB64=bytesToB64(data);data=null}else if(data&&data.buffer instanceof ArrayBuffer&&ArrayBuffer.isView(data)){dataB64=bytesToB64(data.buffer);data=null}"
            + "else if(data!=null&&typeof data!=='string'){if(typeof URLSearchParams!=='undefined'&&data instanceof URLSearchParams){data=data.toString()}else{try{data=JSON.stringify(data)}catch(e){data=String(data)}}}"
            + "var req={callId:callId,method:(d.method||'GET').toUpperCase(),url:abs(d.url),headers:d.headers||{},data:data,dataBase64:dataB64,"
            + "responseType:(rt==='arraybuffer'||rt==='blob')?'arraybuffer':(rt==='json'?'json':'text'),timeout:d.timeout||0,anonymous:!!d.anonymous,overrideMimeType:d.overrideMimeType||null};"
            + "function call(n,r){var f=d[n];if(typeof f==='function'){try{f.call(d,r)}catch(e){console.error(e)}}}\n"
            + "RT.cbs[callId]=function(r){if(aborted)return;var resp={readyState:4,status:r.status||0,statusText:r.statusText||'',responseHeaders:r.responseHeaders||'',"
            + "finalUrl:r.finalUrl||req.url,responseText:r.responseText||'',context:d.context};"
            + "var bin=r.responseBase64||(req.responseType==='arraybuffer'?r.response:null);"
            + "if(req.responseType==='arraybuffer'){var bytes=b64ToBytes(bin||'');resp.response=rt==='blob'?new Blob([bytes]):bytes.buffer}"
            + "else if(req.responseType==='json'){try{resp.response=typeof r.response==='object'&&r.response!==null?r.response:JSON.parse(resp.responseText)}catch(e){resp.response=null}}"
            + "else{resp.response=resp.responseText}"
            + "if(r.timeout){call('ontimeout',resp)}else if(r.error){resp.error=r.error;call('onerror',resp)}else{call('onreadystatechange',resp);call('onload',resp)}call('onloadend',resp)};\n"
            + "if(nb('xhr')){try{var ret=B.xhr(ID,JSON.stringify(req));if(typeof ret==='string'&&ret&&ret!==callId){RT.cbs[ret]=RT.cbs[callId]}}"
            + "catch(e){var cb=RT.cbs[callId];delete RT.cbs[callId];if(cb)cb({error:String(e)})}}"
            + "else{var cb2=RT.cbs[callId];delete RT.cbs[callId];setTimeout(function(){cb2({error:'GM_xmlhttpRequest 不可用'})},0)}\n"
            + "call('onloadstart',{readyState:1});"
            + "return{abort:function(){if(aborted)return;aborted=true;delete RT.cbs[callId];call('onabort',{readyState:4,status:0})}}};\n"
            + "API.GM_openInTab=function(u,o){var bg=false;if(typeof o==='boolean'){bg=o}else if(o){bg=o.active===false||!!o.loadInBackground}"
            + "if(nb('openInTab'))B.openInTab(abs(u),bg);else W.open(abs(u),'_blank');return{closed:false,close:function(){},onclose:null}};\n"
            + "API.GM_setClipboard=function(t){if(nb('setClipboard'))B.setClipboard(String(t))};\n"
            + "API.GM_notification=function(a,b,c,e){var title,text,onclick,ondone;if(a&&typeof a==='object'){title=a.title;text=a.text;onclick=a.onclick;ondone=a.ondone}else{text=a;title=b;onclick=e}"
            + "if(nb('notify'))B.notify(ID,String(title||INFO.script.name),String(text||''));if(typeof ondone==='function'){try{ondone()}catch(x){}}};\n"
            + "API.GM_registerMenuCommand=function(cap,fn,opt){var cid=(opt&&typeof opt==='object'&&opt.id)?String(opt.id):('m'+(++RT.seq));"
            + "var m=RT.menus[ID]||(RT.menus[ID]={});m[cid]=fn;if(nb('registerMenu'))B.registerMenu(ID,cid,String(cap));return cid};\n"
            + "API.GM_unregisterMenuCommand=function(cid){cid=String(cid);var m=RT.menus[ID];if(m)delete m[cid];if(nb('unregisterMenu'))B.unregisterMenu(ID,cid)};\n"
            + "API.GM_getResourceText=function(n){var r=R[n];if(!r)return null;var a=b64ToBytes(r.b);try{return new TextDecoder('utf-8').decode(a)}catch(e){var s='';for(var i=0;i<a.length;i++)s+=String.fromCharCode(a[i]);return s}};\n"
            + "API.GM_getResourceURL=function(n){var r=R[n];if(!r)return null;return 'data:'+r.m+';base64,'+r.b};\n"
            + "API.GM_download=function(a,b){var u,n;if(a&&typeof a==='object'){u=a.url;n=a.name}else{u=a;n=b}if(nb('download'))B.download(abs(u),String(n||''))};\n"
            + "API.GM_log=function(){var t=Array.prototype.map.call(arguments,function(x){try{return typeof x==='string'?x:JSON.stringify(x)}catch(e){return String(x)}}).join(' ');"
            + "if(nb('log'))B.log(ID,t);try{console.log('[PiMet 用户脚本 '+INFO.script.name+'] '+t)}catch(e){}};\n"
            + "var GMo={info:INFO};\n"
            + "GMo.getValue=function(k,d){return Promise.resolve(API.GM_getValue(k,d))};\n"
            + "GMo.setValue=function(k,v){return Promise.resolve(API.GM_setValue(k,v))};\n"
            + "GMo.deleteValue=function(k){return Promise.resolve(API.GM_deleteValue(k))};\n"
            + "GMo.listValues=function(){return Promise.resolve(API.GM_listValues())};\n"
            + "GMo.addStyle=function(c){return Promise.resolve(API.GM_addStyle(c))};\n"
            + "GMo.addElement=function(a,b,c){return Promise.resolve(API.GM_addElement(a,b,c))};\n"
            + "GMo.xmlHttpRequest=function(d){d=d||{};var h;var p=new Promise(function(ok,no){var o={};for(var k in d)o[k]=d[k];"
            + "o.onload=function(r){if(d.onload)d.onload(r);ok(r)};o.onerror=function(r){if(d.onerror)d.onerror(r);no(r)};"
            + "o.ontimeout=function(r){if(d.ontimeout)d.ontimeout(r);no(r)};o.onabort=function(r){if(d.onabort)d.onabort(r);no(r)};h=API.GM_xmlhttpRequest(o)});"
            + "p.abort=function(){if(h)h.abort()};return p};\n"
            + "GMo.xmlhttpRequest=GMo.xmlHttpRequest;\n"
            + "GMo.openInTab=function(u,o){return Promise.resolve(API.GM_openInTab(u,o))};\n"
            + "GMo.setClipboard=function(t){return Promise.resolve(API.GM_setClipboard(t))};\n"
            + "GMo.notification=function(a,b,c,e){return Promise.resolve(API.GM_notification(a,b,c,e))};\n"
            + "GMo.registerMenuCommand=function(c,f,o){return Promise.resolve(API.GM_registerMenuCommand(c,f,o))};\n"
            + "GMo.unregisterMenuCommand=function(c){return Promise.resolve(API.GM_unregisterMenuCommand(c))};\n"
            + "GMo.getResourceText=function(n){return Promise.resolve(API.GM_getResourceText(n))};\n"
            + "GMo.getResourceUrl=function(n){return Promise.resolve(API.GM_getResourceURL(n))};\n"
            + "GMo.getResourceURL=GMo.getResourceUrl;\n"
            + "GMo.download=function(a,b){return Promise.resolve(API.GM_download(a,b))};\n"
            + "GMo.log=function(){return Promise.resolve(API.GM_log.apply(null,arguments))};\n"
            + "API.GM=GMo;\n";

    // ------------------------------------------------------------------ 依赖缓存

    /** 下载脚本的 @require / @resource 到缓存（后台线程）。 */
    public void prefetchDependencies(Script s) {
        if (s == null) return;
        List<String> urls = new ArrayList<>(s.requires);
        urls.addAll(s.resources.values());
        for (String u : urls) {
            if (cacheFile(u).isFile()) continue;
            try { cacheUrl(u); } catch (Throwable ignored) {}
        }
    }

    private void fetchInBackground(List<String> urls) {
        final List<String> todo = new ArrayList<>();
        for (String u : urls) if (fetching.add(u)) todo.add(u);
        if (todo.isEmpty()) return;
        Thread t = new Thread(() -> {
            for (String u : todo) {
                try { cacheUrl(u); } catch (Throwable ignored) {}
                finally { fetching.remove(u); }
            }
        }, "pimet-us-fetch");
        t.setDaemon(true);
        t.start();
    }

    private void cacheUrl(String url) throws Exception {
        byte[] b = download(url);
        synchronized (lock) {
            if (!cacheDir.isDirectory()) //noinspection ResultOfMethodCallIgnored
                cacheDir.mkdirs();
            writeAtomic(cacheFile(url), b);
        }
    }

    private File cacheFile(String url) {
        return new File(cacheDir, sha1(url));
    }

    private byte[] cachedBytes(String url) {
        if (url == null) return null;
        if (url.startsWith("data:")) return decodeDataUrl(url);
        File f = cacheFile(url);
        if (!f.isFile()) return null;
        try { return Files.readAllBytes(f.toPath()); } catch (Exception e) { return null; }
    }

    private String cachedText(String url) {
        byte[] b = cachedBytes(url);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    private static byte[] decodeDataUrl(String url) {
        int comma = url.indexOf(',');
        if (comma < 0) return null;
        String head = url.substring(5, comma);
        String body = url.substring(comma + 1);
        try {
            if (head.endsWith(";base64")) return Base64.getDecoder().decode(body);
            return java.net.URLDecoder.decode(body, "UTF-8").getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static String guessMime(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        if (u.startsWith("data:")) {
            int semi = u.indexOf(';'), comma = u.indexOf(',');
            int end = semi > 0 && (comma < 0 || semi < comma) ? semi : comma;
            return end > 5 ? u.substring(5, end) : "application/octet-stream";
        }
        if (u.endsWith(".png")) return "image/png";
        if (u.endsWith(".jpg") || u.endsWith(".jpeg")) return "image/jpeg";
        if (u.endsWith(".gif")) return "image/gif";
        if (u.endsWith(".svg")) return "image/svg+xml";
        if (u.endsWith(".webp")) return "image/webp";
        if (u.endsWith(".ico")) return "image/x-icon";
        if (u.endsWith(".css")) return "text/css";
        if (u.endsWith(".js")) return "text/javascript";
        if (u.endsWith(".json")) return "application/json";
        if (u.endsWith(".html") || u.endsWith(".htm")) return "text/html";
        if (u.endsWith(".woff2")) return "font/woff2";
        if (u.endsWith(".woff")) return "font/woff";
        if (u.endsWith(".ttf")) return "font/ttf";
        if (u.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    // ------------------------------------------------------------------ 网络

    private static byte[] download(String url) throws Exception {
        if (url == null || url.isEmpty()) throw new Exception("网址为空");
        if (onMainThread()) throw new IllegalStateException("网络操作不能在主线程调用");
        if (url.startsWith("data:")) {
            byte[] b = decodeDataUrl(url);
            if (b == null) throw new Exception("data: 网址无效");
            return b;
        }
        String cur = url;
        for (int hop = 0; hop < 6; hop++) {
            URL u = new URL(cur);
            String proto = u.getProtocol();
            if (!"http".equals(proto) && !"https".equals(proto)) throw new Exception("只支持 http/https: " + cur);
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) PiMet-UserScript/1.0");
            c.setRequestProperty("Accept", "*/*");
            try {
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) throw new Exception("重定向缺少 Location");
                    cur = new URL(u, loc).toString();
                    continue;
                }
                if (code < 200 || code >= 300) throw new Exception("HTTP " + code + ": " + cur);
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n, total = 0;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_DOWNLOAD) throw new Exception("文件过大（超过 10MB）");
                        out.write(buf, 0, n);
                    }
                    return out.toByteArray();
                }
            } finally {
                c.disconnect();
            }
        }
        throw new Exception("重定向次数过多: " + url);
    }

    private static boolean onMainThread() {
        try {
            Looper main = Looper.getMainLooper();
            return main != null && main == Looper.myLooper();
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 匹配

    /** Chrome match pattern；无法解析时按通配规则处理（兼容旧脚本）。调用方持有 lock。 */
    private boolean matchPatternMatches(String pattern, String url) {
        String p = pattern.trim();
        if (p.equals("<all_urls>")) {
            return url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://") || url.startsWith("ftp://");
        }
        String key = "m:" + p;
        Pattern re;
        if (patternCache.containsKey(key)) {
            re = patternCache.get(key);
        } else {
            re = compileMatchPattern(p);
            patternCache.put(key, re);
        }
        if (re == null) return globMatches(p, url);
        return re.matcher(url).matches();
    }

    private static Pattern compileMatchPattern(String p) {
        Matcher m = Pattern.compile("^(\\*|https?|file|ftp|wss?)://([^/]*)(/.*)?$").matcher(p);
        if (!m.matches()) return null;
        String scheme = m.group(1), host = m.group(2), path = m.group(3);
        if (path == null) path = "/";
        StringBuilder sb = new StringBuilder("^");
        sb.append(scheme.equals("*") ? "https?" : Pattern.quote(scheme)).append("://");
        if (scheme.equals("file")) {
            // file:///path：主机为空
        } else if (host.equals("*")) {
            sb.append("[^/]*");
        } else if (host.startsWith("*.")) {
            String h = host.substring(2);
            sb.append("(?:[^/@]*@)?(?:[^/]*\\.)?").append(hostRegex(h));
        } else if (host.contains("*")) {
            return null;
        } else {
            sb.append("(?:[^/@]*@)?").append(hostRegex(host));
        }
        sb.append("(?::\\d+)?");
        sb.append(globBody(path));
        sb.append("$");
        try {
            return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
        } catch (Exception e) {
            return null;
        }
    }

    private static String hostRegex(String host) {
        String h = host;
        String port = null;
        int colon = h.lastIndexOf(':');
        if (colon > 0 && h.indexOf(']') < colon) { port = h.substring(colon + 1); h = h.substring(0, colon); }
        StringBuilder sb = new StringBuilder();
        if (h.toLowerCase(Locale.ROOT).endsWith(".tld")) {
            sb.append(Pattern.quote(h.substring(0, h.length() - 4))).append("\\.[a-z.]{2,}");
        } else {
            sb.append(Pattern.quote(h));
        }
        if (port != null && !port.equals("*")) sb.append(':').append(Pattern.quote(port));
        return sb.toString();
    }

    /** @include / @exclude：* 通配，/正则/ 或 /正则/i。调用方持有 lock。 */
    private boolean globMatches(String rule, String url) {
        String key = "g:" + rule;
        Pattern re;
        if (patternCache.containsKey(key)) {
            re = patternCache.get(key);
        } else {
            re = compileGlob(rule);
            patternCache.put(key, re);
        }
        if (re == null) return false;
        String r = rule.trim();
        boolean isRegex = r.length() > 2 && r.startsWith("/") && (r.endsWith("/") || r.endsWith("/i"));
        return isRegex ? re.matcher(url).find() : re.matcher(url).matches();
    }

    private static Pattern compileGlob(String rule) {
        String g = rule.trim();
        try {
            if (g.length() > 2 && g.startsWith("/") && g.endsWith("/i")) {
                return Pattern.compile(g.substring(1, g.length() - 2), Pattern.CASE_INSENSITIVE);
            }
            if (g.length() > 2 && g.startsWith("/") && g.endsWith("/")) {
                return Pattern.compile(g.substring(1, g.length() - 1));
            }
            if (g.equals("*") || g.equals("<all_urls>")) return Pattern.compile("^.*$", Pattern.DOTALL);
            String body = globBody(g);
            // .tld 通配：example.tld → example.com / example.co.uk ...
            body = body.replace("\\.tld", "\\.[a-z.]{2,}");
            return Pattern.compile("^" + body + "$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        } catch (Exception e) {
            return null;
        }
    }

    private static String globBody(String g) {
        StringBuilder sb = new StringBuilder();
        for (char c : g.toCharArray()) {
            if (c == '*') sb.append(".*");
            else if ("\\.[]{}()+-?^$|".indexOf(c) >= 0) sb.append('\\').append(c);
            else sb.append(c);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 文件

    /** id → 文件；同 id 同时存在 .user.js 与旧 .js 时取 .user.js。调用方持有 lock。 */
    private Map<String, File> scanFiles() {
        Map<String, File> out = new LinkedHashMap<>();
        File[] fs = scriptsDir.listFiles();
        if (fs == null) return out;
        java.util.Arrays.sort(fs);
        for (File f : fs) {
            String n = f.getName();
            if (!f.isFile() || n.startsWith(".") || !n.endsWith(".js") || n.endsWith(".meta.js")) continue;
            if (n.endsWith(".user.js")) {
                String id = n.substring(0, n.length() - 8);
                if (!id.isEmpty()) out.put(id, f);
            }
        }
        for (File f : fs) {
            String n = f.getName();
            if (!f.isFile() || n.startsWith(".") || !n.endsWith(".js") || n.endsWith(".user.js") || n.endsWith(".meta.js")) continue;
            String id = n.substring(0, n.length() - 3);
            if (!id.isEmpty() && !out.containsKey(id)) out.put(id, f);
        }
        return out;
    }

    /** 调用方持有 lock。返回副本。 */
    private Script load(String id, File f, JSONObject state) {
        Script base = parsed.get(f.getName());
        long mt = f.lastModified();
        if (base == null || base.mtime != mt || !id.equals(base.id)) {
            try {
                String code = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                base = parse(code);
            } catch (Exception e) {
                base = new Script();
                base.code = null;
            }
            base.id = id;
            base.mtime = mt;
            if (base.name == null || base.name.trim().isEmpty()) base.name = id;
            parsed.put(f.getName(), base);
        }
        Script s = base.copy();
        JSONObject rec = state.optJSONObject(id);
        s.enabled = rec == null || rec.optBoolean("enabled", true);
        if (rec != null) {
            String iu = rec.optString("installUrl", "");
            if (!iu.isEmpty()) s.installUrl = iu;
        }
        return s;
    }

    private JSONObject readState() {
        try {
            if (!stateFile.isFile()) return new JSONObject();
            return new JSONObject(new String(Files.readAllBytes(stateFile.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void writeState(JSONObject state) throws Exception {
        if (!appsDir.isDirectory()) //noinspection ResultOfMethodCallIgnored
            appsDir.mkdirs();
        writeAtomic(stateFile, state.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    private File dataFile(String id) {
        String n = ID_OK.matcher(id).matches() ? id : id.replaceAll("[^A-Za-z0-9_-]", "_") + "-" + sha1(id).substring(0, 8);
        return new File(dataDir, n + ".json");
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

    // ------------------------------------------------------------------ 工具

    private String newId(String name, String namespace, Map<String, File> existing) {
        String base = name == null ? "" : name.replaceAll("[^A-Za-z0-9_-]+", "-").replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
        if (base.length() > 48) base = base.substring(0, 48);
        if (base.isEmpty()) base = "us-" + sha1((namespace == null ? "" : namespace) + "|" + name).substring(0, 8);
        String id = base;
        int i = 2;
        JSONObject state = readState();
        while (existing.containsKey(id) || state.has(id)) id = base + "-" + (i++);
        return id;
    }

    private static String nameFromUrl(String url) {
        if (url == null || url.isEmpty()) return "未命名脚本";
        String u = url;
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        int h = u.indexOf('#');
        if (h >= 0) u = u.substring(0, h);
        String n = u.substring(u.lastIndexOf('/') + 1);
        if (n.endsWith(".user.js")) n = n.substring(0, n.length() - 8);
        else if (n.endsWith(".js")) n = n.substring(0, n.length() - 3);
        try { n = java.net.URLDecoder.decode(n, "UTF-8"); } catch (Exception ignored) {}
        return n.isEmpty() ? "未命名脚本" : n;
    }

    private static String metaBlock(String code) {
        if (code == null) return "";
        int a = code.indexOf("==UserScript==");
        int b = code.indexOf("==/UserScript==");
        if (a < 0 || b < a) return "";
        int lineStart = code.lastIndexOf('\n', a);
        return code.substring(lineStart < 0 ? 0 : lineStart + 1, b + 15);
    }

    private static boolean eq(String a, String b) {
        String x = a == null ? "" : a.trim(), y = b == null ? "" : b.trim();
        return x.equals(y);
    }

    private static String firstNonEmpty(String... v) {
        for (String s : v) if (s != null && !s.trim().isEmpty()) return s.trim();
        return null;
    }

    private static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
