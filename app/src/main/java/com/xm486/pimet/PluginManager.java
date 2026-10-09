package com.xm486.pimet;

import android.content.Context;
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
     */
    public static boolean saveMcpServer(Context context, String serverName, String command, String argsLine, String envJsonStr) {
        try {
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
            serverObj.put("command", command.trim());

            if (argsLine != null && !argsLine.trim().isEmpty()) {
                JSONArray argsArr = new JSONArray();
                for (String arg : argsLine.trim().split("\\s+")) {
                    if (!arg.isEmpty()) argsArr.put(arg);
                }
                serverObj.put("args", argsArr);
            }

            if (envJsonStr != null && !envJsonStr.trim().isEmpty()) {
                try {
                    JSONObject envObj = new JSONObject(envJsonStr.trim());
                    serverObj.put("env", envObj);
                } catch (Throwable ignored) {}
            }

            servers.put(serverName.trim(), serverObj);
            writeFile(mcpFile, root.toString(2));
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "saveMcpServer error", t);
            return false;
        }
    }

    /**
     * 新建/保存自定义 Skill 技能
     */
    public static boolean saveSkill(Context context, String skillName, String markdownContent) {
        try {
            File rootfs = ProotManager.getRootfsDir(context);
            File skillDir = new File(rootfs, "root/.pi/agent/skills/" + skillName.trim());
            skillDir.mkdirs();
            File skillFile = new File(skillDir, "SKILL.md");
            writeFile(skillFile, markdownContent);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "saveSkill error", t);
            return false;
        }
    }

    /**
     * 新建/保存自定义 Subagent 子智能体
     */
    public static boolean saveSubagent(Context context, String agentName, String promptContent) {
        try {
            File rootfs = ProotManager.getRootfsDir(context);
            File agentDir = new File(rootfs, "root/.pi/agent/subagents");
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
                            return true;
                        }
                    }
                }
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
                return true;
            } else {
                File target = new File(item.path);
                if (target.exists()) {
                    deleteRecursively(target);
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to delete plugin: " + item.name, t);
        }
        return false;
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
