package com.icarme.lyrics.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 手机端总调度（前台服务）：
 *   播放监控 → 换歌取词 → BLE 推歌词 → 定时推进度
 *
 * v1.5: 媒体轮询逻辑移入本服务。原因：ColorOS 覆盖安装 APK 后不重新绑定
 * NotificationListenerService，旧版轮询挂在 onListenerConnected() 里永远不启动，
 * 表现为媒体检测一直"初始化中"。
 * 现改为：
 *   1) 本服务直接用 MediaSessionManager.getActiveSessions(显式组件名) 读会话
 *      —— 只要用户授权过通知使用权即可，不依赖监听服务被系统绑定
 *   2) 启动时调 NotificationListenerService.requestRebind() 请求系统重绑监听
 *      （重绑成功后 getActiveNotifications 通知兜底才可用）
 */
public class PlaybackService extends Service implements NotificationListener.Callback {

    private static final String TAG = "IcarLyrics.Phone";
    private static final long PROGRESS_INTERVAL_MS = 800;
    private static final long MEDIA_POLL_INTERVAL_MS = 2000;

    public static volatile boolean running = false;
    private static PlaybackService instance;

    static PlaybackService instance() { return instance; }

    /** 读取本机蓝牙 MAC（车机按此直连手机）。 */
    static String getOwnMac() {
        try {
            Context ctx = IcarPhoneApp.get();
            if (ctx == null) return null;
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter a = (bm == null) ? null : bm.getAdapter();
            return (a == null || a.getAddress() == null) ? null : a.getAddress();
        } catch (Exception e) {
            return null;
        }
    }

    /* 注意：ble 必须在 onCreate() 中创建（Service 构造函数中 Context 尚未 attach，
     * getSharedPreferences 会抛 NPE），因此不能在这里用 new BlePeripheral(this, ...) 初始化 */
    private BlePeripheral ble;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LyricsFetcher fetcher = new LyricsFetcher();

    /* ---------------- 媒体监控（v1.5 迁入） ---------------- */

    private MediaSessionManager smm;
    private MediaController activeController;
    private boolean nlPermissionOk = true;   /* 通知使用权是否可用（SecurityException 时置 false） */
    private Runnable mediaPollTask;

    private final MediaController.Callback controllerCb = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            emitTrack(metadata);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            emitProgress(state);
        }
    };

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
            /* 必须在 onCreate() 中创建：此时 Context 已 attach，可安全访问 SharedPreferences */
            ble = new BlePeripheral(this, this::onBleState);
        } catch (Exception e) {
            IcarPhoneApp.saveCrash(Thread.currentThread(), e);
            stopSelf(); /* 核心组件初始化失败，直接停止，避免后续 NPE */
            return;
        }
        try {
            startForeground();
        } catch (Exception e) {
            /* 通知通道/前台通知异常不应导致服务崩溃 */
            IcarPhoneApp.saveCrash(Thread.currentThread(), e);
        }
        mon.bleState = "未启动";
        NotificationListener.setCallback(this);
        startMediaMonitor();
        main.post(() -> {
            try {
                ble.start();   /* GATT 服务端就绪，等车机按 MAC 直连 */
            } catch (Exception e) {
                IcarPhoneApp.saveCrash(Thread.currentThread(), e);
                updateNotification("启动异常: " + e.getMessage());
            }
        });
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        NotificationListener.setCallback(null);
        stopMediaPolling();
        stopProgressTask();
        if (ble != null) ble.stop();
        /* 重置监控快照，避免残留旧状态 */
        mon.track = "";
        mon.artist = "";
        mon.diag = "";
        mon.listenerState = "";
        mon.fetchState = "未取词";
        mon.fetchSource = "";
        mon.lrcPreview = "";
        mon.pushCount = 0;
        mon.progressCount = 0;
        mon.writeAck = 0;
        mon.writeFail = 0;
        mon.bleState = "未连接";
        stopForeground(true);
        super.onDestroy();
    }

    /* ---------------- 媒体监控（轮询 + 回调双保险） ---------------- */

    private void startMediaMonitor() {
        smm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);

        /* 请求系统重新绑定通知监听（覆盖安装后 ColorOS 不自动重绑）。
         * 仅在用户已授权时有效；requestRebind 本身无副作用。 */
        try {
            NotificationListenerService.requestRebind(NotificationListener.COMPONENT);
            Log.i(TAG, "requestRebind issued");
        } catch (Throwable t) {
            Log.w(TAG, "requestRebind failed", t);
        }

        /* 轮询 + 回调双保险：低频轮询兜底，回调实时跟帧 */
        mediaPollTask = new Runnable() {
            @Override
            public void run() {
                try {
                    pollMedia();
                } catch (Exception e) {
                    IcarPhoneApp.saveCrash(Thread.currentThread(), e);
                }
                main.postDelayed(this, MEDIA_POLL_INTERVAL_MS);
            }
        };
        main.post(mediaPollTask);
    }

    private void stopMediaPolling() {
        if (mediaPollTask != null) {
            main.removeCallbacks(mediaPollTask);
            mediaPollTask = null;
        }
        if (activeController != null) {
            try { activeController.unregisterCallback(controllerCb); } catch (Exception ignored) {}
            activeController = null;
        }
    }

    private void pollMedia() {
        /* 上报通知监听绑定状态（监控台可见，判断 requestRebind 是否生效） */
        mon.listenerState = NotificationListener.isListenerConnected()
                ? "已绑定" : "未绑定（已请求重绑）";

        if (smm == null) {
            diag("媒体会话服务不可用");
            return;
        }

        MediaController c = pickController();
        if (c == null && NotificationListener.isListenerConnected()) {
            /* getActiveSessions 空列表时的兜底：扫媒体通知提取 token */
            c = NotificationListener.pickFromNotifications(this);
        }
        if (c == null) return;

        if (c != activeController) {
            if (activeController != null) {
                try { activeController.unregisterCallback(controllerCb); } catch (Exception ignored) {}
            }
            activeController = c;
            c.registerCallback(controllerCb, main);
        }
        /* 每轮都补发一次，防止回调丢失（回调 + 轮询双保险） */
        emitTrack(c.getMetadata());
        PlaybackState ps = c.getPlaybackState();
        if (ps != null) emitProgress(ps);
    }

    /** 会话挑选：正在播放 > 蓝牙栈 > 有元数据（显式组件名，不依赖监听服务绑定） */
    private MediaController pickController() {
        List<MediaController> list;
        try {
            list = smm.getActiveSessions(NotificationListener.COMPONENT);
            if (!nlPermissionOk) {
                nlPermissionOk = true;
                diag("媒体会话读取恢复");
            }
        } catch (SecurityException e) {
            nlPermissionOk = false;
            diag("无权限读媒体会话（点「1. 授权」重新授予通知使用权）");
            return null;
        } catch (Exception e) {
            diag("读会话异常: " + e.getClass().getSimpleName());
            return null;
        }

        List<String> names = new ArrayList<>();
        MediaController playing = null;   /* 正在播放 */
        MediaController btStack = null;   /* 蓝牙栈会话 */
        MediaController anyMeta = null;   /* 有元数据的会话 */

        for (MediaController ctl : list) {
            String pkg = ctl.getPackageName();
            if (pkg == null) continue;
            String shortName = NotificationListener.shortPkg(pkg);
            names.add(shortName + (NotificationListener.isPlaying(ctl) ? "(播放中)" : ""));
            if (playing == null && NotificationListener.isPlaying(ctl)) playing = ctl;
            if (btStack == null && pkg.contains("bluetooth")) btStack = ctl;
            if (anyMeta == null && ctl.getMetadata() != null) anyMeta = ctl;
        }

        if (!names.isEmpty()) {
            diag("会话: " + String.join(", ", names));
        } else {
            diag("无媒体会话（放歌后仍无会话则检查通知使用权）");
        }

        /* 优先级：正在播放 > 蓝牙栈 > 有元数据
         * 注：手机连车机蓝牙听歌时，音频走 A2DP，可能出现 com.android.bluetooth 会话；
         * 手机本地外放时直接选正在播放的会话 */
        MediaController pick = (playing != null) ? playing
                : (btStack != null) ? btStack : anyMeta;
        if (pick != null) {
            String state = NotificationListener.isPlaying(pick) ? "播放中" : "未播放";
            diag("选中: " + NotificationListener.shortPkg(pick.getPackageName()) + " (" + state + ")");
        } else if (!names.isEmpty()) {
            diag("会话均无元数据");
        }
        return pick;
    }

    /* ---------------- 播放监控回调（主线程） ---------------- */

    @Override
    public void onTrackChanged(String track, String artist, String album, long durationMs) {
        if (ble == null) return;
        String key = track + "|" + (artist == null ? "" : artist);
        if (key.equals(curKey)) {
            if (durationMs > 0) curDuration = durationMs;
            return;
        }
        curKey = key;
        curDuration = durationMs;
        mon.track = track;
        mon.artist = artist == null ? "" : artist;
        mon.lastActivity = System.currentTimeMillis();
        updateNotification("待推送: " + track);
        if (ble.isConnected()) {
            fetchAndPush(track, artist, album, durationMs);
        }
        /* 未连接时 ble 会自动重连；连接建立后由 onBleState 补推 */
    }

    @Override
    public void onProgress(long positionMs, boolean playing) {
        lastPos = positionMs;
        lastPlaying = playing;
        startProgressTask();
    }

    @Override
    public void onDiag(String line) {
        diag(line);
    }

    private void diag(String line) {
        /* 媒体检测诊断：仅无曲目时更新通知，避免刷屏 */
        mon.diag = line;
        if (curKey.isEmpty()) {
            updateNotification(line);
        }
    }

    /* ---------------- 数据上报（来自 controllerCb / 轮询补发） ---------------- */

    private void emitTrack(MediaMetadata md) {
        if (md == null) return;
        String track = textOf(md, MediaMetadata.METADATA_KEY_TITLE);
        String artist = textOf(md, MediaMetadata.METADATA_KEY_ARTIST);
        String album = textOf(md, MediaMetadata.METADATA_KEY_ALBUM);
        long dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (track != null && !track.isEmpty()) {
            onTrackChanged(track, artist, album, dur);
        }
    }

    private void emitProgress(PlaybackState ps) {
        if (ps == null) return;
        onProgress(ps.getPosition(), ps.getState() == PlaybackState.STATE_PLAYING);
    }

    private static String textOf(MediaMetadata md, String key) {
        String s = md.getString(key);
        return (s == null || s.equals("<unknown>")) ? null : s;
    }

    /* ---------------- 取词 + 推送 ---------------- */

    /** 全局监控快照（主界面轮询显示）：媒体链路每一步的状态 */
    static final class Monitor {
        volatile String diag = "";          /* 媒体检测诊断 */
        volatile String listenerState = ""; /* 通知监听绑定状态 */
        volatile String track = "";         /* 当前曲目（空=未检测到播放） */
        volatile String artist = "";
        volatile String fetchState = "未取词"; /* 取词中/已获取(lrc)/未找到/未取词 */
        volatile String fetchSource = "";
        volatile String lrcPreview = "";    /* 歌词前几行预览 */
        volatile int pushCount = 0;         /* 成功推送歌词次数 */
        volatile int progressCount = 0;     /* 进度包计数 */
        volatile int writeAck = 0;          /* BLE 写入 ACK 数 */
        volatile int writeFail = 0;         /* BLE 写入失败数 */
        volatile String bleState = "未连接"; /* BLE 状态 */
        volatile long lastActivity = 0;     /* 最后活动时刻 */
    }

    static final Monitor mon = new Monitor();

    private void fetchAndPush(String track, String artist, String album, long durationMs) {
        if (fetchInFlight || ble == null) return;
        fetchInFlight = true;
        mon.track = track;
        mon.artist = artist == null ? "" : artist;
        mon.fetchState = "取词中";
        updateNotification("取词中: " + track);
        main.post(() -> new Thread(() -> {
            LyricsFetcher.Result r = fetcher.fetch(track, artist,
                    (int) (durationMs / 1000));
            fetchInFlight = false;
            if (r == null) {
                mon.fetchState = "未找到";
                mon.lrcPreview = "";
                updateNotification("未找到歌词: " + track);
                return;
            }
            mon.fetchState = "已获取";
            mon.fetchSource = r.source;
            mon.lrcPreview = previewOf(r.lrc);
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
                boolean pushed = ble.pushLyrics(msg.toString());
                if (pushed) {
                    mon.pushCount++;
                    updateNotification("已推送: " + track + " (" + r.source + ")");
                } else {
                    updateNotification("BLE 未就绪，未推送: " + track);
                }
            } catch (Exception e) {
                Log.w(TAG, "push lyrics failed", e);
            }
        }).start());
    }

    /** LRC 取前 3 行正文（去掉时间标签与元数据行）做预览 */
    private static String previewOf(String lrc) {
        if (lrc == null) return "";
        String[] lines = lrc.split("\n");
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String ln : lines) {
            if (n >= 3) break;
            String body = ln.replaceFirst("^\\s*(?:\\[\\d+:\\d+(?:\\.\\d+)?\\])+\\s*", "");
            if (body.trim().isEmpty()) continue;
            /* 元数据行（作词/作曲/编曲…）不进预览 */
            if (body.trim().matches("^(作词|作曲|编曲|制作人|混音|母带|录音|和声|监制|出品|发行|翻译|词|曲)\\s*[：:].*")) continue;
            sb.append(body.trim()).append("\n");
            n++;
        }
        return sb.toString().trim();
    }

    /* ---------------- 进度推送循环 ---------------- */

    /* 上次已推送的进度快照：paused 且位置未变时跳过推送，
     * 避免不放歌时持续 BLE 写入/ACK 空转（恢复播放或 seek 立即恢复推送） */
    private long lastPushedPos = -1;
    private boolean lastPushedPlaying = false;

    private synchronized void startProgressTask() {
        if (progressTask != null || ble == null) return; /* 已在跑 */
        progressTask = new Runnable() {
            @Override
            public void run() {
                if (!ble.isConnected()) {
                    progressTask = null;
                    return;
                }
                boolean unchanged = (!lastPlaying && lastPushedPlaying == lastPlaying
                        && lastPushedPos == lastPos);
                if (unchanged) {
                    /* 暂停且未 seek：本周期跳过，下一周期再查 */
                    main.postDelayed(this, PROGRESS_INTERVAL_MS);
                    return;
                }
                try {
                    JSONObject msg = new JSONObject();
                    msg.put("type", "progress");
                    msg.put("positionMs", lastPos);
                    msg.put("playing", lastPlaying);
                    if (curDuration > 0) msg.put("durationMs", curDuration);
                    if (ble.pushJson(msg.toString())) {
                        mon.progressCount++;
                        lastPushedPos = lastPos;
                        lastPushedPlaying = lastPlaying;
                    }
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

    /* ---------------- 设备信息（v2.0 角色对调：车机连手机，无需选设备） ---------------- */

    /** 本机蓝牙 MAC 文本（车机配置用） */
    String ownMacText() {
        String mac = getOwnMac();
        return (mac == null) ? "未知（检查蓝牙是否开启）" : mac;
    }

    /** 同步 BLE 写入统计到监控快照（监控台每秒调用，v1.6） */
    static void refreshWriteStats() {
        PlaybackService svc = instance;
        if (svc == null || svc.ble == null) return;
        BleWriteQueue.Stats s = svc.ble.writeStats();
        mon.writeAck = s.ackCount;
        mon.writeFail = s.failCount;
    }

    private void onBleState(String state, String detail) {
        if ("connected".equals(state)) {
            if (!curKey.isEmpty()) {
                /* 连接建立时若已在放歌：立即补推当前曲目（不等下一首） */
                String[] parts = curKey.split("\\|", 2);
                updateNotification("已连接，补推当前曲目…");
                fetchAndPush(parts[0], parts.length > 1 ? parts[1] : "", null, curDuration);
            } else {
                updateNotification("车机已连接，等待播放…（放歌即推词）");
                return;
            }
        } else {
            updateNotification("BLE: " + detail);
        }
    }

    /* ---------------- 通知 ---------------- */

    private void startForeground() {
        String chId = "icarlyrics_phone";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(
                chId, "IcarLyrics 推送", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, chId)
                .setSmallIcon(R.drawable.ic_stat_lyrics)
                .setContentTitle("IcarLyrics 手机端运行中")
                .setContentText("等待播放音乐…")
                .setOngoing(true)
                .build();
        startForeground(10, n);
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification n = new Notification.Builder(this, "icarlyrics_phone")
                .setSmallIcon(R.drawable.ic_stat_lyrics)
                .setContentTitle("IcarLyrics 手机端")
                .setContentText(text)
                .setOngoing(true)
                .build();
        try { nm.notify(10, n); } catch (Exception ignored) {}
    }
}
