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

    public static class FileItem {
        public final File file;
        public final boolean isDirectory;
        public final boolean isSymlink;
        public final File resolvedTarget;
        public final String name;
        public final long size;
        public final long lastModified;
        public final int childCount;

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

    public void show() {
        if (dialog != null && dialog.isShowing()) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0D1117);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        // ---- 1. 顶栏：标题 + 上级 + 刷新 + 关闭 ----
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvTitle = new TextView(context);
        tvTitle.setText("📁 内部文件浏览器");
        tvTitle.setTextColor(0xFFF0F6FC);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(tvTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnUp = buildActionBtn("⬆️ 上级", 0x223B82F6, 0xFF93C5FD, v -> navigateUp());
        header.addView(btnUp);

        View spaceH1 = new View(context);
        header.addView(spaceH1, new LinearLayout.LayoutParams(dp(6), 1));

        TextView btnRefresh = buildActionBtn("🔄 刷新", 0x2210B981, 0xFFA7F3D0, v -> refresh());
        header.addView(btnRefresh);

        View spaceH2 = new View(context);
        header.addView(spaceH2, new LinearLayout.LayoutParams(dp(6), 1));

        TextView btnBackup = buildActionBtn("📦 迁移备份", 0x228B5CF6, 0xFFC4B5FD, v -> showBackupMigrationDialog());
        header.addView(btnBackup);

        View spaceH3 = new View(context);
        header.addView(spaceH3, new LinearLayout.LayoutParams(dp(6), 1));

        TextView btnClose = buildActionBtn("✕ 关闭", 0x22EF4444, 0xFFFCA5A5, v -> {
            if (dialog != null) dialog.dismiss();
        });
        header.addView(btnClose);

        root.addView(header);

        // ---- 2. 快捷路径芯片横条 ----
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

        // ---- 3. 当前路径导航指示与摘要卡片 ----
        LinearLayout pathCard = new LinearLayout(context);
        pathCard.setOrientation(LinearLayout.VERTICAL);
        pathCard.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable pcd = new GradientDrawable();
        pcd.setColor(0xFF161B22);
        pcd.setCornerRadius(dp(6));
        pcd.setStroke(dp(1), 0xFF30363D);
        pathCard.setBackground(pcd);

        tvCurrentPath = new TextView(context);
        tvCurrentPath.setTextColor(0xFF58A6FF);
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
        tvPathSummary.setTextColor(0xFF8B949E);
        tvPathSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        tvPathSummary.setPadding(0, dp(3), 0, 0);
        pathCard.addView(tvPathSummary);

        LinearLayout.LayoutParams pcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pcLp.topMargin = dp(2);
        pcLp.bottomMargin = dp(8);
        root.addView(pathCard, pcLp);

        // ---- 4. 文件列表 ListView ----
        listView = new ListView(context);
        listView.setDivider(null);
        listView.setDividerHeight(dp(4));
        listView.setVerticalScrollBarEnabled(true);
        adapter = new FileAdapter();
        listView.setAdapter(adapter);

        listView.setOnItemClickListener((parent, view, position, id) -> {
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

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.94f);
            lp.height = (int) (context.getResources().getDisplayMetrics().heightPixels * 0.86f);
            window.setAttributes(lp);
        }

        dialog.show();
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
            loadCurrentDir();
            return;
        }
        if (dir.exists() && dir.isDirectory()) {
            this.currentDir = dir;
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
                loadCurrentDir();
            } else {
                Toast.makeText(context, "已是根目录", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void refresh() {
        loadCurrentDir();
    }

    private void loadCurrentDir() {
        if (currentDir == null) return;

        // 计算相对根路径的友好显示名
        String displayPath;
        File rootfs = ProotManager.getRootfsDir(context);
        if (rootfs != null && currentDir.getAbsolutePath().startsWith(rootfs.getAbsolutePath())) {
            String sub = currentDir.getAbsolutePath().substring(rootfs.getAbsolutePath().length());
            displayPath = sub.isEmpty() ? "/" : sub;
        } else {
            displayPath = currentDir.getAbsolutePath();
        }

        if (tvCurrentPath != null) {
            tvCurrentPath.setText("路径: " + displayPath);
        }

        new Thread(() -> {
            File[] files = currentDir.listFiles();
            List<FileItem> list = new ArrayList<>();
            long dirTotalSize = 0;
            int dirCount = 0;
            int fileCount = 0;

            if (files != null) {
                // 排序：文件夹优先，接着按名称不区分大小写升序
                Arrays.sort(files, (f1, f2) -> {
                    if (f1.isDirectory() && !f2.isDirectory()) return -1;
                    if (!f1.isDirectory() && f2.isDirectory()) return 1;
                    return f1.getName().compareToIgnoreCase(f2.getName());
                });

                for (File f : files) {
                    FileItem item = new FileItem(f, rootDir);
                    list.add(item);
                    if (item.isDirectory) {
                        dirCount++;
                    } else {
                        fileCount++;
                    }
                    dirTotalSize += item.size;
                }
            }

            final int fDirCount = dirCount;
            final int fFileCount = fileCount;
            final long fTotalSize = dirTotalSize;

            mainHandler.post(() -> {
                fileItems.clear();
                fileItems.addAll(list);
                if (adapter != null) adapter.notifyDataSetChanged();
                if (tvPathSummary != null) {
                    if (fFileCount == 0 && fDirCount > 0) {
                        tvPathSummary.setText(String.format(Locale.getDefault(),
                                "📊 %d 个目录 · 0 个直接文件 · (长按项目可查看深度属性与递归大小)",
                                fDirCount));
                    } else {
                        tvPathSummary.setText(String.format(Locale.getDefault(),
                                "📊 %d 个目录 · %d 个文件 · 直接文件共 %s · (长按项目可查看详情)",
                                fDirCount, fFileCount, formatSize(fTotalSize)));
                    }
                }
                if (list.isEmpty()) {
                    Toast.makeText(context, "当前目录为空", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private TextView buildActionBtn(String text, int bg, int textColor, View.OnClickListener l) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(textColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(dp(8), dp(4), dp(8), dp(4));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bg);
        gd.setCornerRadius(dp(5));
        tv.setBackground(gd);
        tv.setOnClickListener(l);
        return tv;
    }

    private TextView buildChipBtn(String text, View.OnClickListener l) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(0xFFC9D1D9);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        tv.setPadding(dp(8), dp(3), dp(8), dp(3));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(0xFF21262D);
        gd.setCornerRadius(dp(12));
        gd.setStroke(dp(1), 0xFF30363D);
        tv.setBackground(gd);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        tv.setLayoutParams(lp);
        tv.setOnClickListener(l);
        return tv;
    }

    public static String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        if (digitGroups >= units.length) digitGroups = units.length - 1;
        return String.format(Locale.getDefault(), "%.1f %s",
                bytes / Math.pow(1024, digitGroups), units[digitGroups]);
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
                row.setPadding(dp(10), dp(8), dp(10), dp(8));
                GradientDrawable gd = new GradientDrawable();
                gd.setColor(0xFF161B22);
                gd.setCornerRadius(dp(6));
                row.setBackground(gd);

                TextView icon = new TextView(context);
                icon.setTag("icon");
                icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
                row.addView(icon, new LinearLayout.LayoutParams(dp(26), ViewGroup.LayoutParams.WRAP_CONTENT));

                LinearLayout textCol = new LinearLayout(context);
                textCol.setOrientation(LinearLayout.VERTICAL);

                TextView name = new TextView(context);
                name.setTag("name");
                name.setTextColor(0xFFF0F6FC);
                name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
                name.setTypeface(Typeface.DEFAULT_BOLD);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                textCol.addView(name);

                TextView meta = new TextView(context);
                meta.setTag("meta");
                meta.setTextColor(0xFF8B949E);
                meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
                meta.setPadding(0, dp(2), 0, 0);
                textCol.addView(meta);

                row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView arrow = new TextView(context);
                arrow.setTag("arrow");
                arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
                arrow.setTextColor(0xFF484F58);
                row.addView(arrow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                row = (LinearLayout) convertView;
            }

            FileItem item = fileItems.get(position);
            TextView icon = row.findViewWithTag("icon");
            TextView name = row.findViewWithTag("name");
            TextView meta = row.findViewWithTag("meta");
            TextView arrow = row.findViewWithTag("arrow");

            name.setText(item.name);
            String timeStr = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(item.lastModified));

            if (item.isDirectory) {
                icon.setText(item.isSymlink ? "🔗" : "📁");
                name.setTextColor(item.isSymlink ? 0xFF79C0FF : 0xFF58A6FF);
                meta.setText(String.format(Locale.getDefault(), "[%s · %d 项]  ·  %s",
                        item.isSymlink ? "快捷链接目录" : "文件夹", item.childCount, timeStr));
                arrow.setText("›");
                arrow.setTextColor(0xFF58A6FF);
            } else {
                String ext = getFileExt(item.name);
                if (isScriptOrCode(ext)) {
                    icon.setText("📜");
                } else if (isDataOrConfig(ext)) {
                    icon.setText("⚙️");
                } else {
                    icon.setText("📄");
                }
                name.setTextColor(0xFFE6EDF3);
                meta.setText(String.format(Locale.getDefault(), "大小: %s  ·  %s", formatSize(item.size), timeStr));
                arrow.setText("🔍");
                arrow.setTextColor(0xFF8B949E);
            }

            return row;
        }
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
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0D1117);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView tvTitle = new TextView(context);
        tvTitle.setText(item.isDirectory ? "📁 目录详细属性" : "📄 文件详细属性");
        tvTitle.setTextColor(0xFFF0F6FC);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(tvTitle);

        View divider = new View(context);
        divider.setBackgroundColor(0xFF30363D);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dlp.topMargin = dp(10);
        dlp.bottomMargin = dp(10);
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
        btnRow.setPadding(0, dp(14), 0, 0);

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

        TextView btnClose = buildActionBtn("关闭", 0x2230363D, 0xFF8B949E, v -> d.dismiss());
        btnRow.addView(btnClose);

        root.addView(btnRow);
        d.show();
    }

    private TextView createDetailRow(LinearLayout parent, String label, String value) {
        TextView tvLabel = new TextView(context);
        tvLabel.setText(label + ":");
        tvLabel.setTextColor(0xFF8B949E);
        tvLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvLabel.setPadding(0, dp(4), 0, 0);
        parent.addView(tvLabel);

        TextView tvVal = new TextView(context);
        tvVal.setText(value);
        tvVal.setTextColor(0xFFE6EDF3);
        tvVal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tvVal.setTypeface(Typeface.MONOSPACE);
        tvVal.setPadding(0, dp(1), 0, dp(4));
        tvVal.setTextIsSelectable(true);
        parent.addView(tvVal);
        return tvVal;
    }

    /**
     * 弹出文本/配置文件安全预览框
     */
    private void showFilePreviewDialog(File file) {
        if (file == null || !file.exists() || file.isDirectory()) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0D1117);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        // 顶栏
        LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(context);
        title.setText("📄 " + file.getName());
        title.setTextColor(0xFFF0F6FC);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
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
        tvMeta.setTextColor(0xFF8B949E);
        tvMeta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
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
        tvContent.setTextColor(0xFFC9D1D9);
        tvContent.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvContent.setTypeface(Typeface.MONOSPACE);
        tvContent.setPadding(dp(8), dp(8), dp(8), dp(8));

        GradientDrawable cbg = new GradientDrawable();
        cbg.setColor(0xFF161B22);
        cbg.setCornerRadius(dp(6));
        cbg.setStroke(dp(1), 0xFF30363D);
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
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0D1117);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView tvTitle = new TextView(context);
        tvTitle.setText(title);
        tvTitle.setTextColor(0xFFF0F6FC);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(tvTitle);

        TextView tvStage = new TextView(context);
        tvStage.setText("准备中...");
        tvStage.setTextColor(0xFF58A6FF);
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
        tvDetail.setTextColor(0xFF8B949E);
        tvDetail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
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
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0D1117);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView tvTitle = new TextView(context);
        tvTitle.setText("📦 PiMet 配置与数据无损迁移");
        tvTitle.setTextColor(0xFFF0F6FC);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(tvTitle);

        TextView tvDesc = new TextView(context);
        tvDesc.setText("支持将当前的聊天记忆、子代理、插件生态、端口及全部偏好设置打包导出为 zip 文件，或将备份包无损还原至当前环境。");
        tvDesc.setTextColor(0xFF8B949E);
        tvDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tvDesc.setPadding(0, dp(4), 0, dp(14));
        root.addView(tvDesc);

        final AlertDialog[] diagRef = new AlertDialog[1];

        // 按钮 1: 立即打包导出
        TextView btnExport = buildActionBtn("📤 快速全量导出备份 (.zip)", 0x223B82F6, 0xFF93C5FD, v -> {
            if (diagRef[0] != null) diagRef[0].dismiss();
            performExportBackup();
        });
        btnExport.setPadding(dp(12), dp(10), dp(12), dp(10));
        btnExport.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.bottomMargin = dp(8);
        root.addView(btnExport, elp);

        // 按钮 2: 自选模块与目标目录导出
        TextView btnCustomExport = buildActionBtn("⚙️ 自选模块与目标目录导出 (.zip)", 0x2206B6D4, 0xFF67E8F9, v -> {
            if (diagRef[0] != null) diagRef[0].dismiss();
            showCustomExportDialog();
        });
        btnCustomExport.setPadding(dp(12), dp(10), dp(12), dp(10));
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
        btnPick.setPadding(dp(12), dp(10), dp(12), dp(10));
        btnPick.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.bottomMargin = dp(8);
        root.addView(btnPick, plp);

        // 按钮 3: 扫描当前目录或下载目录中的备份包
        TextView btnScan = buildActionBtn("📥 从当前目录或下载目录扫描还原", 0x2210B981, 0xFFA7F3D0, v -> {
            if (diagRef[0] != null) diagRef[0].dismiss();
            showRestorePicker();
        });
        btnScan.setPadding(dp(12), dp(10), dp(12), dp(10));
        btnScan.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = dp(8);
        root.addView(btnScan, slp);

        TextView btnClose = buildActionBtn("取消", 0x2230363D, 0xFF8B949E, v -> {
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

            AlertDialog.Builder resB = new AlertDialog.Builder(context);
            LinearLayout r = new LinearLayout(context);
            r.setOrientation(LinearLayout.VERTICAL);
            r.setBackgroundColor(0xFF0D1117);
            r.setPadding(dp(16), dp(16), dp(16), dp(16));

            TextView t = new TextView(context);
            t.setText(success ? "✔ 备份导出成功" : "❌ 备份导出失败");
            t.setTextColor(success ? 0xFF3FB950 : 0xFFF85149);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            r.addView(t);

            TextView msg = new TextView(context);
            msg.setText(message);
            msg.setTextColor(0xFFC9D1D9);
            msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            msg.setPadding(0, dp(8), 0, dp(14));
            r.addView(msg);

            final AlertDialog[] resDiagRef = new AlertDialog[1];

            if (success && file != null) {
                TextView btnShare = buildActionBtn("📤 分享 / 发送备份包", 0x228B5CF6, 0xFFC4B5FD, v -> {
                    BackupManager.shareBackupFile(context, file);
                });
                btnShare.setPadding(dp(12), dp(8), dp(12), dp(8));
                btnShare.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams splp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                splp.bottomMargin = dp(6);
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
                cplp.bottomMargin = dp(6);
                r.addView(btnCopy, cplp);

                loadCurrentDir();
            }

            TextView btnOk = buildActionBtn("确定", 0x2230363D, 0xFF8B949E, v -> {
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
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        ScrollView scroll = new ScrollView(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0D1117);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        scroll.addView(root);

        TextView tvTitle = new TextView(context);
        tvTitle.setText("⚙️ 自选备份导出设置");
        tvTitle.setTextColor(0xFFF0F6FC);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(tvTitle);

        TextView tvSub = new TextView(context);
        tvSub.setText("请勾选需要导出的数据模块，并选择备份保存的目标目录：");
        tvSub.setTextColor(0xFF8B949E);
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
                tvCwdSubHint.setTextColor(0xFF58A6FF);
                tvCwdSubHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
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
        tvTargetDirPath.setTextColor(0xFF58A6FF);
        tvTargetDirPath.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvTargetDirPath.setPadding(dp(8), dp(6), dp(8), dp(6));
        GradientDrawable pathBg = new GradientDrawable();
        pathBg.setColor(0xFF161B22);
        pathBg.setCornerRadius(dp(4));
        pathBg.setStroke(dp(1), 0xFF30363D);
        tvTargetDirPath.setBackground(pathBg);
        root.addView(tvTargetDirPath);

        // 目标目录快速切换按钮组
        LinearLayout dirBtnRow = new LinearLayout(context);
        dirBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        dirBtnRow.setPadding(0, dp(6), 0, dp(10));

        TextView btnUseCurrent = buildActionBtn("📍 设为当前浏览目录", 0x22388BFD, 0xFF79C0FF, v -> {
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
        dlp.leftMargin = dp(6);
        dirBtnRow.addView(btnUseDownloads, dlp);

        TextView btnUseAppExport = buildActionBtn("📦 内部默认目录", 0x228B5CF6, 0xFFC4B5FD, v -> {
            selectedTargetDir[0] = BackupManager.getExportDirectory(context);
            tvTargetDirPath.setText("📂 " + selectedTargetDir[0].getAbsolutePath());
            Toast.makeText(context, "已设为内部备份目录", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = dp(6);
        dirBtnRow.addView(btnUseAppExport, alp);

        root.addView(dirBtnRow);

        // Section 3: 额外容器自定义路径 (可选)
        TextView tvSec3 = createSectionHeader("3. 额外容器路径 (可选，英文逗号分隔)");
        root.addView(tvSec3);

        EditText etCustomPaths = new EditText(context);
        etCustomPaths.setHint("如: root/my_scripts, etc (可选)");
        etCustomPaths.setHintTextColor(0xFF484F58);
        etCustomPaths.setTextColor(0xFFC9D1D9);
        etCustomPaths.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        etCustomPaths.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable etBg = new GradientDrawable();
        etBg.setColor(0xFF161B22);
        etBg.setCornerRadius(dp(4));
        etBg.setStroke(dp(1), 0xFF30363D);
        etCustomPaths.setBackground(etBg);
        root.addView(etCustomPaths);

        final AlertDialog[] customDiagRef = new AlertDialog[1];

        // 底部动作按钮
        LinearLayout actRow = new LinearLayout(context);
        actRow.setOrientation(LinearLayout.HORIZONTAL);
        actRow.setPadding(0, dp(16), 0, 0);

        TextView btnCancel = buildActionBtn("取消", 0x2230363D, 0xFF8B949E, v -> {
            if (customDiagRef[0] != null) customDiagRef[0].dismiss();
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.rightMargin = dp(6);
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
        tv.setTextColor(0xFFE6EDF3);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(10), 0, dp(4));
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
            ssb.setSpan(new ForegroundColorSpan(0xFF8B949E), start, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            ssb.setSpan(new RelativeSizeSpan(0.85f), start, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        cb.setText(ssb);
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        cb.setTextColor(0xFFF0F6FC);
        cb.setChecked(checked);
        cb.setPadding(dp(4), dp(3), dp(4), dp(3));
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
            new AlertDialog.Builder(context)
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

        new AlertDialog.Builder(context)
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

        new AlertDialog.Builder(context)
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

                        new AlertDialog.Builder(context)
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

