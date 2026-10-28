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
import android.content.res.Configuration;
import android.util.TypedValue;
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
import com.xm486.pimet.translation.TranslationBridge;
import com.xm486.pimet.translation.WebTranslationDialog;
import com.xm486.pimet.translation.WebTranslationManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
    public static final int PLUGIN_CAT_MODELS = 0;
    public static final int PLUGIN_CAT_SKILLS = 1;
    public static final int PLUGIN_CAT_SUBAGENTS = 2;
    public static final int PLUGIN_CAT_PLUGINS = 3;
    public static final int PLUGIN_CAT_MCP = 4;
    private int currentPluginCategory = PLUGIN_CAT_MODELS;
    private TextView settingsShizukuStatusTv;
    private View btnSyncAiFromContainer;

    // Launch (启动/仪表盘) 视图组件
    private View launchStatusDot;
    private TextView launchStateTv;
    private TextView launchPortBadge;
    private TextView launchSubtitleTv;
    private ProgressBar launchProgressBar;
    private View launchProgressContainer;
    private TextView launchProgressLabelTv;
    private TextView launchProgressPercentTv;
    private ScrollView launchConsoleScroll;
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

    private View menuItemHelp;

    // 界面外观与设计风格组件 (Theme)
    private View menuItemTheme;
    private TextView menuTitleTheme;
    private TextView menuArrowTheme;
    private View cardSettingsTheme;
    private TextView tvCurrentThemeBadge;
    private View themeRowAuto;
    private TextView tvThemeTitleAuto;
    private TextView tvThemeCheckAuto;
    private View themeRowLight;
    private TextView tvThemeTitleLight;
    private TextView tvThemeCheckLight;
    private View themeRowDark;
    private TextView tvThemeTitleDark;
    private TextView tvThemeCheckDark;
    private View themeRowMist;
    private TextView tvThemeTitleMist;
    private TextView tvThemeCheckMist;
    private View themeRowRose;
    private TextView tvThemeTitleRose;
    private TextView tvThemeCheckRose;
    private View themeRowPine;
    private TextView tvThemeTitlePine;
    private TextView tvThemeCheckPine;
    /** Pi-Web 0.12.2 新增主题的选项行（代码生成，追加在 XML 六个选项之后）：id -> {row, title, check} */
    private final java.util.LinkedHashMap<String, View[]> extraThemeRows = new java.util.LinkedHashMap<>();
    /** 上次 applyAppTheme 时的主题偏好；在工作台独立窗口里经 Pi-Web 改了主题，回到主界面时据此重绘 */
    private String lastAppliedThemePref;

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
    private View cardSettingsRootfsMirror;
    private View btnTestAllRootfsMirrors;
    private TextView tvRootfsMirrorStatusHint;
    private LinearLayout rootfsMirrorOptionsContainer;
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
    private static final int SETTINGS_CAT_THEME = 6;
    public static final int REQUEST_CODE_IMPORT_BACKUP = 2001;
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
    private TextView btnFloatTranslate;
    private TextView btnFloatZoom;
    private TextView btnFloatTheme;
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
    private com.xm486.pimet.ui.StorageCleanPanel storageCleanPanel;
    private TextView btnBatteryIgnoreOpt;
    private View btnAutoStartSettings;
    private TextView btnPrivilegeRoot;
    private TextView btnPrivilegeShizuku;
    private TextView btnPrivilegeAllFiles;
    private TextView btnSyncClipboard;

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
            boolean running = PetOverlayService.isRunning();
            updatePetDisplay(running);
            updateLaunchPetUI();
            if (btnToggleGlobalOverlay != null) {
                btnToggleGlobalOverlay.setText(running
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

        // 统一全面应用主题配色方案
        applyAppTheme();

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
        // 写容器内扩展/代理文件属于磁盘 I/O，放到后台线程，避免拖慢首屏
        final android.content.Context appCtx = getApplicationContext();
        new Thread(() -> {
            PluginManager.ensureAndroidBridgeExtension(appCtx);
            com.xm486.pimet.pet.PetMemoryManager.ensurePetSubagentInstalled(appCtx);
        }, "pimet-ensure-ext").start();
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
        launchProgressContainer = findViewById(R.id.launchProgressContainer);
        launchProgressLabelTv = findViewById(R.id.launchProgressLabelTv);
        launchProgressPercentTv = findViewById(R.id.launchProgressPercentTv);
        launchProgressBar = findViewById(R.id.launchProgressBar);
        launchConsoleScroll = findViewById(R.id.launchConsoleScroll);
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

        menuItemUpdate = null;
        menuTitleUpdate = null;
        menuArrowUpdate = null;

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

        menuItemHelp = findViewById(R.id.menuItemHelp);

        menuItemTheme = findViewById(R.id.menuItemTheme);
        menuTitleTheme = findViewById(R.id.menuTitleTheme);
        menuArrowTheme = findViewById(R.id.menuArrowTheme);

        cardSettingsTheme = findViewById(R.id.cardSettingsTheme);
        tvCurrentThemeBadge = findViewById(R.id.tvCurrentThemeBadge);
        themeRowAuto = findViewById(R.id.themeRowAuto);
        tvThemeTitleAuto = findViewById(R.id.tvThemeTitleAuto);
        tvThemeCheckAuto = findViewById(R.id.tvThemeCheckAuto);
        themeRowLight = findViewById(R.id.themeRowLight);
        tvThemeTitleLight = findViewById(R.id.tvThemeTitleLight);
        tvThemeCheckLight = findViewById(R.id.tvThemeCheckLight);
        themeRowDark = findViewById(R.id.themeRowDark);
        tvThemeTitleDark = findViewById(R.id.tvThemeTitleDark);
        tvThemeCheckDark = findViewById(R.id.tvThemeCheckDark);
        themeRowMist = findViewById(R.id.themeRowMist);
        tvThemeTitleMist = findViewById(R.id.tvThemeTitleMist);
        tvThemeCheckMist = findViewById(R.id.tvThemeCheckMist);
        themeRowRose = findViewById(R.id.themeRowRose);
        tvThemeTitleRose = findViewById(R.id.tvThemeTitleRose);
        tvThemeCheckRose = findViewById(R.id.tvThemeCheckRose);
        themeRowPine = findViewById(R.id.themeRowPine);
        tvThemeTitlePine = findViewById(R.id.tvThemeTitlePine);
        tvThemeCheckPine = findViewById(R.id.tvThemeCheckPine);

        if (menuItemTheme != null) menuItemTheme.setOnClickListener(v -> openSettingsSubWindow(SETTINGS_CAT_THEME));

        if (themeRowAuto != null) themeRowAuto.setOnClickListener(v -> selectTheme(ThemeManager.THEME_AUTO));
        if (themeRowLight != null) themeRowLight.setOnClickListener(v -> selectTheme(ThemeManager.THEME_LIGHT));
        if (themeRowDark != null) themeRowDark.setOnClickListener(v -> selectTheme(ThemeManager.THEME_DARK));
        if (themeRowMist != null) themeRowMist.setOnClickListener(v -> selectTheme(ThemeManager.THEME_MIST));
        if (themeRowRose != null) themeRowRose.setOnClickListener(v -> selectTheme(ThemeManager.THEME_ROSE));
        if (themeRowPine != null) themeRowPine.setOnClickListener(v -> selectTheme(ThemeManager.THEME_PINE));
        buildExtraThemeRows();

        cardSettingsPorts = findViewById(R.id.cardSettingsPorts);
        cardSettingsUpdate = findViewById(R.id.cardSettingsUpdate);
        cardSettingsRegistry = findViewById(R.id.cardSettingsRegistry);
        cardSettingsRootfsMirror = findViewById(R.id.cardSettingsRootfsMirror);
        btnTestAllRootfsMirrors = findViewById(R.id.btnTestAllRootfsMirrors);
        tvRootfsMirrorStatusHint = findViewById(R.id.tvRootfsMirrorStatusHint);
        rootfsMirrorOptionsContainer = findViewById(R.id.rootfsMirrorOptionsContainer);
        initRootfsMirrorSettings();
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
        if (menuItemHelp != null) menuItemHelp.setOnClickListener(v -> new com.xm486.pimet.ui.HelpGuideDialog(this).show());

        if (btnSettingsExportBackup != null) btnSettingsExportBackup.setOnClickListener(v -> {
            new com.xm486.pimet.ui.FileBrowserDialog(this).showBackupMigrationDialog();
        });
        if (btnSettingsImportBackup != null) btnSettingsImportBackup.setOnClickListener(v -> {
            launchBackupFilePicker();
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
        btnFloatTranslate = findViewById(R.id.btnFloatTranslate);
        btnFloatZoom = null; // 悬浮球缩放按钮已移除，双指缩放保留
        btnFloatTheme = findViewById(R.id.btnFloatTheme);
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
        View btnStorageScan = findViewById(R.id.btnStorageScan);
        LinearLayout storageScanContainer = findViewById(R.id.storageScanContainer);
        if (btnStorageScan != null && storageScanContainer != null) {
            storageCleanPanel = new com.xm486.pimet.ui.StorageCleanPanel(this, storageScanContainer,
                    piWebWebView, this::refreshStorageSize);
            btnStorageScan.setOnClickListener(v -> storageCleanPanel.scan());
        }
        btnBatteryIgnoreOpt = findViewById(R.id.btnBatteryIgnoreOpt);
        btnAutoStartSettings = findViewById(R.id.btnAutoStartSettings);
        btnPrivilegeRoot = findViewById(R.id.btnPrivilegeRoot);
        btnPrivilegeShizuku = findViewById(R.id.btnPrivilegeShizuku);
        settingsShizukuStatusTv = findViewById(R.id.settingsShizukuStatusTv);
        btnPrivilegeAllFiles = findViewById(R.id.btnPrivilegeAllFiles);
        btnSyncClipboard = findViewById(R.id.btnSyncClipboard);

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

    /** 底栏线性图标与标签同色（选中=强调色，未选中=次要文字色），颜色全部来自当前主题调色板。 */
    private void syncTabIconColors() {
        int[][] pairs = {
                {R.id.tabLaunchIcon, R.id.tabLaunchText},
                {R.id.tabPiWebIcon, R.id.tabPiWebText},
                {R.id.tabPluginsIcon, R.id.tabPluginsText},
                {R.id.tabSettingsIcon, R.id.tabSettingsText},
        };
        for (int[] pair : pairs) {
            View icon = findViewById(pair[0]);
            View label = findViewById(pair[1]);
            if (icon instanceof TextView && label instanceof TextView) {
                ((TextView) icon).setTextColor(((TextView) label).getCurrentTextColor());
            }
        }
    }

    public void switchTab(int index) {
        viewLaunch.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        viewPiWeb.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        if (viewPlugins != null) viewPlugins.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        viewSettings.setVisibility(index == 3 ? View.VISIBLE : View.GONE);
        if (viewSettingsSubWindow != null) viewSettingsSubWindow.setVisibility(View.GONE);

        // 更新底栏颜色
        ThemeManager.ThemePalette palette = ThemeManager.getEffectivePalette(this);
        int activeColor = palette.accent;
        int normalColor = palette.textMuted;

        tabLaunchText.setTextColor(index == 0 ? activeColor : normalColor);
        tabPiWebText.setTextColor(index == 1 ? activeColor : normalColor);
        if (tabPluginsText != null) tabPluginsText.setTextColor(index == 2 ? activeColor : normalColor);
        tabSettingsText.setTextColor(index == 3 ? activeColor : normalColor);
        syncTabIconColors();

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
        ThemeManager.ThemePalette palette = ThemeManager.getEffectivePalette(this);

        for (PetRegistry.PetInfo pet : pets) {
            boolean selected = pet.dir.equals(currentDir);
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));
            item.setBackground(selected ? ThemeManager.createPrimaryButtonDrawable(this, palette, 8f) : ThemeManager.createSunkenDrawable(this, palette, 8f));

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
            tv.setTextColor(selected ? (palette.isDark ? 0xFF0D1117 : 0xFFFFFFFF) : palette.textMuted);
            tv.setGravity(Gravity.CENTER);
            tv.setMaxLines(1);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(thumbSize + dpToPx(8), LinearLayout.LayoutParams.WRAP_CONTENT);
            tvLp.topMargin = dpToPx(4);
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
        new com.xm486.pimet.ui.Icons.CompatDialogBuilder(this)
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
        new com.xm486.pimet.ui.Icons.CompatDialogBuilder(this)
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

    private OperitState mainLastPetState = null;

    /**
     * 主界面自己的 Pi-Web 状态监听：只在界面可见时运行（onPause 停止），重复进入不再重建连接；
     * 只播报真实转折（开始思考/调用工具/完成/出错），不再把每个 SSE 事件或重连时的「空闲」当成「任务完成」。
     */
    public void initPetMonitor() {
        int port = PiMetConfig.getWebPort(this);
        if (piWebMonitor != null && piWebMonitor.isRunning() && piWebMonitorPort == port) return;
        if (piWebMonitor != null) {
            piWebMonitor.stop();
        }
        piWebMonitorPort = port;
        piWebMonitor = new PiWebMonitor(this, port, new PiWebMonitor.Listener() {
            @Override
            public void onSnapshot(OperitState.Snapshot snapshot) {
                OperitState prev = mainLastPetState;
                mainLastPetState = snapshot.state;
                lastPetSnapshot = snapshot;
                updateFloatingPetChatCardStatus();
                if (!PetRegistry.isPetEnabled(MainActivity.this) && !isFullscreen) {
                    return;
                }
                if (floatingPetView != null) {
                    floatingPetView.updateState(snapshot.state);
                }
                String tr = snapshot.transition;
                if ("error".equals(tr)) {
                    if (floatingPetView != null) floatingPetView.playOneShot("failed");
                    showPetBubble("😱 任务出错啦: " + (TextUtils.isEmpty(snapshot.errorMessage) ? "未知错误" : snapshot.errorMessage));
                    return;
                }
                if ("completed".equals(tr)) {
                    if (floatingPetView != null) floatingPetView.playOneShot("jumping");
                    showPetBubble("🎉 任务完成！随时待命");
                    return;
                }
                if ("aborted".equals(tr)) {
                    showPetBubble("⏹ 任务已中止");
                    return;
                }
                if (prev == snapshot.state) return; // 同一状态的后续事件不重复播报
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
                    default:
                        break;
                }
            }

            @Override
            public void onError(String message) {}
        });
        piWebMonitor.start();
    }

    private int piWebMonitorPort = -1;

    @Override
    protected void onPause() {
        super.onPause();
        // 后台时停掉主界面的监听与网页渲染（桌宠悬浮窗有自己的监控，不受影响）
        if (piWebMonitor != null) {
            piWebMonitor.stop();
            piWebMonitor = null;
        }
        try {
            if (piWebWebView != null) piWebWebView.onPause();
        } catch (Throwable ignored) {}
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
            AlertDialog.Builder builder = new com.xm486.pimet.ui.Icons.CompatDialogBuilder(this);
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12));

            // ---- 顶栏：标题 + ✕ 关闭 ----
            LinearLayout topBar = new LinearLayout(this);
            topBar.setOrientation(LinearLayout.HORIZONTAL);
            topBar.setGravity(Gravity.CENTER_VERTICAL);

            TextView title = new TextView(this);
            title.setText("🐾 桌宠控制中心");
            title.setTextColor(0xFFF0F6FC);
            title.setTextSize(12f);
            title.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
            topBar.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView closeBtn = new TextView(this);
            closeBtn.setText("✕");
            closeBtn.setTextColor(0xFF8B949E);
            closeBtn.setTextSize(13f);
            closeBtn.setPadding(dpToPx(8), dpToPx(2), dpToPx(4), dpToPx(2));
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
            divLp1.topMargin = dpToPx(8);
            divLp1.bottomMargin = dpToPx(8);
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
            divLp2.bottomMargin = dpToPx(8);
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
            tvPetName.setTextSize(12f);
            tvPetName.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
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
            actionChipsRow.setPadding(0, dpToPx(8), 0, 0);

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
            divLp3.bottomMargin = dpToPx(8);
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
                bg.setCornerRadius(dpToPx(12));
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
        chip.setTextSize(11f);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));
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
            PetRegistry.applyTargetPet(MainActivity.this, targetKey); // 切到该监控绑定的桌宠
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
        tv.setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));

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
        AlertDialog.Builder builder = new com.xm486.pimet.ui.Icons.CompatDialogBuilder(this);
        builder.setTitle("🌐 自定义多服务端口配置");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12));

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
        tv.setPadding(0, dpToPx(8), 0, dpToPx(2));
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
        bg.setCornerRadius(dpToPx(6));
        bg.setStroke(dpToPx(1), 0x33475569);
        et.setBackground(bg);
        row.addView(et);

        TextView btnTest = new TextView(this);
        btnTest.setText("测试");
        btnTest.setTextColor(0xFFC9D1D9);
        btnTest.setTextSize(11f);
        btnTest.setGravity(Gravity.CENTER);
        btnTest.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));
        GradientDrawable tbg = new GradientDrawable();
        tbg.setColor(0x2230363D);
        tbg.setCornerRadius(dpToPx(6));
        tbg.setStroke(dpToPx(1), 0x22FFFFFF);
        btnTest.setBackground(tbg);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dpToPx(36));
        btnLp.setMarginStart(dpToPx(8));
        row.addView(btnTest, btnLp);

        TextView tvStatus = new TextView(this);
        tvStatus.setTextSize(11f);
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

    // ---- 聊天卡「停止」打断 ----
    private volatile boolean petChatRunning = false;
    private volatile boolean petChatCancelled = false;
    private volatile HttpURLConnection petChatConn;
    private volatile String petChatSessionId;

    private void setPetChatRunning(boolean running) {
        petChatRunning = running;
        mainHandler.post(() -> {
            if (btnSendFloatPetChat instanceof TextView) {
                ((TextView) btnSendFloatPetChat).setText(running ? "停止" : "发送");
            }
        });
    }

    /** 打断当前 Pi-Web 回复：通知 Agent abort 并断开事件流 */
    private void interruptPetChat() {
        petChatCancelled = true;
        final HttpURLConnection c = petChatConn;
        final String sid = petChatSessionId;
        final int port = PiMetConfig.getWebPort(this);
        new Thread(() -> {
            if (sid != null) {
                HttpURLConnection a = null;
                try {
                    a = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/api/agent/" + sid).openConnection();
                    a.setRequestMethod("POST");
                    a.setDoOutput(true);
                    a.setConnectTimeout(2000);
                    a.setReadTimeout(3000);
                    a.setRequestProperty("Content-Type", "application/json");
                    a.getOutputStream().write("{\"type\":\"abort\"}".getBytes(StandardCharsets.UTF_8));
                    a.getResponseCode();
                } catch (Throwable ignored) {
                } finally {
                    if (a != null) try { a.disconnect(); } catch (Throwable ignored) {}
                }
            }
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) {}
        }).start();
        setPetChatRunning(false);
        showPetBubble("⏹ 已打断");
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
            PetRegistry.applyTargetPet(this, PetRegistry.TARGET_PIWEB); // 切到该监控绑定的桌宠
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
            PetRegistry.applyTargetPet(this, PetRegistry.TARGET_OPERIT); // 切到该监控绑定的桌宠
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
            PetRegistry.applyTargetPet(this, PetRegistry.TARGET_CLAWBENCH); // 切到该监控绑定的桌宠
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
            PetRegistry.applyTargetPet(this, PetRegistry.TARGET_CUSTOM_API); // 切到该监控绑定的桌宠
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
                    petChatCancelled = false;
                    petChatSessionId = null;
                    setPetChatRunning(true);
                    try {
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

                    petChatSessionId = sessionId;
                    if (petChatCancelled) return;
                    mainHandler.post(() -> showPetBubble("💭 Pi-Web Agent 正在思考中..."));

                    // 2. 监听 SSE 事件流实时更新桌宠动画与气泡
                    URL sseUrl = new URL("http://127.0.0.1:" + piwebPort + "/api/agent/" + sessionId + "/events");
                    HttpURLConnection sseConn = (HttpURLConnection) sseUrl.openConnection();
                    petChatConn = sseConn;
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
                    } catch (Throwable readErr) {
                        if (!petChatCancelled) throw readErr;
                    } finally {
                        sseConn.disconnect();
                        petChatConn = null;
                    }

                    if (petChatCancelled) return;
                    mainHandler.post(() -> {
                        if (floatingPetView != null) floatingPetView.playOneShot("jumping");
                        if (answer.length() > 0) {
                            showPetBubble(answer.toString().trim());
                        } else {
                            showPetBubble("🎉 Pi-Web Agent 已执行完毕！随时待命");
                        }
                    });
                    return;
                    } finally {
                        if (!petChatCancelled) setPetChatRunning(false);
                    }
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
            } else if (!ProotManager.isRootfsInstalled(this)) {
                // 从头启动且尚未安装根系统：直接开启部署
                triggerFullDeploy();
            } else {
                // 未运行但根系统已就绪，启动服务
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

        View btnLaunchHelpGuide = findViewById(R.id.btnLaunchHelpGuide);
        if (btnLaunchHelpGuide != null) {
            btnLaunchHelpGuide.setOnClickListener(v -> new com.xm486.pimet.ui.HelpGuideDialog(this).show());
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
        boolean running = PetOverlayService.isRunning();
        if (!running && PetRegistry.isPetEnabled(this)) {
            PetRegistry.setPetEnabled(this, false);
        }
        boolean enabled = running;
        ThemeManager.ThemePalette palette = ThemeManager.getEffectivePalette(this);
        if (launchPetStatusTv != null) {
            launchPetStatusTv.setText(enabled ? "运行中" : "已关闭");
            launchPetStatusTv.setTextColor(enabled ? (palette.isDark ? 0xFF3FB950 : 0xFF1A7F37) : palette.textMuted);
        }
        if (launchPetStateBadge != null) {
            launchPetStateBadge.setText(enabled ? "已开" : "已关");
            launchPetStateBadge.setBackground(enabled
                    ? ThemeManager.createBadgeDrawable(this, palette, ThemeManager.RADIUS_SMALL)
                    : ThemeManager.createSecondaryButtonDrawable(this, palette, ThemeManager.RADIUS_SMALL));
            launchPetStateBadge.setTextColor(enabled ? (palette.isDark ? 0xFF3FB950 : 0xFF1A7F37) : palette.textMuted);
        }
        if (launchPetIconTv != null) {
            launchPetIconTv.setText(enabled ? "🐾" : "💤");
        }
    }

    private void togglePetFromLaunch() {
        boolean currentlyActive = PetOverlayService.isRunning();
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
            if (launchConsoleScroll != null) {
                launchConsoleScroll.post(() -> launchConsoleScroll.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    private void showLaunchProgress(String label, int percent) {
        mainHandler.post(() -> {
            if (launchProgressContainer != null) {
                launchProgressContainer.setVisibility(View.VISIBLE);
            }
            if (launchProgressLabelTv != null && label != null) {
                launchProgressLabelTv.setText(label);
            }
            if (launchProgressBar != null) {
                if (percent >= 0) {
                    launchProgressBar.setIndeterminate(false);
                    launchProgressBar.setProgress(Math.min(100, Math.max(0, percent)));
                } else {
                    launchProgressBar.setIndeterminate(true);
                }
            }
            if (launchProgressPercentTv != null) {
                if (percent >= 0) {
                    launchProgressPercentTv.setVisibility(View.VISIBLE);
                    launchProgressPercentTv.setText(percent + "%");
                } else {
                    launchProgressPercentTv.setVisibility(View.GONE);
                }
            }
        });
    }

    private void hideLaunchProgress() {
        mainHandler.post(() -> {
            if (launchProgressContainer != null) {
                launchProgressContainer.setVisibility(View.GONE);
            }
            if (launchProgressBar != null) {
                launchProgressBar.setProgress(0);
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
                new com.xm486.pimet.ui.Icons.CompatDialogBuilder(this)
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
        input.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12));

        new com.xm486.pimet.ui.Icons.CompatDialogBuilder(this)
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

        ThemeManager.ThemePalette palette = ThemeManager.getEffectivePalette(this);
        if (alive) {
            PiMetService.start(this);
            launchStatusDot.setBackgroundResource(R.drawable.bg_status_dot_green);
            launchStateTv.setText("服务运行中");
            launchStateTv.setTextColor(palette.isDark ? 0xFF3FB950 : 0xFF1A7F37);
            launchSubtitleTv.setText("Pi-Web 守护进程正常监听中，可进入工作台或外部浏览器使用");
            btnLaunchMain.setText("🌐 进入 Pi-Web 工作台");
            btnLaunchMain.setBackground(ThemeManager.createPrimaryButtonDrawable(this, palette, 10f));
            btnLaunchMain.setTextColor(palette.accentContrast);

            if (btnLaunchStop != null) {
                btnLaunchStop.setEnabled(true);
                btnLaunchStop.setAlpha(1.0f);
                btnLaunchStop.setBackground(ThemeManager.createDangerButtonDrawable(this, palette, 8f));
                if (btnLaunchStop instanceof TextView) {
                    ((TextView) btnLaunchStop).setTextColor(ThemeManager.statusDangerColor(palette));
                }
            }
            if (btnLaunchRestart != null) {
                btnLaunchRestart.setEnabled(true);
                btnLaunchRestart.setAlpha(1.0f);
                btnLaunchRestart.setBackground(ThemeManager.createSecondaryButtonDrawable(this, palette, 8f));
                if (btnLaunchRestart instanceof TextView) {
                    ((TextView) btnLaunchRestart).setTextColor(palette.text);
                }
            }
        } else {
            launchStatusDot.setBackgroundResource(R.drawable.bg_status_dot_gray);
            launchStateTv.setText("服务已停止");
            launchStateTv.setTextColor(palette.textMuted);
            launchSubtitleTv.setText("内置 PRoot 容器环境已就绪，点击下方按钮启动 Pi-Web 守护服务");
            btnLaunchMain.setText("🚀 启动 Pi-Web 服务");
            btnLaunchMain.setBackground(ThemeManager.createSuccessButtonDrawable(this, palette, 10f));
            btnLaunchMain.setTextColor(0xFFFFFFFF);

            if (btnLaunchStop != null) {
                btnLaunchStop.setEnabled(false);
                btnLaunchStop.setAlpha(0.35f);
                btnLaunchStop.setBackground(ThemeManager.createSecondaryButtonDrawable(this, palette, 8f));
                if (btnLaunchStop instanceof TextView) {
                    ((TextView) btnLaunchStop).setTextColor(palette.textDim);
                }
            }
            if (btnLaunchRestart != null) {
                btnLaunchRestart.setEnabled(false);
                btnLaunchRestart.setAlpha(0.35f);
                btnLaunchRestart.setBackground(ThemeManager.createSecondaryButtonDrawable(this, palette, 8f));
                if (btnLaunchRestart instanceof TextView) {
                    ((TextView) btnLaunchRestart).setTextColor(palette.textDim);
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
        showLaunchProgress("正在拉起服务...", -1);
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
                    showLaunchProgress(message, percent);
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
                hideLaunchProgress();
                isPiWebAlive = true;
                PiMetService.start(MainActivity.this);
                updateLaunchStatusUI(true);
                Toast.makeText(MainActivity.this, "🎉 Pi-Web 服务已成功启动！", Toast.LENGTH_SHORT).show();
                refreshLaunchLog();

                // 核心预加载：服务一就绪，后台立刻触发静默渲染，用户点击进入工作台秒开呈现
                warmThenLoadPiWeb(false);
            }

            @Override
            public void onError(String error) {
                isPiWebActionInProgress = false;
                hideLaunchProgress();
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
        showLaunchProgress("正在重启服务...", -1);
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
                    showLaunchProgress(message, percent);
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
                hideLaunchProgress();
                isPiWebAlive = true;
                PiMetService.start(MainActivity.this);
                updateLaunchStatusUI(true);
                Toast.makeText(MainActivity.this, "🎉 Pi-Web 服务已成功重启！", Toast.LENGTH_SHORT).show();
                refreshLaunchLog();

                // 核心预加载：服务就绪瞬间立即静默加载 WebView，进入工作台 0 秒呈现
                warmThenLoadPiWeb(false);
            }

            @Override
            public void onError(String error) {
                isPiWebActionInProgress = false;
                hideLaunchProgress();
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
        showLaunchProgress("正在停止服务...", -1);
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
            hideLaunchProgress();
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
        startDeployPipeline();
    }
    private void startDeployPipeline() {
        if (isDeploying) return;
        isDeploying = true;
        showLaunchProgress("准备执行一键部署流水线...", -1);
        updateLaunchStatusUI(false);

        appendLaunchLog("\u001B[33m🚀 开始执行一键部署流水线...\u001B[0m\n");

        if (!ProotManager.isRootfsInstalled(this)) {
            appendLaunchLog("\u001B[36m• 正在从镜像源提取 Linux 根文件系统...\u001B[0m\n");
            ProotManager.installRootfs(this, new ProotManager.InstallCallback() {
                @Override
                public void onProgress(String message, int percent) {
                    mainHandler.post(() -> {
                        showLaunchProgress(message, percent);
                        appendLaunchLog("• " + message + " " + percent + "%\n");
                    });
                }

                @Override
                public void onSuccess() {
                    mainHandler.post(() -> {
                        appendLaunchLog("\u001B[32m✔ Linux 根系统部署完成！\u001B[0m\n");
                        isDeploying = false;
                        hideLaunchProgress();
                        startPiWebService();
                    });
                }

                @Override
                public void onError(String error) {
                    mainHandler.post(() -> {
                        isDeploying = false;
                        hideLaunchProgress();
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
        // 双指布局缩放（重排适配屏宽，25%–300%），与悬浮球缩放按钮共用同一缩放值
        com.xm486.pimet.ui.WebPinchZoom.attach(piWebWebView, (pct, committed) -> {
            if (btnFloatZoom != null) btnFloatZoom.setText(pct + "%");
        });
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setAllowFileAccessFromFileURLs(true);
        webSettings.setAllowUniversalAccessFromFileURLs(true);

        // 初始化网页缩放
        int savedZoom = com.xm486.pimet.ui.WebPinchZoom.getZoom(this);
        webSettings.setTextZoom(100);
        if (btnFloatZoom != null) btnFloatZoom.setText(savedZoom + "%");

        // 注册通用网页翻译原生桥接 (支持任意网页、多引擎、绕过CORS)
        TranslationBridge transBridge = new TranslationBridge(this, piWebWebView);
        transBridge.setStateListener((isTranslated, doneCount, totalCount) -> {
            isWebTranslated = isTranslated;
            ThemeManager.ThemePalette palette = ThemeManager.getEffectivePalette(MainActivity.this);
            if (btnFloatTranslate != null) {
                btnFloatTranslate.setText(isTranslated ? "原" : "译");
                btnFloatTranslate.setTextColor(isTranslated ? palette.accent : palette.text);
            }
        });
        piWebWebView.addJavascriptInterface(transBridge, "PiMetTranslator");
        // Pi-Web 设置里切换主题 → PiMet 原生界面跟随
        piWebWebView.addJavascriptInterface(new ThemeManager.WebThemeBridge(this, () -> {
            updateThemeUIState();
            applyAppTheme();
        }), "PiMetTheme");

        piWebWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showPiWebOffline(true);
                }
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                super.onPageCommitVisible(view, url);
                com.xm486.pimet.ui.WebPinchZoom.apply(view, url);
                com.xm486.pimet.ui.PiWebCompat.apply(view, url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                ThemeManager.syncThemeToWebView(view, MainActivity.this);
                com.xm486.pimet.ui.WebPinchZoom.apply(view, url);
                com.xm486.pimet.ui.PiWebCompat.apply(view, url);
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

                    // 自愈：页面能滚动/缩放却点不动 = 前端 JS 没有激活（常见于 Pi-Web 升级后 Service Worker 仍提供旧脚本）
                    schedulePiWebHydrationCheck(view, url);

                    // 深度自愈：检测页面是否由于 Node.js 启动延迟被 Service Worker 错误截获为 offline.html
                    view.evaluateJavascript(
                        "(function() { " +
                        "  var h1 = document.querySelector('h1'); " +
                        "  var t = (document.title + ' ' + (h1 ? h1.innerText : '')).toLowerCase(); " +
                        "  return t.indexOf('offline') !== -1; " +
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
            public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
                try {
                    if (cm != null && cm.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                        String m = cm.message() == null ? "" : cm.message();
                        if (m.contains("ChunkLoadError") || m.contains("Loading chunk")
                                || m.contains("Failed to fetch dynamically imported module")
                                || m.contains("Failed to load chunk")) {
                            healPiWebPage("前端脚本加载失败");
                        }
                    }
                } catch (Throwable ignored) {}
                return super.onConsoleMessage(cm);
            }

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
                if (petChatRunning) {
                    interruptPetChat();
                    return;
                }
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
        btnFloatTranslate.setOnClickListener(v -> {
            toggleWebTranslation();
        });
        btnFloatTranslate.setOnLongClickListener(v -> {
            WebTranslationDialog.show(MainActivity.this, piWebWebView);
            return true;
        });
        View btnFloatApps = findViewById(R.id.btnFloatApps);
        if (btnFloatApps != null) {
            btnFloatApps.setOnClickListener(v -> {
                floatingMenuVertical.setVisibility(View.GONE);
                com.xm486.pimet.ui.FloatingApps.toggle(MainActivity.this);
            });
        }
        // Pi 应用页面里的「让 Pi 帮我…」：填进工作台输入框并发送（手机端发送快捷键是 Ctrl+Enter）
        com.xm486.pimet.ui.FloatingApps.setPiBridge((text, send) -> {
            if (piWebWebView == null) return;
            String js = "(function(t,send){var el=document.querySelector('textarea');if(!el)return 'noinput';"
                    + "var setter=Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set;"
                    + "setter.call(el,t);el.dispatchEvent(new Event('input',{bubbles:true}));el.focus();"
                    + "if(send){setTimeout(function(){el.dispatchEvent(new KeyboardEvent('keydown',"
                    + "{key:'Enter',code:'Enter',keyCode:13,which:13,ctrlKey:true,bubbles:true,cancelable:true}));},120);}"
                    + "return 'ok';})(" + org.json.JSONObject.quote(text) + "," + send + ")";
            piWebWebView.evaluateJavascript(js, r -> {
                if (r != null && r.contains("noinput")) {
                    Toast.makeText(MainActivity.this, "工作台还没打开对话输入框", Toast.LENGTH_SHORT).show();
                }
            });
        });
        new Thread(() -> com.xm486.pimet.ui.FloatingApps.ensureScaffold(MainActivity.this), "pimet-apps-scaffold").start();
        btnFloatTheme.setOnClickListener(v -> {
            cycleThemeQuick();
        });
        btnFloatBrowser.setOnClickListener(v -> {
            floatingMenuVertical.setVisibility(View.GONE);
            openExternalBrowser();
        });
        View btnFloatTerminal = findViewById(R.id.btnFloatTerminal);
        if (btnFloatTerminal != null) {
            btnFloatTerminal.setOnClickListener(v -> {
                floatingMenuVertical.setVisibility(View.GONE);
                com.xm486.pimet.terminal.FloatingTerminal.toggle(MainActivity.this);
            });
        }
        piWebWakeBtn.setOnClickListener(v -> startPiWebService());
    }

    private void toggleFloatingMenu() {
        if (floatingMenuVertical.getVisibility() == View.VISIBLE) {
            floatingMenuVertical.setVisibility(View.GONE);
        } else {
            floatingMenuVertical.setVisibility(View.VISIBLE);
        }
    }

    private boolean isWebTranslated = false;

    private void toggleWebTranslation() {
        if (piWebWebView == null) return;
        WebTranslationManager.toggleTranslation(piWebWebView, this);
    }

    private void showZoomDialog() {
        int currentZoom = com.xm486.pimet.ui.WebPinchZoom.getZoom(this);
        ThemeManager.ThemePalette palette = ThemeManager.getEffectivePalette(this);
        final int minZ = com.xm486.pimet.ui.WebPinchZoom.MIN_ZOOM;
        final int maxZ = com.xm486.pimet.ui.WebPinchZoom.MAX_ZOOM;
        final int onPrimary = palette.isDark ? 0xFF0D1117 : 0xFFFFFFFF;

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(24), dpToPx(16), dpToPx(24), dpToPx(16));
        layout.setBackground(ThemeManager.createCardDrawable(this, palette, 14f));

        TextView title = new TextView(this);
        title.setText("工作台显示缩放");
        title.setTextSize(16f);
        title.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
        title.setTextColor(palette.text);
        layout.addView(title);

        TextView tvVal = new TextView(this);
        tvVal.setText("当前 " + currentZoom + "% · 也可双指缩放");
        tvVal.setTextSize(13f);
        tvVal.setTextColor(palette.accent);
        tvVal.setPadding(0, dpToPx(8), 0, dpToPx(12));
        layout.addView(tvVal);

        // 固定预设（100% 即重置）
        LinearLayout presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        presetRow.setGravity(Gravity.CENTER);

        final int[] presets = {50, 75, 100, 125, 150};
        final List<TextView> presetViews = new ArrayList<>();
        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            int z = com.xm486.pimet.ui.WebPinchZoom.getZoom(MainActivity.this);
            tvVal.setText("当前 " + z + "% · 也可双指缩放");
            for (int i = 0; i < presets.length; i++) {
                boolean active = presets[i] == z;
                presetViews.get(i).setTextColor(active ? onPrimary : palette.textMuted);
                presetViews.get(i).setBackground(active
                        ? ThemeManager.createPrimaryButtonDrawable(MainActivity.this, palette, 6f)
                        : ThemeManager.createSecondaryButtonDrawable(MainActivity.this, palette, 6f));
            }
        };
        for (int i = 0; i < presets.length; i++) {
            final int presetVal = presets[i];
            TextView pBtn = new TextView(this);
            pBtn.setText(presetVal + "%");
            pBtn.setTextSize(12f);
            pBtn.setSingleLine(true);
            pBtn.setGravity(Gravity.CENTER);
            pBtn.setPadding(0, dpToPx(8), 0, dpToPx(8));
            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) pLp.leftMargin = dpToPx(6);
            pBtn.setLayoutParams(pLp);
            pBtn.setOnClickListener(v -> {
                applyWebZoom(presetVal);
                refresh[0].run();
            });
            presetViews.add(pBtn);
            presetRow.addView(pBtn);
        }
        layout.addView(presetRow);

        // 自定义数值：输入百分比后点「应用」
        LinearLayout customRow = new LinearLayout(this);
        customRow.setOrientation(LinearLayout.HORIZONTAL);
        customRow.setGravity(Gravity.CENTER_VERTICAL);
        customRow.setPadding(0, dpToPx(12), 0, 0);

        android.widget.EditText etCustom = new android.widget.EditText(this);
        etCustom.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etCustom.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(3)});
        etCustom.setSingleLine(true);
        etCustom.setHint("自定义 " + minZ + "–" + maxZ);
        etCustom.setTextSize(13f);
        etCustom.setTextColor(palette.text);
        etCustom.setHintTextColor(palette.textDim);
        etCustom.setBackground(ThemeManager.createSecondaryButtonDrawable(this, palette, 8f));
        etCustom.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));
        etCustom.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        customRow.addView(etCustom, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView pct = new TextView(this);
        pct.setText("%");
        pct.setTextSize(13f);
        pct.setTextColor(palette.textMuted);
        pct.setPadding(dpToPx(6), 0, dpToPx(8), 0);
        customRow.addView(pct);

        TextView btnApply = new TextView(this);
        btnApply.setText("应用");
        btnApply.setTextSize(12f);
        btnApply.setSingleLine(true);
        btnApply.setTextColor(palette.accent);
        btnApply.setBackground(ThemeManager.createSecondaryButtonDrawable(this, palette, 8f));
        btnApply.setPadding(dpToPx(14), dpToPx(8), dpToPx(14), dpToPx(8));
        customRow.addView(btnApply);
        layout.addView(customRow);

        final Runnable applyCustom = () -> {
            String txt = etCustom.getText() == null ? "" : etCustom.getText().toString().trim();
            int v;
            try {
                v = Integer.parseInt(txt);
            } catch (NumberFormatException e) {
                Toast.makeText(MainActivity.this, "请输入 " + minZ + "–" + maxZ + " 之间的数字", Toast.LENGTH_SHORT).show();
                return;
            }
            int z = com.xm486.pimet.ui.WebPinchZoom.clamp(v);
            if (z != v) {
                Toast.makeText(MainActivity.this, "已限制在 " + minZ + "–" + maxZ + "%，应用 " + z + "%", Toast.LENGTH_SHORT).show();
            }
            applyWebZoom(z);
            etCustom.setText(String.valueOf(z));
            etCustom.setSelection(etCustom.getText().length());
            refresh[0].run();
        };
        btnApply.setOnClickListener(v -> applyCustom.run());
        etCustom.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                applyCustom.run();
                return true;
            }
            return false;
        });

        refresh[0].run();

        // 完成按键
        TextView btnDone = new TextView(this);
        btnDone.setText("完成");
        btnDone.setTextSize(13f);
        btnDone.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
        btnDone.setGravity(Gravity.CENTER);
        btnDone.setTextColor(onPrimary);
        btnDone.setBackground(ThemeManager.createPrimaryButtonDrawable(this, palette, 8f));
        btnDone.setPadding(0, dpToPx(12), 0, dpToPx(12));
        LinearLayout.LayoutParams doneLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        doneLp.topMargin = dpToPx(16);
        btnDone.setLayoutParams(doneLp);
        layout.addView(btnDone);

        AlertDialog dialog = new com.xm486.pimet.ui.Icons.CompatDialogBuilder(this)
                .setView(layout)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        btnDone.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void applyWebZoom(int zoom) {
        int z = com.xm486.pimet.ui.WebPinchZoom.clamp(zoom);
        if (piWebWebView != null) {
            z = com.xm486.pimet.ui.WebPinchZoom.setZoom(piWebWebView, z);
        } else {
            PiMetConfig.setWebZoom(this, z);
        }
        if (btnFloatZoom != null) {
            btnFloatZoom.setText(z + "%");
        }
    }

    private void cycleThemeQuick() {
        java.util.List<String> all = ThemeManager.getAllThemes();
        int idx = all.indexOf(ThemeManager.getThemePreference(this));
        selectTheme(all.get((idx + 1) % all.size()));
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
        // setSystemUiVisibility 会整体覆盖标志位，浅色主题的深色状态栏/导航栏图标需要补回
        ThemeManager.applyWindowTheme(this, ThemeManager.getEffectivePalette(this));
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void updatePiWebDisplay() {
        int port = PiMetConfig.getWebPort(this);
        String url = getPiWebUrl();

        // 关键秒开优化：若服务已知在运行，或者 WebView 已在目标地址，主线程同步直接切出工作台，毫秒级即现！
        if (isPiWebAlive || (piWebWebView != null && piWebWebView.getUrl() != null && piWebWebView.getUrl().startsWith("http://127.0.0.1:" + port))) {
            if (piWebWebView != null && (piWebWebView.getUrl() == null || !piWebWebView.getUrl().startsWith("http://127.0.0.1:" + port))) {
                warmThenLoadPiWeb(true);
            } else if (!isPiWebLoading) {
                showPiWebOffline(false);
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
                    if (piWebWebView != null && (piWebWebView.getUrl() == null || !piWebWebView.getUrl().startsWith("http://127.0.0.1:" + port))) {
                        warmThenLoadPiWeb(true);
                    } else if (!isPiWebLoading) {
                        showPiWebOffline(false);
                    }
                    // 目标网页已正确加载，切勿调用 reload()，以完整保留 WebView 内存状态
                } else if (isPiWebActionInProgress || isOfflineRecovering) {
                    // 服务正在启动：显示「正在加载」并等首页就绪，而不是「未在运行」
                    warmThenLoadPiWeb(true);
                } else {
                    showPiWebOffline(true);
                }
            });
        }).start();
    }

    private long lastPiWebHealAt = 0;

    private static final String PIWEB_HYDRATION_JS = "(function(){try{" +
            "function hasReact(n){if(!n)return false;for(var k in n){if(k.indexOf('__react')===0)return true;}return false;}" +
            "var tt=((document.title||'')+' '+((document.querySelector('h1')||{}).innerText||'')).toLowerCase();" +
            "if(tt.indexOf('offline')!==-1)return 'offline';" +
            "if(hasReact(document)||hasReact(document.documentElement)||hasReact(document.body))return 'ok';" +
            "var all=document.querySelectorAll('button,a,input,textarea,[role=button]');" +
            "for(var j=0;j<Math.min(all.length,40);j++){if(hasReact(all[j]))return 'ok';}" +
            "return all.length?'dead':'empty';}catch(e){return 'err';}})()";

    /** 页面加载完 6 秒后检查前端是否已激活（React 已挂载）；未激活则清缓存重载 */
    private void schedulePiWebHydrationCheck(WebView view, String url) {
        if (view == null || url == null) return;
        view.postDelayed(() -> {
            String cur = view.getUrl();
            if (cur == null || !cur.equals(url)) return;
            view.evaluateJavascript(PIWEB_HYDRATION_JS, r -> {
                if ("\"offline\"".equals(r)) handlePiWebOfflineDetected();
                else if ("\"dead\"".equals(r)) healPiWebPage("页面按钮未激活");
            });
        }, 6000);
    }

    /** 清除 WebView 缓存、注销 Service Worker 与 Cache Storage 后重新加载（3 分钟内最多一次，防止循环） */
    private void healPiWebPage(String reason) {
        // 服务还没就绪时页面本来就不可用：交给离线自动重连，不清缓存（否则下次加载更慢）
        final int port = PiMetConfig.getWebPort(this);
        new Thread(() -> {
            boolean ready = ProotManager.isPiWebHttpReady(port);
            mainHandler.post(() -> {
                if (!ready) handlePiWebOfflineDetected();
                else doHealPiWebPage(reason);
            });
        }, "piweb-heal-check").start();
    }

    private void doHealPiWebPage(String reason) {
        runOnUiThread(() -> {
            long now = System.currentTimeMillis();
            if (now - lastPiWebHealAt < 180000L || piWebWebView == null) return;
            lastPiWebHealAt = now;
            Log.w("PiMet", "healPiWebPage: " + reason);
            try {
                piWebWebView.clearCache(true);
            } catch (Throwable ignored) {}
            Toast.makeText(MainActivity.this, "检测到网页" + reason + "，已清理缓存并重新加载", Toast.LENGTH_SHORT).show();
            piWebWebView.evaluateJavascript("(function(){if(window.__pimetHealing)return;window.__pimetHealing=1;var p=[];" +
                    "try{if(navigator.serviceWorker)p.push(navigator.serviceWorker.getRegistrations().then(function(rs){" +
                    "return Promise.all(rs.map(function(r){return r.unregister();}));}));" +
                    "if(window.caches)p.push(caches.keys().then(function(ks){return Promise.all(ks.map(function(k){return caches.delete(k);}));}));" +
                    "}catch(e){}var done=false;function go(){if(done)return;done=true;location.reload();}" +
                    "Promise.all(p).then(go,go);setTimeout(go,2500);})()", null);
        });
    }

    private void handlePiWebOfflineDetected() {
        // 离线页是 Service Worker 在首页 8 秒没响应时给的兜底：盖上「正在加载」，预热首页后自动重载
        warmThenLoadPiWeb(true);
    }

    private volatile boolean isPiWebLoading = false;
    private TextView piWebOfflineTitleTv;

    /** 后台等首页能出首字节再加载 WebView；showUi=true 时期间显示原生「正在加载」而不是离线页 */
    private void warmThenLoadPiWeb(boolean showUi) {
        if (showUi) showPiWebLoading();
        if (isOfflineRecovering) return;
        isOfflineRecovering = true;
        final int port = PiMetConfig.getWebPort(this);
        new Thread(() -> {
            boolean ok = false;
            try {
                long deadline = System.currentTimeMillis() + 150000L;
                while (System.currentTimeMillis() < deadline && !isFinishing() && !isDestroyed()) {
                    if (ProotManager.warmPiWebPage(port)) { ok = true; break; }
                    try { Thread.sleep(700); } catch (InterruptedException ignored) {}
                }
            } finally {
                isOfflineRecovering = false;
            }
            final boolean ready = ok;
            mainHandler.post(() -> {
                if (piWebWebView == null) return;
                if (ready) {
                    String url = getPiWebUrl();
                    String cur = piWebWebView.getUrl();
                    if (cur == null || !cur.startsWith("http://127.0.0.1:" + port)) piWebWebView.loadUrl(url);
                    else piWebWebView.reload();
                    if (isPiWebLoading) showPiWebOffline(false);
                } else if (isPiWebLoading) {
                    showPiWebOffline(true);
                }
            });
        }, "piweb-warmup").start();
    }

    private void showPiWebLoading() {
        if (piWebOfflineCard == null || piWebWebView == null) return;
        isPiWebLoading = true;
        piWebOfflineCard.setVisibility(View.VISIBLE);
        piWebWebView.setVisibility(View.INVISIBLE);
        if (piWebOfflineTitleTv == null && piWebOfflineSubTv != null
                && piWebOfflineSubTv.getParent() instanceof android.view.ViewGroup) {
            android.view.ViewGroup pg = (android.view.ViewGroup) piWebOfflineSubTv.getParent();
            int idx = pg.indexOfChild(piWebOfflineSubTv);
            if (idx > 0 && pg.getChildAt(idx - 1) instanceof TextView) piWebOfflineTitleTv = (TextView) pg.getChildAt(idx - 1);
        }
        if (piWebOfflineTitleTv != null) piWebOfflineTitleTv.setText("Pi-Web 正在加载…");
        if (piWebOfflineSubTv != null) piWebOfflineSubTv.setText("服务刚启动，首屏准备好后会自动进入");
        if (piWebWakeBtn != null) piWebWakeBtn.setVisibility(View.GONE);
    }

    private void showPiWebOffline(boolean offline) {
        isPiWebLoading = false;
        if (piWebOfflineTitleTv != null) piWebOfflineTitleTv.setText("Pi-Web 服务未在运行");
        if (piWebWakeBtn != null) piWebWakeBtn.setVisibility(View.VISIBLE);
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
                        new com.xm486.pimet.ui.Icons.CompatDialogBuilder(this)
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
            new com.xm486.pimet.ui.Icons.CompatDialogBuilder(this)
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
                new com.xm486.pimet.ui.Icons.CompatDialogBuilder(this)
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
        new com.xm486.pimet.ui.Icons.CompatDialogBuilder(this)
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
                            new com.xm486.pimet.ui.Icons.CompatDialogBuilder(this)
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
                            new com.xm486.pimet.ui.Icons.CompatDialogBuilder(this)
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

                        new com.xm486.pimet.ui.Icons.CompatDialogBuilder(this)
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
                        new com.xm486.pimet.ui.Icons.CompatDialogBuilder(this)
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
        tvStage.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
        layout.addView(tvStage);

        ProgressBar pBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pBar.setMax(100);
        pBar.setProgress(5);
        LinearLayout.LayoutParams pbParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(6));
        pbParams.topMargin = dpToPx(12);
        pbParams.bottomMargin = dpToPx(8);
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
        logScrollView.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));

        TextView tvLiveLog = new TextView(this);
        tvLiveLog.setTextColor(0xFF7EE787);
        tvLiveLog.setTextSize(11f);
        tvLiveLog.setTypeface(Typeface.MONOSPACE);
        tvLiveLog.setText("[info] 启动更新流水线...\n");
        logScrollView.addView(tvLiveLog);
        layout.addView(logScrollView);

        AlertDialog dialog = new com.xm486.pimet.ui.Icons.CompatDialogBuilder(this)
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
                // 工作台 WebView 里还开着旧版页面（单页应用不会自己换代码）：清缓存并重新加载，不论当前是否在工作台页
                if (piWebWebView != null) {
                    try {
                        piWebWebView.clearCache(true);
                        piWebWebView.reload();
                    } catch (Throwable ignored) {}
                }
                new com.xm486.pimet.ui.Icons.CompatDialogBuilder(this)
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
                new com.xm486.pimet.ui.Icons.CompatDialogBuilder(this)
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
            case SETTINGS_CAT_ABOUT:
                title = "✨ 关于与版本更新";
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
            case SETTINGS_CAT_THEME:
                title = "🎨 界面外观与设计风格";
                break;
        }

        if (tvSettingsSubWindowTitle != null) {
            tvSettingsSubWindowTitle.setText(title);
        }

        updateMenuItemState(menuItemPorts, menuTitlePorts, menuArrowPorts, category == SETTINGS_CAT_PORTS);
        if (menuItemUpdate != null) {
            updateMenuItemState(menuItemUpdate, menuTitleUpdate, menuArrowUpdate, category == SETTINGS_CAT_UPDATE);
        }
        updateMenuItemState(menuItemPet, menuTitlePet, menuArrowPet, category == SETTINGS_CAT_PET);
        updateMenuItemState(menuItemPrivileges, menuTitlePrivileges, menuArrowPrivileges, category == SETTINGS_CAT_PRIVILEGES);
        updateMenuItemState(menuItemStorage, menuTitleStorage, menuArrowStorage, category == SETTINGS_CAT_STORAGE);
        updateMenuItemState(menuItemAbout, menuTitleAbout, menuArrowAbout, category == SETTINGS_CAT_ABOUT || category == SETTINGS_CAT_UPDATE);
        updateMenuItemState(menuItemTheme, menuTitleTheme, menuArrowTheme, category == SETTINGS_CAT_THEME);

        applySettingsCardsVisibility();

        if (category == SETTINGS_CAT_THEME) {
            // 首帧即按当前生效配色与选中项渲染外观选项，杜绝首次打开时的深色占位闪烁
            updateThemeUIState();
        }

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
        updateMenuItemState(menuItemTheme, menuTitleTheme, menuArrowTheme, category == SETTINGS_CAT_THEME);

        applySettingsCardsVisibility();
    }

    private void updateMenuItemState(View item, TextView title, TextView arrow, boolean isSelected) {
        if (item == null) return;
        ThemeManager.ThemePalette palette = ThemeManager.getEffectivePalette(this);
        if (isSelected) {
            item.setBackground(ThemeManager.createSelectedOptionDrawable(this, palette, 10f));
            if (title != null) title.setTextColor(palette.accent);
            if (arrow != null) {
                arrow.setTextColor(palette.accent);
                arrow.setText("进入 ›");
            }
        } else {
            item.setBackground(ThemeManager.createSecondaryButtonDrawable(this, palette, 10f));
            if (title != null) title.setTextColor(palette.text);
            if (arrow != null) {
                arrow.setTextColor(palette.textMuted);
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
        if (cardSettingsRootfsMirror != null) {
            cardSettingsRootfsMirror.setVisibility(category == SETTINGS_CAT_PORTS ? View.VISIBLE : View.GONE);
        }

        // 更新中心与关于卡片合并展示
        if (cardSettingsAbout != null) {
            cardSettingsAbout.setVisibility((category == SETTINGS_CAT_ABOUT || category == SETTINGS_CAT_UPDATE) ? View.VISIBLE : View.GONE);
        }
        if (cardSettingsUpdate != null) {
            cardSettingsUpdate.setVisibility(View.VISIBLE);
        }
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

        // 外观与主题卡片
        if (cardSettingsTheme != null) {
            cardSettingsTheme.setVisibility(category == SETTINGS_CAT_THEME ? View.VISIBLE : View.GONE);
        }
    }

    private void checkBatteryOptimizationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    new com.xm486.pimet.ui.Icons.CompatDialogBuilder(this)
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

        new com.xm486.pimet.ui.Icons.CompatDialogBuilder(this)
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

    private final Map<String, String> mirrorSpeedCache = new HashMap<>();

    private void initRootfsMirrorSettings() {
        if (btnTestAllRootfsMirrors != null) {
            btnTestAllRootfsMirrors.setOnClickListener(v -> testAllRootfsMirrors());
        }
        renderRootfsMirrorOptions();
    }

    private void renderRootfsMirrorOptions() {
        if (rootfsMirrorOptionsContainer == null) return;
        rootfsMirrorOptionsContainer.removeAllViews();
        ThemeManager.ThemePalette palette = ThemeManager.getEffectivePalette(this);
        String currentMirror = PiMetConfig.getRootfsUrl(this);

        // 选项 0: 自动竞速优选 (默认)
        addRootfsMirrorOptionRow("auto", "⚡ 自动优选 (每次下载前并发真测速)", "按真实下载吞吐量智能竞速 + 动态熔断换线 [推荐]", currentMirror.equals("auto"), palette);

        // 镜像选项列表
        for (int i = 0; i < ProotManager.ROOTFS_MIRROR_PREFIXES.length; i++) {
            String pfx = ProotManager.ROOTFS_MIRROR_PREFIXES[i];
            String name = ProotManager.ROOTFS_MIRROR_NAMES[i];
            addRootfsMirrorOptionRow(pfx, name, pfx, currentMirror.equals(pfx), palette);
        }
    }

    private void addRootfsMirrorOptionRow(String mirrorKey, String title, String subtitle, boolean isSelected, ThemeManager.ThemePalette palette) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(isSelected ? ThemeManager.createSelectedOptionDrawable(this, palette, 8f) : ThemeManager.createSecondaryButtonDrawable(this, palette, 8f));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dpToPx(8);
        row.setLayoutParams(lp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView titleTv = new TextView(this);
        titleTv.setText(title);
        titleTv.setTextColor(isSelected ? palette.accent : palette.text);
        titleTv.setTextSize(12f);
        titleTv.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
        textCol.addView(titleTv);

        TextView subTv = new TextView(this);
        subTv.setText(subtitle);
        subTv.setTextColor(palette.textMuted);
        subTv.setTextSize(11f);
        textCol.addView(subTv);

        row.addView(textCol);

        // 测速结果指示器
        String speedStr = mirrorSpeedCache.get(mirrorKey);
        if (speedStr != null) {
            TextView speedBadge = new TextView(this);
            speedBadge.setText(speedStr);
            speedBadge.setTextColor(speedStr.contains("FAIL") ? 0xFFF85149 : palette.accent);
            speedBadge.setTextSize(11f);
            speedBadge.setPadding(dpToPx(8), 0, dpToPx(8), 0);
            row.addView(speedBadge);
        }

        TextView checkTv = new TextView(this);
        checkTv.setText(isSelected ? "✔" : "");
        checkTv.setTextColor(palette.accent);
        checkTv.setTextSize(16f);
        checkTv.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
        row.addView(checkTv);

        row.setOnClickListener(v -> {
            PiMetConfig.setRootfsUrl(this, mirrorKey);
            renderRootfsMirrorOptions();
            Toast.makeText(this, "已设置容器下载源: " + title, Toast.LENGTH_SHORT).show();
        });

        rootfsMirrorOptionsContainer.addView(row);
    }

    private void testAllRootfsMirrors() {
        if (btnTestAllRootfsMirrors != null) {
            btnTestAllRootfsMirrors.setEnabled(false);
            if (btnTestAllRootfsMirrors instanceof TextView) {
                ((TextView) btnTestAllRootfsMirrors).setText("⏳ 正在测速...");
            }
        }
        Toast.makeText(this, "正在进行并发真实吞吐量测速...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            boolean isX86 = false;
            for (String abi : Build.SUPPORTED_ABIS) {
                if (abi.contains("x86_64")) {
                    isX86 = true;
                    break;
                }
            }
            final boolean finalIsX86 = isX86;

            for (String pfx : ProotManager.ROOTFS_MIRROR_PREFIXES) {
                String fullUrl = ProotManager.getFullRootfsUrl(pfx, finalIsX86);
                ProotManager.MirrorSpeedResult res = ProotManager.testUrlRealThroughput(fullUrl);
                if (res.success) {
                    double kb = res.speedBps / 1024.0;
                    String s = kb > 1024 ? String.format(java.util.Locale.US, "%.1f MB/s", kb / 1024.0) : String.format(java.util.Locale.US, "%.0f KB/s", kb);
                    mirrorSpeedCache.put(pfx, s + " (" + res.latencyMs + "ms)");
                } else {
                    mirrorSpeedCache.put(pfx, "超时不可达");
                }
            }
            mirrorSpeedCache.put("auto", "⚡ 动态测速");

            mainHandler.post(() -> {
                if (btnTestAllRootfsMirrors != null) {
                    btnTestAllRootfsMirrors.setEnabled(true);
                    if (btnTestAllRootfsMirrors instanceof TextView) {
                        ((TextView) btnTestAllRootfsMirrors).setText("⚡ 全部并发测速");
                    }
                }
                renderRootfsMirrorOptions();
                Toast.makeText(this, "✔ 全部镜像源测速完毕！", Toast.LENGTH_SHORT).show();
            });
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
        } else if (requestCode == REQUEST_CODE_IMPORT_BACKUP) {
            if (resultCode == RESULT_OK && data != null) {
                Uri uri = data.getData();
                if (uri != null) {
                    confirmAndImportBackupUri(uri);
                }
            }
        } else if (requestCode == REQUEST_CODE_IMPORT_CONTAINER) {
            if (resultCode == RESULT_OK && data != null) {
                handleImportToContainer(data);
            }
        } else if (requestCode == REQUEST_CODE_EXPORT_DOCUMENT) {
            java.util.function.Consumer<Uri> cb = pendingExportCallback;
            pendingExportCallback = null;
            if (cb != null) {
                cb.accept(resultCode == RESULT_OK && data != null ? data.getData() : null);
            }
        }
    }

    // ================= 文件浏览器：单独导出到手机存储 (系统文档选择器) =================
    public static final int REQUEST_CODE_EXPORT_DOCUMENT = 2002;
    private java.util.function.Consumer<Uri> pendingExportCallback;

    /** 调起系统「保存到」选择器，用户选定位置后回调目标 Uri（取消回调 null）。 */
    public boolean launchCreateDocument(String fileName, String mime, java.util.function.Consumer<Uri> callback) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mime != null ? mime : "application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, fileName);
        try {
            pendingExportCallback = callback;
            startActivityForResult(intent, REQUEST_CODE_EXPORT_DOCUMENT);
            return true;
        } catch (Exception e) {
            pendingExportCallback = null;
            Toast.makeText(this, "未能调起系统文件保存器: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return false;
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

        // 隐藏已移除的商店和旧版多余操作按钮，仅保留“刷新列表”
        View chipCatStore = findViewById(R.id.chipCatStore);
        if (chipCatStore != null) chipCatStore.setVisibility(View.GONE);

        View btnPluginStore = findViewById(R.id.btnPluginStore);
        if (btnPluginStore != null) btnPluginStore.setVisibility(View.GONE);

        View btnCheckUpdates = findViewById(R.id.btnCheckUpdates);
        if (btnCheckUpdates != null) btnCheckUpdates.setVisibility(View.GONE);

        View btnUpdateAllPlugins = findViewById(R.id.btnUpdateAllPlugins);
        if (btnUpdateAllPlugins != null) btnUpdateAllPlugins.setVisibility(View.GONE);

        View btnAddPluginConfig = findViewById(R.id.btnAddPluginConfig);
        if (btnAddPluginConfig != null) btnAddPluginConfig.setVisibility(View.GONE);

        // 映射 5 大分类 chip：模型、技能、子代理、插件、MCP
        if (chipCatAll != null) {
            chipCatAll.setText("🧠 模型");
            chipCatAll.setVisibility(View.VISIBLE);
            chipCatAll.setOnClickListener(v -> selectPluginCategory(PLUGIN_CAT_MODELS));
        }
        if (chipCatSkills != null) {
            chipCatSkills.setText("⚡ 技能");
            chipCatSkills.setVisibility(View.VISIBLE);
            chipCatSkills.setOnClickListener(v -> selectPluginCategory(PLUGIN_CAT_SKILLS));
        }
        if (chipCatSubagents != null) {
            chipCatSubagents.setText("🤖 子代理");
            chipCatSubagents.setVisibility(View.VISIBLE);
            chipCatSubagents.setOnClickListener(v -> selectPluginCategory(PLUGIN_CAT_SUBAGENTS));
        }
        if (chipCatExtensions != null) {
            chipCatExtensions.setText("🧩 插件");
            chipCatExtensions.setVisibility(View.VISIBLE);
            chipCatExtensions.setOnClickListener(v -> selectPluginCategory(PLUGIN_CAT_PLUGINS));
        }
        if (chipCatMcp != null) {
            chipCatMcp.setText("🔌 MCP");
            chipCatMcp.setVisibility(View.VISIBLE);
            chipCatMcp.setOnClickListener(v -> selectPluginCategory(PLUGIN_CAT_MCP));
        }

        // 调整视觉顺序：模型 ➔ 技能 ➔ 子代理 ➔ 插件 ➔ MCP
        if (chipCatAll != null && chipCatAll.getParent() instanceof ViewGroup) {
            ViewGroup chipContainer = (ViewGroup) chipCatAll.getParent();
            chipContainer.removeView(chipCatAll);
            chipContainer.removeView(chipCatSkills);
            chipContainer.removeView(chipCatSubagents);
            chipContainer.removeView(chipCatExtensions);
            chipContainer.removeView(chipCatMcp);
            if (chipCatStore != null) chipContainer.removeView(chipCatStore);

            chipContainer.addView(chipCatAll);
            chipContainer.addView(chipCatSkills);
            chipContainer.addView(chipCatSubagents);
            chipContainer.addView(chipCatExtensions);
            chipContainer.addView(chipCatMcp);
        }

        if (btnRefreshPlugins != null) {
            btnRefreshPlugins.setOnClickListener(v -> {
                Toast.makeText(this, "正在从 Pi-Web 刷新列表...", Toast.LENGTH_SHORT).show();
                refreshPluginsList(currentPluginCategory);
            });
        }

        if (btnInstallCustomPlugin != null) {
            btnInstallCustomPlugin.setOnClickListener(v -> {
                String input = inputCustomPlugin != null ? inputCustomPlugin.getText().toString().trim() : "";
                if (input.isEmpty()) {
                    Toast.makeText(this, "请输入包名、仓库地址、命令或配置", Toast.LENGTH_SHORT).show();
                    return;
                }
                handleInstallPlugin(currentPluginCategory, input);
            });
        }

        selectPluginCategory(PLUGIN_CAT_MODELS);
    }

    private void selectPluginCategory(int index) {
        currentPluginCategory = index;
        ThemeManager.ThemePalette palette = ThemeManager.getEffectivePalette(this);
        int activeText = palette.isDark ? 0xFF0D1117 : 0xFFFFFFFF;
        int normalText = palette.textMuted;

        updateCategoryChip(chipCatAll, index == PLUGIN_CAT_MODELS, palette, activeText, normalText);
        updateCategoryChip(chipCatSkills, index == PLUGIN_CAT_SKILLS, palette, activeText, normalText);
        updateCategoryChip(chipCatSubagents, index == PLUGIN_CAT_SUBAGENTS, palette, activeText, normalText);
        updateCategoryChip(chipCatExtensions, index == PLUGIN_CAT_PLUGINS, palette, activeText, normalText);
        updateCategoryChip(chipCatMcp, index == PLUGIN_CAT_MCP, palette, activeText, normalText);

        updatePluginInputHint(index);
        refreshPluginsList(index);
    }

    private void updateCategoryChip(TextView chip, boolean active, ThemeManager.ThemePalette palette, int activeText, int normalText) {
        if (chip == null) return;
        if (active) {
            chip.setBackground(ThemeManager.createPrimaryButtonDrawable(this, palette, 16f));
            chip.setTextColor(activeText);
        } else {
            chip.setBackground(ThemeManager.createSecondaryButtonDrawable(this, palette, 16f));
            chip.setTextColor(normalText);
        }
    }

    private void updatePluginInputHint(int category) {
        if (inputCustomPlugin == null || btnInstallCustomPlugin == null) return;
        switch (category) {
            case PLUGIN_CAT_MODELS:
                inputCustomPlugin.setHint("模型在工作台自动发现，或输入 NPM/Git 安装模型插件");
                if (btnInstallCustomPlugin instanceof TextView) {
                    ((TextView) btnInstallCustomPlugin).setText("⚡ 智能识别类型并拉取安装");
                }
                break;
            case PLUGIN_CAT_SKILLS:
                inputCustomPlugin.setHint("输入技能包名或 GitHub 仓库 (如 @agent/skill-name)");
                if (btnInstallCustomPlugin instanceof TextView) {
                    ((TextView) btnInstallCustomPlugin).setText("⚡ 安装 Skill 专家技能");
                }
                break;
            case PLUGIN_CAT_SUBAGENTS:
                inputCustomPlugin.setHint("输入智能体配置或 NPM/Git 包名");
                if (btnInstallCustomPlugin instanceof TextView) {
                    ((TextView) btnInstallCustomPlugin).setText("⚡ 智能识别类型并拉取安装");
                }
                break;
            case PLUGIN_CAT_PLUGINS:
                inputCustomPlugin.setHint("输入 npm 插件包名或 Git 地址 (如 @pi-plugin/...)");
                if (btnInstallCustomPlugin instanceof TextView) {
                    ((TextView) btnInstallCustomPlugin).setText("⚡ 安装插件 (Plugin)");
                }
                break;
            case PLUGIN_CAT_MCP:
                inputCustomPlugin.setHint("输入 MCP 启动命令 (如 npx -y @modelcontextprotocol/server-xxx)");
                if (btnInstallCustomPlugin instanceof TextView) {
                    ((TextView) btnInstallCustomPlugin).setText("⚡ 新增 MCP 服务器");
                }
                break;
        }
    }

    private void handleInstallPlugin(int category, String input) {
        Toast.makeText(this, "正在处理安装请求...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                switch (category) {
                    case PLUGIN_CAT_SKILLS: {
                        JSONObject body = new JSONObject();
                        body.put("package", input);
                        body.put("scope", "global");
                        body.put("cwd", getEffectiveCwd());
                        PiWebApi.post(MainActivity.this, "/api/skills/install", body);
                        mainHandler.post(() -> {
                            Toast.makeText(MainActivity.this, "✔ Skill 技能已成功安装！", Toast.LENGTH_LONG).show();
                            if (inputCustomPlugin != null) inputCustomPlugin.setText("");
                            refreshPluginsList(currentPluginCategory);
                        });
                        break;
                    }
                    case PLUGIN_CAT_PLUGINS: {
                        JSONObject body = new JSONObject();
                        body.put("cwd", getEffectiveCwd());
                        body.put("action", "install");
                        body.put("source", input);
                        body.put("scope", "global");
                        PiWebApi.post(MainActivity.this, "/api/plugins", body);
                        mainHandler.post(() -> {
                            Toast.makeText(MainActivity.this, "✔ 插件已成功安装！", Toast.LENGTH_LONG).show();
                            if (inputCustomPlugin != null) inputCustomPlugin.setText("");
                            refreshPluginsList(currentPluginCategory);
                            promptReloadPi();
                        });
                        break;
                    }
                    case PLUGIN_CAT_MCP: {
                        JSONObject body = new JSONObject();
                        body.put("cwd", getEffectiveCwd());
                        body.put("action", "add");
                        body.put("scope", "global");
                        body.put("text", input);
                        PiWebApi.post(MainActivity.this, "/api/mcp", body);
                        mainHandler.post(() -> {
                            Toast.makeText(MainActivity.this, "✔ MCP 外部服务已成功添加！", Toast.LENGTH_LONG).show();
                            if (inputCustomPlugin != null) inputCustomPlugin.setText("");
                            refreshPluginsList(currentPluginCategory);
                        });
                        break;
                    }
                    default: {
                        PluginManager.SmartInstallResult res = PluginManager.smartInstall(MainActivity.this, input);
                        mainHandler.post(() -> {
                            if (res.success) {
                                Toast.makeText(MainActivity.this, "【" + res.detectedType + "】" + res.message, Toast.LENGTH_LONG).show();
                                if (inputCustomPlugin != null) inputCustomPlugin.setText("");
                                refreshPluginsList(currentPluginCategory);
                                if ("Plugin".equalsIgnoreCase(res.detectedType) || "Extension".equalsIgnoreCase(res.detectedType)) {
                                    promptReloadPi();
                                }
                            } else {
                                Toast.makeText(MainActivity.this, "安装失败: " + res.message, Toast.LENGTH_LONG).show();
                                refreshPluginsList(currentPluginCategory);
                            }
                        });
                        break;
                    }
                }
            } catch (Exception e) {
                mainHandler.post(() -> {
                    String msg = e.getMessage();
                    if (msg == null || msg.isEmpty()) msg = e.getClass().getSimpleName();
                    Toast.makeText(MainActivity.this, "安装/新增失败: " + msg, Toast.LENGTH_LONG).show();
                    refreshPluginsList(currentPluginCategory);
                });
            }
        }).start();
    }

    private void refreshPluginsList(int category) {
        currentPluginCategory = category;
        new Thread(() -> {
            try {
                switch (category) {
                    case PLUGIN_CAT_MODELS:
                        loadModelsCategory();
                        break;
                    case PLUGIN_CAT_SKILLS:
                        loadSkillsCategory();
                        break;
                    case PLUGIN_CAT_SUBAGENTS:
                        loadSubagentsCategory();
                        break;
                    case PLUGIN_CAT_PLUGINS:
                        loadPluginsCategory();
                        break;
                    case PLUGIN_CAT_MCP:
                        loadMcpCategory();
                        break;
                    default:
                        loadModelsCategory();
                        break;
                }
            } catch (Exception e) {
                Log.w("MainActivity", "refreshPluginsList failed: " + e.getMessage(), e);
                mainHandler.post(() -> showPiNotRunningView(e.getMessage()));
            }
        }).start();
    }

    private String getEffectiveCwd() {
        try {
            com.xm486.pimet.pet.PetMemoryManager.MainSessionInfo info =
                    com.xm486.pimet.pet.PetMemoryManager.getMainSessionInfo(this);
            if (info != null && info.cwd != null && !info.cwd.trim().isEmpty()) {
                return info.cwd.trim();
            }
        } catch (Throwable ignored) {}
        return "/root";
    }

    private void showPiNotRunningView(String errorMsg) {
        if (layoutPluginItems == null) return;
        layoutPluginItems.removeAllViews();
        if (tvPluginEmpty != null) tvPluginEmpty.setVisibility(View.GONE);

        ThemeManager.ThemePalette palette = ThemeManager.getEffectivePalette(this);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setBackground(ThemeManager.createCardDrawable(this, palette, 14f));
        card.setPadding(dpToPx(24), dpToPx(32), dpToPx(24), dpToPx(32));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dpToPx(24);
        card.setLayoutParams(lp);

        TextView icon = new TextView(this);
        icon.setText("⚠️");
        icon.setTextSize(36f);
        icon.setGravity(Gravity.CENTER);
        card.addView(icon);

        TextView title = new TextView(this);
        title.setText("Pi 未运行");
        title.setTextColor(palette.text);
        title.setTextSize(16f);
        title.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tLp.topMargin = dpToPx(8);
        title.setLayoutParams(tLp);
        card.addView(title);

        TextView desc = new TextView(this);
        desc.setText("Pi-Web 服务暂未在 127.0.0.1:" + PiWebApi.getPort(this) + " 运行或请求无响应。\n请先启动 Pi 服务后再管理生态配置。");
        desc.setTextColor(palette.textMuted);
        desc.setTextSize(12f);
        desc.setGravity(Gravity.CENTER);
        desc.setLineSpacing(dpToPx(2), 1f);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dLp.topMargin = dpToPx(8);
        desc.setLayoutParams(dLp);
        card.addView(desc);

        // 启动 Pi 按钮
        TextView btnStart = new TextView(this);
        btnStart.setText("🚀 启动 Pi");
        btnStart.setTextColor(palette.isDark ? 0xFF0D1117 : 0xFFFFFFFF);
        btnStart.setTextSize(14f);
        btnStart.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
        btnStart.setBackground(ThemeManager.createPrimaryButtonDrawable(this, palette, 8f));
        btnStart.setGravity(Gravity.CENTER);
        btnStart.setPadding(dpToPx(24), dpToPx(10), dpToPx(24), dpToPx(10));
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bLp.topMargin = dpToPx(16);
        btnStart.setLayoutParams(bLp);
        btnStart.setOnClickListener(v -> startPiWebService());
        card.addView(btnStart);

        // 重试连接按钮
        TextView btnRetry = new TextView(this);
        btnRetry.setText("🔄 重试连接");
        btnRetry.setTextColor(palette.accent);
        btnRetry.setTextSize(12f);
        btnRetry.setGravity(Gravity.CENTER);
        btnRetry.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rLp.topMargin = dpToPx(8);
        btnRetry.setLayoutParams(rLp);
        btnRetry.setOnClickListener(v -> refreshPluginsList(currentPluginCategory));
        card.addView(btnRetry);

        layoutPluginItems.addView(card);
    }

    private void promptReloadPi() {
        new com.xm486.pimet.ui.Icons.CompatDialogBuilder(this)
                .setTitle("提示")
                .setMessage("已更改，重载 Pi 后生效")
                .setPositiveButton("立即重载", (dialog, which) -> restartPiWebService())
                .setNegativeButton("稍后", null)
                .show();
    }

    private LinearLayout createBaseCard(ThemeManager.ThemePalette palette) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(ThemeManager.createCardDrawable(this, palette, 14f));
        card.setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dpToPx(8);
        card.setLayoutParams(lp);
        return card;
    }

    private TextView createBadge(String text, int textColor, ThemeManager.ThemePalette palette) {
        TextView badge = new TextView(this);
        badge.setText(text);
        badge.setTextColor(textColor);
        badge.setTextSize(11f);
        badge.setBackground(ThemeManager.createBadgeDrawable(this, palette, 4f));
        badge.setPadding(dpToPx(8), dpToPx(2), dpToPx(8), dpToPx(2));
        return badge;
    }

    private TextView createStateBadge(boolean enabled, ThemeManager.ThemePalette palette) {
        TextView stateBadge = new TextView(this);
        stateBadge.setText(enabled ? "已启用" : "已停用");
        stateBadge.setTextColor(enabled ? (palette.isDark ? 0xFF3FB950 : 0xFF1A7F37) : palette.textMuted);
        stateBadge.setTextSize(11f);
        stateBadge.setBackground(ThemeManager.createBadgeDrawable(this, palette, 4f));
        stateBadge.setPadding(dpToPx(4), dpToPx(2), dpToPx(4), dpToPx(2));
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sbLp.leftMargin = dpToPx(8);
        stateBadge.setLayoutParams(sbLp);
        return stateBadge;
    }

    private TextView createActionButton(String text, int textColor, ThemeManager.ThemePalette palette) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor(textColor);
        btn.setTextSize(12f);
        btn.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
        btn.setBackground(ThemeManager.createSecondaryButtonDrawable(this, palette, 6f));
        btn.setPadding(dpToPx(12), dpToPx(4), dpToPx(12), dpToPx(4));
        return btn;
    }

    private void showSkillContentDialog(String skillName, String content) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12));

        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(content != null && !content.isEmpty() ? content : "(内容为空)");
        tv.setTextColor(0xFFF0F6FC);
        tv.setTextSize(12f);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setLineSpacing(dpToPx(2), 1f);
        sv.addView(tv);

        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(320));
        layout.addView(sv, svLp);

        new com.xm486.pimet.ui.Icons.CompatDialogBuilder(this)
                .setTitle("技能文档: " + skillName)
                .setView(layout)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void loadModelsCategory() throws Exception {
        String encCwd = URLEncoder.encode(getEffectiveCwd(), "UTF-8");
        JSONObject data = PiWebApi.get(this, "/api/models/enabled?cwd=" + encCwd);
        final int enabledTotal = data.optInt("enabledTotal", 0);
        final int availableTotal = data.optInt("availableTotal", 0);
        final JSONArray providers = data.optJSONArray("providers");

        mainHandler.post(() -> {
            if (currentPluginCategory != PLUGIN_CAT_MODELS || layoutPluginItems == null) return;
            layoutPluginItems.removeAllViews();
            ThemeManager.ThemePalette palette = ThemeManager.getEffectivePalette(MainActivity.this);

            // 顶部操作栏：启用全部模型按钮与统计信息
            LinearLayout topBar = new LinearLayout(MainActivity.this);
            topBar.setOrientation(LinearLayout.HORIZONTAL);
            topBar.setGravity(Gravity.CENTER_VERTICAL);
            topBar.setBackground(ThemeManager.createCardDrawable(MainActivity.this, palette, 10f));
            topBar.setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10));
            LinearLayout.LayoutParams tbLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            tbLp.bottomMargin = dpToPx(8);
            topBar.setLayoutParams(tbLp);

            TextView tvStats = new TextView(MainActivity.this);
            tvStats.setText("已启用 " + enabledTotal + " / " + availableTotal + " 个模型");
            tvStats.setTextColor(palette.text);
            tvStats.setTextSize(13f);
            tvStats.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
            topBar.addView(tvStats, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

            TextView btnEnableAll = new TextView(MainActivity.this);
            btnEnableAll.setText("✨ 启用全部模型");
            btnEnableAll.setTextColor(palette.isDark ? 0xFF0D1117 : 0xFFFFFFFF);
            btnEnableAll.setTextSize(12f);
            btnEnableAll.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
            btnEnableAll.setBackground(ThemeManager.createPrimaryButtonDrawable(MainActivity.this, palette, 6f));
            btnEnableAll.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6));
            btnEnableAll.setOnClickListener(v -> {
                Toast.makeText(MainActivity.this, "正在启用全部模型...", Toast.LENGTH_SHORT).show();
                new Thread(() -> {
                    try {
                        JSONObject body = new JSONObject();
                        body.put("op", "clear");
                        PiWebApi.put(MainActivity.this, "/api/models/enabled", body);
                        mainHandler.post(() -> {
                            Toast.makeText(MainActivity.this, "✔ 已成功启用全部模型！", Toast.LENGTH_SHORT).show();
                            refreshPluginsList(PLUGIN_CAT_MODELS);
                        });
                    } catch (Exception ex) {
                        mainHandler.post(() -> Toast.makeText(MainActivity.this, "操作失败: " + ex.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                }).start();
            });
            topBar.addView(btnEnableAll);
            layoutPluginItems.addView(topBar);

            int modelCount = 0;
            if (providers != null) {
                for (int pIdx = 0; pIdx < providers.length(); pIdx++) {
                    JSONObject provider = providers.optJSONObject(pIdx);
                    if (provider == null) continue;
                    String providerName = provider.optString("name", provider.optString("id", "未知供应商"));
                    JSONArray models = provider.optJSONArray("models");
                    if (models == null) continue;

                    for (int mIdx = 0; mIdx < models.length(); mIdx++) {
                        JSONObject model = models.optJSONObject(mIdx);
                        if (model == null) continue;
                        modelCount++;

                        final String modelId = model.optString("id", "");
                        final String modelName = model.optString("name", modelId);
                        final String modelRef = model.optString("ref", modelId);
                        final boolean enabled = model.optBoolean("enabled", false);

                        LinearLayout card = createBaseCard(palette);

                        // Header
                        LinearLayout header = new LinearLayout(MainActivity.this);
                        header.setOrientation(LinearLayout.HORIZONTAL);
                        header.setGravity(Gravity.CENTER_VERTICAL);

                        TextView title = new TextView(MainActivity.this);
                        title.setText(modelName);
                        title.setTextColor(palette.text);
                        title.setTextSize(14f);
                        title.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
                        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

                        TextView badge = createBadge(providerName, palette.accent, palette);
                        header.addView(badge);

                        TextView stateBadge = createStateBadge(enabled, palette);
                        header.addView(stateBadge);
                        card.addView(header);

                        // Ref / Description
                        TextView desc = new TextView(MainActivity.this);
                        desc.setText("ID: " + modelRef);
                        desc.setTextColor(palette.textMuted);
                        desc.setTextSize(12f);
                        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                        dLp.topMargin = dpToPx(6);
                        desc.setLayoutParams(dLp);
                        card.addView(desc);

                        // Actions
                        LinearLayout actions = new LinearLayout(MainActivity.this);
                        actions.setOrientation(LinearLayout.HORIZONTAL);
                        actions.setGravity(Gravity.END);
                        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                        aLp.topMargin = dpToPx(8);
                        actions.setLayoutParams(aLp);

                        TextView btnToggle = createActionButton(enabled ? "🟢 启停: 开" : "⏸ 启停: 关",
                                enabled ? (palette.isDark ? 0xFF3FB950 : 0xFF1A7F37) : (palette.isDark ? 0xFFF85149 : 0xFFCF222E), palette);
                        btnToggle.setOnClickListener(v -> {
                            new Thread(() -> {
                                try {
                                    JSONObject body = new JSONObject();
                                    body.put("op", "models");
                                    JSONArray refs = new JSONArray();
                                    refs.put(modelRef);
                                    body.put("refs", refs);
                                    body.put("enabled", !enabled);
                                    PiWebApi.put(MainActivity.this, "/api/models/enabled", body);
                                    mainHandler.post(() -> {
                                        Toast.makeText(MainActivity.this, (!enabled ? "✔ 已启用: " : "⏸ 已停用: ") + modelName, Toast.LENGTH_SHORT).show();
                                        refreshPluginsList(PLUGIN_CAT_MODELS);
                                    });
                                } catch (Exception ex) {
                                    mainHandler.post(() -> Toast.makeText(MainActivity.this, "切换失败: " + ex.getMessage(), Toast.LENGTH_SHORT).show());
                                }
                            }).start();
                        });
                        actions.addView(btnToggle);
                        card.addView(actions);

                        layoutPluginItems.addView(card);
                    }
                }
            }

            if (modelCount == 0) {
                if (tvPluginEmpty != null) {
                    tvPluginEmpty.setVisibility(View.VISIBLE);
                    tvPluginEmpty.setText("暂无可用的模型。\n工作台将自动发现配置了 API Key 的 Provider 模型。");
                }
            } else {
                if (tvPluginEmpty != null) tvPluginEmpty.setVisibility(View.GONE);
            }
        });
    }

    private void loadSkillsCategory() throws Exception {
        String encCwd = URLEncoder.encode(getEffectiveCwd(), "UTF-8");
        JSONObject data = PiWebApi.get(this, "/api/skills?cwd=" + encCwd);
        final JSONArray skills = data.optJSONArray("skills");

        mainHandler.post(() -> {
            if (currentPluginCategory != PLUGIN_CAT_SKILLS || layoutPluginItems == null) return;
            layoutPluginItems.removeAllViews();
            ThemeManager.ThemePalette palette = ThemeManager.getEffectivePalette(MainActivity.this);

            if (skills == null || skills.length() == 0) {
                if (tvPluginEmpty != null) {
                    tvPluginEmpty.setVisibility(View.VISIBLE);
                    tvPluginEmpty.setText("暂无已安装的技能 (Skills)。\n可在上方输入框粘贴 npm 包名或 GitHub 仓库地址进行安装。");
                }
                return;
            }

            if (tvPluginEmpty != null) tvPluginEmpty.setVisibility(View.GONE);

            for (int i = 0; i < skills.length(); i++) {
                JSONObject skill = skills.optJSONObject(i);
                if (skill == null) continue;
                final String name = skill.optString("name", "未命名技能");
                final String descText = skill.optString("description", "");
                final String filePath = skill.optString("filePath", "");
                final boolean disabled = skill.optBoolean("disableModelInvocation", false);
                final boolean enabled = !disabled;

                LinearLayout card = createBaseCard(palette);

                // Header
                LinearLayout header = new LinearLayout(MainActivity.this);
                header.setOrientation(LinearLayout.HORIZONTAL);
                header.setGravity(Gravity.CENTER_VERTICAL);

                TextView title = new TextView(MainActivity.this);
                title.setText(name);
                title.setTextColor(palette.text);
                title.setTextSize(14f);
                title.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
                header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

                TextView badge = createBadge("Skill", palette.accent, palette);
                header.addView(badge);

                TextView stateBadge = createStateBadge(enabled, palette);
                header.addView(stateBadge);
                card.addView(header);

                // Description & path
                TextView desc = new TextView(MainActivity.this);
                StringBuilder sb = new StringBuilder();
                if (!descText.isEmpty()) sb.append(descText).append("\n");
                if (!filePath.isEmpty()) sb.append("📁 ").append(filePath);
                desc.setText(sb.toString().trim());
                desc.setTextColor(palette.textMuted);
                desc.setTextSize(12f);
                desc.setLineSpacing(dpToPx(2), 1f);
                LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                dLp.topMargin = dpToPx(6);
                desc.setLayoutParams(dLp);
                card.addView(desc);

                // Actions
                LinearLayout actions = new LinearLayout(MainActivity.this);
                actions.setOrientation(LinearLayout.HORIZONTAL);
                actions.setGravity(Gravity.END);
                LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                aLp.topMargin = dpToPx(8);
                actions.setLayoutParams(aLp);

                // 启停
                TextView btnToggle = createActionButton(enabled ? "🟢 启停: 开" : "⏸ 启停: 关",
                        enabled ? (palette.isDark ? 0xFF3FB950 : 0xFF1A7F37) : (palette.isDark ? 0xFFF85149 : 0xFFCF222E), palette);
                btnToggle.setOnClickListener(v -> {
                    new Thread(() -> {
                        try {
                            JSONObject body = new JSONObject();
                            body.put("filePath", filePath);
                            body.put("disableModelInvocation", enabled);
                            PiWebApi.patch(MainActivity.this, "/api/skills", body);
                            mainHandler.post(() -> {
                                Toast.makeText(MainActivity.this, (!enabled ? "✔ 已启用: " : "⏸ 已停用: ") + name, Toast.LENGTH_SHORT).show();
                                refreshPluginsList(PLUGIN_CAT_SKILLS);
                            });
                        } catch (Exception ex) {
                            mainHandler.post(() -> Toast.makeText(MainActivity.this, "切换失败: " + ex.getMessage(), Toast.LENGTH_SHORT).show());
                        }
                    }).start();
                });
                actions.addView(btnToggle);

                // 查看内容
                if (!filePath.isEmpty()) {
                    View space = new View(MainActivity.this);
                    actions.addView(space, new LinearLayout.LayoutParams(dpToPx(6), 1));

                    TextView btnContent = createActionButton("📄 查看内容", palette.text, palette);
                    btnContent.setOnClickListener(v -> {
                        Toast.makeText(MainActivity.this, "正在读取技能文档...", Toast.LENGTH_SHORT).show();
                        new Thread(() -> {
                            try {
                                String encPath = URLEncoder.encode(filePath, "UTF-8");
                                JSONObject contentObj = PiWebApi.get(MainActivity.this, "/api/skills/content?cwd=" + encCwd + "&filePath=" + encPath);
                                String content = contentObj.optString("content", "");
                                mainHandler.post(() -> showSkillContentDialog(name, content));
                            } catch (Exception ex) {
                                mainHandler.post(() -> Toast.makeText(MainActivity.this, "读取失败: " + ex.getMessage(), Toast.LENGTH_SHORT).show());
                            }
                        }).start();
                    });
                    actions.addView(btnContent);
                }

                card.addView(actions);
                layoutPluginItems.addView(card);
            }
        });
    }

    private void loadSubagentsCategory() throws Exception {
        String encCwd = URLEncoder.encode(getEffectiveCwd(), "UTF-8");
        JSONObject settingsObj = PiWebApi.get(this, "/api/subagents/settings");
        final boolean globalEnabled = settingsObj.optBoolean("enabled", true);

        JSONObject profilesObj = PiWebApi.get(this, "/api/subagents/profiles?cwd=" + encCwd);
        final JSONArray profiles = profilesObj.optJSONArray("profiles");

        mainHandler.post(() -> {
            if (currentPluginCategory != PLUGIN_CAT_SUBAGENTS || layoutPluginItems == null) return;
            layoutPluginItems.removeAllViews();
            ThemeManager.ThemePalette palette = ThemeManager.getEffectivePalette(MainActivity.this);

            // 顶部子代理总开关卡片
            LinearLayout settingsCard = createBaseCard(palette);
            LinearLayout sRow = new LinearLayout(MainActivity.this);
            sRow.setOrientation(LinearLayout.HORIZONTAL);
            sRow.setGravity(Gravity.CENTER_VERTICAL);

            LinearLayout sTextCol = new LinearLayout(MainActivity.this);
            sTextCol.setOrientation(LinearLayout.VERTICAL);
            TextView sTitle = new TextView(MainActivity.this);
            sTitle.setText("🤖 子代理功能总开关");
            sTitle.setTextColor(palette.text);
            sTitle.setTextSize(14f);
            sTitle.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
            sTextCol.addView(sTitle);

            TextView sSub = new TextView(MainActivity.this);
            sSub.setText("控制全局是否允许拉起和调度子智能体实例");
            sSub.setTextColor(palette.textMuted);
            sSub.setTextSize(11f);
            sTextCol.addView(sSub);
            sRow.addView(sTextCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

            TextView btnGlobal = createActionButton(globalEnabled ? "🟢 全局: 开" : "⏸ 全局: 关",
                    globalEnabled ? (palette.isDark ? 0xFF3FB950 : 0xFF1A7F37) : (palette.isDark ? 0xFFF85149 : 0xFFCF222E), palette);
            btnGlobal.setOnClickListener(v -> {
                new Thread(() -> {
                    try {
                        JSONObject body = new JSONObject();
                        body.put("enabled", !globalEnabled);
                        PiWebApi.put(MainActivity.this, "/api/subagents/settings", body);
                        mainHandler.post(() -> {
                            Toast.makeText(MainActivity.this, (!globalEnabled ? "✔ 已开启子代理总开关" : "⏸ 已关闭子代理总开关"), Toast.LENGTH_SHORT).show();
                            refreshPluginsList(PLUGIN_CAT_SUBAGENTS);
                        });
                    } catch (Exception ex) {
                        mainHandler.post(() -> Toast.makeText(MainActivity.this, "切换失败: " + ex.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                }).start();
            });
            sRow.addView(btnGlobal);
            settingsCard.addView(sRow);
            layoutPluginItems.addView(settingsCard);

            // Profiles 列表
            if (profiles == null || profiles.length() == 0) {
                if (tvPluginEmpty != null) {
                    tvPluginEmpty.setVisibility(View.VISIBLE);
                    tvPluginEmpty.setText("暂无子代理 Profile 配置。");
                }
                return;
            }

            if (tvPluginEmpty != null) tvPluginEmpty.setVisibility(View.GONE);

            for (int i = 0; i < profiles.length(); i++) {
                JSONObject profile = profiles.optJSONObject(i);
                if (profile == null) continue;
                final String name = profile.optString("name", "");
                final String displayName = profile.optString("displayName", name);
                final String descText = profile.optString("description", "");
                final String scope = profile.optString("scope", "global");
                final boolean enabled = profile.optBoolean("enabled", true);
                final boolean isBuiltin = "builtin".equalsIgnoreCase(scope);

                LinearLayout card = createBaseCard(palette);

                // Header
                LinearLayout header = new LinearLayout(MainActivity.this);
                header.setOrientation(LinearLayout.HORIZONTAL);
                header.setGravity(Gravity.CENTER_VERTICAL);

                TextView title = new TextView(MainActivity.this);
                title.setText(displayName);
                title.setTextColor(palette.text);
                title.setTextSize(14f);
                title.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
                header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

                String scopeLabel = isBuiltin ? "内置" : ("project".equalsIgnoreCase(scope) ? "项目" : "全局");
                TextView badge = createBadge(scopeLabel, palette.accent, palette);
                header.addView(badge);

                TextView stateBadge = createStateBadge(enabled, palette);
                header.addView(stateBadge);
                card.addView(header);

                // Description
                if (!descText.isEmpty()) {
                    TextView desc = new TextView(MainActivity.this);
                    desc.setText(descText);
                    desc.setTextColor(palette.textMuted);
                    desc.setTextSize(12f);
                    desc.setLineSpacing(dpToPx(2), 1f);
                    LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    dLp.topMargin = dpToPx(6);
                    desc.setLayoutParams(dLp);
                    card.addView(desc);
                }

                // Actions
                LinearLayout actions = new LinearLayout(MainActivity.this);
                actions.setOrientation(LinearLayout.HORIZONTAL);
                actions.setGravity(Gravity.END);
                LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                aLp.topMargin = dpToPx(8);
                actions.setLayoutParams(aLp);

                // 启停
                TextView btnToggle = createActionButton(enabled ? "🟢 启停: 开" : "⏸ 启停: 关",
                        enabled ? (palette.isDark ? 0xFF3FB950 : 0xFF1A7F37) : (palette.isDark ? 0xFFF85149 : 0xFFCF222E), palette);
                btnToggle.setOnClickListener(v -> {
                    new Thread(() -> {
                        try {
                            JSONObject body = new JSONObject();
                            body.put("cwd", getEffectiveCwd());
                            body.put("scope", scope);
                            body.put("name", name);
                            body.put("enabled", !enabled);
                            PiWebApi.patch(MainActivity.this, "/api/subagents/profiles", body);
                            mainHandler.post(() -> {
                                Toast.makeText(MainActivity.this, (!enabled ? "✔ 已启用: " : "⏸ 已停用: ") + displayName, Toast.LENGTH_SHORT).show();
                                refreshPluginsList(PLUGIN_CAT_SUBAGENTS);
                            });
                        } catch (Exception ex) {
                            mainHandler.post(() -> Toast.makeText(MainActivity.this, "切换失败: " + ex.getMessage(), Toast.LENGTH_SHORT).show());
                        }
                    }).start();
                });
                actions.addView(btnToggle);

                // 删除 (内置不可删)
                if (!isBuiltin) {
                    View space = new View(MainActivity.this);
                    actions.addView(space, new LinearLayout.LayoutParams(dpToPx(6), 1));

                    TextView btnDelete = createActionButton("🗑️ 删除",
                            palette.isDark ? 0xFFF85149 : 0xFFCF222E, palette);
                    btnDelete.setOnClickListener(v -> {
                        new com.xm486.pimet.ui.Icons.CompatDialogBuilder(MainActivity.this)
                                .setTitle("确认删除子代理")
                                .setMessage("确定要删除子代理配置 " + displayName + " 吗？")
                                .setPositiveButton("删除", (d, w) -> {
                                    new Thread(() -> {
                                        try {
                                            JSONObject body = new JSONObject();
                                            body.put("cwd", getEffectiveCwd());
                                            body.put("scope", scope);
                                            body.put("name", name);
                                            PiWebApi.delete(MainActivity.this, "/api/subagents/profiles", body);
                                            mainHandler.post(() -> {
                                                Toast.makeText(MainActivity.this, "✔ 已删除: " + displayName, Toast.LENGTH_SHORT).show();
                                                refreshPluginsList(PLUGIN_CAT_SUBAGENTS);
                                            });
                                        } catch (Exception ex) {
                                            mainHandler.post(() -> Toast.makeText(MainActivity.this, "删除失败: " + ex.getMessage(), Toast.LENGTH_SHORT).show());
                                        }
                                    }).start();
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    });
                    actions.addView(btnDelete);
                }

                card.addView(actions);
                layoutPluginItems.addView(card);
            }
        });
    }

    private void loadPluginsCategory() throws Exception {
        String encCwd = URLEncoder.encode(getEffectiveCwd(), "UTF-8");
        JSONObject data = PiWebApi.get(this, "/api/plugins?cwd=" + encCwd);
        final JSONArray packages = data.optJSONArray("packages");
        final JSONArray standalone = data.optJSONArray("standaloneExtensions");

        mainHandler.post(() -> {
            if (currentPluginCategory != PLUGIN_CAT_PLUGINS || layoutPluginItems == null) return;
            layoutPluginItems.removeAllViews();
            ThemeManager.ThemePalette palette = ThemeManager.getEffectivePalette(MainActivity.this);

            int totalCount = (packages != null ? packages.length() : 0) + (standalone != null ? standalone.length() : 0);
            if (totalCount == 0) {
                if (tvPluginEmpty != null) {
                    tvPluginEmpty.setVisibility(View.VISIBLE);
                    tvPluginEmpty.setText("暂无已安装的插件或独立扩展。\n请在上方输入 npm 包名或 Git 地址安装。");
                }
                return;
            }

            if (tvPluginEmpty != null) tvPluginEmpty.setVisibility(View.GONE);

            // 1. 已安装的插件包
            if (packages != null) {
                for (int i = 0; i < packages.length(); i++) {
                    JSONObject pkg = packages.optJSONObject(i);
                    if (pkg == null) continue;

                    final String source = pkg.optString("source", "");
                    final String pkgName = pkg.optString("packageName", source);
                    final String scope = pkg.optString("scope", "global");
                    final String version = pkg.optString("version", "");
                    final String descText = pkg.optString("description", "");
                    final boolean disabled = pkg.optBoolean("disabled", false);
                    final boolean enabled = !disabled;

                    LinearLayout card = createBaseCard(palette);

                    // Header
                    LinearLayout header = new LinearLayout(MainActivity.this);
                    header.setOrientation(LinearLayout.HORIZONTAL);
                    header.setGravity(Gravity.CENTER_VERTICAL);

                    TextView title = new TextView(MainActivity.this);
                    title.setText(pkgName);
                    title.setTextColor(palette.text);
                    title.setTextSize(14f);
                    title.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
                    header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

                    TextView badge = createBadge("插件", palette.accent, palette);
                    header.addView(badge);

                    if (!version.isEmpty()) {
                        TextView vBadge = createBadge("v" + version, palette.textMuted, palette);
                        LinearLayout.LayoutParams vbLp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                        vbLp.leftMargin = dpToPx(6);
                        vBadge.setLayoutParams(vbLp);
                        header.addView(vBadge);
                    }

                    TextView stateBadge = createStateBadge(enabled, palette);
                    header.addView(stateBadge);
                    card.addView(header);

                    // Description & Source
                    TextView desc = new TextView(MainActivity.this);
                    StringBuilder sb = new StringBuilder();
                    if (!descText.isEmpty()) sb.append(descText).append("\n");
                    sb.append("📦 来源: ").append(source);
                    desc.setText(sb.toString());
                    desc.setTextColor(palette.textMuted);
                    desc.setTextSize(12f);
                    desc.setLineSpacing(dpToPx(2), 1f);
                    LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    dLp.topMargin = dpToPx(6);
                    desc.setLayoutParams(dLp);
                    card.addView(desc);

                    // Actions
                    LinearLayout actions = new LinearLayout(MainActivity.this);
                    actions.setOrientation(LinearLayout.HORIZONTAL);
                    actions.setGravity(Gravity.END);
                    LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    aLp.topMargin = dpToPx(8);
                    actions.setLayoutParams(aLp);

                    // 启停
                    TextView btnToggle = createActionButton(enabled ? "🟢 启停: 开" : "⏸ 启停: 关",
                            enabled ? (palette.isDark ? 0xFF3FB950 : 0xFF1A7F37) : (palette.isDark ? 0xFFF85149 : 0xFFCF222E), palette);
                    btnToggle.setOnClickListener(v -> {
                        new Thread(() -> {
                            try {
                                JSONObject body = new JSONObject();
                                body.put("cwd", getEffectiveCwd());
                                body.put("action", enabled ? "disable" : "enable");
                                body.put("source", source);
                                body.put("scope", scope);
                                PiWebApi.post(MainActivity.this, "/api/plugins", body);
                                mainHandler.post(() -> {
                                    Toast.makeText(MainActivity.this, (enabled ? "⏸ 已停用: " : "✔ 已启用: ") + pkgName, Toast.LENGTH_SHORT).show();
                                    refreshPluginsList(PLUGIN_CAT_PLUGINS);
                                    promptReloadPi(); // 只有插件类变更后才弹 promptReloadPi
                                });
                            } catch (Exception ex) {
                                mainHandler.post(() -> Toast.makeText(MainActivity.this, "切换失败: " + ex.getMessage(), Toast.LENGTH_SHORT).show());
                            }
                        }).start();
                    });
                    actions.addView(btnToggle);

                    // 移除
                    View space = new View(MainActivity.this);
                    actions.addView(space, new LinearLayout.LayoutParams(dpToPx(6), 1));

                    TextView btnDelete = createActionButton("🗑️ 移除",
                            palette.isDark ? 0xFFF85149 : 0xFFCF222E, palette);
                    btnDelete.setOnClickListener(v -> {
                        new com.xm486.pimet.ui.Icons.CompatDialogBuilder(MainActivity.this)
                                .setTitle("确认移除插件")
                                .setMessage("确定要从容器中卸载并移除 " + pkgName + " 吗？")
                                .setPositiveButton("移除", (d, w) -> {
                                    new Thread(() -> {
                                        try {
                                            JSONObject body = new JSONObject();
                                            body.put("cwd", getEffectiveCwd());
                                            body.put("action", "remove");
                                            body.put("source", source);
                                            body.put("scope", scope);
                                            PiWebApi.post(MainActivity.this, "/api/plugins", body);
                                            mainHandler.post(() -> {
                                                Toast.makeText(MainActivity.this, "✔ 已成功移除插件: " + pkgName, Toast.LENGTH_SHORT).show();
                                                refreshPluginsList(PLUGIN_CAT_PLUGINS);
                                                promptReloadPi(); // 只有插件类变更后才弹 promptReloadPi
                                            });
                                        } catch (Exception ex) {
                                            mainHandler.post(() -> Toast.makeText(MainActivity.this, "移除失败: " + ex.getMessage(), Toast.LENGTH_SHORT).show());
                                        }
                                    }).start();
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    });
                    actions.addView(btnDelete);

                    card.addView(actions);
                    layoutPluginItems.addView(card);
                }
            }

            // 2. 独立扩展 (如内置的 pimet-bridge 等)
            if (standalone != null) {
                for (int i = 0; i < standalone.length(); i++) {
                    JSONObject ext = standalone.optJSONObject(i);
                    if (ext == null) continue;
                    String name = ext.optString("name", "");
                    boolean enabled = ext.optBoolean("enabled", true);
                    String descText = ext.optString("description", "");

                    LinearLayout card = createBaseCard(palette);

                    LinearLayout header = new LinearLayout(MainActivity.this);
                    header.setOrientation(LinearLayout.HORIZONTAL);
                    header.setGravity(Gravity.CENTER_VERTICAL);

                    TextView title = new TextView(MainActivity.this);
                    title.setText(name);
                    title.setTextColor(palette.text);
                    title.setTextSize(14f);
                    title.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
                    header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

                    TextView badge = createBadge("独立扩展", palette.accent, palette);
                    header.addView(badge);

                    TextView stateBadge = createStateBadge(enabled, palette);
                    header.addView(stateBadge);
                    card.addView(header);

                    if (!descText.isEmpty()) {
                        TextView desc = new TextView(MainActivity.this);
                        desc.setText(descText);
                        desc.setTextColor(palette.textMuted);
                        desc.setTextSize(12f);
                        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                        dLp.topMargin = dpToPx(6);
                        desc.setLayoutParams(dLp);
                        card.addView(desc);
                    }

                    layoutPluginItems.addView(card);
                }
            }
        });
    }

    private void loadMcpCategory() throws Exception {
        String encCwd = URLEncoder.encode(getEffectiveCwd(), "UTF-8");
        JSONObject data = PiWebApi.get(this, "/api/mcp?cwd=" + encCwd);
        final JSONArray servers = data.optJSONArray("servers");

        mainHandler.post(() -> {
            if (currentPluginCategory != PLUGIN_CAT_MCP || layoutPluginItems == null) return;
            layoutPluginItems.removeAllViews();
            ThemeManager.ThemePalette palette = ThemeManager.getEffectivePalette(MainActivity.this);

            if (servers == null || servers.length() == 0) {
                if (tvPluginEmpty != null) {
                    tvPluginEmpty.setVisibility(View.VISIBLE);
                    tvPluginEmpty.setText("暂无已配置的 MCP 服务器。\n可在上方输入启动命令 (如 npx -y @modelcontextprotocol/server-fetch) 新增。");
                }
                return;
            }

            if (tvPluginEmpty != null) tvPluginEmpty.setVisibility(View.GONE);

            for (int i = 0; i < servers.length(); i++) {
                JSONObject server = servers.optJSONObject(i);
                if (server == null) continue;
                final String name = server.optString("name", "未命名服务");
                final String scope = server.optString("scope", "global");
                final boolean enabled = server.optBoolean("enabled", true);
                JSONObject transport = server.optJSONObject("transport");

                LinearLayout card = createBaseCard(palette);

                // Header
                LinearLayout header = new LinearLayout(MainActivity.this);
                header.setOrientation(LinearLayout.HORIZONTAL);
                header.setGravity(Gravity.CENTER_VERTICAL);

                TextView title = new TextView(MainActivity.this);
                title.setText(name);
                title.setTextColor(palette.text);
                title.setTextSize(14f);
                title.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
                header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

                TextView badge = createBadge("MCP · " + scope, palette.accent, palette);
                header.addView(badge);

                TextView stateBadge = createStateBadge(enabled, palette);
                header.addView(stateBadge);
                card.addView(header);

                // Transport details
                StringBuilder sb = new StringBuilder();
                if (transport != null) {
                    String type = transport.optString("type", "");
                    if (!type.isEmpty()) sb.append("协议: ").append(type);
                    String cmd = transport.optString("command", "");
                    if (!cmd.isEmpty()) {
                        if (sb.length() > 0) sb.append(" | ");
                        sb.append("命令: ").append(cmd);
                    }
                    String url = transport.optString("url", "");
                    if (!url.isEmpty()) {
                        if (sb.length() > 0) sb.append(" | ");
                        sb.append("URL: ").append(url);
                    }
                    JSONArray args = transport.optJSONArray("args");
                    if (args != null && args.length() > 0) {
                        sb.append("\n参数: ");
                        for (int a = 0; a < args.length(); a++) {
                            if (a > 0) sb.append(" ");
                            sb.append(args.optString(a));
                        }
                    }
                }
                if (sb.length() > 0) {
                    TextView desc = new TextView(MainActivity.this);
                    desc.setText(sb.toString());
                    desc.setTextColor(palette.textMuted);
                    desc.setTextSize(12f);
                    desc.setLineSpacing(dpToPx(2), 1f);
                    LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    dLp.topMargin = dpToPx(6);
                    desc.setLayoutParams(dLp);
                    card.addView(desc);
                }

                // Actions
                LinearLayout actions = new LinearLayout(MainActivity.this);
                actions.setOrientation(LinearLayout.HORIZONTAL);
                actions.setGravity(Gravity.END);
                LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                aLp.topMargin = dpToPx(8);
                actions.setLayoutParams(aLp);

                // 启停
                TextView btnToggle = createActionButton(enabled ? "🟢 启停: 开" : "⏸ 启停: 关",
                        enabled ? (palette.isDark ? 0xFF3FB950 : 0xFF1A7F37) : (palette.isDark ? 0xFFF85149 : 0xFFCF222E), palette);
                btnToggle.setOnClickListener(v -> {
                    new Thread(() -> {
                        try {
                            JSONObject body = new JSONObject();
                            body.put("cwd", getEffectiveCwd());
                            body.put("action", enabled ? "disable" : "enable");
                            body.put("scope", scope);
                            body.put("name", name);
                            PiWebApi.post(MainActivity.this, "/api/mcp", body);
                            mainHandler.post(() -> {
                                Toast.makeText(MainActivity.this, (enabled ? "⏸ 已停用: " : "✔ 已启用: ") + name, Toast.LENGTH_SHORT).show();
                                refreshPluginsList(PLUGIN_CAT_MCP);
                            });
                        } catch (Exception ex) {
                            mainHandler.post(() -> Toast.makeText(MainActivity.this, "切换失败: " + ex.getMessage(), Toast.LENGTH_SHORT).show());
                        }
                    }).start();
                });
                actions.addView(btnToggle);

                // 移除
                View space = new View(MainActivity.this);
                actions.addView(space, new LinearLayout.LayoutParams(dpToPx(6), 1));

                TextView btnDelete = createActionButton("🗑️ 移除",
                        palette.isDark ? 0xFFF85149 : 0xFFCF222E, palette);
                btnDelete.setOnClickListener(v -> {
                    new com.xm486.pimet.ui.Icons.CompatDialogBuilder(MainActivity.this)
                            .setTitle("确认移除 MCP 服务")
                            .setMessage("确定要移除 MCP 服务器 " + name + " 吗？")
                            .setPositiveButton("移除", (d, w) -> {
                                new Thread(() -> {
                                    try {
                                        JSONObject body = new JSONObject();
                                        body.put("cwd", getEffectiveCwd());
                                        body.put("action", "remove");
                                        body.put("scope", scope);
                                        body.put("name", name);
                                        PiWebApi.post(MainActivity.this, "/api/mcp", body);
                                        mainHandler.post(() -> {
                                            Toast.makeText(MainActivity.this, "✔ 已成功移除 MCP 服务: " + name, Toast.LENGTH_SHORT).show();
                                            refreshPluginsList(PLUGIN_CAT_MCP);
                                        });
                                    } catch (Exception ex) {
                                        mainHandler.post(() -> Toast.makeText(MainActivity.this, "移除失败: " + ex.getMessage(), Toast.LENGTH_SHORT).show());
                                    }
                                }).start();
                            })
                            .setNegativeButton("取消", null)
                            .show();
                });
                actions.addView(btnDelete);

                card.addView(actions);
                layoutPluginItems.addView(card);
            }
        });
    }

    private TextView createDialogLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFF8B949E);
        tv.setTextSize(12f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dpToPx(8);
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
        et.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));
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
        try {
            if (piWebWebView != null) piWebWebView.onResume();
        } catch (Throwable ignored) {}
        if (lastAppliedThemePref != null
                && !lastAppliedThemePref.equals(ThemeManager.getThemePreference(this))) {
            applyAppTheme();
        }
        initPetMonitor();
        applyPetParams();
        boolean isPetRunning = PetOverlayService.isRunning();
        updatePetDisplay(isPetRunning);
        updateLaunchPetUI();
        updatePetPreview();
        buildPetList();
        buildPhysicsSettings();
        if (btnToggleGlobalOverlay != null) {
            btnToggleGlobalOverlay.setText(isPetRunning
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
        // 页面已销毁：丢弃尚未执行的界面回调，避免后台线程回来后操作已销毁的视图
        mainHandler.removeCallbacksAndMessages(null);
    }

    // ---------------- 拖动物理手感参数设置 (8 项原生交互滑块) ----------------
    private static final Object[][] PHYSICS_ITEMS = {
            {"宠物大小", PetRegistry.KEY_PET_SIZE, 32, 120, PetRegistry.DEFAULT_PET_SIZE, "dp"},
            {"甩动灵敏度", PetRegistry.KEY_FLING_THRESHOLD, 100, 2000, PetRegistry.DEFAULT_FLING_THRESHOLD, "px/s"},
            {"反弹保留", PetRegistry.KEY_BOUNCE, 0, 100, PetRegistry.DEFAULT_BOUNCE, "%"},
            {"空气摩擦", PetRegistry.KEY_FRICTION, 0, 100, PetRegistry.DEFAULT_FRICTION, "%"},
            {"停稳阈值", PetRegistry.KEY_STOP_SPEED, 10, 200, PetRegistry.DEFAULT_STOP_SPEED, "px/s"},
            {"聊天框宽度", PetRegistry.KEY_CARD_WIDTH, 140, 360, PetRegistry.DEFAULT_CARD_WIDTH, "dp"},
            {"菜单大小", PetRegistry.KEY_MENU_SCALE, 150, 250, PetRegistry.DEFAULT_MENU_SCALE, "%"},
            {"窗口紧凑度", PetRegistry.KEY_CARD_SCALE, 40, 120, PetRegistry.DEFAULT_CARD_SCALE, "%"},
    };

    private void buildPhysicsSettings() {
        if (physicsContainer == null) return;
        physicsContainer.removeAllViews();
        ThemeManager.ThemePalette palette = ThemeManager.getEffectivePalette(this);
        physicsContainer.setBackground(ThemeManager.createSunkenDrawable(this, palette, 10f));

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
            row.setPadding(0, dpToPx(4), 0, dpToPx(4));

            final TextView label = new TextView(this);
            label.setText(name);
            label.setTextSize(12f);
            label.setTextColor(palette.textMuted);
            label.setMaxLines(1);
            row.addView(label, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f));

            final TextView valueText = new TextView(this);
            valueText.setText(cur + " " + unit);
            valueText.setTextSize(12f);
            valueText.setTypeface(Typeface.MONOSPACE);
            valueText.setTextColor(palette.accent);
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

    // ================= 主题与外观管理 (Theme Management) =================
    private void selectTheme(String themeId) {
        ThemeManager.setThemePreference(this, themeId);
        updateThemeUIState();
        applyAppTheme();
        Toast.makeText(this, "已应用主题: " + ThemeManager.getThemeLabel(themeId), Toast.LENGTH_SHORT).show();
    }

    private void updateThemeUIState() {
        String currentPref = ThemeManager.getThemePreference(this);
        ThemeManager.ThemePalette effPalette = ThemeManager.getEffectivePalette(this);

        if (tvCurrentThemeBadge != null) {
            String badgeText = ThemeManager.getThemeLabel(currentPref);
            if (ThemeManager.THEME_AUTO.equals(currentPref)) {
                badgeText += " (" + (effPalette.isDark ? "当前夜间" : "当前日间") + ")";
            }
            tvCurrentThemeBadge.setText(badgeText);
            tvCurrentThemeBadge.setBackground(ThemeManager.createBadgeDrawable(this, effPalette, ThemeManager.RADIUS_PILL));
            tvCurrentThemeBadge.setTextColor(effPalette.accent);
            tvCurrentThemeBadge.setSingleLine(true);
            tvCurrentThemeBadge.setEllipsize(android.text.TextUtils.TruncateAt.END);
        }

        updateThemeOptionRow(themeRowAuto, tvThemeTitleAuto, tvThemeCheckAuto, ThemeManager.THEME_AUTO.equals(currentPref), effPalette);
        updateThemeOptionRow(themeRowLight, tvThemeTitleLight, tvThemeCheckLight, ThemeManager.THEME_LIGHT.equals(currentPref), effPalette);
        updateThemeOptionRow(themeRowDark, tvThemeTitleDark, tvThemeCheckDark, ThemeManager.THEME_DARK.equals(currentPref), effPalette);
        updateThemeOptionRow(themeRowMist, tvThemeTitleMist, tvThemeCheckMist, ThemeManager.THEME_MIST.equals(currentPref), effPalette);
        updateThemeOptionRow(themeRowRose, tvThemeTitleRose, tvThemeCheckRose, ThemeManager.THEME_ROSE.equals(currentPref), effPalette);
        updateThemeOptionRow(themeRowPine, tvThemeTitlePine, tvThemeCheckPine, ThemeManager.THEME_PINE.equals(currentPref), effPalette);
        for (java.util.Map.Entry<String, View[]> e : extraThemeRows.entrySet()) {
            View[] r = e.getValue();
            updateThemeOptionRow(r[0], (TextView) r[1], (TextView) r[2], e.getKey().equals(currentPref), effPalette);
        }
    }

    /**
     * 追加 Pi-Web 0.12.2 新增的 8 个主题选项（暖沙、丁香、暮紫、余烬、岩蓝、高对比浅/深色、Zenburn），
     * 样式与 XML 中的选项行一致；左侧用该主题自己的底色 + 强调色画一个色块，便于一眼区分。
     */
    private void buildExtraThemeRows() {
        if (themeRowPine == null || !(themeRowPine.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) themeRowPine.getParent();
        String[] ids = {
                ThemeManager.THEME_SAND, ThemeManager.THEME_LILAC, ThemeManager.THEME_DUSK,
                ThemeManager.THEME_EMBER, ThemeManager.THEME_SLATE, ThemeManager.THEME_CONTRAST_LIGHT,
                ThemeManager.THEME_CONTRAST_DARK, ThemeManager.THEME_ZENBURN
        };
        int padMd = dpToPx(12);
        for (String id : ids) {
            if (extraThemeRows.containsKey(id)) continue;
            ThemeManager.ThemePalette p = ThemeManager.getPaletteById(id);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(padMd, padMd, padMd, padMd);
            row.setClickable(true);
            row.setFocusable(true);

            View swatch = new View(this);
            GradientDrawable sw = new GradientDrawable();
            sw.setShape(GradientDrawable.OVAL);
            sw.setColor(p.bg);
            sw.setStroke(dpToPx(4), p.accent);
            swatch.setBackground(sw);
            row.addView(swatch, new LinearLayout.LayoutParams(dpToPx(22), dpToPx(22)));

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tlp.setMarginStart(padMd);
            row.addView(texts, tlp);

            TextView title = new TextView(this);
            title.setText(p.name);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, ThemeManager.TEXT_BODY_SMALL);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            title.setTag("theme_role:primary");
            texts.addView(title);

            TextView sub = new TextView(this);
            sub.setText(ThemeManager.getThemeDescription(id));
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, ThemeManager.TEXT_CAPTION);
            sub.setTag("theme_role:muted");
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dpToPx(2);
            texts.addView(sub, slp);

            TextView check = new TextView(this);
            check.setTextSize(TypedValue.COMPLEX_UNIT_SP, ThemeManager.TEXT_BODY);
            check.setTag("theme_role:accent");
            row.addView(check, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dpToPx(8);
            parent.addView(row, rlp);

            row.setOnClickListener(v -> selectTheme(id));
            extraThemeRows.put(id, new View[]{row, title, check});
        }
    }

    private void updateThemeOptionRow(View row, TextView title, TextView check, boolean isSelected, ThemeManager.ThemePalette effPalette) {
        if (row == null) return;
        if (isSelected) {
            row.setBackground(ThemeManager.createSelectedOptionDrawable(this, effPalette, 8f));
            if (check != null) {
                check.setText("✔");
                check.setTextColor(effPalette.accent);
            }
            if (title != null) {
                title.setTextColor(effPalette.accent);
            }
        } else {
            row.setBackground(ThemeManager.createSecondaryButtonDrawable(this, effPalette, 8f));
            if (check != null) {
                check.setText("");
            }
            if (title != null) {
                title.setTextColor(effPalette.text);
            }
        }
    }

    public void applyAppTheme() {
        lastAppliedThemePref = ThemeManager.getThemePreference(this);
        ThemeManager.ThemePalette palette = ThemeManager.getEffectivePalette(this);
        ThemeManager.applyWindowTheme(this, palette);

        // 根布局底色与离线提示底色对齐
        View mainRoot = findViewById(R.id.mainRootLayout);
        if (mainRoot != null) mainRoot.setBackgroundColor(palette.bg);
        View offlineCard = findViewById(R.id.piWebOfflineCard);
        if (offlineCard != null) offlineCard.setBackgroundColor(palette.bg);

        // 整树递归应用主题色彩 (启动页、插件页、设置页、二级窗口)
        if (viewLaunch != null) ThemeManager.applyThemeToHierarchy(viewLaunch, palette);
        if (viewPlugins != null) ThemeManager.applyThemeToHierarchy(viewPlugins, palette);
        if (viewSettings != null) ThemeManager.applyThemeToHierarchy(viewSettings, palette);
        if (storageCleanPanel != null) storageCleanPanel.rerender();
        if (viewSettingsSubWindow != null) ThemeManager.applyThemeToHierarchy(viewSettingsSubWindow, palette);

        if (bottomNavBar != null) {
            bottomNavBar.setBackground(ThemeManager.createNavBarDrawable(this, palette));
        }
        int activeTabColor = palette.accent;
        int normalTabColor = palette.textMuted;
        if (tabLaunchText != null) {
            tabLaunchText.setTextColor(viewLaunch != null && viewLaunch.getVisibility() == View.VISIBLE ? activeTabColor : normalTabColor);
        }
        if (tabPiWebText != null) {
            tabPiWebText.setTextColor(viewPiWeb != null && viewPiWeb.getVisibility() == View.VISIBLE ? activeTabColor : normalTabColor);
        }
        if (tabPluginsText != null) {
            tabPluginsText.setTextColor(viewPlugins != null && viewPlugins.getVisibility() == View.VISIBLE ? activeTabColor : normalTabColor);
        }
        if (tabSettingsText != null) {
            tabSettingsText.setTextColor(((viewSettings != null && viewSettings.getVisibility() == View.VISIBLE)
                    || (viewSettingsSubWindow != null && viewSettingsSubWindow.getVisibility() == View.VISIBLE)) ? activeTabColor : normalTabColor);
        }
        syncTabIconColors();

        View headerView = findViewById(R.id.viewSettingsSubHeader);
        if (headerView != null) {
            headerView.setBackgroundColor(palette.bgPanel);
        }
        if (tvSettingsSubWindowTitle != null) {
            tvSettingsSubWindowTitle.setTextColor(palette.text);
        }
        if (btnSettingsSubWindowBack instanceof TextView) {
            ((TextView) btnSettingsSubWindowBack).setTextColor(palette.accent);
        }
        if (btnSettingsSubWindowClose instanceof TextView) {
            ((TextView) btnSettingsSubWindowClose).setTextColor(palette.textMuted);
        }

        // 刷新设置条目菜单状态
        updateMenuItemState(menuItemPorts, menuTitlePorts, menuArrowPorts, currentSettingsCategory == SETTINGS_CAT_PORTS);
        if (menuItemUpdate != null) {
            updateMenuItemState(menuItemUpdate, menuTitleUpdate, menuArrowUpdate, currentSettingsCategory == SETTINGS_CAT_UPDATE);
        }
        updateMenuItemState(menuItemPet, menuTitlePet, menuArrowPet, currentSettingsCategory == SETTINGS_CAT_PET);
        updateMenuItemState(menuItemPrivileges, menuTitlePrivileges, menuArrowPrivileges, currentSettingsCategory == SETTINGS_CAT_PRIVILEGES);
        updateMenuItemState(menuItemStorage, menuTitleStorage, menuArrowStorage, currentSettingsCategory == SETTINGS_CAT_STORAGE);
        updateMenuItemState(menuItemAbout, menuTitleAbout, menuArrowAbout, currentSettingsCategory == SETTINGS_CAT_ABOUT || currentSettingsCategory == SETTINGS_CAT_UPDATE);
        updateMenuItemState(menuItemTheme, menuTitleTheme, menuArrowTheme, currentSettingsCategory == SETTINGS_CAT_THEME);

        // 仅在非工作台可见或专职页面时进行开销较大的列表重绘，杜绝会话中卡顿
        if (viewPlugins != null && viewPlugins.getVisibility() == View.VISIBLE) {
            selectPluginCategory(currentPluginCategory);
        }
        if (viewSettingsSubWindow != null && viewSettingsSubWindow.getVisibility() == View.VISIBLE) {
            if (currentSettingsCategory == SETTINGS_CAT_PET) {
                buildPhysicsSettings();
                buildPetList();
            }
        }
        updateLaunchPetUI();
        updateLaunchStatusUI(isPiWebAlive);

        if (launchProgressLabelTv != null) {
            launchProgressLabelTv.setTextColor(palette.accent);
        }
        if (launchProgressPercentTv != null) {
            launchProgressPercentTv.setTextColor(palette.accent);
        }

        // 刷新悬浮球与悬浮菜单样式
        if (floatingBall != null) {
            GradientDrawable ballBg = new GradientDrawable();
            ballBg.setColor(palette.bgPanel);
            ballBg.setShape(GradientDrawable.OVAL);
            ballBg.setStroke(Math.max(1, dpToPx(1)), palette.border);
            floatingBall.setBackground(ballBg);
            floatingBall.setTextColor(palette.accent);
        }
        if (floatingMenuVertical != null) {
            floatingMenuVertical.setBackground(ThemeManager.createCardDrawable(this, palette, 12f));
            ThemeManager.applyThemeToHierarchy(floatingMenuVertical, palette);
        }
        if (btnFloatTranslate != null) {
            btnFloatTranslate.setTextColor(isWebTranslated ? palette.accent : palette.text);
        }

        if (piWebWebView != null) {
            ThemeManager.syncThemeToWebView(piWebWebView, this);
        }

        // 外观页选项卡与当前主题徽章：必须在整树递归着色之后按当前选择重绘，
        // 否则首次进入外观页时会残留布局 XML 中的深色默认底与「深色 (Dark)」占位徽章。
        updateThemeUIState();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        String pref = ThemeManager.getThemePreference(this);
        if (ThemeManager.THEME_AUTO.equals(pref)) {
            updateThemeUIState();
            applyAppTheme();
        }
    }

    // ================= 备份与外部文件导入 (Backup & Restore Picker) =================
    public void launchBackupFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        String[] mimes = {"application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimes);
        try {
            startActivityForResult(Intent.createChooser(intent, "选择 PiMet 备份压缩包 (.zip)"), REQUEST_CODE_IMPORT_BACKUP);
        } catch (Exception e) {
            Toast.makeText(this, "未能调起系统文件选择器: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmAndImportBackupUri(Uri uri) {
        new com.xm486.pimet.ui.Icons.CompatDialogBuilder(this)
                .setTitle("⚠️ 确认无损还原备份包？")
                .setMessage("即将从所选外部文件无损恢复全部数据与配置。\n包含聊天记忆、子代理、插件生态、端口及应用偏好。\n是否继续？")
                .setPositiveButton("立即还原", (d, w) -> {
                    BackupProgressHolder holder = showBackupProgressDialog("📥 正在无损还原配置与数据...");
                    BackupManager.importBackupFromUri(this, uri, (pct, stage, logLine) -> {
                        holder.progressBar.setProgress(pct);
                        holder.tvStage.setText(stage + " (" + pct + "%)");
                        holder.tvDetail.setText(logLine);
                    }, (success, restoredCount, msg) -> {
                        holder.dialog.dismiss();
                        new com.xm486.pimet.ui.Icons.CompatDialogBuilder(this)
                                .setTitle(success ? "🎉 还原完成" : "❌ 还原失败")
                                .setMessage(msg)
                                .setPositiveButton("确定", null)
                                .show();
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static class BackupProgressHolder {
        AlertDialog dialog;
        TextView tvStage;
        TextView tvDetail;
        ProgressBar progressBar;
    }

    private BackupProgressHolder showBackupProgressDialog(String title) {
        AlertDialog.Builder builder = new com.xm486.pimet.ui.Icons.CompatDialogBuilder(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0D1117);
        int pad = dpToPx(16);
        root.setPadding(pad, pad, pad, pad);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextColor(0xFFF0F6FC);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        tvTitle.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
        root.addView(tvTitle);

        TextView tvStage = new TextView(this);
        tvStage.setText("准备开始...");
        tvStage.setTextColor(0xFF58A6FF);
        tvStage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tvStage.setPadding(0, dpToPx(8), 0, dpToPx(4));
        root.addView(tvStage);

        ProgressBar progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        LinearLayout.LayoutParams pblp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(8));
        pblp.topMargin = dpToPx(4);
        pblp.bottomMargin = dpToPx(8);
        root.addView(progressBar, pblp);

        TextView tvDetail = new TextView(this);
        tvDetail.setText("");
        tvDetail.setTextColor(0xFF8B949E);
        tvDetail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvDetail.setTypeface(Typeface.MONOSPACE);
        tvDetail.setSingleLine(true);
        tvDetail.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        root.addView(tvDetail);

        builder.setView(root);
        builder.setCancelable(false);
        AlertDialog d = builder.create();

        Window window = d.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.90f);
            window.setAttributes(lp);
        }
        d.show();

        BackupProgressHolder holder = new BackupProgressHolder();
        holder.dialog = d;
        holder.tvStage = tvStage;
        holder.tvDetail = tvDetail;
        holder.progressBar = progressBar;
        return holder;
    }
}
