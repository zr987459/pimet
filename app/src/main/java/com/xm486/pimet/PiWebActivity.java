package com.xm486.pimet;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.xm486.pimet.pet.PetRegistry;
import com.xm486.pimet.ui.ThemeHelper;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 通用 Web 控制台（WebView 页面）。
 * - 极简超薄/可折叠顶栏，不影响网页内部 AI 界面。
 * - 外部浏览器打开时支持自选浏览器（Intent.createChooser）。
 * - 完整支持网页端相册与文件选择上传。
 */
public class PiWebActivity extends AppCompatActivity {

    private WebView webView;
    private TextView statusText;
    private View indicatorDot;
    private LinearLayout topBar;
    private View expandBtn;
    private int port;
    private String currentUrl;
    private boolean isBarCollapsed = false;

    public static final String EXTRA_PORT = "extra_port";

    private ValueCallback<Uri[]> filePathCallback;
    private final ActivityResultLauncher<Intent> fileChooserLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (filePathCallback != null) {
                    Uri[] results = null;
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Intent data = result.getData();
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
                }
            }
    );

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        int extra = getIntent().getIntExtra(EXTRA_PORT, 0);
        port = (extra >= 1 && extra <= 65535) ? extra : PetRegistry.getPiWebPort(this);
        currentUrl = "http://127.0.0.1:" + port + "/";
        buildUi();
        loadWeb();
    }

    private String getTargetName(int p) {
        if (p == PetRegistry.DEFAULT_PIWEB_PORT) return "pi-web";
        if (p == PetRegistry.DEFAULT_CB_PORT) return "ClawBench";
        if (p == PetRegistry.DEFAULT_OPERIT_PORT) return "Operit";
        if (p == PetRegistry.DEFAULT_RK_PORT) return "RikkaHub";
        return "Console";
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);

        // ---- WebView（全屏底层展示，最大化视野） ----
        LinearLayout webContainer = new LinearLayout(this);
        webContainer.setOrientation(LinearLayout.VERTICAL);

        // 占位给顶栏（未折叠时），折叠后为 0
        View spacer = new View(this);
        webContainer.addView(spacer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));

        webView = new WebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setSupportZoom(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null && url.startsWith("devpetm://terminal")) {
                    Intent intent = new Intent(PiWebActivity.this, com.xm486.pimet.terminal.TerminalActivity.class);
                    startActivity(intent);
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (url != null && !url.startsWith("about:") && !url.isEmpty()) {
                    currentUrl = url;
                    statusText.setText(getTargetName(port) + " · :" + port);
                    statusText.setTextColor(getColor(R.color.feedback_success));
                    if (indicatorDot != null) {
                        indicatorDot.setBackgroundColor(getColor(R.color.feedback_success));
                    }
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (PiWebActivity.this.filePathCallback != null) {
                    PiWebActivity.this.filePathCallback.onReceiveValue(null);
                }
                PiWebActivity.this.filePathCallback = filePathCallback;

                Intent intent = null;
                if (fileChooserParams != null) {
                    try {
                        intent = fileChooserParams.createIntent();
                    } catch (Throwable ignored) {}
                }
                if (intent == null) {
                    intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                }
                try {
                    fileChooserLauncher.launch(Intent.createChooser(intent, "选择文件或图片"));
                } catch (ActivityNotFoundException e) {
                    Intent fallback = new Intent(Intent.ACTION_PICK);
                    fallback.setType("image/*");
                    try {
                        fileChooserLauncher.launch(Intent.createChooser(fallback, "选择图片"));
                    } catch (Throwable t) {
                        Toast.makeText(PiWebActivity.this, "未能启动文件选择器", Toast.LENGTH_SHORT).show();
                        if (PiWebActivity.this.filePathCallback != null) {
                            PiWebActivity.this.filePathCallback.onReceiveValue(null);
                            PiWebActivity.this.filePathCallback = null;
                        }
                    }
                }
                return true;
            }
        });

        webContainer.addView(webView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(webContainer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // ---- 超薄微型悬浮顶栏（高度仅 34dp） ----
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(10), 0, dp(8), 0);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(0xE6201B2E); // 半透明深色背景
        barBg.setCornerRadius(0);
        topBar.setBackground(barBg);

        // 状态指示圆点
        indicatorDot = new View(this);
        indicatorDot.setBackgroundColor(getColor(R.color.text_tertiary));
        topBar.addView(indicatorDot, new LinearLayout.LayoutParams(dp(6), dp(6)));

        // 状态文本
        statusText = new TextView(this);
        statusText.setText(getTargetName(port) + " :" + port);
        statusText.setTextSize(11f);
        statusText.setTextColor(0xDDFFFFFF);
        statusText.setPadding(dp(6), 0, dp(4), 0);
        topBar.addView(statusText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // 刷新小按钮
        Button refreshBtn = createTinyButton("🔄", v -> {
            if (webView != null) webView.reload();
        });
        topBar.addView(refreshBtn);

        // 外部浏览器选择器按钮
        Button openExternalBtn = createTinyButton("🌐 浏览器", v -> openInExternalBrowser());
        LinearLayout.LayoutParams extLp = (LinearLayout.LayoutParams) openExternalBtn.getLayoutParams();
        extLp.leftMargin = dp(4);
        topBar.addView(openExternalBtn);

        // 收起顶栏按钮
        Button collapseBtn = createTinyButton("▲", v -> {
            isBarCollapsed = true;
            topBar.setVisibility(View.GONE);
            spacer.getLayoutParams().height = 0;
            spacer.requestLayout();
            if (expandBtn != null) expandBtn.setVisibility(View.VISIBLE);
        });
        LinearLayout.LayoutParams colLp = (LinearLayout.LayoutParams) collapseBtn.getLayoutParams();
        colLp.leftMargin = dp(4);
        topBar.addView(collapseBtn);

        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(34));
        barLp.gravity = Gravity.TOP;
        root.addView(topBar, barLp);

        // 折叠后右上角微型药丸恢复按钮
        Button restoreBtn = new Button(this);
        restoreBtn.setText("🌐");
        restoreBtn.setTextSize(11f);
        restoreBtn.setTextColor(0xFFFFFFFF);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setColor(0xCC302A45);
        dotBg.setCornerRadius(dp(12));
        restoreBtn.setBackground(dotBg);
        restoreBtn.setPadding(0, 0, 0, 0);
        restoreBtn.setVisibility(View.GONE);
        restoreBtn.setOnClickListener(v -> {
            isBarCollapsed = false;
            topBar.setVisibility(View.VISIBLE);
            spacer.getLayoutParams().height = dp(34);
            spacer.requestLayout();
            restoreBtn.setVisibility(View.GONE);
        });
        expandBtn = restoreBtn;
        FrameLayout.LayoutParams expLp = new FrameLayout.LayoutParams(dp(28), dp(28));
        expLp.gravity = Gravity.TOP | Gravity.END;
        expLp.topMargin = dp(4);
        expLp.rightMargin = dp(6);
        root.addView(restoreBtn, expLp);

        setContentView(root);
    }

    private Button createTinyButton(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(10.5f);
        b.setTextColor(0xFFFFFFFF);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x33FFFFFF);
        bg.setCornerRadius(dp(6));
        b.setBackground(bg);
        b.setPadding(dp(6), 0, dp(6), 0);
        b.setMinHeight(0);
        b.setMinWidth(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(24));
        b.setLayoutParams(lp);
        b.setOnClickListener(listener);
        return b;
    }

    private void openInExternalBrowser() {
        try {
            String url = currentUrl != null && !currentUrl.isEmpty() ? currentUrl : "http://127.0.0.1:" + port + "/";
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(Intent.createChooser(intent, "选择外部浏览器打开"));
        } catch (Throwable t) {
            Toast.makeText(this, "打开外部浏览器失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void loadWeb() {
        final String url = "http://127.0.0.1:" + port + "/";
        currentUrl = url;
        statusText.setText("连接 127.0.0.1:" + port + " …");
        statusText.setTextColor(0xBBFFFFFF);
        if (indicatorDot != null) indicatorDot.setBackgroundColor(getColor(R.color.text_tertiary));

        new Thread(() -> {
            int code = probe(url);
            runOnUiThread(() -> {
                if (code >= 200 && code < 500) {
                    webView.loadUrl(url);
                } else {
                    showErrorPage();
                }
            });
        }).start();
    }

    private void showErrorPage() {
        String html = "<!DOCTYPE html><html><head>" +
                "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "</head><body style='margin:0;display:flex;align-items:center;justify-content:center;" +
                "height:100vh;background:#FBF9FF;font-family:system-ui,sans-serif;" +
                "color:#1A1626;'>" +
                "<div style='text-align:center;padding:24px;max-width:280px;'>" +
                "<div style='font-size:52px;margin-bottom:16px;'>🔌</div>" +
                "<div style='font-size:17px;font-weight:700;margin-bottom:8px;'>连接失败</div>" +
                "<div style='font-size:13px;color:#6F6A85;line-height:1.6;margin-bottom:20px;'>" +
                "无法连接 127.0.0.1:" + port + " (" + getTargetName(port) + ")。<br>" +
                "请确认该端口上的服务已在终端运行。</div>" +
                "<button id='retryBtn' style='background:#5B4BE0;color:#fff;border:none;" +
                "padding:10px 24px;border-radius:10px;font-size:14px;cursor:pointer;" +
                ">重新连接</button><br>" +
                "<a href='devpetm://terminal' style='display:inline-block;margin-top:16px;" +
                "color:#5B4BE0;text-decoration:none;font-size:13px;font-weight:600;'>" +
                "💻 打开终端从零一键部署</a>" +
                "</div><script>document.getElementById('retryBtn')" +
                ".onclick=function(){window.location.reload()};</script>" +
                "</body></html>";
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
        statusText.setText("连接失败 · 端口 " + port);
        statusText.setTextColor(getColor(R.color.feedback_error));
        if (indicatorDot != null) indicatorDot.setBackgroundColor(getColor(R.color.feedback_error));
    }

    private int probe(String urlStr) {
        try {
            URL u = new URL(urlStr);
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            c.setRequestMethod("GET");
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            c.disconnect();
            return code;
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onResume() { super.onResume(); if (webView != null) webView.onResume(); }
    @Override protected void onPause()  { if (webView != null) webView.onPause(); super.onPause(); }
    @Override protected void onDestroy() {
        if (webView != null) { webView.destroy(); webView = null; }
        super.onDestroy();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
