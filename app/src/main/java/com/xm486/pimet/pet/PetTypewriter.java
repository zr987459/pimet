package com.xm486.pimet.pet;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

/**
 * 打字机效果：把 AI 回复一字一字地显示在对话气泡里。
 *
 * 每 50ms 递增一个字符；每帧重新测量气泡高度并把桌宠/状态卡顶开，
 * 播完后 1.5s 自动隐藏气泡。
 */
public class PetTypewriter implements Runnable {

    private final PetOverlayService service;
    private final Handler handler;
    private final String text;
    private Runnable onTyped;
    private int index = 0;
    public PetTypewriter(PetOverlayService service, String text) {
        this(service, text, null);
    }
    /** 可选 onTyped：本段打字结束回调（用于「分段发送」——打完全立刻接下一段，末段传 null 走默认隐藏） */
    public PetTypewriter(PetOverlayService service, String text, Runnable onTyped) {
        this.service = service;
        this.text = text;
        this.onTyped = onTyped;
        this.handler = new Handler(Looper.getMainLooper());
    }

    @Override
    public void run() {
        if (service == null || text == null) return;
        TextView bubble = service.getChatBubble();
        if (bubble == null) return;

        int length = text.length();
        if (index >= length) return;

        if (index == 0) {
            // 首帧：限制气泡尺寸，避免长回复把悬浮窗顶出屏幕
            bubble.setMaxLines(5);
            bubble.setEllipsize(TextUtils.TruncateAt.END);
            bubble.setMaxWidth(service.dp(210));
            service.cancelBubbleHide();
            // 取消上一次隐藏动画，防止其 withEndAction 把新气泡重新设成 GONE
            bubble.animate().cancel();
            bubble.setScaleX(0.9f);
            bubble.setScaleY(0.9f);
            bubble.setAlpha(0f);
            bubble.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.1f))
                    .start();
        }

        index++;
        bubble.setText(text.substring(0, index));
        bubble.setVisibility(View.VISIBLE);

        // 重新测量并顶开桌宠与状态卡
        service.layoutChatBubble();

        if (index < length) {
            handler.postDelayed(this, 50);
        } else {
            // 播完：有「分段接续」回调则交给它（打完全立刻播下一段），否则 1.5s 后自动隐藏气泡
            if (onTyped != null) {
                onTyped.run();
            } else {
                service.scheduleBubbleHide(1500);
            }
        }
    }
}
