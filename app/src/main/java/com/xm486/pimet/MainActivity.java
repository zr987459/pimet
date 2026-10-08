package com.xm486.pimet;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.xm486.pimet.proot.PiWebManager;
import com.xm486.pimet.proot.ProotManager;
import com.xm486.pimet.proot.ProotSession;
import com.xm486.pimet.terminal.AnsiParser;

/**
 * PiMet 主界面：深度融合 PRoot 独立 Linux 容器、全功能 Web 控制台与交互终端
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String PI_WEB_URL = "http://127.0.0.1:30141";

    // 页面与导航
    private View viewPiWeb;
    private View viewTerminal;
    private LinearLayout tabPiWeb;
    private LinearLayout tabTerminal;
    private TextView tabPiWebText;
    private TextView tabTerminalText;
    private TextView tabPiWebIcon;
    private TextView tabTerminalIcon;

    // Pi-Web 相关视图
    private View piWebStatusDot;
    private TextView piWebTitleTv;
    private View piWebReloadBtn;
    private View piWebBrowserBtn;
    private View piWebTerminalBtn;
    private WebView piWebWebView;
    private View piWebOfflineCard;
    private Button piWebLaunchBtn;
    private Button piWebToTerminalBtn;

    // 终端相关视图
    private TextView termStatusTv;
    private Button termDeployBtn;
    private Button termLogBtn;
    private Button termClearBtn;
    private Button termKillBtn;
    private ScrollView termScrollView;
    private TextView termConsoleTv;
    private LinearLayout termExtraKeysContainer;
    private EditText termCommandInput;
    private Button termSendBtn;

    // 运行时会话与解析器
    private ProotSession prootSession;
    private final SpannableStringBuilder termBuffer = new SpannableStringBuilder();
    private final AnsiParser ansiParser = new AnsiParser();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean isPiWebLoaded = false;
    private boolean isWatchdogRunning = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        try {
            setupWebView();
        } catch (Throwable t) {
            android.util.Log.e(TAG, "Failed to setup WebView", t);
        }
        setupTerminal();
        setupExtraKeys();
        setupTabs();
        startPortWatchdog();
    }

    private void initViews() {
        viewPiWeb = findViewById(R.id.viewPiWeb);
        viewTerminal = findViewById(R.id.viewTerminal);

        tabPiWeb = findViewById(R.id.tabPiWeb);
        tabTerminal = findViewById(R.id.tabTerminal);
        tabPiWebText = findViewById(R.id.tabPiWebText);
        tabTerminalText = findViewById(R.id.tabTerminalText);
        tabPiWebIcon = findViewById(R.id.tabPiWebIcon);
        tabTerminalIcon = findViewById(R.id.tabTerminalIcon);

        piWebStatusDot = findViewById(R.id.piWebStatusDot);
        piWebTitleTv = findViewById(R.id.piWebTitleTv);
        piWebReloadBtn = findViewById(R.id.piWebReloadBtn);
        piWebBrowserBtn = findViewById(R.id.piWebBrowserBtn);
        piWebTerminalBtn = findViewById(R.id.piWebTerminalBtn);
        piWebWebView = findViewById(R.id.piWebWebView);
        piWebOfflineCard = findViewById(R.id.piWebOfflineCard);
        piWebLaunchBtn = findViewById(R.id.piWebLaunchBtn);
        piWebToTerminalBtn = findViewById(R.id.piWebToTerminalBtn);

        termStatusTv = findViewById(R.id.termStatusTv);
        termDeployBtn = findViewById(R.id.termDeployBtn);
        termLogBtn = findViewById(R.id.termLogBtn);
        termClearBtn = findViewById(R.id.termClearBtn);
        termKillBtn = findViewById(R.id.termKillBtn);
        termScrollView = findViewById(R.id.termScrollView);
        termConsoleTv = findViewById(R.id.termConsoleTv);
        termExtraKeysContainer = findViewById(R.id.termExtraKeysContainer);
        termCommandInput = findViewById(R.id.termCommandInput);
        termSendBtn = findViewById(R.id.termSendBtn);

        // 绑定常用点击事件
        piWebReloadBtn.setOnClickListener(v -> piWebWebView.reload());
        piWebBrowserBtn.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(PI_WEB_URL));
                startActivity(intent);
            } catch (Throwable ignored) {}
        });
        piWebTerminalBtn.setOnClickListener(v -> switchTab(false));
        piWebToTerminalBtn.setOnClickListener(v -> switchTab(false));

        piWebLaunchBtn.setOnClickListener(v -> triggerDeploy());
        termDeployBtn.setOnClickListener(v -> triggerDeploy());

        termLogBtn.setOnClickListener(v -> {
            String log = PiWebManager.readLastLog(this);
            appendTerminalOutput("\n\u001B[1;36m===== pi-web.log =====\u001B[0m\n" + log + "\n");
        });

        termClearBtn.setOnClickListener(v -> {
            termBuffer.clear();
            ansiParser.reset();
            termConsoleTv.setText("");
        });

        termKillBtn.setOnClickListener(v -> {
            if (prootSession != null) prootSession.sendCtrlC();
        });

        termSendBtn.setOnClickListener(v -> sendCommand());
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings ws = piWebWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setSupportZoom(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);

        piWebWebView.setWebChromeClient(new WebChromeClient());
        piWebWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null && url.contains("30141")) {
                    isPiWebLoaded = true;
                    piWebOfflineCard.setVisibility(View.GONE);
                    piWebWebView.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    isPiWebLoaded = false;
                    piWebOfflineCard.setVisibility(View.VISIBLE);
                    piWebWebView.setVisibility(View.GONE);
                }
            }
        });
    }

    private void setupTerminal() {
        prootSession = new ProotSession(this, new ProotSession.OutputListener() {
            @Override
            public void onOutput(String text) {
                appendTerminalOutput(text);
            }

            @Override
            public void onExit(int code) {
                appendTerminalOutput("\r\n\u001B[33m[会话已退出, code: " + code + "]\u001B[0m\r\n");
            }
        });

        // 启动交互会话
        prootSession.start();
    }

    private void setupExtraKeys() {
        String[] keys = {"Ctrl+C", "Tab", "node -v", "pi --version", "ps", "clear"};
        for (String k : keys) {
            TextView btn = new TextView(this);
            btn.setText(k);
            btn.setTextColor(Color.parseColor("#58A6FF"));
            btn.setBackgroundResource(R.drawable.bg_tab_normal);
            btn.setTextSize(11f);
            btn.setGravity(android.view.Gravity.CENTER);
            btn.setPadding(dpToPx(8), 0, dpToPx(8), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dpToPx(28)
            );
            lp.setMargins(dpToPx(3), 0, dpToPx(3), 0);
            btn.setLayoutParams(lp);

            btn.setOnClickListener(v -> {
                if ("Ctrl+C".equals(k)) {
                    if (prootSession != null) prootSession.sendCtrlC();
                } else if ("Tab".equals(k)) {
                    if (prootSession != null) prootSession.write("\t");
                } else if ("clear".equals(k)) {
                    termBuffer.clear();
                    ansiParser.reset();
                    termConsoleTv.setText("");
                } else {
                    if (prootSession != null) prootSession.write(k + "\n");
                }
            });
            termExtraKeysContainer.addView(btn);
        }
    }

    private void setupTabs() {
        tabPiWeb.setOnClickListener(v -> switchTab(true));
        tabTerminal.setOnClickListener(v -> switchTab(false));
        switchTab(true);
    }

    private void switchTab(boolean showPiWeb) {
        if (showPiWeb) {
            viewPiWeb.setVisibility(View.VISIBLE);
            viewTerminal.setVisibility(View.GONE);

            tabPiWebIcon.setTextColor(Color.parseColor("#58A6FF"));
            tabPiWebText.setTextColor(Color.parseColor("#58A6FF"));
            tabTerminalIcon.setTextColor(Color.parseColor("#8B949E"));
            tabTerminalText.setTextColor(Color.parseColor("#8B949E"));

            if (ProotManager.isPiWebPortAlive() && !isPiWebLoaded) {
                piWebWebView.loadUrl(PI_WEB_URL);
            }
        } else {
            viewPiWeb.setVisibility(View.GONE);
            viewTerminal.setVisibility(View.VISIBLE);

            tabPiWebIcon.setTextColor(Color.parseColor("#8B949E"));
            tabPiWebText.setTextColor(Color.parseColor("#8B949E"));
            tabTerminalIcon.setTextColor(Color.parseColor("#58A6FF"));
            tabTerminalText.setTextColor(Color.parseColor("#58A6FF"));

            // 滚动到底部
            termScrollView.post(() -> termScrollView.fullScroll(ScrollView.FOCUS_DOWN));
        }
    }

    private void triggerDeploy() {
        switchTab(false); // 自动切到终端查看实时输出
        appendTerminalOutput("\n\u001B[1;35m🚀 开始自动化部署与拉起 Pi-Web...\u001B[0m\n");

        PiWebManager.startOrDeploy(this, new PiWebManager.StateListener() {
            @Override
            public void onLog(String log) {
                appendTerminalOutput(log);
            }

            @Override
            public void onStarted() {
                appendTerminalOutput("\n\u001B[1;32m🎉 启动成功！正在载入 Web 界面...\u001B[0m\n");
                handler.postDelayed(() -> switchTab(true), 1200);
            }

            @Override
            public void onError(String error) {
                appendTerminalOutput("\n\u001B[1;31m✘ " + error + "\u001B[0m\n");
            }
        });
    }

    private void sendCommand() {
        String cmd = termCommandInput.getText().toString();
        if (cmd.trim().isEmpty()) return;
        termCommandInput.setText("");
        if (prootSession != null) {
            prootSession.write(cmd + "\n");
        }
    }

    private void appendTerminalOutput(String text) {
        ansiParser.appendAnsiText(termBuffer, text);
        // 限制最大行数
        if (termBuffer.length() > 50000) {
            termBuffer.delete(0, 15000);
        }
        termConsoleTv.setText(termBuffer);
        termScrollView.post(() -> termScrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void startPortWatchdog() {
        new Thread(() -> {
            while (isWatchdogRunning) {
                boolean alive = ProotManager.isPiWebPortAlive();
                handler.post(() -> {
                    if (alive) {
                        piWebStatusDot.setBackgroundColor(Color.parseColor("#2EA043"));
                        termStatusTv.setText("状态: 运行中 (30141)");
                        termStatusTv.setTextColor(Color.parseColor("#2EA043"));
                        if (!isPiWebLoaded) {
                            piWebWebView.loadUrl(PI_WEB_URL);
                        }
                    } else {
                        piWebStatusDot.setBackgroundColor(Color.parseColor("#8B949E"));
                        termStatusTv.setText("状态: 离线 (30141)");
                        termStatusTv.setTextColor(Color.parseColor("#8B949E"));
                    }
                });
                try {
                    Thread.sleep(2500);
                } catch (InterruptedException ignored) {}
            }
        }).start();
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isWatchdogRunning = false;
        if (prootSession != null) {
            prootSession.close();
        }
    }
}
