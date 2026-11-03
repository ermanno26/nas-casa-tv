package it.nascasa.pulizia.adb;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/** Chiave RSA con cui l'app si presenta al debug ADB della TV (generata una volta e conservata). */
public final class AdbKey {
    // Prefisso DigestInfo di SHA-1 (adb firma il "token" come se fosse gia' un hash SHA-1)
    private static final byte[] SHA1_PREFIX = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private final PrivateKey priv;
    private final RSAPublicKey pub;

    private AdbKey(PrivateKey priv, RSAPublicKey pub) {
        this.priv = priv;
        this.pub = pub;
    }

    public static AdbKey loadOrCreate(File dir) throws Exception {
        File fp = new File(dir, "adbkey");
        File fq = new File(dir, "adbkey.pub");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (fp.exists() && fq.exists()) {
            try {
                PrivateKey p = kf.generatePrivate(new PKCS8EncodedKeySpec(readAll(fp)));
                RSAPublicKey q = (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(readAll(fq)));
                return new AdbKey(p, q);
            } catch (Exception ignored) {
                // file rovinati: se ne crea una nuova
            }
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        dir.mkdirs();
        writeAll(fp, kp.getPrivate().getEncoded());
        writeAll(fq, kp.getPublic().getEncoded());
        return new AdbKey(kp.getPrivate(), (RSAPublicKey) kp.getPublic());
    }

    /** Firma il token ricevuto dalla TV. */
    public byte[] sign(byte[] token) throws Exception {
        Signature s = Signature.getInstance("NONEwithRSA");
        s.initSign(priv);
        s.update(SHA1_PREFIX);
        s.update(token);
        return s.sign();
    }

    /** Chiave pubblica nel formato particolare di adb (struttura RSAPublicKey + base64 + nome). */
    public byte[] androidPublicKey() {
        BigInteger n = pub.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        int n0inv = r32.subtract(n.mod(r32).modInverse(r32)).intValue();
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);

        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64);
        b.putInt(n0inv);
        b.put(littleEndian(n, 256));
        b.put(littleEndian(rr, 256));
        b.putInt(pub.getPublicExponent().intValue());

        String text = base64(b.array()) + " pulizia@tv";
        byte[] raw = text.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] out = new byte[raw.length + 1];
        System.arraycopy(raw, 0, out, 0, raw.length);
        return out; // termina con 0
    }

    private static byte[] littleEndian(BigInteger v, int len) {
        byte[] be = v.toByteArray();
        byte[] le = new byte[len];
        for (int i = 0; i < be.length && i < len + 1; i++) {
            int idx = be.length - 1 - i;
            if (i < len) le[i] = be[idx];
        }
        return le;
    }

    private static final String B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    static String base64(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i += 3) {
            int b0 = d[i] & 0xff;
            int b1 = i + 1 < d.length ? d[i + 1] & 0xff : 0;
            int b2 = i + 2 < d.length ? d[i + 2] & 0xff : 0;
            sb.append(B64.charAt(b0 >> 2));
            sb.append(B64.charAt(((b0 & 3) << 4) | (b1 >> 4)));
            sb.append(i + 1 < d.length ? B64.charAt(((b1 & 15) << 2) | (b2 >> 6)) : '=');
            sb.append(i + 2 < d.length ? B64.charAt(b2 & 63) : '=');
        }
        return sb.toString();
    }

    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        }
    }

    private static void writeAll(File f, byte[] data) throws IOException {
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(data);
        }
    }
}
