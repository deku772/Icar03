package com.icarme.lyrics;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 车机本地 HTTP：向同一热点/内网的手机分发内嵌的手机端 APK。
 * 无公网依赖，端口固定，下载完自动关闭可选。
 */
public final class ApkHttpServer {

    private static final String TAG = "IcarLyrics.Http";
    public static final int PORT = 18765;
    private static final String FILE_NAME = "icarlyrics-phone.apk";

    private ServerSocket server;
    private byte[] payload;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;

    public synchronized boolean start(byte[] apkBytes) {
        if (apkBytes == null || apkBytes.length == 0) return false;
        stop();
        payload = apkBytes;
        try {
            server = new ServerSocket(PORT, 8);
            running.set(true);
            worker = new Thread(this::loop, "icar-apk-http");
            worker.setDaemon(true);
            worker.start();
            return true;
        } catch (IOException e) {
            Log.w(TAG, "start failed", e);
            return false;
        }
    }

    public synchronized void stop() {
        running.set(false);
        if (server != null) {
            try { server.close(); } catch (IOException ignored) {}
            server = null;
        }
        worker = null;
    }

    public boolean isRunning() { return running.get(); }

    private void loop() {
        while (running.get()) {
            try {
                Socket s = server.accept();
                handle(s);
            } catch (IOException e) {
                if (running.get()) Log.w(TAG, "accept", e);
                break;
            }
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(8000);
            InputStream in = s.getInputStream();
            // 读请求行即可
            StringBuilder req = new StringBuilder();
            int c;
            while ((c = in.read()) != -1 && c != '\n') {
                req.append((char) c);
                if (req.length() > 512) break;
            }
            String line = req.toString();
            OutputStream out = s.getOutputStream();
            if (line.startsWith("GET / ") || line.startsWith("GET /index")) {
                byte[] html = landingPage().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                writeResponse(out, "200 OK", "text/html; charset=utf-8", html);
            } else if (line.contains(FILE_NAME) || line.startsWith("GET /a.apk")) {
                writeResponse(out, "200 OK", "application/vnd.android.package-archive", payload);
            } else {
                writeResponse(out, "404 Not Found", "text/plain", "not found".getBytes());
            }
        } catch (Exception e) {
            Log.w(TAG, "handle", e);
        } finally {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    private static void writeResponse(OutputStream out, String status, String ctype, byte[] body) throws IOException {
        String header = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + ctype + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "\r\n";
        out.write(header.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static String landingPage() {
        return "<!doctype html><html lang=zh-CN><meta charset=utf-8>"
                + "<meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>IcarLyrics 手机端</title>"
                + "<body style='font-family:sans-serif;background:#0B0F17;color:#EFF2F8;padding:24px'>"
                + "<h2 style='margin-top:0'>IcarLyrics 手机端</h2>"
                + "<p style='color:#8A93A8'>由车机内网分发，安装后启动推送服务即可。</p>"
                + "<p><a href='/" + FILE_NAME + "' style='display:inline-block;background:#5B93F0;"
                + "color:#fff;padding:14px 22px;border-radius:12px;text-decoration:none;font-size:16px'>"
                + "下载安装包</a></p>"
                + "<p style='color:#8A93A8;font-size:13px'>若无法安装，请开启「允许来自此来源 / 未知来源」。</p>"
                + "</body></html>";
    }

    /** 优先返回热点常见网段，否则返回任意 IPv4 */
    public static String findLanIp() {
        try {
            String fallback = null;
            Enumeration<NetworkInterface> nifs = NetworkInterface.getNetworkInterfaces();
            while (nifs != null && nifs.hasMoreElements()) {
                NetworkInterface ni = nifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String ip = a.getHostAddress();
                    if (ip.startsWith("192.168.43.") || ip.startsWith("192.168.49.")
                            || ip.startsWith("192.168.137.")) return ip;
                    if (ip.startsWith("192.168.") || ip.startsWith("10.")) {
                        if (fallback == null) fallback = ip;
                    }
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    public static String downloadUrl(String ip) {
        return "http://" + ip + ":" + PORT + "/";
    }
}
