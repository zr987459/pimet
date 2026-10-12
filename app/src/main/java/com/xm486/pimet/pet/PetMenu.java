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
import android.util.Log;
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
import com.xm486.pimet.ThemeManager;

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

    /** 菜单最小宽度：保证 2 列按钮在大字号下仍可单行显示。 */
    public static final int MIN_MENU_WIDTH = 240;
    public static final int MAX_MENU_WIDTH = 320;

    private ThemeManager.ThemePalette palette;

    /** 将保存的菜单宽度规整到可用区间，并不超过屏幕宽度（左右各留 8dp）。 */
    private int effectiveMenuWidthDp(int savedDp) {
        int w = Math.max(MIN_MENU_WIDTH, Math.min(MAX_MENU_WIDTH, savedDp));
        android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int screenDp = (int) (dm.widthPixels / dm.density);
        return Math.max(200, Math.min(w, screenDp - 16));
    }

    private View hairline() {
        View div = new View(context);
        div.setBackgroundColor(palette.border);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(8);
        div.setLayoutParams(lp);
        return div;
    }

    private TextView sectionLabel(String text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(palette.textMuted);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        return tv;
    }

    /** 两个按钮等宽并排，中间 8dp 间距。 */
    private LinearLayout pairRow(View left, View right) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams r = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        r.leftMargin = dp(8);
        row.addView(left, l);
        row.addView(right, r);
        return row;
    }

    private LinearLayout.LayoutParams rowLp(int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topMarginDp);
        return lp;
    }

    private void tintSeekBar(SeekBar sb) {
        try {
            android.content.res.ColorStateList acc = android.content.res.ColorStateList.valueOf(palette.accent);
            sb.setProgressTintList(acc);
            sb.setThumbTintList(acc);
            sb.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(palette.border));
        } catch (Throwable ignored) {}
    }

    public void show() {
        if (dialog != null && dialog.isShowing()) return;

        try {
            palette = ThemeManager.getEffectivePalette(context);
            dialog = new Dialog(context);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            // 读取已保存的菜单宽度；为保证按钮不挤压，实际宽度不小于 240dp
            int savedMenuWidth = PetRegistry.getIntPref(context, PetRegistry.KEY_MENU_WIDTH,
                    PetRegistry.getIntPref(context, "pref_menu_width", PetRegistry.DEFAULT_MENU_WIDTH));
            final int initialMenuWidth = effectiveMenuWidthDp(savedMenuWidth);

            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(12), dp(8), dp(12), dp(12));

            // 不透明面板底色 + 发丝描边，杜绝首页内容透出
            GradientDrawable rootBg = new GradientDrawable();
            rootBg.setColor(palette.bgPanel);
            rootBg.setCornerRadius(dp(12));
            rootBg.setStroke(Math.max(1, dp(1)), palette.border);
            root.setBackground(rootBg);

            // ---- 顶栏：标题 + ✕ 关闭按钮 ----
            LinearLayout topBar = new LinearLayout(context);
            topBar.setOrientation(LinearLayout.HORIZONTAL);
            topBar.setGravity(Gravity.CENTER_VERTICAL);

            TextView title = new TextView(context);
            title.setText("🐾 桌宠控制");
            title.setTextColor(palette.text);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
            topBar.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView closeBtn = new TextView(context);
            closeBtn.setText("✕");
            closeBtn.setTextColor(palette.textMuted);
            closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            closeBtn.setGravity(Gravity.CENTER);
            closeBtn.setOnClickListener(v -> dismiss());
            topBar.addView(closeBtn, new LinearLayout.LayoutParams(dp(36), dp(36)));
            root.addView(topBar);

            root.addView(hairline());

            // ---- 监控目标 (点按一键切换监控AI · 长按直达Web界面) ----
            root.addView(sectionLabel("🎯 监控 AI · 点按切换 · 长按进入"));

            targetButtons.clear();

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

            root.addView(pairRow(btnPiWeb, btnOperit), rowLp(8));
            root.addView(pairRow(btnClaw, btnRikka), rowLp(8));

            targetButtons.add(btnPiWeb);
            targetButtons.add(btnOperit);
            targetButtons.add(btnClaw);
            targetButtons.add(btnRikka);

            String activeTarget = PetRegistry.getStringPref(context, PetRegistry.KEY_MONITOR_TARGET, PetRegistry.TARGET_PIWEB);
            refreshTargetButtonStyles(activeTarget);

            root.addView(hairline());

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

            TextView prevPetBtn = buildCompactActionBtn("◀", false, v -> stepPet(-1));
            prevPetBtn.setPadding(0, 0, 0, 0);
            prevPetBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));

            petNameTv = new TextView(context);
            updatePetNameLabel();
            petNameTv.setTextColor(palette.text);
            petNameTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            petNameTv.setGravity(Gravity.CENTER);
            petNameTv.setSingleLine(true);
            petNameTv.setEllipsize(TextUtils.TruncateAt.END);
            petNameTv.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            nameLp.leftMargin = dp(8);
            nameLp.rightMargin = dp(8);
            petNameTv.setLayoutParams(nameLp);

            TextView nextPetBtn = buildCompactActionBtn("▶", false, v -> stepPet(1));
            nextPetBtn.setPadding(0, 0, 0, 0);
            nextPetBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));

            petSwitchRow.addView(prevPetBtn);
            petSwitchRow.addView(petNameTv);
            petSwitchRow.addView(nextPetBtn);
            root.addView(petSwitchRow);

            // ---- 尺寸滑块 1: 桌宠大小 (32 ~ 140 dp) ----
            int curPetSize = PetRegistry.getIntPref(context, PetRegistry.KEY_PET_SIZE, PetRegistry.DEFAULT_PET_SIZE);
            curPetSize = Math.max(32, Math.min(140, curPetSize));
            TextView petSizeLabel = sectionLabel("📏 桌宠大小 · " + curPetSize + " dp");
            root.addView(petSizeLabel, rowLp(8));

            SeekBar petSizeBar = new SeekBar(context);
            petSizeBar.setMax(108); // 32 ~ 140
            petSizeBar.setProgress(curPetSize - 32);
            petSizeBar.setPadding(dp(8), dp(6), dp(8), dp(6));
            tintSeekBar(petSizeBar);
            petSizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        int size = progress + 32;
                        petSizeLabel.setText("📏 桌宠大小 · " + size + " dp");
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

            // ---- 尺寸滑块 2: 菜单面板宽度 (240 ~ 320 dp) ----
            TextView menuWidthLabel = sectionLabel("📐 菜单宽度 · " + initialMenuWidth + " dp");
            root.addView(menuWidthLabel, rowLp(4));

            SeekBar menuWidthBar = new SeekBar(context);
            menuWidthBar.setMax(MAX_MENU_WIDTH - MIN_MENU_WIDTH);
            menuWidthBar.setProgress(Math.max(0, initialMenuWidth - MIN_MENU_WIDTH));
            menuWidthBar.setPadding(dp(8), dp(6), dp(8), dp(6));
            tintSeekBar(menuWidthBar);
            menuWidthBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        int width = effectiveMenuWidthDp(progress + MIN_MENU_WIDTH);
                        menuWidthLabel.setText("📐 菜单宽度 · " + width + " dp");
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

            root.addView(hairline());

            // ---- 桌宠专属子代理功能: [🧠 汇报进度] [🗣️ 活跃度] ----
            TextView btnProgress = buildCompactActionBtn("🧠 汇报进度", false, v -> {
                dismiss();
                try {
                    String report = PetMemoryManager.getSystemProgressReport(context);
                    if (service != null) {
                        service.showProactiveBubble(report);
                        if (service.getPetView() != null) service.getPetView().playOneShot("waving");
                    } else if (activity != null) {
                        activity.showPetBubble(report);
                    } else {
                        Toast.makeText(context, report, Toast.LENGTH_LONG).show();
                    }
                } catch (Throwable t) {
                    Log.e("PetMenu", "btnProgress error", t);
                }
            });

            boolean proactiveOn = PetRegistry.getBooleanPref(context, PetMemoryManager.KEY_PROACTIVE_CHAT_ENABLED, true);
            int curMin = PetMemoryManager.getProactiveIntervalMin(context);
            String label = proactiveOn ? "🗣️ 活跃 " + curMin + " 分" : "🗣️ 说话: 关";

            TextView btnProactive = buildCompactActionBtn(label, false, v -> {
                dismiss();
                showActivenessDialog();
            });
            if (proactiveOn) btnProactive.setTextColor(palette.accent);
            root.addView(pairRow(btnProgress, btnProactive));

            // ---- 详细设置 / 指南 ----
            TextView setBtn = buildCompactActionBtn("⚙️ 设置", false, v -> {
                dismiss();
                try {
                    PetParamsDialog.show(context, service, () -> {
                        // 回调更新界面或桌宠状态
                        if (service != null) {
                            service.applyBubbleDimensions();
                        }
                    });
                } catch (Throwable t) {
                    Log.e("PetMenu", "open PetParamsDialog failed", t);
                }
            });

            TextView guideBtn = buildCompactActionBtn("📖 指南", false, v -> {
                dismiss();
                try {
                    new com.xm486.pimet.ui.HelpGuideDialog(context).show();
                } catch (Throwable t) {
                    Log.e("PetMenu", "open HelpGuideDialog failed", t);
                }
            });
            root.addView(pairRow(setBtn, guideBtn), rowLp(8));

            // ---- 关闭桌宠：整行低调危险样式 ----
            TextView stopBtn = buildCompactActionBtn("💤 关闭桌宠", true, v -> {
                dismiss();
                try {
                    PetRegistry.setPetEnabled(context, false);
                    if (service != null) {
                        service.stopSelf();
                    }
                    if (activity != null) {
                        activity.updatePetDisplay(false);
                    }
                } catch (Throwable t) {
                    Log.e("PetMenu", "stopBtn failed", t);
                }
            });
            root.addView(stopBtn, rowLp(8));

            dialog.setContentView(com.xm486.pimet.ui.Icons.install(root));

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
                window.setDimAmount(0f);
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }

            dialog.show();

            if (window != null) {
                WindowManager.LayoutParams lp = window.getAttributes();
                int menuW = dp(initialMenuWidth);
                lp.width = menuW;
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
                lp.alpha = 1f;

                PetOverlayService effectiveService = (service != null) ? service : PetOverlayService.getInstance();
                if (effectiveService != null && effectiveService.getOverlayRoot() != null) {
                    lp.gravity = Gravity.TOP | Gravity.START;
                    int[] pos = effectiveService.getPetScreenPosition();
                    int petX = pos[0];
                    int petY = pos[1];
                    int petW = pos[2];

                    android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
                    int screenW = dm.widthPixels;
                    int screenH = dm.heightPixels;

                    // 若桌宠在屏幕右半边，菜单排在桌宠左侧；若桌宠在左半边，菜单排在桌宠右侧，杜绝遮挡桌宠
                    if (petX + petW / 2 > screenW / 2) {
                        lp.x = Math.max(dp(8), petX - menuW - dp(8));
                    } else {
                        lp.x = Math.min(screenW - menuW - dp(8), petX + petW + dp(8));
                    }

                    // 按实际内容高度防止超出上下屏幕边界
                    int menuH;
                    try {
                        root.measure(View.MeasureSpec.makeMeasureSpec(menuW, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                        menuH = root.getMeasuredHeight();
                    } catch (Throwable t) {
                        menuH = dp(420);
                    }
                    int targetY = Math.max(dp(36), petY - dp(10));
                    int maxAllowedY = screenH - menuH - dp(24);
                    lp.y = Math.min(targetY, Math.max(dp(36), maxAllowedY));
                } else {
                    lp.gravity = Gravity.CENTER;
                }
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
        try {
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
        } catch (Throwable t) {
            Log.e("PetMenu", "openConsole error", t);
        }
    }

    private TextView createTargetButton(String label, String targetKey, View.OnClickListener clk, View.OnLongClickListener lclk) {
        TextView btn = new TextView(context);
        btn.setText(label);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(8), dp(6), dp(8), dp(6));
        btn.setMinHeight(dp(36));
        btn.setMinimumHeight(dp(36));
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
        if (palette == null) palette = ThemeManager.getEffectivePalette(context);
        for (TextView btn : targetButtons) {
            String key = (String) btn.getTag();
            boolean isActive = key != null && key.equals(activeTarget);

            GradientDrawable normal;
            if (isActive) {
                normal = ThemeManager.createSelectedOptionDrawable(context, palette, ThemeManager.RADIUS_CONTROL);
                btn.setTextColor(palette.accent);
                btn.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
                btn.setText("● " + getTargetLabel(key));
            } else {
                normal = ThemeManager.createSecondaryButtonDrawable(context, palette, ThemeManager.RADIUS_CONTROL);
                btn.setTextColor(palette.textMuted);
                btn.setTypeface(Typeface.DEFAULT);
                btn.setText(getTargetLabel(key));
            }

            GradientDrawable pressed = ThemeManager.createSelectedOptionDrawable(context, palette, ThemeManager.RADIUS_CONTROL);

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

    /** 统一按钮：单行 + 末尾省略，最小高度 36dp，扁平中性底色；danger 为低调危险样式。 */
    private TextView buildCompactActionBtn(String text, boolean danger, View.OnClickListener listener) {
        if (palette == null) palette = ThemeManager.getEffectivePalette(context);
        TextView btn = new TextView(context);
        btn.setText(text);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        btn.setTextColor(danger ? ThemeManager.statusDangerColor(palette) : palette.text);
        btn.setGravity(Gravity.CENTER);
        btn.setSingleLine(true);
        btn.setEllipsize(TextUtils.TruncateAt.END);
        btn.setPadding(dp(8), dp(6), dp(8), dp(6));
        btn.setMinHeight(dp(36));
        btn.setMinimumHeight(dp(36));
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setIncludeFontPadding(false);

        GradientDrawable normal = danger
                ? ThemeManager.createDangerButtonDrawable(context, palette, ThemeManager.RADIUS_CONTROL)
                : ThemeManager.createSecondaryButtonDrawable(context, palette, ThemeManager.RADIUS_CONTROL);

        GradientDrawable pressed = ThemeManager.createSelectedOptionDrawable(context, palette, ThemeManager.RADIUS_CONTROL);

        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sld.addState(new int[]{}, normal);

        btn.setBackground(sld);
        btn.setOnClickListener(listener);
        return btn;
    }

    private void showActivenessDialog() {
        final String[] options = new String[]{
                "🌟 话痨模式 (每 1 分钟发言)",
                "😊 适度陪伴 (每 3 分钟发言 - 默认)",
                "🍵 偶尔关怀 (每 8 分钟发言)",
                "🤫 安静守护 (每 15 分钟发言)",
                "🔕 彻底关闭主动说话"
        };
        final int[] minutes = new int[]{1, 3, 8, 15, -1};

        boolean currentOn = PetRegistry.getBooleanPref(context, PetMemoryManager.KEY_PROACTIVE_CHAT_ENABLED, true);
        int currentMin = PetMemoryManager.getProactiveIntervalMin(context);

        int checkedItem = 1;
        if (!currentOn) {
            checkedItem = 4;
        } else {
            for (int i = 0; i < 4; i++) {
                if (minutes[i] == currentMin) {
                    checkedItem = i;
                    break;
                }
            }
        }

        try {
            android.app.AlertDialog alert = new com.xm486.pimet.ui.Icons.DialogBuilder(context)
                    .setTitle("🗣️ 桌宠活跃度与主动说话频率")
                    .setSingleChoiceItems(options, checkedItem, (dialogInterface, which) -> {
                        dialogInterface.dismiss();
                        if (which == 4) {
                            PetRegistry.setBooleanPref(context, PetMemoryManager.KEY_PROACTIVE_CHAT_ENABLED, false);
                            if (service != null) service.stopProactiveChatter();
                            Toast.makeText(context, "已关闭桌宠主动说话", Toast.LENGTH_SHORT).show();
                        } else {
                            int m = minutes[which];
                            PetRegistry.setBooleanPref(context, PetMemoryManager.KEY_PROACTIVE_CHAT_ENABLED, true);
                            PetMemoryManager.setProactiveIntervalMin(context, m);
                            if (service != null) service.startProactiveChatter();
                            Toast.makeText(context, "已设置为: " + options[which], Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .create();

            if (!(context instanceof android.app.Activity)) {
                if (alert.getWindow() != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        alert.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                    } else {
                        alert.getWindow().setType(WindowManager.LayoutParams.TYPE_PHONE);
                    }
                }
            }
            alert.show();
        } catch (Throwable t) {
            Log.e("PetMenu", "showActivenessDialog failed", t);
        }
    }

    public void dismiss() {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
            dialog = null;
        }
    }
}
