package com.icarme.lyrics;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * BLE 歌词分片重组器。
 *
 * 包格式（小端）：
 *   byte 0-1 : frameId  uint16（新歌词帧递增，换歌时作废旧分片）
 *   byte 2   : seq      uint8（从 0 开始）
 *   byte 3   : total    uint8（总片数）
 *   byte 4+  : UTF-8 JSON 片段（原始字节累积，集齐后一次性解码，
 *              避免多字节字符被分片边界切断产生乱码）
 *
 * frameId 变化即重置缓冲。全部收齐后拼出 JSON 字符串上抛。
 */
final class Reassembler {

    static final class Frame {
        final String json;
        Frame(String json) { this.json = json; }
    }

    private int frameId = -1;
    private int total = 0;
    private int received = 0;
    private ByteArrayOutputStream buf;

    /** 丢弃当前未完成帧 */
    void reset() {
        frameId = -1;
        total = 0;
        received = 0;
        buf = null;
    }

    /**
     * 喂入一片；集齐整帧返回 Frame，否则返回 null。
     * 包短于 4 字节视为非法直接丢弃。
     */
    Frame feed(byte[] packet) {
        if (packet == null || packet.length < 4) return null;

        int fid = (packet[0] & 0xFF) | ((packet[1] & 0xFF) << 8);
        int seq = packet[2] & 0xFF;
        int tot = packet[3] & 0xFF;

        if (fid != frameId) {
            /* 新帧：重置缓冲 */
            frameId = fid;
            total = tot;
            received = 0;
            buf = new ByteArrayOutputStream();
        } else if (tot != total || seq >= total || buf == null) {
            /* 参数不一致：丢弃该片 */
            return null;
        }

        buf.write(packet, 4, packet.length - 4);
        received++;

        if (received >= total) {
            String json = new String(buf.toByteArray(), StandardCharsets.UTF_8);
            reset();
            return new Frame(json);
        }
        return null;
    }

    static String toText(byte[] data) {
        return (data == null) ? "" : new String(data, StandardCharsets.UTF_8);
    }
}
