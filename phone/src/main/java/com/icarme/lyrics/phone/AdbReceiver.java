package com.icarme.lyrics.phone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * ADB 直控入口（v1.8.2）：无需打开界面点按钮，一条命令拉起/停止推送服务。
 *
 *   adb shell am broadcast -a com.icarme.lyrics.phone.START
 *   adb shell am broadcast -a com.icarme.lyrics.phone.STOP
 *
 * PlaybackService 启动后会自动连接已记住的车机（无记忆则等用户选择）。
 */
public class AdbReceiver extends BroadcastReceiver {

    static final String ACTION_START = "com.icarme.lyrics.phone.START";
    static final String ACTION_STOP = "com.icarme.lyrics.phone.STOP";

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        if (ACTION_START.equals(a)) {
            try {
                context.startForegroundService(new Intent(context, PlaybackService.class));
            } catch (Exception e) {
                IcarPhoneApp.saveCrash(Thread.currentThread(), e);
            }
        } else if (ACTION_STOP.equals(a)) {
            context.stopService(new Intent(context, PlaybackService.class));
        }
    }
}
