package com.icarme.lyrics.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONObject;

/**
 * 手机端总调度（前台服务）：
 *   播放监控 → 换歌取词 → BLE 推歌词 → 定时推进度
 */
public class PlaybackService extends Service implements NotificationListener.Callback {

    private static final String TAG = "IcarLyrics.Phone";
    private static final long PROGRESS_INTERVAL_MS = 800;

    public static volatile boolean running = false;
    private static PlaybackService instance;

    static PlaybackService instance() { return instance; }

    /** 主界面选择设备入口：服务运行中直接生效，否则仅持久化等待服务启动 */
    static void remoteSelectDevice(String address) {
        PlaybackService svc = instance;
        if (svc != null) {
            svc.selectDevice(address);
        } else {
            android.content.Context ctx = IcarPhoneApp.get();
            if (ctx != null) {
                ctx.getSharedPreferences("icarlyrics_phone", MODE_PRIVATE)
                        .edit().putString("target_device", address).apply();
            }
        }
    }

    private final BleClient ble = new BleClient(this, this::onBleState);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LyricsFetcher fetcher = new LyricsFetcher();

    private String curKey = "";        /* track|artist 去重 */
    private long curDuration = 0;
    private long lastPos = 0;
    private boolean lastPlaying = false;
    private boolean fetchInFlight = false;
    private Runnable progressTask;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        running = true;
        try {
            startForeground();
        } catch (Exception e) {
            /* 通知通道/前台通知异常不应导致服务崩溃 */
            IcarPhoneApp.saveCrash(Thread.currentThread(), e);
        }
        NotificationListener.setCallback(this);
        main.post(() -> {
            try {
                if (ble.getSelectedAddressText() == null) {
                    updateNotification("请先在主界面选择车机");
                } else {
                    ble.connect();
                }
            } catch (Exception e) {
                IcarPhoneApp.saveCrash(Thread.currentThread(), e);
                updateNotification("连接异常: " + e.getMessage());
            }
        });
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        NotificationListener.setCallback(null);
        stopProgressTask();
        ble.disconnect();
        stopForeground(true);
        super.onDestroy();
    }

    /* ---------------- 播放监控回调（主线程） ---------------- */

    @Override
    public void onTrackChanged(String track, String artist, String album, long durationMs) {
        String key = track + "|" + (artist == null ? "" : artist);
        if (key.equals(curKey)) {
            if (durationMs > 0) curDuration = durationMs;
            return;
        }
        curKey = key;
        curDuration = durationMs;
        updateNotification("待推送: " + track);
        if (ble.isConnected()) {
            fetchAndPush(track, artist, album, durationMs);
        }
        /* 未连接时 ble 会自动重连；连接建立后由进度循环补推 */
    }

    @Override
    public void onProgress(long positionMs, boolean playing) {
        lastPos = positionMs;
        lastPlaying = playing;
        startProgressTask();
    }

    /* ---------------- 取词 + 推送 ---------------- */

    private void fetchAndPush(String track, String artist, String album, long durationMs) {
        if (fetchInFlight) return;
        fetchInFlight = true;
        updateNotification("取词中: " + track);
        main.post(() -> new Thread(() -> {
            LyricsFetcher.Result r = fetcher.fetch(track, artist,
                    (int) (durationMs / 1000));
            fetchInFlight = false;
            if (r == null) {
                updateNotification("未找到歌词: " + track);
                return;
            }
            try {
                JSONObject msg = new JSONObject();
                msg.put("type", "lyrics");
                msg.put("track", track);
                msg.put("artist", TextUtils.isEmpty(artist) ? "" : artist);
                msg.put("album", TextUtils.isEmpty(album) ? "" : album);
                msg.put("durationMs", curDuration);
                msg.put("lrc", r.lrc);
                if (r.tlyric != null) msg.put("tlyric", r.tlyric);
                msg.put("source", r.source);
                msg.put("resetProgress", true);
                ble.pushLyrics(msg.toString());
                updateNotification("已推送: " + track + " (" + r.source + ")");
            } catch (Exception e) {
                Log.w(TAG, "push lyrics failed", e);
            }
        }).start());
    }

    /* ---------------- 进度推送循环 ---------------- */

    private synchronized void startProgressTask() {
        if (progressTask != null) return; /* 已在跑 */
        progressTask = new Runnable() {
            @Override
            public void run() {
                if (!ble.isConnected()) {
                    progressTask = null;
                    return;
                }
                try {
                    JSONObject msg = new JSONObject();
                    msg.put("type", "progress");
                    msg.put("positionMs", lastPos);
                    msg.put("playing", lastPlaying);
                    if (curDuration > 0) msg.put("durationMs", curDuration);
                    ble.pushJson(msg.toString());
                } catch (Exception ignored) {}
                main.postDelayed(this, PROGRESS_INTERVAL_MS);
            }
        };
        main.post(progressTask);
    }

    private synchronized void stopProgressTask() {
        if (progressTask != null) {
            main.removeCallbacks(progressTask);
            progressTask = null;
        }
    }

    /* ---------------- 设备选择 ---------------- */

    /** 供主界面调：列出可连接设备（已配对 + 扫描发现） */
    void scanDevices(BleClient.DevicesCallback cb) {
        ble.scanForDevices(cb);
    }

    /** 供主界面调：选择某个设备并连接（记住默认，后续默认直连） */
    void selectDevice(String address) {
        ble.connectTo(address);
    }

    /** 供主界面调：清除已选设备 */
    void clearDevice() {
        ble.clearSelectedDevice();
        updateNotification("未选择车机");
    }

    String getSelectedDeviceName() { return ble.getSelectedName(); }

    private void onBleState(String state, String detail) {
        if ("connected".equals(state) && !curKey.isEmpty()) {
            /* 重连成功：用当前 key 补推一次歌词 */
            String[] parts = curKey.split("\\|", 2);
            fetchAndPush(parts[0], parts.length > 1 ? parts[1] : "", null, curDuration);
        }
        updateNotification("BLE: " + detail);
    }

    /* ---------------- 通知 ---------------- */

    private void startForeground() {
        String chId = "icarlyrics_phone";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(
                chId, "IcarLyrics 推送", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, chId)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("IcarLyrics 手机端运行中")
                .setContentText("等待播放音乐…")
                .setOngoing(true)
                .build();
        startForeground(10, n);
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification n = new Notification.Builder(this, "icarlyrics_phone")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("IcarLyrics 手机端")
                .setContentText(text)
                .setOngoing(true)
                .build();
        try { nm.notify(10, n); } catch (Exception ignored) {}
    }
}
