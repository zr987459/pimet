package com.xm486.pimet;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;

public class PiMetApp extends Application {

    private static final String TAG = "PiMetApp";
    private static Context instance;

    public static Context getContext() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        // 全局线性图标：为每个 Activity 的视图树安装 emoji→Lucide 线性图标渲染
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(android.app.Activity a, android.os.Bundle b) {}
            @Override public void onActivityStarted(android.app.Activity a) {
                try {
                    com.xm486.pimet.ui.Icons.install(a.getWindow().getDecorView());
                } catch (Throwable t) {
                    Log.w(TAG, "Icons install failed", t);
                }
            }
            @Override public void onActivityResumed(android.app.Activity a) {}
            @Override public void onActivityPaused(android.app.Activity a) {}
            @Override public void onActivityStopped(android.app.Activity a) {}
            @Override public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) {}
            @Override public void onActivityDestroyed(android.app.Activity a) {}
        });

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e(TAG, "Uncaught exception in thread " + thread.getName(), throwable);
            try {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                throwable.printStackTrace(pw);
                String stackTrace = sw.toString();

                File crashFile = new File(getExternalFilesDir(null), "crash.log");
                try (FileWriter fw = new FileWriter(crashFile, true)) {
                    fw.write("=== CRASH at " + System.currentTimeMillis() + " ===\n");
                    fw.write(stackTrace);
                    fw.write("\n\n");
                }

                new Handler(Looper.getMainLooper()).post(() -> {
                    Toast.makeText(getApplicationContext(), "PiMet 异常: " + throwable.getMessage(), Toast.LENGTH_LONG).show();
                });
                Thread.sleep(1500);
            } catch (Throwable ignored) {}
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(10);
        });
    }
}
