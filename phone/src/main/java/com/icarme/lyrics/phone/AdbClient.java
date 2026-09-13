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

/**
 * 极简无线 ADB 客户端（手机连车机 :5555）。
 * 协议子集：CNXN / AUTH(RSA) / OPEN shell: / WRTE / CLSE。
 * 与 03 车机助手同思路：同网段扫 5555，直接 shell 代跑授权命令。
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
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int IO_TIMEOUT_MS = 12000;

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

        send(A_CNXN, A_VERSION, MAX_DATA, "host::features=shell_v2,cmd\0".getBytes(StandardCharsets.UTF_8));

        /* 最多 4 次 AUTH 往返（未授权时先送公钥，再签 token） */
        for (int i = 0; i < 5; i++) {
            Packet p = read();
            if (p.is(A_CNXN)) {
                Log.i(TAG, "connected to " + host);
                return;
            }
            if (p.is(A_AUTH)) {
                if (p.arg0 == AUTH_TOKEN) {
                    byte[] sig = AdbKeys.signToken(p.data);
                    if (sig == null) {
                        throw new IOException("无 ADB 密钥，无法签名（请先生成）");
                    }
                    send(A_AUTH, AUTH_SIGNATURE, 0, sig);
                } else if (p.arg0 == AUTH_RSA_PUBKEY) {
                    /* 设备要求重发公钥 */
                    send(A_AUTH, AUTH_RSA_PUBKEY, 0, AdbKeys.adbPublicKey());
                } else {
                    send(A_AUTH, AUTH_RSA_PUBKEY, 0, AdbKeys.adbPublicKey());
                }
                continue;
            }
            throw new IOException("握手失败 cmd=0x" + Integer.toHexString(p.cmd));
        }
        throw new IOException("ADB 授权未通过：请在车机屏幕上点「允许」，或先用电脑 adb connect");
    }

    /** 执行 shell 命令，返回 stdout+stderr 文本。 */
    String shell(String command) throws IOException {
        int localId = localIdSeq++;
        byte[] payload = (command + "\0").getBytes(StandardCharsets.UTF_8);
        send(A_OPEN, localId, 0, payload);

        /* 等 OKAY 拿 remote id */
        int remoteId = -1;
        long deadline = System.currentTimeMillis() + IO_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Packet p = read();
            if (p.is(A_OKAY) && p.arg1 == localId) {
                remoteId = p.arg0;
                break;
            }
            if (p.is(A_CLSE)) {
                throw new IOException("shell 打开被拒: " + command);
            }
            /* AUTH 重试等杂包忽略 */
        }
        if (remoteId < 0) throw new IOException("shell 无响应");

        StringBuilder sb = new StringBuilder();
        while (true) {
            Packet p = read();
            if (p.is(A_WRTE) && p.arg1 == localId) {
                sb.append(new String(p.data, StandardCharsets.UTF_8));
                send(A_OKAY, localId, remoteId, new byte[0]);
                continue;
            }
            if (p.is(A_CLSE) && p.arg1 == localId) {
                send(A_CLSE, localId, remoteId, new byte[0]);
                break;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IOException("shell 读超时");
            }
        }
        return sb.toString();
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
