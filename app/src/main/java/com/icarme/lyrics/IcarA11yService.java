package com.icarme.lyrics;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

/**
 * 可选：无障碍探测系统组件层几何（座椅/雨刮/场景等在 launcher 上的节点）。
 * 只读 view id，不注入点击、不扫描其它应用包名做业务分支。
 * 授权：adb shell settings put secure enabled_accessibility_services com.icarme.lyrics/.IcarA11yService
 */
public class IcarA11yService extends AccessibilityService {

    private static final String TAG = "IcarLyrics.A11y";
    private static final String LAUNCHER = "com.mengbo.launcher3";
    private static final String ID_ADAS = "com.mengbo.launcher3:id/adas_handler_view";
    private static final String ID_SCENE = "com.mengbo.launcher3:id/scene_view";

    /** 左侧系统场景层顶边 y（物理 px）；null=未探测到 */
    public static volatile Integer leftSceneTopPx;
    /** ADAS Dock 把手 left x */
    public static volatile Integer adasLeftPx;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean enabled;
    private long lastScan;

    private final Runnable scan = new Runnable() {
        @Override public void run() {
            if (!enabled) return;
            long now = System.currentTimeMillis();
            if (now - lastScan >= 80) {
                lastScan = now;
                scanWindows();
            }
            ui.postDelayed(this, 400);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        enabled = true;
        Log.i(TAG, "accessibility connected");
        ui.post(scan);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 几何由节流扫描处理，避免事件风暴
    }

    @Override
    public void onInterrupt() {
        enabled = false;
        ui.removeCallbacks(scan);
    }

    @Override
    public void onDestroy() {
        enabled = false;
        ui.removeCallbacks(scan);
        super.onDestroy();
    }

    private void scanWindows() {
        Integer sceneTop = null;
        Integer adasLeft = null;
        try {
            DisplayMetrics dm = new DisplayMetrics();
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm != null) wm.getDefaultDisplay().getRealMetrics(dm);
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null) return;
            for (AccessibilityWindowInfo win : windows) {
                AccessibilityNodeInfo root = win.getRoot();
                if (root == null) continue;
                try {
                    CharSequence pkgCs = root.getPackageName();
                    String pkg = pkgCs == null ? "" : pkgCs.toString();
                    if (!LAUNCHER.equals(pkg)) continue;
                    if (sceneTop == null) {
                        AccessibilityNodeInfo n = findNode(root, ID_SCENE);
                        if (n != null) {
                            Rect r = new Rect();
                            n.getBoundsInScreen(r);
                            sceneTop = clamp(r.top, 0, dm.heightPixels);
                            n.recycle();
                        }
                    }
                    if (adasLeft == null) {
                        AccessibilityNodeInfo n = findNode(root, ID_ADAS);
                        if (n != null) {
                            Rect r = new Rect();
                            n.getBoundsInScreen(r);
                            adasLeft = r.left;
                            n.recycle();
                        }
                    }
                } finally {
                    try { root.recycle(); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "scan failed", t);
        }
        leftSceneTopPx = sceneTop;
        adasLeftPx = adasLeft;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static AccessibilityNodeInfo findNode(AccessibilityNodeInfo root, String viewId) {
        try {
            List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByViewId(viewId);
            if (list != null && !list.isEmpty()) {
                AccessibilityNodeInfo n = list.get(0);
                for (int i = 1; i < list.size(); i++) {
                    try { list.get(i).recycle(); } catch (Throwable ignored) {}
                }
                return n;
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
