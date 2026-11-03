package it.nascasa.tv.dlna;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Scoperta dei server multimediali in rete locale (SSDP) e collegamento manuale tramite IP. */
public final class Ssdp {
    private Ssdp() {}

    private static final String MCAST = "239.255.255.250";
    private static final int PORT = 1900;
    private static final String[] TARGETS = {
            "urn:schemas-upnp-org:device:MediaServer:1",
            "upnp:rootdevice"
    };

    /**
     * Cerca i NAS in rete. extraHost (facoltativo, es. 192.168.1.28) viene interrogato anche
     * direttamente: aiuta quando il multicast non passa (Wi-Fi, switch, VLAN).
     */
    public static List<UpnpServer> discover(String extraHost, int timeoutMs) {
        return discover(extraHost, PORT, timeoutMs);
    }

    static List<UpnpServer> discover(String extraHost, int extraPort, int timeoutMs) {
        Set<String> locations = searchLocations(extraHost, extraPort, timeoutMs, true);
        return describeAll(locations);
    }

    /** Collegamento manuale: IP (con o senza porta) oppure URL completo della scheda XML. */
    public static UpnpServer probe(String input) {
        if (input == null) return null;
        input = input.trim();
        if (input.isEmpty()) return null;

        if (input.startsWith("http://") || input.startsWith("https://")) {
            return UpnpServer.describe(input);
        }
        String host = input;
        int colon = host.indexOf(':');
        if (colon > 0) host = host.substring(0, colon);

        // 1) domanda diretta al NAS (SSDP unicast)
        Set<String> locs = searchLocations(host, PORT, 2500, false);
        List<UpnpServer> found = describeAll(locs);
        if (!found.isEmpty()) return found.get(0);

        // 2) indirizzi di descrizione tipici dei server DLNA piu' diffusi
        String[] guesses = {
                "http://" + host + ":8200/rootDesc.xml",              // MiniDLNA / ReadyMedia
                "http://" + host + ":32469/DeviceDescription.xml",    // Plex
                "http://" + host + ":9000/DeviceDescription.xml",
                "http://" + host + ":50001/description.xml",
        };
        if (colon > 0) {
            String port = input.substring(colon + 1);
            guesses = new String[]{"http://" + host + ":" + port + "/rootDesc.xml",
                    "http://" + host + ":" + port + "/DeviceDescription.xml",
                    "http://" + host + ":" + port + "/description.xml"};
        }
        for (String g : guesses) {
            UpnpServer s = UpnpServer.describe(g, 1500);
            if (s != null) return s;
        }
        return null;
    }

    static Set<String> searchLocations(String extraHost, int extraPort, int timeoutMs, boolean multicast) {
        Set<String> locations = new LinkedHashSet<>();
        DatagramSocket sock = null;
        try {
            sock = new DatagramSocket();
            sock.setSoTimeout(300);
            long deadline = System.currentTimeMillis() + timeoutMs;
            long nextSend = 0;
            int round = 0;
            byte[] buf = new byte[4096];
            while (System.currentTimeMillis() < deadline) {
                if (System.currentTimeMillis() >= nextSend && round < 3) {
                    for (String st : TARGETS) {
                        byte[] msg = msearch(st).getBytes(StandardCharsets.UTF_8);
                        if (multicast) trySend(sock, msg, MCAST, PORT);
                        if (extraHost != null && !extraHost.trim().isEmpty()) {
                            trySend(sock, msg, extraHost.trim(), extraPort);
                        }
                    }
                    round++;
                    nextSend = System.currentTimeMillis() + 1000;  // UDP puo' perdersi: ripetiamo
                }
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                String loc = headerValue(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8), "location");
                if (loc != null && !loc.isEmpty()) locations.add(loc);
            }
        } catch (IOException ignored) {
            // niente rete: restituiamo quello che abbiamo
        } finally {
            if (sock != null) sock.close();
        }
        return locations;
    }

    private static void trySend(DatagramSocket sock, byte[] msg, String host, int port) {
        try {
            sock.send(new DatagramPacket(msg, msg.length, InetAddress.getByName(host), port));
        } catch (Exception ignored) {
            // questa interfaccia/indirizzo non e' raggiungibile
        }
    }

    private static String msearch(String st) {
        return "M-SEARCH * HTTP/1.1\r\n"
                + "HOST: 239.255.255.250:1900\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: 2\r\n"
                + "ST: " + st + "\r\n"
                + "USER-AGENT: Android/1.0 UPnP/1.1 NASCasa/1.0\r\n\r\n";
    }

    static String headerValue(String response, String name) {
        for (String line : response.split("\r\n|\n")) {
            int i = line.indexOf(':');
            if (i <= 0) continue;
            if (line.substring(0, i).trim().toLowerCase(Locale.ROOT).equals(name)) {
                return line.substring(i + 1).trim();
            }
        }
        return null;
    }

    private static List<UpnpServer> describeAll(Set<String> locations) {
        List<UpnpServer> out = new ArrayList<>();
        if (locations.isEmpty()) return out;
        ExecutorService ex = Executors.newFixedThreadPool(Math.min(4, locations.size()));
        try {
            List<Future<UpnpServer>> fs = new ArrayList<>();
            for (final String loc : locations) {
                fs.add(ex.submit(new Callable<UpnpServer>() {
                    @Override public UpnpServer call() { return UpnpServer.describe(loc); }
                }));
            }
            Set<String> seen = new LinkedHashSet<>();
            for (Future<UpnpServer> f : fs) {
                try {
                    UpnpServer s = f.get(6, TimeUnit.SECONDS);
                    if (s != null && seen.add(s.controlUrl)) out.add(s);
                } catch (Exception ignored) {
                    // questo dispositivo non risponde
                }
            }
        } finally {
            ex.shutdownNow();
        }
        return out;
    }
}
