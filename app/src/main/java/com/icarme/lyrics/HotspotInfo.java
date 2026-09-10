package com.icarme.lyrics;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 读取车机 SoftAP（热点）SSID / 密码。
 * Android 9 多数车机可通过隐藏 API getWifiApConfiguration 读取；
 * 腾讯云手车互联每次启动生成的新 SSID/密码也能拿到。
 */
public final class HotspotInfo {

    private static final String TAG = "IcarLyrics.Ap";

    public final String ssid;
    public final String password;
    public final boolean apEnabled;

    private HotspotInfo(String ssid, String password, boolean apEnabled) {
        this.ssid = ssid;
        this.password = password;
        this.apEnabled = apEnabled;
    }

    public boolean valid() {
        return ssid != null && !ssid.isEmpty();
    }

    /** 尽力读取；失败返回 null 字段的实例 */
    public static HotspotInfo read(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return new HotspotInfo(null, null, false);

            boolean enabled = false;
            try {
                Method st = wm.getClass().getDeclaredMethod("getWifiApState");
                st.setAccessible(true);
                int state = (Integer) st.invoke(wm);
                /* WIFI_AP_STATE_ENABLED = 13 */
                enabled = (state == 13);
            } catch (Throwable ignored) {}

            WifiConfiguration conf = null;
            try {
                Method m = wm.getClass().getDeclaredMethod("getWifiApConfiguration");
                m.setAccessible(true);
                conf = (WifiConfiguration) m.invoke(wm);
            } catch (Throwable t) {
                Log.w(TAG, "getWifiApConfiguration failed", t);
            }
            if (conf == null) return new HotspotInfo(null, null, enabled);

            String ssid = conf.SSID;
            String pass = conf.preSharedKey;
            /* 开放热点 preSharedKey 可能为 null */
            if (pass != null && (pass.equals("null") || pass.isEmpty())) pass = null;
            return new HotspotInfo(stripQuote(ssid), pass, enabled);
        } catch (Throwable t) {
            Log.w(TAG, "read failed", t);
            return new HotspotInfo(null, null, false);
        }
    }

    private static String stripQuote(String s) {
        if (s == null) return null;
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** Wi-Fi 二维码 payload（WPA/WPA2 或开放） */
    public String wifiQrPayload() {
        if (!valid()) return null;
        String escS = escape(ssid);
        if (password == null || password.isEmpty()) {
            return "WIFI:T:nopass;S:" + escS + ";;";
        }
        return "WIFI:T:WPA;S:" + escS + ";P:" + escape(password) + ";;";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
                .replace(":", "\\:").replace("\"", "\\\"");
    }
}
