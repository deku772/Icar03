package com.icarme.lyrics.phone;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.UUID;

/**
 * BLE GATT 客户端：扫描 IcarLyrics 车机 → 连接 → 协商 MTU → 推送歌词/进度。
 */
class BleClient {

    interface Listener {
        void onState(String state, String detail);
    }

    static final UUID SVC_LYRICS  = UUID.fromString("0000A100-CA21-4B58-9C2F-6B1F3C0E9A01");
    static final UUID CH_LYRICS   = UUID.fromString("0000A101-CA21-4B58-9C2F-6B1F3C0E9A01");
    static final UUID CH_PROGRESS = UUID.fromString("0000A102-CA21-4B58-9C2F-6B1F3C0E9A01");
    static final UUID CH_CMD      = UUID.fromString("0000A103-CA21-4B58-9C2F-6B1F3C0E9A01");

    private static final String TAG = "IcarLyrics.Phone";
    private static final String DEVICE_NAME_PREFIX = "iCAR";

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BlePacketizer packetizer = new BlePacketizer();
    private final Listener listener;

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic chLyrics;
    private BluetoothGattCharacteristic chProgress;
    private int mtu = 20;
    private String state = "idle";

    BleClient(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    String getState() { return state; }

    boolean isConnected() {
        return gatt != null && chLyrics != null;
    }

    /* ---------------- 扫描与连接 ---------------- */

    void connect() {
        if (isConnected()) return;
        setState("scanning", "正在扫描车机…");
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = (bm == null) ? null : bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            setState("error", "蓝牙未开启");
            return;
        }
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            setState("error", "缺少 BLUETOOTH_CONNECT 权限");
            return;
        }

        /* 已绑定设备优先直连；否则扫描 */
        for (BluetoothDevice d : adapter.getBondedDevices()) {
            if (d.getName() != null && d.getName().startsWith(DEVICE_NAME_PREFIX)) {
                connectDevice(d);
                return;
                }
        }
        setState("error", "未找到已配对的 iCAR 车机，请先在系统蓝牙里完成配对");
    }

    private void connectDevice(BluetoothDevice device) {
        setState("connecting", "连接中: " + device.getName());
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                g.requestMtu(512);
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                cleanup();
                setState("disconnected", "车机连接断开");
                main.postDelayed(BleClient.this::connect, 3000); /* 自动重连 */
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            BleClient.this.mtu = mtu;
            if (status == BluetoothGatt.GATT_SUCCESS) {
                gatt.discoverServices();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                setState("error", "服务发现失败");
                return;
            }
            android.bluetooth.BluetoothGattService svc = gatt.getService(SVC_LYRICS);
            if (svc == null) {
                setState("error", "车机未提供 IcarLyrics 服务");
                return;
            }
            chLyrics = svc.getCharacteristic(CH_LYRICS);
            chProgress = svc.getCharacteristic(CH_PROGRESS);
            if (chLyrics == null) {
                setState("error", "特征缺失");
                return;
            }
            setState("connected", "车机已连接，等待播放…");
        }
    };

    /* ---------------- 推送 ---------------- */

    /** 推歌词 JSON（自动分片，逐片带响应写入） */
    void pushLyrics(String json) {
        if (!isConnected()) return;
        int payload = Math.max(20, mtu - 3 - 4); /* 减 ATT 头 3B + 分片头 4B */
        byte[][] pkts = packetizer.pack(json, payload);
        main.post(() -> writePackets(pkts, 0));
    }

    /** 推进度/指令 JSON（单包，无分片头） */
    void pushJson(String json) {
        if (!isConnected()) return;
        byte[] data = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (data.length > mtu - 3) {
            Log.w(TAG, "progress json too large: " + data.length);
            return;
        }
        main.post(() -> writeRaw(chProgress, data));
    }

    private void writePackets(byte[][] pkts, int index) {
        if (!isConnected() || index >= pkts.length) return;
        boolean ok = writeRaw(chLyrics, pkts[index]);
        if (!ok) {
            setState("error", "写入失败（第 " + (index + 1) + "/" + pkts.length + " 片）");
            return;
        }
        main.postDelayed(() -> writePackets(pkts, index + 1), 15); /* 片间 15ms 防拥塞 */
    }

    private boolean writeRaw(BluetoothGattCharacteristic ch, byte[] data) {
        if (ch == null || gatt == null) return false;
        ch.setValue(data);
        ch.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        return gatt.writeCharacteristic(ch);
    }

    /* ---------------- 生命周期 ---------------- */

    void disconnect() {
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) {}
        }
        cleanup();
    }

    private void cleanup() {
        gatt = null;
        chLyrics = null;
        chProgress = null;
        mtu = 20;
    }

    private void setState(String s, String detail) {
        state = s;
        Listener l = listener;
        if (l != null) l.onState(s, detail);
    }

    private boolean hasPermission(String perm) {
        return context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }
}
