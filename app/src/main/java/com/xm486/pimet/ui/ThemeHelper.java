package com.xm486.pimet.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.TypedValue;

import com.xm486.pimet.R;

/**
 * 主题风格管理器：管理 App 的色彩主题（经典单色、暗黑/OLED 纯黑、双拼撞色/高对比）。
 */
public class ThemeHelper {

    private static final String PREF_NAME = "app_theme_prefs";
    private static final String KEY_THEME = "key_app_theme";

    // 经典单色系
    public static final int THEME_PURPLE = 0;   // 经典紫（默认）
    public static final int THEME_BLUE = 1;     // 极光蓝
    public static final int THEME_GREEN = 2;    // 翡翠绿
    public static final int THEME_ORANGE = 3;   // 落日橙
    public static final int THEME_PINK = 4;     // 甜心粉

    // 暗黑与纯黑系
    public static final int THEME_DARK = 5;     // 深邃暗夜 (Midnight Dark)
    public static final int THEME_OLED = 6;     // 极致纯黑 (OLED Pure Black)

    // 双拼撞色 / 高对比系
    public static final int THEME_CYBERPUNK = 7; // 赛博朋克 (Cyberpunk: 霓虹粉 + 赛博青)
    public static final int THEME_BLACK_GOLD = 8;// 黑金奢华 (Black & Gold: 曜石黑 + 璀璨金)
    public static final int THEME_TWILIGHT = 9;  // 暮色黄昏 (Twilight: 暮夜紫 + 暖阳橙)
    public static final int THEME_ICE_FIRE = 10; // 冰与火 (Ice & Fire: 炽火红 + 寒冰蓝)

    public static class ThemeInfo {
        public final int id;
        public final String name;
        public final int primaryColor;
        public final int secondaryColor;
        public final String icon;
        public final String category;
        public final boolean isDark;

        public ThemeInfo(int id, String name, int primaryColor, int secondaryColor,
                         String icon, String category, boolean isDark) {
            this.id = id;
            this.name = name;
            this.primaryColor = primaryColor;
            this.secondaryColor = secondaryColor;
            this.icon = icon;
            this.category = category;
            this.isDark = isDark;
        }

        public boolean isTwoTone() {
            return primaryColor != secondaryColor;
        }
    }

    public static final ThemeInfo[] THEMES = new ThemeInfo[]{
            // 单色经典
            new ThemeInfo(THEME_PURPLE, "经典紫", 0xFF5B4BE0, 0xFF5B4BE0, "🟣", "单色经典", false),
            new ThemeInfo(THEME_BLUE, "极光蓝", 0xFF0284C7, 0xFF0284C7, "🔵", "单色经典", false),
            new ThemeInfo(THEME_GREEN, "翡翠绿", 0xFF059669, 0xFF059669, "🟢", "单色经典", false),
            new ThemeInfo(THEME_ORANGE, "落日橙", 0xFFEA580C, 0xFFEA580C, "🟠", "单色经典", false),
            new ThemeInfo(THEME_PINK, "甜心粉", 0xFFDB2777, 0xFFDB2777, "🌸", "单色经典", false),

            // 暗黑 / 纯黑
            new ThemeInfo(THEME_DARK, "深邃暗夜", 0xFFA78BFA, 0xFF38BDF8, "🌙", "暗黑/纯黑", true),
            new ThemeInfo(THEME_OLED, "OLED纯黑", 0xFF10B981, 0xFF06B6D4, "🖤", "暗黑/纯黑", true),

            // 双拼撞色
            new ThemeInfo(THEME_CYBERPUNK, "赛博朋克", 0xFFF43F5E, 0xFF06B6D4, "⚡", "双拼撞色", true),
            new ThemeInfo(THEME_BLACK_GOLD, "黑金奢华", 0xFFF59E0B, 0xFFFBBF24, "👑", "双拼撞色", true),
            new ThemeInfo(THEME_TWILIGHT, "暮色黄昏", 0xFF7C3AED, 0xFFEA580C, "🌆", "双拼撞色", false),
            new ThemeInfo(THEME_ICE_FIRE, "冰与火", 0xFFEF4444, 0xFF0284C7, "🔥", "双拼撞色", false)
    };

    public static int getTheme(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return sp.getInt(KEY_THEME, THEME_PURPLE);
    }

    public static void setTheme(Context context, int themeId) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_THEME, themeId).apply();
    }

    public static int getThemeResId(int themeId) {
        switch (themeId) {
            case THEME_BLUE:
                return R.style.AppTheme_Blue;
            case THEME_GREEN:
                return R.style.AppTheme_Green;
            case THEME_ORANGE:
                return R.style.AppTheme_Orange;
            case THEME_PINK:
                return R.style.AppTheme_Pink;
            case THEME_DARK:
                return R.style.AppTheme_Dark;
            case THEME_OLED:
                return R.style.AppTheme_Oled;
            case THEME_CYBERPUNK:
                return R.style.AppTheme_Cyberpunk;
            case THEME_BLACK_GOLD:
                return R.style.AppTheme_BlackGold;
            case THEME_TWILIGHT:
                return R.style.AppTheme_Twilight;
            case THEME_ICE_FIRE:
                return R.style.AppTheme_IceFire;
            case THEME_PURPLE:
            default:
                return R.style.AppTheme_Purple;
        }
    }

    /** 在 Activity.onCreate 的 super.onCreate 之前调用，注入当前选中主题 */
    public static void applyTheme(Activity activity) {
        int themeId = getTheme(activity);
        activity.setTheme(getThemeResId(themeId));
    }

    public static int getPrimaryColor(Context context) {
        int themeId = getTheme(context);
        for (ThemeInfo t : THEMES) {
            if (t.id == themeId) return t.primaryColor;
        }
        return 0xFF5B4BE0;
    }

    public static int getAttrColor(Context context, int attrResId, int fallbackColor) {
        TypedValue typedValue = new TypedValue();
        if (context.getTheme().resolveAttribute(attrResId, typedValue, true)) {
            return typedValue.data;
        }
        return fallbackColor;
    }

    public static int getBackgroundColor(Context context) {
        return getAttrColor(context, android.R.attr.colorBackground, 0xFFFBF9FF);
    }

    public static int getCardColor(Context context) {
        return getAttrColor(context, com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF);
    }

    public static int getOutlineColor(Context context) {
        return getAttrColor(context, com.google.android.material.R.attr.colorOutline, 0xFFDCD7E8);
    }

    public static int getTextPrimary(Context context) {
        return getAttrColor(context, R.attr.colorTextPrimary, 0xFF1A1626);
    }

    public static int getTextSecondary(Context context) {
        return getAttrColor(context, R.attr.colorTextSecondary, 0xFF4C4761);
    }

    public static int getTextTertiary(Context context) {
        return getAttrColor(context, R.attr.colorTextTertiary, 0xFF6F6A85);
    }
}
