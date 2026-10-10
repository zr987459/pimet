package com.xm486.pimet.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.xm486.pimet.R;
import com.xm486.pimet.monitor.OperitState;

/**
 * 状态卡（AI 对话卡）：点击桌宠展开。
 *
 * 设计参考市面成熟 AI 桌宠（pet-assistant / CoPet / vibe-pet）：
 *   - 顶栏极简：一个状态点 + 模式名，不堆信息
 *   - 快捷短语 chips：一键开聊，降低对话门槛（这是桌宠交互的核心）
 *   - 输入框圆角化，发送按钮用主题色胶囊
 *
 * 卡片高度由内容决定，配合长按菜单的「窗口宽度/紧凑度」滑块可进一步缩小。
 */
public class StatusCardView extends LinearLayout {

    private TextView stateDot;      // 状态点（彩色圆点，不占空间）
    private TextView stateLabel;    // 状态文字
    private TextView modeView;      // 当前 AI 模式标签
    private EditText chatInput;     // 输入框
    private Button sendButton;      // 发送按钮
    private Button attachButton;    // 附件/相册导入按钮
    private OnClickListener chipClickListener;

    // ---- 详细监控区域视图 ----
    private LinearLayout monitorDetailContainer;
    private TextView monitorTargetTv;
    private TextView monitorActionTv;
    private TextView monitorMetricsTv;

    /** 聊天处理阶段：active 时顶栏显示 chatPhaseText（细粒度：思考中/工具/搜索/回复中），
     * 让位给监控文案；endChatPhase 后恢复监控驱动 */
    private volatile boolean chatPhaseActive = false;
    private volatile String chatPhaseText = "";

    public StatusCardView(Context context) {
        super(context);
        init(context);
    }

    private void init(Context context) {
        setOrientation(VERTICAL);
        setPadding(dp(12), dp(10), dp(12), dp(12));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color(R.color.overlay_card_bg));
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), 0x33FFFFFF);
        setBackground(bg);

        // ---- 顶栏：状态点 + 状态文字 + 模式标签 ----
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        addView(header, new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT));

        stateDot = new TextView(context);
        stateDot.setTextSize(10f);
        stateDot.setText("●");
        header.addView(stateDot, new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT));

        stateLabel = new TextView(context);
        stateLabel.setTextSize(11f);
        stateLabel.setMaxLines(1);
        stateLabel.setEllipsize(TextUtils.TruncateAt.END);
        stateLabel.setPadding(dp(4), 0, 0, 0);
        header.addView(stateLabel, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        modeView = new TextView(context);
        modeView.setTextSize(10f);
        modeView.setTextColor(color(R.color.overlay_text_secondary));
        modeView.setMaxLines(1);
        modeView.setPadding(dp(8), 0, 0, 0);
        header.addView(modeView, new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT));

        // ---- 详细监控区（位于状态顶栏与聊天输入框之间） ----
        monitorDetailContainer = new LinearLayout(context);
        monitorDetailContainer.setOrientation(VERTICAL);
        GradientDrawable detBg = new GradientDrawable();
        detBg.setColor(0x24000000);
        detBg.setCornerRadius(dp(8));
        detBg.setStroke(dp(1), 0x22FFFFFF);
        monitorDetailContainer.setBackground(detBg);
        monitorDetailContainer.setPadding(dp(8), dp(5), dp(8), dp(5));

        LinearLayout line1 = new LinearLayout(context);
        line1.setOrientation(HORIZONTAL);
        line1.setGravity(Gravity.CENTER_VERTICAL);

        monitorTargetTv = new TextView(context);
        monitorTargetTv.setTextSize(10f);
        monitorTargetTv.setTextColor(0xFF38BDF8);
        monitorTargetTv.setMaxLines(1);
        monitorTargetTv.setEllipsize(TextUtils.TruncateAt.END);
        monitorTargetTv.setText("🎯 监控: pi-web");
        line1.addView(monitorTargetTv, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        monitorMetricsTv = new TextView(context);
        monitorMetricsTv.setTextSize(9.5f);
        monitorMetricsTv.setTextColor(0xAAFFFFFF);
        monitorMetricsTv.setMaxLines(1);
        line1.addView(monitorMetricsTv, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        monitorDetailContainer.addView(line1);

        monitorActionTv = new TextView(context);
        monitorActionTv.setTextSize(10.5f);
        monitorActionTv.setTextColor(0xEEF8FAFC);
        monitorActionTv.setMaxLines(2);
        monitorActionTv.setEllipsize(TextUtils.TruncateAt.END);
        monitorActionTv.setPadding(0, dp(2), 0, 0);
        monitorActionTv.setText("⚡ 服务正常运行，守护待命");
        monitorDetailContainer.addView(monitorActionTv);

        LayoutParams detLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        detLp.topMargin = dp(6);
        addView(monitorDetailContainer, detLp);

        // ---- 输入框 + 发送按钮 ----
        LinearLayout inputRow = new LinearLayout(context);
        inputRow.setOrientation(HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);

        Button fullScreenBtn = new Button(context);
        fullScreenBtn.setText("⛶");
        fullScreenBtn.setTextSize(15f);
        fullScreenBtn.setTextColor(0xFF38BDF8);
        fullScreenBtn.setPadding(0, 0, 0, 0);
        fullScreenBtn.setMinHeight(0);
        fullScreenBtn.setMinWidth(0);
        GradientDrawable fsBg = new GradientDrawable();
        fsBg.setColor(0x2238BDF8);
        fsBg.setCornerRadius(dp(12));
        fullScreenBtn.setBackground(fsBg);
        fullScreenBtn.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(context, com.xm486.pimet.MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                intent.putExtra("pimet.toggle_web_fullscreen", true);
                context.startActivity(intent);
                if (context instanceof com.xm486.pimet.pet.PetOverlayService) {
                    ((com.xm486.pimet.pet.PetOverlayService) context).toggleCard();
                }
            } catch (Throwable ignored) {}
        });
        LayoutParams fsLp = new LayoutParams(dp(36), dp(36));
        fsLp.rightMargin = dp(6);
        inputRow.addView(fullScreenBtn, fsLp);

        chatInput = new EditText(context);
        chatInput.setHint("和我说说话…");
        chatInput.setHintTextColor(0x77FFFFFF);
        chatInput.setTextColor(color(R.color.overlay_text_primary));
        chatInput.setTextSize(12f);
        chatInput.setSingleLine(true);
        chatInput.setMaxLines(1);
        chatInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        chatInput.setInputType(InputType.TYPE_CLASS_TEXT);

        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(0x22FFFFFF);
        inputBg.setCornerRadius(dp(12));
        inputBg.setStroke(dp(1), 0x22FFFFFF);
        chatInput.setBackground(inputBg);
        int padH = dp(12);
        chatInput.setPadding(padH, dp(4), padH, dp(4));
        inputRow.addView(chatInput, new LayoutParams(0, dp(36), 1f));

        sendButton = new Button(context);
        sendButton.setText("发送");
        sendButton.setTextColor(0xFFFFFFFF);
        sendButton.setTextSize(12f);
        sendButton.setTypeface(Typeface.DEFAULT_BOLD);
        sendButton.setAllCaps(false);

        android.graphics.drawable.StateListDrawable btnStates = new android.graphics.drawable.StateListDrawable();
        GradientDrawable normalBtn = new GradientDrawable();
        normalBtn.setColor(0xFF3B82F6);
        normalBtn.setCornerRadius(dp(12));
        GradientDrawable pressedBtn = new GradientDrawable();
        pressedBtn.setColor(0xFF2563EB);
        pressedBtn.setCornerRadius(dp(12));
        btnStates.addState(new int[]{android.R.attr.state_pressed}, pressedBtn);
        btnStates.addState(new int[]{}, normalBtn);
        sendButton.setBackground(btnStates);

        sendButton.setIncludeFontPadding(false);
        sendButton.setPadding(dp(8), 0, dp(8), 0);
        sendButton.setMinHeight(0);
        sendButton.setMinWidth(0);
        LayoutParams btnLp = new LayoutParams(dp(54), dp(36));
        btnLp.leftMargin = dp(6);
        inputRow.addView(sendButton, btnLp);

        LayoutParams inputLp = new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT);
        inputLp.topMargin = dp(6);
        addView(inputRow, inputLp);
    }

    /** 设置快捷短语点击监听（Service 里接到发送逻辑） */
    public void setOnChipClickListener(OnClickListener listener) {
        this.chipClickListener = listener;
    }

    /** 用最新快照刷新状态点与中间详细监控 */
    public void update(OperitState.Snapshot snap) {
        if (snap == null) return;

        // 1. 刷新顶栏简要状态
        OperitState s = snap.state != null ? snap.state : OperitState.UNKNOWN;
        if (chatPhaseActive) {
            if (s != OperitState.IDLE && s != OperitState.UNKNOWN) {
                setChatPhase(s.getEmoji() + " " + s.getLabel());
            }
        } else {
            stateLabel.setText(s.getLabel());
            int c = StateStyle.overlayColor(getContext(), s);
            stateDot.setTextColor(c);
            stateLabel.setTextColor(c);
            stateLabel.setTypeface(Typeface.DEFAULT_BOLD);
        }

        // 2. 刷新中间详细监控视图
        if (monitorTargetTv != null) {
            String target = (snap.agentName != null && !snap.agentName.isEmpty()) ? snap.agentName : "pi-web";
            if (snap.model != null && !snap.model.isEmpty()) {
                target += " · " + snap.model;
            }
            monitorTargetTv.setText("🎯 " + target);
        }

        if (monitorMetricsTv != null) {
            String metrics = "";
            if (snap.inputTokens > 0 || snap.outputTokens > 0) {
                metrics = "↑" + snap.inputTokens + " ↓" + snap.outputTokens;
            } else if (snap.lastActiveTime > 0) {
                long diffSec = Math.max(0, (System.currentTimeMillis() - snap.lastActiveTime) / 1000);
                metrics = (diffSec < 60) ? (diffSec + "s前") : ((diffSec / 60) + "m前");
            }
            monitorMetricsTv.setText(metrics);
        }

        if (monitorActionTv != null) {
            if (chatPhaseActive && !chatPhaseText.isEmpty()) {
                monitorActionTv.setText("💬 " + chatPhaseText);
            } else {
                String action;
                if (s == OperitState.TOOL_RUNNING) {
                    action = "🔧 工具执行: " + (snap.lastTool != null && !snap.lastTool.isEmpty() ? snap.lastTool : "执行指令中...");
                } else if (s == OperitState.THINKING) {
                    action = "🤔 正在推理分析与规划任务...";
                } else if (s == OperitState.RESPONDING) {
                    action = "💬 正在组织流式回复...";
                } else if (s == OperitState.WORKING) {
                    action = "⚡ 正在执行后台任务...";
                } else if (s == OperitState.WAITING) {
                    action = "⏳ 等待任务与依赖返回...";
                } else if (s == OperitState.ERROR) {
                    action = "❌ 发生异常，请检查控制台输出";
                } else if (s == OperitState.IDLE) {
                    action = snap.operitRunning ? "😴 进程待命中 · 容器环境正常" : "💤 空闲待命";
                } else {
                    action = "🔌 状态监听中...";
                }
                if (snap.recentEvents != null && !snap.recentEvents.isEmpty() && s != OperitState.IDLE) {
                    String latestEvent = snap.recentEvents.get(0);
                    if (latestEvent != null && !latestEvent.trim().isEmpty()) {
                        action += " (" + latestEvent.trim() + ")";
                    }
                }
                monitorActionTv.setText(action);
            }
        }
    }

    /** 聊天开始：顶栏切到细粒度阶段（思考中/工具执行中/搜索中/回复中…），让位监控 */
    public void startChatPhase(String phaseText) {
        chatPhaseActive = true;
        chatPhaseText = phaseText == null ? "" : phaseText;
        stateLabel.setText(chatPhaseText);
        stateDot.setTextColor(color(R.color.overlay_text_primary));
        stateLabel.setTextColor(color(R.color.overlay_text_primary));
        stateLabel.setTypeface(Typeface.DEFAULT_BOLD);
    }

    /** 更新聊天阶段文案（请求过程中可多次调用：思考中→工具执行中→回复中） */
    public void setChatPhase(String phaseText) {
        if (!chatPhaseActive) return;
        chatPhaseText = phaseText == null ? "" : phaseText;
        stateLabel.setText(chatPhaseText);
    }

    /** 聊天是否处于阶段态（顶栏被细粒度阶段文案占用） */
    public boolean isChatPhaseActive() {
        return chatPhaseActive;
    }

    /** 聊天结束：恢复监控驱动的状态刷新（立刻重画一次当前监控快照） */
    public void endChatPhase() {
        chatPhaseActive = false;
        chatPhaseText = "";
    }

    /** 当前 AI 模式标签 */
    public void setModeLabel(String label) {
        modeView.setText(label);
    }

    public EditText getChatInput() {
        return chatInput;
    }

    public Button getSendButton() {
        return sendButton;
    }

    public Button getAttachButton() {
        return attachButton;
    }

    private int color(int resId) {
        return getContext().getResources().getColor(resId, getContext().getTheme());
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}