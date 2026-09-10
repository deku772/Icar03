package com.icarme.lyrics.phone;

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
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 手机端 BLE GATT 服务端（v2.0 角色对调）。
 *
 * 背景：v1.x 手机作为 GATT Client 主动连接车机，被手机厂商车联组件
 * （OPPO/小米/华为的数字钥匙、Carlink、欢太配件等）扫描到车机广播后
 * 强制掐断整条 ACL，造成连接循环。此问题与手机品牌强相关，App 层无法根治。
 *
 * 解决：角色对调——手机只做被动 GATT 服务端，由车机（Central）按 MAC
 * 主动连接手机。手机不再主动发 GATT 连接，因此不再触发厂商组件对"车机身份"
 * 的探查与掐断。数据仍由手机通过 Notify 推送（歌词分片/进度/指令），
 * 分片协议（BlePacketizer）与车机端 Reassembler 保持不变。
 *
 * 手机端仅需 GATT Server，不需要定位/扫描权限，不持续扫描，更省电。
 */
class BlePeripheral {

    interface Listener {
        void onState(String state, String detail);
    }

    static final UUID SVC_LYRICS  = UUID.fromString("0000A100-CA21-4B58-9C2F-6B1F3C0E9A01");
    static final UUID CH_LYRICS   = UUID.fromString("0000A101-CA21-4B58-9C2F-6B1F3C0E9A01");
    static final UUID CH_PROGRESS = UUID.fromString("0000A102-CA21-4B58-9C2F-6B1F3C0E9A01");
    static final UUID CH_CMD      = UUID.fromString("0000A103-CA21-4B58-9C2F-6B1F3C0E9A01");
    static final UUID CH_CTRL     = UUID.fromString("0000A104-CA21-4B58-9C2F-6B1F3C0E9A01");
    static final UUID DESC_CCCD   = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final String TAG = "IcarLyrics.Phone.Peri";

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BlePacketizer packetizer = new BlePacketizer();
    private final Listener listener;

    private BluetoothGattServer gattServer;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattCharacteristic chLyrics;
    private BluetoothGattCharacteristic chProgress;
    private BluetoothGattCharacteristic chCmd;

    private int mtu = 20;
    private String state = "idle";
    private final Set<BluetoothDevice> connectedDevs = new HashSet<>();
    private final Set<BluetoothDevice> subscribedDevs = new HashSet<>();

    /* 串行 Notify 队列：writer=notifyCharacteristicChanged，ACK=onNotificationSent，
     * 保序且避免通知洪泛丢包；进度包可合并（droppable），歌词分片严格保序。 */
    private final BleWriteQueue notifyQueue =
            new BleWriteQueue(this::notifyRaw, this::onNotifyStalled);

    BlePeripheral(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    String getState() { return state; }

    boolean isConnected() {
        return gattServer != null && !subscribedDevs.isEmpty();
    }

    boolean hasAnyClient() {
        return gattServer != null && !connectedDevs.isEmpty();
    }

    /** Notify 队列统计（监控台展示） */
    BleWriteQueue.Stats writeStats() {
        return notifyQueue.stats();
    }

    /* ---------------- 启动 / 停止 ---------------- */

    void start() {
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = (bm == null) ? null : bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            setState("error", "蓝牙未开启");
            return;
        }

        try {
            gattServer = bm.openGattServer(context, serverCallback);
            if (gattServer == null) {
                setState("error", "GATT服务创建失败");
                return;
            }

            chLyrics = new BluetoothGattCharacteristic(
                    CH_LYRICS,
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    0);
            chProgress = new BluetoothGattCharacteristic(
                    CH_PROGRESS,
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    0);
            chCmd = new BluetoothGattCharacteristic(
                    CH_CMD,
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    0);

            addCccd(chLyrics);
            addCccd(chProgress);
            addCccd(chCmd);

            BluetoothGattService svc = new BluetoothGattService(
                    SVC_LYRICS, BluetoothGattService.SERVICE_TYPE_PRIMARY);
            svc.addCharacteristic(chLyrics);
            svc.addCharacteristic(chProgress);
            svc.addCharacteristic(chCmd);
            gattServer.addService(svc);

            startAdvertising(adapter);
            setState("idle", "手机端就绪，等待车机扫描连接…");
        } catch (Exception e) {
            Log.e(TAG, "start failed", e);
            setState("error", "服务异常: " + e.getClass().getSimpleName());
        }
    }

    private void addCccd(BluetoothGattCharacteristic ch) {
        ch.addDescriptor(new BluetoothGattDescriptor(
                DESC_CCCD, BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
    }

    /** 广播 IcarLyrics 服务（辅助发现入口；主连接走车机按服务 UUID 扫描）。
     *  自定义 UUID 不匹配任何厂商车联服务，不会触发手机端组件对"车机身份"的探查。
     *  注意：车机按 MAC 发起 LE 连接要求手机有可连接广播，此广播是必须项而非可选。
     *  不用 isMultipleAdvertisementSupported() 做闸门（部分机型误报 false），
     *  直接尝试，失败由回调日志可见。 */
    private void startAdvertising(BluetoothAdapter adapter) {
        try {
            if (advertiser == null) advertiser = adapter.getBluetoothLeAdvertiser();
            Log.i(TAG, "advertiser=" + advertiser
                    + " multiAdv=" + adapter.isMultipleAdvertisementSupported());
            if (advertiser == null) {
                setState("error", "未取得广播器（BLE 广播不可用），车机将无法连入");
                return;
            }
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setConnectable(true)
                    .setTimeout(0)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .build();
            AdvertiseData data = new AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .addServiceUuid(new ParcelUuid(SVC_LYRICS))
                    .build();
            AdvertiseData scanRsp = new AdvertiseData.Builder()
                    .setIncludeDeviceName(true)
                    .build();
            advertiser.startAdvertising(settings, data, scanRsp, advCb);
        } catch (Exception e) {
            Log.w(TAG, "advertise failed", e);
        }
    }

    /** 无参重载：自行取 adapter（断连后重启广播用） */
    private void startAdvertising() {
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = (bm == null) ? null : bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) return;
        startAdvertising(adapter);
    }

    private final AdvertiseCallback advCb = new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings s) {
            Log.i(TAG, "advertising started");
        }
        @Override public void onStartFailure(int code) {
            Log.w(TAG, "advertising failed: " + code);
            if (advertiser == null || gattServer == null) return;
            /* 广播失败按 5s 周期重试（部分栈启动早期会暂时失败） */
            main.postDelayed(this::retryAdvertise, 5000);
        }

        private void retryAdvertise() {
            if (advertiser == null || gattServer == null) return;
            try {
                advertiser.stopAdvertising(advCb);
            } catch (Exception ignored) {}
            try {
                advertiser.startAdvertising(
                        new AdvertiseSettings.Builder()
                                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                                .setConnectable(true).setTimeout(0)
                                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).build(),
                        new AdvertiseData.Builder().setIncludeDeviceName(false)
                                .addServiceUuid(new ParcelUuid(SVC_LYRICS)).build(),
                        new AdvertiseData.Builder().setIncludeDeviceName(true).build(),
                        advCb);
                Log.i(TAG, "advertising retry");
            } catch (Exception e) {
                Log.w(TAG, "advertising retry failed", e);
            }
        }
    };

    void stop() {
        notifyQueue.clear();
        main.removeCallbacks(restartAdvTask);   /* 防 stop 后广播被断连回调复活 */
        connectedDevs.clear();
        subscribedDevs.clear();
        if (advertiser != null) {
            try { advertiser.stopAdvertising(advCb); } catch (Exception ignored) {}
            advertiser = null;
        }
        if (gattServer != null) {
            try { gattServer.close(); } catch (Exception ignored) {}
            gattServer = null;
        }
        chLyrics = chProgress = chCmd = null;
        mtu = 20;
        setState("idle", "已停止");
    }

    /* ---------------- 推送 ---------------- */

    /** 推歌词 JSON（自动分片，经 Notify 队列串行逐片发送）。返回 false=未订阅或打包失败 */
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
        for (byte[] pkt : pkts) {
            notifyQueue.enqueue(chLyrics, pkt, false);   /* 歌词分片：严格保序 */
        }
        return true;
    }

    /** 推进度/指令 JSON（单包）。返回 false=未订阅或超长 */
    boolean pushJson(String json) {
        if (!isConnected()) return false;
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        if (data.length > mtu - 3) {
            Log.w(TAG, "notify json too large: " + data.length);
            return false;
        }
        notifyQueue.enqueue(chProgress, data, true);   /* 进度包：可合并 */
        return true;
    }

    /** 供 BleWriteQueue 调用的底层通知：发给所有已订阅设备。
     *  注意：4 参（带 byte[]）重载仅 API 33+ 存在，老手机会 NoSuchMethodError，
     *  必须用旧 3 参写法（先 setValue 再 notify）。 */
    private boolean notifyRaw(BluetoothGattCharacteristic ch, byte[] data) {
        if (gattServer == null || ch == null || subscribedDevs.isEmpty()) return false;
        boolean any = false;
        for (BluetoothDevice d : subscribedDevs) {
            try {
                ch.setValue(data);
                if (gattServer.notifyCharacteristicChanged(d, ch, false)) any = true;
            } catch (Exception ignored) {}
        }
        return any;
    }

    /** 通知发送卡死（长时间无 onNotificationSent）：清队列，等车机重连 */
    private void onNotifyStalled() {
        setState("error", "通知卡死，等待车机重连…");
    }

    /* ---------------- GATT Server 回调 ---------------- */

    private final BluetoothGattServerCallback serverCallback = new BluetoothGattServerCallback() {

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDevs.add(device);
                Log.i(TAG, "client connected: " + device.getAddress());
                setState("waiting", "车机已连接，等待订阅…");
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedDevs.remove(device);
                subscribedDevs.remove(device);
                Log.i(TAG, "client disconnected: " + device.getAddress());
                if (connectedDevs.isEmpty()) {
                    notifyQueue.clear();
                    setState("idle", "车机已断开，等待重连");
                    /* v2.0.1 关键修复：Android BLE 在连接建立时会自动暂停可连接广播，
                     * 断开后不会自动恢复——必须重新 startAdvertising，否则车机永远连不回。
                     * （车机重启/车机App重装/超距断连都会触发此路径）。
                     * 延迟 800ms 让底层栈先完成链路清理。 */
                    main.removeCallbacks(restartAdvTask);
                    main.postDelayed(restartAdvTask, 800);
                }
            }
        }

        @Override
        public void onMtuChanged(BluetoothDevice device, int mtu) {
            BlePeripheral.this.mtu = Math.max(23, mtu);
            Log.i(TAG, "MTU=" + mtu);
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                             BluetoothGattDescriptor descriptor,
                                             boolean preparedWrite, boolean responseNeeded,
                                             int offset, byte[] value) {
            try {
                byte[] v = value == null ? new byte[0] : value;
                boolean enable = v.length > 0 && (v[0] & 0x01) != 0;
                if (enable) {
                    subscribedDevs.add(device);
                } else {
                    subscribedDevs.remove(device);
                }
                if (responseNeeded) {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
                }
                if (enable) {
                    setState("connected", "车机已连接并订阅，等待放歌…");
                } else if (!subscribedDevs.isEmpty()) {
                    setState("connected", "车机已连接");
                }
            } catch (Exception e) {
                Log.w(TAG, "descriptor write failed", e);
                if (responseNeeded) {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
                }
            }
        }

        @Override
        public void onNotificationSent(BluetoothDevice device, int status) {
            notifyQueue.onWriteResult(status == BluetoothGatt.GATT_SUCCESS);
        }
    };

    /* ---------------- 生命周期 ---------------- */

    /** 断连后重启广播（去重：removeCallbacks 防止多次断连叠加） */
    private final Runnable restartAdvTask = new Runnable() {
        @Override public void run() {
            if (gattServer == null) return;   /* 已 stop()，不复活 */
            Log.i(TAG, "restarting advertising after disconnect");
            try { if (advertiser != null) advertiser.stopAdvertising(advCb); } catch (Exception ignored) {}
            startAdvertising();
        }
    };

    private void setState(String s, String detail) {
        state = s;
        PlaybackService.mon.bleState = s + (detail == null || detail.isEmpty() ? "" : " · " + detail);
        Listener l = listener;
        if (l != null) l.onState(s, detail);
    }
}
