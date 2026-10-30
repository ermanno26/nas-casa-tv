# NAS Casa - app per Xiaomi TV (Android / Google TV)

Sfoglia le cartelle del NAS dalla TV e guarda foto e video in qualita' originale.
Niente server da installare: legge direttamente il NAS in rete locale (DLNA/UPnP, lo stesso
meccanismo del "Browser multimediale" di AirScreen).

- Cartelle in griglia con miniature, ordinamento per nome / data / ordine del NAS
- Foto a schermo intero, presentazione automatica, rotazione EXIF corretta
- Video e audio con ExoPlayer (decodifica hardware, 4K/HEVC dove la TV lo supporta), riprende da dove eri
- Nessuna pubblicita', nessun account

## 1. Cosa serve (una volta sola)

1. Un account su https://github.com (gratis).
2. Git per Windows. In PowerShell:
   `winget install --id Git.Git -e`
   Poi chiudi e riapri PowerShell.
3. Dire a Git chi sei (solo la prima volta):
   `git config --global user.name "Il Tuo Nome"`
   `git config --global user.email "tua@email.it"`

## 2. Compilare l'app (la fa GitHub, gratis)

1. Su github.com: **New repository** > nome `nas-casa-tv` > **Private** > Create repository
   (senza aggiungere README o altri file).
2. Estrai questo zip in `C:\nas-casa-tv` (deve contenere `app`, `.github`, `build.gradle`...).
3. In PowerShell:

```
cd C:\nas-casa-tv
git init
git add .
git commit -m "NAS Casa TV"
git branch -M main
git remote add origin https://github.com/TUO_UTENTE_GITHUB/nas-casa-tv.git
git push -u origin main
```

   (la prima volta si apre il browser per accedere a GitHub: accetta)
4. Sul repository apri la scheda **Actions**: parte da solo "Build APK" (5-8 minuti).
   Quando diventa verde, aprilo e in fondo, sotto **Artifacts**, scarica **nas-casa-apk**
   (e' uno zip: non serve estrarlo).

## 3. Installarla sulla TV

1. TV accesa, stessa rete, debug USB attivo (come per gli altri script).
2. Copia `nas_install.py` in `C:\platform-tools`.
3. In PowerShell:

```
cd C:\platform-tools
py nas_install.py 192.168.1.19
```

   Senza altri argomenti usa lo zip piu' recente in Downloads. Oppure indica il file:
   `py nas_install.py 192.168.1.19 "C:\Users\TUO_NOME\Downloads\nas-casa-apk.zip"`
4. Se la TV mostra un avviso di Google Play Protect, consenti l'installazione.
5. Sulla home leggera: **Aggiungi app** > **NAS Casa**.

## 4. Primo avvio e uso

- Si collega da sola al NAS (se ne trova uno solo apre subito le cartelle). Se non lo trova:
  **Inserisci IP** > `192.168.1.28`. L'app ricorda il NAS: dalla volta dopo si apre diretta.
- Nelle cartelle: frecce per muoversi, **OK** apre, **Indietro** risale.
  In alto: **Ordine**, **Aggiorna**, **Altro NAS** (si arriva con la freccia su).
- Foto: **destra/sinistra** = successiva/precedente, **OK** = avvia/ferma la presentazione,
  **su/giu** = nome del file.
- Video (barra nascosta): **sinistra** -10 s, **destra** +30 s, **OK** pausa/play,
  **su/giu** mostra la barra con la timeline.

## 5. Aggiornare l'app in futuro

Modifica i file, poi:

```
cd C:\nas-casa-tv
git add .
git commit -m "modifica"
git push
```

GitHub ricompila; riscarichi lo zip e rilanci `nas_install.py`: si installa sopra la versione
precedente (la chiave di firma e' nel progetto), senza perdere le impostazioni.

## 6. Se qualcosa non va

| Problema | Cosa fare |
|---|---|
| La build su GitHub diventa rossa | Apri l'esecuzione, clicca il passo rosso e copia le ultime righe del log |
| "Nessun NAS trovato" | Inserisci IP `192.168.1.28`. Controlla che nel NAS sia attivo il server multimediale / DLNA |
| Una foto non si apre e c'e' scritto HEIC | La TV non decodifica HEIC. Sull'iPhone: Impostazioni > Fotocamera > Formati > **Piu' compatibile** (nuove foto in JPEG). Le vecchie vanno convertite |
| Foto lente a comparire | La prima volta scarica dal NAS; poi resta in cache sulla TV |
| Un video non parte | Compare il codice dell'errore a schermo: mandamelo |
| Voglio cambiare NAS | In alto, **Altro NAS** |

## 7. Per sviluppatori

- Codice: `app/src/main/java/it/nascasa/tv` (Java, nessuna libreria oltre a Media3 per i video).
- Il client DLNA (`dlna/`) e' Java puro e si prova senza Android:
  `sh tests/run_tests.sh` (usa un finto NAS in `tests/mock_dlna.py`).
- AirPlay NON e' incluso in questa app: per ora si continua a usare AirScreen.
