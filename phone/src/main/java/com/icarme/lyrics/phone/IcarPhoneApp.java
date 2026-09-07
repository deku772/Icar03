package com.icarme.lyrics.phone;

import android.app.Application;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;

/**
 * 轻量全局上下文 + 未捕获异常兜底：
 * 崩溃时把堆栈写到 files/crash.log，下次打开主界面可查看，便于排查（无需抓 logcat）。
 */
public class IcarPhoneApp extends Application {

    private static IcarPhoneApp app;

    @Override
    public void onCreate() {
        super.onCreate();
        app = this;
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                saveCrash(thread, throwable);
                /* 保留系统默认行为，避免吞掉崩溃 */
                if (Thread.getDefaultUncaughtExceptionHandler() != null
                        && Thread.getDefaultUncaughtExceptionHandler() != this) {
                    Thread.getDefaultUncaughtExceptionHandler().uncaughtException(thread, throwable);
                } else {
                    Log.e("IcarLyrics", "uncaught", throwable);
                    android.os.Process.killProcess(android.os.Process.myPid());
                }
            }
        });
    }

    public static IcarPhoneApp get() { return app; }

    static void saveCrash(Thread t, Throwable th) {
        try {
            File f = new File(app.getFilesDir(), "crash.log");
            FileWriter fw = new FileWriter(f, false);
            PrintWriter pw = new PrintWriter(fw);
            pw.println("=== crash @" + System.currentTimeMillis() + " thread=" + t.getName());
            th.printStackTrace(pw);
            pw.flush();
            pw.close();
        } catch (Exception ignored) {}
    }

    /** 读取上次崩溃内容（无则 null） */
    static String readCrash() {
        try {
            File f = new File(app.getFilesDir(), "crash.log");
            if (!f.exists()) return null;
            byte[] b = new byte[(int) Math.min(f.length(), 8192)];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = in.read(b);
            in.close();
            return n > 0 ? new String(b, 0, n, "UTF-8") : null;
        } catch (Exception e) {
            return null;
        }
    }
}