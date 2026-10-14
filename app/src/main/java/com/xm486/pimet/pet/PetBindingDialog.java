package com.xm486.pimet.pet;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.xm486.pimet.ThemeManager;

import java.util.List;

/**
 * 「监控绑定桌宠」：每个监控目标可绑定一只桌宠，切到该监控时自动换成它；不绑定则保持当前桌宠。
 * 绑定存于 pet_dir_mode_&lt;目标键&gt;（见 {@link PetRegistry#BINDABLE_TARGETS}）。
 */
public class PetBindingDialog {

    private static final LruCache<String, Bitmap> THUMBS = new LruCache<>(48);

    private final Context context;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ThemeManager.ThemePalette palette;
    private AlertDialog dialog;
    private LinearLayout listBox;

    public static void show(Context context) {
        new PetBindingDialog(context).show();
    }

    public PetBindingDialog(Context context) {
        this.context = context;
    }

    private int dp(int v) {
        return (int) (v * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    public void show() {
        try {
            palette = ThemeManager.getEffectivePalette(context);
            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(14), dp(12), dp(14), dp(10));

            root.addView(header("监控绑定桌宠", v -> dismiss()));
            TextView hint = text("切到该监控时自动换成绑定的桌宠", 12f, palette.textMuted);
            hint.setPadding(0, 0, 0, dp(8));
            root.addView(hint);

            listBox = new LinearLayout(context);
            listBox.setOrientation(LinearLayout.VERTICAL);
            ScrollView sv = new ScrollView(context);
            sv.addView(listBox);
            root.addView(new MaxHeightFrame(context,
                    (int) (context.getResources().getDisplayMetrics().heightPixels * 0.6f), sv));
            buildRows();

            dialog = new com.xm486.pimet.ui.Icons.DialogBuilder(context).setView(root).create();
            prepareWindow(dialog);
            dialog.show();
        } catch (Throwable t) {
            android.util.Log.w("DevPetM.PetBinding", "show failed", t);
        }
    }

    private void buildRows() {
        listBox.removeAllViews();
        String active = PetRegistry.getStringPref(context, PetRegistry.KEY_MONITOR_TARGET, PetRegistry.TARGET_PIWEB);
        for (String[] t : PetRegistry.BINDABLE_TARGETS) {
            final String key = t[0];
            final String label = t[1];
            String bound = PetRegistry.getBoundPet(context, key);

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(6), dp(8), dp(6));
            row.setMinimumHeight(dp(44));
            row.setBackground(pressable(key.equals(active)));
            row.setClickable(true);

            TextView name = text(label, 13f, key.equals(active) ? palette.accent : palette.text);
            if (key.equals(active)) name.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            ImageView thumb = new ImageView(context);
            thumb.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams thLp = new LinearLayout.LayoutParams(dp(28), dp(30));
            thLp.rightMargin = dp(6);
            row.addView(thumb, thLp);
            if (bound != null) loadThumb(bound, thumb); else thumb.setVisibility(View.GONE);

            TextView val = text(bound != null ? PetRegistry.getPetDisplayName(context, bound) : "不绑定",
                    12f, bound != null ? palette.text : palette.textMuted);
            val.setMaxWidth(dp(96));
            row.addView(val, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView arrow = text("›", 14f, palette.textMuted);
            arrow.setPadding(dp(6), 0, 0, 0);
            row.addView(arrow);

            row.setOnClickListener(v -> showPicker(key, label));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            listBox.addView(row, lp);
        }
    }

    /** 选择桌宠：「不绑定」+ 全部已安装桌宠（内置 / 导入 / 商城），带首帧缩略图。 */
    private void showPicker(String key, String label) {
        try {
            List<PetRegistry.PetInfo> pets = PetRegistry.loadPets(context);
            String bound = PetRegistry.getBoundPet(context, key);
            final AlertDialog[] holder = new AlertDialog[1];

            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(14), dp(12), dp(14), dp(10));
            root.addView(header(label + " 绑定", v -> { if (holder[0] != null) holder[0].dismiss(); }));

            LinearLayout box = new LinearLayout(context);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(0, dp(6), 0, 0);
            box.addView(pickRow(null, "不绑定", bound == null, () -> {
                choose(key, null);
                if (holder[0] != null) holder[0].dismiss();
            }));
            for (PetRegistry.PetInfo p : pets) {
                String nm = p.displayName != null && !p.displayName.isEmpty() ? p.displayName : p.dir;
                box.addView(pickRow(p.dir, nm, p.dir.equals(bound), () -> {
                    choose(key, p.dir);
                    if (holder[0] != null) holder[0].dismiss();
                }));
            }
            ScrollView sv = new ScrollView(context);
            sv.addView(box);
            int maxH = (int) (context.getResources().getDisplayMetrics().heightPixels * 0.6f);
            root.addView(new MaxHeightFrame(context, maxH, sv));

            holder[0] = new com.xm486.pimet.ui.Icons.DialogBuilder(context).setView(root).create();
            prepareWindow(holder[0]);
            holder[0].show();
        } catch (Throwable t) {
            android.util.Log.w("DevPetM.PetBinding", "picker failed", t);
        }
    }

    private View pickRow(String dir, String name, boolean selected, Runnable onPick) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(4), dp(10), dp(4));
        row.setMinimumHeight(dp(42));
        row.setBackground(pressable(selected));
        row.setClickable(true);
        row.setOnClickListener(v -> onPick.run());

        ImageView thumb = new ImageView(context);
        thumb.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams thLp = new LinearLayout.LayoutParams(dp(30), dp(32));
        thLp.rightMargin = dp(10);
        row.addView(thumb, thLp);
        if (dir != null) loadThumb(dir, thumb); else thumb.setVisibility(View.INVISIBLE);

        TextView tv = text(name, 13f, selected ? palette.accent : (dir == null ? palette.textMuted : palette.text));
        if (selected) tv.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
        row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (selected) row.addView(text("✓", 13f, palette.accent));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(4);
        row.setLayoutParams(lp);
        return row;
    }

    private void choose(String key, String dir) {
        PetRegistry.setBoundPet(context, key, dir);
        String active = PetRegistry.getStringPref(context, PetRegistry.KEY_MONITOR_TARGET, PetRegistry.TARGET_PIWEB);
        String label = key;
        for (String[] t : PetRegistry.BINDABLE_TARGETS) if (t[0].equals(key)) label = t[1];
        if (dir != null && key.equals(active)) {
            // 当前正在监控这个目标：立即换上
            ui.post(() -> PetRegistry.applyTargetPet(context, key));
        }
        Toast.makeText(context, dir == null ? label + " 已取消绑定"
                : label + " → " + PetRegistry.getPetDisplayName(context, dir), Toast.LENGTH_SHORT).show();
        if (listBox != null) buildRows();
    }

    private void loadThumb(String dir, ImageView iv) {
        Bitmap cached = THUMBS.get(dir);
        if (cached != null && !cached.isRecycled()) {
            iv.setImageBitmap(cached);
            return;
        }
        iv.setTag(dir);
        final int px = dp(32);
        new Thread(() -> {
            Bitmap b = PetRegistry.loadPetThumb(context.getApplicationContext(), dir, px);
            if (b == null) return;
            THUMBS.put(dir, b);
            ui.post(() -> {
                if (dir.equals(iv.getTag())) iv.setImageBitmap(b);
            });
        }, "pet-thumb").start();
    }

    // ---------------- 小部件 ----------------

    private View header(String title, View.OnClickListener onClose) {
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView tv = text(title, 14f, palette.text);
        tv.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
        bar.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = text("✕", 14f, palette.textMuted);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(onClose);
        bar.addView(close, new LinearLayout.LayoutParams(dp(36), dp(36)));
        return bar;
    }

    private TextView text(String s, float sp, int color) {
        TextView tv = new TextView(context);
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setIncludeFontPadding(false);
        return tv;
    }

    private StateListDrawable pressable(boolean selected) {
        GradientDrawable n = selected
                ? ThemeManager.createSelectedOptionDrawable(context, palette, ThemeManager.RADIUS_CONTROL)
                : ThemeManager.createSecondaryButtonDrawable(context, palette, ThemeManager.RADIUS_CONTROL);
        GradientDrawable p = ThemeManager.createSelectedOptionDrawable(context, palette, ThemeManager.RADIUS_CONTROL);
        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed}, p);
        sld.addState(new int[]{}, n);
        return sld;
    }

    private void prepareWindow(AlertDialog d) {
        Window w = d.getWindow();
        if (w == null) return;
        if (!(context instanceof Activity)) {
            w.setType(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE);
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(palette.bgPanel);
        bg.setCornerRadius(dp(12));
        bg.setStroke(Math.max(1, dp(1)), palette.border);
        w.setBackgroundDrawable(bg);
    }

    public void dismiss() {
        if (dialog != null) {
            try { dialog.dismiss(); } catch (Throwable ignored) {}
            dialog = null;
        }
    }

    /** 限高容器：内容超过 maxH 时内部滚动。 */
    private static final class MaxHeightFrame extends android.widget.FrameLayout {
        private final int maxH;

        MaxHeightFrame(Context c, int maxH, View child) {
            super(c);
            this.maxH = maxH;
            addView(child);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST));
        }
    }
}
