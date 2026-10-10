package com.xm486.pimet.pet;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * 桌宠二级详细设置弹窗：专门用于调节尺寸、气泡字号与宽度、物理惯性、活跃度与动作。
 * 实时滑动立即生效并持久化，不遮挡桌宠。
 */
public class PetParamsDialog {

    private final Context context;
    private final PetOverlayService service;
    private final Runnable onChangeCallback;
    private AlertDialog dialog;

    public static void show(Context context, PetOverlayService service, Runnable onChangeCallback) {
        new PetParamsDialog(context, service, onChangeCallback).show();
    }

    public PetParamsDialog(PetOverlayService service) {
        this(service, service, null);
    }

    public PetParamsDialog(Context context, Runnable onChangeCallback) {
        this(context, (context instanceof PetOverlayService ? (PetOverlayService) context : PetOverlayService.getInstance()), onChangeCallback);
    }

    public PetParamsDialog(Context context, PetOverlayService service, Runnable onChangeCallback) {
        this.context = context;
        this.service = (service != null) ? service : PetOverlayService.getInstance();
        this.onChangeCallback = onChangeCallback;
    }

    private int dp(int v) {
        return (int) (v * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    public void show() {
        if (dialog != null && dialog.isShowing()) return;

        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(context);

            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(12), dp(10), dp(12), dp(10));

            // ---- 顶栏：标题 + 返回按钮 ----
            LinearLayout topBar = new LinearLayout(context);
            topBar.setOrientation(LinearLayout.HORIZONTAL);
            topBar.setGravity(Gravity.CENTER_VERTICAL);

            TextView title = new TextView(context);
            title.setText("⚙️ 桌宠详细参数调节");
            title.setTextColor(0xFFE2E8F0);
            title.setTextSize(12.5f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            topBar.addView(title, titleLp);

            TextView closeBtn = new TextView(context);
            closeBtn.setText("✕");
            closeBtn.setTextColor(0xFF94A3B8);
            closeBtn.setTextSize(13f);
            closeBtn.setPadding(dp(4), dp(2), dp(4), dp(2));
            closeBtn.setOnClickListener(v -> dismiss());
            topBar.addView(closeBtn);
            root.addView(topBar);

            TextView hint = new TextView(context);
            hint.setText("滑动即生效 · 记忆持久化 · 杜绝遮挡桌宠");
            hint.setTextColor(0xFF64748B);
            hint.setTextSize(9.5f);
            hint.setPadding(0, dp(1), 0, dp(4));
            root.addView(hint);

            // ---- 可滑动内容区 ----
            ScrollView scroll = new ScrollView(context);
            scroll.setFillViewport(true);

            LinearLayout listLayout = new LinearLayout(context);
            listLayout.setOrientation(LinearLayout.VERTICAL);

            int curPetSize = PetRegistry.getIntPref(context, PetRegistry.KEY_PET_SIZE, PetRegistry.DEFAULT_PET_SIZE);
            int curBubbleWidth = PetRegistry.getIntPref(context, PetRegistry.KEY_BUBBLE_WIDTH, PetRegistry.DEFAULT_BUBBLE_WIDTH);
            int curBubbleTextSize = PetRegistry.getIntPref(context, PetRegistry.KEY_BUBBLE_TEXT_SIZE, PetRegistry.DEFAULT_BUBBLE_TEXT_SIZE);
            int curCardWidth = PetRegistry.getIntPref(context, PetRegistry.KEY_CARD_WIDTH, PetRegistry.DEFAULT_CARD_WIDTH);
            int curCardScale = PetRegistry.getIntPref(context, PetRegistry.KEY_CARD_SCALE, PetRegistry.DEFAULT_CARD_SCALE);
            int curMenuWidth = PetRegistry.getIntPref(context, PetRegistry.KEY_MENU_WIDTH, PetRegistry.DEFAULT_MENU_WIDTH);

            int curBounce = PetRegistry.getIntPref(context, PetRegistry.KEY_BOUNCE, PetRegistry.DEFAULT_BOUNCE);
            int curFriction = PetRegistry.getIntPref(context, PetRegistry.KEY_FRICTION, PetRegistry.DEFAULT_FRICTION);
            int curFlingThresh = PetRegistry.getIntPref(context, PetRegistry.KEY_FLING_THRESHOLD, PetRegistry.DEFAULT_FLING_THRESHOLD);
            int curStopSpeed = PetRegistry.getIntPref(context, PetRegistry.KEY_STOP_SPEED, PetRegistry.DEFAULT_STOP_SPEED);

            // 1. 桌宠大小: 32 ~ 140 dp (min 32)
            listLayout.addView(createCompactSlider("📏 桌宠大小", curPetSize, "dp", 32, 140, 32, (val) -> {
                PetRegistry.setIntPref(context, PetRegistry.KEY_PET_SIZE, val);
                if (service != null) service.applyPetSize(val);
                if (onChangeCallback != null) onChangeCallback.run();
            }));

            // 2. 气泡宽度: 100 ~ 280 dp (min 100)
            listLayout.addView(createCompactSlider("💬 气泡显示宽度", curBubbleWidth, "dp", 100, 280, 100, (val) -> {
                PetRegistry.setIntPref(context, PetRegistry.KEY_BUBBLE_WIDTH, val);
                if (service != null) service.applyBubbleWidth(val);
                if (onChangeCallback != null) onChangeCallback.run();
            }));

            // 3. 气泡文字大小: 9 ~ 18 sp (min 9)
            listLayout.addView(createCompactSlider("🔤 气泡文字字号", curBubbleTextSize, "sp", 9, 18, 9, (val) -> {
                PetRegistry.setIntPref(context, PetRegistry.KEY_BUBBLE_TEXT_SIZE, val);
                if (service != null) service.applyBubbleTextSize(val);
                if (onChangeCallback != null) onChangeCallback.run();
            }));

            // 4. 边界反弹: 0 ~ 100 % (min 0)
            listLayout.addView(createCompactSlider("🏀 边界反弹能量", curBounce, "%", 0, 100, 0, (val) -> {
                PetRegistry.setIntPref(context, PetRegistry.KEY_BOUNCE, val);
                if (onChangeCallback != null) onChangeCallback.run();
            }));

            // 5. 空气摩擦: 0 ~ 100 % (min 0)
            listLayout.addView(createCompactSlider("💨 空气阻力摩擦", curFriction, "%", 0, 100, 0, (val) -> {
                PetRegistry.setIntPref(context, PetRegistry.KEY_FRICTION, val);
                if (onChangeCallback != null) onChangeCallback.run();
            }));

            // 6. 甩动灵敏阈值: 100 ~ 2000 px/s (min 100)
            listLayout.addView(createCompactSlider("⚡ 甩动起飞灵敏度", curFlingThresh, "px/s", 100, 2000, 100, (val) -> {
                PetRegistry.setIntPref(context, PetRegistry.KEY_FLING_THRESHOLD, val);
                if (onChangeCallback != null) onChangeCallback.run();
            }));

            // 7. 停稳速度阈值: 10 ~ 200 px/s (min 10)
            listLayout.addView(createCompactSlider("🛑 停稳减速阈值", curStopSpeed, "px/s", 10, 200, 10, (val) -> {
                PetRegistry.setIntPref(context, PetRegistry.KEY_STOP_SPEED, val);
                if (onChangeCallback != null) onChangeCallback.run();
            }));

            // 8. 监控卡片宽度: 140 ~ 300 dp (min 140)
            listLayout.addView(createCompactSlider("📊 监控卡片宽度", curCardWidth, "dp", 140, 300, 140, (val) -> {
                PetRegistry.setIntPref(context, PetRegistry.KEY_CARD_WIDTH, val);
                if (service != null) service.applyCardWidth(val);
                if (onChangeCallback != null) onChangeCallback.run();
            }));

            // 9. 监控卡缩放比例: 40 ~ 120 % (min 40)
            listLayout.addView(createCompactSlider("🔍 监控卡缩放比例", curCardScale, "%", 40, 120, 40, (val) -> {
                PetRegistry.setIntPref(context, PetRegistry.KEY_CARD_SCALE, val);
                if (service != null) service.applyCardScale(val);
                if (onChangeCallback != null) onChangeCallback.run();
            }));

            // 10. 长按菜单宽度: 120 ~ 240 dp (min 120)
            listLayout.addView(createCompactSlider("📑 长按菜单面板宽", curMenuWidth, "dp", 120, 240, 120, (val) -> {
                PetRegistry.setIntPref(context, PetRegistry.KEY_MENU_WIDTH, val);
                if (onChangeCallback != null) onChangeCallback.run();
            }));

            scroll.addView(listLayout, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            int maxScrollH = (int) (context.getResources().getDisplayMetrics().heightPixels * 0.55f);
            root.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, maxScrollH));

            // ---- 底部重置与完成按钮 ----
            LinearLayout btnRow = new LinearLayout(context);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            btnRow.setPadding(0, dp(6), 0, 0);

            Button resetBtn = buildMiniBtn("↺ 重置默认", 0x22475569, 0x44475569, 0xFF94A3B8, v -> {
                PetRegistry.setIntPref(context, PetRegistry.KEY_PET_SIZE, PetRegistry.DEFAULT_PET_SIZE);
                PetRegistry.setIntPref(context, PetRegistry.KEY_BUBBLE_WIDTH, PetRegistry.DEFAULT_BUBBLE_WIDTH);
                PetRegistry.setIntPref(context, PetRegistry.KEY_BUBBLE_TEXT_SIZE, PetRegistry.DEFAULT_BUBBLE_TEXT_SIZE);
                PetRegistry.setIntPref(context, PetRegistry.KEY_BOUNCE, PetRegistry.DEFAULT_BOUNCE);
                PetRegistry.setIntPref(context, PetRegistry.KEY_FRICTION, PetRegistry.DEFAULT_FRICTION);
                PetRegistry.setIntPref(context, PetRegistry.KEY_FLING_THRESHOLD, PetRegistry.DEFAULT_FLING_THRESHOLD);
                PetRegistry.setIntPref(context, PetRegistry.KEY_STOP_SPEED, PetRegistry.DEFAULT_STOP_SPEED);
                PetRegistry.setIntPref(context, PetRegistry.KEY_CARD_WIDTH, PetRegistry.DEFAULT_CARD_WIDTH);
                PetRegistry.setIntPref(context, PetRegistry.KEY_CARD_SCALE, PetRegistry.DEFAULT_CARD_SCALE);
                PetRegistry.setIntPref(context, PetRegistry.KEY_MENU_WIDTH, PetRegistry.DEFAULT_MENU_WIDTH);

                if (service != null) {
                    service.applyPetSize(PetRegistry.DEFAULT_PET_SIZE);
                    service.applyBubbleDimensions();
                    service.applyCardWidth(PetRegistry.DEFAULT_CARD_WIDTH);
                    service.applyCardScale(PetRegistry.DEFAULT_CARD_SCALE);
                }
                dismiss();
                if (onChangeCallback != null) onChangeCallback.run();
            });
            btnRow.addView(resetBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            View spacer = new View(context);
            btnRow.addView(spacer, new LinearLayout.LayoutParams(dp(8), 1));

            Button backBtn = buildMiniBtn("✔ 完成退出", 0xFF2563EB, 0xFF1D4ED8, 0xFFFFFFFF, v -> {
                dismiss();
                if (onChangeCallback != null) onChangeCallback.run();
            });
            btnRow.addView(backBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f));

            root.addView(btnRow);

            builder.setView(root);
            dialog = builder.create();

            Window window = dialog.getWindow();
            if (window != null) {
                if (service != null || !(context instanceof android.app.Activity)) {
                    window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                }
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xF6181A24);
                bg.setCornerRadius(dp(14));
                bg.setStroke(dp(1), 0x3360A5FA);
                window.setBackgroundDrawable(bg);
            }

            dialog.show();

            // 定位在桌宠旁 (如果是在 OverlayService 悬浮模式下，绝不遮挡桌宠)
            PetOverlayService effectiveService = (service != null) ? service : PetOverlayService.getInstance();
            if (window != null && effectiveService != null && effectiveService.getOverlayRoot() != null) {
                WindowManager.LayoutParams attrs = window.getAttributes();
                if (attrs != null) {
                    attrs.gravity = Gravity.TOP | Gravity.START;
                    int winW = dp(Math.max(170, curMenuWidth + 24));
                    attrs.width = winW;
                    attrs.height = WindowManager.LayoutParams.WRAP_CONTENT;

                    int[] pos = effectiveService.getPetScreenPosition();
                    int petX = pos[0];
                    int petY = pos[1];
                    int petW = pos[2];
                    int screenW = context.getResources().getDisplayMetrics().widthPixels;
                    int screenH = context.getResources().getDisplayMetrics().heightPixels;

                    if (petX + petW / 2 > screenW / 2) {
                        attrs.x = Math.max(dp(8), petX - winW - dp(6));
                    } else {
                        attrs.x = Math.min(screenW - winW - dp(8), petX + petW + dp(6));
                    }
                    attrs.y = Math.max(dp(30), Math.min(screenH - dp(320), petY - dp(10)));
                    window.setAttributes(attrs);
                }
            }

        } catch (Throwable t) {
            android.util.Log.w("DevPetM.PetParams", "show params dialog failed", t);
        }
    }

    private interface OnSliderChange {
        void onChange(int val);
    }

    private View createCompactSlider(String name, int curVal, String unit,
                                     int minVal, int maxVal, int offset, OnSliderChange callback) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));

        TextView label = new TextView(context);
        label.setText(name + " · " + curVal + unit);
        label.setTextColor(0xFFCBD5E1);
        label.setTextSize(10f);
        label.setPadding(0, 0, 0, 0);
        row.addView(label);

        SeekBar sb = new SeekBar(context);
        sb.setMax(maxVal - offset);
        sb.setProgress(Math.max(0, curVal - offset));
        sb.setPadding(dp(4), dp(2), dp(4), dp(2));

        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    int finalVal = progress + offset;
                    label.setText(name + " · " + finalVal + unit);
                    if (callback != null) callback.onChange(finalVal);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        row.addView(sb);
        return row;
    }

    private Button buildMiniBtn(String text, int bgNormal, int bgPressed, int textColor, View.OnClickListener clk) {
        Button btn = new Button(context);
        btn.setText(text);
        btn.setTextSize(10.5f);
        btn.setTextColor(textColor);
        btn.setPadding(dp(6), dp(4), dp(6), dp(4));
        btn.setMinHeight(dp(28));
        btn.setMinimumHeight(dp(28));
        btn.setMinWidth(0);
        btn.setMinimumWidth(0);

        GradientDrawable n = new GradientDrawable();
        n.setColor(bgNormal);
        n.setCornerRadius(dp(8));

        GradientDrawable p = new GradientDrawable();
        p.setColor(bgPressed);
        p.setCornerRadius(dp(8));

        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed}, p);
        sld.addState(new int[]{}, n);

        btn.setBackground(sld);
        btn.setOnClickListener(clk);
        return btn;
    }

    public void dismiss() {
        if (dialog != null) {
            try { dialog.dismiss(); } catch (Throwable ignored) {}
            dialog = null;
        }
    }
}
