package com.icarme.lyrics;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * BLE 歌词分片重组器（v1.8.1）。
 *
 * 包格式（小端）：
 *   byte 0-1 : frameId  uint16（新歌词帧递增，换歌时作废旧分片）
 *   byte 2   : seq      uint8（从 0 开始）
 *   byte 3   : total    uint8（总片数）
 *   byte 4+  : UTF-8 JSON 片段（原始字节累积，集齐后一次性解码，
 *              避免多字节字符被分片边界切断产生乱码）
 *
 * v1.8 教训：旧实现按"顺序累积"（received 计数 + 单缓冲），丢任意一片
 * 整帧永远凑不齐（同 frameId 不 reset），且重复片会重复写入导致错位。
 * v1.8.1 改为按 seq 槽位缓冲：
 *   - 容忍乱序：任意顺序到达都能落位
 *   - 容忍重复：同 seq 重复片直接忽略（不重复写入）
 *   - 容忍丢片：丢片超时（FRAME_TIMEOUT_MS）后自动作废当前帧并复位，
 *     等待下一帧（frameId 递增）重新开始，避免整帧永久卡死
 *   - 换歌（frameId 变化）立即作废旧帧
 */
final class Reassembler {

    static final class Frame {
        final String json;
        Frame(String json) { this.json = json; }
    }

    /** 整帧超时：距首片超过此时间仍未集齐则作废（丢片保护） */
    private static final long FRAME_TIMEOUT_MS = 4000;

    private int frameId = -1;
    private int total = 0;
    private long startedAt = 0;          /* 当前帧首片到达时刻（超时判据） */
    private byte[][] slots;              /* 按 seq 索引的槽位缓冲 */
    private int received = 0;            /* 已到达且去重后的片数 */

    /** 丢弃当前未完成帧（连接断开/主动复位） */
    synchronized void reset() {
        frameId = -1;
        total = 0;
        startedAt = 0;
        slots = null;
        received = 0;
    }

    /**
     * 喂入一片；集齐整帧返回 Frame，否则返回 null。
     * 包短于 4 字节视为非法直接丢弃。
     */
    synchronized Frame feed(byte[] packet) {
        if (packet == null || packet.length < 4) return null;

        int fid = (packet[0] & 0xFF) | ((packet[1] & 0xFF) << 8);
        int seq = packet[2] & 0xFF;
        int tot = packet[3] & 0xFF;
        long now = System.currentTimeMillis();

        if (fid != frameId) {
            /* 新帧：重置槽位缓冲 */
            frameId = fid;
            total = tot;
            startedAt = now;
            slots = new byte[tot][];
            received = 0;
        } else if (tot != total || seq >= total || slots == null) {
            /* 参数不一致：丢弃该片 */
            return null;
        } else if (now - startedAt > FRAME_TIMEOUT_MS) {
            /* 同 frameId 但超时未集齐：丢片保护，作废旧帧，本片作新帧首片 */
            frameId = fid;
            total = tot;
            startedAt = now;
            received = 0;
            slots = new byte[tot][];
        }

        if (slots[seq] != null) {
            /* 同 seq 重复片：已收过，忽略（不重复写入、不误触发完成） */
            return null;
        }
        slots[seq] = Arrays.copyOfRange(packet, 4, packet.length);
        received++;

        if (received >= total) {
            /* 全部槽位就位：按 seq 顺序拼装，保证多字节 UTF-8 不被切开 */
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int i = 0; i < total; i++) {
                if (slots[i] == null) return null; /* 理论不可达，防御 */
                out.write(slots[i], 0, slots[i].length);
            }
            String json = new String(out.toByteArray(), StandardCharsets.UTF_8);
            reset();
            return new Frame(json);
        }
        return null;
    }

    static String toText(byte[] data) {
        return (data == null) ? "" : new String(data, StandardCharsets.UTF_8);
    }
}