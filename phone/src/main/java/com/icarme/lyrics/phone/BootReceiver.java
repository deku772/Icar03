package com.icarme.lyrics.phone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机/覆盖安装自启推送服务；手动停止（auto_start=false）后不再自动拉起。
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        if (!PlaybackService.isAutoStart()) return;
        try {
            context.startForegroundService(new Intent(context, PlaybackService.class));
        } catch (Exception e) {
            IcarPhoneApp.saveCrash(Thread.currentThread(), e);
        }
    }
}
