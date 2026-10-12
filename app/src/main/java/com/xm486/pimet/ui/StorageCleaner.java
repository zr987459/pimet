package com.xm486.pimet.ui;

import android.content.Context;
import android.os.Environment;

import com.xm486.pimet.pet.PetMemoryManager;
import com.xm486.pimet.pet.PetRegistry;
import com.xm486.pimet.proot.ProotManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 容器存储扫描与清理（后台线程调用）。
 *
 * <p>只在固定的白名单根目录里删东西：容器 rootfs、应用 cache、容器临时目录 files/tmp、导入桌宠目录、
 * 备份导出目录；路径必须是这些根目录的子路径。删除时<b>从不跟随符号链接</b>（链接本身被删除，指向的内容不动），
 * 也跳过 PRoot link2symlink 的 .l2s.* 硬链接实体，避免破坏别处的“硬链接”。</p>
 * <p>体积统计复用 {@link ProotManager#getDirectorySize}（du -sk，与首页「存储占用」、文件浏览器同一算法，
 * 不跟随符号链接）。</p>
 */
public final class StorageCleaner {

    public static final long MIN_SHOW = 1024L * 1024L;          // 小于 1 MB 不提示
    public static final long LARGE_FILE = 100L * 1024L * 1024L; // 大文件阈值
    private static final long DAY = 24L * 3600L * 1000L;
    private static final Pattern ROTATED = Pattern.compile(".*(\\.\\d+|\\.\\d+\\.log|\\.gz|\\.xz|\\.bz2|\\.old|-\\d{8})$");

    /** 一条建议清理项。 */
    public static final class Item {
        public final String key;
        public final String name;
        public String desc;
        public final boolean safe;
        public long size;
        public int count;
        public boolean checked;
        public boolean touchesPiWeb;
        public boolean webViewCache;
        public final List<File> targets = new ArrayList<>();   // 整个删除（不跟随链接）
        public final List<File> truncates = new ArrayList<>(); // 清空内容（运行中的日志）

        Item(String key, String name, String desc, boolean safe) {
            this.key = key;
            this.name = name;
            this.desc = desc;
            this.safe = safe;
            this.checked = safe;
        }
    }

    /** 大文件（仅供参考，不在此处删除）。 */
    public static final class LargeFile {
        public final String path;
        public final long size;

        LargeFile(String path, long size) {
            this.path = path;
            this.size = size;
        }
    }

    public static final class Result {
        public final List<Item> items = new ArrayList<>();
        public final List<LargeFile> largeFiles = new ArrayList<>();
        public long containerSize;
    }

    public interface Progress {
        void onStep(String label, int step, int total);
    }

    private final Context ctx;
    private final File rootfs;
    private final File home;
    private final List<File> roots = new ArrayList<>();

    public StorageCleaner(Context context) {
        this.ctx = context.getApplicationContext();
        this.rootfs = ProotManager.getRootfsDir(ctx);
        this.home = new File(rootfs, "root");
        roots.add(rootfs);
        roots.add(ctx.getCacheDir());
        roots.add(new File(ctx.getFilesDir(), "tmp"));
        try { roots.add(PetRegistry.getExternalPetsDir(ctx)); } catch (Throwable ignored) {}
        roots.addAll(backupDirs());
    }

    // =====================================================================
    // 扫描
    // =====================================================================

    public Result scan(Progress p, AtomicBoolean cancel) {
        Result r = new Result();
        String[] steps = {"npm 缓存", "yarn / pnpm", "pip / node-gyp", "其他缓存", "apt 缓存", "临时文件",
                "安装包残留", "日志", "崩溃转储", "Gradle / Android", "应用缓存", "旧备份", "聊天会话",
                "桌宠", "大文件", "容器总占用"};
        int n = steps.length, i = 0;
        List<Item> all = new ArrayList<>();

        step(p, steps[i], ++i, n);
        Item npm = item("npm", "npm 缓存", "npm 下载缓存，可随时重建；下次安装会重新下载", true);
        addIfExists(npm, new File(home, ".npm/_cacache"));
        all.add(npm);
        if (cancelled(cancel)) return r;

        step(p, steps[i], ++i, n);
        Item yarn = item("yarn", "yarn / pnpm 缓存", "包管理器下载缓存，可自动重建", true);
        addIfExists(yarn, new File(home, ".cache/yarn"));
        addIfExists(yarn, new File(home, ".cache/pnpm"));
        addIfExists(yarn, new File(home, ".yarn/berry/cache"));
        addIfExists(yarn, new File(rootfs, "usr/local/share/.cache/yarn"));
        all.add(yarn);
        Item pnpmStore = item("pnpm_store", "pnpm 全局存储", "项目依赖可能硬链接到这里，删除后需重新 pnpm install", false);
        addIfExists(pnpmStore, new File(home, ".local/share/pnpm/store"));
        addIfExists(pnpmStore, new File(home, ".pnpm-store"));
        all.add(pnpmStore);
        if (cancelled(cancel)) return r;

        step(p, steps[i], ++i, n);
        Item pip = item("pip", "pip 缓存", "Python 包下载缓存，可自动重建", true);
        addIfExists(pip, new File(home, ".cache/pip"));
        all.add(pip);
        Item gyp = item("gyp", "node-gyp 头文件", "原生模块编译用的 Node 头文件，需要时自动重新下载", true);
        addIfExists(gyp, new File(home, ".cache/node-gyp"));
        addIfExists(gyp, new File(home, ".node-gyp"));
        all.add(gyp);
        if (cancelled(cancel)) return r;

        step(p, steps[i], ++i, n);
        Item browsers = item("browsers", "浏览器内核缓存", "Playwright / Puppeteer 下载的浏览器，删除后用到时需重新下载", false);
        addIfExists(browsers, new File(home, ".cache/ms-playwright"));
        addIfExists(browsers, new File(home, ".cache/puppeteer"));
        all.add(browsers);
        Item cache = item("cache", "其他程序缓存", "~/.cache 里的其他缓存，程序会自动重建", true);
        Set<String> skip = new HashSet<>(Arrays.asList("yarn", "pnpm", "pip", "node-gyp", "ms-playwright", "puppeteer"));
        for (File c : list(new File(home, ".cache"))) {
            if (!skip.contains(c.getName())) cache.targets.add(c);
        }
        all.add(cache);
        if (cancelled(cancel)) return r;

        step(p, steps[i], ++i, n);
        Item apt = item("apt", "apt 安装包缓存", "已下载的 .deb 安装包与包索引缓存，可随时删除", true);
        File aptCache = new File(rootfs, "var/cache/apt");
        for (File f : list(new File(aptCache, "archives"))) {
            if (f.isFile() && f.getName().endsWith(".deb")) apt.targets.add(f);
        }
        apt.targets.addAll(list(new File(aptCache, "archives/partial")));
        for (File f : list(aptCache)) {
            if (f.isFile() && f.getName().endsWith(".bin")) apt.targets.add(f);
        }
        all.add(apt);
        Item aptLists = item("apt_lists", "apt 软件源索引", "可删除；下次 apt install 前需先 apt update", true);
        for (File f : list(new File(rootfs, "var/lib/apt/lists"))) {
            String nm = f.getName();
            if (!nm.equals("lock") && !nm.equals("partial")) aptLists.targets.add(f);
        }
        all.add(aptLists);
        if (cancelled(cancel)) return r;

        step(p, steps[i], ++i, n);
        Item tmp = item("tmp", "临时文件", "/tmp 等临时目录里 1 天前的文件；正在使用的不动", true);
        long old = System.currentTimeMillis() - DAY;
        for (File dir : new File[]{new File(rootfs, "tmp"), new File(rootfs, "var/tmp"), new File(ctx.getFilesDir(), "tmp")}) {
            for (File f : list(dir)) {
                if (isInstaller(f)) continue;
                if (f.getName().startsWith(".X11-unix") || f.getName().startsWith(".ICE-unix")) continue;
                if (newestMtime(f, 3) < old) tmp.targets.add(f);
            }
        }
        all.add(tmp);
        if (cancelled(cancel)) return r;

        step(p, steps[i], ++i, n);
        Item inst = item("installer", "安装包残留", "容器系统等已解压完成的下载包，可安全删除", true);
        if (ProotManager.isRootfsInstalled(ctx)) {
            long tenMin = System.currentTimeMillis() - 10 * 60 * 1000L;
            for (File dir : new File[]{new File(ctx.getFilesDir(), "tmp"), new File(rootfs, "tmp")}) {
                for (File f : list(dir)) {
                    if (isInstaller(f) && f.lastModified() < tenMin) inst.targets.add(f);
                }
            }
        }
        all.add(inst);
        if (cancelled(cancel)) return r;

        step(p, steps[i], ++i, n);
        Item logs = item("logs", "日志文件", "轮转旧日志、7 天前的日志与 npm 日志；pi-web 日志会被清空", true);
        long week = System.currentTimeMillis() - 7 * DAY;
        collectLogs(new File(rootfs, "var/log"), logs, week, 0);
        addIfExists(logs, new File(home, ".npm/_logs"));
        File piLog = new File(home, "pi-web.log");
        if (piLog.isFile() && !isLink(piLog) && piLog.length() > 256 * 1024) logs.truncates.add(piLog);
        all.add(logs);
        if (cancelled(cancel)) return r;

        step(p, steps[i], ++i, n);
        Item cores = item("core", "崩溃转储", "程序崩溃时留下的 core 文件，排查完即可删除", true);
        findCores(home, cores, 0, cancel);
        findCores(new File(rootfs, "tmp"), cores, 2, cancel);
        all.add(cores);
        if (cancelled(cancel)) return r;

        step(p, steps[i], ++i, n);
        Item gradle = item("gradle", "Gradle 构建缓存", "下次构建会重新下载依赖", true);
        addIfExists(gradle, new File(home, ".gradle/caches"));
        addIfExists(gradle, new File(home, ".gradle/daemon"));
        all.add(gradle);
        Item gradleDist = item("gradle_dist", "Gradle 发行包", "gradle wrapper 下载的 Gradle 本体，删除后下次构建重新下载", false);
        addIfExists(gradleDist, new File(home, ".gradle/wrapper/dists"));
        all.add(gradleDist);
        Item android = item("android", "Android 构建缓存", "Android 构建工具的缓存，可自动重建", true);
        addIfExists(android, new File(home, ".android/cache"));
        addIfExists(android, new File(home, ".android/build-cache"));
        all.add(android);
        if (cancelled(cancel)) return r;

        step(p, steps[i], ++i, n);
        File cacheDir = ctx.getCacheDir();
        Item export = item("export", "导出临时文件", "导出/分享时生成的临时包，已不再需要", true);
        addIfExists(export, new File(cacheDir, "export"));
        for (File f : list(cacheDir)) {
            if (f.isFile() && f.getName().startsWith("import_temp_")) export.targets.add(f);
        }
        all.add(export);
        Item web = item("webview", "WebView 缓存", "工作台网页缓存，下次打开自动重建", true);
        web.webViewCache = true;
        File wv = new File(cacheDir, "WebView");
        if (wv.exists()) web.size = sizeOf(wv);
        File appWv = new File(ctx.getApplicationInfo().dataDir, "app_webview/Default");
        for (String sub : new String[]{"HTTP Cache", "Cache"}) {
            File d = new File(appWv, sub);
            if (d.exists()) web.size += sizeOf(d);
        }
        all.add(web);
        Item appCache = item("app_cache", "应用缓存", "图片、临时下载等应用缓存，可随时清除", true);
        for (File f : list(cacheDir)) {
            String nm = f.getName();
            if (nm.equals("export") || nm.equals("WebView") || nm.startsWith("import_temp_")) continue;
            appCache.targets.add(f);
        }
        all.add(appCache);
        if (cancelled(cancel)) return r;

        step(p, steps[i], ++i, n);
        Item backups = item("backups", "旧备份包", "以前导出的 PiMet 备份（保留最新一个）", false);
        List<File> bk = new ArrayList<>();
        for (File d : backupDirs()) {
            for (File f : list(d)) {
                String nm = f.getName();
                if (f.isFile() && !isLink(f) && nm.startsWith("PiMet-Backup-") && nm.endsWith(".zip") && !bk.contains(f)) bk.add(f);
            }
        }
        Collections.sort(bk, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int k = 1; k < bk.size(); k++) backups.targets.add(bk.get(k));
        all.add(backups);
        if (cancelled(cancel)) return r;

        step(p, steps[i], ++i, n);
        Item sessions = item("sessions", "旧聊天会话", "30 天前的聊天记录，删除后无法恢复", false);
        sessions.touchesPiWeb = true;
        long month = System.currentTimeMillis() - 30 * DAY;
        collectOldFiles(new File(home, ".pi/agent/sessions"), sessions, month, 0);
        sessions.desc = "30 天前的聊天记录 " + sessions.targets.size() + " 个，删除后无法恢复";
        all.add(sessions);
        if (cancelled(cancel)) return r;

        step(p, steps[i], ++i, n);
        Item mem = item("pet_memory", "桌宠记忆", "桌宠的独立记忆与好感度，删除后从头开始", false);
        try {
            File mf = PetMemoryManager.getMemoryFile(ctx);
            if (mf != null && mf.isFile()) mem.targets.add(mf);
        } catch (Throwable ignored) {}
        all.add(mem);
        scanPets(all);
        if (cancelled(cancel)) return r;

        step(p, steps[i], ++i, n);
        Set<String> covered = new HashSet<>();
        for (Item it : all) for (File f : it.targets) covered.add(f.getAbsolutePath());
        findLargeFiles(r.largeFiles, covered, cancel);
        if (cancelled(cancel)) return r;

        step(p, steps[i], ++i, n);
        for (Item it : all) {
            if (cancelled(cancel)) return r;
            if (!it.webViewCache) {
                // 只保留白名单内、且路径上没有符号链接的目标
                for (java.util.Iterator<File> iter = it.targets.iterator(); iter.hasNext(); ) {
                    if (!isAllowed(iter.next())) iter.remove();
                }
                for (java.util.Iterator<File> iter = it.truncates.iterator(); iter.hasNext(); ) {
                    if (!isAllowed(iter.next())) iter.remove();
                }
                long s = 0;
                for (File f : it.targets) s += sizeOf(f);
                for (File f : it.truncates) s += f.length();
                it.size = s;
                it.count = it.targets.size() + it.truncates.size();
            }
            if (it.size >= MIN_SHOW) r.items.add(it);
        }
        r.containerSize = ProotManager.getDirectorySize(rootfs);
        return r;
    }

    // =====================================================================
    // 清理
    // =====================================================================

    /** 删除选中的项；返回释放的字节数（按扫描时的体积估算）。webView 缓存由调用方在主线程清理。 */
    public long clean(List<Item> items, Progress p, AtomicBoolean cancel) {
        long freed = 0;
        int n = items.size(), i = 0;
        for (Item it : items) {
            if (cancelled(cancel)) break;
            step(p, it.name, ++i, n);
            for (File f : it.targets) {
                if (cancelled(cancel)) break;
                if (isAllowed(f)) deleteNoFollow(f, 0);
            }
            for (File f : it.truncates) {
                if (!isAllowed(f) || isLink(f)) continue;
                try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
                    raf.setLength(0);
                } catch (Throwable ignored) {}
            }
            freed += it.size;
        }
        return freed;
    }

    /** 路径必须位于白名单根目录之内（规范化后比较，不解析符号链接）。 */
    private boolean isAllowed(File f) {
        if (f == null) return false;
        String path = normalize(f.getAbsolutePath());
        if (path == null) return false;
        for (File root : roots) {
            if (root == null) continue;
            String rp = normalize(root.getAbsolutePath());
            if (rp == null || rp.length() < 2) continue;
            if (path.startsWith(rp + "/") && path.length() > rp.length() + 1) {
                // 根目录与目标之间任何一级是符号链接都拒绝（防止经由链接删到外面）
                for (File a = f.getParentFile(); a != null && !rp.equals(normalize(a.getAbsolutePath())); a = a.getParentFile()) {
                    if (isLink(a)) return false;
                }
                // 永远不删除 rootfs 下的关键系统目录本身
                if (root.equals(rootfs)) {
                    String rel = path.substring(rp.length() + 1);
                    if (!rel.contains("/")) return false;
                }
                return true;
            }
        }
        return false;
    }

    private static String normalize(String p) {
        if (p == null) return null;
        String[] parts = p.split("/");
        List<String> out = new ArrayList<>();
        for (String s : parts) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..")) return null; // 不接受含 .. 的路径
            out.add(s);
        }
        return "/" + android.text.TextUtils.join("/", out);
    }

    /** 递归删除，不跟随符号链接；跳过 .l2s.* 硬链接实体。 */
    private static void deleteNoFollow(File f, int depth) {
        if (f == null || depth > 64) return;
        if (f.getName().startsWith(".l2s.")) return;
        if (isLink(f)) {
            //noinspection ResultOfMethodCallIgnored
            f.delete(); // 只删除链接本身
            return;
        }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteNoFollow(k, depth + 1);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // =====================================================================
    // 工具
    // =====================================================================

    private static Item item(String key, String name, String desc, boolean safe) {
        return new Item(key, name, desc, safe);
    }

    private static void step(Progress p, String label, int i, int n) {
        if (p != null) p.onStep(label, i, n);
    }

    private static boolean cancelled(AtomicBoolean c) {
        return c != null && c.get();
    }

    private static void addIfExists(Item it, File f) {
        if (f.exists() && !isLink(f)) it.targets.add(f);
    }

    private static List<File> list(File dir) {
        List<File> out = new ArrayList<>();
        if (dir == null || isLink(dir) || !dir.isDirectory()) return out;
        File[] kids = dir.listFiles();
        if (kids != null) out.addAll(Arrays.asList(kids));
        return out;
    }

    static boolean isLink(File f) {
        return ProotManager.isSymbolicLink(f);
    }

    private static long sizeOf(File f) {
        if (f == null || isLink(f) || !f.exists()) return 0;
        if (f.isDirectory()) return ProotManager.getDirectorySize(f);
        return f.length();
    }

    private static boolean isInstaller(File f) {
        if (!f.isFile() || isLink(f)) return false;
        String nm = f.getName().toLowerCase(Locale.US);
        return nm.endsWith(".tar.gz") || nm.endsWith(".tgz") || nm.endsWith(".tar.xz") || nm.endsWith(".tar.zst");
    }

    /** 文件或目录（限深）中最新的修改时间，用于判断临时文件是否仍在使用。 */
    private static long newestMtime(File f, int depth) {
        long m = f.lastModified();
        if (depth <= 0 || isLink(f) || !f.isDirectory()) return m;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) m = Math.max(m, newestMtime(k, depth - 1));
        }
        return m;
    }

    private static void collectLogs(File dir, Item it, long before, int depth) {
        if (depth > 3) return;
        for (File f : list(dir)) {
            if (isLink(f)) continue;
            if (f.isDirectory()) {
                collectLogs(f, it, before, depth + 1);
            } else if (f.isFile()) {
                String nm = f.getName();
                boolean rotated = ROTATED.matcher(nm).matches();
                boolean oldLog = nm.endsWith(".log") && f.lastModified() < before;
                if (rotated || oldLog) it.targets.add(f);
            }
        }
    }

    private static void collectOldFiles(File dir, Item it, long before, int depth) {
        if (depth > 4) return;
        for (File f : list(dir)) {
            if (isLink(f)) continue;
            if (f.isDirectory()) {
                collectOldFiles(f, it, before, depth + 1);
            } else if (f.isFile() && f.lastModified() < before) {
                String nm = f.getName();
                if (nm.endsWith(".jsonl") || nm.endsWith(".json")) it.targets.add(f);
            }
        }
    }

    private static final Set<String> CORE_SKIP = new HashSet<>(Arrays.asList(
            "node_modules", ".git", ".npm", ".cache", ".gradle", ".pi", "proc", "sys", "dev"));

    private static void findCores(File dir, Item it, int depth, AtomicBoolean cancel) {
        if (depth > 4 || cancelled(cancel)) return;
        for (File f : list(dir)) {
            if (isLink(f)) continue;
            String nm = f.getName();
            if (f.isDirectory()) {
                if (!CORE_SKIP.contains(nm)) findCores(f, it, depth + 1, cancel);
            } else if (f.isFile() && (nm.equals("core") || nm.matches("core\\.\\d+")) && isElfCore(f)) {
                it.targets.add(f);
            }
        }
    }

    /** ELF 头且 e_type == ET_CORE(4)。 */
    private static boolean isElfCore(File f) {
        byte[] h = new byte[18];
        try (FileInputStream in = new FileInputStream(f)) {
            if (in.read(h) < 18) return false;
        } catch (Throwable t) {
            return false;
        }
        if (h[0] != 0x7F || h[1] != 'E' || h[2] != 'L' || h[3] != 'F') return false;
        boolean little = h[5] == 1;
        int type = little ? ((h[16] & 0xFF) | ((h[17] & 0xFF) << 8)) : (((h[16] & 0xFF) << 8) | (h[17] & 0xFF));
        return type == 4;
    }

    private List<File> backupDirs() {
        List<File> out = new ArrayList<>();
        try {
            File d = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (d != null) out.add(d);
        } catch (Throwable ignored) {}
        try {
            File d = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (d != null) out.add(d);
        } catch (Throwable ignored) {}
        return out;
    }

    /** 导入桌宠：同一 id 的重复副本（保留正在用/已绑定/最新的那份）与未使用的导入桌宠。 */
    private void scanPets(List<Item> all) {
        Item dup = item("pet_dup", "重复的导入桌宠", "同一桌宠导入了多份，保留在用或最新的一份", false);
        Item unused = item("pet_unused", "未使用的导入桌宠", "既不是当前桌宠、也没绑定到任何监控的导入桌宠", false);
        try {
            String cur = PetRegistry.getPetDir(ctx);
            Set<String> inUse = new HashSet<>();
            if (cur != null) inUse.add(cur);
            for (String[] t : PetRegistry.BINDABLE_TARGETS) {
                String b = PetRegistry.getBoundPet(ctx, t[0]);
                if (b != null) inUse.add(b);
            }
            File ext = PetRegistry.getExternalPetsDir(ctx);
            Map<String, List<PetRegistry.PetInfo>> byId = new HashMap<>();
            for (PetRegistry.PetInfo p : PetRegistry.loadPets(ctx)) {
                if (!p.external) continue;
                String id = p.id != null && !p.id.isEmpty() ? p.id : p.dir;
                List<PetRegistry.PetInfo> l = byId.get(id);
                if (l == null) byId.put(id, l = new ArrayList<>());
                l.add(p);
            }
            for (List<PetRegistry.PetInfo> l : byId.values()) {
                PetRegistry.PetInfo keep = null;
                for (PetRegistry.PetInfo p : l) if (inUse.contains(p.dir)) { keep = p; break; }
                if (keep == null) {
                    for (PetRegistry.PetInfo p : l) {
                        if (keep == null || new File(ext, p.dir).lastModified() > new File(ext, keep.dir).lastModified()) keep = p;
                    }
                }
                for (PetRegistry.PetInfo p : l) {
                    if (p == keep) continue;
                    if (inUse.contains(p.dir)) continue;
                    File d = new File(ext, p.dir);
                    if (d.isDirectory() && !isLink(d)) dup.targets.add(d);
                }
                if (keep != null && !inUse.contains(keep.dir)) {
                    File d = new File(ext, keep.dir);
                    if (d.isDirectory() && !isLink(d)) unused.targets.add(d);
                }
            }
        } catch (Throwable ignored) {}
        dup.desc = "同一桌宠多导入了 " + dup.targets.size() + " 份，保留在用或最新的一份";
        unused.desc = unused.targets.size() + " 只导入桌宠既不在用、也没绑定监控";
        all.add(dup);
        all.add(unused);
    }

    /** 大于 100 MB 的文件（优先 find，失败退回限时遍历），只列出不删除。 */
    private void findLargeFiles(List<LargeFile> out, Set<String> covered, AtomicBoolean cancel) {
        List<LargeFile> found = new ArrayList<>();
        boolean ok = false;
        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("find", rootfs.getAbsolutePath(), "-type", "f", "-size", "+102400k");
            pb.redirectError(ProcessBuilder.Redirect.to(new File("/dev/null")));
            proc = pb.start();
            try (BufferedReader rd = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = rd.readLine()) != null) {
                    if (cancelled(cancel)) break;
                    File f = new File(line.trim());
                    if (f.isFile() && !isLink(f) && f.length() >= LARGE_FILE) found.add(new LargeFile(f.getAbsolutePath(), f.length()));
                }
            }
            ok = proc.waitFor() == 0 || !found.isEmpty();
        } catch (Throwable ignored) {
        } finally {
            if (proc != null) try { proc.destroy(); } catch (Throwable ignored) {}
        }
        if (!ok) {
            found.clear();
            long deadline = System.currentTimeMillis() + 15000;
            walkLarge(rootfs, found, 0, deadline, cancel);
        }
        String rp = rootfs.getAbsolutePath();
        for (LargeFile lf : found) {
            boolean inCovered = false;
            for (String c : covered) {
                if (lf.path.equals(c) || lf.path.startsWith(c + "/")) { inCovered = true; break; }
            }
            if (inCovered) continue;
            String shown = lf.path.startsWith(rp) ? lf.path.substring(rp.length()) : lf.path;
            out.add(new LargeFile(shown.isEmpty() ? "/" : shown, lf.size));
        }
        Collections.sort(out, (a, b) -> Long.compare(b.size, a.size));
        while (out.size() > 10) out.remove(out.size() - 1);
    }

    private static void walkLarge(File dir, List<LargeFile> out, int depth, long deadline, AtomicBoolean cancel) {
        if (depth > 16 || System.currentTimeMillis() > deadline || cancelled(cancel)) return;
        for (File f : list(dir)) {
            if (isLink(f)) continue;
            if (f.isDirectory()) {
                if (depth == 0 && (f.getName().equals("proc") || f.getName().equals("sys") || f.getName().equals("dev"))) continue;
                walkLarge(f, out, depth + 1, deadline, cancel);
            } else if (f.length() >= LARGE_FILE) {
                out.add(new LargeFile(f.getAbsolutePath(), f.length()));
            }
        }
    }
}
