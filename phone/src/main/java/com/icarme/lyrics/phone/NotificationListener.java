package com.icarme.lyrics.phone;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.List;

/**
 * 播放监控（服务端常驻组件）：
 *  - NotificationListenerService 仅作为"授权凭证"让系统放行 MediaSessionManager
 *  - 真正的数据源是 MediaSessionManager：遍历本机媒体会话，
 *    优先取蓝牙栈会话（车机蓝牙连接时系统会创建 com.android.bluetooth 会话），
 *    与 03歌词 在车机端读蓝牙会话同源，天然兼容任意音乐 App。
 */
public class NotificationListener extends NotificationListenerService {

    interface Callback {
        void onTrackChanged(String track, String artist, String album, long durationMs);

        void onProgress(long positionMs, boolean playing);
    }

    private static MediaSessionManager smm;
    private static MediaController activeController;
    private static Callback callback;
    private static Handler handler;

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
        startPolling();
    }

    @Override
    public void onDestroy() {
        stopPolling();
        super.onDestroy();
    }

    static void setCallback(Callback cb) {
        callback = cb;
    }

    private MediaController pickController() {
        if (smm == null) return null;
        try {
            List<MediaController> list = smm.getActiveSessions(null);
            MediaController best = null;
            for (MediaController c : list) {
                String pkg = c.getPackageName();
                if (pkg == null) continue;
                /* 优先蓝牙栈会话（车机场景），其次任意有元数据的会话 */
                if (pkg.contains("bluetooth")) return c;
                if (best == null && c.getMetadata() != null) best = c;
            }
            return best;
        } catch (SecurityException e) {
            return null; /* 通知使用权未授权 */
        }
    }

    private void startPolling() {
        /* 轮询 + 回调双保险：低频轮询兜底，回调实时跟帧 */
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                MediaController c = pickController();
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

    private void emitTrack(MediaMetadata md) {
        Callback cb = callback;
        if (cb == null || md == null) return;
        String track = textOf(md, MediaMetadata.METADATA_KEY_TITLE);
        String artist = textOf(md, MediaMetadata.METADATA_KEY_ARTIST);
        String album = textOf(md, MediaMetadata.METADATA_KEY_ALBUM);
        long dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (track != null && !track.isEmpty()) {
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
}
