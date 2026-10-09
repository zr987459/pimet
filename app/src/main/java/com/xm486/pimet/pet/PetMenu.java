package com.xm486.pimet.pet;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.xm486.pimet.MainActivity;

import java.util.List;

public class PetMenu {

    private final Context context;
    private final PetOverlayService service;
    private final MainActivity activity;
    private Dialog dialog;

    private TextView petNameTv;
    private List<PetRegistry.PetInfo> allPets;
    private int currentPetIndex = 0;

    public PetMenu(PetOverlayService service) {
        this.context = service;
        this.service = service;
        this.activity = null;
    }

    public PetMenu(MainActivity activity) {
        this.context = activity;
        this.service = null;
        this.activity = activity;
    }

    public int dp(int v) {
        return (int) (v * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    public void show() {
        if (dialog != null && dialog.isShowing()) return;

        try {
            dialog = new Dialog(context);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(12), dp(10), dp(12), dp(12));

            GradientDrawable rootBg = new GradientDrawable();
            rootBg.setColor(0xF4181A22);
            rootBg.setCornerRadius(dp(14));
            rootBg.setStroke(dp(1), 0x33475569);
            root.setBackground(rootBg);

            // ---- 顶栏：标题 + ✕ 关闭按钮 ----
            LinearLayout topBar = new LinearLayout(context);
            topBar.setOrientation(LinearLayout.HORIZONTAL);
            topBar.setGravity(Gravity.CENTER_VERTICAL);

            TextView title = new TextView(context);
            title.setText("🐾 桌宠控制");
            title.setTextColor(0xFFF1F5F9);
            title.setTextSize(12.5f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            topBar.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView closeBtn = new TextView(context);
            closeBtn.setText("✕");
            closeBtn.setTextColor(0xFF94A3B8);
            closeBtn.setTextSize(13f);
            closeBtn.setPadding(dp(6), dp(2), dp(4), dp(2));
            closeBtn.setOnClickListener(v -> dismiss());
            topBar.addView(closeBtn);
            root.addView(topBar);

            // 分割线
            View div1 = new View(context);
            div1.setBackgroundColor(0x22475569);
            LinearLayout.LayoutParams divLp1 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
            divLp1.topMargin = dp(6);
            divLp1.bottomMargin = dp(8);
            root.addView(div1, divLp1);

            // ---- 角色切换 ----
            allPets = PetRegistry.getAllPets(context);
            String curPetDir = PetRegistry.getPetDir(context);
            for (int i = 0; i < allPets.size(); i++) {
                if (allPets.get(i).dir.equals(curPetDir)) {
                    currentPetIndex = i;
                    break;
                }
            }

            LinearLayout petSwitchRow = new LinearLayout(context);
            petSwitchRow.setOrientation(LinearLayout.HORIZONTAL);
            petSwitchRow.setGravity(Gravity.CENTER_VERTICAL);

            Button prevPetBtn = buildMiniBtn("◀", 0x22334155, 0x44475569, 0xFF94A3B8, v -> stepPet(-1));
            prevPetBtn.setPadding(dp(8), dp(2), dp(8), dp(2));

            petNameTv = new TextView(context);
            updatePetNameLabel();
            petNameTv.setTextColor(0xFFE2E8F0);
            petNameTv.setTextSize(11f);
            petNameTv.setGravity(Gravity.CENTER);
            petNameTv.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            petNameTv.setLayoutParams(nameLp);

            Button nextPetBtn = buildMiniBtn("▶", 0x22334155, 0x44475569, 0xFF94A3B8, v -> stepPet(1));
            nextPetBtn.setPadding(dp(8), dp(2), dp(8), dp(2));

            petSwitchRow.addView(prevPetBtn);
            petSwitchRow.addView(petNameTv);
            petSwitchRow.addView(nextPetBtn);
            root.addView(petSwitchRow);

            // ---- 尺寸滑块 ----
            int curPetSize = PetRegistry.getIntPref(context, PetRegistry.KEY_PET_SIZE, PetRegistry.DEFAULT_PET_SIZE);
            TextView sizeLabel = new TextView(context);
            sizeLabel.setText("📏 尺寸: " + curPetSize + " dp");
            sizeLabel.setTextColor(0xFF94A3B8);
            sizeLabel.setTextSize(10f);
            LinearLayout.LayoutParams sizeLabelLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sizeLabelLp.topMargin = dp(8);
            root.addView(sizeLabel, sizeLabelLp);

            SeekBar sizeBar = new SeekBar(context);
            sizeBar.setMax(128); // 32 ~ 160
            sizeBar.setProgress(Math.max(0, Math.min(128, curPetSize - 32)));
            sizeBar.setPadding(dp(4), dp(2), dp(4), dp(2));
            sizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        int size = progress + 32;
                        sizeLabel.setText("📏 尺寸: " + size + " dp");
                        PetRegistry.setIntPref(context, PetRegistry.KEY_PET_SIZE, size);
                        if (service != null) {
                            service.updatePetSize(size);
                        }
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            root.addView(sizeBar);

            // 分割线
            View div2 = new View(context);
            div2.setBackgroundColor(0x22475569);
            LinearLayout.LayoutParams divLp2 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
            divLp2.topMargin = dp(6);
            divLp2.bottomMargin = dp(8);
            root.addView(div2, divLp2);

            // ---- 底部操作功能键行 1: [💬 快速聊天] [🌐 工作台] ----
            LinearLayout actionRow1 = new LinearLayout(context);
            actionRow1.setOrientation(LinearLayout.HORIZONTAL);
            actionRow1.setGravity(Gravity.CENTER_VERTICAL);

            Button chatBtn = buildMiniBtn("💬 快速聊天", 0x2210B981, 0x4410B981, 0xFFA7F3D0, v -> {
                dismiss();
                openFullChat();
            });
            actionRow1.addView(chatBtn, new LinearLayout.LayoutParams(0, dp(30), 1f));
            actionRow1.addView(createSpacingView(4));

            Button webBtn = buildMiniBtn("🌐 工作台", 0x223B82F6, 0x443B82F6, 0xFF93C5FD, v -> {
                dismiss();
                if (activity != null) {
                    activity.switchTab(1);
                } else {
                    Intent intent = new Intent(context, MainActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    intent.putExtra("pimet.open_web", true);
                    context.startActivity(intent);
                }
            });
            actionRow1.addView(webBtn, new LinearLayout.LayoutParams(0, dp(30), 1f));
            root.addView(actionRow1);

            // ---- 底部操作功能键行 2: [🎛️ 参数] [⚙️ 设置] [🔴 关闭] ----
            LinearLayout actionRow2 = new LinearLayout(context);
            actionRow2.setOrientation(LinearLayout.HORIZONTAL);
            actionRow2.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams ar2Lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            ar2Lp.topMargin = dp(5);

            Button paramsBtn = buildMiniBtn("🎛️ 参数", 0x228B5CF6, 0x448B5CF6, 0xFFC4B5FD, v -> {
                dismiss();
                if (service != null) {
                    new PetParamsDialog(service).show();
                } else {
                    new PetParamsDialog(context, () -> {
                        if (activity != null) activity.applyPetParams();
                    }).show();
                }
            });
            actionRow2.addView(paramsBtn, new LinearLayout.LayoutParams(0, dp(28), 1f));
            actionRow2.addView(createSpacingView(3));

            Button setBtn = buildMiniBtn("⚙️ 设置", 0x22F59E0B, 0x44F59E0B, 0xFFFCD34D, v -> {
                dismiss();
                if (activity != null) {
                    activity.switchTab(3);
                } else {
                    Intent intent = new Intent(context, MainActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    intent.putExtra("devpetm.open_ai_tab", true);
                    context.startActivity(intent);
                }
            });
            actionRow2.addView(setBtn, new LinearLayout.LayoutParams(0, dp(28), 1f));
            actionRow2.addView(createSpacingView(3));

            Button stopBtn = buildMiniBtn("🔴 关闭", 0x22EF4444, 0x44EF4444, 0xFFFCA5A5, v -> {
                dismiss();
                if (service != null) {
                    service.stopSelf();
                }
                if (activity != null) {
                    activity.updatePetDisplay(false);
                }
            });
            actionRow2.addView(stopBtn, new LinearLayout.LayoutParams(0, dp(28), 1f));
            root.addView(actionRow2, ar2Lp);

            dialog.setContentView(root);

            Window window = dialog.getWindow();
            if (window != null) {
                if (service != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                    } else {
                        window.setType(WindowManager.LayoutParams.TYPE_PHONE);
                    }
                }
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }

            dialog.show();

            if (window != null) {
                WindowManager.LayoutParams lp = window.getAttributes();
                lp.width = dp(230);
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
                lp.gravity = Gravity.CENTER;
                window.setAttributes(lp);
            }

        } catch (Throwable t) {
            Toast.makeText(context, "打开菜单失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void openFullChat() {
        if (activity != null) {
            activity.togglePetChatCard(true);
        } else {
            Intent intent = new Intent(context, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra("pimet.open_pet_chat", true);
            context.startActivity(intent);
            if (service != null) {
                service.toggleCard();
            }
        }
    }

    private void stepPet(int delta) {
        if (allPets == null || allPets.isEmpty()) return;
        currentPetIndex = (currentPetIndex + delta + allPets.size()) % allPets.size();
        PetRegistry.PetInfo nextPet = allPets.get(currentPetIndex);
        PetRegistry.setPetDir(context, nextPet.dir);
        updatePetNameLabel();

        if (service != null) {
            service.changePet(nextPet.dir);
        }
        if (activity != null) {
            if (activity.floatingPetView != null) {
                activity.floatingPetView.setPetDir(nextPet.dir);
                activity.floatingPetView.playOneShot("waving");
            }
            activity.showPetBubble("✨ 切换角色为: " + nextPet.displayName);
        }
    }

    private void updatePetNameLabel() {
        if (petNameTv == null || allPets == null || allPets.isEmpty()) return;
        petNameTv.setText(allPets.get(currentPetIndex).displayName);
    }

    private View createSpacingView(int dpWidth) {
        View v = new View(context);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(dpWidth), 1));
        return v;
    }

    private Button buildMiniBtn(String text, int normalColor, int pressedColor, int textColor, View.OnClickListener listener) {
        Button btn = new Button(context);
        btn.setText(text);
        btn.setTextSize(11f);
        btn.setTextColor(textColor);
        btn.setPadding(dp(4), dp(2), dp(4), dp(2));
        btn.setMinHeight(0);
        btn.setMinWidth(0);
        btn.setGravity(Gravity.CENTER);
        btn.setTypeface(Typeface.DEFAULT_BOLD);

        GradientDrawable normal = new GradientDrawable();
        normal.setColor(normalColor);
        normal.setCornerRadius(dp(8));
        normal.setStroke(dp(1), pressedColor);

        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(pressedColor);
        pressed.setCornerRadius(dp(8));

        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sld.addState(new int[]{}, normal);

        btn.setBackground(sld);
        btn.setOnClickListener(listener);
        return btn;
    }

    public void dismiss() {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
            dialog = null;
        }
    }
}
