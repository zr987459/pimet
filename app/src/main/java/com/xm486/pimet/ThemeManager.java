package com.xm486.pimet;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * PiMet 界面主题管理器 (ThemeManager)
 * 深度对齐 Pi-Web 官方 UI 设计语言与配色规范。
 * 支持系统自适应与 Light、Dark、Mist、Rose、Pine 5套原生色彩矩阵。
 * 具备整树递归无损着色引擎，全面覆盖原生卡片、面板、输入框、按钮及文本。
 * 视觉规范：极简风格 —— 扁平表面、1dp 发丝描边、统一圆角/间距/字号阶梯（见下方 Design Token），
 * 所有颜色均派生自 ThemePalette，切换主题时不引入任何额外色值。
 */
public class ThemeManager {

    public static final String PREF_NAME = "pimet_theme_prefs";
    public static final String PREF_THEME_KEY = "current_theme_id";

    public static final String THEME_AUTO = "auto";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";
    public static final String THEME_MIST = "mist";
    public static final String THEME_ROSE = "rose";
    public static final String THEME_PINE = "pine";

    public static class ThemePalette {
        public final String id;
        public final String name;
        public final int bg;
        public final int bgPanel;
        public final int bgHover;
        public final int bgSunken;
        public final int border;
        public final int text;
        public final int textMuted;
        public final int textDim;
        public final int accent;
        public final int accentHover;
        public final int accentContrast;
        public final int accentSubtle;
        public final boolean isDark;

        public ThemePalette(String id, String name, int bg, int bgPanel, int bgHover, int bgSunken,
                            int border, int text, int textMuted, int textDim,
                            int accent, int accentHover, int accentContrast, int accentSubtle,
                            boolean isDark) {
            this.id = id;
            this.name = name;
            this.bg = bg;
            this.bgPanel = bgPanel;
            this.bgHover = bgHover;
            this.bgSunken = bgSunken;
            this.border = border;
            this.text = text;
            this.textMuted = textMuted;
            this.textDim = textDim;
            this.accent = accent;
            this.accentHover = accentHover;
            this.accentContrast = accentContrast;
            this.accentSubtle = accentSubtle;
            this.isDark = isDark;
        }
    }

    public static final ThemePalette PALETTE_LIGHT = new ThemePalette(
            THEME_LIGHT, "浅色 (Light)",
            0xFFFFFFFF, 0xFFF5F5F5, 0xFFEEEEEE, 0xFFF9FAFB,
            0xFFE0E0E0, 0xFF1A1A1A, 0xFF515C6B, 0xFF5E6673,
            0xFF245BCE, 0xFF1D4ED8, 0xFFFFFFFF, 0xFFEBF1FD,
            false
    );

    public static final ThemePalette PALETTE_DARK = new ThemePalette(
            THEME_DARK, "深色 (Dark)",
            0xFF1A1A1A, 0xFF242424, 0xFF2E2E2E, 0xFF222222,
            0xFF454545, 0xFFE8E8E8, 0xFFB7B7B7, 0xFFA4A4A4,
            0xFFA4C2F4, 0xFFC3D8FA, 0xFF182234, 0xFF293242,
            true
    );

    public static final ThemePalette PALETTE_MIST = new ThemePalette(
            THEME_MIST, "雾青 (Mist)",
            0xFFF4F8F7, 0xFFE9F0EE, 0xFFE0EAE7, 0xFFECF3F0,
            0xFFAFC4BA, 0xFF202E2B, 0xFF455F56, 0xFF52685F,
            0xFF1E6559, 0xFF174F46, 0xFFFFFFFF, 0xFFE0EFEB,
            false
    );

    public static final ThemePalette PALETTE_ROSE = new ThemePalette(
            THEME_ROSE, "蔷薇 (Rose)",
            0xFFFCF7F8, 0xFFF3EDEF, 0xFFEEE3E7, 0xFFF7EEF2,
            0xFFCDB5BF, 0xFF34282E, 0xFF65505A, 0xFF705B65,
            0xFF914360, 0xFF76324D, 0xFFFFFFFF, 0xFFF3E4EB,
            false
    );

    public static final ThemePalette PALETTE_PINE = new ThemePalette(
            THEME_PINE, "松夜 (Pine)",
            0xFF19201F, 0xFF212B28, 0xFF2B3632, 0xFF222D29,
            0xFF4A5F52, 0xFFE6EDE8, 0xFFC3D0C6, 0xFFAFC2B5,
            0xFFACCCB7, 0xFFD0E2D3, 0xFF182B20, 0xFF25382F,
            true
    );

    private ThemeManager() {}

    public static String getThemePreference(Context context) {
        if (context == null) return THEME_AUTO;
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return sp.getString(PREF_THEME_KEY, THEME_AUTO);
    }

    public static void setThemePreference(Context context, String themeId) {
        if (context == null || themeId == null) return;
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit().putString(PREF_THEME_KEY, themeId).apply();
    }

    public static boolean isSystemNightMode(Context context) {
        if (context == null) return true;
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    public static ThemePalette getEffectivePalette(Context context) {
        String themeId = getThemePreference(context);
        if (THEME_AUTO.equals(themeId)) {
            return isSystemNightMode(context) ? PALETTE_DARK : PALETTE_LIGHT;
        }
        return getPaletteById(themeId);
    }

    public static ThemePalette getPaletteById(String themeId) {
        if (THEME_LIGHT.equals(themeId)) return PALETTE_LIGHT;
        if (THEME_MIST.equals(themeId)) return PALETTE_MIST;
        if (THEME_ROSE.equals(themeId)) return PALETTE_ROSE;
        if (THEME_PINE.equals(themeId)) return PALETTE_PINE;
        return PALETTE_DARK;
    }

    public static String getThemeLabel(String themeId) {
        if (THEME_AUTO.equals(themeId)) return "跟随系统 (Auto)";
        if (THEME_LIGHT.equals(themeId)) return PALETTE_LIGHT.name;
        if (THEME_DARK.equals(themeId)) return PALETTE_DARK.name;
        if (THEME_MIST.equals(themeId)) return PALETTE_MIST.name;
        if (THEME_ROSE.equals(themeId)) return PALETTE_ROSE.name;
        if (THEME_PINE.equals(themeId)) return PALETTE_PINE.name;
        return "默认主题";
    }

    public static List<String> getAllThemes() {
        List<String> list = new ArrayList<>();
        list.add(THEME_AUTO);
        list.add(THEME_LIGHT);
        list.add(THEME_DARK);
        list.add(THEME_MIST);
        list.add(THEME_ROSE);
        list.add(THEME_PINE);
        return list;
    }

    public static void applyWindowTheme(Activity activity, ThemePalette palette) {
        if (activity == null || palette == null) return;
        Window window = activity.getWindow();
        if (window == null) return;

        window.setStatusBarColor(palette.bg);
        window.setNavigationBarColor(palette.bgPanel);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            View decor = window.getDecorView();
            int flags = decor.getSystemUiVisibility();
            int newFlags = flags;
            if (!palette.isDark) {
                newFlags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                newFlags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!palette.isDark) {
                    newFlags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                } else {
                    newFlags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            }
            if (flags != newFlags) {
                decor.setSystemUiVisibility(newFlags);
            }
        }
    }

    public static void syncThemeToWebView(WebView webView, Context context) {
        if (webView == null || context == null) return;
        ThemePalette palette = getEffectivePalette(context);
        final String effectiveTheme = palette.id;
        final boolean isDark = palette.isDark;
        final String bgHex = String.format("#%06X", (0xFFFFFF & palette.bg));

        String js = "(function() {" +
                "  try {" +
                "    var theme = '" + effectiveTheme + "';" +
                "    var isDark = " + isDark + ";" +
                "    localStorage.setItem('pi-theme', theme);" +
                "    document.documentElement.dataset.theme = theme;" +
                "    document.documentElement.classList.toggle('dark', isDark);" +
                "    var meta = document.querySelector('meta[name=\"theme-color\"]');" +
                "    if (!meta) {" +
                "      meta = document.createElement('meta');" +
                "      meta.name = 'theme-color';" +
                "      document.head.appendChild(meta);" +
                "    }" +
                "    meta.content = '" + bgHex + "';" +
                "    window.dispatchEvent(new StorageEvent('storage', {" +
                "      key: 'pi-theme'," +
                "      newValue: theme," +
                "      storageArea: localStorage" +
                "    }));" +
                "    window.dispatchEvent(new CustomEvent('pi-theme-change', { detail: { theme: theme, isDark: isDark } }));" +
                "  } catch (e) {}" +
                "})();";
        webView.evaluateJavascript(js, null);
    }

    public static int dpToPx(Context context, float dp) {
        if (context == null) return (int) dp;
        return (int) (dp * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ================= 极简设计 Token (Minimal Design Tokens) =================
    // 与 res/values/dimens.xml 保持一致。只约束尺寸/间距/圆角/字号，颜色全部来自 ThemePalette。

    /** 间距阶梯 (dp)：4 / 8 / 12 / 16 / 24 */
    public static final float SPACE_XXS = 2f;
    public static final float SPACE_XS = 4f;
    public static final float SPACE_SM = 8f;
    public static final float SPACE_MD = 12f;
    public static final float SPACE_LG = 16f;
    public static final float SPACE_XL = 24f;

    /** 圆角 (dp)：小徽章 / 控件(按钮、输入框、选项) / 卡片 / 胶囊 */
    public static final float RADIUS_SMALL = 6f;
    public static final float RADIUS_CONTROL = 10f;
    public static final float RADIUS_CARD = 12f;
    public static final float RADIUS_PILL = 16f;

    /** 发丝描边 (dp) */
    public static final float HAIRLINE = 1f;

    /** 控件高度 (dp) */
    public static final float CONTROL_HEIGHT = 44f;
    public static final float CONTROL_HEIGHT_COMPACT = 32f;
    public static final float CTA_HEIGHT = 48f;

    /** 字号阶梯 (sp)：11 / 12 / 13 / 14 / 16 / 20 */
    public static final float TEXT_CAPTION = 11f;
    public static final float TEXT_SMALL = 12f;
    public static final float TEXT_BODY_SMALL = 13f;
    public static final float TEXT_BODY = 14f;
    public static final float TEXT_TITLE = 16f;
    public static final float TEXT_HEADLINE = 20f;

    /** 将任意圆角吸附到统一的圆角体系：≤7 → 小徽章，≤11 → 控件，<16 → 卡片，≥16 保留（胶囊/圆形）。 */
    public static float snapRadius(float radiusDp) {
        if (radiusDp <= 0f) return 0f;
        if (radiusDp <= 7f) return RADIUS_SMALL;
        if (radiusDp <= 11f) return RADIUS_CONTROL;
        if (radiusDp < 16f) return RADIUS_CARD;
        return radiusDp;
    }

    /** 将任意字号吸附到统一字号阶梯；超出阶梯的大字号（如图标/表情）保持不变。 */
    public static float snapTextSize(float sp) {
        if (sp <= 0f || sp > 20f) return sp;
        if (sp < 11.25f) return TEXT_CAPTION;
        if (sp < 12.75f) return TEXT_SMALL;
        if (sp < 13.25f) return TEXT_BODY_SMALL;
        if (sp < 14.75f) return TEXT_BODY;
        if (sp < 19f) return TEXT_TITLE;
        return TEXT_HEADLINE;
    }

    /** 将任意间距吸附到 4 / 8 / 12 / 16 / 24 阶梯；超出 28dp 的特殊间距保持不变。 */
    public static float snapSpacing(float dp) {
        if (dp <= 0f || dp > 28f) return dp;
        if (dp <= 2f) return SPACE_XXS;
        if (dp <= 5f) return SPACE_XS;
        if (dp <= 9f) return SPACE_SM;
        if (dp <= 13f) return SPACE_MD;
        if (dp <= 18f) return SPACE_LG;
        return SPACE_XL;
    }

    public static int hairlinePx(Context context) {
        return Math.max(1, dpToPx(context, HAIRLINE));
    }

    private static float systemDensity() {
        return android.content.res.Resources.getSystem().getDisplayMetrics().density;
    }

    /** 像素版本的圆角吸附（用于仅传入 px 的旧接口）。 */
    private static int snapRadiusPx(int radiusPx) {
        float density = systemDensity();
        if (density <= 0f) return radiusPx;
        return (int) (snapRadius(radiusPx / density) * density + 0.5f);
    }

    private static int hairlinePx() {
        return Math.max(1, (int) (HAIRLINE * systemDensity() + 0.5f));
    }

    private static GradientDrawable flat(int color, int radiusPx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(color);
        gd.setCornerRadius(radiusPx);
        return gd;
    }

    // ================= Drawable 生成工厂（极简：扁平 + 发丝描边 + 统一圆角） =================

    /** 卡片：面板底色 + 1dp 发丝描边，无阴影。 */
    public static GradientDrawable createCardDrawable(Context context, ThemePalette palette, float radiusDp) {
        GradientDrawable gd = flat(palette.bgPanel, dpToPx(context, snapRadius(radiusDp)));
        gd.setStroke(hairlinePx(context), palette.border);
        return gd;
    }

    public static GradientDrawable createCardDrawable(ThemePalette palette, int radiusPx) {
        GradientDrawable gd = flat(palette.bgPanel, snapRadiusPx(radiusPx));
        gd.setStroke(hairlinePx(), palette.border);
        return gd;
    }

    /** 选中项：强调色浅底 + 1dp 强调色描边。 */
    public static GradientDrawable createSelectedOptionDrawable(Context context, ThemePalette palette, float radiusDp) {
        GradientDrawable gd = flat(palette.accentSubtle, dpToPx(context, snapRadius(radiusDp)));
        gd.setStroke(hairlinePx(context), palette.accent);
        return gd;
    }

    /** 次级按钮 / 未选中项：纯色扁平填充，无描边。 */
    public static GradientDrawable createSecondaryButtonDrawable(Context context, ThemePalette palette, float radiusDp) {
        return flat(palette.bgHover, dpToPx(context, snapRadius(radiusDp)));
    }

    public static GradientDrawable createSecondaryButtonDrawable(ThemePalette palette, int radiusPx) {
        return flat(palette.bgHover, snapRadiusPx(radiusPx));
    }

    /** 主按钮：强调色纯色填充。 */
    public static GradientDrawable createPrimaryButtonDrawable(Context context, ThemePalette palette, float radiusDp) {
        return flat(palette.accent, dpToPx(context, snapRadius(radiusDp)));
    }

    public static GradientDrawable createPrimaryButtonDrawable(ThemePalette palette, int radiusPx) {
        return flat(palette.accent, snapRadiusPx(radiusPx));
    }

    /** 下沉区域（日志/控制台/快捷栏）：下沉底色，无描边，靠底色层级区分。 */
    public static GradientDrawable createSunkenDrawable(Context context, ThemePalette palette, float radiusDp) {
        return flat(palette.bgSunken, dpToPx(context, snapRadius(radiusDp)));
    }

    public static GradientDrawable createSuccessButtonDrawable(Context context, ThemePalette palette, float radiusDp) {
        int greenColor = palette.isDark ? 0xFF238636 : 0xFF2EA043;
        return flat(greenColor, dpToPx(context, snapRadius(radiusDp)));
    }

    /**
     * 危险按钮（停止 / 重置）：极简处理 —— 与次级按钮相同的中性底色，
     * 叠加约 8% 透明度的现有危险色作为极淡提示，危险色仅用于文字与图标（见 {@link #statusDangerColor}）。
     */
    public static GradientDrawable createDangerButtonDrawable(Context context, ThemePalette palette, float radiusDp) {
        return flat(blendOver(palette.bgHover, statusDangerColor(palette), 0.08f),
                dpToPx(context, snapRadius(radiusDp)));
    }

    // ================= 状态语义色（沿用既有色值，不新增颜色）=================

    public static int statusSuccessColor(ThemePalette palette) {
        return palette.isDark ? 0xFF3FB950 : 0xFF1A7F37;
    }

    public static int statusWarningColor(ThemePalette palette) {
        return palette.isDark ? 0xFFD29922 : 0xFF9A6700;
    }

    public static int statusDangerColor(ThemePalette palette) {
        return palette.isDark ? 0xFFF85149 : 0xFFCF222E;
    }

    /** 将 top 以 alpha 比例叠加到不透明 base 上，得到派生色（仅用于从既有色值派生淡色底）。 */
    public static int blendOver(int base, int top, float alpha) {
        int r = Math.round(((base >> 16) & 0xFF) * (1 - alpha) + ((top >> 16) & 0xFF) * alpha);
        int g = Math.round(((base >> 8) & 0xFF) * (1 - alpha) + ((top >> 8) & 0xFF) * alpha);
        int b = Math.round((base & 0xFF) * (1 - alpha) + (top & 0xFF) * alpha);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** 输入框：保留 1dp 发丝描边以提示可输入。 */
    public static GradientDrawable createInputDrawable(Context context, ThemePalette palette, float radiusDp) {
        int inputBg = palette.isDark ? palette.bg : palette.bgPanel;
        GradientDrawable gd = flat(inputBg, dpToPx(context, snapRadius(radiusDp)));
        gd.setStroke(hairlinePx(context), palette.border);
        return gd;
    }

    /** 徽章：强调色浅底，无描边。 */
    public static GradientDrawable createBadgeDrawable(Context context, ThemePalette palette, float radiusDp) {
        return flat(palette.accentSubtle, dpToPx(context, snapRadius(radiusDp)));
    }

    /** 底部导航：面板底色 + 仅顶部 1dp 发丝分隔线。 */
    public static Drawable createNavBarDrawable(Context context, ThemePalette palette) {
        GradientDrawable line = new GradientDrawable();
        line.setColor(palette.border);
        GradientDrawable body = new GradientDrawable();
        body.setColor(palette.bgPanel);
        LayerDrawable ld = new LayerDrawable(new Drawable[]{line, body});
        ld.setLayerInset(1, 0, hairlinePx(context), 0, 0);
        return ld;
    }

    /** 将加粗标题统一为 Medium 字重（等宽字体与非加粗文本保持不变）。 */
    public static void applyMediumWeight(TextView tv) {
        if (tv == null || tv instanceof EditText) return;
        Typeface tf = tv.getTypeface();
        if (tf == null || !tf.isBold()) return;
        Paint p = tv.getPaint();
        if (p != null && Math.abs(p.measureText("i") - p.measureText("W")) < 0.5f) return; // 等宽字体
        tv.setTypeface(MEDIUM_TYPEFACE);
    }

    public static final Typeface MEDIUM_TYPEFACE = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    // ================= 视图层级递归主题应用 (Tree Theming Engine) =================

    public static void applyThemeToHierarchy(View v, ThemePalette palette) {
        if (v == null || palette == null) return;

        styleSingleView(v, palette);
        if (v instanceof TextView) {
            applyMediumWeight((TextView) v);
        }

        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                applyThemeToHierarchy(vg.getChildAt(i), palette);
            }
        }
    }

    private static void styleSingleView(View v, ThemePalette palette) {
        Context ctx = v.getContext();
        String idName = getResourceEntryName(v);

        // 1. 输入框 (EditText)
        if (v instanceof EditText) {
            EditText et = (EditText) v;
            et.setBackground(createInputDrawable(ctx, palette, RADIUS_CONTROL));
            if ("launchPortInput".equals(idName)) {
                et.setTextColor(palette.accent);
            } else {
                et.setTextColor(palette.text);
            }
            et.setHintTextColor(palette.textMuted);
            return;
        }

        // 2. 文本与交互按钮 (TextView)
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            if (idName != null) {
                // 主操作高亮按钮
                if (idName.equals("launchStartBtn") || idName.equals("btnUpdateAllPlugins")
                        || idName.equals("btnCheckAppUpdate") || idName.equals("btnSmartInstall")
                        || idName.equals("btnInstallCustomPlugin") || idName.equals("btnLaunchPortApplySave")
                        || idName.equals("btnSendFloatPetChat") || idName.equals("btnSmartFix")) {
                    tv.setBackground(createPrimaryButtonDrawable(ctx, palette, RADIUS_CONTROL));
                    tv.setTextColor(palette.isDark ? 0xFF0D1117 : 0xFFFFFFFF);
                    return;
                }
                // 停止/危险操作按钮
                if (idName.equals("launchStopBtn") || idName.equals("btnLaunchStop") || idName.equals("btnResetContainer")
                        || idName.equals("btnClearContainerData")) {
                    tv.setBackground(createDangerButtonDrawable(ctx, palette, RADIUS_CONTROL));
                    tv.setTextColor(statusDangerColor(palette));
                    return;
                }
                // 顶栏特定文本
                if (idName.equals("btnSettingsSubWindowBack")) {
                    tv.setTextColor(palette.accent);
                    return;
                }
                if (idName.equals("btnSettingsSubWindowClose")) {
                    tv.setTextColor(palette.textMuted);
                    return;
                }
                if (idName.equals("tvSettingsSubWindowTitle") || idName.equals("launchStateTv")
                        || idName.equals("tvDetailedLogText")) {
                    tv.setTextColor(palette.text);
                    return;
                }
                if (idName.equals("launchSubStateTv")) {
                    tv.setTextColor(palette.textMuted);
                    return;
                }
                // 状态徽章 (端口 PORT / 桌宠开关 / 当前主题)：强调色浅底 + 强调色文字，所有主题均清晰可读
                if (!idName.startsWith("btn") && (idName.endsWith("Badge") || idName.startsWith("badge"))) {
                    tv.setBackground(createBadgeDrawable(ctx, palette, RADIUS_SMALL));
                    if (!idName.equals("launchPetStateBadge")) {
                        tv.setTextColor(palette.accent);
                    }
                    return;
                }
                // 普通次级按钮
                if (idName.startsWith("btn") || idName.endsWith("Btn")) {
                    tv.setBackground(createSecondaryButtonDrawable(ctx, palette, RADIUS_CONTROL));
                    if (idName.equals("btnRefreshPlugins") || idName.equals("btnSettingsExportBackup")
                            || idName.equals("btnSettingsImportBackup")) {
                        tv.setTextColor(palette.accent);
                    } else {
                        tv.setTextColor(palette.text);
                    }
                    return;
                }
                // 底栏与过滤 Chip 由各自专职逻辑刷新
                if (idName.startsWith("chipCat") || (idName.startsWith("tab") && idName.endsWith("Text"))) {
                    return;
                }
                if (idName.startsWith("tab") && idName.endsWith("Icon")) {
                    return;
                }
            }

            // 通用 TextView 角色探测与映射
            Object tag = tv.getTag();
            String role = null;
            if (tag instanceof String && ((String) tag).startsWith("theme_role:")) {
                role = ((String) tag).substring("theme_role:".length());
            } else {
                int curColor = tv.getCurrentTextColor();
                if (isColorSimilar(curColor, 0xFF58A6FF) || isColorSimilar(curColor, 0xFF38BDF8)
                        || isColorSimilar(curColor, 0xFF3B82F6) || isColorSimilar(curColor, 0xFF245BCE)
                        || isColorSimilar(curColor, 0xFF1E6559) || isColorSimilar(curColor, 0xFF914360)
                        || isColorSimilar(curColor, 0xFFACCCB7)) {
                    role = "accent";
                } else if (isColorSimilar(curColor, 0xFF3FB950) || isColorSimilar(curColor, 0xFF2EA043)
                        || isColorSimilar(curColor, 0xFF238636) || isColorSimilar(curColor, 0xFF10B981)) {
                    role = "status_success";
                } else if (isColorSimilar(curColor, 0xFFF85149) || isColorSimilar(curColor, 0xFFDA3633)
                        || isColorSimilar(curColor, 0xFFE5534B) || isColorSimilar(curColor, 0xFFEF4444)) {
                    role = "status_danger";
                } else if (isColorSimilar(curColor, 0xFFD29922) || isColorSimilar(curColor, 0xFFE3B341)
                        || isColorSimilar(curColor, 0xFFF59E0B)) {
                    role = "status_warning";
                } else if (isColorSimilar(curColor, 0xFF8B949E) || isColorSimilar(curColor, 0xFFC9D1D9)
                        || isColorSimilar(curColor, 0xFFA0AEC0) || isColorSimilar(curColor, 0xFF94A3B8)
                        || isColorSimilar(curColor, 0xFF6E7681) || isColorSimilar(curColor, 0xFF666666)) {
                    role = "muted";
                } else if (isColorSimilar(curColor, 0xFFF0F6FC) || isColorSimilar(curColor, 0xFFFFFFFF)
                        || isColorSimilar(curColor, 0xFFE6EDF3) || isColorSimilar(curColor, 0xFF1A1A1A)) {
                    role = "primary";
                } else {
                    if (tv.getTextSize() >= dpToPx(ctx, 13.5f)
                            || (tv.getTypeface() != null && tv.getTypeface().isBold())) {
                        role = "primary";
                    } else {
                        role = "muted";
                    }
                }
                tv.setTag("theme_role:" + role);
            }

            if ("accent".equals(role)) {
                tv.setTextColor(palette.accent);
            } else if ("status_success".equals(role)) {
                tv.setTextColor(palette.isDark ? 0xFF3FB950 : 0xFF1A7F37);
            } else if ("status_danger".equals(role)) {
                tv.setTextColor(palette.isDark ? 0xFFF85149 : 0xFFCF222E);
            } else if ("status_warning".equals(role)) {
                tv.setTextColor(palette.isDark ? 0xFFD29922 : 0xFF9A6700);
            } else if ("muted".equals(role)) {
                tv.setTextColor(palette.textMuted);
            } else {
                tv.setTextColor(palette.text);
            }
            return;
        }

        // 3. 容器与面板 (ViewGroup)
        if (v instanceof ViewGroup) {
            if (idName != null) {
                // 页面主根底色与主要容器
                if (idName.equals("mainRootLayout") || idName.equals("viewLaunch")
                        || idName.equals("viewPiWeb") || idName.equals("piWebOfflineCard")
                        || idName.equals("viewPlugins") || idName.equals("viewSettings")
                        || idName.equals("viewSettingsSubWindow")) {
                    v.setBackgroundColor(palette.bg);
                    return;
                }
                // 状态角标与徽章 (包含端口Badge、桌宠开关Badge、文件管理Badge等)
                if (idName.endsWith("Badge") || idName.startsWith("badge")
                        || idName.equals("launchPortBadge") || idName.equals("launchPetStateBadge")
                        || idName.equals("btnLaunchFileBadge") || idName.equals("btnLaunchHelpGuideBadge")) {
                    v.setBackground(createBadgeDrawable(ctx, palette, RADIUS_SMALL));
                    return;
                }
                // 二级窗口顶栏
                if (idName.equals("viewSettingsSubHeader")) {
                    v.setBackgroundColor(palette.bgPanel);
                    return;
                }
                // 底栏导航栏
                if (idName.equals("bottomNavBar")) {
                    v.setBackground(createNavBarDrawable(ctx, palette));
                    return;
                }
                // 悬浮工具球
                if (idName.equals("floatingBall")) {
                    GradientDrawable ballBg = new GradientDrawable();
                    ballBg.setColor(palette.bgPanel);
                    ballBg.setShape(GradientDrawable.OVAL);
                    ballBg.setStroke(hairlinePx(ctx), palette.border);
                    v.setBackground(ballBg);
                    if (v instanceof TextView) {
                        ((TextView) v).setTextColor(palette.accent);
                    }
                    return;
                }
                // 悬浮球垂直展开菜单
                if (idName.equals("floatingMenuVertical")) {
                    v.setBackground(createCardDrawable(ctx, palette, RADIUS_CARD));
                    return;
                }
                // 下沉等宽文本/日志/控制台区域与快捷配置栏
                if (idName.equals("viewDetailedLogContainer") || idName.equals("physicsContainer")
                        || idName.equals("launchConsoleScroll") || idName.equals("cardLaunchPortQuickSet")) {
                    v.setBackground(createSunkenDrawable(ctx, palette, RADIUS_CARD));
                    return;
                }
                // 卡片容器 (包含首页状态卡、双指标卡、文件管理卡片、桌宠开关卡片、日志控制台等)
                if (idName.startsWith("card") || idName.endsWith("Card")
                        || idName.equals("piWebOfflineCard") || idName.equals("floatingPetChatCard")
                        || idName.equals("btnLaunchFileManager") || idName.equals("btnLaunchPetToggle")
                        || idName.equals("btnLaunchHelpGuide")) {
                    v.setBackground(createCardDrawable(ctx, palette, RADIUS_CARD));
                    return;
                }
                // 设置菜单选项条目
                if (idName.startsWith("menuItem")) {
                    v.setBackground(createSecondaryButtonDrawable(ctx, palette, RADIUS_CONTROL));
                    return;
                }
            }
            return;
        }

        // 4. 分割线 (Divider View)
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp != null && lp.height > 0 && lp.height <= dpToPx(ctx, 2.5f)) {
            v.setBackgroundColor(palette.border);
        }
    }

    private static String getResourceEntryName(View v) {
        if (v == null || v.getId() == View.NO_ID) return null;
        try {
            return v.getResources().getResourceEntryName(v.getId());
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isColorSimilar(int c1, int c2) {
        if (c1 == c2) return true;
        int r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
        int r2 = (c2 >> 16) & 0xFF, g2 = (c2 >> 8) & 0xFF, b2 = c2 & 0xFF;
        return Math.abs(r1 - r2) + Math.abs(g1 - g2) + Math.abs(b1 - b2) < 45;
    }
}
