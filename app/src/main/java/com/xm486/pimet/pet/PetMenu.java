package com.xm486.pimet.pet;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.xm486.pimet.MainActivity;

import java.util.List;

/**
 * 桌面宠物长按菜单：紧凑 HUD 控制卡片，支持实时缩放调节、宠物切换与核心快捷操作。
 */
public class PetMenu {

    private final Context context;
    private final PetOverlayService service;
    private final MainActivity activity;
    private Dialog dialog;

    private TextView petNameTv;
    private List<PetRegistry.PetInfo> allPets;
    private int currentPetIndex = 0;

    public PetMenu(Context context) {
        this(context, PetOverlayService.getInstance());
    }

    public PetMenu(Context context, PetOverlayService service) {
        this.context = context;
        this.service = service != null ? service : PetOverlayService.getInstance();
        this.activity = context instanceof MainActivity ? (MainActivity) context : null;
    }

    public PetMenu(PetOverlayService service) {
        this((Context) service, service);
    }

    public PetMenu(MainActivity activity) {
        this((Context) activity, PetOverlayService.getInstance());
    }

    public int dp(int v) {
        return (int) (v * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    public void show() {
        if (dialog != null && dialog.isShowing()) return;

        try {
            dialog = new Dialog(context);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            // 读取已保存的菜单宽度（默认紧凑 150dp，可在 120~240dp 间任意调节）
            int savedMenuWidth = PetRegistry.getIntPref(context, PetRegistry.KEY_MENU_WIDTH,
                    PetRegistry.getIntPref(context, "pref_menu_width", PetRegistry.DEFAULT_MENU_WIDTH));
            if (savedMenuWidth < 120 || savedMenuWidth > 260) {
                savedMenuWidth = 150;
            }
            final int initialMenuWidth = savedMenuWidth;

            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(9), dp(7), dp(9), dp(8));

            GradientDrawable rootBg = new GradientDrawable();
            rootBg.setColor(0xF6131722);
            rootBg.setCornerRadius(dp(12));
            rootBg.setStroke(dp(1), 0x44475569);
            root.setBackground(rootBg);

            // ---- 顶栏：标题 + ✕ 关闭按钮 ----
            LinearLayout topBar = new LinearLayout(context);
            topBar.setOrientation(LinearLayout.HORIZONTAL);
            topBar.setGravity(Gravity.CENTER_VERTICAL);

            TextView title = new TextView(context);
            title.setText("🐾 桌宠控制");
            title.setTextColor(0xFFF1F5F9);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            topBar.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView closeBtn = new TextView(context);
            closeBtn.setText("✕");
            closeBtn.setTextColor(0xFF94A3B8);
            closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            closeBtn.setPadding(dp(6), dp(1), dp(3), dp(1));
            closeBtn.setOnClickListener(v -> dismiss());
            topBar.addView(closeBtn);
            root.addView(topBar);

            // 分割线 1
            View div1 = new View(context);
            div1.setBackgroundColor(0x22475569);
            LinearLayout.LayoutParams divLp1 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
            divLp1.topMargin = dp(4);
            divLp1.bottomMargin = dp(5);
            root.addView(div1, divLp1);

            // ---- 角色切换横排：[◀] [角色名] [▶] ----
            allPets = PetRegistry.loadPets(context);
            String curPetDir = PetRegistry.getPetDir(context);
            if (allPets != null) {
                for (int i = 0; i < allPets.size(); i++) {
                    if (allPets.get(i).dir.equals(curPetDir)) {
                        currentPetIndex = i;
                        break;
                    }
                }
            }

            LinearLayout petSwitchRow = new LinearLayout(context);
            petSwitchRow.setOrientation(LinearLayout.HORIZONTAL);
            petSwitchRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView prevPetBtn = buildCompactActionBtn("◀", 0x33334155, 0x66475569, 0xFFCBD5E1, v -> stepPet(-1));
            LinearLayout.LayoutParams prevLp = new LinearLayout.LayoutParams(dp(24), dp(22));
            prevPetBtn.setLayoutParams(prevLp);

            petNameTv = new TextView(context);
            updatePetNameLabel();
            petNameTv.setTextColor(0xFFE2E8F0);
            petNameTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            petNameTv.setGravity(Gravity.CENTER);
            petNameTv.setMaxLines(1);
            petNameTv.setEllipsize(TextUtils.TruncateAt.END);
            petNameTv.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, dp(22), 1f);
            nameLp.leftMargin = dp(4);
            nameLp.rightMargin = dp(4);
            petNameTv.setLayoutParams(nameLp);

            TextView nextPetBtn = buildCompactActionBtn("▶", 0x33334155, 0x66475569, 0xFFCBD5E1, v -> stepPet(1));
            LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(dp(24), dp(22));
            nextPetBtn.setLayoutParams(nextLp);

            petSwitchRow.addView(prevPetBtn);
            petSwitchRow.addView(petNameTv);
            petSwitchRow.addView(nextPetBtn);
            root.addView(petSwitchRow);

            // ---- 尺寸滑块 1: 桌宠大小 (32 ~ 140 dp) ----
            int curPetSize = PetRegistry.getIntPref(context, PetRegistry.KEY_PET_SIZE, PetRegistry.DEFAULT_PET_SIZE);
            curPetSize = Math.max(32, Math.min(140, curPetSize));
            TextView petSizeLabel = new TextView(context);
            petSizeLabel.setText("📏 桌宠大小: " + curPetSize + " dp");
            petSizeLabel.setTextColor(0xFF94A3B8);
            petSizeLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
            LinearLayout.LayoutParams pslLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            pslLp.topMargin = dp(5);
            root.addView(petSizeLabel, pslLp);

            SeekBar petSizeBar = new SeekBar(context);
            petSizeBar.setMax(108); // 32 ~ 140
            petSizeBar.setProgress(curPetSize - 32);
            petSizeBar.setPadding(dp(2), dp(1), dp(2), dp(1));
            petSizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        int size = progress + 32;
                        petSizeLabel.setText("📏 桌宠大小: " + size + " dp");
                        PetRegistry.setIntPref(context, PetRegistry.KEY_PET_SIZE, size);
                        if (service != null) {
                            service.applyPetSize(size);
                        }
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            root.addView(petSizeBar);

            // ---- 尺寸滑块 2: 菜单面板大小 (120 ~ 240 dp) ----
            TextView menuWidthLabel = new TextView(context);
            menuWidthLabel.setText("📐 菜单宽度: " + initialMenuWidth + " dp");
            menuWidthLabel.setTextColor(0xFF94A3B8);
            menuWidthLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
            LinearLayout.LayoutParams mwlLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            mwlLp.topMargin = dp(3);
            root.addView(menuWidthLabel, mwlLp);

            SeekBar menuWidthBar = new SeekBar(context);
            menuWidthBar.setMax(120); // 120 ~ 240
            menuWidthBar.setProgress(initialMenuWidth - 120);
            menuWidthBar.setPadding(dp(2), dp(1), dp(2), dp(1));
            menuWidthBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        int width = progress + 120;
                        menuWidthLabel.setText("📐 菜单宽度: " + width + " dp");
                        PetRegistry.setIntPref(context, PetRegistry.KEY_MENU_WIDTH, width);
                        PetRegistry.setIntPref(context, "pref_menu_width", width);
                        if (dialog != null && dialog.getWindow() != null) {
                            WindowManager.LayoutParams wlp = dialog.getWindow().getAttributes();
                            wlp.width = dp(width);
                            dialog.getWindow().setAttributes(wlp);
                        }
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            root.addView(menuWidthBar);

            // 分割线 2
            View div2 = new View(context);
            div2.setBackgroundColor(0x22475569);
            LinearLayout.LayoutParams divLp2 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
            divLp2.topMargin = dp(4);
            divLp2.bottomMargin = dp(5);
            root.addView(div2, divLp2);

            // ---- 底部操作功能键行 1: [💬 快速聊天] [🌐 工作台] ----
            LinearLayout actionRow1 = new LinearLayout(context);
            actionRow1.setOrientation(LinearLayout.HORIZONTAL);
            actionRow1.setGravity(Gravity.CENTER_VERTICAL);

            TextView chatBtn = buildCompactActionBtn("💬 快捷聊天", 0x2210B981, 0x5510B981, 0xFFA7F3D0, v -> {
                dismiss();
                openFullChat();
            });
            actionRow1.addView(chatBtn, new LinearLayout.LayoutParams(0, dp(25), 1f));

            View space1 = new View(context);
            actionRow1.addView(space1, new LinearLayout.LayoutParams(dp(4), 1));

            TextView webBtn = buildCompactActionBtn("🌐 工作台", 0x223B82F6, 0x553B82F6, 0xFF93C5FD, v -> {
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
            actionRow1.addView(webBtn, new LinearLayout.LayoutParams(0, dp(25), 1f));
            root.addView(actionRow1);

            // ---- 底部操作功能键行 2: [🎛️ 参数] [⚙️ 设置] [🔴 关闭] ----
            LinearLayout actionRow2 = new LinearLayout(context);
            actionRow2.setOrientation(LinearLayout.HORIZONTAL);
            actionRow2.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams ar2Lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            ar2Lp.topMargin = dp(4);

            TextView paramsBtn = buildCompactActionBtn("🎛️ 参数", 0x228B5CF6, 0x558B5CF6, 0xFFC4B5FD, v -> {
                dismiss();
                if (service != null) {
                    new PetParamsDialog(service).show();
                } else {
                    new PetParamsDialog(context, () -> {
                        if (activity != null) activity.applyPetParams();
                    }).show();
                }
            });
            actionRow2.addView(paramsBtn, new LinearLayout.LayoutParams(0, dp(23), 1f));

            View space2 = new View(context);
            actionRow2.addView(space2, new LinearLayout.LayoutParams(dp(3), 1));

            TextView setBtn = buildCompactActionBtn("⚙️ 设置", 0x22F59E0B, 0x55F59E0B, 0xFFFCD34D, v -> {
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
            actionRow2.addView(setBtn, new LinearLayout.LayoutParams(0, dp(23), 1f));

            View space3 = new View(context);
            actionRow2.addView(space3, new LinearLayout.LayoutParams(dp(3), 1));

            TextView stopBtn = buildCompactActionBtn("🔴 关闭", 0x22EF4444, 0x55EF4444, 0xFFFCA5A5, v -> {
                dismiss();
                if (service != null) {
                    service.stopSelf();
                }
                if (activity != null) {
                    activity.updatePetDisplay(false);
                }
            });
            actionRow2.addView(stopBtn, new LinearLayout.LayoutParams(0, dp(23), 1f));
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
                lp.width = dp(initialMenuWidth);
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
        PetRegistry.PetInfo chosen = allPets.get(currentPetIndex);
        PetRegistry.setPetDir(context, chosen.dir);
        if (service != null) {
            service.changePet(chosen.dir);
        }
        updatePetNameLabel();
    }

    private void updatePetNameLabel() {
        if (petNameTv == null || allPets == null || allPets.isEmpty()) return;
        petNameTv.setText(allPets.get(currentPetIndex).displayName);
    }

    private TextView buildCompactActionBtn(String text, int normalColor, int strokeColor, int textColor, View.OnClickListener listener) {
        TextView btn = new TextView(context);
        btn.setText(text);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        btn.setTextColor(textColor);
        btn.setGravity(Gravity.CENTER);
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setIncludeFontPadding(false);

        GradientDrawable normal = new GradientDrawable();
        normal.setColor(normalColor);
        normal.setCornerRadius(dp(6));
        normal.setStroke(dp(1), strokeColor);

        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(strokeColor);
        pressed.setCornerRadius(dp(6));

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
