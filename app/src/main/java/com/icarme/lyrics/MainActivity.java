package com.icarme.lyrics;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 主界面（v2.1 深色车机风格）：
 * 状态卡片（彩色指示点）+ 大号圆角按钮 + 帮助卡片。
 *
 * v2.0：车机为 GATT 客户端，扫描发现手机（IcarLyrics 服务 UUID）后直连，
 * 不再手工配置 MAC（手机外设地址随机化，存 MAC 不可靠）。
 */
public class MainActivity extends Activity {

    private static final long SCAN_MS = 10000;

    /* 深色主题色板 */
    private static final int C_BG        = 0xFF0B0F17;
    private static final int C_CARD      = 0xFF141A28;
    private static final int C_STROKE    = 0xFF232C42;
    private static final int C_TEXT      = 0xFFEFF2F8;
    private static final int C_TEXT_DIM  = 0xFF8A93A8;
    private static final int C_PRIMARY   = 0xFF5B93F0;
    private static final int C_GREEN     = 0xFF4ADE80;
    private static final int C_RED       = 0xFFF87171;
    private static final int C_AMBER     = 0xFFFBBF24;

    private Button btnService;
    private Button btnScan;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ApkHttpServer apkServer = new ApkHttpServer();
    private AlertDialog installDialog;

    /* 扫描发现结果 */
    private final List<ScanResult> found = new ArrayList<>();
    private boolean scanning = false;

    /* 状态行控件（refreshStatus 动态更新点色与值） */
    private static final int ROW_COUNT = 8;
    private static final int ROW_PERM = 0, ROW_OVERLAY = 1, ROW_BLE = 2, ROW_CONN = 3,
            ROW_PHONE = 4, ROW_OFFSET = 5, ROW_ALIGN = 6, ROW_COLOR = 7;
    private final View[] rowDots = new View[ROW_COUNT];
    private final TextView[] rowValues = new TextView[ROW_COUNT];
    private TextView tvScanHint;

    private final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            boolean first = found.isEmpty();
            for (int i = 0; i < found.size(); i++) {
                if (found.get(i).getDevice().getAddress().equals(result.getDevice().getAddress())) {
                    found.set(i, result);   /* 刷新 RSSI */
                    return;
                }
            }
            found.add(result);
            /* 发现即弹出选择，不等 10s 扫满 */
            if (first) {
                ui.post(() -> {
                    if (scanning && !found.isEmpty()) showScanResult();
                });
            }
        }
        @Override public void onScanFailed(int errorCode) {
            scanning = false;
            btnScan.setText("扫描失败(" + errorCode + ")，点此重试");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(pad, pad, pad, pad);

        root.addView(buildHeader());
        root.addView(buildStatusCard());
        root.addView(buildButtons());
        root.addView(buildHelpCard());
        setContentView(root);

        /* 周期刷新状态行（服务启动/断连重试等变化即时可见） */
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (isFinishing()) return;
                refreshStatus();
                ui.postDelayed(this, 1000);
            }
        }, 500);

        if (!Settings.canDrawOverlays(this)) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) { }
        }
    }

    /* ---------------- 界面构建 ---------------- */

    private View buildHeader() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);

        /* Logo 徽章：圆角深底 + 蓝描边 + 前景矢量 */
        GradientDrawable badge = new GradientDrawable();
        badge.setCornerRadius(dp(16));
        badge.setColor(0xFF101725);
        badge.setStroke(dp(1), C_STROKE);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher_foreground);
        logo.setBackground(badge);
        logo.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(64), dp(64));
        lp.rightMargin = dp(16);
        logo.setLayoutParams(lp);
        h.addView(logo);

        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("IcarLyrics");
        title.setTextColor(C_TEXT);
        title.setTextSize(26);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        TextView sub = new TextView(this);
        sub.setText("车机悬浮歌词 · BLE 接收端");
        sub.setTextColor(C_TEXT_DIM);
        sub.setTextSize(14);
        t.addView(title);
        t.addView(sub);
        h.addView(t);
        return h;
    }

    private View buildStatusCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_bg);
        card.setPadding(dp(20), dp(16), dp(20), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(20);
        card.setLayoutParams(lp);

        String[] labels = {"悬浮窗权限", "悬浮服务", "BLE 服务", "连接状态", "手机地址", "歌词偏移", "对齐", "颜色"};
        for (int i = 0; i < ROW_COUNT; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));

            View dot = new View(this);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(C_GREEN);
            dot.setBackground(d);
            row.addView(dot, new LinearLayout.LayoutParams(dp(9), dp(9)));

            TextView label = new TextView(this);
            label.setText(labels[i]);
            label.setTextColor(C_TEXT_DIM);
            label.setTextSize(14);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    dp(88), LinearLayout.LayoutParams.WRAP_CONTENT);
            llp.leftMargin = dp(12);
            label.setLayoutParams(llp);
            row.addView(label);

            TextView value = new TextView(this);
            value.setTextColor(C_TEXT);
            value.setTextSize(15);
            value.setSingleLine(false);
            value.setMaxLines(2);
            row.addView(value, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            rowDots[i] = dot;
            rowValues[i] = value;
            card.addView(row);

            /* 歌词偏移行：内嵌调节按钮（词晚于声→提前，词早于声→延后；250ms 步进，±15s） */
            if (i == ROW_OFFSET) {
                LinearLayout ctl = new LinearLayout(this);
                ctl.setOrientation(LinearLayout.HORIZONTAL);
                ctl.setGravity(Gravity.CENTER_VERTICAL);
                Button later = new Button(new android.view.ContextThemeWrapper(this,
                        android.R.style.Widget_Material_Button_Borderless), null, 0);
                later.setText("延后");
                styleMiniButton(later);
                later.setOnClickListener(v -> {
                    OverlayService.adjustOffsetMs(-250);
                    refreshStatus();
                });
                Button earlier = new Button(new android.view.ContextThemeWrapper(this,
                        android.R.style.Widget_Material_Button_Borderless), null, 0);
                earlier.setText("提前");
                styleMiniButton(earlier);
                earlier.setOnClickListener(v -> {
                    OverlayService.adjustOffsetMs(250);
                    refreshStatus();
                });
                ctl.addView(later);
                ctl.addView(earlier);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                ctl.setLayoutParams(clp);
                row.addView(ctl);
            }
            /* 对齐行：居左 / 居中 / 居右 */
            if (i == ROW_ALIGN) {
                LinearLayout ctl = new LinearLayout(this);
                ctl.setOrientation(LinearLayout.HORIZONTAL);
                ctl.setGravity(Gravity.CENTER_VERTICAL);
                String[] als = {"left", "center", "right"};
                String[] names = {"居左", "居中", "居右"};
                for (int k = 0; k < 3; k++) {
                    final String al = als[k];
                    Button b = new Button(new android.view.ContextThemeWrapper(this,
                            android.R.style.Widget_Material_Button_Borderless), null, 0);
                    b.setText(names[k]);
                    styleMiniButton(b);
                    b.setOnClickListener(v -> {
                        OverlayService.setAlign(al);
                        refreshStatus();
                    });
                    ctl.addView(b);
                }
                row.addView(ctl);
            }
            /* 颜色行：白 / 黑 / 蓝 / 绿 / 琥珀 / 粉 */
            if (i == ROW_COLOR) {
                LinearLayout wrap = new LinearLayout(this);
                wrap.setOrientation(LinearLayout.HORIZONTAL);
                wrap.setGravity(Gravity.CENTER_VERTICAL);
                String[] cols = {"white", "black", "blue", "green", "amber", "pink"};
                String[] names = {"白", "黑", "蓝", "绿", "琥珀", "粉"};
                for (int k = 0; k < cols.length; k++) {
                    final String col = cols[k];
                    Button b = new Button(new android.view.ContextThemeWrapper(this,
                            android.R.style.Widget_Material_Button_Borderless), null, 0);
                    b.setText(names[k]);
                    b.setBackgroundResource(R.drawable.btn_ghost);
                    b.setTextColor(C_PRIMARY);
                    b.setTextSize(13);
                    b.setAllCaps(false);
                    b.setStateListAnimator(null);
                    LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(52), dp(44));
                    blp.leftMargin = dp(4);
                    b.setLayoutParams(blp);
                    b.setOnClickListener(v -> {
                        OverlayService.setColor(col);
                        refreshStatus();
                    });
                    wrap.addView(b);
                }
                row.addView(wrap);
            }
        }
        return card;
    }

    private void styleMiniButton(Button b) {
        b.setBackgroundResource(R.drawable.btn_ghost);
        b.setTextColor(C_PRIMARY);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setStateListAnimator(null);
        b.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(84), dp(48));
        lp.leftMargin = dp(6);
        b.setLayoutParams(lp);
    }

    private View buildButtons() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(18);
        box.setLayoutParams(blp);

        /* 统一蓝底主按钮：高度/间距一致，扫描 → 装机 → 启停 */
        btnScan = new Button(new android.view.ContextThemeWrapper(this,
                android.R.style.Widget_Material_Button_Borderless), null, 0);
        btnScan.setText("1 · 扫描发现手机");
        styleButton(btnScan, R.drawable.btn_primary, 0xFFFFFFFF, true);
        btnScan.setOnClickListener(v -> scanPhones());
        box.addView(btnScan);

        tvScanHint = new TextView(this);
        tvScanHint.setTextColor(C_TEXT_DIM);
        tvScanHint.setTextSize(13);
        tvScanHint.setGravity(Gravity.CENTER);
        tvScanHint.setPadding(0, dp(6), 0, dp(6));
        tvScanHint.setVisibility(View.GONE);
        box.addView(tvScanHint);

        Button btnInstall = new Button(new android.view.ContextThemeWrapper(this,
                android.R.style.Widget_Material_Button_Borderless), null, 0);
        btnInstall.setText("2 · 热点扫码装手机端");
        styleButton(btnInstall, R.drawable.btn_primary, 0xFFFFFFFF, true);
        btnInstall.setOnClickListener(v -> showInstallQr());
        box.addView(btnInstall);

        btnService = new Button(new android.view.ContextThemeWrapper(this,
                android.R.style.Widget_Material_Button_Borderless), null, 0);
        btnService.setText("3 · 启动歌词悬浮");
        styleButton(btnService, R.drawable.btn_primary, 0xFFFFFFFF, true);
        btnService.setOnClickListener(v -> toggleService());
        box.addView(btnService);
        return box;
    }

    private void styleButton(Button b, int bg, int textColor, boolean big) {
        b.setBackgroundResource(bg);
        b.setTextColor(textColor);
        b.setTextSize(17);
        b.setAllCaps(false);
        b.setStateListAnimator(null);
        b.setPadding(dp(18), 0, dp(18), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        lp.topMargin = dp(12);
        b.setLayoutParams(lp);
    }

    private static final String PREFS = "icarlyrics";
    private static final String KEY_SHOW_HELP = "show_adb_help";

    private View buildHelpCard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.card_bg);
        box.setPadding(dp(20), dp(16), dp(20), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(20);
        box.setLayoutParams(lp);

        boolean showHelp = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_SHOW_HELP, true);

        Button btnHelp = new Button(new android.view.ContextThemeWrapper(this,
                android.R.style.Widget_Material_Button_Borderless), null, 0);
        btnHelp.setText(showHelp ? "收起 ADB 授权帮助" : "ADB 授权帮助");
        btnHelp.setBackgroundResource(R.drawable.btn_ghost);
        btnHelp.setTextColor(C_PRIMARY);
        btnHelp.setTextSize(14);
        btnHelp.setAllCaps(false);
        btnHelp.setStateListAnimator(null);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        btnHelp.setLayoutParams(hlp);
        box.addView(btnHelp);

        final LinearLayout helpWrap = new LinearLayout(this);
        helpWrap.setOrientation(LinearLayout.VERTICAL);
        helpWrap.setVisibility(showHelp ? View.VISIBLE : View.GONE);

        ScrollView sv = new ScrollView(this);
        TextView help = new TextView(this);
        help.setText(AdbHelper.helpText());
        help.setTextColor(C_TEXT_DIM);
        help.setTextSize(12);
        help.setLineSpacing(dp(3), 1f);
        sv.addView(help);
        helpWrap.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(160)));

        final CheckBox cbHide = new CheckBox(this);
        cbHide.setText("不再显示（弄好授权后勾选）");
        cbHide.setTextColor(C_TEXT_DIM);
        cbHide.setTextSize(13);
        cbHide.setChecked(!showHelp);
        helpWrap.addView(cbHide);
        box.addView(helpWrap);

        btnHelp.setOnClickListener(v -> {
            boolean vis = helpWrap.getVisibility() != View.VISIBLE;
            helpWrap.setVisibility(vis ? View.VISIBLE : View.GONE);
            btnHelp.setText(vis ? "收起 ADB 授权帮助" : "ADB 授权帮助");
            if (!vis && cbHide.isChecked()) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_SHOW_HELP, false).apply();
            }
        });
        cbHide.setOnCheckedChangeListener((bv, checked) -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_SHOW_HELP, !checked).apply();
        });

        TextView repo = new TextView(this);
        repo.setText("项目地址：github.com/deku772/Icar03");
        repo.setTextColor(0xFF5B93F0);
        repo.setTextSize(12);
        repo.setGravity(Gravity.CENTER);
        repo.setPadding(0, dp(8), 0, 0);
        box.addView(repo);
        return box;
    }

    /* ---------------- 状态刷新 ---------------- */

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void setDot(int row, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        rowDots[row].setBackground(d);
    }

    private void refreshStatus() {
        boolean overlay = Settings.canDrawOverlays(this);
        boolean svc = OverlayService.running;
        boolean ble = BleService.running;
        String conn = BleService.advState;
        String phone = BleService.phoneMacText();

        setDot(ROW_PERM, overlay ? C_GREEN : C_RED);
        rowValues[ROW_PERM].setText(overlay ? "已授予" : "未授予（需 ADB 授权）");

        setDot(ROW_OVERLAY, svc ? C_GREEN : 0xFF4B5563);
        rowValues[ROW_OVERLAY].setText(svc ? "运行中" : "已停止");

        setDot(ROW_BLE, ble ? C_GREEN : 0xFF4B5563);
        rowValues[ROW_BLE].setText(ble ? "运行中" : "已停止");

        int connColor = 0xFF4B5563;
        if (conn != null) {
            if (conn.contains("已连接")) connColor = C_GREEN;
            else if (conn.contains("扫描") || conn.contains("连接中") || conn.contains("发现")) connColor = C_PRIMARY;
            else if (conn.contains("重试") || conn.contains("失败") || conn.contains("超时") || conn.contains("未发现")) connColor = C_AMBER;
        }
        setDot(ROW_CONN, connColor);
        rowValues[ROW_CONN].setText(conn == null || conn.isEmpty() ? "未启动" : conn);

        setDot(ROW_PHONE, phone.equals("未配置") ? C_AMBER : C_PRIMARY);
        rowValues[ROW_PHONE].setText(phone + (phone.equals("未配置") ? "（点上方按钮扫描）" : " · 地址会变，无需手填"));

        int off = OverlayService.getOffsetMs();
        setDot(ROW_OFFSET, off == 0 ? 0xFF4B5563 : C_PRIMARY);
        rowValues[ROW_OFFSET].setText(off == 0
                ? "已同步（词快按「延后」· 词慢按「提前」）"
                : String.format(java.util.Locale.US, "%s%.2fs · %s",
                        off > 0 ? "提前 " : "延后 ", Math.abs(off) / 1000f,
                        off > 0 ? "词晚于声" : "词早于声"));

        String al = OverlayService.getAlign();
        setDot(ROW_ALIGN, C_PRIMARY);
        rowValues[ROW_ALIGN].setText("left".equals(al) ? "居左" : "right".equals(al) ? "居右" : "居中");

        String col = OverlayService.getColor();
        setDot(ROW_COLOR, C_PRIMARY);
        rowValues[ROW_COLOR].setText("white".equals(col) ? "白" : "black".equals(col) ? "黑"
                : "blue".equals(col) ? "蓝" : "green".equals(col) ? "绿"
                : "amber".equals(col) ? "琥珀" : "粉");

        btnService.setText(svc ? "3 · 停止歌词悬浮" : "3 · 启动歌词悬浮");
    }

    /* ---------------- 扫描发现手机 ---------------- */

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
            try {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
            } catch (Exception ignored) {}
            showHint("缺少定位权限（BLE 扫描需要）\nADB: pm grant com.icarme.lyrics android.permission.ACCESS_FINE_LOCATION");
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
            showHint("请确保手机端推送服务已启动");
            startScanCountdown();
            ui.postDelayed(this::showScanResult, SCAN_MS);
        } catch (Exception e) {
            scanning = false;
            showHint("扫描发起失败: " + e);
        }
    }

    /** 扫描倒计时：按钮文字逐秒更新（10→0），结束自动恢复 */
    private void startScanCountdown() {
        final long deadline = System.currentTimeMillis() + SCAN_MS;
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (!scanning) { btnScan.setText("重新扫描"); return; }
                long remain = Math.max(0, deadline - System.currentTimeMillis());
                int foundN = found.size();
                btnScan.setText("扫描中… " + ((remain + 999) / 1000) + "s"
                        + (foundN > 0 ? " · 已发现 " + foundN : ""));
                if (remain > 0) ui.postDelayed(this, 250);
            }
        }, 100);
    }

    private void showHint(String s) {
        tvScanHint.setVisibility(View.VISIBLE);
        tvScanHint.setText(s);
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
        btnScan.setText("重新扫描");

        if (found.isEmpty()) {
            showHint("未发现 IcarLyrics 手机。请确认：手机端推送服务已启动、手机蓝牙开启、距离够近。");
            return;
        }
        tvScanHint.setVisibility(View.GONE);
        String[] names = new String[found.size()];
        for (int i = 0; i < found.size(); i++) {
            ScanResult r = found.get(i);
            String n = r.getScanRecord() == null ? null : r.getScanRecord().getDeviceName();
            if (n == null || n.isEmpty()) {
                try { n = r.getDevice().getName(); } catch (SecurityException ignored) {}
            }
            names[i] = (n == null || n.isEmpty() ? "未知设备" : n)
                    + "\n" + r.getDevice().getAddress() + " · 信号 " + r.getRssi();
        }
        new AlertDialog.Builder(new android.view.ContextThemeWrapper(this,
                android.R.style.Theme_Material_Dialog))
                .setTitle("选择手机")
                .setItems(names, (d, which) -> {
                    String mac = found.get(which).getDevice().getAddress();
                    BleService.phoneMac = mac;
                    refreshStatus();
                    restartBle();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 选择手机后重启 BLE 服务立即生效 */
    private void restartBle() {
        try {
            stopService(new Intent(this, BleService.class));
        } catch (Exception ignored) {}
        if (OverlayService.running || Settings.canDrawOverlays(this)) {
            try {
                if (!OverlayService.running) {
                    startService(new Intent(this, OverlayService.class));
                }
                startForegroundService(new Intent(this, BleService.class));
            } catch (Exception ignored) {}
        }
        ui.postDelayed(this::refreshStatus, 800);
    }

    private void toggleService() {
        if (OverlayService.running) {
            OverlayService.setAutoStart(false);
            stopService(new Intent(this, OverlayService.class));
            stopService(new Intent(this, BleService.class));
        } else {
            if (!Settings.canDrawOverlays(this)) {
                showHint("请先用 ADB 命令授予悬浮窗权限（见下方帮助）");
                return;
            }
            OverlayService.setAutoStart(true);
            startService(new Intent(this, OverlayService.class));
            startService(new Intent(this, BleService.class));
        }
        ui.postDelayed(this::refreshStatus, 300);
        ui.postDelayed(this::refreshStatus, 1500);
    }

    /* ---------------- 扫码安装手机端（车机内网分发） ---------------- */

    private byte[] loadPhoneApk() {
        try (java.io.InputStream in = getAssets().open("icarlyrics-phone.apk");
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private void showInstallQr() {
        byte[] apk = loadPhoneApk();
        if (apk == null) {
            showHint("未内嵌手机端 APK，请用完整包构建（会自动打包 phone-debug.apk）");
            return;
        }
        if (!apkServer.start(apk)) {
            showHint("本地下载服务启动失败（端口 " + ApkHttpServer.PORT + " 被占用？）");
            return;
        }

        /* 1) 尽力用系统 API 开热点；2) 读 SoftAP；3) 仍无则打开原生热点设置页 */
        HotspotInfo ap = HotspotInfo.read(this);
        if (!ap.apEnabled) {
            HotspotInfo.tryEnableAp(this);
            ui.postDelayed(() -> {
                HotspotInfo again = HotspotInfo.read(this);
                if (!again.apEnabled) HotspotInfo.openTetherSettings(this);
            }, 400);
        }

        String ip = ApkHttpServer.findLanIp();
        if (ip == null) {
            showHint("未找到内网 IP，正在打开系统热点设置…");
            HotspotInfo.openTetherSettings(this);
            ui.postDelayed(this::showInstallQr, 1500);
            return;
        }
        String url = ApkHttpServer.downloadUrl(ip);
        ap = HotspotInfo.read(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), dp(4));

        TextView tip = new TextView(this);
        if (ap.valid()) {
            tip.setText((ap.fromMemory ? "热点（上次手填）：" : "热点：") + ap.ssid
                    + (ap.apEnabled ? " · 已开启" : " · 请确认已开启")
                    + "\n手机扫码一步：入网 + 下载安装");
        } else {
            tip.setText("系统读不到热点 SSID（腾讯车联可能自管 AP）。\n"
                    + "请：打开系统热点 → 手机连上后填 SSID/密码点刷新；\n"
                    + "或已连同一 Wi-Fi 时直接扫右侧下载码。");
        }
        tip.setTextColor(C_TEXT_DIM);
        tip.setTextSize(13);
        tip.setPadding(0, 0, 0, dp(6));
        root.addView(tip);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0, dp(200), 1.1f));

        final EditText etSsid = new EditText(this);
        etSsid.setHint("SSID");
        etSsid.setTextColor(C_TEXT);
        etSsid.setHintTextColor(C_TEXT_DIM);
        etSsid.setTextSize(13);
        etSsid.setText(ap.valid() ? ap.ssid : "");
        left.addView(etSsid);
        final EditText etPass = new EditText(this);
        etPass.setHint("密码");
        etPass.setTextColor(C_TEXT);
        etPass.setHintTextColor(C_TEXT_DIM);
        etPass.setTextSize(13);
        etPass.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        if (ap.password != null) etPass.setText(ap.password);
        left.addView(etPass);

        TextView urlTv = new TextView(this);
        urlTv.setText(url);
        urlTv.setTextColor(C_PRIMARY);
        urlTv.setTextSize(11);
        urlTv.setPadding(0, dp(4), 0, 0);
        left.addView(urlTv);

        Button btnWifi = new Button(new android.view.ContextThemeWrapper(this,
                android.R.style.Widget_Material_Button_Borderless), null, 0);
        btnWifi.setText("刷新二维码");
        btnWifi.setBackgroundResource(R.drawable.btn_ghost);
        btnWifi.setTextColor(C_PRIMARY);
        btnWifi.setTextSize(13);
        btnWifi.setAllCaps(false);
        btnWifi.setStateListAnimator(null);
        Button btnOpenAp = new Button(new android.view.ContextThemeWrapper(this,
                android.R.style.Widget_Material_Button_Borderless), null, 0);
        btnOpenAp.setText("打开系统热点设置");
        btnOpenAp.setBackgroundResource(R.drawable.btn_ghost);
        btnOpenAp.setTextColor(C_PRIMARY);
        btnOpenAp.setTextSize(13);
        btnOpenAp.setAllCaps(false);
        btnOpenAp.setStateListAnimator(null);
        left.addView(btnOpenAp);
        btnOpenAp.setOnClickListener(v -> HotspotInfo.openTetherSettings(this));
        row.addView(left);

        final ImageView qrView = new ImageView(this);
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(dp(190), dp(190));
        qlp.leftMargin = dp(6);
        qrView.setLayoutParams(qlp);
        row.addView(qrView);
        root.addView(row);

        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            String ssid = etSsid.getText().toString().trim();
            String pass = etPass.getText().toString();
            String payload = url;
            if (!ssid.isEmpty()) {
                String wifi = pass.isEmpty()
                        ? "WIFI:T:nopass;S:" + escapeWifi(ssid) + ";;"
                        : "WIFI:T:WPA;S:" + escapeWifi(ssid) + ";P:" + escapeWifi(pass) + ";;";
                payload = wifi + "\n" + url;
            }
            try {
                qrView.setImageBitmap(QrEncoder.encode(payload, 4));
            } catch (Exception e) {
                try { qrView.setImageBitmap(QrEncoder.encode(url, 4)); }
                catch (Exception ignored) {}
            }
        };
        render[0].run();
        btnWifi.setOnClickListener(v -> {
            HotspotInfo.save(this, etSsid.getText().toString().trim(), etPass.getText().toString());
            render[0].run();
            showHint("已保存热点信息，下次打开自动带上");
        });

        installDialog = new AlertDialog.Builder(new android.view.ContextThemeWrapper(this,
                android.R.style.Theme_Material_Dialog))
                .setTitle("热点扫码装手机端")
                .setView(root)
                .setNegativeButton("关闭", null)
                .setOnDismissListener(d -> ui.postDelayed(apkServer::stop, 3 * 60 * 1000))
                .show();
    }

    private static String escapeWifi(String s) {
        return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
                .replace(":", "\\:").replace("\"", "\\\"");
    }

    @Override
    protected void onDestroy() {
        apkServer.stop();
        if (installDialog != null && installDialog.isShowing()) installDialog.dismiss();
        super.onDestroy();
    }

    private int dp(float v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }
}
