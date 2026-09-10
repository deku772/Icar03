package com.icarme.lyrics;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 车机端 BLE GATT 客户端（v2.0 角色对调）。
 *
 * 背景：v1.x 车机作为 GATT 服务端广播，手机主动连接。手机厂商车联组件
 * （数字钥匙/Carlink 等）扫描到车机广播后探查、强制断开整条 ACL → 循环断连。
 * 此行为与手机品牌强相关，App 层无法根治。
 *
 * 解决：角色对调——车机作为 Central 主动连接手机，手机只做被动 GATT 服务端。
 * 车机发起 MTU 协商（自身上限 512，请求 247 安全，绕开手机栈 517 问题），
 * 发现服务后订阅 Notify，歌词/进度/指令仍由手机推送过来。
 *
 * 手机识别：按 IcarLyrics 服务 UUID 扫描发现（不依赖 MAC）。
 * 手机作为 BLE 外设广播使用可解析随机地址(RPA)，MAC 会不断变化、
 * 且与系统设置显示地址不一致，因此"记住 MAC"不可靠——改为每次扫描发现。
 * 断线自动重新扫描重连（3→5→8→12→20s 退避）。
 */
public class BleService extends Service {

    public static final UUID SVC_LYRICS  = UUID.fromString("0000A100-CA21-4B58-9C2F-6B1F3C0E9A01");
    public static final UUID CH_LYRICS   = UUID.fromString("0000A101-CA21-4B58-9C2F-6B1F3C0E9A01");
    public static final UUID CH_PROGRESS = UUID.fromString("0000A102-CA21-4B58-9C2F-6B1F3C0E9A01");
    public static final UUID CH_CMD      = UUID.fromString("0000A103-CA21-4B58-9C2F-6B1F3C0E9A01");
    public static final UUID DESC_CCCD   = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final String TAG = "IcarLyrics.Ble";
    private static final int MTU_REQUEST = 247;
    private static final long CONNECT_WATCHDOG_MS = 15000;
    private static final long SCAN_TIMEOUT_MS = 15000;
    private static final long[] RETRY_DELAYS = {3000, 5000, 8000, 12000, 20000};

    public static volatile boolean running = false;

    /** 连接状态文本（主界面/悬浮窗展示）：未配置手机/连接中/已连接/重连倒计时等 */
    public static volatile String advState = "未启动";

    /** 已配置的手机 MAC（主界面展示用） */
    public static volatile String phoneMac = "";

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic chLyrics;
    private BluetoothGattCharacteristic chProgress;
    private BluetoothGattCharacteristic chCmd;
    private int mtu = 20;
    private Handler main;
    private final Reassembler reassembler = new Reassembler();
    private int retryIndex = 0;
    private boolean userStopped = false;
    private boolean subscribed = false;
    private boolean scanning = false;

    /** 接收统计（供渲染层展示诊断断点） */
    public static volatile int fragCount = 0;
    public static volatile int frameCount = 0;
    public static volatile int progCount = 0;
    public static volatile long lastFragMs = 0;
    public static volatile String lastFrameBrief = "";

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        userStopped = false;
        main = new Handler(Looper.getMainLooper());
        startForeground();
        main.post(this::startConnect);
    }

    @Override
    public void onDestroy() {
        running = false;
        userStopped = true;
        main.removeCallbacksAndMessages(null);
        stopScanning();
        closeGatt();
        advState = "未启动";
        reportState();
        stopForeground(true);
        super.onDestroy();
    }

    /* ---------------- 连接管理 ---------------- */

    private void startConnect() {
        if (userStopped || subscribed || scanning || gatt != null) return;

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = (bm == null) ? null : bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            advState = "蓝牙未开启";
            reportState();
            scheduleRetry();
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            advState = "缺少定位权限，无法扫描手机";
            reportState();
            scheduleRetry();
            return;
        }
        if (adapter.getBluetoothLeScanner() == null) {
            advState = "LE扫描不可用";
            reportState();
            scheduleRetry();
            return;
        }

        advState = "扫描发现手机中…";
        reportState();
        try {
            List<ScanFilter> filters = new ArrayList<>();
            filters.add(new ScanFilter.Builder()
                    .setServiceUuid(new ParcelUuid(SVC_LYRICS)).build());
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            adapter.getBluetoothLeScanner().startScan(filters, settings, scanCb);
            scanning = true;
            main.postDelayed(scanTimeout, SCAN_TIMEOUT_MS);
        } catch (Exception e) {
            Log.w(TAG, "startScan failed", e);
            advState = "扫描发起失败";
            reportState();
            scheduleRetry();
        }
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            stopScanning();
            BluetoothDevice device = result.getDevice();
            phoneMac = device.getAddress();   /* 仅诊断显示，不作为持久身份 */
            Log.i(TAG, "found IcarLyrics phone address=" + phoneMac
                    + " rssi=" + result.getRssi());
            advState = "发现手机，连接中…";
            reportState();
            connectDevice(device);
        }

        @Override public void onScanFailed(int errorCode) {
            stopScanning();
            advState = "扫描失败(" + errorCode + ")";
            reportState();
            scheduleRetry();
        }
    };

    private final Runnable scanTimeout = () -> {
        if (!scanning) return;
        stopScanning();
        advState = "未发现手机";
        reportState();
        scheduleRetry();
    };

    private void stopScanning() {
        main.removeCallbacks(scanTimeout);
        if (!scanning) return;
        scanning = false;
        try {
            BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = (bm == null) ? null : bm.getAdapter();
            if (adapter != null && adapter.getBluetoothLeScanner() != null) {
                adapter.getBluetoothLeScanner().stopScan(scanCb);
            }
        } catch (Exception ignored) {}
    }

    private void connectDevice(BluetoothDevice device) {
        closeGatt();
        try {
            gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (Exception e) {
            Log.w(TAG, "connectGatt failed", e);
            advState = "连接发起失败";
            reportState();
            scheduleRetry();
            return;
        }
        main.postDelayed(connectWatchdog, CONNECT_WATCHDOG_MS);
    }

    private final Runnable connectWatchdog = new Runnable() {
        @Override public void run() {
            if (subscribed) return;   /* 已完成握手 */
            Log.w(TAG, "connect watchdog fired");
            closeGatt();
            advState = "连接超时";
            reportState();
            scheduleRetry();
        }
    };

    private void closeGatt() {
        stopScanning();
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }
        chLyrics = chProgress = chCmd = null;
        subscribed = false;
        mtu = 20;
    }

    private void scheduleRetry() {
        if (userStopped) return;
        long delay = RETRY_DELAYS[Math.min(retryIndex, RETRY_DELAYS.length - 1)];
        retryIndex++;
        advState = advState + "，" + (delay / 1000) + "s后重试";
        reportState();
        main.postDelayed(this::startConnect, delay);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "connected to phone");
                main.removeCallbacks(connectWatchdog);
                advState = "已连接，协商MTU…";
                reportState();
                g.requestMtu(MTU_REQUEST);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "phone disconnected status=" + status);
                reassembler.reset();
                subscribed = false;
                closeGatt();
                advState = "手机连接断开(" + status + ")";
                reportState();
                scheduleRetry();
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            BleService.this.mtu = Math.max(23, mtu);
            Log.i(TAG, "MTU=" + mtu + " status=" + status);
            advState = "MTU=" + BleService.this.mtu + "，发现服务…";
            reportState();
            g.discoverServices();
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                advState = "服务发现失败(" + status + ")";
                reportState();
                closeGatt();
                scheduleRetry();
                return;
            }
            BluetoothGattService svc = g.getService(SVC_LYRICS);
            if (svc == null) {
                advState = "手机未提供IcarLyrics服务";
                reportState();
                closeGatt();
                scheduleRetry();
                return;
            }
            chLyrics = svc.getCharacteristic(CH_LYRICS);
            chProgress = svc.getCharacteristic(CH_PROGRESS);
            chCmd = svc.getCharacteristic(CH_CMD);
            if (chLyrics == null) {
                advState = "歌词特征缺失(手机端版本旧)";
                reportState();
                closeGatt();
                scheduleRetry();
                return;
            }
            advState = "订阅Notify…";
            reportState();
            subscribe(g, chLyrics);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            if (subscribed) return;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                advState = "订阅失败(" + status + ")";
                reportState();
                closeGatt();
                scheduleRetry();
                return;
            }
            subscribed = true;
            retryIndex = 0;
            reassembler.reset();
            advState = "已连接手机(MTU=" + mtu + ")，等待推送";
            reportState();
            /* 订阅成功后继续订阅进度/指令特征（失败不影响歌词主链路） */
            if (chProgress != null) subscribe(g, chProgress);
            if (chCmd != null) subscribe(g, chCmd);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            handleNotify(characteristic);
        }
    };

    /** 使能本地通知接收 + 写 CCCD */
    private void subscribe(BluetoothGatt g, BluetoothGattCharacteristic ch) {
        try {
            g.setCharacteristicNotification(ch, true);
            BluetoothGattDescriptor cccd = ch.getDescriptor(DESC_CCCD);
            if (cccd == null) return;
            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            g.writeDescriptor(cccd);
        } catch (Exception e) {
            Log.w(TAG, "subscribe failed", e);
        }
    }

    /* ---------------- 数据接收（与 v1.x 服务端逻辑一致） ---------------- */

    private void handleNotify(BluetoothGattCharacteristic characteristic) {
        if (characteristic == null) return;
        UUID uuid = characteristic.getUuid();
        byte[] value;
        try {
            value = characteristic.getValue();
        } catch (SecurityException e) {
            return;
        }
        try {
            if (CH_LYRICS.equals(uuid)) {
                fragCount++;
                lastFragMs = System.currentTimeMillis();
                Reassembler.Frame f = reassembler.feed(value);
                if (f != null) {
                    frameCount++;
                    try {
                        org.json.JSONObject jo = new org.json.JSONObject(f.json);
                        lastFrameBrief = jo.optString("track", "?") + " / "
                                + jo.optString("artist", "") + " / "
                                + String.valueOf(jo.optString("lrc", "").length()) + "字 / "
                                + jo.optString("source", "?");
                    } catch (Exception e) {
                        lastFrameBrief = "解析失败";
                    }
                    OverlayService.push(f.json);
                }
                reportRx();
            } else if (CH_PROGRESS.equals(uuid) || CH_CMD.equals(uuid)) {
                if (value != null && value.length > 0) {
                    progCount++;
                    OverlayService.push(new String(value, java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "handle notify failed", e);
        }
    }

    private void reportRx() {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("type", "cmd");
            o.put("action", "rxStats");
            o.put("frag", fragCount);
            o.put("frame", frameCount);
            o.put("prog", progCount);
            o.put("lastFragMs", lastFragMs);
            o.put("brief", lastFrameBrief);
            OverlayService.push(o.toString());
        } catch (Exception ignored) {}
    }

    /** 连接状态变化：更新前台通知 + 推悬浮窗 */
    private void reportState() {
        main.post(() -> {
            updateNotification("BLE: " + advState);
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("type", "conn");
                o.put("advState", advState);
                OverlayService.push(o.toString());
            } catch (Exception ignored) {}
        });
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            Notification n;
            if (Build.VERSION.SDK_INT >= 26) {
                n = new Notification.Builder(this, "icarlyrics_ble")
                        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                        .setContentTitle("IcarLyrics BLE")
                        .setContentText(text)
                        .setOngoing(true)
                        .build();
            } else {
                n = new Notification.Builder(this)
                        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                        .setContentText(text)
                        .setOngoing(true)
                        .build();
            }
            nm.notify(2, n);
        } catch (Exception ignored) {}
    }

    /** 已配置的手机 MAC 文本（主界面展示） */
    public static String phoneMacText() {
        String m = phoneMac;
        return (m == null || m.isEmpty()) ? "未配置" : m;
    }

    private void startForeground() {
        String chId = "icarlyrics_ble";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    chId, "IcarLyrics BLE", NotificationManager.IMPORTANCE_MIN));
        }
        Notification n;
        if (Build.VERSION.SDK_INT >= 26) {
            n = new Notification.Builder(this, chId)
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setContentTitle("IcarLyrics BLE")
                    .setContentText("连接手机并接收歌词推送")
                    .setOngoing(true)
                    .build();
        } else {
            n = new Notification.Builder(this)
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setContentText("连接手机并接收歌词推送")
                    .setOngoing(true)
                    .build();
        }
        startForeground(2, n);
    }
}
