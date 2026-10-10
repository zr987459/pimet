package com.xm486.pimet;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.webkit.WebView;

import java.util.ArrayList;
import java.util.List;

/**
 * PiMet 界面主题管理器 (ThemeManager)
 * 深度对齐 Pi-Web 官方 UI 设计语言与配色规范。
 * 支持多种主题外观：浅色 (Light)、深色 (Dark)、雾青 (Mist)、蔷薇 (Rose)、松夜 (Pine) 与 跟随系统 (Auto)。
 * 支持与系统深浅色切换联动及同步注入 Web 端 dataset.theme 与 localStorage。
 */
public final class ThemeManager {
    public static final String PREF_NAME = "pimet_theme_prefs";
    public static final String PREF_THEME_KEY = "app_theme_mode";

    public static final String THEME_AUTO = "auto";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";
    public static final String THEME_MIST = "mist";
    public static final String THEME_ROSE = "rose";
    public static final String THEME_PINE = "pine";

    public static class ThemePalette {
        public final String id;
        public final String name;
        public final String desc;
        public final int bg;
        public final int bgPanel;
        public final int bgHover;
        public final int bgSelected;
        public final int border;
        public final int text;
        public final int textMuted;
        public final int textDim;
        public final int accent;
        public final int accentHover;
        public final int accentContrast;
        public final boolean isDark;

        public ThemePalette(String id, String name, String desc,
                            int bg, int bgPanel, int bgHover, int bgSelected,
                            int border, int text, int textMuted, int textDim,
                            int accent, int accentHover, int accentContrast,
                            boolean isDark) {
            this.id = id;
            this.name = name;
            this.desc = desc;
            this.bg = bg;
            this.bgPanel = bgPanel;
            this.bgHover = bgHover;
            this.bgSelected = bgSelected;
            this.border = border;
            this.text = text;
            this.textMuted = textMuted;
            this.textDim = textDim;
            this.accent = accent;
            this.accentHover = accentHover;
            this.accentContrast = accentContrast;
            this.isDark = isDark;
        }
    }

    public static final ThemePalette PALETTE_LIGHT = new ThemePalette(
            THEME_LIGHT, "浅色 (Light)", "明亮纯净 · 经典素雅",
            0xFFFFFFFF, 0xFFF5F5F5, 0xFFEEEEEE, 0xFFE8E8E8,
            0xFFE0E0E0, 0xFF1A1A1A, 0xFF515C6B, 0xFF5E6673,
            0xFF245BCE, 0xFF1D4ED8, 0xFFFFFFFF,
            false
    );

    public static final ThemePalette PALETTE_DARK = new ThemePalette(
            THEME_DARK, "深色 (Dark)", "沉浸极客 · 低光舒适",
            0xFF0D1117, 0xFF161B22, 0xFF21262D, 0xFF30363D,
            0xFF30363D, 0xFFF0F6FC, 0xFF8B949E, 0xFF6E7681,
            0xFF58A6FF, 0xFF1F6FEB, 0xFFFFFFFF,
            true
    );

    public static final ThemePalette PALETTE_MIST = new ThemePalette(
            THEME_MIST, "雾青 (Mist)", "水墨云雾 · 清透青灰",
            0xFFF4F8F7, 0xFFE9F0EE, 0xFFE0EAE7, 0xFFD7E4DF,
            0xFFAFC4BA, 0xFF202E2B, 0xFF455F56, 0xFF52685F,
            0xFF1E6559, 0xFF174F46, 0xFFFFFFFF,
            false
    );

    public static final ThemePalette PALETTE_ROSE = new ThemePalette(
            THEME_ROSE, "蔷薇 (Rose)", "暮色柔粉 · 温润淡雅",
            0xFFFCF7F8, 0xFFF3EDEF, 0xFFEEE3E7, 0xFFE8DCE1,
            0xFFCDB5BF, 0xFF34282E, 0xFF65505A, 0xFF705B65,
            0xFF914360, 0xFF76324D, 0xFFFFFFFF,
            false
    );

    public static final ThemePalette PALETTE_PINE = new ThemePalette(
            THEME_PINE, "松夜 (Pine)", "幽深松林 · 静谧暗青",
            0xFF19201F, 0xFF212B28, 0xFF2B3632, 0xFF35433C,
            0xFF4A5F52, 0xFFE6EDE8, 0xFFC3D0C6, 0xFFAFC2B5,
            0xFFACCCB7, 0xFFD0E2D3, 0xFF182B20,
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
        String pref = getThemePreference(context);
        if (THEME_AUTO.equals(pref)) {
            return isSystemNightMode(context) ? PALETTE_DARK : PALETTE_LIGHT;
        }
        return getPaletteById(pref);
    }

    public static ThemePalette getPaletteById(String themeId) {
        if (themeId == null) return PALETTE_DARK;
        switch (themeId) {
            case THEME_LIGHT:
                return PALETTE_LIGHT;
            case THEME_MIST:
                return PALETTE_MIST;
            case THEME_ROSE:
                return PALETTE_ROSE;
            case THEME_PINE:
                return PALETTE_PINE;
            case THEME_DARK:
            default:
                return PALETTE_DARK;
        }
    }

    public static String getThemeLabel(String themeId) {
        if (THEME_AUTO.equals(themeId)) return "🌓 跟随系统";
        if (THEME_LIGHT.equals(themeId)) return "☀️ 浅色 (Light)";
        if (THEME_DARK.equals(themeId)) return "🌙 深色 (Dark)";
        if (THEME_MIST.equals(themeId)) return "🌿 雾青 (Mist)";
        if (THEME_ROSE.equals(themeId)) return "🌸 蔷薇 (Rose)";
        if (THEME_PINE.equals(themeId)) return "🌲 松夜 (Pine)";
        return "深色 (Dark)";
    }

    /**
     * 将主题同步应用至 Activity 窗口、状态栏与导航栏
     */
    public static void applyWindowTheme(Activity activity, ThemePalette palette) {
        if (activity == null || palette == null) return;
        Window window = activity.getWindow();
        if (window == null) return;

        window.getDecorView().setBackgroundColor(palette.bg);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.setStatusBarColor(palette.bgPanel);
            window.setNavigationBarColor(palette.bgPanel);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            View decor = window.getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (!palette.isDark) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            decor.setSystemUiVisibility(flags);
        }
    }

    /**
     * 将主题状态无缝注入并同步至 Pi-Web 前端工作台 WebView
     */
    public static void syncThemeToWebView(WebView webView, Context context) {
        if (webView == null || context == null) return;
        try {
            String pref = getThemePreference(context);
            ThemePalette palette = getEffectivePalette(context);
            String js = "try { " +
                    "localStorage.setItem('pi-theme', '" + pref + "'); " +
                    "document.documentElement.dataset.theme = '" + palette.id + "'; " +
                    "document.documentElement.classList.toggle('dark', " + (palette.isDark ? "true" : "false") + "); " +
                    "} catch(e) {}";
            webView.evaluateJavascript(js, null);
        } catch (Throwable ignored) {}
    }

    /**
     * 创建卡片背景 Drawable
     */
    public static GradientDrawable createCardDrawable(ThemePalette palette, int radiusPx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(palette.bgPanel);
        gd.setCornerRadius(radiusPx);
        gd.setStroke(1, palette.border);
        return gd;
    }

    /**
     * 创建强调色按钮 Drawable
     */
    public static GradientDrawable createAccentButtonDrawable(ThemePalette palette, int radiusPx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(palette.accent);
        gd.setCornerRadius(radiusPx);
        return gd;
    }

    /**
     * 创建次级按钮 Drawable
     */
    public static GradientDrawable createSecondaryButtonDrawable(ThemePalette palette, int radiusPx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(palette.bgHover);
        gd.setCornerRadius(radiusPx);
        gd.setStroke(1, palette.border);
        return gd;
    }
}
