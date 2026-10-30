package com.xm486.pimet.ui;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 浏览历史：appsDir/history.jsonl，每行 {"url","title","container","t"}，追加写，最多保留 5000 条。
 *
 * 内存列表为准（旧→新），文件读写全部在单线程 executor 上执行；公开方法线程安全，可在主线程调用
 * （首次查询若历史还没加载完，最多等 2 秒）。
 */
public final class HistoryStore {

    private static final String TAG = "HistoryStore";
    private static final int MAX = 5000;
    private static final int COMPACT_AT = 6000;      // 文件行数超过它就整体重写
    private static final long DEDUP_MS = 30_000L;

    private static final class Entry {
        String url, title, container;
        long t;
    }

    private final File file;
    private final Object lock = new Object();
    private final List<Entry> items = new ArrayList<>();   // 旧 → 新
    private final CountDownLatch loaded = new CountDownLatch(1);
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pimet-history");
        t.setDaemon(true);
        return t;
    });
    private int fileLines;
    private boolean rewritePending;

    public HistoryStore(File appsDir) {
        this.file = new File(appsDir, "history.jsonl");
        io.execute(this::load);
    }

    /** 同一 URL 30 秒内重复只更新标题（和容器）；about:/data:/javascript:/blob: 不记。 */
    public void add(String url, String title, String container) {
        if (url == null) return;
        String u = url.trim();
        if (u.isEmpty()) return;
        String lower = u.toLowerCase(Locale.ROOT);
        if (lower.startsWith("about:") || lower.startsWith("data:") || lower.startsWith("javascript:")
                || lower.startsWith("blob:") || lower.startsWith("chrome:") || lower.startsWith("view-source:")) return;
        final long now = System.currentTimeMillis();
        final String ti = title == null ? "" : title.trim();
        final String c = container == null ? "" : container;
        io.execute(() -> {
            synchronized (lock) {
                Entry dup = null;
                for (int i = items.size() - 1; i >= 0; i--) {
                    Entry e = items.get(i);
                    if (now - e.t > DEDUP_MS) break;
                    if (e.url.equals(u)) { dup = e; break; }
                }
                if (dup != null) {
                    boolean changed = false;
                    if (!ti.isEmpty() && !ti.equals(dup.title)) { dup.title = ti; changed = true; }
                    if (!c.isEmpty() && !c.equals(dup.container)) { dup.container = c; changed = true; }
                    if (changed) scheduleRewriteLocked();
                    return;
                }
                Entry e = new Entry();
                e.url = u;
                e.title = ti;
                e.container = c;
                e.t = now;
                items.add(e);
                boolean trimmed = trimLocked();
                if (trimmed || fileLines + 1 > COMPACT_AT || rewritePending) {
                    scheduleRewriteLocked();
                } else {
                    appendLine(e);
                }
            }
        });
    }

    /** 新的在前 [{url,title,container,t}]。 */
    public JSONArray recent(int limit, int offset) {
        awaitLoaded();
        JSONArray out = new JSONArray();
        int lim = limit <= 0 ? 100 : limit;
        int off = Math.max(0, offset);
        synchronized (lock) {
            for (int i = items.size() - 1 - off; i >= 0 && out.length() < lim; i--) out.put(toJson(items.get(i)));
        }
        return out;
    }

    /** url/title 不区分大小写包含；q 为空时等于 recent(limit, 0)。新的在前。 */
    public JSONArray search(String q, int limit) {
        if (q == null || q.trim().isEmpty()) return recent(limit, 0);
        awaitLoaded();
        String k = q.trim().toLowerCase(Locale.ROOT);
        int lim = limit <= 0 ? 100 : limit;
        JSONArray out = new JSONArray();
        synchronized (lock) {
            for (int i = items.size() - 1; i >= 0 && out.length() < lim; i--) {
                Entry e = items.get(i);
                if (e.url.toLowerCase(Locale.ROOT).contains(k)
                        || (e.title != null && e.title.toLowerCase(Locale.ROOT).contains(k))) out.put(toJson(e));
            }
        }
        return out;
    }

    /** 删除 URL 等于 urlOrPrefix 或以它开头的记录，返回删除条数。 */
    public int delete(String urlOrPrefix) {
        if (urlOrPrefix == null || urlOrPrefix.isEmpty()) return 0;
        awaitLoaded();
        int n = 0;
        synchronized (lock) {
            for (int i = items.size() - 1; i >= 0; i--) {
                if (items.get(i).url.startsWith(urlOrPrefix)) { items.remove(i); n++; }
            }
            if (n > 0) scheduleRewriteLocked();
        }
        return n;
    }

    public void clear() {
        awaitLoaded();
        synchronized (lock) {
            items.clear();
            scheduleRewriteLocked();
        }
    }

    /**
     * 删除 t &gt;= ms 的记录。ms 是时间戳（毫秒，例：System.currentTimeMillis() - 3600000 = 最近一小时）；
     * 兼容传“时长”：ms 小于 1e11（约 1973 年之前）时按“最近 ms 毫秒”处理。
     */
    public void clearSince(long ms) {
        long since = ms < 100_000_000_000L ? System.currentTimeMillis() - Math.max(0, ms) : ms;
        awaitLoaded();
        synchronized (lock) {
            boolean changed = false;
            for (int i = items.size() - 1; i >= 0; i--) {
                if (items.get(i).t >= since) { items.remove(i); changed = true; }
            }
            if (changed) scheduleRewriteLocked();
        }
    }

    // ------------------------------------------------------------------ 内部

    private void awaitLoaded() {
        try { loaded.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static JSONObject toJson(Entry e) {
        JSONObject o = new JSONObject();
        try {
            o.put("url", e.url);
            o.put("title", e.title == null ? "" : e.title);
            o.put("container", e.container == null ? "" : e.container);
            o.put("t", e.t);
        } catch (Exception ignored) {}
        return o;
    }

    /** IO 线程：读文件，合并 30 秒内同 URL 的重复行（后写的标题覆盖），裁到 5000 条。 */
    private void load() {
        List<Entry> list = new ArrayList<>();
        int lines = 0;
        try {
            if (file.isFile()) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        lines++;
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        try {
                            JSONObject o = new JSONObject(line);
                            Entry e = new Entry();
                            e.url = o.optString("url", "");
                            if (e.url.isEmpty()) continue;
                            e.title = o.optString("title", "");
                            e.container = o.optString("container", "");
                            e.t = o.optLong("t", 0);
                            Entry dup = null;
                            for (int i = list.size() - 1; i >= 0; i--) {
                                Entry p = list.get(i);
                                if (Math.abs(e.t - p.t) > DEDUP_MS) break;
                                if (p.url.equals(e.url)) { dup = p; break; }
                            }
                            if (dup != null) {
                                if (!e.title.isEmpty()) dup.title = e.title;
                                if (!e.container.isEmpty()) dup.container = e.container;
                            } else {
                                list.add(e);
                            }
                        } catch (Exception ignored) {
                            // 坏行跳过
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "读取 history.jsonl 失败", t);
        }
        synchronized (lock) {
            // 加载期间若已有 add（理论上不会，add 也在同一线程排队），合并到末尾
            list.addAll(items);
            items.clear();
            items.addAll(list);
            fileLines = lines;
            boolean trimmed = trimLocked();
            if (trimmed || lines > items.size() + 200) rewriteNowLocked();
        }
        loaded.countDown();
    }

    private boolean trimLocked() {
        int extra = items.size() - MAX;
        if (extra <= 0) return false;
        items.subList(0, extra).clear();
        return true;
    }

    /** 合并多次改动：排一次整体重写。 */
    private void scheduleRewriteLocked() {
        if (rewritePending) return;
        rewritePending = true;
        io.execute(() -> {
            synchronized (lock) {
                rewritePending = false;
                rewriteNowLocked();
            }
        });
    }

    /** IO 线程、持有 lock：原子重写整个文件。 */
    private void rewriteNowLocked() {
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory()) //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            File tmp = new File(dir, ".history.jsonl.tmp");
            try (FileOutputStream fo = new FileOutputStream(tmp);
                 Writer w = new OutputStreamWriter(fo, StandardCharsets.UTF_8)) {
                for (Entry e : items) {
                    w.write(toJson(e).toString());
                    w.write('\n');
                }
                w.flush();
                fo.getFD().sync();
            }
            if (!tmp.renameTo(file)) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(file);
            }
            fileLines = items.size();
        } catch (Throwable t) {
            Log.w(TAG, "写入 history.jsonl 失败", t);
        }
    }

    /** IO 线程、持有 lock：追加一行。 */
    private void appendLine(Entry e) {
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory()) //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            try (Writer w = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
                w.write(toJson(e).toString());
                w.write('\n');
            }
            fileLines++;
        } catch (Throwable t) {
            Log.w(TAG, "追加 history.jsonl 失败", t);
        }
    }
}
