package it.nascasa.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import it.nascasa.tv.dlna.ContentDirectoryClient;
import it.nascasa.tv.dlna.MediaEntry;

/** Le cartelle del NAS in griglia: OK apre una cartella / foto / video, Indietro risale. */
public class BrowseActivity extends Activity {

    static final String EXTRA_NAME = "name";
    static final String EXTRA_LOCATION = "location";
    static final String EXTRA_CONTROL = "control";
    static final String EXTRA_SERVICE = "svc";

    private static final String[] SORT_LABELS = {"Ordine: NAS", "Ordine: Nome", "Ordine: Data"};

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Map<String, List<MediaEntry>> cache = new HashMap<>();
    private final ArrayList<String> idStack = new ArrayList<>();
    private final ArrayList<String> titleStack = new ArrayList<>();
    private final ArrayList<Integer> posStack = new ArrayList<>();

    private ContentDirectoryClient client;
    private String serverName;
    private List<MediaEntry> shown = new ArrayList<>();
    private int sortMode;
    private int loadToken;
    private volatile boolean alive = true;

    private TextView pathView;
    private TextView statusView;
    private TextView btnSort;
    private GridView grid;
    private EntryAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        String control = in.getStringExtra(EXTRA_CONTROL);
        if (control == null) {
            startActivity(new Intent(this, ServerListActivity.class));
            finish();
            return;
        }
        serverName = in.getStringExtra(EXTRA_NAME);
        client = new ContentDirectoryClient(control, in.getStringExtra(EXTRA_SERVICE));
        sortMode = Prefs.sort(this);

        buildUi();
        idStack.add("0");
        titleStack.add(serverName);
        load("0", -1);
    }

    // ------------------------------------------------------------------ interfaccia

    private void buildUi() {
        int pad = Ui.dp(this, 32);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(pad, Ui.dp(this, 20), pad, 0);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        pathView = Ui.text(this, "", 22, Color.WHITE);
        pathView.setTypeface(Typeface.DEFAULT_BOLD);
        pathView.setSingleLine(true);
        pathView.setEllipsize(TextUtils.TruncateAt.START);
        header.addView(pathView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.leftMargin = Ui.dp(this, 12);

        btnSort = Ui.button(this, SORT_LABELS[sortMode], new View.OnClickListener() {
            @Override public void onClick(View v) { cycleSort(); }
        });
        TextView btnRefresh = Ui.button(this, "Aggiorna", new View.OnClickListener() {
            @Override public void onClick(View v) {
                String id = currentId();
                cache.remove(id);
                load(id, -1);
            }
        });
        TextView btnServer = Ui.button(this, "Altro NAS", new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(BrowseActivity.this, ServerListActivity.class);
                i.putExtra(ServerListActivity.EXTRA_PICK, true);
                startActivity(i);
                finish();
            }
        });
        header.addView(btnSort, bp);
        header.addView(btnRefresh, bp);
        header.addView(btnServer, bp);
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout body = new FrameLayout(this);
        adapter = new EntryAdapter();
        grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(Ui.dp(this, 220));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(Ui.dp(this, 12));
        grid.setVerticalSpacing(Ui.dp(this, 12));
        grid.setPadding(Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16));
        grid.setClipToPadding(false);
        grid.setSelector(Ui.selector(this));
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < shown.size()) open(shown.get(position));
            }
        });
        body.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        statusView = Ui.text(this, "", 22, Ui.MUTED);
        statusView.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        body.addView(statusView, sp);

        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    // ------------------------------------------------------------------ caricamento

    private String currentId() {
        return idStack.get(idStack.size() - 1);
    }

    private void updatePath() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < titleStack.size(); i++) {
            if (i > 0) sb.append("  ›  ");
            sb.append(titleStack.get(i));
        }
        pathView.setText(sb.toString());
    }

    private void load(final String id, final int restorePos) {
        updatePath();
        final int token = ++loadToken;
        List<MediaEntry> cached = cache.get(id);
        if (cached != null) {
            show(cached, restorePos);
            return;
        }
        shown = new ArrayList<>();
        adapter.notifyDataSetChanged();
        statusView.setText("Caricamento...");
        statusView.setVisibility(View.VISIBLE);
        exec.execute(new Runnable() {
            @Override public void run() {
                List<MediaEntry> result = null;
                String error = null;
                try {
                    result = client.browseAll(id);
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
                final List<MediaEntry> r = result;
                final String err = error;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!alive || token != loadToken) return;
                        if (r == null) {
                            statusView.setText("Impossibile leggere il NAS.\n" + err
                                    + "\n\nPremi \"Aggiorna\" per riprovare oppure \"Altro NAS\".");
                            statusView.setVisibility(View.VISIBLE);
                            return;
                        }
                        cache.put(id, r);
                        show(r, restorePos);
                    }
                });
            }
        });
    }

    private void show(List<MediaEntry> raw, int restorePos) {
        List<MediaEntry> list = new ArrayList<>();
        for (MediaEntry e : raw) {
            if (e.kind() != MediaEntry.Kind.OTHER) list.add(e);
        }
        Collections.sort(list, comparator(sortMode));
        shown = list;
        adapter.notifyDataSetChanged();
        if (list.isEmpty()) {
            statusView.setText("Cartella vuota");
            statusView.setVisibility(View.VISIBLE);
        } else {
            statusView.setVisibility(View.GONE);
            grid.requestFocus();
            grid.setSelection(restorePos >= 0 && restorePos < list.size() ? restorePos : 0);
        }
    }

    private void cycleSort() {
        sortMode = (sortMode + 1) % SORT_LABELS.length;
        Prefs.setSort(this, sortMode);
        btnSort.setText(SORT_LABELS[sortMode]);
        List<MediaEntry> raw = cache.get(currentId());
        if (raw != null) show(raw, 0);
    }

    // ------------------------------------------------------------------ ordinamento

    private static Comparator<MediaEntry> comparator(final int mode) {
        return new Comparator<MediaEntry>() {
            @Override public int compare(MediaEntry a, MediaEntry b) {
                boolean fa = a.kind() == MediaEntry.Kind.FOLDER, fb = b.kind() == MediaEntry.Kind.FOLDER;
                if (fa != fb) return fa ? -1 : 1;              // prima le cartelle
                if (mode == 0 && !fa) return 0;                // ordine del NAS (la sort e' stabile)
                if (mode == 2 && !fa) {                        // data: le piu' recenti prima
                    int d = b.date.compareTo(a.date);
                    if (d != 0) return d;
                }
                return natural(a.title, b.title);
            }
        };
    }

    /** Confronto "naturale": IMG_2 viene prima di IMG_10. */
    static int natural(String a, String b) {
        a = a.toLowerCase();
        b = b.toLowerCase();
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char x = a.charAt(i), y = b.charAt(j);
            if (Character.isDigit(x) && Character.isDigit(y)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", "");
                String nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                if (na.length() != nb.length()) return na.length() - nb.length();
                int c = na.compareTo(nb);
                if (c != 0) return c;
            } else {
                if (x != y) return x - y;
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }

    // ------------------------------------------------------------------ apertura

    private void open(MediaEntry e) {
        switch (e.kind()) {
            case FOLDER:
                posStack.add(grid.getSelectedItemPosition());
                idStack.add(e.id);
                titleStack.add(e.title);
                load(e.id, -1);
                break;
            case PHOTO:
                openPhoto(e);
                break;
            case VIDEO:
            case AUDIO:
                openMedia(e);
                break;
            default:
                break;
        }
    }

    private void openPhoto(MediaEntry clicked) {
        List<String> urls = new ArrayList<>(), titles = new ArrayList<>(), mimes = new ArrayList<>();
        int index = 0;
        for (MediaEntry m : shown) {
            if (m.kind() != MediaEntry.Kind.PHOTO) continue;
            MediaEntry.Res r = m.bestImage();
            if (r == null) continue;
            if (m == clicked) index = urls.size();
            urls.add(r.url);
            titles.add(m.title);
            mimes.add(r.mime);
        }
        if (urls.isEmpty()) return;
        Shared.photoUrls = urls;
        Shared.photoTitles = titles;
        Shared.photoMimes = mimes;
        Intent i = new Intent(this, PhotoActivity.class);
        i.putExtra(PhotoActivity.EXTRA_INDEX, index);
        startActivity(i);
    }

    private void openMedia(MediaEntry clicked) {
        List<String> urls = new ArrayList<>(), titles = new ArrayList<>();
        int index = 0;
        for (MediaEntry m : shown) {
            if (m.kind() != MediaEntry.Kind.VIDEO && m.kind() != MediaEntry.Kind.AUDIO) continue;
            MediaEntry.Res r = m.playable();
            if (r == null) continue;
            if (m == clicked) index = urls.size();
            urls.add(r.url);
            titles.add(m.title);
        }
        if (urls.isEmpty()) return;
        Shared.mediaUrls = urls;
        Shared.mediaTitles = titles;
        Intent i = new Intent(this, VideoActivity.class);
        i.putExtra(VideoActivity.EXTRA_INDEX, index);
        startActivity(i);
    }

    @Override
    public void onBackPressed() {
        if (idStack.size() > 1) {
            idStack.remove(idStack.size() - 1);
            titleStack.remove(titleStack.size() - 1);
            int pos = posStack.isEmpty() ? 0 : posStack.remove(posStack.size() - 1);
            load(currentId(), pos);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        alive = false;
        exec.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ griglia

    private static final class Holder {
        ImageView image;
        TextView badge;
        TextView title;
        TextView sub;
        String thumbUrl;
        ImageLoader.Handle handle;
    }

    private final class EntryAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int position) { return shown.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder h;
            View v = convertView;
            if (v == null) {
                v = createCell();
                h = (Holder) v.getTag();
            } else {
                h = (Holder) v.getTag();
            }
            MediaEntry e = shown.get(position);
            MediaEntry.Kind kind = e.kind();

            h.title.setText(e.title);
            h.sub.setText(subtitle(e));
            h.badge.setVisibility(kind == MediaEntry.Kind.VIDEO ? View.VISIBLE : View.GONE);

            if (h.handle != null) h.handle.cancel();
            h.handle = null;
            h.thumbUrl = null;
            h.image.setImageDrawable(new Ui.Placeholder(kind));

            String thumb = kind == MediaEntry.Kind.FOLDER ? null : e.thumbnailUrl();
            if (thumb != null) {
                final Holder hh = h;
                final String tu = thumb;
                h.thumbUrl = tu;
                h.handle = ImageLoader.load(BrowseActivity.this, tu, null,
                        Ui.dp(BrowseActivity.this, 260), Ui.dp(BrowseActivity.this, 146), false,
                        new ImageLoader.Callback() {
                            @Override public void onLoaded(Bitmap b) {
                                if (tu.equals(hh.thumbUrl)) hh.image.setImageBitmap(b);
                            }
                            @Override public void onError(String message) { }
                        });
            }
            return v;
        }

        private View createCell() {
            LinearLayout cell = new LinearLayout(BrowseActivity.this);
            cell.setOrientation(LinearLayout.VERTICAL);
            int p = Ui.dp(BrowseActivity.this, 6);
            cell.setPadding(p, p, p, p);

            FrameLayout box = new FrameLayout(BrowseActivity.this);
            ImageView img = new ImageView(BrowseActivity.this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            box.addView(img, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            TextView badge = Ui.text(BrowseActivity.this, "▶", 14, Color.WHITE);
            badge.setBackgroundColor(0x99000000);
            badge.setPadding(Ui.dp(BrowseActivity.this, 8), Ui.dp(BrowseActivity.this, 2),
                    Ui.dp(BrowseActivity.this, 8), Ui.dp(BrowseActivity.this, 2));
            box.addView(badge, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.START));
            cell.addView(box, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(BrowseActivity.this, 118)));

            TextView title = Ui.text(BrowseActivity.this, "", 15, Color.WHITE);
            title.setLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setPadding(0, Ui.dp(BrowseActivity.this, 6), 0, 0);
            cell.addView(title);

            TextView sub = Ui.text(BrowseActivity.this, "", 12, Ui.MUTED);
            sub.setSingleLine(true);
            cell.addView(sub);

            Holder h = new Holder();
            h.image = img;
            h.badge = badge;
            h.title = title;
            h.sub = sub;
            cell.setTag(h);
            return cell;
        }

        private String subtitle(MediaEntry e) {
            switch (e.kind()) {
                case FOLDER:
                    return e.childCount >= 0 ? e.childCount + (e.childCount == 1 ? " elemento" : " elementi") : "Cartella";
                case VIDEO:
                case AUDIO: {
                    MediaEntry.Res r = e.playable();
                    return r != null && r.duration != null ? shortDuration(r.duration) : "";
                }
                default:
                    return e.date.length() >= 10 ? e.date.substring(0, 10) : e.date;
            }
        }

        /** "0:02:00.000" -> "2:00" */
        private String shortDuration(String d) {
            int dot = d.indexOf('.');
            if (dot > 0) d = d.substring(0, dot);
            if (d.startsWith("0:")) d = d.substring(2);
            return d;
        }
    }
}
