package com.xm486.pimet.ui;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebView;

import androidx.webkit.Profile;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 容器标签：一个容器 = 一个 WebView Profile（独立 Cookie / Storage / 缓存）。
 *
 * 元数据存 appsDir/containers.json：{"&lt;name&gt;":{"label":"容器 3","color":"#4CAF50","createdAt":0,"keep":false}}
 * 内存为准，写文件走单线程 executor，不在主线程做 IO。
 * ProfileStore / WebViewCompat 只能在主线程调用：apply / cookies / release 请在主线程调用
 * （release 在非主线程调用时自动切到主线程）。
 */
public final class ContainerManager {

    public static final String DEFAULT = "Default";

    private static final String TAG = "ContainerManager";
    private static final Pattern AUTO_NAME = Pattern.compile("^c(\\d+)$");
    private static final String[] COLORS = {
            "#4CAF50", "#2196F3", "#FF9800", "#E91E63", "#9C27B0",
            "#00BCD4", "#F44336", "#795548", "#3F51B5", "#CDDC39", "#009688", "#FFC107"
    };

    private static Boolean supportedCache;

    private final File metaFile;
    private final Object lock = new Object();
    private final Map<String, JSONObject> meta = new HashMap<>();
    /** 容器 → 已 apply 到该容器的 WebView（弱引用，WebView 销毁后自动消失） */
    private final Map<String, Map<WebView, Boolean>> users = new HashMap<>();
    /** 容器 → apply 次数减 release 次数（WebView 未被回收时的兜底计数） */
    private final Map<String, Integer> refs = new HashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pimet-containers");
        t.setDaemon(true);
        return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private int maxSeen;

    public static boolean supported() {
        Boolean c = supportedCache;
        if (c != null) return c;
        boolean s;
        try {
            s = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE);
        } catch (Throwable t) {
            s = false;
        }
        supportedCache = s;
        return s;
    }

    public ContainerManager(File appsDir) {
        this.metaFile = new File(appsDir, "containers.json");
        // 文件很小，构造时同步读一次（之后只写）
        try {
            if (metaFile.isFile()) {
                JSONObject o = new JSONObject(new String(Files.readAllBytes(metaFile.toPath()), StandardCharsets.UTF_8));
                for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                    String k = it.next();
                    JSONObject m = o.optJSONObject(k);
                    if (m != null) meta.put(k, m);
                    noteName(k);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "读取 containers.json 失败", t);
        }
        if (!meta.containsKey(DEFAULT)) meta.put(DEFAULT, makeMeta("共享容器", "#9E9E9E", true));
    }

    /** 生成 "c&lt;递增数字&gt;"，分配颜色和中文标签，返回 name。 */
    public String newContainer() {
        synchronized (lock) {
            if (supported()) {
                try {
                    for (String n : ProfileStore.getInstance().getAllProfileNames()) noteName(n);
                } catch (Throwable ignored) {}
            }
            int n = maxSeen + 1;
            String name = "c" + n;
            while (meta.containsKey(name)) name = "c" + (++n);
            noteName(name);
            meta.put(name, makeMeta("容器 " + n, COLORS[(n - 1) % COLORS.length], false));
            saveLocked();
            return name;
        }
    }

    /** 在 loadUrl 之前调用：支持多 Profile 时 WebViewCompat.setProfile(wv, name)；不支持时什么都不做。 */
    public void apply(WebView wv, String name) {
        if (wv == null) return;
        String n = name == null || name.isEmpty() ? DEFAULT : name;
        synchronized (lock) {
            if (!meta.containsKey(n)) {
                noteName(n);
                meta.put(n, makeMeta(labelFor(n), colorFor(n), false));
                saveLocked();
            }
            Map<WebView, Boolean> set = users.get(n);
            if (set == null) { set = new WeakHashMap<>(); users.put(n, set); }
            if (set.put(wv, Boolean.TRUE) == null) refs.put(n, refCount(n) + 1);
        }
        if (!supported()) return;
        try {
            ProfileStore.getInstance().getOrCreateProfile(n);
            WebViewCompat.setProfile(wv, n);
        } catch (Throwable t) {
            // WebView 已经加载过内容时 setProfile 会抛 IllegalStateException
            Log.w(TAG, "setProfile(" + n + ") 失败", t);
        }
    }

    /** 该容器的 CookieManager；不支持时返回全局。 */
    public CookieManager cookies(String name) {
        String n = name == null || name.isEmpty() ? DEFAULT : name;
        if (supported()) {
            try {
                Profile p = ProfileStore.getInstance().getOrCreateProfile(n);
                if (p != null) return p.getCookieManager();
            } catch (Throwable t) {
                Log.w(TAG, "取容器 Cookie 失败: " + n, t);
            }
        }
        return CookieManager.getInstance();
    }

    /** 标签关闭时调：keep==false 且没有其他标签在用 → 删除 Profile 与元数据；DEFAULT 永不删除。 */
    public void release(String name) {
        if (name == null || name.isEmpty() || DEFAULT.equals(name)) return;
        synchronized (lock) {
            int c = refCount(name) - 1;
            if (c > 0) { refs.put(name, c); return; }
            refs.remove(name);
            JSONObject m = meta.get(name);
            if (m != null && m.optBoolean("keep", false)) return;
        }
        // 关闭标签时 WebView 往往还没 destroy，稍后再删；被占用时重试几次
        main.postDelayed(() -> tryDelete(name, 0), 500);
    }

    private void tryDelete(String name, int attempt) {
        synchronized (lock) {
            if (refCount(name) > 0) return;          // 期间又被别的标签用上了
            JSONObject m = meta.get(name);
            if (m != null && m.optBoolean("keep", false)) return;
            Map<WebView, Boolean> set = users.get(name);
            if (set != null) {
                set.clear();
                users.remove(name);
            }
        }
        boolean done = true;
        if (supported()) {
            try {
                ProfileStore store = ProfileStore.getInstance();
                if (store.getProfile(name) != null) done = store.deleteProfile(name);
            } catch (IllegalStateException inUse) {
                done = false;
            } catch (Throwable t) {
                Log.w(TAG, "删除容器失败: " + name, t);
            }
        }
        if (!done) {
            if (attempt < 5) main.postDelayed(() -> tryDelete(name, attempt + 1), 2000L * (attempt + 1));
            return;   // 仍删不掉就保留元数据，下次启动由 cleanupOrphans 处理
        }
        synchronized (lock) {
            if (refCount(name) > 0) return;
            if (meta.remove(name) != null) saveLocked();
        }
    }

    /**
     * 可选：启动时（还没有任何标签前）在主线程调用，删除上次异常退出遗留的、未保留的自动容器。
     * openNames 为当前恢复的标签正在用的容器，可为 null。
     */
    public void cleanupOrphans(java.util.Collection<String> openNames) {
        List<String> victims = new ArrayList<>();
        synchronized (lock) {
            List<String> names = new ArrayList<>(meta.keySet());
            if (supported()) {
                try { names.addAll(ProfileStore.getInstance().getAllProfileNames()); } catch (Throwable ignored) {}
            }
            for (String n : names) {
                if (DEFAULT.equals(n) || victims.contains(n)) continue;
                if (!AUTO_NAME.matcher(n).matches()) continue;
                if (openNames != null && openNames.contains(n)) continue;
                if (refCount(n) > 0) continue;
                JSONObject m = meta.get(n);
                if (m != null && m.optBoolean("keep", false)) continue;
                victims.add(n);
            }
        }
        for (String n : victims) tryDelete(n, 5);
    }

    public void setKeep(String name, boolean keep) {
        if (name == null) return;
        synchronized (lock) {
            JSONObject m = ensureMetaLocked(name);
            try { m.put("keep", keep || DEFAULT.equals(name)); } catch (Exception ignored) {}
            saveLocked();
        }
    }

    public void rename(String name, String label) {
        if (name == null || label == null) return;
        String l = label.trim();
        if (l.isEmpty()) return;
        synchronized (lock) {
            JSONObject m = ensureMetaLocked(name);
            try { m.put("label", l); } catch (Exception ignored) {}
            saveLocked();
        }
    }

    /** [{name,label,color,keep,createdAt,tabs:0}]，tabs 由调用方填。Default 在前，其余按编号。 */
    public JSONArray list() {
        synchronized (lock) {
            List<String> names = new ArrayList<>(meta.keySet());
            Collections.sort(names, (a, b) -> {
                if (a.equals(b)) return 0;
                if (DEFAULT.equals(a)) return -1;
                if (DEFAULT.equals(b)) return 1;
                int na = autoNum(a), nb = autoNum(b);
                if (na >= 0 && nb >= 0) return Integer.compare(na, nb);
                if (na >= 0) return -1;
                if (nb >= 0) return 1;
                return a.compareTo(b);
            });
            JSONArray out = new JSONArray();
            for (String n : names) out.put(metaCopyLocked(n));
            return out;
        }
    }

    /** {name,label,color,keep,createdAt,tabs:0}；不存在时返回 null。 */
    public JSONObject meta(String name) {
        if (name == null) return null;
        synchronized (lock) {
            return meta.containsKey(name) ? metaCopyLocked(name) : null;
        }
    }

    // ------------------------------------------------------------------ 内部

    private JSONObject metaCopyLocked(String n) {
        JSONObject m = meta.get(n);
        JSONObject o = new JSONObject();
        try {
            o.put("name", n);
            o.put("label", m.optString("label", labelFor(n)));
            o.put("color", m.optString("color", colorFor(n)));
            o.put("keep", DEFAULT.equals(n) || m.optBoolean("keep", false));
            o.put("createdAt", m.optLong("createdAt", 0));
            o.put("tabs", 0);
        } catch (Exception ignored) {}
        return o;
    }

    private JSONObject ensureMetaLocked(String name) {
        JSONObject m = meta.get(name);
        if (m == null) {
            noteName(name);
            m = makeMeta(labelFor(name), colorFor(name), DEFAULT.equals(name));
            meta.put(name, m);
        }
        return m;
    }

    private int refCount(String n) {
        Integer c = refs.get(n);
        return c == null ? 0 : Math.max(0, c);
    }

    private static JSONObject makeMeta(String label, String color, boolean keep) {
        JSONObject m = new JSONObject();
        try {
            m.put("label", label);
            m.put("color", color);
            m.put("createdAt", System.currentTimeMillis());
            m.put("keep", keep);
        } catch (Exception ignored) {}
        return m;
    }

    private void noteName(String n) {
        int v = autoNum(n);
        if (v > maxSeen) maxSeen = v;
    }

    private static int autoNum(String n) {
        if (n == null) return -1;
        Matcher m = AUTO_NAME.matcher(n);
        if (!m.matches()) return -1;
        try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException e) { return -1; }
    }

    private static String labelFor(String n) {
        if (DEFAULT.equals(n)) return "共享容器";
        int v = autoNum(n);
        return v >= 0 ? "容器 " + v : n;
    }

    private static String colorFor(String n) {
        if (DEFAULT.equals(n)) return "#9E9E9E";
        int v = autoNum(n);
        int i = v > 0 ? (v - 1) : Math.abs(n.hashCode());
        return COLORS[i % COLORS.length];
    }

    /** 持有 lock 时调用：拍快照后在 IO 线程原子写入。 */
    private void saveLocked() {
        JSONObject snap = new JSONObject();
        try {
            for (Map.Entry<String, JSONObject> e : meta.entrySet()) {
                snap.put(e.getKey(), new JSONObject(e.getValue().toString()));
            }
        } catch (Exception ignored) {}
        final String text;
        try { text = snap.toString(2); } catch (Exception e) { return; }
        io.execute(() -> {
            try {
                File dir = metaFile.getParentFile();
                if (dir != null && !dir.isDirectory()) //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                File tmp = new File(dir, ".containers.json.tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                    out.getFD().sync();
                }
                if (!tmp.renameTo(metaFile)) {
                    //noinspection ResultOfMethodCallIgnored
                    metaFile.delete();
                    //noinspection ResultOfMethodCallIgnored
                    tmp.renameTo(metaFile);
                }
            } catch (Throwable t) {
                Log.w(TAG, "写入 containers.json 失败", t);
            }
        });
    }
}
