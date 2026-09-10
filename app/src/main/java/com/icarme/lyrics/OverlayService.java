package com.icarme.lyrics;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
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

import java.util.List;

/**
 * 悬浮窗渲染服务：
 * TYPE_APPLICATION_OVERLAY + WebView 加载 assets/lyrics_overlay.html。
 * 对外提供 push(jsonString) 供 BleService 转发数据。
 *
 * v2.1 歌词同步：轮询车机蓝牙栈 MediaSession（com.android.bluetooth）的
 * 播放进度作为可信时间轴——它与车机实际发声对齐，消除手机 A2DP 推流
 * +车机缓冲导致的歌词慢几秒（官方 03歌词 同款思路）。
 * BLE 进度包降级为兜底：本地会话未授权/不可用时仍用手机推来的进度。
 */
public class OverlayService extends Service {

    public static volatile boolean running = false;
    private static OverlayService instance;

    private WindowManager wm;
    private WebView web;
    private Handler ui;
    private boolean pageReady;

    /* ---- 本地可信时间轴（车机蓝牙栈进度） ---- */
    private long localPosMs = -1;      /* 最近一次蓝牙栈 position 快照 */
    private long localSyncAt = 0;      /* 快照时刻（本机时钟） */
    private boolean localPlaying = false;
    private boolean localReady = false;
    private long lyricsAt = 0;         /* 最近一次换歌时刻（换歌保护窗，防旧快照污染新曲） */
    private Runnable localTimelineTask;

    /* 歌词时间偏移（用户可调，持久化）：正值=提前，负值=延后，作用于最终 positionMs */
    private static final String PREFS = "icarlyrics";
    private static final String KEY_OFFSET_MS = "lyrics_offset_ms";
    private static final int OFFSET_LIMIT_MS = 5000;
    private static final int OFFSET_STEP_MS = 250;

    /** 当前偏移（ms），主界面调节后持久化 */
    static int getOffsetMs() {
        return IcarApp.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_OFFSET_MS, 0);
    }

    /** 调整偏移并持久化（±5s 夹取），同时立即下发给渲染器实时重定位 */
    static int adjustOffsetMs(int deltaMs) {
        int v = Math.max(-OFFSET_LIMIT_MS, Math.min(OFFSET_LIMIT_MS, getOffsetMs() + deltaMs));
        IcarApp.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_OFFSET_MS, v).apply();
        pushOffset(v);
        return v;
    }

    /** 向渲染器下发当前偏移（服务未运行时静默跳过，启动时同步一次） */
    static void pushOffset(int valueMs) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("type", "cmd");
            o.put("action", "setOffset");
            o.put("value", valueMs);
            push(o.toString());
        } catch (Exception ignored) {}
    }

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
        startLocalTimeline();
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        stopLocalTimeline();
        ui.post(this::hideOverlay);
        stopForeground(true);
        super.onDestroy();
    }

    /* ---- BleService 调用入口 ---- */
    public static void push(String json) {
        OverlayService svc = instance;
        if (svc != null) svc.dispatch(json);
    }

    /* ---------------- 本地时间轴轮询（1s） ---------------- */

    private void startLocalTimeline() {
        /* 覆盖安装后系统可能不重绑监听服务，主动请求一次 */
        try {
            android.service.notification.NotificationListenerService
                    .requestRebind(CarMediaListener.COMPONENT);
        } catch (Throwable ignored) {}
        localTimelineTask = new Runnable() {
            @Override public void run() {
                pollLocalTimeline();
                ui.postDelayed(this, 1000);
            }
        };
        ui.post(localTimelineTask);
    }

    private void stopLocalTimeline() {
        if (localTimelineTask != null) {
            ui.removeCallbacks(localTimelineTask);
            localTimelineTask = null;
        }
    }

    /** 读车机蓝牙栈会话进度；只信 PLAYING 快照（PAUSED 残留会话的固定 position
     *  会把歌词钉死不滚动）。快照 3.5s 过期自动降级 BLE 进度。 */
    private void pollLocalTimeline() {
        try {
            MediaSessionManager msm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            if (msm == null) return;
            List<MediaController> list = msm.getActiveSessions(CarMediaListener.COMPONENT);
            for (MediaController c : list) {
                String pkg = c.getPackageName();
                if (pkg == null || !pkg.contains("bluetooth")) continue;
                PlaybackState ps = c.getPlaybackState();
                if (ps == null) continue;
                if (ps.getState() != PlaybackState.STATE_PLAYING) continue;
                localPosMs = Math.max(0, ps.getPosition());
                localSyncAt = System.currentTimeMillis();
                localPlaying = true;
                localReady = true;
                return;   /* 取第一个蓝牙栈会话即可 */
            }
        } catch (Exception e) {
            /* SecurityException：通知使用权未授权（adb cmd notification allow_listener） */
            localReady = false;
        }
    }

    /** 本地时间轴插值当前位置（快照过期 3.5s 视为不可信） */
    private long localNow() {
        if (!localReady) return -1;
        long dt = System.currentTimeMillis() - localSyncAt;
        if (dt > 3500) return -1;
        return localPlaying ? localPosMs + dt : localPosMs;
    }

private void dispatch(final String json) {
        ui.post(() -> {
            if (web == null || !pageReady) return;
            try {
                JSONObject obj = new JSONObject(json);
                String type = obj.optString("type", "");
                String payload = json;
                if ("progress".equals(type)) {
                    /* 进度包：本地蓝牙栈时间轴可信时覆盖 positionMs/playing，
                     * 消除 A2DP 传输+缓冲延迟。用户偏移不在 Java 侧叠加——
                     * 由渲染层 setOffset 实时应用（暂停/调节立即生效）。
                     * 换歌后 4s 内且本地快照早于换歌时刻 → 不覆盖（旧曲快照） */
                    long now = System.currentTimeMillis();
                    long ln = (now - lyricsAt > 4000 || localSyncAt > lyricsAt) ? localNow() : -1;
                    if (ln >= 0) {
                        obj.put("positionMs", ln);
                        obj.put("playing", localPlaying);
                        payload = obj.toString();
                    }
                } else if ("lyrics".equals(type)) {
                    lyricsAt = System.currentTimeMillis();
                }
                String js;
                if ("lyrics".equals(type)) {
                    js = "IcarJS.onLyrics(" + JSONObject.quote(json) + ")";
                } else if ("progress".equals(type)) {
                    js = "IcarJS.onProgress(" + JSONObject.quote(payload) + ")";
                } else if ("cmd".equals(type)) {
                    js = "IcarJS.onCmd(" + JSONObject.quote(json) + ")";
                } else if ("conn".equals(type)) {
                    /* BLE 连接状态变化（BleService 转发） */
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
                if (p >= 100 && !pageReady) {
                    pageReady = true;
                    pushOffset(getOffsetMs());   /* 页面就绪：同步用户偏移 */
                }
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
                    .setSmallIcon(R.drawable.ic_stat_lyrics)
                    .setContentTitle("IcarLyrics 运行中")
                    .setContentText("等待手机端 BLE 推送歌词")
                    .setOngoing(true)
                    .build();
        } else {
            n = new Notification.Builder(this)
                    .setSmallIcon(R.drawable.ic_stat_lyrics)
                    .setContentTitle("IcarLyrics 运行中")
                    .setOngoing(true)
                    .build();
        }
        startForeground(1, n);
    }
}
