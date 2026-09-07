package com.icarme.lyrics.phone;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * BLE GATT 客户端：连接 IcarLyrics 车机 → 协商 MTU → 推送歌词/进度。
 *
 * 设备选择策略：手动选择一次后记住（SharedPreferences），
 * 之后默认直连所选设备；断线自动重连。
 */
class BleClient {

    interface Listener {
        void onState(String state, String detail);
    }

    interface DevicesCallback {
        void onDevices(List<DeviceInfo> devices);
    }

    /** 列表展示用设备信息（名称可为空，地址唯一） */
    static class DeviceInfo {
        final String address;
        final String name;
        final boolean bonded;

        DeviceInfo(String address, String name, boolean bonded) {
            this.address = address;
            this.name = name;
            this.bonded = bonded;
        }

        @Override
        public String toString() {
            return (name == null || name.isEmpty()) ? address : name + " (" + address + ")";
        }
    }

    static final UUID SVC_LYRICS  = UUID.fromString("0000A100-CA21-4B58-9C2F-6B1F3C0E9A01");
    static final UUID CH_LYRICS   = UUID.fromString("0000A101-CA21-4B58-9C2F-6B1F3C0E9A01");
    static final UUID CH_PROGRESS = UUID.fromString("0000A102-CA21-4B58-9C2F-6B1F3C0E9A01");
    static final UUID CH_CMD      = UUID.fromString("0000A103-CA21-4B58-9C2F-6B1F3C0E9A01");

    private static final String TAG = "IcarLyrics.Phone";
    private static final String PREFS = "icarlyrics_phone";
    private static final String KEY_DEVICE = "target_device";

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BlePacketizer packetizer = new BlePacketizer();
    private final Listener listener;

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic chLyrics;
    private BluetoothGattCharacteristic chProgress;
    private int mtu = 20;
    private String state = "idle";
    private String selectedAddress;
    private boolean reconnectEnabled = false;

    BleClient(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        this.selectedAddress = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_DEVICE, null);
    }

    String getState() { return state; }

    String getSelectedAddressText() { return selectedAddress; }

    String getSelectedName() {
        String addr = selectedAddress;
        if (addr == null) return null;
        try {
            BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothDevice d = bm.getAdapter().getRemoteDevice(addr);
            return d.getName() == null ? addr : d.getName();
        } catch (Exception e) {
            return addr;
        }
    }

    boolean isConnected() {
        return gatt != null && chLyrics != null;
    }

    /* ---------------- 设备选择 ---------------- */

    /** 手动选择设备并立即连接（记住默认，后续默认直连） */
    void connectTo(String address) {
        selectedAddress = address;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_DEVICE, address).apply();
        reconnectEnabled = true;
        reconnectNow();
    }

    void clearSelectedDevice() {
        selectedAddress = null;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_DEVICE).apply();
        reconnectEnabled = false;
        disconnect();
    }

    /* ---------------- 扫描与连接 ---------------- */

    /** 列出可连接设备：已配对设备 + 6 秒扫描发现的新设备 */
    void scanForDevices(final DevicesCallback cb) {
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = (bm == null) ? null : bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            cb.onDevices(new ArrayList<>());
            return;
        }

        List<DeviceInfo> all = new ArrayList<>();
        if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                all.add(new DeviceInfo(d.getAddress(), d.getName(), true));
            }
        }

        boolean scanning = false;
        try {
            if (hasPermission(Manifest.permission.BLUETOOTH_SCAN) && !adapter.isDiscovering()) {
                BluetoothAdapter.LeScanCallback leCb = (device, rssi, scanRecord) -> {
                    String n = device.getName();
                    if (n == null) return;
                    for (DeviceInfo di : all) {
                        if (di.address.equals(device.getAddress())) return;
                    }
                    all.add(new DeviceInfo(device.getAddress(), n, false));
                    cb.onDevices(new ArrayList<>(all));
                };
                adapter.startLeScan(leCb);
                scanning = true;
                main.postDelayed(() -> {
                    try { adapter.stopLeScan(leCb); } catch (Exception ignored) {}
                    cb.onDevices(new ArrayList<>(all));
                }, 6000);
            }
        } catch (Exception e) {
            Log.w(TAG, "scan error", e);
        }

        if (!scanning) {
            cb.onDevices(new ArrayList<>(all));
        }
    }

    /** 默认连接：已选设备直连；未选择时提示 */
    void connect() {
        if (isConnected()) return;
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

        if (selectedAddress != null) {
            try {
                BluetoothDevice d = adapter.getRemoteDevice(selectedAddress);
                /* 不做 DEVICE_TYPE 校验：车机为双模芯片（经典蓝牙+BLE 同 MAC），
                 * 系统对未做过 GATT 连接的设备常缓存为 CLASSIC/UNKNOWN，
                 * 实际可直接以 TRANSPORT_LE 发起 GATT 连接（connectDevice 内已指定） */
                connectDevice(d);
                return;
            } catch (IllegalArgumentException e) {
                selectedAddress = null;
            } catch (Exception e) {
                setState("error", "连接异常: " + e.getMessage());
                return;
            }
        }
        setState("idle", "请先选择车机设备（主界面点「选择车机」）");
    }

    private void connectDevice(BluetoothDevice device) {
        reconnectEnabled = true;
        setState("connecting", "正在连接: " + nameOf(device) + "（等握手，一般 5-15 秒）");
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);

        /* 连接超时看门狗：15 秒未完成服务发现则视为连接失败，关闭重试 */
        cancelConnectWatchdog();
        main.postDelayed(connectWatchdog, 15000);
    }

    /** 连接超时看门狗（区分"连接中"卡死与正常慢握手） */
    private final Runnable connectWatchdog = new Runnable() {
        @Override public void run() {
            if (state.equals("connecting")) {
                setState("error", "连接超时（15秒无响应）。请确认：车机已启动歌词悬浮、蓝牙已开启、距离够近");
                if (gatt != null) {
                    try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) {}
                }
                cleanup();
                if (reconnectEnabled && selectedAddress != null) {
                    main.postDelayed(BleClient.this::connect, 5000); /* 5秒后自动重试 */
                }
            }
        }
    };

    private void cancelConnectWatchdog() {
        main.removeCallbacks(connectWatchdog);
    }

    private static String nameOf(BluetoothDevice d) {
        try { return d.getName() == null ? d.getAddress() : d.getName(); }
        catch (SecurityException e) { return d.getAddress(); }
    }

    private void reconnectNow() {
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) {}
        }
        cleanup();
        if (selectedAddress != null) connect();
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                cancelConnectWatchdog();
                setState("connecting", "物理连接成功，协商 MTU…");
                g.requestMtu(512);
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                cancelConnectWatchdog();
                cleanup();
                setState("disconnected", "车机连接断开"
                        + (status != 0 ? "（错误码 " + status + "）" : ""));
                if (reconnectEnabled && selectedAddress != null) {
                    main.postDelayed(BleClient.this::connect, 3000); /* 自动重连已选设备 */
                }
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            BleClient.this.mtu = mtu;
            if (status == BluetoothGatt.GATT_SUCCESS) {
                setState("connecting", "MTU=" + mtu + "，发现服务…");
                g.discoverServices();
            } else {
                setState("connecting", "MTU 协商失败，发现服务…");
                g.discoverServices();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                setState("error", "服务发现失败（错误码 " + status + "）");
                return;
            }
            setState("connecting", "服务已发现，校验歌词特征…");
            BluetoothGattService svc = g.getService(SVC_LYRICS);
            if (svc == null) {
                setState("error", "车机未提供 IcarLyrics 服务（车机端版本旧或未启动）");
                return;
            }
            chLyrics = svc.getCharacteristic(CH_LYRICS);
            chProgress = svc.getCharacteristic(CH_PROGRESS);
            if (chLyrics == null) {
                setState("error", "歌词特征缺失（车机端版本旧）");
                return;
            }
            setState("connected", "车机已连接，等待播放…");
        }
    };

    /* ---------------- 推送 ---------------- */

    /** 推歌词 JSON（自动分片，逐片带响应写入）。返回 false=未连接或打包失败 */
    boolean pushLyrics(String json) {
        if (!isConnected()) return false;
        int payload = Math.max(20, mtu - 3 - 4); /* 减 ATT 头 3B + 分片头 4B */
        byte[][] pkts;
        try {
            pkts = packetizer.pack(json, payload);
        } catch (Exception e) {
            return false;
        }
        if (pkts == null || pkts.length == 0) return false;
        main.post(() -> writePackets(pkts, 0));
        return true;
    }

    /** 推进度/指令 JSON（单包，无分片头）。返回 false=未连接或超长 */
    boolean pushJson(String json) {
        if (!isConnected()) return false;
        byte[] data = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (data.length > mtu - 3) {
            Log.w(TAG, "progress json too large: " + data.length);
            return false;
        }
        main.post(() -> writeRaw(chProgress, data));
        return true;
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
        reconnectEnabled = false;
        cancelConnectWatchdog();
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) {}
        }
        cleanup();
    }

    private void cleanup() {
        cancelConnectWatchdog();
        gatt = null;
        chLyrics = null;
        chProgress = null;
        mtu = 20;
    }

    private void setState(String s, String detail) {
        state = s;
        PlaybackService.mon.bleState = s + (detail == null || detail.isEmpty() ? "" : " · " + detail);
        Listener l = listener;
        if (l != null) l.onState(s, detail);
    }

    private boolean hasPermission(String perm) {
        return context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }
}