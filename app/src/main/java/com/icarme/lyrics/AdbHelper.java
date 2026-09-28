package com.icarme.lyrics;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** ADB 授权命令帮助文本（与 03 系列安装流程一致；含歌词避让所需项） */
public final class AdbHelper {
    private AdbHelper() {}

    /** 车机本机 IPv4，便于手机/电脑直连 ADB（界面展示用）。热点 10/192.168 网段优先 */
    public static String localIpText() {
        List<String> ips = localIpv4();
        if (ips.isEmpty()) return "无网络";
        StringBuilder sb = new StringBuilder(ips.get(0)).append(" · 端口 5555");
        if (ips.size() > 1) {
            sb.append("　备选 ");
            for (int i = 1; i < ips.size(); i++) {
                if (i > 1) sb.append(" / ");
                sb.append(ips.get(i));
            }
        }
        return sb.toString();
    }

    public static List<String> localIpv4() {
        Set<String> set = new LinkedHashSet<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                try {
                    if (nif == null || !nif.isUp() || nif.isLoopback()) continue;
                    String name = nif.getName() == null ? "" : nif.getName().toLowerCase(Locale.US);
                    if (name.startsWith("rmnet") || name.startsWith("ccmni")
                            || name.startsWith("clat") || name.startsWith("dummy")) continue;
                    for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                        if (a == null || a.isLoopbackAddress() || !(a instanceof Inet4Address)) continue;
                        String s = a.getHostAddress();
                        if (s != null && !s.startsWith("127.")) set.add(s);
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        List<String> ips = new ArrayList<>(set);
        /* 手机热点常见 10.x / 192.168.x / 172.16-31.x，排前面便于 ADB 直连 */
        java.util.Collections.sort(ips, (a, b) -> {
            int pa = ipPriority(a), pb = ipPriority(b);
            return pa != pb ? Integer.compare(pa, pb) : a.compareTo(b);
        });
        return ips;
    }

    private static int ipPriority(String ip) {
        if (ip == null) return 9;
        if (ip.startsWith("10.")) return 0;
        if (ip.startsWith("192.168.")) return 1;
        if (ip.startsWith("172.")) {
            try {
                int second = Integer.parseInt(ip.split("\\.")[1]);
                if (second >= 16 && second <= 31) return 2;
            } catch (Exception ignored) {}
        }
        return 3;
    }

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
            + "adb shell settings put secure accessibility_enabled 1\n"
            + "或车机设置 → 服务状态 → 打开系统无障碍设置，手动勾选 IcarLyrics\n\n"
            + "6) 启动/停止双服务\n"
            + "adb shell am broadcast -a com.icarme.lyrics.START -n com.icarme.lyrics/.AdbReceiver\n"
            + "adb shell am broadcast -a com.icarme.lyrics.STOP -n com.icarme.lyrics/.AdbReceiver\n\n"
            + "7) 打开主界面\n"
            + "adb shell am start -n com.icarme.lyrics/.MainActivity\n\n"
            + "连接地址：车机设置 → 服务状态 →「本机 IP（ADB）」，\n"
            + "电脑/手机执行 adb connect <IP>:5555 即可。\n\n"
            + "避让说明：只读 setting_tbt_show / setting_tbt_guide_status 等公开键，\n"
            + "地图卡出现时按顶避让缩写安全区；空间不足则隐藏歌词，不盖住系统组件。\n"
            + "启动后手机端 App 通过 BLE 推送歌词，车机全程不联网、零流量。";
    }
}
