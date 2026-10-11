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

import com.xm486.pimet.ThemeManager;
import com.xm486.pimet.automation.PiMetAccessibilityService;
import com.xm486.pimet.pet.PetMemoryManager;

import java.util.ArrayList;
import java.util.List;

/**
 * PiMet 全功能使用与操作指南弹窗 (HelpGuideDialog)
 * 具备以下特性：
 * 1. 深度对齐 ThemeManager 主题配色（自动适配 Light, Dark, Mist, Rose, Pine）
 * 2. 全方位涵盖整个软件的难懂操作、高级技巧与核心避坑指南：
 *    - 桌宠双轨交互（正常说话独立空间 vs . 主工作区插话 vs ! 手机控制）
 *    - 手机屏幕免 OCR 全自动化与系统防杀保活设置
 *    - 守护服务、详细诊断日志排查与 Pi-Web 工作台
 *    - PRoot Linux 终端常用命令、端口与休眠唤醒
 *    - 插件与生态管理（热启停开关、MCP 与 Extensions）
 *    - 容器文件深度管理、zip整包无损备份与换机自愈修复
 *    - 常见故障排查 (FAQ)
 */
public class HelpGuideDialog {

    private final Context context;
    private AlertDialog dialog;
    private LinearLayout contentContainer;
    private int currentTab = 0;
    private final List<TextView> tabButtons = new ArrayList<>();
    private ThemeManager.ThemePalette palette;

    private static final int TAB_PET = 0;
    private static final int TAB_AUTOMATION = 1;
    private static final int TAB_WORKSPACE = 2;
    private static final int TAB_PLUGINS = 3;
    private static final int TAB_FILE = 4;
    private static final int TAB_FAQ = 5;

    public HelpGuideDialog(Context context) {
        this.context = context;
        this.palette = ThemeManager.getEffectivePalette(context);
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                context.getResources().getDisplayMetrics());
    }

    public void show() {
        if (dialog != null && dialog.isShowing()) return;

        try {
            this.palette = ThemeManager.getEffectivePalette(context);
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(14), dp(12), dp(14), dp(12));

            GradientDrawable rootBg = new GradientDrawable();
            rootBg.setColor(palette.bgPanel);
            rootBg.setCornerRadius(dp(12));
            rootBg.setStroke(dp(1), palette.border);
            root.setBackground(rootBg);

            // ---- 1. 顶栏：标题 + 描述 + 关闭按钮 ----
            LinearLayout header = new LinearLayout(context);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);

            LinearLayout titleCol = new LinearLayout(context);
            titleCol.setOrientation(LinearLayout.VERTICAL);

            TextView tvTitle = new TextView(context);
            tvTitle.setText("📖 PiMet 全功能使用与操作手册");
            tvTitle.setTextColor(palette.text);
            tvTitle.setTextSize(14.5f);
            tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
            titleCol.addView(tvTitle);

            TextView tvSub = new TextView(context);
            tvSub.setText("桌宠插话 · 手机控制 · 工作台与终端 · 插件启停 · 备份自愈");
            tvSub.setTextColor(palette.textMuted);
            tvSub.setTextSize(10f);
            tvSub.setPadding(0, dp(1), 0, 0);
            titleCol.addView(tvSub);

            header.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView btnClose = new TextView(context);
            btnClose.setText("✕");
            btnClose.setTextColor(palette.textMuted);
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
            tabRow.addView(createTabBtn("🧩 插件与启停", TAB_PLUGINS));
            tabRow.addView(createTabBtn("📁 文件与备份", TAB_FILE));
            tabRow.addView(createTabBtn("⚡ 技巧与 FAQ", TAB_FAQ));

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
                lp.width = Math.min(screenW - dp(20), dp(520));
                lp.height = Math.min((int) (screenH * 0.90f), dp(660));
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
                gd.setColor(palette.accent);
                btn.setTextColor(palette.accentContrast);
            } else {
                gd.setColor(palette.bgHover);
                gd.setStroke(dp(1), palette.border);
                btn.setTextColor(palette.textMuted);
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
            case TAB_PLUGINS:
                renderPluginsGuide();
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
        addCardDesc("桌宠常驻手机桌面，兼具「可爱伴侣」与「主会话插话麦克风」双重能力。");

        addSectionHeader("1. 双轨消息路由（核心交互语法）");
        addBulletPoint("💬 正常直接说话", "进入桌宠【专属独立聊天专区】，拥有专属人设与记忆。无论主人怎么和桌宠闲聊，绝不打扰或污染主工作区长流程代码编写。");
        addBulletPoint("⚡ 以 . 或 。 开头", "【直接对主工作区活跃会话插话】！直接将消息注入当前 Web 端编程 Agent 会话，在 Agent 跑任务、编译代码或思考时，随时下达干预指令、追问进展或补充要求。");
        addBulletPoint("📱 以 ! 或 ！ 开头", "进入【手机自动化独立会话】，自动路由 @phone-operator 操作手机。");

        addSectionHeader("2. 常用操作示例与语法");
        addCodeSnippet("• 查主任务进度: 输入 . 或 。 回车即可一键查询 Agent 当前进展\n" +
                       "• 向主代理插话: . 记得把端口改成 8080 并检查是否有报错\n" +
                       "• 与桌宠闲聊: 你好呀，今天天气真不错\n" +
                       "• 操控手机屏幕: ! 点击屏幕正中间的确认按钮\n" +
                       "• 查常用指令: #help  |  重置专属会话: #reset\n" +
                       "• 切换后端: #piweb / #operit / #api");

        addSectionHeader("3. 悬浮菜单、手势与物理特性");
        addBulletPoint("手势操作", "单击气泡折叠/展开内容；长按气泡快速复制文字；拖拽桌宠带有物理惯性与屏幕反弹。");
        addBulletPoint("长按桌宠", "呼出紧凑 HUD 控制菜单，支持一键切换角色、缩放大小、查看实时任务汇报或打开详细设置。");

        // 按钮行：探测主工作区会话
        LinearLayout btnRow = new LinearLayout(context);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(6), 0, dp(4));

        Button testSessionBtn = createActionButton("🔍 探测主工作区活跃会话", palette.accent, v -> {
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
        addCardDesc("基于 Android 原生无障碍辅助框架，无需投屏或截图 OCR，全窗口多层级穿透识别屏幕上每一个文字与控件坐标。");

        addSectionHeader("1. 前置授权与关键防杀保活设置");
        boolean isRunning = PiMetAccessibilityService.isRunning();
        addBulletPoint("无障碍辅助权限", isRunning ? "🟢 已授权并正常运行" : "🔴 尚未开启（点击下方按钮前往授权）");
        addBulletPoint("厂商系统防杀保活（极其关键）", "小米/华为/OPPO/vivo等系统常自动关闭无障碍服务。请务必在系统设置中将 PiMet 设为「电池无限制 / 允许自启动」，并在手机多任务界面将 PiMet 卡片加锁！");

        addSectionHeader("2. 自然语言操作手机指令示例");
        addBulletPoint("桌宠输入框直接驱动", "以 ! (英文感叹号) 或 ！(中文感叹号) 开头，输入你的自然语言意图：");
        addCodeSnippet("• ! 看看当前屏幕上有哪些文字 (inspect 全屏穿透识别)\n" +
                       "• ! 点击登录 (根据语义精准溯源点击)\n" +
                       "• ! 向下滑动一屏 (方向: up/down/left/right)\n" +
                       "• ! 在输入框填入 React 并搜索 (智能聚焦输入)\n" +
                       "• ! 返回桌面 / ! 返回上一页 / ! 截图 (物理系统键模拟)");

        addSectionHeader("3. AI 智能体 Agent 工具联动");
        addBulletPoint("phone_control 工具", "主工作区 Agent 内置了 phone_control 专用工具，能够自主调用 inspect、tap、swipe、input、key 完成复杂多步的手机跨 App 操作。");

        // 快捷授权按钮
        LinearLayout btnRow = new LinearLayout(context);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(6), 0, dp(4));

        Button authBtn = createActionButton(isRunning ? "⚙️ 打开系统无障碍设置" : "🚀 去开启手机自动化无障碍服务", palette.accent, v -> {
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

        addSectionHeader("1. Pi-Web 工作台与外部访问");
        addBulletPoint("内置与外部访问", "点击底部「工作台」直接进入代码编辑器与会话流；也可在电脑或手机浏览器直接访问 http://127.0.0.1:13580。");
        addBulletPoint("浮动网页翻译球（文/A）", "工作台右上方提供一键悬浮翻译球，支持双语对照、沉浸式译文替换及划词即时翻译，支持本地 AI 与免费引擎切换。");
        addBulletPoint("休眠唤醒", "若服务异常中断，工作台离线卡片提供「⚡ 立即唤醒服务」一键拉起。");

        addSectionHeader("2. PRoot Linux 终端常用命令技巧");
        addBulletPoint("底层环境", "点击底部「终端」直接进入 PRoot Linux 容器，内置 Node 24.x、npm、curl、git 等环境。");
        addCodeSnippet("• 查看 Node 与守护进程 : ps aux | grep node\n" +
                       "• 查看服务端口监听     : netstat -tlpn 或 ss -tlpn\n" +
                       "• 手动强制重启后台服务 : pkill -f node (随后在主页点启动)\n" +
                       "• 容器根目录位置       : 映射在 App 沙盒内部 /root");

        addSectionHeader("3. 简略日志 vs 详细诊断日志");
        addBulletPoint("排查启动失败", "主页守护日志控制台提供「🔍 详细日志」按钮，开启后可查看容器内 stdout/stderr 与 Node 报错栈，并支持右上角一键复制完整报错。");
    }

    // ==================== 4. 插件生态与启停管理 ====================
    private void renderPluginsGuide() {
        addCardTitle("🧩 插件、生态与热启停管理");
        addCardDesc("无需繁琐命令行，在界面中即可自由安装、配置与随时启停各类插件。");

        addSectionHeader("1. 插件生态体系分类");
        addBulletPoint("扩展 (Extensions)", "TypeScript/JavaScript 脚本模块，赋予 Agent 专属工具与行为（如 @narumitw/pi-btw 插话插件、服务自启等）。");
        addBulletPoint("技能 (Skills)", "最佳工程实践 Markdown 规范，包含代码审查、系统调试、前端规范等。");
        addBulletPoint("MCP 服务", "Model Context Protocol 外部协议服务，支持与本地或远程工具系统联动。");

        addSectionHeader("2. 插件一键启停开关（新功能）");
        addBulletPoint("无损启停", "在设置中的插件列表中，每个插件均配备「🟢 启停: 开 / ⏸ 启停: 关」切换按钮。无需删除插件即可临时禁用，再次点击恢复运行。");
        addBulletPoint("生态商店", "点击「生态商店」可一键安装官方与精选扩展包，或在线浏览 NPM 插件库。");
    }

    // ==================== 5. 文件与备份迁移指南 ====================
    private void renderFileGuide() {
        addCardTitle("📁 文件管理与无损备份迁移");
        addCardDesc("方便快捷地管理容器内部文件，并支持跨设备无损整包打包与路径自愈恢复。");

        addSectionHeader("1. 文件管理器");
        addBulletPoint("打开方式", "主页启动卡片点击「文件管理」，即可穿透浏览 /root 容器工作区全部层级。");
        addBulletPoint("功能支持", "支持文件与文件夹查看大小、时间、长按删除、重命名、文本安全预览与外部下载导出。");

        addSectionHeader("2. 整包无损备份与换机自愈");
        addBulletPoint("一键整包导出", "在设置中点击「备份与存储」，可勾选会话、配置、自定义插件、桌宠记忆导出为标准 .zip。");
        addBulletPoint("新机自愈恢复", "在新手机上安装 PiMet 后导入备份 zip 包，系统会自动检测并重写新设备私有路径与软链接，彻底避免路径变化导致的打不开问题。");
    }

    // ==================== 6. 技巧与 FAQ ====================
    private void renderFaqGuide() {
        addCardTitle("⚡ 高级排查与常见问题 (FAQ)");
        addCardDesc("遇到疑问或异常时的速查与解决指南。");

        addSectionHeader("1. 常见故障排查");
        addBulletPoint("Q: 插话提示「暂无活跃主工作区会话」？", "A: 说明 Web 工作台暂无正在执行的会话，请先进入 Pi-Web 工作台发送一条消息开启任务，桌宠即可自动感知并绑定。");
        addBulletPoint("Q: 手机自动化点击无效或断开？", "A: 确认已开启「PiMet 手机自动化服务」，且在系统电池优化中将 PiMet 设为「无限制」，并在任务列表锁定。");
        addBulletPoint("Q: 提示端口冲突无法启动？", "A: 可能是旧进程残留或端口被占，在主页切换到「详细日志」查看具体端口，进入「设置 -> 端口管理」修改端口后重启。");
        addBulletPoint("Q: 桌宠不见了？", "A: 主页点击「桌宠开关」重新开启；确保已授权「显示在其他应用上层」悬浮窗权限。");

        addSectionHeader("2. 常用全局快捷指令速查");
        addCodeSnippet("• #help / #?    : 显示快捷指令手册\n" +
                       "• #guide / #指南: 弹出完整图文操作手册\n" +
                       "• #reset        : 清空当前桌宠专属会话，开启新空间\n" +
                       "• #phone        : 查看手机自动化专属控制指南\n" +
                       "• #piweb/#operit: 切换对话后端\n" +
                       "• . 或 。       : 直连主工作区活跃会话插话 / 查进度\n" +
                       "• ! 或 ！       : 专属控制手机屏幕自动化");
    }

    // ==================== 帮助组件与样式辅助方法 ====================
    private void addCardTitle(String title) {
        TextView tv = new TextView(context);
        tv.setText(title);
        tv.setTextColor(palette.accent);
        tv.setTextSize(13f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(2), 0, dp(1));
        contentContainer.addView(tv);
    }

    private void addCardDesc(String desc) {
        TextView tv = new TextView(context);
        tv.setText(desc);
        tv.setTextColor(palette.textMuted);
        tv.setTextSize(10f);
        tv.setPadding(0, 0, 0, dp(8));
        contentContainer.addView(tv);
    }

    private void addSectionHeader(String header) {
        TextView tv = new TextView(context);
        tv.setText(header);
        tv.setTextColor(palette.text);
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
        tvLabel.setTextColor(palette.isDark ? 0xFF79C0FF : 0xFF0969DA);
        tvLabel.setTextSize(10.5f);
        tvLabel.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(tvLabel);

        TextView tvDetail = new TextView(context);
        tvDetail.setText(detail);
        tvDetail.setTextColor(palette.text);
        tvDetail.setTextSize(10f);
        tvDetail.setPadding(dp(8), dp(1), 0, 0);
        row.addView(tvDetail);

        contentContainer.addView(row);
    }

    private void addCodeSnippet(String code) {
        TextView tv = new TextView(context);
        tv.setText(code);
        tv.setTextColor(palette.isDark ? 0xFF7EE787 : 0xFF1A7F37);
        tv.setTextSize(9.5f);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setPadding(dp(8), dp(6), dp(8), dp(6));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(palette.bgSunken);
        bg.setCornerRadius(dp(6));
        bg.setStroke(dp(1), palette.border);
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
        btn.setTextColor(palette.accentContrast);
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
