package com.icarme.lyrics;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * ADB 直控入口（v2.0）：无需界面模拟点击，命令拉起/停止双服务、配置手机 MAC。
 *
 *   adb shell am broadcast -a com.icarme.lyrics.START
 *   adb shell am broadcast -a com.icarme.lyrics.STOP
 *   adb shell am broadcast -a com.icarme.lyrics.SET_PHONE --es mac XX:XX:XX:XX:XX:XX
 *
 * START 需已授予悬浮窗权限（appops），否则拒绝拉起避免空跑。
 * SET_PHONE 保存手机蓝牙 MAC 后自动重启 BLE 服务使其立即生效。
 */
public class AdbReceiver extends BroadcastReceiver {

    static final String ACTION_START = "com.icarme.lyrics.START";
    static final String ACTION_STOP = "com.icarme.lyrics.STOP";
    static final String ACTION_SET_PHONE = "com.icarme.lyrics.SET_PHONE";

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        if (ACTION_START.equals(a)) {
            OverlayService.setAutoStart(true);
            if (!android.provider.Settings.canDrawOverlays(context)) return;
            try {
                context.startForegroundService(new Intent(context, OverlayService.class));
                context.startForegroundService(new Intent(context, BleService.class));
            } catch (Exception ignored) {}
        } else if (ACTION_STOP.equals(a)) {
            OverlayService.setAutoStart(false);
            context.stopService(new Intent(context, OverlayService.class));
            context.stopService(new Intent(context, BleService.class));
        } else if (ACTION_SET_PHONE.equals(a)) {
            String mac = intent.getStringExtra("mac");
            if (mac == null || !mac.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) {
                android.widget.Toast.makeText(context,
                        "SET_PHONE 失败：MAC 格式错误", android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            context.getSharedPreferences("icarlyrics", Context.MODE_PRIVATE)
                    .edit().putString("phone_device", mac.toUpperCase()).apply();
            BleService.phoneMac = mac.toUpperCase();
            /* BLE 服务运行中则重启生效；未运行则先确保悬浮+BLE 拉起 */
            try {
                context.stopService(new Intent(context, BleService.class));
                if (android.provider.Settings.canDrawOverlays(context)) {
                    context.startForegroundService(new Intent(context, OverlayService.class));
                    context.startForegroundService(new Intent(context, BleService.class));
                }
            } catch (Exception ignored) {}
            android.widget.Toast.makeText(context,
                    "已配置手机 MAC: " + mac.toUpperCase(), android.widget.Toast.LENGTH_LONG).show();
        }
    }
}
