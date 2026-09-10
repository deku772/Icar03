package com.icarme.lyrics;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * ADB 直控入口（v1.8.2）：无需界面模拟点击，一条命令拉起/停止双服务。
 *
 *   adb shell am broadcast -a com.icarme.lyrics.START
 *   adb shell am broadcast -a com.icarme.lyrics.STOP
 *
 * START 需已授予悬浮窗权限（appops），否则拒绝拉起避免空跑。
 */
public class AdbReceiver extends BroadcastReceiver {

    static final String ACTION_START = "com.icarme.lyrics.START";
    static final String ACTION_STOP = "com.icarme.lyrics.STOP";

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        if (ACTION_START.equals(a)) {
            if (!android.provider.Settings.canDrawOverlays(context)) return;
            try {
                context.startForegroundService(new Intent(context, OverlayService.class));
                context.startForegroundService(new Intent(context, BleService.class));
            } catch (Exception ignored) {}
        } else if (ACTION_STOP.equals(a)) {
            context.stopService(new Intent(context, OverlayService.class));
            context.stopService(new Intent(context, BleService.class));
        }
    }
}
