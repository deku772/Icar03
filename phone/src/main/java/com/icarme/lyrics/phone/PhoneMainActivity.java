package com.icarme.lyrics.phone;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 手机端主界面：权限引导 + 选择车机（记住默认） + 服务开关 + 状态显示。
 */
public class PhoneMainActivity extends Activity {

    private static final int REQ_PERMS = 1;

    private Button btnService;
    private Button btnDevice;
    private TextView tvStatus;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (getResources().getDisplayMetrics().density * 20);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("IcarLyrics 手机端\n手机取词 → BLE 推送 → 车机渲染");
        title.setTextSize(20);
        root.addView(title);

        tvStatus = new TextView(this);
        tvStatus.setPadding(0, pad, 0, 0);
        root.addView(tvStatus);

        Button btnPerms = new Button(this);
        btnPerms.setText("1. 授权（蓝牙/定位/通知使用权）");
        btnPerms.setOnClickListener(v -> requestPermissions());
        root.addView(btnPerms);

        btnDevice = new Button(this);
        btnDevice.setText("2. 选择车机");
        btnDevice.setOnClickListener(v -> pickDevice());
        root.addView(btnDevice);

        btnService = new Button(this);
        btnService.setText("3. 启动推送服务");
        btnService.setOnClickListener(v -> toggleService());
        root.addView(btnService);

        TextView help = new TextView(this);
        help.setText("使用说明：\n\n"
                + "1) 手机蓝牙连接车机（系统设置里配对）\n"
                + "2) 授权（蓝牙/定位/通知使用权）\n"
                + "3) 点「选择车机」，从列表里选中你的车机（只需一次，\n"
                + "    以后默认自动连接）\n"
                + "4) 启动推送服务\n"
                + "5) 随便用任何音乐 App 放歌\n"
                + "6) 车机端悬浮窗自动显示逐字歌词\n\n"
                + "车机全程不联网，歌词由手机取好后经 BLE 推送。");
        help.setTextSize(13);
        ScrollView sv = new ScrollView(this);
        sv.addView(help);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = pad;
        sv.setLayoutParams(lp);
        root.addView(sv);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        String enabled = Settings.Secure.getString(
                getContentResolver(), "enabled_notification_listeners");
        boolean nl = enabled != null && enabled.contains(getPackageName());

        String dev = getSharedPreferences("icarlyrics_phone", MODE_PRIVATE)
                .getString("target_device", null);
        String devName = null;
        if (dev != null) {
            try {
                android.bluetooth.BluetoothManager bm =
                        (android.bluetooth.BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
                android.bluetooth.BluetoothDevice d = bm.getAdapter().getRemoteDevice(dev);
                devName = d.getName() == null ? dev : d.getName();
            } catch (Exception e) {
                devName = dev;
            }
        }

        tvStatus.setText("通知使用权: " + (nl ? "已授权" : "未授权")
                + "\n推送服务: " + (PlaybackService.running ? "运行中" : "已停止"));
        btnService.setText(PlaybackService.running ? "停止推送服务" : "3. 启动推送服务");
        btnDevice.setText(devName == null ? "2. 选择车机" : "已选车机: " + devName);
    }

    /* ---------------- 选择车机 ---------------- */

    private void pickDevice() {
        new BleClient(this, (s, d) -> {}).scanForDevices(devices ->
                ui.post(() -> showDeviceDialog(devices)));
    }

    private void showDeviceDialog(List<BleClient.DeviceInfo> devices) {
        if (devices.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("未发现设备")
                    .setMessage("请确认：1) 蓝牙已开启 2) 车机已开机并运行 IcarLyrics 3) 手机已与车机配对。\n\n然后重试。")
                    .setPositiveButton("好", null)
                    .show();
            return;
        }
        String[] names = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            BleClient.DeviceInfo di = devices.get(i);
            names[i] = di.toString() + (di.bonded ? "  [已配对]" : "");
        }
        new AlertDialog.Builder(this)
                .setTitle("选择车机（只需一次，以后默认连接）")
                .setItems(names, (d, which) -> {
                    BleClient.DeviceInfo sel = devices.get(which);
                    PlaybackService.remoteSelectDevice(sel.address);
                    refreshStatus();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /* ---------------- 权限与服务 ---------------- */

    private void requestPermissions() {
        String[] perms = {
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION
        };
        boolean need = false;
        for (String p : perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) { need = true; break; }
        }
        if (need) {
            requestPermissions(perms, REQ_PERMS);
        }
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Exception ignored) {}
    }

    private void toggleService() {
        if (PlaybackService.running) {
            stopService(new Intent(this, PlaybackService.class));
        } else {
            startForegroundService(new Intent(this, PlaybackService.class));
        }
        refreshStatus();
    }
}