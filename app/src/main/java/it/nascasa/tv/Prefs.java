package it.nascasa.tv;

import android.content.Context;
import android.content.SharedPreferences;

import it.nascasa.tv.dlna.UpnpServer;

/** Impostazioni salvate sulla TV: ultimo NAS usato, ordinamento, punto in cui eri nei video. */
final class Prefs {
    private Prefs() {}

    /** IP del NAS di casa: usato come suggerimento iniziale. */
    static final String DEFAULT_HOST = "192.168.1.28";

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("nascasa", Context.MODE_PRIVATE);
    }

    static void saveServer(Context c, UpnpServer s) {
        sp(c).edit()
                .putString("name", s.name)
                .putString("location", s.location)
                .putString("control", s.controlUrl)
                .putString("svc", s.serviceType)
                .putString("host", s.host())
                .apply();
    }

    /** L'ultimo NAS usato, oppure null. */
    static UpnpServer server(Context c) {
        SharedPreferences p = sp(c);
        String control = p.getString("control", null);
        if (control == null || control.isEmpty()) return null;
        return new UpnpServer(p.getString("name", "NAS"), p.getString("location", ""),
                control, p.getString("svc", ""));
    }

    static String host(Context c) {
        return sp(c).getString("host", DEFAULT_HOST);
    }

    static void setHost(Context c, String host) {
        sp(c).edit().putString("host", host).apply();
    }

    static int sort(Context c) {
        return sp(c).getInt("sort", 1);
    }

    static void setSort(Context c, int mode) {
        sp(c).edit().putInt("sort", mode).apply();
    }

    /** Modalita' audio del video: 0 = software, 1 = hardware, 2 = software + profilo "musica". */
    static int audioMode(Context c) {
        return sp(c).getInt("audiomode", 0);
    }

    static void setAudioMode(Context c, int mode) {
        sp(c).edit().putInt("audiomode", mode).apply();
    }

    static long position(Context c, String url) {
        return sp(c).getLong("pos_" + url.hashCode(), 0);
    }

    static void setPosition(Context c, String url, long ms) {
        SharedPreferences.Editor e = sp(c).edit();
        if (ms <= 0) e.remove("pos_" + url.hashCode()); else e.putLong("pos_" + url.hashCode(), ms);
        e.apply();
    }
}
