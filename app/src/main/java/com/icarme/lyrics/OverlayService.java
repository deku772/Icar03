package com.icarme.lyrics;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

/**
 * 悬浮窗渲染服务：
 * TYPE_APPLICATION_OVERLAY + WebView 加载 assets/lyrics_overlay.html。
 * 对外提供 push(jsonString) 供 BleService 转发数据。
 */
public class OverlayService extends Service {

    public static volatile boolean running = false;
    private static OverlayService instance;

    private WindowManager wm;
    private WebView web;
    private Handler ui;
    private boolean pageReady;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        running = true;
        ui = new Handler(Looper.getMainLooper());
        startForeground();
        ui.post(this::showOverlay);
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        ui.post(this::hideOverlay);
        stopForeground(true);
        super.onDestroy();
    }

    /* ---- BleService 调用入口 ---- */
    public static void push(String json) {
        OverlayService svc = instance;
        if (svc != null) svc.dispatch(json);
    }

    private void dispatch(final String json) {
        ui.post(() -> {
            if (web == null || !pageReady) return;
            try {
                JSONObject obj = new JSONObject(json);
                String type = obj.optString("type", "");
                String js;
                if ("lyrics".equals(type)) {
                    js = "IcarJS.onLyrics(" + JSONObject.quote(json) + ")";
                } else if ("progress".equals(type)) {
                    js = "IcarJS.onProgress(" + JSONObject.quote(json) + ")";
                } else if ("cmd".equals(type)) {
                    js = "IcarJS.onCmd(" + JSONObject.quote(json) + ")";
                } else {
                    return;
                }
                web.evaluateJavascript(js, null);
            } catch (Exception ignored) {}
        });
    }

    private void showOverlay() {
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        web = new WebView(this);
        web.setBackgroundColor(0x00000000);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setAllowFileAccess(true);
        web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Bridge(), "NativeBridge");

        int flag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        WindowManager.LayoutParams lp;
        if (Build.VERSION.SDK_INT >= 28) {   /* Android 9+ 全屏异形屏适配 */
            lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    flag, PixelFormat.TRANSLUCENT);
        } else {
            lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_PHONE,
                    flag, PixelFormat.TRANSLUCENT);
        }
        lp.gravity = Gravity.TOP | Gravity.START;
        wm.addView(web, lp);

        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        web.loadUrl("file:///android_asset/lyrics_overlay.html");
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int p) {
                if (p >= 100) pageReady = true;
            }
        });
    }
    private void hideOverlay() {
        if (web != null && wm != null) {
            try { wm.removeView(web); } catch (Exception ignored) {}
            web.destroy();
            web = null;
        }
        pageReady = false;
    }

    /** JS 回传通道（歌词加载完成 / ping 等事件） */
    private class Bridge {
        @JavascriptInterface
        public void post(String json) {
            /* 预留：如需把页面事件回传给 BleService（例如渲染就绪握手） */
        }
    }

    private void startForeground() {
        String chId = "icarlyrics_svc";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    chId, "IcarLyrics 后台", NotificationManager.IMPORTANCE_MIN));
        }
        Notification n;
        if (Build.VERSION.SDK_INT >= 26) {
            n = new Notification.Builder(this, chId)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("IcarLyrics 运行中")
                    .setContentText("等待手机端 BLE 推送歌词")
                    .setOngoing(true)
                    .build();
        } else {
            n = new Notification.Builder(this)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("IcarLyrics 运行中")
                    .setOngoing(true)
                    .build();
        }
        startForeground(1, n);
    }
}
