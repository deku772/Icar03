package com.icarme.lyrics;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 应用内下载车机端 APK 并安装（纯 SDK，无 androidx）。
 *
 * 背景：原先「检查更新」只 ACTION_VIEW 打开 GitHub 网页，车机上没有可用浏览器/
 * 文件管理器，点「下载」毫无意义。
 * 现改为：下载到 cache → PackageInstaller 会话写入 → 系统安装确认界面。
 * 兼容 Android 9（iCAR 03）与更高版本；Android 8+ 需「安装未知应用」权限。
 */
public final class ApkInstaller {

    public interface Callback {
        void onLog(String line);
        void onDone(boolean ok, String message);
    }

    private ApkInstaller() {}

    public static final String CAR_APK_URL =
            "https://github.com/deku772/Icar03/releases/latest/download/IcarLyrics-Car.apk";
    private static final String MIRROR_PREFIX = "https://ghfast.top/";
    private static final Handler main = new Handler(Looper.getMainLooper());

    public static void downloadAndInstall(Activity activity, String primaryUrl,
                                          AlertDialog dialog, TextView progressView,
                                          Callback cb) {
        new Thread(() -> {
            File out = new File(activity.getCacheDir(), "update/IcarLyrics-Car-update.apk");
            try {
                //noinspection ResultOfMethodCallIgnored
                out.getParentFile().mkdirs();

                boolean ok = fetch(primaryUrl, out, progressView, cb);
                if (!ok) {
                    cb.onLog("主源失败，尝试国内镜像…");
                    ok = fetch(MIRROR_PREFIX + primaryUrl, out, progressView, cb);
                }
                if (!ok || out.length() < 10000) {
                    fail(dialog, cb, out, "下载失败（网络不可用或文件异常）");
                    return;
                }
                cb.onLog("下载完成 " + (out.length() / 1024) + "KB，拉起安装…");
                main.post(() -> installViaSession(activity, out, dialog, cb));
            } catch (Throwable t) {
                fail(dialog, cb, out, "异常: " + t.getMessage());
            }
        }, "apk-install").start();
    }

    private static boolean fetch(String urlStr, File out, TextView progressView, Callback cb) {
        HttpURLConnection c = null;
        InputStream in = null;
        FileOutputStream fos = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(90000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "IcarLyrics-Updater");
            int code = c.getResponseCode();
            if (code != 200) {
                cb.onLog("HTTP " + code + " ← " + shortHost(urlStr));
                return false;
            }
            int total = c.getContentLength();
            in = c.getInputStream();
            fos = new FileOutputStream(out);
            byte[] buf = new byte[64 * 1024];
            int n, read = 0, lastPct = -1;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
                read += n;
                if (total > 0 && progressView != null) {
                    int pct = (int) (read * 100L / total);
                    if (pct != lastPct) {
                        lastPct = pct;
                        final int p = pct, r = read, t = total;
                        main.post(() -> progressView.setText(
                                "下载中 " + p + "%（" + (r / 1024) + "/" + (t / 1024) + "KB）"));
                    }
                }
            }
            fos.flush();
            return out.length() > 10000;
        } catch (Exception e) {
            cb.onLog("下载异常: " + e.getClass().getSimpleName() + " ← " + shortHost(urlStr));
            return false;
        } finally {
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            if (c != null) c.disconnect();
        }
    }

    private static String shortHost(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    /** PackageInstaller 会话安装（不需要 FileProvider / 文件管理器） */
    private static void installViaSession(Activity activity, File apk,
                                          AlertDialog dialog, Callback cb) {
        try {
            if (Build.VERSION.SDK_INT >= 26
                    && !activity.getPackageManager().canRequestPackageInstalls()) {
                try {
                    Intent s = new Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.getPackageName()));
                    s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(s);
                } catch (Exception ignored) {}
                if (dialog != null && dialog.isShowing()) dialog.dismiss();
                cb.onDone(false, "请先允许「安装未知应用」，再点一次检查更新");
                return;
            }

            PackageInstaller pi = activity.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(activity.getPackageName());
            int sessionId = pi.createSession(params);
            PackageInstaller.Session session = pi.openSession(sessionId);
            try (OutputStream os = session.openWrite("base.apk", 0, apk.length());
                 InputStream in = new java.io.FileInputStream(apk)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                session.fsync(os);
            }
            Intent confirm = new Intent(activity, activity.getClass());
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent piIntent = PendingIntent.getActivity(activity, sessionId, confirm, flags);
            session.commit(piIntent.getIntentSender());
            session.close();

            if (dialog != null && dialog.isShowing()) dialog.dismiss();
            cb.onDone(true, "已提交安装，请在系统界面点「安装 / 继续」");
        } catch (Exception e) {
            /* 某些车机 ROM 对 PackageInstaller 限制较严，再试 ACTION_VIEW */
            try {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(Uri.fromFile(apk), "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(i);
                if (dialog != null && dialog.isShowing()) dialog.dismiss();
                cb.onDone(true, "已用文件方式拉起安装");
            } catch (Exception e2) {
                fail(dialog, cb, apk, "安装失败: " + e.getMessage()
                        + " / " + e2.getMessage());
            }
        }
    }

    private static void fail(AlertDialog dialog, Callback cb, File apk, String msg) {
        main.post(() -> {
            if (apk != null) //noinspection ResultOfMethodCallIgnored
                apk.delete();
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
            cb.onDone(false, msg);
        });
    }
}
