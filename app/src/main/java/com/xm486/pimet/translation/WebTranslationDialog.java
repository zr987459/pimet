package com.xm486.pimet.translation;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.xm486.pimet.PiMetConfig;
import com.xm486.pimet.ThemeManager;

/**
 * 网页通用翻译配置面板 (WebTranslationDialog)
 * 采用完全与 Pi-Web 官方多套主题对齐的设计系统，支持即时测试、引擎切换、呈现模式与动态增量配置
 */
public class WebTranslationDialog {

    public static void show(Context context, WebView webView) {
        if (context == null) return;
        ThemeManager.ThemePalette palette = ThemeManager.getEffectivePalette(context);

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 18);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(palette.bgPanel);

        // 标题与副标题
        TextView titleTv = new TextView(context);
        titleTv.setText("🌐 网页通用翻译配置");
        titleTv.setTextSize(17f);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        titleTv.setTextColor(palette.text);
        root.addView(titleTv);

        TextView subTitleTv = new TextView(context);
        subTitleTv.setText("无缝支持内部工作台及任意外部网站，多引擎极速解析");
        subTitleTv.setTextSize(12f);
        subTitleTv.setTextColor(palette.textMuted);
        subTitleTv.setPadding(0, dp(context, 3), 0, dp(context, 14));
        root.addView(subTitleTv);

        // 1. 翻译引擎选择区
        TextView secEngineTv = new TextView(context);
        secEngineTv.setText("1. 翻译引擎策略");
        secEngineTv.setTextSize(13.5f);
        secEngineTv.setTypeface(Typeface.DEFAULT_BOLD);
        secEngineTv.setTextColor(palette.accent);
        root.addView(secEngineTv);

        RadioGroup engineGroup = new RadioGroup(context);
        engineGroup.setOrientation(LinearLayout.VERTICAL);
        engineGroup.setPadding(0, dp(context, 6), 0, dp(context, 10));

        String curEngine = PiMetConfig.getTransEngine(context);

        RadioButton rbAuto = createRadioButton(context, palette, "智能混合 (推荐 · 优先 AI，自动降级至极速通道)");
        RadioButton rbAi = createRadioButton(context, palette, "宿主/Pi 已配置 AI (复用当前系统 DeepSeek/OpenAI)");
        RadioButton rbFree = createRadioButton(context, palette, "免配置极速通道 (无需任何 Key · 毫秒级多线路)");
        RadioButton rbCustom = createRadioButton(context, palette, "自定义专属 API (OpenAI 兼容端点 / 自建模型)");

        rbAuto.setId(View.generateViewId());
        rbAi.setId(View.generateViewId());
        rbFree.setId(View.generateViewId());
        rbCustom.setId(View.generateViewId());

        engineGroup.addView(rbAuto);
        engineGroup.addView(rbAi);
        engineGroup.addView(rbFree);
        engineGroup.addView(rbCustom);

        if (PiMetConfig.TRANS_ENGINE_AI.equals(curEngine)) {
            rbAi.setChecked(true);
        } else if (PiMetConfig.TRANS_ENGINE_FREE.equals(curEngine)) {
            rbFree.setChecked(true);
        } else if (PiMetConfig.TRANS_ENGINE_CUSTOM.equals(curEngine)) {
            rbCustom.setChecked(true);
        } else {
            rbAuto.setChecked(true);
        }

        root.addView(engineGroup);

        // 2. 自定义 API 参数区域 (可折叠 / 条件显示)
        LinearLayout customApiContainer = new LinearLayout(context);
        customApiContainer.setOrientation(LinearLayout.VERTICAL);
        customApiContainer.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 12));
        customApiContainer.setBackground(ThemeManager.createSunkenDrawable(context, palette, 10f));
        customApiContainer.setVisibility(rbCustom.isChecked() ? View.VISIBLE : View.GONE);

        TextView customHintTv = new TextView(context);
        customHintTv.setText("自定义 OpenAI 兼容接口参数:");
        customHintTv.setTextSize(12f);
        customHintTv.setTextColor(palette.textMuted);
        customApiContainer.addView(customHintTv);

        EditText etBaseUrl = createInput(context, palette, "API Base URL (如: https://api.deepseek.com/v1)");
        etBaseUrl.setText(PiMetConfig.getTransCustomBaseUrl(context));
        customApiContainer.addView(etBaseUrl);

        EditText etApiKey = createInput(context, palette, "API Key (sk-...)");
        etApiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etApiKey.setText(PiMetConfig.getTransCustomApiKey(context));
        customApiContainer.addView(etApiKey);

        EditText etModel = createInput(context, palette, "模型代号 (如: deepseek-chat)");
        etModel.setText(PiMetConfig.getTransCustomModel(context));
        customApiContainer.addView(etModel);

        root.addView(customApiContainer);

        engineGroup.setOnCheckedChangeListener((group, checkedId) -> {
            boolean isCustom = (checkedId == rbCustom.getId());
            customApiContainer.setVisibility(isCustom ? View.VISIBLE : View.GONE);
        });

        // 3. 译文呈现模式
        TextView secModeTv = new TextView(context);
        secModeTv.setText("2. 译文呈现方式");
        secModeTv.setTextSize(13.5f);
        secModeTv.setTypeface(Typeface.DEFAULT_BOLD);
        secModeTv.setTextColor(palette.accent);
        secModeTv.setPadding(0, dp(context, 12), 0, dp(context, 6));
        root.addView(secModeTv);

        RadioGroup modeGroup = new RadioGroup(context);
        modeGroup.setOrientation(LinearLayout.VERTICAL);

        RadioButton rbReplace = createRadioButton(context, palette, "直接替换原文 (纯净沉浸 · 保持原有版式)");
        RadioButton rbBilingual = createRadioButton(context, palette, "双语对照显示 (原文后标注优雅中文字样)");

        rbReplace.setId(View.generateViewId());
        rbBilingual.setId(View.generateViewId());

        modeGroup.addView(rbReplace);
        modeGroup.addView(rbBilingual);

        String curMode = PiMetConfig.getTransDisplayMode(context);
        if (PiMetConfig.TRANS_MODE_BILINGUAL.equals(curMode)) {
            rbBilingual.setChecked(true);
        } else {
            rbReplace.setChecked(true);
        }
        root.addView(modeGroup);

        // 4. 动态 DOM 监听
        CheckBox cbLive = new CheckBox(context);
        cbLive.setText("实时翻译后续动态加载内容 (SPA / 滚动加载 / 流式响应)");
        cbLive.setTextSize(12.5f);
        cbLive.setTextColor(palette.text);
        cbLive.setChecked(PiMetConfig.isTransLiveEnabled(context));
        cbLive.setPadding(0, dp(context, 8), 0, dp(context, 14));
        root.addView(cbLive);

        // 5. 测试结果展示标签
        TextView testStatusTv = new TextView(context);
        testStatusTv.setTextSize(11.5f);
        testStatusTv.setTextColor(palette.textMuted);
        testStatusTv.setVisibility(View.GONE);
        testStatusTv.setPadding(0, 0, 0, dp(context, 8));
        root.addView(testStatusTv);

        // 6. 操作按键行
        LinearLayout btnRow = new LinearLayout(context);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);

        Button btnTest = new Button(context);
        btnTest.setText("🧪 测试引擎");
        btnTest.setTextSize(12.5f);
        btnTest.setBackground(ThemeManager.createSecondaryButtonDrawable(context, palette, 8f));
        btnTest.setTextColor(palette.accent);
        LinearLayout.LayoutParams testLp = new LinearLayout.LayoutParams(0, dp(context, 40), 1f);
        testLp.setMarginEnd(dp(context, 6));
        btnTest.setLayoutParams(testLp);
        btnRow.addView(btnTest);

        Button btnRestore = new Button(context);
        btnRestore.setText("🔄 还原原文");
        btnRestore.setTextSize(12.5f);
        btnRestore.setBackground(ThemeManager.createSecondaryButtonDrawable(context, palette, 8f));
        btnRestore.setTextColor(palette.textMuted);
        LinearLayout.LayoutParams restoreLp = new LinearLayout.LayoutParams(0, dp(context, 40), 1f);
        restoreLp.setMarginEnd(dp(context, 6));
        btnRestore.setLayoutParams(restoreLp);
        btnRow.addView(btnRestore);

        Button btnSave = new Button(context);
        btnSave.setText("💾 保存并翻译");
        btnSave.setTextSize(12.5f);
        btnSave.setBackground(ThemeManager.createPrimaryButtonDrawable(context, palette, 8f));
        btnSave.setTextColor(palette.accentContrast);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0, dp(context, 40), 1.2f);
        btnSave.setLayoutParams(saveLp);
        btnRow.addView(btnSave);

        root.addView(btnRow);
        scrollView.addView(root);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(scrollView)
                .create();

        // 按钮交互
        btnTest.setOnClickListener(v -> {
            btnTest.setEnabled(false);
            testStatusTv.setVisibility(View.VISIBLE);
            testStatusTv.setText("正在测试连接中，请稍候...");
            testStatusTv.setTextColor(palette.accent);

            String selectedEngine;
            if (rbAi.isChecked()) selectedEngine = PiMetConfig.TRANS_ENGINE_AI;
            else if (rbFree.isChecked()) selectedEngine = PiMetConfig.TRANS_ENGINE_FREE;
            else if (rbCustom.isChecked()) selectedEngine = PiMetConfig.TRANS_ENGINE_CUSTOM;
            else selectedEngine = PiMetConfig.TRANS_ENGINE_AUTO;

            String testBaseUrl = etBaseUrl.getText().toString().trim();
            String testKey = etApiKey.getText().toString().trim();
            String testModel = etModel.getText().toString().trim();

            WebTranslationManager.testTranslation(context, selectedEngine, testBaseUrl, testKey, testModel,
                    (success, latencyMs, orig, trans, msg) -> {
                        btnTest.setEnabled(true);
                        if (success) {
                            testStatusTv.setText("✅ 测试通过 (" + latencyMs + "ms): \"" + orig + "\" -> \"" + trans + "\"");
                            testStatusTv.setTextColor(palette.isDark ? 0xFF3FB950 : 0xFF1A7F37);
                        } else {
                            testStatusTv.setText("❌ " + msg);
                            testStatusTv.setTextColor(palette.isDark ? 0xFFF85149 : 0xFFCF222E);
                        }
                    });
        });

        btnRestore.setOnClickListener(v -> {
            if (webView != null) {
                webView.evaluateJavascript("if (window.__pimet_restore) window.__pimet_restore();", null);
            }
            dialog.dismiss();
            Toast.makeText(context, "已恢复网页原文", Toast.LENGTH_SHORT).show();
        });

        btnSave.setOnClickListener(v -> {
            // 保存配置
            String selectedEngine;
            if (rbAi.isChecked()) selectedEngine = PiMetConfig.TRANS_ENGINE_AI;
            else if (rbFree.isChecked()) selectedEngine = PiMetConfig.TRANS_ENGINE_FREE;
            else if (rbCustom.isChecked()) selectedEngine = PiMetConfig.TRANS_ENGINE_CUSTOM;
            else selectedEngine = PiMetConfig.TRANS_ENGINE_AUTO;

            PiMetConfig.setTransEngine(context, selectedEngine);
            PiMetConfig.setTransCustomBaseUrl(context, etBaseUrl.getText().toString().trim());
            PiMetConfig.setTransCustomApiKey(context, etApiKey.getText().toString().trim());
            PiMetConfig.setTransCustomModel(context, etModel.getText().toString().trim());

            String selectedMode = rbBilingual.isChecked() ? PiMetConfig.TRANS_MODE_BILINGUAL : PiMetConfig.TRANS_MODE_REPLACE;
            PiMetConfig.setTransDisplayMode(context, selectedMode);
            PiMetConfig.setTransLiveEnabled(context, cbLive.isChecked());

            dialog.dismiss();
            Toast.makeText(context, "翻译配置已保存，正在重新解析页面...", Toast.LENGTH_SHORT).show();

            // 重新刷新 WebView 翻译
            if (webView != null) {
                WebTranslationManager.refreshTranslation(webView, context);
            }
        });

        dialog.show();
    }

    private static RadioButton createRadioButton(Context ctx, ThemeManager.ThemePalette palette, String text) {
        RadioButton rb = new RadioButton(ctx);
        rb.setText(text);
        rb.setTextSize(12.5f);
        rb.setTextColor(palette.text);
        rb.setPadding(dp(ctx, 4), dp(ctx, 4), 0, dp(ctx, 4));
        return rb;
    }

    private static EditText createInput(Context ctx, ThemeManager.ThemePalette palette, String hint) {
        EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setHintTextColor(palette.textDim);
        et.setTextColor(palette.text);
        et.setTextSize(12f);
        et.setBackground(ThemeManager.createInputDrawable(ctx, palette, 6f));
        int padH = dp(ctx, 10);
        int padV = dp(ctx, 8);
        et.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(ctx, 6), 0, 0);
        et.setLayoutParams(lp);
        return et;
    }

    private static int dp(Context ctx, int dpVal) {
        return ThemeManager.dpToPx(ctx, dpVal);
    }
}
