package com.icarme.lyrics;

import android.content.ComponentName;
import android.service.notification.NotificationListenerService;

/**
 * 车机端媒体会话监听（v2.1 歌词同步）。
 *
 * 作用：给 MediaSessionManager.getActiveSessions() 提供授权凭证，
 * 让 OverlayService 能读到车机蓝牙栈（com.android.bluetooth）的
 * 播放进度——该进度与车机实际发声对齐，用作歌词时间轴的"可信源"，
 * 消除手机 A2DP 推流 + 车机缓冲造成的歌词慢几秒问题（官方 03歌词 同款思路）。
 *
 * 授权（一次性，ADB）：
 *   adb shell cmd notification allow_listener com.icarme.lyrics/.CarMediaListener
 */
public class CarMediaListener extends NotificationListenerService {

    public static final ComponentName COMPONENT =
            new ComponentName("com.icarme.lyrics", "com.icarme.lyrics.CarMediaListener");

    private static volatile CarMediaListener instance;

    public static boolean isConnected() { return instance != null; }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        instance = null;
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }
}
