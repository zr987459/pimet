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

    /**
     * 菜单尺寸：跟随桌宠当前显示宽度，约为桌宠宽 × 倍数（默认 1.8×，可在设置里调 150–250%），
     * 下限 140dp、上限 220dp，且不超过屏宽。按钮为纯图标网格，大字号下也不换行。
     */
    public static final int MIN_MENU_SCALE = 150;
    public static final int MAX_MENU_SCALE = 250;
    public static final int MIN_MENU_WIDTH_DP = 140;
    public static final int MAX_MENU_WIDTH_DP = 220;
    private static final int CELL_DP = 32;
    private static final int GAP_DP = 5;
    private static final int PAD_DP = 8;

    private ThemeManager.ThemePalette palette;
    private LinearLayout rootView;
    private final android.os.Handler followHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private int[] lastPetRect;
    private final Runnable followRunnable = new Runnable() {
        @Override
        public void run() {
            if (dialog == null || !dialog.isShowing()) return;
            int[] r = currentPetRect();
            if (r != null && (lastPetRect == null || !java.util.Arrays.equals(r, lastPetRect))) {
                positionWindow();
            }
            followHandler.postDelayed(this, 200);
        }
    };

    /** 桌宠当前显示宽度（dp）。 */
    private int petWidthDp() {
        PetOverlayService s = service != null ? service : PetOverlayService.getInstance();
        float density = context.getResources().getDisplayMetrics().density;
        if (s != null && s.getPetView() != null && s.getPetView().getWidth() > 0) {
            return Math.round(s.getPetView().getWidth() / density);
        }
        return PetRegistry.getIntPref(context, PetRegistry.KEY_PET_SIZE, PetRegistry.DEFAULT_PET_SIZE);
    }

    /** 菜单宽度（dp）：桌宠宽 × 倍数，夹在 140–220dp 且不超出屏幕。 */
    private int menuWidthDp() {
        int scale = PetRegistry.getIntPref(context, PetRegistry.KEY_MENU_SCALE, PetRegistry.DEFAULT_MENU_SCALE);
        scale = Math.max(MIN_MENU_SCALE, Math.min(MAX_MENU_SCALE, scale));
        int w = Math.round(petWidthDp() * scale / 100f);
        w = Math.max(MIN_MENU_WIDTH_DP, Math.min(MAX_MENU_WIDTH_DP, w));
        android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int screenDp = (int) (dm.widthPixels / dm.density);
        return Math.max(120, Math.min(w, screenDp - 16));
    }

    /** 桌宠在屏幕上的实际矩形 [x, y, w, h]（像素）；无悬浮桌宠时返回 null。 */
    private int[] currentPetRect() {
        PetOverlayService s = service != null ? service : PetOverlayService.getInstance();
        if (s == null || s.getOverlayRoot() == null) return null;
        return s.getPetViewScreenRect();
    }

    private void tintSeekBar(SeekBar sb) {
        try {
            android.content.res.ColorStateList acc = android.content.res.ColorStateList.valueOf(palette.accent);
            sb.setProgressTintList(acc);
            sb.setThumbTintList(acc);
            sb.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(palette.border));
        } catch (Throwable ignored) {}
    }

    private StateListDrawable buttonBg(boolean danger, boolean selected) {
        GradientDrawable normal = danger
                ? ThemeManager.createDangerButtonDrawable(context, palette, ThemeManager.RADIUS_CONTROL)
                : selected
                ? ThemeManager.createSelectedOptionDrawable(context, palette, ThemeManager.RADIUS_CONTROL)
                : ThemeManager.createSecondaryButtonDrawable(context, palette, ThemeManager.RADIUS_CONTROL);
        GradientDrawable pressed = ThemeManager.createSelectedOptionDrawable(context, palette, ThemeManager.RADIUS_CONTROL);
        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sld.addState(new int[]{}, normal);
        return sld;
    }

    /** 纯图标按钮：32dp 触控区，长按显示文字提示（tooltip）。 */
    private android.widget.ImageView iconBtn(int iconRes, String tip, int tint, boolean danger,
                                             View.OnClickListener listener) {
        android.widget.ImageView iv = new android.widget.ImageView(context);
        iv.setImageResource(iconRes);
        iv.setColorFilter(tint, android.graphics.PorterDuff.Mode.SRC_IN);
        iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        iv.setPadding(dp(7), dp(7), dp(7), dp(7));
        iv.setBackground(buttonBg(danger, false));
        iv.setContentDescription(tip);
        iv.setTooltipText(tip);
        iv.setClickable(true);
        iv.setFocusable(true);
        iv.setOnClickListener(listener);
        return iv;
    }

    /** 等宽网格：按可用宽度自动决定列数（每格约 32dp，间距 5dp），空位补占位。 */
    private LinearLayout grid(List<View> cells, int innerWidthDp, int cellHeightDp, int minCols, int maxCols) {
        int cols = (innerWidthDp + GAP_DP) / (CELL_DP + GAP_DP);
        cols = Math.max(minCols, Math.min(maxCols, Math.min(cols, cells.size())));
        LinearLayout g = new LinearLayout(context);
        g.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < cells.size(); i += cols) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < cols; c++) {
                int idx = i + c;
                View v = idx < cells.size() ? cells.get(idx) : new View(context);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(cellHeightDp), 1f);
                if (c > 0) lp.leftMargin = dp(GAP_DP);
                row.addView(v, lp);
            }
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) rlp.topMargin = dp(GAP_DP);
            g.addView(row, rlp);
        }
        return g;
    }

    private LinearLayout.LayoutParams rowLp(int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topMarginDp);
        return lp;
    }

    private TextView glyphBtn(String glyph, View.OnClickListener l) {
        TextView tv = new TextView(context);
        tv.setText(glyph);
        tv.setTextColor(palette.text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        tv.setGravity(Gravity.CENTER);
        tv.setIncludeFontPadding(false);
        tv.setBackground(buttonBg(false, false));
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setOnClickListener(l);
        return tv;
    }

    public void show() {
        if (dialog != null && dialog.isShowing()) return;

        try {
            palette = ThemeManager.getEffectivePalette(context);
            dialog = new Dialog(context);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            final int menuWDp = menuWidthDp();
            final int innerDp = menuWDp - PAD_DP * 2;

            LinearLayout root = new LinearLayout(context);
            rootView = root;
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(PAD_DP), dp(PAD_DP), dp(PAD_DP), dp(PAD_DP));

            // 不透明面板底色 + 发丝描边
            GradientDrawable rootBg = new GradientDrawable();
            rootBg.setColor(palette.bgPanel);
            rootBg.setCornerRadius(dp(12));
            rootBg.setStroke(Math.max(1, dp(1)), palette.border);
            root.setBackground(rootBg);

            // ---- 顶行：[‹] 角色名 [›] [✕] ----
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
            LinearLayout topRow = new LinearLayout(context);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView prevPetBtn = glyphBtn("‹", v -> stepPet(-1));
            prevPetBtn.setTooltipText("上一个角色");
            petNameTv = new TextView(context);
            updatePetNameLabel();
            petNameTv.setTextColor(palette.text);
            petNameTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
            petNameTv.setGravity(Gravity.CENTER);
            petNameTv.setSingleLine(true);
            petNameTv.setEllipsize(TextUtils.TruncateAt.END);
            petNameTv.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
            TextView nextPetBtn = glyphBtn("›", v -> stepPet(1));
            nextPetBtn.setTooltipText("下一个角色");
            android.widget.ImageView closeBtn = iconBtn(com.xm486.pimet.R.drawable.ic_close, "收起菜单",
                    palette.textMuted, false, v -> dismiss());
            closeBtn.setBackground(null);

            topRow.addView(prevPetBtn, new LinearLayout.LayoutParams(dp(26), dp(28)));
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            nameLp.leftMargin = dp(4);
            nameLp.rightMargin = dp(4);
            topRow.addView(petNameTv, nameLp);
            topRow.addView(nextPetBtn, new LinearLayout.LayoutParams(dp(26), dp(28)));
            LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(dp(28), dp(28));
            closeLp.leftMargin = dp(4);
            topRow.addView(closeBtn, closeLp);
            root.addView(topRow);

            // ---- 监控目标 2×2：点按切换 · 长按进入 ----
            targetButtons.clear();
            String[][] targets = {
                    {"pi-web", PetRegistry.TARGET_PIWEB},
                    {"Operit", PetRegistry.TARGET_OPERIT},
                    {"Claw", PetRegistry.TARGET_CLAWBENCH},
                    {"Rikka", PetRegistry.TARGET_RIKKA},
            };
            List<View> targetCells = new ArrayList<>();
            for (String[] t : targets) {
                final String key = t[1];
                TextView b = createTargetButton(t[0], key, v -> switchTarget(key), v -> {
                    openConsole(key);
                    return true;
                });
                targetButtons.add(b);
                targetCells.add(b);
            }
            root.addView(grid(targetCells, innerDp, 28, 2, 2), rowLp(6));
            String activeTarget = PetRegistry.getStringPref(context, PetRegistry.KEY_MONITOR_TARGET, PetRegistry.TARGET_PIWEB);
            refreshTargetButtonStyles(activeTarget);

            // ---- 桌宠大小滑块（32 ~ 140 dp），菜单随之缩放并贴紧桌宠 ----
            int curPetSize = PetRegistry.getIntPref(context, PetRegistry.KEY_PET_SIZE, PetRegistry.DEFAULT_PET_SIZE);
            curPetSize = Math.max(32, Math.min(140, curPetSize));
            LinearLayout sizeRow = new LinearLayout(context);
            sizeRow.setOrientation(LinearLayout.HORIZONTAL);
            sizeRow.setGravity(Gravity.CENTER_VERTICAL);
            android.widget.ImageView rulerIc = new android.widget.ImageView(context);
            rulerIc.setImageResource(com.xm486.pimet.R.drawable.ic_ruler);
            rulerIc.setColorFilter(palette.textMuted, android.graphics.PorterDuff.Mode.SRC_IN);
            rulerIc.setTooltipText("桌宠大小");
            sizeRow.addView(rulerIc, new LinearLayout.LayoutParams(dp(14), dp(14)));
            SeekBar petSizeBar = new SeekBar(context);
            petSizeBar.setMax(108); // 32 ~ 140
            petSizeBar.setProgress(curPetSize - 32);
            petSizeBar.setPadding(dp(8), dp(4), dp(8), dp(4));
            tintSeekBar(petSizeBar);
            sizeRow.addView(petSizeBar, new LinearLayout.LayoutParams(0, dp(24), 1f));
            TextView sizeVal = new TextView(context);
            sizeVal.setText(String.valueOf(curPetSize));
            sizeVal.setTextColor(palette.textMuted);
            sizeVal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
            sizeVal.setSingleLine(true);
            sizeRow.addView(sizeVal);
            petSizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        int size = progress + 32;
                        sizeVal.setText(String.valueOf(size));
                        PetRegistry.setIntPref(context, PetRegistry.KEY_PET_SIZE, size);
                        if (service != null) {
                            service.applyPetSize(size);
                        }
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {
                    // 松手后按新的桌宠尺寸重算菜单宽度与位置
                    if (rootView != null) rootView.post(() -> positionWindow());
                }
            });
            root.addView(sizeRow, rowLp(4));

            // ---- 操作图标网格：汇报进度 · 活跃度 · 设置 · 指南 · 关闭桌宠 ----
            List<View> actions = new ArrayList<>();
            actions.add(iconBtn(com.xm486.pimet.R.drawable.ic_brain, "汇报进度", palette.text, false, v -> {
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
            }));

            boolean proactiveOn = PetRegistry.getBooleanPref(context, PetMemoryManager.KEY_PROACTIVE_CHAT_ENABLED, true);
            int curMin = PetMemoryManager.getProactiveIntervalMin(context);
            String proTip = proactiveOn ? "主动说话 · 每 " + curMin + " 分" : "主动说话 · 关";
            actions.add(iconBtn(proactiveOn ? com.xm486.pimet.R.drawable.ic_megaphone : com.xm486.pimet.R.drawable.ic_bell_off,
                    proTip, proactiveOn ? palette.accent : palette.textMuted, false, v -> {
                        dismiss();
                        showActivenessDialog();
                    }));

            actions.add(iconBtn(com.xm486.pimet.R.drawable.ic_settings, "设置", palette.text, false, v -> {
                dismiss();
                try {
                    PetParamsDialog.show(context, service, () -> {
                        if (service != null) {
                            service.applyBubbleDimensions();
                        }
                    });
                } catch (Throwable t) {
                    Log.e("PetMenu", "open PetParamsDialog failed", t);
                }
            }));

            actions.add(iconBtn(com.xm486.pimet.R.drawable.ic_book, "指南", palette.text, false, v -> {
                dismiss();
                try {
                    new com.xm486.pimet.ui.HelpGuideDialog(context).show();
                } catch (Throwable t) {
                    Log.e("PetMenu", "open HelpGuideDialog failed", t);
                }
            }));

            // 关闭桌宠：低调危险样式（极淡危险底 + 危险色图标）
            actions.add(iconBtn(com.xm486.pimet.R.drawable.ic_power, "关闭桌宠",
                    ThemeManager.statusDangerColor(palette), true, v -> {
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
                    }));
            root.addView(grid(actions, innerDp, CELL_DP, 3, 5), rowLp(6));

            dialog.setContentView(com.xm486.pimet.ui.Icons.install(root));
            dialog.setOnDismissListener(d -> followHandler.removeCallbacks(followRunnable));

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
            positionWindow();
            followHandler.removeCallbacks(followRunnable);
            followHandler.postDelayed(followRunnable, 200);

        } catch (Throwable t) {
            Toast.makeText(context, "打开菜单失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 让菜单贴在桌宠旁边：优先右侧，右侧放不下则左侧（都放不下取空间较大的一侧并夹在屏内），
     * 竖向与桌宠中线对齐并限制在屏幕内。桌宠移动/缩放后重复调用即可跟随。
     */
    private void positionWindow() {
        if (dialog == null || dialog.getWindow() == null || rootView == null) return;
        Window window = dialog.getWindow();
        WindowManager.LayoutParams lp = window.getAttributes();
        int menuW = dp(menuWidthDp());
        lp.width = menuW;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.alpha = 1f;

        int[] pet = currentPetRect();
        lastPetRect = pet;
        if (pet == null) {
            lp.gravity = Gravity.CENTER;
            window.setAttributes(lp);
            return;
        }
        lp.gravity = Gravity.TOP | Gravity.START;
        int petX = pet[0], petY = pet[1], petW = pet[2], petH = pet[3];
        android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;
        int gap = dp(6);
        int edge = dp(4);

        int rightRoom = screenW - (petX + petW) - gap - edge;
        int leftRoom = petX - gap - edge;
        int x;
        if (rightRoom >= menuW) {
            x = petX + petW + gap;
        } else if (leftRoom >= menuW) {
            x = petX - gap - menuW;
        } else if (rightRoom >= leftRoom) {
            x = screenW - menuW - edge;
        } else {
            x = edge;
        }
        lp.x = Math.max(edge, Math.min(x, screenW - menuW - edge));

        int menuH;
        try {
            rootView.measure(View.MeasureSpec.makeMeasureSpec(menuW, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            menuH = rootView.getMeasuredHeight();
        } catch (Throwable t) {
            menuH = dp(220);
        }
        int y = petY + petH / 2 - menuH / 2;
        int minY = dp(28);
        int maxY = Math.max(minY, screenH - menuH - dp(24));
        lp.y = Math.max(minY, Math.min(y, maxY));
        window.setAttributes(lp);
    }

    private void switchTarget(String targetKey) {
        PetRegistry.setStringPref(context, PetRegistry.KEY_MONITOR_TARGET, targetKey);
        // 切到该监控绑定的桌宠（未绑定保持当前），并同步菜单里的角色名
        PetRegistry.applyTargetPet(context, targetKey);
        syncPetIndex();
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
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(4), 0, dp(4), 0);
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
            } else {
                normal = ThemeManager.createSecondaryButtonDrawable(context, palette, ThemeManager.RADIUS_CONTROL);
                btn.setTextColor(palette.textMuted);
                btn.setTypeface(Typeface.DEFAULT);
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

    private void syncPetIndex() {
        if (allPets == null) return;
        String cur = PetRegistry.getPetDir(context);
        for (int i = 0; i < allPets.size(); i++) {
            if (allPets.get(i).dir.equals(cur)) {
                currentPetIndex = i;
                break;
            }
        }
        updatePetNameLabel();
    }

    private void updatePetNameLabel() {
        if (petNameTv == null || allPets == null || allPets.isEmpty()) return;
        petNameTv.setText(allPets.get(currentPetIndex).displayName);
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
        followHandler.removeCallbacks(followRunnable);
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
            dialog = null;
        }
    }
}
