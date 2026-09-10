package com.icarme.lyrics;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机/覆盖安装自启：有悬浮窗权限且未被手动停止（auto_start=true）才拉起服务。
 * 覆盖安装用 MY_PACKAGE_REPLACED，避免升级后要再点一次启动。
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        if (!OverlayService.isAutoStart()) return;
        if (!android.provider.Settings.canDrawOverlays(context)) return;
        /* v1.8.2：startService 在 Android 9 后台被拒，改用 startForegroundService */
        try {
            context.startForegroundService(new Intent(context, OverlayService.class));
            context.startForegroundService(new Intent(context, BleService.class));
        } catch (Exception ignored) {}
    }
}
