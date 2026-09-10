package com.icarme.lyrics;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.nio.charset.StandardCharsets;

/**
 * 极简 QR 编码器：字节模式 + ECC-M，版本 1–10。
 * 仅用于车机内网下载链接 / Wi-Fi 配置码，零第三方依赖。
 */
public final class QrEncoder {

    private QrEncoder() {}

    public static Bitmap encode(String text, int scale) {
        boolean[][] m = encodeMatrix(text);
        int n = m.length;
        int quiet = 4;
        int px = (n + quiet * 2) * scale;
        Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.RGB_565);
        bmp.eraseColor(Color.WHITE);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (!m[y][x]) continue;
                int x0 = (x + quiet) * scale;
                int y0 = (y + quiet) * scale;
                for (int dy = 0; dy < scale; dy++) {
                    for (int dx = 0; dx < scale; dx++) {
                        bmp.setPixel(x0 + dx, y0 + dy, Color.BLACK);
                    }
                }
            }
        }
        return bmp;
    }

    public static boolean[][] encodeMatrix(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        Version v = Version.pick(data.length);
        byte[] codewords = buildCodewords(data, v);
        boolean[][] m = new boolean[v.size][v.size];
        boolean[][] fn = new boolean[v.size][v.size];
        drawFunctionPatterns(m, fn, v);
        drawCodewords(m, fn, codewords);
        int mask = selectMask(m, fn, v);
        applyMask(m, fn, mask);
        drawFormatBits(m, v, mask);
        return m;
    }

    /* ---------------- 版本参数（ECC-M） ---------------- */

    private static final class Version {
        final int ver, size, dataCw, ecCw, blocks;
        Version(int ver, int dataCw, int ecCw, int blocks) {
            this.ver = ver;
            this.size = 17 + ver * 4;
            this.dataCw = dataCw;
            this.ecCw = ecCw;
            this.blocks = blocks;
        }
        static Version pick(int nbytes) {
            // ECC-M 可容纳的字节数（含 mode/length 开销后的近似，取保守值）
            int[] cap = {0, 14, 26, 42, 62, 84, 106, 122, 152, 180, 213};
            int[] dataCw = {0, 16, 28, 44, 64, 86, 108, 124, 154, 182, 216};
            int[] ecCw = {0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};
            int[] blocks = {0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5};
            for (int i = 1; i <= 10; i++) if (nbytes <= cap[i]) return new Version(i, dataCw[i], ecCw[i], blocks[i]);
            throw new IllegalArgumentException("QR too long: " + nbytes);
        }
    }

    private static final int[][] ALIGN = {
            {}, {}, {6, 18}, {6, 22}, {6, 26}, {6, 30},
            {6, 34}, {6, 22, 38}, {6, 24, 42}, {6, 26, 46}, {6, 28, 50}
    };

    /* ---------------- GF(256) ---------------- */

    private static final int[] EXP = new int[512];
    private static final int[] LOG = new int[256];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = x;
            LOG[x] = i;
            x = (x << 1) ^ ((x & 0x80) != 0 ? 0x11D : 0);
        }
        for (int i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
    }

    private static int gmul(int a, int b) {
        return (a == 0 || b == 0) ? 0 : EXP[LOG[a] + LOG[b]];
    }

    private static int[] rsGenerator(int degree) {
        // Nayuki：返回 degree 长系数（不含首项 1）
        int[] result = new int[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = gmul(result[j], root);
                if (j + 1 < result.length) result[j] ^= result[j + 1];
            }
            root = gmul(root, 0x02);
        }
        return result;
    }

    private static byte[] rsRemainder(byte[] data, int ecLen) {
        int[] divisor = rsGenerator(ecLen);
        byte[] result = new byte[ecLen];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, ecLen - 1);
            result[ecLen - 1] = 0;
            for (int i = 0; i < ecLen; i++) {
                result[i] ^= (byte) gmul(divisor[i], factor);
            }
        }
        return result;
    }

    /* ---------------- 码字 ---------------- */

    private static byte[] buildCodewords(byte[] data, Version v) {
        // bit buffer
        int[] bb = new int[v.dataCw * 8];
        int bp = 0;
        writeBits(bb, bp, 0b0100, 4); bp += 4;
        int lenBits = v.ver < 10 ? 8 : 16;
        writeBits(bb, bp, data.length, lenBits); bp += lenBits;
        for (byte b : data) {
            writeBits(bb, bp, b & 0xFF, 8);
            bp += 8;
        }
        int cap = v.dataCw * 8;
        int term = Math.min(4, cap - bp);
        bp += term; // already 0
        while (bp % 8 != 0) bp++;
        int pad = 0xEC;
        while (bp < cap) {
            writeBits(bb, bp, pad, 8);
            bp += 8;
            pad = (pad == 0xEC) ? 0x11 : 0xEC;
        }
        byte[] dataCw = new byte[v.dataCw];
        for (int i = 0; i < v.dataCw; i++) {
            int val = 0;
            for (int j = 0; j < 8; j++) val = (val << 1) | bb[i * 8 + j];
            dataCw[i] = (byte) val;
        }

        // split blocks
        int n = v.blocks;
        int shortLen = v.dataCw / n;
        int numLong = v.dataCw % n;
        int ecLen = v.ecCw / n;
        byte[][] dBlk = new byte[n][];
        byte[][] eBlk = new byte[n][];
        int off = 0;
        for (int i = 0; i < n; i++) {
            int len = shortLen + (i >= n - numLong ? 1 : 0);
            dBlk[i] = new byte[len];
            System.arraycopy(dataCw, off, dBlk[i], 0, len);
            off += len;
            eBlk[i] = rsRemainder(dBlk[i], ecLen);
        }

        // interleave
        byte[] all = new byte[v.dataCw + v.ecCw];
        int ai = 0;
        int maxData = shortLen + 1;
        for (int j = 0; j < maxData; j++) {
            for (int i = 0; i < n; i++) {
                if (j < dBlk[i].length) all[ai++] = dBlk[i][j];
            }
        }
        for (int j = 0; j < ecLen; j++) {
            for (int i = 0; i < n; i++) all[ai++] = eBlk[i][j];
        }
        return all;
    }

    private static void writeBits(int[] bb, int off, int val, int len) {
        for (int i = len - 1; i >= 0; i--) {
            bb[off++] = (val >>> i) & 1;
        }
    }

    /* ---------------- 矩阵 ---------------- */

    private static void drawFunctionPatterns(boolean[][] m, boolean[][] fn, Version v) {
        int n = v.size;
        // timing
        for (int i = 0; i < n; i++) {
            setFn(m, fn, 6, i, i % 2 == 0);
            setFn(m, fn, i, 6, i % 2 == 0);
        }
        // finders
        drawFinder(m, fn, 0, 0);
        drawFinder(m, fn, n - 7, 0);
        drawFinder(m, fn, 0, n - 7);
        // alignment
        int[] ac = ALIGN[v.ver];
        for (int cy : ac) {
            for (int cx : ac) {
                if ((cx == 6 && cy == 6) || (cx == 6 && cy == n - 7) || (cx == n - 7 && cy == 6)) continue;
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        setFn(m, fn, cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
                    }
                }
            }
        }
        // reserve format areas (filled later)
        for (int i = 0; i <= 8; i++) {
            fn[8][i] = true;
            fn[i][8] = true;
        }
        for (int i = 0; i < 8; i++) {
            fn[8][n - 1 - i] = true;
            fn[n - 1 - i][8] = true;
        }
        setFn(m, fn, 8, n - 8, true); // dark module
        if (v.ver >= 7) {
            int rem = v.ver;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            int bits = (v.ver << 12) | (rem & 0xFFF);
            for (int i = 0; i < 18; i++) {
                boolean bit = ((bits >>> i) & 1) != 0;
                int a = i / 3;
                int b = n - 11 + i % 3;
                setFn(m, fn, b, a, bit);
                setFn(m, fn, a, b, bit);
            }
        }
    }

    private static void setFn(boolean[][] m, boolean[][] fn, int x, int y, boolean dark) {
        m[y][x] = dark;
        fn[y][x] = true;
    }

    private static void drawFinder(boolean[][] m, boolean[][] fn, int x, int y) {
        for (int dy = -1; dy <= 7; dy++) {
            for (int dx = -1; dx <= 7; dx++) {
                int xx = x + dx, yy = y + dy;
                if (xx < 0 || yy < 0 || xx >= m.length || yy >= m.length) continue;
                boolean dark = dx >= 0 && dx <= 6 && dy >= 0 && dy <= 6
                        && Math.max(Math.abs(dx - 3), Math.abs(dy - 3)) != 2;
                setFn(m, fn, xx, yy, dark);
            }
        }
    }

    private static void drawCodewords(boolean[][] m, boolean[][] fn, byte[] cw) {
        int n = m.length;
        int bit = 0;
        int total = cw.length * 8;
        for (int right = n - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < n; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? (n - 1 - vert) : vert;
                    if (fn[y][x] || bit >= total) continue;
                    boolean dark = ((cw[bit >>> 3] >>> (7 - (bit & 7))) & 1) != 0;
                    m[y][x] = dark;
                    bit++;
                }
            }
        }
    }

    private static int selectMask(boolean[][] m, boolean[][] fn, Version v) {
        int best = 0;
        int bestScore = Integer.MAX_VALUE;
        boolean[][] bak = new boolean[m.length][m.length];
        for (int i = 0; i < m.length; i++) System.arraycopy(m[i], 0, bak[i], 0, m.length);
        for (int mask = 0; mask < 8; mask++) {
            for (int i = 0; i < m.length; i++) System.arraycopy(bak[i], 0, m[i], 0, m.length);
            applyMask(m, fn, mask);
            drawFormatBits(m, v, mask);
            int s = penalty(m);
            if (s < bestScore) {
                bestScore = s;
                best = mask;
            }
        }
        for (int i = 0; i < m.length; i++) System.arraycopy(bak[i], 0, m[i], 0, m.length);
        return best;
    }

    private static void applyMask(boolean[][] m, boolean[][] fn, int mask) {
        int n = m.length;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (fn[y][x]) continue;
                boolean inv;
                switch (mask) {
                    case 0: inv = (x + y) % 2 == 0; break;
                    case 1: inv = y % 2 == 0; break;
                    case 2: inv = x % 3 == 0; break;
                    case 3: inv = (x + y) % 3 == 0; break;
                    case 4: inv = (y / 2 + x / 3) % 2 == 0; break;
                    case 5: inv = (x * y) % 2 + (x * y) % 3 == 0; break;
                    case 6: inv = ((x * y) % 2 + (x * y) % 3) % 2 == 0; break;
                    default: inv = ((x + y) % 2 + (x * y) % 3) % 2 == 0; break;
                }
                if (inv) m[y][x] = !m[y][x];
            }
        }
    }

    private static void drawFormatBits(boolean[][] m, Version v, int mask) {
        // ECC-M formatBits = 0
        int data = mask; // (0 << 3) | mask
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = ((data << 10) | (rem & 0x3FF)) ^ 0x5412;
        int n = m.length;
        // copy 1 around top-left (Nayuki)
        for (int i = 0; i <= 5; i++) m[i][8] = ((bits >>> i) & 1) != 0;
        m[7][8] = ((bits >>> 6) & 1) != 0;
        m[8][8] = ((bits >>> 7) & 1) != 0;
        m[8][7] = ((bits >>> 8) & 1) != 0;
        for (int i = 9; i < 15; i++) m[8][14 - i] = ((bits >>> i) & 1) != 0;
        // copy 2
        for (int i = 0; i < 8; i++) m[8][n - 1 - i] = ((bits >>> i) & 1) != 0;
        for (int i = 8; i < 15; i++) m[n - 15 + i][8] = ((bits >>> i) & 1) != 0;
        m[n - 8][8] = true;
    }

    private static int penalty(boolean[][] m) {
        int n = m.length;
        int p = 0;
        for (int y = 0; y < n; y++) {
            int run = 1;
            for (int x = 1; x < n; x++) {
                if (m[y][x] == m[y][x - 1]) run++;
                else {
                    if (run >= 5) p += 3 + run - 5;
                    run = 1;
                }
            }
            if (run >= 5) p += 3 + run - 5;
        }
        for (int x = 0; x < n; x++) {
            int run = 1;
            for (int y = 1; y < n; y++) {
                if (m[y][x] == m[y - 1][x]) run++;
                else {
                    if (run >= 5) p += 3 + run - 5;
                    run = 1;
                }
            }
            if (run >= 5) p += 3 + run - 5;
        }
        for (int y = 0; y < n - 1; y++) {
            for (int x = 0; x < n - 1; x++) {
                boolean v0 = m[y][x];
                if (v0 == m[y][x + 1] && v0 == m[y + 1][x] && v0 == m[y + 1][x + 1]) p += 3;
            }
        }
        return p;
    }
}
