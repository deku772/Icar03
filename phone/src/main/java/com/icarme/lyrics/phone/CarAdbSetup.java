package com.icarme.lyrics.phone;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 车机无线 ADB 一键授权（同 03 车机助手思路）：
 *   扫局域网 :5555 → ADB 握手 → 代跑悬浮窗/定位/通知使用权 → 拉起服务。
 * 仅在手机与车机同一 Wi-Fi/热点下可用；车机需已开启无线调试。
 */
final class CarAdbSetup {

    private static final String TAG = "IcarLyrics.CarAdb";
    static final int ADB_PORT = 5555;
    private static final int SCAN_TIMEOUT_MS = 220;

    interface Callback {
        void onLog(String line);
        void onDone(boolean ok, String summary);
    }

    private CarAdbSetup() {}

    /** 从手机 Context 初始化 ADB RSA 密钥 */
    static void ensureKeys(Context ctx) throws Exception {
        File dir = ctx.getFilesDir();
        AdbClient.AdbKeys.ensure(new AdbClient.ContextHolder() {
            @Override public byte[] read(String name) {
                try {
                    File f = new File(dir, name);
                    if (!f.exists()) return null;
                    try (FileInputStream in = new FileInputStream(f)) {
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                        return bos.toByteArray();
                    }
                } catch (Exception e) {
                    return null;
                }
            }
            @Override public void write(String name, byte[] data) {
                try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
                    out.write(data);
                } catch (Exception ignored) {}
            }
        });
    }

    /** 后台跑：扫描 → 授权。回调在主线程外，由 UI 层 post。 */
    static void runAsync(Context ctx, Callback cb) {
        new Thread(() -> {
            try {
                ensureKeys(ctx);
                cb.onLog("正在扫描局域网 ADB（:5555）…");
                List<String> hosts = scanLan(ctx);
                if (hosts.isEmpty()) {
                    cb.onDone(false, "未发现开放 ADB 的设备。请确认：手机与车机同一 Wi-Fi/热点，且车机已开无线调试");
                    return;
                }
                cb.onLog("发现 " + hosts.size() + " 台: " + String.join(", ", hosts));
                Exception last = null;
                for (String host : hosts) {
                    try {
                        cb.onLog("连接 " + host + ":" + ADB_PORT + " …");
                        grantOnHost(host, cb);
                        cb.onDone(true, "已授权车机 " + host + "（悬浮窗/定位/通知使用权），并尝试启动服务");
                        return;
                    } catch (Exception e) {
                        last = e;
                        cb.onLog(host + " 失败: " + e.getMessage());
                    }
                }
                cb.onDone(false, "全部连接失败。"
                        + (last != null ? "最后错误: " + last.getMessage()
                        + "。若车机弹出「允许调试」，请点允许后重试。" : ""));
            } catch (Exception e) {
                cb.onDone(false, "异常: " + e.getMessage());
            }
        }, "car-adb-setup").start();
    }

    /** 对已知 host 直接授权（跳过扫描） */
    static void grantOnHost(String host, Callback cb) throws IOException {
        try (AdbClient adb = new AdbClient()) {
            adb.connect(host, ADB_PORT);
            String banner = runCmd(adb, cb, "echo ok");
            if (banner == null || !banner.contains("ok")) {
                throw new IOException("shell 通道异常");
            }
            /* 与 AdbHelper / README 一致的车机三项 + 启动 */
            runCmd(adb, cb, "appops set com.icarme.lyrics SYSTEM_ALERT_WINDOW allow");
            runCmd(adb, cb, "pm grant com.icarme.lyrics android.permission.ACCESS_FINE_LOCATION");
            runCmd(adb, cb, "cmd notification allow_listener com.icarme.lyrics/.CarMediaListener");
            /* 尽力而为：部分 ROM 对 usage stats 名不同 */
            runCmd(adb, cb, "appops set com.icarme.lyrics android:get_usage_stats allow");
            runCmd(adb, cb, "am broadcast -a com.icarme.lyrics.START -n com.icarme.lyrics/.AdbReceiver");
            runCmd(adb, cb, "am start -n com.icarme.lyrics/.MainActivity");
        }
    }

    private static String runCmd(AdbClient adb, Callback cb, String cmd) {
        try {
            String out = adb.shell(cmd);
            String line = out == null ? "" : out.trim();
            cb.onLog("$ " + cmd + (line.isEmpty() ? "" : " → " + firstLine(line)));
            return out;
        } catch (Exception e) {
            cb.onLog("$ " + cmd + " → 失败: " + e.getMessage());
            return null;
        }
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i > 0 ? s.substring(0, i) : s;
    }

    /** 扫本机所在网段的 :5555（并行 TCP connect） */
    static List<String> scanLan(Context ctx) {
        String prefix = localLanPrefix(ctx);
        if (prefix == null) return Collections.emptyList();
        List<String> candidates = new ArrayList<>(254);
        for (int i = 1; i <= 254; i++) candidates.add(prefix + i);
        /* 排除本机 */
        String self = localIpv4(ctx);
        if (self != null) candidates.remove(self);

        ExecutorService pool = Executors.newFixedThreadPool(64);
        List<String> hit = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> fs = new ArrayList<>();
        for (String ip : candidates) {
            fs.add(pool.submit((Callable<Void>) () -> {
                if (probe(ip, ADB_PORT, SCAN_TIMEOUT_MS)) hit.add(ip);
                return null;
            }));
        }
        for (Future<?> f : fs) {
            try { f.get(SCAN_TIMEOUT_MS + 800, TimeUnit.MILLISECONDS); }
            catch (Exception ignored) {}
        }
        pool.shutdownNow();
        Collections.sort(hit);
        return hit;
    }

    private static boolean probe(String ip, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 取当前 Wi-Fi/热点 IPv4，如 192.168.43.100 */
    static String localIpv4(Context ctx) {
        try {
            List<NetworkInterface> nifs = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface nif : nifs) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                List<InetAddress> addrs = Collections.list(nif.getInetAddresses());
                for (InetAddress a : addrs) {
                    if (a.isLoopbackAddress() || !(a instanceof java.net.Inet4Address)) continue;
                    String s = a.getHostAddress();
                    if (s != null && !s.startsWith("127.")) return s;
                }
            }
        } catch (Exception ignored) {}
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int ip = wm.getConnectionInfo().getIpAddress();
                if (ip != 0) {
                    return String.format(java.util.Locale.US, "%d.%d.%d.%d",
                            ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    static String localLanPrefix(Context ctx) {
        String ip = localIpv4(ctx);
        if (ip == null) return null;
        int cut = ip.lastIndexOf('.');
        if (cut <= 0) return null;
        return ip.substring(0, cut + 1);
    }
}
