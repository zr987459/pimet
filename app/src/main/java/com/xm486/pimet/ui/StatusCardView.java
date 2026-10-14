package com.xm486.pimet.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.xm486.pimet.R;
import com.xm486.pimet.ThemeManager;
import com.xm486.pimet.monitor.OperitState;

/**
 * 桌宠下方的聊天卡（点击桌宠展开）。
 *
 * v1.7.4 极简重做：
 *   - 只有两行：顶栏（状态点 + 状态 + 监控/模型/时间，单行省略，点按展开详情）+ 输入行
 *   - 去掉原先夹在顶栏和输入框之间的「详细监控」内框（那块大空白），详情并入顶栏
 *   - 颜色全部取自当前主题调色板（auto/light/dark/mist/rose/pine），主题切换实时生效
 *   - 图标用 Lucide 线性图标，不再用 emoji
 *   - 高度随系统字号自适应（不用固定高度），大字体下不裁切
 */
public class StatusCardView extends LinearLayout {

    private ThemeManager.ThemePalette palette;

    private View stateDot;          // 状态点（8dp 圆点）
    private TextView stateLabel;    // 状态文字（加粗）
    private TextView detailLine;    // 监控 · 模型 · 时间（单行省略）
    private ImageView chevron;      // 展开/收起详情
    private LinearLayout detailBox; // 展开后的详情（默认收起，不占空间）
    private TextView detailAction;  // 当前动作
    private TextView detailMeta;    // 模式 / 用法提示
    private ImageView detailActionIcon;
    private ImageView detailMetaIcon;
    private ImageView fullScreenIcon;
    private View fullScreenBtn;
    private EditText chatInput;     // 输入框
    private Button sendButton;      // 发送按钮
    private boolean sendRunning = false; // 回复进行中：发送按钮变为「停止」
    private Button attachButton;    // 附件按钮（当前未放入布局，保留接口）
    private OnClickListener chipClickListener;

    private boolean detailsExpanded = false;

    // 顶栏详情的组成部分
    private String target = "pi-web";
    private String model = "";
    private String metrics = "";
    private String modeLabel = "";
    private String actionText = "空闲待命";
    private OperitState lastState = OperitState.UNKNOWN;

    /** 聊天处理阶段：active 时顶栏显示 chatPhaseText（思考中/工具/回复中），结束后恢复监控驱动 */
    private volatile boolean chatPhaseActive = false;
    private volatile String chatPhaseText = "";

    public StatusCardView(Context context) {
        super(context);
        init(context);
    }

    private void init(Context context) {
        palette = ThemeManager.getEffectivePalette(context);
        setOrientation(VERTICAL);
        setPadding(dp(12), dp(10), dp(12), dp(12));

        // ---- 顶栏：状态点 + 状态 + 详情（单行省略）+ 展开箭头 ----
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(24));
        header.setOnClickListener(v -> setDetailsExpanded(!detailsExpanded));
        addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        stateDot = new View(context);
        LayoutParams dotLp = new LayoutParams(dp(8), dp(8));
        dotLp.rightMargin = dp(8);
        header.addView(stateDot, dotLp);

        stateLabel = new TextView(context);
        stateLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        stateLabel.setTypeface(Typeface.DEFAULT_BOLD);
        stateLabel.setSingleLine(true);
        stateLabel.setEllipsize(TextUtils.TruncateAt.END);
        stateLabel.setIncludeFontPadding(false);
        stateLabel.setMaxWidth(dp(120));
        header.addView(stateLabel, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        detailLine = new TextView(context);
        detailLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        detailLine.setSingleLine(true);
        detailLine.setEllipsize(TextUtils.TruncateAt.END);
        detailLine.setIncludeFontPadding(false);
        LayoutParams dlLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        dlLp.leftMargin = dp(8);
        header.addView(detailLine, dlLp);

        chevron = new ImageView(context);
        chevron.setImageResource(R.drawable.ic_chevron_down);
        LayoutParams chLp = new LayoutParams(dp(16), dp(16));
        chLp.leftMargin = dp(6);
        header.addView(chevron, chLp);

        // ---- 详情（点按顶栏展开；默认 GONE，不占任何高度）----
        detailBox = new LinearLayout(context);
        detailBox.setOrientation(VERTICAL);
        detailBox.setVisibility(GONE);
        LayoutParams dbLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        dbLp.topMargin = dp(6);
        addView(detailBox, dbLp);

        detailActionIcon = new ImageView(context);
        detailAction = new TextView(context);
        detailBox.addView(detailRow(context, detailActionIcon, R.drawable.ic_activity, detailAction, 2));
        detailMetaIcon = new ImageView(context);
        detailMeta = new TextView(context);
        View metaRow = detailRow(context, detailMetaIcon, R.drawable.ic_message, detailMeta, 2);
        ((LayoutParams) metaRow.getLayoutParams()).topMargin = dp(4);
        detailBox.addView(metaRow);

        // ---- 输入行：全屏 + 输入框 + 发送 ----
        LinearLayout inputRow = new LinearLayout(context);
        inputRow.setOrientation(HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);

        fullScreenIcon = new ImageView(context);
        fullScreenIcon.setImageResource(R.drawable.ic_maximize);
        fullScreenIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        fullScreenIcon.setPadding(dp(10), dp(10), dp(10), dp(10));
        fullScreenIcon.setContentDescription("全屏工作台");
        fullScreenBtn = fullScreenIcon;
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
        fsLp.rightMargin = dp(8);
        inputRow.addView(fullScreenBtn, fsLp);

        chatInput = new EditText(context);
        chatInput.setHint("说点什么");
        chatInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        chatInput.setSingleLine(true);
        chatInput.setMaxLines(1);
        chatInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        chatInput.setInputType(InputType.TYPE_CLASS_TEXT);
        chatInput.setGravity(Gravity.CENTER_VERTICAL);
        chatInput.setIncludeFontPadding(false);
        chatInput.setPadding(dp(10), dp(8), dp(10), dp(8));
        chatInput.setMinHeight(dp(36));
        chatInput.setMinimumHeight(dp(36));
        // 键盘上的「发送」键与发送按钮行为一致
        chatInput.setOnEditorActionListener((v, actionId, ev) -> {
            boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && ev.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEND || enter) {
                if (sendButton != null) sendButton.performClick();
                return true;
            }
            return false;
        });
        // 高度随字号增长（WRAP_CONTENT + 最小 36dp），大字体不裁切
        inputRow.addView(chatInput, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        // 发送：强调色方形图标按钮（线性 send 图标），省出宽度给输入框，提示文字不被截断
        sendButton = new Button(context);
        sendButton.setText("");
        sendButton.setContentDescription("发送");
        sendButton.setStateListAnimator(null);
        sendButton.setPadding(0, 0, 0, 0);
        sendButton.setMinHeight(0);
        sendButton.setMinimumHeight(0);
        sendButton.setMinWidth(0);
        sendButton.setMinimumWidth(0);
        LayoutParams btnLp = new LayoutParams(dp(36), dp(36));
        btnLp.leftMargin = dp(8);
        inputRow.addView(sendButton, btnLp);

        LayoutParams inputLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        inputLp.topMargin = dp(10);
        addView(inputRow, inputLp);

        applyTheme(palette);
        refreshHeader();
    }

    private View detailRow(Context ctx, ImageView icon, int iconRes, TextView tv, int maxLines) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.TOP);
        icon.setImageResource(iconRes);
        LayoutParams icLp = new LayoutParams(dp(14), dp(14));
        icLp.rightMargin = dp(6);
        icLp.topMargin = dp(1);
        row.addView(icon, icLp);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setMaxLines(maxLines);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setLineSpacing(dp(2), 1f);
        row.addView(tv, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        row.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** 按主题调色板重新着色（主题切换时由 PetOverlayService 调用，实时生效） */
    public void applyTheme(ThemeManager.ThemePalette p) {
        if (p == null) return;
        palette = p;
        Context ctx = getContext();
        setBackground(ThemeManager.createCardDrawable(ctx, p, ThemeManager.RADIUS_CARD));

        stateLabel.setTextColor(p.text);
        detailLine.setTextColor(p.textMuted);
        chevron.setColorFilter(p.textMuted, android.graphics.PorterDuff.Mode.SRC_IN);
        detailAction.setTextColor(p.text);
        detailMeta.setTextColor(p.textMuted);
        detailActionIcon.setColorFilter(p.textMuted, android.graphics.PorterDuff.Mode.SRC_IN);
        detailMetaIcon.setColorFilter(p.textMuted, android.graphics.PorterDuff.Mode.SRC_IN);
        GradientDrawable sunk = ThemeManager.createSunkenDrawable(ctx, p, ThemeManager.RADIUS_SMALL);
        detailBox.setBackground(sunk);
        detailBox.setPadding(dp(8), dp(6), dp(8), dp(6));

        fullScreenIcon.setColorFilter(p.textMuted, android.graphics.PorterDuff.Mode.SRC_IN);
        fullScreenIcon.setBackground(ThemeManager.createSecondaryButtonDrawable(ctx, p, ThemeManager.RADIUS_CONTROL));

        chatInput.setBackground(ThemeManager.createInputDrawable(ctx, p, ThemeManager.RADIUS_CONTROL));
        chatInput.setTextColor(p.text);
        chatInput.setHintTextColor(p.textDim);

        StateListDrawable btnStates = new StateListDrawable();
        btnStates.addState(new int[]{android.R.attr.state_pressed}, sendLayer(ctx, p, p.accentHover));
        btnStates.addState(new int[]{}, sendLayer(ctx, p, p.accent));
        sendButton.setBackground(btnStates);
        sendButton.setContentDescription(sendRunning ? "停止" : "发送");

        refreshHeader();
    }

    private Drawable sendLayer(Context ctx, ThemeManager.ThemePalette p, int bg) {
        GradientDrawable base = ThemeManager.createPrimaryButtonDrawable(ctx, p, ThemeManager.RADIUS_CONTROL);
        base.setColor(bg);
        Drawable icon = ctx.getResources().getDrawable(sendRunning ? R.drawable.ic_stop : R.drawable.ic_send, ctx.getTheme()).mutate();
        icon.setTint(p.accentContrast);
        android.graphics.drawable.LayerDrawable ld =
                new android.graphics.drawable.LayerDrawable(new Drawable[]{base, icon});
        int in = dp(9);
        ld.setLayerInset(1, in, in, in, in);
        return ld;
    }

    /** 回复进行中把发送按钮切成「停止」图标，点击即可打断 */
    public void setSendRunning(boolean running) {
        if (sendRunning == running) return;
        sendRunning = running;
        if (palette != null) applyTheme(palette);
    }

    private void setDetailsExpanded(boolean expanded) {
        detailsExpanded = expanded;
        detailBox.setVisibility(expanded ? VISIBLE : GONE);
        chevron.setRotation(expanded ? 180f : 0f);
    }

    /** 设置快捷短语点击监听（Service 里接到发送逻辑） */
    public void setOnChipClickListener(OnClickListener listener) {
        this.chipClickListener = listener;
    }

    /** 状态 → 主题语义色（取自当前调色板，不新增颜色） */
    private int stateColor(OperitState s) {
        ThemeManager.ThemePalette p = palette;
        switch (s) {
            case IDLE:         return ThemeManager.statusSuccessColor(p);
            case THINKING:
            case RESPONDING:   return p.accent;
            case WORKING:
            case TOOL_RUNNING:
            case WAITING:      return ThemeManager.statusWarningColor(p);
            case ERROR:        return ThemeManager.statusDangerColor(p);
            case UNKNOWN:
            default:           return p.textDim;
        }
    }

    private void paintDot(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        stateDot.setBackground(d);
    }

    private void refreshHeader() {
        if (stateLabel == null) return;
        if (chatPhaseActive) {
            stateLabel.setText(clean(chatPhaseText));
            paintDot(palette.accent);
        } else {
            stateLabel.setText(lastState.getLabel());
            paintDot(stateColor(lastState));
        }
        StringBuilder sb = new StringBuilder(target);
        if (!TextUtils.isEmpty(model)) sb.append(" · ").append(model);
        if (!TextUtils.isEmpty(metrics)) sb.append(" · ").append(metrics);
        detailLine.setText(sb.toString());

        detailAction.setText(chatPhaseActive && !chatPhaseText.isEmpty()
                ? clean(chatPhaseText) : actionText);
        String meta = (TextUtils.isEmpty(modeLabel) ? "" : ("模式 " + clean(modeLabel) + "\n"))
                + "以 . 开头：后台问答";
        detailMeta.setText(meta);
    }

    /** 去掉文案开头的 emoji（桥接层的阶段文案仍可能带 emoji），保持线性图标风格 */
    public static String clean(String s) {
        if (s == null) return "";
        int i = 0, n = s.length();
        while (i < n) {
            int cp = s.codePointAt(i);
            int type = Character.getType(cp);
            boolean emojiLike = type == Character.OTHER_SYMBOL || type == Character.SURROGATE
                    || cp == 0xFE0F || cp == 0x200D || Character.isWhitespace(cp)
                    || (cp >= 0x1F000 && cp <= 0x1FAFF) || (cp >= 0x2600 && cp <= 0x27BF);
            if (!emojiLike) break;
            i += Character.charCount(cp);
        }
        return s.substring(i).trim();
    }

    /** 用最新快照刷新顶栏与详情 */
    public void update(OperitState.Snapshot snap) {
        if (snap == null) return;
        OperitState s = snap.state != null ? snap.state : OperitState.UNKNOWN;
        if (chatPhaseActive && s != OperitState.IDLE && s != OperitState.UNKNOWN) {
            chatPhaseText = s.getLabel();
        }
        lastState = s;

        target = (snap.agentName != null && !snap.agentName.isEmpty()) ? snap.agentName : "pi-web";
        model = snap.model != null ? snap.model : "";

        String m = "";
        if (snap.inputTokens > 0 || snap.outputTokens > 0) {
            m = "↑" + snap.inputTokens + " ↓" + snap.outputTokens;
        } else if (snap.lastActiveTime > 0) {
            long diffSec = Math.max(0, (System.currentTimeMillis() - snap.lastActiveTime) / 1000);
            m = (diffSec < 60) ? (diffSec + "s前") : ((diffSec / 60) + "m前");
        }
        metrics = m;

        String action;
        if (s == OperitState.TOOL_RUNNING) {
            action = "工具执行：" + (snap.lastTool != null && !snap.lastTool.isEmpty() ? snap.lastTool : "执行指令中…");
        } else if (s == OperitState.THINKING) {
            action = "正在推理分析与规划任务…";
        } else if (s == OperitState.RESPONDING) {
            action = "正在组织流式回复…";
        } else if (s == OperitState.WORKING) {
            action = "正在执行后台任务…";
        } else if (s == OperitState.WAITING) {
            action = "等待任务与依赖返回…";
        } else if (s == OperitState.ERROR) {
            action = "发生异常，请检查控制台输出";
        } else if (s == OperitState.IDLE) {
            action = snap.operitRunning ? "进程待命中 · 容器环境正常" : "空闲待命";
        } else {
            action = "状态监听中…";
        }
        if (snap.recentEvents != null && !snap.recentEvents.isEmpty() && s != OperitState.IDLE) {
            String latestEvent = snap.recentEvents.get(0);
            if (latestEvent != null && !latestEvent.trim().isEmpty()) {
                action += "（" + clean(latestEvent.trim()) + "）";
            }
        }
        actionText = action;
        refreshHeader();
    }

    /** 聊天开始：顶栏切到细粒度阶段（思考中/工具执行中/回复中…） */
    public void startChatPhase(String phaseText) {
        chatPhaseActive = true;
        chatPhaseText = phaseText == null ? "" : phaseText;
        refreshHeader();
    }

    /** 更新聊天阶段文案（请求过程中可多次调用） */
    public void setChatPhase(String phaseText) {
        if (!chatPhaseActive) return;
        chatPhaseText = phaseText == null ? "" : phaseText;
        refreshHeader();
    }

    /** 聊天是否处于阶段态 */
    public boolean isChatPhaseActive() {
        return chatPhaseActive;
    }

    /** 聊天结束：恢复监控驱动的状态显示 */
    public void endChatPhase() {
        chatPhaseActive = false;
        chatPhaseText = "";
        refreshHeader();
    }

    /** 当前 AI 模式标签（并入详情，不再单独占顶栏右侧） */
    public void setModeLabel(String label) {
        modeLabel = label == null ? "" : label;
        refreshHeader();
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

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
