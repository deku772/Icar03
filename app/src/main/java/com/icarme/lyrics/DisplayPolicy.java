package com.icarme.lyrics;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;

/**
 * 车机歌词安全区策略（自有实现）。
 * 目标：避让系统组件时优先「缩小安全区」，而不是把歌词整层藏没。
 */
public final class DisplayPolicy {

    private static final String TAG = "IcarLyrics.Policy";

    public static final String KEY_TBT_SHOW = "setting_tbt_show";
    public static final String KEY_TBT_GUIDE = "setting_tbt_guide_status";
    public static final String KEY_WINDOW_MODE =
            "com.mengbo.launcher3.settings.secure.window_mode";
    public static final String KEY_AC_PAGE = "global_setting_ac_page_status";

    private static final int MAP_INSET_GUIDE_ACTIVE = 178;
    private static final int MAP_INSET_GUIDE_OTHER = 322;
    private static final int REF_W = 1920;
    private static final int REF_H = 1080;
    private static final int REF_CARD_W = 1230;
    private static final int REF_MARGIN_X = 30;
    private static final int REF_TOP = 90;
    private static final int REF_BOTTOM = 900;
    /** 高度低于此值才考虑降级/隐藏；尽量保底可见 */
    private static final int MIN_SAFE_H = 120;
    private static final int MIN_SAFE_W = 160;

    private static volatile int tbtShow = 0;
    private static volatile int tbtGuide = 0;
    private static volatile int windowMode = -1;
    private static volatile int acPage = -1;
    /** auto=按场景避让；always=始终显示壁纸歌词；car=贴近原车（地图/TBT 隐藏） */
    private static volatile String displayMode = "auto";

    public static final String MODE_AUTO = "auto";
    public static final String MODE_ALWAYS = "always";
    public static final String MODE_CAR = "car";

    public static void setDisplayMode(String mode) {
        if (MODE_AUTO.equals(mode) || MODE_ALWAYS.equals(mode) || MODE_CAR.equals(mode)) {
            displayMode = mode;
            Log.i(TAG, "displayMode=" + mode);
        }
    }

    public static String displayMode() { return displayMode; }

    private static ContentResolver resolver;
    private static ContentObserver observer;

    private DisplayPolicy() {}

    public static final class SafeBox {
        public final int left;
        public final int top;
        public final int width;
        public final int height;
        public final boolean withhold;
        public final String position;
        public final int mapInset;

        public SafeBox(int left, int top, int width, int height,
                       boolean withhold, String position, int mapInset) {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
            this.withhold = withhold;
            this.position = position;
            this.mapInset = mapInset;
        }
    }

    public static void attach(Context ctx, final Runnable onChanged) {
        resolver = ctx.getContentResolver();
        tbtShow = readSecureInt(KEY_TBT_SHOW, 0);
        tbtGuide = readSecureInt(KEY_TBT_GUIDE, 0);
        if (observer != null) return;
        observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange) {
                int s = readSecureInt(KEY_TBT_SHOW, 0);
                int g = readSecureInt(KEY_TBT_GUIDE, 0);
                int wm = readSecureInt(KEY_WINDOW_MODE, -1);
                int ac = readSecureInt(KEY_AC_PAGE, -1);
                boolean changed = (s != tbtShow || g != tbtGuide
                        || wm != windowMode || ac != acPage);
                tbtShow = s;
                tbtGuide = g;
                windowMode = wm;
                acPage = ac;
                if (changed) {
                    Log.i(TAG, "TBT show=" + s + " guide=" + g
                            + " window=" + wm + " ac=" + ac);
                }
                if (onChanged != null) onChanged.run();
            }
        };
        register(Settings.Secure.getUriFor(KEY_TBT_SHOW));
        register(Settings.Secure.getUriFor(KEY_TBT_GUIDE));
        register(Settings.Secure.getUriFor(KEY_WINDOW_MODE));
        register(Settings.Global.getUriFor(KEY_AC_PAGE));
        try {
            register(Settings.Global.getUriFor(KEY_TBT_SHOW));
            register(Settings.Global.getUriFor(KEY_TBT_GUIDE));
        } catch (Throwable ignored) {}
        tbtShow = readSecureInt(KEY_TBT_SHOW, 0);
        tbtGuide = readSecureInt(KEY_TBT_GUIDE, 0);
        windowMode = readSecureInt(KEY_WINDOW_MODE, -1);
        acPage = readSecureInt(KEY_AC_PAGE, -1);
        Log.i(TAG, "attach tbt=" + tbtShow + "/" + tbtGuide
                + " window=" + windowMode + " ac=" + acPage);
    }

    /** 供 OverlayService 在 pushSafeArea 前强制刷键（含只读 Global） */
    public static void refreshAll(Context ctx) {
        if (resolver == null) resolver = ctx.getContentResolver();
        tbtShow = readSecureInt(KEY_TBT_SHOW, 0);
        tbtGuide = readSecureInt(KEY_TBT_GUIDE, 0);
        windowMode = readSecureInt(KEY_WINDOW_MODE, -1);
        acPage = readSecureInt(KEY_AC_PAGE, -1);
    }

    private static void register(android.net.Uri uri) {
        if (resolver == null || observer == null || uri == null) return;
        try {
            resolver.registerContentObserver(uri, false, observer);
        } catch (Throwable ignored) {}
    }

    public static void detach(Context ctx) {
        if (resolver != null && observer != null) {
            try { resolver.unregisterContentObserver(observer); } catch (Throwable ignored) {}
        }
        observer = null;
        resolver = null;
    }

    private static int readSecureInt(String key, int def) {
        int v = def;
        try {
            v = Settings.Secure.getInt(resolver, key, Integer.MIN_VALUE);
            if (v != Integer.MIN_VALUE) return v;
        } catch (Throwable ignored) {}
        try {
            v = Settings.Global.getInt(resolver, key, Integer.MIN_VALUE);
            if (v != Integer.MIN_VALUE) return v;
        } catch (Throwable ignored) {}
        return def;
    }

    public static int tbtShow() { return tbtShow; }

    public static int tbtGuide() { return tbtGuide; }

    public static int windowMode() { return windowMode; }

    public static int mapInsetPx(DisplayMetrics dm) {
        if (tbtShow != 1 || tbtGuide == 0) return 0;
        int raw = (tbtGuide == 2) ? MAP_INSET_GUIDE_ACTIVE : MAP_INSET_GUIDE_OTHER;
        int h = dm == null ? REF_H : Math.max(1, dm.heightPixels);
        return Math.round(raw * (h / (float) REF_H));
    }

    private static SafeBox bottomCompact(int w, int h, float sy, int mapInset,
                                         String position) {
        int top = Math.round(h * 0.58f);
        if (mapInset > 0) top = Math.max(top, Math.min(mapInset, h / 3));
        int bottom = h - Math.round(16 * sy);
        int height = bottom - top;
        if (height < MIN_SAFE_H) {
            // 再试更靠下的一小条
            top = h - MIN_SAFE_H - Math.round(16 * sy);
            height = bottom - top;
        }
        if (height < MIN_SAFE_H) {
            return new SafeBox(0, Math.max(0, bottom - MIN_SAFE_H), w,
                    Math.max(0, height), false, position, mapInset);
        }
        return new SafeBox(0, top, w, height, false, position, mapInset);
    }

    private static SafeBox sideWindow(boolean left, int w, int h, float sx, float sy,
                                      int mapInset, Integer sceneTopPx, String position) {
        int cardW = Math.round(REF_CARD_W * sx);
        if (cardW > w) cardW = w;
        int margin = Math.round(REF_MARGIN_X * sx);
        int x = left ? margin : Math.max(0, w - cardW - margin);
        int top = Math.max(Math.round(REF_TOP * sy), mapInset);
        int bottom = Math.min(h, Math.round(REF_BOTTOM * sy));
        // 仅当 sceneTop 明显位于中下部时，才当作「组件层顶边」压低下边界
        if (left && sceneTopPx != null && sceneTopPx > h * 0.40f && sceneTopPx < h) {
            int b = sceneTopPx - Math.round(16 * sy);
            if (b - top >= MIN_SAFE_H) bottom = Math.min(bottom, b);
        }
        int height = bottom - top;
        if (cardW >= MIN_SAFE_W && height >= MIN_SAFE_H) {
            return new SafeBox(x, top, cardW, height, false, position, mapInset);
        }
        return null;
    }

    public static SafeBox compute(Context ctx, String sceneMode, String position,
                                  Integer sceneTopPx) {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int w = dm.widthPixels;
        int h = dm.heightPixels;
        float sx = w / (float) REF_W;
        float sy = h / (float) REF_H;
        int mapInset = mapInsetPx(dm);
        boolean left = "left".equals(position);

        if (acPage == 1 || acPage == 3) {
            mapInset = Math.max(mapInset, Math.round(140 * sy));
        }

        boolean sceneMap = SceneDetector.MODE_MAP.equals(sceneMode);
        boolean tbtMap = (tbtShow == 1);
        boolean always = MODE_ALWAYS.equals(displayMode);
        boolean car = MODE_CAR.equals(displayMode);

        /*
         * 避让语义（修正颠倒）：
         *  auto  = 仅当前台判定为地图包时隐藏；壁纸/其它一律显示
         *  car   = 地图包或 TBT 卡显示时隐藏（更贴近原车）
         *  always= 永不隐藏；地图时也从「状态栏下方」起排，而不是半屏中部
         */
        boolean shouldHide;
        if (always) {
            shouldHide = false;
        } else if (car) {
            shouldHide = sceneMap || tbtMap;
        } else {
            shouldHide = sceneMap;
        }

        if (shouldHide) {
            Log.i(TAG, "hide lyrics scene=" + sceneMode + " tbt=" + tbtShow
                    + " mode=" + displayMode);
            return new SafeBox(0, 0, w, 0, true, position, mapInset);
        }

        /* 顶部安全起点：通知栏/状态栏下方（约 y=90/1080），TBT 时再下移 */
        int top = Math.max(Math.round(REF_TOP * sy), mapInset);
        int bottom = h - Math.round(24 * sy);
        int margin = Math.round(REF_MARGIN_X * sx);

        /* always 且在地图/TBT：仍从顶部开始整列显示，禁止落到屏幕中部 */
        if (always && (sceneMap || tbtMap)) {
            int bw = Math.round(REF_CARD_W * sx);
            if (bw > w - 2 * margin) bw = Math.max(MIN_SAFE_W, w - 2 * margin);
            int x = left ? margin : Math.max(margin, w - bw - margin);
            int height = bottom - top;
            if (height < MIN_SAFE_H) height = Math.max(MIN_SAFE_H, Math.round(300 * sy));
            return new SafeBox(x, top, bw, height, false, position, mapInset);
        }

        /* 壁纸/常规：侧窗；失败则退回顶部起排的安全窗（不是半屏底） */
        SafeBox box = sideWindow(left, w, h, sx, sy, mapInset, sceneTopPx, position);
        if (box != null) return box;

        if (windowMode == 2 || windowMode == 3) {
            SafeBox other = sideWindow(!left, w, h, sx, sy, mapInset, null, position);
            if (other != null) return other;
        }

        int cardW = Math.round(REF_CARD_W * sx);
        if (cardW > w - 2 * margin) cardW = Math.max(MIN_SAFE_W, w - 2 * margin);
        int x = left ? margin : Math.max(margin, w - cardW - margin);
        int height = bottom - top;
        if (cardW >= MIN_SAFE_W && height >= MIN_SAFE_H) {
            return new SafeBox(x, top, cardW, height, false, position, mapInset);
        }
        /* 最后保底：顶部一条，避免整层消失 */
        return new SafeBox(margin, top, w - 2 * margin,
                Math.max(MIN_SAFE_H, Math.round(160 * sy)), false, position, mapInset);
    }
}
