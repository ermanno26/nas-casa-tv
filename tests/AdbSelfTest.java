import it.nascasa.pulizia.adb.AdbClient;
import it.nascasa.pulizia.adb.AdbKey;

import java.io.File;
import java.nio.file.Files;

public class AdbSelfTest {
    static void check(boolean ok, String what) {
        System.out.println((ok ? "OK   " : "FAIL ") + what);
        if (!ok) System.exit(1);
    }

    public static void main(String[] a) throws Exception {
        int port = Integer.parseInt(a[0]);
        File dir = Files.createTempDirectory("adbkey").toFile();
        AdbKey key = AdbKey.loadOrCreate(dir);

        AdbClient c1 = AdbClient.connect("127.0.0.1", port, key, 5000);
        check(c1.shell("ciao", 5000).equals("eco: ciao\n"), "primo collegamento (invio chiave) + shell");
        String big = c1.shell("grande", 5000);
        check(big.split("\n").length == 3000 && big.contains("riga 2999"), "output grande in piu' pacchetti");
        c1.close();

        AdbKey again = AdbKey.loadOrCreate(dir); // ricarica da file
        AdbClient c2 = AdbClient.connect("127.0.0.1", port, again, 5000);
        check(c2.shell("stato", 5000).equals("chiavi=1\n"), "secondo collegamento: solo firma, nessuna nuova chiave");
        c2.close();
    }
}
