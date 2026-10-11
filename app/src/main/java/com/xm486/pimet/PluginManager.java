package com.xm486.pimet;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.xm486.pimet.proot.ProotManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * 容器内部真实插件、技能与生态管理器
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

        public String version = "";
        public String latestVersion = "";
        public boolean hasUpdate = false;
        public String chineseDesc = "";
        public boolean enabled = true;

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

    public interface UpdateCheckCallback {
        void onChecked(int updateCount);
    }

    /**
     * 清理保护：不误删用户的真实技能与插件
     */
    public static void cleanSelfAddedPlugins(Context context) {
        // 保留方法签名，不再删除用户文件
    }

    /**
     * 深度扫描容器中真实已安装的各类插件、扩展包与技能
     */
    public static List<PluginItem> getInstalledPlugins(Context context) {
        List<PluginItem> list = new ArrayList<>();
        Set<String> addedPaths = new HashSet<>();

        // 优先触发双向同步：外部文件的安装/卸载与内部配置互相同步
        syncPlugins(context);

        try {
            File rootfs = ProotManager.getRootfsDir(context);
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

                                String modPath = targetModDir.exists() ? targetModDir.getAbsolutePath() : npmPkgJson.getAbsolutePath();
                                if (addedPaths.add(modPath)) {
                                    PluginItem item = new PluginItem(PluginItem.TYPE_EXTENSION, pkgName, modPath, desc, pkgName);
                                    item.version = version;
                                    list.add(item);
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
                                        PluginItem item = new PluginItem(PluginItem.TYPE_EXTENSION, cleanPkg, modDir.getAbsolutePath(), "配置项声明包: " + pName, cleanPkg);
                                        File subPkgJson = new File(modDir, "package.json");
                                        if (subPkgJson.exists()) {
                                            try {
                                                String c = readFile(subPkgJson);
                                                if (c != null) item.version = new JSONObject(c).optString("version", "");
                                            } catch (Throwable ignored) {}
                                        }
                                        list.add(item);
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

            // 5. 扫描 Subagents 标准目录 (~/.pi/agent/agents) 与兼容目录 (~/.pi/agent/subagents)
            File agentsDir = new File(piAgentDir, "agents");
            if (agentsDir.exists() && agentsDir.isDirectory()) {
                File[] files = agentsDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.getName().startsWith(".") || !f.getName().endsWith(".md")) continue;
                        if (addedPaths.add(f.getAbsolutePath())) {
                            list.add(new PluginItem(PluginItem.TYPE_SUBAGENT, f.getName(), f.getAbsolutePath(), "Pi 标准子代理: " + f.getName()));
                        }
                    }
                }
            }

            File subagentsDir = new File(piAgentDir, "subagents");
            if (subagentsDir.exists() && subagentsDir.isDirectory()) {
                File[] files = subagentsDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.getName().startsWith(".") || !f.getName().endsWith(".md")) continue;
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
                            PluginItem item = new PluginItem(PluginItem.TYPE_EXTENSION, gName, gf.getAbsolutePath(), "全局 NPM 工具包: " + gName, gName);
                            File gPkg = new File(gf, "package.json");
                            if (gPkg.exists()) {
                                try {
                                    String c = readFile(gPkg);
                                    if (c != null) item.version = new JSONObject(c).optString("version", "");
                                } catch (Throwable ignored) {}
                            }
                            list.add(item);
                        }
                    }
                }
            }

        } catch (Throwable t) {
            Log.e(TAG, "Failed to get installed plugins", t);
        }

        // 自动注入中文注释与描述
        for (PluginItem item : list) {
            item.enabled = isPluginEnabled(context, item);
            if (item.chineseDesc == null || item.chineseDesc.isEmpty()) {
                item.chineseDesc = getChineseAnnotation(item.name, item.description, item.type);
            }
        }

        return list;
    }

    /**
     * 判断插件当前是否处于启用状态
     */
    public static boolean isPluginEnabled(Context context, PluginItem item) {
        if (item == null) return false;
        if (item.path != null && (item.path.endsWith(".disabled") || item.path.endsWith(".off"))) {
            return false;
        }
        if (item.type == PluginItem.TYPE_MCP) {
            try {
                File rootfs = ProotManager.getRootfsDir(context);
                File mcpFile = new File(rootfs, "root/.pi/agent/mcp.json");
                if (mcpFile.exists()) {
                    String jsonStr = readFile(mcpFile);
                    if (jsonStr != null) {
                        JSONObject root = new JSONObject(jsonStr);
                        JSONObject servers = root.optJSONObject("mcpServers");
                        if (servers != null && servers.has(item.name)) {
                            JSONObject sObj = servers.optJSONObject(item.name);
                            if (sObj != null && sObj.optBoolean("disabled", false)) {
                                return false;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        try {
            SharedPreferences sp = context.getSharedPreferences("pimet_plugins_state", Context.MODE_PRIVATE);
            Set<String> disabledSet = sp.getStringSet("disabled_plugins", Collections.emptySet());
            if (disabledSet != null && (disabledSet.contains(item.name) || (item.rawPkgName != null && disabledSet.contains(item.rawPkgName)))) {
                return false;
            }
        } catch (Throwable ignored) {}
        return true;
    }

    /**
     * 启停插件（无损热切换，无需删除用户数据与文件）
     */
    public static boolean setPluginEnabled(Context context, PluginItem item, boolean enable) {
        if (item == null) return false;
        try {
            SharedPreferences sp = context.getSharedPreferences("pimet_plugins_state", Context.MODE_PRIVATE);
            Set<String> disabledSet = new HashSet<>(sp.getStringSet("disabled_plugins", Collections.emptySet()));
            if (enable) {
                disabledSet.remove(item.name);
                if (item.rawPkgName != null) disabledSet.remove(item.rawPkgName);
            } else {
                disabledSet.add(item.name);
                if (item.rawPkgName != null) disabledSet.add(item.rawPkgName);
            }
            sp.edit().putStringSet("disabled_plugins", disabledSet).apply();

            // 1. 如果是文件路径并且是单文件扩展/脚本/子代理，支持动态更名 .disabled
            if (item.path != null) {
                File f = new File(item.path);
                if (f.exists()) {
                    if (!enable && !f.getName().endsWith(".disabled")) {
                        File disabledFile = new File(f.getParentFile(), f.getName() + ".disabled");
                        f.renameTo(disabledFile);
                    } else if (enable && f.getName().endsWith(".disabled")) {
                        String normalName = f.getName().substring(0, f.getName().length() - ".disabled".length());
                        File normalFile = new File(f.getParentFile(), normalName);
                        f.renameTo(normalFile);
                    }
                }
            }

            // 2. 如果是 MCP 服务，更新 mcp.json 中的 disabled 标识
            if (item.type == PluginItem.TYPE_MCP) {
                File rootfs = ProotManager.getRootfsDir(context);
                File mcpFile = new File(rootfs, "root/.pi/agent/mcp.json");
                if (mcpFile.exists()) {
                    String jsonStr = readFile(mcpFile);
                    if (jsonStr != null) {
                        JSONObject root = new JSONObject(jsonStr);
                        JSONObject servers = root.optJSONObject("mcpServers");
                        if (servers != null && servers.has(item.name)) {
                            JSONObject sObj = servers.optJSONObject(item.name);
                            if (sObj != null) {
                                sObj.put("disabled", !enable);
                                saveFile(mcpFile, root.toString(2));
                            }
                        }
                    }
                }
            }

            // 3. 如果在 settings.json 的 extensions 中有声明
            File rootfs = ProotManager.getRootfsDir(context);
            File settingsFile = new File(rootfs, "root/.pi/agent/settings.json");
            if (settingsFile.exists()) {
                String sContent = readFile(settingsFile);
                if (sContent != null) {
                    JSONObject sObj = new JSONObject(sContent);
                    JSONArray extArr = sObj.optJSONArray("extensions");
                    if (extArr != null) {
                        boolean modified = false;
                        for (int i = 0; i < extArr.length(); i++) {
                            String entry = extArr.optString(i, "");
                            String cleanEntry = entry.startsWith("-") ? entry.substring(1) : entry;
                            if (cleanEntry.equals(item.name) || cleanEntry.endsWith("/" + item.name)) {
                                if (!enable && !entry.startsWith("-")) {
                                    extArr.put(i, "-" + cleanEntry);
                                    modified = true;
                                } else if (enable && entry.startsWith("-")) {
                                    extArr.put(i, cleanEntry);
                                    modified = true;
                                }
                            }
                        }
                        if (modified) {
                            saveFile(settingsFile, sObj.toString(2));
                        }
                    }
                }
            }

            item.enabled = enable;
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "setPluginEnabled error: " + item.name, t);
            return false;
        }
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
                if (skillDoc.exists() && skillDoc.isFile()) {
                    if (addedPaths.add(skillDoc.getAbsolutePath())) {
                        String desc = parentPkg != null ? ("来自 " + parentPkg + " 技能文档") : ("标准化技能: " + f.getName());
                        list.add(new PluginItem(PluginItem.TYPE_SKILL, f.getName(), skillDoc.getAbsolutePath(), desc, parentPkg != null ? parentPkg : f.getName()));
                    }
                } else {
                    scanSkillsRecursive(f, list, addedPaths, parentPkg);
                }
            }
        }
    }

    /**
     * 异步检测是否有新版本发布（仅针对 NPM 插件）
     */
    public static void checkUpdatesAsync(Context context, List<PluginItem> items, UpdateCheckCallback callback) {
        new Thread(() -> {
            int updateCount = 0;
            if (items != null) {
                for (PluginItem item : items) {
                    if (item.type != PluginItem.TYPE_EXTENSION) continue;
                    String pkg = item.rawPkgName != null ? item.rawPkgName : item.name;
                    if (pkg.contains(" (全局)")) pkg = pkg.replace(" (全局)", "").trim();
                    if (pkg.isEmpty()) continue;

                    String latest = fetchLatestNpmVersion(pkg);
                    if (latest != null && !latest.isEmpty()) {
                        if (isVersionNewer(latest, item.version)) {
                            item.hasUpdate = true;
                            item.latestVersion = latest;
                            updateCount++;
                        } else {
                            item.hasUpdate = false;
                        }
                    }
                }
            }
            final int finalCount = updateCount;
            if (callback != null) {
                callback.onChecked(finalCount);
            }
        }).start();
    }

    private static String fetchLatestNpmVersion(String pkg) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://registry.npmmirror.com/" + pkg + "/latest");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            if (conn.getResponseCode() == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    JSONObject json = new JSONObject(sb.toString());
                    return json.optString("version", null);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
        }
        return null;
    }

    public static boolean isVersionNewer(String remote, String local) {
        if (remote == null || remote.isEmpty()) return false;
        if (local == null || local.isEmpty()) return true;
        if (remote.equals(local)) return false;
        try {
            String cleanRemote = remote.replaceAll("[^0-9.]", "");
            String cleanLocal = local.replaceAll("[^0-9.]", "");
            String[] rParts = cleanRemote.split("\\.");
            String[] lParts = cleanLocal.split("\\.");
            int max = Math.max(rParts.length, lParts.length);
            for (int i = 0; i < max; i++) {
                int rVal = i < rParts.length && !rParts[i].isEmpty() ? Integer.parseInt(rParts[i]) : 0;
                int lVal = i < lParts.length && !lParts[i].isEmpty() ? Integer.parseInt(lParts[i]) : 0;
                if (rVal > lVal) return true;
                if (rVal < lVal) return false;
            }
        } catch (Throwable ignored) {}
        return !remote.equalsIgnoreCase(local);
    }

    /**
     * 更新单个插件（仅当有新版或明确请求时执行）
     */
    public static boolean updatePlugin(Context context, PluginItem item) {
        try {
            String pkg = item.rawPkgName != null ? item.rawPkgName : item.name;
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
     * 更新有新版本的插件或依赖包
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
     * 保存/更新 MCP 外部服务配置
     * 支持 stdio 命令行服务 (command, args, env) 与 HTTP/SSE 远程服务 (url, headers, description)
     */
    public static boolean saveMcpServer(Context context, String serverName, String commandOrUrl, String argsOrDesc, String envOrHeaders) {
        try {
            if (serverName == null || serverName.trim().isEmpty()) return false;
            String cleanName = serverName.trim().replaceAll("[^a-zA-Z0-9_-]", "_");

            File rootfs = ProotManager.getRootfsDir(context);
            File mcpFile = new File(rootfs, "root/.pi/agent/mcp.json");
            mcpFile.getParentFile().mkdirs();

            JSONObject root;
            if (mcpFile.exists()) {
                String existing = readFile(mcpFile);
                root = existing != null ? new JSONObject(existing) : new JSONObject();
            } else {
                root = new JSONObject();
            }

            JSONObject servers = root.optJSONObject("mcpServers");
            if (servers == null) {
                servers = new JSONObject();
                root.put("mcpServers", servers);
            }

            JSONObject serverObj = new JSONObject();
            String target = commandOrUrl != null ? commandOrUrl.trim() : "";

            if (target.startsWith("http://") || target.startsWith("https://")) {
                serverObj.put("url", target);
                if (argsOrDesc != null && !argsOrDesc.trim().isEmpty()) {
                    serverObj.put("description", argsOrDesc.trim());
                }
                if (envOrHeaders != null && !envOrHeaders.trim().isEmpty()) {
                    try {
                        JSONObject headersObj = new JSONObject(envOrHeaders.trim());
                        serverObj.put("headers", headersObj);
                    } catch (Throwable ignored) {}
                }
            } else {
                serverObj.put("command", target);
                if (argsOrDesc != null && !argsOrDesc.trim().isEmpty()) {
                    JSONArray argsArr = new JSONArray();
                    for (String arg : argsOrDesc.trim().split("\\s+")) {
                        if (!arg.isEmpty()) argsArr.put(arg);
                    }
                    serverObj.put("args", argsArr);
                }
                if (envOrHeaders != null && !envOrHeaders.trim().isEmpty()) {
                    try {
                        JSONObject envObj = new JSONObject(envOrHeaders.trim());
                        serverObj.put("env", envObj);
                    } catch (Throwable ignored) {}
                }
            }

            servers.put(cleanName, serverObj);
            writeFile(mcpFile, root.toString(2));
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "saveMcpServer error", t);
            return false;
        }
    }

    /**
     * 新建/保存自定义 Skill 技能 (自动补全标准 Frontmatter 元数据)
     */
    public static boolean saveSkill(Context context, String skillName, String markdownContent) {
        try {
            if (skillName == null || skillName.trim().isEmpty()) return false;
            String cleanName = skillName.trim().replaceAll("[^a-zA-Z0-9_-]", "_");

            File rootfs = ProotManager.getRootfsDir(context);
            File skillDir = new File(rootfs, "root/.pi/agent/skills/" + cleanName);
            skillDir.mkdirs();
            File skillFile = new File(skillDir, "SKILL.md");

            String content = markdownContent != null ? markdownContent.trim() : "";
            if (!content.startsWith("---")) {
                content = "---\nname: " + cleanName + "\ndescription: " + cleanName + " 技能\n---\n\n" + content;
            }

            writeFile(skillFile, content);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "saveSkill error", t);
            return false;
        }
    }

    /**
     * 将扩展包自动同步写入 settings.json 的 packages 列表
     */
    public static void addPackageToSettings(Context context, String pkgName) {
        try {
            if (pkgName == null || pkgName.trim().isEmpty()) return;
            String clean = pkgName.trim();
            if (clean.startsWith("npm install ") || clean.startsWith("npm i ")) {
                clean = clean.replaceFirst("^npm (install|i) ", "").trim();
            }
            if (clean.contains("@") && !clean.startsWith("@")) {
                clean = clean.substring(0, clean.indexOf("@")).trim();
            }

            File rootfs = ProotManager.getRootfsDir(context);
            File settingsJson = new File(rootfs, "root/.pi/agent/settings.json");
            JSONObject obj = new JSONObject();
            if (settingsJson.exists()) {
                String c = readFile(settingsJson);
                if (c != null && !c.trim().isEmpty()) {
                    try { obj = new JSONObject(c); } catch (Throwable ignored) {}
                }
            }
            JSONArray pkgs = obj.optJSONArray("packages");
            if (pkgs == null) {
                pkgs = new JSONArray();
                obj.put("packages", pkgs);
            }
            String target = clean.startsWith("npm:") || clean.startsWith("git:") ? clean : ("npm:" + clean);
            boolean exists = false;
            for (int i = 0; i < pkgs.length(); i++) {
                if (target.equalsIgnoreCase(pkgs.optString(i, ""))) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                pkgs.put(target);
                writeFile(settingsJson, obj.toString(2));
            }
        } catch (Throwable t) {
            Log.e(TAG, "addPackageToSettings error", t);
        }
    }

    /**
     * 校验配置语法与格式规范是否符合 Pi 引擎要求
     */
    public static class ValidationResult {
        public boolean valid;
        public String message = "";
        public String formatted = "";
    }

    public static ValidationResult validatePluginConfig(int type, String name, String content) {
        ValidationResult r = new ValidationResult();
        if (content == null || content.trim().isEmpty()) {
            r.valid = false;
            r.message = "配置内容不能为空";
            return r;
        }
        String str = content.trim();

        if (type == PluginItem.TYPE_MCP) {
            try {
                JSONObject obj = new JSONObject(str);
                boolean hasCmd = obj.has("command") && !obj.optString("command").trim().isEmpty();
                boolean hasUrl = obj.has("url") && !obj.optString("url").trim().isEmpty();
                if (!hasCmd && !hasUrl) {
                    r.valid = false;
                    r.message = "MCP 配置必须包含 'command' (stdio 服务) 或 'url' (HTTP 服务)";
                    return r;
                }
                r.valid = true;
                r.formatted = obj.toString(2);
                r.message = hasUrl ? "✔ HTTP/SSE MCP 服务配置校验通过" : "✔ stdio MCP 服务配置校验通过";
                return r;
            } catch (Throwable e) {
                r.valid = false;
                r.message = "JSON 语法解析失败: " + e.getMessage();
                return r;
            }
        } else if (type == PluginItem.TYPE_SKILL) {
            if (name == null || name.trim().isEmpty()) {
                r.valid = false;
                r.message = "技能名称不能为空";
                return r;
            }
            if (name.contains("/") || name.contains("\\") || name.contains("..")) {
                r.valid = false;
                r.message = "技能名称不能包含路径分隔符或非法符号";
                return r;
            }
            r.valid = true;
            if (!str.startsWith("---")) {
                r.formatted = "---\nname: " + name.trim() + "\ndescription: " + name.trim() + " 技能\n---\n\n" + str;
                r.message = "✔ Skill 内容符合规范 (已自动补充标准 YAML frontmatter 元数据)";
            } else {
                r.formatted = str;
                r.message = "✔ Skill 内容校验通过 (包含标准元数据)";
            }
            return r;
        } else if (type == PluginItem.TYPE_SUBAGENT) {
            if (name == null || name.trim().isEmpty()) {
                r.valid = false;
                r.message = "子代理名称不能为空";
                return r;
            }
            r.valid = true;
            r.formatted = str;
            r.message = "✔ 子代理 Prompt 配置有效";
            return r;
        } else {
            // Extension or package
            r.valid = true;
            r.formatted = str;
            r.message = "✔ 扩展配置已就绪";
            return r;
        }
    }

    /**
     * 新建/保存自定义 Subagent 子智能体
     */
    public static boolean saveSubagent(Context context, String agentName, String promptContent) {
        try {
            File rootfs = ProotManager.getRootfsDir(context);
            File agentDir = new File(rootfs, "root/.pi/agent/agents");
            agentDir.mkdirs();
            File agentFile = new File(agentDir, agentName.trim().endsWith(".md") ? agentName.trim() : (agentName.trim() + ".md"));
            writeFile(agentFile, promptContent);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "saveSubagent error", t);
            return false;
        }
    }

    /**
     * 获取指定插件项的配置文件内容
     */
    public static String getPluginConfig(Context context, PluginItem item) {
        try {
            File f = new File(item.path);
            if (item.type == PluginItem.TYPE_MCP) {
                File rootfs = ProotManager.getRootfsDir(context);
                File mcpFile = new File(rootfs, "root/.pi/agent/mcp.json");
                if (mcpFile.exists()) {
                    String str = readFile(mcpFile);
                    if (str != null) {
                        JSONObject root = new JSONObject(str);
                        JSONObject servers = root.optJSONObject("mcpServers");
                        if (servers != null && servers.has(item.name)) {
                            return servers.getJSONObject(item.name).toString(2);
                        }
                    }
                    return str;
                }
            } else if (f.isFile()) {
                return readFile(f);
            } else if (f.isDirectory()) {
                File skillDoc = new File(f, "SKILL.md");
                if (skillDoc.exists()) return readFile(skillDoc);
                File pkgJson = new File(f, "package.json");
                if (pkgJson.exists()) return readFile(pkgJson);
            }
        } catch (Throwable ignored) {}
        return "";
    }

    /**
     * 更新指定插件项的配置或内容
     */
    public static boolean savePluginConfig(Context context, PluginItem item, String content) {
        try {
            File f = new File(item.path);
            if (item.type == PluginItem.TYPE_MCP) {
                File rootfs = ProotManager.getRootfsDir(context);
                File mcpFile = new File(rootfs, "root/.pi/agent/mcp.json");
                if (mcpFile.exists()) {
                    String str = readFile(mcpFile);
                    JSONObject root = str != null ? new JSONObject(str) : new JSONObject();
                    JSONObject servers = root.optJSONObject("mcpServers");
                    if (servers == null) {
                        servers = new JSONObject();
                        root.put("mcpServers", servers);
                    }
                    JSONObject newObj = new JSONObject(content.trim());
                    servers.put(item.name, newObj);
                    writeFile(mcpFile, root.toString(2));
                    return true;
                }
            } else if (f.isFile()) {
                writeFile(f, content);
                return true;
            } else if (f.isDirectory()) {
                File skillDoc = new File(f, "SKILL.md");
                if (skillDoc.exists()) {
                    writeFile(skillDoc, content);
                    return true;
                }
                File pkgJson = new File(f, "package.json");
                if (pkgJson.exists()) {
                    writeFile(pkgJson, content);
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "savePluginConfig error", t);
        }
        return false;
    }

    /**
     * 读取全局 settings.json
     */
    public static String getGlobalSettings(Context context) {
        File rootfs = ProotManager.getRootfsDir(context);
        File sFile = new File(rootfs, "root/.pi/agent/settings.json");
        if (sFile.exists()) {
            return readFile(sFile);
        }
        return "{\n  \"packages\": []\n}";
    }

    /**
     * 保存全局 settings.json
     */
    public static boolean saveGlobalSettings(Context context, String content) {
        try {
            File rootfs = ProotManager.getRootfsDir(context);
            File sFile = new File(rootfs, "root/.pi/agent/settings.json");
            sFile.getParentFile().mkdirs();
            new JSONObject(content); // 验证合规性
            writeFile(sFile, content);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "saveGlobalSettings error", t);
            return false;
        }
    }

    /**
     * 删除指定插件或技能
     */
    public static boolean deletePlugin(Context context, PluginItem item) {
        try {
            File rootfs = ProotManager.getRootfsDir(context);
            if (item.type == PluginItem.TYPE_MCP) {
                File mcpFile = new File(rootfs, "root/.pi/agent/mcp.json");
                if (mcpFile.exists()) {
                    String jsonStr = readFile(mcpFile);
                    if (jsonStr != null) {
                        JSONObject root = new JSONObject(jsonStr);
                        JSONObject servers = root.optJSONObject("mcpServers");
                        if (servers != null && servers.has(item.name)) {
                            servers.remove(item.name);
                            writeFile(mcpFile, root.toString(2));
                        }
                    }
                }
                syncPlugins(context);
                return true;
            } else if (item.type == PluginItem.TYPE_EXTENSION) {
                String pkg = item.rawPkgName != null ? item.rawPkgName : item.name;
                if (pkg.contains(" (v")) {
                    pkg = pkg.substring(0, pkg.indexOf(" (v")).trim();
                }
                if (pkg.contains(" (全局)")) {
                    pkg = pkg.replace(" (全局)", "").trim();
                    ProotManager.executeCommandSync(context, "npm uninstall -g " + pkg);
                } else {
                    ProotManager.executeCommandSync(context, "cd /root/.pi/agent/npm && npm uninstall " + pkg);
                }

                // 若在 extensions/ 目录下存在同名文件或文件夹，一并移除
                File target = new File(item.path);
                if (target.exists()) {
                    deleteRecursively(target);
                }

                // 从 settings.json 中移除该扩展
                removeExtensionFromSettings(context, item.name, item.rawPkgName, item.path);
                syncPlugins(context);
                return true;
            } else {
                File target = new File(item.path);
                if (target.exists()) {
                    deleteRecursively(target);
                }
                removeExtensionFromSettings(context, item.name, item.rawPkgName, item.path);
                syncPlugins(context);
                return true;
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to delete plugin: " + item.name, t);
        }
        return false;
    }

    private static void removeExtensionFromSettings(Context context, String name, String rawPkgName, String path) {
        try {
            File rootfs = ProotManager.getRootfsDir(context);
            File settingsJson = new File(rootfs, "root/.pi/agent/settings.json");
            if (!settingsJson.exists()) return;
            String c = readFile(settingsJson);
            if (c == null || c.trim().isEmpty()) return;
            JSONObject obj = new JSONObject(c);
            JSONArray exts = obj.optJSONArray("extensions");
            if (exts == null) return;
            JSONArray newExts = new JSONArray();
            for (int i = 0; i < exts.length(); i++) {
                String e = exts.optString(i, "");
                boolean match = e.equals(name) || (rawPkgName != null && e.equals(rawPkgName))
                        || (path != null && (e.equals(path) || path.endsWith(e)));
                if (!match) {
                    newExts.put(e);
                }
            }
            obj.put("extensions", newExts);
            writeFile(settingsJson, obj.toString(2));
        } catch (Throwable ignored) {}
    }

    /**
     * 自动双向同步插件列表：
     * 1. 扫描磁盘上安装的扩展，将外面装好的自动同步写入 settings.json
     * 2. 清理 settings.json 中已被外部删除/卸载的不存在扩展
     * 3. 确保 mcp.json 结构合法与状态一致
     */
    public static void syncPlugins(Context context) {
        try {
            File rootfs = ProotManager.getRootfsDir(context);
            if (!rootfs.exists()) return;
            File piAgentDir = new File(rootfs, "root/.pi/agent");
            if (!piAgentDir.exists()) piAgentDir.mkdirs();

            File settingsJson = new File(piAgentDir, "settings.json");
            JSONObject settingsObj = new JSONObject();
            if (settingsJson.exists()) {
                String c = readFile(settingsJson);
                if (c != null && !c.trim().isEmpty()) {
                    try { settingsObj = new JSONObject(c); } catch (Throwable ignored) {}
                }
            }

            JSONArray currentExts = settingsObj.optJSONArray("extensions");
            if (currentExts == null) {
                currentExts = new JSONArray();
                settingsObj.put("extensions", currentExts);
            }

            // 收集现有配置中的扩展
            Set<String> configuredExts = new HashSet<>();
            for (int i = 0; i < currentExts.length(); i++) {
                String item = currentExts.optString(i, "").trim();
                if (!item.isEmpty()) configuredExts.add(item);
            }

            // 扫描磁盘上实际存在的扩展
            Set<String> actualDiskExts = new HashSet<>();
            File extDir = new File(piAgentDir, "extensions");
            if (extDir.exists() && extDir.isDirectory()) {
                File[] extFiles = extDir.listFiles();
                if (extFiles != null) {
                    for (File ef : extFiles) {
                        String name = ef.getName();
                        if (ef.isFile() && (name.endsWith(".ts") || name.endsWith(".js"))) {
                            actualDiskExts.add("extensions/" + name);
                            actualDiskExts.add(ef.getAbsolutePath());
                        } else if (ef.isDirectory()) {
                            actualDiskExts.add("extensions/" + name);
                            actualDiskExts.add(ef.getAbsolutePath());
                        }
                    }
                }
            }

            File npmDir = new File(piAgentDir, "npm");
            File npmPkgJson = new File(npmDir, "package.json");
            File npmNodeModules = new File(npmDir, "node_modules");
            if (npmPkgJson.exists()) {
                String pkgContent = readFile(npmPkgJson);
                if (pkgContent != null) {
                    try {
                        JSONObject rootPkg = new JSONObject(pkgContent);
                        JSONObject deps = rootPkg.optJSONObject("dependencies");
                        if (deps != null) {
                            Iterator<String> depKeys = deps.keys();
                            while (depKeys.hasNext()) {
                                String pkgName = depKeys.next();
                                actualDiskExts.add(pkgName);
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
            if (npmNodeModules.exists() && npmNodeModules.isDirectory()) {
                File[] mods = npmNodeModules.listFiles();
                if (mods != null) {
                    for (File m : mods) {
                        if (m.getName().startsWith("@")) {
                            File[] subMods = m.listFiles();
                            if (subMods != null) {
                                for (File sm : subMods) {
                                    actualDiskExts.add(m.getName() + "/" + sm.getName());
                                }
                            }
                        } else {
                            actualDiskExts.add(m.getName());
                        }
                    }
                }
            }

            // 1. 同步外面的卸载到里面：移除 settings.json 中磁盘上已不存在的插件
            JSONArray newExts = new JSONArray();
            boolean changed = false;
            for (int i = 0; i < currentExts.length(); i++) {
                String ext = currentExts.optString(i, "").trim();
                if (ext.isEmpty()) continue;
                boolean exists = false;
                if (actualDiskExts.contains(ext)) {
                    exists = true;
                } else if (ext.startsWith("/") && new File(ext).exists()) {
                    exists = true;
                } else if (new File(piAgentDir, ext).exists()) {
                    exists = true;
                } else if (new File(npmNodeModules, ext).exists()) {
                    exists = true;
                } else if (new File(extDir, ext).exists()) {
                    exists = true;
                }
                if (exists) {
                    newExts.put(ext);
                } else {
                    changed = true;
                    Log.i(TAG, "Sync: removed uninstalled extension from settings.json: " + ext);
                }
            }

            // 2. 同步外面的安装到里面：若本地 extensions 目录或 npm package.json 中有新插件，自动加入 settings.json
            for (String diskExt : actualDiskExts) {
                if (diskExt.startsWith("extensions/") || diskExt.equals("oh-my-pi") || diskExt.startsWith("@bacnh85/")) {
                    boolean alreadyIn = false;
                    for (int i = 0; i < newExts.length(); i++) {
                        String e = newExts.optString(i, "");
                        if (e.equals(diskExt) || (diskExt.startsWith("extensions/") && e.endsWith(diskExt))) {
                            alreadyIn = true;
                            break;
                        }
                    }
                    if (!alreadyIn) {
                        newExts.put(diskExt);
                        changed = true;
                        Log.i(TAG, "Sync: added newly installed extension to settings.json: " + diskExt);
                    }
                }
            }

            if (changed) {
                settingsObj.put("extensions", newExts);
                writeFile(settingsJson, settingsObj.toString(2));
            }
        } catch (Throwable t) {
            Log.e(TAG, "syncPlugins error", t);
        }
    }

    /**
     * 智能识别并安装多类型扩展、NPM包、MCP服务、Skill或命令
     */
    public static class SmartInstallResult {
        public boolean success;
        public String detectedType = "未知类型";
        public String message = "";
        public String targetName = "";
    }

    public static SmartInstallResult smartInstall(Context context, String rawInput) {
        SmartInstallResult res = new SmartInstallResult();
        if (rawInput == null || rawInput.trim().isEmpty()) {
            res.success = false;
            res.message = "输入内容为空";
            return res;
        }

        String input = rawInput.trim();
        String reg = PiMetConfig.getNpmRegistry(context);

        // 1. 去除用户复制的 shell 前缀
        if (input.startsWith("npm i -g ") || input.startsWith("npm install -g ")) {
            input = input.replaceFirst("^npm (install|i) -g ", "").trim();
        } else if (input.startsWith("npm i ") || input.startsWith("npm install ")) {
            input = input.replaceFirst("^npm (install|i) ", "").trim();
        } else if (input.startsWith("pnpm add -g ") || input.startsWith("pnpm add ")) {
            input = input.replaceFirst("^pnpm add (-g )?", "").trim();
        }

        try {
            // Case A: Git 仓库链接或 Raw 文件链接 (http://, https://, git@)
            if (input.startsWith("http://") || input.startsWith("https://") || input.startsWith("git@")) {
                if (input.endsWith("/SKILL.md") || (input.contains("/skills/") && input.endsWith(".md"))) {
                    // 这是 Skill 技能的 Raw 链接
                    res.detectedType = "Skill 专家技能";
                    String skillName = "custom-skill-" + System.currentTimeMillis();
                    String[] parts = input.split("/");
                    if (parts.length > 2 && parts[parts.length - 1].equalsIgnoreCase("SKILL.md")) {
                        skillName = parts[parts.length - 2];
                    }
                    res.targetName = skillName;
                    String content = downloadUrlContent(input);
                    if (content != null && !content.isEmpty()) {
                        saveSkill(context, skillName, content);
                        syncPlugins(context);
                        res.success = true;
                        res.message = "成功下载并添加技能: " + skillName;
                        return res;
                    } else {
                        res.success = false;
                        res.message = "下载 SKILL.md 失败，请检查网络链接";
                        return res;
                    }
                } else if (input.endsWith(".md")) {
                    // 这是 Subagent 提示词的 Raw 链接
                    res.detectedType = "Subagent 子智能体";
                    String agentName = new File(input).getName().replace(".md", "");
                    res.targetName = agentName;
                    String content = downloadUrlContent(input);
                    if (content != null && !content.isEmpty()) {
                        saveSubagent(context, agentName, content);
                        syncPlugins(context);
                        res.success = true;
                        res.message = "成功下载并添加子智能体: " + agentName;
                        return res;
                    } else {
                        res.success = false;
                        res.message = "下载子智能体失败，请检查网络链接";
                        return res;
                    }
                } else {
                    // Git 仓库 -> 作为扩展克隆并集成
                    res.detectedType = "Git 仓库扩展";
                    String repoName = input;
                    if (repoName.endsWith(".git")) repoName = repoName.substring(0, repoName.length() - 4);
                    int lastSlash = repoName.lastIndexOf('/');
                    if (lastSlash >= 0) repoName = repoName.substring(lastSlash + 1);
                    res.targetName = repoName;

                    // 优先在 ~/.pi/agent/extensions 目录中 git clone
                    String cloneCmd = "mkdir -p /root/.pi/agent/extensions && cd /root/.pi/agent/extensions && git clone --depth=1 " + input;
                    int code = ProotManager.executeCommandSync(context, cloneCmd);
                    if (code != 0) {
                        // 备选方案: npm install <git-url>
                        String npmGitCmd = "cd /root/.pi/agent/npm && npm install " + input + " --registry=" + reg;
                        code = ProotManager.executeCommandSync(context, npmGitCmd);
                    }
                    syncPlugins(context);
                    res.success = (code == 0);
                    res.message = res.success ? "成功克隆并同步扩展: " + repoName : "Git 克隆或安装失败";
                    return res;
                }
            }

            // Case B: MCP 模块 (以 @modelcontextprotocol/ 开头，或 mcp-server-，或以 npx 开头)
            if (input.startsWith("@modelcontextprotocol/") || input.startsWith("mcp-server-") || input.startsWith("npx -y @modelcontextprotocol/")) {
                res.detectedType = "MCP 外部协议服务";
                String pkgName = input.replace("npx -y ", "").trim();
                String serverName = pkgName;
                if (serverName.contains("/")) serverName = serverName.substring(serverName.lastIndexOf('/') + 1);
                if (serverName.startsWith("server-")) serverName = serverName.substring(7);
                res.targetName = serverName;

                // 1. 在容器中安装
                String cmd = "npm install -g " + pkgName + " --registry=" + reg;
                ProotManager.executeCommandSync(context, cmd);

                // 2. 自动注入到 ~/.pi/agent/mcp.json
                File rootfs = ProotManager.getRootfsDir(context);
                File mcpFile = new File(rootfs, "root/.pi/agent/mcp.json");
                JSONObject mcpRoot = new JSONObject();
                if (mcpFile.exists()) {
                    String str = readFile(mcpFile);
                    if (str != null && !str.trim().isEmpty()) {
                        try { mcpRoot = new JSONObject(str); } catch (Throwable ignored) {}
                    }
                }
                JSONObject servers = mcpRoot.optJSONObject("mcpServers");
                if (servers == null) {
                    servers = new JSONObject();
                    mcpRoot.put("mcpServers", servers);
                }
                JSONObject srv = new JSONObject();
                srv.put("command", "npx");
                JSONArray args = new JSONArray();
                args.put("-y");
                args.put(pkgName);
                srv.put("args", args);
                servers.put(serverName, srv);
                writeFile(mcpFile, mcpRoot.toString(2));

                syncPlugins(context);
                res.success = true;
                res.message = "成功安装并自动配置 MCP 服务: " + serverName;
                return res;
            }

            // Case C: 自定义 Shell 命令 (如 git clone, curl, npm ...)
            if (input.startsWith("git ") || input.startsWith("curl ") || input.startsWith("wget ") || input.contains(" && ")) {
                res.detectedType = "自定义 Shell 命令";
                res.targetName = "shell-command";
                int code = ProotManager.executeCommandSync(context, input);
                syncPlugins(context);
                res.success = (code == 0);
                res.message = res.success ? "命令执行完成" : "命令执行返回非 0 状态码";
                return res;
            }

            // Case D: 标准 NPM 扩展包名 (如 oh-my-pi, @bacnh85/pi-ux 等)
            res.detectedType = "NPM 官方扩展包";
            res.targetName = input;
            String cmd1 = "cd /root/.pi/agent/npm && npm install " + input + " --registry=" + reg;
            int code1 = ProotManager.executeCommandSync(context, cmd1);
            if (code1 != 0) {
                String cmd2 = "npm install -g " + input + " --registry=" + reg;
                code1 = ProotManager.executeCommandSync(context, cmd2);
            }
            if (code1 == 0) {
                addPackageToSettings(context, input);
            }
            syncPlugins(context);
            res.success = (code1 == 0);
            res.message = res.success ? "成功安装并同步扩展: " + input : "NPM 安装失败，请检查包名或网络";
            return res;

        } catch (Throwable t) {
            Log.e(TAG, "smartInstall error", t);
            res.success = false;
            res.message = "安装异常: " + t.getMessage();
            return res;
        }
    }

    private static String downloadUrlContent(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 PiMet/1.2.7");
            if (conn.getResponseCode() == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                    return sb.toString();
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    /**
     * 自动为插件、工具、技能或 MCP 服务生成中文注释与说明
     */
    public static String getChineseAnnotation(String name, String originalDesc, int type) {
        String lowerName = (name != null ? name : "").toLowerCase().trim();
        String lowerDesc = (originalDesc != null ? originalDesc : "").toLowerCase().trim();

        // 1. 精确匹配已知官方及社区核心扩展/工具/技能
        if (lowerName.contains("pet-companion") || lowerName.contains("desktop-pet") || lowerName.contains("pet-agent")) {
            return "动态桌宠专属智能体 · 具备独立长久记忆、系统进度感知汇报与动作互动能力";
        }
        if (lowerName.equals("oh-my-pi") || lowerName.contains("oh-my-pi")) {
            return "全能 AI 编排与多智能体系统（内置代码审查、自动化重构、浏览器测试与架构专家）";
        }
        if (lowerName.contains("pi-ux") || lowerName.contains("ux-design")) {
            return "防 AI 劣质设计的 UI/UX 设计规范与自动化审查工具";
        }
        if (lowerName.contains("android-bridge") || lowerName.contains("pimet-android-bridge") || lowerName.contains("pimet-bridge")) {
            return "Android 宿主原生控制扩展（支持动态操控桌宠、切换界面、配置端口）";
        }
        if (lowerName.equals("code-review")) {
            return "代码审查专家 · 全面评估正确性、性能、安全性与代码风格";
        }
        if (lowerName.equals("debugging")) {
            return "系统化调试专家 · 假设驱动定位、根因分析与最小化修复";
        }
        if (lowerName.equals("frontend")) {
            return "前端 UI/UX 开发专家 · 页面设计、组件构建与响应式布局";
        }
        if (lowerName.equals("git-master")) {
            return "Git 版本控制专家 · 原子提交、变基整理与历史检索";
        }
        if (lowerName.equals("playwright")) {
            return "浏览器自动化与 E2E 测试 · 网页操控、截图录屏与状态抓取";
        }
        if (lowerName.equals("refactor")) {
            return "智能重构专家 · 模块解耦、代码提炼与结构现代性优化";
        }
        if (lowerName.equals("remove-ai-slops")) {
            return "消除 AI 生成代码异味 · 移除冗余复杂度与性能提速";
        }
        if (lowerName.equals("review-work")) {
            return "实施后全面复盘审查 · 自动化 QA、代码质量与安全性检查";
        }
        if (lowerName.equals("security-review")) {
            return "安全审计专家 · 漏洞评估、威胁建模与 OWASP 检查";
        }
        if (lowerName.equals("oracle")) {
            return "架构设计、跨系统权衡与疑难排查专家顾问";
        }
        if (lowerName.equals("metis")) {
            return "需求意图分析、潜在歧义与前置任务规划顾问";
        }
        if (lowerName.equals("momus")) {
            return "工作计划与执行方案严苛审查顾问";
        }
        if (lowerName.equals("librarian")) {
            return "多仓库研究、开源文档检索与 API 范例调研专家";
        }
        if (lowerName.equals("explore")) {
            return "上下文快速检索与项目代码库导航专家";
        }
        if (lowerName.equals("sisyphus-junior")) {
            return "专注单任务高效执行代理";
        }
        if (lowerName.equals("multimodal-looker")) {
            return "多模态媒体与图像分析智能体";
        }
        if (lowerName.contains("fetch") || lowerName.contains("server-fetch")) {
            return "网页内容抓取与文档提取服务";
        }
        if (lowerName.contains("filesystem") || lowerName.contains("server-filesystem")) {
            return "PRoot 容器本地文件系统安全操作服务";
        }
        if (lowerName.contains("github") || lowerName.contains("server-github")) {
            return "GitHub 远程仓库、Issue 与 Pull Request 交互服务";
        }
        if (lowerName.contains("puppeteer") || lowerName.contains("server-puppeteer")) {
            return "Puppeteer 无头浏览器渲染与页面控制服务";
        }
        if (lowerName.contains("sqlite") || lowerName.contains("server-sqlite")) {
            return "SQLite 数据库查询与结构管理服务";
        }
        if (lowerName.contains("memory") || lowerName.contains("server-memory") || lowerName.contains("pi-hermes-memory")) {
            return "持久化跨会话知识、经验与记忆库管理服务";
        }
        if (lowerName.contains("brave-search") || lowerName.contains("server-brave-search")) {
            return "Brave 互联网实时搜索引擎";
        }
        if (lowerName.contains("docker")) {
            return "Docker 容器编排与镜像管理服务";
        }

        // 2. 根据语义和类型生成智能中文标签
        if (type == PluginItem.TYPE_MCP) {
            return "MCP 协议外部能力服务 (" + name + ")";
        }
        if (type == PluginItem.TYPE_SKILL) {
            if (lowerDesc.contains("test") || lowerDesc.contains("browser")) return "自动化测试与浏览器交互技能";
            if (lowerDesc.contains("git") || lowerDesc.contains("commit")) return "Git 仓库协同与代码提交技能";
            if (lowerDesc.contains("refactor") || lowerDesc.contains("clean")) return "代码结构优化与重构技能";
            if (lowerDesc.contains("review") || lowerDesc.contains("audit")) return "代码质量审查与规范检查技能";
            return "Pi Agent 专业领域技能: " + name;
        }
        if (type == PluginItem.TYPE_SUBAGENT) {
            return "智能体角色与专属提示词: " + name;
        }

        if (lowerDesc.contains("ui") || lowerDesc.contains("frontend") || lowerDesc.contains("css")) {
            return "前端与用户界面交互扩展";
        }
        if (lowerDesc.contains("test") || lowerDesc.contains("testing")) {
            return "自动化测试与质量保障工具包";
        }
        if (lowerDesc.contains("tool") || lowerDesc.contains("utility")) {
            return "通用开发工具与效率扩展";
        }
        if (lowerDesc.contains("model") || lowerDesc.contains("ai") || lowerDesc.contains("agent")) {
            return "AI 智能体与模型能力增强扩展";
        }

        if (originalDesc != null && !originalDesc.isEmpty() && !originalDesc.startsWith("来自") && !originalDesc.startsWith("NPM 官方")) {
            return originalDesc;
        }
        return "Pi Agent 生态功能扩展包: " + name;
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
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString().trim();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void writeFile(File file, String content) {
        try {
            file.getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {}
    }

    private static void deleteRecursively(File fileOrDir) {
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        fileOrDir.delete();
    }
}
