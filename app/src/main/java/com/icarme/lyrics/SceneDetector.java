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
            "com.autonavi.amapautopro",
            "com.tencent.wecarnavi",
            "com.tencent.map",
            "com.baidu.BaiduMap",
            "com.baidu.baidumap",
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
    /** 最近一次可信前台包；UsageEvents 窗口查不到时沿用，避免误判回壁纸 */
    private String lastKnownFgPkg = "";

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
        /* 桌面/设置不当地图，避免误隐藏壁纸歌词 */
        if (p.contains("launcher") || p.contains("mengbo")) return MODE_WALLPAPER;
        if (p.contains("settings") || p.contains("tether")) return MODE_SETTINGS;
        if (MAP_PKGS.contains(pkg)) return MODE_MAP;
        if (p.contains("navi") || p.contains("amap") || p.contains("autonavi")
                || p.contains("baidumap") || p.endsWith(".map")
                || p.contains("gaode") || p.contains("tencent.map")) return MODE_MAP;
        return MODE_WALLPAPER;
    }

    private String topPackage(Context ctx) {
        /* 1) UsageStats：窗口放宽到 3 分钟。
           只查 15s 时，地图开久了查不到 FG 事件，会误判回壁纸导致歌词回跳。 */
        try {
            UsageStatsManager usm = (UsageStatsManager)
                    ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm != null) {
                long now = System.currentTimeMillis();
                UsageEvents evs = usm.queryEvents(now - 180000L, now);
                UsageEvents.Event e = new UsageEvents.Event();
                String pkg = null;
                while (evs.hasNextEvent()) {
                    evs.getNextEvent(e);
                    if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND
                            || e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                        pkg = e.getPackageName();
                    }
                }
                if (pkg != null && !pkg.isEmpty()) {
                    lastKnownFgPkg = pkg;
                    return pkg;
                }
            }
        } catch (Throwable ignored) {}

        /* 2) 沿用上次可信前台，禁止 RunningAppProcesses 把场景打回壁纸 */
        if (!lastKnownFgPkg.isEmpty()) return lastKnownFgPkg;

        /* 3) 兜底：RunningAppProcesses（Android 9 常只给自身进程，不可靠） */
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
                            String cand = p.pkgList[0];
                            if (cand != null && cand.equals(ctx.getPackageName())) continue;
                            return cand;
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
