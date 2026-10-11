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
            // 首帧：支持充裕的多行文本展开（至多10行），允许气泡跟随配置自适应宽度
            bubble.setMaxLines(10);
            bubble.setEllipsize(TextUtils.TruncateAt.END);
            int bw = PetRegistry.getIntPref(service, PetRegistry.KEY_BUBBLE_WIDTH, PetRegistry.DEFAULT_BUBBLE_WIDTH);
            bubble.setMaxWidth(service.dp(Math.max(220, bw)));
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
            handler.postDelayed(this, 48);
        } else {
            // 播完当前分段：
            if (onTyped != null) {
                // 有后续分段：保留充裕的阅读时间（至少 2.8 秒，按字数动态延长），绝不瞬间刷掉
                int readPause = Math.max(2800, Math.min(7000, length * 140));
                handler.postDelayed(onTyped, readPause);
            } else {
                // 最终分段结束：预留 5.5 秒 ~ 10 秒供用户完整阅读
                int finalPause = Math.max(5500, Math.min(10000, length * 160));
                service.scheduleBubbleHide(finalPause);
            }
        }
    }
}
