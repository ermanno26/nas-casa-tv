package it.nascasa.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Scarica le immagini dal NAS e le mostra: cache su disco + cache in memoria, ridimensiona in
 * fase di lettura (nessun rischio di memoria piena con foto da 50 megapixel) e applica la
 * rotazione EXIF (le foto scattate in verticale dal telefono).
 */
final class ImageLoader {
    private ImageLoader() {}

    interface Callback {
        void onLoaded(Bitmap bitmap);
        void onError(String message);
    }

    static final class Handle {
        private volatile boolean cancelled;
        void cancel() { cancelled = true; }
    }

    /** Lato massimo consentito dopo la decodifica: oltre, molte GPU di TV non riescono a disegnare. */
    private static final int MAX_SIDE = 4096;
    private static final long DISK_LIMIT = 500L * 1024 * 1024;
    private static final long DISK_TARGET = 350L * 1024 * 1024;

    private static final ExecutorService THUMBS = Executors.newFixedThreadPool(4);
    private static final ExecutorService FULL = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static LruCache<String, Bitmap> cache;
    private static File dir;

    private static synchronized void init(Context ctx) {
        if (cache != null) return;
        long max = Runtime.getRuntime().maxMemory();
        int bytes = (int) Math.min(max / 6, 160L * 1024 * 1024);
        cache = new LruCache<String, Bitmap>(bytes) {
            @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
        };
        dir = new File(ctx.getCacheDir(), "nas");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        THUMBS.execute(new Runnable() {
            @Override public void run() { trimDisk(); }
        });
    }

    /**
     * Carica l'immagine (maxW x maxH e' la dimensione a cui serve). Il risultato arriva sempre sul
     * thread principale. Con full=true usa la corsia dedicata alle foto a schermo intero.
     */
    static Handle load(Context ctx, final String url, final String mime, final int maxW, final int maxH,
                       boolean full, final Callback cb) {
        init(ctx.getApplicationContext());
        final Handle handle = new Handle();
        final String key = url + "@" + maxW + "x" + maxH;
        Bitmap hit = cache.get(key);
        if (hit != null) {
            cb.onLoaded(hit);
            return handle;
        }
        (full ? FULL : THUMBS).execute(new Runnable() {
            @Override public void run() {
                if (handle.cancelled) return;
                String error;
                try {
                    File f = download(url);
                    if (handle.cancelled) return;
                    Bitmap b = decode(f, maxW, maxH);
                    if (b != null) {
                        cache.put(key, b);
                        deliver(handle, cb, b, null);
                        return;
                    }
                    error = unsupportedMessage(mime);
                } catch (Throwable t) {
                    error = "Impossibile leggere l'immagine: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
                }
                deliver(handle, cb, null, error);
            }
        });
        return handle;
    }

    private static String unsupportedMessage(String mime) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (m.contains("heic") || m.contains("heif")) {
            return "Foto in formato HEIC: questa TV non sa aprirla.\nCaricala in JPEG (iPhone: Impostazioni > Fotocamera > Formati > Piu' compatibile).";
        }
        return "Formato immagine non supportato" + (m.isEmpty() ? "" : " (" + m + ")") + ".";
    }

    private static void deliver(final Handle h, final Callback cb, final Bitmap b, final String err) {
        MAIN.post(new Runnable() {
            @Override public void run() {
                if (h.cancelled) return;
                if (b != null) cb.onLoaded(b); else cb.onError(err);
            }
        });
    }

    // ---------------------------------------------------------------- download + cache su disco

    private static File download(String url) throws Exception {
        File out = new File(dir, sha1(url));
        if (out.exists() && out.length() > 0) {
            //noinspection ResultOfMethodCallIgnored
            out.setLastModified(System.currentTimeMillis());
            return out;
        }
        File tmp = new File(dir, out.getName() + ".tmp" + Thread.currentThread().getId());
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "NASCasa/1.0");
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("il NAS ha risposto " + code);
            try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            if (!tmp.renameTo(out)) throw new IOException("cache non scrivibile");
            return out;
        } finally {
            c.disconnect();
            if (tmp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    private static void trimDisk() {
        try {
            File[] files = dir.listFiles();
            if (files == null) return;
            long total = 0;
            for (File f : files) total += f.length();
            if (total <= DISK_LIMIT) return;
            Arrays.sort(files, new Comparator<File>() {
                @Override public int compare(File a, File b) { return Long.compare(a.lastModified(), b.lastModified()); }
            });
            for (File f : files) {
                if (total <= DISK_TARGET) break;
                long len = f.length();
                if (f.delete()) total -= len;
            }
        } catch (Throwable ignored) {
            // la cache e' solo un'ottimizzazione
        }
    }

    private static String sha1(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // ---------------------------------------------------------------- decodifica

    private static Bitmap decode(File f, int maxW, int maxH) {
        String path = f.getAbsolutePath();
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;

        int shortSide = Math.min(o.outWidth, o.outHeight), longSide = Math.max(o.outWidth, o.outHeight);
        int tShort = Math.min(maxW, maxH), tLong = Math.max(maxW, maxH);
        int sample = 1;
        // riduciamo finche' restiamo almeno alla dimensione richiesta, e comunque sotto il limite GPU
        while ((shortSide / (sample * 2) >= tShort && longSide / (sample * 2) >= tLong)
                || longSide / sample > MAX_SIDE) {
            sample *= 2;
        }

        boolean small = (long) maxW * maxH <= 600L * 400L;
        Bitmap bmp = null;
        for (int attempt = 0; attempt < 3 && bmp == null; attempt++) {
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            d.inPreferredConfig = small ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
            try {
                bmp = BitmapFactory.decodeFile(path, d);
                break;
            } catch (OutOfMemoryError oom) {
                sample *= 2;
            }
        }
        if (bmp == null) return null;
        return applyExif(bmp, path);
    }

    private static Bitmap applyExif(Bitmap b, String path) {
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try {
            orientation = new ExifInterface(path).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Throwable ignored) {
            // non e' un JPEG con EXIF: va bene cosi'
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
            default: return b;
        }
        try {
            Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (r != b) b.recycle();
            return r;
        } catch (Throwable t) {
            return b;
        }
    }
}
