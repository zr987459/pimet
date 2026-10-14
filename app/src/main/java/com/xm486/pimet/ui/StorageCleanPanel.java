package com.xm486.pimet.ui;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.xm486.pimet.PiMetConfig;
import com.xm486.pimet.ThemeManager;
import com.xm486.pimet.proot.ProotManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 「容器存储与清理」页里的扫描面板：后台扫描 → 列出建议清理项（名称、大小、一句说明、安全/谨慎、勾选框，
 * 安全项默认勾选）→ 确认后后台清理 → 自动重新扫描并刷新「容器已占存储空间」。
 */
public final class StorageCleanPanel {

    private final Activity act;
    private final LinearLayout box;
    private final Runnable onCleaned;
    private final WebView webView;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private StorageCleaner.Result result;
    private boolean piWebRunning;
    private boolean busy;
    private AtomicBoolean cancel;
    private TextView progressTv;
    private ProgressBar progressBar;
    private TextView summaryTv;

    public StorageCleanPanel(Activity act, LinearLayout container, WebView webView, Runnable onCleaned) {
        this.act = act;
        this.box = container;
        this.webView = webView;
        this.onCleaned = onCleaned;
    }

    public boolean isBusy() {
        return busy;
    }

    private int dp(float v) {
        return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f);
    }

    private ThemeManager.ThemePalette pal() {
        return ThemeManager.getEffectivePalette(act);
    }

    // ---------------- 扫描 ----------------

    public void scan() {
        if (busy) return;
        busy = true;
        cancel = new AtomicBoolean(false);
        showProgress("正在扫描…");
        final AtomicBoolean c = cancel;
        new Thread(() -> {
            StorageCleaner.Result r = null;
            boolean running = false;
            try {
                running = ProotManager.isPiWebPortAlive(PiMetConfig.getWebPort(act));
            } catch (Throwable ignored) {}
            try {
                r = new StorageCleaner(act).scan((label, i, n) -> postProgress("扫描中 · " + label, i, n), c);
            } catch (Throwable t) {
                android.util.Log.w("PiMet.Clean", "scan failed", t);
            }
            final StorageCleaner.Result fr = r;
            final boolean fRunning = running;
            ui.post(() -> {
                busy = false;
                if (c.get()) {
                    box.removeAllViews();
                    box.setVisibility(View.GONE);
                    return;
                }
                result = fr;
                piWebRunning = fRunning;
                render();
            });
        }, "storage-scan").start();
    }

    /** 主题切换后重绘（保持当前结果）。 */
    public void rerender() {
        if (!busy && result != null && box.getVisibility() == View.VISIBLE) render();
    }

    // ---------------- 渲染 ----------------

    private void showProgress(String text) {
        ThemeManager.ThemePalette p = pal();
        box.removeAllViews();
        box.setVisibility(View.VISIBLE);
        progressTv = text(text, 12f, "muted", p.textMuted);
        box.addView(progressTv);
        progressBar = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgressTintList(ColorStateList.valueOf(p.accent));
        progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(p.border));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        lp.topMargin = dp(6);
        box.addView(progressBar, lp);
        TextView cancelBtn = button("取消", false);
        cancelBtn.setOnClickListener(v -> {
            if (cancel != null) cancel.set(true);
            cancelBtn.setEnabled(false);
            cancelBtn.setText("正在取消…");
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
        clp.topMargin = dp(8);
        clp.gravity = Gravity.END;
        box.addView(cancelBtn, clp);
    }

    private void postProgress(String label, int i, int n) {
        ui.post(() -> {
            if (progressTv != null) progressTv.setText(label + "（" + i + "/" + n + "）");
            if (progressBar != null) progressBar.setProgress(n > 0 ? i * 100 / n : 0);
        });
    }

    private void render() {
        ThemeManager.ThemePalette p = pal();
        box.removeAllViews();
        box.setVisibility(View.VISIBLE);
        if (result == null) {
            box.addView(text("扫描失败，请稍后重试", 12f, "status_danger", ThemeManager.statusDangerColor(p)));
            return;
        }

        summaryTv = text("", 13f, "accent", p.accent);
        summaryTv.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
        box.addView(summaryTv);

        if (result.items.isEmpty()) {
            TextView none = text("没有发现值得清理的内容", 12f, "muted", p.textMuted);
            none.setPadding(0, dp(6), 0, 0);
            box.addView(none);
        }
        for (StorageCleaner.Item it : result.items) box.addView(itemRow(it, p));

        if (!result.largeFiles.isEmpty()) {
            TextView h = text("大文件（仅供参考，可在文件浏览器处理）", 12f, "primary", p.text);
            h.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
            h.setPadding(0, dp(12), 0, dp(2));
            box.addView(h);
            for (StorageCleaner.LargeFile lf : result.largeFiles) {
                LinearLayout row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, dp(3), 0, dp(3));
                TextView path = text(lf.path, 11f, "muted", p.textMuted);
                path.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                row.addView(path, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                TextView sz = text(ProotManager.formatSize(lf.size), 11f, "primary", p.text);
                sz.setPadding(dp(8), 0, 0, 0);
                row.addView(sz);
                box.addView(row);
            }
        }

        LinearLayout btnRow = new LinearLayout(act);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(12), 0, 0);
        TextView cleanBtn = button("🗑 清理所选", true);
        cleanBtn.setOnClickListener(v -> confirmClean());
        TextView hideBtn = button("收起", false);
        hideBtn.setOnClickListener(v -> {
            box.removeAllViews();
            box.setVisibility(View.GONE);
        });
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, dp(36), 1f);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, dp(36), 1f);
        l2.leftMargin = dp(8);
        btnRow.addView(cleanBtn, l1);
        btnRow.addView(hideBtn, l2);
        box.addView(btnRow);
        updateSummary();
        Icons.install(box);
    }

    private View itemRow(StorageCleaner.Item it, ThemeManager.ThemePalette p) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        CheckBox cb = new CheckBox(act);
        cb.setChecked(it.checked);
        cb.setButtonTintList(ColorStateList.valueOf(p.accent));
        cb.setTag("theme_role:accent");
        cb.setOnCheckedChangeListener((b, checked) -> {
            it.checked = checked;
            updateSummary();
        });
        row.addView(cb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout texts = new LinearLayout(act);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout line1 = new LinearLayout(act);
        line1.setOrientation(LinearLayout.HORIZONTAL);
        line1.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(it.name, 13f, "primary", p.text);
        line1.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView size = text(ProotManager.formatSize(it.size), 12f, "primary", p.text);
        size.setPadding(dp(6), 0, 0, 0);
        line1.addView(size);
        texts.addView(line1);

        int tone = it.safe ? ThemeManager.statusSuccessColor(p) : ThemeManager.statusWarningColor(p);
        TextView desc = text((it.safe ? "安全 · " : "谨慎 · ") + it.desc, 11f, "muted", p.textMuted);
        desc.setSingleLine(false);
        desc.setMaxLines(2);
        android.text.SpannableString ss = new android.text.SpannableString(desc.getText());
        ss.setSpan(new android.text.style.ForegroundColorSpan(tone), 0, 2, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        desc.setText(ss);
        desc.setPadding(0, dp(2), 0, 0);
        texts.addView(desc);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(v -> cb.toggle());
        return row;
    }

    private void updateSummary() {
        if (summaryTv == null || result == null) return;
        long total = 0, all = 0;
        int n = 0;
        for (StorageCleaner.Item it : result.items) {
            all += it.size;
            if (it.checked) {
                total += it.size;
                n++;
            }
        }
        summaryTv.setText(result.items.isEmpty() ? "容器占用 " + ProotManager.formatSize(result.containerSize)
                : "已选 " + n + " 项 · 可释放 " + ProotManager.formatSize(total) + " / " + ProotManager.formatSize(all));
    }

    // ---------------- 清理 ----------------

    private void confirmClean() {
        if (busy || result == null) return;
        final List<StorageCleaner.Item> sel = new ArrayList<>();
        long total = 0;
        boolean careful = false, touchesPi = false;
        StringBuilder sb = new StringBuilder();
        for (StorageCleaner.Item it : result.items) {
            if (!it.checked) continue;
            sel.add(it);
            total += it.size;
            if (!it.safe) careful = true;
            if (it.touchesPiWeb) touchesPi = true;
            sb.append("· ").append(it.name).append("  ").append(ProotManager.formatSize(it.size)).append('\n');
        }
        if (sel.isEmpty()) {
            Toast.makeText(act, "请先勾选要清理的项目", Toast.LENGTH_SHORT).show();
            return;
        }
        sb.append("\n合计约 ").append(ProotManager.formatSize(total));
        if (careful) sb.append("\n\n含「谨慎」项，删除后无法恢复。");
        if (touchesPi && piWebRunning) sb.append("\npi-web 正在运行，清理会话后建议重启 pi-web。");
        new Icons.CompatDialogBuilder(act)
                .setTitle("清理所选？")
                .setMessage(sb.toString())
                .setPositiveButton("清理", (d, w) -> runClean(sel))
                .setNegativeButton("取消", null)
                .show();
    }

    private void runClean(List<StorageCleaner.Item> sel) {
        if (busy) return;
        busy = true;
        cancel = new AtomicBoolean(false);
        showProgress("正在清理…");
        final AtomicBoolean c = cancel;
        boolean hasWeb = false;
        List<StorageCleaner.Item> fileItems = new ArrayList<>();
        for (StorageCleaner.Item it : sel) {
            if (it.webViewCache) hasWeb = true;
            else fileItems.add(it);
        }
        if (hasWeb) {
            try {
                if (webView != null) {
                    webView.clearCache(true);
                } else {
                    WebView tmp = new WebView(act);
                    tmp.clearCache(true);
                    tmp.destroy();
                }
            } catch (Throwable ignored) {}
        }
        long webSize = 0;
        for (StorageCleaner.Item it : sel) if (it.webViewCache) webSize += it.size;
        final long fWeb = webSize;
        new Thread(() -> {
            long freed = fWeb;
            try {
                freed += new StorageCleaner(act).clean(fileItems,
                        (label, i, n) -> postProgress("清理中 · " + label, i, n), c);
            } catch (Throwable t) {
                android.util.Log.w("PiMet.Clean", "clean failed", t);
            }
            final long f = freed;
            ui.post(() -> {
                busy = false;
                Toast.makeText(act, (c.get() ? "已取消，已释放约 " : "已释放约 ") + ProotManager.formatSize(f),
                        Toast.LENGTH_SHORT).show();
                if (onCleaned != null) onCleaned.run();
                scan();
            });
        }, "storage-clean").start();
    }

    // ---------------- 小部件 ----------------

    private TextView text(String s, float sp, String role, int color) {
        TextView tv = new TextView(act);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setTag("theme_role:" + role);
        return tv;
    }

    private TextView button(String label, boolean accent) {
        ThemeManager.ThemePalette p = pal();
        TextView b = text(label, 12f, accent ? "accent" : "primary", accent ? p.accent : p.text);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setBackground(ThemeManager.createSecondaryButtonDrawable(act, p, ThemeManager.RADIUS_CONTROL));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }
}
