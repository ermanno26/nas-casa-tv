package it.nascasa.tv.dlna;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Chiede al NAS l'elenco di cartelle e file (azione "Browse" del servizio ContentDirectory). */
public final class ContentDirectoryClient {

    private static final int PAGE = 200;
    private static final int MAX_ENTRIES = 20000;

    private final String controlUrl;
    private final String serviceType;

    public ContentDirectoryClient(String controlUrl, String serviceType) {
        this.controlUrl = controlUrl;
        this.serviceType = (serviceType == null || serviceType.isEmpty())
                ? "urn:schemas-upnp-org:service:ContentDirectory:1" : serviceType;
    }

    /** Tutto il contenuto di una cartella ("0" = radice), leggendo a pagine. */
    public List<MediaEntry> browseAll(String objectId) throws IOException {
        List<MediaEntry> all = new ArrayList<>();
        int start = 0;
        while (all.size() < MAX_ENTRIES) {
            Page p = browse(objectId, start, PAGE);
            all.addAll(p.entries);
            start += p.numberReturned;
            if (p.numberReturned <= 0) break;                       // fine
            if (p.totalMatches > 0 && start >= p.totalMatches) break; // letto tutto
        }
        return all;
    }

    static final class Page {
        List<MediaEntry> entries = new ArrayList<>();
        int numberReturned;
        int totalMatches;
    }

    Page browse(String objectId, int start, int count) throws IOException {
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:Browse xmlns:u=\"" + serviceType + "\">"
                + "<ObjectID>" + Xml.escape(objectId) + "</ObjectID>"
                + "<BrowseFlag>BrowseDirectChildren</BrowseFlag>"
                + "<Filter>*</Filter>"
                + "<StartingIndex>" + start + "</StartingIndex>"
                + "<RequestedCount>" + count + "</RequestedCount>"
                + "<SortCriteria></SortCriteria>"
                + "</u:Browse></s:Body></s:Envelope>";

        HttpURLConnection c = (HttpURLConnection) new URL(controlUrl).openConnection();
        String response;
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(30000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            c.setRequestProperty("SOAPACTION", "\"" + serviceType + "#Browse\"");
            c.setRequestProperty("User-Agent", "NASCasa/1.0 UPnP/1.0");
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(data.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(data);
            }
            int code = c.getResponseCode();
            if (code != 200) {
                String err = "";
                try {
                    InputStream es = c.getErrorStream();
                    if (es != null) err = UpnpServer.readAll(es);
                } catch (IOException ignored) { }
                throw new IOException("Il NAS ha risposto con errore " + code + faultInfo(err));
            }
            response = UpnpServer.readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }

        try {
            Document env = Xml.parse(response, true);
            Page p = new Page();
            Element r = Xml.firstDeep(env, "Result");
            String didl = r == null ? "" : r.getTextContent();
            p.numberReturned = intOf(Xml.firstDeep(env, "NumberReturned"), -1);
            p.totalMatches = intOf(Xml.firstDeep(env, "TotalMatches"), -1);
            p.entries = DidlParser.parse(didl, controlUrl);
            if (p.numberReturned < 0) p.numberReturned = p.entries.size();
            return p;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Risposta del NAS non leggibile: " + e.getMessage());
        }
    }

    private static int intOf(Element e, int def) {
        if (e == null) return def;
        try { return Integer.parseInt(e.getTextContent().trim()); } catch (NumberFormatException x) { return def; }
    }

    private static String faultInfo(String xml) {
        try {
            Document d = Xml.parse(xml, true);
            Element desc = Xml.firstDeep(d, "errorDescription");
            if (desc != null) return " (" + desc.getTextContent().trim() + ")";
        } catch (Exception ignored) { }
        return "";
    }
}
