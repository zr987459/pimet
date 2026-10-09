package com.xm486.pimet;

import android.content.Context;
import android.util.Log;

import com.xm486.pimet.proot.ProotManager;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Pi-Web 插件、技能与 MCP 服务管理类
 * 负责与 PRoot 容器文件系统内（/root/.pi/agent/）真实插件生态双向同步，杜绝预设污染与配置打架。
 */
public final class PluginManager {
    private static final String TAG = "PluginManager";

    public static class PluginItem {
        public static final int TYPE_EXTENSION = 0;
        public static final int TYPE_SKILL = 1;
        public static final int TYPE_MCP = 2;
        public static final int TYPE_SUBAGENT = 3;

        public final int type;
        public final String name;
        public final String path;
        public final String description;

        public PluginItem(int type, String name, String path, String description) {
            this.type = type;
            this.name = name;
            this.path = path;
            this.description = description;
        }

        public String getTypeName() {
            switch (type) {
                case TYPE_EXTENSION: return "扩展 (Extension)";
                case TYPE_SKILL: return "技能 (Skill)";
                case TYPE_MCP: return "MCP 服务";
                case TYPE_SUBAGENT: return "子代理 / 提示词";
                default: return "插件";
            }
        }
    }

    /**
     * 清理所有历史自动注入的自定义预设插件，确保容器内干净、不打架
     */
    public static void cleanSelfAddedPlugins(Context context) {
        new Thread(() -> {
            try {
                File rootfs = ProotManager.getRootfsDir(context);
                if (!rootfs.exists()) return;

                File piAgentDir = new File(rootfs, "root/.pi/agent");
                if (piAgentDir.exists()) {
                    // 1. 删除自动注入的 android-bridge.ts
                    File bridgeExt = new File(piAgentDir, "extensions/android-bridge.ts");
                    if (bridgeExt.exists()) bridgeExt.delete();

                    // 2. 删除自动注入的预设技能
                    String[] presetSkills = {"android-dev", "linux-ops", "git-master", "web-scraper", "system-admin.md", "package-deploy.md", "device-control.md"};
                    for (String s : presetSkills) {
                        File sDir = new File(piAgentDir, "skills/" + s);
                        if (sDir.exists()) deleteRecursively(sDir);
                    }

                    // 3. 删除自动注入的预设子代理
                    String[] presetSubagents = {"code-reviewer.md", "system-architect.md"};
                    for (String sa : presetSubagents) {
                        File saFile = new File(piAgentDir, "subagents/" + sa);
                        if (saFile.exists()) saFile.delete();
                    }

                    // 4. 清理默认注入的 dummy mcp.json (如果只包含 filesystem/memory 模板且无其它用户服务)
                    File mcpFile = new File(piAgentDir, "mcp.json");
                    if (mcpFile.exists()) {
                        String mcpContent = readFile(mcpFile);
                        if (mcpContent != null && mcpContent.contains("@modelcontextprotocol/server-filesystem") && !mcpContent.contains("user-custom")) {
                            mcpFile.delete();
                        }
                    }
                }

                // 5. 清理 /usr/local/bin 注入的桥接脚本
                File pimetDev = new File(rootfs, "usr/local/bin/pimet-device");
                if (pimetDev.exists()) pimetDev.delete();
                File pimetShizuku = new File(rootfs, "usr/local/bin/pimet-shizuku");
                if (pimetShizuku.exists()) pimetShizuku.delete();

                Log.i(TAG, "Self-added preset plugins cleaned successfully.");
            } catch (Throwable t) {
                Log.w(TAG, "Failed to clean self-added plugins", t);
            }
        }).start();
    }

    /**
     * 动态从容器扫描真实已安装的插件与技能
     */
    public static List<PluginItem> getInstalledPlugins(Context context) {
        List<PluginItem> list = new ArrayList<>();
        try {
            File rootfs = ProotManager.getRootfsDir(context);
            if (!rootfs.exists()) return list;

            File piAgentDir = new File(rootfs, "root/.pi/agent");
            if (!piAgentDir.exists()) return list;

            // 1. 扫描 Extensions (~/.pi/agent/extensions)
            File extDir = new File(piAgentDir, "extensions");
            if (extDir.exists() && extDir.isDirectory()) {
                File[] files = extDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.getName().startsWith(".")) continue;
                        list.add(new PluginItem(PluginItem.TYPE_EXTENSION, f.getName(), f.getAbsolutePath(), "本地扩展: " + f.getName()));
                    }
                }
            }

            // 2. 扫描 Skills (~/.pi/agent/skills)
            File skillsDir = new File(piAgentDir, "skills");
            if (skillsDir.exists() && skillsDir.isDirectory()) {
                File[] files = skillsDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.getName().startsWith(".")) continue;
                        list.add(new PluginItem(PluginItem.TYPE_SKILL, f.getName(), f.getAbsolutePath(), "专家技能定义: " + f.getName()));
                    }
                }
            }

            // 3. 扫描 MCP 服务 (~/.pi/agent/mcp.json)
            File mcpFile = new File(piAgentDir, "mcp.json");
            if (mcpFile.exists() && mcpFile.isFile()) {
                try {
                    String jsonStr = readFile(mcpFile);
                    if (jsonStr != null) {
                        JSONObject root = new JSONObject(jsonStr);
                        JSONObject servers = root.optJSONObject("mcpServers");
                        if (servers != null) {
                            Iterator<String> keys = servers.keys();
                            while (keys.hasNext()) {
                                String sName = keys.next();
                                list.add(new PluginItem(PluginItem.TYPE_MCP, sName, mcpFile.getAbsolutePath(), "MCP 外部协议服务"));
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }

            // 4. 扫描 Subagents (~/.pi/agent/subagents)
            File subagentsDir = new File(piAgentDir, "subagents");
            if (subagentsDir.exists() && subagentsDir.isDirectory()) {
                File[] files = subagentsDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.getName().startsWith(".")) continue;
                        list.add(new PluginItem(PluginItem.TYPE_SUBAGENT, f.getName(), f.getAbsolutePath(), "子代理模板: " + f.getName()));
                    }
                }
            }

            // 5. 扫描 Prompts (~/.pi/agent/prompts)
            File promptsDir = new File(piAgentDir, "prompts");
            if (promptsDir.exists() && promptsDir.isDirectory()) {
                File[] files = promptsDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.getName().startsWith(".")) continue;
                        list.add(new PluginItem(PluginItem.TYPE_SUBAGENT, f.getName(), f.getAbsolutePath(), "提示词模板: " + f.getName()));
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to get installed plugins", t);
        }
        return list;
    }

    /**
     * 删除指定插件或技能
     */
    public static boolean deletePlugin(Context context, PluginItem item) {
        try {
            if (item.type == PluginItem.TYPE_MCP) {
                File rootfs = ProotManager.getRootfsDir(context);
                File mcpFile = new File(rootfs, "root/.pi/agent/mcp.json");
                if (mcpFile.exists()) {
                    String jsonStr = readFile(mcpFile);
                    if (jsonStr != null) {
                        JSONObject root = new JSONObject(jsonStr);
                        JSONObject servers = root.optJSONObject("mcpServers");
                        if (servers != null && servers.has(item.name)) {
                            servers.remove(item.name);
                            writeFile(mcpFile, root.toString(2));
                            return true;
                        }
                    }
                }
                return false;
            } else {
                File target = new File(item.path);
                if (target.exists()) {
                    return deleteRecursively(target);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to delete plugin: " + item.name, t);
        }
        return false;
    }

    private static boolean deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        return file.delete();
    }

    /**
     * 自动注入宿主 Android 界面与桌宠控制插件到 PRoot 容器环境
     * 赋予 AI Agent 原生修改 Android 外部桌宠、界面、参数与端口的能力
     */
    public static void ensureAndroidBridgeExtension(Context context) {
        try {
            File rootfs = ProotManager.getRootfsDir(context);
            if (!rootfs.exists()) return;

            File extDir = new File(rootfs, "root/.pi/agent/extensions");
            if (!extDir.exists()) {
                extDir.mkdirs();
            }

            File bridgeFile = new File(extDir, "pimet-android-bridge.ts");
            String code = "/**\n" +
                    " * PiMet Host Android & Desktop Pet Bridge Extension\n" +
                    " * 允许 AI 动态控制和修改 Android 宿主界面的桌宠形象、尺寸、动作、气泡、物理参数与标签页\n" +
                    " */\n" +
                    "import type { ExtensionAPI } from \"@earendil-works/pi-coding-agent\";\n" +
                    "import { Type } from \"typebox\";\n" +
                    "import * as http from \"http\";\n" +
                    "import * as fs from \"fs\";\n" +
                    "import * as path from \"path\";\n" +
                    "import * as os from \"os\";\n\n" +
                    "function sendBridgeAction(actionData: Record<string, any>): Promise<string> {\n" +
                    "  return new Promise((resolve) => {\n" +
                    "    try {\n" +
                    "      const bridgeDir = path.join(os.homedir(), \".pi\", \"agent\");\n" +
                    "      if (!fs.existsSync(bridgeDir)) fs.mkdirSync(bridgeDir, { recursive: true });\n" +
                    "      fs.writeFileSync(path.join(bridgeDir, \"app_control.json\"), JSON.stringify(actionData), \"utf-8\");\n" +
                    "    } catch (e) {}\n\n" +
                    "    const postData = JSON.stringify(actionData);\n" +
                    "    const req = http.request(\n" +
                    "      {\n" +
                    "        hostname: \"127.0.0.1\",\n" +
                    "        port: 30143,\n" +
                    "        path: \"/api/app-control\",\n" +
                    "        method: \"POST\",\n" +
                    "        headers: {\n" +
                    "          \"Content-Type\": \"application/json\",\n" +
                    "          \"Content-Length\": Buffer.byteLength(postData),\n" +
                    "        },\n" +
                    "        timeout: 1500,\n" +
                    "      },\n" +
                    "      (res) => {\n" +
                    "        let data = \"\";\n" +
                    "        res.on(\"data\", (chunk) => (data += chunk));\n" +
                    "        res.on(\"end\", () => resolve(`Android 宿主执行成功: ${data}`));\n" +
                    "      }\n" +
                    "    );\n" +
                    "    req.on(\"error\", (err) => {\n" +
                    "      resolve(`指令已写入 IPC 文件缓存 (HTTP: ${err.message})`);\n" +
                    "    });\n" +
                    "    req.write(postData);\n" +
                    "    req.end();\n" +
                    "  });\n" +
                    "}\n\n" +
                    "export default function (pi: ExtensionAPI) {\n" +
                    "  pi.registerTool({\n" +
                    "    name: \"control_desktop_pet\",\n" +
                    "    label: \"Control Desktop Pet\",\n" +
                    "    description: \"控制 Android 宿主界面的动态桌宠：修改角色/皮肤、尺寸大小(dp)、动作(跳跃/跳舞/挥手)、对话气泡、反弹摩擦力或开关全局桌宠\",\n" +
                    "    parameters: Type.Object({\n" +
                    "      character: Type.Optional(Type.String({ description: \"角色目录名，如 cat_maid(猫娘), eva(小初), gup(少女), paimon(派蒙), custom\" })),\n" +
                    "      size: Type.Optional(Type.Number({ description: \"桌宠大小 (dp，范围 32~160)\" })),\n" +
                    "      bubble_text: Type.Optional(Type.String({ description: \"让桌宠说出的气泡文字\" })),\n" +
                    "      action: Type.Optional(Type.String({ description: \"桌宠动作: jumping (跳跃/跟斗), dancing (跳舞), waving (招手), idle (静止)\" })),\n" +
                    "      bounce: Type.Optional(Type.Number({ description: \"物理反弹系数 0~100\" })),\n" +
                    "      friction: Type.Optional(Type.Number({ description: \"空气摩擦力 0~100\" })),\n" +
                    "      global_pet_enabled: Type.Optional(Type.Boolean({ description: \"是否开启全局系统悬浮桌宠\" })),\n" +
                    "    }),\n" +
                    "    async execute(_toolCallId, params) {\n" +
                    "      const result = await sendBridgeAction({ action: \"control_desktop_pet\", ...params });\n" +
                    "      return {\n" +
                    "        content: [{ type: \"text\", text: `🐾 桌宠控制指令已下发！${result}` }],\n" +
                    "        details: params,\n" +
                    "      };\n" +
                    "    },\n" +
                    "  });\n\n" +
                    "  pi.registerTool({\n" +
                    "    name: \"control_app_ui\",\n" +
                    "    label: \"Control App UI\",\n" +
                    "    description: \"控制 Android 宿主应用的界面与端口：切换选项卡(工作台/终端/设置/操控台)、配置各服务端口\",\n" +
                    "    parameters: Type.Object({\n" +
                    "      switch_tab: Type.Optional(Type.String({ description: \"切换界面: launch(主页), web(Pi-Web工作台), terminal(终端), settings(设置)\" })),\n" +
                    "      ports: Type.Optional(\n" +
                    "        Type.Object({\n" +
                    "          piweb: Type.Optional(Type.Number()),\n" +
                    "          operit: Type.Optional(Type.Number()),\n" +
                    "          clawbench: Type.Optional(Type.Number()),\n" +
                    "          rikka: Type.Optional(Type.Number()),\n" +
                    "        })\n" +
                    "      ),\n" +
                    "    }),\n" +
                    "    async execute(_toolCallId, params) {\n" +
                    "      const result = await sendBridgeAction({ action: \"control_app_ui\", ...params });\n" +
                    "      return {\n" +
                    "        content: [{ type: \"text\", text: `📱 Android 宿主界面指令已下发！${result}` }],\n" +
                    "        details: params,\n" +
                    "      };\n" +
                    "    },\n" +
                    "  });\n" +
                    "}\n";

            writeFile(bridgeFile, code);
            Log.i("PiMet.PluginMgr", "pimet-android-bridge.ts ensured in container");
        } catch (Throwable t) {
            Log.w("PiMet.PluginMgr", "Failed to ensure Android bridge extension", t);
        }
    }

    private static String readFile(File file) {
        try (FileInputStream fis = new FileInputStream(file);
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[2048];
            int n;
            while ((n = fis.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeFile(File file, String content) {
        try (FileOutputStream fos = new FileOutputStream(file, false)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        } catch (Throwable ignored) {}
    }
}
