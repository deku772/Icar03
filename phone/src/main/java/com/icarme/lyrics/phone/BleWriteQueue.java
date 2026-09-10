package com.icarme.lyrics.phone;

import android.bluetooth.BluetoothGattCharacteristic;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * BLE 写入队列 v2。
 *
 * v1.5.1 教训：150ms 无 ACK 便"伪放行"下一片，与迟到的真实回调竞争，
 * 产生并发在途写 → 违反 BLE 单在途限制 → GATT 栈拒绝后续写（失败 8 次）
 * → 链路丢包（ACK 95 车机只收到 39）→ 歌词分片序列被清空，永远组不成帧。
 *
 * v2 原则：
 *  1) WRITE_TYPE_DEFAULT（带响应写）保证 onCharacteristicWrite 必回调
 *     （成功或失败都会），绝不伪放行。超时 = 链路卡死 → 通知上层断开重连
 *  2) onWriteComplete 防重入：非 busy 状态直接忽略迟到/重复回调
 *  3) 写发起失败：重试当前片（3 次 × 60ms），不再清空整个队列丢分片
 *  4) 进度包（droppable）合并：队列中只保留最新一个，避免积压
 */
final class BleWriteQueue {

    private static final String TAG = "IcarLyrics.Phone.WriteQ";

    interface Writer {
        boolean write(BluetoothGattCharacteristic ch, byte[] data);
    }

    interface StallListener {
        /** 写入长时间无 ACK：链路疑似卡死，上层应断开重连 */
        void onStalled();
    }

    static final class Stats {
        volatile int ackCount = 0;   /* 真实 ACK 次数 */
        volatile int failCount = 0;  /* 重试 3 次仍发起失败的次数 */
    }

    private static final class Item {
        final BluetoothGattCharacteristic ch;
        final byte[] data;
        final boolean droppable;   /* 进度包：可被更新版本替换 */
        int retry = 0;
        Item(BluetoothGattCharacteristic ch, byte[] data, boolean droppable) {
            this.ch = ch; this.data = data; this.droppable = droppable;
        }
    }

    /** 带响应写超时：超过此时间无回调判定链路卡死（触发重连）。
     *  v1.8 用 1200ms 与车机（Android 9）迟到的真实 ACK 竞争产生并发在途写，
     *  v1.8.1 放宽到 3000ms 适配慢链路。 */
    private static final long STALL_MS = 3000;

    /* v1.8.2：发起失败/ACK 失败改为退避重试（60→100→200→400→800ms 封顶），
     * 重试次数 3→8；不再 180ms 内就升级杀连接（v1.8.1 循环重连的帮凶）。 */
    private static final long[] RETRY_DELAYS = {60, 100, 200, 400, 800};
    private static final int MAX_RETRY = 8;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Writer writer;
    private final StallListener stallListener;
    private final Deque<Item> queue = new ArrayDeque<>();
    private boolean busy = false;
    private Item current;   /* 在途分片（ACK 失败时退避重发，不推进队列） */

    final Stats stats = new Stats();

    Stats stats() { return stats; }

    BleWriteQueue(Writer writer, StallListener stallListener) {
        this.writer = writer;
        this.stallListener = stallListener;
    }

    /**
     * 入队。
     * droppable=true（进度包）：移除队列中旧进度包只留最新，防止积压拖慢歌词分片。
     * droppable=false（歌词分片）：严格保序，绝不丢弃。
     */
    synchronized void enqueue(BluetoothGattCharacteristic ch, byte[] data, boolean droppable) {
        if (droppable && !queue.isEmpty()) {
            Iterator<Item> it = queue.iterator();
            while (it.hasNext()) {
                if (it.next().droppable) it.remove();
            }
        }
        queue.addLast(new Item(ch, data, droppable));
        pumpLocked();
    }

    /** onCharacteristicWrite 回调：v1.8.2 区分 ACK 成败。
     *  成功 → 放行下一片；失败 → 退避重发当前片（不推进、不丢弃）。 */
    synchronized void onWriteResult(boolean ackOk) {
        if (!busy) return;   /* 防重入：迟到/重复回调直接忽略 */
        busy = false;
        Item item = current;
        current = null;
        main.removeCallbacks(stallTimer);
        if (ackOk) {
            stats.ackCount++;
            pumpLocked();
            return;
        }
        /* ACK status != 0：重发当前片 */
        if (item != null && item.retry < MAX_RETRY) {
            item.retry++;
            queue.addFirst(item);
            main.postDelayed(retryTask, delayFor(item.retry));
            return;
        }
        if (item != null) stats.failCount++;
        queue.clear();
        stallListener.onStalled();
    }

    /** GATT 断开/重置：清空队列 */
    synchronized void clear() {
        queue.clear();
        busy = false;
        current = null;
        main.removeCallbacks(stallTimer);
        main.removeCallbacks(retryTask);
    }

    /** 退避延迟：第 retry 次重试 → 60/100/200/400/800…ms 封顶 */
    private static long delayFor(int retry) {
        if (retry <= 0) return RETRY_DELAYS[0];
        return RETRY_DELAYS[Math.min(retry - 1, RETRY_DELAYS.length - 1)];
    }

    private void pumpLocked() {
        if (busy) return;
        Item item = queue.pollFirst();
        if (item == null) return;
        boolean ok;
        try {
            ok = writer.write(item.ch, item.data);
        } catch (Exception e) {
            Log.w(TAG, "write threw", e);
            ok = false;
        }
        if (!ok) {
            /* 发起失败：退避重试当前片（不清空队列，保住歌词分片序列） */
            if (item.retry < MAX_RETRY) {
                item.retry++;
                queue.addFirst(item);
                main.postDelayed(retryTask, delayFor(item.retry));
                return;
            }
            /* 退避重试耗尽：链路已无救，断开重连后由上层补推当前曲目 */
            stats.failCount++;
            queue.clear();
            stallListener.onStalled();
            return;
        }
        /* 已发起：等真实 ACK。超时未回 = 链路卡死 */
        busy = true;
        current = item;
        main.postDelayed(stallTimer, STALL_MS);
    }

    private final Runnable retryTask = new Runnable() {
        @Override public void run() {
            synchronized (BleWriteQueue.this) { pumpLocked(); }
        }
    };

    private final Runnable stallTimer = new Runnable() {
        @Override public void run() {
            synchronized (BleWriteQueue.this) {
                if (!busy) return;
                Log.w(TAG, "write stalled " + STALL_MS + "ms, forcing reconnect");
                queue.clear();
                busy = false;
                current = null;
                stallListener.onStalled();
            }
        }
    };
}