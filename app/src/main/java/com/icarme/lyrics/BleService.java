package com.icarme.lyrics;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.UUID;

/**
 * BLE GATT 服务端：广播 IcarLyrics 服务，接收手机端（GATT Client）推送。
 *
 * 特征分工：
 *  - CH_LYRICS   Write  歌词分片（Reassembler 协议，见 Reassembler.java）
 *  - CH_PROGRESS Write  进度 JSON 单包 {"type":"progress",...}
 *  - CH_CMD      Write  控制 JSON 单包 {"type":"cmd",...}
 *  - CH_CTRL     Notify 预留：车机->手机回传事件（渲染就绪/仲裁）
 */
public class BleService extends Service {

    public static final UUID SVC_LYRICS  = UUID.fromString("0000A100-CA21-4B58-9C2F-6B1F3C0E9A01");
    public static final UUID CH_LYRICS   = UUID.fromString("0000A101-CA21-4B58-9C2F-6B1F3C0E9A01");
    public static final UUID CH_PROGRESS = UUID.fromString("0000A102-CA21-4B58-9C2F-6B1F3C0E9A01");
    public static final UUID CH_CMD      = UUID.fromString("0000A103-CA21-4B58-9C2F-6B1F3C0E9A01");
    public static final UUID CH_CTRL     = UUID.fromString("0000A104-CA21-4B58-9C2F-6B1F3C0E9A01");
    public static final UUID DESC_CCCD   = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final String TAG = "IcarLyrics.Ble";

    public static volatile boolean running = false;

    private BluetoothGattServer gattServer;
    private BluetoothLeAdvertiser advertiser;
    private Handler main;
    private final Reassembler reassembler = new Reassembler();

    /** 接收统计（v1.6 新增，供渲染层展示诊断断点） */
    public static volatile int fragCount = 0;   /* 收到的歌词分片数 */
    public static volatile int frameCount = 0;  /* 成功组装的完整歌词帧数 */
    public static volatile int progCount = 0;   /* 收到的进度包数 */
    public static volatile long lastFragMs = 0; /* 最近分片时刻 */
    public static volatile String lastFrameBrief = ""; /* 最近歌词帧摘要（曲目/行数/字节） */

    /** 广播/服务器状态（主界面与悬浮窗展示用） */
    public static volatile String advState = "未启动";   /* 未启动/启动中/广播中/广播失败:xx/蓝牙未开启/服务异常 */

    private final AdvertiseCallback advCb = new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Log.i(TAG, "advertising started");
            advState = "广播中";
            reportAdvState();
        }
        @Override public void onStartFailure(int errorCode) {
            Log.w(TAG, "advertising failed: " + errorCode);
            advState = "广播失败(" + advErrText(errorCode) + ")";
            reportAdvState();
        }
    };

    private static String advErrText(int code) {
        switch (code) {
            case AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE: return "数据超长";
            case AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS: return "广播器占用";
            case AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED: return "已在广播";
            case AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR: return "内部错误";
            case AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED: return "芯片不支持";
            default: return "错误" + code;
        }
    }

    /** 广播状态变化：更新前台通知 + 推悬浮窗 */
    private void reportAdvState() {
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

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        main = new Handler(Looper.getMainLooper());
        startForeground();
        main.post(this::startBle);
    }

    @Override
    public void onDestroy() {
        running = false;
        if (advertiser != null) {
            try { advertiser.stopAdvertising(advCb); } catch (Exception ignored) {}
            advertiser = null;
        }
        if (gattServer != null) {
            try { gattServer.close(); } catch (Exception ignored) {}
            gattServer = null;
        }
        stopForeground(true);
        super.onDestroy();
    }

    private void startBle() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null) {
            advState = "无蓝牙管理器";
            reportAdvState();
            return;
        }
        BluetoothAdapter adapter = bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            advState = "蓝牙未开启";
            reportAdvState();
            return;
        }

        try {
            gattServer = bm.openGattServer(this, serverCallback);
            if (gattServer == null) {
                advState = "GATT服务创建失败";
                reportAdvState();
                return;
            }

            BluetoothGattCharacteristic lyrics = new BluetoothGattCharacteristic(
                    CH_LYRICS,
                    BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE);

            BluetoothGattCharacteristic progress = new BluetoothGattCharacteristic(
                    CH_PROGRESS,
                    BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE);

            BluetoothGattCharacteristic cmd = new BluetoothGattCharacteristic(
                    CH_CMD,
                    BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE);

            BluetoothGattCharacteristic ctrl = new BluetoothGattCharacteristic(
                    CH_CTRL,
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ);
            ctrl.addDescriptor(new BluetoothGattDescriptor(
                    DESC_CCCD, BluetoothGattDescriptor.PERMISSION_WRITE));

            BluetoothGattService svc = new BluetoothGattService(
                    SVC_LYRICS, BluetoothGattService.SERVICE_TYPE_PRIMARY);
            svc.addCharacteristic(lyrics);
            svc.addCharacteristic(progress);
            svc.addCharacteristic(cmd);
            svc.addCharacteristic(ctrl);
            gattServer.addService(svc);

            if (adapter.isMultipleAdvertisementSupported()) {
                advertiser = adapter.getBluetoothLeAdvertiser();
                if (advertiser != null) {
                    AdvertiseSettings settings = new AdvertiseSettings.Builder()
                            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                            .setConnectable(true)
                            .setTimeout(0)
                            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                            .build();
                    /* 广播包上限 31 字节：Flags(3) + 128位UUID(18) = 21，设备名(13+) 放不进，
                     * 必须放扫描响应包，否则 ADVERTISE_FAILED_DATA_TOO_LARGE 直接广播失败 */
                    AdvertiseData data = new AdvertiseData.Builder()
                            .setIncludeDeviceName(false)
                            .setIncludeTxPowerLevel(false)
                            .addServiceUuid(new ParcelUuid(SVC_LYRICS))
                            .build();
                    AdvertiseData scanRsp = new AdvertiseData.Builder()
                            .setIncludeDeviceName(true)
                            .build();
                    advState = "启动中";
                    reportAdvState();
                    advertiser.startAdvertising(settings, data, scanRsp, advCb);
                } else {
                    advState = "未取得广播器";
                    reportAdvState();
                }
            } else {
                advState = "芯片不支持多广播";
                reportAdvState();
            }
            Log.i(TAG, "GATT server ready, adv=" + advState);
        } catch (Exception e) {
            Log.e(TAG, "startBle failed", e);
            advState = "服务异常: " + e.getClass().getSimpleName();
            reportAdvState();
        }
    }

    /** 更新 BLE 前台通知文本（广播/连接状态可视化） */
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

    public static String advertisedName() {
        try {
            IcarApp app = IcarApp.get();
            if (app == null) return "-";
            BluetoothManager bm = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter a = (bm == null) ? null : bm.getAdapter();
            return (a == null || a.getName() == null) ? "-" : a.getName();
        } catch (Exception e) {
            return "-";
        }
    }

    private final BluetoothGattServerCallback serverCallback = new BluetoothGattServerCallback() {

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "client connected: " + device.getAddress());
                pushConn(true, device);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "client disconnected: " + device.getAddress());
                reassembler.reset();
                pushConn(false, device);
            }
        }

        private void pushConn(boolean on, BluetoothDevice device) {
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("type", "conn");
                o.put("connected", on);
                String name = null;
                try { name = device.getName(); } catch (SecurityException ignored) {}
                o.put("device", name == null ? device.getAddress() : name);
                OverlayService.push(o.toString());
            } catch (Exception ignored) {}
        }

        @Override
        public void onMtuChanged(BluetoothDevice device, int mtu) {
            Log.i(TAG, "MTU=" + mtu);
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic characteristic,
                                                 boolean preparedWrite, boolean responseNeeded,
                                                 int offset, byte[] value) {
            UUID uuid = characteristic.getUuid();
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
                Log.w(TAG, "handle write failed", e);
            }
            /* v1.8.2：回空响应（标准 Write Response 无载荷）。
             * 旧代码回传整个 value，在 MTU 23 时大包会被截断/异常，徒增空中开销。 */
            if (responseNeeded) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, new byte[0]);
            }
        }

        /** 接收统计 → 渲染层（断点诊断可见） */
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

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                             BluetoothGattDescriptor descriptor,
                                             boolean preparedWrite, boolean responseNeeded,
                                             int offset, byte[] value) {
            if (responseNeeded) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, new byte[0]);
            }
        }
    };

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
                    .setContentText("等待手机连接并推送歌词")
                    .setOngoing(true)
                    .build();
        } else {
            n = new Notification.Builder(this)
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setOngoing(true)
                    .build();
        }
        startForeground(2, n);
    }
}
