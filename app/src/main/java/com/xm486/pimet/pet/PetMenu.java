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
import com.xm486.pimet.PiWebActivity;

import java.util.ArrayList;
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
    private final List<TextView> targetButtons = new ArrayList<>();

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

            // ---- 监控目标标签行 (点按一键切换监控AI · 长按直达Web界面) ----
            TextView targetHeader = new TextView(context);
            targetHeader.setText("🎯 监控AI (点按切换 · 长按进入)");
            targetHeader.setTextColor(0xFF94A3B8);
            targetHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
            LinearLayout.LayoutParams thLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            thLp.bottomMargin = dp(3);
            root.addView(targetHeader, thLp);

            targetButtons.clear();

            LinearLayout targetRow1 = new LinearLayout(context);
            targetRow1.setOrientation(LinearLayout.HORIZONTAL);
            targetRow1.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams tr1Lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(22));

            TextView btnPiWeb = createTargetButton("pi-web", PetRegistry.TARGET_PIWEB, v -> {
                switchTarget(PetRegistry.TARGET_PIWEB);
            }, v -> {
                openConsole(PetRegistry.TARGET_PIWEB);
                return true;
            });
            TextView btnOperit = createTargetButton("Operit", PetRegistry.TARGET_OPERIT, v -> {
                switchTarget(PetRegistry.TARGET_OPERIT);
            }, v -> {
                openConsole(PetRegistry.TARGET_OPERIT);
                return true;
            });

            targetRow1.addView(btnPiWeb, new LinearLayout.LayoutParams(0, dp(22), 1f));
            targetRow1.addView(new View(context), new LinearLayout.LayoutParams(dp(3), 1));
            targetRow1.addView(btnOperit, new LinearLayout.LayoutParams(0, dp(22), 1f));
            root.addView(targetRow1, tr1Lp);

            LinearLayout targetRow2 = new LinearLayout(context);
            targetRow2.setOrientation(LinearLayout.HORIZONTAL);
            targetRow2.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams tr2Lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(22));
            tr2Lp.topMargin = dp(3);

            TextView btnClaw = createTargetButton("ClawBench", PetRegistry.TARGET_CLAWBENCH, v -> {
                switchTarget(PetRegistry.TARGET_CLAWBENCH);
            }, v -> {
                openConsole(PetRegistry.TARGET_CLAWBENCH);
                return true;
            });
            TextView btnRikka = createTargetButton("RikkaHub", PetRegistry.TARGET_RIKKA, v -> {
                switchTarget(PetRegistry.TARGET_RIKKA);
            }, v -> {
                openConsole(PetRegistry.TARGET_RIKKA);
                return true;
            });

            targetRow2.addView(btnClaw, new LinearLayout.LayoutParams(0, dp(22), 1f));
            targetRow2.addView(new View(context), new LinearLayout.LayoutParams(dp(3), 1));
            targetRow2.addView(btnRikka, new LinearLayout.LayoutParams(0, dp(22), 1f));
            root.addView(targetRow2, tr2Lp);

            targetButtons.add(btnPiWeb);
            targetButtons.add(btnOperit);
            targetButtons.add(btnClaw);
            targetButtons.add(btnRikka);

            String activeTarget = PetRegistry.getStringPref(context, PetRegistry.KEY_MONITOR_TARGET, PetRegistry.TARGET_PIWEB);
            refreshTargetButtonStyles(activeTarget);

            // 分割线 (监控与形象之间)
            View divTarget = new View(context);
            divTarget.setBackgroundColor(0x22475569);
            LinearLayout.LayoutParams divLpTarget = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
            divLpTarget.topMargin = dp(5);
            divLpTarget.bottomMargin = dp(5);
            root.addView(divTarget, divLpTarget);

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

            // ---- 底部操作功能键行 1: [💬 快捷聊天(直接打开对应网页端)] [🌐 工作台] ----
            LinearLayout actionRow1 = new LinearLayout(context);
            actionRow1.setOrientation(LinearLayout.HORIZONTAL);
            actionRow1.setGravity(Gravity.CENTER_VERTICAL);

            TextView chatBtn = buildCompactActionBtn("💬 快捷聊天", 0x2210B981, 0x5510B981, 0xFFA7F3D0, v -> {
                dismiss();
                String target = PetRegistry.getStringPref(context, PetRegistry.KEY_MONITOR_TARGET, PetRegistry.TARGET_PIWEB);
                openConsole(target);
            });
            actionRow1.addView(chatBtn, new LinearLayout.LayoutParams(0, dp(23), 1f));

            View space1 = new View(context);
            actionRow1.addView(space1, new LinearLayout.LayoutParams(dp(3), 1));

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
            actionRow1.addView(webBtn, new LinearLayout.LayoutParams(0, dp(23), 1f));
            root.addView(actionRow1);

            // ---- 底部操作功能键行 2: [⚙️ 设置] [🔴 关闭] ----
            LinearLayout actionRow2 = new LinearLayout(context);
            actionRow2.setOrientation(LinearLayout.HORIZONTAL);
            actionRow2.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams ar2Lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(23));
            ar2Lp.topMargin = dp(3);

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

    private void switchTarget(String targetKey) {
        PetRegistry.setStringPref(context, PetRegistry.KEY_MONITOR_TARGET, targetKey);
        try {
            ChatConfig cfg = ChatConfig.load(context);
            if (PetRegistry.TARGET_PIWEB.equals(targetKey)) {
                cfg.mode = ChatConfig.MODE_PIWEB;
            } else if (PetRegistry.TARGET_OPERIT.equals(targetKey)) {
                cfg.mode = ChatConfig.MODE_OPERIT;
            } else if (PetRegistry.TARGET_CLAWBENCH.equals(targetKey)) {
                cfg.mode = ChatConfig.MODE_CLAWBENCH;
            }
            cfg.save(context);
        } catch (Throwable ignored) {}

        if (service != null) {
            service.switchMonitorTarget(targetKey);
        } else {
            PetOverlayService s = PetOverlayService.getInstance();
            if (s != null) {
                s.switchMonitorTarget(targetKey);
            }
        }

        if (activity != null) {
            activity.initPetMonitor();
            activity.showPetBubble("🎯 监控已切换至: " + getTargetLabel(targetKey));
        }

        refreshTargetButtonStyles(targetKey);
        Toast.makeText(context, "🎯 监控已切换至: " + getTargetLabel(targetKey), Toast.LENGTH_SHORT).show();
    }

    private void openConsole(String targetKey) {
        dismiss();
        if (service != null) {
            service.openConsoleForTarget(targetKey);
            return;
        }
        PetOverlayService s = PetOverlayService.getInstance();
        if (s != null) {
            s.openConsoleForTarget(targetKey);
            return;
        }
        if (activity != null) {
            if (PetRegistry.TARGET_PIWEB.equals(targetKey)) {
                activity.switchTab(1);
                return;
            }
        }
        int port;
        if (PetRegistry.TARGET_CLAWBENCH.equals(targetKey)) {
            port = PetRegistry.getClawbenchPort(context);
        } else if (PetRegistry.TARGET_PIWEB.equals(targetKey)) {
            port = PetRegistry.getPiWebPort(context);
        } else if (PetRegistry.TARGET_RIKKA.equals(targetKey)) {
            port = PetRegistry.getIntPref(context, PetRegistry.KEY_RK_PORT, PetRegistry.DEFAULT_RK_PORT);
        } else if (PetRegistry.TARGET_OPERIT.equals(targetKey)) {
            port = PetRegistry.getOperitPort(context);
        } else {
            Intent intent = new Intent(context, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra("devpetm.open_ai_tab", true);
            context.startActivity(intent);
            return;
        }

        try {
            Intent intent = new Intent(context, PiWebActivity.class);
            intent.putExtra(PiWebActivity.EXTRA_PORT, port);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable t) {
            try {
                Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:" + port));
                browser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(browser);
            } catch (Throwable ignored) {}
        }
    }

    private TextView createTargetButton(String label, String targetKey, View.OnClickListener clk, View.OnLongClickListener lclk) {
        TextView btn = new TextView(context);
        btn.setText(label);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(4), dp(2), dp(4), dp(2));
        btn.setTag(targetKey);
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setSingleLine(true);
        btn.setEllipsize(TextUtils.TruncateAt.END);
        btn.setIncludeFontPadding(false);
        btn.setOnClickListener(clk);
        if (lclk != null) {
            btn.setOnLongClickListener(lclk);
        }
        return btn;
    }

    private void refreshTargetButtonStyles(String activeTarget) {
        for (TextView btn : targetButtons) {
            String key = (String) btn.getTag();
            boolean isActive = key != null && key.equals(activeTarget);

            GradientDrawable normal = new GradientDrawable();
            normal.setCornerRadius(dp(6));
            if (isActive) {
                normal.setColor(0x3310B981);
                normal.setStroke(dp(1), 0xFF10B981);
                btn.setTextColor(0xFF34D399);
                btn.setTypeface(Typeface.DEFAULT_BOLD);
                btn.setText("● " + getTargetLabel(key));
            } else {
                normal.setColor(0x221E293B);
                normal.setStroke(dp(1), 0x22475569);
                btn.setTextColor(0xFF94A3B8);
                btn.setTypeface(Typeface.DEFAULT);
                btn.setText("○ " + getTargetLabel(key));
            }

            GradientDrawable pressed = new GradientDrawable();
            pressed.setCornerRadius(dp(6));
            pressed.setColor(isActive ? 0x6610B981 : 0x55334155);
            pressed.setStroke(dp(1), isActive ? 0xFF34D399 : 0x6694A3B8);

            StateListDrawable sld = new StateListDrawable();
            sld.addState(new int[]{android.R.attr.state_pressed}, pressed);
            sld.addState(new int[]{}, normal);

            btn.setBackground(sld);
        }
    }

    private String getTargetLabel(String targetKey) {
        if (PetRegistry.TARGET_PIWEB.equals(targetKey)) return "pi-web";
        if (PetRegistry.TARGET_OPERIT.equals(targetKey)) return "Operit";
        if (PetRegistry.TARGET_CLAWBENCH.equals(targetKey)) return "ClawBench";
        if (PetRegistry.TARGET_RIKKA.equals(targetKey)) return "RikkaHub";
        return targetKey != null ? targetKey : "";
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
