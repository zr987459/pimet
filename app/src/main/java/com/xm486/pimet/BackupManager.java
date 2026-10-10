package com.xm486.pimet;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
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
import java.util.Date;
import java.util.HashSet;
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
     * 生成标准备份包文件名
     */
    public static String generateBackupFileName() {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        return "PiMet-Backup-" + timeStamp + ".zip";
    }

    /**
     * 执行全量配置、聊天记录、插件生态、子代理及设置打包导出
     */
    public static void exportBackup(Context context, File targetFile,
                                    ProgressListener progressListener,
                                    BackupCallback callback) {
        new Thread(() -> {
            File outFile = targetFile;
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
                    manifest.put("version", "1.3.8");
                    manifest.put("format_version", 1);
                    manifest.put("export_time", System.currentTimeMillis());
                    manifest.put("export_date", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));

                    byte[] manifestBytes = manifest.toString(2).getBytes(StandardCharsets.UTF_8);
                    ZipEntry mEntry = new ZipEntry("manifest.json");
                    zos.putNextEntry(mEntry);
                    zos.write(manifestBytes);
                    zos.closeEntry();
                    totalCount++;

                    // 2. 打包 Android SharedPreferences 全部设置与参数
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

                    // 3. 打包 Linux 容器内部 ~/.pi/agent 数据 (聊天记忆、子代理、技能、扩展、模型)
                    File rootfs = ProotManager.getRootfsDir(context);
                    File piAgentDir = new File(rootfs, "root/.pi/agent");
                    if (piAgentDir.exists() && piAgentDir.isDirectory()) {
                        if (progressListener != null) {
                            MAIN_HANDLER.post(() -> progressListener.onProgress(35, "正在扫描 ~/.pi/agent 聊天记忆与插件生态...", piAgentDir.getAbsolutePath()));
                        }

                        Set<String> skipNames = new HashSet<>();
                        skipNames.add(".sqlite-wal");
                        skipNames.add(".sqlite-shm");
                        skipNames.add(".cache");

                        int[] piCount = new int[]{0};
                        zipDirectoryRecursive(zos, piAgentDir, piAgentDir, "pi_agent", skipNames, (curFile) -> {
                            piCount[0]++;
                            if (piCount[0] % 5 == 0 && progressListener != null) {
                                int pct = Math.min(85, 35 + (piCount[0] / 3));
                                final String rel = curFile.getName();
                                MAIN_HANDLER.post(() -> progressListener.onProgress(pct, "正在压缩: " + rel, curFile.getAbsolutePath()));
                            }
                        });
                        totalCount += piCount[0];
                    }

                    // 4. 检查是否有独立的自定义桌宠皮肤文件 (custom_pets)
                    File customPetDir = new File(context.getFilesDir(), "custom_pets");
                    if (customPetDir.exists() && customPetDir.isDirectory()) {
                        int[] petCount = new int[]{0};
                        zipDirectoryRecursive(zos, customPetDir, customPetDir, "custom_pets", null, (cur) -> petCount[0]++);
                        totalCount += petCount[0];
                    }

                    if (progressListener != null) {
                        MAIN_HANDLER.post(() -> progressListener.onProgress(95, "正在完成归档封包...", "总计打包文件数: " + totalCount));
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
        new Thread(() -> {
            if (zipFile == null || !zipFile.exists() || zipFile.length() < 100) {
                MAIN_HANDLER.post(() -> callback.onResult(false, 0, "备份文件不存在或为空"));
                return;
            }

            try {
                if (progressListener != null) {
                    MAIN_HANDLER.post(() -> progressListener.onProgress(5, "正在验证备份包结构...", zipFile.getName()));
                }

                // 检查是否为合法的 PiMet 备份包
                boolean hasManifest = false;
                boolean hasSharedPrefs = false;
                boolean hasPiAgent = false;

                try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        String name = entry.getName();
                        if ("manifest.json".equals(name)) hasManifest = true;
                        if (name.startsWith("shared_prefs/")) hasSharedPrefs = true;
                        if (name.startsWith("pi_agent/")) hasPiAgent = true;
                        if (hasManifest || (hasSharedPrefs && hasPiAgent)) break;
                    }
                }

                if (!hasManifest && !hasSharedPrefs && !hasPiAgent) {
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

                if (progressListener != null) {
                    MAIN_HANDLER.post(() -> progressListener.onProgress(100, "✔ 全部数据已成功还原！", "总计恢复 " + restoredCount + " 个配置文件与数据"));
                }

                final int finalRestored = restoredCount;
                MAIN_HANDLER.post(() -> callback.onResult(true, finalRestored,
                        "✔ 成功无损恢复 " + finalRestored + " 项配置、聊天记录、子代理与插件生态！"));
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

    private interface FileVisitCallback {
        void onFileVisited(File file);
    }

    private static void zipDirectoryRecursive(ZipOutputStream zos, File currentFile, File rootDir,
                                               String basePrefix, Set<String> skipNames,
                                               FileVisitCallback callback) throws Exception {
        if (currentFile == null || !currentFile.exists()) return;

        String name = currentFile.getName();
        if (skipNames != null) {
            for (String s : skipNames) {
                if (name.contains(s)) return;
            }
        }

        if (currentFile.isDirectory()) {
            File[] children = currentFile.listFiles();
            if (children != null) {
                for (File child : children) {
                    zipDirectoryRecursive(zos, child, rootDir, basePrefix, skipNames, callback);
                }
            }
        } else if (currentFile.isFile()) {
            String rel = currentFile.getAbsolutePath().substring(rootDir.getAbsolutePath().length());
            if (rel.startsWith(File.separator)) rel = rel.substring(1);
            String entryPath = basePrefix + "/" + rel.replace(File.separatorChar, '/');
            writeZipEntry(zos, currentFile, entryPath);
            if (callback != null) callback.onFileVisited(currentFile);
        }
    }
}
