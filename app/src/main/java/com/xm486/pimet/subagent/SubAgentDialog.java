package com.xm486.pimet.subagent;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pi 子代理管理中心对话框
 * 提供全局 ~/.pi/agent/agents/*.md 子代理的可视化浏览、新建、编辑、复制与删除
 */
public class SubAgentDialog {
    private final Context context;
    private AlertDialog mainDialog;
    private LinearLayout listContainer;

    public SubAgentDialog(Context context) {
        this.context = context;
    }

    private int dp(float dpVal) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dpVal,
                context.getResources().getDisplayMetrics());
    }

    public void show() {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0D1117);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        // 顶部标题与新建按钮
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(12));

        LinearLayout titleCol = new LinearLayout(context);
        titleCol.setOrientation(LinearLayout.VERTICAL);

        TextView tvTitle = new TextView(context);
        tvTitle.setText("🤖 Pi 子代理中心 (Subagents)");
        tvTitle.setTextColor(0xFFF0F6FC);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        tvTitle.setTypeface(null, Typeface.BOLD);
        titleCol.addView(tvTitle);

        TextView tvSub = new TextView(context);
        tvSub.setText("双向打通 ~/.pi/agent/agents/*.md 规范配置");
        tvSub.setTextColor(0xFF8B949E);
        tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tvSub.setPadding(0, dp(2), 0, 0);
        titleCol.addView(tvSub);

        header.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btnNew = new Button(context);
        btnNew.setText("➕ 新建子代理");
        btnNew.setTextColor(0xFFFFFFFF);
        btnNew.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        btnNew.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(0xFF238636);
        btnBg.setCornerRadius(dp(6));
        btnNew.setBackground(btnBg);
        btnNew.setOnClickListener(v -> showEditDialog(null));
        header.addView(btnNew);

        root.addView(header);

        // 列表区域
        ScrollView sv = new ScrollView(context);
        listContainer = new LinearLayout(context);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        sv.addView(listContainer);

        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 底部关闭按钮
        Button btnClose = new Button(context);
        btnClose.setText("关闭");
        btnClose.setTextColor(0xFFC9D1D9);
        btnClose.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        GradientDrawable closeBg = new GradientDrawable();
        closeBg.setColor(0xFF21262D);
        closeBg.setCornerRadius(dp(6));
        btnClose.setBackground(closeBg);
        btnClose.setOnClickListener(v -> {
            if (mainDialog != null) mainDialog.dismiss();
        });
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        closeLp.topMargin = dp(12);
        root.addView(btnClose, closeLp);

        builder.setView(root);
        mainDialog = builder.create();

        Window window = mainDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.94f);
            lp.height = (int) (context.getResources().getDisplayMetrics().heightPixels * 0.82f);
            window.setAttributes(lp);
        }

        refreshList();
        mainDialog.show();
    }

    private void refreshList() {
        if (listContainer == null) return;
        listContainer.removeAllViews();

        List<SubAgentInfo> list = SubAgentManager.listAllAgents(context);
        if (list.isEmpty()) {
            TextView emptyTv = new TextView(context);
            emptyTv.setText("暂无自定义子代理。\n点击右上角「➕ 新建子代理」创建专属智能体！");
            emptyTv.setTextColor(0xFF8B949E);
            emptyTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            emptyTv.setGravity(Gravity.CENTER);
            emptyTv.setPadding(0, dp(40), 0, dp(40));
            listContainer.addView(emptyTv);
            return;
        }

        for (SubAgentInfo agent : list) {
            listContainer.addView(createAgentCard(agent));
        }
    }

    private View createAgentCard(final SubAgentInfo agent) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFF161B22);
        cardBg.setCornerRadius(dp(8));
        cardBg.setStroke(dp(1), 0xFF30363D);
        card.setBackground(cardBg);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(10);
        card.setLayoutParams(cardLp);

        // 首行：ID + Scope Badge + Action 按钮
        LinearLayout row1 = new LinearLayout(context);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvId = new TextView(context);
        tvId.setText("🤖 " + agent.id);
        tvId.setTextColor(0xFF58A6FF);
        tvId.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        tvId.setTypeface(null, Typeface.BOLD);
        row1.addView(tvId);

        TextView tvScope = new TextView(context);
        tvScope.setText("全局 (~/.pi/agent/agents/)");
        tvScope.setTextColor(0xFF3FB950);
        tvScope.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        tvScope.setPadding(dp(6), dp(1), dp(6), dp(1));
        GradientDrawable scopeBg = new GradientDrawable();
        scopeBg.setColor(0x223FB950);
        scopeBg.setCornerRadius(dp(4));
        scopeBg.setStroke(dp(1), 0x553FB950);
        tvScope.setBackground(scopeBg);
        LinearLayout.LayoutParams scopeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scopeLp.marginStart = dp(8);
        row1.addView(tvScope, scopeLp);

        View spacer = new View(context);
        row1.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        // 操作按钮行：编辑 / 复制 / 删除
        Button btnEdit = createSmallBtn("✏️ 编辑", 0xFF21262D, 0xFFC9D1D9);
        btnEdit.setOnClickListener(v -> showEditDialog(agent));
        row1.addView(btnEdit);

        Button btnCopy = createSmallBtn("📋 复制", 0xFF21262D, 0xFFC9D1D9);
        btnCopy.setOnClickListener(v -> showEditDialog(agent.copy()));
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(26));
        copyLp.marginStart = dp(6);
        row1.addView(btnCopy, copyLp);

        Button btnDel = createSmallBtn("🗑️ 删除", 0x33DA3633, 0xFFF85149);
        btnDel.setOnClickListener(v -> {
            new AlertDialog.Builder(context)
                    .setTitle("确认删除子代理")
                    .setMessage("确定删除子代理「" + agent.id + "」(" + agent.filePath + ") 吗？此操作不可逆。")
                    .setPositiveButton("删除", (d, w) -> {
                        SubAgentManager.deleteAgent(context, agent);
                        Toast.makeText(context, "已删除 " + agent.id, Toast.LENGTH_SHORT).show();
                        refreshList();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(26));
        delLp.marginStart = dp(6);
        row1.addView(btnDel, delLp);

        card.addView(row1);

        // 名称与描述
        if (!TextUtils.isEmpty(agent.name) && !agent.name.equals(agent.id)) {
            TextView tvName = new TextView(context);
            tvName.setText("显示名称: " + agent.name);
            tvName.setTextColor(0xFFF0F6FC);
            tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            tvName.setPadding(0, dp(4), 0, 0);
            card.addView(tvName);
        }

        if (!TextUtils.isEmpty(agent.description)) {
            TextView tvDesc = new TextView(context);
            tvDesc.setText(agent.description);
            tvDesc.setTextColor(0xFF8B949E);
            tvDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            tvDesc.setPadding(0, dp(3), 0, dp(4));
            card.addView(tvDesc);
        }

        // 参数属性栏 (模型、思考、轮次、上下文)
        StringBuilder metaSb = new StringBuilder();
        metaSb.append("• 模型: ").append(TextUtils.isEmpty(agent.model) ? "跟随父会话" : agent.model);
        if (!TextUtils.isEmpty(agent.thinking)) {
            metaSb.append("  • 思考: ").append(agent.thinking);
        }
        metaSb.append("  • 最大轮次: ").append(agent.maxTurns);
        if (agent.inheritContext) metaSb.append("  • 继承上下文");
        if (agent.background) metaSb.append("  • 后台运行");

        TextView tvMeta = new TextView(context);
        tvMeta.setText(metaSb.toString());
        tvMeta.setTextColor(0xFF7EE787);
        tvMeta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        tvMeta.setPadding(0, dp(2), 0, dp(4));
        card.addView(tvMeta);

        // 工具列表 Chips
        if (agent.tools != null && !agent.tools.isEmpty()) {
            HorizontalScrollView hsv = new HorizontalScrollView(context);
            LinearLayout chipRow = new LinearLayout(context);
            chipRow.setOrientation(LinearLayout.HORIZONTAL);

            TextView toolsLabel = new TextView(context);
            toolsLabel.setText("工具: ");
            toolsLabel.setTextColor(0xFF8B949E);
            toolsLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            toolsLabel.setGravity(Gravity.CENTER_VERTICAL);
            chipRow.addView(toolsLabel);

            for (String tool : agent.tools) {
                TextView chip = new TextView(context);
                chip.setText(tool);
                chip.setTextColor(0xFF58A6FF);
                chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
                chip.setTypeface(Typeface.MONOSPACE);
                chip.setPadding(dp(5), dp(1), dp(5), dp(1));

                GradientDrawable cbg = new GradientDrawable();
                cbg.setColor(0xFF1F242C);
                cbg.setCornerRadius(dp(4));
                cbg.setStroke(dp(1), 0xFF388BFD);
                chip.setBackground(cbg);

                LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                chipLp.marginEnd = dp(4);
                chipRow.addView(chip, chipLp);
            }

            hsv.addView(chipRow);
            card.addView(hsv);
        }

        return card;
    }

    private Button createSmallBtn(String text, int bgColor, int textColor) {
        Button b = new Button(context);
        b.setText(text);
        b.setTextColor(textColor);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        b.setPadding(dp(8), dp(0), dp(8), dp(0));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(4));
        b.setBackground(bg);
        b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(26)));
        return b;
    }

    /**
     * 弹出「新建 / 编辑子代理」全量表单对话框
     */
    private void showEditDialog(final SubAgentInfo targetAgent) {
        final boolean isEdit = (targetAgent != null && !TextUtils.isEmpty(targetAgent.filePath));
        final SubAgentInfo agent = (targetAgent != null) ? targetAgent : new SubAgentInfo();

        AlertDialog.Builder b = new AlertDialog.Builder(context);

        ScrollView sv = new ScrollView(context);
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setBackgroundColor(0xFF0D1117);
        form.setPadding(dp(16), dp(16), dp(16), dp(16));
        sv.addView(form);

        // 标题
        TextView tvDialogTitle = new TextView(context);
        tvDialogTitle.setText(isEdit ? "✏️ 编辑子代理: " + agent.id : "➕ 新建子代理");
        tvDialogTitle.setTextColor(0xFFF0F6FC);
        tvDialogTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        tvDialogTitle.setTypeface(null, Typeface.BOLD);
        form.addView(tvDialogTitle);

        TextView tvHint = new TextView(context);
        tvHint.setText("配置将直接写入 ~/.pi/agent/agents/<id>.md，与 Web 工作台实时同步");
        tvHint.setTextColor(0xFF8B949E);
        tvHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvHint.setPadding(0, dp(2), 0, dp(12));
        form.addView(tvHint);

        // 1. 范围展示
        addFormLabel(form, "存储范围 (Scope):");
        TextView tvScopeVal = new TextView(context);
        tvScopeVal.setText("全局 (~/.pi/agent/agents/ - 所有项目通用)");
        tvScopeVal.setTextColor(0xFF3FB950);
        tvScopeVal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tvScopeVal.setPadding(0, 0, 0, dp(8));
        form.addView(tvScopeVal);

        // 2. 子代理 ID
        addFormLabel(form, "子代理 ID (英文/连字符，如 plan-copy): *");
        final EditText etId = createStyledEditText(agent.id, "例如: plan-copy, reviewer");
        if (isEdit) {
            etId.setEnabled(false);
            etId.setTextColor(0xFF8B949E);
        }
        form.addView(etId);

        // 3. 显示名称
        addFormLabel(form, "显示名称 (Display Name):");
        final EditText etName = createStyledEditText(agent.name, "例如: 架构规划专家");
        form.addView(etName);

        // 4. 描述
        addFormLabel(form, "描述 (Description):");
        final EditText etDesc = createStyledEditText(agent.description, "简述该智能体的擅长领域和调用场景");
        form.addView(etDesc);

        // 5. 工具选择 (Chips + 复选)
        addFormLabel(form, "启用工具 (Tools):");
        List<String> presetTools = Arrays.asList(SubAgentManager.getPresetTools());
        final List<CheckBox> toolBoxes = new ArrayList<>();

        LinearLayout toolsGrid = new LinearLayout(context);
        toolsGrid.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = null;
        for (int i = 0; i < presetTools.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                toolsGrid.addView(row);
            }
            String tool = presetTools.get(i);
            CheckBox cb = new CheckBox(context);
            cb.setText(tool);
            cb.setTextColor(0xFFC9D1D9);
            cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            cb.setChecked(agent.tools.contains(tool));
            row.addView(cb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            toolBoxes.add(cb);
        }
        form.addView(toolsGrid);

        // 6. 资源配置
        addFormLabel(form, "资源开关 (Resources):");
        LinearLayout resRow = new LinearLayout(context);
        resRow.setOrientation(LinearLayout.HORIZONTAL);

        final CheckBox cbSkills = new CheckBox(context);
        cbSkills.setText("加载技能 (skills: true)");
        cbSkills.setTextColor(0xFFC9D1D9);
        cbSkills.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        cbSkills.setChecked(agent.loadSkills);
        resRow.addView(cbSkills, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final CheckBox cbExtensions = new CheckBox(context);
        cbExtensions.setText("加载扩展 (extensions: true)");
        cbExtensions.setTextColor(0xFFC9D1D9);
        cbExtensions.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        cbExtensions.setChecked(agent.loadExtensions);
        resRow.addView(cbExtensions, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        form.addView(resRow);

        // 7. 指定模型
        addFormLabel(form, "指定模型 (留空则跟随父会话):");
        final EditText etModel = createStyledEditText(agent.model, "例如: claude-3-7-sonnet, deepseek-chat 或留空");
        form.addView(etModel);

        // 8. 思考级别 (Spinner)
        addFormLabel(form, "思考级别 (Thinking Level):");
        final Spinner spThinking = new Spinner(context);
        String[] thinkingOpts = SubAgentManager.getThinkingLevels();
        ArrayAdapter<String> thAdapter = new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_dropdown_item, thinkingOpts);
        spThinking.setAdapter(thAdapter);
        if (!TextUtils.isEmpty(agent.thinking)) {
            for (int i = 0; i < thinkingOpts.length; i++) {
                if (thinkingOpts[i].equalsIgnoreCase(agent.thinking)) {
                    spThinking.setSelection(i);
                    break;
                }
            }
        }
        spThinking.setBackgroundColor(0xFF161B22);
        spThinking.setPadding(dp(8), dp(8), dp(8), dp(8));
        form.addView(spThinking);

        // 9. 最大轮次
        addFormLabel(form, "最大轮次 (Max Turns):");
        final EditText etTurns = createStyledEditText(String.valueOf(agent.maxTurns > 0 ? agent.maxTurns : 10), "10");
        etTurns.setInputType(InputType.TYPE_CLASS_NUMBER);
        form.addView(etTurns);

        // 10. 高级选项 (继承上下文、后台运行)
        addFormLabel(form, "运行选项 (Flags):");
        LinearLayout flagsRow = new LinearLayout(context);
        flagsRow.setOrientation(LinearLayout.HORIZONTAL);

        final CheckBox cbInherit = new CheckBox(context);
        cbInherit.setText("继承父会话上下文");
        cbInherit.setTextColor(0xFFC9D1D9);
        cbInherit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        cbInherit.setChecked(agent.inheritContext);
        flagsRow.addView(cbInherit, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final CheckBox cbBg = new CheckBox(context);
        cbBg.setText("默认在后台运行");
        cbBg.setTextColor(0xFFC9D1D9);
        cbBg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        cbBg.setChecked(agent.background);
        flagsRow.addView(cbBg, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        form.addView(flagsRow);

        // 11. 系统指令 (System Prompt)
        addFormLabel(form, "系统指令 / Prompt 正文: *");
        final EditText etPrompt = createStyledEditText(agent.systemPrompt, "输入智能体的角色定位、职责分工与专业行为约束...");
        etPrompt.setMinLines(6);
        etPrompt.setGravity(Gravity.TOP | Gravity.START);
        etPrompt.setTypeface(Typeface.MONOSPACE);
        form.addView(etPrompt);

        b.setView(sv);
        b.setPositiveButton("保存并写入容器", null); // 自定义点击校验
        b.setNegativeButton("取消", null);

        final AlertDialog editDialog = b.create();
        editDialog.setOnShowListener(dialogInterface -> {
            Button saveBtn = editDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            saveBtn.setOnClickListener(v -> {
                String id = etId.getText().toString().trim();
                if (TextUtils.isEmpty(id)) {
                    Toast.makeText(context, "子代理 ID 不能为空", Toast.LENGTH_SHORT).show();
                    return;
                }

                agent.id = id;
                agent.name = etName.getText().toString().trim();
                agent.description = etDesc.getText().toString().trim();
                agent.systemPrompt = etPrompt.getText().toString().trim();
                agent.model = etModel.getText().toString().trim();

                int selThinking = spThinking.getSelectedItemPosition();
                if (selThinking > 0) {
                    agent.thinking = SubAgentManager.getThinkingLevels()[selThinking];
                } else {
                    agent.thinking = "";
                }

                try {
                    agent.maxTurns = Integer.parseInt(etTurns.getText().toString().trim());
                } catch (Throwable t) {
                    agent.maxTurns = 10;
                }

                agent.loadSkills = cbSkills.isChecked();
                agent.loadExtensions = cbExtensions.isChecked();
                agent.inheritContext = cbInherit.isChecked();
                agent.background = cbBg.isChecked();

                agent.tools.clear();
                for (CheckBox cb : toolBoxes) {
                    if (cb.isChecked()) {
                        agent.tools.add(cb.getText().toString().trim());
                    }
                }

                boolean ok = SubAgentManager.saveAgent(context, agent);
                if (ok) {
                    Toast.makeText(context, "子代理「" + agent.id + "」已成功保存！两端已实时互通", Toast.LENGTH_LONG).show();
                    editDialog.dismiss();
                    refreshList();
                } else {
                    Toast.makeText(context, "保存失败，请检查 PRoot 容器文件系统", Toast.LENGTH_SHORT).show();
                }
            });
        });

        editDialog.show();
    }

    private void addFormLabel(LinearLayout parent, String label) {
        TextView tv = new TextView(context);
        tv.setText(label);
        tv.setTextColor(0xFF8B949E);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, dp(10), 0, dp(3));
        parent.addView(tv);
    }

    private EditText createStyledEditText(String text, String hint) {
        EditText et = new EditText(context);
        et.setText(text != null ? text : "");
        et.setHint(hint != null ? hint : "");
        et.setHintTextColor(0xFF484F58);
        et.setTextColor(0xFFF0F6FC);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        et.setPadding(dp(10), dp(8), dp(10), dp(8));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF161B22);
        bg.setCornerRadius(dp(6));
        bg.setStroke(dp(1), 0xFF30363D);
        et.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(4);
        et.setLayoutParams(lp);
        return et;
    }
}
