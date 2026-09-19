package com.icarme.lyrics;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.icarme.lyrics.ui.LyricsSettingsRenderer;
import com.icarme.lyrics.ui.ThemeAccent;
import com.icarme.lyrics.ui.UiKit;

import java.util.ArrayList;
import java.util.List;

/**
 * 车机设置页 · 模式 A：左导航 + 右内容。
 * 规范：原车设置双栏视觉；本 Activity 只负责导航、主题、窗口与动作转发。
 */
public class MainActivity extends Activity implements LyricsSettingsRenderer.Host {

    private static final long SCAN_MS = 10000;
    private static final String PREFS = "icarlyrics";
    private static final String KEY_SHOW_HELP = "show_adb_help";
    private static final String KEY_PAGE = "settings_page";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<ScanResult> found = new ArrayList<>();
    private boolean scanning = false;
    private boolean helpOpen;
    private String page = LyricsSettingsRenderer.SettingsPages.LYRICS;

    private FrameLayout root;
    private FrameLayout panel;
    private FrameLayout dialogHost;
    private LinearLayout leftNav;
    private FrameLayout contentHost;
    private LinearLayout navItems;
    private LyricsSettingsRenderer renderer;

    private final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            for (int i = 0; i < found.size(); i++) {
                if (found.get(i).getDevice().getAddress().equals(result.getDevice().getAddress())) {
                    found.set(i, result);
                    return;
                }
            }
            found.add(result);
        }
        @Override public void onScanFailed(int errorCode) {
            scanning = false;
            showHint("扫描失败(" + errorCode + ")，请重试");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        getWindow().setWindowAnimations(R.style.IcarWindowAnim);

        helpOpen = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_SHOW_HELP, false);
        page = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_PAGE,
                LyricsSettingsRenderer.SettingsPages.LYRICS);
        ThemeAccent.attach(this, this::onAccentChanged);
        DisplayPolicy.attach(this, null);
        renderer = new LyricsSettingsRenderer(this);
        applyStandardWindow();
        buildShell();
        showPage(page);

        if (!Settings.canDrawOverlays(this)) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) { }
        }
        ensureLocationPermission(false);

        // 不做定时整页重建：会导致滚动位置被重置到顶部
    }

    @Override
    protected void onResume() {
        super.onResume();
        ThemeAccent.attach(this, this::onAccentChanged);
        applyStandardWindow();
        // 回前台只在尚未布局时重建；已打开的页面保留滚动
        if (contentHost.getChildCount() == 0) {
            showPage(page);
        } else {
            restyleAllNav();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyStandardWindow();
    }

    @Override
    protected void onDestroy() {
        ThemeAccent.detach(this);
        DisplayPolicy.detach(this);
        super.onDestroy();
    }

    private void onAccentChanged() {
        OverlayService.pushAccent(ThemeAccent.accentColor());
        if ("system".equals(OverlayService.getColor())) {
            // 悬浮层已接收 accent 推送
        }
        showPage(page);
    }

    /**
     * 窗口策略：Activity 始终全屏透明；设置内容只画在「标准右侧浮窗」面板里。
     * 这样既不会用深色底盖住原车界面，也不会被启动器 freeform 裁成一小条。
     */
    private void applyStandardWindow() {
        DisplayMetricsHolder dmh = DisplayMetricsHolder.of(this);
        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setWindowAnimations(R.style.IcarWindowAnim);
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = 0;
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.MATCH_PARENT;
        w.setAttributes(lp);
        w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT);
        if (panel != null) {
            FrameLayout.LayoutParams plp = (FrameLayout.LayoutParams) panel.getLayoutParams();
            if (plp == null) {
                plp = new FrameLayout.LayoutParams(dmh.w, dmh.h);
            }
            plp.gravity = Gravity.TOP | Gravity.START;
            plp.leftMargin = dmh.carLike ? dmh.x : 0;
            plp.topMargin = dmh.carLike ? dmh.y : 0;
            plp.width = dmh.carLike ? dmh.w : ViewGroup.LayoutParams.MATCH_PARENT;
            plp.height = dmh.carLike ? dmh.h : ViewGroup.LayoutParams.MATCH_PARENT;
            panel.setLayoutParams(plp);
        }
        ui.removeCallbacks(applyWindowRetry);
        ui.postDelayed(applyWindowRetry, 120);
        ui.postDelayed(applyWindowRetry, 400);
    }

    private final Runnable applyWindowRetry = new Runnable() {
        @Override public void run() {
            if (isFinishing()) return;
            applyStandardWindow();
        }
    };

    private static final class DisplayMetricsHolder {
        boolean carLike;
        int x, y, w, h;

        static DisplayMetricsHolder of(Context c) {
            DisplayMetricsHolder o = new DisplayMetricsHolder();
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            try {
                android.view.WindowManager wm =
                        (android.view.WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) wm.getDefaultDisplay().getRealMetrics(dm);
            } catch (Throwable ignored) {
                dm = c.getResources().getDisplayMetrics();
            }
            if (dm.widthPixels <= 0) dm = c.getResources().getDisplayMetrics();
            o.carLike = dm.widthPixels >= 1600;
            /* 1920x1080 标度：标准浮窗 1230x810 @(660,90) */
            o.x = Math.round(660 * (dm.widthPixels / 1920f));
            o.y = Math.round(90 * (dm.heightPixels / 1080f));
            o.w = Math.round(1230 * (dm.widthPixels / 1920f));
            o.h = Math.round(810 * (dm.heightPixels / 1080f));
            if (o.w < 700) o.w = Math.max(700, dm.widthPixels - 40);
            if (o.h < 480) o.h = Math.max(480, dm.heightPixels - 40);
            if (o.x + o.w > dm.widthPixels) o.x = Math.max(0, dm.widthPixels - o.w);
            if (o.y + o.h > dm.heightPixels) o.y = Math.max(0, dm.heightPixels - o.h);
            return o;
        }
    }

    private void buildShell() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);

        panel = new FrameLayout(this);
        panel.setBackgroundColor(Color.TRANSPARENT);

        LinearLayout split = new LinearLayout(this);
        split.setOrientation(LinearLayout.HORIZONTAL);

        leftNav = buildLeftNav();
        split.addView(leftNav, new LinearLayout.LayoutParams(
                getResources().getDimensionPixelSize(R.dimen.icar_nav_width),
                LinearLayout.LayoutParams.MATCH_PARENT));

        contentHost = new FrameLayout(this);
        contentHost.setBackgroundResource(R.drawable.icar_bg_right);
        split.addView(contentHost, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        panel.addView(split, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        dialogHost = new FrameLayout(this);
        dialogHost.setVisibility(View.GONE);
        root.addView(dialogHost, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        applyStandardWindow();
    }

    private LinearLayout buildLeftNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.VERTICAL);
        nav.setBackgroundResource(R.drawable.icar_bg_left);
        int pad = getResources().getDimensionPixelSize(R.dimen.icar_nav_pad_h);
        nav.setPadding(pad, getResources().getDimensionPixelSize(R.dimen.icar_content_pad_v), pad, pad);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER);
        TextView title = new TextView(this);
        title.setText("IcarLyrics");
        title.setTextColor(UiKit.color(this, R.color.icar_text_primary));
        title.setTextSize(TypedValuePx(this, R.dimen.icar_nav_title_size));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleRow.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        nav.addView(titleRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        navItems = new LinearLayout(this);
        navItems.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams itemsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        itemsLp.topMargin = UiKit.dpI(this, 40);
        nav.addView(navItems, itemsLp);

        addNavItem(LyricsSettingsRenderer.SettingsPages.LYRICS, "歌词设置");
        addNavItem(LyricsSettingsRenderer.SettingsPages.SERVICE, "服务状态");

        View spacer = new View(this);
        navItems.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        addNavItem(LyricsSettingsRenderer.SettingsPages.ABOUT, "关于");
        return nav;
    }

    private float TypedValuePx(Context c, int dimen) {
        return c.getResources().getDimension(dimen);
    }

    private void addNavItem(final String id, String label) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        int h = getResources().getDimensionPixelSize(R.dimen.icar_nav_item_h);
        int w = getResources().getDimensionPixelSize(R.dimen.icar_nav_item_w);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.bottomMargin = UiKit.dpI(this, 12);
        item.setLayoutParams(lp);
        item.setPadding(UiKit.dpI(this, 20), 0, UiKit.dpI(this, 20), 0);

        View icon = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(ThemeAccent.accentColor());
        icon.setBackground(dot);
        int iconSz = getResources().getDimensionPixelSize(R.dimen.icar_nav_icon_size);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(iconSz / 2, iconSz / 2);
        ilp.rightMargin = UiKit.dpI(this, 20);
        item.addView(icon, ilp);

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextSize(TypedValuePx(this, R.dimen.icar_body2));
        labelView.setTag("label");
        item.addView(labelView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        item.setTag(id);
        item.setOnClickListener(v -> showPage(id));
        navItems.addView(item);
        restyleNavItem(item);
    }

    private void restyleNavItem(LinearLayout item) {
        if (item == null) return;
        Object tag = item.getTag();
        boolean selected = page != null && page.equals(tag);
        TextView label = item.findViewWithTag("label");
        int accent = ThemeAccent.accentColor();
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(UiKit.dp(this, 20));
        if (selected) {
            bg.setColor(accent);
            item.setBackground(bg);
            if (label != null) {
                label.setTextColor(ThemeAccent.onAccentTextColor(accent));
                label.setTypeface(Typeface.DEFAULT_BOLD);
            }
        } else {
            bg.setColor(0x00000000);
            item.setBackground(bg);
            if (label != null) {
                label.setTextColor(UiKit.color(this, R.color.icar_text_inactive));
                label.setTypeface(Typeface.DEFAULT);
            }
        }
    }

    private void restyleAllNav() {
        for (int i = 0; i < navItems.getChildCount(); i++) {
            View c = navItems.getChildAt(i);
            if (c instanceof LinearLayout) restyleNavItem((LinearLayout) c);
        }
    }

    private ScrollView currentScroll;
    private final java.util.HashMap<String, Integer> scrollYByPage = new java.util.HashMap<>();

    private void showPage(String id) {
        showPage(id, false);
    }

    private void showPage(String id, boolean keepScroll) {
        if (currentScroll != null && page != null) {
            scrollYByPage.put(page, currentScroll.getScrollY());
        }
        page = id;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_PAGE, id).apply();
        restyleAllNav();
        contentHost.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setBackgroundColor(Color.TRANSPARENT);
        scroll.addView(renderer.renderCategory(id));
        contentHost.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        currentScroll = scroll;
        final Integer prev = scrollYByPage.get(id);
        if (prev != null && prev > 0) {
            final int y = prev;
            scroll.post(() -> {
                if (currentScroll == scroll) scroll.scrollTo(0, y);
            });
        }
    }

    /* ---------------- Host ---------------- */

    @Override public Context context() { return this; }
    @Override public boolean isHelpOpen() { return helpOpen; }

    @Override public void refreshChrome() {
        showPage(page, true);
    }

    @Override public void onToggleHelp() {
        helpOpen = !helpOpen;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_SHOW_HELP, helpOpen).apply();
        showPage(LyricsSettingsRenderer.SettingsPages.ABOUT);
    }

    @Override public void onScanPhones() { scanPhones(); }

    @Override public void onToggleService() { toggleService(); }

    @Override public void onRestartService() {
        try { stopService(new Intent(this, OverlayService.class)); } catch (Exception ignored) {}
        try { stopService(new Intent(this, BleService.class)); } catch (Exception ignored) {}
        if (Settings.canDrawOverlays(this)) {
            OverlayService.setAutoStart(true);
            startService(new Intent(this, OverlayService.class));
            try { startForegroundService(new Intent(this, BleService.class)); } catch (Exception ignored) {}
            showHint("已重启悬浮歌词服务");
        } else {
            showHint("请先授予悬浮窗权限");
        }
        ui.postDelayed(() -> showPage(page), 400);
    }

    @Override public void onDownloadApk() { showMirrorDownloadDialog(); }

    /* ---------------- 权限 / 扫描 / 服务 ---------------- */

    private static final int REQ_LOCATION = 11;

    private void ensureLocationPermission(boolean forceHint) {
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return;
        try {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
        } catch (Exception e) {
            showHint("请用 ADB 授予定位权限");
            return;
        }
        if (forceHint) showHint("请在系统弹窗中点「允许」定位权限");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) {
            boolean ok = grantResults != null && grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            showHint(ok ? "定位权限已授予，可扫描手机" : "定位未授予，BLE 扫描不可用");
        }
    }

    private boolean hasPermission(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private void scanPhones() {
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = (bm == null) ? null : bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            showHint("蓝牙未开启");
            return;
        }
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            ensureLocationPermission(true);
            return;
        }
        if (scanning) return;
        try {
            found.clear();
            List<ScanFilter> filters = new ArrayList<>();
            filters.add(new ScanFilter.Builder()
                    .setServiceUuid(new ParcelUuid(BleService.SVC_LYRICS)).build());
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            adapter.getBluetoothLeScanner().startScan(filters, settings, scanCb);
            scanning = true;
            showHint("扫描中… 请确认手机端推送服务已启动");
            ui.postDelayed(this::showScanResult, SCAN_MS);
        } catch (Exception e) {
            scanning = false;
            showHint("扫描发起失败: " + e);
        }
    }

    private void showScanResult() {
        try {
            BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = (bm == null) ? null : bm.getAdapter();
            if (adapter != null && adapter.getBluetoothLeScanner() != null && scanning) {
                adapter.getBluetoothLeScanner().stopScan(scanCb);
            }
        } catch (Exception ignored) {}
        scanning = false;
        if (found.isEmpty()) {
            showHint("未发现 IcarLyrics 手机。请确认手机端推送服务已启动、蓝牙开启。");
            return;
        }
        showPhonePicker();
    }

    /** 规范 §5.5：Activity 根层局部信息弹窗，不用系统 Dialog/Toast */
    private void showPhonePicker() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = getResources().getDimensionPixelSize(R.dimen.icar_dialog_pad);
        body.setPadding(pad, pad, pad, pad);
        for (ScanResult r : found) {
            String n = r.getScanRecord() == null ? null : r.getScanRecord().getDeviceName();
            if (n == null || n.isEmpty()) {
                try { n = r.getDevice().getName(); } catch (SecurityException ignored) {}
            }
            final String mac = r.getDevice().getAddress();
            String title = (n == null || n.isEmpty() ? "未知设备" : n);
            String desc = mac + " · 信号 " + r.getRssi();
            body.addView(UiKit.actionCard(this, title, desc, () -> {
                BleService.phoneMac = mac;
                dismissLocalDialog();
                restartBle();
            }));
        }
        showLocalDialog("选择手机", body);
    }

    private void restartBle() {
        try { stopService(new Intent(this, BleService.class)); } catch (Exception ignored) {}
        if (OverlayService.running || Settings.canDrawOverlays(this)) {
            try {
                if (!OverlayService.running) startService(new Intent(this, OverlayService.class));
                startForegroundService(new Intent(this, BleService.class));
            } catch (Exception ignored) {}
        }
        ui.postDelayed(() -> showPage(page), 600);
    }

    private void toggleService() {
        if (OverlayService.running) {
            OverlayService.setAutoStart(false);
            stopService(new Intent(this, OverlayService.class));
            stopService(new Intent(this, BleService.class));
            showHint("已停止歌词悬浮");
        } else {
            if (!Settings.canDrawOverlays(this)) {
                showHint("请先授予悬浮窗权限（见「关于」ADB 帮助）");
                return;
            }
            OverlayService.setAutoStart(true);
            startService(new Intent(this, OverlayService.class));
            try { startForegroundService(new Intent(this, BleService.class)); } catch (Exception ignored) {}
            showHint("已启动歌词悬浮");
        }
        ui.postDelayed(() -> showPage(page), 300);
        ui.postDelayed(() -> showPage(page), 1500);
    }

    /* ---------------- 局部弹窗 / 提示 ---------------- */

    private TextView hintView;

    private void showHint(String s) {
        if (hintView == null) {
            hintView = new TextView(this);
            hintView.setTextColor(UiKit.color(this, R.color.icar_text_primary));
            hintView.setTextSize(TypedValuePx(this, R.dimen.icar_body2));
            hintView.setBackgroundResource(R.drawable.icar_dialog_panel);
            int p = UiKit.dpI(this, 24);
            hintView.setPadding(p, p, p, p);
            hintView.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
            lp.bottomMargin = UiKit.dpI(this, 48);
            lp.leftMargin = UiKit.dpI(this, 48);
            lp.rightMargin = UiKit.dpI(this, 48);
            root.addView(hintView, lp);
        }
        hintView.setText(s);
        hintView.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideHint);
        ui.postDelayed(hideHint, 3200);
    }

    private final Runnable hideHint = () -> {
        if (hintView != null) hintView.setVisibility(View.GONE);
    };

    private void showLocalDialog(String title, View body) {
        dismissLocalDialog();
        dialogHost.setVisibility(View.VISIBLE);
        dialogHost.removeAllViews();

        View mask = new View(this);
        mask.setBackgroundColor(UiKit.color(this, R.color.icar_dialog_mask));
        mask.setOnClickListener(v -> dismissLocalDialog());
        dialogHost.addView(mask, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.icar_dialog_panel);
        int pad = getResources().getDimensionPixelSize(R.dimen.icar_dialog_pad);
        panel.setPadding(pad, pad, pad, pad);

        TextView t = UiKit.body1(this, title);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        panel.addView(t);
        View gap = new View(this);
        panel.addView(gap, new LinearLayout.LayoutParams(1, UiKit.dpI(this, 20)));
        ScrollView sc = new ScrollView(this);
        sc.addView(body);
        panel.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dpI(this, 360)));

        TextView close = UiKit.body2(this, "关闭");
        close.setGravity(Gravity.CENTER);
        close.setTextColor(ThemeAccent.onAccentTextColor(ThemeAccent.accentColor()));
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setCornerRadius(UiKit.dp(this, 14));
        btnBg.setColor(ThemeAccent.accentColor());
        close.setBackground(btnBg);
        int bh = getResources().getDimensionPixelSize(R.dimen.icar_dialog_btn_h);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, bh);
        blp.topMargin = UiKit.dpI(this, 24);
        close.setLayoutParams(blp);
        close.setOnClickListener(v -> dismissLocalDialog());
        panel.addView(close);

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                getResources().getDimensionPixelSize(R.dimen.icar_dialog_width),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        dialogHost.addView(panel, plp);
    }

    private void dismissLocalDialog() {
        dialogHost.removeAllViews();
        dialogHost.setVisibility(View.GONE);
    }

    /* ---------------- 下载镜像 ---------------- */

    private static final String PHONE_APK_URL =
            "https://github.com/deku772/Icar03/releases/latest/download/IcarLyrics-Phone.apk";
    private static final String CAR_APK_URL =
            "https://github.com/deku772/Icar03/releases/latest/download/IcarLyrics-Car.apk";
    private static final String MIRROR_PREFIX = "https://ghfast.top/";

    private void showMirrorDownloadDialog() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = getResources().getDimensionPixelSize(R.dimen.icar_dialog_pad);
        body.setPadding(pad, pad, pad, pad);

        TextView tip = UiKit.caption2(this, "下载最新 Release APK（车机 / 手机）。国内网络可选用镜像加速。");
        tip.setGravity(Gravity.CENTER);
        body.addView(tip);

        final boolean[] useMirror = {false};
        CheckBox cb = new CheckBox(this);
        cb.setText("使用国内镜像（ghfast.top）");
        cb.setTextColor(UiKit.color(this, R.color.icar_text_secondary));
        body.addView(cb);

        ImageView qrCar = new ImageView(this);
        ImageView qrPhone = new ImageView(this);
        int qs = UiKit.px(this, R.dimen.icar_qr_size);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_HORIZONTAL);
        row.addView(qrCar, new LinearLayout.LayoutParams(qs, qs));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(qs, qs);
        plp.leftMargin = UiKit.dpI(this, 20);
        qrPhone.setLayoutParams(plp);
        row.addView(qrPhone);
        body.addView(row);
        TextView lab = UiKit.caption2(this, "左：车机端 · 右：手机端");
        lab.setGravity(Gravity.CENTER);
        body.addView(lab);

        Runnable[] render = new Runnable[1];
        render[0] = () -> {
            try {
                String prefix = useMirror[0] ? MIRROR_PREFIX : "";
                qrCar.setImageBitmap(QrEncoder.encode(prefix + CAR_APK_URL, 4));
                qrPhone.setImageBitmap(QrEncoder.encode(prefix + PHONE_APK_URL, 4));
            } catch (Exception ignored) {}
        };
        render[0].run();
        cb.setOnCheckedChangeListener((bv, checked) -> {
            useMirror[0] = checked;
            render[0].run();
        });

        showLocalDialog("下载 APK（可选镜像）", body);
    }
}
