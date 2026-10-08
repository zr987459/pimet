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
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
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

import java.io.File;
import java.util.Arrays;

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
    private View btnDeploy;
    private View btnViewLog;

    // 设置视图组件
    private EditText settingsPortInput;
    private View btnSavePort;
    private TextView btnRegistryMirror;
    private TextView btnRegistryOfficial;
    private TextView settingsStorageTv;
    private View btnClearNpmCache;
    private View btnResetContainer;

    // 后台与状态调度
    private ProotSession prootSession;
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

        // 启动终端 Session
        startTerminalSession();

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
        btnLaunchLogRefresh = findViewById(R.id.btnLaunchLogRefresh);
        btnLaunchLogClear = findViewById(R.id.btnLaunchLogClear);
        launchLogTv = findViewById(R.id.launchLogTv);

        // Pi-Web 组件
        piWebStatusDot = findViewById(R.id.piWebStatusDot);
        piWebTitleTv = findViewById(R.id.piWebTitleTv);
        piWebReloadBtn = findViewById(R.id.piWebReloadBtn);
        piWebBrowserBtn = findViewById(R.id.piWebBrowserBtn);
        piWebWebView = findViewById(R.id.piWebWebView);
        piWebOfflineCard = findViewById(R.id.piWebOfflineCard);
        piWebOfflineSubTv = findViewById(R.id.piWebOfflineSubTv);
        piWebWakeBtn = findViewById(R.id.piWebWakeBtn);

        // Terminal 组件
        terminalOutput = findViewById(R.id.terminalOutput);
        terminalScrollView = findViewById(R.id.terminalScrollView);
        commandInput = findViewById(R.id.commandInput);
        btnSend = findViewById(R.id.btnSend);
        btnClear = findViewById(R.id.btnClear);
        btnCtrlC = findViewById(R.id.btnCtrlC);
        btnDeploy = findViewById(R.id.btnDeploy);
        btnViewLog = findViewById(R.id.btnViewLog);

        // Settings 组件
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

        btnLaunchLogRefresh.setOnClickListener(v -> refreshLaunchLog());
        btnLaunchLogClear.setOnClickListener(v -> {
            PiWebManager.clearLog(this);
            launchLogTv.setText("[日志已清空]");
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
        });

        refreshLaunchLog();
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

        PiWebManager.startPiWebDaemon(this, new PiWebManager.PiWebListener() {
            @Override
            public void onLog(String log) {
                if (prootSession != null) {
                    prootSession.appendOutput(log);
                }
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
        Toast.makeText(this, "正在重启 Pi-Web...", Toast.LENGTH_SHORT).show();
        PiWebManager.stopPiWeb();
        mainHandler.postDelayed(this::startPiWebService, 1000);
    }

    private void stopPiWebService() {
        PiWebManager.stopPiWeb();
        isPiWebAlive = false;
        updateLaunchStatusUI(false);
        Toast.makeText(this, "Pi-Web 服务已停止", Toast.LENGTH_SHORT).show();
        refreshLaunchLog();
    }

    private void triggerFullDeploy() {
        if (isDeploying) {
            Toast.makeText(this, "正在部署中...", Toast.LENGTH_SHORT).show();
            return;
        }
        isDeploying = true;
        launchProgressBar.setVisibility(View.VISIBLE);
        switchTab(2); // 自动切到终端查看实时输出

        new Thread(() -> {
            mainHandler.post(() -> appendTerminalLog("\u001B[33m🚀 开始执行一键部署流水线...\u001B[0m\n"));

            if (!ProotManager.isInstalled(this)) {
                mainHandler.post(() -> appendTerminalLog("\u001B[36m• 正在从镜像源提取 Linux 根文件系统...\u001B[0m\n"));
                boolean ok = ProotManager.installFromAssets(this);
                if (!ok) {
                    mainHandler.post(() -> {
                        isDeploying = false;
                        launchProgressBar.setVisibility(View.GONE);
                        appendTerminalLog("\u001B[31m❌ 根文件系统解压失败！\u001B[0m\n");
                    });
                    return;
                }
            }

            mainHandler.post(() -> {
                isDeploying = false;
                startPiWebService();
            });
        }).start();
    }

    // ================= Pi-Web 工作台 =================
    @SuppressLint("SetJavaScriptEnabled")
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

        piWebWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showPiWebOffline(true);
                }
            }
        });

        piWebWebView.setWebChromeClient(new WebChromeClient());

        piWebReloadBtn.setOnClickListener(v -> updatePiWebDisplay());
        piWebBrowserBtn.setOnClickListener(v -> openExternalBrowser());
        piWebWakeBtn.setOnClickListener(v -> startPiWebService());
    }

    private void updatePiWebDisplay() {
        int port = PiMetConfig.getWebPort(this);
        String url = getPiWebUrl();
        piWebTitleTv.setText(url);

        new Thread(() -> {
            boolean alive = ProotManager.isPiWebPortAlive(port);
            mainHandler.post(() -> {
                isPiWebAlive = alive;
                if (alive) {
                    showPiWebOffline(false);
                    piWebStatusDot.setBackgroundResource(R.drawable.bg_status_dot_green);
                    if (piWebWebView.getUrl() == null || !piWebWebView.getUrl().startsWith("http://127.0.0.1:" + port)) {
                        piWebWebView.loadUrl(url);
                    } else {
                        piWebWebView.reload();
                    }
                } else {
                    showPiWebOffline(true);
                    piWebStatusDot.setBackgroundResource(R.drawable.bg_status_dot_gray);
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

    // ================= PRoot 终端 =================
    private void initTerminalPanel() {
        btnSend.setOnClickListener(v -> sendCommand());
        commandInput.setOnEditorActionListener((v, actionId, event) -> {
            sendCommand();
            return true;
        });

        btnClear.setOnClickListener(v -> {
            if (prootSession != null) prootSession.clearBuffer();
            terminalOutput.setText("");
        });

        btnCtrlC.setOnClickListener(v -> {
            if (prootSession != null) {
                prootSession.sendCtrlC();
                Toast.makeText(this, "已发送 Ctrl+C 中断信号", Toast.LENGTH_SHORT).show();
            }
        });

        btnDeploy.setOnClickListener(v -> triggerFullDeploy());
        btnViewLog.setOnClickListener(v -> {
            String log = PiWebManager.readLastLog(this);
            appendTerminalLog("\n\u001B[33m--- /root/pi-web.log 最新日志 ---\u001B[0m\n" + log + "\n");
        });

        // 快捷键栏绑定
        setupKeyButton(R.id.keyCtrlC, "\u0003");
        setupKeyButton(R.id.keyTab, "\t");
        setupKeyButton(R.id.keyEsc, "\u001B");
        setupKeyButton(R.id.keyTilde, "~");
        setupKeyButton(R.id.keySlash, "/");
        setupKeyButton(R.id.keyDash, "-");
        setupKeyButton(R.id.keyPipe, "|");
        setupKeyButton(R.id.keyNodeV, "node -v\n");
        setupKeyButton(R.id.keyNpmV, "npm -v\n");
        setupKeyButton(R.id.keyPi, "pi --help\n");
        setupKeyButton(R.id.keyClear, "clear\n");
    }

    private void setupKeyButton(int viewId, String keySequence) {
        View v = findViewById(viewId);
        if (v != null) {
            v.setOnClickListener(view -> {
                if (prootSession != null) {
                    prootSession.write(keySequence);
                }
            });
        }
    }

    private void startTerminalSession() {
        prootSession = new ProotSession(this);
        prootSession.setOutputListener(text -> mainHandler.post(() -> {
            appendTerminalLog(text);
        }));
        prootSession.startSession();
    }

    private void appendTerminalLog(String text) {
        SpannableStringBuilder parsed = AnsiParser.parse(text);
        terminalOutput.append(parsed);
        terminalScrollView.post(() -> terminalScrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void sendCommand() {
        String cmd = commandInput.getText().toString();
        if (prootSession != null && !TextUtils.isEmpty(cmd)) {
            prootSession.write(cmd + "\n");
            commandInput.setText("");
        }
    }

    // ================= 设置面板 =================
    private void initSettingsPanel() {
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
            PiMetConfig.setNpmRegistry(this, PiMetConfig.DEFAULT_NPM_REGISTRY);
            updateRegistryButtons();
            Toast.makeText(this, "已切换为国内加速源", Toast.LENGTH_SHORT).show();
        });

        btnRegistryOfficial.setOnClickListener(v -> {
            PiMetConfig.setNpmRegistry(this, PiMetConfig.OFFICIAL_NPM_REGISTRY);
            updateRegistryButtons();
            Toast.makeText(this, "已切换为官方 npm 源", Toast.LENGTH_SHORT).show();
        });

        btnClearNpmCache.setOnClickListener(v -> {
            if (prootSession != null) {
                prootSession.write("npm cache clean --force\n");
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
    protected void onDestroy() {
        super.onDestroy();
        if (prootSession != null) {
            prootSession.destroy();
        }
    }
}
