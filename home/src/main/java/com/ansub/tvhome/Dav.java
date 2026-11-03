package com.ansub.tvhome;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Minimo client WebDAV (solo lettura, solo http): elenca le immagini di una cartella del NAS.
 * Usa un socket diretto perche' HttpURLConnection non accetta il metodo PROPFIND.
 */
final class Dav {
    private Dav() {}

    static boolean isImageName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")
                || n.endsWith(".bmp") || n.endsWith(".gif");
    }

    /** Codifica un percorso ("/Pubblico/TV Sfondi") per l'URL, segmento per segmento. */
    static String encodePath(String path) {
        StringBuilder b = new StringBuilder();
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) continue;
            try {
                b.append('/').append(URLEncoder.encode(seg, "UTF-8").replace("+", "%20"));
            } catch (java.io.UnsupportedEncodingException e) {
                throw new IllegalStateException(e);
            }
        }
        return b.length() == 0 ? "/" : b.toString();
    }

    /**
     * Immagini nella cartella (e sottocartelle, fino a depth).
     * @return coppie {percorso, url completo}
     */
    static void collect(String base, String path, String auth, int depth, List<String[]> out, int[] budget)
            throws IOException {
        URI u = URI.create(base);
        String host = u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : 80;
        String body = propfind(host, port, encodePath(path), auth);
        List<String[]> items = parse(body); // {href decodificato, "1" se cartella}
        String self = stripSlash(decode(encodePath(path)));
        List<String> subs = new ArrayList<>();
        for (String[] it : items) {
            String href = stripSlash(it[0]);
            if (href.equals(self)) continue;
            if ("1".equals(it[1])) {
                subs.add(href);
            } else if (isImageName(href) && out.size() < 8000) {
                out.add(new String[] {href, "http://" + host + ":" + port + encodePath(href)});
            }
        }
        if (depth >= 3) return;
        for (String s : subs) {
            if (budget[0] <= 0 || out.size() >= 8000) return;
            budget[0]--;
            collect(base, s, auth, depth + 1, out, budget);
        }
    }

    private static String stripSlash(String s) {
        while (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /** {href, "1" cartella / "0" file} per ogni risposta del multistatus. */
    static List<String[]> parse(String xml) throws IOException {
        List<String[]> out = new ArrayList<>();
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            Document d = f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            NodeList rs = d.getElementsByTagNameNS("*", "response");
            for (int i = 0; i < rs.getLength(); i++) {
                Element r = (Element) rs.item(i);
                NodeList h = r.getElementsByTagNameNS("*", "href");
                if (h.getLength() == 0) continue;
                String href = h.item(0).getTextContent().trim();
                if (href.startsWith("http")) href = URI.create(href.replace(" ", "%20")).getRawPath();
                boolean dir = r.getElementsByTagNameNS("*", "collection").getLength() > 0;
                out.add(new String[] {decode(href), dir ? "1" : "0"});
            }
        } catch (Exception e) {
            throw new IOException("risposta WebDAV non valida");
        }
        return out;
    }

    static String propfind(String host, int port, String encPath, String auth) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 6000);
            s.setSoTimeout(20000);
            String req = "PROPFIND " + encPath + " HTTP/1.1\r\nHost: " + host + ":" + port + "\r\n"
                    + "Authorization: " + auth + "\r\nDepth: 1\r\nContent-Type: application/xml\r\n"
                    + "Content-Length: 0\r\nConnection: close\r\n\r\n";
            OutputStream o = s.getOutputStream();
            o.write(req.getBytes("UTF-8"));
            o.flush();
            InputStream in = s.getInputStream();
            String status = readLine(in);
            if (status == null) throw new IOException("nessuna risposta dal NAS");
            String[] sp = status.split(" ");
            int code = sp.length > 1 ? Integer.parseInt(sp[1].trim()) : 0;
            long len = -1;
            boolean chunked = false;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                String l = line.toLowerCase(Locale.ROOT);
                if (l.startsWith("content-length:")) len = Long.parseLong(l.substring(15).trim());
                if (l.startsWith("transfer-encoding:") && l.contains("chunked")) chunked = true;
            }
            if (code == 401) throw new IOException("utente o password non accettati (401)");
            if (code == 404) throw new IOException("cartella non trovata (404): controlla il percorso");
            if (code != 207 && code != 200) throw new IOException("il NAS ha risposto " + code);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            if (chunked) {
                while (true) {
                    String sz = readLine(in);
                    if (sz == null) break;
                    int semi = sz.indexOf(';');
                    if (semi >= 0) sz = sz.substring(0, semi);
                    sz = sz.trim();
                    if (sz.isEmpty()) continue;
                    int n = Integer.parseInt(sz, 16);
                    if (n == 0) break;
                    copy(in, b, n);
                    readLine(in);
                }
            } else if (len >= 0) {
                copy(in, b, len);
            } else {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            }
            return b.toString("UTF-8");
        }
    }

    private static void copy(InputStream in, ByteArrayOutputStream b, long n) throws IOException {
        byte[] buf = new byte[8192];
        while (n > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, n));
            if (r < 0) throw new IOException("risposta interrotta");
            b.write(buf, 0, r);
            n -= r;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        boolean any = false;
        while ((c = in.read()) >= 0) {
            any = true;
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        return any ? sb.toString() : null;
    }
}
