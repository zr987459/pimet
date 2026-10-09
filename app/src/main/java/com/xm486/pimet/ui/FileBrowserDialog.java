package com.xm486.pimet.ui;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
                this.size = file.length();
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
                    tvPathSummary.setText(String.format(Locale.getDefault(),
                            "📊 统计: %d 个文件夹 · %d 个文件 · 当前层占用约 %s",
                            fDirCount, fFileCount, formatSize(fTotalSize)));
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

        if (file.length() > 5 * 1024 * 1024) {
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
}
