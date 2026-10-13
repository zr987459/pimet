package com.xm486.pimet.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import com.xm486.pimet.R;
import com.xm486.pimet.ThemeManager;

/**
 * 通知统一外观：状态栏小图标（猫耳 + π 单色剪影）、主题强调色、启动图标大图。
 * 应用内所有通知 / 前台服务都应从这里取图标，保证与启动图标一致。
 */
public final class NotificationStyle {

    /** 状态栏小图标：纯白 + 透明（系统只取 alpha）。 */
    public static final int SMALL_ICON = R.drawable.ic_stat_pimet;

    private static volatile Bitmap largeIcon;

    private NotificationStyle() {}

    /** 当前主题强调色，用于通知展开态的小图标与操作按钮着色。 */
    public static int accentColor(Context context) {
        try {
            return ThemeManager.getEffectivePalette(context).accent;
        } catch (Throwable t) {
            return 0xFF2F9E8F;
        }
    }

    /** 启动图标位图（自适应图标也能绘制），失败返回 null。结果缓存。 */
    public static Bitmap largeIcon(Context context) {
        Bitmap b = largeIcon;
        if (b != null && !b.isRecycled()) return b;
        try {
            Drawable d = context.getApplicationContext().getDrawable(R.mipmap.ic_launcher_round);
            if (d == null) return null;
            int px = Math.round(64 * context.getResources().getDisplayMetrics().density);
            b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, px, px);
            d.draw(c);
            largeIcon = b;
            return b;
        } catch (Throwable t) {
            return null;
        }
    }
}
