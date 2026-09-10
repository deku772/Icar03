package com.icarme.lyrics;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 车机 SoftAP（热点）SSID / 密码读取与开启。
 * 腾讯云手车互联可能托管自己的 AP，系统 getWifiApConfiguration 常读不到——
 * 多路径反射 + 用户手填记忆，保证合并二维码总能生成。
 */
public final class HotspotInfo {

    private static final String TAG = "IcarLyrics.Ap";
    private static final String PREFS = "icarlyrics";
    private static final String KEY_SSID = "hotspot_ssid";
    private static final String KEY_PASS = "hotspot_pass";

    public final String ssid;
    public final String password;
    public final boolean apEnabled;
    public final boolean fromMemory;

    private HotspotInfo(String ssid, String password, boolean apEnabled, boolean fromMemory) {
        this.ssid = ssid;
        this.password = password;
        this.apEnabled = apEnabled;
        this.fromMemory = fromMemory;
    }

    public boolean valid() {
        return ssid != null && !ssid.isEmpty();
    }

    /** 保存用户手填的热点信息（下次打开自动带上） */
    public static void save(Context ctx, String ssid, String pass) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit().putString(KEY_SSID, ssid == null ? "" : ssid)
                .putString(KEY_PASS, pass == null ? "" : pass).apply();
    }

    /** 系统读取优先，失败回落到上次手填记忆 */
    public static HotspotInfo read(Context ctx) {
        HotspotInfo sys = readSystem(ctx);
        if (sys.valid()) return sys;
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String ssid = p.getString(KEY_SSID, "");
        String pass = p.getString(KEY_PASS, "");
        if (!ssid.isEmpty()) {
            return new HotspotInfo(ssid, pass.isEmpty() ? null : pass, sys.apEnabled, true);
        }
        return sys;
    }

    private static HotspotInfo readSystem(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return new HotspotInfo(null, null, false, false);

            boolean enabled = readApEnabled(wm);
            WifiConfiguration conf = readApConfig(wm);
            if (conf != null) {
                String ssid = stripQuote(conf.SSID);
                String pass = conf.preSharedKey;
                if (pass != null && (pass.equals("null") || pass.isEmpty())) pass = null;
                if (ssid != null && !ssid.isEmpty()) {
                    return new HotspotInfo(ssid, pass, enabled, false);
                }
            }

            /* Settings 兜底（部分车机/厂商把 AP 名写在 Global） */
            String[] keys = {
                    "wifi_ap_ssid", "soft_ap_ssid", "hotspot_ssid",
                    "tether_wifi_ssid", "wifi_hotspot_ssid"
            };
            for (String k : keys) {
                String v = Settings.Global.getString(ctx.getContentResolver(), k);
                if (v != null && !v.isEmpty() && v.length() < 64) {
                    return new HotspotInfo(stripQuote(v), null, enabled, false);
                }
            }
            return new HotspotInfo(null, null, enabled, false);
        } catch (Throwable t) {
            Log.w(TAG, "readSystem failed", t);
            return new HotspotInfo(null, null, false, false);
        }
    }

    private static boolean readApEnabled(WifiManager wm) {
        String[] methods = {"getWifiApState", "getWifiApEnabled"};
        for (String name : methods) {
            try {
                Method m = findMethod(wm.getClass(), name);
                if (m == null) continue;
                m.setAccessible(true);
                Object r = m.invoke(wm);
                if (r instanceof Integer) {
                    int st = (Integer) r;
                    /* 13=ENABLED, 12=ENABLING */
                    if (st == 13 || st == 12) return true;
                } else if (r instanceof Boolean) {
                    return (Boolean) r;
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private static WifiConfiguration readApConfig(WifiManager wm) {
        String[] methods = {"getWifiApConfiguration", "getSoftApConfiguration"};
        for (String name : methods) {
            try {
                Method m = findMethod(wm.getClass(), name);
                if (m == null) continue;
                m.setAccessible(true);
                Object conf = m.invoke(wm);
                if (conf instanceof WifiConfiguration) return (WifiConfiguration) conf;
                /* Android 11+ SoftApConfiguration：反射取 ssid/password */
                if (conf != null) {
                    String ssid = callString(conf, "getSsid");
                    String pass = callString(conf, "getPassphrase");
                    if (ssid != null && !ssid.isEmpty()) {
                        WifiConfiguration fake = new WifiConfiguration();
                        fake.SSID = ssid;
                        fake.preSharedKey = pass;
                        return fake;
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, name + " failed", t);
            }
        }
        /* 字段直读 */
        try {
            Field f = WifiManager.class.getDeclaredField("mWifiApConfig");
            f.setAccessible(true);
            Object conf = f.get(wm);
            if (conf instanceof WifiConfiguration) return (WifiConfiguration) conf;
        } catch (Throwable ignored) {}
        return null;
    }

    private static Method findMethod(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length == 0) return m;
            }
            c = c.getSuperclass();
        }
        try {
            return WifiManager.class.getDeclaredMethod(name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String callString(Object obj, String method) {
        try {
            Method m = obj.getClass().getMethod(method);
            Object r = m.invoke(obj);
            return r == null ? null : r.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String stripQuote(String s) {
        if (s == null) return null;
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    public static boolean tryEnableAp(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return false;
            Method m = findMethod(wm.getClass(), "setWifiApEnabled");
            if (m == null) {
                try { m = WifiManager.class.getDeclaredMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class); }
                catch (Throwable t) { return false; }
            }
            m.setAccessible(true);
            Object r;
            if (m.getParameterTypes().length == 2) {
                r = m.invoke(wm, null, true);
            } else {
                r = m.invoke(wm, true);
            }
            return !(r instanceof Boolean) || (Boolean) r;
        } catch (Throwable t) {
            Log.w(TAG, "tryEnableAp failed", t);
            return false;
        }
    }

    public static void openTetherSettings(Context ctx) {
        String[] actions = {
                "android.settings.TETHER_SETTINGS",
                "android.settings.WIFI_AP_SETTINGS",
                Settings.ACTION_WIRELESS_SETTINGS
        };
        for (String a : actions) {
            try {
                android.content.Intent i = new android.content.Intent(a);
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return;
            } catch (Exception ignored) {}
        }
    }

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
