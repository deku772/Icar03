package com.icarme.lyrics;

/** ADB 授权命令帮助文本（与 03 系列安装流程一致；含歌词避让所需项） */
public final class AdbHelper {
    private AdbHelper() {}

    public static String helpText() {
        return "首次安装需通过 ADB 授权（电脑连车机执行）：\n\n"
            + "1) 悬浮窗权限\n"
            + "adb shell appops set com.icarme.lyrics SYSTEM_ALERT_WINDOW allow\n\n"
            + "2) 定位权限（BLE 扫描发现手机需要）\n"
            + "adb shell pm grant com.icarme.lyrics android.permission.ACCESS_FINE_LOCATION\n\n"
            + "3) 通知使用权（读车机蓝牙进度，歌词不慢的关键）\n"
            + "adb shell cmd notification allow_listener com.icarme.lyrics/.CarMediaListener\n\n"
            + "4) 使用情况访问（壁纸/地图场景检测）\n"
            + "adb shell appops set com.icarme.lyrics android:get_usage_stats allow\n\n"
            + "5) 无障碍（可选：系统组件/场景层几何，歌词避让更准）\n"
            + "adb shell settings put secure enabled_accessibility_services com.icarme.lyrics/.IcarA11yService\n"
            + "adb shell settings put secure accessibility_enabled 1\n\n"
            + "6) 启动/停止双服务\n"
            + "adb shell am broadcast -a com.icarme.lyrics.START -n com.icarme.lyrics/.AdbReceiver\n"
            + "adb shell am broadcast -a com.icarme.lyrics.STOP -n com.icarme.lyrics/.AdbReceiver\n\n"
            + "7) 打开主界面\n"
            + "adb shell am start -n com.icarme.lyrics/.MainActivity\n\n"
            + "避让说明：只读 setting_tbt_show / setting_tbt_guide_status 等公开键，\n"
            + "地图卡出现时按顶避让缩写安全区；空间不足则隐藏歌词，不盖住系统组件。\n"
            + "启动后手机端 App 通过 BLE 推送歌词，车机全程不联网、零流量。";
    }
}
