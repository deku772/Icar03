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
 *
 * 行业共性：壁纸/地图歌词必须避让系统组件（导航卡、Dock、场景提示层），
 * 厂家与第三方最终都会收敛到「读公开状态 → 算安全矩形 → 缩短或隐藏」。
 *
 * 本类只读系统公开键与自身偏好，不写回、不连私有服务：
 *   setting_tbt_show / setting_tbt_guide_status —— 地图导航卡与引导态
 * 1080p 参考：引导中 top 预留 178px，其它非 0 引导态 322px（按高度缩放）。
 */
public final class DisplayPolicy {

    private static final String TAG = "IcarLyrics.Policy";

    public static final String KEY_TBT_SHOW = "setting_tbt_show";
    public static final String KEY_TBT_GUIDE = "setting_tbt_guide_status";
    public static final String KEY_WINDOW_MODE =
            "com.mengbo.launcher3.settings.secure.window_mode";
    public static final String KEY_AC_PAGE = "global_setting_ac_page_status";

    /** 1080p 物理 px 参考值 */
    private static final int MAP_INSET_GUIDE_ACTIVE = 178;
    private static final int MAP_INSET_GUIDE_OTHER = 322;
    /** 标准浮窗卡片在 1920x1080 上的参考几何 */
    private static final int REF_W = 1920;
    private static final int REF_H = 1080;
    private static final int REF_CARD_W = 1230;
    private static final int REF_MARGIN_X = 30;
    private static final int REF_TOP = 90;
    private static final int REF_BOTTOM = 900;
    private static final int MIN_SAFE_H = 180;
    private static final int MIN_SAFE_W = 200;

    private static volatile int tbtShow = 0;
    private static volatile int tbtGuide = 0;
    private static volatile int windowMode = -1;
    private static volatile int acPage = -1;

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
        // 部分车机写在 Global
        try {
            register(Settings.Global.getUriFor(KEY_TBT_SHOW));
            register(Settings.Global.getUriFor(KEY_TBT_GUIDE));
        } catch (Throwable ignored) {}
        // 全量刷新一次
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

    /** 地图导航卡顶部预留（已按屏高缩放） */
    public static int mapInsetPx(DisplayMetrics dm) {
        if (tbtShow != 1 || tbtGuide == 0) return 0;
        int raw = (tbtGuide == 2) ? MAP_INSET_GUIDE_ACTIVE : MAP_INSET_GUIDE_OTHER;
        int h = dm == null ? REF_H : Math.max(1, dm.heightPixels);
        return Math.round(raw * (h / (float) REF_H));
    }

    /**
     * 计算壁纸/地图歌词安全矩形。
     *
     * @param sceneMode SceneDetector 模式
     * @param position  wallpaper 歌词位置 left|right
     * @param sceneTopPx 无障碍探测到的左侧系统组件层顶边（可空；作歌词下边界）
     */
    public static SafeBox compute(Context ctx, String sceneMode, String position,
                                  Integer sceneTopPx) {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int w = dm.widthPixels;
        int h = dm.heightPixels;
        float sx = w / (float) REF_W;
        float sy = h / (float) REF_H;
        int mapInset = mapInsetPx(dm);
        boolean left = position != null && position.equals("left");

        // 空调页：展开时桌面/壁纸歌词让位（保守顶避让）
        if (acPage == 1 || acPage == 3) {
            mapInset = Math.max(mapInset, Math.round(160 * sy));
        }

        // 地图前台：底部紧凑条，避开上半屏路线；TBT 卡通常在顶部，与底部条不冲突
        if (SceneDetector.MODE_MAP.equals(sceneMode)) {
            int top = Math.round(h * 0.55f);
            if (mapInset > 0) {
                top = Math.max(top, mapInset);
            }
            int bottom = h - Math.round(24 * sy);
            int height = bottom - top;
            if (height < MIN_SAFE_H) {
                return new SafeBox(0, top, w, 0, true, position, mapInset);
            }
            return new SafeBox(0, top, w, height, false, position, mapInset);
        }

        // 壁纸：标准右侧浮窗卡片模型；左侧为水平镜像
        int cardW = Math.round(REF_CARD_W * sx);
        if (cardW > w) cardW = w;
        int margin = Math.round(REF_MARGIN_X * sx);
        int x = left ? margin : Math.max(0, w - cardW - margin);

        // window_mode 2/3：标准浮窗占用桌面歌词区 → 仅顶部安全条
        if (windowMode == 2 || windowMode == 3) {
            int top = Math.max(Math.round(24 * sy), mapInset);
            int bottom = Math.max(top, Math.round(REF_TOP * sy) - Math.round(8 * sy));
            int height = bottom - top;
            int fullWidth = w - 2 * margin;
            if (height < MIN_SAFE_H) {
                return new SafeBox(margin, top, fullWidth, 0, true, position, mapInset);
            }
            return new SafeBox(margin, top, fullWidth, height, false, position, mapInset);
        }

        int baseTop = Math.round(REF_TOP * sy);
        int top = Math.max(baseTop, mapInset);
        int bottom = Math.min(h, Math.round(REF_BOTTOM * sy));
        // 左侧系统组件层：歌词保持在其上沿之上
        if (left && sceneTopPx != null && sceneTopPx > 0) {
            bottom = Math.min(bottom, sceneTopPx - Math.round(16 * sy));
        }
        // 左侧 ADAS 卡展开（window_mode=1）且用户选右侧时，保持右侧镜像；选左侧时贴左
        if (windowMode == 1 && !left) {
            x = Math.max(0, w - cardW - margin);
        }
        int height = bottom - top;
        if (cardW < MIN_SAFE_W || height < MIN_SAFE_H) {
            return new SafeBox(x, top, cardW, 0, true, position, mapInset);
        }
        return new SafeBox(x, top, cardW, height, false, position, mapInset);
    }
}
