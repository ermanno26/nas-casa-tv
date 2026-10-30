package it.nascasa.tv.dlna;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/** Piccole utilita' XML (DOM senza namespace) usate da tutto il client DLNA. */
final class Xml {
    private Xml() {}

    // '&' "nudi" (non seguiti da un'entita' valida) li mandano in errore alcuni NAS: li correggiamo.
    private static final Pattern BARE_AMP =
            Pattern.compile("&(?!(?:amp|lt|gt|quot|apos|#\\d+|#x[0-9a-fA-F]+);)");

    static Document parse(String xml, boolean repairAmpersands) throws Exception {
        if (repairAmpersands) {
            xml = BARE_AMP.matcher(xml).replaceAll("&amp;");
        }
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        // Hardening: non esiste su tutte le implementazioni (Android), quindi best-effort.
        trySet(f, "http://apache.org/xml/features/disallow-doctype-decl", true);
        trySet(f, "http://xml.org/sax/features/external-general-entities", false);
        trySet(f, "http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(new InputSource(new StringReader(xml)));
    }

    private static void trySet(DocumentBuilderFactory f, String feature, boolean value) {
        try {
            f.setFeature(feature, value);
        } catch (Throwable ignored) {
            // non supportato: pazienza
        }
    }

    /** Nome senza prefisso: "dc:title" -> "title". */
    static String local(String tag) {
        int i = tag.indexOf(':');
        return i >= 0 ? tag.substring(i + 1) : tag;
    }

    /** Figli diretti con il nome locale indicato. */
    static List<Element> children(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element && local(((Element) n).getTagName()).equals(localName)) {
                out.add((Element) n);
            }
        }
        return out;
    }

    static String childText(Element parent, String localName) {
        List<Element> l = children(parent, localName);
        return l.isEmpty() ? null : l.get(0).getTextContent().trim();
    }

    /** Primo elemento (a qualunque profondita') con il nome locale indicato. */
    static Element firstDeep(Document d, String localName) {
        NodeList all = d.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            if (local(e.getTagName()).equals(localName)) return e;
        }
        return null;
    }

    static List<Element> allDeep(Document d, String localName) {
        List<Element> out = new ArrayList<>();
        NodeList all = d.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            if (local(e.getTagName()).equals(localName)) out.add(e);
        }
        return out;
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
