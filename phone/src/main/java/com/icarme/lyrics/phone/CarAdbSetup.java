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
     * 下载最新车机 APK：多镜像测速后择优下载，失败自动换下一个。
     * 固定写到 cache/update/IcarLyrics-Car-push.apk，不会堆积多个包。
     */
    private static java.io.File downloadCarApk(Context ctx, Callback cb) {
        String gh = "https://github.com/deku772/Icar03/releases/latest/download/IcarLyrics-Car.apk";
        String[] candidates = buildMirrorUrls(gh);
        long[] probe = new long[candidates.length];
        for (int i = 0; i < candidates.length; i++) {
            probe[i] = probeUrlMs(candidates[i], cb);
        }
        /* 按探测延迟升序；探测失败排最后（仍可尝试下载） */
        Integer[] order = new Integer[candidates.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Long.compare(probe[a], probe[b]));
        StringBuilder rank = new StringBuilder("镜像测速:");
        for (int idx : order) {
            rank.append(' ').append(shortLabel(candidates[idx]))
                    .append(probe[idx] >= Long.MAX_VALUE / 2 ? "=超时" : "=" + probe[idx] + "ms");
        }
        cb.onLog(rank.toString());

        java.io.File out = new java.io.File(ctx.getCacheDir(), "update/IcarLyrics-Car-push.apk");
        //noinspection ResultOfMethodCallIgnored
        out.getParentFile().mkdirs();
        if (out.exists()) //noinspection ResultOfMethodCallIgnored
            out.delete();

        for (int oi = 0; oi < order.length; oi++) {
            int i = order[oi];
            String u = candidates[i];
            String label = shortLabel(u);
            boolean official = u.equals(gh);
            cb.onLog("从 " + label + " 下载…");
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                c.setConnectTimeout(official ? 15000 : 10000);
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
                cb.onLog(label + " 文件过小，换源");
            } catch (Exception e) {
                cb.onLog(label + " 失败: " + e.getMessage());
            }
        }
        return null;
    }

    /** 官方 + 多个 GitHub 反代前缀；URL 拼法为 prefix + 完整 GitHub 地址 */
    private static String[] buildMirrorUrls(String gh) {
        String[] prefixes = {
                "https://ghfast.top/",
                "https://ghproxy.net/",
                "https://mirror.ghproxy.com/",
                "https://gh-proxy.com/",
                "https://ghproxy.cn/",
                "https://ghps.cc/",
                "https://hub.gitmirror.com/",
        };
        java.util.ArrayList<String> list = new java.util.ArrayList<>();
        for (String p : prefixes) list.add(p + gh);
        list.add(gh);
        return list.toArray(new String[0]);
    }

    /** 轻量探测：HEAD/首包延迟；失败返回极大值排最后 */
    private static long probeUrlMs(String url, Callback cb) {
        long t0 = System.currentTimeMillis();
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(3500);
            c.setReadTimeout(4000);
            c.setInstanceFollowRedirects(true);
            c.setRequestMethod("HEAD");
            c.setRequestProperty("User-Agent", "IcarLyrics-Updater");
            try {
                int code = c.getResponseCode();
                long dt = System.currentTimeMillis() - t0;
                /* 2xx/3xx 视为可达 */
                if (code >= 200 && code < 400) return dt;
                return Long.MAX_VALUE / 3;
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    private static String shortLabel(String url) {
        if (url == null) return "?";
        if (url.startsWith("https://github.com/")) return "GitHub";
        if (url.contains("ghfast.top")) return "ghfast";
        if (url.contains("ghproxy.net")) return "ghproxy.net";
        if (url.contains("mirror.ghproxy.com")) return "mirror.ghproxy";
        if (url.contains("gh-proxy.com")) return "gh-proxy";
        if (url.contains("ghproxy.cn")) return "ghproxy.cn";
        if (url.contains("ghps.cc")) return "ghps";
        if (url.contains("gitmirror.com")) return "gitmirror";
        try {
            return new java.net.URL(url).getHost();
        } catch (Exception e) {
            return "mirror";
        }
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

    /* ---------------- 组合授权（03 车机助手同款思路，v2.8.2） ---------------- */

    /**
     * 全部授权 + 验证 + 启动合并为一个 shell 脚本，推到车机一次执行：
     * - 单会话：旧版 8 条命令 8 次往返 → 现在 1 次
     * - 幂等：每项先读现状，已生效直接跳过（changed=0）
     * - 追加：无障碍/通知监听列表先读后追加并回读，绝不覆盖车机已有配置
     * - 回读：每项设置后回读验证，marker 行结构化回传逐项结果
     */
    private static void grantVia(AdbClient adb, Callback cb) throws IOException {
        byte[] script = grantScript().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        adb.pushBytes(script, REMOTE_SCRIPT);
        cb.onLog("执行组合授权（单次会话）…");
        String out = adb.shell("sh " + REMOTE_SCRIPT);
        parseGrantOutput(out, cb);
        adb.shell("rm -f " + REMOTE_SCRIPT);
    }

    private static final String REMOTE_SCRIPT = "/data/local/tmp/icar_grant.sh";
    private static final String MARKER = "ICARLY|";
    private static final String CAR_PKG = "com.icarme.lyrics";

    /** 生成授权脚本（mksh 兼容；函数命名对齐 03 助手反编译产物便于日后对照） */
    private static String grantScript() {
        return "set -u\n"
                + "marker='ICARLY'\n"
                + "emit() { printf '%s|%s\\n' \"$marker\" \"$*\"; }\n"
                + "fail() { emit \"FAIL|$1|${2:-}\"; exit 1; }\n"
                + "first_reason=''; first_component=''\n"
                + "remember_failure() { [ -n \"$first_reason\" ] || { first_reason=\"$1\"; first_component=\"$2\"; } }\n"
                + "finish_failures() { [ -z \"$first_reason\" ] || fail \"$first_reason\" \"$first_component\"; }\n"
                + "package_present() { pm path \"$1\" >/dev/null 2>&1; }\n"
                + "appop_state() {\n"
                + "  value=\"$(appops get \"$1\" \"$2\" 2>/dev/null)\" || return 1\n"
                + "  case \"$value\" in\n"
                + "    *\"$2: allow\"*) echo ALLOWED;;\n"
                + "    *\"$2: deny\"*) echo DENIED;;\n"
                + "    *\"$2: ignore\"*) echo IGNORED;;\n"
                + "    *\"$2: errored\"*) echo ERRORED;;\n"
                + "    *\"Uid mode: allow\"*) echo ALLOWED;;\n"
                + "    *\"Default mode: allow\"*) echo ALLOWED;;\n"
                + "    *\"Default mode: default\"*) echo DEFAULT;;\n"
                + "    *\"Default mode: ignore\"*) echo IGNORED;;\n"
                + "    *\"No operations.\"*) echo DEFAULT;;\n"
                + "    *) return 1;;\n"
                + "  esac\n"
                + "}\n"
                + "runtime_state() { dumpsys package \"$1\" 2>/dev/null | grep -Fq \"$2: granted=true\" && echo GRANTED || echo DENIED; }\n"
                + "list_contains() { case \":$1:\" in *\":$2:\"*) return 0;; *) return 1;; esac; }\n"
                + "dedupe_list() {\n"
                + "  raw=\"$1\"; result=''; old_ifs=\"$IFS\"; IFS=':'\n"
                + "  for item in $raw; do\n"
                + "    [ -n \"$item\" ] || continue\n"
                + "    case \":$result:\" in *\":$item:\"*) continue;; esac\n"
                + "    if [ -n \"$result\" ]; then result=\"$result:$item\"; else result=\"$item\"; fi\n"
                + "  done\n"
                + "  IFS=\"$old_ifs\"; printf '%s' \"$result\"\n"
                + "}\n"
                + "emit_auth() { emit \"AUTH|$1|$2|$3|$4\"; }\n"
                + "ensure_appop() {\n"
                + "  pkg=\"$1\"; op=\"$2\"; item=\"$3\"\n"
                + "  before=\"$(appop_state \"$pkg\" \"$op\")\" || { remember_failure appop_read_failed \"$item\"; return 0; }\n"
                + "  after=\"$before\"; changed=0\n"
                + "  if [ \"$before\" != ALLOWED ]; then\n"
                + "    appops set \"$pkg\" \"$op\" allow >/dev/null 2>&1 || { remember_failure appop_write_failed \"$item\"; return 0; }\n"
                + "    changed=1\n"
                + "    after=\"$(appop_state \"$pkg\" \"$op\")\" || { remember_failure appop_readback_failed \"$item\"; return 0; }\n"
                + "  fi\n"
                + "  [ \"$after\" = ALLOWED ] || { remember_failure appop_not_allowed \"$item\"; return 0; }\n"
                + "  emit_auth \"$item\" \"$before\" \"$changed\" \"$after\"\n"
                + "}\n"
                + "ensure_runtime_permission() {\n"
                + "  pkg=\"$1\"; perm=\"$2\"; item=\"$3\"\n"
                + "  before=\"$(runtime_state \"$pkg\" \"$perm\")\"\n"
                + "  after=\"$before\"; changed=0\n"
                + "  if [ \"$before\" != GRANTED ]; then\n"
                + "    pm grant \"$pkg\" \"$perm\" >/dev/null 2>&1 || { remember_failure perm_write_failed \"$item\"; return 0; }\n"
                + "    changed=1\n"
                + "    after=\"$(runtime_state \"$pkg\" \"$perm\")\"\n"
                + "  fi\n"
                + "  [ \"$after\" = GRANTED ] || { remember_failure perm_not_granted \"$item\"; return 0; }\n"
                + "  emit_auth \"$item\" \"$before\" \"$changed\" \"$after\"\n"
                + "}\n"
                + "append_secure_component() {\n"
                + "  setting=\"$1\"; target=\"$2\"; item=\"$3\"\n"
                + "  before_raw=\"$(settings get secure \"$setting\" 2>/dev/null)\" || { remember_failure list_read_failed \"$item\"; return 0; }\n"
                + "  before_norm=\"$(dedupe_list \"$before_raw\")\"\n"
                + "  before_state=ABSENT; list_contains \"$before_norm\" \"$target\" && before_state=PRESENT\n"
                + "  after_raw=\"$before_norm\"; changed=0\n"
                + "  if [ \"$before_state\" = ABSENT ]; then\n"
                + "    if [ \"$setting\" = enabled_notification_listeners ]; then\n"
                + "      cmd notification allow_listener \"$target\" >/dev/null 2>&1 || {\n"
                + "        if [ -z \"$before_norm\" ] || [ \"$before_norm\" = null ]; then next=\"$target\"; else next=\"$before_norm:$target\"; fi\n"
                + "        settings put secure \"$setting\" \"$next\" >/dev/null 2>&1 || { remember_failure list_write_failed \"$item\"; return 0; }\n"
                + "      }\n"
                + "    else\n"
                + "      if [ -z \"$before_norm\" ] || [ \"$before_norm\" = null ]; then next=\"$target\"; else next=\"$before_norm:$target\"; fi\n"
                + "      settings put secure \"$setting\" \"$next\" >/dev/null 2>&1 || { remember_failure list_write_failed \"$item\"; return 0; }\n"
                + "    fi\n"
                + "    changed=1\n"
                + "    after_raw=\"$(settings get secure \"$setting\" 2>/dev/null)\" || { remember_failure list_readback_failed \"$item\"; return 0; }\n"
                + "  fi\n"
                + "  list_contains \"$after_raw\" \"$target\" || { remember_failure list_not_present \"$item\"; return 0; }\n"
                + "  old_ifs=\"$IFS\"; IFS=':'\n"
                + "  for keep in $before_norm; do\n"
                + "    [ -n \"$keep\" ] || continue\n"
                + "    list_contains \"$after_raw\" \"$keep\" || { IFS=\"$old_ifs\"; remember_failure list_clobbered \"$item\"; return 0; }\n"
                + "  done\n"
                + "  IFS=\"$old_ifs\"\n"
                + "  emit_auth \"$item\" \"$before_state\" \"$changed\" \"PRESENT\"\n"
                + "}\n"
                + "pkg=" + CAR_PKG + "\n"
                + "package_present \"$pkg\" || fail package_missing ''\n"
                + "ensure_appop \"$pkg\" SYSTEM_ALERT_WINDOW overlay\n"
                + "ensure_appop \"$pkg\" android:get_usage_stats usage_stats\n"
                + "ensure_runtime_permission \"$pkg\" android.permission.ACCESS_FINE_LOCATION location\n"
                + "append_secure_component enabled_notification_listeners \"$pkg/.CarMediaListener\" notification_listener\n"
                + "enabled=\"$(settings get secure accessibility_enabled 2>/dev/null)\"\n"
                + "[ \"$enabled\" = 1 ] || settings put secure accessibility_enabled 1 >/dev/null 2>&1 || remember_failure a11y_master_failed accessibility\n"
                + "append_secure_component enabled_accessibility_services \"$pkg/.IcarA11yService\" accessibility\n"
                + "finish_failures\n"
                + "am broadcast -a com.icarme.lyrics.START -n \"$pkg/.AdbReceiver\" >/dev/null 2>&1\n"
                + "am start -n \"$pkg/.MainActivity\" >/dev/null 2>&1 || fail launch_failed ''\n"
                + "emit \"LAUNCH|OK\"\n"
                + "emit \"DONE|OK\"\n";
    }

    /** 解析脚本 marker 输出；失败抛 IOException 由外层汇总 */
    private static void parseGrantOutput(String out, Callback cb) throws IOException {
        if (out == null || out.trim().isEmpty()) {
            throw new IOException("授权脚本无输出（shell 通道可能被占用）");
        }
        boolean done = false;
        String failReason = null;
        for (String raw : out.split("\n")) {
            String line = raw.trim();
            if (!line.startsWith(MARKER)) continue;
            String[] p = line.substring(MARKER.length()).split("\\|");
            if (p.length < 1 || p[0].isEmpty()) continue;
            switch (p[0]) {
                case "AUTH": {
                    /* AUTH|item|before|changed|after */
                    if (p.length < 5) continue;
                    boolean changed = "1".equals(p[3]);
                    cb.onLog("  " + itemCn(p[1]) + ": " + stateCn(p[4])
                            + (changed ? "" : "（原已生效，跳过）"));
                    break;
                }
                case "FAIL": {
                    String reason = p.length > 1 ? p[1] : "unknown";
                    String item = p.length > 2 ? p[2] : "";
                    failReason = reasonCn(reason)
                            + (item.isEmpty() ? "" : "（" + itemCn(item) + "）");
                    cb.onLog("  失败: " + failReason);
                    break;
                }
                case "LAUNCH":
                    cb.onLog("  车机端已启动");
                    break;
                case "DONE":
                    done = true;
                    break;
            }
        }
        if (failReason != null) throw new IOException("授权失败: " + failReason);
        if (!done) throw new IOException("授权脚本未完成（未见 DONE 标记）");
    }

    private static String itemCn(String item) {
        if (item == null) return "?";
        switch (item) {
            case "overlay": return "悬浮窗权限";
            case "usage_stats": return "场景检测（使用情况）";
            case "location": return "定位权限";
            case "notification_listener": return "媒体监听";
            case "accessibility": return "无障碍服务";
            default: return item;
        }
    }

    private static String stateCn(String state) {
        if (state == null) return "?";
        switch (state) {
            case "ALLOWED": case "GRANTED": case "PRESENT": return "已生效";
            case "DEFAULT": return "默认";
            case "DENIED": return "拒绝";
            case "IGNORED": return "忽略";
            case "ABSENT": return "未配置";
            default: return state;
        }
    }

    private static String reasonCn(String reason) {
        if (reason == null) return "未知错误";
        switch (reason) {
            case "package_missing": return "车机端未安装（请先执行安装）";
            case "appop_read_failed": case "list_read_failed": return "读取权限状态失败";
            case "appop_write_failed": case "perm_write_failed": case "list_write_failed":
                return "设置权限失败";
            case "appop_readback_failed": case "list_readback_failed": return "回读验证失败";
            case "appop_not_allowed": case "perm_not_granted": return "设置后仍未生效";
            case "list_not_present": return "追加后未生效";
            case "list_clobbered": return "检测到会覆盖车机已有配置，已中止（安全保护）";
            case "a11y_master_failed": return "无障碍总开关开启失败";
            case "launch_failed": return "启动车机端失败";
            default: return reason;
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
