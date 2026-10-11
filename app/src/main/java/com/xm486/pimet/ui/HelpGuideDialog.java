package com.xm486.pimet.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.xm486.pimet.automation.PiMetAccessibilityService;
import com.xm486.pimet.pet.PetMemoryManager;
import com.xm486.pimet.pet.PetOverlayService;

import java.util.ArrayList;
import java.util.List;

/**
 * PiMet 全功能使用与操作指南弹窗 (HelpGuideDialog)
 * 为用户提供详尽清晰的入门指引，包含：
 * 1. 动态桌宠双轨分流交互（正常说话独立空间 vs . 开头主工作区插话）
 * 2. 手机屏幕无障碍全自动化操控（! 前缀与 phone_control）
 * 3. Pi-Web 工作台与 PRoot Linux 终端
 * 4. 文件管理与无损备份迁移
 * 5. 常用快捷指令与常见问题 (FAQ)
 */
public class HelpGuideDialog {

    private final Context context;
    private AlertDialog dialog;
    private LinearLayout contentContainer;
    private int currentTab = 0;
    private final List<TextView> tabButtons = new ArrayList<>();

    private static final int TAB_PET = 0;
    private static final int TAB_AUTOMATION = 1;
    private static final int TAB_WORKSPACE = 2;
    private static final int TAB_FILE = 3;
    private static final int TAB_FAQ = 4;

    public HelpGuideDialog(Context context) {
        this.context = context;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                context.getResources().getDisplayMetrics());
    }

    public void show() {
        if (dialog != null && dialog.isShowing()) return;

        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(14), dp(12), dp(14), dp(12));

            GradientDrawable rootBg = new GradientDrawable();
            rootBg.setColor(0xFF0D1117);
            rootBg.setCornerRadius(dp(12));
            rootBg.setStroke(dp(1), 0xFF30363D);
            root.setBackground(rootBg);

            // ---- 1. 顶栏：标题 + 描述 + 关闭按钮 ----
            LinearLayout header = new LinearLayout(context);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);

            LinearLayout titleCol = new LinearLayout(context);
            titleCol.setOrientation(LinearLayout.VERTICAL);

            TextView tvTitle = new TextView(context);
            tvTitle.setText("📖 PiMet 使用指南与操作手册");
            tvTitle.setTextColor(0xFFF0F6FC);
            tvTitle.setTextSize(14.5f);
            tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
            titleCol.addView(tvTitle);

            TextView tvSub = new TextView(context);
            tvSub.setText("桌宠双轨插话 · 手机全自动控制 · 工作台与备份迁移");
            tvSub.setTextColor(0xFF8B949E);
            tvSub.setTextSize(10f);
            tvSub.setPadding(0, dp(1), 0, 0);
            titleCol.addView(tvSub);

            header.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView btnClose = new TextView(context);
            btnClose.setText("✕");
            btnClose.setTextColor(0xFF8B949E);
            btnClose.setTextSize(16f);
            btnClose.setPadding(dp(8), dp(4), dp(8), dp(4));
            btnClose.setOnClickListener(v -> dismiss());
            header.addView(btnClose);
            root.addView(header);

            // ---- 2. 分类标签横滑栏 (Tabs) ----
            HorizontalScrollView tabScroll = new HorizontalScrollView(context);
            tabScroll.setHorizontalScrollBarEnabled(false);
            tabScroll.setPadding(0, dp(8), 0, dp(8));

            LinearLayout tabRow = new LinearLayout(context);
            tabRow.setOrientation(LinearLayout.HORIZONTAL);

            tabButtons.clear();
            tabRow.addView(createTabBtn("🐾 桌宠与插话", TAB_PET));
            tabRow.addView(createTabBtn("📱 手机自动化", TAB_AUTOMATION));
            tabRow.addView(createTabBtn("💻 工作台与终端", TAB_WORKSPACE));
            tabRow.addView(createTabBtn("📁 文件与备份", TAB_FILE));
            tabRow.addView(createTabBtn("⚡ 指令与 FAQ", TAB_FAQ));

            tabScroll.addView(tabRow);
            root.addView(tabScroll);

            // ---- 3. 内容滚动区 ----
            ScrollView scroll = new ScrollView(context);
            scroll.setFillViewport(true);

            contentContainer = new LinearLayout(context);
            contentContainer.setOrientation(LinearLayout.VERTICAL);
            contentContainer.setPadding(0, dp(4), 0, dp(6));
            scroll.addView(contentContainer);

            LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            root.addView(scroll, scrollLp);

            // 渲染默认 Tab
            switchTab(TAB_PET);

            builder.setView(root);
            dialog = builder.create();

            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                if (!(context instanceof Activity)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                    } else {
                        window.setType(WindowManager.LayoutParams.TYPE_PHONE);
                    }
                }
            }

            dialog.show();

            if (window != null) {
                WindowManager.LayoutParams lp = window.getAttributes();
                int screenW = context.getResources().getDisplayMetrics().widthPixels;
                int screenH = context.getResources().getDisplayMetrics().heightPixels;
                lp.width = Math.min(screenW - dp(24), dp(480));
                lp.height = Math.min((int) (screenH * 0.88f), dp(620));
                lp.gravity = Gravity.CENTER;
                window.setAttributes(lp);
            }

        } catch (Throwable t) {
            Toast.makeText(context, "打开使用指南失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public void dismiss() {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
            dialog = null;
        }
    }

    private View createTabBtn(String title, int tabIndex) {
        TextView tab = new TextView(context);
        tab.setText(title);
        tab.setTextSize(11f);
        tab.setTypeface(Typeface.DEFAULT_BOLD);
        tab.setPadding(dp(10), dp(5), dp(10), dp(5));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        tab.setLayoutParams(lp);

        tab.setOnClickListener(v -> switchTab(tabIndex));
        tabButtons.add(tab);
        return tab;
    }

    private void switchTab(int tabIndex) {
        currentTab = tabIndex;
        for (int i = 0; i < tabButtons.size(); i++) {
            TextView btn = tabButtons.get(i);
            boolean active = (i == tabIndex);
            GradientDrawable gd = new GradientDrawable();
            gd.setCornerRadius(dp(6));
            if (active) {
                gd.setColor(0xFF1F6FEB);
                gd.setStroke(dp(1), 0xFF58A6FF);
                btn.setTextColor(0xFFFFFFFF);
            } else {
                gd.setColor(0xFF161B22);
                gd.setStroke(dp(1), 0xFF30363D);
                btn.setTextColor(0xFF8B949E);
            }
            btn.setBackground(gd);
        }

        contentContainer.removeAllViews();
        switch (tabIndex) {
            case TAB_PET:
                renderPetGuide();
                break;
            case TAB_AUTOMATION:
                renderAutomationGuide();
                break;
            case TAB_WORKSPACE:
                renderWorkspaceGuide();
                break;
            case TAB_FILE:
                renderFileGuide();
                break;
            case TAB_FAQ:
                renderFaqGuide();
                break;
        }
    }

    // ==================== 1. 动态桌宠与插话指南 ====================
    private void renderPetGuide() {
        addCardTitle("🐾 动态桌宠与智能双轨交互");
        addCardDesc("PiMet 桌宠不仅是常驻桌面的可爱伴侣，更是随时与主 Agent 会话沟通的「交互麦克风」。");

        addSectionHeader("1. 双轨交互机制（核心原理）");
        addBulletPoint("💬 正常直接说话", "自动开启桌宠【专属独立聊天专区】，拥有专属人设与记忆。无论主人怎么和桌宠闲聊，绝不打扰或污染主工作区长流程代码编写。");
        addBulletPoint("⚡ 以 . 或 。 开头", "【直接对主工作区活跃会话插话】！直接将消息注入当前 Web 端编程 Agent 会话，在 Agent 跑任务、编译代码或思考时，随时下达干预指令、追问进展或补充要求。");
        addBulletPoint("📱 以 ! 或 ！ 开头", "自动开启【手机自动化独立会话】，自动路由 @phone-operator 操作手机。");

        addSectionHeader("2. 常用操作示例");
        addCodeSnippet("• 查主任务进度: 输入 . 或 。 回车即可一键查询 Agent 当前进展\n" +
                       "• 向主代理插话: . 记得把端口改成 8080 并检查是否有报错\n" +
                       "• 与桌宠闲聊: 你好呀，今天天气真不错\n" +
                       "• 操控手机屏幕: ! 点击屏幕正中间的确认按钮");

        addSectionHeader("3. 悬浮菜单与设置");
        addBulletPoint("长按桌宠", "呼出紧凑 HUD 控制菜单，支持一键切换角色、缩放大小、查看实时任务汇报或打开详细设置。");
        addBulletPoint("拖拽与物理惯性", "手指拖拽桌宠可自由移动，甩动有逼真的重力与屏幕边缘反弹效果。");

        // 按钮行：探测主工作区会话
        LinearLayout btnRow = new LinearLayout(context);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(6), 0, dp(4));

        Button testSessionBtn = createActionButton("🔍 探测主工作区活跃会话", 0xFF0284C7, v -> {
            String activeId = PetMemoryManager.getActiveMainSessionId(context);
            if (activeId != null && !activeId.isEmpty()) {
                PetMemoryManager.MainSessionInfo info = PetMemoryManager.getMainSessionInfo(context);
                String cwd = (info != null && !info.cwd.isEmpty()) ? info.cwd : "/root";
                Toast.makeText(context, "🟢 检测到主工作区活跃会话: " + activeId.substring(0, Math.min(8, activeId.length())) + "... (" + cwd + ")", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(context, "🟡 暂无活跃主工作区会话，请先在 Pi-Web 发送一条消息即可建立", Toast.LENGTH_SHORT).show();
            }
        });
        btnRow.addView(testSessionBtn);
        contentContainer.addView(btnRow);
    }

    // ==================== 2. 手机自动化操作指南 ====================
    private void renderAutomationGuide() {
        addCardTitle("📱 手机屏幕免 OCR 全自动化");
        addCardDesc("基于 Android 原生无障碍辅助框架，无需配置投屏或截屏 OCR，全窗口多层级穿透识别屏幕上每一个文字与控件。");

        addSectionHeader("1. 前置授权步骤");
        boolean isRunning = PiMetAccessibilityService.isRunning();
        addBulletPoint("无障碍辅助权限", isRunning ? "🟢 已授权并正常运行" : "🔴 尚未开启（点击下方按钮前往授权）");
        addBulletPoint("悬浮窗权限", "用于桌宠展示及跨应用无缝悬浮操作");

        addSectionHeader("2. 如何使用手机控制");
        addBulletPoint("桌宠输入框直接驱动", "以 ! (英文感叹号) 或 ！(中文感叹号) 开头，输入你的自然语言操作意图。");
        addCodeSnippet("• ! 看看当前屏幕上有哪些文字\n" +
                       "• ! 点击登录\n" +
                       "• ! 向下滑动一屏\n" +
                       "• ! 在搜索框输入 React 并搜索\n" +
                       "• ! 返回桌面");
        addBulletPoint("AI Agent 工具调用", "主工作区 Agent 内置了 phone_control 工具，支持 inspect (读取全屏)、tap (精准语义溯源点击)、swipe (滑动)、input (填字)、key (物理按键) 等高阶操作。");

        // 快捷授权按钮
        LinearLayout btnRow = new LinearLayout(context);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(6), 0, dp(4));

        Button authBtn = createActionButton(isRunning ? "⚙️ 打开系统无障碍设置" : "🚀 去开启手机自动化无障碍服务", 0xFF0284C7, v -> {
            PiMetAccessibilityService.openAccessibilitySettings(context);
            Toast.makeText(context, "请在已安装的服务中找到「PiMet 手机自动化服务」并开启", Toast.LENGTH_LONG).show();
        });
        btnRow.addView(authBtn);
        contentContainer.addView(btnRow);
    }

    // ==================== 3. 工作台与终端指南 ====================
    private void renderWorkspaceGuide() {
        addCardTitle("💻 Pi-Web 工作台与 PRoot Linux 终端");
        addCardDesc("移动端完整的前端 Web IDE 与底层 Linux 容器架构，开箱即用。");

        addSectionHeader("1. Pi-Web 工作台");
        addBulletPoint("工作台入口", "点击底部导航栏「工作台」，即可进入移动端全功能代码编辑器与 AI 交互面板。");
        addBulletPoint("服务端口", "默认运行于 http://127.0.0.1:13580，支持在「设置 -> 端口管理」中按需自定义。");
        addBulletPoint("会话持久化", "所有会话自动保存在 ~/.pi/agent/ 目录，会话索引与任务进度自动被桌宠智能嗅探。");

        addSectionHeader("2. Linux 终端环境");
        addBulletPoint("终端入口", "点击底部导航栏「终端」，直接进入 PRoot Linux 容器内。");
        addBulletPoint("内置运行环境", "内置 Node.js 24.x、npm、curl、git 等完整开发者工具链。");
        addBulletPoint("非破坏性升级", "升级新版 APK 时，所有工作区代码、数据库、npm 包与全局配置自动平滑保留，绝不清空数据。");
    }

    // ==================== 4. 文件与备份迁移指南 ====================
    private void renderFileGuide() {
        addCardTitle("📁 文件管理与无损备份迁移");
        addCardDesc("方便快捷地管理容器内部文件，并支持跨设备无损整包打包与恢复。");

        addSectionHeader("1. 文件管理器");
        addBulletPoint("打开方式", "在主页启动卡片点击「文件管理」，即可穿透浏览 /root 容器工作区全部层级。");
        addBulletPoint("功能支持", "支持文件与文件夹查看大小、时间、长按删除、重命名、文本安全预览与外部下载。");

        addSectionHeader("2. 整包无损备份与迁移");
        addBulletPoint("一键整包导出", "在设置中点击「备份与存储」，可将工作区会话、配置、自定义插件、桌宠记忆打包为 .zip。");
        addBulletPoint("新机自愈恢复", "在新手机上安装 PiMet 后，导入备份 zip 包即可一键自愈修复路径并完整复原工作环境。");
    }

    // ==================== 5. 快捷指令与 FAQ ====================
    private void renderFaqGuide() {
        addCardTitle("⚡ 快捷指令与常见问题 (FAQ)");
        addCardDesc("遇到疑问或需要快速操作时查阅。");

        addSectionHeader("1. 桌宠常用指令速查");
        addCodeSnippet("• #help / #?    : 显示快捷指令手册\n" +
                       "• #reset        : 清空当前桌宠专属会话，开启新空间\n" +
                       "• #phone        : 查看手机自动化专属控制指南\n" +
                       "• #piweb        : 快速切换对话模式为 Pi-Web\n" +
                       "• #operit       : 切换对话模式为 Operit 本地模式\n" +
                       "• #api          : 切换对话模式为自定义 OpenAI API 模式\n" +
                       "• . 或 。       : 直连主工作区活跃会话插话 / 查进度\n" +
                       "• ! 或 ！       : 专属控制手机屏幕自动化");

        addSectionHeader("2. 常见问题排查");
        addBulletPoint("Q: 插话提示「暂无活跃主工作区会话」？", "A: 说明 Web 工作台暂无正在执行的会话，请先进入 Pi-Web 发送一条消息开启任务，桌宠即可自动感知并绑定。");
        addBulletPoint("Q: 手机自动化点击无效？", "A: 请确认已在系统无障碍设置中开启「PiMet 手机自动化服务」，且在电池优化中将 PiMet 设为「无限制 / 允许后台自启」。");
        addBulletPoint("Q: 桌宠不见了怎么重新显示？", "A: 在主页点击「桌宠开关」即可开启，如被系统清理，可在桌宠设置中开启保活通知。");
    }

    // ==================== 帮助组件与样式辅助方法 ====================
    private void addCardTitle(String title) {
        TextView tv = new TextView(context);
        tv.setText(title);
        tv.setTextColor(0xFF58A6FF);
        tv.setTextSize(13f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(2), 0, dp(1));
        contentContainer.addView(tv);
    }

    private void addCardDesc(String desc) {
        TextView tv = new TextView(context);
        tv.setText(desc);
        tv.setTextColor(0xFF8B949E);
        tv.setTextSize(10f);
        tv.setPadding(0, 0, 0, dp(8));
        contentContainer.addView(tv);
    }

    private void addSectionHeader(String header) {
        TextView tv = new TextView(context);
        tv.setText(header);
        tv.setTextColor(0xFFF0F6FC);
        tv.setTextSize(11.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(6), 0, dp(2));
        contentContainer.addView(tv);
    }

    private void addBulletPoint(String label, String detail) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(4), dp(2), dp(4), dp(4));

        TextView tvLabel = new TextView(context);
        tvLabel.setText("• " + label);
        tvLabel.setTextColor(0xFF79C0FF);
        tvLabel.setTextSize(10.5f);
        tvLabel.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(tvLabel);

        TextView tvDetail = new TextView(context);
        tvDetail.setText(detail);
        tvDetail.setTextColor(0xFFC9D1D9);
        tvDetail.setTextSize(10f);
        tvDetail.setPadding(dp(8), dp(1), 0, 0);
        row.addView(tvDetail);

        contentContainer.addView(row);
    }

    private void addCodeSnippet(String code) {
        TextView tv = new TextView(context);
        tv.setText(code);
        tv.setTextColor(0xFF7EE787);
        tv.setTextSize(9.5f);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setPadding(dp(8), dp(6), dp(8), dp(6));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF161B22);
        bg.setCornerRadius(dp(4));
        bg.setStroke(dp(1), 0xFF30363D);
        tv.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(3);
        lp.bottomMargin = dp(4);
        tv.setLayoutParams(lp);

        contentContainer.addView(tv);
    }

    private Button createActionButton(String text, int color, View.OnClickListener listener) {
        Button btn = new Button(context);
        btn.setText(text);
        btn.setTextColor(0xFFFFFFFF);
        btn.setTextSize(10.5f);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setPadding(dp(12), dp(4), dp(12), dp(4));
        btn.setMinHeight(dp(30));
        btn.setMinimumHeight(dp(30));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(6));
        btn.setBackground(bg);

        btn.setOnClickListener(listener);
        return btn;
    }
}
