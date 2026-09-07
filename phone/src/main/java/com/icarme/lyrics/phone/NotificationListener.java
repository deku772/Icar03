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
import android.app.Notification;

import java.util.ArrayList;
import java.util.List;

/**
 * 通知监听服务（授权载体 + 通知 token 兜底）：
 *
 * v1.5 起媒体轮询逻辑已迁移到 PlaybackService（详见该类），原因：
 * ColorOS 在覆盖安装 APK 后不重新绑定 NotificationListenerService，
 * onListenerConnected() 永不触发，导致旧版轮询永远不启动。
 *
 * 本服务保留两个职责：
 *   1) 授权凭证：Manifest 声明 + 用户在系统设置授权后，
 *      PlaybackService 用显式组件名调 MediaSessionManager.getActiveSessions()
 *      即可读取媒体会话，不依赖本服务实例被系统绑定
 *   2) 通知 token 兜底：被系统绑定后，PlaybackService 可调用本类的
 *      pickFromNotifications() 扫描媒体通知提取 MediaSession.Token
 */
public class NotificationListener extends NotificationListenerService {

    interface Callback {
        void onTrackChanged(String track, String artist, String album, long durationMs);

        void onProgress(long positionMs, boolean playing);

        /** 诊断信息（媒体检测状态，用户可见） */
        default void onDiag(String line) {}
    }

    private static volatile Callback callback;
    private static volatile boolean listenerConnected = false;

    /** 本服务的组件名（授权凭证，供 PlaybackService 构造 getActiveSessions 参数） */
    static final ComponentName COMPONENT =
            new ComponentName("com.icarme.lyrics.phone", NotificationListener.class.getName());

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        listenerConnected = true;
        diag("通知监听已绑定（可通知兜底）");
        /* v1.5: 轮询由 PlaybackService 负责，这里只上报绑定状态 */
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        listenerConnected = false;
    }

    @Override
    public void onDestroy() {
        listenerConnected = false;
        super.onDestroy();
    }

    static void setCallback(Callback cb) {
        callback = cb;
    }

    static boolean isListenerConnected() {
        return listenerConnected;
    }

    static void diag(String msg) {
        Callback cb = callback;
        if (cb != null) cb.onDiag(msg);
    }

    /**
     * 通知 token 兜底：getActiveSessions 拿不到会话时，
     * 扫描状态栏媒体通知，从 extras 提取 EXTRA_MEDIA_SESSION。
     * 只有服务被系统绑定（isListenerConnected）时 getActiveNotifications 才可用。
     */
    static MediaController pickFromNotifications(Context ctx) {
        NotificationListener self = (NotificationListener) instance();
        if (self == null) return null;
        try {
            StatusBarNotification[] all = self.getActiveNotifications();
            if (all == null) return null;
            for (StatusBarNotification sbn : all) {
                Notification n = sbn.getNotification();
                if (n == null || n.extras == null) continue;
                if (!n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) continue;
                android.media.session.MediaSession.Token token = n.extras.getParcelable(
                        Notification.EXTRA_MEDIA_SESSION);
                if (token == null) continue;
                MediaController c = new MediaController(ctx, token);
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

    /** 拿到系统绑定的服务实例（仅用于 getActiveNotifications） */
    static NotificationListenerService instance() {
        return SERVICE.get();
    }

    private static final java.lang.ref.WeakReference<NotificationListenerService> NO_SERVICE =
            new java.lang.ref.WeakReference<>(null);
    private static volatile java.lang.ref.WeakReference<NotificationListenerService> SERVICE = NO_SERVICE;

    @Override
    public void onCreate() {
        super.onCreate();
        SERVICE = new java.lang.ref.WeakReference<>(this);
    }

    /* ---------------- 共享工具（PlaybackService 也用） ---------------- */

    static boolean isPlaying(MediaController c) {
        PlaybackState ps = c.getPlaybackState();
        return ps != null
                && ps.getState() == PlaybackState.STATE_PLAYING
                && ps.getPosition() >= 0;
    }

    static String shortPkg(String pkg) {
        if (pkg == null) return "?";
        int i = pkg.lastIndexOf('.');
        return (i > 0 && i < pkg.length() - 1) ? pkg.substring(i + 1) : pkg;
    }
}
