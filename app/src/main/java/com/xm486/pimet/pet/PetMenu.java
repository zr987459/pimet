package com.xm486.pimet.pet;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
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
import com.xm486.pimet.PiMetConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 桌宠一级快捷悬浮控制菜单（外部弹窗风格）：
 * 1. 监控目标一键切换（Operit / pi-web / ClawBench / API / RikkaHub）
 * 2. 对话通道模式切换
 * 3. 角色形象即时切换与绑定
 * 4. 宠物尺寸实时滑块调节
 * 5. 🌐 全局桌面桌宠快捷开关
 * 6. 二级控制：🎛️ 物理参数调节、💬 全屏聊天对话、💻 终端、⚙️ 设置、🔴 退出
 */
public class PetMenu {

    private final Context context;
    private PetOverlayService service;
    private MainActivity activity;

    private AlertDialog dialog;
    private Button chatModeBtn;
    private TextView petHeader;
    private TextView petNameTv;
    private Button bindPetBtn;
    private Button globalSwitchBtn;
    private List<PetRegistry.PetInfo> allPets;
    private int currentPetIndex = 0;
    private final List<Button> targetButtons = new ArrayList<>();

    public PetMenu(PetOverlayService service) {
        this.context = service;
        this.service = service;
    }

    public PetMenu(MainActivity activity) {
        this.context = activity;
        this.activity = activity;
    }

    public int dp(int v) {
        return (int) (v * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    public void show() {
        if (dialog != null && dialog.isShowing()) return;

        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(12), dp(10), dp(12), dp(10));

            // ---- 顶栏：标题 + ✕ 关闭按钮 ----
            LinearLayout topBar = new LinearLayout(context);
            topBar.setOrientation(LinearLayout.HORIZONTAL);
            topBar.setGravity(Gravity.CENTER_VERTICAL);

            TextView title = new TextView(context);
            title.setText("🐾 控制中心");
            title.setTextColor(0xFFF1F5F9);
            title.setTextSize(12.5f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            topBar.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView closeBtn = new TextView(context);
            closeBtn.setText("✕");
            closeBtn.setTextColor(0xFF94A3B8);
            closeBtn.setTextSize(14f);
            closeBtn.setPadding(dp(6), dp(2), dp(4), dp(2));
            closeBtn.setOnClickListener(v -> dismiss());
            topBar.addView(closeBtn);
            root.addView(topBar);

            // 分割线
            View div1 = new View(context);
            div1.setBackgroundColor(0x33475569);
            LinearLayout.LayoutParams divLp1 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
            divLp1.topMargin = dp(5);
            divLp1.bottomMargin = dp(6);
            root.addView(div1, divLp1);

            // ---- 监控目标标签行 (点按切换，长按直达工作台/网页) ----
            TextView targetHeader = new TextView(context);
            targetHeader.setText("🎯 监控目标 (点按切换 · 长按进入)");
            targetHeader.setTextColor(0xFF94A3B8);
            targetHeader.setTextSize(10f);
            root.addView(targetHeader);

            LinearLayout targetRow1 = new LinearLayout(context);
            targetRow1.setOrientation(LinearLayout.HORIZONTAL);
            targetRow1.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams tr1Lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            tr1Lp.topMargin = dp(3);

            Button btnPiWeb = createTargetButton("pi-web", PetRegistry.TARGET_PIWEB, v -> {
                switchTarget(PetRegistry.TARGET_PIWEB);
            }, v -> {
                openConsole(PetRegistry.TARGET_PIWEB);
                return true;
            });
            Button btnOperit = createTargetButton("Operit", PetRegistry.TARGET_OPERIT, v -> {
                switchTarget(PetRegistry.TARGET_OPERIT);
            }, v -> {
                openConsole(PetRegistry.TARGET_OPERIT);
                return true;
            });

            targetRow1.addView(btnPiWeb, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            targetRow1.addView(createSpacingView(4));
            targetRow1.addView(btnOperit, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            root.addView(targetRow1, tr1Lp);

            LinearLayout targetRow2 = new LinearLayout(context);
            targetRow2.setOrientation(LinearLayout.HORIZONTAL);
            targetRow2.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams tr2Lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            tr2Lp.topMargin = dp(3);

            Button btnClaw = createTargetButton("ClawBench", PetRegistry.TARGET_CLAWBENCH, v -> {
                switchTarget(PetRegistry.TARGET_CLAWBENCH);
            }, v -> {
                openConsole(PetRegistry.TARGET_CLAWBENCH);
                return true;
            });
            Button btnRikka = createTargetButton("RikkaHub", PetRegistry.TARGET_RIKKA, v -> {
                switchTarget(PetRegistry.TARGET_RIKKA);
            }, v -> {
                openConsole(PetRegistry.TARGET_RIKKA);
                return true;
            });

            targetRow2.addView(btnClaw, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            targetRow2.addView(createSpacingView(4));
            targetRow2.addView(btnRikka, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            root.addView(targetRow2, tr2Lp);

            targetButtons.clear();
            targetButtons.add(btnPiWeb);
            targetButtons.add(btnOperit);
            targetButtons.add(btnClaw);
            targetButtons.add(btnRikka);

            refreshTargetButtonStyles(getCurrentTarget());

            // ---- 对话通道 (点按轮换) ----
            LinearLayout chatModeRow = new LinearLayout(context);
            chatModeRow.setOrientation(LinearLayout.HORIZONTAL);
            chatModeRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams cmLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cmLp.topMargin = dp(6);

            TextView chatHeader = new TextView(context);
            chatHeader.setText("💬 对话通道");
            chatHeader.setTextColor(0xFF94A3B8);
            chatHeader.setTextSize(10f);
            chatModeRow.addView(chatHeader, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            ChatConfig currentCfg = ChatConfig.load(context);
            chatModeBtn = buildMiniBtn(currentCfg.modeLabel(), 0x2238BDF8, 0x4438BDF8, 0xFF7DD3FC, v -> {
                cycleChatMode();
            });
            chatModeRow.addView(chatModeBtn);
            root.addView(chatModeRow, cmLp);

            // 分割线
            View div2 = new View(context);
            div2.setBackgroundColor(0x33475569);
            LinearLayout.LayoutParams divLp2 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
            divLp2.topMargin = dp(5);
            divLp2.bottomMargin = dp(6);
            root.addView(div2, divLp2);

            // ---- 角色形象切换器 ◀ 名字 ▶ ----
            LinearLayout petSwitchRow = new LinearLayout(context);
            petSwitchRow.setOrientation(LinearLayout.HORIZONTAL);
            petSwitchRow.setGravity(Gravity.CENTER_VERTICAL);

            petHeader = new TextView(context);
            petHeader.setText("🐾 角色形象");
            petHeader.setTextColor(0xFF94A3B8);
            petHeader.setTextSize(10f);
            petSwitchRow.addView(petHeader, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            allPets = PetRegistry.getAllPets(context);
            String currentDir = PetRegistry.getStringPref(context, PetRegistry.KEY_PET_DIR, PetRegistry.DEFAULT_PET_DIR);
            currentPetIndex = 0;
            for (int i = 0; i < allPets.size(); i++) {
                if (allPets.get(i).dir.equals(currentDir)) {
                    currentPetIndex = i;
                    break;
                }
            }

            Button prevPetBtn = buildMiniBtn("◀", 0x22334155, 0x44475569, 0xFF94A3B8, v -> stepPet(-1));
            prevPetBtn.setPadding(dp(5), dp(2), dp(5), dp(2));

            petNameTv = new TextView(context);
            petNameTv.setTextColor(0xFFF8FAFC);
            petNameTv.setTextSize(10.5f);
            petNameTv.setGravity(Gravity.CENTER);
            petNameTv.setPadding(dp(6), 0, dp(6), 0);
            updatePetNameLabel();

            Button nextPetBtn = buildMiniBtn("▶", 0x22334155, 0x44475569, 0xFF94A3B8, v -> stepPet(1));
            nextPetBtn.setPadding(dp(5), dp(2), dp(5), dp(2));

            bindPetBtn = buildMiniBtn("📌 绑定", 0x22F59E0B, 0x44F59E0B, 0xFFFCD34D, v -> bindCurrentPetToMode());
            bindPetBtn.setPadding(dp(6), dp(2), dp(6), dp(2));

            petSwitchRow.addView(prevPetBtn);
            petSwitchRow.addView(petNameTv);
            petSwitchRow.addView(nextPetBtn);
            petSwitchRow.addView(createSpacingView(4));
            petSwitchRow.addView(bindPetBtn);

            root.addView(petSwitchRow);

            // ---- 尺寸滑块 ----
            int curPetSize = PetRegistry.getIntPref(context, PetRegistry.KEY_PET_SIZE, PetRegistry.DEFAULT_PET_SIZE);
            TextView sizeLabel = new TextView(context);
            sizeLabel.setText("📏 宠物大小: " + curPetSize + " dp");
            sizeLabel.setTextColor(0xFF94A3B8);
            sizeLabel.setTextSize(10f);
            LinearLayout.LayoutParams sizeLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sizeLp.topMargin = dp(4);
            root.addView(sizeLabel, sizeLp);

            SeekBar sizeBar = new SeekBar(context);
            sizeBar.setMax(140 - 32);
            sizeBar.setProgress(Math.max(0, Math.min(140 - 32, curPetSize - 32)));
            sizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    int val = progress + 32;
                    sizeLabel.setText("📏 宠物大小: " + val + " dp");
                    PetRegistry.setIntPref(context, PetRegistry.KEY_PET_SIZE, val);
                    if (service != null) {
                        service.applyPetWidth(val);
                    }
                    if (activity != null) {
                        activity.applyPetParams();
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sbLp.topMargin = dp(1);
            root.addView(sizeBar, sbLp);

            // 分割线
            View div3 = new View(context);
            div3.setBackgroundColor(0x33475569);
            LinearLayout.LayoutParams divLp3 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
            divLp3.topMargin = dp(4);
            divLp3.bottomMargin = dp(5);
            root.addView(div3, divLp3);

            // ---- 🌐 全局桌面桌宠开关 (长按菜单专属) ----
            boolean overlayRunning = PetOverlayService.isRunning();
            globalSwitchBtn = buildMiniBtn(
                    overlayRunning ? "🌐 全局桌宠: 已开启 (点击关闭)" : "🌐 全局桌宠: 未开启 (点击开启)",
                    overlayRunning ? 0x2E10B981 : 0x22374151,
                    overlayRunning ? 0x4D10B981 : 0x444B5563,
                    overlayRunning ? 0xFF34D399 : 0xFF9CA3AF,
                    v -> toggleGlobalOverlayPet()
            );
            LinearLayout.LayoutParams gLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(28));
            gLp.bottomMargin = dp(6);
            root.addView(globalSwitchBtn, gLp);

            // ---- 底部操作功能键行：[🎛️ 调节] [💬 聊天] [💻 终端] [⚙️ 设置] [🔴 关闭] ----
            LinearLayout actionRow = new LinearLayout(context);
            actionRow.setOrientation(LinearLayout.HORIZONTAL);
            actionRow.setGravity(Gravity.CENTER_VERTICAL);

            // 1. 🎛️ 物理参数调节弹窗
            Button paramsBtn = buildMiniBtn("🎛️ 调节", 0x228B5CF6, 0x448B5CF6, 0xFFC4B5FD, v -> {
                dismiss();
                if (service != null) {
                    new PetParamsDialog(service).show();
                } else {
                    new PetParamsDialog(context, () -> {
                        if (activity != null) activity.applyPetParams();
                    }).show();
                }
            });
            actionRow.addView(paramsBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            actionRow.addView(createSpacingView(3));

            // 2. 💬 全屏聊天工作台
            Button chatBtn = buildMiniBtn("💬 聊天", 0x2210B981, 0x4410B981, 0xFFA7F3D0, v -> {
                dismiss();
                openFullChat();
            });
            actionRow.addView(chatBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            actionRow.addView(createSpacingView(3));

            // 3. 💻 终端
            Button termBtn = buildMiniBtn("💻 终端", 0x223B82F6, 0x443B82F6, 0xFF93C5FD, v -> {
                dismiss();
                if (activity != null) {
                    activity.openTerminalInWorkbench();
                } else {
                    Intent intent = new Intent(context, MainActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    intent.putExtra("pimet.open_terminal", true);
                    context.startActivity(intent);
                }
            });
            actionRow.addView(termBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            actionRow.addView(createSpacingView(3));

            // 4. ⚙️ 设置
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
            actionRow.addView(setBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            actionRow.addView(createSpacingView(3));

            // 5. 🔴 关闭桌宠
            Button stopBtn = buildMiniBtn("🔴 关闭", 0x22EF4444, 0x44EF4444, 0xFFFCA5A5, v -> {
                dismiss();
                if (service != null) {
                    service.stopSelf();
                }
                if (activity != null) {
                    activity.updatePetDisplay(false);
                }
            });
            actionRow.addView(stopBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            root.addView(actionRow);
            builder.setView(root);

            dialog = builder.create();
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
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xF4181A22);
                bg.setCornerRadius(dp(13));
                bg.setStroke(dp(1), 0x33475569);
                window.setBackgroundDrawable(bg);
            }

            dialog.show();

            if (window != null) {
                WindowManager.LayoutParams lp = window.getAttributes();
                lp.width = dp(230);
                lp.gravity = Gravity.CENTER;
                window.setAttributes(lp);
            }

        } catch (Throwable t) {
            Toast.makeText(context, "打开菜单失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleGlobalOverlayPet() {
        if (PetOverlayService.isRunning()) {
            Intent intent = new Intent(context, PetOverlayService.class);
            context.stopService(intent);
            Toast.makeText(context, "已关闭全局系统桌面桌宠", Toast.LENGTH_SHORT).show();
            if (activity != null) {
                activity.updatePetDisplay(true);
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                Toast.makeText(context, "请先在系统设置中授予 PiMet 悬浮窗权限", Toast.LENGTH_LONG).show();
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + context.getPackageName()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                } catch (Throwable t) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                }
            } else {
                Intent intent = new Intent(context, PetOverlayService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent);
                } else {
                    context.startService(intent);
                }
                Toast.makeText(context, "已开启全局系统桌面桌宠", Toast.LENGTH_SHORT).show();
                if (activity != null) {
                    activity.updatePetDisplay(false);
                }
            }
        }
        dismiss();
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

    private void switchTarget(String targetKey) {
        PetRegistry.setStringPref(context, PetRegistry.KEY_MONITOR_TARGET, targetKey);
        if (service != null) {
            service.switchMonitorTarget(targetKey);
        }
        if (activity != null) {
            activity.initPetMonitor();
            activity.showPetBubble("🎯 监控已切换至: " + targetKey);
        }
        refreshTargetButtonStyles(targetKey);
    }

    private void openConsole(String targetKey) {
        dismiss();
        if (PetRegistry.TARGET_PIWEB.equals(targetKey)) {
            if (activity != null) {
                activity.switchTab(1);
            } else {
                int p = PetRegistry.getPiWebPort(context);
                openBrowser("http://127.0.0.1:" + p);
            }
        } else if (PetRegistry.TARGET_OPERIT.equals(targetKey)) {
            int p = PetRegistry.getOperitPort(context);
            openBrowser("http://127.0.0.1:" + p);
        } else if (PetRegistry.TARGET_CLAWBENCH.equals(targetKey)) {
            int p = PetRegistry.getClawbenchPort(context);
            openBrowser("http://127.0.0.1:" + p);
        } else if (PetRegistry.TARGET_RIKKA.equals(targetKey)) {
            int p = PetRegistry.getRikkaPort(context);
            openBrowser("http://127.0.0.1:" + p);
        }
    }

    private void openBrowser(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(context, "无法打开浏览器: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private String getCurrentTarget() {
        return PetRegistry.getStringPref(context, PetRegistry.KEY_MONITOR_TARGET, PetRegistry.TARGET_PIWEB);
    }

    private void cycleChatMode() {
        ChatConfig cfg = ChatConfig.load(context);
        cfg.cycleMode();
        cfg.save(context);
        if (chatModeBtn != null) {
            chatModeBtn.setText(cfg.modeLabel());
        }
        if (service != null) {
            service.showReplyBubble("💬 对话通道已切换: " + cfg.modeLabel());
        }
        if (activity != null) {
            activity.showPetBubble("💬 对话通道: " + cfg.modeLabel());
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

    private void bindCurrentPetToMode() {
        if (allPets == null || allPets.isEmpty()) return;
        PetRegistry.PetInfo curPet = allPets.get(currentPetIndex);
        ChatConfig cfg = ChatConfig.load(context);
        cfg.bindPetToCurrentMode(context, curPet.dir);
        String msg = "✨ 已将「" + curPet.displayName + "」绑定到 " + cfg.modeLabel() + "！";
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
        if (service != null) service.showReplyBubble(msg);
        if (activity != null) activity.showPetBubble(msg);
    }

    private void updatePetNameLabel() {
        if (petNameTv != null && allPets != null && !allPets.isEmpty()) {
            petNameTv.setText(allPets.get(currentPetIndex).displayName);
        }
    }

    private Button createTargetButton(String label, String targetKey, View.OnClickListener clk, View.OnLongClickListener lclk) {
        Button btn = new Button(context);
        btn.setText(label);
        btn.setTextSize(10f);
        btn.setPadding(dp(4), dp(3), dp(4), dp(3));
        btn.setMinHeight(dp(24));
        btn.setMinimumHeight(dp(24));
        btn.setTag(targetKey);
        btn.setOnClickListener(clk);
        btn.setOnLongClickListener(lclk);
        return btn;
    }

    private void refreshTargetButtonStyles(String activeTarget) {
        for (Button btn : targetButtons) {
            String key = (String) btn.getTag();
            boolean isActive = key != null && key.equals(activeTarget);
            GradientDrawable d = new GradientDrawable();
            d.setCornerRadius(dp(6));
            if (isActive) {
                d.setColor(0x3310B981);
                d.setStroke(dp(1), 0xFF10B981);
                btn.setTextColor(0xFF34D399);
                btn.setTypeface(Typeface.DEFAULT_BOLD);
            } else {
                d.setColor(0x221E293B);
                d.setStroke(dp(1), 0x22475569);
                btn.setTextColor(0xFF94A3B8);
                btn.setTypeface(Typeface.DEFAULT);
            }
            btn.setBackground(d);
        }
    }

    private View createSpacingView(int widthDp) {
        View v = new View(context);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(widthDp), dp(1)));
        return v;
    }

    private Button buildMiniBtn(String text, int bgNormal, int bgPressed, int textColor, View.OnClickListener clk) {
        Button btn = new Button(context);
        btn.setText(text);
        btn.setTextSize(10f);
        btn.setTextColor(textColor);
        btn.setPadding(dp(6), dp(3), dp(6), dp(3));
        btn.setMinHeight(dp(24));
        btn.setMinimumHeight(dp(24));

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
