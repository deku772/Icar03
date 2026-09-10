package com.icarme.lyrics;

/** ADB 授权命令帮助文本（与 03 系列安装流程一致的三项授权） */
final class AdbHelper {
    private AdbHelper() {}

    static String helpText() {
        return "首次安装需通过 ADB 授权（电脑连车机执行）：\n\n"
            + "1) 悬浮窗权限\n"
            + "adb shell appops set com.icarme.lyrics SYSTEM_ALERT_WINDOW allow\n\n"
            + "2) 定位权限（BLE 扫描发现手机需要）\n"
            + "adb shell pm grant com.icarme.lyrics android.permission.ACCESS_FINE_LOCATION\n\n"
            + "3) 通知使用权（读车机蓝牙进度，歌词不慢的关键）\n"
            + "adb shell cmd notification allow_listener com.icarme.lyrics/.CarMediaListener\n\n"
            + "4) 启动/停止双服务\n"
            + "adb shell am broadcast -a com.icarme.lyrics.START -n com.icarme.lyrics/.AdbReceiver\n"
            + "adb shell am broadcast -a com.icarme.lyrics.STOP -n com.icarme.lyrics/.AdbReceiver\n\n"
            + "5) 打开主界面\n"
            + "adb shell am start -n com.icarme.lyrics/.MainActivity\n\n"
            + "启动后手机端 App 将通过 BLE 向本机推送歌词，\n"
            + "车机全程不联网、零流量。";
    }
}
