package it.nascasa.pulizia;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Elenco delle app aperte (dalla piu' pesante): OK seleziona/deseleziona, poi "Chiudi selezionate". */
public class AppsActivity extends Activity {

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private volatile boolean alive = true;

    private final Set<String> selected = new HashSet<>();
    private ArrayAdapter<Cleaner.RunningApp> adapter;
    private TextView status;
    private TextView btnClose;
    private ListView list;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = Ui.dp(this, 56);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(pad, Ui.dp(this, 36), pad, Ui.dp(this, 24));

        TextView title = Ui.text(this, "Chiudi app a scelta", 30, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        status = Ui.text(this, "Cerco le app aperte...", 17, Ui.MUTED);
        status.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 12));
        root.addView(status);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        btnClose = Ui.button(this, "Chiudi selezionate", new View.OnClickListener() {
            @Override public void onClick(View v) { closeSelected(); }
        });
        TextView btnReload = Ui.button(this, "Aggiorna", new View.OnClickListener() {
            @Override public void onClick(View v) { load(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(this, 14);
        row.addView(btnClose, lp);
        row.addView(btnReload, lp);
        root.addView(row);

        adapter = new ArrayAdapter<Cleaner.RunningApp>(this, 0) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView t = convertView instanceof TextView ? (TextView) convertView
                        : Ui.text(getContext(), "", 20, Color.WHITE);
                Cleaner.RunningApp a = getItem(position);
                boolean on = selected.contains(a.pkg);
                t.setText((on ? "[x]  " : "[  ]  ") + a.label + "   -   " + (a.rssKb / 1024) + " MB"
                        + (a.systemOnly ? "   (di sistema)" : ""));
                t.setTextColor(on ? 0xFF8DBBFF : Color.WHITE);
                t.setPadding(Ui.dp(getContext(), 18), Ui.dp(getContext(), 12), Ui.dp(getContext(), 18), Ui.dp(getContext(), 12));
                return t;
            }
        };
        list = new ListView(this);
        list.setAdapter(adapter);
        list.setSelector(new ColorDrawable(0x553D8BFF));
        list.setDivider(null);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                String pkg = adapter.getItem(pos).pkg;
                if (!selected.remove(pkg)) selected.add(pkg);
                adapter.notifyDataSetChanged();
                updateButton();
            }
        });
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        ll.topMargin = Ui.dp(this, 12);
        root.addView(list, ll);

        setContentView(root);
        updateButton();
        load();
    }

    private void updateButton() {
        btnClose.setText("Chiudi selezionate (" + selected.size() + ")");
    }

    private void load() {
        status.setText("Cerco le app aperte...");
        exec.execute(new Runnable() {
            @Override public void run() {
                List<Cleaner.RunningApp> apps = null;
                String err = null;
                try {
                    Cleaner c = Cleaner.get(AppsActivity.this);
                    c.connect();
                    apps = c.runningApps();
                } catch (IOException e) {
                    err = "Non riesco a leggere le app: " + e.getMessage();
                }
                final List<Cleaner.RunningApp> result = apps;
                final String error = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!alive) return;
                        adapter.clear();
                        if (result == null) {
                            status.setText(error);
                            return;
                        }
                        adapter.addAll(result);
                        status.setText(result.isEmpty() ? "Nessuna app aperta."
                                : result.size() + " app aperte. OK per selezionare, poi \"Chiudi selezionate\" (in alto).");
                        updateButton();
                        if (!result.isEmpty()) list.requestFocus();
                    }
                });
            }
        });
    }

    private void closeSelected() {
        if (selected.isEmpty()) {
            status.setText("Seleziona almeno una app (OK sull'elenco).");
            return;
        }
        final Set<String> todo = new HashSet<>(selected);
        status.setText("Chiudo " + todo.size() + " app...");
        exec.execute(new Runnable() {
            @Override public void run() {
                String msg;
                try {
                    Cleaner c = Cleaner.get(AppsActivity.this);
                    for (String p : todo) c.forceStop(p);
                    msg = "Chiuse " + todo.size() + " app.";
                } catch (IOException e) {
                    msg = "Operazione non riuscita: " + e.getMessage();
                }
                final String m = msg;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!alive) return;
                        selected.clear();
                        load();
                        status.setText(m);
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
