package com.xm486.pimet;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

/**
 * 设备特权与高级权限管理器
 * 涵盖：Root 探测与授权、Shizuku 特权、全盘文件管理、系统电池优化白名单、剪贴板无缝同步
 */
public class DevicePrivilegeManager {
    private static final String TAG = "DevicePrivilegeManager";
    public static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";

    // 常见 su 路径探测
    private static final String[] SU_PATHS = {
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/data/local/su"
    };

    /**
     * 检测设备是否存在 su 二进制文件 (Root 环境)
     */
    public static boolean isRootBinaryPresent() {
        for (String path : SU_PATHS) {
            if (new File(path).exists()) return true;
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String p : pathEnv.split(":")) {
                if (new File(p, "su").exists()) return true;
            }
        }
        return false;
    }

    /**
     * 申请并测试 Root 权限
     * @return true 若成功获取 Root (uid=0)
     */
    public static boolean requestRootAccess() {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                if (line != null && line.contains("uid=0")) {
                    return true;
                }
            }
            int code = process.waitFor();
            return code == 0;
        } catch (Throwable t) {
            Log.w(TAG, "Request root failed: " + t.getMessage());
            return false;
        } finally {
            if (process != null) {
                try { process.destroy(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * 检测是否安装了 Shizuku / Sui 管理器
     */
    public static boolean isShizukuInstalled(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            pm.getPackageInfo(SHIZUKU_PACKAGE, 0);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 打开 Shizuku 管理器
     */
    public static boolean openShizukuApp(Context context) {
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(SHIZUKU_PACKAGE);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * 检测是否已拥有 Android 11+ 全盘所有文件读写权限
     */
    public static boolean isAllFilesAccessGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return true;
    }

    /**
     * 打开全盘所有文件访问权限授权界面
     */
    public static void requestAllFilesAccess(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + context.getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Throwable t) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * 检测是否已获得系统电池优化豁免 (无限制后台运行)
     */
    public static boolean isIgnoringBatteryOptimizations(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
            } catch (Throwable ignored) {}
        }
        return true;
    }
}
