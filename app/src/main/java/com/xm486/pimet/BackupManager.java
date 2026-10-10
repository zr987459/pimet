package com.xm486.pimet;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.xm486.pimet.pet.PetMemoryManager;
import com.xm486.pimet.pet.PetRegistry;
import com.xm486.pimet.proot.ProotManager;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * PiMet 完整配置与数据无损迁移管理器 (BackupManager)
 * 支持将当前环境的全部聊天记忆、子代理、插件生态、端口设置及应用偏好无损打包为 .zip，
 * 并支持在新安装的 PiMet 中一键解包还原，实现完全无损迁移。
 */
public final class BackupManager {
    private static final String TAG = "PiMet.BackupManager";

    public interface ProgressListener {
        void onProgress(int percent, String stage, String logLine);
    }

    public interface BackupCallback {
        void onResult(boolean success, File file, String message);
    }

    public interface RestoreCallback {
        void onResult(boolean success, int restoredFilesCount, String message);
    }

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private BackupManager() {}

    /**
     * 从系统文件选择器返回的 Uri 中导入并无损还原备份包
     */
    public static void importBackupFromUri(Context context, Uri uri, ProgressListener progressListener, RestoreCallback callback) {
        if (context == null || uri == null) {
            if (callback != null) callback.onResult(false, 0, "参数无效");
            return;
        }

        new Thread(() -> {
            File tempFile = null;
            try {
                if (progressListener != null) {
                    MAIN_HANDLER.post(() -> progressListener.onProgress(2, "正在准备读取备份包...", "从外部存储流式复制..."));
                }

                File cacheDir = context.getCacheDir();
                tempFile = new File(cacheDir, "import_temp_" + System.currentTimeMillis() + ".zip");

                try (java.io.InputStream is = context.getContentResolver().openInputStream(uri);
                     FileOutputStream fos = new FileOutputStream(tempFile)) {
                    if (is == null) {
                        throw new java.io.IOException("无法打开所选文件的输入流");
                    }
                    byte[] buf = new byte[16384];
                    int len;
                    while ((len = is.read(buf)) != -1) {
                        fos.write(buf, 0, len);
                    }
                    fos.flush();
                }

                final File fileToImport = tempFile;
                importBackup(context, fileToImport, progressListener, (success, restoredFilesCount, message) -> {
                    try {
                        if (fileToImport.exists()) fileToImport.delete();
                    } catch (Throwable ignored) {}
                    if (callback != null) {
                        callback.onResult(success, restoredFilesCount, message);
                    }
                });
            } catch (Throwable t) {
                Log.e(TAG, "importBackupFromUri failed", t);
                if (tempFile != null && tempFile.exists()) {
                    try { tempFile.delete(); } catch (Throwable ignored) {}
                }
                if (progressListener != null) {
                    MAIN_HANDLER.post(() -> progressListener.onProgress(0, "读取失败", t.getMessage()));
                }
                if (callback != null) {
                    MAIN_HANDLER.post(() -> callback.onResult(false, 0, "无法读取所选备份包: " + t.getMessage()));
                }
            }
        }).start();
    }

    /**
     * 获取推荐的备份文件导出存储目录
     */
    public static File getExportDirectory(Context context) {
        File dir = null;
        try {
            dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dir != null && (dir.exists() || dir.mkdirs())) {
                return dir;
            }
        } catch (Throwable ignored) {}

        try {
            dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir != null && (dir.exists() || dir.mkdirs())) {
                return dir;
            }
        } catch (Throwable ignored) {}

        return context.getFilesDir();
    }

    /**
     * 自选导出选项配置 (ExportOptions)
     */
    public static class ExportOptions {
        public boolean includeSharedPrefs = true;      // 应用与偏好设置 (SharedPreferences)
        public boolean includeSessions = true;         // 会话与聊天历史 (sessions)
        public boolean includeSubAgents = true;        // 子代理配置与规则 (agents)
        public boolean includeSkillsAndPlugins = true; // 技能与插件扩展 (skills, npm, extensions)
        public boolean includeSettingsAndAuth = true;  // 模型与全局设置 (settings.json, auth.json)
        public boolean includeCustomPets = true;       // 自定义桌宠皮肤与资源 (custom_pets)
        public boolean includePiCwd = true;            // 用户工作区工程目录 (root/pi-cwd)
        public final Set<String> selectedPiCwdSubDirs = new HashSet<>(); // 指定的工作区子工程 (空则包含全部)
        public final List<String> extraCustomDirs = new ArrayList<>();   // 额外自选的容器内目录路径
        public File targetOutFile = null;

        public ExportOptions() {}
    }

    /**
     * 生成标准备份包文件名
     */
    public static String generateBackupFileName() {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        return "PiMet-Backup-" + timeStamp + ".zip";
    }

    /**
     * 执行全量配置、聊天记录、插件生态、子代理及设置打包导出 (默认全量)
     */
    public static void exportBackup(Context context, File targetFile,
                                    ProgressListener progressListener,
                                    BackupCallback callback) {
        ExportOptions options = new ExportOptions();
        options.targetOutFile = targetFile;
        exportBackup(context, options, progressListener, callback);
    }

    /**
     * 支持自选内容与自选目录的细粒度备份导出
     */
    public static void exportBackup(Context context, ExportOptions options,
                                    ProgressListener progressListener,
                                    BackupCallback callback) {
        final ExportOptions opts = options != null ? options : new ExportOptions();
        new Thread(() -> {
            File outFile = opts.targetOutFile;
            if (outFile == null) {
                File dir = getExportDirectory(context);
                outFile = new File(dir, generateBackupFileName());
            }

            try {
                if (progressListener != null) {
                    final String outName = outFile.getName();
                    MAIN_HANDLER.post(() -> progressListener.onProgress(5, "正在准备打包环境...", "创建备份目标文件: " + outName));
                }

                File parent = outFile.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();

                int totalCount = 0;
                try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(outFile)))) {

                    // 1. 写入 manifest.json 元数据
                    if (progressListener != null) {
                        MAIN_HANDLER.post(() -> progressListener.onProgress(10, "生成迁移清单元数据...", "manifest.json"));
                    }
                    JSONObject manifest = new JSONObject();
                    manifest.put("app", "PiMet");
                    manifest.put("version", "1.4.6");
                    manifest.put("format_version", 2);
                    manifest.put("export_time", System.currentTimeMillis());
                    manifest.put("export_date", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));

                    JSONObject optJson = new JSONObject();
                    optJson.put("includeSharedPrefs", opts.includeSharedPrefs);
                    optJson.put("includeSessions", opts.includeSessions);
                    optJson.put("includeSubAgents", opts.includeSubAgents);
                    optJson.put("includeSkillsAndPlugins", opts.includeSkillsAndPlugins);
                    optJson.put("includeSettingsAndAuth", opts.includeSettingsAndAuth);
                    optJson.put("includeCustomPets", opts.includeCustomPets);
                    optJson.put("includePiCwd", opts.includePiCwd);
                    if (!opts.selectedPiCwdSubDirs.isEmpty()) {
                        optJson.put("selectedPiCwdSubDirs", new org.json.JSONArray(opts.selectedPiCwdSubDirs));
                    }
                    if (!opts.extraCustomDirs.isEmpty()) {
                        optJson.put("extraCustomDirs", new org.json.JSONArray(opts.extraCustomDirs));
                    }
                    manifest.put("options", optJson);

                    byte[] manifestBytes = manifest.toString(2).getBytes(StandardCharsets.UTF_8);
                    ZipEntry mEntry = new ZipEntry("manifest.json");
                    zos.putNextEntry(mEntry);
                    zos.write(manifestBytes);
                    zos.closeEntry();
                    totalCount++;

                    // 2. 打包 Android SharedPreferences 全部设置与参数
                    if (opts.includeSharedPrefs) {
                        if (progressListener != null) {
                            MAIN_HANDLER.post(() -> progressListener.onProgress(20, "正在打包核心偏好设置 (SharedPreferences)...", "ports, model keys, pet parameters..."));
                        }

                        File dataDir = new File(context.getApplicationInfo().dataDir);
                        File spDir = new File(dataDir, "shared_prefs");
                        if (spDir.exists() && spDir.isDirectory()) {
                            File[] spFiles = spDir.listFiles();
                            if (spFiles != null) {
                                for (File spf : spFiles) {
                                    if (spf.isFile() && spf.getName().endsWith(".xml")) {
                                        writeZipEntry(zos, spf, "shared_prefs/" + spf.getName());
                                        totalCount++;
                                        if (progressListener != null) {
                                            final String fName = spf.getName();
                                            MAIN_HANDLER.post(() -> progressListener.onProgress(25, "打包设置项: " + fName, "shared_prefs/" + fName));
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 3. 打包 Linux 容器内部 ~/.pi/agent 数据 (聊天记忆、子代理、技能、扩展、模型)
                    File rootfs = ProotManager.getRootfsDir(context);
                    File piAgentDir = new File(rootfs, "root/.pi/agent");
                    boolean needPiAgent = opts.includeSessions || opts.includeSubAgents || opts.includeSkillsAndPlugins || opts.includeSettingsAndAuth;
                    if (needPiAgent && piAgentDir.exists() && piAgentDir.isDirectory()) {
                        if (progressListener != null) {
                            MAIN_HANDLER.post(() -> progressListener.onProgress(35, "正在扫描 ~/.pi/agent 聊天记忆与插件生态...", piAgentDir.getAbsolutePath()));
                        }

                        Set<String> skipNames = new HashSet<>();
                        skipNames.add(".sqlite-wal");
                        skipNames.add(".sqlite-shm");
                        skipNames.add(".cache");

                        FileFilterPredicate agentFilter = (file, relPath) -> {
                            if (file.isDirectory()) {
                                if (!opts.includeSessions && (relPath.equals("sessions") || relPath.startsWith("sessions/"))) {
                                    return false;
                                }
                                if (!opts.includeSubAgents && (relPath.equals("agents") || relPath.startsWith("agents/") || relPath.equals("subagents") || relPath.startsWith("subagents/"))) {
                                    return false;
                                }
                                if (!opts.includeSkillsAndPlugins && (relPath.equals("skills") || relPath.startsWith("skills/") || relPath.equals("npm") || relPath.startsWith("npm/") || relPath.equals("extensions") || relPath.startsWith("extensions/"))) {
                                    return false;
                                }
                            } else {
                                if (!opts.includeSessions && (relPath.startsWith("sessions/") || relPath.equals("pi-web-session-state.json") || relPath.equals("pi-web-session-index.json"))) {
                                    return false;
                                }
                                if (!opts.includeSubAgents && (relPath.startsWith("agents/") || relPath.startsWith("subagents/"))) {
                                    return false;
                                }
                                if (!opts.includeSkillsAndPlugins && (relPath.startsWith("skills/") || relPath.startsWith("npm/") || relPath.startsWith("extensions/"))) {
                                    return false;
                                }
                                if (!opts.includeSettingsAndAuth && (file.getName().equals("settings.json") || file.getName().equals("auth.json") || file.getName().equals("models.json"))) {
                                    return false;
                                }
                            }
                            return true;
                        };

                        int[] piCount = new int[]{0};
                        zipDirectoryRecursive(zos, piAgentDir, piAgentDir, "pi_agent", skipNames, agentFilter, (curFile) -> {
                            piCount[0]++;
                            if (piCount[0] % 5 == 0 && progressListener != null) {
                                int pct = Math.min(75, 35 + (piCount[0] / 3));
                                final String rel = curFile.getName();
                                MAIN_HANDLER.post(() -> progressListener.onProgress(pct, "正在压缩: " + rel, curFile.getAbsolutePath()));
                            }
                        });
                        totalCount += piCount[0];
                    }

                    // 4. 打包用户工作区工程目录 root/pi-cwd (自选工程子目录或全部)
                    File piCwdDir = new File(rootfs, "root/pi-cwd");
                    if (opts.includePiCwd && piCwdDir.exists() && piCwdDir.isDirectory()) {
                        if (progressListener != null) {
                            MAIN_HANDLER.post(() -> progressListener.onProgress(78, "正在扫描用户工作区工程 (root/pi-cwd)...", piCwdDir.getAbsolutePath()));
                        }
                        int[] cwdCount = new int[]{0};
                        if (opts.selectedPiCwdSubDirs != null && !opts.selectedPiCwdSubDirs.isEmpty()) {
                            for (String subName : opts.selectedPiCwdSubDirs) {
                                File subDir = new File(piCwdDir, subName);
                                if (subDir.exists()) {
                                    zipDirectoryRecursive(zos, subDir, piCwdDir, "pi_cwd", null, null, (cur) -> {
                                        cwdCount[0]++;
                                        if (cwdCount[0] % 5 == 0 && progressListener != null) {
                                            MAIN_HANDLER.post(() -> progressListener.onProgress(82, "正在打包工程: " + cur.getName(), cur.getAbsolutePath()));
                                        }
                                    });
                                }
                            }
                        } else {
                            zipDirectoryRecursive(zos, piCwdDir, piCwdDir, "pi_cwd", null, null, (cur) -> {
                                cwdCount[0]++;
                                if (cwdCount[0] % 5 == 0 && progressListener != null) {
                                    MAIN_HANDLER.post(() -> progressListener.onProgress(82, "正在打包工程: " + cur.getName(), cur.getAbsolutePath()));
                                }
                            });
                        }
                        totalCount += cwdCount[0];
                    }

                    // 5. 打包用户自选的额外容器目录
                    if (opts.extraCustomDirs != null && !opts.extraCustomDirs.isEmpty()) {
                        for (String customPath : opts.extraCustomDirs) {
                            if (TextUtils.isEmpty(customPath)) continue;
                            customPath = customPath.trim();
                            File targetDir;
                            String relInRootfs;
                            if (customPath.startsWith("/root/")) {
                                relInRootfs = customPath.substring(1);
                                targetDir = new File(rootfs, relInRootfs);
                            } else if (customPath.startsWith("/")) {
                                relInRootfs = customPath.substring(1);
                                targetDir = new File(rootfs, relInRootfs);
                            } else {
                                relInRootfs = "root/" + customPath;
                                targetDir = new File(rootfs, relInRootfs);
                            }

                            if (targetDir.exists()) {
                                if (progressListener != null) {
                                    final String dName = targetDir.getName();
                                    MAIN_HANDLER.post(() -> progressListener.onProgress(86, "打包自选目录: " + dName, targetDir.getAbsolutePath()));
                                }
                                int[] extraCount = new int[]{0};
                                String entryBase = "custom_dirs/" + relInRootfs.replace(File.separatorChar, '/');
                                zipDirectoryRecursive(zos, targetDir, targetDir, entryBase, null, null, (cur) -> extraCount[0]++);
                                totalCount += extraCount[0];
                            }
                        }
                    }

                    // 6. 打包自定义桌宠皮肤文件 (custom_pets)
                    File customPetDir = new File(context.getFilesDir(), "custom_pets");
                    if (opts.includeCustomPets && customPetDir.exists() && customPetDir.isDirectory()) {
                        int[] petCount = new int[]{0};
                        zipDirectoryRecursive(zos, customPetDir, customPetDir, "custom_pets", null, null, (cur) -> petCount[0]++);
                        totalCount += petCount[0];
                    }

                    final int snapshotCount = totalCount;
                    if (progressListener != null) {
                        MAIN_HANDLER.post(() -> progressListener.onProgress(95, "正在完成归档封包...", "总计打包文件数: " + snapshotCount));
                    }
                }

                final File resultFile = outFile;
                final int finalCount = totalCount;
                if (progressListener != null) {
                    MAIN_HANDLER.post(() -> progressListener.onProgress(100, "✔ 备份导出成功！", "文件大小: " + String.format(Locale.getDefault(), "%.2f MB", resultFile.length() / 1048576f)));
                }

                MAIN_HANDLER.post(() -> callback.onResult(true, resultFile,
                        "✔ 成功导出 " + finalCount + " 项配置与数据！\n文件大小: " +
                                String.format(Locale.getDefault(), "%.2f MB", resultFile.length() / 1048576f) +
                                "\n路径: " + resultFile.getAbsolutePath()));
            } catch (Throwable t) {
                Log.e(TAG, "exportBackup failed", t);
                if (progressListener != null) {
                    MAIN_HANDLER.post(() -> progressListener.onProgress(0, "导出失败", t.getMessage()));
                }
                MAIN_HANDLER.post(() -> callback.onResult(false, null, "导出备份失败: " + t.getMessage()));
            }
        }).start();
    }

    /**
     * 执行全量配置、聊天记忆、插件生态与子代理无损导入还原
     */
    public static void importBackup(Context context, File zipFile,
                                    ProgressListener progressListener,
                                    RestoreCallback callback) {
        if (context == null || zipFile == null || !zipFile.exists()) {
            if (callback != null) callback.onResult(false, 0, "备份文件无效或不存在");
            return;
        }

        new Thread(() -> {
            try {
                if (progressListener != null) {
                    MAIN_HANDLER.post(() -> progressListener.onProgress(5, "正在校验备份压缩包完整性...", zipFile.getName()));
                }

                boolean hasManifest = false;
                boolean hasSharedPrefs = false;
                boolean hasPiAgent = false;
                boolean hasPiCwd = false;

                try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        String name = entry.getName();
                        if ("manifest.json".equals(name)) hasManifest = true;
                        if (name.startsWith("shared_prefs/")) hasSharedPrefs = true;
                        if (name.startsWith("pi_agent/")) hasPiAgent = true;
                        if (name.startsWith("pi_cwd/")) hasPiCwd = true;
                        if (hasManifest || (hasSharedPrefs && hasPiAgent)) break;
                    }
                }

                if (!hasManifest && !hasSharedPrefs && !hasPiAgent && !hasPiCwd) {
                    MAIN_HANDLER.post(() -> callback.onResult(false, 0, "所选压缩包不是有效的 PiMet 备份迁移包"));
                    return;
                }

                if (progressListener != null) {
                    MAIN_HANDLER.post(() -> progressListener.onProgress(15, "校验通过，正在无损解压并还原数据...", zipFile.getName()));
                }

                File dataDir = new File(context.getApplicationInfo().dataDir);
                File spDir = new File(dataDir, "shared_prefs");
                if (!spDir.exists()) spDir.mkdirs();

                File rootfs = ProotManager.getRootfsDir(context);
                File piAgentDir = new File(rootfs, "root/.pi/agent");
                if (!piAgentDir.exists()) piAgentDir.mkdirs();

                File piCwdDir = new File(rootfs, "root/pi-cwd");
                if (!piCwdDir.exists()) piCwdDir.mkdirs();

                File customPetDir = new File(context.getFilesDir(), "custom_pets");

                int restoredCount = 0;
                byte[] buffer = new byte[8192];

                try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        String name = entry.getName();
                        if (entry.isDirectory()) {
                            continue;
                        }

                        File destFile = null;
                        if (name.startsWith("shared_prefs/")) {
                            String rel = name.substring("shared_prefs/".length());
                            destFile = new File(spDir, rel);
                        } else if (name.startsWith("pi_agent/")) {
                            String rel = name.substring("pi_agent/".length());
                            destFile = new File(piAgentDir, rel);
                        } else if (name.startsWith("custom_pets/")) {
                            String rel = name.substring("custom_pets/".length());
                            destFile = new File(customPetDir, rel);
                        } else if (name.startsWith("pi_cwd/")) {
                            String rel = name.substring("pi_cwd/".length());
                            destFile = new File(piCwdDir, rel);
                        } else if (name.startsWith("custom_dirs/")) {
                            String rel = name.substring("custom_dirs/".length());
                            destFile = new File(rootfs, rel);
                        }

                        if (destFile != null) {
                            File parent = destFile.getParentFile();
                            if (parent != null && !parent.exists()) {
                                parent.mkdirs();
                            }

                            try (FileOutputStream fos = new FileOutputStream(destFile)) {
                                int len;
                                while ((len = zis.read(buffer)) != -1) {
                                    fos.write(buffer, 0, len);
                                }
                            }
                            restoredCount++;

                            if (restoredCount % 5 == 0 && progressListener != null) {
                                int pct = Math.min(85, 20 + (restoredCount / 2));
                                final String destName = destFile.getName();
                                MAIN_HANDLER.post(() -> progressListener.onProgress(pct, "已还原: " + destName, destName));
                            }
                        }
                    }
                }

                if (progressListener != null) {
                    MAIN_HANDLER.post(() -> progressListener.onProgress(90, "正在重载系统配置与同步插件生态...", "PluginManager.syncPlugins..."));
                }

                // 同步内存数据与插件、桌宠记忆
                try {
                    PluginManager.syncPlugins(context);
                    PetMemoryManager.loadMemory(context);
                    PiMetConfig.syncFromContainer(context);
                } catch (Throwable ignored) {}

                // 自愈补齐历史会话与项目工作区物理目录，彻底杜绝 Directory does not exist 错误
                ensureReferencedProjectDirsExist(rootfs);

                final int finalRestored = restoredCount;
                if (progressListener != null) {
                    MAIN_HANDLER.post(() -> progressListener.onProgress(100, "✔ 全部数据已成功还原！", "总计恢复 " + finalRestored + " 个配置文件与数据"));
                }

                MAIN_HANDLER.post(() -> callback.onResult(true, finalRestored,
                        "✔ 成功无损恢复 " + finalRestored + " 项配置、聊天记录、子代理、工作区与插件生态！"));
            } catch (Throwable t) {
                Log.e(TAG, "importBackup failed", t);
                if (progressListener != null) {
                    MAIN_HANDLER.post(() -> progressListener.onProgress(0, "导入失败", t.getMessage()));
                }
                MAIN_HANDLER.post(() -> callback.onResult(false, 0, "导入恢复异常: " + t.getMessage()));
            }
        }).start();
    }

    /**
     * 自愈补齐历史工程与工作区目录，确保 Pi-Web 无论是导入老包还是新包，
     * 都绝不会因为物理目录缺失而报 Directory does not exist.
     */
    public static void ensureReferencedProjectDirsExist(File rootfs) {
        if (rootfs == null || !rootfs.exists()) return;
        try {
            File piCwd = new File(rootfs, "root/pi-cwd");
            if (!piCwd.exists()) piCwd.mkdirs();

            File piAgentDir = new File(rootfs, "root/.pi/agent");
            if (!piAgentDir.exists()) return;

            // 1. 扫描 pi-web-session-state.json 中的 projectOrder
            File stateFile = new File(piAgentDir, "pi-web-session-state.json");
            if (stateFile.exists()) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(stateFile), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                }
                JSONObject stateObj = new JSONObject(sb.toString());
                if (stateObj.has("projectOrder")) {
                    org.json.JSONArray order = stateObj.getJSONArray("projectOrder");
                    for (int i = 0; i < order.length(); i++) {
                        String projPath = order.getString(i);
                        createDirIfRootPath(rootfs, projPath);
                    }
                }
            }

            // 2. 扫描 sessions 目录下的每个历史会话
            File sessionsDir = new File(piAgentDir, "sessions");
            if (sessionsDir.exists() && sessionsDir.isDirectory()) {
                File[] sessionDirs = sessionsDir.listFiles();
                if (sessionDirs != null) {
                    for (File sDir : sessionDirs) {
                        if (sDir.isDirectory()) {
                            String dName = sDir.getName();
                            if (dName.startsWith("--root-") && dName.endsWith("--")) {
                                String inner = dName.substring(2, dName.length() - 2);
                                File[] jsonls = sDir.listFiles((dir, name) -> name.endsWith(".jsonl"));
                                boolean resolved = false;
                                if (jsonls != null && jsonls.length > 0) {
                                    for (File jf : jsonls) {
                                        try (BufferedReader jbr = new BufferedReader(new InputStreamReader(new FileInputStream(jf), StandardCharsets.UTF_8))) {
                                            String firstLine = jbr.readLine();
                                            if (firstLine != null && firstLine.contains("\"cwd\":")) {
                                                JSONObject jObj = new JSONObject(firstLine);
                                                if (jObj.has("cwd")) {
                                                    createDirIfRootPath(rootfs, jObj.getString("cwd"));
                                                    resolved = true;
                                                    break;
                                                }
                                            }
                                        } catch (Throwable ignored) {}
                                    }
                                }
                                if (!resolved && inner.startsWith("root-pi-cwd-")) {
                                    String sub = inner.substring("root-pi-cwd-".length());
                                    File f = new File(piCwd, sub);
                                    if (!f.exists()) f.mkdirs();
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensureReferencedProjectDirsExist error", t);
        }
    }

    private static void createDirIfRootPath(File rootfs, String path) {
        if (path == null) return;
        path = path.trim();
        if (path.startsWith("/root/")) {
            String rel = path.substring("/root/".length());
            File d = new File(rootfs, "root/" + rel);
            if (!d.exists()) d.mkdirs();
        } else if (path.equals("/root")) {
            File d = new File(rootfs, "root");
            if (!d.exists()) d.mkdirs();
        }
    }

    /**
     * 通过 Android 系统分享对话框将备份压缩包发送至其他设备或应用
     */
    public static void shareBackupFile(Context context, File zipFile) {
        if (zipFile == null || !zipFile.exists()) {
            Toast.makeText(context, "备份文件不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri uri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", zipFile);
            } else {
                uri = Uri.fromFile(zipFile);
            }

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("application/zip");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "PiMet 完整配置与数据备份包");
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            context.startActivity(Intent.createChooser(shareIntent, "发送 / 迁移 PiMet 备份压缩包"));
        } catch (Throwable t) {
            Log.e(TAG, "shareBackupFile error", t);
            Toast.makeText(context, "调用系统分享失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private static void writeZipEntry(ZipOutputStream zos, File file, String entryPath) throws Exception {
        ZipEntry entry = new ZipEntry(entryPath);
        zos.putNextEntry(entry);
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) != -1) {
                zos.write(buf, 0, len);
            }
        }
        zos.closeEntry();
    }

    public interface FileFilterPredicate {
        boolean shouldInclude(File file, String relativePath);
    }

    private interface FileVisitCallback {
        void onFileVisited(File file);
    }

    private static void zipDirectoryRecursive(ZipOutputStream zos, File currentFile, File rootDir,
                                               String basePrefix, Set<String> skipNames,
                                               FileFilterPredicate filter,
                                               FileVisitCallback callback) throws Exception {
        if (currentFile == null || !currentFile.exists()) return;

        String name = currentFile.getName();
        if (skipNames != null) {
            for (String s : skipNames) {
                if (name.contains(s)) return;
            }
        }

        String rel = currentFile.getAbsolutePath().substring(rootDir.getAbsolutePath().length());
        if (rel.startsWith(File.separator)) rel = rel.substring(1);
        String relNorm = rel.replace(File.separatorChar, '/');

        if (filter != null && !relNorm.isEmpty() && !filter.shouldInclude(currentFile, relNorm)) {
            return;
        }

        if (currentFile.isDirectory()) {
            File[] children = currentFile.listFiles();
            if (children != null) {
                for (File child : children) {
                    zipDirectoryRecursive(zos, child, rootDir, basePrefix, skipNames, filter, callback);
                }
            }
        } else if (currentFile.isFile()) {
            String pathPart = relNorm.isEmpty() ? currentFile.getName() : relNorm;
            String entryPath = TextUtils.isEmpty(basePrefix) ? pathPart : (basePrefix + "/" + pathPart);
            writeZipEntry(zos, currentFile, entryPath);
            if (callback != null) callback.onFileVisited(currentFile);
        }
    }
}
