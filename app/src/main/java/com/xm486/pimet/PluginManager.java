package com.xm486.pimet;

import android.content.Context;
import android.util.Log;

import com.xm486.pimet.proot.ProotManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Pi-Web 插件、技能与 MCP 服务管理类
 * 深度扫描并管理 PRoot 容器环境内完整生态（NPM 包、本地扩展、Agent 技能树、子代理、MCP 服务等）。
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
        public final String rawPkgName;

        public PluginItem(int type, String name, String path, String description) {
            this(type, name, path, description, name);
        }

        public PluginItem(int type, String name, String path, String description, String rawPkgName) {
            this.type = type;
            this.name = name;
            this.path = path;
            this.description = description;
            this.rawPkgName = rawPkgName;
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
     * 清理保护：不再误删用户的真实技能与插件
     */
    public static void cleanSelfAddedPlugins(Context context) {
        // 保留接口兼容，避免误删用户技能文件
    }

    /**
     * 深度扫描容器中真实已安装的各类插件、扩展包与技能
     */
    public static List<PluginItem> getInstalledPlugins(Context context) {
        List<PluginItem> list = new ArrayList<>();
        Set<String> addedPaths = new HashSet<>();
        try {
            File rootfs = ProotManager.getRootfsDir(context);
            if (!rootfs.exists()) return list;

            File piAgentDir = new File(rootfs, "root/.pi/agent");
            if (!piAgentDir.exists()) return list;

            // 1. 扫描 NPM 依赖包生态 (~/.pi/agent/npm/package.json & node_modules)
            File npmDir = new File(piAgentDir, "npm");
            File npmPkgJson = new File(npmDir, "package.json");
            File npmNodeModules = new File(npmDir, "node_modules");

            if (npmPkgJson.exists()) {
                try {
                    String pkgContent = readFile(npmPkgJson);
                    if (pkgContent != null) {
                        JSONObject rootPkg = new JSONObject(pkgContent);
                        JSONObject deps = rootPkg.optJSONObject("dependencies");
                        if (deps != null) {
                            Iterator<String> depKeys = deps.keys();
                            while (depKeys.hasNext()) {
                                String pkgName = depKeys.next();
                                File targetModDir = new File(npmNodeModules, pkgName);
                                String version = "";
                                String desc = "NPM 官方扩展包: " + pkgName;

                                if (targetModDir.exists()) {
                                    File subPkgJson = new File(targetModDir, "package.json");
                                    if (subPkgJson.exists()) {
                                        String subContent = readFile(subPkgJson);
                                        if (subContent != null) {
                                            JSONObject subObj = new JSONObject(subContent);
                                            version = subObj.optString("version", "");
                                            String d = subObj.optString("description", "");
                                            if (!d.isEmpty()) desc = d;
                                        }
                                    }
                                }

                                String displayName = pkgName + (version.isEmpty() ? "" : " (v" + version + ")");
                                String modPath = targetModDir.exists() ? targetModDir.getAbsolutePath() : npmPkgJson.getAbsolutePath();
                                if (addedPaths.add(modPath)) {
                                    list.add(new PluginItem(PluginItem.TYPE_EXTENSION, displayName, modPath, desc, pkgName));
                                }

                                // 扫描该 NPM 包内置的技能 (skills/*.md 或 skills/*)
                                if (targetModDir.exists()) {
                                    File pkgSkillsDir = new File(targetModDir, "skills");
                                    if (pkgSkillsDir.exists() && pkgSkillsDir.isDirectory()) {
                                        scanSkillsRecursive(pkgSkillsDir, list, addedPaths, pkgName);
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }

            // 2. 扫描 settings.json 中声明的 packages
            File settingsFile = new File(piAgentDir, "settings.json");
            if (settingsFile.exists()) {
                try {
                    String sContent = readFile(settingsFile);
                    if (sContent != null) {
                        JSONObject sObj = new JSONObject(sContent);
                        JSONArray pkgsArr = sObj.optJSONArray("packages");
                        if (pkgsArr != null) {
                            for (int i = 0; i < pkgsArr.length(); i++) {
                                String pName = pkgsArr.optString(i, "");
                                String cleanPkg = pName.startsWith("npm:") ? pName.substring(4) : pName;
                                if (!cleanPkg.isEmpty()) {
                                    File modDir = new File(npmNodeModules, cleanPkg);
                                    if (!addedPaths.contains(modDir.getAbsolutePath())) {
                                        addedPaths.add(modDir.getAbsolutePath());
                                        list.add(new PluginItem(PluginItem.TYPE_EXTENSION, cleanPkg, modDir.getAbsolutePath(), "配置项声明包: " + pName, cleanPkg));
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }

            // 3. 扫描本地手动编写的 Extensions (~/.pi/agent/extensions)
            File extDir = new File(piAgentDir, "extensions");
            if (extDir.exists() && extDir.isDirectory()) {
                File[] files = extDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.getName().startsWith(".")) continue;
                        if (addedPaths.add(f.getAbsolutePath())) {
                            list.add(new PluginItem(PluginItem.TYPE_EXTENSION, f.getName(), f.getAbsolutePath(), "本地脚本扩展: " + f.getName(), f.getName()));
                        }
                    }
                }
            }

            // 4. 扫描独立技能目录 (~/.pi/agent/skills)
            File skillsDir = new File(piAgentDir, "skills");
            if (skillsDir.exists() && skillsDir.isDirectory()) {
                scanSkillsRecursive(skillsDir, list, addedPaths, null);
            }

            // 5. 扫描 Subagents (~/.pi/agent/subagents)
            File subagentsDir = new File(piAgentDir, "subagents");
            if (subagentsDir.exists() && subagentsDir.isDirectory()) {
                File[] files = subagentsDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.getName().startsWith(".")) continue;
                        if (addedPaths.add(f.getAbsolutePath())) {
                            list.add(new PluginItem(PluginItem.TYPE_SUBAGENT, f.getName(), f.getAbsolutePath(), "子代理模板: " + f.getName()));
                        }
                    }
                }
            }

            // 6. 扫描 Prompts (~/.pi/agent/prompts)
            File promptsDir = new File(piAgentDir, "prompts");
            if (promptsDir.exists() && promptsDir.isDirectory()) {
                File[] files = promptsDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.getName().startsWith(".")) continue;
                        if (addedPaths.add(f.getAbsolutePath())) {
                            list.add(new PluginItem(PluginItem.TYPE_SUBAGENT, f.getName(), f.getAbsolutePath(), "提示词模板: " + f.getName()));
                        }
                    }
                }
            }

            // 7. 扫描 MCP 服务 (~/.pi/agent/mcp.json)
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

            // 8. 扫描全局 node_modules (/usr/local/lib/node_modules)
            File globalNm = new File(rootfs, "usr/local/lib/node_modules");
            if (globalNm.exists() && globalNm.isDirectory()) {
                File[] gFiles = globalNm.listFiles();
                if (gFiles != null) {
                    for (File gf : gFiles) {
                        String gName = gf.getName();
                        if (gName.startsWith(".") || gName.equals("npm")) continue;
                        if (addedPaths.add(gf.getAbsolutePath())) {
                            list.add(new PluginItem(PluginItem.TYPE_EXTENSION, gName + " (全局)", gf.getAbsolutePath(), "全局 NPM 工具包: " + gName, gName));
                        }
                    }
                }
            }

        } catch (Throwable t) {
            Log.e(TAG, "Failed to get installed plugins", t);
        }
        return list;
    }

    private static void scanSkillsRecursive(File dir, List<PluginItem> list, Set<String> addedPaths, String parentPkg) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.getName().startsWith(".")) continue;
            if (f.isFile() && f.getName().endsWith(".md")) {
                if (addedPaths.add(f.getAbsolutePath())) {
                    String title = f.getName().replace(".md", "");
                    String desc = parentPkg != null ? ("来自 " + parentPkg + " 内置技能") : ("本地专家技能: " + f.getName());
                    list.add(new PluginItem(PluginItem.TYPE_SKILL, title, f.getAbsolutePath(), desc, parentPkg != null ? parentPkg : title));
                }
            } else if (f.isDirectory()) {
                File skillDoc = new File(f, "SKILL.md");
                if (skillDoc.exists()) {
                    if (addedPaths.add(f.getAbsolutePath())) {
                        String desc = parentPkg != null ? ("来自 " + parentPkg + " 技能包") : ("独立技能包: " + f.getName());
                        list.add(new PluginItem(PluginItem.TYPE_SKILL, f.getName(), f.getAbsolutePath(), desc, parentPkg != null ? parentPkg : f.getName()));
                    }
                } else {
                    scanSkillsRecursive(f, list, addedPaths, parentPkg);
                }
            }
        }
    }

    /**
     * 检查并更新指定插件
     */
    public static boolean updatePlugin(Context context, PluginItem item) {
        try {
            String pkg = item.rawPkgName != null && !item.rawPkgName.isEmpty() ? item.rawPkgName : item.name;
            if (pkg.contains(" (v")) {
                pkg = pkg.substring(0, pkg.indexOf(" (v")).trim();
            }
            if (pkg.contains(" (全局)")) {
                pkg = pkg.replace(" (全局)", "").trim();
            }

            if (item.type == PluginItem.TYPE_EXTENSION) {
                String cmd1 = "cd /root/.pi/agent/npm && npm install " + pkg + "@latest --registry=https://registry.npmmirror.com";
                int code1 = ProotManager.executeCommandSync(context, cmd1);
                if (code1 == 0) return true;

                String cmd2 = "npm install -g " + pkg + "@latest --registry=https://registry.npmmirror.com";
                int code2 = ProotManager.executeCommandSync(context, cmd2);
                return code2 == 0;
            } else if (item.type == PluginItem.TYPE_SKILL) {
                if (item.rawPkgName != null && !item.rawPkgName.equals(item.name)) {
                    String cmd = "cd /root/.pi/agent/npm && npm install " + item.rawPkgName + "@latest --registry=https://registry.npmmirror.com";
                    return ProotManager.executeCommandSync(context, cmd) == 0;
                }
                File gitDir = new File(item.path, ".git");
                if (gitDir.exists()) {
                    String cmd = "cd " + item.path + " && git pull";
                    return ProotManager.executeCommandSync(context, cmd) == 0;
                }
            }

            String fallbackCmd = "cd /root/.pi/agent/npm && npm update " + pkg + " --registry=https://registry.npmmirror.com";
            return ProotManager.executeCommandSync(context, fallbackCmd) == 0;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to update plugin: " + item.name, t);
        }
        return false;
    }

    /**
     * 一键更新容器内所有插件与依赖包
     */
    public static boolean updateAllPlugins(Context context) {
        try {
            String cmd1 = "cd /root/.pi/agent/npm && npm update --registry=https://registry.npmmirror.com";
            int code1 = ProotManager.executeCommandSync(context, cmd1);

            String cmd2 = "npm update -g --registry=https://registry.npmmirror.com";
            int code2 = ProotManager.executeCommandSync(context, cmd2);

            return code1 == 0 || code2 == 0;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to update all plugins", t);
        }
        return false;
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
                String pkg = item.rawPkgName != null && !item.rawPkgName.isEmpty() ? item.rawPkgName : item.name;
                if (pkg.contains(" (v")) {
                    pkg = pkg.substring(0, pkg.indexOf(" (v")).trim();
                }
                if (item.type == PluginItem.TYPE_EXTENSION && !pkg.endsWith(".ts") && !pkg.endsWith(".js")) {
                    ProotManager.executeCommandSync(context, "cd /root/.pi/agent/npm && npm uninstall " + pkg);
                }
                File target = new File(item.path);
                if (target.exists()) {
                    return deleteRecursively(target);
                }
                return true;
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
                    "      ports: Type.Optional(Type.Object({\n" +
                    "        piweb: Type.Optional(Type.Number()),\n" +
                    "        operit: Type.Optional(Type.Number()),\n" +
                    "        clawbench: Type.Optional(Type.Number()),\n" +
                    "        rikka: Type.Optional(Type.Number()),\n" +
                    "      })),\n" +
                    "    }),\n" +
                    "    async execute(_toolCallId, params) {\n" +
                    "      const result = await sendBridgeAction({ action: \"control_app_ui\", ...params });\n" +
                    "      return {\n" +
                    "        content: [{ type: \"text\", text: `📱 宿主界面与端口控制指令已执行！${result}` }],\n" +
                    "        details: params,\n" +
                    "      };\n" +
                    "    },\n" +
                    "  });\n" +
                    "}\n";

            writeFile(bridgeFile, code);
            Log.i(TAG, "PiMet Android Bridge extension ensured at: " + bridgeFile.getAbsolutePath());
        } catch (Throwable t) {
            Log.e(TAG, "Failed to ensure Android Bridge extension", t);
        }
    }

    private static String readFile(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            return new String(data, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeFile(File file, String content) {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {}
    }
}
