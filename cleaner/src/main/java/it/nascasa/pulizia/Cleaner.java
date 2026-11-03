package it.nascasa.pulizia;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Environment;
import android.os.StatFs;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import it.nascasa.pulizia.adb.AdbClient;
import it.nascasa.pulizia.adb.AdbKey;

/** Le operazioni di pulizia: tutte passano dal debug ADB della TV stessa (127.0.0.1:5555). */
final class Cleaner {

    static final class RunningApp {
        String pkg;
        String label;
        long rssKb;
        boolean systemOnly;
    }

    private static Cleaner instance;

    static synchronized Cleaner get(Context c) {
        if (instance == null) instance = new Cleaner(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private AdbClient adb;

    private Cleaner(Context c) {
        this.ctx = c;
    }

    // ------------------------------------------------------------------ collegamento

    /** Si collega (la prima volta la TV chiede di consentire il debug). */
    synchronized void connect() throws IOException {
        if (adb != null) return;
        try {
            AdbKey key = AdbKey.loadOrCreate(new File(ctx.getFilesDir(), "adb"));
            adb = AdbClient.connect("127.0.0.1", 5555, key, 90000);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.toString());
        }
    }

    synchronized boolean isConnected() {
        return adb != null;
    }

    private synchronized void drop() {
        if (adb != null) adb.close();
        adb = null;
    }

    synchronized String sh(String cmd) throws IOException {
        connect();
        try {
            return adb.shell(cmd, 30000);
        } catch (IOException first) {
            drop();
            connect();
            return adb.shell(cmd, 30000);
        }
    }

    // ------------------------------------------------------------------ numeri

    static long availRam(Context c) {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
        return mi.availMem;
    }

    static long totalRam(Context c) {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
        return mi.totalMem;
    }

    static long freeStorage() {
        StatFs s = new StatFs(Environment.getDataDirectory().getPath());
        return s.getAvailableBytes();
    }

    static long totalStorage() {
        StatFs s = new StatFs(Environment.getDataDirectory().getPath());
        return s.getTotalBytes();
    }

    static String fmt(long bytes) {
        if (bytes >= 1L << 30) return String.format(java.util.Locale.ITALY, "%.2f GB", bytes / (double) (1L << 30));
        return String.format(java.util.Locale.ITALY, "%d MB", bytes / (1L << 20));
    }

    // ------------------------------------------------------------------ app in esecuzione

    private Set<String> protectedPackages() {
        Set<String> p = new HashSet<>();
        p.add(ctx.getPackageName());
        p.add("android");
        p.add("com.android.systemui");
        PackageManager pm = ctx.getPackageManager();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : pm.queryIntentActivities(home, 0)) p.add(ri.activityInfo.packageName);
        try {
            InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            for (InputMethodInfo i : imm.getInputMethodList()) p.add(i.getPackageName());
        } catch (Exception ignored) {
        }
        return p;
    }

    /** App con icona (non servizi di sistema) che hanno un processo attivo, dalla piu' pesante. */
    List<RunningApp> runningApps() throws IOException {
        String out = sh("ps -A -o RSS,NAME 2>/dev/null || ps -A 2>/dev/null || ps");
        PackageManager pm = ctx.getPackageManager();
        Set<String> prot = protectedPackages();
        Map<String, RunningApp> map = new HashMap<>();

        String[] lines = out.split("\n");
        int rssIdx = -1;
        for (String line : lines) {
            String[] t = line.trim().split("\\s+");
            if (t.length < 2) continue;
            if (rssIdx < 0) {
                for (int i = 0; i < t.length; i++) if (t[i].equals("RSS")) rssIdx = i;
                if (rssIdx >= 0) continue; // riga di intestazione
                rssIdx = 0;
            }
            if (rssIdx >= t.length - 1) continue;
            long rss;
            try {
                rss = Long.parseLong(t[rssIdx]);
            } catch (NumberFormatException e) {
                continue;
            }
            String name = t[t.length - 1];
            String pkg = name.contains(":") ? name.substring(0, name.indexOf(':')) : name;
            if (!pkg.contains(".") || prot.contains(pkg)) continue;
            RunningApp ra = map.get(pkg);
            if (ra == null) {
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    if (pm.getLaunchIntentForPackage(pkg) == null
                            && pm.getLeanbackLaunchIntentForPackage(pkg) == null) continue;
                    ra = new RunningApp();
                    ra.pkg = pkg;
                    ra.label = String.valueOf(pm.getApplicationLabel(ai));
                    ra.systemOnly = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                            && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                } catch (PackageManager.NameNotFoundException e) {
                    continue;
                }
                map.put(pkg, ra);
            }
            ra.rssKb += rss;
        }
        List<RunningApp> list = new ArrayList<>(map.values());
        Collections.sort(list, new Comparator<RunningApp>() {
            @Override public int compare(RunningApp a, RunningApp b) { return Long.compare(b.rssKb, a.rssKb); }
        });
        return list;
    }

    void forceStop(String pkg) throws IOException {
        sh("am force-stop " + pkg);
    }

    // ------------------------------------------------------------------ azioni

    /** Chiude le app installate dall'utente rimaste aperte e svuota la memoria in cache. */
    String freeRam() throws IOException {
        long before = availRam(ctx);
        int n = 0;
        StringBuilder names = new StringBuilder();
        for (RunningApp a : runningApps()) {
            if (a.systemOnly) continue;
            forceStop(a.pkg);
            n++;
            if (names.length() < 120) names.append(names.length() > 0 ? ", " : "").append(a.label);
        }
        sh("am kill-all");
        sleep(1500);
        long after = availRam(ctx);
        return "RAM libera: " + fmt(before) + " -> " + fmt(after)
                + (n > 0 ? "\nChiuse " + n + " app: " + names : "\nNessuna app da chiudere.");
    }

    /** Svuota la cache di tutte le app. */
    String clearCache() throws IOException {
        long before = freeStorage();
        sh("pm trim-caches 1000000000000");
        sleep(1500);
        long after = freeStorage();
        return "Spazio libero: " + fmt(before) + " -> " + fmt(after)
                + (after > before ? "\nLiberati " + fmt(after - before) + " di cache." : "\nCache gia' pulita.");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
