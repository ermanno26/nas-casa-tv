package com.ansub.tvhome;
import java.util.*;
public class DavSelfTest {
    public static void main(String[] a) throws Exception {
        String base = "http://127.0.0.1:" + a[0];
        String ok = "Basic dHY6c2VncmV0bw==";
        List<String[]> out = new ArrayList<>();
        Dav.collect(base, "/Pubblico/TV Sfondi", ok, 0, out, new int[]{20});
        Set<String> got = new TreeSet<>();
        for (String[] e : out) { got.add(e[0]); System.out.println(e[0] + " -> " + e[1]); }
        check(got.equals(new TreeSet<>(Arrays.asList("/Pubblico/TV Sfondi/a.png", "/Pubblico/TV Sfondi/b.JPG", "/Pubblico/TV Sfondi/sub/c.jpeg"))), "elenco");
        try { Dav.collect(base, "/Pubblico/TV Sfondi", "Basic xxx", 0, new ArrayList<>(), new int[]{5}); check(false, "401"); }
        catch (java.io.IOException e) { check(e.getMessage().contains("401"), "401 msg"); }
        try { Dav.collect(base, "/nope", ok, 0, new ArrayList<>(), new int[]{5}); check(false, "404"); }
        catch (java.io.IOException e) { check(e.getMessage().contains("404"), "404 msg"); }
        System.out.println("DAV OK");
    }
    static void check(boolean b, String m) { if (!b) { System.out.println("FAIL " + m); System.exit(1); } }
}
