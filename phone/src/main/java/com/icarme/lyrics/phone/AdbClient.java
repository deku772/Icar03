package com.icarme.lyrics.phone;

import android.util.Log;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Locale;

/**
 * 极简无线 ADB 客户端（手机连车机 :5555）。
 *
 * 注意：
 *  1) 不要在 CNXN 里声明 shell_v2——否则 adbd 会按 v2 帧格式回，旧式 OPEN 会被 CLSE。
 *  2) 一次性命令优先用 exec:（无 PTY），比 shell: 更不容易被占用/拒绝。
 *  3) adbd 通常只允许一个 shell 会话；若 ADB Helper 等已占用，OPEN 会 CLSE，需先断开它们。
 */
final class AdbClient implements Closeable {

    private static final String TAG = "IcarLyrics.Adb";

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;

    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSA_PUBKEY = 0;

    private static final int A_VERSION = 0x01000001;
    private static final int MAX_DATA = 256 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int IO_TIMEOUT_MS = 15000;

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private int localIdSeq = 1;

    static final class Packet {
        int cmd, arg0, arg1, dataLen;
        byte[] data = new byte[0];

        boolean is(int c) { return cmd == c; }
    }

    /** 连接并完成认证；失败抛 IOException。 */
    void connect(String host, int port) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(IO_TIMEOUT_MS);
        in = new DataInputStream(socket.getInputStream());
        out = new DataOutputStream(socket.getOutputStream());

        /* 刻意不声明 shell_v2，保持经典协议 */
        send(A_CNXN, A_VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));

        for (int i = 0; i < 6; i++) {
            Packet p = read();
            if (p.is(A_CNXN)) {
                Log.i(TAG, "connected to " + host + " banner="
                        + new String(p.data, 0, Math.min(p.data.length, 80), StandardCharsets.UTF_8)
                                .replace('\0', ' '));
                return;
            }
            if (p.is(A_AUTH)) {
                if (p.arg0 == AUTH_TOKEN) {
                    byte[] sig = AdbKeys.signToken(p.data);
                    if (sig == null) {
                        throw new IOException("无 ADB 密钥，无法签名");
                    }
                    send(A_AUTH, AUTH_SIGNATURE, 0, sig);
                } else {
                    send(A_AUTH, AUTH_RSA_PUBKEY, 0, AdbKeys.adbPublicKey());
                }
                continue;
            }
            throw new IOException("握手失败 cmd=0x" + Integer.toHexString(p.cmd));
        }
        throw new IOException("ADB 授权未通过：请在车机点「允许调试」，并关掉其它 ADB 客户端后重试");
    }

    /** 执行一次性命令。优先 exec:，失败再试 shell:。 */
    String shell(String command) throws IOException {
        try {
            return openService("exec:" + command);
        } catch (IOException e1) {
            Log.w(TAG, "exec: failed (" + e1.getMessage() + "), try shell:");
            try {
                return openService("shell:" + command);
            } catch (IOException e2) {
                throw new IOException(e1.getMessage() + " | shell: " + e2.getMessage());
            }
        }
    }

    /** OPEN 一个 adbd 服务并读到 CLSE。 */
    private String openService(String service) throws IOException {
        int localId = localIdSeq++;
        byte[] payload = (service + "\0").getBytes(StandardCharsets.UTF_8);
        send(A_OPEN, localId, 0, payload);

        int remoteId = -1;
        long deadline = System.currentTimeMillis() + IO_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Packet p = read();
            if (p.is(A_OKAY) && p.arg1 == localId) {
                remoteId = p.arg0;
                break;
            }
            if (p.is(A_CLSE)) {
                /* 无论 arg 是否匹配，打开阶段的 CLSE 都视为拒绝 */
                try { send(A_CLSE, localId, p.arg0, new byte[0]); } catch (Exception ignored) {}
                throw new IOException("服务被拒: " + service
                        + (p.dataLen > 0 ? (" " + new String(p.data, StandardCharsets.UTF_8).trim()) : ""));
            }
        }
        if (remoteId < 0) throw new IOException("服务无响应: " + service);

        StringBuilder sb = new StringBuilder();
        while (System.currentTimeMillis() < deadline) {
            Packet p = read();
            if (p.is(A_WRTE) && p.arg1 == localId) {
                sb.append(new String(p.data, StandardCharsets.UTF_8));
                send(A_OKAY, localId, remoteId, new byte[0]);
                deadline = System.currentTimeMillis() + IO_TIMEOUT_MS; /* 有数据则续命 */
                continue;
            }
            if (p.is(A_CLSE) && p.arg1 == localId) {
                send(A_CLSE, localId, remoteId, new byte[0]);
                break;
            }
        }
        return sb.toString();
    }

    /* ---------------- sync: 推文件 + pm install（03 助手同款路径） ---------------- */

    /**
     * 把本地 APK 推到车机 /data/local/tmp 并 pm install -r。
     * 先试 sync:；部分车机 adbd 会拒 sync，再退回 exec:cat 流式写入。
     */
    void pushAndInstall(java.io.File apk, String remoteName) throws IOException {
        if (apk == null || !apk.isFile() || apk.length() < 1000) {
            throw new IOException("APK 文件无效");
        }
        String remote = "/data/local/tmp/" + remoteName;
        try {
            pushFile(apk, remote);
            Log.i(TAG, "pushed via sync: " + remote);
        } catch (IOException e) {
            Log.w(TAG, "sync push failed (" + e.getMessage() + "), fallback shell-cat");
            pushFileViaShell(apk, remote);
        }
        String out = shell("pm install -r " + remote);
        if (out == null) out = "";
        String t = out.trim();
        if (t.toLowerCase(Locale.US).contains("fail") && !t.contains("Success")) {
            throw new IOException("pm install 失败: " + firstLine(t));
        }
        shell("rm -f " + remote);
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i > 0 ? s.substring(0, i) : s;
    }

    /** exec:cat > file 流式写入（sync: 被 adbd 拒时的兜底） */
    private void pushFileViaShell(java.io.File local, String remotePath) throws IOException {
        int localId = localIdSeq++;
        String svc = "exec:cat > " + remotePath;
        send(A_OPEN, localId, 0, (svc + "\0").getBytes(StandardCharsets.UTF_8));
        int remoteId = waitOkay(localId);

        try (java.io.InputStream in = new java.io.FileInputStream(local)) {
            byte[] chunk = new byte[32 * 1024];
            int n;
            while ((n = in.read(chunk)) > 0) {
                byte[] data = new byte[n];
                System.arraycopy(chunk, 0, data, 0, n);
                /* WRTE: arg0=本端 localId, arg1=对端 remoteId */
                send(A_WRTE, localId, remoteId, data);
                waitOkay(localId);
            }
        }
        send(A_CLSE, localId, remoteId, new byte[0]);
        /* 读完设备侧 CLSE */
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            Packet p = read();
            if (p.is(A_CLSE) && p.arg1 == localId) break;
        }
    }

    /** ADB sync SEND/DATA/DONE 推单个文件 */
    private void pushFile(java.io.File local, String remotePath) throws IOException {
        int localId = localIdSeq++;
        send(A_OPEN, localId, 0, "sync:\0".getBytes(StandardCharsets.UTF_8));
        int remoteId = waitOkay(localId);

        /* SEND path,mode */
        byte[] path = (remotePath + ",0755").getBytes(StandardCharsets.UTF_8);
        byte[] sendReq = new byte[8 + path.length];
        System.arraycopy("SEND".getBytes(StandardCharsets.US_ASCII), 0, sendReq, 0, 4);
        putLe32(sendReq, 4, path.length);
        System.arraycopy(path, 0, sendReq, 8, path.length);
        writeRaw(sendReq);
        readSyncOk("SEND");

        try (java.io.InputStream in = new java.io.FileInputStream(local)) {
            byte[] chunk = new byte[64 * 1024];
            int n;
            while ((n = in.read(chunk)) > 0) {
                byte[] dataReq = new byte[8 + n];
                System.arraycopy("DATA".getBytes(StandardCharsets.US_ASCII), 0, dataReq, 0, 4);
                putLe32(dataReq, 4, n);
                System.arraycopy(chunk, 0, dataReq, 8, n);
                writeRaw(dataReq);
            }
        }
        byte[] done = new byte[8];
        System.arraycopy("DONE".getBytes(StandardCharsets.US_ASCII), 0, done, 0, 4);
        putLe32(done, 4, (int) (System.currentTimeMillis() / 1000L));
        writeRaw(done);
        readSyncOk("DONE");

        send(A_CLSE, localId, remoteId, new byte[0]);
    }

    private int waitOkay(int localId) throws IOException {
        long deadline = System.currentTimeMillis() + IO_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Packet p = read();
            if (p.is(A_OKAY) && p.arg1 == localId) return p.arg0;
            if (p.is(A_CLSE)) throw new IOException("sync 打开被拒");
        }
        throw new IOException("sync 无响应");
    }

    /** sync 应答：8 字节 "OKAY"/"FAIL" + len */
    private void readSyncOk(String phase) throws IOException {
        byte[] h = new byte[8];
        in.readFully(h);
        String id = new String(h, 0, 4, StandardCharsets.US_ASCII);
        if (!"OKAY".equals(id)) {
            int len = leInt(h, 4);
            String msg = "";
            if (len > 0 && len < 512) {
                byte[] b = new byte[len];
                in.readFully(b);
                msg = new String(b, StandardCharsets.UTF_8);
            }
            throw new IOException("sync " + phase + " 失败: " + msg);
        }
    }

    private void writeRaw(byte[] data) throws IOException {
        out.write(data);
        out.flush();
    }

    private static void putLe32(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        int crc = (cmd == A_WRTE || cmd == A_CNXN || cmd == A_AUTH || cmd == A_OPEN)
                ? checksum(data, len) : 0;
        out.writeInt(Integer.reverseBytes(cmd));
        out.writeInt(Integer.reverseBytes(arg0));
        out.writeInt(Integer.reverseBytes(arg1));
        out.writeInt(Integer.reverseBytes(len));
        out.writeInt(Integer.reverseBytes(crc));
        out.writeInt(Integer.reverseBytes(cmd ^ 0xffffffff));
        if (len > 0) out.write(data);
        out.flush();
    }

    private Packet read() throws IOException {
        byte[] head = new byte[24];
        in.readFully(head);
        Packet p = new Packet();
        p.cmd = leInt(head, 0);
        p.arg0 = leInt(head, 4);
        p.arg1 = leInt(head, 8);
        p.dataLen = leInt(head, 12);
        int magic = leInt(head, 20);
        if (magic != (p.cmd ^ 0xffffffff)) {
            throw new IOException("ADB 包 magic 错误");
        }
        if (p.dataLen < 0 || p.dataLen > MAX_DATA + 4096) {
            throw new IOException("ADB 包长度异常: " + p.dataLen);
        }
        p.data = new byte[p.dataLen];
        if (p.dataLen > 0) in.readFully(p.data);
        return p;
    }

    private static int leInt(byte[] b, int off) {
        return (b[off] & 0xff)
                | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16)
                | ((b[off + 3] & 0xff) << 24);
    }

    private static int checksum(byte[] data, int len) {
        int sum = 0;
        for (int i = 0; i < len; i++) sum += data[i] & 0xff;
        return sum;
    }

    @Override
    public void close() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        socket = null;
    }

    /* ---------------- RSA 密钥（ADB 公钥格式 + 签 token） ---------------- */

    static final class AdbKeys {
        private static final String FILE = "adbkey.pk8";
        private static volatile PrivateKey privateKey;
        private static volatile byte[] pubBlob;

        static synchronized void ensure(ContextHolder ctx) throws Exception {
            if (privateKey != null && pubBlob != null) return;
            byte[] raw = ctx.read(FILE);
            if (raw != null && raw.length > 0) {
                privateKey = KeyFactory.getInstance("RSA")
                        .generatePrivate(new PKCS8EncodedKeySpec(raw));
            } else {
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
                kpg.initialize(2048);
                KeyPair kp = kpg.generateKeyPair();
                privateKey = kp.getPrivate();
                ctx.write(FILE, privateKey.getEncoded());
            }
            pubBlob = buildAdbPublicKey((RSAPublicKey) extractPublic(privateKey));
        }

        static byte[] adbPublicKey() {
            return pubBlob == null ? new byte[0] : pubBlob.clone();
        }

        static byte[] signToken(byte[] token) {
            try {
                Signature sig = Signature.getInstance("SHA1withRSA");
                sig.initSign(privateKey);
                sig.update(token);
                return sig.sign();
            } catch (Exception e) {
                return null;
            }
        }

        /** 从 PKCS8 私钥导出公钥 */
        private static java.security.PublicKey extractPublic(PrivateKey priv) throws Exception {
            java.security.interfaces.RSAPrivateCrtKey rk =
                    (java.security.interfaces.RSAPrivateCrtKey) priv;
            java.security.spec.RSAPublicKeySpec spec =
                    new java.security.spec.RSAPublicKeySpec(rk.getModulus(), rk.getPublicExponent());
            return KeyFactory.getInstance("RSA").generatePublic(spec);
        }

        /**
         * AOSP adb_auth 格式：
         * len(words) | n0inv | modulus | rr | exponent | "user@host\0"
         */
        private static byte[] buildAdbPublicKey(RSAPublicKey key) throws Exception {
            BigInteger n = key.getModulus();
            BigInteger e = key.getPublicExponent();
            int numBytes = (n.bitLength() + 7) / 8;
            if (numBytes != 256) {
                /* 归一到 2048-bit */
                throw new IOException("ADB 公钥长度异常: " + numBytes);
            }
            int numWords = numBytes / 4;
            byte[] nBytes = toFixedBE(n, numBytes);
            BigInteger n0 = new BigInteger(1, new byte[]{
                    nBytes[0], nBytes[1], nBytes[2], nBytes[3]
            });
            /* 注意：AOSP 用小端 32-bit word 的 n[0] */
            int n0le = (nBytes[0] & 0xff)
                    | ((nBytes[1] & 0xff) << 8)
                    | ((nBytes[2] & 0xff) << 16)
                    | ((nBytes[3] & 0xff) << 24);
            int n0inv = modInverse32(n0le);

            /* rr = 2^(2*keybits) mod n */
            BigInteger rr = BigInteger.ONE.shiftLeft(n.bitLength() * 2).mod(n);
            byte[] rrBytes = toFixedBE(rr, numBytes);

            byte[] host = "icarlyrics@phone\0".getBytes(StandardCharsets.US_ASCII);
            int total = 4 + 4 + numBytes + numBytes + 4 + host.length;
            byte[] outB = new byte[total];
            int o = 0;
            o += putLe32(outB, o, numWords);
            o += putLe32(outB, o, n0inv);
            System.arraycopy(nBytes, 0, outB, o, numBytes);
            o += numBytes;
            System.arraycopy(rrBytes, 0, outB, o, numBytes);
            o += numBytes;
            o += putLe32(outB, o, e.intValue());
            System.arraycopy(host, 0, outB, o, host.length);
            return outB;
        }

        private static byte[] toFixedBE(BigInteger v, int size) {
            byte[] raw = v.toByteArray();
            byte[] out = new byte[size];
            if (raw.length == size) {
                System.arraycopy(raw, 0, out, 0, size);
            } else if (raw.length == size + 1 && raw[0] == 0) {
                System.arraycopy(raw, 1, out, 0, size);
            } else if (raw.length < size) {
                System.arraycopy(raw, 0, out, size - raw.length, raw.length);
            } else {
                System.arraycopy(raw, raw.length - size, out, 0, size);
            }
            return out;
        }

        private static int putLe32(byte[] b, int off, int v) {
            b[off] = (byte) v;
            b[off + 1] = (byte) (v >> 8);
            b[off + 2] = (byte) (v >> 16);
            b[off + 3] = (byte) (v >> 24);
            return 4;
        }

        /** 32-bit 模逆：-1/n0 mod 2^32 */
        private static int modInverse32(int n0) {
            int x = n0;
            for (int i = 0; i < 5; i++) {
                x = x * (2 - n0 * x);
            }
            return -x;
        }
    }

    /** 密钥持久化回调（由调用方接 Context.filesDir） */
    interface ContextHolder {
        byte[] read(String name);
        void write(String name, byte[] data);
    }
}
