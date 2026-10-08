package com.xm486.pimet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * PiMet Linux 守护与后台高保活前台服务
 * - 保持 CPU 活跃 (WakeLock) 保证退出前台或熄屏后 Node.js 与 PRoot 不被内核冻结
 * - 保持 WiFi 活跃 (WifiLock) 保证浏览器 Localhost / 局域网访问不断流
 * - 前台常驻通知 (startForeground) 避免被 Android 系统 Low Memory Killer 杀掉
 */
public class PiMetService extends Service {
    private static final String TAG = "PiMetService";
    public static final String ACTION_START = "com.xm486.pimet.START_DAEMON";
    public static final String ACTION_STOP = "com.xm486.pimet.STOP_DAEMON";
    private static final String CHANNEL_ID = "pimet_daemon_keepalive";
    private static final int NOTIF_ID = 20241;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    public static void start(Context context) {
        try {
            Intent intent = new Intent(context, PiMetService.class);
            intent.setAction(ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start PiMetService: " + t.getMessage());
        }
    }

    public static void stop(Context context) {
        try {
            Intent intent = new Intent(context, PiMetService.class);
            intent.setAction(ACTION_STOP);
            context.startService(intent);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to stop PiMetService: " + t.getMessage());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForeground(true);
            releaseLocks();
            stopSelf();
            return START_NOT_STICKY;
        }

        acquireLocks();
        try {
            startForeground(NOTIF_ID, buildNotification());
        } catch (Throwable t) {
            Log.w(TAG, "startForeground error: " + t.getMessage());
        }
        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "PiMet 守护保活",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("保持 Linux 容器与 Pi-Web 在后台及浏览器打开时持续稳定运行");
            channel.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Intent notifyIntent = new Intent(this, MainActivity.class);
        notifyIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notifyIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        int port = PiMetConfig.getWebPort(this);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_nav_home)
                .setContentTitle("PiMet Linux 守护服务运行中")
                .setContentText("端口 " + port + " | PRoot 与 Pi-Web 后台持续在线 (点击切回应用)")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void acquireLocks() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PiMet:DaemonWakeLock");
                    wakeLock.acquire();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "WakeLock acquire error: " + t.getMessage());
        }

        try {
            if (wifiLock == null) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PiMet:DaemonWifiLock");
                    wifiLock.acquire();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "WifiLock acquire error: " + t.getMessage());
        }
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                wakeLock = null;
            }
        } catch (Throwable ignored) {}

        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
                wifiLock = null;
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public void onDestroy() {
        releaseLocks();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
