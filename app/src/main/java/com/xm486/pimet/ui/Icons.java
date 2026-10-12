package com.xm486.pimet.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ReplacementSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.TextView;

import com.xm486.pimet.PiMetApp;
import com.xm486.pimet.R;
import com.xm486.pimet.ThemeManager;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 极简线性图标引擎 (Icons)
 *
 * 将界面文本开头的 emoji 图标统一渲染为 Lucide 风格线性矢量图标 (res/drawable/ic_*.xml，ISC License)。
 * - 文本内容保持不变（emoji 字符仍在 getText() 中），仅在绘制层用 {@link IconSpan} 替换显示，
 *   因此所有基于文本的逻辑、复制、ID 与点击行为均不受影响。
 * - 图标颜色在绘制时直接取自 TextView 当前文字颜色（来自 ThemeManager 调色板），
 *   切换 6 套主题、禁用态透明度均自动跟随，不引入任何新色值。
 * - 图标与文字间距统一为 8dp；行内图标 16~20dp，独立图标 18~24dp，底栏导航 22dp。
 */
public final class Icons {

    private Icons() {}

    /** 状态圆点的语义色角色 (取自 ThemeManager 现有状态色)。 */
    private static final int ROLE_TEXT = 0;
    private static final int ROLE_SUCCESS = 1;
    private static final int ROLE_WARNING = 2;
    private static final int ROLE_DANGER = 3;

    private static final Map<Integer, Integer> ICONS = new HashMap<>();
    private static final Map<Integer, Integer> DOT_ROLES = new HashMap<>();

    private static void map(String emoji, int res) {
        ICONS.put(emoji.codePointAt(0), res);
    }

    static {
        // 服务与导航
        map("🚀", R.drawable.ic_rocket);
        map("🌐", R.drawable.ic_globe);
        map("🧩", R.drawable.ic_puzzle);
        map("⚙", R.drawable.ic_settings);
        map("🔄", R.drawable.ic_refresh);
        map("↺", R.drawable.ic_refresh);
        map("⏹", R.drawable.ic_stop);
        map("🛑", R.drawable.ic_octagon_x);
        map("⏸", R.drawable.ic_pause);
        map("⚡", R.drawable.ic_zap);
        map("🐧", R.drawable.ic_container);
        map("🖥", R.drawable.ic_monitor);
        map("💻", R.drawable.ic_laptop);
        map("🦞", R.drawable.ic_gauge);
        map("📊", R.drawable.ic_chart);
        map("🏠", R.drawable.ic_house);
        // 文件与数据
        map("📁", R.drawable.ic_folder);
        map("📂", R.drawable.ic_folder_open);
        map("📄", R.drawable.ic_file_text);
        map("📝", R.drawable.ic_file_pen);
        map("📜", R.drawable.ic_scroll);
        map("📖", R.drawable.ic_book);
        map("📋", R.drawable.ic_clipboard);
        map("📑", R.drawable.ic_list);
        map("📦", R.drawable.ic_package);
        map("💾", R.drawable.ic_hard_drive);
        map("📤", R.drawable.ic_upload);
        map("⬆", R.drawable.ic_upload);
        map("📥", R.drawable.ic_download);
        map("⬇", R.drawable.ic_download);
        map("🔗", R.drawable.ic_link);
        map("📌", R.drawable.ic_pin);
        map("📍", R.drawable.ic_map_pin);
        map("📨", R.drawable.ic_mail);
        map("✉", R.drawable.ic_mail);
        // 操作
        map("🔍", R.drawable.ic_search);
        map("🔎", R.drawable.ic_search);
        map("🧹", R.drawable.ic_eraser);
        map("🗑", R.drawable.ic_trash);
        map("✕", R.drawable.ic_close);
        map("✖", R.drawable.ic_close);
        map("✔", R.drawable.ic_check);
        map("✓", R.drawable.ic_check);
        map("✅", R.drawable.ic_check_circle);
        map("❌", R.drawable.ic_x_circle);
        map("✘", R.drawable.ic_x_circle);
        map("⚠", R.drawable.ic_alert);
        map("😱", R.drawable.ic_alert_circle);
        map("❔", R.drawable.ic_help);
        map("➕", R.drawable.ic_plus);
        map("➖", R.drawable.ic_minus);
        map("↗", R.drawable.ic_external);
        map("⛶", R.drawable.ic_maximize);
        map("✏", R.drawable.ic_pencil);
        map("🔧", R.drawable.ic_wrench);
        map("🛠", R.drawable.ic_wrench);
        map("🎛", R.drawable.ic_sliders);
        map("🧪", R.drawable.ic_flask);
        map("⏳", R.drawable.ic_hourglass);
        map("⏱", R.drawable.ic_timer);
        map("🎯", R.drawable.ic_target);
        map("🔤", R.drawable.ic_sort_az);
        map("🔥", R.drawable.ic_flame);
        map("🔕", R.drawable.ic_bell_off);
        // 生态与 AI
        map("🏪", R.drawable.ic_store);
        map("🛍", R.drawable.ic_shopping_bag);
        map("🔌", R.drawable.ic_plug);
        map("🤖", R.drawable.ic_bot);
        map("🧠", R.drawable.ic_brain);
        map("🤔", R.drawable.ic_brain);
        map("💬", R.drawable.ic_message);
        map("💭", R.drawable.ic_message);
        map("🗣", R.drawable.ic_megaphone);
        map("💡", R.drawable.ic_lightbulb);
        map("✨", R.drawable.ic_sparkles);
        map("🌟", R.drawable.ic_star);
        map("🎉", R.drawable.ic_party);
        map("👤", R.drawable.ic_user);
        // 外观与主题
        map("🎨", R.drawable.ic_palette);
        map("🌓", R.drawable.ic_sun_moon);
        map("☀", R.drawable.ic_sun);
        map("🌙", R.drawable.ic_moon);
        map("🌿", R.drawable.ic_leaf);
        map("🌸", R.drawable.ic_flower);
        map("🌲", R.drawable.ic_trees);
        // 权限与系统
        map("🛡", R.drawable.ic_shield);
        map("🔋", R.drawable.ic_battery);
        map("📱", R.drawable.ic_smartphone);
        // 桌宠
        map("🐾", R.drawable.ic_paw);
        map("💤", R.drawable.ic_power);
        map("💃", R.drawable.ic_person);
        map("👋", R.drawable.ic_hand);
        map("📏", R.drawable.ic_ruler);
        map("📐", R.drawable.ic_ruler);
        // 状态圆点
        map("🟢", R.drawable.ic_dot);
        map("🟡", R.drawable.ic_dot);
        map("🟠", R.drawable.ic_dot);
        map("🔴", R.drawable.ic_dot);
        map("⚪", R.drawable.ic_dot);
        map("🔵", R.drawable.ic_dot);
        map("🟣", R.drawable.ic_dot);
        DOT_ROLES.put("🟢".codePointAt(0), ROLE_SUCCESS);
        DOT_ROLES.put("🟡".codePointAt(0), ROLE_WARNING);
        DOT_ROLES.put("🟠".codePointAt(0), ROLE_WARNING);
        DOT_ROLES.put("🔴".codePointAt(0), ROLE_DANGER);
    }

    private static final Set<TextView> WATCHED = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<View> ROOTS = Collections.newSetFromMap(new WeakHashMap<>());

    private static Context appContext(View v) {
        Context c = PiMetApp.getContext();
        return c != null ? c : (v != null ? v.getContext() : null);
    }

    // ================= 公共 API =================

    /** 为整棵视图树安装图标渲染：现有文本立即替换，后续 setText / 新增子视图自动跟随。 */
    public static <T extends View> T install(T root) {
        if (root == null) return null;
        walk(root);
        if (ROOTS.add(root)) {
            final View r = root;
            root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    walk(r);
                }
            });
        }
        return root;
    }

    /** 为单个 TextView 安装图标渲染。 */
    public static void decorate(TextView tv) {
        if (tv == null || tv instanceof EditText) return;
        if (WATCHED.contains(tv)) return;
        WATCHED.add(tv);
        tv.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                applySpan(tv, s);
            }
        });
        CharSequence cur = tv.getText();
        if (cur != null && findLeading(cur) != null && !hasIconSpan(cur)) {
            // 重新设置一次文本，触发 TextWatcher 以可编辑缓冲区承载图标 Span
            tv.setText(cur);
        }
    }

    /** 为对话框标题 / 按钮等非视图文本生成带线性图标的 CharSequence。 */
    public static CharSequence label(CharSequence text) {
        if (text == null) return null;
        int[] r = findLeading(text);
        if (r == null) return text;
        SpannableString ss = new SpannableString(text);
        ss.setSpan(createSpan(null, text, r), r[0], r[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return ss;
    }

    /** 判断文本是否以可映射的 emoji 图标开头。 */
    public static boolean hasLeadingIcon(CharSequence text) {
        return text != null && findLeading(text) != null;
    }

    /** 对话框创建后安装图标渲染（标题、按钮与内容区）。在窗口装饰视图就绪后自动挂载，无需改变对话框原有逻辑。 */
    public static void installDialog(final android.app.Dialog dialog) {
        if (dialog == null) return;
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.post(new Runnable() {
            int tries = 0;
            @Override
            public void run() {
                android.view.Window w = dialog.getWindow();
                View decor = w != null ? w.peekDecorView() : null;
                if (decor != null) {
                    install(decor);
                } else if (++tries < 40) {
                    h.postDelayed(this, 50);
                }
            }
        });
    }

    /** android.app.AlertDialog 构建器：create() 后自动安装线性图标。 */
    public static class DialogBuilder extends android.app.AlertDialog.Builder {
        public DialogBuilder(Context context) { super(context); }
        public DialogBuilder(Context context, int themeResId) { super(context, themeResId); }
        @Override
        public android.app.AlertDialog create() {
            android.app.AlertDialog d = super.create();
            installDialog(d);
            return d;
        }
    }

    /** androidx AlertDialog 构建器：create() 后自动安装线性图标。 */
    public static class CompatDialogBuilder extends androidx.appcompat.app.AlertDialog.Builder {
        public CompatDialogBuilder(Context context) { super(context); }
        public CompatDialogBuilder(Context context, int themeResId) { super(context, themeResId); }
        @Override
        public androidx.appcompat.app.AlertDialog create() {
            androidx.appcompat.app.AlertDialog d = super.create();
            installDialog(d);
            return d;
        }
    }

    // ================= 内部实现 =================

    private static void walk(View v) {
        if (v instanceof TextView) {
            if (!WATCHED.contains(v)) decorate((TextView) v);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                walk(vg.getChildAt(i));
            }
        }
    }

    private static boolean hasIconSpan(CharSequence cs) {
        if (!(cs instanceof Spanned)) return false;
        Spanned sp = (Spanned) cs;
        return sp.getSpans(0, sp.length(), IconSpan.class).length > 0;
    }

    private static void applySpan(TextView tv, Editable s) {
        if (s == null) return;
        IconSpan[] old = s.getSpans(0, s.length(), IconSpan.class);
        int[] r = findLeading(s);
        if (r == null) {
            for (IconSpan o : old) s.removeSpan(o);
            return;
        }
        // 已存在且范围一致则不重复设置
        if (old.length == 1 && s.getSpanStart(old[0]) == r[0] && s.getSpanEnd(old[0]) == r[1]
                && old[0].res == r[2]) {
            return;
        }
        // 以新文本整体重设，确保 TextView 按新的图标宽度重新测量与排版（重入时上方检查会直接返回）
        android.text.SpannableStringBuilder ssb = new android.text.SpannableStringBuilder(s);
        for (IconSpan o : ssb.getSpans(0, ssb.length(), IconSpan.class)) ssb.removeSpan(o);
        ssb.setSpan(createSpan(tv, ssb, r), r[0], r[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tv.setText(ssb);
    }

    private static IconSpan createSpan(TextView tv, CharSequence text, int[] r) {
        boolean iconOnly = r[1] >= text.length() || text.subSequence(r[1], text.length()).toString().trim().isEmpty();
        boolean nav = false;
        if (tv != null && tv.getId() != View.NO_ID) {
            try {
                String name = tv.getResources().getResourceEntryName(tv.getId());
                nav = name.startsWith("tab") && name.endsWith("Icon");
            } catch (Exception ignored) {}
        }
        Integer role = DOT_ROLES.get(r[3]);
        return new IconSpan(appContext(tv), r[2], iconOnly, nav, r[2] == R.drawable.ic_dot,
                role != null ? role : ROLE_TEXT);
    }

    /**
     * 查找开头的 emoji 图标。返回 {spanStart, spanEnd(含变体选择符与后随空格), drawableRes, codePoint}；
     * 多行文本（日志、聊天内容）不做替换。
     */
    private static int[] findLeading(CharSequence text) {
        int len = text.length();
        if (len == 0 || len > 160) return null;
        for (int k = 0; k < len; k++) {
            if (text.charAt(k) == '\n') return null;
        }
        int i = 0;
        while (i < len && text.charAt(i) == ' ') i++;
        if (i >= len) return null;
        int cp = Character.codePointAt(text, i);
        Integer res = ICONS.get(cp);
        if (res == null) return null;
        int start = i;
        i += Character.charCount(cp);
        while (i < len) {
            char c = text.charAt(i);
            if (c == '\uFE0F' || c == '\uFE0E' || c == '\u200D' || c == ' ' || c == '\u00A0') {
                i++;
            } else {
                break;
            }
        }
        return new int[]{start, i, res, cp};
    }

    /** 以文字颜色实时着色的线性矢量图标 Span。 */
    static final class IconSpan extends ReplacementSpan {
        final int res;
        private final Context ctx;
        private final boolean iconOnly;
        private final boolean nav;
        private final boolean dot;
        private final int role;
        private Drawable drawable;
        private final float density;

        IconSpan(Context ctx, int res, boolean iconOnly, boolean nav, boolean dot, int role) {
            this.ctx = ctx;
            this.res = res;
            this.iconOnly = iconOnly;
            this.nav = nav;
            this.dot = dot;
            this.role = role;
            this.density = ctx != null ? ctx.getResources().getDisplayMetrics().density : 2f;
        }

        private Drawable drawable() {
            if (drawable == null && ctx != null) {
                Drawable d = ctx.getDrawable(res);
                if (d != null) drawable = d.mutate();
            }
            return drawable;
        }

        private int sizePx(Paint paint) {
            float ts = paint.getTextSize();
            float size;
            if (nav) {
                size = 22f * density;
            } else if (iconOnly) {
                size = clamp(ts * 1.15f, 18f * density, 24f * density);
            } else {
                size = clamp(ts * 1.25f, 16f * density, 20f * density);
            }
            if (dot) size = Math.min(size, 16f * density);
            return Math.round(size);
        }

        private int gapPx() {
            return iconOnly ? 0 : Math.round(8f * density);
        }

        private static float clamp(float v, float lo, float hi) {
            return Math.max(lo, Math.min(hi, v));
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            int size = sizePx(paint);
            if (fm != null) {
                Paint.FontMetricsInt pfm = paint.getFontMetricsInt();
                fm.ascent = pfm.ascent;
                fm.descent = pfm.descent;
                fm.top = pfm.top;
                fm.bottom = pfm.bottom;
                fm.leading = pfm.leading;
                int textH = pfm.descent - pfm.ascent;
                if (size > textH) {
                    int extra = size - textH;
                    int up = extra / 2;
                    int down = extra - up;
                    fm.ascent -= up;
                    fm.top = Math.min(fm.top, fm.ascent);
                    fm.descent += down;
                    fm.bottom = Math.max(fm.bottom, fm.descent);
                }
            }
            return size + gapPx();
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                         int top, int y, int bottom, Paint paint) {
            Drawable d = drawable();
            if (d == null) return;
            int size = sizePx(paint);
            Paint.FontMetricsInt pfm = paint.getFontMetricsInt();
            float centerY = y + (pfm.ascent + pfm.descent) / 2f;
            int l = Math.round(x);
            int t = Math.round(centerY - size / 2f);
            d.setBounds(l, t, l + size, t + size);
            int color = paint.getColor();
            if (role != ROLE_TEXT && ctx != null) {
                ThemeManager.ThemePalette p = ThemeManager.getEffectivePalette(ctx);
                int base = role == ROLE_SUCCESS ? ThemeManager.statusSuccessColor(p)
                        : role == ROLE_WARNING ? ThemeManager.statusWarningColor(p)
                        : ThemeManager.statusDangerColor(p);
                color = (base & 0x00FFFFFF) | (paint.getAlpha() << 24);
            }
            d.setTint(color);
            d.draw(canvas);
        }
    }
}
