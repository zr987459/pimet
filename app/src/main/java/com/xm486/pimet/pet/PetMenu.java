package com.xm486.pimet.pet;

import android.app.AlertDialog;
import android.content.Intent;
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
import com.xm486.pimet.MainActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * 桌宠一级快捷悬浮菜单：
 * 1. 监控目标一键切换（Operit / pi-web / ClawBench / API / RikkaHub）
 * 2. 对话目标快速切换联动
 * 3. 二级菜单入口「🎛️ 参数调节」（尺寸/气泡/卡片/缩放）
 * 4. 主页与关闭桌宠快捷操作
 */
public class PetMenu {

    private final PetOverlayService service;
    private AlertDialog dialog;
    private Button chatModeBtn;
    private TextView petHeader;
    private TextView petNameTv;
    private Button bindPetBtn;
    private List<PetRegistry.PetInfo> allPets;
    private int currentPetIndex = 0;
    private final List<Button> targetButtons = new ArrayList<>();

    public PetMenu(PetOverlayService service) {
        this.service = service;
    }

    public void show() {
        if (dialog != null && dialog.isShowing()) return;

        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(service);
            LinearLayout root = new LinearLayout(service);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(service.dp(10), service.dp(8), service.dp(10), service.dp(8));

            // ---- 顶栏：标题 + 关闭按钮 ----
            LinearLayout topBar = new LinearLayout(service);
            topBar.setOrientation(LinearLayout.HORIZONTAL);
            topBar.setGravity(Gravity.CENTER_VERTICAL);

            TextView title = new TextView(service);
            title.setText("🐾 控制中心");
            title.setTextColor(0xFFF1F5F9);
            title.setTextSize(11.5f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            topBar.addView(title, titleLp);

            TextView closeBtn = new TextView(service);
            closeBtn.setText("✕");
            closeBtn.setTextColor(0xFF94A3B8);
            closeBtn.setTextSize(13f);
            closeBtn.setPadding(service.dp(6), service.dp(2), service.dp(4), service.dp(2));
            closeBtn.setOnClickListener(v -> dismiss());
            topBar.addView(closeBtn);
            root.addView(topBar);

            // 分割线
            View divider1 = new View(service);
            divider1.setBackgroundColor(0x33475569);
            LinearLayout.LayoutParams divLp1 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, service.dp(1));
            divLp1.topMargin = service.dp(4);
            divLp1.bottomMargin = service.dp(5);
            root.addView(divider1, divLp1);

            // ---- 监控目标切换器 ----
            TextView targetHeader = new TextView(service);
            targetHeader.setText("🎯 监控目标 (点击切换 · 长按进入端口)");
            targetHeader.setTextColor(0xFF94A3B8);
            targetHeader.setTextSize(9.5f);
            targetHeader.setOnClickListener(v -> {
                dismiss();
                service.openTargetConsole();
            });
            targetHeader.setOnLongClickListener(v -> {
                dismiss();
                service.openTargetConsole();
                return true;
            });
            root.addView(targetHeader);

            targetButtons.clear();
            String currentTarget = service.getCurrentTarget();

            // 目标列表：2x2 网格（Operit/pi-web/ClawBench/RikkaHub）
            LinearLayout row1 = new LinearLayout(service);
            row1.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams r1Lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            r1Lp.topMargin = service.dp(3);
            row1.setLayoutParams(r1Lp);

            Button btnOperit = createTargetChip("Operit", PetRegistry.TARGET_OPERIT, currentTarget);
            Button btnPiWeb = createTargetChip("pi-web", PetRegistry.TARGET_PIWEB, currentTarget);
            row1.addView(btnOperit, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row1.addView(createSpacing(4));
            row1.addView(btnPiWeb, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            root.addView(row1);

            LinearLayout row2 = new LinearLayout(service);
            row2.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams r2Lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            r2Lp.topMargin = service.dp(3);
            row2.setLayoutParams(r2Lp);

            Button btnClaw = createTargetChip("ClawBench", PetRegistry.TARGET_CLAWBENCH, currentTarget);
            Button btnRikka = createTargetChip("RikkaHub", PetRegistry.TARGET_RIKKA, currentTarget);
            row2.addView(btnClaw, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row2.addView(createSpacing(4));
            row2.addView(btnRikka, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            root.addView(row2);

            // 分割线
            View divider2 = new View(service);
            divider2.setBackgroundColor(0x33475569);
            LinearLayout.LayoutParams divLp2 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, service.dp(1));
            divLp2.topMargin = service.dp(6);
            divLp2.bottomMargin = service.dp(5);
            root.addView(divider2, divLp2);

            // ---- 对话目标快速切换 ----
            TextView chatHeader = new TextView(service);
            chatHeader.setText("💬 对话通道 (点按轮换)");
            chatHeader.setTextColor(0xFF94A3B8);
            chatHeader.setTextSize(9.5f);
            root.addView(chatHeader);

            chatModeBtn = buildMiniBtn(getChatModeButtonLabel(), 0x333B82F6, 0x553B82F6, 0xFF93C5FD, v -> cycleChatMode());
            LinearLayout.LayoutParams chatBtnLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            chatBtnLp.topMargin = service.dp(3);
            root.addView(chatModeBtn, chatBtnLp);

            // 分割线
            View dividerPet = new View(service);
            dividerPet.setBackgroundColor(0x33475569);
            LinearLayout.LayoutParams divPetLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, service.dp(1));
            divPetLp.topMargin = service.dp(6);
            divPetLp.bottomMargin = service.dp(5);
            root.addView(dividerPet, divPetLp);

            // ---- 形象切换与绑定 ----
            initPets();
            petHeader = new TextView(service);
            petHeader.setText(getPetHeaderLabel());
            petHeader.setTextColor(0xFF94A3B8);
            petHeader.setTextSize(9.5f);
            root.addView(petHeader);

            LinearLayout petSwitchRow = new LinearLayout(service);
            petSwitchRow.setOrientation(LinearLayout.HORIZONTAL);
            petSwitchRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams psrLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            psrLp.topMargin = service.dp(3);
            petSwitchRow.setLayoutParams(psrLp);

            Button prevPetBtn = buildMiniBtn("◀", 0x22FFFFFF, 0x44FFFFFF, 0xFFFFFFFF, v -> switchPetStep(-1));
            prevPetBtn.setMinWidth(service.dp(28));
            prevPetBtn.setMinimumWidth(service.dp(28));

            petNameTv = new TextView(service);
            petNameTv.setText(getCurrentPetDisplayName());
            petNameTv.setTextColor(0xFFF1F5F9);
            petNameTv.setTextSize(11f);
            petNameTv.setTypeface(null, Typeface.BOLD);
            petNameTv.setGravity(Gravity.CENTER);
            petNameTv.setPadding(service.dp(4), service.dp(2), service.dp(4), service.dp(2));

            GradientDrawable nameBg = new GradientDrawable();
            nameBg.setColor(0x18FFFFFF);
            nameBg.setCornerRadius(service.dp(6));
            petNameTv.setBackground(nameBg);

            Button nextPetBtn = buildMiniBtn("▶", 0x22FFFFFF, 0x44FFFFFF, 0xFFFFFFFF, v -> switchPetStep(1));
            nextPetBtn.setMinWidth(service.dp(28));
            nextPetBtn.setMinimumWidth(service.dp(28));

            petSwitchRow.addView(prevPetBtn, new LinearLayout.LayoutParams(service.dp(30), LinearLayout.LayoutParams.WRAP_CONTENT));
            petSwitchRow.addView(createSpacing(3));
            petSwitchRow.addView(petNameTv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            petSwitchRow.addView(createSpacing(3));
            petSwitchRow.addView(nextPetBtn, new LinearLayout.LayoutParams(service.dp(30), LinearLayout.LayoutParams.WRAP_CONTENT));
            root.addView(petSwitchRow);

            bindPetBtn = buildMiniBtn(getBindButtonLabel(), 0x3310B981, 0x5510B981, 0xFF6EE7B7, v -> bindCurrentPetToMode());
            LinearLayout.LayoutParams bindLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            bindLp.topMargin = service.dp(3);
            root.addView(bindPetBtn, bindLp);

            // ---- 宠物大小滑块调节 (32~120dp) ----
            int curPetSize = PetRegistry.getIntPref(service, PetRegistry.KEY_PET_SIZE, PetRegistry.DEFAULT_PET_SIZE);
            TextView sizeLabel = new TextView(service);
            sizeLabel.setText("📏 宠物大小: " + curPetSize + " dp");
            sizeLabel.setTextColor(0xFF94A3B8);
            sizeLabel.setTextSize(9.5f);
            LinearLayout.LayoutParams sizeLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sizeLp.topMargin = service.dp(4);
            root.addView(sizeLabel, sizeLp);

            SeekBar sizeBar = new SeekBar(service);
            sizeBar.setMax(120 - 32);
            sizeBar.setProgress(curPetSize - 32);
            sizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    int val = 32 + progress;
                    sizeLabel.setText("📏 宠物大小: " + val + " dp");
                    PetRegistry.setIntPref(service, PetRegistry.KEY_PET_SIZE, val);
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            root.addView(sizeBar);

            // 分割线
            View divider3 = new View(service);
            divider3.setBackgroundColor(0x33475569);
            LinearLayout.LayoutParams divLp3 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, service.dp(1));
            divLp3.topMargin = service.dp(6);
            divLp3.bottomMargin = service.dp(5);
            root.addView(divider3, divLp3);

            // ---- 底部操作按钮行 ----
            LinearLayout actionRow = new LinearLayout(service);
            actionRow.setOrientation(LinearLayout.HORIZONTAL);
            actionRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams actRowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            actRowLp.topMargin = service.dp(2);
            actionRow.setLayoutParams(actRowLp);

            // 1. 参数调节二级菜单
            Button paramsBtn = buildMiniBtn("🎛️ 调节", 0x228B5CF6, 0x448B5CF6, 0xFFDDD6FE, v -> {
                dismiss();
                new PetParamsDialog(service).show();
            });
            actionRow.addView(paramsBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            actionRow.addView(createSpacing(3));

            // 2. 聊天卡片开关（打开/收起底部聊天框）
            Button chatBtn = buildMiniBtn("💬 聊天", 0x2210B981, 0x4410B981, 0xFFA7F3D0, v -> {
                dismiss();
                service.toggleCard();
            });
            actionRow.addView(chatBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            actionRow.addView(createSpacing(3));

            // 3. 主页设置
            Button homeBtn = buildMiniBtn("⚙️ 设置", 0x22FFFFFF, 0x44FFFFFF, 0xFFE2E8F0, v -> {
                dismiss();
                Intent intent = new Intent(service, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                intent.putExtra("devpetm.open_ai_tab", true);
                service.startActivity(intent);
            });
            actionRow.addView(homeBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            actionRow.addView(createSpacing(3));

            // 4. 关闭桌宠
            Button stopBtn = buildMiniBtn("🔴 关闭", 0x33EF4444, 0x55EF4444, 0xFFFCA5A5, v -> {
                dismiss();
                service.stopSelf();
            });
            actionRow.addView(stopBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            root.addView(actionRow);
            builder.setView(root);

            dialog = builder.create();
            Window window = dialog.getWindow();
            if (window != null) {
                window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xF4181A22);
                bg.setCornerRadius(service.dp(13));
                bg.setStroke(service.dp(1), 0x33475569);
                window.setBackgroundDrawable(bg);
            }

            dialog.show();

            // 定位在桌宠旁
            if (window != null) {
                WindowManager.LayoutParams attrs = window.getAttributes();
                if (attrs != null) {
                    attrs.gravity = Gravity.TOP | Gravity.START;
                    int menuW = service.dp(PetRegistry.getIntPref(service, "pref_menu_width", 160));
                    attrs.width = menuW;
                    attrs.height = WindowManager.LayoutParams.WRAP_CONTENT;

                    WindowManager.LayoutParams petLp =
                            (WindowManager.LayoutParams) service.getOverlayRoot().getLayoutParams();
                    int screenW = service.getResources().getDisplayMetrics().widthPixels;
                    int petW = service.getOverlayRoot().getWidth();
                    if (petW <= 0) petW = service.dp(70);

                    if (petLp.x + petW / 2 > screenW / 2) {
                        attrs.x = Math.max(service.dp(6), petLp.x - menuW - service.dp(4));
                    } else {
                        attrs.x = Math.min(screenW - menuW - service.dp(6), petLp.x + petW + service.dp(4));
                    }
                    attrs.y = Math.max(service.dp(20), petLp.y);
                    window.setAttributes(attrs);
                }
            }

        } catch (Throwable t) {
            android.util.Log.w("DevPetM.PetMenu", "show failed", t);
        }
    }

    private View createSpacing(int widthDp) {
        View v = new View(service);
        v.setLayoutParams(new LinearLayout.LayoutParams(service.dp(widthDp), 1));
        return v;
    }

    private Button createTargetChip(String label, String targetKey, String activeTarget) {
        boolean isActive = targetKey.equals(activeTarget);
        Button btn = new Button(service);
        btn.setTag(targetKey);
        btn.setText(label);
        btn.setTextSize(9.5f);
        btn.setPadding(service.dp(2), service.dp(3), service.dp(2), service.dp(3));
        btn.setMinHeight(service.dp(24));
        btn.setMinimumHeight(service.dp(24));
        btn.setMinWidth(0);
        btn.setMinimumWidth(0);

        applyChipStyle(btn, isActive);

        btn.setOnClickListener(v -> {
            service.switchMonitorTarget(targetKey);
            for (Button b : targetButtons) {
                String k = (String) b.getTag();
                applyChipStyle(b, targetKey.equals(k));
            }
            if (chatModeBtn != null) {
                chatModeBtn.setText(getChatModeButtonLabel());
            }
            refreshPetDisplay();
        });
        // 长按目标名字：直接进入该目标的 Web 端口/控制台
        btn.setOnLongClickListener(v -> {
            dismiss();
            service.openConsoleForTarget(targetKey);
            return true;
        });
        targetButtons.add(btn);
        return btn;
    }

    private void applyChipStyle(Button btn, boolean isActive) {
        int bg = isActive ? 0xFF2563EB : 0x22FFFFFF;
        int textClr = isActive ? 0xFFFFFFFF : 0xFFCBD5E1;
        btn.setTextColor(textClr);

        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(service.dp(6));
        if (isActive) {
            d.setStroke(service.dp(1), 0xFF60A5FA);
        } else {
            d.setStroke(service.dp(1), 0x22475569);
        }
        btn.setBackground(d);
    }

    private String getChatModeButtonLabel() {
        try {
            ChatConfig cfg = ChatConfig.load(service);
            return "💬 模式: " + cfg.modeLabel();
        } catch (Throwable t) {
            return "💬 模式: 默认";
        }
    }

    private void cycleChatMode() {
        try {
            ChatConfig cfg = ChatConfig.load(service);
            String nextMode;
            if (ChatConfig.MODE_OPERIT.equals(cfg.mode)) {
                nextMode = ChatConfig.MODE_PIWEB;
            } else if (ChatConfig.MODE_PIWEB.equals(cfg.mode)) {
                nextMode = ChatConfig.MODE_CLAWBENCH;
            } else if (ChatConfig.MODE_CLAWBENCH.equals(cfg.mode)) {
                nextMode = ChatConfig.MODE_CUSTOM_API;
            } else {
                nextMode = ChatConfig.MODE_OPERIT;
            }
            cfg.mode = nextMode;
            cfg.save(service);
            service.onChatConfigChanged();
            if (chatModeBtn != null) {
                chatModeBtn.setText(getChatModeButtonLabel());
            }
            refreshPetDisplay();
            service.showReplyBubble("💬 对话通道已切换: " + cfg.modeLabel());
        } catch (Throwable t) {
            android.util.Log.w("DevPetM.PetMenu", "cycleChatMode failed", t);
        }
    }

    private void initPets() {
        allPets = PetRegistry.loadPets(service);
        if (allPets == null || allPets.isEmpty()) {
            allPets = new ArrayList<>();
            PetRegistry.PetInfo def = new PetRegistry.PetInfo();
            def.dir = PetRegistry.DEFAULT_PET_DIR;
            def.displayName = "Classic";
            allPets.add(def);
        }
        syncCurrentPetIndex();
    }

    private void syncCurrentPetIndex() {
        String curDir = PetRegistry.getPetDir(service);
        currentPetIndex = 0;
        if (allPets == null) return;
        for (int i = 0; i < allPets.size(); i++) {
            if (allPets.get(i).dir.equals(curDir)) {
                currentPetIndex = i;
                break;
            }
        }
    }

    private String getCurrentPetDisplayName() {
        if (allPets == null || allPets.isEmpty()) return "Classic";
        if (currentPetIndex < 0 || currentPetIndex >= allPets.size()) syncCurrentPetIndex();
        return allPets.get(currentPetIndex).displayName;
    }

    private String getPetHeaderLabel() {
        try {
            ChatConfig cfg = ChatConfig.load(service);
            return "🐾 角色形象 (" + cfg.modeLabel() + ")";
        } catch (Throwable t) {
            return "🐾 角色形象";
        }
    }

    private String getBindButtonLabel() {
        try {
            ChatConfig cfg = ChatConfig.load(service);
            String boundDir = PetRegistry.getPetDirForMode(service, cfg.mode);
            String curDir = PetRegistry.getPetDir(service);
            if (curDir != null && curDir.equals(boundDir)) {
                return "✓ 当前角色已绑定到此模式";
            }
        } catch (Throwable ignore) {}
        return "📌 绑定为此模式专属角色";
    }

    private void switchPetStep(int step) {
        if (allPets == null || allPets.isEmpty()) return;
        currentPetIndex = (currentPetIndex + step + allPets.size()) % allPets.size();
        PetRegistry.PetInfo nextPet = allPets.get(currentPetIndex);
        service.changePet(nextPet.dir);
        if (petNameTv != null) {
            petNameTv.setText(nextPet.displayName);
        }
        if (bindPetBtn != null) {
            bindPetBtn.setText(getBindButtonLabel());
        }
    }

    private void bindCurrentPetToMode() {
        try {
            if (allPets == null || allPets.isEmpty()) return;
            ChatConfig cfg = ChatConfig.load(service);
            if (currentPetIndex < 0 || currentPetIndex >= allPets.size()) syncCurrentPetIndex();
            PetRegistry.PetInfo curPet = allPets.get(currentPetIndex);
            PetRegistry.setPetDirForMode(service, cfg.mode, curPet.dir);
            if (bindPetBtn != null) {
                bindPetBtn.setText("✓ 已绑定至 " + cfg.modeLabel());
            }
            service.showReplyBubble("✨ 已将「" + curPet.displayName + "」绑定到 " + cfg.modeLabel() + "！");
        } catch (Throwable t) {
            android.util.Log.w("DevPetM.PetMenu", "bindCurrentPetToMode failed", t);
        }
    }

    private void refreshPetDisplay() {
        syncCurrentPetIndex();
        if (petHeader != null) petHeader.setText(getPetHeaderLabel());
        if (petNameTv != null) petNameTv.setText(getCurrentPetDisplayName());
        if (bindPetBtn != null) bindPetBtn.setText(getBindButtonLabel());
    }

    private Button buildMiniBtn(String text, int bgNormal, int bgPressed, int textColor, View.OnClickListener clk) {
        Button btn = new Button(service);
        btn.setText(text);
        btn.setTextSize(9.5f);
        btn.setTextColor(textColor);
        btn.setPadding(service.dp(4), service.dp(3), service.dp(4), service.dp(3));
        btn.setMinHeight(service.dp(24));
        btn.setMinimumHeight(service.dp(24));
        btn.setMinWidth(0);
        btn.setMinimumWidth(0);

        GradientDrawable n = new GradientDrawable();
        n.setColor(bgNormal);
        n.setCornerRadius(service.dp(6));

        GradientDrawable p = new GradientDrawable();
        p.setColor(bgPressed);
        p.setCornerRadius(service.dp(6));

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
