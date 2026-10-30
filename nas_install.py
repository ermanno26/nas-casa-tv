#!/usr/bin/env python3
"""
nas_install.py - Installa (o aggiorna) l'app "NAS Casa" sulla Xiaomi TV via ADB e la avvia.

Uso (da Windows, nella cartella platform-tools dove c'e' adb.exe):
  py nas_install.py 192.168.1.19 "C:\\Users\\TUO_NOME\\Downloads\\nas-casa-apk.zip"
  py nas_install.py 192.168.1.19 "C:\\Users\\TUO_NOME\\Downloads\\app-debug.apk"
  py nas_install.py 192.168.1.19            (cerca da solo il file piu' recente in Downloads)

Il file puo' essere lo zip scaricato da GitHub Actions oppure direttamente l'APK.
"""
import glob
import os
import subprocess
import sys
import tempfile
import zipfile

PKG = "it.nascasa.tv"
ACTIVITY = PKG + "/.ServerListActivity"


def adb(*args):
    r = subprocess.run(["adb", *args], capture_output=True, text=True)
    return (r.stdout + r.stderr).replace("\r", "").strip(), r.returncode


def find_download():
    folder = os.path.join(os.path.expanduser("~"), "Downloads")
    found = []
    for pattern in ("nas-casa-apk*.zip", "app-debug*.apk", "nas-casa*.apk"):
        found += glob.glob(os.path.join(folder, pattern))
    return max(found, key=os.path.getmtime) if found else None


def get_apk(path):
    if path.lower().endswith(".apk"):
        return path
    if path.lower().endswith(".zip"):
        out = tempfile.mkdtemp(prefix="nascasa_")
        with zipfile.ZipFile(path) as z:
            names = [n for n in z.namelist() if n.lower().endswith(".apk")]
            if not names:
                print("Nello zip non c'e' nessun file .apk.")
                sys.exit(1)
            z.extract(names[0], out)
            return os.path.join(out, names[0])
    print("Il file deve essere uno .zip (scaricato da GitHub) oppure un .apk.")
    sys.exit(1)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    ip = sys.argv[1]
    tv = ip if ":" in ip else ip + ":5555"

    path = sys.argv[2] if len(sys.argv) > 2 else find_download()
    if not path or not os.path.exists(path):
        print("File non trovato. Scrivi il percorso dello zip/apk scaricato come secondo argomento.")
        sys.exit(1)
    print("File:", path)
    apk = get_apk(path)

    print(adb("connect", tv)[0])
    state = adb("-s", tv, "get-state")[0]
    if state != "device":
        if "unauthorized" in state:
            print("La TV chiede 'Consentire il debug?': accetta (spunta 'Consenti sempre') e rilancia.")
        else:
            print("TV non raggiungibile: accesa, stessa rete, debug USB attivo?")
        sys.exit(1)

    print("Installo sulla TV (puo' richiedere 20-60 secondi)...")
    out, rc = adb("-s", tv, "install", "-r", apk)
    if "Success" not in out:
        if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in out or "signatures do not match" in out:
            print("Versione precedente firmata con un'altra chiave: la sostituisco.")
            adb("-s", tv, "uninstall", PKG)
            out, rc = adb("-s", tv, "install", apk)
        if "Success" not in out:
            print("Installazione non riuscita:\n" + out)
            print("Se la TV mostra un avviso di Google Play Protect, consenti l'installazione e rilancia.")
            sys.exit(1)

    print("Installata. La avvio...")
    adb("-s", tv, "shell", "am", "start", "-n", ACTIVITY)
    print("\nFatto. Per averla nella tua home: Aggiungi app > NAS Casa.")


if __name__ == "__main__":
    main()
