package com.icarme.lyrics.phone;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 车机无线 ADB 一键授权（03 车机助手同思路）：
 *
 * 推荐拓扑：手机开热点 → 车机连上该热点 → 手机对车机 IP 发 ADB。
 * 此时手机是 AP：WifiManager.getIpAddress() 常为 0，不能只信它；
 * 必须：NetworkInterface 扫全部 192.168 网段 + 读 /proc/net/arp 已连客户端 + 常见热点前缀。
 */
final class CarAdbSetup {

    private static final String TAG = "IcarLyrics.CarAdb";
    static final int ADB_PORT = 5555;
    private static final int SCAN_TIMEOUT_MS = 400;

    interface Callback {
        void onLog(String line);
        void onDone(boolean ok, String summary);
    }

    private CarAdbSetup() {}

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

    /** 一键授权车机（已有 APK 时） */
    static void runAsync(Context ctx, String optionalIp, Callback cb) {
        new Thread(() -> {
            try {
                ensureKeys(ctx);
                List<String> hosts = resolveHosts(ctx, optionalIp, cb);
                if (hosts.isEmpty()) {
                    cb.onDone(false, "未发现 ADB。请：手机开热点、车机连上；或填车机 IP");
                    return;
                }
                Exception last = null;
                for (String host : hosts) {
                    try {
                        cb.onLog("授权 " + host + " …");
                        grantOnHost(host, cb);
                        cb.onDone(true, "已授权车机 " + host);
                        return;
                    } catch (Exception e) {
                        last = e;
                        cb.onLog(host + " 失败: " + e.getMessage());
                    }
                }
                cb.onDone(false, "授权失败。"
                        + (last != null ? "最后: " + last.getMessage() : ""));
            } catch (Exception e) {
                cb.onDone(false, "异常: " + e.getMessage());
            }
        }, "car-adb-grant").start();
    }

    /**
     * 一键安装车机端：GitHub 下 APK → ADB sync 推送 → pm install → 再授权并启动。
     * 03 车机助手同路径，不在车机上点「未知来源」。
     */
    static void runInstallAsync(Context ctx, String optionalIp, Callback cb) {
        new Thread(() -> {
            try {
                ensureKeys(ctx);
                List<String> hosts = resolveHosts(ctx, optionalIp, cb);
                if (hosts.isEmpty()) {
                    cb.onDone(false, "未发现 ADB。请：手机开热点、车机连上；或填车机 IP");
                    return;
                }
                cb.onLog("下载车机端 APK…");
                java.io.File apk = downloadCarApk(ctx, cb);
                if (apk == null) {
                    cb.onDone(false, "APK 下载失败");
                    return;
                }
                Exception last = null;
                for (String host : hosts) {
                    try {
                        cb.onLog("安装到 " + host + " …");
                        try (AdbClient adb = new AdbClient()) {
                            adb.connect(host, ADB_PORT);
                            String echo = adb.shell("echo ok");
                            if (echo == null || !echo.contains("ok")) {
                                throw new IOException("shell 通道异常");
                            }
                            adb.pushAndInstall(apk, "IcarLyrics-Car.apk");
                            cb.onLog("pm install 完成，开始授权…");
                            grantVia(adb, cb);
                        }
                        cb.onDone(true, "车机 " + host + " 安装并授权完成");
                        return;
                    } catch (Exception e) {
                        last = e;
                        cb.onLog(host + " 失败: " + e.getMessage());
                    }
                }
                cb.onDone(false, "安装失败。"
                        + (last != null ? "最后: " + last.getMessage() : ""));
            } catch (Exception e) {
                cb.onDone(false, "异常: " + e.getMessage());
            }
        }, "car-adb-install").start();
    }

    private static List<String> resolveHosts(Context ctx, String optionalIp, Callback cb) {
        List<String> hosts = new ArrayList<>();
        String manual = optionalIp == null ? "" : optionalIp.trim();
        if (manual.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            hosts.add(manual);
            cb.onLog("直连手动指定: " + manual);
            return hosts;
        }
        hosts = discoverHosts(ctx, cb);
        if (!hosts.isEmpty()) cb.onLog("候选: " + String.join(", ", hosts));
        return hosts;
    }

    /**
     * 下载最新车机 APK：默认国内镜像，慢/失败再回退 GitHub。
     * 固定写到 cache/update/IcarLyrics-Car-push.apk，不会堆积多个包。
     */
    private static java.io.File downloadCarApk(Context ctx, Callback cb) {
        String gh = "https://github.com/deku772/Icar03/releases/latest/download/IcarLyrics-Car.apk";
        String[] urls = {
                "https://ghfast.top/" + gh,   /* 默认镜像，国内快 */
                gh                              /* 镜像失败再回官方 */
        };
        java.io.File out = new java.io.File(ctx.getCacheDir(), "update/IcarLyrics-Car-push.apk");
        //noinspection ResultOfMethodCallIgnored
        out.getParentFile().mkdirs();
        if (out.exists()) //noinspection ResultOfMethodCallIgnored
            out.delete();

        for (int i = 0; i < urls.length; i++) {
            String u = urls[i];
            String label = i == 0 ? "镜像" : "GitHub";
            cb.onLog("从" + label + "下载…");
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                /* 镜像 20s 连不上就换源；官方放宽 */
                c.setConnectTimeout(i == 0 ? 12000 : 20000);
                c.setReadTimeout(120000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "IcarLyrics-Updater");
                int code = c.getResponseCode();
                if (code != 200) {
                    cb.onLog(label + " HTTP " + code);
                    c.disconnect();
                    continue;
                }
                int contentLen = c.getContentLength();
                try (java.io.InputStream in = c.getInputStream();
                     java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    long read = 0;
                    int lastPct = -1;
                    long lastLogAt = 0;
                    while ((n = in.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                        read += n;
                        long now = System.currentTimeMillis();
                        if (contentLen > 0) {
                            int pct = (int) (read * 100L / contentLen);
                            if (pct >= lastPct + 10 && now - lastLogAt > 400) {
                                lastPct = pct;
                                lastLogAt = now;
                                cb.onLog(label + " 下载 " + pct + "%（"
                                        + (read / 1024) + "/" + (contentLen / 1024) + "KB）");
                            }
                        } else if (now - lastLogAt > 1500) {
                            lastLogAt = now;
                            cb.onLog(label + " 已下载 " + (read / 1024) + "KB…");
                        }
                    }
                    cb.onLog(label + " 完成 " + (read / 1024) + "KB");
                }
                c.disconnect();
                if (out.length() > 10000) return out;
            } catch (Exception e) {
                cb.onLog(label + " 失败: " + e.getMessage());
            }
        }
        return null;
    }

    static void grantOnHost(String host, Callback cb) throws IOException {
        try (AdbClient adb = new AdbClient()) {
            adb.connect(host, ADB_PORT);
            String banner = runCmd(adb, cb, "echo ok");
            if (banner == null || !banner.contains("ok")) {
                throw new IOException("shell 通道异常（若 ADB Helper/电脑 adb 已连接，请先断开再试）");
            }
            grantVia(adb, cb);
        }
    }

    private static void grantVia(AdbClient adb, Callback cb) throws IOException {
        runCmd(adb, cb, "appops set com.icarme.lyrics SYSTEM_ALERT_WINDOW allow");
        runCmd(adb, cb, "pm grant com.icarme.lyrics android.permission.ACCESS_FINE_LOCATION");
        runCmd(adb, cb, "cmd notification allow_listener com.icarme.lyrics/.CarMediaListener");
        runCmd(adb, cb, "appops set com.icarme.lyrics android:get_usage_stats allow");
        runCmd(adb, cb, "am broadcast -a com.icarme.lyrics.START -n com.icarme.lyrics/.AdbReceiver");
        runCmd(adb, cb, "am start -n com.icarme.lyrics/.MainActivity");
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

    /* ---------------- 发现：ARP 优先 + 多网段 + 常见热点前缀 ---------------- */

    static List<String> discoverHosts(Context ctx, Callback cb) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();

        /* 1) ARP 表：车机已连热点时通常已有条目，最快最准 */
        List<String> arp = readArpHosts();
        cb.onLog("本机 ARP: " + (arp.isEmpty() ? "（空）" : String.join(", ", arp)));
        ordered.addAll(arp);

        /* 2) 本机全部 192.168/10/172 私网 IPv4 的 /24 */
        List<String> selfs = allLocalIpv4(ctx);
        cb.onLog("本机地址: " + (selfs.isEmpty() ? "（无）" : String.join(", ", selfs)));
        LinkedHashSet<String> prefixes = new LinkedHashSet<>();
        for (String ip : selfs) prefixes.add(prefixOf(ip));
        /* 3) 手机热点常见网段（即使本机 IP 读不到也扫） */
        prefixes.add("192.168.43.");
        prefixes.add("192.168.49.");
        prefixes.add("192.168.137.");
        prefixes.remove(null);
        cb.onLog("扫描网段: " + String.join(" ", prefixes));

        List<String> candidates = new ArrayList<>(ordered);
        String self = selfs.isEmpty() ? null : selfs.get(0);
        for (String prefix : prefixes) {
            for (int i = 1; i <= 254; i++) {
                String ip = prefix + i;
                if (ip.equals(self) || ordered.contains(ip)) continue;
                candidates.add(ip);
            }
        }

        /* 先快速探测 ARP/已知，再全段 */
        List<String> hit = new ArrayList<>();
        probeAll(candidates.subList(0, Math.min(ordered.size(), candidates.size())), hit, 500);
        if (hit.isEmpty()) {
            probeAll(candidates, hit, SCAN_TIMEOUT_MS);
        }
        Collections.sort(hit);
        /* ARP 命中的排前面 */
        List<String> result = new ArrayList<>();
        for (String a : arp) if (hit.contains(a)) result.add(a);
        for (String h : hit) if (!result.contains(h)) result.add(h);
        return result;
    }

    private static void probeAll(List<String> ips, List<String> hit, int timeoutMs) {
        if (ips.isEmpty()) return;
        ExecutorService pool = Executors.newFixedThreadPool(48);
        List<String> sync = Collections.synchronizedList(hit);
        List<Future<?>> fs = new ArrayList<>();
        for (String ip : ips) {
            fs.add(pool.submit((Callable<Void>) () -> {
                if (probe(ip, ADB_PORT, timeoutMs)) sync.add(ip);
                return null;
            }));
        }
        for (Future<?> f : fs) {
            try { f.get(timeoutMs + 1200, TimeUnit.MILLISECONDS); }
            catch (Exception ignored) {}
        }
        pool.shutdownNow();
    }

    private static boolean probe(String ip, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** /proc/net/arp：IP 已连过的客户端（车机连上热点后立刻可见） */
    static List<String> readArpHosts() {
        List<String> out = new ArrayList<>();
        File arp = new File("/proc/net/arp");
        if (!arp.exists()) return out;
        try (BufferedReader br = new BufferedReader(new FileReader(arp))) {
            String line;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                if (first) { first = false; continue; }
                String[] p = line.trim().split("\\s+");
                if (p.length < 4) continue;
                String ip = p[0];
                String flag = p[2];
                /* 0x0 = incomplete */
                if ("0x0".equalsIgnoreCase(flag)) continue;
                if (ip.matches("\\d{1,3}(\\.\\d{1,3}){3}") && !ip.startsWith("0.")) {
                    out.add(ip);
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** 收集本机所有非回环 IPv4（优先 wlan/ap，排除 rmnet 蜂窝） */
    static List<String> allLocalIpv4(Context ctx) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        try {
            List<NetworkInterface> nifs = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface nif : nifs) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                String name = nif.getName() == null ? "" : nif.getName().toLowerCase(Locale.US);
                if (name.startsWith("rmnet") || name.startsWith("ccmni")
                        || name.startsWith("clat") || name.startsWith("dummy")) {
                    continue;
                }
                List<InetAddress> addrs = Collections.list(nif.getInetAddresses());
                for (InetAddress a : addrs) {
                    if (a.isLoopbackAddress() || !(a instanceof Inet4Address)) continue;
                    String s = a.getHostAddress();
                    if (s != null && !s.startsWith("127.")) set.add(s);
                }
            }
        } catch (Exception ignored) {}
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int ip = wm.getConnectionInfo().getIpAddress();
                if (ip != 0) {
                    set.add(String.format(Locale.US, "%d.%d.%d.%d",
                            ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff));
                }
            }
        } catch (Exception ignored) {}
        return new ArrayList<>(set);
    }

    private static String prefixOf(String ip) {
        if (ip == null) return null;
        int cut = ip.lastIndexOf('.');
        return cut <= 0 ? null : ip.substring(0, cut + 1);
    }
}
