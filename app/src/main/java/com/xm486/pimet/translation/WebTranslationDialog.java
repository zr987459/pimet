package com.xm486.pimet.translation;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.xm486.pimet.PiMetConfig;
import com.xm486.pimet.ThemeManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 网页翻译设置：顶部当前状态 + 测试整条引擎链；下面是引擎链（排序 / 开关）、呈现方式、站点规则。
 */
public class WebTranslationDialog {

    public static void show(Context context, WebView webView) {
        if (context == null) return;
        final ThemeManager.ThemePalette palette = ThemeManager.getEffectivePalette(context);
        final Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        final Handler main = new Handler(Looper.getMainLooper());
        final int okColor = palette.isDark ? 0xFF3FB950 : 0xFF1A7F37;
        final int errColor = palette.isDark ? 0xFFF85149 : 0xFFCF222E;

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 18);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(palette.bgPanel);

        TextView titleTv = new TextView(context);
        titleTv.setText("🌐 网页翻译");
        titleTv.setTextSize(16f);
        titleTv.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
        titleTv.setTextColor(palette.text);
        root.addView(titleTv);

        // ---- 顶部：当前状态 + 测试
        LinearLayout statusRow = new LinearLayout(context);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(0, dp(context, 8), 0, dp(context, 4));
        TextView statusTv = new TextView(context);
        statusTv.setTextSize(12f);
        statusTv.setTextColor(palette.textMuted);
        statusRow.addView(statusTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button btnTest = new Button(context);
        btnTest.setText("🧪 测试");
        btnTest.setTextSize(12f);
        btnTest.setAllCaps(false);
        btnTest.setBackground(ThemeManager.createSecondaryButtonDrawable(context, palette, 8f));
        btnTest.setTextColor(palette.accent);
        LinearLayout.LayoutParams testLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 36));
        testLp.setMarginStart(dp(context, 8));
        statusRow.addView(btnTest, testLp);
        root.addView(statusRow);

        TextView testResultTv = new TextView(context);
        testResultTv.setTextSize(11.5f);
        testResultTv.setTextColor(palette.textMuted);
        testResultTv.setVisibility(View.GONE);
        testResultTv.setPadding(0, 0, 0, dp(context, 6));
        root.addView(testResultTv);

        // ---- 引擎链
        root.addView(section(context, palette, "引擎链（从上到下依次尝试，失败或限流自动换下一个）"));
        final List<TranslationEngines.Entry> chain = TranslationEngines.loadChain(context);
        final LinearLayout chainBox = new LinearLayout(context);
        chainBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(chainBox);
        final Map<String, LinearLayout> rows = new HashMap<>();
        final Map<String, CheckBox> checks = new HashMap<>();
        final Map<String, TextView> rowStatus = new HashMap<>();

        // Pi 的模型：下拉
        final List<PiModelCatalog.PiModel> piModels = new ArrayList<>();
        final List<String> piLabels = new ArrayList<>();
        final Spinner piSpinner = new Spinner(context);
        final TextView piDetailTv = small(context, palette, "正在读取 Pi 的模型配置...");
        ArrayAdapter<String> piAdapter = new ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, piLabels) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                return tint(super.getView(position, convertView, parent), false);
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                return tint(super.getDropDownView(position, convertView, parent), true);
            }

            private View tint(View v, boolean drop) {
                if (v instanceof TextView) {
                    ((TextView) v).setTextColor(palette.text);
                    ((TextView) v).setTextSize(12.5f);
                    if (drop) {
                        v.setBackgroundColor(palette.bgPanel);
                        int p = dp(context, 10);
                        v.setPadding(p, p, p, p);
                    }
                }
                return v;
            }
        };
        piAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        piSpinner.setAdapter(piAdapter);
        piSpinner.setVisibility(View.GONE);
        piSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < piModels.size()) piDetailTv.setText(piModels.get(position).detail());
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // 自定义 API：三个输入框
        final EditText etBaseUrl = createInput(context, palette, "API Base URL（如 https://api.deepseek.com/v1）");
        etBaseUrl.setText(PiMetConfig.getTransCustomBaseUrl(context));
        final EditText etApiKey = createInput(context, palette, "API Key（sk-...）");
        etApiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etApiKey.setText(PiMetConfig.getTransCustomApiKey(context));
        final EditText etModel = createInput(context, palette, "模型（如 deepseek-chat）");
        etModel.setText(PiMetConfig.getTransCustomModel(context));

        final Runnable[] relayout = new Runnable[1];
        for (TranslationEngines.Entry e : chain) {
            final String id = e.id;
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(context, 10), dp(context, 6), dp(context, 6), dp(context, 8));
            row.setBackground(ThemeManager.createSunkenDrawable(context, palette, 10f));
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.setMargins(0, 0, 0, dp(context, 8));
            row.setLayoutParams(rowLp);

            LinearLayout head = new LinearLayout(context);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            CheckBox cb = new CheckBox(context);
            cb.setText(TranslationEngines.displayName(id) + (TranslationEngines.FREE.equals(id) ? "（Google / MyMemory / Bing）" : ""));
            cb.setTextSize(13f);
            cb.setTextColor(palette.text);
            cb.setChecked(e.enabled);
            head.addView(cb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            head.addView(arrow(context, palette, "↑", v -> move(chain, id, -1, relayout[0])));
            head.addView(arrow(context, palette, "↓", v -> move(chain, id, 1, relayout[0])));
            row.addView(head);

            TextView st = small(context, palette, "");
            st.setPadding(dp(context, 32), 0, 0, 0);
            row.addView(st);
            rowStatus.put(id, st);

            final LinearLayout detail = new LinearLayout(context);
            detail.setOrientation(LinearLayout.VERTICAL);
            detail.setPadding(dp(context, 32), dp(context, 2), 0, 0);
            if (TranslationEngines.PI.equals(id)) {
                detail.addView(piSpinner);
                detail.addView(piDetailTv);
            } else if (TranslationEngines.CUSTOM.equals(id)) {
                detail.addView(etBaseUrl);
                detail.addView(etApiKey);
                detail.addView(etModel);
            }
            if (detail.getChildCount() > 0) {
                detail.setVisibility(e.enabled ? View.VISIBLE : View.GONE);
                row.addView(detail);
            }
            cb.setOnCheckedChangeListener((b, checked) -> {
                for (TranslationEngines.Entry x : chain) if (x.id.equals(id)) x.enabled = checked;
                if (detail.getChildCount() > 0) detail.setVisibility(checked ? View.VISIBLE : View.GONE);
            });
            rows.put(id, row);
            checks.put(id, cb);
        }
        relayout[0] = () -> {
            chainBox.removeAllViews();
            for (TranslationEngines.Entry e : chain) chainBox.addView(rows.get(e.id));
        };
        relayout[0].run();

        final Runnable refreshStatus = () -> {
            StringBuilder sb = new StringBuilder("当前：");
            int n = 0;
            for (TranslationEngines.Entry e : chain) {
                TextView st = rowStatus.get(e.id);
                String s = e.enabled ? TranslationEngines.statusText(e.id) : "未启用";
                if (st != null) st.setText("状态：" + s);
                if (!e.enabled) continue;
                if (n++ > 0) sb.append(" → ");
                sb.append(TranslationEngines.displayName(e.id));
                if (TranslationEngines.PI.equals(e.id)) {
                    int pos = piSpinner.getSelectedItemPosition();
                    if (pos >= 0 && pos < piModels.size()) sb.append("（").append(piModels.get(pos).id).append("）");
                }
                String s2 = TranslationEngines.statusText(e.id);
                if (s2.contains("重试")) sb.append("［").append(s2).append("］");
            }
            if (n == 0) sb.append("没有启用的引擎");
            statusTv.setText(sb.toString());
        };
        refreshStatus.run();

        // ---- 呈现方式
        root.addView(section(context, palette, "呈现方式"));
        RadioGroup modeGroup = new RadioGroup(context);
        modeGroup.setOrientation(LinearLayout.HORIZONTAL);
        RadioButton rbReplace = createRadioButton(context, palette, "替换原文");
        RadioButton rbBilingual = createRadioButton(context, palette, "双语对照");
        rbReplace.setId(View.generateViewId());
        rbBilingual.setId(View.generateViewId());
        modeGroup.addView(rbReplace);
        modeGroup.addView(rbBilingual);
        if (PiMetConfig.TRANS_MODE_BILINGUAL.equals(PiMetConfig.getTransDisplayMode(context))) rbBilingual.setChecked(true);
        else rbReplace.setChecked(true);
        root.addView(modeGroup);

        // ---- 站点规则
        root.addView(section(context, palette, "站点规则"));
        final String host = webView != null ? TranslationSites.hostOf(webView.getUrl()) : null;
        RadioGroup siteGroup = new RadioGroup(context);
        siteGroup.setOrientation(LinearLayout.VERTICAL);
        final RadioButton rbSiteDefault = createRadioButton(context, palette, "默认（手动点「译」）");
        final RadioButton rbSiteAlways = createRadioButton(context, palette, "对此网站总是翻译");
        final RadioButton rbSiteNever = createRadioButton(context, palette, "对此网站从不翻译");
        rbSiteDefault.setId(View.generateViewId());
        rbSiteAlways.setId(View.generateViewId());
        rbSiteNever.setId(View.generateViewId());
        if (host != null) {
            root.addView(small(context, palette, "当前网站：" + host + "（子域名同样生效）"));
            siteGroup.addView(rbSiteDefault);
            siteGroup.addView(rbSiteAlways);
            siteGroup.addView(rbSiteNever);
            int rule = TranslationSites.exactRule(context, host);
            if (rule == TranslationSites.RULE_ALWAYS) rbSiteAlways.setChecked(true);
            else if (rule == TranslationSites.RULE_NEVER) rbSiteNever.setChecked(true);
            else rbSiteDefault.setChecked(true);
            root.addView(siteGroup);
        } else {
            root.addView(small(context, palette, "当前页面不是网站（http/https），无法设置站点规则"));
        }
        LinearLayout siteTools = new LinearLayout(context);
        siteTools.setOrientation(LinearLayout.HORIZONTAL);
        siteTools.setPadding(0, dp(context, 4), 0, dp(context, 12));
        final TextView clearRules = link(context, palette, "清除全部站点规则（" + TranslationSites.ruleCount(context) + " 条）");
        clearRules.setOnClickListener(v -> {
            TranslationSites.clearAll(context);
            clearRules.setText("已清除全部站点规则");
            rbSiteDefault.setChecked(true);
        });
        siteTools.addView(clearRules);
        final TextView clearCache = link(context, palette, "清空译文缓存");
        clearCache.setPadding(dp(context, 16), 0, 0, 0);
        clearCache.setOnClickListener(v -> WebTranslationManager.THREAD_POOL.execute(() -> {
            TranslationCache.clear(appCtx);
            main.post(() -> clearCache.setText("缓存已清空"));
        }));
        siteTools.addView(clearCache);
        root.addView(siteTools);

        // ---- 底部按钮
        LinearLayout btnRow = new LinearLayout(context);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        Button btnRestore = new Button(context);
        btnRestore.setText("🔄 还原原文");
        btnRestore.setTextSize(12f);
        btnRestore.setAllCaps(false);
        btnRestore.setBackground(ThemeManager.createSecondaryButtonDrawable(context, palette, 8f));
        btnRestore.setTextColor(palette.textMuted);
        LinearLayout.LayoutParams restoreLp = new LinearLayout.LayoutParams(0, dp(context, 40), 1f);
        restoreLp.setMarginEnd(dp(context, 8));
        btnRow.addView(btnRestore, restoreLp);
        Button btnSave = new Button(context);
        btnSave.setText("💾 保存并翻译");
        btnSave.setTextSize(12f);
        btnSave.setAllCaps(false);
        btnSave.setBackground(ThemeManager.createPrimaryButtonDrawable(context, palette, 8f));
        btnSave.setTextColor(palette.accentContrast);
        btnRow.addView(btnSave, new LinearLayout.LayoutParams(0, dp(context, 40), 1.2f));
        root.addView(btnRow);
        scrollView.addView(root);

        AlertDialog dialog = new com.xm486.pimet.ui.Icons.DialogBuilder(context).setView(scrollView).create();

        // 后台读 Pi 的模型
        WebTranslationManager.THREAD_POOL.execute(() -> {
            List<PiModelCatalog.PiModel> list;
            String saved;
            try {
                list = PiModelCatalog.load(appCtx);
                saved = PiModelCatalog.savedRef(appCtx);
            } catch (Throwable t) {
                list = new ArrayList<>();
                saved = "";
            }
            final List<PiModelCatalog.PiModel> fList = list;
            final String fSaved = saved;
            main.post(() -> {
                piModels.clear();
                piLabels.clear();
                piModels.addAll(fList);
                int sel = -1;
                for (int i = 0; i < fList.size(); i++) {
                    piLabels.add(fList.get(i).label());
                    if (!TextUtils.isEmpty(fSaved) && fSaved.equals(fList.get(i).ref())) sel = i;
                }
                if (sel < 0) for (int i = 0; i < fList.size(); i++) if (fList.get(i).isDefault) { sel = i; break; }
                if (sel < 0 && !fList.isEmpty()) sel = 0;
                piAdapter.notifyDataSetChanged();
                if (fList.isEmpty()) {
                    piSpinner.setVisibility(View.GONE);
                    piDetailTv.setText("没读到 Pi 的模型：请先在 Pi 里配置 provider（~/.pi/agent/models.json）或默认模型（settings.json）");
                } else {
                    piSpinner.setVisibility(View.VISIBLE);
                    piSpinner.setSelection(sel);
                    piDetailTv.setText(fList.get(sel).detail());
                }
                refreshStatus.run();
            });
        });

        btnTest.setOnClickListener(v -> {
            btnTest.setEnabled(false);
            testResultTv.setVisibility(View.VISIBLE);
            testResultTv.setTextColor(palette.accent);
            testResultTv.setText("正在逐个测试引擎链...");
            final TranslationEngines.Config cfg = buildConfig(chain, piSpinner, piModels, etBaseUrl, etApiKey, etModel);
            WebTranslationManager.THREAD_POOL.execute(() -> {
                List<TranslationEngines.TestLine> lines = TranslationEngines.testChain(appCtx, cfg);
                main.post(() -> {
                    btnTest.setEnabled(true);
                    if (lines.isEmpty()) {
                        testResultTv.setTextColor(errColor);
                        testResultTv.setText("❌ 没有启用的引擎");
                        return;
                    }
                    StringBuilder sb = new StringBuilder();
                    boolean anyOk = false;
                    for (TranslationEngines.TestLine l : lines) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(l.ok ? "✅ " : "❌ ").append(TranslationEngines.displayName(l.id))
                                .append("  ").append(l.latencyMs).append("ms  ").append(l.text);
                        anyOk |= l.ok;
                    }
                    testResultTv.setTextColor(anyOk ? okColor : errColor);
                    testResultTv.setText(sb.toString());
                    refreshStatus.run();
                });
            });
        });

        btnRestore.setOnClickListener(v -> {
            if (webView != null) webView.evaluateJavascript("if (window.__pimet_restore) window.__pimet_restore();", null);
            dialog.dismiss();
            Toast.makeText(context, "已还原网页原文", Toast.LENGTH_SHORT).show();
        });

        btnSave.setOnClickListener(v -> {
            TranslationEngines.saveChain(context, chain);
            int pos = piSpinner.getSelectedItemPosition();
            if (pos >= 0 && pos < piModels.size()) {
                PiModelCatalog.PiModel pm = piModels.get(pos);
                PiModelCatalog.saveSelection(context, pm.provider, pm.id);
            }
            PiMetConfig.setTransCustomBaseUrl(context, etBaseUrl.getText().toString().trim());
            PiMetConfig.setTransCustomApiKey(context, etApiKey.getText().toString().trim());
            PiMetConfig.setTransCustomModel(context, etModel.getText().toString().trim());
            PiMetConfig.setTransDisplayMode(context, rbBilingual.isChecked() ? PiMetConfig.TRANS_MODE_BILINGUAL : PiMetConfig.TRANS_MODE_REPLACE);
            int siteRule = TranslationSites.RULE_NONE;
            if (host != null) {
                if (rbSiteAlways.isChecked()) siteRule = TranslationSites.RULE_ALWAYS;
                else if (rbSiteNever.isChecked()) siteRule = TranslationSites.RULE_NEVER;
                TranslationSites.setRule(context, host, siteRule);
            }
            dialog.dismiss();
            if (webView == null) return;
            if (siteRule == TranslationSites.RULE_NEVER) {
                webView.evaluateJavascript("if (window.__pimet_restore) window.__pimet_restore();", null);
                Toast.makeText(context, "已保存：此网站不再翻译", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, "已保存，正在重新翻译...", Toast.LENGTH_SHORT).show();
                WebTranslationManager.refreshTranslation(webView, context);
            }
        });

        dialog.show();
    }

    private static TranslationEngines.Config buildConfig(List<TranslationEngines.Entry> chain, Spinner piSpinner,
                                                         List<PiModelCatalog.PiModel> piModels, EditText base, EditText key, EditText model) {
        TranslationEngines.Config c = new TranslationEngines.Config();
        for (TranslationEngines.Entry e : chain) c.chain.add(new TranslationEngines.Entry(e.id, e.enabled));
        int pos = piSpinner.getSelectedItemPosition();
        c.piRef = pos >= 0 && pos < piModels.size() ? piModels.get(pos).ref() : "";
        c.customBaseUrl = base.getText().toString().trim();
        c.customApiKey = key.getText().toString().trim();
        c.customModel = model.getText().toString().trim();
        return c;
    }

    private static void move(List<TranslationEngines.Entry> chain, String id, int delta, Runnable relayout) {
        for (int i = 0; i < chain.size(); i++) {
            if (!chain.get(i).id.equals(id)) continue;
            int j = i + delta;
            if (j < 0 || j >= chain.size()) return;
            TranslationEngines.Entry e = chain.remove(i);
            chain.add(j, e);
            if (relayout != null) relayout.run();
            return;
        }
    }

    private static TextView arrow(Context ctx, ThemeManager.ThemePalette palette, String text, View.OnClickListener l) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(16f);
        tv.setTextColor(palette.accent);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4));
        tv.setOnClickListener(l);
        return tv;
    }

    private static TextView section(Context ctx, ThemeManager.ThemePalette palette, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(13.5f);
        tv.setTypeface(ThemeManager.MEDIUM_TYPEFACE);
        tv.setTextColor(palette.accent);
        tv.setPadding(0, dp(ctx, 12), 0, dp(ctx, 8));
        return tv;
    }

    private static TextView small(Context ctx, ThemeManager.ThemePalette palette, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(11.5f);
        tv.setTextColor(palette.textMuted);
        return tv;
    }

    private static TextView link(Context ctx, ThemeManager.ThemePalette palette, String text) {
        TextView tv = small(ctx, palette, text);
        tv.setTextColor(palette.accent);
        return tv;
    }

    private static RadioButton createRadioButton(Context ctx, ThemeManager.ThemePalette palette, String text) {
        RadioButton rb = new RadioButton(ctx);
        rb.setText(text);
        rb.setTextSize(12f);
        rb.setTextColor(palette.text);
        rb.setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4));
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
