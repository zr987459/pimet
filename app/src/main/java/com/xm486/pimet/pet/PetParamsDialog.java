package com.xm486.pimet.pet;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.xm486.pimet.ThemeManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 桌宠二级详细设置弹窗：专门用于调节尺寸、气泡字号与宽度、物理惯性、活跃度与动作。
 * 实时滑动立即生效并持久化，不遮挡桌宠。
 */
public class PetParamsDialog {

    private final Context context;
    private final PetOverlayService service;
    private final Runnable onChangeCallback;
    private AlertDialog dialog;
    private ThemeManager.ThemePalette palette;

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
            palette = ThemeManager.getEffectivePalette(context);
            AlertDialog.Builder builder = new com.xm486.pimet.ui.Icons.DialogBuilder(context);

            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(12), dp(12), dp(12), dp(12));

            // ---- 顶栏：标题 + 返回按钮 ----
            LinearLayout topBar = new LinearLayout(context);
            topBar.setOrientation(LinearLayout.HORIZONTAL);
            topBar.setGravity(Gravity.CENTER_VERTICAL);

            TextView title = new TextView(context);
            title.setText("⚙️ 桌宠详细参数调节");
            title.setTextColor(palette.text);
            title.setTextSize(14f);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setTypeface(com.xm486.pimet.ThemeManager.MEDIUM_TYPEFACE);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            topBar.addView(title, titleLp);

            TextView closeBtn = new TextView(context);
            closeBtn.setText("✕");
            closeBtn.setTextColor(palette.textMuted);
            closeBtn.setTextSize(14f);
            closeBtn.setGravity(Gravity.CENTER);
            closeBtn.setOnClickListener(v -> dismiss());
            topBar.addView(closeBtn, new LinearLayout.LayoutParams(dp(36), dp(36)));
            root.addView(topBar);

            TextView hint = new TextView(context);
            hint.setText("滑动即生效 · 自动保存");
            hint.setTextColor(palette.textMuted);
            hint.setTextSize(12f);
            hint.setSingleLine(true);
            hint.setEllipsize(TextUtils.TruncateAt.END);
            hint.setPadding(0, 0, 0, dp(8));
            root.addView(hint);

            // ---- 可滑动内容区 ----
            ScrollView scroll = new ScrollView(context);
            scroll.setFillViewport(true);

            LinearLayout listLayout = new LinearLayout(context);
            listLayout.setOrientation(LinearLayout.VERTICAL);

            // 0. 主工作区插话与独立专区设置
            listLayout.addView(createInterjectSection());

            // 0.1 手机自动化 (无障碍服务) 授权卡片
            listLayout.addView(createAutomationSection());

            // 0.2 监控绑定桌宠
            listLayout.addView(createBindingSection());

            int curPetSize = PetRegistry.getIntPref(context, PetRegistry.KEY_PET_SIZE, PetRegistry.DEFAULT_PET_SIZE);
            int curBubbleWidth = PetRegistry.getIntPref(context, PetRegistry.KEY_BUBBLE_WIDTH, PetRegistry.DEFAULT_BUBBLE_WIDTH);
            int curBubbleTextSize = PetRegistry.getIntPref(context, PetRegistry.KEY_BUBBLE_TEXT_SIZE, PetRegistry.DEFAULT_BUBBLE_TEXT_SIZE);
            int curCardWidth = PetRegistry.getIntPref(context, PetRegistry.KEY_CARD_WIDTH, PetRegistry.DEFAULT_CARD_WIDTH);
            int curCardScale = PetRegistry.getIntPref(context, PetRegistry.KEY_CARD_SCALE, PetRegistry.DEFAULT_CARD_SCALE);
            int curMenuScale = PetRegistry.getIntPref(context, PetRegistry.KEY_MENU_SCALE, PetRegistry.DEFAULT_MENU_SCALE);

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

            // 10. 长按菜单大小：桌宠宽度的 150% ~ 250%（下限 140dp、上限 220dp）
            listLayout.addView(createCompactSlider("📑 长按菜单大小", Math.max(PetMenu.MIN_MENU_SCALE, Math.min(PetMenu.MAX_MENU_SCALE, curMenuScale)), "%",
                    PetMenu.MIN_MENU_SCALE, PetMenu.MAX_MENU_SCALE, PetMenu.MIN_MENU_SCALE, (val) -> {
                PetRegistry.setIntPref(context, PetRegistry.KEY_MENU_SCALE, val);
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
            btnRow.setGravity(Gravity.CENTER_VERTICAL);
            btnRow.setPadding(0, dp(12), 0, 0);

            Button resetBtn = buildMiniBtn("↺ 重置默认", BTN_SECONDARY, v -> {
                PetRegistry.setIntPref(context, PetRegistry.KEY_PET_SIZE, PetRegistry.DEFAULT_PET_SIZE);
                PetRegistry.setIntPref(context, PetRegistry.KEY_BUBBLE_WIDTH, PetRegistry.DEFAULT_BUBBLE_WIDTH);
                PetRegistry.setIntPref(context, PetRegistry.KEY_BUBBLE_TEXT_SIZE, PetRegistry.DEFAULT_BUBBLE_TEXT_SIZE);
                PetRegistry.setIntPref(context, PetRegistry.KEY_BOUNCE, PetRegistry.DEFAULT_BOUNCE);
                PetRegistry.setIntPref(context, PetRegistry.KEY_FRICTION, PetRegistry.DEFAULT_FRICTION);
                PetRegistry.setIntPref(context, PetRegistry.KEY_FLING_THRESHOLD, PetRegistry.DEFAULT_FLING_THRESHOLD);
                PetRegistry.setIntPref(context, PetRegistry.KEY_STOP_SPEED, PetRegistry.DEFAULT_STOP_SPEED);
                PetRegistry.setIntPref(context, PetRegistry.KEY_CARD_WIDTH, PetRegistry.DEFAULT_CARD_WIDTH);
                PetRegistry.setIntPref(context, PetRegistry.KEY_CARD_SCALE, PetRegistry.DEFAULT_CARD_SCALE);
                PetRegistry.setIntPref(context, PetRegistry.KEY_MENU_SCALE, PetRegistry.DEFAULT_MENU_SCALE);

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

            Button backBtn = buildMiniBtn("✔ 完成", BTN_PRIMARY, v -> {
                dismiss();
                if (onChangeCallback != null) onChangeCallback.run();
            });
            btnRow.addView(backBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            root.addView(btnRow);

            builder.setView(root);
            dialog = builder.create();

            Window window = dialog.getWindow();
            if (window != null) {
                if (service != null || !(context instanceof android.app.Activity)) {
                    window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                }
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                window.setDimAmount(0f);

                // 不透明面板底色 + 发丝描边，杜绝首页内容透出
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(palette.bgPanel);
                bg.setCornerRadius(dp(12));
                bg.setStroke(Math.max(1, dp(1)), palette.border);
                window.setBackgroundDrawable(bg);
            }

            dialog.show();
            if (window != null) {
                WindowManager.LayoutParams a0 = window.getAttributes();
                a0.alpha = 1f;
                window.setAttributes(a0);
            }

            // 定位在桌宠旁 (如果是在 OverlayService 悬浮模式下，绝不遮挡桌宠)
            PetOverlayService effectiveService = (service != null) ? service : PetOverlayService.getInstance();
            if (window != null && effectiveService != null && effectiveService.getOverlayRoot() != null) {
                WindowManager.LayoutParams attrs = window.getAttributes();
                if (attrs != null) {
                    attrs.gravity = Gravity.TOP | Gravity.START;
                    int screenWdp = (int) (context.getResources().getDisplayMetrics().widthPixels
                            / context.getResources().getDisplayMetrics().density);
                    int winW = dp(Math.max(240, Math.min(300, screenWdp - 16)));
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

    private LinearLayout sectionBox() {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(palette.bg);
        bg.setCornerRadius(dp(10));
        bg.setStroke(Math.max(1, dp(1)), palette.border);
        box.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        box.setLayoutParams(lp);
        return box;
    }

    private TextView sectionTitle(String text, int color) {
        TextView title = new TextView(context);
        title.setText(text);
        title.setTextColor(color);
        title.setTextSize(13f);
        title.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        return title;
    }

    private TextView bodyText(String text) {
        TextView tip = new TextView(context);
        tip.setText(text);
        tip.setTextColor(palette.textMuted);
        tip.setTextSize(12f);
        tip.setLineSpacing(dp(2), 1f);
        tip.setPadding(0, dp(4), 0, 0);
        return tip;
    }

    private View createInterjectSection() {
        LinearLayout box = sectionBox();

        box.addView(sectionTitle("💬 pi-btw 支线问答与聊天专区", palette.text));

        box.addView(bodyText("已接入全隔离后台支线问答引擎：\n" +
                "· 正常说话：桌宠专属空间对话（独立好感度与性格记忆）\n" +
                "· 以 . 或 。 开头：pi-btw 支线后台问答，嗅探主工作区上下文独立解答，不影响主进程与长任务。"));

        Button clearPetChatBtn = buildMiniBtn("🧹 重置会话", BTN_SECONDARY, v -> {
            ChatConfig config = ChatConfig.load(context);
            config.piwebSessionId = "";
            config.save(context);
            Toast.makeText(context, "已重置后台问答会话，下次提问将开启全新空间", Toast.LENGTH_SHORT).show();
        });

        Button testInterjectBtn = buildMiniBtn("🔍 嗅探上下文", BTN_SECONDARY, v -> {
            String activeId = PetMemoryManager.getActiveMainSessionId(context);
            if (activeId != null && !activeId.isEmpty()) {
                PetMemoryManager.MainSessionInfo info = PetMemoryManager.getMainSessionInfo(context);
                String cwd = info != null && !info.cwd.isEmpty() ? info.cwd : "默认工作区";
                String task = info != null && !info.lastUserTask.isEmpty() ? info.lastUserTask : "无";
                Toast.makeText(context, "🟢 嗅探到主工作区 (" + cwd + ")\n任务: " + task, Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(context, "🟡 暂无活跃主工作区上下文，在 Web 端发一句话即可自动绑定", Toast.LENGTH_SHORT).show();
            }
        });
        if (testInterjectBtn != null) testInterjectBtn.setTextColor(palette.accent);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams btnLp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnLp2.leftMargin = dp(8);
        row.addView(clearPetChatBtn, btnLp);
        row.addView(testInterjectBtn, btnLp2);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(12);
        box.addView(row, rowLp);

        Button guideBtn = buildMiniBtn("📖 使用指南", BTN_SECONDARY, v -> {
            new com.xm486.pimet.ui.HelpGuideDialog(context).show();
        });
        LinearLayout.LayoutParams guideLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        guideLp.topMargin = dp(8);
        box.addView(guideBtn, guideLp);

        return box;
    }

    private View createAutomationSection() {
        LinearLayout box = sectionBox();

        // 0 未开启 / 1 已开启但未连接（被系统断开，需关掉重开）/ 2 已连接
        int st = com.xm486.pimet.automation.PiMetAccessibilityService.getState(context);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(sectionTitle("📱 手机自动化", palette.text));
        TextView state = new TextView(context);
        state.setText(st == 2 ? "已连接，可用" : st == 1 ? "已开启 · 未连接" : "未开启");
        state.setTextColor(st == 2 ? ThemeManager.statusSuccessColor(palette) : ThemeManager.statusWarningColor(palette));
        state.setTextSize(12f);
        state.setSingleLine(true);
        state.setEllipsize(TextUtils.TruncateAt.END);
        state.setPadding(0, dp(2), 0, 0);
        texts.addView(state);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button authBtn = buildMiniBtn(st == 2 ? "设置" : st == 1 ? "去重开" : "去开启", st == 2 ? BTN_SECONDARY : BTN_PRIMARY, v -> {
            com.xm486.pimet.automation.PiMetAccessibilityService.openAccessibilitySettings(context);
            Toast.makeText(context, st == 1
                    ? "找到「PiMet 手机自动化」，先关闭再重新打开"
                    : "在「已下载的应用」中找到「PiMet 手机自动化」并开启", Toast.LENGTH_LONG).show();
        });
        LinearLayout.LayoutParams authLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        authLp.leftMargin = dp(8);
        authBtn.setMinWidth(dp(88));
        authBtn.setMinimumWidth(dp(88));
        row.addView(authBtn, authLp);
        box.addView(row);

        // 从系统设置返回后状态会变：面板显示期间每 1.5 秒刷新一次状态文字（离开窗口即停止）
        Runnable[] poll = new Runnable[1];
        poll[0] = () -> {
            if (!state.isAttachedToWindow()) return;
            int now = com.xm486.pimet.automation.PiMetAccessibilityService.getState(context);
            state.setText(now == 2 ? "已连接，可用" : now == 1 ? "已开启 · 未连接" : "未开启");
            state.setTextColor(now == 2 ? ThemeManager.statusSuccessColor(palette) : ThemeManager.statusWarningColor(palette));
            state.postDelayed(poll[0], 1500);
        };
        state.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { v.removeCallbacks(poll[0]); v.postDelayed(poll[0], 1500); }
            @Override public void onViewDetachedFromWindow(View v) { v.removeCallbacks(poll[0]); }
        });

        if (st == 2) {
            box.addView(bodyText("桌宠与 AI 可通过 phone_control 读屏、点击、滑动、输入、启动应用和截图。"));
        } else if (st == 1) {
            box.addView(bodyText("服务被系统断开了（常见于更新应用或后台清理后）：在无障碍里关闭再打开即可恢复，并建议允许自启动、电池无限制。"));
        } else {
            box.addView(bodyText("开启后桌宠与 AI 可通过 phone_control 自动操作手机。开关是灰色的？到「应用信息」右上角 ⋮ 选「允许受限制的设置」后再开启。"));
            Button infoBtn = buildMiniBtn("打开应用信息", BTN_SECONDARY, v ->
                    com.xm486.pimet.automation.PiMetAccessibilityService.openAppDetails(context));
            LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            infoLp.topMargin = dp(8);
            box.addView(infoBtn, infoLp);
        }

        return box;
    }

    private View createBindingSection() {
        LinearLayout box = sectionBox();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(sectionTitle("监控绑定桌宠", palette.text));
        int n = 0;
        for (String[] t : PetRegistry.BINDABLE_TARGETS) {
            if (PetRegistry.getBoundPet(context, t[0]) != null) n++;
        }
        TextView state = new TextView(context);
        state.setText(n > 0 ? "已绑定 " + n + " 个监控" : "未绑定");
        state.setTextColor(n > 0 ? palette.accent : palette.textMuted);
        state.setTextSize(12f);
        state.setSingleLine(true);
        state.setEllipsize(TextUtils.TruncateAt.END);
        state.setPadding(0, dp(2), 0, 0);
        texts.addView(state);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button setBtn = buildMiniBtn("设置", BTN_SECONDARY, v -> {
            dismiss();
            PetBindingDialog.show(context);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        setBtn.setMinWidth(dp(72));
        setBtn.setMinimumWidth(dp(72));
        row.addView(setBtn, lp);
        box.addView(row);
        box.addView(bodyText("每个监控可绑一只桌宠，切到哪个监控就换成哪只；不绑定则保持当前桌宠。"));
        return box;
    }

    private interface OnSliderChange {
        void onChange(int val);
    }

    private View createCompactSlider(String name, int curVal, String unit,
                                     int minVal, int maxVal, int offset, OnSliderChange callback) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        TextView label = new TextView(context);
        label.setText(name + " · " + curVal + unit);
        label.setTextColor(palette.text);
        label.setTextSize(12f);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setPadding(0, 0, 0, 0);
        row.addView(label);

        SeekBar sb = new SeekBar(context);
        sb.setMax(maxVal - offset);
        sb.setProgress(Math.max(0, curVal - offset));
        sb.setPadding(dp(8), dp(6), dp(8), dp(6));
        try {
            android.content.res.ColorStateList acc = android.content.res.ColorStateList.valueOf(palette.accent);
            sb.setProgressTintList(acc);
            sb.setThumbTintList(acc);
            sb.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(palette.border));
        } catch (Throwable ignored) {}

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

    private static final int BTN_SECONDARY = 0;
    private static final int BTN_PRIMARY = 1;

    /** 统一按钮：单行 + 末尾省略，最小高度 36dp，不全大写、无阴影。 */
    private Button buildMiniBtn(String text, int style, View.OnClickListener clk) {
        Button btn = new Button(context);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(12f);
        btn.setSingleLine(true);
        btn.setEllipsize(TextUtils.TruncateAt.END);
        btn.setGravity(Gravity.CENTER);
        btn.setIncludeFontPadding(false);
        btn.setPadding(dp(10), dp(6), dp(10), dp(6));
        btn.setMinHeight(dp(36));
        btn.setMinimumHeight(dp(36));
        btn.setMinWidth(0);
        btn.setMinimumWidth(0);
        btn.setStateListAnimator(null);

        boolean primary = style == BTN_PRIMARY;
        btn.setTextColor(primary ? palette.accentContrast : palette.text);
        GradientDrawable n = primary
                ? ThemeManager.createPrimaryButtonDrawable(context, palette, ThemeManager.RADIUS_CONTROL)
                : ThemeManager.createSecondaryButtonDrawable(context, palette, ThemeManager.RADIUS_CONTROL);
        GradientDrawable p = primary
                ? ThemeManager.createPrimaryButtonDrawable(context, palette, ThemeManager.RADIUS_CONTROL)
                : ThemeManager.createSelectedOptionDrawable(context, palette, ThemeManager.RADIUS_CONTROL);
        if (primary) p.setColor(palette.accentHover);

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
