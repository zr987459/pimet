package com.xm486.pimet;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import rikka.shizuku.Shizuku;

import com.xm486.pimet.proot.PiWebManager;
import com.xm486.pimet.proot.ProotManager;
import com.xm486.pimet.proot.ProotSession;
import com.xm486.pimet.terminal.AnsiParser;
import com.xm486.pimet.monitor.OperitState;
import com.xm486.pimet.monitor.PiWebMonitor;
import com.xm486.pimet.pet.BubbleMessage;
import com.xm486.pimet.pet.ChatConfig;
import com.xm486.pimet.pet.PetDexShop;
import com.xm486.pimet.pet.PetOverlayService;
import com.xm486.pimet.pet.PetParamsDialog;
import com.xm486.pimet.pet.PetRegistry;
import com.xm486.pimet.pet.PetTypewriter;
import com.xm486.pimet.pet.SpritePetView;
import com.xm486.pimet.ui.StateStyle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import java.io.File;
import java.io.FileOutputStream;

/**
 * PiMet 主界面：深度融合 PRoot 独立 Linux 容器、全功能 Web 控制台、交互终端与系统设置
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // 页面与导航 Tab
    private View viewLaunch;
    private View viewPiWeb;
    private View viewPlugins;
    private View viewTerminal;
    private View viewSettings;

    private LinearLayout tabLaunch;
    private LinearLayout tabPiWeb;
    private LinearLayout tabPlugins;
    private LinearLayout tabSettings;

    private TextView tabLaunchText;
    private TextView tabPiWebText;
    private TextView tabPluginsText;
    private TextView tabSettingsText;

    // 操控台融合终端控制组件
    private TextView btnTermMaximize;
    private TextView btnTermClose;
    private boolean isTermMaximized = false;

    // 桌面宠物组件
    private SpritePetView floatingPetView;
    private LinearLayout petBubbleLayout;
    private TextView petBubbleTv;
    private LinearLayout floatingPetChatCard;
    private View viewPetStatusDot;
    private TextView tvPetStatusText;
    private View btnHidePetChatCard;
    private View btnPetChipDance;
    private View btnPetChipWave;
    private View btnPetChipJump;
    private View btnPetChipTest;
    private View btnPetChipSwitch;
    private EditText inputFloatPetChat;
    private View btnSendFloatPetChat;

    private TextView btnFloatPetChat;
    private TextView btnFloatPetSwitch;
    private TextView tvCurrentPetName;
    private TextView btnTogglePetEnabled;
    private TextView btnPetParams;
    private TextView btnPetShop;
    private TextView btnToggleGlobalOverlay;

    // 设置页桌宠预览与调节组件
    private SpritePetView previewPetView;
    private TextView previewPetNameTv;
    private TextView previewPetDescTv;
    private View btnPreviewPlayAction;
    private LinearLayout layoutPetThumbList;
    private TextView tvPetSizeLabel;
    private SeekBar sbPetSize;
    private TextView tvBubbleWidthLabel;
    private SeekBar sbBubbleWidth;
    private static final android.util.LruCache<String, Bitmap> PET_THUMB_CACHE = new android.util.LruCache<>(24);

    private PiWebMonitor piWebMonitor;
    private OperitState.Snapshot lastPetSnapshot = new OperitState.Snapshot();
    private final Handler petBubbleHandler = new Handler(Looper.getMainLooper());
    private final Runnable petBubbleDismissRunnable = () -> {
        if (petBubbleLayout != null) {
            petBubbleLayout.setVisibility(View.GONE);
        }
    };
    private int petInteractionCount = 0;

    // 插件生态管理组件
    private View btnRefreshPlugins;
    private TextView chipCatAll;
    private TextView chipCatExtensions;
    private TextView chipCatSkills;
    private TextView chipCatMcp;
    private TextView chipCatSubagents;
    private LinearLayout layoutPluginItems;
    private TextView tvPluginEmpty;
    private EditText inputCustomPlugin;
    private View btnInstallCustomPlugin;
    private int currentPluginCategory = 0;
    private TextView settingsShizukuStatusTv;
    private View btnSyncAiFromContainer;

    // Launch (启动/仪表盘) 视图组件
    private View launchStatusDot;
    private TextView launchStateTv;
    private TextView launchPortBadge;
    private TextView launchSubtitleTv;
    private ProgressBar launchProgressBar;
    private TextView btnLaunchMain;
    private View btnLaunchRestart;
    private View btnLaunchStop;
    private View btnLaunchDeploy;
    private TextView launchMetricPortTv;
    private TextView launchMetricEnvTv;
    private TextView launchMetricSizeTv;
    private View btnActionCopyUrl;
    private TextView launchCopyUrlDescTv;
    private View btnActionOpenBrowser;
    private View btnActionModelConfig;
    private TextView launchModelDescTv;
    private View btnLaunchLogRefresh;
    private View btnLaunchLogClear;
    private TextView launchLogTv;

    // Pi-Web 工作台视图组件
    private View piWebStatusDot;
    private TextView piWebTitleTv;
    private View piWebReloadBtn;
    private View piWebBrowserBtn;
    private WebView piWebWebView;
    private View piWebOfflineCard;
    private TextView piWebOfflineSubTv;
    private View piWebWakeBtn;

    // PRoot 终端视图组件
    private TextView terminalOutput;
    private ScrollView terminalScrollView;
    private EditText commandInput;
    private View btnSend;
    private View btnClear;
    private View btnCtrlC;
    private TextView btnTermFontDec;
    private TextView btnTermFontInc;
    private View btnTermQuickWeb;
    private View btnTermReconnect;
    private View btnTermCustomKey;
    private View btnTermImportFile;
    private LinearLayout termToolbarContainer;
    private HorizontalScrollView termuxKeysBar;
    private LinearLayout termuxKeysContainer;
    private TextView btnToggleKeysBar;
    private View slashSuggestCard;
    private LinearLayout slashSuggestContainer;
    private View btnSlashSuggestClose;
    private float currentTermFontSize = 12.0f;

    // 历史命令与 Termux 快捷按键模型
    private final List<String> commandHistory = new ArrayList<>();
    private int historyIndex = -1;
    private final List<ShortcutKey> shortcutKeys = new ArrayList<>();

    // 终端快捷键数据模型
    public static class ShortcutKey {
        public String id;
        public String label;
        public String command;
        public String description;
        public boolean isDirectRun;
        public boolean isSystem;

        public ShortcutKey(String id, String label, String command, String description, boolean isDirectRun, boolean isSystem) {
            this.id = id;
            this.label = label;
            this.command = command;
            this.description = description;
            this.isDirectRun = isDirectRun;
            this.isSystem = isSystem;
        }

        public JSONObject toJson() {
            try {
                JSONObject obj = new JSONObject();
                obj.put("id", id);
                obj.put("label", label);
                obj.put("command", command);
                obj.put("description", description);
                obj.put("isDirectRun", isDirectRun);
                obj.put("isSystem", isSystem);
                return obj;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        public static ShortcutKey fromJson(JSONObject obj) {
            return new ShortcutKey(
                obj.optString("id", String.valueOf(System.currentTimeMillis())),
                obj.optString("label", "KEY"),
                obj.optString("command", ""),
                obj.optString("description", ""),
                obj.optBoolean("isDirectRun", false),
                obj.optBoolean("isSystem", false)
            );
        }
    }

    // 多窗口终端架构
    private static class TerminalTab {
        int id;
        String title;
        ProotSession session;
        SpannableStringBuilder buffer = new SpannableStringBuilder();
        AnsiParser ansi = new AnsiParser();

        TerminalTab(int id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    private final List<TerminalTab> terminalTabs = new ArrayList<>();
    private int activeTabId = -1;
    private int nextTabId = 1;
    private LinearLayout termTabsContainer;
    private View btnNewTab;

    // 布局全屏与增强组件
    private View bottomNavBar;
    private ProgressBar piWebProgressBar;
    private FrameLayout floatingMenuContainer;
    private View floatingMenuVertical;
    private TextView floatingBall;
    private TextView btnFloatFullscreen;
    private View btnFloatTerminal;
    private View btnFloatReload;
    private TextView btnFloatZoom;
    private View btnFloatImport;
    private View btnFloatBrowser;
    private View btnFloatClose;

    // 文件选择与导入回调
    private ValueCallback<Uri[]> filePathCallback;
    private static final int REQUEST_CODE_FILE_CHOOSER = 1001;
    private static final int REQUEST_CODE_IMPORT_CONTAINER = 1002;
    private static final int REQUEST_CODE_PERMISSIONS = 1003;
    private boolean isFullscreen = false;

    // 拖拽手势状态
    private float floatDownRawX, floatDownRawY;
    private float floatInitialX, floatInitialY;
    private boolean isFloatDragging = false;

    // 设置视图组件
    private TextView chipProviderDeepSeek;
    private TextView chipProviderOpenAI;
    private TextView chipProviderClaude;
    private TextView chipProviderOpenRouter;
    private TextView chipProviderCustom;
    private EditText inputAiApiKey;
    private View btnToggleKeyVisibility;
    private EditText inputAiBaseUrl;
    private EditText inputAiModel;
    private View btnSaveAiConfig;
    private View btnFetchAiModels;
    private String selectedProvider = PiMetConfig.PROVIDER_DEEPSEEK;
    private boolean isApiKeyVisible = false;

    private EditText settingsPortInput;
    private View btnSavePort;
    private TextView btnRegistryMirror;
    private TextView btnRegistryOfficial;
    private TextView settingsStorageTv;
    private View btnClearNpmCache;
    private View btnResetContainer;
    private TextView btnBatteryIgnoreOpt;
    private View btnAutoStartSettings;
    private TextView btnPrivilegeRoot;
    private TextView btnPrivilegeShizuku;
    private TextView btnPrivilegeAllFiles;
    private TextView btnSyncClipboard;
    private View btnTermClipboard;
    private View btnFloatClipboard;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isPiWebAlive = false;
    private boolean isDeploying = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        checkStoragePermissions();
        checkBatteryOptimizationPermission();
        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);
        } catch (Throwable ignored) {}
        initViews();
        initNavigation();
        initLaunchPanel();
        initPiWebView();
        initPluginsPanel();
        initTerminalPanel();
        initSettingsPanel();

        // 彻底清理历史自动注入的预设插件，杜绝生态污染与配置冲突
        PluginManager.cleanSelfAddedPlugins(this);

        // 启动时在后台静默尝试从容器反向同步最新 AI 凭据配置
        new Thread(() -> {
            if (PiMetConfig.syncFromContainer(this)) {
                mainHandler.post(this::refreshSettingsUiFields);
            }
        }).start();

        // 启动主终端会话
        if (ProotManager.isRootfsInstalled(this)) {
            createTab(true);
        }

        // 首次状态自检
        checkServiceStatus();

        if (getIntent() != null && getIntent().getBooleanExtra("pimet.open_terminal", false)) {
            mainHandler.postDelayed(this::openTerminalInWorkbench, 300);
        }
    }

    private String getPiWebUrl() {
        int port = PiMetConfig.getWebPort(this);
        return "http://127.0.0.1:" + port;
    }

    private void initViews() {
        // 四个主视图 -> 五个主视图
        viewLaunch = findViewById(R.id.viewLaunch);
        viewPiWeb = findViewById(R.id.viewPiWeb);
        viewPlugins = findViewById(R.id.viewPlugins);
        viewTerminal = findViewById(R.id.viewTerminal);
        viewSettings = findViewById(R.id.viewSettings);

        // 底栏 Tab (4 栏: 启动, 操控台, 插件, 设置)
        tabLaunch = findViewById(R.id.tabLaunch);
        tabPiWeb = findViewById(R.id.tabPiWeb);
        tabPlugins = findViewById(R.id.tabPlugins);
        tabSettings = findViewById(R.id.tabSettings);

        tabLaunchText = findViewById(R.id.tabLaunchText);
        tabPiWebText = findViewById(R.id.tabPiWebText);
        tabPluginsText = findViewById(R.id.tabPluginsText);
        tabSettingsText = findViewById(R.id.tabSettingsText);

        // 底栏导航
        bottomNavBar = findViewById(R.id.bottomNavBar);

        // Launch 组件
        launchStatusDot = findViewById(R.id.launchStatusDot);
        launchStateTv = findViewById(R.id.launchStateTv);
        launchPortBadge = findViewById(R.id.launchPortBadge);
        launchSubtitleTv = findViewById(R.id.launchSubtitleTv);
        launchProgressBar = findViewById(R.id.launchProgressBar);
        btnLaunchMain = findViewById(R.id.btnLaunchMain);
        btnLaunchRestart = findViewById(R.id.btnLaunchRestart);
        btnLaunchStop = findViewById(R.id.btnLaunchStop);
        btnLaunchDeploy = findViewById(R.id.btnLaunchDeploy);
        launchMetricPortTv = findViewById(R.id.launchMetricPortTv);
        launchMetricEnvTv = findViewById(R.id.launchMetricEnvTv);
        launchMetricSizeTv = findViewById(R.id.launchMetricSizeTv);
        btnActionCopyUrl = findViewById(R.id.btnActionCopyUrl);
        launchCopyUrlDescTv = findViewById(R.id.launchCopyUrlDescTv);
        btnActionOpenBrowser = findViewById(R.id.btnActionOpenBrowser);
        btnActionModelConfig = findViewById(R.id.btnActionModelConfig);
        launchModelDescTv = findViewById(R.id.launchModelDescTv);
        btnLaunchLogRefresh = findViewById(R.id.btnLaunchLogRefresh);
        btnLaunchLogClear = findViewById(R.id.btnLaunchLogClear);
        launchLogTv = findViewById(R.id.launchLogTv);

        // Pi-Web 组件
        piWebProgressBar = findViewById(R.id.piWebProgressBar);
        piWebWebView = findViewById(R.id.piWebWebView);
        piWebOfflineCard = findViewById(R.id.piWebOfflineCard);
        piWebOfflineSubTv = findViewById(R.id.piWebOfflineSubTv);
        piWebWakeBtn = findViewById(R.id.piWebWakeBtn);

        floatingMenuContainer = findViewById(R.id.floatingMenuContainer);
        floatingMenuVertical = findViewById(R.id.floatingMenuVertical);
        floatingBall = findViewById(R.id.floatingBall);
        floatingPetView = findViewById(R.id.floatingPetView);
        petBubbleLayout = findViewById(R.id.petBubbleLayout);
        petBubbleTv = findViewById(R.id.petBubbleTv);
        btnFloatPetChat = findViewById(R.id.btnFloatPetChat);
        btnFloatPetSwitch = findViewById(R.id.btnFloatPetSwitch);
        btnFloatFullscreen = findViewById(R.id.btnFloatFullscreen);
        btnFloatTerminal = findViewById(R.id.btnFloatTerminal);
        btnFloatReload = findViewById(R.id.btnFloatReload);
        btnFloatZoom = findViewById(R.id.btnFloatZoom);
        btnFloatImport = findViewById(R.id.btnFloatImport);
        btnFloatBrowser = findViewById(R.id.btnFloatBrowser);
        btnFloatClose = findViewById(R.id.btnFloatClose);

        // Terminal 组件 (操控台内置)
        btnTermMaximize = findViewById(R.id.btnTermMaximize);
        btnTermClose = findViewById(R.id.btnTermClose);
        termToolbarContainer = findViewById(R.id.termToolbarContainer);
        btnTermFontDec = findViewById(R.id.btnTermFontDec);
        btnTermFontInc = findViewById(R.id.btnTermFontInc);
        btnTermReconnect = findViewById(R.id.btnTermReconnect);
        btnTermCustomKey = findViewById(R.id.btnTermCustomKey);
        btnTermImportFile = findViewById(R.id.btnTermImportFile);
        btnClear = findViewById(R.id.btnClear);
        btnCtrlC = findViewById(R.id.btnCtrlC);
        btnTermQuickWeb = findViewById(R.id.btnTermQuickWeb);
        terminalOutput = findViewById(R.id.terminalOutput);
        terminalScrollView = findViewById(R.id.terminalScrollView);
        slashSuggestCard = findViewById(R.id.slashSuggestCard);
        slashSuggestContainer = findViewById(R.id.slashSuggestContainer);
        btnSlashSuggestClose = findViewById(R.id.btnSlashSuggestClose);
        termuxKeysBar = findViewById(R.id.termuxKeysBar);
        termuxKeysContainer = findViewById(R.id.termuxKeysContainer);
        btnToggleKeysBar = findViewById(R.id.btnToggleKeysBar);
        commandInput = findViewById(R.id.commandInput);
        btnSend = findViewById(R.id.btnSend);

        // Settings 组件
        chipProviderDeepSeek = findViewById(R.id.chipProviderDeepSeek);
        chipProviderOpenAI = findViewById(R.id.chipProviderOpenAI);
        chipProviderClaude = findViewById(R.id.chipProviderClaude);
        chipProviderOpenRouter = findViewById(R.id.chipProviderOpenRouter);
        chipProviderCustom = findViewById(R.id.chipProviderCustom);
        inputAiApiKey = findViewById(R.id.inputAiApiKey);
        btnToggleKeyVisibility = findViewById(R.id.btnToggleKeyVisibility);
        inputAiBaseUrl = findViewById(R.id.inputAiBaseUrl);
        inputAiModel = findViewById(R.id.inputAiModel);
        btnSaveAiConfig = findViewById(R.id.btnSaveAiConfig);
        btnFetchAiModels = findViewById(R.id.btnFetchAiModels);

        settingsPortInput = findViewById(R.id.settingsPortInput);
        btnSavePort = findViewById(R.id.btnSavePort);
        btnRegistryMirror = findViewById(R.id.btnRegistryMirror);
        btnRegistryOfficial = findViewById(R.id.btnRegistryOfficial);
        settingsStorageTv = findViewById(R.id.settingsStorageTv);
        btnClearNpmCache = findViewById(R.id.btnClearNpmCache);
        btnResetContainer = findViewById(R.id.btnResetContainer);
        btnBatteryIgnoreOpt = findViewById(R.id.btnBatteryIgnoreOpt);
        btnAutoStartSettings = findViewById(R.id.btnAutoStartSettings);
        btnPrivilegeRoot = findViewById(R.id.btnPrivilegeRoot);
        btnPrivilegeShizuku = findViewById(R.id.btnPrivilegeShizuku);
        settingsShizukuStatusTv = findViewById(R.id.settingsShizukuStatusTv);
        btnPrivilegeAllFiles = findViewById(R.id.btnPrivilegeAllFiles);
        btnSyncClipboard = findViewById(R.id.btnSyncClipboard);
        btnTermClipboard = findViewById(R.id.btnTermClipboard);
        btnFloatClipboard = findViewById(R.id.btnFloatClipboard);

        // 桌面宠物设置组件
        tvCurrentPetName = findViewById(R.id.tvCurrentPetName);
        btnTogglePetEnabled = findViewById(R.id.btnTogglePetEnabled);
        btnPetParams = findViewById(R.id.btnPetParams);
        btnPetShop = findViewById(R.id.btnPetShop);
        btnToggleGlobalOverlay = findViewById(R.id.btnToggleGlobalOverlay);

        // 终端多窗口 Tab 容器与新建按钮
        termTabsContainer = findViewById(R.id.termTabsContainer);
        btnNewTab = findViewById(R.id.btnNewTab);
        if (btnNewTab != null) {
            btnNewTab.setOnClickListener(v -> createTab(true));
        }
    }

    private void initNavigation() {
        tabLaunch.setOnClickListener(v -> switchTab(0));
        tabPiWeb.setOnClickListener(v -> switchTab(1));
        if (tabPlugins != null) tabPlugins.setOnClickListener(v -> switchTab(2));
        tabSettings.setOnClickListener(v -> switchTab(3));
    }

    private void switchTab(int index) {
        viewLaunch.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        viewPiWeb.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        if (viewPlugins != null) viewPlugins.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        viewSettings.setVisibility(index == 3 ? View.VISIBLE : View.GONE);

        // 更新底栏颜色
        int activeColor = 0xFF58A6FF;
        int normalColor = 0xFF8B949E;

        tabLaunchText.setTextColor(index == 0 ? activeColor : normalColor);
        tabPiWebText.setTextColor(index == 1 ? activeColor : normalColor);
        if (tabPluginsText != null) tabPluginsText.setTextColor(index == 2 ? activeColor : normalColor);
        tabSettingsText.setTextColor(index == 3 ? activeColor : normalColor);

        if (index == 0) {
            checkServiceStatus();
            refreshLaunchLog();
        } else if (index == 1) {
            updatePiWebDisplay();
        } else if (index == 2) {
            // 插件与生态中心
            refreshPluginsList(currentPluginCategory);
        } else if (index == 3) {
            refreshStorageSize();
            refreshPrivilegeStatus();
            updatePetPreview();
            buildPetList();
            new Thread(() -> {
                if (PiMetConfig.syncFromContainer(this)) {
                    mainHandler.post(this::refreshSettingsUiFields);
                }
            }).start();
            if (tvCurrentPetName != null) {
                tvCurrentPetName.setText("当前角色: " + PetRegistry.getPetDir(this) + " (全屏悬浮桌宠互动/拖拽奔跑)");
            }
        }
    }

    public void openTerminalInWorkbench() {
        switchTab(1);
        if (viewTerminal != null) {
            viewTerminal.setVisibility(View.VISIBLE);
        }
        if (terminalTabs.isEmpty()) {
            createTab(true);
        } else {
            TerminalTab active = getActiveTab();
            if (active != null) {
                terminalOutput.setText(active.buffer);
                if (active.session == null || !active.session.isRunning()) {
                    restartActiveTab();
                }
            }
        }
        refreshTabsUi();
        terminalScrollView.post(() -> terminalScrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }

    public void closeTerminalInWorkbench() {
        if (viewTerminal != null) {
            viewTerminal.setVisibility(View.GONE);
        }
    }

    public void toggleTerminalInWorkbench() {
        if (viewTerminal != null) {
            if (viewTerminal.getVisibility() == View.VISIBLE) {
                closeTerminalInWorkbench();
            } else {
                openTerminalInWorkbench();
            }
        }
    }

    private void showPetBubble(String msg) {
        if (petBubbleLayout == null || petBubbleTv == null) return;
        petBubbleHandler.removeCallbacks(petBubbleDismissRunnable);
        int maxW = dpToPx(PetRegistry.getIntPref(this, PetRegistry.KEY_BUBBLE_WIDTH, PetRegistry.DEFAULT_BUBBLE_WIDTH));
        ViewGroup.LayoutParams lp = petBubbleLayout.getLayoutParams();
        if (lp != null) {
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            petBubbleLayout.setLayoutParams(lp);
        }
        petBubbleTv.setMaxWidth(maxW);
        petBubbleLayout.setVisibility(View.VISIBLE);
        petBubbleTv.setText("");
        final int len = msg != null ? msg.length() : 0;
        final String text = msg != null ? msg : "";
        final int[] idx = new int[]{0};
        Runnable typer = new Runnable() {
            @Override
            public void run() {
                if (idx[0] < len) {
                    idx[0]++;
                    petBubbleTv.setText(text.substring(0, idx[0]));
                    petBubbleHandler.postDelayed(this, 26);
                } else {
                    int delay = Math.max(3500, Math.min(8000, len * 120));
                    petBubbleHandler.postDelayed(petBubbleDismissRunnable, delay);
                }
            }
        };
        petBubbleHandler.post(typer);
    }

    private void updatePetDisplay(boolean isPetEnabled) {
        if (floatingPetView == null || floatingBall == null) return;
        boolean shouldShowPet = isPetEnabled || isFullscreen;
        if (shouldShowPet) {
            floatingPetView.setVisibility(View.VISIBLE);
            floatingBall.setVisibility(View.GONE);
            floatingPetView.startTicker();
        } else {
            floatingPetView.setVisibility(View.GONE);
            floatingBall.setVisibility(View.VISIBLE);
            floatingPetView.stopTicker();
            if (floatingPetChatCard != null) {
                floatingPetChatCard.setVisibility(View.GONE);
            }
        }
        if (tvCurrentPetName != null) {
            tvCurrentPetName.setText("当前角色: " + PetRegistry.getPetDir(this) + " (全屏悬浮桌宠互动/拖拽奔跑)");
        }
        if (btnTogglePetEnabled != null) {
            btnTogglePetEnabled.setText(isPetEnabled ? "🐾 悬浮桌宠: 开启" : "⚪ 悬浮桌宠: 关闭");
        }
    }

    private void updatePetPreview() {
        String curDir = PetRegistry.getPetDir(this);
        if (previewPetView != null) {
            previewPetView.setPetDir(curDir);
            previewPetView.startTicker();
        }
        if (previewPetNameTv != null) {
            previewPetNameTv.setText(curDir);
            for (PetRegistry.PetInfo info : PetRegistry.loadPets(this)) {
                if (info.dir.equals(curDir)) {
                    previewPetNameTv.setText(info.displayName + " (" + info.id + ")");
                    if (previewPetDescTv != null && !TextUtils.isEmpty(info.description)) {
                        previewPetDescTv.setText(info.description);
                    }
                    break;
                }
            }
        }
    }

    private Bitmap loadPetThumb(PetRegistry.PetInfo pet) {
        Bitmap cached = PET_THUMB_CACHE.get(pet.dir);
        if (cached != null) return cached;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 2;
            Bitmap full = null;
            if (pet.external) {
                java.io.File f = new java.io.File(PetRegistry.getExternalPetsDir(this), pet.dir + "/" + pet.spriteFile);
                if (f.exists()) {
                    full = BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
                }
            }
            if (full == null) {
                String path = "pets/" + pet.dir + "/" + pet.spriteFile;
                try (java.io.InputStream in = getAssets().open(path)) {
                    full = BitmapFactory.decodeStream(in, null, opts);
                }
            }
            if (full == null) return null;
            int cellW = Math.max(1, full.getWidth() / 4);
            int cellH = Math.max(1, full.getHeight() / 4);
            Bitmap frame = Bitmap.createBitmap(full, 0, 0, cellW, cellH);
            if (full != frame) full.recycle();
            if (frame != null) {
                PET_THUMB_CACHE.put(pet.dir, frame);
            }
            return frame;
        } catch (Throwable t) {
            Log.w("PiMet.Pet", "loadPetThumb failed: " + pet.dir, t);
            return null;
        }
    }

    private void buildPetList() {
        if (layoutPetThumbList == null) return;
        layoutPetThumbList.removeAllViews();
        java.util.List<PetRegistry.PetInfo> pets = PetRegistry.loadPets(this);
        String currentDir = PetRegistry.getPetDir(this);
        int thumbSize = dpToPx(52);

        for (PetRegistry.PetInfo pet : pets) {
            boolean selected = pet.dir.equals(currentDir);
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6));
            item.setBackgroundResource(selected ? R.drawable.bg_btn_primary : R.drawable.bg_sunken);

            ImageView iv = new ImageView(this);
            Bitmap bmp = loadPetThumb(pet);
            if (bmp != null) {
                iv.setImageBitmap(bmp);
            } else {
                iv.setImageResource(R.drawable.bg_logo);
            }
            LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(thumbSize, thumbSize);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            item.addView(iv, ivLp);

            TextView tv = new TextView(this);
            tv.setText(pet.displayName);
            tv.setTextSize(11f);
            tv.setTextColor(selected ? 0xFFFFFFFF : 0xFF8B949E);
            tv.setGravity(Gravity.CENTER);
            tv.setMaxLines(1);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(thumbSize + dpToPx(8), LinearLayout.LayoutParams.WRAP_CONTENT);
            tvLp.topMargin = dpToPx(3);
            item.addView(tv, tvLp);

            item.setOnClickListener(v -> {
                PetRegistry.setPetDir(this, pet.dir);
                updatePetDisplay(PetRegistry.isPetEnabled(this));
                updatePetPreview();
                if (floatingPetView != null) {
                    floatingPetView.setPetDir(pet.dir);
                    floatingPetView.playOneShot("waving");
                }
                showPetBubble("已切换为 " + pet.displayName + "，请多指教呀！ฅ'ω'ฅ");
                buildPetList();
            });

            item.setOnLongClickListener(v -> {
                confirmDeletePet(pet);
                return true;
            });

            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            itemLp.rightMargin = dpToPx(8);
            layoutPetThumbList.addView(item, itemLp);
        }
    }

    private void confirmDeletePet(PetRegistry.PetInfo pet) {
        String typeDesc = pet.external ? "外部导入" : "内置角色";
        new AlertDialog.Builder(this)
                .setTitle("删除桌宠形象")
                .setMessage("确定要删除「" + pet.displayName + "」（" + typeDesc + "）吗？\n\n"
                        + (pet.external ? "本地导入的角色素材将被彻底清除。" : "内置角色删除后可在角色商城中恢复。"))
                .setPositiveButton("删除", (dialog, which) -> {
                    boolean wasSelected = pet.dir.equals(PetRegistry.getPetDir(this));
                    PetRegistry.deletePet(this, pet);
                    PET_THUMB_CACHE.remove(pet.dir);
                    Toast.makeText(this, "已删除形象「" + pet.displayName + "」", Toast.LENGTH_SHORT).show();
                    java.util.List<PetRegistry.PetInfo> remaining = PetRegistry.loadPets(this);
                    if (wasSelected && !remaining.isEmpty()) {
                        String fallback = remaining.get(0).dir;
                        PetRegistry.setPetDir(this, fallback);
                    }
                    updatePetDisplay(PetRegistry.isPetEnabled(this));
                    updatePetPreview();
                    buildPetList();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showPetSwitchDialog() {
        java.util.List<PetRegistry.PetInfo> pets = PetRegistry.loadPets(this);
        String[] names = new String[pets.size()];
        for (int i = 0; i < pets.size(); i++) {
            names[i] = pets.get(i).displayName + " (" + pets.get(i).dir + ")";
        }
        new AlertDialog.Builder(this)
                .setTitle("🐾 选择切换桌宠伙伴")
                .setItems(names, (dialog, which) -> {
                    PetRegistry.PetInfo selected = pets.get(which);
                    PetRegistry.setPetDir(this, selected.dir);
                    if (floatingPetView != null) {
                        floatingPetView.setPetDir(selected.dir);
                        floatingPetView.playOneShot("waving");
                    }
                    updatePetDisplay(PetRegistry.isPetEnabled(this));
                    updatePetPreview();
                    buildPetList();
                    showPetBubble("主人~ 我是 " + selected.displayName + "，请多关照！(≧∇≦)ﾉ");
                    Toast.makeText(this, "已切换为桌宠: " + selected.displayName, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void togglePetChatCard(Boolean forceShow) {
        if (floatingPetChatCard == null) return;
        boolean willShow = forceShow != null ? forceShow : (floatingPetChatCard.getVisibility() != View.VISIBLE);
        if (willShow) {
            floatingPetChatCard.setVisibility(View.VISIBLE);
            floatingPetChatCard.setAlpha(0f);
            floatingPetChatCard.setScaleX(0.92f);
            floatingPetChatCard.setScaleY(0.92f);
            floatingPetChatCard.animate()
                    .alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(160)
                    .start();
            if (floatingPetView != null) {
                floatingPetView.playOneShot("waving");
            }
            updateFloatingPetChatCardStatus();
        } else {
            floatingPetChatCard.animate()
                    .alpha(0f).scaleX(0.92f).scaleY(0.92f)
                    .setDuration(120)
                    .withEndAction(() -> floatingPetChatCard.setVisibility(View.GONE))
                    .start();
        }
    }

    private void updateFloatingPetChatCardStatus() {
        if (tvPetStatusText == null || viewPetStatusDot == null) return;
        if (lastPetSnapshot != null && lastPetSnapshot.state != null) {
            switch (lastPetSnapshot.state) {
                case THINKING:
                    tvPetStatusText.setText("🤔 Agent 深度思考中...");
                    viewPetStatusDot.setBackgroundResource(R.drawable.bg_status_dot_yellow);
                    break;
                case TOOL_RUNNING:
                    String tool = TextUtils.isEmpty(lastPetSnapshot.lastTool) ? "工具中" : lastPetSnapshot.lastTool;
                    tvPetStatusText.setText("🔧 执行: " + tool);
                    viewPetStatusDot.setBackgroundResource(R.drawable.bg_status_dot_yellow);
                    break;
                case RESPONDING:
                    tvPetStatusText.setText("💬 Agent 组织回复...");
                    viewPetStatusDot.setBackgroundResource(R.drawable.bg_status_dot_yellow);
                    break;
                case ERROR:
                    tvPetStatusText.setText("😱 遇到异常: " + (lastPetSnapshot.lastTool != null ? lastPetSnapshot.lastTool : ""));
                    viewPetStatusDot.setBackgroundResource(R.drawable.bg_status_dot_gray);
                    break;
                case IDLE:
                default:
                    tvPetStatusText.setText("🟢 随时待命就绪");
                    viewPetStatusDot.setBackgroundResource(R.drawable.bg_status_dot_green);
                    break;
            }
        } else {
            tvPetStatusText.setText("🟢 随时待命就绪");
            viewPetStatusDot.setBackgroundResource(R.drawable.bg_status_dot_green);
        }
    }

    private void initPetMonitor() {
        int port = PiMetConfig.getWebPort(this);
        if (piWebMonitor != null) {
            piWebMonitor.stop();
        }
        piWebMonitor = new PiWebMonitor(this, port, new PiWebMonitor.Listener() {
            @Override
            public void onSnapshot(OperitState.Snapshot snapshot) {
                lastPetSnapshot = snapshot;
                updateFloatingPetChatCardStatus();
                if (!PetRegistry.isPetEnabled(MainActivity.this) && !isFullscreen) {
                    return;
                }
                if (floatingPetView != null) {
                    floatingPetView.updateState(snapshot.state);
                }
                switch (snapshot.state) {
                    case THINKING:
                        showPetBubble("🤔 Agent 正在深度思考中...");
                        break;
                    case TOOL_RUNNING:
                        String tool = TextUtils.isEmpty(snapshot.lastTool) ? "指令执行" : snapshot.lastTool;
                        showPetBubble("🔧 正在调用工具: " + tool);
                        break;
                    case RESPONDING:
                        showPetBubble("💬 Agent 正在组织回复...");
                        break;
                    case IDLE:
                        if (floatingPetView != null) {
                            floatingPetView.playOneShot("jumping");
                        }
                        showPetBubble("🎉 任务完成！随时待命");
                        break;
                    case ERROR:
                        showPetBubble("😱 任务出错啦: " + snapshot.lastTool);
                        break;
                    default:
                        break;
                }
            }

            @Override
            public void onError(String message) {}
        });
        piWebMonitor.start();
    }

    private void showPetMenuDialog() {
        String curPet = PetRegistry.getPetDir(this);
        LinearLayout menuLayout = new LinearLayout(this);
        menuLayout.setOrientation(LinearLayout.VERTICAL);
        menuLayout.setPadding(dpToPx(20), dpToPx(16), dpToPx(20), dpToPx(10));

        // 宠物大小滑块
        int curSize = PetRegistry.getIntPref(this, PetRegistry.KEY_PET_SIZE, PetRegistry.DEFAULT_PET_SIZE);
        TextView tvSize = new TextView(this);
        tvSize.setText("📏 宠物显示大小: " + curSize + " dp");
        tvSize.setTextColor(0xFFC9D1D9);
        tvSize.setTextSize(12f);
        menuLayout.addView(tvSize);

        SeekBar sbSize = new SeekBar(this);
        sbSize.setMax(120 - 32);
        sbSize.setProgress(curSize - 32);
        sbSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int val = 32 + progress;
                tvSize.setText("📏 宠物显示大小: " + val + " dp");
                PetRegistry.setIntPref(MainActivity.this, PetRegistry.KEY_PET_SIZE, val);
                applyPetParams();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        menuLayout.addView(sbSize);

        String[] menuItems = new String[]{
                "🔄 切换角色形象 (当前: " + curPet + ")",
                "💬 打开桌宠对话卡片",
                "💻 打开 PRoot Linux 终端抽屉",
                "💃 来段才艺互动 (跳舞/挥手/翻跟斗)",
                "🎛️ 更多物理参数调节",
                "🏪 宠物社区商店与素材",
                "🌐 系统全局悬浮窗 (" + (PetOverlayService.isRunning() ? "🟢 运行中·点击关闭" : "⚪ 未开启·点击开启") + ")"
        };

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("🐾 桌宠伴侣控制台")
                .setView(menuLayout)
                .setItems(menuItems, (d, which) -> {
                    switch (which) {
                        case 0:
                            showPetSwitchDialog();
                            break;
                        case 1:
                            togglePetChatCard(true);
                            break;
                        case 2:
                            openTerminalInWorkbench();
                            break;
                        case 3:
                            if (floatingPetView != null) {
                                String[] acts = {"dancing", "waving", "jumping"};
                                String act = acts[new java.util.Random().nextInt(acts.length)];
                                floatingPetView.playOneShot(act);
                                showPetBubble("主人的专属互动动作搞定啦！✨");
                            }
                            break;
                        case 4:
                            showPetParamsDialog();
                            break;
                        case 5:
                            startActivity(new Intent(this, PetShopActivity.class));
                            break;
                        case 6:
                            toggleGlobalOverlay();
                            break;
                    }
                })
                .setNegativeButton("关闭", null)
                .create();
        dialog.show();
    }

    private void showPetParamsDialog() {
        new PetParamsDialog(this, this::applyPetParams).show();
    }

    private void applyPetParams() {
        int petSize = PetRegistry.getIntPref(this, PetRegistry.KEY_PET_SIZE, PetRegistry.DEFAULT_PET_SIZE);
        int bubbleWidth = PetRegistry.getIntPref(this, PetRegistry.KEY_BUBBLE_WIDTH, PetRegistry.DEFAULT_BUBBLE_WIDTH);
        if (floatingPetView != null) {
            int px = dpToPx(petSize);
            ViewGroup.LayoutParams lp = floatingPetView.getLayoutParams();
            if (lp != null) {
                lp.width = px;
                lp.height = (int) (px * 1.08f);
                floatingPetView.setLayoutParams(lp);
            }
        }
        if (previewPetView != null) {
            int px = dpToPx(petSize);
            ViewGroup.LayoutParams lp = previewPetView.getLayoutParams();
            if (lp != null) {
                lp.width = px;
                lp.height = (int) (px * 1.08f);
                previewPetView.setLayoutParams(lp);
            }
        }
        if (petBubbleLayout != null) {
            int pxW = dpToPx(bubbleWidth);
            ViewGroup.LayoutParams lp = petBubbleLayout.getLayoutParams();
            if (lp != null) {
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                petBubbleLayout.setLayoutParams(lp);
            }
            if (petBubbleTv != null) {
                petBubbleTv.setMaxWidth(pxW);
            }
        }
        if (sbPetSize != null && sbPetSize.getProgress() != (petSize - 32)) {
            sbPetSize.setProgress(petSize - 32);
        }
        if (tvPetSizeLabel != null) {
            tvPetSizeLabel.setText("📏 宠物大小: " + petSize + " dp");
        }
        if (sbBubbleWidth != null && sbBubbleWidth.getProgress() != (bubbleWidth - 140)) {
            sbBubbleWidth.setProgress(bubbleWidth - 140);
        }
        if (tvBubbleWidthLabel != null) {
            tvBubbleWidthLabel.setText("💬 气泡宽度: " + bubbleWidth + " dp");
        }
    }

    private void toggleGlobalOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "开启系统级全局悬浮窗需要授予「显示在其他应用上层」权限", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            return;
        }
        Intent svc = new Intent(this, PetOverlayService.class);
        if (PetOverlayService.isRunning()) {
            stopService(svc);
            Toast.makeText(this, "已关闭系统全局桌宠悬浮窗", Toast.LENGTH_SHORT).show();
            if (btnToggleGlobalOverlay != null) {
                btnToggleGlobalOverlay.setText("🌐 系统全局桌宠悬浮窗: 未开启 (点击开启)");
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
            Toast.makeText(this, "已开启系统全局桌宠悬浮窗！退出应用后桌宠依然在屏幕上陪伴", Toast.LENGTH_SHORT).show();
            if (btnToggleGlobalOverlay != null) {
                btnToggleGlobalOverlay.setText("🌐 系统全局桌宠悬浮窗: 运行中 (点击关闭)");
            }
        }
    }

    private void showPetChatDialog() {
        togglePetChatCard(true);
    }

    private void sendPetChatMessage(String question) {
        if (floatingPetView != null) {
            floatingPetView.playOneShot("waving");
        }
        showPetBubble("收到啦！正在思考回答中...");

        if (question.startsWith("#test")) {
            int port = PiMetConfig.getWebPort(this);
            boolean ok = ProotManager.isPiWebHttpReady(port);
            showPetBubble(ok ? "连通性正常！Pi-Web 在端口 " + port + " 愉快运行中~" : "端口 " + port + " 似乎还没响应，主人请检查服务哦！");
            return;
        } else if (question.startsWith("#model")) {
            showPetBubble("当前挂载模型: " + PiMetConfig.getAiModel(this) + " (" + PiMetConfig.getAiProvider(this) + ")");
            return;
        }

        String apiKey = PiMetConfig.getAiApiKey(this);
        if (TextUtils.isEmpty(apiKey)) {
            showPetBubble("主人还没配置 AI Key 哦~ 可以去设置面板填入！");
            return;
        }

        String baseUrl = PiMetConfig.getAiBaseUrl(this);
        String model = PiMetConfig.getAiModel(this);

        new Thread(() -> {
            try {
                String urlStr = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";
                java.net.URL url = new java.net.URL(urlStr);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(15000);
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("model", model);
                JSONArray messages = new JSONArray();

                JSONObject sysMsg = new JSONObject();
                sysMsg.put("role", "system");
                sysMsg.put("content", "你是 Android PiMet 内置的桌面萌宠伴侣助手，性格活泼可爱、忠诚，说话简短精炼、带有可爱的语气词。回答主人关于 Linux 容器、脚本开发与日常提问，字数控制在 50 字以内。");
                messages.put(sysMsg);

                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                userMsg.put("content", question);
                messages.put(userMsg);

                body.put("messages", messages);
                body.put("max_tokens", 256);

                try (java.io.OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }

                int respCode = conn.getResponseCode();
                if (respCode == 200) {
                    try (java.io.InputStream is = conn.getInputStream();
                         java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
                        byte[] buf = new byte[2048];
                        int n;
                        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
                        String respStr = bos.toString("UTF-8");
                        JSONObject respObj = new JSONObject(respStr);
                        JSONArray choices = respObj.optJSONArray("choices");
                        if (choices != null && choices.length() > 0) {
                            String answer = choices.getJSONObject(0).optJSONObject("message").optString("content", "");
                            mainHandler.post(() -> {
                                if (floatingPetView != null) floatingPetView.playOneShot("jumping");
                                showPetBubble(answer);
                            });
                        }
                    }
                } else {
                    mainHandler.post(() -> showPetBubble("唔……网络请求遇到点问题: HTTP " + respCode));
                }
            } catch (Throwable t) {
                mainHandler.post(() -> showPetBubble("思考出错了: " + t.getMessage()));
            }
        }).start();
    }

    private void initLaunchPanel() {
        int port = PiMetConfig.getWebPort(this);
        launchMetricPortTv.setText(String.valueOf(port));
        launchPortBadge.setText("PORT " + port);
        launchCopyUrlDescTv.setText(getPiWebUrl());

        btnLaunchMain.setOnClickListener(v -> {
            if (isPiWebAlive) {
                // 已运行，直接切换到 Pi-Web 工作台
                switchTab(1);
            } else {
                // 未运行，启动服务
                startPiWebService();
            }
        });

        btnLaunchRestart.setOnClickListener(v -> restartPiWebService());
        btnLaunchStop.setOnClickListener(v -> stopPiWebService());
        btnLaunchDeploy.setOnClickListener(v -> triggerFullDeploy());

        btnActionCopyUrl.setOnClickListener(v -> copyTextToClipboard(getPiWebUrl(), "访问地址已复制到剪切板"));
        btnActionOpenBrowser.setOnClickListener(v -> openExternalBrowser());
        btnActionModelConfig.setOnClickListener(v -> switchTab(3));

        updateLaunchModelDesc();

        btnLaunchLogRefresh.setOnClickListener(v -> refreshLaunchLog());
        btnLaunchLogClear.setOnClickListener(v -> {
            PiWebManager.clearLog(this);
            launchLogTv.setText("[日志已清空]");
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
        });

        refreshLaunchLog();
    }

    private void updateLaunchModelDesc() {
        String provider = PiMetConfig.getAiProvider(this);
        String model = PiMetConfig.getAiModel(this);
        launchModelDescTv.setText(provider + " · " + model);
    }

    private void appendLaunchLog(String message) {
        if (message == null) return;
        mainHandler.post(() -> {
            CharSequence cur = launchLogTv.getText();
            if (cur == null || cur.toString().contains("[系统就绪]")) {
                launchLogTv.setText(message);
            } else {
                launchLogTv.append(message);
            }
        });
    }

    private void refreshLaunchLog() {
        new Thread(() -> {
            String log = PiWebManager.readLastLog(this);
            mainHandler.post(() -> {
                if (!TextUtils.isEmpty(log)) {
                    launchLogTv.setText(log);
                } else {
                    launchLogTv.setText("[系统就绪] 暂无守护日志");
                }
            });
        }).start();
    }

    private void checkServiceStatus() {
        int port = PiMetConfig.getWebPort(this);
        new Thread(() -> {
            boolean alive = ProotManager.isPiWebPortAlive(port);
            mainHandler.post(() -> {
                isPiWebAlive = alive;
                updateLaunchStatusUI(alive);
            });
        }).start();
    }

    private void updateLaunchStatusUI(boolean alive) {
        int port = PiMetConfig.getWebPort(this);
        launchPortBadge.setText("PORT " + port);
        launchMetricPortTv.setText(String.valueOf(port));
        launchCopyUrlDescTv.setText(getPiWebUrl());

        if (alive) {
            PiMetService.start(this);
            launchStatusDot.setBackgroundResource(R.drawable.bg_status_dot_green);
            launchStateTv.setText("服务运行中");
            launchStateTv.setTextColor(0xFF3FB950);
            launchSubtitleTv.setText("Pi-Web 守护进程正常监听中，可进入工作台或外部浏览器使用");
            btnLaunchMain.setText("🌐 进入 Pi-Web 工作台");
            btnLaunchMain.setBackgroundResource(R.drawable.bg_btn_primary);
        } else {
            launchStatusDot.setBackgroundResource(R.drawable.bg_status_dot_gray);
            launchStateTv.setText("服务已停止");
            launchStateTv.setTextColor(0xFF8B949E);
            launchSubtitleTv.setText("内置 PRoot 容器环境已就绪，点击下方按钮启动 Pi-Web 守护服务");
            btnLaunchMain.setText("🚀 启动 Pi-Web 服务");
            btnLaunchMain.setBackgroundResource(R.drawable.bg_btn_success);
        }
    }

    private void startPiWebService() {
        if (isDeploying) {
            Toast.makeText(this, "正在部署中，请稍候...", Toast.LENGTH_SHORT).show();
            return;
        }
        launchProgressBar.setVisibility(View.VISIBLE);
        launchStateTv.setText("正在拉起服务...");
        launchStatusDot.setBackgroundResource(R.drawable.bg_status_dot_yellow);

        PiWebManager.startOrDeploy(this, new PiWebManager.StateListener() {
            @Override
            public void onLog(String log) {
                appendLaunchLog(log);
            }

            @Override
            public void onProgress(String message, int percent) {
                mainHandler.post(() -> {
                    if (percent >= 0) {
                        launchSubtitleTv.setText(message + " (" + percent + "%)");
                    } else {
                        launchSubtitleTv.setText(message);
                    }
                });
            }

            @Override
            public void onStarted() {
                launchProgressBar.setVisibility(View.GONE);
                isPiWebAlive = true;
                PiMetService.start(MainActivity.this);
                updateLaunchStatusUI(true);
                Toast.makeText(MainActivity.this, "🎉 Pi-Web 服务已成功启动！", Toast.LENGTH_SHORT).show();
                refreshLaunchLog();
            }

            @Override
            public void onError(String error) {
                launchProgressBar.setVisibility(View.GONE);
                isPiWebAlive = false;
                updateLaunchStatusUI(false);
                Toast.makeText(MainActivity.this, "启动失败: " + error, Toast.LENGTH_LONG).show();
                refreshLaunchLog();
            }
        });
    }

    private void restartPiWebService() {
        Toast.makeText(this, "正在安全重启 Pi-Web...", Toast.LENGTH_SHORT).show();
        launchProgressBar.setVisibility(View.VISIBLE);
        launchStateTv.setText("正在终止旧服务并释放端口...");
        launchStatusDot.setBackgroundResource(R.drawable.bg_status_dot_yellow);

        PiWebManager.stopPiWeb(this, () -> {
            isPiWebAlive = false;
            updateLaunchStatusUI(false);
            startPiWebService();
        });
    }

    private void stopPiWebService() {
        launchProgressBar.setVisibility(View.VISIBLE);
        launchStateTv.setText("正在停止服务...");
        launchStatusDot.setBackgroundResource(R.drawable.bg_status_dot_yellow);

        PiWebManager.stopPiWeb(this, () -> {
            isPiWebAlive = false;
            PiMetService.stop(this);
            launchProgressBar.setVisibility(View.GONE);
            updateLaunchStatusUI(false);
            Toast.makeText(this, "Pi-Web 服务已成功停止", Toast.LENGTH_SHORT).show();
            refreshLaunchLog();
        });
    }

    private void triggerFullDeploy() {
        if (isDeploying) {
            Toast.makeText(this, "正在部署中...", Toast.LENGTH_SHORT).show();
            return;
        }
        isDeploying = true;
        launchProgressBar.setVisibility(View.VISIBLE);
        updateLaunchStatusUI(false);

        appendLaunchLog("\u001B[33m🚀 开始执行一键部署流水线...\u001B[0m\n");

        if (!ProotManager.isRootfsInstalled(this)) {
            appendLaunchLog("\u001B[36m• 正在从镜像源提取 Linux 根文件系统...\u001B[0m\n");
            ProotManager.installRootfs(this, new ProotManager.InstallCallback() {
                @Override
                public void onProgress(String message, int percent) {
                    mainHandler.post(() -> appendLaunchLog("• " + message + " " + percent + "%\n"));
                }

                @Override
                public void onSuccess() {
                    mainHandler.post(() -> {
                        appendLaunchLog("\u001B[32m✔ Linux 根系统部署完成！\u001B[0m\n");
                        isDeploying = false;
                        launchProgressBar.setVisibility(View.GONE);
                        for (TerminalTab t : terminalTabs) {
                            if (t.session != null) t.session.close();
                        }
                        terminalTabs.clear();
                        nextTabId = 1;
                        activeTabId = -1;
                        refreshTabsUi();
                        createTab(false);
                        startPiWebService();
                    });
                }

                @Override
                public void onError(String error) {
                    mainHandler.post(() -> {
                        isDeploying = false;
                        launchProgressBar.setVisibility(View.GONE);
                        appendLaunchLog("\u001B[31m❌ 根系统部署失败: " + error + "\u001B[0m\n");
                        updateLaunchStatusUI(false);
                    });
                }
            });
        } else {
            isDeploying = false;
            startPiWebService();
        }
    }

    // ================= Pi-Web 工作台 =================
    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    private void initPiWebView() {
        WebSettings webSettings = piWebWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setSupportZoom(true);
        webSettings.setBuiltInZoomControls(true);
        webSettings.setDisplayZoomControls(false);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setAllowFileAccessFromFileURLs(true);
        webSettings.setAllowUniversalAccessFromFileURLs(true);

        // 初始化网页缩放
        int savedZoom = PiMetConfig.getWebZoom(this);
        webSettings.setTextZoom(savedZoom);
        btnFloatZoom.setText(savedZoom + "%");

        piWebWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showPiWebOffline(true);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null && url.startsWith("http://127.0.0.1:")) {
                    // 深度自愈：检测页面是否由于 Node.js 启动延迟被 Service Worker 错误截获为 offline.html
                    view.evaluateJavascript(
                        "(function() { " +
                        "  var h1 = document.querySelector('h1'); " +
                        "  return (document.title.indexOf('offline') !== -1 || (h1 && h1.innerText.indexOf('offline') !== -1)); " +
                        "})()",
                        result -> {
                            if ("true".equalsIgnoreCase(result)) {
                                handlePiWebOfflineDetected();
                            }
                        }
                    );
                }
            }
        });

        piWebWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) {
                    piWebProgressBar.setVisibility(View.VISIBLE);
                    piWebProgressBar.setProgress(newProgress);
                } else {
                    piWebProgressBar.setVisibility(View.GONE);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                    filePathCallback = null;
                }
                filePathCallback = callback;

                Intent intent = null;
                if (params != null) {
                    try {
                        intent = params.createIntent();
                    } catch (Exception ignored) {}
                }

                if (intent == null) {
                    intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                }

                if (params != null && params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }

                try {
                    startActivityForResult(Intent.createChooser(intent, "选择导入的文件或图片"), REQUEST_CODE_FILE_CHOOSER);
                    return true;
                } catch (ActivityNotFoundException e) {
                    try {
                        Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                        fallback.addCategory(Intent.CATEGORY_OPENABLE);
                        fallback.setType("*/*");
                        startActivityForResult(Intent.createChooser(fallback, "选择导入的文件或图片"), REQUEST_CODE_FILE_CHOOSER);
                        return true;
                    } catch (Exception ex) {
                        if (filePathCallback != null) {
                            filePathCallback.onReceiveValue(null);
                            filePathCallback = null;
                        }
                        Toast.makeText(MainActivity.this, "未能找到系统文件选择器", Toast.LENGTH_SHORT).show();
                        return false;
                    }
                }
            }
        });

        // 悬浮球自由拖拽与点击手势 (任意移动，不挡界面)
        floatingBall.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    floatDownRawX = event.getRawX();
                    floatDownRawY = event.getRawY();
                    floatInitialX = floatingMenuContainer.getTranslationX();
                    floatInitialY = floatingMenuContainer.getTranslationY();
                    isFloatDragging = false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - floatDownRawX;
                    float dy = event.getRawY() - floatDownRawY;
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                        isFloatDragging = true;
                        View parent = (View) floatingMenuContainer.getParent();
                        if (parent != null) {
                            int parentWidth = parent.getWidth();
                            int parentHeight = parent.getHeight();
                            int containerWidth = floatingMenuContainer.getWidth();
                            int containerHeight = floatingMenuContainer.getHeight();

                            float targetX = floatingMenuContainer.getLeft() + floatInitialX + dx;
                            float targetY = floatingMenuContainer.getTop() + floatInitialY + dy;

                            // 关键边界防护：顶部保留至少 64dp 安全区（避免被状态栏/刘海遮挡导致拖至顶层丢失）
                            // 底部预留 16dp，左右预留 8dp
                            float density = getResources().getDisplayMetrics().density;
                            float minMarginTop = 64 * density;
                            float minMarginBottom = 16 * density;
                            float minMarginSide = 8 * density;

                            float clampedX = Math.max(minMarginSide, Math.min(targetX, parentWidth - containerWidth - minMarginSide));
                            float clampedY = Math.max(minMarginTop, Math.min(targetY, parentHeight - containerHeight - minMarginBottom));

                            floatingMenuContainer.setTranslationX(clampedX - floatingMenuContainer.getLeft());
                            floatingMenuContainer.setTranslationY(clampedY - floatingMenuContainer.getTop());
                        } else {
                            floatingMenuContainer.setTranslationX(floatInitialX + dx);
                            floatingMenuContainer.setTranslationY(floatInitialY + dy);
                        }
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    if (!isFloatDragging) {
                        toggleFloatingMenu();
                    }
                    return true;
            }
            return false;
        });

        // 桌面宠物自由拖拽交互、奔跑手势与互动动作
        if (floatingPetView != null) {
            floatingPetView.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        floatDownRawX = event.getRawX();
                        floatDownRawY = event.getRawY();
                        floatInitialX = floatingMenuContainer.getTranslationX();
                        floatInitialY = floatingMenuContainer.getTranslationY();
                        isFloatDragging = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - floatDownRawX;
                        float dy = event.getRawY() - floatDownRawY;
                        if (Math.abs(dx) > 6 || Math.abs(dy) > 6) {
                            isFloatDragging = true;
                            // 拖动方向灵敏检测 -> 奔跑逐帧动画切换
                            if (dx > 4) {
                                floatingPetView.setMoveDirection(1);
                            } else if (dx < -4) {
                                floatingPetView.setMoveDirection(-1);
                            }

                            View parent = (View) floatingMenuContainer.getParent();
                            if (parent != null) {
                                int parentWidth = parent.getWidth();
                                int parentHeight = parent.getHeight();
                                int containerWidth = floatingMenuContainer.getWidth();
                                int containerHeight = floatingMenuContainer.getHeight();

                                float targetX = floatingMenuContainer.getLeft() + floatInitialX + dx;
                                float targetY = floatingMenuContainer.getTop() + floatInitialY + dy;

                                float density = getResources().getDisplayMetrics().density;
                                float minMarginTop = 32 * density;
                                float minMarginBottom = 16 * density;
                                float minMarginSide = 8 * density;

                                float clampedX = Math.max(minMarginSide, Math.min(targetX, parentWidth - containerWidth - minMarginSide));
                                float clampedY = Math.max(minMarginTop, Math.min(targetY, parentHeight - containerHeight - minMarginBottom));

                                floatingMenuContainer.setTranslationX(clampedX - floatingMenuContainer.getLeft());
                                floatingMenuContainer.setTranslationY(clampedY - floatingMenuContainer.getTop());
                            } else {
                                floatingMenuContainer.setTranslationX(floatInitialX + dx);
                                floatingMenuContainer.setTranslationY(floatInitialY + dy);
                            }
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        floatingPetView.setMoveDirection(0); // 停止跑步恢复呼吸
                        if (!isFloatDragging) {
                            long pressDuration = event.getEventTime() - event.getDownTime();
                            if (pressDuration >= 400) {
                                showPetMenuDialog();
                            } else {
                                if (petInteractionCount++ % 2 == 0) {
                                    floatingPetView.playOneShot("waving");
                                } else {
                                    floatingPetView.playOneShot("jumping");
                                }
                                togglePetChatCard(null);
                            }
                        }
                        return true;
                }
                return false;
            });
        }

        // 浮动桌宠底部交互卡片绑定
        floatingPetChatCard = findViewById(R.id.floatingPetChatCard);
        viewPetStatusDot = findViewById(R.id.viewPetStatusDot);
        tvPetStatusText = findViewById(R.id.tvPetStatusText);
        btnHidePetChatCard = findViewById(R.id.btnHidePetChatCard);
        btnPetChipDance = findViewById(R.id.btnPetChipDance);
        btnPetChipWave = findViewById(R.id.btnPetChipWave);
        btnPetChipJump = findViewById(R.id.btnPetChipJump);
        btnPetChipTest = findViewById(R.id.btnPetChipTest);
        btnPetChipSwitch = findViewById(R.id.btnPetChipSwitch);
        inputFloatPetChat = findViewById(R.id.inputFloatPetChat);
        btnSendFloatPetChat = findViewById(R.id.btnSendFloatPetChat);

        if (btnHidePetChatCard != null) {
            btnHidePetChatCard.setOnClickListener(v -> togglePetChatCard(false));
        }
        if (btnPetChipDance != null) {
            btnPetChipDance.setOnClickListener(v -> {
                if (floatingPetView != null) floatingPetView.playOneShot("dancing");
                showPetBubble("开心地跳起舞来啦~ 💃");
            });
        }
        if (btnPetChipWave != null) {
            btnPetChipWave.setOnClickListener(v -> {
                if (floatingPetView != null) floatingPetView.playOneShot("waving");
                showPetBubble("主人好呀！愿您今天一切顺利 🐾");
            });
        }
        if (btnPetChipJump != null) {
            btnPetChipJump.setOnClickListener(v -> {
                if (floatingPetView != null) floatingPetView.playOneShot("jumping");
                showPetBubble("哇呼~ 空翻完成！✨");
            });
        }
        if (btnPetChipTest != null) {
            btnPetChipTest.setOnClickListener(v -> sendPetChatMessage("#test"));
        }
        if (btnPetChipSwitch != null) {
            btnPetChipSwitch.setOnClickListener(v -> showPetSwitchDialog());
        }
        if (btnSendFloatPetChat != null) {
            btnSendFloatPetChat.setOnClickListener(v -> {
                String q = inputFloatPetChat != null ? inputFloatPetChat.getText().toString().trim() : "";
                if (q.isEmpty()) return;
                if (inputFloatPetChat != null) inputFloatPetChat.setText("");
                sendPetChatMessage(q);
            });
        }
        if (inputFloatPetChat != null) {
            inputFloatPetChat.setOnEditorActionListener((v, actionId, ev) -> {
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    if (btnSendFloatPetChat != null) btnSendFloatPetChat.performClick();
                    return true;
                }
                return false;
            });
        }

        updatePetDisplay(PetRegistry.isPetEnabled(this));

        if (btnFloatPetChat != null) {
            btnFloatPetChat.setOnClickListener(v -> {
                floatingMenuVertical.setVisibility(View.GONE);
                togglePetChatCard(true);
            });
        }
        if (petBubbleLayout != null) {
            petBubbleLayout.setOnClickListener(v -> togglePetChatCard(true));
        }

        btnFloatClose.setOnClickListener(v -> floatingMenuVertical.setVisibility(View.GONE));
        btnFloatFullscreen.setOnClickListener(v -> {
            toggleFullscreen(!isFullscreen);
            floatingMenuVertical.setVisibility(View.GONE);
        });
        if (btnFloatTerminal != null) {
            btnFloatTerminal.setOnClickListener(v -> {
                floatingMenuVertical.setVisibility(View.GONE);
                toggleTerminalInWorkbench();
            });
        }
        if (btnFloatPetSwitch != null) {
            btnFloatPetSwitch.setOnClickListener(v -> {
                floatingMenuVertical.setVisibility(View.GONE);
                showPetMenuDialog();
            });
        }
        btnFloatReload.setOnClickListener(v -> {
            floatingMenuVertical.setVisibility(View.GONE);
            if (isPiWebAlive) {
                piWebWebView.reload();
            } else {
                updatePiWebDisplay();
            }
        });
        btnFloatZoom.setOnClickListener(v -> cycleWebZoom());
        btnFloatImport.setOnClickListener(v -> {
            floatingMenuVertical.setVisibility(View.GONE);
            launchFilePickerForContainer();
        });
        btnFloatBrowser.setOnClickListener(v -> {
            floatingMenuVertical.setVisibility(View.GONE);
            openExternalBrowser();
        });
        btnFloatClipboard.setOnClickListener(v -> {
            floatingMenuVertical.setVisibility(View.GONE);
            showClipboardActionsDialog();
        });
        piWebWakeBtn.setOnClickListener(v -> startPiWebService());
    }

    private void toggleFloatingMenu() {
        if (floatingMenuVertical.getVisibility() == View.VISIBLE) {
            floatingMenuVertical.setVisibility(View.GONE);
        } else {
            floatingMenuVertical.setVisibility(View.VISIBLE);
        }
    }

    private void cycleWebZoom() {
        int zoom = PiMetConfig.getWebZoom(this);
        int nextZoom;
        if (zoom < 100) nextZoom = 100;
        else if (zoom < 120) nextZoom = 120;
        else if (zoom < 140) nextZoom = 140;
        else nextZoom = 80;

        PiMetConfig.setWebZoom(this, nextZoom);
        piWebWebView.getSettings().setTextZoom(nextZoom);
        btnFloatZoom.setText(nextZoom + "%");
        Toast.makeText(this, "工作台文字缩放: " + nextZoom + "%", Toast.LENGTH_SHORT).show();
    }

    private void toggleFullscreen(boolean fullscreen) {
        isFullscreen = fullscreen;
        bottomNavBar.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        btnFloatFullscreen.setText(fullscreen ? "✕" : "⛶");
        updatePetDisplay(PetRegistry.isPetEnabled(this));
        if (fullscreen && floatingPetView != null) {
            floatingPetView.playOneShot("waving");
            showPetBubble("已进入全屏沉浸模式！ฅ'ω'ฅ");
        }
        Toast.makeText(this, fullscreen ? "已进入沉浸模式 (桌宠悬浮常驻)" : "已退出全屏", Toast.LENGTH_SHORT).show();
    }

    private void updatePiWebDisplay() {
        int port = PiMetConfig.getWebPort(this);
        String url = getPiWebUrl();

        new Thread(() -> {
            boolean alive = ProotManager.isPiWebHttpReady(port);
            if (!alive && ProotManager.isPiWebPortAlive(port)) {
                // 端口已开启但 HTTP 尚在启动编译中，缓冲等待最多 3.5 秒
                for (int i = 0; i < 7; i++) {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    if (ProotManager.isPiWebHttpReady(port)) {
                        alive = true;
                        break;
                    }
                }
            }
            final boolean isReady = alive;
            mainHandler.post(() -> {
                isPiWebAlive = isReady;
                if (isReady) {
                    showPiWebOffline(false);
                    if (piWebWebView.getUrl() == null || !piWebWebView.getUrl().startsWith("http://127.0.0.1:" + port)) {
                        piWebWebView.loadUrl(url);
                    } else {
                        piWebWebView.reload();
                    }
                } else {
                    showPiWebOffline(true);
                }
            });
        }).start();
    }

    private void handlePiWebOfflineDetected() {
        int port = PiMetConfig.getWebPort(this);
        // 如果服务实际在运行或刚拉起，静默轮询并在就绪后自动重新加载，摆脱手动点击 Try again
        new Thread(() -> {
            for (int i = 0; i < 15; i++) {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                if (ProotManager.isPiWebHttpReady(port)) {
                    mainHandler.post(() -> {
                        if (piWebWebView != null) {
                            piWebWebView.reload();
                        }
                    });
                    break;
                }
            }
        }).start();
    }

    private void showPiWebOffline(boolean offline) {
        piWebOfflineCard.setVisibility(offline ? View.VISIBLE : View.GONE);
        piWebWebView.setVisibility(offline ? View.GONE : View.VISIBLE);
        int port = PiMetConfig.getWebPort(this);
        piWebOfflineSubTv.setText("端口 " + port + " 尚未启动监听，请先启动服务。");
    }

    // ================= PRoot 终端 (多窗口 / 快捷按键 / Termux 交互) =================
    private void initTerminalPanel() {
        btnSend.setOnClickListener(v -> sendCommand());
        commandInput.setOnEditorActionListener((v, actionId, event) -> {
            sendCommand();
            return true;
        });

        // 内置终端抽屉折叠与最大化控制
        if (btnTermClose != null) {
            btnTermClose.setOnClickListener(v -> closeTerminalInWorkbench());
        }
        if (btnTermMaximize != null) {
            btnTermMaximize.setOnClickListener(v -> {
                isTermMaximized = !isTermMaximized;
                ViewGroup.LayoutParams lp = viewTerminal.getLayoutParams();
                if (isTermMaximized) {
                    lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                    btnTermMaximize.setText("⇲ 半屏");
                } else {
                    lp.height = (int) (350 * getResources().getDisplayMetrics().density);
                    btnTermMaximize.setText("⛶ 最大化");
                }
                viewTerminal.setLayoutParams(lp);
            });
        }

        // 字体缩放控制
        currentTermFontSize = PiMetConfig.getTermFontSize(this);
        terminalOutput.setTextSize(currentTermFontSize);
        btnTermFontDec.setOnClickListener(v -> adjustTermFontSize(-1.0f));
        btnTermFontInc.setOnClickListener(v -> adjustTermFontSize(1.0f));

        // 顶部操作栏事件
        btnTermReconnect.setOnClickListener(v -> {
            restartActiveTab();
            Toast.makeText(this, "正在重新连接当前窗口...", Toast.LENGTH_SHORT).show();
        });
        btnTermQuickWeb.setOnClickListener(v -> closeTerminalInWorkbench());

        // ⚙️ 快捷键自定义与注释管理
        btnTermCustomKey.setOnClickListener(v -> showCustomShortcutsManagerDialog());

        // 📁 从手机导入文件/图片至 PRoot 容器
        btnTermImportFile.setOnClickListener(v -> launchFilePickerForContainer());

        // 📋 剪贴板快速操作
        btnTermClipboard.setOnClickListener(v -> showClipboardActionsDialog());

        btnClear.setOnClickListener(v -> {
            TerminalTab tab = getActiveTab();
            if (tab != null) {
                tab.buffer.clear();
                tab.ansi.reset();
                terminalOutput.setText("");
                if (tab.session != null) {
                    tab.session.write("clear\n");
                }
            }
        });

        btnCtrlC.setOnClickListener(v -> {
            TerminalTab tab = getActiveTab();
            if (tab != null && tab.session != null) {
                tab.session.sendCtrlC();
                appendTerminalLog(tab, "^C\r\n");
                Toast.makeText(this, "已发送 Ctrl+C", Toast.LENGTH_SHORT).show();
            }
        });

        // 初始化 Termux 风格快捷键与交互
        initDefaultShortcutKeys();
        loadCustomShortcuts();
        renderTermuxKeys();
        setupSlashCommandSuggestions();
        setupToolbarReordering();
        restoreToolbarOrder();
        restoreKeyboardPinnedState();

        btnToggleKeysBar.setOnClickListener(v -> toggleKeyboardPinnedState());
        btnSlashSuggestClose.setOnClickListener(v -> slashSuggestCard.setVisibility(View.GONE));
    }

    private void initDefaultShortcutKeys() {
        shortcutKeys.clear();
        // 控制键与导航键
        shortcutKeys.add(new ShortcutKey("esc", "ESC", "\u001B", "退出当前输入模式 / Vi Esc", true, true));
        shortcutKeys.add(new ShortcutKey("tab", "TAB", "    ", "插入 4 个空格缩进 / 制表符", false, true));
        shortcutKeys.add(new ShortcutKey("ctrl_c", "Ctrl+C", "\u0003", "发送 SIGINT 中断当前前台任务", true, true));
        shortcutKeys.add(new ShortcutKey("ctrl_d", "Ctrl+D", "\u0004", "发送 EOF 退出当前 Shell", true, true));
        shortcutKeys.add(new ShortcutKey("ctrl_l", "Ctrl+L", "clear\n", "清屏终端显示内容", true, true));
        shortcutKeys.add(new ShortcutKey("key_up", "↑", "UP", "调出上一条历史执行指令", false, true));
        shortcutKeys.add(new ShortcutKey("key_down", "↓", "DOWN", "调出下一条历史执行指令", false, true));
        shortcutKeys.add(new ShortcutKey("key_left", "←", "LEFT", "将光标向左移动一位", false, true));
        shortcutKeys.add(new ShortcutKey("key_right", "→", "RIGHT", "将光标向右移动一位", false, true));

        // 常用 Linux 符号
        shortcutKeys.add(new ShortcutKey("tilde", "~", "~", "Linux 家目录符号 (~)", false, true));
        shortcutKeys.add(new ShortcutKey("slash", "/", "/", "路径斜杠 / 唤出指令补全提示", false, true));
        shortcutKeys.add(new ShortcutKey("dash", "-", "-", "短横线减号 / 命令参数前缀 (-)", false, true));
        shortcutKeys.add(new ShortcutKey("pipe", "|", "|", "管道符号 (|) 将输出传递给下一指令", false, true));
        shortcutKeys.add(new ShortcutKey("gt", ">", ">", "重定向输出符号 (>)", false, true));
        shortcutKeys.add(new ShortcutKey("amp", "&", "&", "后台运行或条件连接符号 (&)", false, true));

        // 核心指令按键 (统一样式，无过度高亮)
        shortcutKeys.add(new ShortcutKey("pi_chat", "pi-chat", "pi-chat\n", "启动 Pi AI 终端多轮交互对话模式", true, true));
        shortcutKeys.add(new ShortcutKey("clip", "clip", "CLIP_ACTION", "呼出剪贴板菜单 (提问/粘贴/同步)", false, true));
        shortcutKeys.add(new ShortcutKey("pi", "pi", "pi\n", "启动 Pi 官方命令行 AI 编程助手", true, true));
        shortcutKeys.add(new ShortcutKey("pi_model", "/model", "/model\n", "查看与切换当前配置的 AI 模型", true, true));
        shortcutKeys.add(new ShortcutKey("pi_login", "/login", "/login\n", "登录配置 AI 服务提供商凭证", true, true));
        shortcutKeys.add(new ShortcutKey("node_v", "node -v", "node -v\n", "查看容器内 Node.js 运行时版本", true, true));
        shortcutKeys.add(new ShortcutKey("npm_v", "npm -v", "npm -v\n", "查看容器内 npm 包管理器版本", true, true));
        shortcutKeys.add(new ShortcutKey("ps_ef", "ps -ef", "ps -ef\n", "查看 Linux 容器内运行的所有进程", true, true));
        shortcutKeys.add(new ShortcutKey("top", "top", "top\n", "实时监控系统资源与 CPU 内存占用", true, true));
        shortcutKeys.add(new ShortcutKey("clear", "clear", "clear\n", "执行 clear 清空终端缓冲区", true, true));
    }

    private void loadCustomShortcuts() {
        String jsonStr = PiMetConfig.getCustomShortcutsJson(this);
        if (TextUtils.isEmpty(jsonStr)) return;
        try {
            JSONArray arr = new JSONArray(jsonStr);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                ShortcutKey customKey = ShortcutKey.fromJson(obj);
                // 检查是否覆盖了系统按键注释
                boolean replaced = false;
                for (int j = 0; j < shortcutKeys.size(); j++) {
                    if (shortcutKeys.get(j).id.equals(customKey.id)) {
                        shortcutKeys.set(j, customKey);
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) {
                    shortcutKeys.add(customKey);
                }
            }
        } catch (Exception e) {
            Log.e("PiMet", "Failed to parse custom shortcuts: " + e.getMessage());
        }
    }

    private void saveCustomShortcuts() {
        try {
            JSONArray arr = new JSONArray();
            for (ShortcutKey key : shortcutKeys) {
                if (!key.isSystem || !TextUtils.isEmpty(key.description)) {
                    arr.put(key.toJson());
                }
            }
            PiMetConfig.setCustomShortcutsJson(this, arr.toString());
        } catch (Exception e) {
            Log.e("PiMet", "Failed to save custom shortcuts: " + e.getMessage());
        }
    }

    private void renderTermuxKeys() {
        if (termuxKeysContainer == null) return;
        termuxKeysContainer.removeAllViews();

        for (ShortcutKey key : shortcutKeys) {
            View keyView = getLayoutInflater().inflate(R.layout.item_termux_key, termuxKeysContainer, false);
            TextView labelTv = keyView.findViewById(R.id.termuxKeyLabel);
            labelTv.setText(key.label);

            keyView.setOnClickListener(v -> handleShortcutKeyClick(key));
            keyView.setOnLongClickListener(v -> {
                String desc = TextUtils.isEmpty(key.description) ? "暂无注释" : key.description;
                Toast.makeText(this, "【" + key.label + "】 " + desc + "\n指令: " + key.command.replace("\n", ""), Toast.LENGTH_SHORT).show();
                return true;
            });

            termuxKeysContainer.addView(keyView);
        }

        // 尾部添加快捷添加按键 (+)
        TextView btnAddKey = new TextView(this);
        btnAddKey.setText("＋");
        btnAddKey.setTextColor(0xFF58A6FF);
        btnAddKey.setTextSize(12);
        btnAddKey.setGravity(android.view.Gravity.CENTER);
        btnAddKey.setBackgroundResource(R.drawable.bg_btn_secondary);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dpToPx(28), dpToPx(26));
        lp.setMargins(dpToPx(4), 0, dpToPx(4), 0);
        btnAddKey.setLayoutParams(lp);
        btnAddKey.setOnClickListener(v -> showEditShortcutKeyDialog(null, true));
        termuxKeysContainer.addView(btnAddKey);
    }

    private void handleShortcutKeyClick(ShortcutKey key) {
        if ("clip".equals(key.id) || "CLIP_ACTION".equals(key.command)) {
            showClipboardActionsDialog();
            return;
        }
        if ("key_up".equals(key.id)) {
            handleHistoryKey(true);
            return;
        }
        if ("key_down".equals(key.id)) {
            handleHistoryKey(false);
            return;
        }
        if ("key_left".equals(key.id)) {
            int pos = Math.max(0, commandInput.getSelectionStart() - 1);
            commandInput.setSelection(pos);
            return;
        }
        if ("key_right".equals(key.id)) {
            int pos = Math.min(commandInput.getText().length(), commandInput.getSelectionStart() + 1);
            commandInput.setSelection(pos);
            return;
        }
        if ("ctrl_c".equals(key.id)) {
            btnCtrlC.performClick();
            return;
        }
        if ("ctrl_d".equals(key.id)) {
            TerminalTab tab = getActiveTab();
            if (tab != null && tab.session != null) {
                tab.session.write("\u0004");
                appendTerminalLog(tab, "^D\r\n");
            }
            return;
        }
        if ("ctrl_l".equals(key.id) || "clear".equals(key.id)) {
            btnClear.performClick();
            return;
        }
        if ("esc".equals(key.id)) {
            TerminalTab tab = getActiveTab();
            if (tab != null && tab.session != null) {
                tab.session.write("\u001B");
            }
            return;
        }

        if (key.isDirectRun) {
            executeCommand(key.command);
        } else {
            insertTextToInput(key.command);
        }
    }

    private void handleHistoryKey(boolean up) {
        if (commandHistory.isEmpty()) return;
        if (up) {
            if (historyIndex == -1) {
                historyIndex = commandHistory.size() - 1;
            } else if (historyIndex > 0) {
                historyIndex--;
            }
        } else {
            if (historyIndex != -1 && historyIndex < commandHistory.size() - 1) {
                historyIndex++;
            } else {
                historyIndex = -1;
                commandInput.setText("");
                return;
            }
        }
        if (historyIndex >= 0 && historyIndex < commandHistory.size()) {
            commandInput.setText(commandHistory.get(historyIndex));
            commandInput.setSelection(commandInput.getText().length());
        }
    }

    private void setupSlashCommandSuggestions() {
        commandInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String text = s != null ? s.toString() : "";
                if (text.startsWith("/")) {
                    updateSlashSuggestions(text);
                } else {
                    slashSuggestCard.setVisibility(View.GONE);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    private void updateSlashSuggestions(String query) {
        if (slashSuggestContainer == null) return;
        slashSuggestContainer.removeAllViews();

        String q = query.trim().toLowerCase();
        List<ShortcutKey> matches = new ArrayList<>();
        for (ShortcutKey key : shortcutKeys) {
            String lbl = key.label.toLowerCase();
            String cmd = key.command.toLowerCase();
            String desc = key.description.toLowerCase();
            if (q.equals("/") || lbl.contains(q) || cmd.contains(q) || desc.contains(q)) {
                matches.add(key);
            }
        }

        if (matches.isEmpty()) {
            slashSuggestCard.setVisibility(View.GONE);
            return;
        }

        slashSuggestCard.setVisibility(View.VISIBLE);
        for (ShortcutKey key : matches) {
            View itemView = getLayoutInflater().inflate(R.layout.item_slash_suggest, slashSuggestContainer, false);
            TextView cmdTv = itemView.findViewById(R.id.suggestCommandTv);
            TextView descTv = itemView.findViewById(R.id.suggestDescTv);

            cmdTv.setText(key.label);
            descTv.setText(TextUtils.isEmpty(key.description) ? key.command.replace("\n", "") : key.description);

            itemView.setOnClickListener(v -> {
                if (key.isDirectRun) {
                    executeCommand(key.command);
                    commandInput.setText("");
                } else {
                    commandInput.setText(key.command);
                    commandInput.setSelection(commandInput.getText().length());
                }
                slashSuggestCard.setVisibility(View.GONE);
            });

            slashSuggestContainer.addView(itemView);
        }
    }

    private void restoreKeyboardPinnedState() {
        boolean pinned = PiMetConfig.isKeyboardPinned(this);
        termuxKeysBar.setVisibility(pinned ? View.VISIBLE : View.GONE);
        btnToggleKeysBar.setText(pinned ? "⌨" : "⌨⋯");
    }

    private void toggleKeyboardPinnedState() {
        boolean nowPinned = termuxKeysBar.getVisibility() != View.VISIBLE;
        termuxKeysBar.setVisibility(nowPinned ? View.VISIBLE : View.GONE);
        btnToggleKeysBar.setText(nowPinned ? "⌨" : "⌨⋯");
        PiMetConfig.setKeyboardPinned(this, nowPinned);
        Toast.makeText(this, nowPinned ? "快捷按键栏已常驻" : "快捷按键栏已收起", Toast.LENGTH_SHORT).show();
    }

    // 终端顶部工具栏长按调换位置与持久化
    private void setupToolbarReordering() {
        if (termToolbarContainer == null) return;
        for (int i = 0; i < termToolbarContainer.getChildCount(); i++) {
            View child = termToolbarContainer.getChildAt(i);
            child.setOnLongClickListener(v -> {
                showReorderToolbarDialog(v);
                return true;
            });
        }
    }

    private void restoreToolbarOrder() {
        String order = PiMetConfig.getTopToolbarOrder(this);
        if (TextUtils.isEmpty(order) || termToolbarContainer == null) return;
        String[] ids = order.split(",");
        List<View> orderedViews = new ArrayList<>();
        List<View> allViews = new ArrayList<>();

        for (int i = 0; i < termToolbarContainer.getChildCount(); i++) {
            allViews.add(termToolbarContainer.getChildAt(i));
        }

        for (String idName : ids) {
            String trimmed = idName.trim();
            for (View v : allViews) {
                if (v.getId() != View.NO_ID) {
                    try {
                        String entryName = getResources().getResourceEntryName(v.getId());
                        if (trimmed.equals(entryName) && !orderedViews.contains(v)) {
                            orderedViews.add(v);
                            break;
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        // 追加剩余未在排序配置中的按钮
        for (View v : allViews) {
            if (!orderedViews.contains(v)) {
                orderedViews.add(v);
            }
        }

        termToolbarContainer.removeAllViews();
        for (View v : orderedViews) {
            termToolbarContainer.addView(v);
        }
    }

    private void saveToolbarOrder() {
        if (termToolbarContainer == null) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < termToolbarContainer.getChildCount(); i++) {
            View v = termToolbarContainer.getChildAt(i);
            if (v.getId() != View.NO_ID) {
                try {
                    String name = getResources().getResourceEntryName(v.getId());
                    if (sb.length() > 0) sb.append(",");
                    sb.append(name);
                } catch (Exception ignored) {}
            }
        }
        PiMetConfig.setTopToolbarOrder(this, sb.toString());
    }

    private void showReorderToolbarDialog(View targetView) {
        String btnName = (targetView instanceof TextView) ? ((TextView) targetView).getText().toString() : "该按钮";
        String[] actions = {"⬅ 向左移动一位", "➡ 向右移动一位", "⏮ 置顶到最前", "⏭ 移到最后"};

        new AlertDialog.Builder(this)
                .setTitle("调整工具栏按钮顺序: " + btnName)
                .setItems(actions, (dialog, which) -> {
                    int currentIndex = termToolbarContainer.indexOfChild(targetView);
                    int total = termToolbarContainer.getChildCount();
                    termToolbarContainer.removeView(targetView);

                    if (which == 0) { // 向左
                        termToolbarContainer.addView(targetView, Math.max(0, currentIndex - 1));
                    } else if (which == 1) { // 向右
                        termToolbarContainer.addView(targetView, Math.min(total - 1, currentIndex + 1));
                    } else if (which == 2) { // 置顶
                        termToolbarContainer.addView(targetView, 0);
                    } else if (which == 3) { // 移到最后
                        termToolbarContainer.addView(targetView, total - 1);
                    }
                    saveToolbarOrder();
                    Toast.makeText(this, "按钮位置已更新并保存", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // 快捷键自定义与注释管理对话框
    private void showCustomShortcutsManagerDialog() {
        String[] items = new String[shortcutKeys.size() + 1];
        items[0] = "➕ 添加新快捷键...";
        for (int i = 0; i < shortcutKeys.size(); i++) {
            ShortcutKey k = shortcutKeys.get(i);
            String desc = TextUtils.isEmpty(k.description) ? "无注释" : k.description;
            items[i + 1] = k.label + " (" + desc + ") → " + k.command.replace("\n", "");
        }

        new AlertDialog.Builder(this)
                .setTitle("⚙️ 快捷键与注释管理")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        showEditShortcutKeyDialog(null, true);
                    } else {
                        showEditShortcutKeyDialog(shortcutKeys.get(which - 1), false);
                    }
                })
                .setPositiveButton("完成", null)
                .show();
    }

    private void showEditShortcutKeyDialog(ShortcutKey existingKey, boolean isNew) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12));

        TextView labelPrompt = new TextView(this);
        labelPrompt.setText("按键名称 (Label):");
        labelPrompt.setTextColor(0xFFC9D1D9);
        layout.addView(labelPrompt);

        EditText etLabel = new EditText(this);
        etLabel.setHint("如: git st");
        etLabel.setTextColor(0xFFF0F6FC);
        etLabel.setText(existingKey != null ? existingKey.label : "");
        layout.addView(etLabel);

        TextView cmdPrompt = new TextView(this);
        cmdPrompt.setText("执行/插入指令 (Command):");
        cmdPrompt.setTextColor(0xFFC9D1D9);
        cmdPrompt.setPadding(0, dpToPx(8), 0, 0);
        layout.addView(cmdPrompt);

        EditText etCmd = new EditText(this);
        etCmd.setHint("如: git status\\n");
        etCmd.setTextColor(0xFFF0F6FC);
        etCmd.setText(existingKey != null ? existingKey.command.replace("\n", "\\n") : "");
        layout.addView(etCmd);

        TextView descPrompt = new TextView(this);
        descPrompt.setText("注释说明 (Description):");
        descPrompt.setTextColor(0xFFC9D1D9);
        descPrompt.setPadding(0, dpToPx(8), 0, 0);
        layout.addView(descPrompt);

        EditText etDesc = new EditText(this);
        etDesc.setHint("如: 查看当前 Git 工作区状态");
        etDesc.setTextColor(0xFFF0F6FC);
        etDesc.setText(existingKey != null ? existingKey.description : "");
        layout.addView(etDesc);

        android.widget.CheckBox cbDirectRun = new android.widget.CheckBox(this);
        cbDirectRun.setText("直接在终端执行 (不勾选则填入输入框)");
        cbDirectRun.setTextColor(0xFF8B949E);
        cbDirectRun.setChecked(existingKey == null || existingKey.isDirectRun);
        layout.addView(cbDirectRun);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(isNew ? "添加自定义快捷键" : "编辑快捷键与注释")
                .setView(layout)
                .setPositiveButton("保存", (dialog, which) -> {
                    String label = etLabel.getText().toString().trim();
                    String cmd = etCmd.getText().toString().replace("\\n", "\n");
                    String desc = etDesc.getText().toString().trim();
                    boolean directRun = cbDirectRun.isChecked();

                    if (TextUtils.isEmpty(label)) {
                        Toast.makeText(this, "按键名称不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (isNew) {
                        String id = "custom_" + System.currentTimeMillis();
                        shortcutKeys.add(new ShortcutKey(id, label, cmd, desc, directRun, false));
                    } else if (existingKey != null) {
                        existingKey.label = label;
                        existingKey.command = cmd;
                        existingKey.description = desc;
                        existingKey.isDirectRun = directRun;
                    }
                    saveCustomShortcuts();
                    renderTermuxKeys();
                    Toast.makeText(this, "快捷键已保存", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null);

        if (!isNew && existingKey != null && !existingKey.isSystem) {
            builder.setNeutralButton("删除", (dialog, which) -> {
                shortcutKeys.remove(existingKey);
                saveCustomShortcuts();
                renderTermuxKeys();
                Toast.makeText(this, "已删除快捷键", Toast.LENGTH_SHORT).show();
            });
        }

        builder.show();
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void adjustTermFontSize(float delta) {
        float newSize = currentTermFontSize + delta;
        if (newSize >= 8.0f && newSize <= 22.0f) {
            currentTermFontSize = newSize;
            terminalOutput.setTextSize(currentTermFontSize);
            PiMetConfig.setTermFontSize(this, currentTermFontSize);
            Toast.makeText(this, "终端字体: " + (int) currentTermFontSize + "sp", Toast.LENGTH_SHORT).show();
        }
    }

    private void insertTextToInput(String text) {
        if (commandInput == null || text == null) return;
        int start = Math.max(commandInput.getSelectionStart(), 0);
        int end = Math.max(commandInput.getSelectionEnd(), 0);
        commandInput.getText().replace(Math.min(start, end), Math.max(start, end), text, 0, text.length());
        commandInput.requestFocus();
    }

    private TerminalTab getActiveTab() {
        for (TerminalTab tab : terminalTabs) {
            if (tab.id == activeTabId) return tab;
        }
        if (!terminalTabs.isEmpty()) return terminalTabs.get(0);
        return null;
    }

    private void createTab(boolean switchToIt) {
        if (!ProotManager.isRootfsInstalled(this)) {
            Toast.makeText(this, "Linux 容器尚未部署，请先在控制中心部署！", Toast.LENGTH_SHORT).show();
            terminalOutput.setText("• Linux 容器尚未部署，请先在【控制中心】点击一键部署！");
            return;
        }

        int id = nextTabId++;
        TerminalTab tab = new TerminalTab(id, "窗口 " + id);
        terminalTabs.add(tab);

        startTabSession(tab);
        if (switchToIt) {
            switchToTab(id);
        } else {
            refreshTabsUi();
        }
    }

    private void startTabSession(TerminalTab tab) {
        if (tab.session != null) {
            tab.session.close();
        }
        tab.buffer.clear();
        tab.ansi.reset();

        tab.session = new ProotSession(this, new ProotSession.OutputListener() {
            @Override
            public void onOutput(String text) {
                appendTerminalLog(tab, text);
            }

            @Override
            public void onExit(int code) {
                appendTerminalLog(tab, "\n\u001B[33m[窗口 " + tab.id + " 已退出, 退出码: " + code + ", 点击上方重连]\u001B[0m\n");
            }
        });
        tab.session.start();
    }

    private void restartActiveTab() {
        TerminalTab tab = getActiveTab();
        if (tab != null) {
            startTabSession(tab);
            terminalOutput.setText(tab.buffer);
        }
    }

    private void closeTab(int id) {
        if (terminalTabs.size() <= 1) {
            Toast.makeText(this, "至少保留一个终端窗口", Toast.LENGTH_SHORT).show();
            return;
        }

        TerminalTab target = null;
        for (TerminalTab t : terminalTabs) {
            if (t.id == id) {
                target = t;
                break;
            }
        }
        if (target != null) {
            if (target.session != null) {
                target.session.close();
            }
            int index = terminalTabs.indexOf(target);
            terminalTabs.remove(target);
            if (activeTabId == id) {
                int nextIndex = Math.min(index, terminalTabs.size() - 1);
                switchToTab(terminalTabs.get(nextIndex).id);
            } else {
                refreshTabsUi();
            }
        }
    }

    private void switchToTab(int id) {
        activeTabId = id;
        TerminalTab tab = getActiveTab();
        if (tab != null) {
            terminalOutput.setText(tab.buffer);
            terminalScrollView.post(() -> terminalScrollView.fullScroll(ScrollView.FOCUS_DOWN));
        }
        refreshTabsUi();
    }

    private void refreshTabsUi() {
        if (termTabsContainer == null) return;
        termTabsContainer.removeAllViews();

        for (TerminalTab tab : terminalTabs) {
            View tabView = getLayoutInflater().inflate(R.layout.item_terminal_tab, termTabsContainer, false);
            TextView titleTv = tabView.findViewById(R.id.tabTitleTv);
            TextView closeBtn = tabView.findViewById(R.id.tabCloseBtn);

            titleTv.setText(tab.title);
            boolean isActive = (tab.id == activeTabId);
            if (isActive) {
                tabView.setBackgroundResource(R.drawable.bg_btn_primary);
                titleTv.setTextColor(0xFFFFFFFF);
                closeBtn.setTextColor(0xCCFFFFFF);
            } else {
                tabView.setBackgroundResource(R.drawable.bg_btn_secondary);
                titleTv.setTextColor(0xFF8B949E);
                closeBtn.setTextColor(0xFF8B949E);
            }

            if (terminalTabs.size() > 1) {
                closeBtn.setVisibility(View.VISIBLE);
                closeBtn.setOnClickListener(v -> closeTab(tab.id));
            } else {
                closeBtn.setVisibility(View.GONE);
            }

            tabView.setOnClickListener(v -> switchToTab(tab.id));
            termTabsContainer.addView(tabView);
        }

        if (btnNewTab != null) {
            if (btnNewTab.getParent() != null) {
                ((ViewGroup) btnNewTab.getParent()).removeView(btnNewTab);
            }
            int h = (int) (26 * getResources().getDisplayMetrics().density + 0.5f);
            int m = (int) (4 * getResources().getDisplayMetrics().density + 0.5f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, h);
            lp.setMargins(m, 0, 0, 0);
            termTabsContainer.addView(btnNewTab, lp);
        }
    }

    private void appendTerminalLog(TerminalTab tab, String text) {
        if (tab == null || text == null) return;
        mainHandler.post(() -> {
            tab.ansi.appendAnsiText(tab.buffer, text);
            if (tab.buffer.length() > 40000) {
                tab.buffer.delete(0, 8000);
            }
            TerminalTab active = getActiveTab();
            if (active != null && active.id == tab.id && viewTerminal.getVisibility() == View.VISIBLE) {
                terminalOutput.setText(tab.buffer);
                terminalScrollView.post(() -> terminalScrollView.fullScroll(ScrollView.FOCUS_DOWN));
            }
        });
    }

    private void executeCommand(String cmd) {
        TerminalTab tab = getActiveTab();
        if (tab == null) {
            createTab(true);
            tab = getActiveTab();
        }
        if (tab == null) return;

        if (tab.session == null || !tab.session.isRunning()) {
            Toast.makeText(this, "正在重新启动会话...", Toast.LENGTH_SHORT).show();
            startTabSession(tab);
        }

        String toSend = cmd.endsWith("\n") ? cmd : cmd + "\n";
        appendTerminalLog(tab, "\u001B[32mroot@pimet\u001B[0m:\u001B[34m~\u001B[0m# " + toSend);
        if (tab.session != null) {
            tab.session.write(toSend);
        }
    }

    private void sendCommand() {
        String cmd = commandInput.getText().toString();
        if (!TextUtils.isEmpty(cmd)) {
            commandHistory.add(cmd);
            historyIndex = -1;
            executeCommand(cmd + "\n");
            commandInput.setText("");
        }
    }

    // ================= 设置面板 =================
    private void initSettingsPanel() {
        // AI 模型与 API Key 配置初始化
        selectedProvider = PiMetConfig.getAiProvider(this);
        inputAiApiKey.setText(PiMetConfig.getAiApiKey(this));
        inputAiBaseUrl.setText(PiMetConfig.getAiBaseUrl(this));
        inputAiModel.setText(PiMetConfig.getAiModel(this));
        updateProviderChips();

        chipProviderDeepSeek.setOnClickListener(v -> selectProvider(PiMetConfig.PROVIDER_DEEPSEEK, "https://api.deepseek.com", "deepseek-chat"));
        chipProviderOpenAI.setOnClickListener(v -> selectProvider(PiMetConfig.PROVIDER_OPENAI, "https://api.openai.com/v1", "gpt-4o"));
        chipProviderClaude.setOnClickListener(v -> selectProvider(PiMetConfig.PROVIDER_CLAUDE, "https://api.anthropic.com", "claude-3-7-sonnet"));
        chipProviderOpenRouter.setOnClickListener(v -> selectProvider(PiMetConfig.PROVIDER_OPENROUTER, "https://openrouter.ai/api/v1", "anthropic/claude-3.7-sonnet"));
        chipProviderCustom.setOnClickListener(v -> selectProvider(PiMetConfig.PROVIDER_CUSTOM, "", ""));

        btnToggleKeyVisibility.setOnClickListener(v -> {
            isApiKeyVisible = !isApiKeyVisible;
            if (isApiKeyVisible) {
                inputAiApiKey.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            } else {
                inputAiApiKey.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            }
            inputAiApiKey.setSelection(inputAiApiKey.getText().length());
        });

        btnSaveAiConfig.setOnClickListener(v -> {
            String apiKey = inputAiApiKey.getText().toString().trim();
            String baseUrl = inputAiBaseUrl.getText().toString().trim();
            String model = inputAiModel.getText().toString().trim();

            PiMetConfig.setAiProvider(this, selectedProvider);
            PiMetConfig.setAiApiKey(this, apiKey);
            PiMetConfig.setAiBaseUrl(this, baseUrl);
            PiMetConfig.setAiModel(this, model);

            PiMetConfig.syncToContainer(this);
            updateLaunchModelDesc();
            Toast.makeText(this, "✔ AI 凭据已保存并安全同步至 PRoot 容器！", Toast.LENGTH_SHORT).show();
        });

        btnSyncAiFromContainer = findViewById(R.id.btnSyncAiFromContainer);
        if (btnSyncAiFromContainer != null) {
            btnSyncAiFromContainer.setOnClickListener(v -> {
                Toast.makeText(this, "正在从容器 (~/.pi/agent/) 读取配置...", Toast.LENGTH_SHORT).show();
                new Thread(() -> {
                    boolean updated = PiMetConfig.syncFromContainer(this);
                    mainHandler.post(() -> {
                        refreshSettingsUiFields();
                        if (updated) {
                            Toast.makeText(this, "✔ 已成功从容器同步最新 AI 凭据！", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "容器内部配置已与当前界面保持一致", Toast.LENGTH_SHORT).show();
                        }
                    });
                }).start();
            });
        }

        btnFetchAiModels.setOnClickListener(v -> fetchAiModels());

        int currentPort = PiMetConfig.getWebPort(this);
        settingsPortInput.setText(String.valueOf(currentPort));

        btnSavePort.setOnClickListener(v -> {
            String portStr = settingsPortInput.getText().toString().trim();
            try {
                int port = Integer.parseInt(portStr);
                if (port < 1024 || port > 65535) {
                    Toast.makeText(this, "请输入合法的端口号 (1024 - 65535)", Toast.LENGTH_SHORT).show();
                    return;
                }
                PiMetConfig.setWebPort(this, port);
                Toast.makeText(this, "端口配置已更新为 " + port, Toast.LENGTH_SHORT).show();
                checkServiceStatus();
            } catch (Exception e) {
                Toast.makeText(this, "无效端口", Toast.LENGTH_SHORT).show();
            }
        });

        updateRegistryButtons();
        btnRegistryMirror.setOnClickListener(v -> {
            PiMetConfig.setNpmRegistry(this, PiMetConfig.NPM_MIRROR_TAOBAO);
            updateRegistryButtons();
            Toast.makeText(this, "已切换为国内加速源", Toast.LENGTH_SHORT).show();
        });

        btnRegistryOfficial.setOnClickListener(v -> {
            PiMetConfig.setNpmRegistry(this, PiMetConfig.NPM_MIRROR_OFFICIAL);
            updateRegistryButtons();
            Toast.makeText(this, "已切换为官方 npm 源", Toast.LENGTH_SHORT).show();
        });

        btnClearNpmCache.setOnClickListener(v -> {
            executeCommand("npm cache clean --force\n");
            Toast.makeText(this, "已在容器内发送 npm 缓存清理指令", Toast.LENGTH_SHORT).show();
            openTerminalInWorkbench();
        });

        btnResetContainer.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("重置 Linux 容器？")
                    .setMessage("此操作将彻底删除内置 PRoot 容器文件系统，所有已安装的 npm 包及数据将被清空。")
                    .setPositiveButton("确认重置", (dialog, which) -> {
                        Toast.makeText(this, "正在清理容器目录...", Toast.LENGTH_SHORT).show();
                        for (TerminalTab t : terminalTabs) {
                            if (t.session != null) t.session.close();
                        }
                        terminalTabs.clear();
                        nextTabId = 1;
                        activeTabId = -1;
                        refreshTabsUi();
                        new Thread(() -> {
                            File rootfs = ProotManager.getRootfsDir(this);
                            ProotManager.deleteRecursive(rootfs);
                            mainHandler.post(() -> {
                                Toast.makeText(this, "容器重置完成，请点击自动部署以重新生成", Toast.LENGTH_LONG).show();
                                checkServiceStatus();
                                refreshStorageSize();
                            });
                        }).start();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });

        // 后台高保活与应用权限设置
        btnBatteryIgnoreOpt.setOnClickListener(v -> requestBatteryOptimizationExemption());
        btnAutoStartSettings.setOnClickListener(v -> openAppDetailSettings());

        // Root 提权
        btnPrivilegeRoot.setOnClickListener(v -> {
            Toast.makeText(this, "正在检测并申请 Root 权限...", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                boolean hasRoot = DevicePrivilegeManager.requestRootAccess();
                mainHandler.post(() -> {
                    if (hasRoot) {
                        btnPrivilegeRoot.setText("🛡️ 已获得 Root 权限 (uid=0)");
                        btnPrivilegeRoot.setTextColor(0xFF3FB950);
                        Toast.makeText(this, "🎉 成功获得系统 Root (uid=0) 权限！已开启宿主提权代理", Toast.LENGTH_LONG).show();
                        PiMetConfig.syncToContainer(this);
                    } else {
                        Toast.makeText(this, "未获得 Root 权限 (设备未 Root 或授权被拒绝)", Toast.LENGTH_SHORT).show();
                    }
                });
            }).start();
        });

        // Shizuku 特权
        btnPrivilegeShizuku.setOnClickListener(v -> {
            if (DevicePrivilegeManager.isShizukuPermissionGranted()) {
                Toast.makeText(this, "✔ 已获得 Shizuku 特权授权！", Toast.LENGTH_SHORT).show();
            } else if (DevicePrivilegeManager.isShizukuRunning()) {
                DevicePrivilegeManager.requestShizukuPermission(this);
            } else if (DevicePrivilegeManager.isShizukuInstalled(this)) {
                boolean opened = DevicePrivilegeManager.openShizukuApp(this);
                if (opened) {
                    Toast.makeText(this, "正在打开 Shizuku 管理器，请启动服务后再点击申请授权...", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "打开 Shizuku 失败", Toast.LENGTH_SHORT).show();
                }
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("⚡ Shizuku 特权未安装")
                        .setMessage("Shizuku 可通过无线调试 (ADB) 免 Root 为应用赋予系统级 ADB 权限。\n\n是否打开 Shizuku 官方主页下载？")
                        .setPositiveButton("前往下载", (dialog, which) -> {
                            try {
                                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/"));
                                startActivity(intent);
                            } catch (Throwable ignored) {}
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });

        // 全盘文件管理
        btnPrivilegeAllFiles.setOnClickListener(v -> {
            if (DevicePrivilegeManager.isAllFilesAccessGranted()) {
                Toast.makeText(this, "✔ 已拥有全盘所有文件读写权限", Toast.LENGTH_SHORT).show();
            } else {
                DevicePrivilegeManager.requestAllFilesAccess(this);
            }
        });

        // 剪贴板即时同步
        btnSyncClipboard.setOnClickListener(v -> syncClipboardToContainer(true));

        // 桌面宠物设置与动态预览组件
        previewPetView = findViewById(R.id.previewPetView);
        previewPetNameTv = findViewById(R.id.previewPetNameTv);
        previewPetDescTv = findViewById(R.id.previewPetDescTv);
        btnPreviewPlayAction = findViewById(R.id.btnPreviewPlayAction);
        layoutPetThumbList = findViewById(R.id.layoutPetThumbList);
        tvPetSizeLabel = findViewById(R.id.tvPetSizeLabel);
        sbPetSize = findViewById(R.id.sbPetSize);
        tvBubbleWidthLabel = findViewById(R.id.tvBubbleWidthLabel);
        sbBubbleWidth = findViewById(R.id.sbBubbleWidth);
        btnTogglePetEnabled = findViewById(R.id.btnTogglePetEnabled);
        btnPetParams = findViewById(R.id.btnPetParams);
        btnPetShop = findViewById(R.id.btnPetShop);
        btnToggleGlobalOverlay = findViewById(R.id.btnToggleGlobalOverlay);

        if (btnPreviewPlayAction != null) {
            btnPreviewPlayAction.setOnClickListener(v -> {
                if (previewPetView != null) {
                    String[] acts = {"dancing", "waving", "jumping"};
                    String act = acts[new java.util.Random().nextInt(acts.length)];
                    previewPetView.playOneShot(act);
                }
            });
        }

        if (sbPetSize != null) {
            int curSize = PetRegistry.getIntPref(this, PetRegistry.KEY_PET_SIZE, PetRegistry.DEFAULT_PET_SIZE);
            sbPetSize.setProgress(curSize - 32);
            if (tvPetSizeLabel != null) tvPetSizeLabel.setText("📏 宠物大小: " + curSize + " dp");
            sbPetSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    int val = 32 + progress;
                    if (tvPetSizeLabel != null) tvPetSizeLabel.setText("📏 宠物大小: " + val + " dp");
                    PetRegistry.setIntPref(MainActivity.this, PetRegistry.KEY_PET_SIZE, val);
                    applyPetParams();
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        if (sbBubbleWidth != null) {
            int curWidth = PetRegistry.getIntPref(this, PetRegistry.KEY_BUBBLE_WIDTH, PetRegistry.DEFAULT_BUBBLE_WIDTH);
            sbBubbleWidth.setProgress(curWidth - 140);
            if (tvBubbleWidthLabel != null) tvBubbleWidthLabel.setText("💬 气泡宽度: " + curWidth + " dp");
            sbBubbleWidth.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    int val = 140 + progress;
                    if (tvBubbleWidthLabel != null) tvBubbleWidthLabel.setText("💬 气泡宽度: " + val + " dp");
                    PetRegistry.setIntPref(MainActivity.this, PetRegistry.KEY_BUBBLE_WIDTH, val);
                    applyPetParams();
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        if (btnTogglePetEnabled != null) {
            btnTogglePetEnabled.setOnClickListener(v -> {
                boolean enabled = !PetRegistry.isPetEnabled(this);
                PetRegistry.setPetEnabled(this, enabled);
                updatePetDisplay(enabled);
                Toast.makeText(this, enabled ? "已开启桌宠悬浮形态" : "已切换为极简悬浮球", Toast.LENGTH_SHORT).show();
            });
        }
        if (btnPetParams != null) {
            btnPetParams.setOnClickListener(v -> showPetParamsDialog());
        }
        if (btnPetShop != null) {
            btnPetShop.setOnClickListener(v -> startActivity(new Intent(this, PetShopActivity.class)));
        }
        if (btnToggleGlobalOverlay != null) {
            btnToggleGlobalOverlay.setText(PetOverlayService.isRunning()
                    ? "🌐 系统全局桌宠悬浮窗: 运行中 (点击关闭)"
                    : "🌐 系统全局桌宠悬浮窗: 未开启 (点击开启)");
            btnToggleGlobalOverlay.setOnClickListener(v -> toggleGlobalOverlay());
        }

        updatePetPreview();
        buildPetList();

        refreshStorageSize();
        refreshPrivilegeStatus();
    }

    private void checkBatteryOptimizationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    new AlertDialog.Builder(this)
                            .setTitle("⚡ 开启后台高保活模式")
                            .setMessage("检测到系统尚未为 PiMet 开启电池白名单。\n\n为保证在外部浏览器使用 Pi-Web 或息屏时后台服务不被系统冻结杀掉，建议允许忽略电池优化。")
                            .setPositiveButton("立即开启", (dialog, which) -> requestBatteryOptimizationExemption())
                            .setNegativeButton("稍后再说", null)
                            .show();
                }
            } catch (Throwable ignored) {}
        }
    }

    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Toast.makeText(this, "✔ 已获得电池优化豁免，后台运行不受限制", Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Throwable t) {
                try {
                    Intent intent = new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                    startActivity(intent);
                } catch (Throwable ex) {
                    Toast.makeText(this, "无法打开系统电池优化界面: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        } else {
            Toast.makeText(this, "当前 Android 版本无需配置电池优化", Toast.LENGTH_SHORT).show();
        }
    }

    private void openAppDetailSettings() {
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Throwable e) {
            Toast.makeText(this, "打开应用设置失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshPrivilegeStatus() {
        if (btnPrivilegeRoot != null) {
            if (DevicePrivilegeManager.isRootBinaryPresent()) {
                btnPrivilegeRoot.setText("🛡️ 申请 Root 提权 (已检测到 su)");
            }
        }
        if (btnPrivilegeAllFiles != null) {
            if (DevicePrivilegeManager.isAllFilesAccessGranted()) {
                btnPrivilegeAllFiles.setText("📂 全盘文件权限: 已授权");
                btnPrivilegeAllFiles.setTextColor(0xFF3FB950);
            } else {
                btnPrivilegeAllFiles.setText("📂 申请全盘文件权限");
                btnPrivilegeAllFiles.setTextColor(0xFF58A6FF);
            }
        }
        if (btnPrivilegeShizuku != null) {
            if (DevicePrivilegeManager.isShizukuPermissionGranted()) {
                btnPrivilegeShizuku.setText("⚡ Shizuku: 已授权 (ADB)");
                btnPrivilegeShizuku.setTextColor(0xFF3FB950);
            } else if (DevicePrivilegeManager.isShizukuRunning()) {
                btnPrivilegeShizuku.setText("⚡ 申请 Shizuku 授权");
                btnPrivilegeShizuku.setTextColor(0xFF58A6FF);
            } else if (DevicePrivilegeManager.isShizukuInstalled(this)) {
                btnPrivilegeShizuku.setText("⚡ Shizuku: 启动服务");
                btnPrivilegeShizuku.setTextColor(0xFFD29922);
            } else {
                btnPrivilegeShizuku.setText("⚡ Shizuku 特权");
                btnPrivilegeShizuku.setTextColor(0xFFC9D1D9);
            }
        }
        if (settingsShizukuStatusTv != null) {
            if (DevicePrivilegeManager.isShizukuPermissionGranted()) {
                settingsShizukuStatusTv.setText("Shizuku 状态: ✔ 已获得特权授权 (ADB 级免 Root 权限)");
                settingsShizukuStatusTv.setTextColor(0xFF3FB950);
            } else if (DevicePrivilegeManager.isShizukuRunning()) {
                settingsShizukuStatusTv.setText("Shizuku 状态: ⚡ 服务运行中 (点击按钮立即授权)");
                settingsShizukuStatusTv.setTextColor(0xFF58A6FF);
            } else if (DevicePrivilegeManager.isShizukuInstalled(this)) {
                settingsShizukuStatusTv.setText("Shizuku 状态: ⚠️ 已安装管理器但服务未启动");
                settingsShizukuStatusTv.setTextColor(0xFFD29922);
            } else {
                settingsShizukuStatusTv.setText("Shizuku 状态: ✕ 未安装 Shizuku 管理器");
                settingsShizukuStatusTv.setTextColor(0xFF8B949E);
            }
        }
        if (btnBatteryIgnoreOpt != null) {
            if (DevicePrivilegeManager.isIgnoringBatteryOptimizations(this)) {
                btnBatteryIgnoreOpt.setText("🔋 电池白名单: 已豁免");
                btnBatteryIgnoreOpt.setTextColor(0xFF3FB950);
            } else {
                btnBatteryIgnoreOpt.setText("🔋 申请电池白名单");
                btnBatteryIgnoreOpt.setTextColor(0xFF58A6FF);
            }
        }
    }

    private void showClipboardActionsDialog() {
        String clip = getClipboardText();
        String preview = TextUtils.isEmpty(clip) ? "（剪贴板为空）" : (clip.length() > 60 ? clip.substring(0, 60) + "..." : clip);

        new AlertDialog.Builder(this)
                .setTitle("📋 剪贴板操作")
                .setMessage("当前剪贴板内容：\n" + preview)
                .setItems(new String[]{
                        "💬 作为提问直接发送给 AI",
                        "⌨️ 粘贴到终端命令行",
                        "💾 保存至容器 (/root/.clipboard.txt)"
                }, (dialog, which) -> {
                    if (TextUtils.isEmpty(clip)) {
                        Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    switch (which) {
                        case 0:
                            String escaped = clip.replace("'", "'\\''");
                            executeCommand("pi -p --continue '" + escaped + "'\n");
                            Toast.makeText(this, "已作为提问发送给 AI", Toast.LENGTH_SHORT).show();
                            break;
                        case 1:
                            commandInput.append(clip);
                            commandInput.requestFocus();
                            break;
                        case 2:
                            syncClipboardToContainer(true);
                            break;
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String getClipboardText() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence text = cm.getPrimaryClip().getItemAt(0).getText();
                if (text != null) return text.toString();
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private void syncClipboardToContainer(boolean showToast) {
        new Thread(() -> {
            String clip = getClipboardText();
            try {
                File rootfs = ProotManager.getRootfsDir(this);
                File clipFile = new File(rootfs, "root/.clipboard.txt");
                try (FileOutputStream fos = new FileOutputStream(clipFile, false)) {
                    fos.write(clip.getBytes(StandardCharsets.UTF_8));
                }
                mainHandler.post(() -> {
                    if (showToast) {
                        Toast.makeText(this, "✔ 剪贴板内容已同步至 /root/.clipboard.txt", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Throwable t) {
                if (showToast) {
                    mainHandler.post(() -> Toast.makeText(this, "同步失败: " + t.getMessage(), Toast.LENGTH_SHORT).show());
                }
            }
        }).start();
    }

    private void fetchAiModels() {
        String apiKey = inputAiApiKey.getText().toString().trim();
        String baseUrl = inputAiBaseUrl.getText().toString().trim();

        if (TextUtils.isEmpty(baseUrl)) {
            Toast.makeText(this, "⚠️ 请先填写 API Base URL (接口地址)", Toast.LENGTH_SHORT).show();
            inputAiBaseUrl.requestFocus();
            return;
        }

        if (TextUtils.isEmpty(apiKey)) {
            Toast.makeText(this, "⚠️ 请先填写 API Key (密钥)", Toast.LENGTH_SHORT).show();
            inputAiApiKey.requestFocus();
            return;
        }

        Toast.makeText(this, "🔍 正在连接接口自动获取在线模型...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                String cleanUrl = baseUrl.replaceAll("/+$", "");
                String requestUrl;
                if (cleanUrl.endsWith("/v1")) {
                    requestUrl = cleanUrl + "/models";
                } else {
                    requestUrl = cleanUrl + "/v1/models";
                }

                conn = (HttpURLConnection) new URL(requestUrl).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(12000);
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setRequestProperty("Content-Type", "application/json");

                int code = conn.getResponseCode();
                InputStream in = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();

                StringBuilder resp = new StringBuilder();
                if (in != null) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            resp.append(line);
                        }
                    }
                }

                if (code >= 200 && code < 400) {
                    List<String> modelList = parseModelsFromJson(resp.toString());
                    if (modelList.isEmpty()) {
                        mainHandler.post(() -> Toast.makeText(this, "接口返回成功，但未解析到模型", Toast.LENGTH_LONG).show());
                        return;
                    }
                    mainHandler.post(() -> showModelSelectDialog(modelList));
                } else {
                    String errorMsg = "HTTP " + code + ": " + (resp.length() > 80 ? resp.substring(0, 80) : resp.toString());
                    mainHandler.post(() -> Toast.makeText(this, "获取模型失败: " + errorMsg, Toast.LENGTH_LONG).show());
                }

            } catch (Throwable t) {
                Log.e(TAG, "fetchAiModels error", t);
                mainHandler.post(() -> Toast.makeText(this, "网络请求异常: " + t.getMessage(), Toast.LENGTH_LONG).show());
            } finally {
                if (conn != null) {
                    try { conn.disconnect(); } catch (Throwable ignored) {}
                }
            }
        }).start();
    }

    private List<String> parseModelsFromJson(String json) {
        List<String> list = new ArrayList<>();
        try {
            JSONObject obj = new JSONObject(json);
            if (obj.has("data")) {
                JSONArray data = obj.getJSONArray("data");
                for (int i = 0; i < data.length(); i++) {
                    JSONObject m = data.getJSONObject(i);
                    if (m.has("id")) {
                        list.add(m.getString("id"));
                    }
                }
            } else if (obj.has("models")) {
                JSONArray models = obj.getJSONArray("models");
                for (int i = 0; i < models.length(); i++) {
                    JSONObject m = models.getJSONObject(i);
                    if (m.has("name")) {
                        list.add(m.getString("name"));
                    } else if (m.has("model")) {
                        list.add(m.getString("model"));
                    }
                }
            }
        } catch (Throwable ignored) {}
        Collections.sort(list);
        return list;
    }

    private void showModelSelectDialog(List<String> models) {
        String[] modelArray = models.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("在线获取成功 (共 " + models.size() + " 个模型)")
                .setItems(modelArray, (dialog, which) -> {
                    String chosen = modelArray[which];
                    inputAiModel.setText(chosen);
                    btnSaveAiConfig.performClick();
                    Toast.makeText(this, "🎉 已设定并同步模型: " + chosen, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void selectProvider(String provider, String defaultBaseUrl, String defaultModel) {
        selectedProvider = provider;
        if (!TextUtils.isEmpty(defaultBaseUrl)) {
            inputAiBaseUrl.setText(defaultBaseUrl);
        }
        if (!TextUtils.isEmpty(defaultModel)) {
            inputAiModel.setText(defaultModel);
        }
        updateProviderChips();
    }

    private void updateProviderChips() {
        int primaryBg = R.drawable.bg_btn_primary;
        int secBg = R.drawable.bg_btn_secondary;

        chipProviderDeepSeek.setBackgroundResource(PiMetConfig.PROVIDER_DEEPSEEK.equals(selectedProvider) ? primaryBg : secBg);
        chipProviderDeepSeek.setTextColor(PiMetConfig.PROVIDER_DEEPSEEK.equals(selectedProvider) ? 0xFFFFFFFF : 0xFFC9D1D9);

        chipProviderOpenAI.setBackgroundResource(PiMetConfig.PROVIDER_OPENAI.equals(selectedProvider) ? primaryBg : secBg);
        chipProviderOpenAI.setTextColor(PiMetConfig.PROVIDER_OPENAI.equals(selectedProvider) ? 0xFFFFFFFF : 0xFFC9D1D9);

        chipProviderClaude.setBackgroundResource(PiMetConfig.PROVIDER_CLAUDE.equals(selectedProvider) ? primaryBg : secBg);
        chipProviderClaude.setTextColor(PiMetConfig.PROVIDER_CLAUDE.equals(selectedProvider) ? 0xFFFFFFFF : 0xFFC9D1D9);

        chipProviderOpenRouter.setBackgroundResource(PiMetConfig.PROVIDER_OPENROUTER.equals(selectedProvider) ? primaryBg : secBg);
        chipProviderOpenRouter.setTextColor(PiMetConfig.PROVIDER_OPENROUTER.equals(selectedProvider) ? 0xFFFFFFFF : 0xFFC9D1D9);

        chipProviderCustom.setBackgroundResource(PiMetConfig.PROVIDER_CUSTOM.equals(selectedProvider) ? primaryBg : secBg);
        chipProviderCustom.setTextColor(PiMetConfig.PROVIDER_CUSTOM.equals(selectedProvider) ? 0xFFFFFFFF : 0xFFC9D1D9);
    }

    private void updateRegistryButtons() {
        String currentRegistry = PiMetConfig.getNpmRegistry(this);
        boolean isMirror = currentRegistry.contains("npmmirror");
        btnRegistryMirror.setBackgroundResource(isMirror ? R.drawable.bg_btn_primary : R.drawable.bg_btn_secondary);
        btnRegistryOfficial.setBackgroundResource(!isMirror ? R.drawable.bg_btn_primary : R.drawable.bg_btn_secondary);
    }

    private void refreshStorageSize() {
        new Thread(() -> {
            File rootfs = ProotManager.getRootfsDir(this);
            long size = ProotManager.getDirectorySize(rootfs);
            String formatted = ProotManager.formatSize(size);
            mainHandler.post(() -> {
                settingsStorageTv.setText("容器已占存储空间: " + formatted);
                launchMetricSizeTv.setText("存储占用: " + formatted);
            });
        }).start();
    }

    // ================= 通用工具方法 =================
    private void openExternalBrowser() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(getPiWebUrl()));
            startActivity(Intent.createChooser(intent, "选择外部浏览器打开 Pi-Web"));
        } catch (Exception e) {
            Toast.makeText(this, "未能调起系统浏览器: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void copyTextToClipboard(String text, String tip) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            ClipData clip = ClipData.newPlainText("pimet_url", text);
            cm.setPrimaryClip(clip);
            Toast.makeText(this, tip, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (floatingMenuVertical != null && floatingMenuVertical.getVisibility() == View.VISIBLE) {
            floatingMenuVertical.setVisibility(View.GONE);
            return;
        }
        if (viewTerminal != null && viewTerminal.getVisibility() == View.VISIBLE) {
            closeTerminalInWorkbench();
            return;
        }
        if (isFullscreen) {
            toggleFullscreen(false);
            return;
        }
        if (viewPiWeb.getVisibility() == View.VISIBLE && piWebWebView.canGoBack()) {
            piWebWebView.goBack();
            return;
        }
        super.onBackPressed();
    }

    private void launchFilePickerForContainer() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(Intent.createChooser(intent, "选择导入到容器的文件或图片"), REQUEST_CODE_IMPORT_CONTAINER);
        } catch (Exception e) {
            Toast.makeText(this, "未能打开系统文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    private void handleImportToContainer(Intent data) {
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            int count = data.getClipData().getItemCount();
            for (int i = 0; i < count; i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }

        if (uris.isEmpty()) return;

        Toast.makeText(this, "正在导入 " + uris.size() + " 个文件...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            int successCount = 0;
            StringBuilder sb = new StringBuilder();
            for (Uri uri : uris) {
                String fileName = ProotManager.copyUriToContainer(this, uri, "root");
                if (fileName != null) {
                    successCount++;
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(fileName);
                }
            }
            final int count = successCount;
            final String names = sb.toString();
            mainHandler.post(() -> {
                if (count > 0) {
                    Toast.makeText(this, "成功导入 " + count + " 个文件至 /root", Toast.LENGTH_SHORT).show();
                    TerminalTab tab = getActiveTab();
                    if (tab != null) {
                        appendTerminalLog(tab, "\n\u001B[32m[已导入 " + count + " 个文件至 /root: " + names + "]\u001B[0m\n");
                    }
                    executeCommand("ls -la /root\n");
                } else {
                    Toast.makeText(this, "导入文件失败，请检查文件或权限", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_FILE_CHOOSER) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        } else if (requestCode == REQUEST_CODE_IMPORT_CONTAINER) {
            if (resultCode == RESULT_OK && data != null) {
                handleImportToContainer(data);
            }
        }
    }

    private void checkStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            List<String> needed = new ArrayList<>();
            if (Build.VERSION.SDK_INT >= 33) {
                if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                    needed.add(Manifest.permission.READ_MEDIA_IMAGES);
                }
                if (checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                    needed.add(Manifest.permission.READ_MEDIA_VIDEO);
                }
                if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    needed.add(Manifest.permission.READ_MEDIA_AUDIO);
                }
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    needed.add(Manifest.permission.POST_NOTIFICATIONS);
                }
            } else {
                if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    needed.add(Manifest.permission.READ_EXTERNAL_STORAGE);
                }
            }
            if (!needed.isEmpty()) {
                requestPermissions(needed.toArray(new String[0]), REQUEST_CODE_PERMISSIONS);
            }
        }
    }

    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener =
            (requestCode, grantResult) -> {
                if (requestCode == DevicePrivilegeManager.SHIZUKU_REQUEST_CODE) {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        Toast.makeText(this, "🎉 Shizuku 特权授权成功！", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "❌ Shizuku 授权被拒绝", Toast.LENGTH_SHORT).show();
                    }
                    refreshPrivilegeStatus();
                }
            };

    private void initPluginsPanel() {
        btnRefreshPlugins = findViewById(R.id.btnRefreshPlugins);
        chipCatAll = findViewById(R.id.chipCatAll);
        chipCatExtensions = findViewById(R.id.chipCatExtensions);
        chipCatSkills = findViewById(R.id.chipCatSkills);
        chipCatMcp = findViewById(R.id.chipCatMcp);
        chipCatSubagents = findViewById(R.id.chipCatSubagents);
        layoutPluginItems = findViewById(R.id.layoutPluginItems);
        tvPluginEmpty = findViewById(R.id.tvPluginEmpty);
        inputCustomPlugin = findViewById(R.id.inputCustomPlugin);
        btnInstallCustomPlugin = findViewById(R.id.btnInstallCustomPlugin);

        if (chipCatAll != null) chipCatAll.setOnClickListener(v -> selectPluginCategory(0));
        if (chipCatExtensions != null) chipCatExtensions.setOnClickListener(v -> selectPluginCategory(1));
        if (chipCatSkills != null) chipCatSkills.setOnClickListener(v -> selectPluginCategory(2));
        if (chipCatMcp != null) chipCatMcp.setOnClickListener(v -> selectPluginCategory(3));
        if (chipCatSubagents != null) chipCatSubagents.setOnClickListener(v -> selectPluginCategory(4));

        if (btnRefreshPlugins != null) {
            btnRefreshPlugins.setOnClickListener(v -> {
                Toast.makeText(this, "正在重新扫描容器内部插件...", Toast.LENGTH_SHORT).show();
                refreshPluginsList(currentPluginCategory);
            });
        }

        if (btnInstallCustomPlugin != null) {
            btnInstallCustomPlugin.setOnClickListener(v -> {
                String pkg = inputCustomPlugin != null ? inputCustomPlugin.getText().toString().trim() : "";
                if (pkg.isEmpty()) {
                    Toast.makeText(this, "请输入需要安装的 npm 包名或插件名称", Toast.LENGTH_SHORT).show();
                    return;
                }
                openTerminalInWorkbench();
                TerminalTab active = getActiveTab();
                if (active != null && active.session != null) {
                    active.session.write("npm install -g " + pkg + " --registry=" + PiMetConfig.getNpmRegistry(this) + "\n");
                    Toast.makeText(this, "已发送至终端安装: " + pkg, Toast.LENGTH_SHORT).show();
                }
            });
        }

        refreshPluginsList(0);
    }

    private void selectPluginCategory(int index) {
        currentPluginCategory = index;
        int activeBg = R.drawable.bg_btn_primary;
        int normalBg = R.drawable.bg_btn_secondary;
        int activeText = 0xFFFFFFFF;
        int normalText = 0xFFC9D1D9;

        if (chipCatAll != null) {
            chipCatAll.setBackgroundResource(index == 0 ? activeBg : normalBg);
            chipCatAll.setTextColor(index == 0 ? activeText : normalText);
        }
        if (chipCatExtensions != null) {
            chipCatExtensions.setBackgroundResource(index == 1 ? activeBg : normalBg);
            chipCatExtensions.setTextColor(index == 1 ? activeText : normalText);
        }
        if (chipCatSkills != null) {
            chipCatSkills.setBackgroundResource(index == 2 ? activeBg : normalBg);
            chipCatSkills.setTextColor(index == 2 ? activeText : normalText);
        }
        if (chipCatMcp != null) {
            chipCatMcp.setBackgroundResource(index == 3 ? activeBg : normalBg);
            chipCatMcp.setTextColor(index == 3 ? activeText : normalText);
        }
        if (chipCatSubagents != null) {
            chipCatSubagents.setBackgroundResource(index == 4 ? activeBg : normalBg);
            chipCatSubagents.setTextColor(index == 4 ? activeText : normalText);
        }

        refreshPluginsList(index);
    }

    private void refreshPluginsList(int category) {
        currentPluginCategory = category;
        new Thread(() -> {
            List<PluginManager.PluginItem> all = PluginManager.getInstalledPlugins(this);
            List<PluginManager.PluginItem> filtered = new ArrayList<>();
            for (PluginManager.PluginItem item : all) {
                if (category == 0) {
                    filtered.add(item);
                } else if (category == 1 && item.type == PluginManager.PluginItem.TYPE_EXTENSION) {
                    filtered.add(item);
                } else if (category == 2 && item.type == PluginManager.PluginItem.TYPE_SKILL) {
                    filtered.add(item);
                } else if (category == 3 && item.type == PluginManager.PluginItem.TYPE_MCP) {
                    filtered.add(item);
                } else if (category == 4 && item.type == PluginManager.PluginItem.TYPE_SUBAGENT) {
                    filtered.add(item);
                }
            }
            mainHandler.post(() -> {
                if (layoutPluginItems == null) return;
                layoutPluginItems.removeAllViews();
                if (filtered.isEmpty()) {
                    if (tvPluginEmpty != null) tvPluginEmpty.setVisibility(View.VISIBLE);
                } else {
                    if (tvPluginEmpty != null) tvPluginEmpty.setVisibility(View.GONE);
                    for (PluginManager.PluginItem item : filtered) {
                        layoutPluginItems.addView(createPluginItemView(item));
                    }
                }
            });
        }).start();
    }

    private View createPluginItemView(PluginManager.PluginItem item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card_modern);
        card.setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dpToPx(8);
        card.setLayoutParams(lp);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText(item.name);
        title.setTextColor(0xFFF0F6FC);
        title.setTextSize(14f);
        title.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        title.setLayoutParams(tLp);
        header.addView(title);

        TextView badge = new TextView(this);
        badge.setText(item.getTypeName());
        badge.setTextColor(0xFF58A6FF);
        badge.setTextSize(11f);
        badge.setBackgroundResource(R.drawable.bg_badge_port);
        badge.setPadding(dpToPx(6), dpToPx(2), dpToPx(6), dpToPx(2));
        header.addView(badge);

        card.addView(header);

        TextView desc = new TextView(this);
        desc.setText(item.description + "\n路径: " + item.path);
        desc.setTextColor(0xFF8B949E);
        desc.setTextSize(12f);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dLp.topMargin = dpToPx(6);
        desc.setLayoutParams(dLp);
        card.addView(desc);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        aLp.topMargin = dpToPx(8);
        actions.setLayoutParams(aLp);

        TextView btnDelete = new TextView(this);
        btnDelete.setText("🗑️ 移除此插件");
        btnDelete.setTextColor(0xFFF85149);
        btnDelete.setTextSize(12f);
        btnDelete.setBackgroundResource(R.drawable.bg_btn_secondary);
        btnDelete.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6));
        btnDelete.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("确认移除插件")
                    .setMessage("确定要从容器中删除 " + item.name + " (" + item.getTypeName() + ") 吗？")
                    .setPositiveButton("移除", (d, w) -> {
                        boolean ok = PluginManager.deletePlugin(this, item);
                        if (ok) {
                            Toast.makeText(this, "已移除: " + item.name, Toast.LENGTH_SHORT).show();
                            refreshPluginsList(currentPluginCategory);
                        } else {
                            Toast.makeText(this, "移除失败，请在终端中检查权限", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        actions.addView(btnDelete);
        card.addView(actions);

        return card;
    }

    private void refreshSettingsUiFields() {
        selectedProvider = PiMetConfig.getAiProvider(this);
        if (inputAiApiKey != null) inputAiApiKey.setText(PiMetConfig.getAiApiKey(this));
        if (inputAiBaseUrl != null) inputAiBaseUrl.setText(PiMetConfig.getAiBaseUrl(this));
        if (inputAiModel != null) inputAiModel.setText(PiMetConfig.getAiModel(this));
        updateProviderChips();
        updateLaunchModelDesc();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra("pimet.open_terminal", false)) {
            openTerminalInWorkbench();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        initPetMonitor();
        applyPetParams();
        updatePetDisplay(PetRegistry.isPetEnabled(this));
        updatePetPreview();
        buildPetList();
        if (btnToggleGlobalOverlay != null) {
            btnToggleGlobalOverlay.setText(PetOverlayService.isRunning()
                    ? "🌐 系统全局桌宠悬浮窗: 运行中 (点击关闭)"
                    : "🌐 系统全局桌宠悬浮窗: 未开启 (点击开启)");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (previewPetView != null) {
            previewPetView.stopTicker();
        }
        if (piWebMonitor != null) {
            piWebMonitor.stop();
        }
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
        } catch (Throwable ignored) {}
        for (TerminalTab tab : terminalTabs) {
            if (tab.session != null) {
                tab.session.close();
            }
        }
        terminalTabs.clear();
    }
}
