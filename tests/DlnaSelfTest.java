package it.nascasa.tv.dlna;

import java.util.List;

/** Prova di tutto il client DLNA contro tests/mock_dlna.py. Uso: vedi tests/run_tests.sh */
public class DlnaSelfTest {
    static int failures = 0;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Scoperta SSDP (unicast verso il finto NAS)");
        List<UpnpServer> servers = Ssdp.discover("127.0.0.1", 11900, 2500);
        check("trovato 1 server", servers.size() == 1);
        UpnpServer s = servers.get(0);
        check("nome = NAS-Casa-Test", "NAS-Casa-Test".equals(s.name));
        check("controlUrl assoluto", "http://127.0.0.1:18200/ctl/ContentDir".equals(s.controlUrl));
        check("serviceType ContentDirectory", s.serviceType.contains("ContentDirectory"));

        System.out.println("Collegamento manuale");
        UpnpServer m = Ssdp.probe("http://127.0.0.1:18200/rootDesc.xml");
        check("probe da URL completo", m != null && "NAS-Casa-Test".equals(m.name));
        check("probe di host inesistente = null", Ssdp.probe("http://127.0.0.1:1/x.xml") == null);

        ContentDirectoryClient c = new ContentDirectoryClient(s.controlUrl, s.serviceType);

        System.out.println("Radice");
        List<MediaEntry> root = c.browseAll("0");
        check("4 cartelle in radice (paginazione 3+1)", root.size() == 4);
        check("tutte cartelle", root.stream().allMatch(e -> e.kind() == MediaEntry.Kind.FOLDER));
        check("titolo con & decodificato", "Foto & Co".equals(root.get(3).title));
        check("childCount letto", root.get(0).childCount == 5);

        System.out.println("Cartella foto (5 elementi, pagine da 3)");
        List<MediaEntry> pics = c.browseAll("1");
        check("5 foto lette", pics.size() == 5);
        MediaEntry p1 = pics.get(0), p2 = pics.get(1), p3 = pics.get(2), p4 = pics.get(3), p5 = pics.get(4);
        check("tipo FOTO", p1.kind() == MediaEntry.Kind.PHOTO);
        check("p1 foto grande = originale 4032x3024 (JPEG)", p1.bestImage().url.endsWith("p1-orig.jpg"));
        check("p1 miniatura = JPEG_TN", p1.thumbnailUrl().endsWith("p1-tn.jpg"));
        check("p2 HEIC: unica risorsa, mime image/heic", p2.bestImage().mime.equals("image/heic")
                && !p2.bestImage().isDisplayableImage());
        check("p3 '&' non escapato riparato", p3.bestImage().url.endsWith("p3.jpg?a=1&b=2"));
        check("p4 URL relativo reso assoluto", p4.bestImage().url.equals("http://127.0.0.1:18200/m/p4.png"));
        check("p5 miniatura = albumArt", p5.thumbnailUrl().endsWith("p5-art.jpg"));
        check("p3 senza miniatura: usa l'originale", p3.thumbnailUrl().endsWith("p3.jpg?a=1&b=2"));

        System.out.println("Video");
        List<MediaEntry> vids = c.browseAll("2");
        check("2 video", vids.size() == 2 && vids.get(0).kind() == MediaEntry.Kind.VIDEO);
        check("video: sceglie l'originale (piu' grande)", vids.get(0).playable().url.endsWith("v1.mp4"));
        check("video: miniatura dall'albumArt", vids.get(0).thumbnailUrl().endsWith("v1-art.jpg"));
        check("mkv riconosciuto", vids.get(1).playable() != null
                && vids.get(1).playable().mime.equals("video/x-matroska"));

        System.out.println("Casi particolari");
        check("cartella vuota = lista vuota", c.browseAll("3").isEmpty());
        boolean threw = false;
        String msg = "";
        try { c.browseAll("bad"); } catch (java.io.IOException e) { threw = true; msg = e.getMessage(); }
        check("errore 500 -> IOException con descrizione", threw && msg.contains("No such object"));

        System.out.println(failures == 0 ? "\nTUTTO OK" : "\nERRORI: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
