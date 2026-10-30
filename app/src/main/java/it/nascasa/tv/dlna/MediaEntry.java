package it.nascasa.tv.dlna;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Una cartella o un file restituito dal NAS (un "container" o un "item" DIDL-Lite). */
public final class MediaEntry {

    public enum Kind { FOLDER, PHOTO, VIDEO, AUDIO, OTHER }

    /** Una "risorsa" = un modo di scaricare il file (originale, miniatura, versione ridotta...). */
    public static final class Res {
        public String url;
        public String protocolInfo = "";
        public String mime = "";
        public long size = -1;
        public int width = -1;
        public int height = -1;
        public String duration;

        public long pixels() {
            return (width > 0 && height > 0) ? (long) width * height : -1;
        }

        /** Formati che Android sa mostrare in modo affidabile. */
        public boolean isDisplayableImage() {
            String m = mime.toLowerCase(Locale.ROOT);
            return m.equals("image/jpeg") || m.equals("image/jpg") || m.equals("image/png")
                    || m.equals("image/webp") || m.equals("image/gif") || m.equals("image/bmp");
        }

        public boolean isThumbnailProfile() {
            String p = protocolInfo.toUpperCase(Locale.ROOT);
            return p.contains("_TN") || p.contains("_SM_ICO") || p.contains("_LRG_ICO");
        }
    }

    public String id;
    public String parentId;
    public String title = "";
    public String upnpClass = "";
    public String date = "";
    public String albumArt;
    public boolean container;
    public int childCount = -1;
    public final List<Res> resources = new ArrayList<>();

    public Kind kind() {
        if (container) return Kind.FOLDER;
        String c = upnpClass.toLowerCase(Locale.ROOT);
        if (c.startsWith("object.item.imageitem")) return Kind.PHOTO;
        if (c.startsWith("object.item.videoitem")) return Kind.VIDEO;
        if (c.startsWith("object.item.audioitem")) return Kind.AUDIO;
        // classe mancante o strana: deduciamo dal tipo MIME
        for (Res r : resources) {
            String m = r.mime.toLowerCase(Locale.ROOT);
            if (m.startsWith("image/")) return Kind.PHOTO;
            if (m.startsWith("video/")) return Kind.VIDEO;
            if (m.startsWith("audio/")) return Kind.AUDIO;
        }
        return Kind.OTHER;
    }

    /** La versione piu' grande tra quelle che Android sa mostrare (altrimenti la piu' grande in assoluto). */
    public Res bestImage() {
        Res best = null;
        for (Res r : resources) {
            if (!r.mime.toLowerCase(Locale.ROOT).startsWith("image/") && !r.mime.isEmpty()) continue;
            if (r.isThumbnailProfile() && hasNonThumb()) continue;
            if (best == null || betterImage(r, best)) best = r;
        }
        return best;
    }

    private boolean hasNonThumb() {
        for (Res r : resources) if (!r.isThumbnailProfile()) return true;
        return false;
    }

    private static boolean betterImage(Res a, Res b) {
        if (a.isDisplayableImage() != b.isDisplayableImage()) return a.isDisplayableImage();
        long pa = a.pixels(), pb = b.pixels();
        if (pa >= 0 && pb >= 0 && pa != pb) return pa > pb;
        return a.size > b.size;
    }

    /** URL della miniatura per la griglia (null se non c'e' nulla di sensato). */
    public String thumbnailUrl() {
        if (kind() == Kind.PHOTO) {
            // 1) una risorsa marcata come miniatura, 2) l'albumArt, 3) la versione piu' piccola visualizzabile
            Res tn = null;
            for (Res r : resources) {
                if (r.isThumbnailProfile() && r.mime.toLowerCase(Locale.ROOT).startsWith("image/")) {
                    if (tn == null || r.isDisplayableImage() && !tn.isDisplayableImage()) tn = r;
                }
            }
            if (tn != null) return tn.url;
            if (albumArt != null) return albumArt;
            Res small = null;
            for (Res r : resources) {
                if (!r.mime.toLowerCase(Locale.ROOT).startsWith("image/") || !r.isDisplayableImage()) continue;
                if (small == null || smaller(r, small)) small = r;
            }
            if (small != null) return small.url;
            Res any = bestImage();
            return any == null ? null : any.url;
        }
        return albumArt;
    }

    private static boolean smaller(Res a, Res b) {
        long pa = a.pixels(), pb = b.pixels();
        if (pa >= 0 && pb >= 0 && pa != pb) return pa < pb;
        if (a.size >= 0 && b.size >= 0) return a.size < b.size;
        return false;
    }

    /** La risorsa da riprodurre per video e audio: l'originale (la piu' grande). */
    public Res playable() {
        Res best = null;
        for (Res r : resources) {
            String m = r.mime.toLowerCase(Locale.ROOT);
            if (!m.isEmpty() && !m.startsWith("video/") && !m.startsWith("audio/")
                    && !m.equals("application/octet-stream") && !m.equals("application/x-matroska")) continue;
            if (best == null || r.size > best.size) best = r;
        }
        return best;
    }

    @Override
    public String toString() {
        return kind() + ":" + title + "(" + id + ")";
    }
}
