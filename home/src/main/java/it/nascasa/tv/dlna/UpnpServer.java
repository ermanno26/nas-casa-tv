package it.nascasa.tv.dlna;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Un server multimediale UPnP/DLNA (il NAS) e l'indirizzo a cui chiedere l'elenco dei file. */
public final class UpnpServer {
    public final String name;
    public final String location;     // indirizzo della "scheda tecnica" XML del dispositivo
    public final String controlUrl;   // indirizzo del servizio ContentDirectory
    public final String serviceType;  // es. urn:schemas-upnp-org:service:ContentDirectory:1

    public UpnpServer(String name, String location, String controlUrl, String serviceType) {
        this.name = name;
        this.location = location;
        this.controlUrl = controlUrl;
        this.serviceType = serviceType;
    }

    public String host() {
        try { return new URL(controlUrl).getHost(); } catch (Exception e) { return ""; }
    }

    /** Scarica la scheda del dispositivo; null se non e' un media server (o non risponde). */
    public static UpnpServer describe(String location) {
        return describe(location, 4000);
    }

    public static UpnpServer describe(String location, int timeoutMs) {
        try {
            String xml = httpGet(location, timeoutMs);
            Document d = Xml.parse(xml, true);

            String base = location;
            Element urlBase = Xml.firstDeep(d, "URLBase");
            if (urlBase != null && !urlBase.getTextContent().trim().isEmpty()) {
                base = urlBase.getTextContent().trim();
            }
            Element fn = Xml.firstDeep(d, "friendlyName");
            String name = fn != null ? fn.getTextContent().trim() : "";
            if (name.isEmpty()) name = new URL(location).getHost();

            for (Element s : Xml.allDeep(d, "service")) {
                String type = Xml.childText(s, "serviceType");
                String ctl = Xml.childText(s, "controlURL");
                if (type != null && ctl != null && type.contains(":service:ContentDirectory:")) {
                    String abs = new URL(new URL(base), ctl).toString();
                    return new UpnpServer(name, location, abs, type);
                }
            }
        } catch (Exception ignored) {
            // non raggiungibile o non valido
        }
        return null;
    }

    static String httpGet(String url, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("User-Agent", "NASCasa/1.0 UPnP/1.0");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            return readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    static String readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        }
    }
}
