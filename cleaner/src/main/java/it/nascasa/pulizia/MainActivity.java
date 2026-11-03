package it.nascasa.pulizia;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Schermata unica: pochi pulsanti grandi. Tutto il lavoro e' in Cleaner. */
public class MainActivity extends Activity {

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private volatile boolean alive = true;
    private volatile boolean busy;

    private TextView stats;
    private TextView status;
    private TextView result;
    private TextView[] buttons;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = Ui.dp(this, 56);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(pad, Ui.dp(this, 40), pad, Ui.dp(this, 32));

        TextView title = Ui.text(this, "Pulizia TV", 34, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        stats = Ui.text(this, "", 20, Color.WHITE);
        stats.setPadding(0, Ui.dp(this, 10), 0, 0);
        root.addView(stats);

        status = Ui.text(this, "", 16, Ui.MUTED);
        status.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 18));
        root.addView(status);

        buttons = new TextView[]{
                Ui.button(this, "Pulizia completa  (RAM + cache)", new View.OnClickListener() {
                    @Override public void onClick(View v) { run("Pulizia completa in corso...", 3); }
                }),
                Ui.button(this, "Libera RAM  (chiude le app rimaste aperte)", new View.OnClickListener() {
                    @Override public void onClick(View v) { run("Chiudo le app aperte...", 1); }
                }),
                Ui.button(this, "Svuota cache delle app", new View.OnClickListener() {
                    @Override public void onClick(View v) { run("Svuoto la cache...", 2); }
                }),
                Ui.button(this, "Chiudi app a scelta...", new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        startActivity(new Intent(MainActivity.this, AppsActivity.class));
                    }
                }),
        };
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 620),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 12);
        for (TextView t : buttons) root.addView(t, lp);

        result = Ui.text(this, "", 18, 0xFFB8E0B8);
        result.setPadding(0, Ui.dp(this, 8), 0, 0);
        ScrollView sv = new ScrollView(this);
        sv.addView(result);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        buttons[0].requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStats();
        if (!Cleaner.get(this).isConnected()) connectInBackground();
    }

    private void refreshStats() {
        stats.setText("RAM libera: " + Cleaner.fmt(Cleaner.availRam(this)) + " su " + Cleaner.fmt(Cleaner.totalRam(this))
                + "      Spazio libero: " + Cleaner.fmt(Cleaner.freeStorage()) + " su " + Cleaner.fmt(Cleaner.totalStorage()));
    }

    private void connectInBackground() {
        status.setText("Collegamento al debug della TV...  (la prima volta accetta la richiesta che compare sullo schermo: "
                + "spunta \"Consenti sempre\" e premi OK)");
        exec.execute(new Runnable() {
            @Override public void run() {
                String msg;
                try {
                    Cleaner.get(MainActivity.this).connect();
                    msg = "Collegato.";
                } catch (IOException e) {
                    msg = "Non riesco a collegarmi: " + e.getMessage()
                            + "\nControlla che il debug USB / debug di rete sia attivo nelle Opzioni sviluppatore, poi riapri l'app.";
                }
                final String m = msg;
                runOnUiThread(new Runnable() {
                    @Override public void run() { if (alive) status.setText(m); }
                });
            }
        });
    }

    private void run(final String working, final int what) {
        if (busy) return;
        busy = true;
        result.setTextColor(Color.WHITE);
        result.setText(working);
        exec.execute(new Runnable() {
            @Override public void run() {
                String out;
                int color = 0xFFB8E0B8;
                try {
                    Cleaner c = Cleaner.get(MainActivity.this);
                    if (what == 1) out = c.freeRam();
                    else if (what == 2) out = c.clearCache();
                    else out = c.freeRam() + "\n\n" + c.clearCache();
                    out = "Fatto.\n" + out;
                } catch (IOException e) {
                    out = "Operazione non riuscita: " + e.getMessage();
                    color = 0xFFFF9C9C;
                }
                final String text = out;
                final int col = color;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        busy = false;
                        if (!alive) return;
                        result.setTextColor(col);
                        result.setText(text);
                        refreshStats();
                        status.setText(Cleaner.get(MainActivity.this).isConnected() ? "Collegato." : status.getText());
                    }
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        alive = false;
        exec.shutdown();
        super.onDestroy();
    }
}
