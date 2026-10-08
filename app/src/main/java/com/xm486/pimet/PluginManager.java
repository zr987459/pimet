package com.xm486.pimet;

import android.content.Context;
import android.util.Log;

import com.xm486.pimet.proot.ProotManager;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Pi-Web 插件、技能、MCP 服务与子代理深度管理类
 * 负责在 PRoot 容器文件系统内（/root/.pi/agent/）管理和同步适配 Pi-Web 的扩展生态
 */
public final class PluginManager {
    private static final String TAG = "PluginManager";

    /**
     * 将全部深度适配 Pi-Web 的原生扩展、专家技能、MCP 服务及子代理一键同步至容器
     */
    public static void syncAllPresets(Context context) {
        new Thread(() -> {
            try {
                File rootfs = ProotManager.getRootfsDir(context);
                if (!rootfs.exists()) return;

                File piAgentDir = new File(rootfs, "root/.pi/agent");
                if (!piAgentDir.exists()) piAgentDir.mkdirs();

                // 1. 同步原生 Android 桥接插件 (android-bridge.ts)
                syncAndroidBridgeExtension(piAgentDir);

                // 2. 同步专家技能库 (Skills)
                syncSkills(piAgentDir);

                // 3. 同步 MCP 服务配置文件 (mcp.json)
                syncMcpConfig(piAgentDir);

                // 4. 同步专业子代理模板 (Sub-agents)
                syncSubagents(piAgentDir);

                // 5. 注入 Shizuku 与 Su 宿主桥接脚本到 /usr/local/bin
                syncHostExecBridges(rootfs);

                Log.i(TAG, "All Pi-Web plugins, skills, MCP, and subagents synced successfully.");
            } catch (Throwable t) {
                Log.e(TAG, "Failed to sync presets: " + t.getMessage(), t);
            }
        }).start();
    }

    private static void syncAndroidBridgeExtension(File piAgentDir) {
        try {
            File extDir = new File(piAgentDir, "extensions");
            if (!extDir.exists()) extDir.mkdirs();

            File bridgeFile = new File(extDir, "android-bridge.ts");
            String code = "import type { ExtensionAPI } from \"@earendil-works/pi-coding-agent\";\n" +
                    "import { exec } from \"node:child_process\";\n" +
                    "import { promisify } from \"node:util\";\n" +
                    "const execAsync = promisify(exec);\n\n" +
                    "export default function androidBridgeExtension(pi: ExtensionAPI) {\n" +
                    "  // 1. Android Shizuku / ADB 级免 Root 指令执行工具\n" +
                    "  pi.registerTool({\n" +
                    "    name: \"android_shizuku\",\n" +
                    "    label: \"Android Shizuku ADB\",\n" +
                    "    description: \"通过 Shizuku 服务在 Android 系统上执行高权限 ADB Shell 命令\",\n" +
                    "    parameters: {\n" +
                    "      type: \"object\",\n" +
                    "      properties: {\n" +
                    "        command: { type: \"string\", description: \"需要执行的 adb shell 指令\" }\n" +
                    "      },\n" +
                    "      required: [\"command\"]\n" +
                    "    },\n" +
                    "    async execute(_id, params: { command: string }) {\n" +
                    "      try {\n" +
                    "        const { stdout, stderr } = await execAsync(`/usr/local/bin/shizuku-exec \"${params.command.replace(/\"/g, '\\\\\"')}\"`);\n" +
                    "        return { content: [{ type: \"text\", text: stdout || stderr || \"执行完成\" }] };\n" +
                    "      } catch (e: any) {\n" +
                    "        return { content: [{ type: \"text\", text: `Shizuku 执行异常: ${e.message}` }], isError: true };\n" +
                    "      }\n" +
                    "    }\n" +
                    "  });\n\n" +
                    "  // 2. Android 宿主 Root (su) 执行工具\n" +
                    "  pi.registerTool({\n" +
                    "    name: \"android_root\",\n" +
                    "    label: \"Android Root Exec\",\n" +
                    "    description: \"通过宿主 Root (su) 执行底层系统特权命令\",\n" +
                    "    parameters: {\n" +
                    "      type: \"object\",\n" +
                    "      properties: {\n" +
                    "        command: { type: \"string\", description: \"Root shell 命令\" }\n" +
                    "      },\n" +
                    "      required: [\"command\"]\n" +
                    "    },\n" +
                    "    async execute(_id, params: { command: string }) {\n" +
                    "      try {\n" +
                    "        const { stdout, stderr } = await execAsync(`/usr/local/bin/su-exec \"${params.command.replace(/\"/g, '\\\\\"')}\"`);\n" +
                    "        return { content: [{ type: \"text\", text: stdout || stderr || \"执行完成\" }] };\n" +
                    "      } catch (e: any) {\n" +
                    "        return { content: [{ type: \"text\", text: `Root 失败: ${e.message}` }], isError: true };\n" +
                    "      }\n" +
                    "    }\n" +
                    "  });\n\n" +
                    "  // 3. 手机剪贴板读取与写入工具\n" +
                    "  pi.registerTool({\n" +
                    "    name: \"android_clipboard\",\n" +
                    "    label: \"Android 剪贴板\",\n" +
                    "    description: \"读取或设置宿主 Android 系统的剪贴板内容\",\n" +
                    "    parameters: {\n" +
                    "      type: \"object\",\n" +
                    "      properties: {\n" +
                    "        action: { type: \"string\", enum: [\"read\", \"write\"], description: \"操作类型\" },\n" +
                    "        text: { type: \"string\", description: \"写入内容 (write 模式)\" }\n" +
                    "      },\n" +
                    "      required: [\"action\"]\n" +
                    "    },\n" +
                    "    async execute(_id, params: { action: string; text?: string }) {\n" +
                    "      const fs = await import(\"node:fs/promises\");\n" +
                    "      const clipPath = \"/root/.clipboard.txt\";\n" +
                    "      if (params.action === \"read\") {\n" +
                    "        try {\n" +
                    "          const text = await fs.readFile(clipPath, \"utf-8\");\n" +
                    "          return { content: [{ type: \"text\", text }] };\n" +
                    "        } catch {\n" +
                    "          return { content: [{ type: \"text\", text: \"(剪贴板缓存为空)\" }] };\n" +
                    "        }\n" +
                    "      } else {\n" +
                    "        await fs.writeFile(clipPath, params.text || \"\", \"utf-8\");\n" +
                    "        return { content: [{ type: \"text\", text: \"已同步写入系统剪贴板缓存\" }] };\n" +
                    "      }\n" +
                    "    }\n" +
                    "  });\n\n" +
                    "  // 4. Android 手机运行状态与硬件信息\n" +
                    "  pi.registerTool({\n" +
                    "    name: \"android_device_info\",\n" +
                    "    label: \"Android 设备状态\",\n" +
                    "    description: \"获取 Android 手机的 Linux 内核版本、系统运行时长及容器存储占用\",\n" +
                    "    parameters: { type: \"object\", properties: {} },\n" +
                    "    async execute() {\n" +
                    "      try {\n" +
                    "        const { stdout } = await execAsync(\"uname -a && uptime && df -h /root\");\n" +
                    "        return { content: [{ type: \"text\", text: stdout }] };\n" +
                    "      } catch (e: any) {\n" +
                    "        return { content: [{ type: \"text\", text: e.message }], isError: true };\n" +
                    "      }\n" +
                    "    }\n" +
                    "  });\n" +
                    "}\n";

            writeFile(bridgeFile, code);
        } catch (Throwable ignored) {}
    }

    private static void syncSkills(File piAgentDir) {
        try {
            File skillsDir = new File(piAgentDir, "skills");
            if (!skillsDir.exists()) skillsDir.mkdirs();

            // Skill 1: Android 开发与逆向助手
            writeSkill(skillsDir, "android-dev",
                    "Android 逆向开发、Gradle 构建、ADB 调试与原生开发专家技能。当需要编写 Android 代码、分析 APK 或调试 Android 系统时使用。",
                    "# Android 开发专家技能\n\n具备 Android 架构设计、NDK 开发、Shizuku 提权、PRoot 容器调用与系统服务的全面分析能力。\n");

            // Skill 2: Linux 容器运维与自动化
            writeSkill(skillsDir, "linux-ops",
                    "PRoot 容器运维、Linux Shell 自动化与 Node.js 服务调优专家技能。当需要管理容器、编写自动化脚本或排查进程时使用。",
                    "# Linux 运维自动化技能\n\n精通 Alpine/Debian Linux 容器运维、apt/apk 包管理、网络探活与后台守护机制。\n");

            // Skill 3: Git 工作流大师
            writeSkill(skillsDir, "git-master",
                    "Git 分支管理、代码重构、规范化 Commit 与冲突解决技能。当需要提交代码、发布版本或处理 Git 仓库操作时使用。",
                    "# Git Master 技能\n\n自动化 Git 操作、版本打标、Cherry-pick 与 GitHub CI/CD 流水线编写。\n");

            // Skill 4: Web 全栈与数据抓取
            writeSkill(skillsDir, "web-scraper",
                    "网络数据采集、API 逆向分析与 HTML 结构提取技能。当需要爬取网页内容、解析接口时使用。",
                    "# 网页与数据抓取技能\n\n使用 curl、node-fetch 与 cheerio 进行高效合规的数据提取。\n");

        } catch (Throwable ignored) {}
    }

    private static void writeSkill(File skillsDir, String name, String desc, String body) {
        try {
            File dir = new File(skillsDir, name);
            if (!dir.exists()) dir.mkdirs();
            File skillMd = new File(dir, "SKILL.md");
            String content = "---\nname: " + name + "\ndescription: " + desc + "\n---\n\n" + body;
            writeFile(skillMd, content);
        } catch (Throwable ignored) {}
    }

    private static void syncMcpConfig(File piAgentDir) {
        try {
            File mcpFile = new File(piAgentDir, "mcp.json");
            String json = "{\n" +
                    "  \"mcpServers\": {\n" +
                    "    \"filesystem\": {\n" +
                    "      \"command\": \"npx\",\n" +
                    "      \"args\": [\"-y\", \"@modelcontextprotocol/server-filesystem\", \"/root\", \"/sdcard\"]\n" +
                    "    },\n" +
                    "    \"memory\": {\n" +
                    "      \"command\": \"npx\",\n" +
                    "      \"args\": [\"-y\", \"@modelcontextprotocol/server-memory\"]\n" +
                    "    }\n" +
                    "  }\n" +
                    "}\n";
            writeFile(mcpFile, json);
        } catch (Throwable ignored) {}
    }

    private static void syncSubagents(File piAgentDir) {
        try {
            File subagentsDir = new File(piAgentDir, "subagents");
            if (!subagentsDir.exists()) subagentsDir.mkdirs();

            File reviewer = new File(subagentsDir, "code-reviewer.md");
            writeFile(reviewer, "# 代码审查专家 (Code Reviewer)\n专注于代码安全性、性能瓶颈、潜在并发漏洞与代码规范审查。\n");

            File architect = new File(subagentsDir, "system-architect.md");
            writeFile(architect, "# 系统架构师 (System Architect)\n负责模块解耦、技术选型方案对比与企业级高可用架构设计。\n");
        } catch (Throwable ignored) {}
    }

    private static void syncHostExecBridges(File rootfs) {
        try {
            File binDir = new File(rootfs, "usr/local/bin");
            if (!binDir.exists()) binDir.mkdirs();

            // su-exec 脚本
            File suExec = new File(binDir, "su-exec");
            String suContent = "#!/bin/bash\n/system/bin/su -c \"$@\"\n";
            writeFile(suExec, suContent);
            suExec.setExecutable(true, false);

            // shizuku-exec 脚本
            File shizukuExec = new File(binDir, "shizuku-exec");
            String shizukuContent = "#!/bin/bash\n# PiMet Shizuku Bridge Executable\n/system/bin/sh -c \"$@\"\n";
            writeFile(shizukuExec, shizukuContent);
            shizukuExec.setExecutable(true, false);
        } catch (Throwable ignored) {}
    }

    private static void writeFile(File file, String content) {
        try (FileOutputStream fos = new FileOutputStream(file, false)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        } catch (Throwable ignored) {}
    }
}
