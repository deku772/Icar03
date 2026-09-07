package com.icarme.lyrics.phone;

import android.bluetooth.BluetoothGattCharacteristic;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * BLE 写入队列：所有写入（歌词分片 / 进度单包）串行排队，每片等
 * onCharacteristicWrite 回调（ACK）后才发下一个，严格满足 BLE 单在途
 * 写限制，杜绝"第 N 片写入失败"。
 *
 * 兼容车机端"无响应写"：若车机用 WRITE_TYPE_NO_RESPONSE（无 ACK 回调），
 * 本队列在 MAX_NOACK_MS 后强制放行下一片，避免死等。
 */
final class BleWriteQueue {

    private static final String TAG = "IcarLyrics.Phone.WriteQ";
    /** 无响应写超时：150ms 无回调则视为已发出 */
    private static final long MAX_NOACK_MS = 150;

    interface Writer {
        boolean write(BluetoothGattCharacteristic ch, byte[] data);
    }

    /** 写结果统计（v1.6 诊断）：ack=成功确认，fail=发起失败/写失败 */
    static final class Stats {
        volatile int ackCount = 0;
        volatile int failCount = 0;
    }

    private final Stats stats = new Stats();
    Stats stats() { return stats; }

    private static final class Item {
        final BluetoothGattCharacteristic ch;
        final byte[] data;
        Item(BluetoothGattCharacteristic ch, byte[] data) {
            this.ch = ch;
            this.data = data;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Writer writer;
    private final Deque<Item> queue = new ArrayDeque<>();
    private boolean busy = false;        /* 当前是否在等待 ACK */
    private boolean awaitAck = false;

    BleWriteQueue(Writer writer) { this.writer = writer; }

    /** 入队一份数据（自动串行发送） */
    synchronized void enqueue(BluetoothGattCharacteristic ch, byte[] data) {
        queue.addLast(new Item(ch, data));
        pumpLocked();
    }

    /** 由 onCharacteristicWrite 回调调用：当前片已完成，放行下一片 */
    synchronized void onWriteComplete() {
        awaitAck = false;
        main.removeCallbacks(noAckTimer);
        busy = false;
        stats.ackCount++;
        pumpLocked();
    }

    /** 由 GATT 断开/清除时调用：清空队列 */
    synchronized void clear() {
        queue.clear();
        awaitAck = false;
        main.removeCallbacks(noAckTimer);
        busy = false;
    }

    synchronized boolean isIdle() { return !busy && queue.isEmpty(); }

    private void pumpLocked() {
        if (busy) return;
        Item item = queue.pollFirst();
        if (item == null) return;
        busy = true;
        awaitAck = false;
        boolean ok;
        try {
            ok = writer.write(item.ch, item.data);
        } catch (Exception e) {
            Log.w(TAG, "write threw", e);
            ok = false;
        }
        if (!ok) {
            /* 写入发起失败：连接可能已断，丢弃队列避免死循环 */
            stats.failCount++;
            queue.clear();
            busy = false;
            return;
        }
        /* 等待 ACK；若为无响应写（无回调），由定时器兜底放行 */
        awaitAck = true;
        main.postDelayed(noAckTimer, MAX_NOACK_MS);
    }

    private final Runnable noAckTimer = new Runnable() {
        @Override public void run() {
            synchronized (BleWriteQueue.this) {
                if (awaitAck) onWriteComplete();
            }
        }
    };
}