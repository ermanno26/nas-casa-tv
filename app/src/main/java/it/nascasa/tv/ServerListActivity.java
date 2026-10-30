package it.nascasa.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import it.nascasa.tv.dlna.Ssdp;
import it.nascasa.tv.dlna.UpnpServer;

/**
 * Schermata iniziale. Se c'e' gia' un NAS salvato si collega da solo e apre direttamente le
 * cartelle; altrimenti lo cerca in rete (e se ne trova uno solo lo apre).
 */
public class ServerListActivity extends Activity {

    static final String EXTRA_PICK = "pick";

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private volatile boolean alive = true;
    private WifiManager.MulticastLock multicastLock;

    private TextView status;
    private ListView list;
    private TextView btnSearch;
    private TextView btnManual;
    private ArrayAdapter<UpnpServer> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        boolean pick = getIntent().getBooleanExtra(EXTRA_PICK, false);
        UpnpServer saved = Prefs.server(this);
        if (saved != null && !pick) {
            connectSaved(saved);
        } else {
            startSearch(pick);
        }
    }

    // ------------------------------------------------------------------ interfaccia

    private void buildUi() {
        int pad = Ui.dp(this, 48);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(pad, pad, pad, pad);

        TextView title = Ui.text(this, "NAS Casa", 34, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        status = Ui.text(this, "", 18, Ui.MUTED);
        status.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 16));
        root.addView(status);

        adapter = new ArrayAdapter<UpnpServer>(this, 0) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView t = convertView instanceof TextView ? (TextView) convertView
                        : Ui.text(getContext(), "", 20, Color.WHITE);
                UpnpServer s = getItem(position);
                t.setText(s.name + "\n" + s.host());
                t.setPadding(Ui.dp(getContext(), 20), Ui.dp(getContext(), 14), Ui.dp(getContext(), 20), Ui.dp(getContext(), 14));
                return t;
            }
        };
        list = new ListView(this);
        list.setAdapter(adapter);
        list.setSelector(new ColorDrawable(0x553D8BFF));
        list.setDivider(null);
        list.setVisibility(View.GONE);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                open(adapter.getItem(position));
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, Ui.dp(this, 24), 0, 0);
        btnSearch = Ui.button(this, "Cerca di nuovo", new View.OnClickListener() {
            @Override public void onClick(View v) { startSearch(true); }
        });
        btnManual = Ui.button(this, "Inserisci IP", new View.OnClickListener() {
            @Override public void onClick(View v) { askIp(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(this, 16);
        buttons.addView(btnSearch, lp);
        buttons.addView(btnManual, lp);
        root.addView(buttons, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    // ------------------------------------------------------------------ azioni

    private void connectSaved(final UpnpServer saved) {
        status.setText("Collegamento a " + saved.name + "...");
        exec.execute(new Runnable() {
            @Override public void run() {
                final UpnpServer s = UpnpServer.describe(saved.location, 3000);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!alive) return;
                        if (s != null) {
                            open(s);
                        } else {
                            status.setText(saved.name + " non risponde. Cerco di nuovo...");
                            startSearch(true);
                        }
                    }
                });
            }
        });
    }

    private void startSearch(final boolean showListEvenIfSingle) {
        status.setText("Ricerca del NAS in rete...");
        adapter.clear();
        list.setVisibility(View.GONE);
        final String extra = Prefs.host(this);
        exec.execute(new Runnable() {
            @Override public void run() {
                acquireMulticast();
                List<UpnpServer> found;
                try {
                    found = Ssdp.discover(extra, 4000);
                } finally {
                    releaseMulticast();
                }
                final List<UpnpServer> result = found;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!alive) return;
                        showResults(result, showListEvenIfSingle);
                    }
                });
            }
        });
    }

    private void showResults(List<UpnpServer> found, boolean showListEvenIfSingle) {
        if (found.isEmpty()) {
            status.setText("Nessun NAS trovato.\nPremi \"Inserisci IP\" e scrivi l'indirizzo del NAS (di solito "
                    + Prefs.host(this) + ").");
            btnManual.requestFocus();
            return;
        }
        if (found.size() == 1 && !showListEvenIfSingle) {
            open(found.get(0));
            return;
        }
        status.setText(found.size() == 1 ? "Trovato 1 NAS. Premi OK per aprirlo." : "Scegli il NAS:");
        adapter.addAll(found);
        list.setVisibility(View.VISIBLE);
        list.requestFocus();
        list.setSelection(0);
    }

    private void askIp() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(Prefs.host(this));
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("Indirizzo IP del NAS")
                .setView(input)
                .setPositiveButton("Collega", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        connectManual(input.getText().toString().trim());
                    }
                })
                .setNegativeButton("Annulla", null)
                .show();
    }

    private void connectManual(final String text) {
        if (text.isEmpty()) return;
        status.setText("Collegamento a " + text + "...");
        exec.execute(new Runnable() {
            @Override public void run() {
                final UpnpServer s = Ssdp.probe(text);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!alive) return;
                        if (s != null) {
                            if (!text.startsWith("http")) Prefs.setHost(ServerListActivity.this, text);
                            open(s);
                        } else {
                            status.setText("Nessun server multimediale risponde a " + text
                                    + ".\nControlla che il NAS sia acceso e che il servizio DLNA / media server sia attivo.");
                            btnManual.requestFocus();
                        }
                    }
                });
            }
        });
    }

    private void open(UpnpServer s) {
        Prefs.saveServer(this, s);
        Intent i = new Intent(this, BrowseActivity.class);
        i.putExtra(BrowseActivity.EXTRA_NAME, s.name);
        i.putExtra(BrowseActivity.EXTRA_LOCATION, s.location);
        i.putExtra(BrowseActivity.EXTRA_CONTROL, s.controlUrl);
        i.putExtra(BrowseActivity.EXTRA_SERVICE, s.serviceType);
        startActivity(i);
        finish();
    }

    // ------------------------------------------------------------------ multicast (alcuni Wi-Fi lo filtrano)

    private void acquireMulticast() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                multicastLock = wm.createMulticastLock("nascasa");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (Exception ignored) {
            // senza Wi-Fi (cavo) non serve
        }
    }

    private void releaseMulticast() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        alive = false;
        exec.shutdownNow();
        releaseMulticast();
        super.onDestroy();
    }
}
