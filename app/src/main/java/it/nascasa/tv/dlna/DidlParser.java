package it.nascasa.tv.dlna;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** Legge il formato DIDL-Lite con cui i server DLNA descrivono cartelle e file. */
public final class DidlParser {
    private DidlParser() {}

    public static List<MediaEntry> parse(String didl, String baseUrl) throws Exception {
        List<MediaEntry> out = new ArrayList<>();
        if (didl == null || didl.trim().isEmpty()) return out;

        Document doc = Xml.parse(didl, true);
        Element root = doc.getDocumentElement();
        NodeList nl = root.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (!(n instanceof Element)) continue;
            Element el = (Element) n;
            String name = Xml.local(el.getTagName());
            if (!name.equals("container") && !name.equals("item")) continue;

            MediaEntry e = new MediaEntry();
            e.container = name.equals("container");
            e.id = el.getAttribute("id");
            e.parentId = el.getAttribute("parentID");
            String cc = el.getAttribute("childCount");
            if (cc != null && !cc.isEmpty()) {
                try { e.childCount = Integer.parseInt(cc.trim()); } catch (NumberFormatException ignored) { }
            }
            String t = Xml.childText(el, "title");
            e.title = t == null ? "" : t;
            String k = Xml.childText(el, "class");
            e.upnpClass = k == null ? "" : k;
            String d = Xml.childText(el, "date");
            e.date = d == null ? "" : d;
            String art = Xml.childText(el, "albumArtURI");
            if (art != null && !art.isEmpty()) e.albumArt = absolute(baseUrl, art);

            for (Element r : Xml.children(el, "res")) {
                MediaEntry.Res res = new MediaEntry.Res();
                String u = r.getTextContent().trim();
                if (u.isEmpty()) continue;
                res.url = absolute(baseUrl, u);
                res.protocolInfo = r.getAttribute("protocolInfo");
                res.mime = mimeOf(res.protocolInfo);
                res.size = parseLong(r.getAttribute("size"));
                int[] wh = parseResolution(r.getAttribute("resolution"));
                res.width = wh[0];
                res.height = wh[1];
                String dur = r.getAttribute("duration");
                res.duration = dur == null || dur.isEmpty() ? null : dur;
                e.resources.add(res);
            }
            if (e.id == null || e.id.isEmpty()) continue;
            out.add(e);
        }
        return out;
    }

    /** "http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_LRG;..." -> "image/jpeg" */
    static String mimeOf(String protocolInfo) {
        if (protocolInfo == null) return "";
        String[] p = protocolInfo.split(":");
        return p.length >= 3 ? p[2].trim() : "";
    }

    private static long parseLong(String s) {
        if (s == null || s.trim().isEmpty()) return -1;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return -1; }
    }

    private static int[] parseResolution(String s) {
        int[] r = {-1, -1};
        if (s == null) return r;
        int x = s.toLowerCase().indexOf('x');
        if (x <= 0) return r;
        try {
            r[0] = Integer.parseInt(s.substring(0, x).trim());
            r[1] = Integer.parseInt(s.substring(x + 1).trim());
        } catch (NumberFormatException ignored) {
            r[0] = r[1] = -1;
        }
        return r;
    }

    private static String absolute(String base, String url) {
        try {
            if (base == null || url.startsWith("http://") || url.startsWith("https://")) return url;
            return new URL(new URL(base), url).toString();
        } catch (Exception e) {
            return url;
        }
    }
}
