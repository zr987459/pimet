package com.xm486.pimet.translation;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 译文缓存：内存 LRU（3000 条）+ 磁盘 SQLite（app 私有目录，上限 20MB）。
 * key 只看 目标语言 + 原文（与引擎无关），任何引擎译过的都能复用。磁盘读写只能在后台线程调用。
 */
public final class TranslationCache {

    private static final String TAG = "TranslationCache";
    public static final String TARGET = "zh-CN";
    private static final int MEM_MAX = 3000;
    private static final long DISK_MAX_BYTES = 20L * 1024 * 1024;
    private static final int CHECK_EVERY = 300;

    private static final Map<String, String> MEM = new LinkedHashMap<String, String>(512, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MEM_MAX;
        }
    };

    private static Helper sHelper;
    private static int sWritesSinceCheck;

    private TranslationCache() {}

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context c) {
            super(c, "pimet_translation_cache.db", null, 1);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS t (k TEXT PRIMARY KEY, v TEXT NOT NULL, at INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS t_at ON t(at)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
            db.execSQL("DROP TABLE IF EXISTS t");
            onCreate(db);
        }
    }

    private static synchronized SQLiteDatabase db(Context context) {
        try {
            if (sHelper == null) {
                Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
                sHelper = new Helper(app);
            }
            return sHelper.getWritableDatabase();
        } catch (Throwable t) {
            Log.w(TAG, "open cache db failed: " + t.getMessage());
            return null;
        }
    }

    static String key(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest((TARGET + "\u0001" + text).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(40);
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Throwable t) {
            return TARGET + "\u0001" + text;
        }
    }

    /** 批量查：返回 原文下标 → 译文（只含命中的）。后台线程调用。 */
    public static Map<Integer, String> lookup(Context context, List<String> texts) {
        Map<Integer, String> hits = new HashMap<>();
        Map<String, Integer> missKeys = new LinkedHashMap<>();
        synchronized (MEM) {
            for (int i = 0; i < texts.size(); i++) {
                String t = texts.get(i);
                if (t == null) continue;
                String v = MEM.get(TARGET + "\u0001" + t);
                if (v != null) hits.put(i, v);
                else missKeys.put(key(t), i);
            }
        }
        if (missKeys.isEmpty() || context == null) return hits;
        SQLiteDatabase db = db(context);
        if (db == null) return hits;
        try {
            String[] keys = missKeys.keySet().toArray(new String[0]);
            for (int from = 0; from < keys.length; from += 200) {
                int to = Math.min(keys.length, from + 200);
                StringBuilder q = new StringBuilder("SELECT k, v FROM t WHERE k IN (");
                String[] args = new String[to - from];
                for (int i = from; i < to; i++) {
                    q.append(i == from ? "?" : ",?");
                    args[i - from] = keys[i];
                }
                q.append(")");
                try (Cursor c = db.rawQuery(q.toString(), args)) {
                    while (c.moveToNext()) {
                        Integer idx = missKeys.get(c.getString(0));
                        if (idx == null) continue;
                        String v = c.getString(1);
                        hits.put(idx, v);
                        synchronized (MEM) {
                            MEM.put(TARGET + "\u0001" + texts.get(idx), v);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "cache lookup failed: " + t.getMessage());
        }
        return hits;
    }

    /** 写入一批译文（原文 → 译文，跳过与原文相同的）。后台线程调用。 */
    public static void put(Context context, List<String> src, List<String> dst) {
        if (src == null || dst == null) return;
        Map<String, String> fresh = new LinkedHashMap<>();
        synchronized (MEM) {
            for (int i = 0; i < src.size() && i < dst.size(); i++) {
                String s = src.get(i), d = dst.get(i);
                if (s == null || d == null || d.trim().isEmpty() || d.equals(s)) continue;
                MEM.put(TARGET + "\u0001" + s, d);
                fresh.put(s, d);
            }
        }
        if (fresh.isEmpty() || context == null) return;
        SQLiteDatabase db = db(context);
        if (db == null) return;
        try {
            long now = System.currentTimeMillis();
            db.beginTransaction();
            try {
                for (Map.Entry<String, String> e : fresh.entrySet()) {
                    ContentValues cv = new ContentValues();
                    cv.put("k", key(e.getKey()));
                    cv.put("v", e.getValue());
                    cv.put("at", now);
                    db.insertWithOnConflict("t", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            boolean check;
            synchronized (TranslationCache.class) {
                sWritesSinceCheck += fresh.size();
                check = sWritesSinceCheck >= CHECK_EVERY;
                if (check) sWritesSinceCheck = 0;
            }
            if (check) trim(db);
        } catch (Throwable t) {
            Log.w(TAG, "cache write failed: " + t.getMessage());
        }
    }

    /** 超过 20MB：删掉最旧的四分之一，再回收空间 */
    private static void trim(SQLiteDatabase db) {
        long used = usedBytes(db);
        if (used <= DISK_MAX_BYTES) return;
        long rows = 0;
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM t", null)) {
            if (c.moveToFirst()) rows = c.getLong(0);
        }
        long drop = Math.max(1, rows / 4);
        db.execSQL("DELETE FROM t WHERE k IN (SELECT k FROM t ORDER BY at ASC LIMIT " + drop + ")");
        try {
            db.execSQL("VACUUM");
        } catch (Throwable ignored) {}
    }

    private static long usedBytes(SQLiteDatabase db) {
        long pages = pragma(db, "page_count"), free = pragma(db, "freelist_count"), size = pragma(db, "page_size");
        return (pages - free) * size;
    }

    private static long pragma(SQLiteDatabase db, String name) {
        try (Cursor c = db.rawQuery("PRAGMA " + name, null)) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 清空内存与磁盘缓存。后台线程调用。 */
    public static void clear(Context context) {
        synchronized (MEM) {
            MEM.clear();
        }
        SQLiteDatabase db = db(context);
        if (db == null) return;
        try {
            db.execSQL("DELETE FROM t");
            db.execSQL("VACUUM");
        } catch (Throwable ignored) {}
    }

    /** 磁盘缓存占用（字节）。后台线程调用。 */
    public static long diskBytes(Context context) {
        SQLiteDatabase db = db(context);
        return db == null ? 0 : usedBytes(db);
    }
}
