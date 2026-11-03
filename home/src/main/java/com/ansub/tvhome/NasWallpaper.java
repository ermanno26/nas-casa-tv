package com.ansub.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.media.ExifInterface;
import android.net.wifi.WifiManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import it.nascasa.tv.dlna.ContentDirectoryClient;
import it.nascasa.tv.dlna.MediaEntry;
import it.nascasa.tv.dlna.Ssdp;
import it.nascasa.tv.dlna.UpnpServer;

/**
 * Sfondo della home preso dalle foto di una cartella del NAS (DLNA, come l'app NAS Casa).
 * Una sola immagine alla volta: scaricata in background, ridotta allo schermo, scurita un poco
 * (perche' orologio e icone restino leggibili) e salvata sul disco. La home mostra quella.
 */
final class NasWallpaper {
    private NasWallpaper() {}

    /** Modi di cambio: indice in MODES, minuti in MINUTES (0 = non a tempo). */
    static final String[] MODES = {
            "Foto fissa (quella di adesso)",
            "Nuova foto ogni volta che apro la home",
            "Nuova foto ogni 5 minuti",
            "Nuova foto ogni 15 minuti",
            "Nuova foto ogni 30 minuti",
            "Nuova foto ogni ora",
            "Nuova foto ogni 3 ore",
    };
    static final int[] MINUTES = {0, 0, 5, 15, 30, 60, 180};

    private static final String HINT_HOST = "192.168.1.28";
    private static final int W = 1920, H = 1080;
    private static final long LIST_TTL = 60 * 60 * 1000L;

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nas-wallpaper");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final Random RND = new Random();

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("naswall", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ impostazioni

    static boolean enabled(Context c) {
        SharedPreferences p = sp(c);
        return p.getBoolean("on", false) && p.getString("folder", null) != null;
    }

    static void setEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean("on", on).apply();
    }

    static int mode(Context c) {
        int m = sp(c).getInt("mode", 1);
        return m >= 0 && m < MODES.length ? m : 1;
    }

    static void setMode(Context c, int m) {
        sp(c).edit().putInt("mode", m).putLong("last", System.currentTimeMillis()).apply();
    }

    static String folderName(Context c) {
        return sp(c).getString("fname", null);
    }

    static void setFolder(Context c, UpnpServer s, String folderId, String name) {
        sp(c).edit()
                .putString("srvname", s.name).putString("loc", s.location)
                .putString("ctl", s.controlUrl).putString("svc", s.serviceType)
                .putString("folder", folderId).putString("fname", name).putString("src", "dlna")
                .putBoolean("on", true).putLong("list_ts", 0).putLong("last", 0).putLong("fail", 0)
                .apply();
    }

    // ---- sorgente WebDAV (legge anche PNG e ogni altro formato immagine)

    static boolean isDav(Context c) {
        return "dav".equals(sp(c).getString("src", "dlna"));
    }

    static String davAddress(Context c) {
        return sp(c).getString("dav_addr", HINT_HOST + ":5005");
    }

    static String davPath(Context c) {
        return sp(c).getString("dav_path", "/Pubblico/TV Sfondi");
    }

    static String davUser(Context c) {
        return sp(c).getString("dav_user", "");
    }

    static void setDav(Context c, String addr, String path, String user, String pass) {
        String a = addr.trim().replace("http://", "").replace("https://", "");
        while (a.endsWith("/")) a = a.substring(0, a.length() - 1);
        if (a.indexOf(':') < 0) a += ":5005";
        String pp = path.trim();
        if (!pp.startsWith("/")) pp = "/" + pp;
        String auth = "Basic " + android.util.Base64.encodeToString(
                (user + ":" + pass).getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        sp(c).edit().putString("src", "dav").putString("dav_addr", a).putString("dav_path", pp)
                .putString("dav_user", user).putString("dav_auth", auth)
                .putString("folder", "dav").putString("fname", pp)
                .putBoolean("on", true).putLong("list_ts", 0).putLong("last", 0).putLong("fail", 0)
                .apply();
    }

    /** Prova il collegamento: numero di immagini trovate, oppure eccezione con il motivo. */
    static int davTest(Context c) throws IOException {
        List<String[]> out = new ArrayList<>();
        SharedPreferences p = sp(c);
        Dav.collect("http://" + p.getString("dav_addr", ""), p.getString("dav_path", "/"),
                p.getString("dav_auth", ""), 0, out, new int[] {80});
        return out.size();
    }

    static File current(Context c) {
        File f = new File(c.getFilesDir(), "wall/current.jpg");
        return f.exists() && f.length() > 0 ? f : null;
    }

    // ------------------------------------------------------------------ quando cambiare

    /** La home e' stata aperta (o e' passato del tempo): cambia foto se e' il momento. */
    static void onHomeOpened(Context c, Runnable changed) {
        if (!enabled(c)) return;
        SharedPreferences p = sp(c);
        long now = System.currentTimeMillis();
        if (now - p.getLong("fail", 0) < 2 * 60 * 1000L) return; // NAS spento: riprova tra un po'
        int m = mode(c);
        long last = p.getLong("last", 0);
        boolean due;
        if (current(c) == null) due = true;
        else if (m == 0) due = false;
        else if (m == 1) due = now - last > 20000;
        else due = now - last >= MINUTES[m] * 60000L;
        if (due) refresh(c, changed);
    }

    /** Millisecondi al prossimo cambio a tempo, oppure -1 se non e' a tempo. */
    static long msToNext(Context c) {
        if (!enabled(c)) return -1;
        int m = mode(c);
        if (MINUTES[m] == 0) return -1;
        long left = sp(c).getLong("last", 0) + MINUTES[m] * 60000L - System.currentTimeMillis();
        return Math.max(30000, left);
    }

    /** Cambia foto adesso, in background. */
    static void refresh(final Context c, final Runnable changed) {
        if (!BUSY.compareAndSet(false, true)) return;
        final Context app = c.getApplicationContext();
        EXEC.execute(() -> {
            boolean ok = false;
            try {
                ok = change(app);
            } catch (Throwable ignored) {
                // NAS non raggiungibile, foto non leggibile...: resta quella di prima
            } finally {
                if (!ok) sp(app).edit().putLong("fail", System.currentTimeMillis()).apply();
                BUSY.set(false);
            }
            if (ok && changed != null) changed.run();
        });
    }

    private static boolean change(Context c) throws Exception {
        SharedPreferences p = sp(c);
        UpnpServer s = null;
        if (!isDav(c)) {
            s = server(c);
            if (s == null) return false;
        }
        List<String[]> list = photos(c, s, false);
        if (list.isEmpty()) return false;
        String cur = p.getString("cur", "");
        for (int attempt = 0; attempt < 6; attempt++) {
            String[] pick = list.get(RND.nextInt(list.size()));
            if (list.size() > 1 && pick[0].equals(cur) && attempt < 4) continue;
            File tmp = null;
            try {
                tmp = download(c, pick[1]);
                Bitmap out = process(tmp);
                if (out == null) continue; // formato non leggibile (es. HEIC): prova un'altra
                File dir = new File(c.getFilesDir(), "wall");
                dir.mkdirs();
                File part = new File(dir, "current.part");
                try (FileOutputStream o = new FileOutputStream(part)) {
                    out.compress(Bitmap.CompressFormat.JPEG, 90, o);
                }
                out.recycle();
                File dst = new File(dir, "current.jpg");
                if (!part.renameTo(dst)) return false;
                p.edit().putString("cur", pick[0]).putLong("last", System.currentTimeMillis())
                        .putLong("fail", 0).apply();
                return true;
            } catch (IOException e) {
                // il link puo' essere cambiato (IP del NAS): rileggo l'elenco la prossima volta
                p.edit().putLong("list_ts", 0).apply();
                throw e;
            } finally {
                if (tmp != null) tmp.delete();
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ NAS

    /** Cerca i server multimediali in rete (serve il "multicast lock" su Wi-Fi). */
    static List<UpnpServer> discover(Context c) {
        WifiManager.MulticastLock lock = null;
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                lock = wm.createMulticastLock("naswall");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
        } catch (Exception ignored) {
        }
        try {
            return Ssdp.discover(HINT_HOST, 4000);
        } finally {
            try {
                if (lock != null && lock.isHeld()) lock.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static UpnpServer server(Context c) {
        SharedPreferences p = sp(c);
        String name = p.getString("srvname", "NAS");
        String loc = p.getString("loc", "");
        UpnpServer ok = UpnpServer.describe(loc, 3000);
        if (ok != null) return ok;
        // l'IP del NAS e' cambiato: lo cerco di nuovo per nome
        List<UpnpServer> found = discover(c);
        UpnpServer pick = null;
        for (UpnpServer f : found) if (name.equals(f.name)) pick = f;
        if (pick == null && found.size() == 1) pick = found.get(0);
        if (pick != null) {
            p.edit().putString("loc", pick.location).putString("ctl", pick.controlUrl)
                    .putString("svc", pick.serviceType).putLong("list_ts", 0).apply();
        }
        return pick;
    }

    static List<MediaEntry> browse(UpnpServer s, String objectId) throws IOException {
        return new ContentDirectoryClient(s.controlUrl, s.serviceType).browseAll(objectId);
    }

    /** Elenco delle foto della cartella scelta: coppie {id, url}. Tenuto in cache un'ora. */
    private static List<String[]> photos(Context c, UpnpServer s, boolean forceFresh) throws IOException {
        SharedPreferences p = sp(c);
        File cache = new File(c.getFilesDir(), "wall/list.txt");
        List<String[]> out = new ArrayList<>();
        boolean fresh = !forceFresh && cache.exists()
                && System.currentTimeMillis() - p.getLong("list_ts", 0) < LIST_TTL;
        if (fresh) {
            try (BufferedReader r = new BufferedReader(new FileReader(cache))) {
                String line;
                while ((line = r.readLine()) != null) {
                    int tab = line.indexOf('\t');
                    if (tab > 0) out.add(new String[]{line.substring(0, tab), line.substring(tab + 1)});
                }
            }
            if (!out.isEmpty()) return out;
        }
        if (isDav(c)) {
            Dav.collect("http://" + p.getString("dav_addr", ""), p.getString("dav_path", "/"),
                    p.getString("dav_auth", ""), 0, out, new int[] {80});
        } else
        // la cartella scelta e, se servono, le sottocartelle (anche per data: anno/mese...), con un limite
        collect(s, p.getString("folder", "0"), 0, out, new int[] {80});
        cache.getParentFile().mkdirs();
        try (FileWriter w = new FileWriter(cache)) {
            for (String[] a : out) w.write(a[0].replace('\t', ' ') + "\t" + a[1] + "\n");
        }
        p.edit().putLong("list_ts", System.currentTimeMillis()).apply();
        return out;
    }

    private static void collect(UpnpServer s, String id, int depth, List<String[]> out, int[] budget)
            throws IOException {
        List<MediaEntry> entries = browse(s, id);
        List<MediaEntry> subs = new ArrayList<>();
        for (MediaEntry e : entries) {
            if (e.container) {
                subs.add(e);
                continue;
            }
            if (e.kind() != MediaEntry.Kind.PHOTO || out.size() >= 8000) continue;
            MediaEntry.Res r = e.bestImage();
            if (r == null || r.url == null || !r.isDisplayableImage()) continue;
            out.add(new String[]{e.id, r.url});
        }
        if (depth >= 3) return;
        for (MediaEntry f : subs) {
            if (budget[0] <= 0 || out.size() >= 8000) return;
            budget[0]--;
            collect(s, f.id, depth + 1, out, budget);
        }
    }

    // ------------------------------------------------------------------ immagine

    private static File download(Context c, String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(30000);
        if (isDav(c)) con.setRequestProperty("Authorization", sp(c).getString("dav_auth", ""));
        File f = new File(c.getCacheDir(), "wall_dl.tmp");
        try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(f)) {
            byte[] buf = new byte[32 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
                if (total > 80L * 1024 * 1024) throw new IOException("file troppo grande");
            }
        } finally {
            con.disconnect();
        }
        return f;
    }

    /** Foto -> 1920x1080 (ritaglio al centro), ruotata come da EXIF, con una leggera ombra scura. */
    private static Bitmap process(File f) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= W && o.outHeight / (sample * 2) >= H) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            Bitmap src = BitmapFactory.decodeFile(f.getPath(), d);
            if (src == null) return null;

            int orientation = ExifInterface.ORIENTATION_NORMAL;
            try {
                orientation = new ExifInterface(f.getPath()).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            } catch (Exception ignored) {
            }
            Matrix m = new Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.setScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_180: m.setRotate(180); break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL: m.setRotate(180); m.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_TRANSPOSE: m.setRotate(90); m.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_90: m.setRotate(90); break;
                case ExifInterface.ORIENTATION_TRANSVERSE: m.setRotate(-90); m.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_270: m.setRotate(-90); break;
                default: m = null;
            }
            if (m != null) {
                Bitmap r = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
                if (r != src) src.recycle();
                src = r;
            }

            // ritaglio al centro in 16:9
            float scale = Math.max(W / (float) src.getWidth(), H / (float) src.getHeight());
            int cw = Math.min(src.getWidth(), Math.round(W / scale));
            int ch = Math.min(src.getHeight(), Math.round(H / scale));
            Rect from = new Rect((src.getWidth() - cw) / 2, (src.getHeight() - ch) / 2,
                    (src.getWidth() + cw) / 2, (src.getHeight() + ch) / 2);
            Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(out);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            cv.drawBitmap(src, from, new Rect(0, 0, W, H), paint);
            src.recycle();

            // ombra: un po' sopra (orologio) e piu' forte in basso (etichette delle icone)
            Paint shade = new Paint();
            shade.setShader(new LinearGradient(0, 0, 0, H,
                    new int[]{Color.argb(120, 0, 0, 0), Color.argb(70, 0, 0, 0), Color.argb(150, 0, 0, 0)},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, W, H, shade);
            return out;
        } catch (OutOfMemoryError e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** L'immagine salvata, pronta per lo sfondo (sul processore grafico dove si puo'). */
    static android.graphics.drawable.Drawable drawable(Context c, File f) {
        try {
            Bitmap bm = BitmapFactory.decodeFile(f.getPath());
            if (bm == null) return null;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                Bitmap hw = bm.copy(Bitmap.Config.HARDWARE, false);
                if (hw != null) {
                    bm.recycle();
                    bm = hw;
                }
            }
            return new android.graphics.drawable.BitmapDrawable(c.getResources(), bm);
        } catch (Throwable t) {
            return null;
        }
    }
}
