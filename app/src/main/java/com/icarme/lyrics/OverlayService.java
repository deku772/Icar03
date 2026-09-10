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
    private static final int OFFSET_LIMIT_MS = 15000;   /* ±15s，覆盖 iCAR 缓冲延迟 */
    private static final int OFFSET_STEP_MS = 250;

    /* 自动启动开关：默认开；手动停止置 false，手动启动/ADB START 置 true。开机/升级只在 true 时拉起 */
    private static final String KEY_AUTO_START = "auto_start";
    private static final String KEY_ALIGN = "lyrics_align"; /* left|center|right */

    static boolean isAutoStart() {
        return IcarApp.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_START, true);
    }

    static void setAutoStart(boolean on) {
        IcarApp.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_AUTO_START, on).apply();
    }

    static String getAlign() {
        return IcarApp.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ALIGN, "center");
    }

    static void setAlign(String align) {
        if (!"left".equals(align) && !"center".equals(align) && !"right".equals(align)) return;
        IcarApp.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ALIGN, align).apply();
        pushAlign(align);
    }

    static void pushAlign(String align) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("type", "cmd");
            o.put("action", "setAlign");
            o.put("value", align);
            push(o.toString());
        } catch (Exception ignored) {}
    }

    private static final String KEY_COLOR = "lyrics_color"; /* white|blue|green|amber|pink */

    static String getColor() {
        return IcarApp.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_COLOR, "white");
    }

    static void setColor(String color) {
        if (!"white".equals(color) && !"blue".equals(color) && !"green".equals(color)
                && !"amber".equals(color) && !"pink".equals(color)) return;
        IcarApp.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_COLOR, color).apply();
        pushColor(color);
    }

    static void pushColor(String color) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("type", "cmd");
            o.put("action", "setColor");
            o.put("value", color);
            push(o.toString());
        } catch (Exception ignored) {}
    }

    /** 当前偏移（ms），主界面调节后持久化 */
    static int getOffsetMs() {
        return IcarApp.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_OFFSET_MS, 0);
    }

    /** 调整偏移并持久化（±15s 夹取），同时立即下发给渲染器实时重定位 */
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

    /** 读车机蓝牙栈会话进度；只信 PLAYING 快照。
     *  懒栈陷阱：iCAR 高通栈 state=PLAYING 但 position 长期不刷新（僵尸快照）——
     *  停滞时保留旧快照让插值继续走；停滞超 10s 判定僵尸，放弃本地时间轴走 BLE。 */
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
                long pos = Math.max(0, ps.getPosition());
                long now = System.currentTimeMillis();
                if (localReady && pos == localPosMs) {
                    /* position 停滞：不刷新快照（插值继续由旧快照推进），
                     * 超过 10s 仍未刷新 → 僵尸会话，放弃本地时间轴 */
                    if (now - localSyncAt > 10000) localReady = false;
                    return;
                }
                localPosMs = pos;
                localSyncAt = now;
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
                     * 消除 A2DP 传输+缓冲延迟。两层防御：
                     *  1) 换歌后 4s 内且快照早于换歌时刻 → 不覆盖（旧曲快照）
                     *  2) 本地值与 BLE 进度偏差 > 2.5s → 本地失真（僵尸插值等），降级 BLE
                     * 用户偏移由渲染层 setOffset 实时应用（暂停/调节立即生效）。 */
                    long now = System.currentTimeMillis();
                    long ln = (now - lyricsAt > 4000 || localSyncAt > lyricsAt) ? localNow() : -1;
                    long blePos = obj.optLong("positionMs", 0);
                    if (ln >= 0 && Math.abs(ln - blePos) > 2500) ln = -1;
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
                    pushOffset(getOffsetMs());
                    pushAlign(getAlign());
                    pushColor(getColor());
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
