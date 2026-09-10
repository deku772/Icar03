package com.icarme.lyrics;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机自启：有悬浮窗权限才拉起服务，避免无权限空跑 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (!android.provider.Settings.canDrawOverlays(context)) return;
        /* v1.8.2：startService 在 Android 9 后台被拒（"Background start not allowed"），
         * 改用 startForegroundService（BOOT_COMPLETED 场景允许，服务 onCreate 内
         * 已按规约调用 startForeground）。 */
        context.startForegroundService(new Intent(context, OverlayService.class));
        context.startForegroundService(new Intent(context, BleService.class));
    }
}
