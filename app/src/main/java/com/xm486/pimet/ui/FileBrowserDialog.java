package com.xm486.pimet.ui;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.xm486.pimet.BackupManager;
import com.xm486.pimet.ThemeManager;
import com.xm486.pimet.proot.ProotManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 内部全量文件浏览器弹窗 (FileBrowserDialog)
 * 允许用户浏览 PRoot 容器文件系统及私有数据目录，直观显示所有文件大小、时间及层级，
 * 并支持快速跳转、文件详情与文本内容安全预览。
 */
public class FileBrowserDialog {

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private AlertDialog dialog;
    private File rootDir;
    private File currentDir;

    private TextView tvCurrentPath;
    private TextView tvPathSummary;
    private ListView listView;
    private FileAdapter adapter;
    private final List<FileItem> fileItems = new ArrayList<>();
    private final ThemeManager.ThemePalette pal;

    public static class FileItem {
        public final File file;
        public final boolean isDirectory;
        public final boolean isSymlink;
        public final File resolvedTarget;
        public final String name;
        public final long size;
        public final long lastModified;
        public final int childCount;
        /** 目录递归占用（字节），-1 = 计算中。 */
        public volatile long dirSize = -1;
        /** 分类（CAT_*）。 */
        public int category = 6;

        public FileItem(File file, File rootfs) {
            this.file = file;
            boolean isDir = file.isDirectory();
            boolean symlink = false;
            File resolved = file;
            if (!isDir) {
                resolved = resolveSymlink(rootfs, file);
                if (resolved != null && resolved.exists() && resolved.isDirectory()) {
                    isDir = true;
                    symlink = true;
                } else if (resolved != null && resolved != file) {
                    symlink = true;
                }
            } else {
                if (ProotManager.isSymbolicLink(file)) {
                    symlink = true;
                    File target = resolveSymlink(rootfs, file);
                    if (target != null) resolved = target;
                }
            }
            this.isDirectory = isDir;
            this.isSymlink = symlink;
            this.resolvedTarget = resolved != null ? resolved : file;
            this.name = file.getName();
            this.lastModified = file.lastModified();
            if (this.isDirectory) {
                String[] list = this.resolvedTarget.list();
                this.childCount = list != null ? list.length : 0;
                this.size = 0;
            } else {
                this.childCount = 0;
                long realSize = (resolved != null && resolved.exists()) ? resolved.length() : file.length();
                this.size = realSize;
            }
        }
    }

    public static File resolveSymlink(File rootfs, File file) {
        if (file == null) return null;
        try {
            String targetStr = Os.readlink(file.getAbsolutePath());
            if (targetStr != null && !targetStr.isEmpty()) {
                if (targetStr.startsWith("/")) {
                    if (rootfs != null) {
                        File candidate = new File(rootfs, targetStr.substring(1));
                        if (candidate.exists()) return candidate;
                    }
                } else {
                    File parent = file.getParentFile();
                    if (parent != null) {
                        File candidate = new File(parent, targetStr);
                        if (candidate.exists()) return candidate;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return file;
    }

    public FileBrowserDialog(Context context) {
        this.context = context;
        this.pal = ThemeManager.getEffectivePalette(context);
        File rootfs = ProotManager.getRootfsDir(context);
        if (rootfs != null && rootfs.exists()) {
            this.rootDir = rootfs;
            File rootHome = new File(rootfs, "root");
            this.currentDir = rootHome.exists() ? rootHome : rootfs;
        } else {
            this.rootDir = context.getFilesDir();
            this.currentDir = this.rootDir;
        }
    }

    private int dp(int v) {
        return (int) (v * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    // =========================================================================
    // 分类规则（按本应用实际使用的容器路径）
    // =========================================================================

    public static final int CAT_DEP = 0, CAT_PLUGIN = 1, CAT_CHAT = 2, CAT_PROGRAM = 3,
            CAT_CONFIG = 4, CAT_PROJECT = 5, CAT_OTHER = 6;
    public static final String[] CAT_NAMES = {"依赖", "插件", "聊天/会话", "程序", "配置", "项目", "其他"};
    private static final String[] CAT_ICONS = {"📦", "🧩", "💬", "🚀", "⚙", "📝", "📁"};

    private static final String[] DEP_SEGMENTS = {
            "node_modules", ".npm", ".cache", ".gradle", ".pnpm-store", ".yarn", ".m2", ".cargo",
            ".rustup", "__pycache__", "site-packages", "dist-packages", ".android", ".node-gyp",
            ".bun", ".deno", ".pnpm", ".npm-global", ".nvm"
    };

    /**
     * 按路径判断类别。rel 为相对容器根的路径（如 /root/.pi/agent/npm），不在容器内时为 null（仅按名称判断）。
     * 规则依据：PiWebManager（usr/local/lib/node_modules/@agegr/pi-web、usr/local/bin）、
     * PluginManager（.pi/agent/npm|extensions|skills）、PetMemoryManager（.pi/agent/sessions、pet_memory.json、
     * pi-web-session-*.json）、BackupManager（root/pi-cwd 工作区、custom_pets）。
     */
    public static int categorize(String rel, String name, boolean isDir) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String p = rel == null ? null : rel.toLowerCase(Locale.ROOT);
        if (p != null && !p.startsWith("/")) p = "/" + p;

        // 1. 程序本体：pi-web 应用包（位于 node_modules 内，需先于依赖判断）
        if (p != null && p.contains("/node_modules/@agegr/pi-web")) return CAT_PROGRAM;
        if (p != null && p.contains("/node_modules/@agegr") && n.equals("@agegr")) return CAT_PROGRAM;

        // 2. 插件：pi 代理插件/扩展/技能、自定义桌宠皮肤
        if (p != null && (p.startsWith("/root/.pi/agent/npm") || p.startsWith("/root/.pi/agent/extensions")
                || p.startsWith("/root/.pi/agent/skills") || p.contains("/plugins") || p.contains("/custom_pets"))) {
            return CAT_PLUGIN;
        }
        if (n.equals("plugins") || n.equals("extensions") || n.equals("custom_pets") || n.equals("skills")) return CAT_PLUGIN;

        // 3. 聊天/会话：会话记录、桌宠记忆、pi-web 会话索引与状态
        if (p != null && p.contains("/sessions")) return CAT_CHAT;
        if (n.equals("sessions") || n.contains("session") || n.contains("chat") || n.contains("conversation")
                || n.contains("memory") || n.endsWith(".jsonl")) {
            return CAT_CHAT;
        }

        // 4. 依赖：包管理器缓存与第三方模块
        if (p != null) {
            for (String seg : DEP_SEGMENTS) {
                if (p.contains("/" + seg + "/") || p.endsWith("/" + seg)) return CAT_DEP;
            }
            if (p.startsWith("/var/cache") || p.startsWith("/var/lib/apt") || p.startsWith("/var/lib/dpkg")
                    || p.startsWith("/usr/include") || p.startsWith("/usr/lib/python")) {
                return CAT_DEP;
            }
        } else {
            for (String seg : DEP_SEGMENTS) {
                if (n.equals(seg)) return CAT_DEP;
            }
        }

        // 5. 项目：用户工作区代码
        if (p != null && (p.startsWith("/root/pi-cwd") || p.startsWith("/root/shared"))) return CAT_PROJECT;
        if (n.equals("pi-cwd")) return CAT_PROJECT;

        // 6. 程序：系统与运行时（rootfs 核心、二进制、.local/bin）
        if (p != null && (p.equals("/usr") || p.startsWith("/usr/") || p.equals("/bin") || p.startsWith("/bin/")
                || p.equals("/sbin") || p.startsWith("/sbin/") || p.equals("/lib") || p.startsWith("/lib/")
                || p.startsWith("/lib64") || p.startsWith("/libx32") || p.startsWith("/lib32")
                || p.startsWith("/opt") || p.startsWith("/boot") || p.startsWith("/root/.local"))) {
            return CAT_PROGRAM;
        }

        // 7. 配置：/etc、.config、pi 代理设置、点文件与配置类文件
        if (p != null && (p.equals("/etc") || p.startsWith("/etc/") || p.startsWith("/root/.config")
                || p.startsWith("/root/.pi"))) {
            return CAT_CONFIG;
        }
        String ext = getFileExt(n);
        if (n.startsWith(".") && !n.endsWith(".log")) return CAT_CONFIG;
        if (!isDir && (ext.equals("json") || ext.equals("yaml") || ext.equals("yml") || ext.equals("toml")
                || ext.equals("ini") || ext.equals("conf") || ext.equals("cfg") || ext.equals("rc")
                || ext.equals("properties") || ext.equals("env"))) {
            return CAT_CONFIG;
        }
        return CAT_OTHER;
    }

    private String relPath(File f) {
        File rootfs = ProotManager.getRootfsDir(context);
        if (rootfs == null || f == null) return null;
        String rp = rootfs.getAbsolutePath();
        String ap = f.getAbsolutePath();
        if (ap.equals(rp)) return "/";
        if (ap.startsWith(rp + "/")) return ap.substring(rp.length());
        return null;
    }

    // =========================================================================
    // 目录大小（du 磁盘占用，与首页「存储占用」同一算法；不跟随符号链接）
    // =========================================================================

    private static final long SIZE_TTL_MS = 5 * 60 * 1000L;
    private static final Map<String, long[]> SIZE_CACHE = new ConcurrentHashMap<>();
    private final ExecutorService sizeExecutor = Executors.newSingleThreadExecutor();
    private int loadGeneration = 0;

    private static Long cachedSize(String path) {
        long[] v = SIZE_CACHE.get(path);
        if (v == null) return null;
        if (System.currentTimeMillis() - v[1] > SIZE_TTL_MS) {
            SIZE_CACHE.remove(path);
            return null;
        }
        return v[0];
    }

    private static void putSize(String path, long size) {
        SIZE_CACHE.put(path, new long[]{size, System.currentTimeMillis()});
    }

    /** 一次 `du -k -d 1 dir` 得到当前目录总占用和每个直接子目录占用（KB×1024，不跟随符号链接）。 */
    private static Map<String, Long> duDepth1(File dir) {
        Map<String, Long> out = new HashMap<>();
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("du", "-k", "-d", "1", dir.getAbsolutePath());
            pb.redirectError(ProcessBuilder.Redirect.to(new File("/dev/null")));
            process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int tab = line.indexOf('\t');
                    if (tab <= 0) continue;
                    try {
                        long kb = Long.parseLong(line.substring(0, tab).trim());
                        String path = line.substring(tab + 1).trim();
                        while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
                        out.put(path, kb * 1024L);
                    } catch (NumberFormatException ignored) {}
                }
            }
            process.waitFor();
        } catch (Throwable ignored) {
        } finally {
            if (process != null) {
                try { process.destroy(); } catch (Throwable ignored) {}
            }
        }
        return out;
    }

    /** 后台计算各子目录与当前目录的递归占用（带缓存），完成后刷新列表与分类汇总。 */
    private void computeSizes(final int gen, final File dir, final List<FileItem> items, boolean force) {
        final String dirPath = dir.getAbsolutePath();
        sizeExecutor.execute(() -> {
            Map<String, Long> du = null;
            boolean need = force || cachedSize(dirPath) == null;
            if (!need) {
                for (FileItem it : items) {
                    if (it.isDirectory && !it.isSymlink && cachedSize(it.file.getAbsolutePath()) == null) {
                        need = true;
                        break;
                    }
                }
            }
            if (need) {
                du = duDepth1(dir);
                for (Map.Entry<String, Long> e : du.entrySet()) putSize(e.getKey(), e.getValue());
            }
            long sumChildren = 0;
            for (FileItem it : items) {
                if (gen != loadGeneration) return;
                if (it.isDirectory && !it.isSymlink) {
                    String path = it.file.getAbsolutePath();
                    Long s = cachedSize(path);
                    if (s == null) {
                        // du 不可用或无权限：退回到安全递归计算（不跟随符号链接）
                        s = ProotManager.getDirectorySize(it.file);
                        putSize(path, s);
                    }
                    it.dirSize = s;
                    sumChildren += s;
                } else if (!it.isDirectory) {
                    sumChildren += it.size;
                }
            }
            Long total = cachedSize(dirPath);
            if (total == null || total <= 0) {
                total = sumChildren;
                putSize(dirPath, total);
            }
            final long fTotal = total;
            mainHandler.post(() -> {
                if (gen != loadGeneration) return;
                currentTotalSize = fTotal;
                sortItems(allItems);
                applyFilter();
            });
        });
    }

    // =========================================================================
    // 主界面
    // =========================================================================

    private final List<FileItem> allItems = new ArrayList<>();
    private int activeCategory = -1; // -1 = 全部
    private long currentTotalSize = -1;
    private LinearLayout categoryBar;

    public void show() {
        if (dialog != null && dialog.isShowing()) return;

        AlertDialog.Builder builder = new com.xm486.pimet.ui.Icons.DialogBuilder(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(ThemeManager.createCardDrawable(context, pal, 14f));
        root.setPadding(dp(14), dp(14), dp(14), dp(12));

        // ---- 1. 标题独占一行 ----
        TextView tvTitle = new TextView(context);
        tvTitle.setText("📁 内部文件浏览器");
        tvTitle.setTextColor(pal.text);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        tvTitle.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(tvTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 2. 操作按钮行（等宽、单行） ----
        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        TextView btnUp = buildActionBtn("⬆ 上级", 0, 0, v -> navigateUp());
        TextView btnRefresh = buildActionBtn("🔄 刷新", 0, 0, v -> refresh());
        TextView btnBackup = buildActionBtn("📦 迁移备份", 0, 0, v -> showBackupMigrationDialog());
        TextView btnClose = buildActionBtn("✕ 关闭", 0, COLOR_DANGER_HINT, v -> {
            if (dialog != null) dialog.dismiss();
        });
        TextView[] acts = {btnUp, btnRefresh, btnBackup, btnClose};
        for (int i = 0; i < acts.length; i++) {
            acts[i].setGravity(Gravity.CENTER);
            acts[i].setPadding(dp(4), dp(8), dp(4), dp(8));
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,
                    i == 2 ? 1.3f : 1f);
            if (i > 0) alp.leftMargin = dp(6);
            actions.addView(acts[i], alp);
        }
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actLp.topMargin = dp(10);
        root.addView(actions, actLp);

        // ---- 3. 快捷路径芯片横条 ----
        HorizontalScrollView hsv = new HorizontalScrollView(context);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout shortcutBar = new LinearLayout(context);
        shortcutBar.setOrientation(LinearLayout.HORIZONTAL);
        shortcutBar.setPadding(0, dp(8), 0, dp(6));

        File rootfs = ProotManager.getRootfsDir(context);
        if (rootfs != null && rootfs.exists()) {
            shortcutBar.addView(buildChipBtn("🏠 根目录 /", v -> navigateTo(rootfs)));
            File rootHome = new File(rootfs, "root");
            if (rootHome.exists()) {
                shortcutBar.addView(buildChipBtn("👤 /root", v -> navigateTo(rootHome)));
            }
            File agentDir = new File(rootfs, "root/.pi/agent");
            if (agentDir.exists()) {
                shortcutBar.addView(buildChipBtn("🤖 .pi/agent", v -> navigateTo(agentDir)));
            }
            File piWeb1 = new File(rootfs, "usr/local/lib/node_modules/@agegr/pi-web");
            File piWeb2 = new File(rootfs, "usr/lib/node_modules/@agegr/pi-web");
            if (piWeb1.exists()) {
                shortcutBar.addView(buildChipBtn("🌐 pi-web", v -> navigateTo(piWeb1)));
            } else if (piWeb2.exists()) {
                shortcutBar.addView(buildChipBtn("🌐 pi-web", v -> navigateTo(piWeb2)));
            }
            File etcDir = new File(rootfs, "etc");
            if (etcDir.exists()) {
                shortcutBar.addView(buildChipBtn("⚙️ /etc", v -> navigateTo(etcDir)));
            }
            File usrDir = new File(rootfs, "usr");
            if (usrDir.exists()) {
                shortcutBar.addView(buildChipBtn("📦 /usr", v -> navigateTo(usrDir)));
            }
            File tmpDir = new File(rootfs, "tmp");
            if (tmpDir.exists()) {
                shortcutBar.addView(buildChipBtn("🌐 /tmp", v -> navigateTo(tmpDir)));
            }
            File varDir = new File(rootfs, "var");
            if (varDir.exists()) {
                shortcutBar.addView(buildChipBtn("💾 /var", v -> navigateTo(varDir)));
            }
        }
        File sdcard = Environment.getExternalStorageDirectory();
        if (sdcard != null && sdcard.exists()) {
            shortcutBar.addView(buildChipBtn("📱 存储 (/sdcard)", v -> navigateTo(sdcard)));
        }
        shortcutBar.addView(buildChipBtn("📂 App私有目录", v -> navigateTo(context.getFilesDir())));

        hsv.addView(shortcutBar);
        root.addView(hsv);

        // ---- 4. 当前路径与摘要卡片 ----
        LinearLayout pathCard = new LinearLayout(context);
        pathCard.setOrientation(LinearLayout.VERTICAL);
        pathCard.setPadding(dp(12), dp(8), dp(12), dp(8));
        pathCard.setBackground(ThemeManager.createSunkenDrawable(context, pal, 8f));

        tvCurrentPath = new TextView(context);
        tvCurrentPath.setTextColor(pal.accent);
        tvCurrentPath.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tvCurrentPath.setTypeface(Typeface.MONOSPACE);
        tvCurrentPath.setSingleLine(false);
        tvCurrentPath.setOnClickListener(v -> {
            if (currentDir != null) {
                ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("CurrentPath", currentDir.getAbsolutePath()));
                    Toast.makeText(context, "已复制当前路径", Toast.LENGTH_SHORT).show();
                }
            }
        });
        pathCard.addView(tvCurrentPath);

        tvPathSummary = new TextView(context);
        tvPathSummary.setTextColor(pal.textMuted);
        tvPathSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvPathSummary.setPadding(0, dp(4), 0, 0);
        pathCard.addView(tvPathSummary);

        LinearLayout.LayoutParams pcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pcLp.topMargin = dp(2);
        root.addView(pathCard, pcLp);

        // ---- 5. 分类汇总（点按筛选） ----
        HorizontalScrollView catScroll = new HorizontalScrollView(context);
        catScroll.setHorizontalScrollBarEnabled(false);
        categoryBar = new LinearLayout(context);
        categoryBar.setOrientation(LinearLayout.HORIZONTAL);
        categoryBar.setPadding(0, dp(8), 0, dp(8));
        catScroll.addView(categoryBar);
        root.addView(catScroll);

        // ---- 6. 文件列表 ----
        listView = new ListView(context);
        listView.setDivider(null);
        listView.setDividerHeight(dp(4));
        listView.setVerticalScrollBarEnabled(true);
        adapter = new FileAdapter();
        listView.setAdapter(adapter);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= fileItems.size()) return;
            FileItem item = fileItems.get(position);
            if (item.isDirectory) {
                navigateTo(item.resolvedTarget != null ? item.resolvedTarget : item.file);
            } else {
                showFilePreviewDialog(item.file);
            }
        });

        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < fileItems.size()) {
                FileItem item = fileItems.get(position);
                showItemDetailDialog(item);
                return true;
            }
            return false;
        });

        LinearLayout.LayoutParams lvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(listView, lvLp);

        builder.setView(root);
        dialog = builder.create();
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnDismissListener(d -> {
            loadGeneration++;
            sizeExecutor.shutdownNow();
        });

        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            // 在 show() 之后设置窗口尺寸，避免被 AlertDialog 默认宽度覆盖
            window.setBackgroundDrawableResource(android.R.color.transparent);
            android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
            window.setLayout((int) (dm.widthPixels * 0.96f), (int) (dm.heightPixels * 0.88f));
        }
        loadCurrentDir();
    }

    private void navigateTo(File dir) {
        if (dir == null) {
            Toast.makeText(context, "目录不存在或无法访问", Toast.LENGTH_SHORT).show();
            return;
        }
        File target = resolveSymlink(rootDir, dir);
        if (target != null && target.exists() && target.isDirectory()) {
            this.currentDir = target;
            activeCategory = -1;
            loadCurrentDir();
            return;
        }
        if (dir.exists() && dir.isDirectory()) {
            this.currentDir = dir;
            activeCategory = -1;
            loadCurrentDir();
            return;
        }
        Toast.makeText(context, "目录不存在或无法访问", Toast.LENGTH_SHORT).show();
    }

    private void navigateUp() {
        if (currentDir != null) {
            File parent = currentDir.getParentFile();
            if (parent != null && parent.exists()) {
                currentDir = parent;
                activeCategory = -1;
                loadCurrentDir();
            } else {
                Toast.makeText(context, "已是根目录", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void refresh() {
        loadCurrentDir(true);
    }

    private void loadCurrentDir() {
        loadCurrentDir(false);
    }

    private void loadCurrentDir(boolean force) {
        if (currentDir == null) return;
        final int gen = ++loadGeneration;
        final File dir = currentDir;

        String displayPath = relPath(dir);
        if (displayPath == null) displayPath = dir.getAbsolutePath();
        if (tvCurrentPath != null) {
            tvCurrentPath.setText("路径: " + displayPath);
        }
        currentTotalSize = -1;

        new Thread(() -> {
            File[] files = dir.listFiles();
            List<FileItem> list = new ArrayList<>();
            if (files != null) {
                for (File f : files) {
                    FileItem item = new FileItem(f, rootDir);
                    item.category = categorize(relPath(f), item.name, item.isDirectory);
                    if (item.isDirectory && !item.isSymlink && !force) {
                        Long c = cachedSize(f.getAbsolutePath());
                        if (c != null) item.dirSize = c;
                    }
                    list.add(item);
                }
            }
            sortItems(list);
            mainHandler.post(() -> {
                if (gen != loadGeneration) return;
                allItems.clear();
                allItems.addAll(list);
                if (!force) {
                    Long cachedTotal = cachedSize(dir.getAbsolutePath());
                    if (cachedTotal != null) currentTotalSize = cachedTotal;
                }
                applyFilter();
                if (list.isEmpty()) {
                    Toast.makeText(context, files == null ? "无权限读取此目录" : "当前目录为空", Toast.LENGTH_SHORT).show();
                }
            });
            if (!list.isEmpty() && !sizeExecutor.isShutdown()) {
                try {
                    computeSizes(gen, dir, list, force);
                } catch (Throwable ignored) {}
            } else if (list.isEmpty()) {
                mainHandler.post(() -> {
                    if (gen != loadGeneration) return;
                    currentTotalSize = 0;
                    applyFilter();
                });
            }
        }).start();
    }

    /** 已知大小（文件长度 / 目录递归占用），未知返回 -1。 */
    private static long itemSize(FileItem it) {
        if (!it.isDirectory) return it.size;
        if (it.isSymlink) return 0;
        return it.dirSize;
    }

    /** 排序：按类别，再按大小降序（未计算完的目录排在后面并按名称）。 */
    private static void sortItems(List<FileItem> list) {
        Collections.sort(list, (a, b) -> {
            if (a.category != b.category) return Integer.compare(a.category, b.category);
            long sa = itemSize(a), sb = itemSize(b);
            if (sa >= 0 && sb >= 0 && sa != sb) return Long.compare(sb, sa);
            if (sa >= 0 && sb < 0) return -1;
            if (sa < 0 && sb >= 0) return 1;
            return a.name.compareToIgnoreCase(b.name);
        });
    }

    /** 按当前选中的类别筛选列表，并刷新摘要与分类汇总条。 */
    private void applyFilter() {
        fileItems.clear();
        int dirCount = 0, fileCount = 0;
        long[] catSize = new long[CAT_NAMES.length];
        int[] catCount = new int[CAT_NAMES.length];
        boolean pending = false;
        for (FileItem it : allItems) {
            if (it.isDirectory) dirCount++; else fileCount++;
            catCount[it.category]++;
            long s = itemSize(it);
            if (s < 0) pending = true; else catSize[it.category] += s;
            if (activeCategory < 0 || it.category == activeCategory) fileItems.add(it);
        }
        if (adapter != null) adapter.notifyDataSetChanged();

        if (tvPathSummary != null) {
            String total = currentTotalSize >= 0 ? formatSize(currentTotalSize) : "计算中…";
            tvPathSummary.setText(String.format(Locale.getDefault(),
                    "📊 %d 个目录 · %d 个文件 · 共 %s（含子目录，长按项目看详情）", dirCount, fileCount, total));
        }

        if (categoryBar != null) {
            categoryBar.removeAllViews();
            categoryBar.addView(buildCategoryChip("📑 全部 · " + allItems.size(), activeCategory < 0, v -> {
                activeCategory = -1;
                applyFilter();
            }));
            for (int c = 0; c < CAT_NAMES.length; c++) {
                if (catCount[c] == 0) continue;
                final int cat = c;
                String sizeStr = (pending && catSize[c] == 0) ? "计算中…" : formatSize(catSize[c]) + (pending ? "+" : "");
                TextView chip = buildCategoryChip(CAT_ICONS[c] + " " + CAT_NAMES[c] + " · " + sizeStr,
                        activeCategory == cat, v -> {
                            activeCategory = (activeCategory == cat) ? -1 : cat;
                            applyFilter();
                        });
                chip.setOnLongClickListener(v -> {
                    exportCategory(cat);
                    return true;
                });
                categoryBar.addView(chip);
            }
            if (activeCategory >= 0 && catCount[activeCategory] > 0) {
                final int cat = activeCategory;
                categoryBar.addView(buildCategoryChip("📤 导出此类", false, v -> exportCategory(cat)));
            }
        }
    }

    private TextView buildCategoryChip(String text, boolean selected, View.OnClickListener l) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(selected ? pal.accent : pal.text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tv.setSingleLine(true);
        tv.setPadding(dp(10), dp(5), dp(10), dp(5));
        tv.setBackground(selected
                ? ThemeManager.createSelectedOptionDrawable(context, pal, 12f)
                : ThemeManager.createSecondaryButtonDrawable(context, pal, 12f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        tv.setLayoutParams(lp);
        tv.setOnClickListener(l);
        return tv;
    }

    /** 文字色传入 COLOR_DANGER_HINT 时为低调危险样式；其余按钮统一为极简次级按钮（bg / textColor 参数为兼容旧调用保留）。 */
    private static final int COLOR_DANGER_HINT = 0xFFFCA5A5;
    private static final int COLOR_MUTED_HINT_BG = 0x2230363D;

    private TextView buildActionBtn(String text, int bg, int textColor, View.OnClickListener l) {
        TextView tv = new TextView(context);
        tv.setText(text);
        boolean danger = textColor == COLOR_DANGER_HINT;
        boolean muted = bg == COLOR_MUTED_HINT_BG;
        tv.setTextColor(danger ? ThemeManager.statusDangerColor(pal) : (muted ? pal.textMuted : pal.text));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setPadding(dp(10), dp(6), dp(10), dp(6));
        tv.setBackground(danger
                ? ThemeManager.createDangerButtonDrawable(context, pal, ThemeManager.RADIUS_CONTROL)
                : ThemeManager.createSecondaryButtonDrawable(context, pal, ThemeManager.RADIUS_CONTROL));
        tv.setOnClickListener(l);
        return tv;
    }

    private TextView buildChipBtn(String text, View.OnClickListener l) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(pal.text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tv.setSingleLine(true);
        tv.setPadding(dp(10), dp(5), dp(10), dp(5));
        GradientDrawable gd = ThemeManager.createSecondaryButtonDrawable(context, pal, 12f);
        gd.setStroke(Math.max(1, dp(1) / 2), pal.border);
        tv.setBackground(gd);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        tv.setLayoutParams(lp);
        tv.setOnClickListener(l);
        return tv;
    }

    /** 1024 进制：B / KB / MB / GB / TB，与首页存储占用一致。 */
    public static String formatSize(long bytes) {
        return ProotManager.formatSize(bytes);
    }

    private class FileAdapter extends BaseAdapter {
        @Override public int getCount() { return fileItems.size(); }
        @Override public Object getItem(int position) { return fileItems.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            if (convertView == null) {
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), dp(8), dp(12), dp(8));
                row.setBackground(ThemeManager.createSunkenDrawable(context, pal, 8f));

                TextView icon = new TextView(context);
                icon.setTag("icon");
                icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
                icon.setTextColor(pal.textMuted);
                row.addView(icon, new LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT));

                LinearLayout textCol = new LinearLayout(context);
                textCol.setOrientation(LinearLayout.VERTICAL);

                TextView name = new TextView(context);
                name.setTag("name");
                name.setTextColor(pal.text);
                name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
                name.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                textCol.addView(name);

                TextView meta = new TextView(context);
                meta.setTag("meta");
                meta.setTextColor(pal.textMuted);
                meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
                meta.setSingleLine(true);
                meta.setEllipsize(TextUtils.TruncateAt.END);
                meta.setPadding(0, dp(2), 0, 0);
                textCol.addView(meta);

                row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView size = new TextView(context);
                size.setTag("size");
                size.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
                size.setSingleLine(true);
                size.setGravity(Gravity.END);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                slp.leftMargin = dp(8);
                row.addView(size, slp);

                TextView export = new TextView(context);
                export.setTag("export");
                export.setText("📤");
                export.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
                export.setTextColor(pal.textMuted);
                export.setGravity(Gravity.CENTER);
                export.setFocusable(false);
                export.setContentDescription("导出");
                export.setTooltipText("导出");
                export.setPadding(dp(6), dp(6), dp(6), dp(6));
                LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(32), dp(32));
                elp.leftMargin = dp(4);
                row.addView(export, elp);

                TextView arrow = new TextView(context);
                arrow.setTag("arrow");
                arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
                arrow.setTextColor(pal.textDim);
                LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                alp.leftMargin = dp(6);
                row.addView(arrow, alp);
            } else {
                row = (LinearLayout) convertView;
            }

            FileItem item = fileItems.get(position);
            TextView icon = row.findViewWithTag("icon");
            TextView name = row.findViewWithTag("name");
            TextView meta = row.findViewWithTag("meta");
            TextView size = row.findViewWithTag("size");
            TextView arrow = row.findViewWithTag("arrow");
            TextView export = row.findViewWithTag("export");
            export.setOnClickListener(v -> exportItem(item));

            name.setText(item.name);
            String timeStr = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(item.lastModified));
            String cat = CAT_NAMES[item.category];

            if (item.isDirectory) {
                icon.setText(item.isSymlink ? "🔗" : "📁");
                name.setTextColor(pal.accent);
                meta.setText(String.format(Locale.getDefault(), "%s · %s · %d 项 · %s",
                        cat, item.isSymlink ? "链接目录" : "文件夹", item.childCount, timeStr));
                if (item.isSymlink) {
                    size.setText("链接");
                    size.setTextColor(pal.textDim);
                } else if (item.dirSize >= 0) {
                    size.setText(formatSize(item.dirSize));
                    size.setTextColor(pal.text);
                } else {
                    size.setText("计算中…");
                    size.setTextColor(pal.textDim);
                }
                arrow.setText("›");
            } else {
                String ext = getFileExt(item.name);
                if (isScriptOrCode(ext)) {
                    icon.setText("📜");
                } else if (isDataOrConfig(ext)) {
                    icon.setText("⚙️");
                } else {
                    icon.setText("📄");
                }
                name.setTextColor(pal.text);
                meta.setText(String.format(Locale.getDefault(), "%s · %s", cat, timeStr));
                size.setText(formatSize(item.size));
                size.setTextColor(pal.text);
                arrow.setText("");
            }

            return row;
        }
    }

    // =========================================================================
    // 单独导出：单个文件 / 文件夹 / 整个分类 → 手机存储（系统保存器）或分享
    // =========================================================================

    private static String mimeFor(String name) {
        String ext = getFileExt(name);
        if (ext.isEmpty()) return "application/octet-stream";
        if (ext.equals("zip")) return "application/zip";
        String m = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return m != null ? m : "application/octet-stream";
    }

    private static String safeName(String n) {
        String s = n == null ? "export" : n.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (s.startsWith(".")) s = s.substring(1);
        return s.isEmpty() ? "export" : s;
    }

    /** 统计待打包的总字节数（不跟随符号链接）。 */
    private static long walkSize(File f, java.util.concurrent.atomic.AtomicBoolean cancel) {
        if (cancel.get() || f == null || ProotManager.isSymbolicLink(f)) return 0;
        if (!f.isDirectory()) return f.length();
        long t = 0;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) t += walkSize(k, cancel);
        }
        return t;
    }

    /** 导出入口：label 为显示名，sources 为要导出的文件/文件夹（分类导出时为多个）。 */
    private void showExportOptions(String label, List<File> sources) {
        if (sources == null || sources.isEmpty()) {
            Toast.makeText(context, "没有可导出的项目", Toast.LENGTH_SHORT).show();
            return;
        }
        final boolean singleFile = sources.size() == 1 && sources.get(0).isFile();
        final String outName = singleFile ? sources.get(0).getName() : safeName(label) + ".zip";

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(ThemeManager.createCardDrawable(context, pal, 14f));
        root.setPadding(dp(16), dp(16), dp(16), dp(14));

        TextView title = new TextView(context);
        title.setText("📤 导出「" + label + "」");
        title.setTextColor(pal.text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        title.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        root.addView(title);

        TextView sub = new TextView(context);
        sub.setText((singleFile ? "导出为原文件：" : "打包为 zip：") + outName);
        sub.setTextColor(pal.textMuted);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        sub.setPadding(0, dp(4), 0, dp(12));
        root.addView(sub);

        AlertDialog d = new com.xm486.pimet.ui.Icons.DialogBuilder(context).setView(root).create();

        TextView btnSave = buildActionBtn("💾 保存到手机（选择位置）", 0, 0, v -> {
            d.dismiss();
            exportToDocument(outName, singleFile, sources);
        });
        btnSave.setGravity(Gravity.CENTER);
        btnSave.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(btnSave, l1);

        TextView btnShare = buildActionBtn("📤 分享 / 发送", 0, 0, v -> {
            d.dismiss();
            exportAndShare(outName, singleFile, sources);
        });
        btnShare.setGravity(Gravity.CENTER);
        btnShare.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l2.topMargin = dp(8);
        root.addView(btnShare, l2);

        TextView btnCancel = buildActionBtn("取消", COLOR_MUTED_HINT_BG, 0, v -> d.dismiss());
        btnCancel.setGravity(Gravity.CENTER);
        btnCancel.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams l3 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l3.topMargin = dp(8);
        root.addView(btnCancel, l3);

        if (d.getWindow() != null) d.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        d.show();
    }

    private void exportToDocument(String outName, boolean singleFile, List<File> sources) {
        if (!(context instanceof com.xm486.pimet.MainActivity)) {
            Toast.makeText(context, "当前页面不支持选择保存位置，已改为分享", Toast.LENGTH_SHORT).show();
            exportAndShare(outName, singleFile, sources);
            return;
        }
        String mime = singleFile ? mimeFor(outName) : "application/zip";
        ((com.xm486.pimet.MainActivity) context).launchCreateDocument(outName, mime, uri -> {
            if (uri == null) return; // 用户取消
            runExport("📤 正在导出 " + outName, singleFile, sources, () -> {
                java.io.OutputStream os = context.getContentResolver().openOutputStream(uri, "w");
                if (os == null) throw new java.io.IOException("无法写入所选位置");
                return os;
            }, (bytes, skipped) -> {
                Toast.makeText(context, "已导出到所选位置：" + outName + " · " + formatSize(bytes)
                        + (skipped > 0 ? "（" + skipped + " 个无权限文件已跳过）" : ""), Toast.LENGTH_LONG).show();
            }, () -> {
                try {
                    android.provider.DocumentsContract.deleteDocument(context.getContentResolver(), uri);
                } catch (Throwable ignored) {}
            });
        });
    }

    private void exportAndShare(String outName, boolean singleFile, List<File> sources) {
        if (singleFile) {
            shareFile(sources.get(0), mimeFor(outName));
            return;
        }
        File dir = new File(context.getCacheDir(), "export");
        // 清理上次分享留下的临时包，避免缓存越积越大
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        dir.mkdirs();
        final File out = new File(dir, outName);
        runExport("📦 正在打包 " + outName, false, sources, () -> new java.io.FileOutputStream(out),
                (bytes, skipped) -> {
                    if (skipped > 0) {
                        Toast.makeText(context, skipped + " 个无权限文件已跳过", Toast.LENGTH_SHORT).show();
                    }
                    shareFile(out, "application/zip");
                }, out::delete);
    }

    private void shareFile(File file, String mime) {
        try {
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    context, context.getPackageName() + ".fileprovider", file);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType(mime);
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(share, "分享 " + file.getName() + "（" + formatSize(file.length()) + "）");
            if (!(context instanceof android.app.Activity)) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);
        } catch (Throwable t) {
            Toast.makeText(context, "分享失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private interface StreamOpener { java.io.OutputStream open() throws Exception; }
    private interface ExportDone { void done(long bytes, int skipped); }

    /** 后台流式导出（单文件直接复制；文件夹/分类边读边写 zip），带进度与取消，不阻塞界面。 */
    private void runExport(String title, boolean singleFile, List<File> sources, StreamOpener opener,
                           ExportDone onDone, Runnable onAbortCleanup) {
        final java.util.concurrent.atomic.AtomicBoolean cancel = new java.util.concurrent.atomic.AtomicBoolean(false);
        ProgressDialogHolder holder = showBackupProgressDialog(title);
        TextView btnCancel = buildActionBtn("取消", 0, COLOR_DANGER_HINT, v -> cancel.set(true));
        btnCancel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(12);
        if (holder.tvDetail.getParent() instanceof LinearLayout) {
            ((LinearLayout) holder.tvDetail.getParent()).addView(btnCancel, clp);
        }

        new Thread(() -> {
            final long[] done = {0};
            final int[] skipped = {0};
            long total = 0;
            for (File f : sources) total += walkSize(f, cancel);
            final long fTotal = Math.max(1, total);
            final long[] lastUi = {0};
            Runnable progress = () -> {
                long now = System.currentTimeMillis();
                if (now - lastUi[0] < 150) return;
                lastUi[0] = now;
                final int pct = (int) Math.min(100, done[0] * 100 / fTotal);
                final String detail = formatSize(done[0]) + " / " + formatSize(fTotal);
                mainHandler.post(() -> {
                    holder.progressBar.setProgress(pct);
                    holder.tvStage.setText("已处理 " + pct + "%");
                    holder.tvDetail.setText(detail);
                });
            };
            String error = null;
            try (java.io.OutputStream raw = opener.open();
                 java.io.BufferedOutputStream os = new java.io.BufferedOutputStream(raw, 64 * 1024)) {
                byte[] buf = new byte[64 * 1024];
                if (singleFile) {
                    try (FileInputStream in = new FileInputStream(sources.get(0))) {
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            if (cancel.get()) break;
                            os.write(buf, 0, n);
                            done[0] += n;
                            progress.run();
                        }
                    }
                } else {
                    java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(os);
                    zos.setLevel(java.util.zip.Deflater.BEST_SPEED);
                    for (File src : sources) {
                        if (cancel.get()) break;
                        zipInto(zos, src, src.getName(), buf, cancel, done, skipped, progress);
                    }
                    zos.finish();
                    zos.flush();
                }
                os.flush();
            } catch (SecurityException se) {
                error = "没有权限写入/读取：" + se.getMessage();
            } catch (Throwable t) {
                error = t.getMessage() != null ? t.getMessage() : t.toString();
            }
            final String fError = error;
            final boolean cancelled = cancel.get();
            mainHandler.post(() -> {
                try { holder.dialog.dismiss(); } catch (Throwable ignored) {}
                if (cancelled || fError != null) {
                    if (onAbortCleanup != null) {
                        try { onAbortCleanup.run(); } catch (Throwable ignored) {}
                    }
                    Toast.makeText(context, cancelled ? "已取消导出" : "导出失败: " + fError, Toast.LENGTH_LONG).show();
                } else {
                    onDone.done(done[0], skipped[0]);
                }
            });
        }).start();
    }

    private void zipInto(java.util.zip.ZipOutputStream zos, File f, String entry, byte[] buf,
                         java.util.concurrent.atomic.AtomicBoolean cancel, long[] done, int[] skipped,
                         Runnable progress) throws java.io.IOException {
        if (cancel.get() || ProotManager.isSymbolicLink(f)) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids == null) {
                skipped[0]++;
                return;
            }
            if (kids.length == 0) {
                zos.putNextEntry(new java.util.zip.ZipEntry(entry + "/"));
                zos.closeEntry();
                return;
            }
            for (File k : kids) {
                if (cancel.get()) return;
                zipInto(zos, k, entry + "/" + k.getName(), buf, cancel, done, skipped, progress);
            }
            return;
        }
        FileInputStream in;
        try {
            in = new FileInputStream(f);
        } catch (Throwable t) {
            skipped[0]++;
            return;
        }
        try {
            java.util.zip.ZipEntry ze = new java.util.zip.ZipEntry(entry);
            ze.setTime(f.lastModified());
            zos.putNextEntry(ze);
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancel.get()) break;
                zos.write(buf, 0, n);
                done[0] += n;
                progress.run();
            }
            zos.closeEntry();
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
    }

    /** 导出当前目录下某一类别的全部项目。 */
    private void exportCategory(int cat) {
        List<File> files = new ArrayList<>();
        for (FileItem it : allItems) {
            if (it.category == cat && !it.isSymlink) files.add(it.file);
        }
        String dirName = currentDir != null ? currentDir.getName() : "";
        showExportOptions((dirName.isEmpty() ? "" : dirName + "-") + CAT_NAMES[cat].replace("/", "-"), files);
    }

    private void exportItem(FileItem item) {
        if (item == null) return;
        File src = item.isSymlink && item.resolvedTarget != null ? item.resolvedTarget : item.file;
        if (!src.canRead()) {
            Toast.makeText(context, "无权限读取：" + item.name, Toast.LENGTH_SHORT).show();
            return;
        }
        List<File> one = new ArrayList<>();
        one.add(src);
        showExportOptions(item.name, one);
    }

    private static String getFileExt(String name) {
        if (name == null) return "";
        int idx = name.lastIndexOf('.');
        return idx >= 0 ? name.substring(idx + 1).toLowerCase() : "";
    }

    private static boolean isScriptOrCode(String ext) {
        return ext.equals("sh") || ext.equals("js") || ext.equals("ts") || ext.equals("py") || ext.equals("java") || ext.equals("c") || ext.equals("cpp");
    }

    private static boolean isDataOrConfig(String ext) {
        return ext.equals("json") || ext.equals("yaml") || ext.equals("yml") || ext.equals("xml") || ext.equals("conf") || ext.equals("md") || ext.equals("txt") || ext.equals("log");
    }

    /**
     * 弹出目录/文件详细属性对话框（支持长按调出，毫秒级异步递归计算真实目录总大小）
     */
    private void showItemDetailDialog(FileItem item) {
        if (item == null || item.file == null) return;
        AlertDialog.Builder builder = new com.xm486.pimet.ui.Icons.DialogBuilder(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(ThemeManager.createCardDrawable(context, pal, 14f));
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView tvTitle = new TextView(context);
        tvTitle.setText(item.isDirectory ? "📁 目录详细属性" : "📄 文件详细属性");
        tvTitle.setTextColor(pal.text);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        tvTitle.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
        root.addView(tvTitle);

        View divider = new View(context);
        divider.setBackgroundColor(pal.border);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dlp.topMargin = dp(12);
        dlp.bottomMargin = dp(12);
        root.addView(divider, dlp);

        createDetailRow(root, "名称", item.name);
        createDetailRow(root, "当前路径", item.file.getAbsolutePath());
        if (item.isSymlink) {
            createDetailRow(root, "符号链接指向", item.resolvedTarget != null ? item.resolvedTarget.getAbsolutePath() : "未解析");
        }

        String timeStr = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(item.lastModified));
        createDetailRow(root, "修改时间", timeStr);

        if (item.isDirectory) {
            createDetailRow(root, "直接子项数量", item.childCount + " 项");
            final TextView tvSize = createDetailRow(root, "目录递归总占用", "正在后台计算磁盘总占用...");
            new Thread(() -> {
                File target = item.resolvedTarget != null ? item.resolvedTarget : item.file;
                long totalBytes = ProotManager.getDirectorySize(target);
                String formatted = ProotManager.formatSize(totalBytes);
                mainHandler.post(() -> {
                    tvSize.setText(formatted + " (约 " + totalBytes + " 字节)");
                });
            }).start();
        } else {
            createDetailRow(root, "真实文件大小", formatSize(item.size) + " (" + item.size + " 字节)");
        }

        LinearLayout btnRow = new LinearLayout(context);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);
        btnRow.setPadding(0, dp(16), 0, 0);

        AlertDialog d = builder.setView(root).create();

        TextView btnCopy = buildActionBtn("📋 复制路径", 0x223B82F6, 0xFF93C5FD, v -> {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("FilePath", item.file.getAbsolutePath()));
                Toast.makeText(context, "已复制完整路径", Toast.LENGTH_SHORT).show();
            }
        });
        btnRow.addView(btnCopy);

        View sp1 = new View(context);
        btnRow.addView(sp1, new LinearLayout.LayoutParams(dp(8), 1));

        if (item.isDirectory) {
            TextView btnEnter = buildActionBtn("📂 进入目录", 0x2210B981, 0xFFA7F3D0, v -> {
                d.dismiss();
                navigateTo(item.resolvedTarget != null ? item.resolvedTarget : item.file);
            });
            btnRow.addView(btnEnter);
            View sp2 = new View(context);
            btnRow.addView(sp2, new LinearLayout.LayoutParams(dp(8), 1));
        } else {
            if (item.name.toLowerCase().endsWith(".zip")) {
                TextView btnRestore = buildActionBtn("📥 还原此备份", 0x228B5CF6, 0xFFC4B5FD, v -> {
                    d.dismiss();
                    confirmAndImportBackup(item.file);
                });
                btnRow.addView(btnRestore);
                View spR = new View(context);
                btnRow.addView(spR, new LinearLayout.LayoutParams(dp(8), 1));
            }
            TextView btnPreview = buildActionBtn("🔍 预览文件", 0x2210B981, 0xFFA7F3D0, v -> {
                d.dismiss();
                showFilePreviewDialog(item.file);
            });
            btnRow.addView(btnPreview);
            View sp2 = new View(context);
            btnRow.addView(sp2, new LinearLayout.LayoutParams(dp(8), 1));
        }

        TextView btnExport = buildActionBtn("📤 导出", 0, 0, v -> {
            d.dismiss();
            exportItem(item);
        });
        btnRow.addView(btnExport);
        View spE = new View(context);
        btnRow.addView(spE, new LinearLayout.LayoutParams(dp(8), 1));

        TextView btnClose = buildActionBtn("关闭", 0x2230363D, pal.textMuted, v -> d.dismiss());
        btnRow.addView(btnClose);

        // 按钮较多时可横向滑动，避免大字号下挤压换行
        HorizontalScrollView btnScroll = new HorizontalScrollView(context);
        btnScroll.setHorizontalScrollBarEnabled(false);
        btnScroll.setFillViewport(true);
        btnScroll.addView(btnRow, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(btnScroll);
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        d.show();
    }

    private TextView createDetailRow(LinearLayout parent, String label, String value) {
        TextView tvLabel = new TextView(context);
        tvLabel.setText(label + ":");
        tvLabel.setTextColor(pal.textMuted);
        tvLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvLabel.setPadding(0, dp(4), 0, 0);
        parent.addView(tvLabel);

        TextView tvVal = new TextView(context);
        tvVal.setText(value);
        tvVal.setTextColor(pal.text);
        tvVal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tvVal.setTypeface(Typeface.MONOSPACE);
        tvVal.setPadding(0, dp(2), 0, dp(4));
        tvVal.setTextIsSelectable(true);
        parent.addView(tvVal);
        return tvVal;
    }

    /**
     * 弹出文本/配置文件安全预览框
     */
    private void showFilePreviewDialog(File file) {
        if (file == null || !file.exists() || file.isDirectory()) return;

        AlertDialog.Builder builder = new com.xm486.pimet.ui.Icons.DialogBuilder(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(ThemeManager.createCardDrawable(context, pal, 14f));
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        // 顶栏
        LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(context);
        title.setText("📄 " + file.getName());
        title.setTextColor(pal.text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        title.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnCopy = buildActionBtn("📋 复制路径", 0x223B82F6, 0xFF93C5FD, v -> {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("FilePath", file.getAbsolutePath()));
                Toast.makeText(context, "已复制路径到剪切板", Toast.LENGTH_SHORT).show();
            }
        });
        top.addView(btnCopy);

        root.addView(top);

        // 属性信息
        TextView tvMeta = new TextView(context);
        tvMeta.setText(String.format(Locale.getDefault(), "大小: %s (%d bytes)  ·  路径: %s",
                formatSize(file.length()), file.length(), file.getAbsolutePath()));
        tvMeta.setTextColor(pal.textMuted);
        tvMeta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvMeta.setPadding(0, dp(4), 0, dp(8));
        root.addView(tvMeta);

        boolean isZip = file.getName().toLowerCase().endsWith(".zip");
        final AlertDialog[] previewDialogRef = new AlertDialog[1];
        if (isZip) {
            TextView btnRestoreThis = buildActionBtn("📥 一键无损还原此配置包 (无损迁移)", 0x228B5CF6, 0xFFC4B5FD, v -> {
                if (previewDialogRef[0] != null) previewDialogRef[0].dismiss();
                confirmAndImportBackup(file);
            });
            LinearLayout.LayoutParams rblp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rblp.bottomMargin = dp(8);
            root.addView(btnRestoreThis, rblp);
        }

        // 内容显示区（限制只读前 64KB 文本，避免超大文件卡死）
        ScrollView sv = new ScrollView(context);
        TextView tvContent = new TextView(context);
        tvContent.setTextColor(pal.text);
        tvContent.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvContent.setTypeface(Typeface.MONOSPACE);
        tvContent.setPadding(dp(8), dp(8), dp(8), dp(8));

        GradientDrawable cbg = new GradientDrawable();
        cbg.setColor(pal.bgSunken);
        cbg.setCornerRadius(dp(6));
        cbg.setStroke(dp(1), pal.border);
        tvContent.setBackground(cbg);

        if (isZip) {
            tvContent.setText("[ZIP 压缩归档包]\n\n此文件为 ZIP 归档。点击上方「📥 一键无损还原此配置包」可快速校验并将其作为 PiMet 迁移包还原至当前环境（涵盖聊天记忆、子代理、插件生态及全部应用偏好）。");
        } else if (file.length() > 5 * 1024 * 1024) {
            tvContent.setText("[文件过大 (" + formatSize(file.length()) + ")，请通过终端查看]");
        } else {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                int lineCount = 0;
                while ((line = reader.readLine()) != null && lineCount < 800) {
                    sb.append(line).append("\n");
                    lineCount++;
                }
                if (lineCount >= 800) {
                    sb.append("\n... [已截取前 800 行]");
                }
                tvContent.setText(sb.toString());
            } catch (Throwable t) {
                tvContent.setText("[二进制或无法解析为文本内容: " + t.getMessage() + "]");
            }
        }

        sv.addView(tvContent);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        svLp.bottomMargin = dp(8);
        root.addView(sv, svLp);

        builder.setView(root);
        AlertDialog previewDialog = builder.create();
        previewDialogRef[0] = previewDialog;

        Window window = previewDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.92f);
            lp.height = (int) (context.getResources().getDisplayMetrics().heightPixels * 0.75f);
            window.setAttributes(lp);
        }

        previewDialog.show();
    }

    // =========================================================================
    // 📦 数据与配置无损迁移 (Backup / Restore Migration)
    // =========================================================================

    private static class ProgressDialogHolder {
        AlertDialog dialog;
        TextView tvTitle;
        TextView tvStage;
        TextView tvDetail;
        ProgressBar progressBar;
    }

    private ProgressDialogHolder showBackupProgressDialog(String title) {
        AlertDialog.Builder builder = new com.xm486.pimet.ui.Icons.DialogBuilder(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(ThemeManager.createCardDrawable(context, pal, 14f));
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView tvTitle = new TextView(context);
        tvTitle.setText(title);
        tvTitle.setTextColor(pal.text);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        tvTitle.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
        root.addView(tvTitle);

        TextView tvStage = new TextView(context);
        tvStage.setText("准备中...");
        tvStage.setTextColor(pal.accent);
        tvStage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tvStage.setPadding(0, dp(8), 0, dp(4));
        root.addView(tvStage);

        ProgressBar progressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        LinearLayout.LayoutParams pblp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        pblp.topMargin = dp(4);
        pblp.bottomMargin = dp(8);
        root.addView(progressBar, pblp);

        TextView tvDetail = new TextView(context);
        tvDetail.setText("");
        tvDetail.setTextColor(pal.textMuted);
        tvDetail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvDetail.setTypeface(Typeface.MONOSPACE);
        tvDetail.setSingleLine(true);
        tvDetail.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        root.addView(tvDetail);

        builder.setView(root);
        builder.setCancelable(false);
        AlertDialog d = builder.create();

        Window window = d.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.90f);
            window.setAttributes(lp);
        }
        d.show();

        ProgressDialogHolder holder = new ProgressDialogHolder();
        holder.dialog = d;
        holder.tvTitle = tvTitle;
        holder.tvStage = tvStage;
        holder.tvDetail = tvDetail;
        holder.progressBar = progressBar;
        return holder;
    }

    public void showBackupMigrationDialog() {
        AlertDialog.Builder builder = new com.xm486.pimet.ui.Icons.DialogBuilder(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(ThemeManager.createCardDrawable(context, pal, 14f));
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView tvTitle = new TextView(context);
        tvTitle.setText("📦 PiMet 配置与数据无损迁移");
        tvTitle.setTextColor(pal.text);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        tvTitle.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
        root.addView(tvTitle);

        TextView tvDesc = new TextView(context);
        tvDesc.setText("支持将当前的聊天记忆、子代理、插件生态、端口及全部偏好设置打包导出为 zip 文件，或将备份包无损还原至当前环境。");
        tvDesc.setTextColor(pal.textMuted);
        tvDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tvDesc.setPadding(0, dp(4), 0, dp(16));
        root.addView(tvDesc);

        final AlertDialog[] diagRef = new AlertDialog[1];

        // 按钮 1: 立即打包导出
        TextView btnExport = buildActionBtn("📤 快速全量导出备份 (.zip)", 0x223B82F6, 0xFF93C5FD, v -> {
            if (diagRef[0] != null) diagRef[0].dismiss();
            performExportBackup();
        });
        btnExport.setPadding(dp(12), dp(12), dp(12), dp(12));
        btnExport.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.bottomMargin = dp(8);
        root.addView(btnExport, elp);

        // 按钮 2: 自选模块与目标目录导出
        TextView btnCustomExport = buildActionBtn("⚙️ 自选模块与目标目录导出 (.zip)", 0x2206B6D4, 0xFF67E8F9, v -> {
            if (diagRef[0] != null) diagRef[0].dismiss();
            showCustomExportDialog();
        });
        btnCustomExport.setPadding(dp(12), dp(12), dp(12), dp(12));
        btnCustomExport.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams celp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        celp.bottomMargin = dp(8);
        root.addView(btnCustomExport, celp);

        // 按钮 2: 自主选取手机中的备份包 (调起系统文件选择器)
        TextView btnPick = buildActionBtn("📁 自主选取并上传手机备份包 (.zip)", 0x228B5CF6, 0xFFC4B5FD, v -> {
            if (diagRef[0] != null) diagRef[0].dismiss();
            if (context instanceof com.xm486.pimet.MainActivity) {
                ((com.xm486.pimet.MainActivity) context).launchBackupFilePicker();
            } else if (context instanceof android.app.Activity) {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                String[] mimes = {"application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*"};
                intent.putExtra(Intent.EXTRA_MIME_TYPES, mimes);
                ((android.app.Activity) context).startActivityForResult(
                        Intent.createChooser(intent, "选择 PiMet 备份压缩包 (.zip)"),
                        2001
                );
            }
        });
        btnPick.setPadding(dp(12), dp(12), dp(12), dp(12));
        btnPick.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.bottomMargin = dp(8);
        root.addView(btnPick, plp);

        // 按钮 3: 扫描当前目录或下载目录中的备份包
        TextView btnScan = buildActionBtn("📥 从当前目录或下载目录扫描还原", 0x2210B981, 0xFFA7F3D0, v -> {
            if (diagRef[0] != null) diagRef[0].dismiss();
            showRestorePicker();
        });
        btnScan.setPadding(dp(12), dp(12), dp(12), dp(12));
        btnScan.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = dp(8);
        root.addView(btnScan, slp);

        TextView btnClose = buildActionBtn("取消", 0x2230363D, pal.textMuted, v -> {
            if (diagRef[0] != null) diagRef[0].dismiss();
        });
        btnClose.setPadding(dp(12), dp(8), dp(12), dp(8));
        btnClose.setGravity(Gravity.CENTER);
        root.addView(btnClose, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        builder.setView(root);
        AlertDialog d = builder.create();
        diagRef[0] = d;

        Window window = d.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.90f);
            window.setAttributes(lp);
        }
        d.show();
    }

    private void performExportBackup() {
        performExportBackup(null);
    }

    private void performExportBackup(BackupManager.ExportOptions options) {
        ProgressDialogHolder holder = showBackupProgressDialog("📦 正在打包配置与数据...");

        BackupManager.ExportOptions opts = options != null ? options : new BackupManager.ExportOptions();

        // 如果用户未显式指定目标文件，但当前正在浏览某个可写目录（如 Downloads 或外部存储），优先存入当前目录
        if (opts.targetOutFile == null && currentDir != null && currentDir.canWrite()) {
            opts.targetOutFile = new File(currentDir, BackupManager.generateBackupFileName());
        }

        BackupManager.exportBackup(context, opts, (percent, stage, logLine) -> {
            holder.progressBar.setProgress(percent);
            holder.tvStage.setText(stage + " (" + percent + "%)");
            holder.tvDetail.setText(logLine);
        }, (success, file, message) -> {
            holder.dialog.dismiss();

            AlertDialog.Builder resB = new com.xm486.pimet.ui.Icons.DialogBuilder(context);
            LinearLayout r = new LinearLayout(context);
            r.setOrientation(LinearLayout.VERTICAL);
            r.setBackground(ThemeManager.createCardDrawable(context, pal, 14f));
            r.setPadding(dp(16), dp(16), dp(16), dp(16));

            TextView t = new TextView(context);
            t.setText(success ? "✔ 备份导出成功" : "❌ 备份导出失败");
            t.setTextColor(success ? ThemeManager.statusSuccessColor(pal) : ThemeManager.statusDangerColor(pal));
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
            t.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
            r.addView(t);

            TextView msg = new TextView(context);
            msg.setText(message);
            msg.setTextColor(pal.text);
            msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            msg.setPadding(0, dp(8), 0, dp(16));
            r.addView(msg);

            final AlertDialog[] resDiagRef = new AlertDialog[1];

            if (success && file != null) {
                TextView btnShare = buildActionBtn("📤 分享 / 发送备份包", 0x228B5CF6, 0xFFC4B5FD, v -> {
                    BackupManager.shareBackupFile(context, file);
                });
                btnShare.setPadding(dp(12), dp(8), dp(12), dp(8));
                btnShare.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams splp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                splp.bottomMargin = dp(8);
                r.addView(btnShare, splp);

                TextView btnCopy = buildActionBtn("📋 复制备份路径", 0x223B82F6, 0xFF93C5FD, v -> {
                    ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("BackupPath", file.getAbsolutePath()));
                        Toast.makeText(context, "已复制路径到剪切板", Toast.LENGTH_SHORT).show();
                    }
                });
                btnCopy.setPadding(dp(12), dp(8), dp(12), dp(8));
                btnCopy.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams cplp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cplp.bottomMargin = dp(8);
                r.addView(btnCopy, cplp);

                loadCurrentDir();
            }

            TextView btnOk = buildActionBtn("确定", 0x2230363D, pal.textMuted, v -> {
                if (resDiagRef[0] != null) resDiagRef[0].dismiss();
            });
            btnOk.setPadding(dp(12), dp(8), dp(12), dp(8));
            btnOk.setGravity(Gravity.CENTER);
            r.addView(btnOk, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            resB.setView(r);
            AlertDialog d = resB.create();
            resDiagRef[0] = d;
            d.show();
        });
    }

    public void showCustomExportDialog() {
        AlertDialog.Builder builder = new com.xm486.pimet.ui.Icons.DialogBuilder(context);
        ScrollView scroll = new ScrollView(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(ThemeManager.createCardDrawable(context, pal, 14f));
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        scroll.addView(root);

        TextView tvTitle = new TextView(context);
        tvTitle.setText("⚙️ 自选备份导出设置");
        tvTitle.setTextColor(pal.text);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        tvTitle.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
        root.addView(tvTitle);

        TextView tvSub = new TextView(context);
        tvSub.setText("请勾选需要导出的数据模块，并选择备份保存的目标目录：");
        tvSub.setTextColor(pal.textMuted);
        tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvSub.setPadding(0, dp(4), 0, dp(12));
        root.addView(tvSub);

        // 模块选择
        TextView tvSec1 = createSectionHeader("1. 选择导出模块");
        root.addView(tvSec1);

        CheckBox cbPrefs = createCheckBox("应用偏好与端口配置 (SharedPreferences)", "端口映射、API 密钥、桌宠参数等", true);
        root.addView(cbPrefs);

        CheckBox cbSessions = createCheckBox("聊天历史与会话记忆 (sessions)", "包含所有历史对话、上下文及会话索引", true);
        root.addView(cbSessions);

        CheckBox cbAgents = createCheckBox("子代理配置与预设规则 (agents)", "pet-companion 等独立子代理角色与提示词", true);
        root.addView(cbAgents);

        CheckBox cbSkills = createCheckBox("技能、插件与扩展生态 (skills, npm)", "已安装的扩展插件、自定义 Skill 与 npm 模块", true);
        root.addView(cbSkills);

        CheckBox cbSettings = createCheckBox("模型配置与认证 (settings.json, auth.json)", "全局模型参数、provider 配置与认证 token", true);
        root.addView(cbSettings);

        CheckBox cbPets = createCheckBox("自定义桌宠皮肤与资源 (custom_pets)", "自定义导入的桌宠皮肤素材包", true);
        root.addView(cbPets);

        CheckBox cbPiCwd = createCheckBox("用户工作区工程代码 (root/pi-cwd)", "包含用户编写的代码文件与本地工程项目", true);
        root.addView(cbPiCwd);

        // 如果 root/pi-cwd 存在子目录，列出子目录可供细粒度选择
        File rootfs = ProotManager.getRootfsDir(context);
        File piCwdDir = new File(rootfs, "root/pi-cwd");
        final List<CheckBox> cwdSubBoxes = new ArrayList<>();
        if (piCwdDir.exists() && piCwdDir.isDirectory()) {
            File[] subProjects = piCwdDir.listFiles(File::isDirectory);
            if (subProjects != null && subProjects.length > 0) {
                LinearLayout cwdSubLayout = new LinearLayout(context);
                cwdSubLayout.setOrientation(LinearLayout.VERTICAL);
                cwdSubLayout.setPadding(dp(16), dp(4), 0, dp(8));
                TextView tvCwdSubHint = new TextView(context);
                tvCwdSubHint.setText("可选：细粒度指定要导出的工程子目录 (不勾选则默认全部)：");
                tvCwdSubHint.setTextColor(pal.accent);
                tvCwdSubHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
                cwdSubLayout.addView(tvCwdSubHint);

                for (File sp : subProjects) {
                    CheckBox scb = createCheckBox("📁 " + sp.getName(), sp.getAbsolutePath(), false);
                    scb.setTag(sp.getName());
                    cwdSubBoxes.add(scb);
                    cwdSubLayout.addView(scb);
                }
                root.addView(cwdSubLayout);

                cbPiCwd.setOnCheckedChangeListener((btn, isChecked) -> {
                    cwdSubLayout.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                });
            }
        }

        // Section 2: 目标导出位置
        TextView tvSec2 = createSectionHeader("2. 选择目标导出目录");
        root.addView(tvSec2);

        // 默认目标目录
        final File[] selectedTargetDir = new File[1];
        if (currentDir != null && currentDir.canWrite()) {
            selectedTargetDir[0] = currentDir;
        } else {
            selectedTargetDir[0] = BackupManager.getExportDirectory(context);
        }

        TextView tvTargetDirPath = new TextView(context);
        tvTargetDirPath.setText("📂 " + selectedTargetDir[0].getAbsolutePath());
        tvTargetDirPath.setTextColor(pal.accent);
        tvTargetDirPath.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvTargetDirPath.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable pathBg = new GradientDrawable();
        pathBg.setColor(pal.bgSunken);
        pathBg.setCornerRadius(dp(6));
        pathBg.setStroke(dp(1), pal.border);
        tvTargetDirPath.setBackground(pathBg);
        root.addView(tvTargetDirPath);

        // 目标目录快速切换按钮组
        LinearLayout dirBtnRow = new LinearLayout(context);
        dirBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        dirBtnRow.setPadding(0, dp(8), 0, dp(12));

        TextView btnUseCurrent = buildActionBtn("📍 设为当前浏览目录", 0x22388BFD, pal.accent, v -> {
            if (currentDir != null && currentDir.canWrite()) {
                selectedTargetDir[0] = currentDir;
                tvTargetDirPath.setText("📂 " + selectedTargetDir[0].getAbsolutePath());
                Toast.makeText(context, "已设为当前目录", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, "当前目录不可写或无效", Toast.LENGTH_SHORT).show();
            }
        });
        dirBtnRow.addView(btnUseCurrent);

        TextView btnUseDownloads = buildActionBtn("📥 系统下载目录", 0x22238636, 0xFF7EE787, v -> {
            File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dl != null && dl.exists()) {
                selectedTargetDir[0] = dl;
                tvTargetDirPath.setText("📂 " + selectedTargetDir[0].getAbsolutePath());
                Toast.makeText(context, "已设为系统下载目录", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, "下载目录不可用", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.leftMargin = dp(8);
        dirBtnRow.addView(btnUseDownloads, dlp);

        TextView btnUseAppExport = buildActionBtn("📦 内部默认目录", 0x228B5CF6, 0xFFC4B5FD, v -> {
            selectedTargetDir[0] = BackupManager.getExportDirectory(context);
            tvTargetDirPath.setText("📂 " + selectedTargetDir[0].getAbsolutePath());
            Toast.makeText(context, "已设为内部备份目录", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = dp(8);
        dirBtnRow.addView(btnUseAppExport, alp);

        root.addView(dirBtnRow);

        // Section 3: 额外容器自定义路径 (可选)
        TextView tvSec3 = createSectionHeader("3. 额外容器路径 (可选，英文逗号分隔)");
        root.addView(tvSec3);

        EditText etCustomPaths = new EditText(context);
        etCustomPaths.setHint("如: root/my_scripts, etc (可选)");
        etCustomPaths.setHintTextColor(pal.textDim);
        etCustomPaths.setTextColor(pal.text);
        etCustomPaths.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        etCustomPaths.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable etBg = new GradientDrawable();
        etBg.setColor(pal.bgSunken);
        etBg.setCornerRadius(dp(6));
        etBg.setStroke(dp(1), pal.border);
        etCustomPaths.setBackground(etBg);
        root.addView(etCustomPaths);

        final AlertDialog[] customDiagRef = new AlertDialog[1];

        // 底部动作按钮
        LinearLayout actRow = new LinearLayout(context);
        actRow.setOrientation(LinearLayout.HORIZONTAL);
        actRow.setPadding(0, dp(16), 0, 0);

        TextView btnCancel = buildActionBtn("取消", 0x2230363D, pal.textMuted, v -> {
            if (customDiagRef[0] != null) customDiagRef[0].dismiss();
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.rightMargin = dp(8);
        actRow.addView(btnCancel, clp);

        TextView btnConfirm = buildActionBtn("🚀 开始打包导出", 0x22238636, 0xFF7EE787, v -> {
            BackupManager.ExportOptions options = new BackupManager.ExportOptions();
            options.includeSharedPrefs = cbPrefs.isChecked();
            options.includeSessions = cbSessions.isChecked();
            options.includeSubAgents = cbAgents.isChecked();
            options.includeSkillsAndPlugins = cbSkills.isChecked();
            options.includeSettingsAndAuth = cbSettings.isChecked();
            options.includeCustomPets = cbPets.isChecked();
            options.includePiCwd = cbPiCwd.isChecked();

            for (CheckBox scb : cwdSubBoxes) {
                if (scb.isChecked() && scb.getTag() != null) {
                    options.selectedPiCwdSubDirs.add(scb.getTag().toString());
                }
            }

            String customInput = etCustomPaths.getText().toString().trim();
            if (!customInput.isEmpty()) {
                String[] parts = customInput.split(",");
                for (String p : parts) {
                    p = p.trim();
                    if (!p.isEmpty()) options.extraCustomDirs.add(p);
                }
            }

            File targetDir = selectedTargetDir[0];
            if (targetDir == null || !targetDir.exists()) {
                targetDir = BackupManager.getExportDirectory(context);
            }
            options.targetOutFile = new File(targetDir, BackupManager.generateBackupFileName());

            if (customDiagRef[0] != null) customDiagRef[0].dismiss();
            performExportBackup(options);
        });
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f);
        actRow.addView(btnConfirm, flp);

        root.addView(actRow);

        builder.setView(scroll);
        AlertDialog d = builder.create();
        customDiagRef[0] = d;

        Window window = d.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.94f);
            lp.height = (int) (context.getResources().getDisplayMetrics().heightPixels * 0.85f);
            window.setAttributes(lp);
        }
        d.show();
    }

    private TextView createSectionHeader(String title) {
        TextView tv = new TextView(context);
        tv.setText(title);
        tv.setTextColor(pal.text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
        tv.setPadding(0, dp(12), 0, dp(4));
        return tv;
    }

    private CheckBox createCheckBox(String title, String subtitle, boolean checked) {
        CheckBox cb = new CheckBox(context);
        SpannableStringBuilder ssb = new SpannableStringBuilder();
        ssb.append(title);
        if (!TextUtils.isEmpty(subtitle)) {
            ssb.append("\n");
            int start = ssb.length();
            ssb.append(subtitle);
            ssb.setSpan(new ForegroundColorSpan(pal.textMuted), start, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            ssb.setSpan(new RelativeSizeSpan(0.85f), start, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        cb.setText(ssb);
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        cb.setTextColor(pal.text);
        cb.setChecked(checked);
        cb.setPadding(dp(4), dp(4), dp(4), dp(4));
        return cb;
    }

    private void showRestorePicker() {
        List<File> zipCandidates = new ArrayList<>();

        // 1. 扫描当前目录下的 .zip 文件
        if (currentDir != null && currentDir.exists()) {
            File[] files = currentDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && f.getName().toLowerCase().endsWith(".zip")) {
                        zipCandidates.add(f);
                    }
                }
            }
        }

        // 2. 扫描系统 Downloads 目录下的 PiMet-Backup-*.zip
        try {
            File dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dlDir != null && dlDir.exists() && dlDir != currentDir) {
                File[] files = dlDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.isFile() && f.getName().toLowerCase().endsWith(".zip") && !zipCandidates.contains(f)) {
                            zipCandidates.add(f);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        if (zipCandidates.isEmpty()) {
            new com.xm486.pimet.ui.Icons.DialogBuilder(context)
                    .setTitle("未发现备份包")
                    .setMessage("当前浏览目录及系统 Downloads 文件夹中未发现 .zip 格式备份文件。\n\n提示：您可在文件浏览器中先导航至包含备份包的文件夹，点击该 .zip 文件即可一键无损还原。")
                    .setPositiveButton("我知道了", null)
                    .show();
            return;
        }

        String[] names = new String[zipCandidates.size()];
        for (int i = 0; i < zipCandidates.size(); i++) {
            File f = zipCandidates.get(i);
            names[i] = f.getName() + " (" + formatSize(f.length()) + ")";
        }

        new com.xm486.pimet.ui.Icons.DialogBuilder(context)
                .setTitle("选择要导入还原的备份包")
                .setItems(names, (d, which) -> {
                    if (which >= 0 && which < zipCandidates.size()) {
                        confirmAndImportBackup(zipCandidates.get(which));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    public void confirmAndImportBackup(File zipFile) {
        if (zipFile == null || !zipFile.exists()) {
            Toast.makeText(context, "备份文件不存在", Toast.LENGTH_SHORT).show();
            return;
        }

        new com.xm486.pimet.ui.Icons.DialogBuilder(context)
                .setTitle("⚠️ 确认无损还原备份包？")
                .setMessage("即将从以下文件还原全量数据：\n" + zipFile.getName() + " (" + formatSize(zipFile.length()) + ")\n\n包含内容：\n• 桌面宠物聊天记忆与好感度\n• 全部子代理配置与技能生态\n• NPM 插件与扩展依赖清单\n• 本地多服务端口与偏好设置\n\n现有环境的同名配置将被安全覆盖同步。是否继续？")
                .setPositiveButton("立即还原", (d, which) -> {
                    ProgressDialogHolder holder = showBackupProgressDialog("📥 正在无损还原配置与数据...");
                    BackupManager.importBackup(context, zipFile, (percent, stage, logLine) -> {
                        holder.progressBar.setProgress(percent);
                        holder.tvStage.setText(stage + " (" + percent + "%)");
                        holder.tvDetail.setText(logLine);
                    }, (success, restoredCount, message) -> {
                        holder.dialog.dismiss();

                        new com.xm486.pimet.ui.Icons.DialogBuilder(context)
                                .setTitle(success ? "🎉 还原完成" : "❌ 还原失败")
                                .setMessage(message + (success ? "\n\n建议重启相关后台服务或刷新界面以使所有新配置完全生效。" : ""))
                                .setPositiveButton("确定", (d2, w2) -> {
                                    loadCurrentDir();
                                })
                                .show();
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }
}

