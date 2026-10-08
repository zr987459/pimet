package com.xm486.pimet.ui;

import android.content.Context;
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

        // ---- 输入框 + 发送按钮 ----
        LinearLayout inputRow = new LinearLayout(context);
        inputRow.setOrientation(HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);

        attachButton = new Button(context);
        attachButton.setText("📎");
        attachButton.setTextSize(13f);
        attachButton.setTextColor(0xFFFFFFFF);
        attachButton.setPadding(0, 0, 0, 0);
        attachButton.setMinHeight(0);
        attachButton.setMinWidth(0);
        GradientDrawable attachBg = new GradientDrawable();
        attachBg.setColor(0x22FFFFFF);
        attachBg.setCornerRadius(dp(12));
        attachButton.setBackground(attachBg);
        LayoutParams attachLp = new LayoutParams(dp(36), dp(36));
        attachLp.rightMargin = dp(6);
        inputRow.addView(attachButton, attachLp);

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

    /** 用最新快照刷新状态点（监控信息主要走气泡，这里只留一个点） */
    public void update(OperitState.Snapshot snap) {
        // 聊天阶段激活时：监控的细粒度状态（思考中/工具执行中/回复中…）透到顶栏文案，
        // 空闲/未知则不覆盖「正在处理…」，避免等待期间顶栏完全卡死
        if (chatPhaseActive) {
            OperitState s0 = snap.state;
            if (s0 != null && s0 != OperitState.IDLE && s0 != OperitState.UNKNOWN) {
                setChatPhase(s0.getEmoji() + " " + s0.getLabel());
            }
            return;
        }
        OperitState s = snap.state;
        stateLabel.setText(s.getLabel());
        int c = StateStyle.overlayColor(getContext(), s);
        stateDot.setTextColor(c);
        stateLabel.setTextColor(c);
        stateLabel.setTypeface(Typeface.DEFAULT_BOLD);
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