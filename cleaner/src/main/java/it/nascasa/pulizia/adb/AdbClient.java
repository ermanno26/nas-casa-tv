package it.nascasa.pulizia.adb;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Minuscolo client del protocollo ADB (quello di "adb connect"): l'app si collega al debug
 * di rete della TV stessa (127.0.0.1:5555) ed esegue comandi di shell, come farebbe il PC.
 */
public final class AdbClient implements Closeable {
    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private final Socket sock;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextId = 1;

    private AdbClient(Socket s) throws IOException {
        this.sock = s;
        this.in = new DataInputStream(s.getInputStream());
        this.out = s.getOutputStream();
    }

    /**
     * @param authWaitMs quanto aspettare che l'utente accetti la richiesta "Consentire il debug?"
     *                   sulla TV (serve solo la prima volta).
     */
    public static AdbClient connect(String host, int port, AdbKey key, int authWaitMs) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 5000);
            s.setSoTimeout(8000);
            s.setTcpNoDelay(true);
            AdbClient c = new AdbClient(s);
            c.handshake(key, authWaitMs);
            return c;
        } catch (IOException e) {
            try { s.close(); } catch (IOException ignored) { }
            throw e;
        }
    }

    private void handshake(AdbKey key, int authWaitMs) throws IOException {
        send(A_CNXN, 0x01000000, 4096, "host::\0".getBytes(StandardCharsets.US_ASCII));
        boolean signed = false;
        boolean keySent = false;
        for (int i = 0; i < 20; i++) {
            Msg m = read();
            if (m.cmd == A_CNXN) {
                sock.setSoTimeout(30000);
                return;
            }
            if (m.cmd == A_AUTH && m.arg0 == 1) {
                try {
                    if (!signed) {
                        send(A_AUTH, 2, 0, key.sign(m.data));
                        signed = true;
                    } else if (!keySent) {
                        send(A_AUTH, 3, 0, key.androidPublicKey());
                        keySent = true;
                        sock.setSoTimeout(authWaitMs); // l'utente deve premere "Consenti" sulla TV
                    } else {
                        throw new IOException("La TV ha rifiutato l'autorizzazione al debug.");
                    }
                } catch (IOException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IOException("Errore nella firma: " + e);
                }
            }
        }
        throw new IOException("Risposta inattesa dal debug della TV.");
    }

    /** Esegue un comando di shell sulla TV e restituisce quello che scrive. */
    public synchronized String shell(String command, int timeoutMs) throws IOException {
        sock.setSoTimeout(timeoutMs);
        int local = nextId++;
        send(A_OPEN, local, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int remote = 0;
        while (true) {
            Msg m = read();
            if (m.cmd == A_OKAY && m.arg1 == local) {
                remote = m.arg0;
            } else if (m.cmd == A_WRTE && m.arg1 == local) {
                remote = m.arg0;
                buf.write(m.data, 0, m.data.length);
                send(A_OKAY, local, remote, new byte[0]);
            } else if (m.cmd == A_CLSE && m.arg1 == local) {
                if (remote == 0) remote = m.arg0;
                break;
            }
        }
        try {
            send(A_CLSE, local, remote, new byte[0]);
        } catch (IOException ignored) {
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private synchronized void send(int cmd, int a0, int a1, byte[] data) throws IOException {
        int sum = 0;
        for (byte b : data) sum += b & 0xff;
        ByteBuffer h = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(a0).putInt(a1).putInt(data.length).putInt(sum).putInt(cmd ^ 0xffffffff);
        h.put(data);
        out.write(h.array());
        out.flush();
    }

    private Msg read() throws IOException {
        byte[] hb = new byte[24];
        in.readFully(hb);
        ByteBuffer h = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = h.getInt();
        m.arg0 = h.getInt();
        m.arg1 = h.getInt();
        int len = h.getInt();
        if (len < 0 || len > 1 << 20) throw new IOException("Messaggio ADB non valido.");
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    @Override
    public void close() {
        try { sock.close(); } catch (IOException ignored) { }
    }
}
