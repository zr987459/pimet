package com.xm486.pimet.pet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.widget.TextView;

import com.xm486.pimet.ThemeManager;

/**
 * 桌宠头顶的对话气泡（v1.7.4）。
 *
 * 跟随主题：底色 = 面板色（几乎不透明，压在任意应用上都清晰可读），
 * 1dp 发丝描边 = 主题描边色，文字 = 主题正文色；底部居中一个小尾巴指向桌宠。
 * 尾巴占用的高度通过底部内边距预留（见 {@link #tailHeight()}），不会盖住文字。
 */
public class PetBubbleView extends TextView {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private float radius;
    private float tailW;
    private float tailH;
    private float strokeW;

    public PetBubbleView(Context context) {
        super(context);
        float d = context.getResources().getDisplayMetrics().density;
        radius = 12f * d;
        tailW = 12f * d;
        tailH = 6f * d;
        strokeW = Math.max(1f, d);
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(strokeW);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        setBackground(null);
        setWillNotDraw(false);
        applyTheme(ThemeManager.getEffectivePalette(context));
    }

    /** 尾巴高度（px）：调用方设置内边距时把它加到底部 */
    public int tailHeight() {
        return Math.round(tailH);
    }

    public void applyTheme(ThemeManager.ThemePalette p) {
        if (p == null) return;
        // 面板色 96% 不透明：压在任意壁纸/应用上都可读，又不至于像实心块一样突兀
        fill.setColor((p.bgPanel & 0x00FFFFFF) | 0xF5000000);
        stroke.setColor(p.border);
        setTextColor(p.text);
        setLinkTextColor(p.accent);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w > 0 && h > tailH) {
            float half = strokeW / 2f;
            float bottom = h - tailH;
            rect.set(half, half, w - half, bottom - half);
            float r = Math.min(radius, Math.min(rect.width(), rect.height()) / 2f);
            float cx = w / 2f;
            float tw = Math.min(tailW, Math.max(0f, rect.width() - 2 * r));
            path.reset();
            path.moveTo(rect.left + r, rect.top);
            path.lineTo(rect.right - r, rect.top);
            path.quadTo(rect.right, rect.top, rect.right, rect.top + r);
            path.lineTo(rect.right, rect.bottom - r);
            path.quadTo(rect.right, rect.bottom, rect.right - r, rect.bottom);
            path.lineTo(cx + tw / 2f, rect.bottom);
            path.lineTo(cx, h - half);
            path.lineTo(cx - tw / 2f, rect.bottom);
            path.lineTo(rect.left + r, rect.bottom);
            path.quadTo(rect.left, rect.bottom, rect.left, rect.bottom - r);
            path.lineTo(rect.left, rect.top + r);
            path.quadTo(rect.left, rect.top, rect.left + r, rect.top);
            path.close();
            canvas.drawPath(path, fill);
            canvas.drawPath(path, stroke);
        }
        super.onDraw(canvas);
    }
}
