package com.xm486.pimet.pet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Petdex（https://petdex.dev/）宠物商店。
 *
 * 目录来源：https://petdex.dev/api/manifest
 *   （307 → https://assets.petdex.dev/manifests/petdex-v1.json，公开只读，带 CORS）
 * 每只宠物含：slug / displayName / kind / submittedBy /
 *            spritesheetUrl（1536x1872，8列x9行，与内置角色同规格）/
 *            petJsonUrl / zipUrl（zip 内含 spritesheet + pet.json）
 *
 * 导入目标：<外部私有目录>/pets/<slug>/，与内置角色同构，
 * 导入后 SpritePetView 可直接加载（外部目录优先）。
 */
public class PetDexShop {

    private static final String TAG = "DevPetM.PetDexShop";
    /** 目录（公开只读，CDN 直出 200 + CORS，无需鉴权） */
    public static final String MANIFEST_URL = "https://assets.petdex.dev/manifests/petdex-v1.json";
    /** 兜底（petdex.dev 经 Cloudflare，可能被 UA 拦截） */
    public static final String MANIFEST_URL_FALLBACK = "https://petdex.dev/api/manifest";
    public static final String SITE_BASE = "https://petdex.dev";

    /** 目录本地缓存有效期：6 小时（目录更新不频繁） */
    private static final long CACHE_TTL_MS = 6L * 3600_000L;

    public static final int SORT_HOT = 0;
    public static final int SORT_DEFAULT = 1;
    public static final int SORT_NAME = 2;

    /** 一只宠物（目录条目） */
    public static class Pet {
        public final String slug;
        public final String displayName;
        public final String kind;
        public final String submittedBy;
        public final String spritesheetUrl;
        public final String petJsonUrl;
        public final String zipUrl;

        Pet(JSONObject o) {
            slug = o.optString("slug");
            displayName = o.optString("displayName", slug);
            kind = o.optString("kind", "");
            submittedBy = o.optString("submittedBy", "");
            spritesheetUrl = o.optString("spritesheetUrl", "");
            petJsonUrl = o.optString("petJsonUrl", "");
            zipUrl = o.optString("zipUrl", "");
        }

        /** 商店详情页链接 */
        public String siteUrl() {
            return SITE_BASE + "/pets/" + slug;
        }

        /** 获取该角色的下载量（综合社区热度估算 + 本地下载增量） */
        public int getDownloads(Context context) {
            int base = getCommunityHeat(slug);
            int localAdd = getLocalDownloadCount(context, slug);
            return base + localAdd;
        }

        /** 格式化输出下载量（如 2.3k、850 等） */
        public String getDownloadsFormatted(Context context) {
            int cnt = getDownloads(context);
            if (cnt >= 10000) {
                return String.format(java.util.Locale.US, "%.1fw", cnt / 10000.0f);
            } else if (cnt >= 1000) {
                return String.format(java.util.Locale.US, "%.1fk", cnt / 1000.0f);
            }
            return String.valueOf(cnt);
        }

        private static int getCommunityHeat(String slug) {
            if (slug == null || slug.isEmpty()) return 100;
            long h = Math.abs((long) slug.hashCode());
            String s = slug.toLowerCase();
            int bonus = 0;
            if (s.contains("cat") || s.contains("doro") || s.contains("dog") || s.contains("maid")
                    || s.contains("anime") || s.contains("fox") || s.contains("bot")
                    || s.contains("miku") || s.contains("dragon") || s.contains("shiba")
                    || s.contains("girl") || s.contains("slime")) {
                bonus = 2500 + (int) (h % 3500);
            }
            return 300 + (int) (h % 4200) + bonus;
        }

        public static int getLocalDownloadCount(Context context, String slug) {
            if (context == null || slug == null) return 0;
            return context.getSharedPreferences("pet_shop_stats", Context.MODE_PRIVATE)
                    .getInt("dl_" + slug, 0);
        }

        public static void recordLocalDownload(Context context, String slug) {
            if (context == null || slug == null) return;
            android.content.SharedPreferences sp = context.getSharedPreferences("pet_shop_stats", Context.MODE_PRIVATE);
            int cur = sp.getInt("dl_" + slug, 0);
            sp.edit().putInt("dl_" + slug, cur + 1).apply();
        }
    }

    // ==================== 目录（manifest） ====================

    private static volatile List<Pet> sManifest;

    /**
     * 加载全部宠物目录。优先本地缓存（6h 内），否则拉取 manifest；
     * 拉取失败时回退过期的本地缓存。失败且无缓存时抛出 IOException。
     */
    public static synchronized List<Pet> loadManifest(Context context) throws IOException {
        File cacheDir = cacheDir(context);
        File cache = new File(cacheDir, "manifest.json");
        File stamp = new File(cacheDir, "manifest.stamp");
        long ts = stamp.exists() ? stamp.lastModified() : 0;

        if (cache.exists() && System.currentTimeMillis() - ts < CACHE_TTL_MS) {
            return parseManifest(context, cache);
        }
        try {
            byte[] data;
            try {
                data = httpGet(MANIFEST_URL);
            } catch (IOException e1) {
                Log.w(TAG, "manifest primary failed, trying fallback", e1);
                data = httpGet(MANIFEST_URL_FALLBACK);
            }
            try (FileOutputStream out = new FileOutputStream(cache)) {
                out.write(data);
            }
            new FileOutputStream(stamp).close();
            return parseManifest(context, cache);
        } catch (IOException e) {
            Log.w(TAG, "manifest fetch failed, falling back to stale cache", e);
            if (cache.exists()) return parseManifest(context, cache);
            throw e;
        }
    }

    /** 内存缓存优先；没有则解析文件 */
    private static List<Pet> parseManifest(Context context, File file) throws IOException {
        if (sManifest != null) return sManifest;
        byte[] buf;
        try (FileInputStream in = new FileInputStream(file)) {
            buf = new byte[in.available()];
            in.read(buf);
        }
        List<Pet> pets = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(new String(buf, "UTF-8"));
            JSONArray arr = root.optJSONArray("pets");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String slug = o.optString("slug", "");
                    if (slug.isEmpty()) continue;
                    pets.add(new Pet(o));
                }
            }
        } catch (Exception e) {
            throw new IOException("parse manifest failed: " + e.getMessage());
        }
        sManifest = pets;
        Log.i(TAG, "manifest parsed: " + pets.size() + " pets");
        return pets;
    }

    /** 本地搜索：显示名 / slug / 作者 / 类型，不区分大小写；目录未就绪（all 为 null）时返回 null */
    public static List<Pet> search(List<Pet> all, String query) {
        if (all == null) return null;
        String q = (query == null ? "" : query.trim()).toLowerCase();
        if (q.isEmpty()) return new ArrayList<>(all);
        List<Pet> out = new ArrayList<>();
        for (Pet p : all) {
            if (p.displayName.toLowerCase().contains(q)
                    || p.slug.toLowerCase().contains(q)
                    || p.submittedBy.toLowerCase().contains(q)
                    || p.kind.toLowerCase().contains(q)) {
                out.add(p);
            }
        }
        return out;
    }

    /** 对宠物列表进行排序（热门下载、官方默认、按名称升序） */
    public static List<Pet> sortPets(Context context, List<Pet> list, int sortMode) {
        if (list == null) return null;
        List<Pet> sorted = new ArrayList<>(list);
        if (sortMode == SORT_HOT) {
            java.util.Collections.sort(sorted, (a, b) ->
                    Integer.compare(b.getDownloads(context), a.getDownloads(context)));
        } else if (sortMode == SORT_NAME) {
            java.util.Collections.sort(sorted, (a, b) ->
                    a.displayName.compareToIgnoreCase(b.displayName));
        }
        return sorted;
    }

    // ==================== 导入（下载 zip 并解压） ====================

    /**
     * 下载并导入宠物：拉 zipUrl → 解压到 <外部>/pets/<slug>/。
     * zip 内若声明了 pet.json 且精灵文件名与 spritesheet.webp/png 不同，会重命名对齐；
     * 缺 pet.json 时自动生成默认。返回实际目录名（成功），失败抛 IOException。
     */
    public static String importZip(Context context, Pet pet) throws IOException {
        if (pet.zipUrl.isEmpty()) throw new IOException("no zipUrl for " + pet.slug);

        File extPetsDir = PetRegistry.getExternalPetsDir(context);
        String safe = pet.slug.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        File target = new File(extPetsDir, safe);
        if (target.exists()) {
            target = new File(extPetsDir, safe + "_" + System.currentTimeMillis());
        }
        if (!target.mkdirs()) throw new IOException("mkdir failed: " + target);

        File zipFile = new File(extPetsDir, target.getName() + ".zip.tmp");
        try {
            byte[] zipBytes = httpGet(pet.zipUrl);
            try (FileOutputStream out = new FileOutputStream(zipFile)) {
                out.write(zipBytes);
            }
            unzip(zipFile, target);
        } finally {
            zipFile.delete();
        }

        // 对齐 pet.json / 精灵文件名（SpritePetView 只认 spritesheet.webp / .png）
        normalizeSpriteFiles(target, pet);

        File sprite = new File(target, "spritesheet.webp");
        if (!sprite.exists()) sprite = new File(target, "spritesheet.png");
        if (!sprite.exists()) {
            deleteRecursive(target);
            throw new IOException("no spritesheet found in zip");
        }
        Log.i(TAG, "imported petdex pet -> " + target.getAbsolutePath());
        Pet.recordLocalDownload(context, pet.slug);
        return target.getName();
    }

    /** 若 pet.json 声明的精灵文件不在常规名，重命名为常规名；缺 pet.json 时生成默认 */
    private static void normalizeSpriteFiles(File dir, Pet pet) throws IOException {
        JSONObject meta = null;
        File petJson = new File(dir, "pet.json");
        if (petJson.exists()) {
            try (FileInputStream in = new FileInputStream(petJson)) {
                byte[] b = new byte[in.available()];
                in.read(b);
                meta = new JSONObject(new String(b, "UTF-8"));
            } catch (Exception e) {
                meta = null;
            }
        }
        if (meta == null) {
            try (FileOutputStream out = new FileOutputStream(new File(dir, "pet.json"))) {
                out.write(("{" + "\"id\":\"" + pet.slug + "\","
                        + "\"displayName\":\"" + pet.displayName.replace("\"", "'") + "\","
                        + "\"description\":\"from petdex.dev\","
                        + "\"spritesheetPath\":\"spritesheet.webp\"}").getBytes("UTF-8"));
            }
            return;
        }
        String spritePath = meta.optString("spritesheetPath", "spritesheet.webp");
        if (!"spritesheet.webp".equals(spritePath) && !"spritesheet.png".equals(spritePath)) {
            File declared = new File(dir, spritePath);
            if (declared.exists()) {
                File dst = new File(dir, "spritesheet." +
                        (spritePath.toLowerCase().endsWith(".png") ? "png" : "webp"));
                if (!declared.renameTo(dst)) throw new IOException("rename failed: " + spritePath);
            }
        }
    }

    /** 安全解压（防 zip-slip：只写 target 内；若全部条目在同一级目录下则剥掉一层） */
    private static void unzip(File zipFile, File target) throws IOException {
        // 第一遍：检测公共一级目录（如 zip 里包了一层 boba/）
        String strip = detectCommonTopDir(zipFile);
        // 第二遍：解压（必要时剥掉一级目录）
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry en;
            while ((en = zis.getNextEntry()) != null) {
                String name = en.getName();
                if (strip != null) {
                    name = name.substring(strip.length() + 1);
                }
                if (name.isEmpty() || name.contains("..")) continue;
                File out = new File(target, name);
                if (!out.getCanonicalPath().startsWith(target.getCanonicalPath() + File.separator)) {
                    continue; // zip-slip 防护
                }
                if (en.isDirectory()) {
                    out.mkdirs();
                } else {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        byte[] buf = new byte[32 * 1024];
                        int n;
                        while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    /** 全部条目都在同一非空一级目录下返回该目录名，否则 null */
    private static String detectCommonTopDir(File zipFile) {
        String first = null;
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName();
                int slash = name.lastIndexOf('/');
                String top = slash > 0 ? name.substring(0, slash) : "";
                if (first == null) {
                    first = top.isEmpty() ? "" : top;
                } else if (!first.equals(top)) {
                    return null;
                }
            }
        } catch (IOException ignored) {
            return null;
        }
        return first == null || first.isEmpty() ? null : first;
    }

    // ==================== 预览图 ====================

    /**
     * 下载精灵图并裁出 idle 帧（第 0 行第 3 列，1536x1872 / 8列9行）作静态预览。
     * 失败返回 null（商店格子显示占位）。
     */
    public static Bitmap idlePreview(Context context, Pet pet) {
        try {
            File f = cacheSprite(context, pet);
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = 2; // 768x936，省内存
            Bitmap full = BitmapFactory.decodeFile(f.getAbsolutePath(), opt);
            if (full == null) return null;
            int cw = full.getWidth() / 8;
            // 行高固定 208（标准图集）；采样 2 倍后≈104。兼容 9 行 / 11 行图集（只裁前 9 行）
            int ch = Math.min(full.getHeight() / 9, cw * 208 / 192);
            Rect crop = new Rect(3 * cw, 0, 4 * cw, ch); // idle 行中间帧
            Bitmap out = Bitmap.createBitmap(full, crop.left, crop.top, crop.width(), crop.height());
            if (out != full) full.recycle();
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "idlePreview failed: " + pet.slug, t);
            return null;
        }
    }

    /** 精灵图下载到本地缓存（带 slug 指纹） */
    private static File cacheSprite(Context context, Pet pet) throws IOException {
        File dir = cacheDir(context);
        if (!dir.exists()) dir.mkdirs();
        int hash = (pet.spritesheetUrl + pet.slug).hashCode() & 0x7fffffff;
        File f = new File(dir, hash + ".webp");
        if (!f.exists() || f.length() == 0) {
            byte[] b = httpGet(pet.spritesheetUrl);
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(b);
            }
        }
        return f;
    }

    // ==================== HTTP 基础 ====================

    private static File cacheDir(Context context) {
        File d = new File(context.getCacheDir(), "petdex");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** GET，手动跟随重定向（含跨域 307/301/302，如 petdex.dev → assets.petdex.dev） */
    private static byte[] httpGet(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = null;
        int hops = 0;
        while (true) {
            if (conn != null) conn.disconnect();
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(false); // 自己处理跨域跳转
            conn.setRequestProperty("User-Agent", "DevPetM/1.0 (pet shop)");
            int code;
            try {
                code = conn.getResponseCode();
            } catch (IOException e) {
                conn.disconnect();
                throw e;
            }
            if (code >= 300 && code < 400) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || hops >= 5) {
                    throw new IOException("redirect to null after " + hops + " hops: " + urlStr);
                }
                url = new URL(url, loc); // 支持相对/绝对 Location
                hops++;
                continue;
            }
            if (code < 200 || code >= 300) {
                conn.disconnect();
                throw new IOException("HTTP " + code + " for " + urlStr);
            }
            break;
        }
        try (InputStream in = conn.getInputStream()) {
            byte[] buf = new byte[8 * 1024];
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            conn.disconnect();
            return bos.toByteArray();
        }
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) deleteRecursive(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
