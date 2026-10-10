package com.xm486.pimet.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
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

import com.xm486.pimet.PiWebActivity;
import com.xm486.pimet.PluginManager;
import com.xm486.pimet.PluginManager.PluginItem;

import java.util.ArrayList;
import java.util.List;

/**
 * 插件与生态商店 (Plugin & Ecosystem Store)
 * 包含：
 * 1. 精选官方扩展、MCP 服务、核心技能库的一键安装与状态识别
 * 2. 热门 MCP、Pi-Web 生态网站目录，支持应用内直接浏览并一键调用通用网页翻译（文/A）
 */
public class PluginStoreDialog {

    public static class StoreItem {
        public final String id;
        public final String title;
        public final int type;
        public final String badge;
        public final String desc;
        public final String installPayload;

        public StoreItem(String id, String title, int type, String badge, String desc, String installPayload) {
            this.id = id;
            this.title = title;
            this.type = type;
            this.badge = badge;
            this.desc = desc;
            this.installPayload = installPayload;
        }
    }

    public static class PortalSite {
        public final String title;
        public final String url;
        public final String badge;
        public final String desc;

        public PortalSite(String title, String url, String badge, String desc) {
            this.title = title;
            this.url = url;
            this.badge = badge;
            this.desc = desc;
        }
    }

    private static final List<StoreItem> CURATED_ITEMS = new ArrayList<>();
    private static final List<PortalSite> PORTAL_SITES = new ArrayList<>();

    static {
        // ---- 官方与精选扩展生态 ----
        CURATED_ITEMS.add(new StoreItem(
                "oh-my-pi",
                "oh-my-pi 智能多代理编排",
                PluginItem.TYPE_EXTENSION,
                "官方扩展",
                "全能多代理架构，集成 Oracle(高智商架构顾问)、Librarian(多库检索)、Metis 与 Momus 等专家角色，支持意图精准分发。",
                "oh-my-pi"
        ));
        CURATED_ITEMS.add(new StoreItem(
                "@bacnh85/pi-ux",
                "pi-ux 工业级设计审计与规约",
                PluginItem.TYPE_EXTENSION,
                "官方扩展",
                "反劣质 AI 生成的 UI/UX 设计防糙规约，严格约束色彩对比度(APCA)、Tokens、无障碍和组件交互全状态。",
                "@bacnh85/pi-ux"
        ));
        CURATED_ITEMS.add(new StoreItem(
                "pi-hermes-memory",
                "pi-hermes-memory 持久记忆引擎",
                PluginItem.TYPE_EXTENSION,
                "官方扩展",
                "跨会话持久化经验积累与情境记忆系统，支持用户偏好自动提炼与长期学习演进。",
                "pi-hermes-memory"
        ));
        CURATED_ITEMS.add(new StoreItem(
                "pi-zh-slash",
                "pi-zh-slash 中文快捷指令",
                PluginItem.TYPE_EXTENSION,
                "官方扩展",
                "中文斜杠指令本地化与提示词工作流扩展，显著提升移动端中文交互与开发效率。",
                "pi-zh-slash"
        ));

        // ---- 核心 MCP 协议服务 ----
        CURATED_ITEMS.add(new StoreItem(
                "@modelcontextprotocol/server-filesystem",
                "MCP 本地文件系统管理",
                PluginItem.TYPE_MCP,
                "MCP 服务",
                "官方核心服务，为 AI 赋予受控的文件读写、目录遍历与代码检视能力。",
                "@modelcontextprotocol/server-filesystem"
        ));
        CURATED_ITEMS.add(new StoreItem(
                "mcp-server-fetch",
                "MCP 网页与内容抓取服务",
                PluginItem.TYPE_MCP,
                "MCP 服务",
                "高性能网页获取与 HTML 解析工具，将网页快速转换为清晰的 Markdown 结构供 AI 分析。",
                "mcp-server-fetch"
        ));
        CURATED_ITEMS.add(new StoreItem(
                "@modelcontextprotocol/server-memory",
                "MCP 知识图谱记忆库",
                PluginItem.TYPE_MCP,
                "MCP 服务",
                "基于实体与关系图谱的持久化知识库，实现跨任务的长期背景信息存储。",
                "@modelcontextprotocol/server-memory"
        ));
        CURATED_ITEMS.add(new StoreItem(
                "@modelcontextprotocol/server-github",
                "MCP GitHub 仓库集成",
                PluginItem.TYPE_MCP,
                "MCP 服务",
                "支持 GitHub 代码仓库浏览、提交、Issue 与 Pull Request 全流程操作。",
                "@modelcontextprotocol/server-github"
        ));
        CURATED_ITEMS.add(new StoreItem(
                "@modelcontextprotocol/server-brave-search",
                "MCP Brave 实时搜索",
                PluginItem.TYPE_MCP,
                "MCP 服务",
                "为 AI 接入全球互联网实时搜索与事实核查能力，查询最新技术文档与资讯。",
                "@modelcontextprotocol/server-brave-search"
        ));
        CURATED_ITEMS.add(new StoreItem(
                "mt",
                "MT 管理器本地 HTTP MCP 桥接",
                PluginItem.TYPE_MCP,
                "本地 HTTP",
                "连接本地 MT 管理器 8788 端口 HTTP MCP 桥接，实现端侧应用逆向与分析交互。",
                "http://127.0.0.1:8788/mcp"
        ));

        // ---- 专家技能库 ----
        CURATED_ITEMS.add(new StoreItem(
                "debugging",
                "系统化假设驱动调试技能",
                PluginItem.TYPE_SKILL,
                "核心技能",
                "假设驱动定位、根因分析与最小化修复的最佳实践，彻底杜绝随机修代码。",
                "skill:debugging"
        ));
        CURATED_ITEMS.add(new StoreItem(
                "code-review",
                "深度代码安全与规范审查",
                PluginItem.TYPE_SKILL,
                "核心技能",
                "系统化代码评审清单，全面覆盖逻辑正确性、代码风格、性能开销与安全漏洞。",
                "skill:code-review"
        ));
        CURATED_ITEMS.add(new StoreItem(
                "frontend",
                "现代前端 UI/UX 组件开发",
                PluginItem.TYPE_SKILL,
                "核心技能",
                "移动端与桌面端响应式 UI 规范，语义化布局与设计系统遵循指南。",
                "skill:frontend"
        ));
        CURATED_ITEMS.add(new StoreItem(
                "playwright",
                "无头浏览器自动化与测试",
                PluginItem.TYPE_SKILL,
                "核心技能",
                "浏览器自动化技能，支持网页截图、组件视觉验证与端到端功能测试。",
                "skill:playwright"
        ));
        CURATED_ITEMS.add(new StoreItem(
                "git-master",
                "Git 原子化版本管理工作流",
                PluginItem.TYPE_SKILL,
                "核心技能",
                "原子化提交规范、变基整理、二分法排错与版本历史检索技能。",
                "skill:git-master"
        ));

        // ---- 热门生态网站与注册中心 ----
        PORTAL_SITES.add(new PortalSite(
                "Model Context Protocol 官方网站",
                "https://modelcontextprotocol.io",
                "协议标准",
                "MCP 官方标准规范、架构模型、开发指南与官方 SDK 核心文档。"
        ));
        PORTAL_SITES.add(new PortalSite(
                "MCP 官方服务器核心仓库 (GitHub)",
                "https://github.com/modelcontextprotocol/servers",
                "核心生态",
                "Anthropic 官方开源的参考 MCP 服务器库，包含文件系统、Fetch、Git、Postgres 等服务源码。"
        ));
        PORTAL_SITES.add(new PortalSite(
                "Glama MCP 全球开放市场与目录",
                "https://glama.co/mcp/servers",
                "发现市场",
                "全球活跃的 MCP 服务器索引平台，支持分类检索、星标排行与一键配置复制。"
        ));
        PORTAL_SITES.add(new PortalSite(
                "Smithery MCP 注册中心与生态平台",
                "https://smithery.ai",
                "注册中心",
                "一键配置和发现 MCP 服务器的现代化开放平台，提供丰富 CLI 与工具集成。"
        ));
        PORTAL_SITES.add(new PortalSite(
                "mcp.so 社区插件生态导航",
                "https://mcp.so",
                "社区精选",
                "精选热门 MCP 插件集合，按开发者工作流与工具链直观分类呈现。"
        ));
        PORTAL_SITES.add(new PortalSite(
                "Pi-Web 移动工作台官方仓库",
                "https://github.com/earendil-works/pi-web",
                "Pi 核心",
                "Pi-Web 移动工作台与编码 Agent Web 界面官方源代码及文档更新动态。"
        ));
        PORTAL_SITES.add(new PortalSite(
                "NPM Pi 扩展生态全景搜索",
                "https://www.npmjs.com/search?q=pi-coding-agent",
                "NPM 注册表",
                "NPM 官方注册表中所有带有 pi-coding-agent 标识的扩展插件与工具包。"
        ));
    }

    public static void show(Context context, Runnable onInstallComplete) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        View dialogView = buildStoreView(context, onInstallComplete);
        builder.setView(dialogView);

        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        dialog.show();

        // 设定弹窗尺寸
        Window window = dialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
            lp.copyFrom(window.getAttributes());
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = (int) (context.getResources().getDisplayMetrics().heightPixels * 0.88f);
            window.setAttributes(lp);
        }
    }

    private static View buildStoreView(Context context, Runnable onInstallComplete) {
        int dp8 = dp(context, 8);
        int dp12 = dp(context, 12);
        int dp16 = dp(context, 16);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp16, dp16, dp16, dp16);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF0181A24);
        bg.setCornerRadius(dp(context, 18));
        bg.setStroke(dp(context, 1), 0x3360A5FA);
        root.setBackground(bg);

        // ---- 顶栏：标题 + 副标题 ----
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleBox = new LinearLayout(context);
        titleBox.setOrientation(LinearLayout.VERTICAL);

        TextView titleTv = new TextView(context);
        titleTv.setText("🏪 插件与生态商店");
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        titleTv.setTextColor(0xFFF8FAFC);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        titleBox.addView(titleTv);

        TextView subTitleTv = new TextView(context);
        subTitleTv.setText("精选官方扩展、MCP 服务中心及全球生态站点一键浏览与即时翻译");
        subTitleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        subTitleTv.setTextColor(0xFF94A3B8);
        subTitleTv.setPadding(0, dp(context, 2), 0, 0);
        titleBox.addView(subTitleTv);

        header.addView(titleBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(header);

        // ---- 分页选项卡 (Tab 切换) ----
        LinearLayout tabRow = new LinearLayout(context);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setPadding(0, dp12, 0, dp8);

        TextView tabCurated = createTabButton(context, "🌟 精选生态市场", true);
        TextView tabWebsites = createTabButton(context, "🌐 热门生态网站", false);
        tabRow.addView(tabCurated);
        tabRow.addView(tabWebsites);
        root.addView(tabRow);

        // ---- 内容容器 ----
        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);

        LinearLayout contentContainer = new LinearLayout(context);
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(contentContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 默认渲染精选市场
        renderCuratedTab(context, contentContainer, onInstallComplete);

        tabCurated.setOnClickListener(v -> {
            updateTabStyle(tabCurated, true);
            updateTabStyle(tabWebsites, false);
            contentContainer.removeAllViews();
            renderCuratedTab(context, contentContainer, onInstallComplete);
        });

        tabWebsites.setOnClickListener(v -> {
            updateTabStyle(tabCurated, false);
            updateTabStyle(tabWebsites, true);
            contentContainer.removeAllViews();
            renderWebsitesTab(context, contentContainer);
        });

        return root;
    }

    private static TextView createTabButton(Context context, String text, boolean active) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(context, 14), dp(context, 7), dp(context, 14), dp(context, 7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(context, 8);
        tv.setLayoutParams(lp);
        updateTabStyle(tv, active);
        return tv;
    }

    private static void updateTabStyle(TextView tv, boolean active) {
        GradientDrawable bg = new GradientDrawable();
        if (active) {
            bg.setColor(0xFF2563EB);
            bg.setCornerRadius(dp(tv.getContext(), 14));
            tv.setTextColor(0xFFFFFFFF);
        } else {
            bg.setColor(0x22334155);
            bg.setCornerRadius(dp(tv.getContext(), 14));
            tv.setTextColor(0xFF94A3B8);
        }
        tv.setBackground(bg);
    }

    private static void renderCuratedTab(Context context, LinearLayout container, Runnable onInstallComplete) {
        List<PluginItem> installed = PluginManager.getInstalledPlugins(context);

        for (StoreItem item : CURATED_ITEMS) {
            boolean isInstalled = checkInstalled(item, installed);
            container.addView(buildItemCard(context, item, isInstalled, onInstallComplete));
        }
    }

    private static boolean checkInstalled(StoreItem item, List<PluginItem> installed) {
        for (PluginItem p : installed) {
            if (p.name.equalsIgnoreCase(item.id)) return true;
            if (p.rawPkgName != null && p.rawPkgName.equalsIgnoreCase(item.id)) return true;
            if (item.id.contains("/") && p.name.endsWith(item.id.substring(item.id.lastIndexOf('/') + 1))) return true;
        }
        return false;
    }

    private static View buildItemCard(Context context, StoreItem item, boolean installed, Runnable onInstallComplete) {
        int dp8 = dp(context, 8);
        int dp10 = dp(context, 10);
        int dp12 = dp(context, 12);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp12, dp10, dp12, dp10);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0x221E293B);
        cardBg.setCornerRadius(dp(context, 12));
        cardBg.setStroke(dp(context, 1), 0x22475569);
        card.setBackground(cardBg);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp8;
        card.setLayoutParams(cardLp);

        // 首行：标题 + Badge 标签 + 动作按钮
        LinearLayout row1 = new LinearLayout(context);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleTv = new TextView(context);
        titleTv.setText(item.title);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        titleTv.setTextColor(0xFFF1F5F9);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        row1.addView(titleTv);

        TextView badgeTv = new TextView(context);
        badgeTv.setText(item.badge);
        badgeTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        badgeTv.setTextColor(0xFF38BDF8);
        badgeTv.setPadding(dp(context, 6), dp(context, 2), dp(context, 6), dp(context, 2));
        GradientDrawable bBg = new GradientDrawable();
        bBg.setColor(0x220284C7);
        bBg.setCornerRadius(dp(context, 6));
        badgeTv.setBackground(bBg);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bLp.leftMargin = dp(context, 6);
        row1.addView(badgeTv, bLp);

        View spacer = new View(context);
        row1.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        Button actionBtn = new Button(context);
        actionBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        actionBtn.setPadding(dp(context, 10), dp(context, 4), dp(context, 10), dp(context, 4));
        actionBtn.setMinHeight(0);
        actionBtn.setMinWidth(0);
        actionBtn.setIncludeFontPadding(false);

        if (installed) {
            actionBtn.setText("✔ 已安装");
            actionBtn.setTextColor(0xFF10B981);
            GradientDrawable btnBg = new GradientDrawable();
            btnBg.setColor(0x2210B981);
            btnBg.setCornerRadius(dp(context, 8));
            btnBg.setStroke(dp(context, 1), 0x5510B981);
            actionBtn.setBackground(btnBg);
            actionBtn.setEnabled(false);
        } else {
            actionBtn.setText("⬇ 一键安装");
            actionBtn.setTextColor(0xFFFFFFFF);
            GradientDrawable btnBg = new GradientDrawable();
            btnBg.setColor(0xFF2563EB);
            btnBg.setCornerRadius(dp(context, 8));
            actionBtn.setBackground(btnBg);

            actionBtn.setOnClickListener(v -> {
                actionBtn.setEnabled(false);
                actionBtn.setText("⏳ 安装中...");
                Toast.makeText(context, "正在为容器安装: " + item.id + " ...", Toast.LENGTH_SHORT).show();

                new Thread(() -> {
                    PluginManager.SmartInstallResult res = PluginManager.smartInstall(context, item.installPayload);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (res.success) {
                            Toast.makeText(context, "✔ " + item.title + " 安装并配置成功！", Toast.LENGTH_SHORT).show();
                            actionBtn.setText("✔ 已安装");
                            actionBtn.setTextColor(0xFF10B981);
                            GradientDrawable doneBg = new GradientDrawable();
                            doneBg.setColor(0x2210B981);
                            doneBg.setCornerRadius(dp(context, 8));
                            actionBtn.setBackground(doneBg);
                            if (onInstallComplete != null) onInstallComplete.run();
                        } else {
                            Toast.makeText(context, "安装失败: " + res.message, Toast.LENGTH_LONG).show();
                            actionBtn.setEnabled(true);
                            actionBtn.setText("⬇ 重试安装");
                        }
                    });
                }).start();
            });
        }

        row1.addView(actionBtn);
        card.addView(row1);

        // 描述
        TextView descTv = new TextView(context);
        descTv.setText(item.desc);
        descTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        descTv.setTextColor(0xFF94A3B8);
        descTv.setLineSpacing(dp(context, 2), 1.15f);
        descTv.setPadding(0, dp(context, 4), 0, 0);
        card.addView(descTv);

        return card;
    }

    private static void renderWebsitesTab(Context context, LinearLayout container) {
        int dp8 = dp(context, 8);
        int dp10 = dp(context, 10);
        int dp12 = dp(context, 12);

        // 顶部翻译提示栏
        LinearLayout tipBox = new LinearLayout(context);
        tipBox.setOrientation(LinearLayout.VERTICAL);
        tipBox.setPadding(dp12, dp8, dp12, dp8);
        GradientDrawable tipBg = new GradientDrawable();
        tipBg.setColor(0x2A0284C7);
        tipBg.setCornerRadius(dp(context, 10));
        tipBg.setStroke(dp(context, 1), 0x4438BDF8);
        tipBox.setBackground(tipBg);
        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tipLp.bottomMargin = dp12;
        tipBox.setLayoutParams(tipLp);

        TextView tipTv = new TextView(context);
        tipTv.setText("💡 提示：在应用内打开任何英文生态网站时，可直接点击顶栏「文/A」按钮将全站一键翻译为中文！");
        tipTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tipTv.setTextColor(0xFFE0F2FE);
        tipBox.addView(tipTv);
        container.addView(tipBox);

        for (PortalSite site : PORTAL_SITES) {
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp12, dp10, dp12, dp10);

            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setColor(0x221E293B);
            cardBg.setCornerRadius(dp(context, 12));
            cardBg.setStroke(dp(context, 1), 0x22475569);
            card.setBackground(cardBg);

            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.bottomMargin = dp8;
            card.setLayoutParams(cardLp);

            // 标题 + 标签
            LinearLayout rowTitle = new LinearLayout(context);
            rowTitle.setOrientation(LinearLayout.HORIZONTAL);
            rowTitle.setGravity(Gravity.CENTER_VERTICAL);

            TextView titleTv = new TextView(context);
            titleTv.setText(site.title);
            titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            titleTv.setTextColor(0xFFF1F5F9);
            titleTv.setTypeface(Typeface.DEFAULT_BOLD);
            rowTitle.addView(titleTv);

            TextView badgeTv = new TextView(context);
            badgeTv.setText(site.badge);
            badgeTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
            badgeTv.setTextColor(0xFF38BDF8);
            badgeTv.setPadding(dp(context, 6), dp(context, 2), dp(context, 6), dp(context, 2));
            GradientDrawable bBg = new GradientDrawable();
            bBg.setColor(0x220284C7);
            bBg.setCornerRadius(dp(context, 6));
            badgeTv.setBackground(bBg);
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bLp.leftMargin = dp(context, 6);
            rowTitle.addView(badgeTv, bLp);
            card.addView(rowTitle);

            // URL
            TextView urlTv = new TextView(context);
            urlTv.setText(site.url);
            urlTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            urlTv.setTextColor(0xFF38BDF8);
            urlTv.setPadding(0, dp(context, 2), 0, dp(context, 3));
            card.addView(urlTv);

            // 描述
            TextView descTv = new TextView(context);
            descTv.setText(site.desc);
            descTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            descTv.setTextColor(0xFF94A3B8);
            descTv.setLineSpacing(dp(context, 2), 1.15f);
            card.addView(descTv);

            // 操作按钮行：应用内浏览 (支持翻译) + 外部浏览器
            LinearLayout btnRow = new LinearLayout(context);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            btnRow.setPadding(0, dp(context, 8), 0, 0);

            Button btnInApp = new Button(context);
            btnInApp.setText("📱 应用内浏览 (支持即时翻译)");
            btnInApp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            btnInApp.setTextColor(0xFFFFFFFF);
            btnInApp.setPadding(dp(context, 10), dp(context, 4), dp(context, 10), dp(context, 4));
            btnInApp.setMinHeight(0);
            btnInApp.setMinWidth(0);
            GradientDrawable inAppBg = new GradientDrawable();
            inAppBg.setColor(0xFF2563EB);
            inAppBg.setCornerRadius(dp(context, 8));
            btnInApp.setBackground(inAppBg);
            btnInApp.setOnClickListener(v -> {
                try {
                    Intent intent = new Intent(context, PiWebActivity.class);
                    intent.putExtra(PiWebActivity.EXTRA_URL, site.url);
                    intent.putExtra(PiWebActivity.EXTRA_TITLE, site.title);
                    context.startActivity(intent);
                } catch (Throwable t) {
                    Toast.makeText(context, "打开失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
            btnRow.addView(btnInApp);

            Button btnExternal = new Button(context);
            btnExternal.setText("🌐 外部打开");
            btnExternal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            btnExternal.setTextColor(0xFFCBD5E1);
            btnExternal.setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 4));
            btnExternal.setMinHeight(0);
            btnExternal.setMinWidth(0);
            GradientDrawable extBg = new GradientDrawable();
            extBg.setColor(0x22475569);
            extBg.setCornerRadius(dp(context, 8));
            btnExternal.setBackground(extBg);
            LinearLayout.LayoutParams extLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            extLp.leftMargin = dp(context, 8);
            btnExternal.setLayoutParams(extLp);
            btnExternal.setOnClickListener(v -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(site.url));
                    context.startActivity(Intent.createChooser(intent, "选择浏览器打开"));
                } catch (Throwable t) {
                    Toast.makeText(context, "打开浏览器失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
            btnRow.addView(btnExternal);

            card.addView(btnRow);
            container.addView(card);
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
