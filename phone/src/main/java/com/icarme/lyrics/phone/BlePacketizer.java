package com.icarme.lyrics.phone;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * BLE 分片打包器（与车机端 Reassembler 协议对应）。
 *
 * 包格式（小端）：frameId u16 | seq u8 | total u8 | UTF-8 JSON 片段
 * 换歌时 frameId 递增，车机端检测到变化即丢弃旧帧缓冲。
 */
final class BlePacketizer {

    private int frameId = 0;

    synchronized int nextFrameId() { return ++frameId & 0xFFFF; }

    /** 把整段 JSON 切成分片数组（自动带 4 字节头） */
    byte[][] pack(String json, int maxPayload) {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        int total = (data.length + maxPayload - 1) / maxPayload;
        if (total == 0) total = 1;
        int fid = nextFrameId();
        byte[][] out = new byte[total][];
        for (int i = 0; i < total; i++) {
            int from = i * maxPayload;
            int len = Math.min(maxPayload, data.length - from);
            byte[] pkt = new byte[4 + len];
            pkt[0] = (byte) (fid & 0xFF);
            pkt[1] = (byte) ((fid >> 8) & 0xFF);
            pkt[2] = (byte) (i & 0xFF);
            pkt[3] = (byte) (total & 0xFF);
            System.arraycopy(data, from, pkt, 4, len);
            out[i] = pkt;
        }
        return out;
    }
}
