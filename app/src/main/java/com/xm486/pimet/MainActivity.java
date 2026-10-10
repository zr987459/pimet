package com.xm486.pimet;

import android.Manifest;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
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
import com.xm486.pimet.monitor.PortDetector;
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
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

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
    private View viewSettings;

    private LinearLayout tabLaunch;
    private LinearLayout tabPiWeb;
    private LinearLayout tabPlugins;
    private LinearLayout tabSettings;

    private TextView tabLaunchText;
    private TextView tabPiWebText;
    private TextView tabPluginsText;
    private TextView tabSettingsText;

    // 桌面宠物组件
    public SpritePetView floatingPetView;
    private LinearLayout petBubbleLayout;
    private TextView petBubbleTv;
    public LinearLayout floatingPetChatCard;
    public View viewPetStatusDot;
    public TextView tvPetStatusText;
    public TextView tvPetCardStream;
    public TextView tvPetThinking;
    public TextView tvPetToolStatus;
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
    private LinearLayout physicsContainer;
    private static final android.util.LruCache<String, Bitmap> PET_THUMB_CACHE = new android.util.LruCache<>(24);

    // 原生桌宠物理手感引擎与 HUD 浮动控制弹窗
    private AlertDialog petHudDialog;
    private VelocityTracker inAppVelocityTracker;
    private ValueAnimator inAppFlingAnimator;
    private long inAppLastFlingFrame;

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
    private View btnLaunchFileManager;
    private View btnLaunchPetToggle;
    private TextView launchPetStatusTv;
    private TextView launchPetIconTv;
    private TextView launchPetStateBadge;
    private View btnLaunchLogDetail;
    private View btnLaunchLogCopy;
    private View btnLaunchLogRefresh;
    private View btnLaunchLogClear;
    private TextView launchLogTv;
    private boolean isDetailLogMode = false;

    // 更新中心组件
    private TextView settingsAppVersionTv;
    private View btnCheckAppUpdate;
    private TextView settingsPiWebVersionTv;
    private View btnCheckPiWebUpdate;

    // 启动页自定义端口快捷输入组件
    private EditText launchPortInput;
    private View btnLaunchSavePort;

    // 设置页从上往下二级分类菜单组件
    private View menuItemPorts;
    private TextView menuTitlePorts;
    private TextView menuArrowPorts;

    private View menuItemUpdate;
    private TextView menuTitleUpdate;
    private TextView menuArrowUpdate;

    private View menuItemPet;
    private TextView menuTitlePet;
    private TextView menuArrowPet;

    private View menuItemPrivileges;
    private TextView menuTitlePrivileges;
    private TextView menuArrowPrivileges;

    private View menuItemStorage;
    private TextView menuTitleStorage;
    private TextView menuArrowStorage;

    private View menuItemAbout;
    private TextView menuTitleAbout;
    private TextView menuArrowAbout;

    // 二级设置独立窗口组件
    private View viewSettingsSubWindow;
    private View btnSettingsSubWindowBack;
    private TextView tvSettingsSubWindowTitle;
    private View btnSettingsSubWindowClose;
    private View btnSettingsExportBackup;
    private View btnSettingsImportBackup;

    // 设置页各个卡片
    private View cardSettingsPorts;
    private View cardSettingsUpdate;
    private View cardSettingsRegistry;
    private View cardSettingsStorage;
    private View cardSettingsPrivileges;
    private View cardSettingsPetPreview;
    private View cardSettingsPetProactive;
    private View cardSettingsAbout;

    private static final int SETTINGS_CAT_PORTS = 0;
    private static final int SETTINGS_CAT_UPDATE = 1;
    private static final int SETTINGS_CAT_PET = 2;
    private static final int SETTINGS_CAT_PRIVILEGES = 3;
    private static final int SETTINGS_CAT_STORAGE = 4;
    private static final int SETTINGS_CAT_ABOUT = 5;
    private int currentSettingsCategory = SETTINGS_CAT_PORTS;

    // Pi-Web 工作台视图组件
    private View piWebStatusDot;
    private TextView piWebTitleTv;
    private View piWebReloadBtn;
    private View piWebBrowserBtn;
    private WebView piWebWebView;
    private View piWebOfflineCard;
    private TextView piWebOfflineSubTv;
    private View piWebWakeBtn;

    // 布局全屏与增强组件
    private View bottomNavBar;
    private ProgressBar piWebProgressBar;
    private FrameLayout floatingMenuContainer;
    private View floatingMenuVertical;
    private TextView floatingBall;
    private TextView btnFloatFullscreen;
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
    private EditText settingsPortInput;
    private EditText settingsOperitPortInput;
    private EditText settingsOperitTokenInput;
    private View btnTestOperitPort;
    private TextView tvOperitPortStatus;

    private EditText settingsClawbenchPortInput;
    private EditText settingsClawbenchTokenInput;
    private View btnTestClawbenchPort;
    private TextView tvClawbenchPortStatus;

    private EditText settingsRikkaPortInput;
    private View btnTestRikkaPort;
    private TextView tvRikkaPortStatus;

    private View btnTestPiWebPort;
    private TextView tvPiWebPortStatus;
    private View btnResetDefaultPorts;

    private View btnPetChipPiWeb;
    private View btnPetChipCustomPorts;

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
    private View btnFloatClipboard;

    private Button btnToggleProactiveSettings;
    private Button btnAdjustProactiveIntervalSettings;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isPiWebAlive = false;
    private boolean isDeploying = false;
    private volatile boolean isPiWebActionInProgress = false;
    private volatile boolean isOfflineRecovering = false;

    private final BroadcastReceiver overlayStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updatePetDisplay(PetRegistry.isPetEnabled(MainActivity.this));
            updateLaunchPetUI();
            if (btnToggleGlobalOverlay != null) {
                btnToggleGlobalOverlay.setText(PetOverlayService.isRunning()
                        ? "🌐 系统全局桌宠悬浮窗: 运行中 (点击关闭)"
                        : "🌐 系统全局桌宠悬浮窗: 未开启 (点击开启)");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        try {
            IntentFilter filter = new IntentFilter(PetOverlayService.ACTION_OVERLAY_STATE_CHANGED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(overlayStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(overlayStateReceiver, filter);
            }
        } catch (Throwable ignored) {}

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
        initSettingsPanel();

        // 首次状态自检
        checkServiceStatus();

        if (getIntent() != null && getIntent().getBooleanExtra("pimet.open_terminal", false)) {
            mainHandler.postDelayed(this::openTerminalInWorkbench, 300);
        }
        if (getIntent() != null && getIntent().getBooleanExtra("pimet.open_web", false)) {
            mainHandler.postDelayed(() -> switchTab(1), 300);
        }
        if (getIntent() != null && (getIntent().getBooleanExtra("pimet.open_settings", false)
                || getIntent().getBooleanExtra("devpetm.open_ai_tab", false))) {
            mainHandler.postDelayed(() -> switchTab(3), 300);
        }
        if (getIntent() != null && (getIntent().getBooleanExtra("pimet.toggle_web_fullscreen", false)
                || getIntent().getBooleanExtra("pimet.open_pet_chat", false))) {
            mainHandler.postDelayed(() -> {
                switchTab(1);
                toggleFullscreen(true);
            }, 300);
        }

        // 初始化并启动 App 与 AI 宿主控制桥 (AppBridgeManager)
        com.xm486.pimet.bridge.AppBridgeManager.getInstance(this).start();
        com.xm486.pimet.bridge.AppBridgeManager.getInstance(this).attachActivity(this);
        PluginManager.ensureAndroidBridgeExtension(this);
        com.xm486.pimet.pet.PetMemoryManager.ensurePetSubagentInstalled(this);
    }

    private String getPiWebUrl() {
        int port = PiMetConfig.getWebPort(this);
        return "http://127.0.0.1:" + port;
    }

    private void initViews() {
        viewLaunch = findViewById(R.id.viewLaunch);
        viewPiWeb = findViewById(R.id.viewPiWeb);
        viewPlugins = findViewById(R.id.viewPlugins);
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
        btnLaunchFileManager = findViewById(R.id.btnLaunchFileManager);
        btnLaunchPetToggle = findViewById(R.id.btnLaunchPetToggle);
        launchPetStatusTv = findViewById(R.id.launchPetStatusTv);
        launchPetIconTv = findViewById(R.id.launchPetIconTv);
        launchPetStateBadge = findViewById(R.id.launchPetStateBadge);
        btnLaunchLogDetail = findViewById(R.id.btnLaunchLogDetail);
        btnLaunchLogCopy = findViewById(R.id.btnLaunchLogCopy);
        btnLaunchLogRefresh = findViewById(R.id.btnLaunchLogRefresh);
        btnLaunchLogClear = findViewById(R.id.btnLaunchLogClear);
        launchLogTv = findViewById(R.id.launchLogTv);

        launchPortInput = findViewById(R.id.launchPortInput);
        btnLaunchSavePort = findViewById(R.id.btnLaunchSavePort);

        settingsAppVersionTv = findViewById(R.id.settingsAppVersionTv);
        btnCheckAppUpdate = findViewById(R.id.btnCheckAppUpdate);
        settingsPiWebVersionTv = findViewById(R.id.settingsPiWebVersionTv);
        btnCheckPiWebUpdate = findViewById(R.id.btnCheckPiWebUpdate);

        menuItemPorts = findViewById(R.id.menuItemPorts);
        menuTitlePorts = findViewById(R.id.menuTitlePorts);
        menuArrowPorts = findViewById(R.id.menuArrowPorts);

        menuItemUpdate = findViewById(R.id.menuItemUpdate);
        menuTitleUpdate = findViewById(R.id.menuTitleUpdate);
        menuArrowUpdate = findViewById(R.id.menuArrowUpdate);

        menuItemPet = findViewById(R.id.menuItemPet);
        menuTitlePet = findViewById(R.id.menuTitlePet);
        menuArrowPet = findViewById(R.id.menuArrowPet);

        menuItemPrivileges = findViewById(R.id.menuItemPrivileges);
        menuTitlePrivileges = findViewById(R.id.menuTitlePrivileges);
        menuArrowPrivileges = findViewById(R.id.menuArrowPrivileges);

        menuItemStorage = findViewById(R.id.menuItemStorage);
        menuTitleStorage = findViewById(R.id.menuTitleStorage);
        menuArrowStorage = findViewById(R.id.menuArrowStorage);

        menuItemAbout = findViewById(R.id.menuItemAbout);
        menuTitleAbout = findViewById(R.id.menuTitleAbout);
        menuArrowAbout = findViewById(R.id.menuArrowAbout);

        cardSettingsPorts = findViewById(R.id.cardSettingsPorts);
        cardSettingsUpdate = findViewById(R.id.cardSettingsUpdate);
        cardSettingsRegistry = findViewById(R.id.cardSettingsRegistry);
        cardSettingsStorage = findViewById(R.id.cardSettingsStorage);
        cardSettingsPrivileges = findViewById(R.id.cardSettingsPrivileges);
        cardSettingsPetPreview = findViewById(R.id.cardSettingsPetPreview);
        cardSettingsPetProactive = findViewById(R.id.cardSettingsPetProactive);
        cardSettingsAbout = findViewById(R.id.cardSettingsAbout);

        viewSettingsSubWindow = findViewById(R.id.viewSettingsSubWindow);
        btnSettingsSubWindowBack = findViewById(R.id.btnSettingsSubWindowBack);
        tvSettingsSubWindowTitle = findViewById(R.id.tvSettingsSubWindowTitle);
        btnSettingsSubWindowClose = findViewById(R.id.btnSettingsSubWindowClose);
        btnSettingsExportBackup = findViewById(R.id.btnSettingsExportBackup);
        btnSettingsImportBackup = findViewById(R.id.btnSettingsImportBackup);

        if (btnSettingsSubWindowBack != null) btnSettingsSubWindowBack.setOnClickListener(v -> closeSettingsSubWindow());
        if (btnSettingsSubWindowClose != null) btnSettingsSubWindowClose.setOnClickListener(v -> closeSettingsSubWindow());

        if (menuItemPorts != null) menuItemPorts.setOnClickListener(v -> openSettingsSubWindow(SETTINGS_CAT_PORTS));
        if (menuItemUpdate != null) menuItemUpdate.setOnClickListener(v -> openSettingsSubWindow(SETTINGS_CAT_UPDATE));
        if (menuItemPet != null) menuItemPet.setOnClickListener(v -> openSettingsSubWindow(SETTINGS_CAT_PET));
        if (menuItemPrivileges != null) menuItemPrivileges.setOnClickListener(v -> openSettingsSubWindow(SETTINGS_CAT_PRIVILEGES));
        if (menuItemStorage != null) menuItemStorage.setOnClickListener(v -> openSettingsSubWindow(SETTINGS_CAT_STORAGE));
        if (menuItemAbout != null) menuItemAbout.setOnClickListener(v -> openSettingsSubWindow(SETTINGS_CAT_ABOUT));

        if (btnSettingsExportBackup != null) btnSettingsExportBackup.setOnClickListener(v -> {
            new com.xm486.pimet.ui.FileBrowserDialog(this).showBackupMigrationDialog();
        });
        if (btnSettingsImportBackup != null) btnSettingsImportBackup.setOnClickListener(v -> {
            new com.xm486.pimet.ui.FileBrowserDialog(this).showBackupMigrationDialog();
        });

        applySettingsCardsVisibility();

        // Pi-Web 组件
        piWebProgressBar = findViewById(R.id.piWebProgressBar);
        piWebWebView = findViewById(R.id.piWebWebView);
        piWebOfflineCard = findViewById(R.id.piWebOfflineCard);
        piWebOfflineSubTv = findViewById(R.id.piWebOfflineSubTv);
        piWebWakeBtn = findViewById(R.id.piWebWakeBtn);

        floatingMenuContainer = findViewById(R.id.floatingMenuContainer);
        floatingMenuVertical = findViewById(R.id.floatingMenuVertical);
        floatingBall = findViewById(R.id.floatingBall);
        floatingPetView = null;
        petBubbleLayout = null;
        petBubbleTv = null;
        btnFloatPetChat = null;
        btnFloatPetSwitch = null;
        btnFloatFullscreen = findViewById(R.id.btnFloatFullscreen);
        btnFloatReload = findViewById(R.id.btnFloatReload);
        btnFloatZoom = findViewById(R.id.btnFloatZoom);
        btnFloatImport = findViewById(R.id.btnFloatImport);
        btnFloatBrowser = findViewById(R.id.btnFloatBrowser);
        btnFloatClose = findViewById(R.id.btnFloatClose);

        // Settings 组件
        settingsPortInput = findViewById(R.id.settingsPortInput);
        settingsOperitPortInput = findViewById(R.id.settingsOperitPortInput);
        settingsOperitTokenInput = findViewById(R.id.settingsOperitTokenInput);
        btnTestOperitPort = findViewById(R.id.btnTestOperitPort);
        tvOperitPortStatus = findViewById(R.id.tvOperitPortStatus);

        settingsClawbenchPortInput = findViewById(R.id.settingsClawbenchPortInput);
        settingsClawbenchTokenInput = findViewById(R.id.settingsClawbenchTokenInput);
        btnTestClawbenchPort = findViewById(R.id.btnTestClawbenchPort);
        tvClawbenchPortStatus = findViewById(R.id.tvClawbenchPortStatus);

        settingsRikkaPortInput = findViewById(R.id.settingsRikkaPortInput);
        btnTestRikkaPort = findViewById(R.id.btnTestRikkaPort);
        tvRikkaPortStatus = findViewById(R.id.tvRikkaPortStatus);

        btnTestPiWebPort = findViewById(R.id.btnTestPiWebPort);
        tvPiWebPortStatus = findViewById(R.id.tvPiWebPortStatus);
        btnResetDefaultPorts = findViewById(R.id.btnResetDefaultPorts);

        btnPetChipPiWeb = findViewById(R.id.btnPetChipPiWeb);
        btnPetChipCustomPorts = findViewById(R.id.btnPetChipCustomPorts);
        if (btnPetChipPiWeb != null) {
            btnPetChipPiWeb.setOnClickListener(v -> switchTab(1));
        }
        if (btnPetChipCustomPorts != null) {
            btnPetChipCustomPorts.setOnClickListener(v -> showCustomPortsDialog());
        }

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
        btnFloatClipboard = findViewById(R.id.btnFloatClipboard);

        // 桌面宠物设置组件
        tvCurrentPetName = findViewById(R.id.tvCurrentPetName);
        btnTogglePetEnabled = findViewById(R.id.btnTogglePetEnabled);
        btnPetParams = findViewById(R.id.btnPetParams);
        btnPetShop = findViewById(R.id.btnPetShop);
        btnToggleGlobalOverlay = findViewById(R.id.btnToggleGlobalOverlay);

        btnToggleProactiveSettings = findViewById(R.id.btnToggleProactiveSettings);
        btnAdjustProactiveIntervalSettings = findViewById(R.id.btnAdjustProactiveIntervalSettings);
    }

    private void initNavigation() {
        tabLaunch.setOnClickListener(v -> switchTab(0));
        tabPiWeb.setOnClickListener(v -> switchTab(1));
        if (tabPlugins != null) tabPlugins.setOnClickListener(v -> switchTab(2));
        tabSettings.setOnClickListener(v -> switchTab(3));
    }

    public void switchTab(int index) {
        viewLaunch.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        viewPiWeb.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        if (viewPlugins != null) viewPlugins.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        viewSettings.setVisibility(index == 3 ? View.VISIBLE : View.GONE);
        if (viewSettingsSubWindow != null) viewSettingsSubWindow.setVisibility(View.GONE);

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
            refreshStorageSize();
        } else if (index == 1) {
            updatePiWebDisplay();
        } else if (index == 2) {
            // 插件与生态中心
            refreshPluginsList(currentPluginCategory);
        } else if (index == 3) {
            switchSettingsCategory(currentSettingsCategory);
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
    }

    public void closeTerminalInWorkbench() {
    }

    public void toggleTerminalInWorkbench() {
        switchTab(1);
    }

    public void showPetBubble(String msg) {
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

    public void updatePetDisplay(boolean isPetEnabled) {
        if (floatingMenuContainer == null) return;
        floatingMenuContainer.setVisibility(View.VISIBLE);
        if (floatingBall != null) {
            floatingBall.setVisibility(View.VISIBLE);
        }
        if (floatingPetView != null) {
            floatingPetView.setVisibility(View.GONE);
            floatingPetView.stopTicker();
        }
        if (floatingPetChatCard != null) {
            floatingPetChatCard.setVisibility(View.GONE);
        }
        if (tvCurrentPetName != null) {
            tvCurrentPetName.setText("当前角色: " + PetRegistry.getPetDir(this) + " (系统全局悬浮桌宠)");
        }
        if (btnTogglePetEnabled != null) {
            btnTogglePetEnabled.setText(PetOverlayService.isRunning() ? "🐾 桌宠: 运行中" : "⚪ 桌宠: 已关闭");
        }
        updateLaunchPetUI();
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

    public void togglePetChatCard(Boolean forceShow) {
        if (floatingPetChatCard == null) return;
        boolean willShow = forceShow != null ? forceShow : (floatingPetChatCard.getVisibility() != View.VISIBLE);
        if (willShow) {
            floatingPetChatCard.bringToFront();
            floatingPetChatCard.setVisibility(View.VISIBLE);
            floatingPetChatCard.setAlpha(0f);
            floatingPetChatCard.setScaleX(0.96f);
            floatingPetChatCard.setScaleY(0.96f);
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
                    .alpha(0f).scaleX(0.96f).scaleY(0.96f)
                    .setDuration(120)
                    .withEndAction(() -> floatingPetChatCard.setVisibility(View.GONE))
                    .start();
        }
    }

    private void updateFloatingPetChatCardStatus() {
        if (tvPetStatusText == null || viewPetStatusDot == null) return;
        String target = PetRegistry.getStringPref(this, PetRegistry.KEY_MONITOR_TARGET, PetRegistry.TARGET_PIWEB);
        int port = PetRegistry.TARGET_PIWEB.equals(target) ? PiMetConfig.getWebPort(this) :
                (PetRegistry.TARGET_OPERIT.equals(target) ? PetRegistry.getOperitPort(this) :
                (PetRegistry.TARGET_CLAWBENCH.equals(target) ? PetRegistry.getClawbenchPort(this) :
                (PetRegistry.TARGET_RIKKA.equals(target) ? PetRegistry.getRikkaPort(this) : 8080)));

        if (lastPetSnapshot != null && lastPetSnapshot.state != null) {
            switch (lastPetSnapshot.state) {
                case THINKING:
                    tvPetStatusText.setText("🤔 Pi-Web 思考中 (:" + port + ")");
                    viewPetStatusDot.setBackgroundResource(R.drawable.bg_status_dot_yellow);
                    break;
                case TOOL_RUNNING:
                    String tool = TextUtils.isEmpty(lastPetSnapshot.lastTool) ? "工具中" : lastPetSnapshot.lastTool;
                    tvPetStatusText.setText("🔧 执行: " + tool + " (:" + port + ")");
                    viewPetStatusDot.setBackgroundResource(R.drawable.bg_status_dot_yellow);
                    break;
                case RESPONDING:
                    tvPetStatusText.setText("💬 生成回复中 (:" + port + ")");
                    viewPetStatusDot.setBackgroundResource(R.drawable.bg_status_dot_yellow);
                    break;
                case ERROR:
                    tvPetStatusText.setText("😱 遇到异常 (:" + port + ")");
                    viewPetStatusDot.setBackgroundResource(R.drawable.bg_status_dot_gray);
                    break;
                case IDLE:
                default:
                    tvPetStatusText.setText("🟢 目标: " + target + ":" + port + " · 就绪");
                    viewPetStatusDot.setBackgroundResource(R.drawable.bg_status_dot_green);
                    break;
            }
        } else {
            tvPetStatusText.setText("🟢 目标: " + target + ":" + port + " · 就绪");
            viewPetStatusDot.setBackgroundResource(R.drawable.bg_status_dot_green);
        }
    }

    public void initPetMonitor() {
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

    public void showPetMenuDialog() {
        showPetHudMenu();
    }

    public void showPetHudMenu() {
        new com.xm486.pimet.pet.PetMenu(this).show();
    }

    private void showPetHudMenuLegacy() {
        if (petHudDialog != null && petHudDialog.isShowing()) {
            petHudDialog.dismiss();
        }

        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));

            // ---- 顶栏：标题 + ✕ 关闭 ----
            LinearLayout topBar = new LinearLayout(this);
            topBar.setOrientation(LinearLayout.HORIZONTAL);
            topBar.setGravity(Gravity.CENTER_VERTICAL);

            TextView title = new TextView(this);
            title.setText("🐾 桌宠控制中心");
            title.setTextColor(0xFFF0F6FC);
            title.setTextSize(12.5f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            topBar.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView closeBtn = new TextView(this);
            closeBtn.setText("✕");
            closeBtn.setTextColor(0xFF8B949E);
            closeBtn.setTextSize(13f);
            closeBtn.setPadding(dpToPx(6), dpToPx(2), dpToPx(4), dpToPx(2));
            closeBtn.setOnClickListener(v -> {
                if (petHudDialog != null) petHudDialog.dismiss();
            });
            topBar.addView(closeBtn);
            root.addView(topBar);

            // 分割线
            View div1 = new View(this);
            div1.setBackgroundColor(0x2230363D);
            LinearLayout.LayoutParams divLp1 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1));
            divLp1.topMargin = dpToPx(6);
            divLp1.bottomMargin = dpToPx(6);
            root.addView(div1, divLp1);

            // ---- 监控目标标签行 (Operit / pi-web / ClawBench / RikkaHub) 学习自 DevPetM 架构 ----
            LinearLayout targetGrid = new LinearLayout(this);
            targetGrid.setOrientation(LinearLayout.VERTICAL);

            String activeTarget = PetRegistry.getStringPref(this, PetRegistry.KEY_MONITOR_TARGET, PetRegistry.TARGET_PIWEB);

            LinearLayout targetRow1 = new LinearLayout(this);
            targetRow1.setOrientation(LinearLayout.HORIZONTAL);
            TextView chipPiWeb = createTargetChipHud("pi-web", PetRegistry.TARGET_PIWEB, activeTarget);
            TextView chipOperit = createTargetChipHud("Operit", PetRegistry.TARGET_OPERIT, activeTarget);
            targetRow1.addView(chipPiWeb, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            targetRow1.addView(createSpacingView(4));
            targetRow1.addView(chipOperit, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            targetGrid.addView(targetRow1);

            LinearLayout targetRow2 = new LinearLayout(this);
            targetRow2.setOrientation(LinearLayout.HORIZONTAL);
            targetRow2.setPadding(0, dpToPx(4), 0, 0);
            TextView chipClaw = createTargetChipHud("ClawBench", PetRegistry.TARGET_CLAWBENCH, activeTarget);
            TextView chipRikka = createTargetChipHud("RikkaHub", PetRegistry.TARGET_RIKKA, activeTarget);
            targetRow2.addView(chipClaw, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            targetRow2.addView(createSpacingView(4));
            targetRow2.addView(chipRikka, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            targetGrid.addView(targetRow2);

            root.addView(targetGrid);

            // 分割线
            View div2 = new View(this);
            div2.setBackgroundColor(0x2230363D);
            LinearLayout.LayoutParams divLp2 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1));
            divLp2.topMargin = dpToPx(8);
            divLp2.bottomMargin = dpToPx(6);
            root.addView(div2, divLp2);

            // ---- 角色形象切换器 ◀ 名字 ▶ ----
            LinearLayout petSwitchRow = new LinearLayout(this);
            petSwitchRow.setOrientation(LinearLayout.HORIZONTAL);
            petSwitchRow.setGravity(Gravity.CENTER_VERTICAL);

            List<PetRegistry.PetInfo> allPets = PetRegistry.loadPets(this);
            String curPetDir = PetRegistry.getPetDir(this);
            final int[] petIdx = new int[]{0};
            for (int i = 0; i < allPets.size(); i++) {
                if (allPets.get(i).dir.equals(curPetDir)) {
                    petIdx[0] = i;
                    break;
                }
            }

            TextView btnPrev = createHudChip("◀", 0x223B82F6, 0xFF58A6FF, null);
            TextView tvPetName = new TextView(this);
            tvPetName.setGravity(Gravity.CENTER);
            tvPetName.setTextColor(0xFFF0F6FC);
            tvPetName.setTextSize(11.5f);
            tvPetName.setTypeface(Typeface.DEFAULT_BOLD);
            tvPetName.setText((petIdx[0] + 1) + "/" + allPets.size() + " " + (allPets.isEmpty() ? curPetDir : allPets.get(petIdx[0]).displayName));

            TextView btnNext = createHudChip("▶", 0x223B82F6, 0xFF58A6FF, null);

            Runnable updatePetDisplay = () -> {
                if (allPets.isEmpty()) return;
                PetRegistry.PetInfo p = allPets.get(petIdx[0]);
                tvPetName.setText((petIdx[0] + 1) + "/" + allPets.size() + " " + p.displayName);
                PetRegistry.setPetDir(this, p.dir);
                if (floatingPetView != null) {
                    floatingPetView.setPetDir(p.dir);
                    floatingPetView.playOneShot("waving");
                }
                if (previewPetView != null) {
                    previewPetView.setPetDir(p.dir);
                }
                buildPetList();
                showPetBubble("已切换为角色: " + p.displayName + " ✨");
            };

            btnPrev.setOnClickListener(v -> {
                if (allPets.isEmpty()) return;
                petIdx[0] = (petIdx[0] - 1 + allPets.size()) % allPets.size();
                updatePetDisplay.run();
            });

            btnNext.setOnClickListener(v -> {
                if (allPets.isEmpty()) return;
                petIdx[0] = (petIdx[0] + 1) % allPets.size();
                updatePetDisplay.run();
            });

            tvPetName.setOnClickListener(v -> showPetSwitchDialog());

            petSwitchRow.addView(btnPrev, new LinearLayout.LayoutParams(dpToPx(32), dpToPx(28)));
            petSwitchRow.addView(tvPetName, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            petSwitchRow.addView(btnNext, new LinearLayout.LayoutParams(dpToPx(32), dpToPx(28)));
            root.addView(petSwitchRow);

            // ---- 才艺互动芯片 (跳舞 / 挥手 / 翻跟斗) ----
            LinearLayout actionChipsRow = new LinearLayout(this);
            actionChipsRow.setOrientation(LinearLayout.HORIZONTAL);
            actionChipsRow.setPadding(0, dpToPx(6), 0, 0);

            TextView btnDance = createHudChip("💃 跳舞", 0x228B5CF6, 0xFFC4B5FD, v -> {
                if (floatingPetView != null) {
                    floatingPetView.playOneShot("dancing");
                    showPetBubble("为主人们献上一支欢快的舞蹈~ 💃✨");
                }
            });
            TextView btnWave = createHudChip("👋 挥手", 0x223B82F6, 0xFF93C5FD, v -> {
                if (floatingPetView != null) {
                    floatingPetView.playOneShot("waving");
                    showPetBubble("主人辛苦啦！热情的招呼奉上~ ฅ'ω'ฅ");
                }
            });
            TextView btnJump = createHudChip("✨ 翻跟斗", 0x22F59E0B, 0xFFFDE68A, v -> {
                if (floatingPetView != null) {
                    floatingPetView.playOneShot("jumping");
                    showPetBubble("看我帅气的后空翻！咻咻咻~ 🌟");
                }
            });

            actionChipsRow.addView(btnDance, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            actionChipsRow.addView(createSpacingView(4));
            actionChipsRow.addView(btnWave, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            actionChipsRow.addView(createSpacingView(4));
            actionChipsRow.addView(btnJump, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            root.addView(actionChipsRow);

            // 分割线
            View div3 = new View(this);
            div3.setBackgroundColor(0x2230363D);
            LinearLayout.LayoutParams divLp3 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1));
            divLp3.topMargin = dpToPx(8);
            divLp3.bottomMargin = dpToPx(6);
            root.addView(div3, divLp3);

            // ---- 快捷工具栏按钮 ----
            LinearLayout toolsRow1 = new LinearLayout(this);
            toolsRow1.setOrientation(LinearLayout.HORIZONTAL);

            TextView btnParams = createHudChip("🎛️ 物理手感", 0x223B82F6, 0xFF58A6FF, v -> {
                if (petHudDialog != null) petHudDialog.dismiss();
                showPetParamsDialog();
            });

            TextView btnChat = createHudChip("💬 聊天卡片", 0x2210B981, 0xFF3FB950, v -> {
                if (petHudDialog != null) petHudDialog.dismiss();
                togglePetChatCard(true);
            });

            TextView btnWeb = createHudChip("🌐 工作台", 0x228B5CF6, 0xFFBC8CFF, v -> {
                if (petHudDialog != null) petHudDialog.dismiss();
                switchTab(1);
            });

            toolsRow1.addView(btnParams, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            toolsRow1.addView(createSpacingView(4));
            toolsRow1.addView(btnChat, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            toolsRow1.addView(createSpacingView(4));
            toolsRow1.addView(btnWeb, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            root.addView(toolsRow1);

            LinearLayout toolsRow2 = new LinearLayout(this);
            toolsRow2.setOrientation(LinearLayout.HORIZONTAL);
            toolsRow2.setPadding(0, dpToPx(4), 0, 0);

            TextView btnSettings = createHudChip("⚙️ 设置中心", 0x226E7681, 0xFFC9D1D9, v -> {
                if (petHudDialog != null) petHudDialog.dismiss();
                switchTab(3);
            });

            TextView btnPorts = createHudChip("🌐 自定义端口", 0x22F59E0B, 0xFFF59E0B, v -> {
                if (petHudDialog != null) petHudDialog.dismiss();
                showCustomPortsDialog();
            });

            boolean isOverlayRunning = PetOverlayService.isRunning();
            TextView btnOverlay = createHudChip(isOverlayRunning ? "🌐 全局桌宠·开" : "🌐 全局桌宠·关", isOverlayRunning ? 0x2210B981 : 0x226E7681, isOverlayRunning ? 0xFF3FB950 : 0xFF8B949E, v -> {
                toggleGlobalOverlay();
                boolean nowRunning = PetOverlayService.isRunning();
                ((TextView) v).setText(nowRunning ? "🌐 全局桌宠·开" : "🌐 全局桌宠·关");
            });

            toolsRow2.addView(btnSettings, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            toolsRow2.addView(createSpacingView(4));
            toolsRow2.addView(btnPorts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            toolsRow2.addView(createSpacingView(4));
            toolsRow2.addView(btnOverlay, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            root.addView(toolsRow2);

            builder.setView(root);
            petHudDialog = builder.create();

            Window window = petHudDialog.getWindow();
            if (window != null) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xF4181A22);
                bg.setCornerRadius(dpToPx(13));
                bg.setStroke(dpToPx(1), 0x33475569);
                window.setBackgroundDrawable(bg);
            }

            petHudDialog.show();

            // 定位：吸附在桌宠旁
            if (window != null && floatingMenuContainer != null) {
                WindowManager.LayoutParams attrs = window.getAttributes();
                if (attrs != null) {
                    attrs.gravity = Gravity.TOP | Gravity.START;
                    int winW = dpToPx(230);
                    attrs.width = winW;
                    attrs.height = WindowManager.LayoutParams.WRAP_CONTENT;

                    int[] loc = new int[2];
                    floatingMenuContainer.getLocationOnScreen(loc);
                    int screenW = getResources().getDisplayMetrics().widthPixels;
                    int petW = floatingMenuContainer.getWidth();
                    if (petW <= 0) petW = dpToPx(72);

                    if (loc[0] + petW / 2 > screenW / 2) {
                        attrs.x = Math.max(dpToPx(8), loc[0] - winW - dpToPx(6));
                    } else {
                        attrs.x = Math.min(screenW - winW - dpToPx(8), loc[0] + petW + dpToPx(6));
                    }
                    attrs.y = Math.max(dpToPx(36), loc[1]);
                    window.setAttributes(attrs);
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("PiMet.PetHud", "show hud menu failed", t);
        }
    }

    private TextView createTargetChipHud(String displayName, String targetKey, String activeTarget) {
        boolean isActive = targetKey.equals(activeTarget);
        TextView chip = new TextView(this);
        chip.setText("⏳ " + displayName + "...");
        chip.setTextSize(10.5f);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dpToPx(6), dpToPx(5), dpToPx(6), dpToPx(5));
        applyHudChipStyle(chip, isActive, false);

        // 毫秒级异步端口探测
        PortDetector.checkAsync(this, targetKey, status -> mainHandler.post(() -> {
            String label = (status.alive ? "● " : "○ ") + displayName + (status.alive ? ":" + status.port : " (未启)");
            chip.setText(label);
            applyHudChipStyle(chip, targetKey.equals(PetRegistry.getStringPref(MainActivity.this, PetRegistry.KEY_MONITOR_TARGET, PetRegistry.TARGET_PIWEB)), status.alive);
        }));

        // 点击切换监控与对话目标
        chip.setOnClickListener(v -> {
            PetRegistry.setStringPref(MainActivity.this, PetRegistry.KEY_MONITOR_TARGET, targetKey);
            try {
                ChatConfig cfg = ChatConfig.load(MainActivity.this);
                if (PetRegistry.TARGET_PIWEB.equals(targetKey)) cfg.mode = ChatConfig.MODE_PIWEB;
                else if (PetRegistry.TARGET_OPERIT.equals(targetKey)) cfg.mode = ChatConfig.MODE_OPERIT;
                else if (PetRegistry.TARGET_CLAWBENCH.equals(targetKey)) cfg.mode = ChatConfig.MODE_CLAWBENCH;
                cfg.save(MainActivity.this);
            } catch (Throwable ignored) {}

            PortDetector.checkAsync(MainActivity.this, targetKey, status -> mainHandler.post(() -> {
                if (status.alive) {
                    showPetBubble("🎯 已切换目标: " + displayName + "\n127.0.0.1:" + status.port + " · 延迟 " + status.latencyMs + "ms");
                } else {
                    showPetBubble("🎯 已切换目标: " + displayName + "\n⚠️ 端口 " + status.port + " 未响应或服务未启动");
                }
            }));
            if (petHudDialog != null) petHudDialog.dismiss();
        });

        // 长按直达控制台/Web界面
        chip.setOnLongClickListener(v -> {
            if (petHudDialog != null) petHudDialog.dismiss();
            if (PetRegistry.TARGET_PIWEB.equals(targetKey)) {
                switchTab(1);
            } else if (PetRegistry.TARGET_OPERIT.equals(targetKey)) {
                openBrowserUrl("http://127.0.0.1:" + PetRegistry.getOperitPort(MainActivity.this));
            } else if (PetRegistry.TARGET_CLAWBENCH.equals(targetKey)) {
                openBrowserUrl("http://127.0.0.1:" + PetRegistry.getClawbenchPort(MainActivity.this));
            } else if (PetRegistry.TARGET_RIKKA.equals(targetKey)) {
                openBrowserUrl("http://127.0.0.1:" + PetRegistry.getIntPref(MainActivity.this, PetRegistry.KEY_RK_PORT, 8095));
            }
            return true;
        });

        return chip;
    }

    private void applyHudChipStyle(TextView chip, boolean isActive, boolean isAlive) {
        int bg = isActive ? (isAlive ? 0x3310B981 : 0x333B82F6) : 0x1A21262D;
        int textClr = isActive ? (isAlive ? 0xFF3FB950 : 0xFF58A6FF) : (isAlive ? 0xFFC9D1D9 : 0xFF8B949E);
        chip.setTextColor(textClr);

        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(dpToPx(6));
        d.setStroke(dpToPx(1), isActive ? (isAlive ? 0x883FB950 : 0x8858A6FF) : 0x22FFFFFF);
        chip.setBackground(d);
    }

    private void openBrowserUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(this, "未能调起浏览器: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private TextView createHudChip(String text, int bgColor, int textColor, View.OnClickListener clk) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(textColor);
        tv.setTextSize(11f);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dpToPx(6), dpToPx(5), dpToPx(6), dpToPx(5));

        GradientDrawable n = new GradientDrawable();
        n.setColor(bgColor);
        n.setCornerRadius(dpToPx(6));
        n.setStroke(dpToPx(1), 0x22FFFFFF);
        tv.setBackground(n);

        if (clk != null) {
            tv.setOnClickListener(clk);
        }
        return tv;
    }

    private View createSpacingView(int dpVal) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(dpVal), 1));
        return v;
    }

    // ---------------- 桌宠原生物理手感引擎 (惯性飞行/边界弹性反弹/空气摩擦) ----------------

    private void stopInAppFling() {
        if (inAppFlingAnimator != null) {
            inAppFlingAnimator.cancel();
            inAppFlingAnimator = null;
        }
    }

    private void startInAppFling(float vx0, float vy0) {
        stopInAppFling();
        int bouncePct = PetRegistry.getIntPref(this, PetRegistry.KEY_BOUNCE, PetRegistry.DEFAULT_BOUNCE);
        int frictionPct = PetRegistry.getIntPref(this, PetRegistry.KEY_FRICTION, PetRegistry.DEFAULT_FRICTION);
        int stopSpeed = PetRegistry.getIntPref(this, PetRegistry.KEY_STOP_SPEED, PetRegistry.DEFAULT_STOP_SPEED);
        int duration = PetRegistry.getIntPref(this, PetRegistry.KEY_FLING_DURATION, PetRegistry.DEFAULT_FLING_DURATION);

        float bounce = Math.max(0f, Math.min(1f, bouncePct / 100f));
        float frictionPerSec = Math.max(0f, Math.min(1f, frictionPct / 100f));

        final float[] v = new float[]{vx0, vy0};
        inAppLastFlingFrame = System.currentTimeMillis();

        inAppFlingAnimator = ValueAnimator.ofFloat(0f, 1f);
        inAppFlingAnimator.setDuration(Math.max(500, duration));
        inAppFlingAnimator.setInterpolator(null);
        inAppFlingAnimator.addUpdateListener(anim -> {
            long now = System.currentTimeMillis();
            float dt = (now - inAppLastFlingFrame) / 1000f;
            inAppLastFlingFrame = now;
            if (dt > 0.1f) dt = 0.1f;
            if (dt <= 0f) return;

            if (floatingMenuContainer == null) {
                stopInAppFling();
                return;
            }
            View parent = (View) floatingMenuContainer.getParent();
            if (parent == null) {
                stopInAppFling();
                return;
            }

            // 根据飞行速度调整面向和动作
            if (floatingPetView != null) {
                if (v[0] > 15) {
                    floatingPetView.setMoveDirection(1);
                } else if (v[0] < -15) {
                    floatingPetView.setMoveDirection(-1);
                }
            }

            float currentX = floatingMenuContainer.getLeft() + floatingMenuContainer.getTranslationX();
            float currentY = floatingMenuContainer.getTop() + floatingMenuContainer.getTranslationY();

            float px = currentX + v[0] * dt;
            float py = currentY + v[1] * dt;

            float density = getResources().getDisplayMetrics().density;
            float minMarginTop = 32 * density;
            float minMarginBottom = 16 * density;
            float minMarginSide = 8 * density;

            float minX = minMarginSide;
            float maxX = Math.max(minX, parent.getWidth() - floatingMenuContainer.getWidth() - minMarginSide);
            float minY = minMarginTop;
            float maxY = Math.max(minY, parent.getHeight() - floatingMenuContainer.getHeight() - minMarginBottom);

            // 碰到边界物理弹性反弹
            if (px < minX) {
                px = minX;
                v[0] = -v[0] * bounce;
            } else if (px > maxX) {
                px = maxX;
                v[0] = -v[0] * bounce;
            }

            if (py < minY) {
                py = minY;
                v[1] = -v[1] * bounce;
            } else if (py > maxY) {
                py = maxY;
                v[1] = -v[1] * bounce;
            }

            floatingMenuContainer.setTranslationX(px - floatingMenuContainer.getLeft());
            floatingMenuContainer.setTranslationY(py - floatingMenuContainer.getTop());

            // 空气摩擦衰减
            float friction = 1f - frictionPerSec * dt;
            v[0] *= friction;
            v[1] *= friction;

            // 停稳判定
            if (Math.hypot(v[0], v[1]) < stopSpeed) {
                stopInAppFling();
                if (floatingPetView != null) {
                    floatingPetView.setMoveDirection(0);
                }
            }
        });
        inAppFlingAnimator.start();
    }

    public void showPetParamsDialog() {
        new PetParamsDialog(this, this::applyPetParams).show();
    }

    private String readStreamToString(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toString("UTF-8");
    }

    private void testPortInput(EditText et, TextView tvStatus) {
        if (et == null || tvStatus == null) return;
        try {
            int port = Integer.parseInt(et.getText().toString().trim());
            tvStatus.setVisibility(View.VISIBLE);
            tvStatus.setText("🔍 正在探测端口 " + port + "...");
            tvStatus.setTextColor(0xFF8B949E);
            new Thread(() -> {
                long t0 = System.currentTimeMillis();
                boolean ok = PortDetector.isPortOpen("127.0.0.1", port, 400);
                int latency = (int) (System.currentTimeMillis() - t0);
                boolean isHttp = ok && ProotManager.isPiWebHttpReady(port);
                mainHandler.post(() -> {
                    if (isHttp) {
                        tvStatus.setText("● 端口 " + port + " HTTP 服务正在运行 (延迟 " + latency + "ms)");
                        tvStatus.setTextColor(0xFF3FB950);
                    } else if (ok) {
                        tvStatus.setText("● 端口 " + port + " 开放连通中 (延迟 " + latency + "ms)");
                        tvStatus.setTextColor(0xFF58A6FF);
                    } else {
                        tvStatus.setText("○ 端口 " + port + " 端口空闲 / 尚未启动监听");
                        tvStatus.setTextColor(0xFF8B949E);
                    }
                });
            }).start();
        } catch (Throwable t) {
            Toast.makeText(this, "请输入合法的端口数字", Toast.LENGTH_SHORT).show();
        }
    }

    private void showCustomPortsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("🌐 自定义多服务端口配置");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(18), dpToPx(12), dpToPx(18), dpToPx(12));

        EditText etPiWeb = createPortDialogRow(layout, "Pi-Web 工作台端口:", String.valueOf(PiMetConfig.getWebPort(this)));
        EditText etOperit = createPortDialogRow(layout, "Operit 伴侣服务端口:", String.valueOf(PetRegistry.getOperitPort(this)));
        EditText etClaw = createPortDialogRow(layout, "ClawBench 服务端口:", String.valueOf(PetRegistry.getClawbenchPort(this)));
        EditText etRikka = createPortDialogRow(layout, "RikkaHub 分发端口:", String.valueOf(PetRegistry.getRikkaPort(this)));

        builder.setView(layout);
        builder.setPositiveButton("保存并生效", (dialog, which) -> {
            try {
                int pw = Integer.parseInt(etPiWeb.getText().toString().trim());
                int op = Integer.parseInt(etOperit.getText().toString().trim());
                int cb = Integer.parseInt(etClaw.getText().toString().trim());
                int rk = Integer.parseInt(etRikka.getText().toString().trim());

                if (pw < 1024 || pw > 65535 || op < 1024 || op > 65535 || cb < 1024 || cb > 65535 || rk < 1024 || rk > 65535) {
                    Toast.makeText(this, "端口需在 1024 ~ 65535 范围内", Toast.LENGTH_SHORT).show();
                    return;
                }

                PetRegistry.setPiWebPort(this, pw);
                PetRegistry.setOperitPort(this, op);
                PetRegistry.setClawbenchPort(this, cb);
                PetRegistry.setRikkaPort(this, rk);

                initPetMonitor();
                checkServiceStatus();
                refreshSettingsPortFields();
                Toast.makeText(this, "多服务自定义端口已全部保存并生效！", Toast.LENGTH_SHORT).show();
                showPetBubble("✅ 多服务端口已更新！Pi-Web:" + pw + " / Operit:" + op);
            } catch (Throwable t) {
                Toast.makeText(this, "端口保存异常: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private EditText createPortDialogRow(LinearLayout parent, String label, String currentVal) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xFFCBD5E1);
        tv.setTextSize(12f);
        tv.setPadding(0, dpToPx(6), 0, dpToPx(2));
        parent.addView(tv);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        EditText et = new EditText(this);
        et.setLayoutParams(new LinearLayout.LayoutParams(0, dpToPx(38), 1f));
        et.setText(currentVal);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setTextColor(0xFF58A6FF);
        et.setTextSize(13f);
        et.setTypeface(Typeface.MONOSPACE);
        et.setPadding(dpToPx(8), 0, dpToPx(8), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF0D1117);
        bg.setCornerRadius(dpToPx(4));
        bg.setStroke(dpToPx(1), 0x33475569);
        et.setBackground(bg);
        row.addView(et);

        TextView btnTest = new TextView(this);
        btnTest.setText("测试");
        btnTest.setTextColor(0xFFC9D1D9);
        btnTest.setTextSize(11f);
        btnTest.setGravity(Gravity.CENTER);
        btnTest.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6));
        GradientDrawable tbg = new GradientDrawable();
        tbg.setColor(0x2230363D);
        tbg.setCornerRadius(dpToPx(4));
        tbg.setStroke(dpToPx(1), 0x22FFFFFF);
        btnTest.setBackground(tbg);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dpToPx(36));
        btnLp.setMarginStart(dpToPx(8));
        row.addView(btnTest, btnLp);

        TextView tvStatus = new TextView(this);
        tvStatus.setTextSize(10.5f);
        tvStatus.setTextColor(0xFF8B949E);
        tvStatus.setVisibility(View.GONE);
        tvStatus.setPadding(dpToPx(2), dpToPx(2), 0, 0);

        btnTest.setOnClickListener(v -> {
            try {
                int p = Integer.parseInt(et.getText().toString().trim());
                btnTest.setText("检测中");
                new Thread(() -> {
                    long t0 = System.currentTimeMillis();
                    boolean ok = PortDetector.isPortOpen("127.0.0.1", p, 400);
                    int latency = (int) (System.currentTimeMillis() - t0);
                    mainHandler.post(() -> {
                        btnTest.setText("测试");
                        tvStatus.setVisibility(View.VISIBLE);
                        if (ok) {
                            tvStatus.setText("● 端口 " + p + " 开放 (延迟 " + latency + "ms)");
                            tvStatus.setTextColor(0xFF3FB950);
                        } else {
                            tvStatus.setText("○ 端口 " + p + " 未响应或服务未启动");
                            tvStatus.setTextColor(0xFFF85149);
                        }
                    });
                }).start();
            } catch (Throwable ignored) {
                Toast.makeText(this, "请输入合法的端口数字", Toast.LENGTH_SHORT).show();
            }
        });

        parent.addView(row);
        parent.addView(tvStatus);
        return et;
    }

    public void applyPetParams() {
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
            mainHandler.postDelayed(() -> updatePetDisplay(PetRegistry.isPetEnabled(this)), 250);
        } else {
            // 启动系统全局桌宠前，立即隐匿应用内桌宠，消除双宠并存冲突
            if (floatingMenuContainer != null) {
                floatingMenuContainer.setVisibility(View.GONE);
            }
            stopInAppFling();
            if (floatingPetView != null) {
                floatingPetView.stopTicker();
            }
            if (floatingPetChatCard != null) {
                floatingPetChatCard.setVisibility(View.GONE);
            }
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
        if (TextUtils.isEmpty(question)) return;
        String q = question.trim();

        if (floatingPetView != null) {
            floatingPetView.playOneShot("waving");
        }

        // 0. 自然语言宿主桥接 (控制桌宠大小/角色/动作/全局开关/切换Tab/配置端口等)
        String nlFeedback = com.xm486.pimet.bridge.AppBridgeManager.getInstance(this).tryHandleNaturalLanguage(q);
        if (nlFeedback != null) {
            showPetBubble(nlFeedback);
            if (tvPetCardStream != null) {
                tvPetCardStream.setVisibility(View.VISIBLE);
                tvPetCardStream.setText(nlFeedback);
            }
            return;
        }

        // 1. #test 端口连通性全面诊断
        if (q.startsWith("#test")) {
            showPetBubble("正在探测当前服务连通性与端口状态...");
            new Thread(() -> {
                String target = PetRegistry.getStringPref(MainActivity.this, PetRegistry.KEY_MONITOR_TARGET, PetRegistry.TARGET_PIWEB);
                PortDetector.TargetStatus status = PortDetector.check(MainActivity.this, target);
                mainHandler.post(() -> {
                    if (status.alive) {
                        showPetBubble("✅ [" + status.displayName + " 连通正常]\n127.0.0.1:" + status.port + " · " + status.message);
                    } else {
                        showPetBubble("⚠️ [" + status.displayName + " 端口无响应]\n127.0.0.1:" + status.port + " 未启动或被占用，请检查服务！");
                    }
                });
            }).start();
            return;
        }

        // 2. 快捷指令切换目标与模式
        if (q.equalsIgnoreCase("#piweb")) {
            PetRegistry.setStringPref(this, PetRegistry.KEY_MONITOR_TARGET, PetRegistry.TARGET_PIWEB);
            try {
                ChatConfig cfg = ChatConfig.load(this);
                cfg.mode = ChatConfig.MODE_PIWEB;
                cfg.save(this);
            } catch (Throwable ignored) {}
            showPetBubble("已切换至 pi-web 工作台对话模式 🚀");
            return;
        }
        if (q.equalsIgnoreCase("#operit")) {
            PetRegistry.setStringPref(this, PetRegistry.KEY_MONITOR_TARGET, PetRegistry.TARGET_OPERIT);
            try {
                ChatConfig cfg = ChatConfig.load(this);
                cfg.mode = ChatConfig.MODE_OPERIT;
                cfg.save(this);
            } catch (Throwable ignored) {}
            showPetBubble("已切换至 Operit 伴侣对话模式 ฅ'ω'ฅ");
            return;
        }
        if (q.equalsIgnoreCase("#clawbench")) {
            PetRegistry.setStringPref(this, PetRegistry.KEY_MONITOR_TARGET, PetRegistry.TARGET_CLAWBENCH);
            try {
                ChatConfig cfg = ChatConfig.load(this);
                cfg.mode = ChatConfig.MODE_CLAWBENCH;
                cfg.save(this);
            } catch (Throwable ignored) {}
            showPetBubble("已切换至 ClawBench 评测对话模式 ✨");
            return;
        }
        if (q.equalsIgnoreCase("#api")) {
            PetRegistry.setStringPref(this, PetRegistry.KEY_MONITOR_TARGET, PetRegistry.TARGET_CUSTOM_API);
            try {
                ChatConfig cfg = ChatConfig.load(this);
                cfg.mode = ChatConfig.MODE_CUSTOM_API;
                cfg.save(this);
            } catch (Throwable ignored) {}
            showPetBubble("已切换至 自定义大模型 API 模式 🤖");
            return;
        }
        if (q.startsWith("#model")) {
            String m = q.length() > 6 ? q.substring(6).trim() : "";
            if (!m.isEmpty()) {
                PiMetConfig.setAiModel(this, m);
                PiMetConfig.syncToContainer(this);
                showPetBubble("模型已更新为: " + m);
            } else {
                showPetBubble("当前挂载模型: " + PiMetConfig.getAiModel(this) + " (" + PiMetConfig.getAiProvider(this) + ")");
            }
            return;
        }
        if (q.startsWith("#url")) {
            String u = q.length() > 4 ? q.substring(4).trim() : "";
            if (!u.isEmpty()) {
                PiMetConfig.setAiBaseUrl(this, u);
                PiMetConfig.syncToContainer(this);
                showPetBubble("API 地址已更新: " + u);
            }
            return;
        }
        if (q.startsWith("#key")) {
            String k = q.length() > 4 ? q.substring(4).trim() : "";
            if (!k.isEmpty()) {
                PiMetConfig.setAiApiKey(this, k);
                PiMetConfig.syncToContainer(this);
                showPetBubble("API Key 已安全保存并同步至容器！");
            }
            return;
        }

        if (q.equalsIgnoreCase("#ports") || q.equalsIgnoreCase("#port")) {
            new Thread(() -> {
                int pw = PiMetConfig.getWebPort(MainActivity.this);
                int op = PetRegistry.getOperitPort(MainActivity.this);
                int cb = PetRegistry.getClawbenchPort(MainActivity.this);
                int rk = PetRegistry.getRikkaPort(MainActivity.this);

                boolean pwOk = PortDetector.isPortOpen("127.0.0.1", pw, 350);
                boolean opOk = PortDetector.isPortOpen("127.0.0.1", op, 350);
                boolean cbOk = PortDetector.isPortOpen("127.0.0.1", cb, 350);
                boolean rkOk = PortDetector.isPortOpen("127.0.0.1", rk, 350);

                String msg = "🌐 自定义多服务端口概览：\n"
                        + (pwOk ? "🟢" : "⚪") + " Pi-Web: " + pw + (pwOk ? " (运行中)" : " (未运行)") + "\n"
                        + (opOk ? "🟢" : "⚪") + " Operit: " + op + (opOk ? " (运行中)" : " (未运行)") + "\n"
                        + (cbOk ? "🟢" : "⚪") + " ClawBench: " + cb + (cbOk ? " (运行中)" : " (未运行)") + "\n"
                        + (rkOk ? "🟢" : "⚪") + " RikkaHub: " + rk + (rkOk ? " (运行中)" : " (未运行)") + "\n"
                        + "提示: 可用 #port <服务> <端口> 快捷修改";
                mainHandler.post(() -> showPetBubble(msg));
            }).start();
            return;
        }

        if (q.startsWith("#port ")) {
            String[] parts = q.split("\\s+");
            if (parts.length >= 3) {
                String svc = parts[1].toLowerCase();
                try {
                    int newPort = Integer.parseInt(parts[2]);
                    if (newPort < 1024 || newPort > 65535) {
                        showPetBubble("端口范围应在 1024 ~ 65535 之间哦！");
                        return;
                    }
                    if (svc.contains("pi")) {
                        PetRegistry.setPiWebPort(this, newPort);
                        initPetMonitor();
                        refreshSettingsPortFields();
                        showPetBubble("✅ Pi-Web 端口已更新为 " + newPort);
                    } else if (svc.contains("oper")) {
                        PetRegistry.setOperitPort(this, newPort);
                        refreshSettingsPortFields();
                        showPetBubble("✅ Operit 端口已更新为 " + newPort);
                    } else if (svc.contains("claw")) {
                        PetRegistry.setClawbenchPort(this, newPort);
                        refreshSettingsPortFields();
                        showPetBubble("✅ ClawBench 端口已更新为 " + newPort);
                    } else if (svc.contains("rikka")) {
                        PetRegistry.setRikkaPort(this, newPort);
                        refreshSettingsPortFields();
                        showPetBubble("✅ RikkaHub 端口已更新为 " + newPort);
                    } else {
                        showPetBubble("未知服务名，可选: piweb / operit / claw / rikka");
                    }
                    return;
                } catch (NumberFormatException e) {
                    showPetBubble("请输入合法的数字端口号！例如 #port piweb 30141");
                    return;
                }
            }
        }

        showPetBubble("收到啦！正在思考回答中...");

        String activeTarget = PetRegistry.getStringPref(this, PetRegistry.KEY_MONITOR_TARGET, PetRegistry.TARGET_PIWEB);

        new Thread(() -> {
            try {
                if (PetRegistry.TARGET_PIWEB.equals(activeTarget)) {
                    int piwebPort = PiMetConfig.getWebPort(MainActivity.this);
                    if (!PortDetector.isPortOpen("127.0.0.1", piwebPort, 450)) {
                        mainHandler.post(() -> showPetBubble("⚠️ Pi-Web 未在端口 " + piwebPort + " 启动，请点击上方「🚀 直达工作台」启动服务~"));
                        return;
                    }

                    // 1. 查找或创建会话
                    String sessionId = null;
                    try {
                        URL sUrl = new URL("http://127.0.0.1:" + piwebPort + "/api/sessions");
                        HttpURLConnection sConn = (HttpURLConnection) sUrl.openConnection();
                        sConn.setConnectTimeout(2500);
                        sConn.setReadTimeout(3000);
                        if (sConn.getResponseCode() == 200) {
                            String body = readStreamToString(sConn.getInputStream());
                            JSONObject sRoot = new JSONObject(body);
                            JSONArray sArr = sRoot.optJSONArray("sessions");
                            if (sArr != null && sArr.length() > 0) {
                                sessionId = sArr.getJSONObject(0).optString("id", null);
                            }
                        }
                        sConn.disconnect();
                    } catch (Throwable ignored) {}

                    if (sessionId == null) {
                        try {
                            URL nUrl = new URL("http://127.0.0.1:" + piwebPort + "/api/agent/new");
                            HttpURLConnection nConn = (HttpURLConnection) nUrl.openConnection();
                            nConn.setRequestMethod("POST");
                            nConn.setRequestProperty("Content-Type", "application/json");
                            nConn.setDoOutput(true);
                            nConn.setConnectTimeout(3000);
                            nConn.setReadTimeout(5000);
                            JSONObject nReq = new JSONObject();
                            nReq.put("cwd", "/root");
                            nReq.put("type", "prompt");
                            nReq.put("message", q);
                            nConn.getOutputStream().write(nReq.toString().getBytes(StandardCharsets.UTF_8));
                            if (nConn.getResponseCode() >= 200 && nConn.getResponseCode() < 300) {
                                String nBody = readStreamToString(nConn.getInputStream());
                                JSONObject nObj = new JSONObject(nBody);
                                sessionId = nObj.optString("sessionId", null);
                            }
                            nConn.disconnect();
                        } catch (Throwable ignored) {}
                    } else {
                        try {
                            URL pUrl = new URL("http://127.0.0.1:" + piwebPort + "/api/agent/" + sessionId);
                            HttpURLConnection pConn = (HttpURLConnection) pUrl.openConnection();
                            pConn.setRequestMethod("POST");
                            pConn.setRequestProperty("Content-Type", "application/json");
                            pConn.setDoOutput(true);
                            pConn.setConnectTimeout(3000);
                            pConn.setReadTimeout(5000);
                            JSONObject pReq = new JSONObject();
                            pReq.put("type", "prompt");
                            pReq.put("message", q);
                            pConn.getOutputStream().write(pReq.toString().getBytes(StandardCharsets.UTF_8));
                            pConn.getResponseCode();
                            pConn.disconnect();
                        } catch (Throwable ignored) {}
                    }

                    if (sessionId == null) {
                        mainHandler.post(() -> showPetBubble("未能与 Pi-Web 建立 Agent 会话"));
                        return;
                    }

                    mainHandler.post(() -> showPetBubble("💭 Pi-Web Agent 正在思考中..."));

                    // 2. 监听 SSE 事件流实时更新桌宠动画与气泡
                    URL sseUrl = new URL("http://127.0.0.1:" + piwebPort + "/api/agent/" + sessionId + "/events");
                    HttpURLConnection sseConn = (HttpURLConnection) sseUrl.openConnection();
                    sseConn.setRequestProperty("Accept", "text/event-stream");
                    sseConn.setConnectTimeout(4000);
                    sseConn.setReadTimeout(75000);

                    StringBuilder answer = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(sseConn.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        long t0 = System.currentTimeMillis();
                        while ((line = reader.readLine()) != null) {
                            if (System.currentTimeMillis() - t0 > 75000) break;
                            if (line.startsWith("data:")) {
                                String data = line.substring(5).trim();
                                if (data.isEmpty()) continue;
                                try {
                                    JSONObject ev = new JSONObject(data);
                                    String evType = ev.optString("type", "");
                                    if ("message_update".equals(evType)) {
                                        JSONObject aEv = ev.optJSONObject("assistantMessageEvent");
                                        if (aEv != null) {
                                            String aType = aEv.optString("type", "");
                                            if ("text_delta".equals(aType)) {
                                                String delta = aEv.optString("delta", "");
                                                answer.append(delta);
                                                final String cur = answer.toString();
                                                mainHandler.post(() -> showPetBubble(cur));
                                            } else if ("thinking_delta".equals(aType)) {
                                                mainHandler.post(() -> {
                                                    if (floatingPetView != null) floatingPetView.updateState(OperitState.THINKING);
                                                    showPetBubble("💭 Pi-Web 正在深度分析...");
                                                });
                                            }
                                        }
                                    } else if ("tool_execution_update".equals(evType)) {
                                        String toolName = ev.optString("toolName", "工具");
                                        mainHandler.post(() -> {
                                            if (floatingPetView != null) floatingPetView.updateState(OperitState.TOOL_RUNNING);
                                            showPetBubble("🛠️ Pi-Web 正在执行: " + toolName);
                                        });
                                    } else if ("agent_end".equals(evType) || "session_shutdown".equals(evType)) {
                                        break;
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }
                    } finally {
                        sseConn.disconnect();
                    }

                    mainHandler.post(() -> {
                        if (floatingPetView != null) floatingPetView.playOneShot("jumping");
                        if (answer.length() > 0) {
                            showPetBubble(answer.toString().trim());
                        } else {
                            showPetBubble("🎉 Pi-Web Agent 已执行完毕！随时待命");
                        }
                    });
                    return;
                }
                if (PetRegistry.TARGET_OPERIT.equals(activeTarget)) {
                    int operitPort = PetRegistry.getOperitPort(MainActivity.this);
                    java.net.URL url = new java.net.URL("http://127.0.0.1:" + operitPort + "/api/external-chat");
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(20000);
                    conn.setDoOutput(true);

                    JSONObject body = new JSONObject();
                    body.put("message", q);
                    body.put("token", PetRegistry.getStringPref(MainActivity.this, ChatConfig.KEY_OPERIT_TOKEN, "465ea3984db74e0790e8df63c6e85643"));

                    try (java.io.OutputStream os = conn.getOutputStream()) {
                        os.write(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    int code = conn.getResponseCode();
                    if (code == 200) {
                        try (java.io.InputStream is = conn.getInputStream();
                             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
                            byte[] buf = new byte[2048];
                            int n;
                            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
                            String res = bos.toString("UTF-8");
                            JSONObject obj = new JSONObject(res);
                            String reply = obj.optString("reply", obj.optString("text", ""));
                            if (TextUtils.isEmpty(reply)) reply = res;
                            final String finalReply = reply;
                            mainHandler.post(() -> {
                                if (floatingPetView != null) floatingPetView.playOneShot("jumping");
                                showPetBubble(finalReply);
                            });
                        }
                    } else {
                        mainHandler.post(() -> showPetBubble("Operit 未响应 (HTTP " + code + ")，请检查服务是否开启"));
                    }
                    return;
                }

                String apiKey = PiMetConfig.getAiApiKey(MainActivity.this);
                String baseUrl = PiMetConfig.getAiBaseUrl(MainActivity.this);
                String model = PiMetConfig.getAiModel(MainActivity.this);

                if (TextUtils.isEmpty(apiKey) && !baseUrl.contains("127.0.0.1")) {
                    mainHandler.post(() -> showPetBubble("主人还没配置 AI Key 哦~ 可以去设置面板填入！"));
                    return;
                }

                String urlStr = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";
                java.net.URL url = new java.net.URL(urlStr);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                if (!TextUtils.isEmpty(apiKey)) {
                    conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                }
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(20000);
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
                userMsg.put("content", q);
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
                    mainHandler.post(() -> showPetBubble("唔……请求返回了: HTTP " + respCode));
                }
            } catch (Throwable t) {
                mainHandler.post(() -> showPetBubble("思考出错了: " + t.getMessage()));
            }
        }).start();
    }

    private void initLaunchPanel() {
        int port = PiMetConfig.getWebPort(this);
        if (launchMetricPortTv != null) {
            launchMetricPortTv.setText(String.valueOf(port));
            launchMetricPortTv.setOnClickListener(v -> promptCustomLaunchPort());
        }
        if (launchPortBadge != null) {
            launchPortBadge.setText("PORT " + port);
            launchPortBadge.setOnClickListener(v -> promptCustomLaunchPort());
        }
        if (launchPortInput != null) {
            launchPortInput.setText(String.valueOf(port));
        }
        if (btnLaunchSavePort != null) {
            btnLaunchSavePort.setOnClickListener(v -> saveLaunchPortFromInput());
        }

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

        if (btnLaunchFileManager != null) {
            btnLaunchFileManager.setOnClickListener(v -> new com.xm486.pimet.ui.FileBrowserDialog(this).show());
        }

        if (btnLaunchPetToggle != null) {
            btnLaunchPetToggle.setOnClickListener(v -> togglePetFromLaunch());
        }

        if (btnLaunchLogDetail != null) {
            btnLaunchLogDetail.setOnClickListener(v -> {
                isDetailLogMode = !isDetailLogMode;
                if (btnLaunchLogDetail instanceof TextView) {
                    ((TextView) btnLaunchLogDetail).setText(isDetailLogMode ? "📄 简略日志" : "🔍 详细日志");
                }
                refreshLaunchLog();
            });
        }

        if (btnLaunchLogCopy != null) {
            btnLaunchLogCopy.setOnClickListener(v -> {
                if (launchLogTv != null && !TextUtils.isEmpty(launchLogTv.getText())) {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("PiMetLog", launchLogTv.getText()));
                        Toast.makeText(this, "守护日志已复制到剪切板", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(this, "暂无日志可复制", Toast.LENGTH_SHORT).show();
                }
            });
        }

        updateLaunchPetUI();

        btnLaunchLogRefresh.setOnClickListener(v -> refreshLaunchLog());
        btnLaunchLogClear.setOnClickListener(v -> {
            PiWebManager.clearLog(this);
            launchLogTv.setText("[日志已清空]");
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
        });

        refreshLaunchLog();
    }

    private void updateLaunchPetUI() {
        boolean enabled = PetRegistry.isPetEnabled(this) || PetOverlayService.isRunning();
        if (launchPetStatusTv != null) {
            launchPetStatusTv.setText(enabled ? "状态: 已开启" : "状态: 已关闭");
            launchPetStatusTv.setTextColor(enabled ? 0xFF3FB950 : 0xFF8B949E);
        }
        if (launchPetStateBadge != null) {
            launchPetStateBadge.setText(enabled ? "已开" : "已关");
            launchPetStateBadge.setTextColor(enabled ? 0xFF3FB950 : 0xFF8B949E);
        }
        if (launchPetIconTv != null) {
            launchPetIconTv.setText(enabled ? "🐾" : "💤");
        }
    }

    private void togglePetFromLaunch() {
        boolean currentlyActive = PetOverlayService.isRunning() || PetRegistry.isPetEnabled(this);
        boolean willEnable = !currentlyActive;
        PetRegistry.setPetEnabled(this, willEnable);
        if (willEnable) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                Toast.makeText(this, "请授予悬浮窗权限以显示桌宠", Toast.LENGTH_LONG).show();
            } else {
                PetOverlayService.start(this);
                Toast.makeText(this, "🐾 悬浮桌宠已开启", Toast.LENGTH_SHORT).show();
            }
        } else {
            PetOverlayService.stop(this);
            Toast.makeText(this, "🐾 悬浮桌宠已关闭", Toast.LENGTH_SHORT).show();
        }
        updateLaunchPetUI();
        updatePetDisplay(willEnable);
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
            String baseLog = PiWebManager.readLastLog(this);
            final String displayText;
            if (isDetailLogMode) {
                StringBuilder sb = new StringBuilder();
                sb.append("=== 🔍 PiMet 守护诊断详细日志 ===\n");
                sb.append("• 诊断时间: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new java.util.Date())).append("\n");
                sb.append("• 应用版本: v").append(PiMetConfig.getAppVersion(this)).append("\n");
                sb.append("• PRoot 容器: ").append(ProotManager.isRootfsInstalled(this) ? "已部署就绪 (OK)" : "未部署").append("\n");
                sb.append("• Pi-Web 端口: ").append(PiMetConfig.getWebPort(this))
                  .append(" (监听: ").append(isPiWebAlive ? "ALIVE 运行中" : "OFFLINE 未拉起").append(")\n");
                sb.append("• Operit 端口: ").append(PetRegistry.getOperitPort(this)).append("\n");
                sb.append("• ClawBench 端口: ").append(PetRegistry.getClawbenchPort(this)).append("\n");
                sb.append("• Rikka 端口: ").append(PetRegistry.getRikkaPort(this)).append("\n");
                sb.append("• 悬浮桌宠: ").append((PetRegistry.isPetEnabled(this) || PetOverlayService.isRunning()) ? "已开启" : "已关闭")
                  .append(" (当前形象: ").append(PetRegistry.getPetDir(this)).append(")\n");

                File rootfs = ProotManager.getRootfsDir(this);
                if (rootfs != null && rootfs.exists()) {
                    sb.append("• 容器根路径: ").append(rootfs.getAbsolutePath()).append("\n");
                }
                sb.append("----------------------------------------\n");
                sb.append("=== 📜 守护进程实时日志 ===\n");
                if (!TextUtils.isEmpty(baseLog)) {
                    sb.append(baseLog);
                } else {
                    sb.append("[守护服务暂无新输出]");
                }
                displayText = sb.toString();
            } else {
                displayText = !TextUtils.isEmpty(baseLog) ? baseLog : "[系统就绪] 暂无守护日志";
            }
            mainHandler.post(() -> {
                if (launchLogTv != null) {
                    launchLogTv.setText(displayText);
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

    private void saveLaunchPortFromInput() {
        if (launchPortInput == null) return;
        try {
            int oldPort = PiMetConfig.getWebPort(this);
            int newPort = Integer.parseInt(launchPortInput.getText().toString().trim());
            if (newPort < 1024 || newPort > 65535) {
                Toast.makeText(this, "端口需在 1024 ~ 65535 范围内", Toast.LENGTH_SHORT).show();
                return;
            }
            PiMetConfig.setWebPort(this, newPort);
            PetRegistry.setPiWebPort(this, newPort);
            refreshSettingsPortFields();
            updateLaunchStatusUI(isPiWebAlive);
            if (isPiWebAlive && oldPort != newPort) {
                new AlertDialog.Builder(this)
                        .setTitle("🔄 重启 Pi-Web 服务生效")
                        .setMessage("启动端口已由 " + oldPort + " 调整为 " + newPort + "。\n当前服务正在旧端口运行，是否立即重启服务？")
                        .setPositiveButton("立即重启", (d, w) -> restartPiWebService())
                        .setNegativeButton("稍后手动重启", null)
                        .show();
            } else {
                Toast.makeText(this, "✔ 启动端口已设定为 " + newPort + "，点击启动服务即可生效！", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "请输入合法的端口数字", Toast.LENGTH_SHORT).show();
        }
    }

    private void promptCustomLaunchPort() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(PiMetConfig.getWebPort(this)));
        input.setSelection(input.getText().length());
        input.setTextColor(0xFFF0F6FC);
        input.setBackgroundColor(0xFF0D1117);
        input.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));

        new AlertDialog.Builder(this)
                .setTitle("🌐 自定义 Pi-Web 启动端口")
                .setMessage("请输入 1024 ~ 65535 范围内的端口号：")
                .setView(input)
                .setPositiveButton("保存生效", (d, w) -> {
                    if (launchPortInput != null) {
                        launchPortInput.setText(input.getText().toString().trim());
                    }
                    saveLaunchPortFromInput();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updateLaunchStatusUI(boolean alive) {
        int port = PiMetConfig.getWebPort(this);
        if (launchPortBadge != null) {
            launchPortBadge.setText("PORT " + port);
        }
        if (launchMetricPortTv != null) {
            launchMetricPortTv.setText(String.valueOf(port));
        }
        if (launchPortInput != null && !launchPortInput.hasFocus()) {
            launchPortInput.setText(String.valueOf(port));
        }
        if (piWebOfflineSubTv != null) {
            piWebOfflineSubTv.setText("端口 " + port + " 尚未启动监听，请先启动服务。");
        }

        if (alive) {
            PiMetService.start(this);
            launchStatusDot.setBackgroundResource(R.drawable.bg_status_dot_green);
            launchStateTv.setText("服务运行中");
            launchStateTv.setTextColor(0xFF3FB950);
            launchSubtitleTv.setText("Pi-Web 守护进程正常监听中，可进入工作台或外部浏览器使用");
            btnLaunchMain.setText("🌐 进入 Pi-Web 工作台");
            btnLaunchMain.setBackgroundResource(R.drawable.bg_btn_primary);

            if (btnLaunchStop != null) {
                btnLaunchStop.setEnabled(true);
                btnLaunchStop.setAlpha(1.0f);
                btnLaunchStop.setBackgroundResource(R.drawable.bg_btn_danger);
                if (btnLaunchStop instanceof TextView) {
                    ((TextView) btnLaunchStop).setTextColor(0xFFFCA5A5);
                }
            }
            if (btnLaunchRestart != null) {
                btnLaunchRestart.setEnabled(true);
                btnLaunchRestart.setAlpha(1.0f);
                btnLaunchRestart.setBackgroundResource(R.drawable.bg_btn_secondary);
                if (btnLaunchRestart instanceof TextView) {
                    ((TextView) btnLaunchRestart).setTextColor(0xFFC9D1D9);
                }
            }
        } else {
            launchStatusDot.setBackgroundResource(R.drawable.bg_status_dot_gray);
            launchStateTv.setText("服务已停止");
            launchStateTv.setTextColor(0xFF8B949E);
            launchSubtitleTv.setText("内置 PRoot 容器环境已就绪，点击下方按钮启动 Pi-Web 守护服务");
            btnLaunchMain.setText("🚀 启动 Pi-Web 服务");
            btnLaunchMain.setBackgroundResource(R.drawable.bg_btn_success);

            if (btnLaunchStop != null) {
                btnLaunchStop.setEnabled(false);
                btnLaunchStop.setAlpha(0.35f);
                btnLaunchStop.setBackgroundResource(R.drawable.bg_btn_secondary);
                if (btnLaunchStop instanceof TextView) {
                    ((TextView) btnLaunchStop).setTextColor(0xFF6E7681);
                }
            }
            if (btnLaunchRestart != null) {
                btnLaunchRestart.setEnabled(false);
                btnLaunchRestart.setAlpha(0.35f);
                btnLaunchRestart.setBackgroundResource(R.drawable.bg_btn_secondary);
                if (btnLaunchRestart instanceof TextView) {
                    ((TextView) btnLaunchRestart).setTextColor(0xFF6E7681);
                }
            }
        }
    }

    private void startPiWebService() {
        if (isDeploying) {
            Toast.makeText(this, "正在部署中，请稍候...", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isPiWebActionInProgress) {
            Toast.makeText(this, "服务正在处理中，请稍候...", Toast.LENGTH_SHORT).show();
            return;
        }
        isPiWebActionInProgress = true;
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
                isPiWebActionInProgress = false;
                launchProgressBar.setVisibility(View.GONE);
                isPiWebAlive = true;
                PiMetService.start(MainActivity.this);
                updateLaunchStatusUI(true);
                Toast.makeText(MainActivity.this, "🎉 Pi-Web 服务已成功启动！", Toast.LENGTH_SHORT).show();
                refreshLaunchLog();

                // 核心预加载：服务一就绪，后台立刻触发静默渲染，用户点击进入工作台秒开呈现
                if (piWebWebView != null) {
                    piWebWebView.loadUrl(getPiWebUrl());
                }
            }

            @Override
            public void onError(String error) {
                isPiWebActionInProgress = false;
                launchProgressBar.setVisibility(View.GONE);
                isPiWebAlive = false;
                updateLaunchStatusUI(false);
                Toast.makeText(MainActivity.this, "启动失败: " + error, Toast.LENGTH_LONG).show();
                refreshLaunchLog();
            }
        });
    }

    private void restartPiWebService() {
        if (isDeploying) {
            Toast.makeText(this, "正在部署中，请稍候...", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isPiWebActionInProgress) {
            Toast.makeText(this, "服务正在处理中，请稍候...", Toast.LENGTH_SHORT).show();
            return;
        }
        isPiWebActionInProgress = true;
        launchProgressBar.setVisibility(View.VISIBLE);
        launchStateTv.setText("正在重启服务...");
        launchStatusDot.setBackgroundResource(R.drawable.bg_status_dot_yellow);
        Toast.makeText(this, "正在安全重启 Pi-Web...", Toast.LENGTH_SHORT).show();

        // 断开当前 WebView 连接，防止长连接锁定端口
        if (piWebWebView != null) {
            piWebWebView.stopLoading();
            piWebWebView.loadUrl("about:blank");
        }

        PiWebManager.restart(this, new PiWebManager.StateListener() {
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
                isPiWebActionInProgress = false;
                launchProgressBar.setVisibility(View.GONE);
                isPiWebAlive = true;
                PiMetService.start(MainActivity.this);
                updateLaunchStatusUI(true);
                Toast.makeText(MainActivity.this, "🎉 Pi-Web 服务已成功重启！", Toast.LENGTH_SHORT).show();
                refreshLaunchLog();

                // 核心预加载：服务就绪瞬间立即静默加载 WebView，进入工作台 0 秒呈现
                if (piWebWebView != null) {
                    piWebWebView.loadUrl(getPiWebUrl());
                }
            }

            @Override
            public void onError(String error) {
                isPiWebActionInProgress = false;
                launchProgressBar.setVisibility(View.GONE);
                isPiWebAlive = false;
                updateLaunchStatusUI(false);
                Toast.makeText(MainActivity.this, "重启失败: " + error, Toast.LENGTH_LONG).show();
                refreshLaunchLog();
            }
        });
    }

    private void stopPiWebService() {
        if (!isPiWebAlive) {
            Toast.makeText(this, "服务当前未在运行", Toast.LENGTH_SHORT).show();
            updateLaunchStatusUI(false);
            return;
        }
        if (isPiWebActionInProgress) {
            Toast.makeText(this, "正在处理中，请稍候...", Toast.LENGTH_SHORT).show();
            return;
        }
        isPiWebActionInProgress = true;
        launchProgressBar.setVisibility(View.VISIBLE);
        launchStateTv.setText("正在停止服务...");
        launchStatusDot.setBackgroundResource(R.drawable.bg_status_dot_yellow);

        // 切断当前 WebView 内部连接
        if (piWebWebView != null) {
            piWebWebView.stopLoading();
            piWebWebView.loadUrl("about:blank");
        }

        PiWebManager.stopPiWeb(this, () -> {
            isPiWebAlive = false;
            PiMetService.stop(this);
            isPiWebActionInProgress = false;
            launchProgressBar.setVisibility(View.GONE);
            updateLaunchStatusUI(false);
            Toast.makeText(this, "Pi-Web 服务已安全停止", Toast.LENGTH_SHORT).show();
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
                    // 清理历史残留的输入草稿缓存，防止旧消息重复回填至输入框
                    view.evaluateJavascript(
                        "(function() { " +
                        "  try { " +
                        "    sessionStorage.removeItem('__pimet_chat_draft'); " +
                        "    localStorage.removeItem('__pimet_chat_draft'); " +
                        "  } catch(e) {} " +
                        "})()",
                        null
                    );

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

        // 桌面宠物自由拖拽交互、奔跑手势、惯性甩动与弹射物理反弹
        if (floatingPetView != null) {
            floatingPetView.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        stopInAppFling();
                        floatDownRawX = event.getRawX();
                        floatDownRawY = event.getRawY();
                        floatInitialX = floatingMenuContainer.getTranslationX();
                        floatInitialY = floatingMenuContainer.getTranslationY();
                        isFloatDragging = false;
                        if (inAppVelocityTracker == null) {
                            inAppVelocityTracker = VelocityTracker.obtain();
                        } else {
                            inAppVelocityTracker.clear();
                        }
                        inAppVelocityTracker.addMovement(event);
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        if (inAppVelocityTracker != null) {
                            inAppVelocityTracker.addMovement(event);
                        }
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
                        if (isFloatDragging) {
                            if (inAppVelocityTracker != null) {
                                inAppVelocityTracker.addMovement(event);
                                inAppVelocityTracker.computeCurrentVelocity(1000);
                                float vx = inAppVelocityTracker.getXVelocity();
                                float vy = inAppVelocityTracker.getYVelocity();
                                inAppVelocityTracker.recycle();
                                inAppVelocityTracker = null;
                                int threshold = PetRegistry.getIntPref(
                                        MainActivity.this,
                                        PetRegistry.KEY_FLING_THRESHOLD,
                                        PetRegistry.DEFAULT_FLING_THRESHOLD);
                                if (Math.hypot(vx, vy) >= threshold) {
                                    startInAppFling(vx, vy);
                                }
                            }
                        } else {
                            if (inAppVelocityTracker != null) {
                                inAppVelocityTracker.recycle();
                                inAppVelocityTracker = null;
                            }
                            long pressDuration = event.getEventTime() - event.getDownTime();
                            if (pressDuration >= 400) {
                                showPetHudMenu();
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
        tvPetCardStream = findViewById(R.id.tvPetCardStream);
        tvPetThinking = findViewById(R.id.tvPetThinking);
        tvPetToolStatus = findViewById(R.id.tvPetToolStatus);
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
        if (bottomNavBar != null) {
            bottomNavBar.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        }
        if (btnFloatFullscreen != null) {
            btnFloatFullscreen.setText(fullscreen ? "✕" : "⛶");
        }
        View decorView = getWindow().getDecorView();
        if (fullscreen) {
            // 保留顶部系统状态栏（便于查看时间、电量），仅隐藏底部虚拟导航按键与底栏
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            Toast.makeText(this, "已进入工作台全屏模式", Toast.LENGTH_SHORT).show();
        } else {
            decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            Toast.makeText(this, "已退出全屏", Toast.LENGTH_SHORT).show();
        }
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void updatePiWebDisplay() {
        int port = PiMetConfig.getWebPort(this);
        String url = getPiWebUrl();

        // 关键秒开优化：若服务已知在运行，或者 WebView 已在目标地址，主线程同步直接切出工作台，毫秒级即现！
        if (isPiWebAlive || (piWebWebView != null && piWebWebView.getUrl() != null && piWebWebView.getUrl().startsWith("http://127.0.0.1:" + port))) {
            showPiWebOffline(false);
            if (piWebWebView != null && (piWebWebView.getUrl() == null || !piWebWebView.getUrl().startsWith("http://127.0.0.1:" + port))) {
                piWebWebView.loadUrl(url);
            }
        }

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
                    if (piWebWebView != null && (piWebWebView.getUrl() == null || !piWebWebView.getUrl().startsWith("http://127.0.0.1:" + port))) {
                        piWebWebView.loadUrl(url);
                    }
                    // 目标网页已正确加载，切勿调用 reload()，以完整保留 WebView 内存状态
                } else {
                    showPiWebOffline(true);
                }
            });
        }).start();
    }

    private void handlePiWebOfflineDetected() {
        if (isOfflineRecovering) return;
        isOfflineRecovering = true;
        int port = PiMetConfig.getWebPort(this);
        // 如果服务实际在运行或刚拉起，静默轮询并在就绪后自动重新加载，摆脱手动点击 Try again
        new Thread(() -> {
            try {
                for (int i = 0; i < 15; i++) {
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                    if (ProotManager.isPiWebHttpReady(port)) {
                        mainHandler.post(() -> {
                            if (piWebWebView != null) {
                                String url = getPiWebUrl();
                                String cur = piWebWebView.getUrl();
                                if (cur == null || !cur.startsWith("http://127.0.0.1:" + port)) {
                                    piWebWebView.loadUrl(url);
                                } else {
                                    piWebWebView.reload();
                                }
                                showPiWebOffline(false);
                            }
                        });
                        break;
                    }
                }
            } finally {
                isOfflineRecovering = false;
            }
        }).start();
    }

    private void showPiWebOffline(boolean offline) {
        piWebOfflineCard.setVisibility(offline ? View.VISIBLE : View.GONE);
        piWebWebView.setVisibility(offline ? View.GONE : View.VISIBLE);
        int port = PiMetConfig.getWebPort(this);
        piWebOfflineSubTv.setText("端口 " + port + " 尚未启动监听，请先启动服务。");
    }

    // ================= 设置面板 =================
    private void initSettingsPanel() {
        refreshSettingsPortFields();

        if (btnTestPiWebPort != null) {
            btnTestPiWebPort.setOnClickListener(v -> testPortInput(settingsPortInput, tvPiWebPortStatus));
        }
        if (btnTestOperitPort != null) {
            btnTestOperitPort.setOnClickListener(v -> testPortInput(settingsOperitPortInput, tvOperitPortStatus));
        }
        if (btnTestClawbenchPort != null) {
            btnTestClawbenchPort.setOnClickListener(v -> testPortInput(settingsClawbenchPortInput, tvClawbenchPortStatus));
        }
        if (btnTestRikkaPort != null) {
            btnTestRikkaPort.setOnClickListener(v -> testPortInput(settingsRikkaPortInput, tvRikkaPortStatus));
        }

        if (btnResetDefaultPorts != null) {
            btnResetDefaultPorts.setOnClickListener(v -> {
                if (settingsPortInput != null) settingsPortInput.setText(String.valueOf(PiMetConfig.DEFAULT_WEB_PORT));
                if (settingsOperitPortInput != null) settingsOperitPortInput.setText(String.valueOf(PetRegistry.DEFAULT_OPERIT_PORT));
                if (settingsClawbenchPortInput != null) settingsClawbenchPortInput.setText(String.valueOf(PetRegistry.DEFAULT_CB_PORT));
                if (settingsRikkaPortInput != null) settingsRikkaPortInput.setText("8095");
                Toast.makeText(this, "已重置输入框为默认端口，点击右侧保存生效", Toast.LENGTH_SHORT).show();
            });
        }

        if (btnSavePort != null) {
            btnSavePort.setOnClickListener(v -> {
                try {
                    int oldPort = PiMetConfig.getWebPort(this);
                    int pwPort = Integer.parseInt(settingsPortInput.getText().toString().trim());
                    int opPort = Integer.parseInt(settingsOperitPortInput.getText().toString().trim());
                    int cbPort = Integer.parseInt(settingsClawbenchPortInput.getText().toString().trim());
                    int rkPort = Integer.parseInt(settingsRikkaPortInput.getText().toString().trim());

                    if (pwPort < 1024 || pwPort > 65535 ||
                        opPort < 1024 || opPort > 65535 ||
                        cbPort < 1024 || cbPort > 65535 ||
                        rkPort < 1024 || rkPort > 65535) {
                        Toast.makeText(this, "端口需在 1024 ~ 65535 范围内", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    PiMetConfig.setWebPort(this, pwPort);
                    PetRegistry.setPiWebPort(this, pwPort);
                    PetRegistry.setOperitPort(this, opPort);
                    PetRegistry.setClawbenchPort(this, cbPort);
                    PetRegistry.setRikkaPort(this, rkPort);

                    if (settingsOperitTokenInput != null) {
                        String opToken = settingsOperitTokenInput.getText().toString().trim();
                        if (!opToken.isEmpty()) {
                            PetRegistry.setStringPref(this, ChatConfig.KEY_OPERIT_TOKEN, opToken);
                        }
                    }
                    if (settingsClawbenchTokenInput != null) {
                        String cbToken = settingsClawbenchTokenInput.getText().toString().trim();
                        if (!cbToken.isEmpty()) {
                            PetRegistry.setClawbenchToken(this, cbToken);
                        }
                    }

                    initPetMonitor();
                    checkServiceStatus();
                    refreshSettingsPortFields();
                    updateLaunchStatusUI(isPiWebAlive);
                    Toast.makeText(this, "所有自定义端口与凭证已保存并生效！", Toast.LENGTH_SHORT).show();

                    if (oldPort != pwPort && isPiWebAlive) {
                        new AlertDialog.Builder(this)
                                .setTitle("🔄 重启 Pi-Web 服务生效")
                                .setMessage("Pi-Web 启动端口已由 " + oldPort + " 调整为 " + pwPort + "。\n当前服务正在旧端口运行，是否立即重启服务应用新端口？")
                                .setPositiveButton("立即重启", (d, w) -> restartPiWebService())
                                .setNegativeButton("稍后手动重启", null)
                                .show();
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "无效端口数字: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }

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
            Toast.makeText(this, "正在清理 npm 缓存...", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                ProotManager.executeCommandSync(this, "npm cache clean --force");
                mainHandler.post(() -> {
                    Toast.makeText(this, "✔ npm 缓存清理完成", Toast.LENGTH_SHORT).show();
                    refreshStorageSize();
                });
            }).start();
        });

        btnResetContainer.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("重置 Linux 容器？")
                    .setMessage("此操作将彻底删除内置 PRoot 容器文件系统，所有已安装的 npm 包及数据将被清空。")
                    .setPositiveButton("确认重置", (dialog, which) -> {
                        Toast.makeText(this, "正在清理容器目录...", Toast.LENGTH_SHORT).show();
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
                toggleGlobalOverlay();
                btnTogglePetEnabled.setText(PetOverlayService.isRunning()
                        ? "🐾 桌宠: 运行中"
                        : "⚪ 桌宠: 已关闭");
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

        physicsContainer = findViewById(R.id.physicsContainer);
        buildPhysicsSettings();

        updatePetPreview();
        buildPetList();

        refreshStorageSize();
        refreshPrivilegeStatus();

        initSubagentsAndProactiveSettings();
        initUpdateCenter();
    }

    private void initSubagentsAndProactiveSettings() {
        updateProactiveSettingsUi();

        if (btnToggleProactiveSettings != null) {
            btnToggleProactiveSettings.setOnClickListener(v -> {
                boolean cur = com.xm486.pimet.pet.PetRegistry.getBooleanPref(this,
                        com.xm486.pimet.pet.PetMemoryManager.KEY_PROACTIVE_CHAT_ENABLED, true);
                com.xm486.pimet.pet.PetRegistry.setBooleanPref(this,
                        com.xm486.pimet.pet.PetMemoryManager.KEY_PROACTIVE_CHAT_ENABLED, !cur);
                updateProactiveSettingsUi();
                if (com.xm486.pimet.pet.PetOverlayService.isRunning()) {
                    if (!cur) com.xm486.pimet.pet.PetOverlayService.getInstance().startProactiveChatter();
                    else com.xm486.pimet.pet.PetOverlayService.getInstance().stopProactiveChatter();
                }
            });
        }

        if (btnAdjustProactiveIntervalSettings != null) {
            btnAdjustProactiveIntervalSettings.setOnClickListener(v -> showActivenessSettingsDialog());
        }
    }

    private void updateProactiveSettingsUi() {
        boolean on = com.xm486.pimet.pet.PetRegistry.getBooleanPref(this,
                com.xm486.pimet.pet.PetMemoryManager.KEY_PROACTIVE_CHAT_ENABLED, true);
        int min = com.xm486.pimet.pet.PetMemoryManager.getProactiveIntervalMin(this);
        if (btnToggleProactiveSettings != null) {
            btnToggleProactiveSettings.setText(on ? "🗣️ 主动说话: 开" : "🗣️ 主动说话: 关");
            btnToggleProactiveSettings.setTextColor(on ? 0xFF34D399 : 0xFF8B949E);
        }
        if (btnAdjustProactiveIntervalSettings != null) {
            btnAdjustProactiveIntervalSettings.setText(com.xm486.pimet.pet.PetMemoryManager.getActivenessShortLabel(min));
        }
    }

    private void showActivenessSettingsDialog() {
        final String[] options = new String[]{
                "🌟 话痨模式 (1分钟)",
                "😊 适度陪伴 (3分钟 - 默认)",
                "🍵 偶尔关怀 (8分钟)",
                "🤫 安静守护 (15分钟)"
        };
        final int[] mins = new int[]{1, 3, 8, 15};
        int cur = com.xm486.pimet.pet.PetMemoryManager.getProactiveIntervalMin(this);
        int selected = 1;
        for (int i = 0; i < mins.length; i++) {
            if (mins[i] == cur) {
                selected = i;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("⏱️ 调节桌宠主动关怀活跃度")
                .setSingleChoiceItems(options, selected, (d, which) -> {
                    d.dismiss();
                    com.xm486.pimet.pet.PetMemoryManager.setProactiveIntervalMin(this, mins[which]);
                    updateProactiveSettingsUi();
                    if (com.xm486.pimet.pet.PetOverlayService.isRunning()) {
                        com.xm486.pimet.pet.PetOverlayService.getInstance().startProactiveChatter();
                    }
                    Toast.makeText(this, "活跃度已设定: " + options[which], Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void initUpdateCenter() {
        if (settingsAppVersionTv != null) {
            settingsAppVersionTv.setText("当前客户端版本: v" + PiMetConfig.getAppVersion(this));
        }

        if (settingsPiWebVersionTv != null) {
            String installedPiWeb = UpdateManager.getInstalledPiWebVersion(this);
            settingsPiWebVersionTv.setText("已安装版本: v" + installedPiWeb);
        }

        if (btnCheckPiWebUpdate != null) {
            btnCheckPiWebUpdate.setOnClickListener(v -> {
                btnCheckPiWebUpdate.setEnabled(false);
                Toast.makeText(this, "正在检测 Pi-Web 工作台镜像源...", Toast.LENGTH_SHORT).show();
                UpdateManager.checkPiWebUpdate(this, (success, info, message) -> {
                    btnCheckPiWebUpdate.setEnabled(true);
                    if (success && info != null) {
                        if (settingsPiWebVersionTv != null) {
                            settingsPiWebVersionTv.setText("已安装: v" + info.currentVersion + (info.hasUpdate ? " (有新版: v" + info.latestVersion + ")" : " (最新)"));
                        }
                        if (info.hasUpdate) {
                            new AlertDialog.Builder(this)
                                    .setTitle("🎉 发现 Pi-Web 工作台新版本 (v" + info.latestVersion + ")")
                                    .setMessage("【当前已安装】v" + info.currentVersion +
                                            "\n【最新可用版本】v" + info.latestVersion +
                                            "\n【下载源】" + info.registryUsed +
                                            "\n\n平滑升级将直接在内置 PRoot 容器内执行增量更新，完整保留你的工程代码、配置文件、插件生态和会话记录。\n\n是否立即执行平滑更新？")
                                    .setPositiveButton("🚀 立即平滑更新", (d, w) -> {
                                        performPiWebInPlaceUpdate(info != null ? info.tarballUrl : null);
                                    })
                                    .setNegativeButton("稍后再说", null)
                                    .show();
                        } else {
                            new AlertDialog.Builder(this)
                                    .setTitle("✔ Pi-Web 工作台已是最新")
                                    .setMessage("当前版本: v" + info.currentVersion + "\n已是 npm 镜像源上的最新稳定版本，无需更新。")
                                    .setPositiveButton("确定", null)
                                    .show();
                        }
                    } else {
                        Toast.makeText(this, message != null ? message : "检测 Pi-Web 失败", Toast.LENGTH_SHORT).show();
                    }
                });
            });
        }

        if (btnCheckAppUpdate != null) {
            btnCheckAppUpdate.setOnClickListener(v -> {
                btnCheckAppUpdate.setEnabled(false);
                Toast.makeText(this, "正在检测网络并优选下载线路...", Toast.LENGTH_SHORT).show();
                UpdateManager.checkAppUpdate(this, (success, info, message) -> {
                    btnCheckAppUpdate.setEnabled(true);
                    if (info != null && info.hasUpdate) {
                        String routeTip = (info.bestRouteName != null ? info.bestRouteName : "优选线路") +
                                (info.routeLatencyMs > 0 ? " (" + info.routeLatencyMs + "ms)" : "");

                        String msg = (info.releaseTitle != null && !info.releaseTitle.isEmpty() ? info.releaseTitle + "\n\n" : "") +
                                "【网络优选线路】" + routeTip + "\n\n" +
                                "【更新内容】\n" + (info.changelog != null && !info.changelog.isEmpty() ? info.changelog : "常规体验与底层稳定性优化") +
                                "\n\n【说明】更新采用安全覆盖安装，完整保留现有所有数据、配置、会话与桌宠。";

                        new AlertDialog.Builder(this)
                                .setTitle("✨ 发现 PiMet 客户端新版本 " + info.latestVersion)
                                .setMessage(msg)
                                .setPositiveButton("📥 本地下载", (d, w) -> {
                                    UpdateManager.downloadLocally(this, info.bestDownloadUrl, info.latestVersion);
                                })
                                .setNeutralButton("📋 复制链接", (d, w) -> {
                                    UpdateManager.copyDownloadLink(this, info.bestDownloadUrl);
                                })
                                .setNegativeButton("🌐 浏览器打开", (d, w) -> {
                                    UpdateManager.startApkDownload(this, info.bestDownloadUrl);
                                })
                                .show();
                    } else if (info != null) {
                        String routeTip = (info.bestRouteName != null ? info.bestRouteName : "优选线路") +
                                (info.routeLatencyMs > 0 ? " (" + info.routeLatencyMs + "ms)" : "");
                        new AlertDialog.Builder(this)
                                .setTitle("✔ 当前已是最新版本")
                                .setMessage("当前安装版本: v" + info.currentVersion + "\n已连通线路: " + routeTip +
                                        "\n\n暂无可用新版本，若需备份或在其他设备安装，可复制安装包下载链接。")
                                .setPositiveButton("我知道了", null)
                                .setNeutralButton("📋 复制安装包链接", (d, w) -> {
                                    UpdateManager.copyDownloadLink(this, info.bestDownloadUrl);
                                })
                                .show();
                    } else {
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                    }
                });
            });
        }
    }

    private void performPiWebInPlaceUpdate() {
        performPiWebInPlaceUpdate(null);
    }

    private void performPiWebInPlaceUpdate(String tarballUrl) {
        // 构建带有实时进度条和日志流的高速平滑升级对话框
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16));
        layout.setBackgroundColor(0xFF0D1117);

        TextView tvStage = new TextView(this);
        tvStage.setText("正在准备升级环境...");
        tvStage.setTextColor(0xFFF0F6FC);
        tvStage.setTextSize(13f);
        tvStage.setTypeface(null, Typeface.BOLD);
        layout.addView(tvStage);

        ProgressBar pBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pBar.setMax(100);
        pBar.setProgress(5);
        LinearLayout.LayoutParams pbParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(6));
        pbParams.topMargin = dpToPx(10);
        pbParams.bottomMargin = dpToPx(6);
        pBar.setLayoutParams(pbParams);
        layout.addView(pBar);

        TextView tvPercent = new TextView(this);
        tvPercent.setText("5%");
        tvPercent.setTextColor(0xFF58A6FF);
        tvPercent.setTextSize(11f);
        tvPercent.setGravity(Gravity.END);
        layout.addView(tvPercent);

        TextView tvLogHeader = new TextView(this);
        tvLogHeader.setText("实时控制台输出：");
        tvLogHeader.setTextColor(0xFF8B949E);
        tvLogHeader.setTextSize(11f);
        tvLogHeader.setPadding(0, dpToPx(8), 0, dpToPx(4));
        layout.addView(tvLogHeader);

        ScrollView logScrollView = new ScrollView(this);
        LinearLayout.LayoutParams svParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(120));
        logScrollView.setLayoutParams(svParams);
        logScrollView.setBackgroundColor(0xFF161B22);
        logScrollView.setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6));

        TextView tvLiveLog = new TextView(this);
        tvLiveLog.setTextColor(0xFF7EE787);
        tvLiveLog.setTextSize(10f);
        tvLiveLog.setTypeface(Typeface.MONOSPACE);
        tvLiveLog.setText("[info] 启动更新流水线...\n");
        logScrollView.addView(tvLiveLog);
        layout.addView(logScrollView);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("🚀 Pi-Web 平滑增量升级")
                .setView(layout)
                .setCancelable(false)
                .setPositiveButton("后台升级中...", null)
                .create();

        dialog.show();
        Button positiveBtn = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (positiveBtn != null) positiveBtn.setEnabled(false);

        UpdateManager.updatePiWebInPlace(this, tarballUrl, (percent, stage, logLine) -> {
            pBar.setProgress(percent);
            tvPercent.setText(percent + "%");
            if (stage != null && !stage.isEmpty()) {
                tvStage.setText(stage);
            }
            if (logLine != null && !logLine.isEmpty()) {
                tvLiveLog.append(logLine + "\n");
                logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
            }
        }, (success, newVer, message) -> {
            if (positiveBtn != null) {
                positiveBtn.setEnabled(true);
                positiveBtn.setText("完成");
                positiveBtn.setOnClickListener(v -> dialog.dismiss());
            }

            if (success) {
                pBar.setProgress(100);
                tvPercent.setText("100%");
                tvStage.setText("✔ 升级成功！当前版本: v" + newVer);
                if (settingsPiWebVersionTv != null) {
                    settingsPiWebVersionTv.setText("已安装版本: v" + newVer);
                }
                new AlertDialog.Builder(this)
                        .setTitle("✨ 升级成功")
                        .setMessage("Pi-Web 工作台已成功平滑升级至 v" + newVer + "！\n所有本地配置与工程数据完整保留。")
                        .setPositiveButton("确定", (d, w) -> {
                            if (viewPiWeb != null && viewPiWeb.getVisibility() == View.VISIBLE && piWebWebView != null) {
                                piWebWebView.reload();
                            }
                        })
                        .show();
            } else {
                tvStage.setText("❌ 升级失败");
                tvStage.setTextColor(0xFFF85149);
                new AlertDialog.Builder(this)
                        .setTitle("升级失败")
                        .setMessage(message != null ? message : "更新过程中遇到错误，请检查网络或稍后重试。")
                        .setPositiveButton("确定", null)
                        .show();
            }
        });
    }

    public void openSettingsSubWindow(int category) {
        currentSettingsCategory = category;

        String title = "⚡ 端口与网络服务";
        switch (category) {
            case SETTINGS_CAT_PORTS:
                title = "⚡ 端口与网络服务";
                break;
            case SETTINGS_CAT_UPDATE:
                title = "🔄 软件更新中心";
                break;
            case SETTINGS_CAT_PET:
                title = "🐾 桌面宠物管理";
                break;
            case SETTINGS_CAT_PRIVILEGES:
                title = "🛡️ 系统特权与权限";
                break;
            case SETTINGS_CAT_STORAGE:
                title = "💾 容器存储与清理";
                break;
            case SETTINGS_CAT_ABOUT:
                title = "ℹ️ 关于与致敬";
                break;
        }

        if (tvSettingsSubWindowTitle != null) {
            tvSettingsSubWindowTitle.setText(title);
        }

        updateMenuItemState(menuItemPorts, menuTitlePorts, menuArrowPorts, category == SETTINGS_CAT_PORTS);
        updateMenuItemState(menuItemUpdate, menuTitleUpdate, menuArrowUpdate, category == SETTINGS_CAT_UPDATE);
        updateMenuItemState(menuItemPet, menuTitlePet, menuArrowPet, category == SETTINGS_CAT_PET);
        updateMenuItemState(menuItemPrivileges, menuTitlePrivileges, menuArrowPrivileges, category == SETTINGS_CAT_PRIVILEGES);
        updateMenuItemState(menuItemStorage, menuTitleStorage, menuArrowStorage, category == SETTINGS_CAT_STORAGE);
        updateMenuItemState(menuItemAbout, menuTitleAbout, menuArrowAbout, category == SETTINGS_CAT_ABOUT);

        applySettingsCardsVisibility();

        if (viewSettingsSubWindow != null) {
            viewSettingsSubWindow.setVisibility(View.VISIBLE);
        }
        if (viewSettings != null) {
            viewSettings.setVisibility(View.GONE);
        }
    }

    public void closeSettingsSubWindow() {
        if (viewSettingsSubWindow != null) {
            viewSettingsSubWindow.setVisibility(View.GONE);
        }
        if (viewSettings != null) {
            viewSettings.setVisibility(View.VISIBLE);
        }
    }

    public void switchSettingsCategory(int category) {
        currentSettingsCategory = category;

        updateMenuItemState(menuItemPorts, menuTitlePorts, menuArrowPorts, category == SETTINGS_CAT_PORTS);
        updateMenuItemState(menuItemUpdate, menuTitleUpdate, menuArrowUpdate, category == SETTINGS_CAT_UPDATE);
        updateMenuItemState(menuItemPet, menuTitlePet, menuArrowPet, category == SETTINGS_CAT_PET);
        updateMenuItemState(menuItemPrivileges, menuTitlePrivileges, menuArrowPrivileges, category == SETTINGS_CAT_PRIVILEGES);
        updateMenuItemState(menuItemStorage, menuTitleStorage, menuArrowStorage, category == SETTINGS_CAT_STORAGE);
        updateMenuItemState(menuItemAbout, menuTitleAbout, menuArrowAbout, category == SETTINGS_CAT_ABOUT);

        applySettingsCardsVisibility();
    }

    private void updateMenuItemState(View item, TextView title, TextView arrow, boolean isSelected) {
        if (item == null) return;
        if (isSelected) {
            item.setBackgroundColor(0xFF21262D);
            if (title != null) title.setTextColor(0xFF58A6FF);
            if (arrow != null) {
                arrow.setTextColor(0xFF58A6FF);
                arrow.setText("进入 ›");
            }
        } else {
            item.setBackgroundColor(0xFF161B22);
            if (title != null) title.setTextColor(0xFFF0F6FC);
            if (arrow != null) {
                arrow.setTextColor(0xFF8B949E);
                arrow.setText("›");
            }
        }
    }

    private void applySettingsCardsVisibility() {
        int category = currentSettingsCategory;

        // 端口与网络类卡片
        if (cardSettingsPorts != null) {
            cardSettingsPorts.setVisibility(category == SETTINGS_CAT_PORTS ? View.VISIBLE : View.GONE);
        }
        if (cardSettingsRegistry != null) {
            cardSettingsRegistry.setVisibility(category == SETTINGS_CAT_PORTS ? View.VISIBLE : View.GONE);
        }

        // 更新中心卡片
        if (cardSettingsUpdate != null) {
            cardSettingsUpdate.setVisibility(category == SETTINGS_CAT_UPDATE ? View.VISIBLE : View.GONE);
        }

        // 桌面宠物卡片
        if (cardSettingsPetPreview != null) {
            cardSettingsPetPreview.setVisibility(category == SETTINGS_CAT_PET ? View.VISIBLE : View.GONE);
        }
        if (cardSettingsPetProactive != null) {
            cardSettingsPetProactive.setVisibility(category == SETTINGS_CAT_PET ? View.VISIBLE : View.GONE);
        }

        // 特权与权限卡片
        if (cardSettingsPrivileges != null) {
            cardSettingsPrivileges.setVisibility(category == SETTINGS_CAT_PRIVILEGES ? View.VISIBLE : View.GONE);
        }

        // 容器存储与清理卡片
        if (cardSettingsStorage != null) {
            cardSettingsStorage.setVisibility(category == SETTINGS_CAT_STORAGE ? View.VISIBLE : View.GONE);
        }

        // 关于卡片
        if (cardSettingsAbout != null) {
            cardSettingsAbout.setVisibility(category == SETTINGS_CAT_ABOUT ? View.VISIBLE : View.GONE);
        }
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
                btnPrivilegeShizuku.setText("⚡ Shizuku: 已授权");
                btnPrivilegeShizuku.setTextColor(0xFF3FB950);
            } else if (DevicePrivilegeManager.isShizukuRunning()) {
                btnPrivilegeShizuku.setText("⚡ 申请 Shizuku");
                btnPrivilegeShizuku.setTextColor(0xFF58A6FF);
            } else if (DevicePrivilegeManager.isShizukuInstalled(this)) {
                btnPrivilegeShizuku.setText("⚡ Shizuku 未启动");
                btnPrivilegeShizuku.setTextColor(0xFFD29922);
            } else {
                btnPrivilegeShizuku.setText("⚡ Shizuku 授权");
                btnPrivilegeShizuku.setTextColor(0xFFC9D1D9);
            }
        }
        if (settingsShizukuStatusTv != null) {
            if (DevicePrivilegeManager.isShizukuPermissionGranted()) {
                settingsShizukuStatusTv.setText("Shizuku 状态: ✔ 已获得特权授权 (ADB 免Root)");
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
                        "💾 保存至容器 (/root/.clipboard.txt)",
                        "🌐 切换至工作台"
                }, (dialog, which) -> {
                    if (TextUtils.isEmpty(clip)) {
                        Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (which == 0) {
                        syncClipboardToContainer(true);
                    } else if (which == 1) {
                        switchTab(1);
                        Toast.makeText(this, "已切换至工作台，可直接在输入框粘贴", Toast.LENGTH_SHORT).show();
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
        if (viewSettingsSubWindow != null && viewSettingsSubWindow.getVisibility() == View.VISIBLE) {
            closeSettingsSubWindow();
            return;
        }

        if (floatingMenuVertical != null && floatingMenuVertical.getVisibility() == View.VISIBLE) {
            floatingMenuVertical.setVisibility(View.GONE);
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
                Toast.makeText(this, "正在双向同步并扫描插件与扩展...", Toast.LENGTH_SHORT).show();
                refreshPluginsList(currentPluginCategory);
            });
        }

        View btnCheckUpdates = findViewById(R.id.btnCheckUpdates);
        if (btnCheckUpdates != null) {
            btnCheckUpdates.setOnClickListener(v -> {
                Toast.makeText(this, "正在联网检查最新插件版本...", Toast.LENGTH_SHORT).show();
                new Thread(() -> {
                    List<PluginManager.PluginItem> all = PluginManager.getInstalledPlugins(this);
                    PluginManager.checkUpdatesAsync(this, all, updateCount -> {
                        mainHandler.post(() -> {
                            cachedPluginItems = all;
                            if (updateCount > 0) {
                                Toast.makeText(this, "发现 " + updateCount + " 个插件有新版本发布！", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(this, "已检查：当前所有插件均为最新版本 ✔", Toast.LENGTH_SHORT).show();
                            }
                            updatePluginListView(all, currentPluginCategory);
                        });
                    });
                }).start();
            });
        }

        View btnUpdateAllPlugins = findViewById(R.id.btnUpdateAllPlugins);
        if (btnUpdateAllPlugins != null) {
            btnUpdateAllPlugins.setOnClickListener(v -> {
                Toast.makeText(this, "正在更新容器内插件与依赖包...", Toast.LENGTH_LONG).show();
                new Thread(() -> {
                    boolean ok = PluginManager.updateAllPlugins(this);
                    mainHandler.post(() -> {
                        Toast.makeText(this, ok ? "✔ 插件更新流程完成！" : "插件更新结束", Toast.LENGTH_SHORT).show();
                        refreshPluginsList(currentPluginCategory);
                    });
                }).start();
            });
        }

        View btnAddPluginConfig = findViewById(R.id.btnAddPluginConfig);
        if (btnAddPluginConfig != null) {
            btnAddPluginConfig.setOnClickListener(v -> showAddPluginConfigDialog());
        }

        if (btnInstallCustomPlugin != null) {
            btnInstallCustomPlugin.setOnClickListener(v -> {
                String input = inputCustomPlugin != null ? inputCustomPlugin.getText().toString().trim() : "";
                if (input.isEmpty()) {
                    Toast.makeText(this, "请输入包名、Git 链接、Skill 网址或安装命令", Toast.LENGTH_SHORT).show();
                    return;
                }
                Toast.makeText(this, "正在智能分析输入类型并执行安装...", Toast.LENGTH_SHORT).show();
                new Thread(() -> {
                    PluginManager.SmartInstallResult res = PluginManager.smartInstall(this, input);
                    mainHandler.post(() -> {
                        if (res.success) {
                            Toast.makeText(this, "【" + res.detectedType + "】" + res.message, Toast.LENGTH_LONG).show();
                            if (inputCustomPlugin != null) inputCustomPlugin.setText("");
                        } else {
                            Toast.makeText(this, "安装失败: " + res.message, Toast.LENGTH_LONG).show();
                        }
                        refreshPluginsList(currentPluginCategory);
                    });
                }).start();
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

    private List<PluginManager.PluginItem> cachedPluginItems = new ArrayList<>();

    private void refreshPluginsList(int category) {
        currentPluginCategory = category;
        new Thread(() -> {
            List<PluginManager.PluginItem> fresh = PluginManager.getInstalledPlugins(this);
            if (cachedPluginItems != null && !cachedPluginItems.isEmpty()) {
                for (PluginManager.PluginItem item : fresh) {
                    for (PluginManager.PluginItem cached : cachedPluginItems) {
                        if (item.name.equals(cached.name) && item.type == cached.type) {
                            item.hasUpdate = cached.hasUpdate;
                            item.latestVersion = cached.latestVersion;
                            break;
                        }
                    }
                }
            }
            cachedPluginItems = fresh;
            updatePluginListView(fresh, category);
        }).start();
    }

    private void updatePluginListView(List<PluginManager.PluginItem> all, int category) {
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

        if (item.version != null && !item.version.isEmpty()) {
            TextView vBadge = new TextView(this);
            if (item.hasUpdate && item.latestVersion != null && !item.latestVersion.isEmpty()) {
                vBadge.setText("v" + item.version + " ➔ v" + item.latestVersion);
                vBadge.setTextColor(0xFF3FB950);
            } else {
                vBadge.setText("v" + item.version);
                vBadge.setTextColor(0xFF8B949E);
            }
            vBadge.setTextSize(10.5f);
            vBadge.setBackgroundResource(R.drawable.bg_badge_port);
            vBadge.setPadding(dpToPx(5), dpToPx(1), dpToPx(5), dpToPx(1));
            LinearLayout.LayoutParams vbLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            vbLp.leftMargin = dpToPx(6);
            vBadge.setLayoutParams(vbLp);
            header.addView(vBadge);
        }

        card.addView(header);

        TextView desc = new TextView(this);
        StringBuilder sbDesc = new StringBuilder();
        if (item.chineseDesc != null && !item.chineseDesc.isEmpty()) {
            sbDesc.append("📌 ").append(item.chineseDesc).append("\n");
        }
        if (item.description != null && !item.description.isEmpty() && !item.description.equals(item.chineseDesc)) {
            sbDesc.append("ℹ️ ").append(item.description).append("\n");
        }
        sbDesc.append("📁 路径: ").append(item.path);
        desc.setText(sbDesc.toString());
        desc.setTextColor(0xFF8B949E);
        desc.setTextSize(12f);
        desc.setLineSpacing(dpToPx(2), 1f);
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

        // 1. ⚙️ 配置/查看按钮
        TextView btnConfig = new TextView(this);
        btnConfig.setText("⚙️ 配置/详情");
        btnConfig.setTextColor(0xFFC9D1D9);
        btnConfig.setTextSize(11.5f);
        btnConfig.setBackgroundResource(R.drawable.bg_btn_secondary);
        btnConfig.setPadding(dpToPx(10), dpToPx(5), dpToPx(10), dpToPx(5));
        btnConfig.setOnClickListener(v -> showEditPluginConfigDialog(item));
        actions.addView(btnConfig);

        // 2. 🆙 更新按钮 (仅当有明确新版本时显示)
        if (item.hasUpdate) {
            View spacing1 = new View(this);
            actions.addView(spacing1, new LinearLayout.LayoutParams(dpToPx(6), 1));

            TextView btnUpdate = new TextView(this);
            btnUpdate.setText(item.latestVersion.isEmpty() ? "🆙 更新插件" : ("🆙 升级至 v" + item.latestVersion));
            btnUpdate.setTextColor(0xFF3FB950);
            btnUpdate.setTextSize(11.5f);
            btnUpdate.setBackgroundResource(R.drawable.bg_btn_secondary);
            btnUpdate.setPadding(dpToPx(10), dpToPx(5), dpToPx(10), dpToPx(5));
            btnUpdate.setOnClickListener(v -> {
                Toast.makeText(this, "正在更新: " + item.name + " ...", Toast.LENGTH_SHORT).show();
                new Thread(() -> {
                    boolean ok = PluginManager.updatePlugin(this, item);
                    mainHandler.post(() -> {
                        Toast.makeText(this, ok ? "✔ 插件已更新: " + item.name : "更新失败，请在终端查看输出", Toast.LENGTH_SHORT).show();
                        item.hasUpdate = false;
                        refreshPluginsList(currentPluginCategory);
                    });
                }).start();
            });
            actions.addView(btnUpdate);
        }

        View spacing2 = new View(this);
        actions.addView(spacing2, new LinearLayout.LayoutParams(dpToPx(6), 1));

        // 3. 🗑️ 移除按钮
        TextView btnDelete = new TextView(this);
        btnDelete.setText("🗑️ 移除");
        btnDelete.setTextColor(0xFFF85149);
        btnDelete.setTextSize(11.5f);
        btnDelete.setBackgroundResource(R.drawable.bg_btn_secondary);
        btnDelete.setPadding(dpToPx(10), dpToPx(5), dpToPx(10), dpToPx(5));
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

    private void showAddPluginConfigDialog() {
        String[] options = new String[]{
                "🌐 添加 MCP 外部服务 (mcp.json)",
                "🧠 新建 Skill 专家技能 (SKILL.md)",
                "🤖 新建 Subagent 子智能体 (.md)",
                "📄 编辑 settings.json (全局配置)"
        };
        new AlertDialog.Builder(this)
                .setTitle("添加生态配置")
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            showAddMcpDialog();
                            break;
                        case 1:
                            showAddSkillDialog();
                            break;
                        case 2:
                            showAddSubagentDialog();
                            break;
                        case 3:
                            showEditGlobalSettingsDialog();
                            break;
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showAddMcpDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(10));

        final EditText nameInput = createDialogInput("服务名称 (如 fetch)", false);
        final EditText cmdInput = createDialogInput("启动命令 (如 uvx, npx, python3)", false);
        final EditText argsInput = createDialogInput("参数 (如 mcp-server-fetch)", false);
        final EditText envInput = createDialogInput("环境变量 JSON (可选, 如 {\"API_KEY\":\"...\"})", true);

        layout.addView(createDialogLabel("服务标识名:"));
        layout.addView(nameInput);
        layout.addView(createDialogLabel("执行命令:"));
        layout.addView(cmdInput);
        layout.addView(createDialogLabel("运行参数 (空格隔开):"));
        layout.addView(argsInput);
        layout.addView(createDialogLabel("环境变量 (JSON 格式):"));
        layout.addView(envInput);

        new AlertDialog.Builder(this)
                .setTitle("添加 MCP 外部服务")
                .setView(layout)
                .setPositiveButton("保存并生效", (dialog, which) -> {
                    String name = nameInput.getText().toString().trim();
                    String cmd = cmdInput.getText().toString().trim();
                    String args = argsInput.getText().toString().trim();
                    String env = envInput.getText().toString().trim();
                    if (name.isEmpty() || cmd.isEmpty()) {
                        Toast.makeText(this, "服务名称与执行命令为必填项", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    boolean ok = PluginManager.saveMcpServer(this, name, cmd, args, env);
                    Toast.makeText(this, ok ? "✔ MCP 服务已添加！" : "添加失败", Toast.LENGTH_SHORT).show();
                    refreshPluginsList(currentPluginCategory);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showAddSkillDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(10));

        final EditText nameInput = createDialogInput("技能目录名 (如 code-review)", false);
        final EditText contentInput = createDialogInput("# 技能描述与流程规范\n\n## When to use\n...", true);
        contentInput.setMinLines(6);

        layout.addView(createDialogLabel("技能名称:"));
        layout.addView(nameInput);
        layout.addView(createDialogLabel("SKILL.md Markdown 内容:"));
        layout.addView(contentInput);

        new AlertDialog.Builder(this)
                .setTitle("新建 Skill 专家技能")
                .setView(layout)
                .setPositiveButton("保存", (dialog, which) -> {
                    String name = nameInput.getText().toString().trim();
                    String md = contentInput.getText().toString().trim();
                    if (name.isEmpty() || md.isEmpty()) {
                        Toast.makeText(this, "技能名称与内容不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    boolean ok = PluginManager.saveSkill(this, name, md);
                    Toast.makeText(this, ok ? "✔ Skill 技能已创建！" : "创建失败", Toast.LENGTH_SHORT).show();
                    refreshPluginsList(currentPluginCategory);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showAddSubagentDialog() {
        new com.xm486.pimet.subagent.SubAgentDialog(this).show();
    }

    private void showEditGlobalSettingsDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(10));

        String currentJson = PluginManager.getGlobalSettings(this);
        final EditText contentInput = createDialogInput("", true);
        contentInput.setText(currentJson);
        contentInput.setMinLines(8);
        contentInput.setTypeface(Typeface.MONOSPACE);

        layout.addView(createDialogLabel("编辑 ~/.pi/agent/settings.json:"));
        layout.addView(contentInput);

        new AlertDialog.Builder(this)
                .setTitle("全局 settings.json 配置")
                .setView(layout)
                .setPositiveButton("保存", (dialog, which) -> {
                    String json = contentInput.getText().toString().trim();
                    boolean ok = PluginManager.saveGlobalSettings(this, json);
                    Toast.makeText(this, ok ? "✔ settings.json 已更新" : "保存失败，请检查 JSON 格式", Toast.LENGTH_SHORT).show();
                    refreshPluginsList(currentPluginCategory);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showEditPluginConfigDialog(PluginManager.PluginItem item) {
        if (item.type == PluginManager.PluginItem.TYPE_SUBAGENT) {
            new com.xm486.pimet.subagent.SubAgentDialog(this).show();
            return;
        }

        String content = PluginManager.getPluginConfig(this, item);
        if (content == null || content.isEmpty()) {
            Toast.makeText(this, "该插件暂无文本配置文件，路径: " + item.path, Toast.LENGTH_LONG).show();
            return;
        }

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(10));

        final EditText contentInput = createDialogInput("", true);
        contentInput.setText(content);
        contentInput.setMinLines(8);
        contentInput.setTypeface(Typeface.MONOSPACE);

        layout.addView(createDialogLabel("配置/内容 (" + item.getTypeName() + "):"));
        layout.addView(contentInput);

        new AlertDialog.Builder(this)
                .setTitle("配置: " + item.name)
                .setView(layout)
                .setPositiveButton("保存修改", (dialog, which) -> {
                    String newContent = contentInput.getText().toString().trim();
                    boolean ok = PluginManager.savePluginConfig(this, item, newContent);
                    Toast.makeText(this, ok ? "✔ 配置已保存并更新" : "保存失败", Toast.LENGTH_SHORT).show();
                    refreshPluginsList(currentPluginCategory);
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private TextView createDialogLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFF8B949E);
        tv.setTextSize(11.5f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dpToPx(6);
        lp.bottomMargin = dpToPx(2);
        tv.setLayoutParams(lp);
        return tv;
    }

    private EditText createDialogInput(String hint, boolean multiline) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setTextColor(0xFFF0F6FC);
        et.setHintTextColor(0xFF484F58);
        et.setTextSize(12f);
        et.setBackgroundColor(0xFF0D1117);
        et.setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6));
        if (multiline) {
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            et.setGravity(Gravity.TOP | Gravity.START);
        } else {
            et.setInputType(InputType.TYPE_CLASS_TEXT);
            et.setSingleLine(true);
        }
        return et;
    }

    public void refreshSettingsPortFields() {
        if (settingsPortInput != null) {
            settingsPortInput.setText(String.valueOf(PiMetConfig.getWebPort(this)));
        }
        if (settingsOperitPortInput != null) {
            settingsOperitPortInput.setText(String.valueOf(PetRegistry.getOperitPort(this)));
        }
        if (settingsOperitTokenInput != null) {
            settingsOperitTokenInput.setText(PetRegistry.getStringPref(this, ChatConfig.KEY_OPERIT_TOKEN, "465ea3984db74e0790e8df63c6e85643"));
        }
        if (settingsClawbenchPortInput != null) {
            settingsClawbenchPortInput.setText(String.valueOf(PetRegistry.getClawbenchPort(this)));
        }
        if (settingsClawbenchTokenInput != null) {
            settingsClawbenchTokenInput.setText(PetRegistry.getClawbenchToken(this));
        }
        if (settingsRikkaPortInput != null) {
            settingsRikkaPortInput.setText(String.valueOf(PetRegistry.getRikkaPort(this)));
        }
    }

    private void refreshSettingsUiFields() {
        updateLaunchPetUI();
        refreshSettingsPortFields();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra("pimet.open_terminal", false)) {
            openTerminalInWorkbench();
        }
        if (intent != null && intent.getBooleanExtra("pimet.open_web", false)) {
            switchTab(1);
        }
        if (intent != null && (intent.getBooleanExtra("pimet.open_settings", false)
                || intent.getBooleanExtra("devpetm.open_ai_tab", false))) {
            switchTab(3);
        }
        if (intent != null && (intent.getBooleanExtra("pimet.toggle_web_fullscreen", false)
                || intent.getBooleanExtra("pimet.open_pet_chat", false))) {
            switchTab(1);
            toggleFullscreen(!isFullscreen || (viewPiWeb != null && viewPiWeb.getVisibility() != View.VISIBLE));
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
        buildPhysicsSettings();
        if (btnToggleGlobalOverlay != null) {
            btnToggleGlobalOverlay.setText(PetOverlayService.isRunning()
                    ? "🌐 系统全局桌宠悬浮窗: 运行中 (点击关闭)"
                    : "🌐 系统全局桌宠悬浮窗: 未开启 (点击开启)");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        com.xm486.pimet.bridge.AppBridgeManager.getInstance(this).detachActivity(this);
        try {
            unregisterReceiver(overlayStateReceiver);
        } catch (Throwable ignored) {}
        stopInAppFling();
        if (inAppVelocityTracker != null) {
            try { inAppVelocityTracker.recycle(); } catch (Throwable ignored) {}
            inAppVelocityTracker = null;
        }
        if (petHudDialog != null) {
            try { petHudDialog.dismiss(); } catch (Throwable ignored) {}
            petHudDialog = null;
        }
        if (previewPetView != null) {
            previewPetView.stopTicker();
        }
        if (piWebMonitor != null) {
            piWebMonitor.stop();
        }
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
        } catch (Throwable ignored) {}

    }

    // ---------------- 拖动物理手感参数设置 (8 项原生交互滑块) ----------------
    private static final Object[][] PHYSICS_ITEMS = {
            {"宠物大小", PetRegistry.KEY_PET_SIZE, 32, 120, PetRegistry.DEFAULT_PET_SIZE, "dp"},
            {"甩动灵敏度", PetRegistry.KEY_FLING_THRESHOLD, 100, 2000, PetRegistry.DEFAULT_FLING_THRESHOLD, "px/s"},
            {"反弹保留", PetRegistry.KEY_BOUNCE, 0, 100, PetRegistry.DEFAULT_BOUNCE, "%"},
            {"空气摩擦", PetRegistry.KEY_FRICTION, 0, 100, PetRegistry.DEFAULT_FRICTION, "%"},
            {"停稳阈值", PetRegistry.KEY_STOP_SPEED, 10, 200, PetRegistry.DEFAULT_STOP_SPEED, "px/s"},
            {"聊天框宽度", PetRegistry.KEY_CARD_WIDTH, 140, 360, PetRegistry.DEFAULT_CARD_WIDTH, "dp"},
            {"设置框宽度", PetRegistry.KEY_MENU_WIDTH, 140, 320, PetRegistry.DEFAULT_MENU_WIDTH, "dp"},
            {"窗口紧凑度", PetRegistry.KEY_CARD_SCALE, 40, 120, PetRegistry.DEFAULT_CARD_SCALE, "%"},
    };

    private void buildPhysicsSettings() {
        if (physicsContainer == null) return;
        physicsContainer.removeAllViews();

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);

        for (Object[] item : PHYSICS_ITEMS) {
            final String name = (String) item[0];
            final String key = (String) item[1];
            final int min = (Integer) item[2];
            final int max = (Integer) item[3];
            final int def = (Integer) item[4];
            final String unit = (String) item[5];
            int cur = PetRegistry.getIntPref(this, key, def);
            cur = Math.max(min, Math.min(max, cur));

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dpToPx(3), 0, dpToPx(3));

            final TextView label = new TextView(this);
            label.setText(name);
            label.setTextSize(11.5f);
            label.setTextColor(0xFF8B949E);
            label.setMaxLines(1);
            row.addView(label, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f));

            final TextView valueText = new TextView(this);
            valueText.setText(cur + " " + unit);
            valueText.setTextSize(11.5f);
            valueText.setTypeface(Typeface.MONOSPACE);
            valueText.setTextColor(0xFF58A6FF);
            valueText.setGravity(Gravity.END);
            row.addView(valueText, new LinearLayout.LayoutParams(
                    dpToPx(62), LinearLayout.LayoutParams.WRAP_CONTENT));

            SeekBar sb = new SeekBar(this);
            sb.setMax(max - min);
            sb.setProgress(cur - min);
            sb.setContentDescription(name);
            sb.setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    int val = min + progress;
                    PetRegistry.setIntPref(MainActivity.this, key, val);
                    valueText.setText(val + " " + unit);
                    if (fromUser) {
                        applyPetParams();
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            row.addView(sb, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f));

            column.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        physicsContainer.addView(column);
    }
}
