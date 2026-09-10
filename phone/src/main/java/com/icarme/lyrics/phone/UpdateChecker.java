package com.icarme.lyrics.phone;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** 手机端 GitHub Release 检查更新 */
public final class UpdateChecker {

    private static final String TAG = "IcarLyrics.PhoneUpd";
    private static final String API =
            "https://api.github.com/repos/deku772/Icar03/releases/latest";
    public static final String RELEASES = "https://github.com/deku772/Icar03/releases/latest";

    public interface Callback {
        void onResult(boolean hasUpdate, String latestTag, String htmlUrl, String error);
    }

    public static void checkAsync(String currentVersion, Callback cb) {
        new Thread(() -> {
            String latest = null, url = null, err = null;
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
                if (code >= 400) err = "HTTP " + code;
                else {
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

    public static void checkAndPrompt(Context ctx, String currentVersion, boolean silence) {
        checkAsync(currentVersion, (has, tag, url, err) -> {
            if (has) {
                new AlertDialog.Builder(new android.view.ContextThemeWrapper(ctx,
                        android.R.style.Theme_Material_Dialog))
                        .setTitle("发现新版本 " + tag)
                        .setMessage("当前 " + currentVersion + " → 最新 " + tag)
                        .setPositiveButton("下载", (d, w) -> {
                            try {
                                ctx.startActivity(new Intent(Intent.ACTION_VIEW,
                                        Uri.parse(url.isEmpty() ? RELEASES : url)));
                            } catch (Exception ignored) {}
                        })
                        .setNegativeButton("稍后", null)
                        .show();
            } else if (!silence) {
                if (err != null) Toast.makeText(ctx, "检查更新失败: " + err, Toast.LENGTH_SHORT).show();
                else Toast.makeText(ctx, "已是最新版 " + currentVersion, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
