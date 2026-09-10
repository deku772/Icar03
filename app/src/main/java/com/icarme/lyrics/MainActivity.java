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
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 主界面：授权引导 + 扫描发现手机（选一次记住） + 服务开关 + ADB 命令展示。
 * 车机上若无桌面图标场景，可通过 adb shell am start 拉起。
 *
 * v2.0：车机为 GATT 客户端，按扫描发现的手机 MAC 直连（手机广播 IcarLyrics
 * 服务 UUID）。不再手工填 MAC——手机系统会隐藏真实蓝牙地址，填了也对不上。
 */
public class MainActivity extends Activity {

    private static final long SCAN_MS = 10000;

    private Button btnService;
    private Button btnScan;
    private TextView tvStatus;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /* 扫描发现结果：address -> 展示名 */
    private final List<ScanResult> found = new ArrayList<>();
    private boolean scanning = false;

    private final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            for (ScanResult r : found) {
                if (r.getDevice().getAddress().equals(result.getDevice().getAddress())) {
                    found.set(found.indexOf(r), result);   /* 刷新 RSSI */
                    return;
                }
            }
            found.add(result);
        }
        @Override public void onScanFailed(int errorCode) {
            scanning = false;
            tvStatus.setText(tvStatus.getText() + "\n扫描失败(" + errorCode + ")：检查定位权限");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("IcarLyrics\n车机悬浮歌词（BLE 接收端）");
        title.setTextSize(20);
        root.addView(title);

        tvStatus = new TextView(this);
        tvStatus.setPadding(0, pad, 0, 0);
        root.addView(tvStatus);

        btnScan = new Button(this);
        btnScan.setText("1. 扫描发现手机（选一次记住）");
        btnScan.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { scanPhones(); }
        });
        root.addView(btnScan);

        btnService = new Button(this);
        btnService.setText("2. 启动/停止 歌词悬浮");
        btnService.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleService(); }
        });
        root.addView(btnService);

        TextView help = new TextView(this);
        help.setText(AdbHelper.helpText());
        help.setTextSize(13);
        ScrollView sv = new ScrollView(this);
        sv.addView(help);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = pad;
        sv.setLayoutParams(lp);
        root.addView(sv);

        setContentView(root);

        if (!Settings.canDrawOverlays(this)) {
            /* 车机无标准权限页时的兜底：走 ADB appop 授权，见下方命令说明 */
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) { /* 无对应页面则忽略 */ }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        boolean overlay = Settings.canDrawOverlays(this);
        boolean svc = OverlayService.running;
        boolean ble = BleService.running;
        tvStatus.setText("悬浮窗权限: " + (overlay ? "已授予" : "未授予（需 ADB）")
                + "\n悬浮服务: " + (svc ? "运行中" : "已停止")
                + "\nBLE 服务: " + (ble ? "运行中" : "已停止")
                + "\n连接状态: " + BleService.advState
                + "\n已选手机: " + BleService.phoneMacText());
        btnService.setText(svc ? "停止 歌词悬浮" : "2. 启动/停止 歌词悬浮");
    }

    /* ---------------- 扫描发现手机 ---------------- */

    private boolean hasPermission(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private void scanPhones() {
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = (bm == null) ? null : bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            tvStatus.setText("蓝牙未开启");
            return;
        }
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            try {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
            } catch (Exception ignored) {}
            tvStatus.setText("缺少定位权限（BLE 扫描需要）\nADB: pm grant com.icarme.lyrics "
                    + "android.permission.ACCESS_FINE_LOCATION");
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
            tvStatus.setText("正在扫描手机（10 秒）…请确保手机端推送服务已启动");
            btnScan.setText("扫描中…");
            ui.postDelayed(this::showScanResult, SCAN_MS);
        } catch (Exception e) {
            scanning = false;
            tvStatus.setText("扫描发起失败: " + e);
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
        btnScan.setText("1. 扫描发现手机（重新扫描）");

        if (found.isEmpty()) {
            tvStatus.setText("未发现 IcarLyrics 手机。\n请确认：手机端推送服务已启动、手机蓝牙开启、距离够近。");
            return;
        }
        String[] names = new String[found.size()];
        for (int i = 0; i < found.size(); i++) {
            ScanResult r = found.get(i);
            String n = r.getScanRecord() == null ? null : r.getScanRecord().getDeviceName();
            if (n == null || n.isEmpty()) {
                try { n = r.getDevice().getName(); } catch (SecurityException ignored) {}
            }
            names[i] = (n == null || n.isEmpty() ? "未知设备" : n)
                    + " (" + r.getDevice().getAddress() + ")  RSSI " + r.getRssi();
        }
        new AlertDialog.Builder(this)
                .setTitle("选择手机（记住，之后自动连接）")
                .setItems(names, (d, which) -> {
                    String mac = found.get(which).getDevice().getAddress();
                    getSharedPreferences("icarlyrics", MODE_PRIVATE)
                            .edit().putString("phone_device", mac).apply();
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
            stopService(new Intent(this, OverlayService.class));
            stopService(new Intent(this, BleService.class));
        } else {
            if (!Settings.canDrawOverlays(this)) {
                tvStatus.setText("请先用下方 ADB 命令授予悬浮窗权限");
                return;
            }
            startService(new Intent(this, OverlayService.class));
            startService(new Intent(this, BleService.class));
        }
        /* 服务/连接启动异步，延迟刷新状态 */
        ui.postDelayed(this::refreshStatus, 300);
        ui.postDelayed(this::refreshStatus, 1500);
    }
}
