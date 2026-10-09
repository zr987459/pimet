package com.xm486.pimet.pet;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * 桌宠二级设置弹窗：专门用于调节尺寸、气泡、卡片与缩放参数。
 * 从主菜单「🎛️ 参数调节」点击弹出，实时滑动立即生效并持久化。
 */
public class PetParamsDialog {

    private final Context context;
    private final PetOverlayService service;
    private final Runnable onChangeCallback;
    private AlertDialog dialog;

    public PetParamsDialog(PetOverlayService service) {
        this(service, service, null);
    }

    public PetParamsDialog(Context context, Runnable onChangeCallback) {
        this(context, (context instanceof PetOverlayService ? (PetOverlayService) context : null), onChangeCallback);
    }

    public PetParamsDialog(Context context, PetOverlayService service, Runnable onChangeCallback) {
        this.context = context;
        this.service = service;
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
            title.setText("🎛️ 参数调节");
            title.setTextColor(0xFFE2E8F0);
            title.setTextSize(12f);
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
            hint.setText("滑动即生效 · 自动保存记忆");
            hint.setTextColor(0xFF64748B);
            hint.setTextSize(9f);
            hint.setPadding(0, 0, 0, dp(4));
            root.addView(hint);

            // ---- 各项滑块 ----
            int curPetSize = PetRegistry.getIntPref(context, PetRegistry.KEY_PET_SIZE,
                    PetRegistry.DEFAULT_PET_SIZE);
            int curCardWidth = PetRegistry.getIntPref(context, PetRegistry.KEY_CARD_WIDTH,
                    PetRegistry.DEFAULT_CARD_WIDTH);
            int curCardScale = PetRegistry.getIntPref(context, PetRegistry.KEY_CARD_SCALE,
                    PetRegistry.DEFAULT_CARD_SCALE);
            int curBubbleWidth = PetRegistry.getIntPref(context, PetRegistry.KEY_BUBBLE_WIDTH,
                    PetRegistry.DEFAULT_BUBBLE_WIDTH);
            int curMenuWidth = PetRegistry.getIntPref(context, PetRegistry.KEY_MENU_WIDTH,
                    PetRegistry.getIntPref(context, "pref_menu_width", PetRegistry.DEFAULT_MENU_WIDTH));

            int curBounce = PetRegistry.getIntPref(context, PetRegistry.KEY_BOUNCE,
                    PetRegistry.DEFAULT_BOUNCE);
            int curFriction = PetRegistry.getIntPref(context, PetRegistry.KEY_FRICTION,
                    PetRegistry.DEFAULT_FRICTION);
            int curFlingThresh = PetRegistry.getIntPref(context, PetRegistry.KEY_FLING_THRESHOLD,
                    PetRegistry.DEFAULT_FLING_THRESHOLD);
            int curStopSpeed = PetRegistry.getIntPref(context, PetRegistry.KEY_STOP_SPEED,
                    PetRegistry.DEFAULT_STOP_SPEED);

            // 1. 桌宠大小: 32 ~ 140 dp (min 32)
            root.addView(createCompactSlider("桌宠大小", curPetSize, "dp", 32, 140, 32, (val) -> {
                PetRegistry.setIntPref(context, PetRegistry.KEY_PET_SIZE, val);
                if (onChangeCallback != null) onChangeCallback.run();
            }));

            // 2. 气泡宽度: 120 ~ 280 dp (min 120)
            root.addView(createCompactSlider("气泡宽", curBubbleWidth, "dp", 120, 280, 120, (val) -> {
                PetRegistry.setIntPref(context, PetRegistry.KEY_BUBBLE_WIDTH, val);
                if (onChangeCallback != null) onChangeCallback.run();
            }));

            // 3. 反弹保留: 0 ~ 100 % (min 0)
            root.addView(createCompactSlider("反弹保留", curBounce, "%", 0, 100, 0, (val) -> {
                PetRegistry.setIntPref(context, PetRegistry.KEY_BOUNCE, val);
                if (onChangeCallback != null) onChangeCallback.run();
            }));

            // 4. 空气摩擦: 0 ~ 100 % (min 0)
            root.addView(createCompactSlider("空气摩擦", curFriction, "%", 0, 100, 0, (val) -> {
                PetRegistry.setIntPref(context, PetRegistry.KEY_FRICTION, val);
                if (onChangeCallback != null) onChangeCallback.run();
            }));

            // 5. 甩动灵敏度: 100 ~ 2000 px/s (min 100)
            root.addView(createCompactSlider("甩动阈值", curFlingThresh, "px/s", 100, 2000, 100, (val) -> {
                PetRegistry.setIntPref(context, PetRegistry.KEY_FLING_THRESHOLD, val);
                if (onChangeCallback != null) onChangeCallback.run();
            }));

            // 6. 停稳阈值: 10 ~ 200 px/s (min 10)
            root.addView(createCompactSlider("停稳阈值", curStopSpeed, "px/s", 10, 200, 10, (val) -> {
                PetRegistry.setIntPref(context, PetRegistry.KEY_STOP_SPEED, val);
                if (onChangeCallback != null) onChangeCallback.run();
            }));

            // 7. 卡片宽度: 140 ~ 300 dp (min 140)
            root.addView(createCompactSlider("卡片宽", curCardWidth, "dp", 140, 300, 140, (val) -> {
                PetRegistry.setIntPref(context, PetRegistry.KEY_CARD_WIDTH, val);
                if (onChangeCallback != null) onChangeCallback.run();
            }));

            // 8. 缩放紧凑度: 40 ~ 120 % (min 40)
            root.addView(createCompactSlider("缩放比例", curCardScale, "%", 40, 120, 40, (val) -> {
                PetRegistry.setIntPref(context, PetRegistry.KEY_CARD_SCALE, val);
                if (onChangeCallback != null) onChangeCallback.run();
            }));

            // 9. 菜单宽度: 120 ~ 240 dp (min 120)
            root.addView(createCompactSlider("菜单面板宽", curMenuWidth, "dp", 120, 240, 120, (val) -> {
                PetRegistry.setIntPref(context, PetRegistry.KEY_MENU_WIDTH, val);
                PetRegistry.setIntPref(context, "pref_menu_width", val);
                if (onChangeCallback != null) onChangeCallback.run();
            }));

            // ---- 底部返回主菜单按钮 ----
            Button backBtn = buildMiniBtn("← 完成返回", 0x333B82F6, 0x553B82F6, 0xFF93C5FD, v -> {
                dismiss();
                if (service != null) service.showPetMenu();
                if (onChangeCallback != null) onChangeCallback.run();
            });
            LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            backLp.topMargin = dp(6);
            root.addView(backBtn, backLp);

            builder.setView(root);
            dialog = builder.create();

            Window window = dialog.getWindow();
            if (window != null) {
                if (service != null || !(context instanceof android.app.Activity)) {
                    window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                }
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xF4181A22);
                bg.setCornerRadius(dp(14));
                bg.setStroke(dp(1), 0x44475569);
                window.setBackgroundDrawable(bg);
            }

            dialog.show();

            // 定位在桌宠旁 (如果是在 OverlayService 悬浮模式下)
            if (window != null && service != null && service.getOverlayRoot() != null) {
                WindowManager.LayoutParams attrs = window.getAttributes();
                if (attrs != null) {
                    attrs.gravity = Gravity.TOP | Gravity.START;
                    int winW = dp(Math.max(160, curMenuWidth + 20));
                    attrs.width = winW;
                    attrs.height = WindowManager.LayoutParams.WRAP_CONTENT;

                    WindowManager.LayoutParams petLp =
                            (WindowManager.LayoutParams) service.getOverlayRoot().getLayoutParams();
                    int screenW = context.getResources().getDisplayMetrics().widthPixels;
                    int petW = service.getOverlayRoot().getWidth();
                    if (petW <= 0) petW = dp(70);

                    if (petLp.x + petW / 2 > screenW / 2) {
                        attrs.x = Math.max(dp(6), petLp.x - winW - dp(4));
                    } else {
                        attrs.x = Math.min(screenW - winW - dp(6), petLp.x + petW + dp(4));
                    }
                    attrs.y = Math.max(dp(20), petLp.y);
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
        row.setPadding(0, dp(2), 0, dp(2));

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
        btn.setTextSize(10f);
        btn.setTextColor(textColor);
        btn.setPadding(dp(4), dp(4), dp(4), dp(4));
        btn.setMinHeight(dp(26));
        btn.setMinimumHeight(dp(26));
        btn.setMinWidth(0);
        btn.setMinimumWidth(0);

        GradientDrawable n = new GradientDrawable();
        n.setColor(bgNormal);
        n.setCornerRadius(dp(6));

        GradientDrawable p = new GradientDrawable();
        p.setColor(bgPressed);
        p.setCornerRadius(dp(6));

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
