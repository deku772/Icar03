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
        context.startService(new Intent(context, OverlayService.class));
        context.startService(new Intent(context, BleService.class));
    }
}
