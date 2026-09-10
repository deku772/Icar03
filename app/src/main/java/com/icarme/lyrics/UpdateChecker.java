package com.icarme.lyrics;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** 从 GitHub Releases API 检查最新版本；有新版本弹窗打开下载页。 */
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

    /** 检查并弹窗（silence=true 无更新/失败不打扰） */
    public static void checkAndPrompt(Context ctx, String currentVersion, boolean silence) {
        checkAsync(ctx, currentVersion, (has, tag, url, err) -> {
            if (has) {
                try {
                    AlertDialog.Builder b = new AlertDialog.Builder(
                            new android.view.ContextThemeWrapper(ctx,
                                    android.R.style.Theme_Material_Dialog))
                            .setTitle("发现新版本 " + tag)
                            .setMessage("当前 " + currentVersion + " → 最新 " + tag
                                    + "\n是否打开下载页？")
                            .setPositiveButton("下载", (d, w) -> {
                                try {
                                    Intent i = new Intent(Intent.ACTION_VIEW,
                                            Uri.parse(url.isEmpty() ? RELEASES : url));
                                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    ctx.startActivity(i);
                                } catch (Exception ignored) {}
                            })
                            .setNegativeButton("稍后", null);
                    AlertDialog dlg = b.create();
                    if (Build.VERSION.SDK_INT >= 26) {
                        dlg.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                    }
                    dlg.show();
                } catch (Throwable t) {
                    /* 悬浮窗场景可能无法弹 Activity 对话框，降级为系统浏览器 */
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW,
                                Uri.parse(url.isEmpty() ? RELEASES : url));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(i);
                    } catch (Exception ignored) {}
                }
            } else if (!silence && err != null) {
                Log.i(TAG, "check: " + err);
            }
        });
    }

    public static final String RELEASES = "https://github.com/deku772/Icar03/releases/latest";
}
