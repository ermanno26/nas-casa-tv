#!/usr/bin/env python3
"""
home_install.py - Installa la home con gli sfondi dal NAS (basata su tv-detox, MIT) e la imposta.

Uso (da Windows, in C:\\platform-tools, con la TV accesa):
  py home_install.py 192.168.1.30                    installa e imposta come home (cerca lo zip in Downloads)
  py home_install.py 192.168.1.30 "C:\\...\\home-apk.zip"
  py home_install.py 192.168.1.30 --old              torna alla home di prima (tv-detox)
  py home_install.py 192.168.1.30 --tasti            prova: riaccende la home di Google TV (tasti EPG,
                                                     Impostazioni, YouTube/Prime...) e fa aprire la nostra
                                                     col tasto Home tramite "Pulsante Home" (Accessibilita')
  py home_install.py 192.168.1.30 --tasti-off        annulla --tasti

La home di prima NON viene disinstallata: resta come riserva.
"""
import glob
import os
import subprocess
import sys
import tempfile
import zipfile

NEW = "com.nascasa.home"
NEW_ACT = NEW + "/com.ansub.tvhome.MainActivity"
NEW_SVC = NEW + "/com.ansub.tvhome.HomeButton"
OLD = "com.ansub.tvhome"
OLD_ACT = OLD + "/.MainActivity"
GTV = "com.google.android.apps.tv.launcherx"


def adb(*a):
    r = subprocess.run(["adb", *a], capture_output=True, text=True)
    return (r.stdout + r.stderr).replace("\r", "").strip(), r.returncode


def find_download():
    folder = os.path.join(os.path.expanduser("~"), "Downloads")
    found = []
    for pat in ("home-apk*.zip", "home-debug*.apk"):
        found += glob.glob(os.path.join(folder, pat))
    return max(found, key=os.path.getmtime) if found else None


def get_apk(path):
    if path.lower().endswith(".apk"):
        return path
    out = tempfile.mkdtemp(prefix="nashome_")
    with zipfile.ZipFile(path) as z:
        names = [n for n in z.namelist() if n.lower().endswith(".apk")]
        if not names:
            print("Nello zip non c'e' nessun .apk.")
            sys.exit(1)
        z.extract(names[0], out)
        return os.path.join(out, names[0])


def current_home(sh):
    out = sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
    return out.splitlines()[-1].strip() if out else ""


def set_home(sh, pkg, act):
    sh(f"cmd role add-role-holder --user 0 android.app.role.HOME {pkg}")
    sh(f"cmd package set-home-activity {act}")
    sh("input keyevent KEYCODE_HOME")
    return current_home(sh) == act


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    flags = {a for a in sys.argv[1:] if a.startswith("--")}
    if not args:
        print(__doc__)
        sys.exit(1)
    ip = args[0]
    tv = ip if ":" in ip else ip + ":5555"
    print(adb("connect", tv)[0])
    if adb("-s", tv, "get-state")[0] != "device":
        print("TV non raggiungibile: accesa, stessa rete, debug USB attivo? (l'IP e' ancora giusto?)")
        sys.exit(1)
    sh = lambda c: adb("-s", tv, "shell", c)[0]

    if "--old" in flags:
        ok = set_home(sh, OLD, OLD_ACT)
        print("Home di prima ripristinata." if ok else "Impostata, ma controlla: premi Home sul telecomando.")
        return

    if "--tasti" in flags:
        print("Riaccendo la home di Google TV:", sh(f"pm enable --user 0 {GTV}") or "ok")
        cur = sh("settings get secure enabled_accessibility_services")
        cur = "" if cur in ("null", "") else cur
        if NEW_SVC not in cur:
            cur = (cur + ":" if cur else "") + NEW_SVC
        sh(f'settings put secure enabled_accessibility_services "{cur}"')
        sh("settings put secure accessibility_enabled 1")
        print("Attivato 'Pulsante Home'. Ora prova: tasto Home (deve aprire la nostra home), poi EPG/Impostazioni/YouTube.")
        print("Se qualcosa va storto: py home_install.py", ip, "--tasti-off")
        return

    if "--tasti-off" in flags:
        cur = sh("settings get secure enabled_accessibility_services")
        parts = [x for x in ("" if cur == "null" else cur).split(":") if x and x != NEW_SVC]
        sh('settings put secure enabled_accessibility_services "%s"' % ":".join(parts) if parts
           else "settings delete secure enabled_accessibility_services")
        print(sh(f"pm disable-user --user 0 {GTV}") or "ok")
        set_home(sh, NEW, NEW_ACT)
        print("Annullato: di nuovo solo la nostra home.")
        return

    path = args[1] if len(args) > 1 else find_download()
    if not path or not os.path.exists(path):
        print("File non trovato. Scrivi il percorso dello zip (home-apk) o dell'apk come secondo argomento.")
        sys.exit(1)
    print("File:", path)
    apk = get_apk(path)

    print("Installo (la home di prima resta dov'e')...")
    out, rc = adb("-s", tv, "install", "-r", apk)
    if "Success" not in out:
        if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in out or "signatures do not match" in out:
            adb("-s", tv, "uninstall", NEW)
            out, rc = adb("-s", tv, "install", apk)
        if "Success" not in out:
            print("Installazione non riuscita:\n" + out)
            print("Se la TV mostra un avviso Play Protect, consenti l'installazione e rilancia.")
            sys.exit(1)

    # prima apertura: ricrea la fila di app di prima
    sh(f"am start -n {NEW_ACT}")
    ok = set_home(sh, NEW, NEW_ACT)
    if ok:
        print("\nFatto: la nuova home e' attiva. Premi Home sul telecomando.")
    else:
        print("\nInstallata, ma la TV apre ancora un'altra home. Premi Home: se chiede quale usare, scegli 'Home' > Sempre.")
    print("Sfondo con le foto: Impostazioni (tile in basso) > Sfondo > Foto dal NAS.")
    print("Per tornare a quella di prima:  py home_install.py", ip, "--old")


if __name__ == "__main__":
    main()
