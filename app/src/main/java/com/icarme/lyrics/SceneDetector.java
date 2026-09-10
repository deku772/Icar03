package com.icarme.lyrics;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 前台场景检测：壁纸/桌面 vs 地图导航。
 * 地图时歌词改为底部紧凑模式，避免挡住路线；壁纸时全屏多行。
 * 需要「使用情况访问」权限（ADB: appops set ... GET_USAGE_STATS allow）。
 */
public final class SceneDetector {

    private static final String TAG = "IcarLyrics.Scene";

    public static final String MODE_WALLPAPER = "wallpaper";
    public static final String MODE_MAP = "map";
    public static final String MODE_SETTINGS = "settings";

    private static final Set<String> MAP_PKGS = new HashSet<>(Arrays.asList(
            "com.autonavi.minimap",
            "com.autonavi.amapauto",
            "com.autonavi.amapautojni",
            "com.baidu.BaiduMap",
            "com.baidu.baidumap",
            "com.tencent.map",
            "com.amap.android",
            "com.amap.loc",
            "ctrip.android.map",
            "com.here.app.maps",
            "com.sygic.truck",
            "com.sygic.aura",
            "com.tomtom.gplay.navapp"
    ));

    private String lastPkg = "";
    private String lastMode = MODE_WALLPAPER;

    public String lastMode() { return lastMode; }

    /** 轮询前台包名，返回 wallpaper / map / settings */
    public String poll(Context ctx) {
        try {
            String pkg = topPackage(ctx);
            lastPkg = pkg == null ? "" : pkg;
            lastMode = classify(lastPkg);
        } catch (Throwable t) {
            Log.w(TAG, "poll failed", t);
        }
        return lastMode;
    }

    private static String classify(String pkg) {
        if (pkg == null || pkg.isEmpty()) return MODE_WALLPAPER;
        String p = pkg.toLowerCase();
        if (p.contains("settings") || p.contains("tether")) return MODE_SETTINGS;
        if (MAP_PKGS.contains(pkg)) return MODE_MAP;
        if (p.contains("map") || p.contains("navi") || p.contains("amap")
                || p.contains("autonavi") || p.contains("baidumap")) return MODE_MAP;
        return MODE_WALLPAPER;
    }

    private static String topPackage(Context ctx) {
        /* 1) UsageStats（需授权，最准） */
        try {
            UsageStatsManager usm = (UsageStatsManager)
                    ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm != null) {
                long now = System.currentTimeMillis();
                UsageEvents evs = usm.queryEvents(now - 15000, now);
                UsageEvents.Event e = new UsageEvents.Event();
                String pkg = null;
                while (evs.hasNextEvent()) {
                    evs.getNextEvent(e);
                    if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND
                            || e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                        pkg = e.getPackageName();
                    }
                }
                if (pkg != null) return pkg;
            }
        } catch (Throwable ignored) {}

        /* 2) RunningAppProcesses 兜底（部分车机仍给） */
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                List<android.app.ActivityManager.RunningAppProcessInfo> procs =
                        am.getRunningAppProcesses();
                if (procs != null) {
                    for (android.app.ActivityManager.RunningAppProcessInfo p : procs) {
                        if (p.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                                && p.pkgList != null && p.pkgList.length > 0) {
                            return p.pkgList[0];
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static boolean hasUsagePermission(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.checkPermission("android.permission.PACKAGE_USAGE_STATS",
                    ctx.getPackageName()) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }
}
