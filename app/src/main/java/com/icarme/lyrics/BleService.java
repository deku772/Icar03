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
    private final AdvertiseCallback advCb = new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Log.i(TAG, "advertising started");
        }
        @Override public void onStartFailure(int errorCode) {
            Log.w(TAG, "advertising failed: " + errorCode);
        }
    };

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
        if (bm == null) { Log.w(TAG, "no bluetooth manager"); return; }
        BluetoothAdapter adapter = bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) { Log.w(TAG, "bluetooth off"); return; }

        try {
            gattServer = bm.openGattServer(this, serverCallback);
            if (gattServer == null) { Log.w(TAG, "openGattServer null"); return; }

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
                    AdvertiseData data = new AdvertiseData.Builder()
                            .setIncludeDeviceName(true)
                            .addServiceUuid(new ParcelUuid(SVC_LYRICS))
                            .build();
                    advertiser.startAdvertising(settings, data, advCb);
                }
            }
            Log.i(TAG, "GATT server ready");
        } catch (Exception e) {
            Log.e(TAG, "startBle failed", e);
        }
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
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "client disconnected: " + device.getAddress());
                reassembler.reset();
            }
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
                    Reassembler.Frame f = reassembler.feed(value);
                    if (f != null) OverlayService.push(f.json);
                } else if (CH_PROGRESS.equals(uuid) || CH_CMD.equals(uuid)) {
                    if (value != null && value.length > 0) {
                        OverlayService.push(new String(value, java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "handle write failed", e);
            }
            if (responseNeeded) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value);
            }
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                             BluetoothGattDescriptor descriptor,
                                             boolean preparedWrite, boolean responseNeeded,
                                             int offset, byte[] value) {
            if (responseNeeded) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value);
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
