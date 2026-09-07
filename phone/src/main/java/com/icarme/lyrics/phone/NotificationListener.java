package com.icarme.lyrics.phone;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.app.Notification;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

/**
 * 播放监控（服务端常驻组件）：
 *  - NotificationListenerService 双重职责：
 *    1) 作为"授权凭证"让系统放行 MediaSessionManager.getActiveSessions
 *    2) 扫描媒体通知，从 extras 提取 MediaSession.Token 直连（getActiveSessions
 *       被 ROM 限制/空列表时的兜底，ColorOS 上常见）
 *  - 会话挑选策略：优先"正在播放"的会话 > 蓝牙栈会话 > 有元数据的会话
 *  - 全程诊断上报（onDiag），主界面/通知栏可见检测到几个会话、选中了谁
 */
public class NotificationListener extends NotificationListenerService {

    interface Callback {
        void onTrackChanged(String track, String artist, String album, long durationMs);

        void onProgress(long positionMs, boolean playing);

        /** 诊断信息（媒体检测状态，用户可见） */
        default void onDiag(String line) {}
    }

    private static MediaSessionManager smm;
    private static MediaController activeController;
    private static Callback callback;
    private static Handler handler;
    private static boolean listenerConnected = false;

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

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
    }

    @Override
    public void onListenerConnected() {
        smm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        listenerConnected = true;
        diag("通知监听已连接");
        startPolling();
    }

    @Override
    public void onDestroy() {
        stopPolling();
        listenerConnected = false;
        super.onDestroy();
    }

    static void setCallback(Callback cb) {
        callback = cb;
    }

    static boolean isListenerConnected() {
        return listenerConnected;
    }

    private static void diag(String msg) {
        Callback cb = callback;
        if (cb != null) cb.onDiag(msg);
    }

    /* ---------------- 会话挑选 ---------------- */

    private static boolean isPlaying(MediaController c) {
        PlaybackState ps = c.getPlaybackState();
        return ps != null
                && ps.getState() == PlaybackState.STATE_PLAYING
                && ps.getPosition() >= 0;
    }

    private MediaController pickController() {
        if (smm == null) return null;
        List<MediaController> list;
        try {
            list = smm.getActiveSessions(null);
        } catch (SecurityException e) {
            diag("无权限读媒体会话（通知使用权被撤销）");
            return null;
        }

        List<String> names = new ArrayList<>();
        MediaController playing = null;   /* 正在播放 */
        MediaController btStack = null;   /* 蓝牙栈会话 */
        MediaController anyMeta = null;   /* 有元数据的会话 */

        for (MediaController c : list) {
            String pkg = c.getPackageName();
            if (pkg == null) continue;
            String shortName = shortPkg(pkg);
            names.add(shortName + (isPlaying(c) ? "(播放中)" : ""));
            if (playing == null && isPlaying(c)) playing = c;
            if (btStack == null && pkg.contains("bluetooth")) btStack = c;
            if (anyMeta == null && c.getMetadata() != null) anyMeta = c;
        }

        if (!names.isEmpty()) {
            diag("会话: " + String.join(", ", names));
        }

        /* 优先级：正在播放 > 蓝牙栈 > 有元数据
         * 注：手机连车机蓝牙听歌时，音频走 A2DP，车机/系统侧可能出现
         * com.android.bluetooth 会话；手机本地外放时直接选正在播放的会话 */
        MediaController pick = (playing != null) ? playing
                : (btStack != null) ? btStack : anyMeta;
        if (pick != null) {
            String state = isPlaying(pick) ? "播放中" : "未播放";
            diag("选中: " + shortPkg(pick.getPackageName()) + " (" + state + ")");
        } else if (!names.isEmpty()) {
            diag("会话均无元数据");
        }
        return pick;
    }

    /* ---------------- 媒体通知 token 兜底 ---------------- */

    /**
     * getActiveSessions 拿不到会话时的兜底：
     * 扫描状态栏通知，找 mediaStyle 通知，从 extras 提取
     * Notification.EXTRA_MEDIA_SESSION 直连。
     */
    private MediaController pickFromNotifications() {
        try {
            StatusBarNotification[] all = getActiveNotifications();
            for (StatusBarNotification sbn : all) {
                Notification n = sbn.getNotification();
                if (n == null || n.extras == null) continue;
                if (!n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) continue;
                android.media.session.MediaSession.Token token = n.extras.getParcelable(
                        Notification.EXTRA_MEDIA_SESSION);
                if (token == null) continue;
                MediaController c = new MediaController(getApplicationContext(), token);
                if (c.getMetadata() != null || isPlaying(c)) {
                    diag("通知兜底命中: " + shortPkg(sbn.getPackageName()));
                    return c;
                }
            }
        } catch (Exception e) {
            diag("通知兜底失败: " + e.getClass().getSimpleName());
        }
        return null;
    }

    /* ---------------- 轮询 ---------------- */

    private void startPolling() {
        /* 轮询 + 回调双保险：低频轮询兜底，回调实时跟帧 */
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (smm == null && !listenerConnected) {
                    handler.postDelayed(this, 2000);
                    return;
                }
                MediaController c = pickController();
                if (c == null) c = pickFromNotifications();
                if (c != activeController) {
                    if (activeController != null) {
                        try { activeController.unregisterCallback(controllerCb); } catch (Exception ignored) {}
                    }
                    activeController = c;
                    if (c != null) {
                        c.registerCallback(controllerCb, handler);
                        emitTrack(c.getMetadata());
                        PlaybackState ps = c.getPlaybackState();
                        if (ps != null) emitProgress(ps);
                    }
                }
                handler.postDelayed(this, 2000);
            }
        }, 500);
    }

    private void stopPolling() {
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (activeController != null) {
            try { activeController.unregisterCallback(controllerCb); } catch (Exception ignored) {}
        }
        activeController = null;
    }

    /* ---------------- 数据上报 ---------------- */

    private void emitTrack(MediaMetadata md) {
        Callback cb = callback;
        if (cb == null || md == null) return;
        String track = textOf(md, MediaMetadata.METADATA_KEY_TITLE);
        String artist = textOf(md, MediaMetadata.METADATA_KEY_ARTIST);
        String album = textOf(md, MediaMetadata.METADATA_KEY_ALBUM);
        long dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (track != null && !track.isEmpty()) {
            diag("曲目: " + track + " - " + (artist == null ? "" : artist));
            cb.onTrackChanged(track, artist, album, dur);
        }
    }

    private void emitProgress(PlaybackState ps) {
        Callback cb = callback;
        if (cb == null || ps == null) return;
        long pos = ps.getPosition();
        boolean playing = ps.getState() == PlaybackState.STATE_PLAYING;
        cb.onProgress(pos, playing);
    }

    private static String textOf(MediaMetadata md, String key) {
        String s = md.getString(key);
        return (s == null || s.equals("<unknown>")) ? null : s;
    }

    private static String shortPkg(String pkg) {
        if (pkg == null) return "?";
        int i = pkg.lastIndexOf('.');
        return (i > 0 && i < pkg.length() - 1) ? pkg.substring(i + 1) : pkg;
    }
}
