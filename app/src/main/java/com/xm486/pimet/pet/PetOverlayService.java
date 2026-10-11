package com.xm486.pimet.pet;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.xm486.pimet.MainActivity;
import com.xm486.pimet.PiWebActivity;
import com.xm486.pimet.R;
import com.xm486.pimet.monitor.ClawBenchMonitor;
import com.xm486.pimet.monitor.OperitMonitor;
import com.xm486.pimet.monitor.OperitState;
import com.xm486.pimet.monitor.PiWebMonitor;
import com.xm486.pimet.monitor.RikkaHubMonitor;
import com.xm486.pimet.ui.StatusCardView;

/**
 * 桌宠悬浮窗前台服务。
 *
 * 职责：
 * 1. 创建 TYPE_APPLICATION_OVERLAY 悬浮窗（桌宠 + 状态卡）
 * 2. 启动 OperitMonitor 轮询 Operit 日志，实时更新桌宠动画
 * 3. 点击桌宠展开/收起状态卡；长按弹出快捷菜单；拖动调整位置（含屏幕空气墙）
 * 4. 监听角色切换（SharedPreferences），即时换角色
 * 5. 通过 PetChatBridge 接入大模型，桌宠能聊天（状态卡输入框）
 */
public class PetOverlayService extends Service implements OperitMonitor.Listener,
        android.content.SharedPreferences.OnSharedPreferenceChangeListener {

    public static volatile boolean isRunning = false;
    public static boolean isRunning() { return isRunning; }
    private static volatile PetOverlayService sInstance = null;
    public static PetOverlayService getInstance() { return sInstance; }
    public SpritePetView getPetView() { return petView; }
    public static final String ACTION_OVERLAY_STATE_CHANGED = "com.xm486.pimet.OVERLAY_STATE_CHANGED";

    public static void start(Context context) {
        if (context == null) return;
        try {
            Intent intent = new Intent(context, PetOverlayService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to start PetOverlayService", t);
        }
    }

    public static void stop(Context context) {
        if (context == null) return;
        try {
            context.stopService(new Intent(context, PetOverlayService.class));
        } catch (Throwable t) {
            Log.w(TAG, "Failed to stop PetOverlayService", t);
        }
    }

    private static final String TAG = "DevPetM.PetSvc";
    private static final String CHANNEL_ID = "devpetm_overlay";
    private static final int NOTIFICATION_ID = 1;

    private WindowManager windowManager;
    private FrameLayout overlayRoot;      // 悬浮窗根容器
    private SpritePetView petView;        // 桌宠（精灵图角色）
    private StatusCardView statusCard;    // 状态卡
    private TextView bubbleView;          // 对话气泡（任务完成 + AI 聊天共用）
    private boolean cardVisible = false;

    /** AI 对话桥（状态卡输入框 → 大模型） */
    private PetChatBridge chatBridge;

    /** 长按快捷菜单（宠物大小 + 设置/关闭） */
    private PetMenu petMenu;

    /** 任务完成气泡：上一状态 + 定时隐藏 */
    private OperitState lastState = OperitState.UNKNOWN;
    private int preBubbleX = -1;
    private int preBubbleY = -1;
    private final Handler bubbleHandler = new Handler(Looper.getMainLooper());
    private final Runnable bubbleHideRunnable = () -> {
        if (bubbleView != null && petView != null && statusCard != null) {
            // 气泡隐藏 → 桌宠回到顶部原位，状态卡锚点同步
            FrameLayout.LayoutParams petLp = (FrameLayout.LayoutParams) petView.getLayoutParams();
            petLp.topMargin = 0;
            petView.setLayoutParams(petLp);
            FrameLayout.LayoutParams cardLp = (FrameLayout.LayoutParams) statusCard.getLayoutParams();
            cardLp.topMargin = petLp.height + dp(8);
            statusCard.setLayoutParams(cardLp);

            bubbleView.animate().alpha(0f).setDuration(350)
                    .withEndAction(() -> {
                        bubbleView.setVisibility(View.GONE);
                        if (preBubbleX != -1 && overlayRoot != null) {
                            try {
                                WindowManager.LayoutParams lp =
                                        (WindowManager.LayoutParams) overlayRoot.getLayoutParams();
                                if (lp != null) {
                                    lp.x = preBubbleX;
                                    lp.y = preBubbleY;
                                    windowManager.updateViewLayout(overlayRoot, lp);
                                }
                            } catch (Exception ignored) {}
                            preBubbleX = -1;
                            preBubbleY = -1;
                        } else if (overlayRoot != null) {
                            try {
                                WindowManager.LayoutParams lp =
                                        (WindowManager.LayoutParams) overlayRoot.getLayoutParams();
                                windowManager.updateViewLayout(overlayRoot, lp);
                            } catch (Exception ignored) {}
                        }
                        if (overlayRoot != null) {
                            overlayRoot.post(this::clampToScreen);
                        }
                    })
                    .start();
        }
    };

    public static final String ACTION_TOGGLE_PET = "com.xm486.pimet.action.TOGGLE_PET";
    public static final String ACTION_SHOW_PET = "com.xm486.pimet.action.SHOW_PET";
    public static final String ACTION_HIDE_PET = "com.xm486.pimet.action.HIDE_PET";

    private boolean isPetHidden = false;
    private final Handler bottomPauseHandler = new Handler(Looper.getMainLooper());
    private boolean isWaitingBottomHide = false;
    private final Runnable bottomPauseRunnable = new Runnable() {
        @Override
        public void run() {
            if (isWaitingBottomHide) {
                isWaitingBottomHide = false;
                hidePet();
                Toast.makeText(PetOverlayService.this,
                        "桌宠已隐藏，点击通知栏「🐾 重新弹出桌宠」即可恢复",
                        Toast.LENGTH_SHORT).show();
            }
        }
    };

    /** 拖动物理：速度追踪 + 惯性飞行 */
    private VelocityTracker velocityTracker;
    private ValueAnimator flingAnimator;
    private long lastFlingFrame;

    private OperitMonitor monitor;
    private RikkaHubMonitor rikkaMonitor;
    private ClawBenchMonitor clawbenchMonitor;
    private PiWebMonitor piwebMonitor;
    private OperitState.Snapshot operitSnap;   // Operit 最近快照
    private OperitState.Snapshot rikkaSnap;    // RikkaHub 最近快照
    private OperitState.Snapshot clawbenchSnap; // ClawBench 最近快照
    private OperitState.Snapshot piwebSnap;     // pi-web 最近快照

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_SHOW_PET.equals(action)) {
                showPet();
            } else if (ACTION_HIDE_PET.equals(action)) {
                hidePet();
            } else if (ACTION_TOGGLE_PET.equals(action)) {
                if (isPetHidden) {
                    showPet();
                } else {
                    hidePet();
                }
            }
        }
        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        isRunning = true;
        PetRegistry.setPetEnabled(this, true);
        try {
            sendBroadcast(new Intent(ACTION_OVERLAY_STATE_CHANGED));
        } catch (Throwable ignored) {}
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createNotificationChannel();
        // Android 14+：startForeground 必须传入与 Manifest 一致的类型，否则崩溃
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification("桌宠运行中"),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("桌宠运行中"));
        }
        buildOverlay();
        startMonitoring();
        // 监听角色切换
        PetRegistry.getPrefs(this).registerOnSharedPreferenceChangeListener(this);
        // 挂载 AI 对话桥（状态卡输入框 → 大模型）
        chatBridge = new PetChatBridge(this);
        chatBridge.attach();
        // 状态卡显示当前 AI 模式
        statusCard.setModeLabel(ChatConfig.load(this).modeLabel());
        // 自动应用当前模式绑定的专属角色
        applyModePet(ChatConfig.load(this).mode);
        // 自动启动桌宠独立记忆与主动关怀定时器
        startProactiveChatter();
    }

    // ---------------- 桌宠主动互动与独立记忆 ----------------
    private final Handler proactiveHandler = new Handler(Looper.getMainLooper());
    private final Runnable proactiveRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                boolean enabled = PetRegistry.getBooleanPref(PetOverlayService.this,
                        PetMemoryManager.KEY_PROACTIVE_CHAT_ENABLED, true);
                if (enabled && !isPetHidden && overlayRoot != null && !cardVisible) {
                    String msg = PetMemoryManager.generateProactiveMessage(PetOverlayService.this);
                    showProactiveBubble(msg);
                    if (petView != null) {
                        petView.playOneShot("waving");
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "proactiveRunnable error", t);
            }
            int intervalMin = PetRegistry.getIntPref(PetOverlayService.this,
                    PetMemoryManager.KEY_PROACTIVE_INTERVAL_MIN, 3);
            long delay = (intervalMin * 60L + (long)(Math.random() * 60)) * 1000L;
            proactiveHandler.postDelayed(this, Math.max(60000L, delay));
        }
    };

    public void startProactiveChatter() {
        stopProactiveChatter();
        PetMemoryManager.ensurePetSubagentInstalled(this);
        int intervalMin = PetRegistry.getIntPref(this,
                PetMemoryManager.KEY_PROACTIVE_INTERVAL_MIN, 3);
        proactiveHandler.postDelayed(proactiveRunnable, Math.max(45000L, intervalMin * 60000L));
    }

    public void stopProactiveChatter() {
        proactiveHandler.removeCallbacks(proactiveRunnable);
    }

    private String lastProactiveMsg = null;

    public void showProactiveBubble(String msg) {
        if (bubbleView == null || msg == null || msg.trim().isEmpty()) return;
        this.lastProactiveMsg = msg;
        bubbleHandler.removeCallbacks(bubbleHideRunnable);

        // 记录弹出前桌宠坐标，待气泡隐藏后平滑恢复（若用户中途拖拽则取消恢复）
        if (overlayRoot != null) {
            WindowManager.LayoutParams curLp =
                    (WindowManager.LayoutParams) overlayRoot.getLayoutParams();
            if (curLp != null && preBubbleX == -1) {
                preBubbleX = curLp.x;
                preBubbleY = curLp.y;
            }
        }

        applyBubbleDimensions();
        bubbleView.setText("💬 " + msg);

        // 核心：测量气泡高度并将桌宠顶开至气泡下方，杜绝气泡遮挡桌宠
        layoutChatBubble();

        bubbleView.setVisibility(View.VISIBLE);
        bubbleView.setAlpha(0f);
        bubbleView.animate().alpha(1f).setDuration(200).start();
        bubbleHandler.postDelayed(bubbleHideRunnable, 8500);

        // 核心：重新测量窗口包裹范围并贴合屏幕四周安全边界，杜绝溢出屏幕
        if (overlayRoot != null) {
            overlayRoot.post(() -> {
                try {
                    WindowManager.LayoutParams lp =
                            (WindowManager.LayoutParams) overlayRoot.getLayoutParams();
                    windowManager.updateViewLayout(overlayRoot, lp);
                } catch (Exception ignored) {}
                overlayRoot.post(this::clampToScreen);
            });
        }
    }

    /**
     * 点击主动气泡直接展开互动面板并填充预设话题
     */
    public void onProactiveBubbleClicked(String text) {
        if (!cardVisible) {
            toggleCard();
        }
        if (statusCard != null) {
            android.widget.EditText input = statusCard.getChatInput();
            if (input != null) {
                input.setText("聊聊刚才说的: " + text);
                input.setSelection(input.getText().length());
                input.requestFocus();
            }
        }
    }

    public void sendPromptDirectly(String prompt) {
        if (!cardVisible) {
            toggleCard();
        }
        if (chatBridge != null && prompt != null && !prompt.trim().isEmpty()) {
            chatBridge.sendQuickMessage(prompt);
        }
    }

    @Override
    public void onSharedPreferenceChanged(android.content.SharedPreferences sp, String key) {
        if ("pet_dir".equals(key)) {
            String dir = sp.getString(key, null);
            if (dir != null && petView != null) {
                petView.setPetDir(dir);
            }
        } else if (PetRegistry.KEY_PET_SIZE.equals(key)) {
            // 宠物大小实时调整（桌宠 + 状态卡锚点同步更新）
            applyPetSize(sp.getInt(key, PetRegistry.DEFAULT_PET_SIZE));
        } else if (PetRegistry.KEY_CARD_WIDTH.equals(key)) {
            applyCardWidth(sp.getInt(key, PetRegistry.DEFAULT_CARD_WIDTH));
                } else if (PetRegistry.KEY_BUBBLE_WIDTH.equals(key)) {
            applyBubbleWidth(sp.getInt(key, PetRegistry.DEFAULT_BUBBLE_WIDTH));
} else if (PetRegistry.KEY_CARD_SCALE.equals(key)) {
            applyCardScale(sp.getInt(key, PetRegistry.DEFAULT_CARD_SCALE));
        } else if (PetRegistry.KEY_MONITOR_TARGET.equals(key)) {
            // 监控目标切换：保存新目标并即时切换显示
            String target = sp.getString(key, PetRegistry.TARGET_OPERIT);
            switchMonitorTarget(target);
        } else if (PetRegistry.KEY_OPERIT_PORT.equals(key)) {
            restartMonitor(PetRegistry.TARGET_OPERIT);
        } else if (PetRegistry.KEY_PIWEB_PORT.equals(key)) {
            restartMonitor(PetRegistry.TARGET_PIWEB);
        } else if (PetRegistry.KEY_CB_PORT.equals(key) || PetRegistry.KEY_CB_TOKEN.equals(key)) {
            restartMonitor(PetRegistry.TARGET_CLAWBENCH);
        } else if (PetRegistry.KEY_RK_PORT.equals(key)) {
            restartMonitor(PetRegistry.TARGET_RIKKA);
        }
    }

    /** 按 dp 宽度设置桌宠尺寸（高度按 192:208 比例），并调整状态卡锚点 */
    public void applyPetSize(int sizeDp) {
        if (petView == null || statusCard == null) return;
        int w = dp(sizeDp);
        int h = Math.round(w * 208f / 192f);
        FrameLayout.LayoutParams petLp = (FrameLayout.LayoutParams) petView.getLayoutParams();
        petLp.width = w;
        petLp.height = h;
        petView.setLayoutParams(petLp);
        FrameLayout.LayoutParams cardLp = (FrameLayout.LayoutParams) statusCard.getLayoutParams();
        cardLp.topMargin = w + dp(8);
        statusCard.setLayoutParams(cardLp);
        // 气泡锚点同步：仍贴在桌宠头顶
        if (bubbleView != null) {
            FrameLayout.LayoutParams bLp = (FrameLayout.LayoutParams) bubbleView.getLayoutParams();
            bLp.bottomMargin = w + dp(6);
            bubbleView.setLayoutParams(bLp);
        }
        petView.invalidate();
    }

    /** 状态卡宽度实时调整（140-360dp） */
    public void applyCardWidth(int widthDp) {
        if (statusCard == null) return;
        int w = dp(Math.max(140, Math.min(360, widthDp)));
        FrameLayout.LayoutParams cardLp =
                (FrameLayout.LayoutParams) statusCard.getLayoutParams();
        cardLp.width = w;
        statusCard.setLayoutParams(cardLp);
    }

    /** 状态卡整体缩放（40-120%，X/Y 等比例） */
    public void applyCardScale(int scalePct) {
        if (statusCard == null) return;
        float s = Math.max(0.4f, Math.min(1.2f, scalePct / 100f));
        statusCard.setScaleX(s);
        statusCard.setScaleY(s);
        // 缩放后实际占用高度 = 原高 × s，布局同步收紧
        statusCard.requestLayout();
    }


    /** 气泡尺寸与字号自适应动态调整 */
    public void applyBubbleDimensions() {
        if (bubbleView == null) return;
        int bw = PetRegistry.getIntPref(this, PetRegistry.KEY_BUBBLE_WIDTH, PetRegistry.DEFAULT_BUBBLE_WIDTH);
        int ts = PetRegistry.getIntPref(this, PetRegistry.KEY_BUBBLE_TEXT_SIZE, PetRegistry.DEFAULT_BUBBLE_TEXT_SIZE);
        bw = Math.max(100, Math.min(320, bw));
        ts = Math.max(9, Math.min(18, ts));
        bubbleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, ts);
        int padH = Math.round(ts * 1.15f);
        int padV = Math.round(ts * 0.75f);
        bubbleView.setPadding(dp(padH), dp(padV), dp(padH), dp(padV));
        int maxW = Math.min(dp(bw), (int)(getResources().getDisplayMetrics().widthPixels * 0.75f));
        bubbleView.setMaxWidth(maxW);
    }

    public void applyBubbleWidth(int widthDp) {
        PetRegistry.setIntPref(this, PetRegistry.KEY_BUBBLE_WIDTH, widthDp);
        applyBubbleDimensions();
    }

    public void applyBubbleTextSize(int textSizeSp) {
        PetRegistry.setIntPref(this, PetRegistry.KEY_BUBBLE_TEXT_SIZE, textSizeSp);
        applyBubbleDimensions();
    }

    /** 创建任务完成对话气泡（圆角背景 + 白字） */
    private void buildBubble(int petHeightPx) {
        bubbleView = new TextView(this);
        bubbleView.setGravity(Gravity.CENTER);
        bubbleView.setTextColor(0xFFF1F5F9);
        bubbleView.setMaxLines(10);
        bubbleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bubbleView.setLineSpacing(dp(2), 1.15f);
        applyBubbleDimensions();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF0181A22);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), 0x4460A5FA);
        bubbleView.setBackground(bg);
        bubbleView.setVisibility(View.GONE);
        bubbleView.setOnClickListener(v -> {
            openTargetConsole();
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.topMargin = 0; // 隐藏时不占位置；显示时把桌宠往下顶，气泡自然浮在头顶
        overlayRoot.addView(bubbleView, lp);
    }

    /** 任务完成：弹对话气泡 + 桌宠跳一下庆祝 */
    private void showCompletionBubble() {
        if (bubbleView == null) return;
        String[] msgs = {
                "任务完成啦！🐾\n点击气泡查看详情",
                "搞定！✅\n点击气泡打开控制台",
                "全部完成！\n点击气泡查看详情",
                "完工！干得漂亮 🎉\n点击气泡查看详情",
                "这波稳了！\n点击气泡打开控制台",
                "漂亮！收工～\n点击气泡查看详情"
        };
        bubbleView.setText(msgs[new java.util.Random().nextInt(msgs.length)]);

        // 测量气泡实际高度（按内容自适应）
        int bw = PetRegistry.getIntPref(this, PetRegistry.KEY_BUBBLE_WIDTH, PetRegistry.DEFAULT_BUBBLE_WIDTH);
        int wSpec = View.MeasureSpec.makeMeasureSpec(dp(bw), View.MeasureSpec.AT_MOST);
        int hSpec = View.MeasureSpec.makeMeasureSpec(dp(120), View.MeasureSpec.AT_MOST);
        bubbleView.measure(wSpec, hSpec);
        int bubbleH = bubbleView.getMeasuredHeight();
        int shift = bubbleH + dp(6);

        // 桌宠往下顶出气泡空间 → 气泡显示在人物头顶上方，不遮挡人物
        FrameLayout.LayoutParams petLp = (FrameLayout.LayoutParams) petView.getLayoutParams();
        petLp.topMargin = shift;
        petView.setLayoutParams(petLp);
        FrameLayout.LayoutParams cardLp = (FrameLayout.LayoutParams) statusCard.getLayoutParams();
        cardLp.topMargin = shift + petLp.height + dp(8);
        statusCard.setLayoutParams(cardLp);

        bubbleView.setVisibility(View.VISIBLE);
        bubbleView.setAlpha(0f);
        bubbleView.animate().alpha(1f).setDuration(200).start();
        // 重置隐藏计时：给用户 5.5 秒时间点击气泡进入控制台
        bubbleHandler.removeCallbacks(bubbleHideRunnable);
        bubbleHandler.postDelayed(bubbleHideRunnable, 5500);
        // 桌宠跳一下庆祝
        if (petView != null) {
            petView.playOneShot("jumping");
        }
        // 窗口内容变高，位置贴合屏幕
        overlayRoot.post(this::clampToScreen);
    }

    /** 任务失败/异常：弹对话气泡 + 桌宠播放失败/沮丧动作 */
    public void showFailureBubble(String reason) {
        if (bubbleView == null) return;
        String[] defaultFails = {
                "呜呜，任务好像失败了 😿\n点击气泡查看详情",
                "发生错误啦… 😿\n点击气泡打开控制台",
                "遇到异常了，快救救我 😿\n点击气泡查看详情"
        };
        String msg = (reason != null && !reason.trim().isEmpty())
                ? (reason.startsWith("😿") ? reason : ("😿 " + reason))
                : defaultFails[new java.util.Random().nextInt(defaultFails.length)];
        bubbleView.setText(msg);

        // 测量气泡实际高度（按内容自适应）
        int bw = PetRegistry.getIntPref(this, PetRegistry.KEY_BUBBLE_WIDTH, PetRegistry.DEFAULT_BUBBLE_WIDTH);
        int wSpec = View.MeasureSpec.makeMeasureSpec(dp(bw), View.MeasureSpec.AT_MOST);
        int hSpec = View.MeasureSpec.makeMeasureSpec(dp(120), View.MeasureSpec.AT_MOST);
        bubbleView.measure(wSpec, hSpec);
        int bubbleH = bubbleView.getMeasuredHeight();
        int shift = bubbleH + dp(6);

        FrameLayout.LayoutParams petLp = (FrameLayout.LayoutParams) petView.getLayoutParams();
        petLp.topMargin = shift;
        petView.setLayoutParams(petLp);
        FrameLayout.LayoutParams cardLp = (FrameLayout.LayoutParams) statusCard.getLayoutParams();
        cardLp.topMargin = shift + petLp.height + dp(8);
        statusCard.setLayoutParams(cardLp);

        bubbleView.setVisibility(View.VISIBLE);
        bubbleView.setAlpha(0f);
        bubbleView.animate().alpha(1f).setDuration(200).start();
        bubbleHandler.removeCallbacks(bubbleHideRunnable);
        bubbleHandler.postDelayed(bubbleHideRunnable, 5500);

        if (petView != null) {
            petView.playOneShot("failed");
        }
        overlayRoot.post(this::clampToScreen);
    }

    /** 点击气泡或长按菜单监控目标：直接打开目标对应的 Web 控制台界面 */
    public void openConsoleForTarget(String targetKey) {
        int port;
        if (PetRegistry.TARGET_CLAWBENCH.equals(targetKey)) {
            port = PetRegistry.getClawbenchPort(this);
        } else if (PetRegistry.TARGET_PIWEB.equals(targetKey)) {
            port = PetRegistry.getPiWebPort(this);
        } else if (PetRegistry.TARGET_RIKKA.equals(targetKey)) {
            port = PetRegistry.getIntPref(this, PetRegistry.KEY_RK_PORT, PetRegistry.DEFAULT_RK_PORT);
        } else if (PetRegistry.TARGET_OPERIT.equals(targetKey)) {
            port = PetRegistry.getOperitPort(this);
        } else {
            // 自定义 API 没有固定本地端口，直接打开主页配置
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra("devpetm.open_ai_tab", true);
            startActivity(intent);
            return;
        }

        try {
            Intent intent = new Intent(this, PiWebActivity.class);
            intent.putExtra(PiWebActivity.EXTRA_PORT, port);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "openConsoleForTarget failed", t);
            try {
                Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:" + port));
                browser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(browser);
            } catch (Throwable ignored) {}
        }
    }

    public void hidePet() {
        if (isPetHidden || overlayRoot == null) return;
        isPetHidden = true;
        isWaitingBottomHide = false;
        bottomPauseHandler.removeCallbacks(bottomPauseRunnable);

        if (bubbleView != null) bubbleView.setVisibility(View.GONE);
        if (statusCard != null) statusCard.setVisibility(View.GONE);
        cardVisible = false;

        overlayRoot.animate()
                .alpha(0f)
                .scaleX(0.4f)
                .scaleY(0.4f)
                .translationY(dp(45))
                .setDuration(240)
                .withEndAction(() -> {
                    if (overlayRoot != null) {
                        overlayRoot.setVisibility(View.GONE);
                        overlayRoot.setTranslationY(0f);
                        overlayRoot.setScaleX(1f);
                        overlayRoot.setScaleY(1f);
                        overlayRoot.setAlpha(1f);
                    }
                    if (petView != null) {
                        petView.setAlpha(1f);
                        petView.setScaleX(1f);
                        petView.setScaleY(1f);
                    }
                })
                .start();

        updateNotification("桌宠已隐藏（后台监控中 · 点击通知按键重新弹出）");
    }

    public void showPet() {
        if (overlayRoot == null) return;
        isPetHidden = false;
        isWaitingBottomHide = false;
        bottomPauseHandler.removeCallbacks(bottomPauseRunnable);

        overlayRoot.setVisibility(View.VISIBLE);
        overlayRoot.setAlpha(0f);
        overlayRoot.setScaleX(0.6f);
        overlayRoot.setScaleY(0.6f);
        overlayRoot.setTranslationY(dp(30));
        overlayRoot.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(300)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f))
                .start();

        if (petView != null) {
            petView.setAlpha(1f);
            petView.setScaleX(1f);
            petView.setScaleY(1f);
            petView.playOneShot("jumping");
        }
        clampToScreen();
        updateNotification(lastState != null ? ("桌宠 · " + lastState.getLabel()) : "桌宠运行中");
    }

    public boolean isPetHidden() {
        return isPetHidden;
    }

    public void openTargetConsole() {
        String target = PetRegistry.getStringPref(this, PetRegistry.KEY_MONITOR_TARGET, PetRegistry.TARGET_OPERIT);
        openConsoleForTarget(target);
    }

    public int getCurrentTargetPort() {
        String target = PetRegistry.getStringPref(this, PetRegistry.KEY_MONITOR_TARGET, PetRegistry.TARGET_OPERIT);
        if (PetRegistry.TARGET_CLAWBENCH.equals(target)) {
            return PetRegistry.getClawbenchPort(this);
        } else if (PetRegistry.TARGET_PIWEB.equals(target)) {
            return PetRegistry.getPiWebPort(this);
        } else if (PetRegistry.TARGET_RIKKA.equals(target)) {
            return PetRegistry.getIntPref(this, PetRegistry.KEY_RK_PORT, PetRegistry.DEFAULT_RK_PORT);
        } else {
            return PetRegistry.getOperitPort(this);
        }
    }

    // ---------------- 对外 API（供 PetChatBridge / PetTypewriter / BubbleMessage 使用） ----------------

    public FrameLayout getOverlayRoot() {
        return overlayRoot;
    }

    public int[] getPetScreenPosition() {
        if (overlayRoot != null && overlayRoot.getLayoutParams() instanceof WindowManager.LayoutParams) {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) overlayRoot.getLayoutParams();
            int w = petView != null && petView.getWidth() > 0 ? petView.getWidth() : dp(PetRegistry.getIntPref(this, PetRegistry.KEY_PET_SIZE, PetRegistry.DEFAULT_PET_SIZE));
            int h = petView != null && petView.getHeight() > 0 ? petView.getHeight() : w;
            return new int[]{lp.x, lp.y, w, h};
        }
        return new int[]{dp(16), dp(160), dp(72), dp(72)};
    }

    public TextView getChatBubble() {
        return bubbleView;
    }

    public EditText getChatInput() {
        return statusCard != null ? statusCard.getChatInput() : null;
    }

    /** 状态卡（供 PetChatBridge 驱动聊天阶段状态：思考中/回复中等） */
    public com.xm486.pimet.ui.StatusCardView getStatusCard() {
        return statusCard;
    }

    public Button getSendButton() {
        return statusCard != null ? statusCard.getSendButton() : null;
    }

    public Button getAttachButton() {
        return statusCard != null ? statusCard.getAttachButton() : null;
    }

    public void updateOverlayLayout(WindowManager.LayoutParams lp) {
        try {
            windowManager.updateViewLayout(overlayRoot, lp);
        } catch (Throwable t) {
            Log.w(TAG, "updateOverlayLayout failed", t);
        }
    }

    /**
     * 测量对话气泡高度，把桌宠和状态卡向下顶开，气泡浮在桌宠头顶上方。
     * 打字机每帧调用，实现「边打字边顶开」的效果。
     */
    public void layoutChatBubble() {
        if (bubbleView == null || petView == null || statusCard == null) return;
        applyBubbleDimensions();
        int maxW = bubbleView.getMaxWidth();
        int wSpec = View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST);
        int hSpec = View.MeasureSpec.makeMeasureSpec(dp(180), View.MeasureSpec.AT_MOST);
        bubbleView.measure(wSpec, hSpec);
        int bubbleH = bubbleView.getMeasuredHeight();
        int shift = bubbleH + dp(6);
        FrameLayout.LayoutParams petLp = (FrameLayout.LayoutParams) petView.getLayoutParams();
        petLp.topMargin = shift;
        petView.setLayoutParams(petLp);
        FrameLayout.LayoutParams cardLp = (FrameLayout.LayoutParams) statusCard.getLayoutParams();
        cardLp.topMargin = shift + petLp.height + dp(8);
        statusCard.setLayoutParams(cardLp);
        clampToScreen();
    }

    public void cancelBubbleHide() {
        bubbleHandler.removeCallbacks(bubbleHideRunnable);
    }

    public void scheduleBubbleHide(long delayMs) {
        cancelBubbleHide();
        bubbleHandler.postDelayed(bubbleHideRunnable, delayMs);
    }

    /**
     * 流式显示：把 AI 增量文本直接写进气泡并顶开桌宠/状态卡。
     * 幂等——每个 assistant_delta 调一次，气泡随增量实时增长（秒开、逐段蹦字）。
     */
    public void showStreamingBubble(String text) {
        TextView b = bubbleView;
        if (b == null) return;
        if (!b.isShown()) {
            b.setMaxLines(10);
            b.setEllipsize(android.text.TextUtils.TruncateAt.END);
            int bw = PetRegistry.getIntPref(this, PetRegistry.KEY_BUBBLE_WIDTH, PetRegistry.DEFAULT_BUBBLE_WIDTH);
            b.setMaxWidth(dp(bw));
            cancelBubbleHide();
            b.animate().cancel(); // 取消上次隐藏动画，防止其 endAction 把新气泡设回 GONE
            b.setAlpha(1f);
        }
        b.setText(text);
        b.setVisibility(View.VISIBLE);
        layoutChatBubble();
    }

    /** 长按桌宠：弹出快捷菜单（宠物大小滑块 + 设置/关闭 + 指令说明） */
    public void showPetMenu() {
        if (petMenu == null) {
            petMenu = new PetMenu(this, this);
        }
        petMenu.show();
    }

    // ---------------- 悬浮窗 ----------------

    private void buildOverlay() {
        int sizeDp = PetRegistry.getIntPref(this, PetRegistry.KEY_PET_SIZE,
                PetRegistry.DEFAULT_PET_SIZE);
        int petWidthPx = dp(sizeDp);
        // 状态卡宽度可在长按菜单里调（140-320dp）
        int cardWidthPx = dp(PetRegistry.getIntPref(this, PetRegistry.KEY_CARD_WIDTH,
                PetRegistry.DEFAULT_CARD_WIDTH));
        int cardScale = PetRegistry.getIntPref(this, PetRegistry.KEY_CARD_SCALE,
                PetRegistry.DEFAULT_CARD_SCALE);

        // 根容器（桌宠 + 状态卡 竖向排列）
        overlayRoot = new FrameLayout(this);
        overlayRoot.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));

        // 桌宠（精灵图角色，192:208 比例 → 高度按宽度等比计算，避免重启后被压扁）
        petView = new SpritePetView(this);
        int petHeightPx = Math.round(petWidthPx * 208f / 192f);
        FrameLayout.LayoutParams petLp = new FrameLayout.LayoutParams(petWidthPx, petHeightPx);
        petLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        overlayRoot.addView(petView, petLp);

        // 状态卡（初始隐藏）；高度自适应内容，避免固定高度留白或裁切
        statusCard = new StatusCardView(this);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                cardWidthPx, FrameLayout.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        cardLp.topMargin = petWidthPx + dp(8);
        statusCard.setVisibility(View.GONE);
        statusCard.setScaleX(Math.max(0.4f, Math.min(1.2f, cardScale / 100f)));
        statusCard.setScaleY(Math.max(0.4f, Math.min(1.2f, cardScale / 100f)));
        overlayRoot.addView(statusCard, cardLp);

        // 任务完成对话气泡（显示在桌宠头顶，初始隐藏）
        buildBubble(petWidthPx);

        // 拖动 + 点击
        attachTouchHandlers();

        // 加入 WindowManager
        WindowManager.LayoutParams wmParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        wmParams.gravity = Gravity.TOP | Gravity.START;
        // 恢复上次保存的位置，没有则用默认
        wmParams.x = PetRegistry.getIntPref(this, PetRegistry.KEY_POS_X, dp(16));
        wmParams.y = PetRegistry.getIntPref(this, PetRegistry.KEY_POS_Y, dp(160));

        try {
            windowManager.addView(overlayRoot, wmParams);
        } catch (Exception e) {
            Log.e(TAG, "addView failed: 悬浮窗权限未授予?", e);
            stopSelf();
        }
    }

    @SuppressWarnings("deprecation")
    private int windowType() {
        // Android 8.0+ 必须用 TYPE_APPLICATION_OVERLAY
        return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    }

    /** 拖动 + 点击切换状态卡；猛甩触发惯性飞行（物理） */
    private void attachTouchHandlers() {
        // 拖动通过 WindowManager 更新位置（记录 down 坐标）
        final float[] touchStart = new float[2];
        final int[] wmStart = new int[2];
        final boolean[] dragging = new boolean[1];
        final long[] downTime = new long[1];

        petView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: {
                        stopFling(); // 新的触摸初始打断惯性飞行
                        bottomPauseHandler.removeCallbacks(bottomPauseRunnable);
                        if (isWaitingBottomHide) {
                            isWaitingBottomHide = false;
                            petView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(100).start();
                        }
                        if (velocityTracker == null) {
                            velocityTracker = VelocityTracker.obtain();
                        } else {
                            velocityTracker.clear();
                        }
                        velocityTracker.addMovement(event);
                        touchStart[0] = event.getRawX();
                        touchStart[1] = event.getRawY();
                        WindowManager.LayoutParams downLp =
                                (WindowManager.LayoutParams) overlayRoot.getLayoutParams();
                        wmStart[0] = downLp.x;
                        wmStart[1] = downLp.y;
                        dragging[0] = false;
                        downTime[0] = event.getDownTime();
                        preBubbleX = -1;
                        preBubbleY = -1;
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE: {
                        if (velocityTracker != null) velocityTracker.addMovement(event);
                        float dx = event.getRawX() - touchStart[0];
                        float dy = event.getRawY() - touchStart[1];
                        if (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8)) {
                            dragging[0] = true;
                            preBubbleX = -1;
                            preBubbleY = -1;
                        }
                        if (dragging[0]) {
                            // 拖动中：按方向播放跑动动画
                            petView.setMoveDirection(dx > 0 ? 1 : (dx < 0 ? -1 : 0));
                            WindowManager.LayoutParams lp =
                                    (WindowManager.LayoutParams) overlayRoot.getLayoutParams();
                            // 空气墙：限制在屏幕内（顶部保留状态栏高度，防止遮挡）
                            int[] bounds = getDragBounds(lp);
                            int targetX = clamp(wmStart[0] + Math.round(dx), bounds[0], bounds[1]);
                            int targetY = clamp(wmStart[1] + Math.round(dy), bounds[2], bounds[3]);
                            lp.x = targetX;
                            lp.y = targetY;
                            try {
                                windowManager.updateViewLayout(overlayRoot, lp);
                            } catch (Exception e) {
                                Log.w(TAG, "updateViewLayout failed", e);
                            }

                            // 检测是否拖到屏幕底部中间：停顿 1~2 秒触发隐藏
                            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                            int screenW = dm.widthPixels;
                            int screenH = dm.heightPixels;
                            int petW = petView != null && petView.getWidth() > 0 ? petView.getWidth() : dp(80);
                            int petH = petView != null && petView.getHeight() > 0 ? petView.getHeight() : dp(80);
                            int petCenterX = targetX + petW / 2;
                            int petBottomY = targetY + petH;

                            boolean inBottomCenter = (petBottomY >= screenH - dp(35))
                                    && (Math.abs(petCenterX - screenW / 2) <= dp(70));

                            if (inBottomCenter) {
                                if (!isWaitingBottomHide) {
                                    isWaitingBottomHide = true;
                                    // 视觉反馈：桌宠半透明并轻微缩小，提示停顿即将隐藏
                                    petView.animate().alpha(0.6f).scaleX(0.86f).scaleY(0.86f).setDuration(200).start();
                                    bottomPauseHandler.postDelayed(bottomPauseRunnable, 900); // 停顿 0.9 秒（缩短半秒）触发隐藏
                                }
                            } else {
                                if (isWaitingBottomHide) {
                                    isWaitingBottomHide = false;
                                    bottomPauseHandler.removeCallbacks(bottomPauseRunnable);
                                    petView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start();
                                }
                            }
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        if (isWaitingBottomHide) {
                            isWaitingBottomHide = false;
                            bottomPauseHandler.removeCallbacks(bottomPauseRunnable);
                            petView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start();
                        }
                        // 停止拖动：恢复状态动画
                        petView.setMoveDirection(0);
                        boolean isFling = false;
                        if (velocityTracker != null) {
                            velocityTracker.addMovement(event);
                            velocityTracker.computeCurrentVelocity(1000); // px/s
                            float vx = velocityTracker.getXVelocity();
                            float vy = velocityTracker.getYVelocity();
                            releaseVelocityTracker();
                            // 先判速度：只要甩得够快就飞（快速甩动即使 MOVE 少也算），
                            // 保证 360° 任何方向都有响应
                            int threshold = PetRegistry.getIntPref(
                                    PetOverlayService.this,
                                    PetRegistry.KEY_FLING_THRESHOLD,
                                    PetRegistry.DEFAULT_FLING_THRESHOLD);
                            if (Math.hypot(vx, vy) > threshold) {
                                isFling = true;
                                startFling(vx, vy);
                            }
                            // 慢速拖动：位置已在 MOVE 中精确设置，这里不动作
                        }
                        // 非拖动且非惯性飞行 → 判定点击类型
                        if (!dragging[0] && !isFling && !isPetHidden) {
                            long pressDuration = event.getEventTime() - downTime[0];
                            if (pressDuration >= 400) {
                                // 长按：弹出快捷菜单（宠物大小 + 设置/关闭 + 指令说明）
                                showPetMenu();
                            } else {
                                toggleCard();
                            }
                        }
                        return true;
                    }
                }
                return false;
            }
        });
    }

    /** 惯性飞行：按初速度移动，碰到屏幕边界反弹并衰减，直到停稳 */
    private void startFling(float vx0, float vy0) {
        stopFling();
        // 读取可调物理参数
        int bouncePct = PetRegistry.getIntPref(this, PetRegistry.KEY_BOUNCE,
                PetRegistry.DEFAULT_BOUNCE);                       // 反弹保留 % 0~100
        int frictionPct = PetRegistry.getIntPref(this, PetRegistry.KEY_FRICTION,
                PetRegistry.DEFAULT_FRICTION);                     // 空气摩擦 % 0~100
        int stopSpeed = PetRegistry.getIntPref(this, PetRegistry.KEY_STOP_SPEED,
                PetRegistry.DEFAULT_STOP_SPEED);                   // 停稳阈值 px/s
        int duration = PetRegistry.getIntPref(this, PetRegistry.KEY_FLING_DURATION,
                PetRegistry.DEFAULT_FLING_DURATION);               // 最长飞行 ms
        float bounce = Math.max(0f, Math.min(1f, bouncePct / 100f));      // 反弹后速度保留
        float frictionPerSec = Math.max(0f, Math.min(1f, frictionPct / 100f)); // 每秒衰减比例

        final float[] v = new float[]{vx0, vy0};
        lastFlingFrame = System.currentTimeMillis();
        flingAnimator = ValueAnimator.ofFloat(0f, 1f);
        flingAnimator.setDuration(Math.max(500, duration));
        flingAnimator.setInterpolator(null); // 线性驱动，物理在回调里算
        flingAnimator.addUpdateListener(animation -> {
            long now = System.currentTimeMillis();
            float dt = (now - lastFlingFrame) / 1000f;
            lastFlingFrame = now;
            if (dt > 0.1f) dt = 0.1f; // 防跳帧
            if (dt <= 0f) return;

            WindowManager.LayoutParams lp =
                    (WindowManager.LayoutParams) overlayRoot.getLayoutParams();
            float px = lp.x + v[0] * dt;
            float py = lp.y + v[1] * dt;
            int[] b = getDragBounds(lp);
            // 边界反弹（保留 bounce 比例的速度）
            if (px < b[0]) { px = b[0]; v[0] = -v[0] * bounce; }
            else if (px > b[1]) { px = b[1]; v[0] = -v[0] * bounce; }
            if (py < b[2]) { py = b[2]; v[1] = -v[1] * bounce; }
            else if (py > b[3]) { py = b[3]; v[1] = -v[1] * bounce; }
            lp.x = Math.round(px);
            lp.y = Math.round(py);
            try {
                windowManager.updateViewLayout(overlayRoot, lp);
            } catch (Exception ignored) {}

            // 空气摩擦：每秒衰减 frictionPerSec 比例
            float friction = 1f - frictionPerSec * dt;
            v[0] *= friction;
            v[1] *= friction;

            // 停稳（速度足够小）
            if (Math.abs(v[0]) < stopSpeed && Math.abs(v[1]) < stopSpeed) {
                stopFling();
            }
        });
        flingAnimator.start();
    }

    /** 停止惯性飞行并贴合屏幕边界，保存位置供下次启动恢复 */
    private void stopFling() {
        if (flingAnimator != null) {
            flingAnimator.cancel();
            flingAnimator = null;
        }
        clampToScreen();
        savePosition();
    }
    /** 把当前悬浮窗坐标写入 SharedPreferences（启动时读取恢复） */
    private void savePosition() {
        if (overlayRoot == null) return;
        WindowManager.LayoutParams lp =
                (WindowManager.LayoutParams) overlayRoot.getLayoutParams();
        if (lp == null) return;
        PetRegistry.getPrefs(this).edit()
                .putInt(PetRegistry.KEY_POS_X, lp.x)
                .putInt(PetRegistry.KEY_POS_Y, lp.y)
                .apply();
    }

    private void releaseVelocityTracker() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    /** 计算拖动边界 [minX, maxX, minY, maxY]，保证悬浮窗在屏幕内可划到最底边 */
    private int[] getDragBounds(WindowManager.LayoutParams lp) {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;

        // 真实可见高度：必须区分卡片展开与收起状态！
        // 当卡片收起时，绝不能用 overlayRoot.getHeight()（否则包含了隐藏卡片残留尺寸），
        // 导致桌宠被下方不存在的菜单空区域挡住，划不到屏幕最底部！
        int visibleH;
        if (cardVisible && statusCard != null && statusCard.getVisibility() == View.VISIBLE) {
            int cardB = statusCard.getBottom();
            int rootH = overlayRoot != null ? overlayRoot.getHeight() : 0;
            visibleH = cardB > 0 ? cardB : (rootH > 0 ? rootH : dp(240));
        } else {
            // 卡片收起时：真实可见区域到桌宠底部（若气泡弹出，桌宠会被顶开向下）
            int petB = petView != null ? petView.getBottom() : 0;
            if (petB <= 0 && petView != null && petView.getHeight() > 0) {
                petB = petView.getHeight();
            }
            if (petB <= 0 && petView != null && petView.getLayoutParams() != null) {
                petB = petView.getLayoutParams().height;
            }
            visibleH = petB > 0 ? petB : dp(80);

            // 若气泡正在显示，计算气泡 + 桌宠顶开后的总可见高度
            if (bubbleView != null && bubbleView.getVisibility() == View.VISIBLE) {
                int bubbleH = bubbleView.getHeight() > 0 ? bubbleView.getHeight() : bubbleView.getMeasuredHeight();
                int petH = petView != null && petView.getHeight() > 0 ? petView.getHeight() : dp(80);
                visibleH = Math.max(visibleH, bubbleH + petH + dp(12));
            }
        }

        int w = petView != null && petView.getWidth() > 0 ? petView.getWidth() : dp(80);
        if (cardVisible && statusCard != null && statusCard.getVisibility() == View.VISIBLE) {
            int rootW = overlayRoot != null ? overlayRoot.getWidth() : 0;
            w = rootW > 0 ? rootW : (lp.width > 0 ? lp.width : dp(160));
        } else if (bubbleView != null && bubbleView.getVisibility() == View.VISIBLE) {
            int bubbleW = bubbleView.getWidth() > 0 ? bubbleView.getWidth() : bubbleView.getMeasuredWidth();
            if (bubbleW > 0) {
                w = Math.max(w, bubbleW);
            }
        }

        int edge = dp(4);
        int minX = -edge;
        int maxX = Math.max(minX, screenW - w + edge);
        int minY = dp(24); // 避开顶部状态栏与挖孔
        // 允许桌宠完全划到屏幕最底部，并预留系统导航栏安全边距
        int maxY = Math.max(minY, screenH - visibleH - dp(6));
        return new int[]{minX, maxX, minY, maxY};
    }

    private int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public void toggleCard() {
        cardVisible = !cardVisible;
        // 展开/收起用 180ms 的淡入+轻微缩放（只动 alpha/scale，不触发布局重排）；
        // 动画从桌宠位置向下展开（锚点在顶部中心），保持空间连续性。
        statusCard.animate().cancel();
        final float cs = Math.max(0.4f, Math.min(1.2f,
                PetRegistry.getIntPref(this, PetRegistry.KEY_CARD_SCALE,
                        PetRegistry.DEFAULT_CARD_SCALE) / 100f));
        if (cardVisible) {
            statusCard.setAlpha(0f);
            statusCard.setScaleX(cs * 0.94f);
            statusCard.setScaleY(cs * 0.94f);
            statusCard.setPivotX(statusCard.getWidth() / 2f);
            statusCard.setPivotY(0f);
            statusCard.setVisibility(View.VISIBLE);
            statusCard.animate()
                    .alpha(1f).scaleX(cs).scaleY(cs)
                    .setDuration(180)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        } else {
            // 退出比进入更快（约 60%），手感更利落
            statusCard.animate()
                    .alpha(0f).scaleX(cs * 0.96f).scaleY(cs * 0.96f)
                    .setDuration(110)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .withEndAction(() -> {
                        statusCard.setVisibility(View.GONE);
                        try {
                            WindowManager.LayoutParams lp =
                                    (WindowManager.LayoutParams) overlayRoot.getLayoutParams();
                            windowManager.updateViewLayout(overlayRoot, lp);
                        } catch (Exception ignored) {}
                        overlayRoot.post(this::clampToScreen);
                    })
                    .start();
        }
        // 点击桌宠：跳一下（一次性动作，播完自动恢复）
        if (petView != null) {
            petView.playOneShot("jumping");
        }
        // 展开/收起后窗口内容尺寸变化，需重测（WRAP_CONTENT 重新包裹）并贴合屏幕边界
        overlayRoot.post(() -> {
            WindowManager.LayoutParams lp =
                    (WindowManager.LayoutParams) overlayRoot.getLayoutParams();
            try {
                // 触发窗口重新测量包裹新内容
                windowManager.updateViewLayout(overlayRoot, lp);
            } catch (Exception ignored) {}
            // 等一帧让布局完成后再 clamp 位置，防止状态卡/桌宠飞出屏幕
            overlayRoot.post(this::clampToScreen);
        });
    }

    /** 将窗口位置限制在屏幕内（配合状态卡展开/收起） */
    public void clampToScreen() {
        if (overlayRoot == null) return;
        try {
            WindowManager.LayoutParams lp =
                    (WindowManager.LayoutParams) overlayRoot.getLayoutParams();
            int[] bounds = getDragBounds(lp);
            lp.x = clamp(lp.x, bounds[0], bounds[1]);
            lp.y = clamp(lp.y, bounds[2], bounds[3]);
            windowManager.updateViewLayout(overlayRoot, lp);
        } catch (Exception e) {
            Log.w(TAG, "clampToScreen failed", e);
        }
    }

    // ---------------- 监控（后台并发全量采集，前台毫秒级平滑切源） ----------------

    private String currentTarget = PetRegistry.TARGET_OPERIT;

    private void startMonitoring() {
        currentTarget = PetRegistry.getPrefs(this)
                .getString(PetRegistry.KEY_MONITOR_TARGET, PetRegistry.TARGET_OPERIT);
        ensureAllMonitorsRunning();
        recomputeState();
    }

    public void showReplyBubble(String text) {
        new Handler(Looper.getMainLooper()).post(new PetTypewriter(this, text));
    }

    /** 对外暴露的目标切换方法（保存到 Pref 并即时零延迟平滑切换前台显示） */
    public void switchMonitorTarget(String target) {
        if (target == null) target = PetRegistry.TARGET_OPERIT;
        if (target.equals(currentTarget)) return;

        currentTarget = target;
        lastState = null; // 重置状态变化检测，避免误触发上一目标的完成气泡
        PetRegistry.setStringPref(this, PetRegistry.KEY_MONITOR_TARGET, target);

        boolean chatUpdated = false;
        try {
            ChatConfig cfg = ChatConfig.load(this);
            if (PetRegistry.TARGET_CUSTOM_API.equals(target)) {
                cfg.mode = ChatConfig.MODE_CUSTOM_API;
                chatUpdated = true;
            } else if (PetRegistry.TARGET_PIWEB.equals(target)) {
                cfg.mode = ChatConfig.MODE_PIWEB;
                chatUpdated = true;
            } else if (PetRegistry.TARGET_CLAWBENCH.equals(target)) {
                cfg.mode = ChatConfig.MODE_CLAWBENCH;
                chatUpdated = true;
            } else if (PetRegistry.TARGET_OPERIT.equals(target)) {
                cfg.mode = ChatConfig.MODE_OPERIT;
                chatUpdated = true;
            }
            if (chatUpdated) {
                cfg.save(this);
                onChatConfigChanged();
            }
        } catch (Throwable ignored) {}

        // 确保后台常驻并发采集
        ensureAllMonitorsRunning();
        // 瞬间以目标当前后台已缓存的最新快照刷新桌宠状态（零延迟秒切）
        recomputeState();

        String label = "Operit";
        if (PetRegistry.TARGET_PIWEB.equals(target)) label = "pi-web";
        else if (PetRegistry.TARGET_CLAWBENCH.equals(target)) label = "ClawBench";
        else if (PetRegistry.TARGET_RIKKA.equals(target)) label = "RikkaHub (监控)";
        else if (PetRegistry.TARGET_CUSTOM_API.equals(target)) label = "自定义 API";
        showReplyBubble("🎯 监控已切换: " + label);
    }

    public String getCurrentTarget() {
        return currentTarget != null ? currentTarget : PetRegistry.TARGET_OPERIT;
    }

    /** 确保所有后台监控源并发常驻运行 */
    public synchronized void ensureAllMonitorsRunning() {
        startOperitMonitor();
        startPiWebMonitor();
        startClawBenchMonitor();
        startRikkaMonitor();
    }

    /** 重启某个特定后台监控器（修改端口或配置时使用） */
    public synchronized void restartMonitor(String target) {
        if (PetRegistry.TARGET_OPERIT.equals(target)) {
            if (monitor != null) { monitor.stop(); monitor = null; }
            startOperitMonitor();
        } else if (PetRegistry.TARGET_PIWEB.equals(target)) {
            if (piwebMonitor != null) { piwebMonitor.stop(); piwebMonitor = null; }
            startPiWebMonitor();
        } else if (PetRegistry.TARGET_CLAWBENCH.equals(target)) {
            if (clawbenchMonitor != null) { clawbenchMonitor.stop(); clawbenchMonitor = null; }
            startClawBenchMonitor();
        } else if (PetRegistry.TARGET_RIKKA.equals(target)) {
            if (rikkaMonitor != null) { rikkaMonitor.stop(); rikkaMonitor = null; }
            startRikkaMonitor();
        }
        recomputeState();
    }

    private synchronized void startOperitMonitor() {
        if (monitor != null) return;
        monitor = new OperitMonitor(this, this);
        monitor.start();
        Log.i(TAG, "started background monitor: Operit");
    }

    /** 启动 RikkaHub 监控（读端口配置） */
    private synchronized void startRikkaMonitor() {
        if (rikkaMonitor != null) return;
        int rkPort = PetRegistry.getIntPref(this, PetRegistry.KEY_RK_PORT,
                PetRegistry.DEFAULT_RK_PORT);
        rikkaMonitor = new RikkaHubMonitor(rkPort, new RikkaHubMonitor.Listener() {
            @Override
            public void onSnapshot(OperitState.Snapshot snapshot) {
                rikkaSnap = snapshot;
                if (PetRegistry.TARGET_RIKKA.equals(currentTarget)) {
                    recomputeState();
                }
            }

            @Override
            public void onError(String message) {
                Log.w(TAG, "rikkahub: " + message);
            }
        });
        rikkaMonitor.start();
        Log.i(TAG, "started background monitor: RikkaHub, port=" + rkPort);
    }

    /** 启动 ClawBench 监控（读端口+密码配置，轮询 /api/ai/sessions/overview） */
    private synchronized void startClawBenchMonitor() {
        if (clawbenchMonitor != null) return;
        int cbPort = PetRegistry.getClawbenchPort(this);
        clawbenchMonitor = new ClawBenchMonitor(this, cbPort, new ClawBenchMonitor.Listener() {
            @Override
            public void onSnapshot(OperitState.Snapshot snapshot) {
                clawbenchSnap = snapshot;
                if (PetRegistry.TARGET_CLAWBENCH.equals(currentTarget)) {
                    recomputeState();
                }
            }

            @Override
            public void onError(String message) {
                Log.w(TAG, "clawbench: " + message);
            }
        });
        clawbenchMonitor.start();
        Log.i(TAG, "started background monitor: ClawBench, port=" + cbPort);
    }

    /** 启动 pi-web 监控（读端口配置，SSE 订阅最近活跃会话的 agent 事件流） */
    private synchronized void startPiWebMonitor() {
        if (piwebMonitor != null) return;
        int pwPort = PetRegistry.getPiWebPort(this);
        piwebMonitor = new PiWebMonitor(this, pwPort, new PiWebMonitor.Listener() {
            @Override
            public void onSnapshot(OperitState.Snapshot snapshot) {
                piwebSnap = snapshot;
                if (PetRegistry.TARGET_PIWEB.equals(currentTarget)) {
                    recomputeState();
                }
            }

            @Override
            public void onError(String message) {
                Log.w(TAG, "piweb: " + message);
            }
        });
        piwebMonitor.start();
        Log.i(TAG, "started background monitor: pi-web, port=" + pwPort);
    }

    private synchronized void stopAllMonitors() {
        if (monitor != null) {
            monitor.stop();
            monitor = null;
        }
        if (rikkaMonitor != null) {
            rikkaMonitor.stop();
            rikkaMonitor = null;
        }
        if (clawbenchMonitor != null) {
            clawbenchMonitor.stop();
            clawbenchMonitor = null;
        }
        if (piwebMonitor != null) {
            piwebMonitor.stop();
            piwebMonitor = null;
        }
        operitSnap = null;
        rikkaSnap = null;
        clawbenchSnap = null;
        piwebSnap = null;
    }

    /** Operit 回调（OperitMonitor 实现 Listener 接口） */
    @Override
    public void onSnapshot(OperitState.Snapshot snapshot) {
        operitSnap = snapshot;
        if (PetRegistry.TARGET_OPERIT.equals(currentTarget)) {
            recomputeState();
        }
    }

    @Override
    public void onError(String message) {
        Log.w(TAG, "monitor error: " + message);
    }

    /** 使用当前目标 agent 的快照刷新桌宠 */
    private void recomputeState() {
        OperitState.Snapshot snap;
        if (PetRegistry.TARGET_CUSTOM_API.equals(currentTarget)) {
            snap = new OperitState.Snapshot();
            snap.state = (chatBridge != null && chatBridge.isChatting())
                    ? OperitState.RESPONDING
                    : OperitState.IDLE;
            snap.agentName = "自定义 API";
        } else if (PetRegistry.TARGET_RIKKA.equals(currentTarget)) {
            snap = rikkaSnap;
        } else if (PetRegistry.TARGET_CLAWBENCH.equals(currentTarget)) {
            snap = clawbenchSnap;
        } else if (PetRegistry.TARGET_PIWEB.equals(currentTarget)) {
            snap = piwebSnap;
        } else {
            snap = operitSnap;
        }
        if (snap == null) {
            snap = new OperitState.Snapshot();
            snap.state = OperitState.IDLE;
            if (PetRegistry.TARGET_RIKKA.equals(currentTarget)) {
                snap.agentName = "RikkaHub";
            } else if (PetRegistry.TARGET_CLAWBENCH.equals(currentTarget)) {
                snap.agentName = "ClawBench";
            } else if (PetRegistry.TARGET_PIWEB.equals(currentTarget)) {
                snap.agentName = "pi-web";
            } else {
                snap.agentName = "Operit";
            }
        }
        applyPetState(snap);
    }

    private void applyPetState(OperitState.Snapshot snap) {
        if (petView == null) return;

        // 状态变化：准确反映当前状态过渡（简练准确，杜绝跑任务时虚假提前宣告完成）
        if (lastState != null && lastState != snap.state
                && snap.state != OperitState.UNKNOWN) {
            showStateBubble(snap);
            if (snap.state == OperitState.ERROR && lastState != OperitState.ERROR) {
                showFailureBubble(null);
            }
        }
        lastState = snap.state;

        petView.updateState(snap.state);
        // 状态卡刷新状态与详细监控
        statusCard.update(snap);
        if (!isPetHidden) {
            updateNotification("桌宠 · " + snap.state.getLabel());
        } else {
            updateNotification("桌宠已隐藏（后台监控中 · 点击重新弹出）");
        }
    }

    /** 状态变化气泡：准确反映当前状态过渡，简明扼要，2.8s 自动优雅淡出 */
    private void showStateBubble(OperitState.Snapshot snap) {
        if (bubbleView == null || snap == null) return;
        // 不打断正在进行的 AI 聊天回复
        if (chatBridge != null && chatBridge.isChatting()) return;
        bubbleHandler.removeCallbacks(bubbleHideRunnable);

        OperitState state = snap.state != null ? snap.state : OperitState.IDLE;
        String text;
        switch (state) {
            case TOOL_RUNNING:
                String tool = (snap.lastTool != null && !snap.lastTool.isEmpty()) ? snap.lastTool : "运行中";
                text = "🔧 执行工具: " + tool;
                break;
            case THINKING:
                text = "🤔 思考分析中...";
                break;
            case RESPONDING:
                text = "💬 组织回复中...";
                break;
            case WORKING:
                text = "⚡ 正在处理任务...";
                break;
            case WAITING:
                text = "⏳ 等待响应...";
                break;
            case ERROR:
                text = "❌ 任务异常";
                break;
            case IDLE:
            default:
                if (lastState != null && (lastState == OperitState.RESPONDING || lastState == OperitState.WORKING || lastState == OperitState.TOOL_RUNNING)) {
                    text = "✅ 任务就绪 · 待命中";
                } else {
                    text = "😴 空闲待命";
                }
                break;
        }

        bubbleView.setText(text);
        layoutChatBubble();
        bubbleView.setVisibility(View.VISIBLE);
        bubbleView.setAlpha(0f);
        bubbleView.animate().alpha(1f).setDuration(180).start();
        scheduleBubbleHide(2800);
    }

    /** 动态切换角色形象并持久化保存 */
    public void changePet(String petDir) {
        if (petDir == null || petDir.isEmpty()) return;
        PetRegistry.setPetDir(this, petDir);
        if (petView != null) {
            petView.setPetDir(petDir);
        }
    }

    /** 读取并切换至当前 AI 模式绑定的专属角色形象 */
    public void applyModePet(String mode) {
        if (mode == null || mode.isEmpty()) return;
        String boundDir = PetRegistry.getPetDirForMode(this, mode);
        if (boundDir != null && !boundDir.isEmpty()) {
            changePet(boundDir);
        }
    }

    public PetChatBridge getChatBridge() {
        return chatBridge;
    }

    /** AI 配置变化（#operit/#api 指令）：刷新状态卡模式标签与输入框提示，并联动切换对应绑定的角色形象 */
    public void onChatConfigChanged() {
        ChatConfig config = ChatConfig.load(this);
        if (statusCard != null) {
            statusCard.setModeLabel(config.modeLabel());
        }
        if (chatBridge != null) {
            chatBridge.updateInputHint();
        }
        applyModePet(config.mode);
    }

    // ---------------- 前台服务 ----------------

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "桌宠服务", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("DevPetM 悬浮窗保活通知");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, buildNotification(text));
            }
        } catch (Throwable ignored) {}
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        // 状态栏通知快捷操作按键：支持一键重新弹出桌宠
        Intent toggleIntent = new Intent(this, PetOverlayService.class);
        toggleIntent.setAction(ACTION_TOGGLE_PET);
        PendingIntent togglePi = PendingIntent.getService(this, 102, toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String btnLabel = isPetHidden ? "🐾 重新弹出桌宠" : "🙈 隐藏桌宠";
        Notification.Action action = new Notification.Action.Builder(
                R.mipmap.ic_launcher, btnLabel, togglePi).build();
        builder.addAction(action);

        return builder
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("DevPetM")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopProactiveChatter();
        savePosition();
        PetRegistry.getPrefs(this).unregisterOnSharedPreferenceChangeListener(this);
        stopFling();
        releaseVelocityTracker();
        bubbleHandler.removeCallbacks(bubbleHideRunnable);
        if (petMenu != null) {
            petMenu.dismiss();
            petMenu = null;
        }
        if (monitor != null) {
            monitor.stop();
            monitor = null;
        }
        if (rikkaMonitor != null) {
            rikkaMonitor.stop();
            rikkaMonitor = null;
        }
        if (clawbenchMonitor != null) {
            clawbenchMonitor.stop();
            clawbenchMonitor = null;
        }
        if (piwebMonitor != null) {
            piwebMonitor.stop();
            piwebMonitor = null;
        }
        if (overlayRoot != null) {
            try {
                windowManager.removeView(overlayRoot);
            } catch (Exception ignored) {}
            overlayRoot = null;
        }
        isRunning = false;
        PetRegistry.setPetEnabled(this, false);
        if (sInstance == this) {
            sInstance = null;
        }
        try {
            sendBroadcast(new Intent(ACTION_OVERLAY_STATE_CHANGED));
        } catch (Throwable ignored) {}
        super.onDestroy();
    }

    public int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}