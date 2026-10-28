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
     * 保留 ~/.pi/agent/extensions、skills、prompts、settings.json 中 packages 声明的包
     */
    public static List<PluginItem> getInstalledPlugins(Context context) {
        List<PluginItem> list = new ArrayList<>();
        Set<String> addedPaths = new HashSet<>();

        // 优先触发双向同步与旧 .disabled 兼容迁移
        syncPlugins(context);

        try {
            File rootfs = ProotManager.getRootfsDir(context);
            File piAgentDir = new File(rootfs, "root/.pi/agent");
            if (!piAgentDir.exists()) return list;

            // 1. 扫描本地手动编写的 Extensions (~/.pi/agent/extensions)
            File extDir = new File(piAgentDir, "extensions");
            if (extDir.exists() && extDir.isDirectory()) {
                File[] files = extDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.getName().startsWith(".")) continue;
                        if (addedPaths.add(f.getAbsolutePath())) {
                            String desc = "本地扩展: " + f.getName();
                            String version = "";
                            if (f.isDirectory()) {
                                File pkgJson = new File(f, "package.json");
                                if (pkgJson.exists()) {
                                    try {
                                        String c = readFile(pkgJson);
                                        if (c != null) {
                                            JSONObject o = new JSONObject(c);
                                            version = o.optString("version", "");
                                            String d = o.optString("description", "");
                                            if (!d.isEmpty()) desc = d;
                                        }
                                    } catch (Throwable ignored) {}
                                }
                            }
                            PluginItem item = new PluginItem(PluginItem.TYPE_EXTENSION, f.getName(), f.getAbsolutePath(), desc, f.getName());
                            item.version = version;
                            list.add(item);
                        }
                    }
                }
            }

            // 2. 扫描独立技能目录 (~/.pi/agent/skills)
            File skillsDir = new File(piAgentDir, "skills");
            if (skillsDir.exists() && skillsDir.isDirectory()) {
                scanSkillsRecursive(skillsDir, list, addedPaths, null);
            }

            // 3. 扫描 Prompts (~/.pi/agent/prompts)
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

            // 4. 扫描 settings.json 中声明的 packages
            File settingsFile = new File(piAgentDir, "settings.json");
            if (settingsFile.exists()) {
                try {
                    String sContent = readFile(settingsFile);
                    if (sContent != null) {
                        JSONObject sObj = new JSONObject(sContent);
                        JSONArray pkgsArr = sObj.optJSONArray("packages");
                        if (pkgsArr != null) {
                            for (int i = 0; i < pkgsArr.length(); i++) {
                                String rawPkg = "";
                                Object elem = pkgsArr.opt(i);
                                if (elem instanceof String) {
                                    rawPkg = (String) elem;
                                } else if (elem instanceof JSONObject) {
                                    rawPkg = ((JSONObject) elem).optString("source", "");
                                }
                                rawPkg = rawPkg.trim();
                                if (rawPkg.isEmpty()) continue;

                                boolean disabled = rawPkg.startsWith("-");
                                String cleanPkg = disabled ? rawPkg.substring(1).trim() : rawPkg;
                                String pkgName = cleanPkg;
                                if (pkgName.startsWith("npm:")) {
                                    pkgName = pkgName.substring(4);
                                }
                                String version = "";
                                int atIdx = pkgName.lastIndexOf('@');
                                if (atIdx > 0) {
                                    version = pkgName.substring(atIdx + 1);
                                    pkgName = pkgName.substring(0, atIdx);
                                }

                                File modDir = new File(rootfs, "usr/local/lib/node_modules/" + pkgName);
                                if (!modDir.exists()) {
                                    File piNm = new File(piAgentDir, "node_modules/" + pkgName);
                                    if (piNm.exists()) {
                                        modDir = piNm;
                                    } else {
                                        File npmNm = new File(piAgentDir, "npm/node_modules/" + pkgName);
                                        if (npmNm.exists()) {
                                            modDir = npmNm;
                                        }
                                    }
                                }

                                String modPath = modDir.exists() ? modDir.getAbsolutePath() : settingsFile.getAbsolutePath();
                                if (addedPaths.add(modPath)) {
                                    String desc = "配置项声明包: " + cleanPkg;
                                    if (modDir.exists()) {
                                        File subPkgJson = new File(modDir, "package.json");
                                        if (subPkgJson.exists()) {
                                            try {
                                                String c = readFile(subPkgJson);
                                                if (c != null) {
                                                    JSONObject subObj = new JSONObject(c);
                                                    if (version.isEmpty()) version = subObj.optString("version", "");
                                                    String d = subObj.optString("description", "");
                                                    if (!d.isEmpty()) desc = d;
                                                }
                                            } catch (Throwable ignored) {}
                                        }
                                    }
                                    PluginItem item = new PluginItem(PluginItem.TYPE_EXTENSION, pkgName, modPath, desc, cleanPkg);
                                    item.version = version;
                                    item.enabled = !disabled;
                                    list.add(item);
                                }

                                // 扫描该 NPM 包内置的技能 (skills/*.md 或 skills/*)
                                if (modDir.exists()) {
                                    File pkgSkillsDir = new File(modDir, "skills");
                                    if (pkgSkillsDir.exists() && pkgSkillsDir.isDirectory()) {
                                        scanSkillsRecursive(pkgSkillsDir, list, addedPaths, pkgName);
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }

        } catch (Throwable t) {
            Log.e(TAG, "Failed to get installed plugins", t);
        }

        // 统一注入启停状态与描述
        for (PluginItem item : list) {
            item.enabled = isPluginEnabled(context, item);
            if (item.chineseDesc == null || item.chineseDesc.isEmpty()) {
                item.chineseDesc = item.description;
            }
        }

        return list;
    }

    private static boolean matchesExtensionOrPackage(String clean, PluginItem item) {
        if (clean == null || item == null) return false;
        if (clean.equals(item.name)) return true;
        if (clean.equals("extensions/" + item.name)) return true;
        if (clean.endsWith("/" + item.name)) return true;
        if (item.name.equals(clean.replaceFirst("^extensions/", ""))) return true;
        if (item.rawPkgName != null) {
            if (clean.equals(item.rawPkgName)) return true;
            if (clean.equals("npm:" + item.rawPkgName)) return true;
            String cleanWithoutNpm = clean.replaceFirst("^npm:", "");
            String rawWithoutNpm = item.rawPkgName.replaceFirst("^npm:", "");
            if (cleanWithoutNpm.equals(rawWithoutNpm)) return true;
            if (cleanWithoutNpm.split("@")[0].equals(rawWithoutNpm.split("@")[0])) return true;
            if (cleanWithoutNpm.split("@")[0].equals(item.name)) return true;
        }
        if (item.path != null && (clean.equals(item.path) || item.path.endsWith("/" + clean))) return true;
        return false;
    }

    /**
     * 判断插件当前是否处于启用状态（仅读取 settings.json 中的 extensions/packages 数组）
     */
    public static boolean isPluginEnabled(Context context, PluginItem item) {
        if (item == null) return false;
        try {
            File rootfs = ProotManager.getRootfsDir(context);
            File piAgentDir = new File(rootfs, "root/.pi/agent");
            File settingsFile = new File(piAgentDir, "settings.json");
            if (settingsFile.exists()) {
                String jsonStr = readFile(settingsFile);
                if (jsonStr != null && !jsonStr.trim().isEmpty()) {
                    JSONObject settings = new JSONObject(jsonStr);

                    // 1. 检查 extensions 数组
                    JSONArray exts = settings.optJSONArray("extensions");
                    if (exts != null) {
                        for (int i = 0; i < exts.length(); i++) {
                            String entry = exts.optString(i, "").trim();
                            if (entry.startsWith("-")) {
                                String clean = entry.substring(1).trim();
                                if (matchesExtensionOrPackage(clean, item)) {
                                    return false;
                                }
                            }
                        }
                    }

                    // 2. 检查 packages 数组
                    JSONArray pkgs = settings.optJSONArray("packages");
                    if (pkgs != null) {
                        for (int i = 0; i < pkgs.length(); i++) {
                            String entry = "";
                            Object elem = pkgs.opt(i);
                            if (elem instanceof String) entry = (String) elem;
                            else if (elem instanceof JSONObject) entry = ((JSONObject) elem).optString("source", "");
                            entry = entry.trim();
                            if (entry.startsWith("-")) {
                                String clean = entry.substring(1).trim();
                                if (matchesExtensionOrPackage(clean, item)) {
                                    return false;
                                }
                            }
                        }
                    }
                }
            }

            if (item.type == PluginItem.TYPE_MCP) {
                File mcpFile = new File(piAgentDir, "mcp.json");
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
            }
        } catch (Throwable t) {
            Log.w(TAG, "isPluginEnabled error", t);
        }
        return true;
    }

    /**
     * 启停插件：统一只修改 ~/.pi/agent/settings.json 中的 extensions/packages 数组
     * 停用 = 加 "-" 前缀；启用 = 去掉 "-" 前缀
     */
    public static boolean setPluginEnabled(Context context, PluginItem item, boolean enable) {
        if (item == null) return false;
        try {
            File rootfs = ProotManager.getRootfsDir(context);
            File piAgentDir = new File(rootfs, "root/.pi/agent");
            if (!piAgentDir.exists()) piAgentDir.mkdirs();

            File settingsFile = new File(piAgentDir, "settings.json");
            JSONObject settingsObj = new JSONObject();
            if (settingsFile.exists()) {
                String c = readFile(settingsFile);
                if (c != null && !c.trim().isEmpty()) {
                    try { settingsObj = new JSONObject(c); } catch (Throwable ignored) {}
                }
            }

            boolean modified = false;

            // 1. 查找 packages 数组
            JSONArray pkgs = settingsObj.optJSONArray("packages");
            boolean foundInPackages = false;
            if (pkgs != null) {
                for (int i = 0; i < pkgs.length(); i++) {
                    Object elem = pkgs.opt(i);
                    if (elem instanceof String) {
                        String entry = (String) elem;
                        boolean startsWithDash = entry.startsWith("-");
                        String clean = startsWithDash ? entry.substring(1).trim() : entry.trim();
                        if (matchesExtensionOrPackage(clean, item)) {
                            foundInPackages = true;
                            if (!enable && !startsWithDash) {
                                pkgs.put(i, "-" + clean);
                                modified = true;
                            } else if (enable && startsWithDash) {
                                pkgs.put(i, clean);
                                modified = true;
                            }
                        }
                    }
                }
            }

            // 2. 查找 extensions 数组
            JSONArray exts = settingsObj.optJSONArray("extensions");
            boolean foundInExtensions = false;
            if (exts != null) {
                for (int i = 0; i < exts.length(); i++) {
                    String entry = exts.optString(i, "").trim();
                    boolean startsWithDash = entry.startsWith("-");
                    String clean = startsWithDash ? entry.substring(1).trim() : entry;
                    if (matchesExtensionOrPackage(clean, item)) {
                        foundInExtensions = true;
                        if (!enable && !startsWithDash) {
                            exts.put(i, "-" + clean);
                            modified = true;
                        } else if (enable && startsWithDash) {
                            exts.put(i, clean);
                            modified = true;
                        }
                    }
                }
            }

            // 3. 如果 settings.json 中尚无对应条目，停用时需追加带 "-" 前缀的条目
            if (!enable && !foundInPackages && !foundInExtensions) {
                if (exts == null) {
                    exts = new JSONArray();
                    settingsObj.put("extensions", exts);
                }
                String entryToAdd = "-extensions/" + item.name;
                exts.put(entryToAdd);
                modified = true;
            }

            if (modified) {
                writeFile(settingsFile, settingsObj.toString(2));
            }

            if (item.type == PluginItem.TYPE_MCP) {
                File mcpFile = new File(piAgentDir, "mcp.json");
                if (mcpFile.exists()) {
                    String jsonStr = readFile(mcpFile);
                    if (jsonStr != null) {
                        JSONObject root = new JSONObject(jsonStr);
                        JSONObject servers = root.optJSONObject("mcpServers");
                        if (servers != null && servers.has(item.name)) {
                            JSONObject sObj = servers.optJSONObject(item.name);
                            if (sObj != null) {
                                sObj.put("disabled", !enable);
                                writeFile(mcpFile, root.toString(2));
                            }
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

    /**
     * 兼容旧 .disabled 迁移：扫描到 foo.disabled 时自动改回 foo 并在 settings 里写成 "-extensions/foo"
     */
    private static void migrateLegacyDisabledExtensions(File piAgentDir) {
        try {
            File extDir = new File(piAgentDir, "extensions");
            if (!extDir.exists() || !extDir.isDirectory()) return;
            File[] files = extDir.listFiles();
            if (files == null) return;
            File settingsFile = new File(piAgentDir, "settings.json");
            for (File f : files) {
                String name = f.getName();
                if (name.endsWith(".disabled")) {
                    String normalName = name.substring(0, name.length() - ".disabled".length());
                    File normalFile = new File(f.getParentFile(), normalName);
                    if (f.renameTo(normalFile)) {
                        addDisabledExtensionToSettings(settingsFile, "extensions/" + normalName);
                        Log.i(TAG, "Migrated legacy disabled extension: " + name + " -> -extensions/" + normalName);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "migrateLegacyDisabledExtensions error", t);
        }
    }

    private static void addDisabledExtensionToSettings(File settingsFile, String targetEntry) {
        try {
            JSONObject settings = new JSONObject();
            if (settingsFile.exists()) {
                String c = readFile(settingsFile);
                if (c != null && !c.trim().isEmpty()) {
                    try { settings = new JSONObject(c); } catch (Throwable ignored) {}
                }
            }
            JSONArray exts = settings.optJSONArray("extensions");
            if (exts == null) {
                exts = new JSONArray();
                settings.put("extensions", exts);
            }
            String dashEntry = targetEntry.startsWith("-") ? targetEntry : ("-" + targetEntry);
            String cleanEntry = dashEntry.substring(1);
            boolean exists = false;
            for (int i = 0; i < exts.length(); i++) {
                String e = exts.optString(i, "").trim();
                if (e.equals(dashEntry)) {
                    exists = true;
                    break;
                } else if (e.equals(cleanEntry)) {
                    exts.put(i, dashEntry);
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                exts.put(dashEntry);
            }
            writeFile(settingsFile, settings.toString(2));
        } catch (Throwable t) {
            Log.w(TAG, "Failed to add disabled extension to settings: " + targetEntry, t);
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
            boolean modified = false;

            JSONArray exts = obj.optJSONArray("extensions");
            if (exts != null) {
                JSONArray newExts = new JSONArray();
                for (int i = 0; i < exts.length(); i++) {
                    String e = exts.optString(i, "").trim();
                    String cleanE = e.startsWith("-") ? e.substring(1).trim() : e;
                    boolean match = cleanE.equals(name) || (rawPkgName != null && cleanE.equals(rawPkgName))
                            || (path != null && (cleanE.equals(path) || path.endsWith(cleanE)));
                    if (!match) {
                        newExts.put(e);
                    } else {
                        modified = true;
                    }
                }
                if (modified) obj.put("extensions", newExts);
            }

            JSONArray pkgs = obj.optJSONArray("packages");
            if (pkgs != null) {
                JSONArray newPkgs = new JSONArray();
                for (int i = 0; i < pkgs.length(); i++) {
                    String p = pkgs.optString(i, "").trim();
                    String cleanP = p.startsWith("-") ? p.substring(1).trim() : p;
                    String cleanName = cleanP.replaceFirst("^npm:", "").split("@")[0];
                    String targetName = (rawPkgName != null ? rawPkgName : name).replaceFirst("^npm:", "").split("@")[0];
                    boolean match = cleanName.equals(targetName);
                    if (!match) {
                        newPkgs.put(p);
                    } else {
                        modified = true;
                    }
                }
                if (modified) obj.put("packages", newPkgs);
            }

            if (modified) {
                writeFile(settingsJson, obj.toString(2));
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 自动双向同步插件列表：
     * 1. 扫描磁盘上安装的扩展
     * 2. 清理 settings.json 中已被外部删除/卸载的不存在扩展（不删除 "-" 前缀条目）
     */
    public static void syncPlugins(Context context) {
        try {
            File rootfs = ProotManager.getRootfsDir(context);
            if (!rootfs.exists()) return;
            File piAgentDir = new File(rootfs, "root/.pi/agent");
            if (!piAgentDir.exists()) piAgentDir.mkdirs();

            // 兼容旧 .disabled 迁移：自动改回 foo 并在 settings 里写成 "-extensions/foo"
            migrateLegacyDisabledExtensions(piAgentDir);

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

            // 扫描磁盘上实际存在的扩展
            Set<String> actualDiskExts = new HashSet<>();
            File extDir = new File(piAgentDir, "extensions");
            if (extDir.exists() && extDir.isDirectory()) {
                File[] extFiles = extDir.listFiles();
                if (extFiles != null) {
                    for (File ef : extFiles) {
                        String name = ef.getName();
                        actualDiskExts.add("extensions/" + name);
                        actualDiskExts.add(name);
                        actualDiskExts.add(ef.getAbsolutePath());
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

            // 同步外面的卸载到里面：移除 settings.json 中磁盘上已不存在的插件
            // 注意：不要把 "-" 前缀的条目当成不存在而删除；使用去除 "-" 前缀后的路径判断存在性
            JSONArray newExts = new JSONArray();
            boolean changed = false;
            for (int i = 0; i < currentExts.length(); i++) {
                String ext = currentExts.optString(i, "").trim();
                if (ext.isEmpty()) continue;
                String cleanExt = ext.startsWith("-") ? ext.substring(1).trim() : ext;
                boolean exists = false;
                if (actualDiskExts.contains(cleanExt)) {
                    exists = true;
                } else if (cleanExt.startsWith("/") && new File(cleanExt).exists()) {
                    exists = true;
                } else if (new File(piAgentDir, cleanExt).exists()) {
                    exists = true;
                } else if (new File(npmNodeModules, cleanExt).exists()) {
                    exists = true;
                } else if (new File(extDir, cleanExt).exists()) {
                    exists = true;
                } else if (cleanExt.startsWith("extensions/") && new File(piAgentDir, cleanExt).exists()) {
                    exists = true;
                } else if (new File(rootfs, "usr/local/lib/node_modules/" + cleanExt).exists()) {
                    exists = true;
                }

                if (exists) {
                    newExts.put(ext); // 保留原有条目（含 "-" 前缀）
                } else {
                    changed = true;
                    Log.i(TAG, "Sync: removed uninstalled extension from settings.json: " + ext);
                }
            }

            // extensions/ 目录下的文件 Pi 会自动发现，sync 不要再把 extensions/*.ts 显式追加到 settings（已存在的条目保持不动）。
            // 删除原先硬编码的 "oh-my-pi" 与 "@bacnh85/" 判断。

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

            ensureAssetExtension(context, extDir, "pimet-android-bridge.ts");
            ensureTerminalExtension(context, extDir);
            ensureAssetExtension(context, extDir, "pimet-browser.ts");
        } catch (Throwable t) {
            Log.w("PiMet.PluginMgr", "Failed to ensure Android bridge extension", t);
        }
    }

    /** 终端插件：从 assets/extensions/pimet-terminal.ts 同步到容器（内容不同才写） */
    private static void ensureTerminalExtension(Context context, File extDir) {
        try (java.io.InputStream in = context.getAssets().open("extensions/pimet-terminal.ts")) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            String code = new String(bo.toByteArray(), StandardCharsets.UTF_8);
            File f = new File(extDir, "pimet-terminal.ts");
            if (!code.equals(readFileRaw(f))) {
                writeFile(f, code);
                Log.i("PiMet.PluginMgr", "pimet-terminal.ts updated in container");
            }
        } catch (Throwable t) {
            Log.w("PiMet.PluginMgr", "Failed to ensure terminal extension", t);
        }
    }

    /** 从 assets/extensions/ 同步一个扩展到容器（内容不同才写） */
    private static void ensureAssetExtension(Context context, File extDir, String name) {
        try (java.io.InputStream in = context.getAssets().open("extensions/" + name)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            String code = new String(bo.toByteArray(), StandardCharsets.UTF_8);
            File f = new File(extDir, name);
            if (!code.equals(readFileRaw(f))) {
                writeFile(f, code);
                Log.i("PiMet.PluginMgr", name + " updated in container");
            }
        } catch (Throwable t) {
            Log.w("PiMet.PluginMgr", "Failed to ensure extension " + name, t);
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

    private static String readFileRaw(File file) {
        if (!file.isFile()) return null;
        try {
            return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
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
        com.xm486.pimet.util.SafeFiles.deleteTree(fileOrDir);
    }
}
