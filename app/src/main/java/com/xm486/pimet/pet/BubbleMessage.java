package com.xm486.pimet.pet;

import android.view.View;
import android.widget.TextView;

/**
 * 对话气泡即时消息：把一句话直接显示在桌宠头顶气泡（无打字机效果）。
 *
 * 用于聊天过程中的中间态提示，如「思考中...」「已发送，Operit处理中」。
 * 显示后 1.5s 自动隐藏（由 PetOverlayService 统一调度）。
 */
public class BubbleMessage implements Runnable {

    private final PetOverlayService service;
    private final String text;

    public BubbleMessage(PetOverlayService service, String text) {
        this.service = service;
        this.text = text;
    }

    @Override
    public void run() {
        if (service == null) return;
        TextView bubble = service.getChatBubble();
        if (bubble == null) return;

        bubble.setText(text);
        // 测量气泡高度并把桌宠/状态卡顶开
        service.layoutChatBubble();

        // 先取消上一次隐藏动画（连同 withEndAction），防止它把新气泡重新设成 GONE
        bubble.animate().cancel();
        bubble.setVisibility(View.VISIBLE);
        bubble.setAlpha(0f);
        bubble.setScaleX(0.85f);
        bubble.setScaleY(0.85f);
        bubble.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(220)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f))
                .start();

        // 1.5s 后自动隐藏
        service.cancelBubbleHide();
        service.scheduleBubbleHide(1500);
    }
}