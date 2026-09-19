package com.icarme.lyrics.ui;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

/**
 * 设置页强调色：只读厂商公开键 com.mb.provider.theme_key。
 * 规范 §5.4：33/历史16=粉 #DE5185，32=紫 #5C66BF；不写回系统。
 */
public final class ThemeAccent {

    public static final String KEY = "com.mb.provider.theme_key";
    public static final int PURPLE = 0xFF5C66BF;
    public static final int PINK = 0xFFDE5185;

    private static volatile int accent = PURPLE;
    private static ContentResolver resolver;
    private static ContentObserver observer;

    private ThemeAccent() {}

    public static int accentColor() {
        return accent;
    }

    /** 选中前景：按亮度选白/黑，保证亮色主题可读。 */
    public static int onAccentTextColor(int accentRgb) {
        int r = Color.red(accentRgb);
        int g = Color.green(accentRgb);
        int b = Color.blue(accentRgb);
        double y = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        return y > 0.62 ? Color.BLACK : Color.WHITE;
    }

    public static int onAccentTextColor() {
        return onAccentTextColor(accent);
    }

    public static int resolveKey(int key) {
        if (key == 33 || key == 16) return PINK;
        return PURPLE;
    }

    public static int readKey(Context ctx) {
        ContentResolver cr = ctx.getContentResolver();
        Integer v = readInt(cr, Settings.System.CONTENT_URI, Settings.System.class, KEY);
        if (v == null) {
            v = readInt(cr, Settings.Secure.CONTENT_URI, Settings.Secure.class, KEY);
        }
        if (v == null) {
            v = readInt(cr, Settings.Global.CONTENT_URI, Settings.Global.class, KEY);
        }
        return v == null ? -1 : v;
    }

    private static Integer readInt(ContentResolver cr, Uri base, Class<?> table, String name) {
        try {
            Uri uri = Uri.withAppendedPath(base, name);
            android.database.Cursor c = cr.query(uri, null, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        int idx = c.getColumnIndex(Settings.NameValueTable.VALUE);
                        if (idx >= 0) {
                            return Integer.parseInt(c.getString(idx));
                        }
                    }
                } finally {
                    c.close();
                }
            }
            // 兼容：部分车机只在 Settings.System.getInt 可读
            if (table == Settings.System.class) {
                return Settings.System.getInt(cr, name, Integer.MIN_VALUE + 1) == Integer.MIN_VALUE + 1
                        ? null : Settings.System.getInt(cr, name);
            }
            if (table == Settings.Secure.class) {
                int s = Settings.Secure.getInt(cr, name, Integer.MIN_VALUE + 1);
                return s == Integer.MIN_VALUE + 1 ? null : s;
            }
            if (table == Settings.Global.class) {
                int g = Settings.Global.getInt(cr, name, Integer.MIN_VALUE + 1);
                return g == Integer.MIN_VALUE + 1 ? null : g;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static void attach(Context ctx, final Runnable onChanged) {
        resolver = ctx.getContentResolver();
        int key = readKey(ctx);
        if (key >= 0) accent = resolveKey(key);
        if (observer != null) return;
        observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange) {
                int k = readKey(ctx);
                int next = k >= 0 ? resolveKey(k) : accent;
                if (next != accent) {
                    accent = next;
                    if (onChanged != null) onChanged.run();
                }
            }
        };
        register(Settings.System.getUriFor(KEY));
        register(Settings.Secure.getUriFor(KEY));
        register(Settings.Global.getUriFor(KEY));
    }

    private static void register(Uri uri) {
        if (resolver == null || observer == null || uri == null) return;
        try {
            resolver.registerContentObserver(uri, false, observer);
        } catch (Throwable ignored) {
        }
    }

    public static void detach(Context ctx) {
        if (resolver != null && observer != null) {
            try {
                resolver.unregisterContentObserver(observer);
            } catch (Throwable ignored) {
            }
        }
        observer = null;
        resolver = null;
    }

    public static String cssColor(int argb) {
        return String.format(java.util.Locale.US, "#%06X", (0xFFFFFF & argb));
    }
}
