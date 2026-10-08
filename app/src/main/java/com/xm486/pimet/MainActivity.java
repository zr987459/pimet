package com.xm486.pimet;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.xm486.pimet.proot.PiWebManager;
import com.xm486.pimet.proot.ProotManager;
import com.xm486.pimet.proot.ProotSession;
import com.xm486.pimet.terminal.AnsiParser;

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

/**
 * PiMet 主界面：深度融合 PRoot 独立 Linux 容器、全功能 Web 控制台、交互终端与系统设置
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // 页面与导航 Tab
    private View viewLaunch;
    private View viewPiWeb;
    private View viewTerminal;
    private View viewSettings;

    private LinearLayout tabLaunch;
    private LinearLayout tabPiWeb;
    private LinearLayout tabTerminal;
    private LinearLayout tabSettings;

    private TextView tabLaunchText;
    private TextView tabPiWebText;
    private TextView tabTerminalText;
    private TextView tabSettingsText;

    // 顶部栏
    private View btnTopBrowser;

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
    private TextView termTitleTv;
    private TextView btnTermFontDec;
    private TextView btnTermFontInc;
    private View btnTermQuickWeb;
    private View btnTermReconnect;
    private TextView tabTermSession1;
    private TextView tabTermSession2;
    private View btnTermNewSession;
    private float currentTermFontSize = 12.0f;

    // 布局全屏与增强组件
    private View appBar;
    private View bottomNavBar;
    private ProgressBar piWebProgressBar;
    private FrameLayout floatingMenuContainer;
    private View floatingMenuVertical;
    private TextView floatingBall;
    private TextView btnFloatFullscreen;
    private View btnFloatTerminal;
    private View btnFloatReload;
    private TextView btnFloatZoom;
    private View btnFloatBrowser;
    private View btnFloatClose;
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

    // 多终端会话管理 (Multi-Session Terminal)
    private static class TermSessionHolder {
        ProotSession session;
        final SpannableStringBuilder buffer = new SpannableStringBuilder();
        final AnsiParser ansi = new AnsiParser();
        boolean started = false;
        final String name;

        TermSessionHolder(String name) {
            this.name = name;
        }
    }

    private final TermSessionHolder[] termSessions = new TermSessionHolder[]{
            new TermSessionHolder("终端 1 (主会话)"),
            new TermSessionHolder("终端 2")
    };
    private int activeSessionIdx = 0;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isPiWebAlive = false;
    private boolean isDeploying = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        initNavigation();
        initLaunchPanel();
        initPiWebView();
        initTerminalPanel();
        initSettingsPanel();

        // 启动主终端会话
        startSession(0);

        // 首次状态自检
        checkServiceStatus();
    }

    private String getPiWebUrl() {
        int port = PiMetConfig.getWebPort(this);
        return "http://127.0.0.1:" + port;
    }

    private void initViews() {
        // 四个主视图
        viewLaunch = findViewById(R.id.viewLaunch);
        viewPiWeb = findViewById(R.id.viewPiWeb);
        viewTerminal = findViewById(R.id.viewTerminal);
        viewSettings = findViewById(R.id.viewSettings);

        // 底栏 Tab
        tabLaunch = findViewById(R.id.tabLaunch);
        tabPiWeb = findViewById(R.id.tabPiWeb);
        tabTerminal = findViewById(R.id.tabTerminal);
        tabSettings = findViewById(R.id.tabSettings);

        tabLaunchText = findViewById(R.id.tabLaunchText);
        tabPiWebText = findViewById(R.id.tabPiWebText);
        tabTerminalText = findViewById(R.id.tabTerminalText);
        tabSettingsText = findViewById(R.id.tabSettingsText);

        // 顶栏与底栏
        appBar = findViewById(R.id.appBar);
        bottomNavBar = findViewById(R.id.bottomNavBar);

        // 顶栏
        btnTopBrowser = findViewById(R.id.btnTopBrowser);
        btnTopBrowser.setOnClickListener(v -> openExternalBrowser());

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
        btnFloatFullscreen = findViewById(R.id.btnFloatFullscreen);
        btnFloatTerminal = findViewById(R.id.btnFloatTerminal);
        btnFloatReload = findViewById(R.id.btnFloatReload);
        btnFloatZoom = findViewById(R.id.btnFloatZoom);
        btnFloatBrowser = findViewById(R.id.btnFloatBrowser);
        btnFloatClose = findViewById(R.id.btnFloatClose);

        // Terminal 组件
        termTitleTv = findViewById(R.id.termTitleTv);
        btnTermFontDec = findViewById(R.id.btnTermFontDec);
        btnTermFontInc = findViewById(R.id.btnTermFontInc);
        btnTermReconnect = findViewById(R.id.btnTermReconnect);
        btnClear = findViewById(R.id.btnClear);
        btnCtrlC = findViewById(R.id.btnCtrlC);
        btnTermQuickWeb = findViewById(R.id.btnTermQuickWeb);
        tabTermSession1 = findViewById(R.id.tabTermSession1);
        tabTermSession2 = findViewById(R.id.tabTermSession2);
        btnTermNewSession = findViewById(R.id.btnTermNewSession);
        terminalOutput = findViewById(R.id.terminalOutput);
        terminalScrollView = findViewById(R.id.terminalScrollView);
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
    }

    private void initNavigation() {
        tabLaunch.setOnClickListener(v -> switchTab(0));
        tabPiWeb.setOnClickListener(v -> switchTab(1));
        tabTerminal.setOnClickListener(v -> switchTab(2));
        tabSettings.setOnClickListener(v -> switchTab(3));
    }

    private void switchTab(int index) {
        viewLaunch.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        viewPiWeb.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        viewTerminal.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        viewSettings.setVisibility(index == 3 ? View.VISIBLE : View.GONE);

        // 更新底栏颜色
        int activeColor = 0xFF58A6FF;
        int normalColor = 0xFF8B949E;

        tabLaunchText.setTextColor(index == 0 ? activeColor : normalColor);
        tabPiWebText.setTextColor(index == 1 ? activeColor : normalColor);
        tabTerminalText.setTextColor(index == 2 ? activeColor : normalColor);
        tabSettingsText.setTextColor(index == 3 ? activeColor : normalColor);

        if (index == 0) {
            checkServiceStatus();
            refreshLaunchLog();
        } else if (index == 1) {
            updatePiWebDisplay();
        } else if (index == 3) {
            refreshStorageSize();
        }
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
                appendTerminalLog(log);
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
                        // 重置终端会话状态，允许进入时立即启动 Bash
                        for (TermSessionHolder holder : termSessions) {
                            if (holder != null) {
                                holder.started = false;
                                if (holder.session != null) {
                                    holder.session.close();
                                }
                            }
                        }
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
                        floatingMenuContainer.setTranslationX(floatInitialX + dx);
                        floatingMenuContainer.setTranslationY(floatInitialY + dy);
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

        btnFloatClose.setOnClickListener(v -> floatingMenuVertical.setVisibility(View.GONE));
        btnFloatFullscreen.setOnClickListener(v -> {
            toggleFullscreen(!isFullscreen);
            floatingMenuVertical.setVisibility(View.GONE);
        });
        btnFloatTerminal.setOnClickListener(v -> {
            floatingMenuVertical.setVisibility(View.GONE);
            switchTab(2);
        });
        btnFloatReload.setOnClickListener(v -> {
            floatingMenuVertical.setVisibility(View.GONE);
            if (isPiWebAlive) {
                piWebWebView.reload();
            } else {
                updatePiWebDisplay();
            }
        });
        btnFloatZoom.setOnClickListener(v -> cycleWebZoom());
        btnFloatBrowser.setOnClickListener(v -> {
            floatingMenuVertical.setVisibility(View.GONE);
            openExternalBrowser();
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
        appBar.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        bottomNavBar.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        btnFloatFullscreen.setText(fullscreen ? "✕" : "⛶");
        Toast.makeText(this, fullscreen ? "已进入沉浸模式 (可拖拽悬浮球随时切换)" : "已退出全屏", Toast.LENGTH_SHORT).show();
    }

    private void updatePiWebDisplay() {
        int port = PiMetConfig.getWebPort(this);
        String url = getPiWebUrl();

        new Thread(() -> {
            boolean alive = ProotManager.isPiWebPortAlive(port);
            mainHandler.post(() -> {
                isPiWebAlive = alive;
                if (alive) {
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

    private void showPiWebOffline(boolean offline) {
        piWebOfflineCard.setVisibility(offline ? View.VISIBLE : View.GONE);
        piWebWebView.setVisibility(offline ? View.GONE : View.VISIBLE);
        int port = PiMetConfig.getWebPort(this);
        piWebOfflineSubTv.setText("端口 " + port + " 尚未启动监听，请先启动服务。");
    }

    // ================= PRoot 终端 (多窗口 / 专业快捷键) =================
    private void initTerminalPanel() {
        btnSend.setOnClickListener(v -> sendCommand());
        commandInput.setOnEditorActionListener((v, actionId, event) -> {
            sendCommand();
            return true;
        });

        // 字体缩放控制
        currentTermFontSize = PiMetConfig.getTermFontSize(this);
        terminalOutput.setTextSize(currentTermFontSize);
        btnTermFontDec.setOnClickListener(v -> adjustTermFontSize(-1.0f));
        btnTermFontInc.setOnClickListener(v -> adjustTermFontSize(1.0f));

        // 右上角快速重连与切回工作台
        btnTermReconnect.setOnClickListener(v -> {
            TermSessionHolder cur = termSessions[activeSessionIdx];
            if (cur.session != null) {
                cur.session.close();
            }
            cur.started = false;
            startSession(activeSessionIdx);
            Toast.makeText(this, "正在重新连接终端...", Toast.LENGTH_SHORT).show();
        });
        btnTermQuickWeb.setOnClickListener(v -> switchTab(1));

        // 多会话 Tab 切换
        tabTermSession1.setOnClickListener(v -> selectSession(0));
        tabTermSession2.setOnClickListener(v -> selectSession(1));
        btnTermNewSession.setOnClickListener(v -> selectSession(1));

        btnClear.setOnClickListener(v -> {
            TermSessionHolder cur = termSessions[activeSessionIdx];
            cur.buffer.clear();
            cur.ansi.reset();
            terminalOutput.setText("");
        });

        btnCtrlC.setOnClickListener(v -> {
            TermSessionHolder cur = termSessions[activeSessionIdx];
            if (cur.session != null) {
                cur.session.sendCtrlC();
                Toast.makeText(this, "已向终端 " + (activeSessionIdx + 1) + " 发送 Ctrl+C", Toast.LENGTH_SHORT).show();
            }
        });

        // 快捷键栏绑定
        setupKeyButton(R.id.keyCtrlC, "\u0003");
        setupKeyButton(R.id.keyCtrlD, "\u0004");
        setupKeyButton(R.id.keyCtrlL, "\u000C");
        setupKeyButton(R.id.keyTab, "\t");
        setupKeyButton(R.id.keyEsc, "\u001B");
        setupKeyButton(R.id.keyPi, "pi\n");
        setupKeyButton(R.id.keyPiHelp, "pi -h\n");
        setupKeyButton(R.id.keyPiModel, "/model\n");
        setupKeyButton(R.id.keyPiLogin, "/login\n");
        setupKeyButton(R.id.keyTilde, "~");
        setupKeyButton(R.id.keySlash, "/");
        setupKeyButton(R.id.keyDash, "-");
        setupKeyButton(R.id.keyPipe, "|");
        setupKeyButton(R.id.keyGt, ">");
        setupKeyButton(R.id.keyAmp, "&");
        setupKeyButton(R.id.keyNodeV, "node -v\n");
        setupKeyButton(R.id.keyNpmV, "npm -v\n");
        setupKeyButton(R.id.keyPs, "ps -ef\n");
        setupKeyButton(R.id.keyTop, "top\n");
        setupKeyButton(R.id.keyClear, "clear\n");
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

    private void selectSession(int idx) {
        if (idx < 0 || idx >= termSessions.length) return;
        activeSessionIdx = idx;
        TermSessionHolder cur = termSessions[idx];
        if (!cur.started || cur.session == null || !cur.session.isRunning()) {
            startSession(idx);
        }
        updateSessionTabUi();
        terminalOutput.setText(termSessions[idx].buffer);
        terminalScrollView.post(() -> terminalScrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void updateSessionTabUi() {
        if (activeSessionIdx == 0) {
            tabTermSession1.setBackgroundResource(R.drawable.bg_btn_primary);
            tabTermSession1.setTextColor(0xFFFFFFFF);
            tabTermSession2.setBackgroundResource(R.drawable.bg_btn_secondary);
            tabTermSession2.setTextColor(0xFF8B949E);
            termTitleTv.setText("💻 PRoot 终端 (会话 1)");
        } else {
            tabTermSession1.setBackgroundResource(R.drawable.bg_btn_secondary);
            tabTermSession1.setTextColor(0xFF8B949E);
            tabTermSession2.setBackgroundResource(R.drawable.bg_btn_primary);
            tabTermSession2.setTextColor(0xFFFFFFFF);
            termTitleTv.setText("💻 PRoot 终端 (会话 2)");
        }
    }

    private void setupKeyButton(int viewId, String keySequence) {
        View v = findViewById(viewId);
        if (v != null) {
            v.setOnClickListener(view -> {
                TermSessionHolder cur = termSessions[activeSessionIdx];
                if (cur != null && cur.session != null) {
                    cur.session.write(keySequence);
                }
            });
        }
    }

    private void startSession(int idx) {
        TermSessionHolder holder = termSessions[idx];
        if (holder.session != null && holder.session.isRunning()) return;

        if (!ProotManager.isRootfsInstalled(this)) {
            holder.started = false;
            holder.buffer.clear();
            holder.ansi.reset();
            holder.buffer.append("\u001B[33m• Linux 容器系统尚未部署，请先在【控制中心】点击一键部署！\u001B[0m\r\n");
            if (activeSessionIdx == idx) {
                terminalOutput.setText(holder.buffer);
            }
            return;
        }

        holder.buffer.clear();
        holder.ansi.reset();
        holder.started = true;
        if (holder.session != null) {
            holder.session.close();
        }
        holder.session = new ProotSession(this, new ProotSession.OutputListener() {
            @Override
            public void onOutput(String text) {
                appendTerminalLog(idx, text);
            }

            @Override
            public void onExit(int code) {
                holder.started = false;
                appendTerminalLog(idx, "\n\u001B[33m[会话 " + (idx + 1) + " 已退出, 退出码: " + code + ", 点击右上角重连]\u001B[0m\n");
            }
        });
        holder.session.start();
    }

    private void appendTerminalLog(String text) {
        appendTerminalLog(activeSessionIdx, text);
    }

    private void appendTerminalLog(int sessionIdx, String text) {
        if (text == null) return;
        mainHandler.post(() -> {
            TermSessionHolder holder = termSessions[sessionIdx];
            holder.ansi.appendAnsiText(holder.buffer, text);
            if (holder.buffer.length() > 30000) {
                holder.buffer.delete(0, 6000);
            }
            if (activeSessionIdx == sessionIdx) {
                terminalOutput.setText(holder.buffer);
                terminalScrollView.post(() -> terminalScrollView.fullScroll(ScrollView.FOCUS_DOWN));
            }
        });
    }

    private void sendCommand() {
        String cmd = commandInput.getText().toString();
        TermSessionHolder cur = termSessions[activeSessionIdx];
        if (cur != null && cur.session != null && !TextUtils.isEmpty(cmd)) {
            cur.session.write(cmd + "\n");
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
            Toast.makeText(this, "✔ AI 凭据已保存并同步至 PRoot 容器！", Toast.LENGTH_SHORT).show();
        });

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
            TermSessionHolder cur = termSessions[activeSessionIdx];
            if (cur != null && cur.session != null) {
                cur.session.write("npm cache clean --force\n");
                Toast.makeText(this, "已在容器内发送 npm 缓存清理指令", Toast.LENGTH_SHORT).show();
                switchTab(2);
            }
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

        refreshStorageSize();
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        for (TermSessionHolder holder : termSessions) {
            if (holder != null && holder.session != null) {
                holder.session.close();
            }
        }
    }
}
