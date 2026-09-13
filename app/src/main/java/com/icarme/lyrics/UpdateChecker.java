package com.icarme.lyrics;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** 从 GitHub Releases API 检查最新版本；有新版本则应用内下载并拉起系统安装。 */
public final class UpdateChecker {

    private static final String TAG = "IcarLyrics.Update";
    private static final String API =
            "https://api.github.com/repos/deku772/Icar03/releases/latest";

    public interface Callback {
        void onResult(boolean hasUpdate, String latestTag, String htmlUrl, String error);
    }

    /** 后台检查；主线程回调 */
    public static void checkAsync(Context ctx, String currentVersion, Callback cb) {
        new Thread(() -> {
            String latest = null;
            String url = null;
            String err = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "IcarLyrics");
                int code = c.getResponseCode();
                BufferedReader br = new BufferedReader(new InputStreamReader(
                        code >= 400 ? c.getErrorStream() : c.getInputStream(),
                        StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                if (code >= 400) {
                    err = "HTTP " + code;
                } else {
                    JSONObject o = new JSONObject(sb.toString());
                    latest = o.optString("tag_name", "");
                    url = o.optString("html_url", "");
                }
            } catch (Throwable t) {
                Log.w(TAG, "check failed", t);
                err = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            }
            final String L = latest, U = url, E = err;
            final boolean has = L != null && !L.isEmpty()
                    && isNewer(L.replaceFirst("^v", ""), currentVersion.replaceFirst("^v", ""));
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                    cb.onResult(has, L == null ? "" : L, U == null ? "" : U, E));
        }).start();
    }

    /** 语义化版本比较：latest > current 则 true */
    static boolean isNewer(String latest, String current) {
        try {
            String[] a = latest.split("[.-]");
            String[] b = current.split("[.-]");
            for (int i = 0; i < 3; i++) {
                int x = i < a.length ? parseInt(a[i]) : 0;
                int y = i < b.length ? parseInt(b[i]) : 0;
                if (x != y) return x > y;
            }
            return false;
        } catch (Throwable t) {
            return !latest.equals(current) && latest.length() > 0;
        }
    }

    private static int parseInt(String s) {
        StringBuilder n = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') break;
            n.append(c);
        }
        return n.length() == 0 ? 0 : Integer.parseInt(n.toString());
    }

    /** 检查并弹窗：有新版本则应用内下载车机端 APK 并拉起系统安装（不再打开浏览器） */
    public static void checkAndPrompt(Context ctx, String currentVersion, boolean silence) {
        checkAsync(ctx, currentVersion, (has, tag, url, err) -> {
            if (has) {
                showInstallDialog(ctx, currentVersion, tag);
            } else if (!silence) {
                String msg = err != null
                        ? "检查失败: " + err
                        : "已是最新版本 " + currentVersion;
                try {
                    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) {}
                Log.i(TAG, "check: " + msg);
            }
        });
    }

    /** 发现新版本：确认后应用内下载 + PackageInstaller 系统安装界面 */
    private static void showInstallDialog(Context ctx, String currentVersion, String tag) {
        if (!(ctx instanceof Activity)) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception ignored) {}
            return;
        }
        Activity act = (Activity) ctx;
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (act.getResources().getDisplayMetrics().density * 20);
        box.setPadding(pad, pad / 2, pad, pad / 2);
        TextView msg = new TextView(act);
        msg.setText("当前 " + currentVersion + " → 最新 " + tag
                + "\n将下载车机端 APK，完成后打开系统安装界面");
        msg.setTextSize(14);
        box.addView(msg);

        AlertDialog pick = new AlertDialog.Builder(
                new android.view.ContextThemeWrapper(act, android.R.style.Theme_Material_Dialog))
                .setTitle("发现新版本 " + tag)
                .setView(box)
                .setNegativeButton("稍后", null)
                .setPositiveButton("下载并安装", null)
                .create();
        if (Build.VERSION.SDK_INT >= 26) {
            pick.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        }
        try {
            pick.show();
            pick.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                pick.dismiss();
                showProgressDialog(act, tag);
            });
        } catch (Throwable t) {
            showProgressDialog(act, tag);
        }
    }

    private static void showProgressDialog(Activity act, String tag) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (act.getResources().getDisplayMetrics().density * 20);
        box.setPadding(pad, pad / 2, pad, pad / 2);
        TextView progress = new TextView(act);
        progress.setText("连接下载源…");
        progress.setTextSize(14);
        box.addView(progress);

        AlertDialog pd = new AlertDialog.Builder(
                new android.view.ContextThemeWrapper(act, android.R.style.Theme_Material_Dialog))
                .setTitle("安装 " + tag)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("取消", null)
                .create();
        if (Build.VERSION.SDK_INT >= 26) {
            pd.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        }
        try { pd.show(); } catch (Throwable ignored) {}

        ApkInstaller.downloadAndInstall(act, ApkInstaller.CAR_APK_URL, pd, progress,
                new ApkInstaller.Callback() {
                    @Override public void onLog(String line) {
                        act.runOnUiThread(() -> progress.setText(line));
                    }
                    @Override public void onDone(boolean ok, String message) {
                        act.runOnUiThread(() -> {
                            if (pd.isShowing()) pd.dismiss();
                            try {
                                android.widget.Toast.makeText(act, message,
                                        android.widget.Toast.LENGTH_LONG).show();
                            } catch (Exception ignored) {}
                        });
                    }
                });
    }

    public static final String RELEASES = "https://github.com/deku772/Icar03/releases/latest";
}
