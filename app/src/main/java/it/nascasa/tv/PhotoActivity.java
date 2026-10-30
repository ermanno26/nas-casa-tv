package it.nascasa.tv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;

/**
 * Foto a schermo intero. Destra/Sinistra = foto successiva/precedente, OK = avvia/ferma la
 * presentazione automatica, Su/Giu = mostra il nome del file, Indietro = torna alle cartelle.
 */
public class PhotoActivity extends Activity {

    static final String EXTRA_INDEX = "index";
    private static final long SLIDE_MS = 6000;
    private static final long INFO_MS = 3000;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private List<String> urls;
    private List<String> titles;
    private List<String> mimes;
    private int index;
    private boolean slideshow;
    private int targetW;
    private int targetH;
    private ImageLoader.Handle current;

    private ImageView image;
    private TextView info;
    private TextView message;

    private final Runnable hideInfo = new Runnable() {
        @Override public void run() { info.setVisibility(View.GONE); }
    };
    private final Runnable nextSlide = new Runnable() {
        @Override public void run() { if (slideshow) step(1); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        urls = Shared.photoUrls;
        titles = Shared.photoTitles;
        mimes = Shared.photoMimes;
        if (urls == null || urls.isEmpty()) {
            finish();
            return;
        }
        index = Math.max(0, Math.min(getIntent().getIntExtra(EXTRA_INDEX, 0), urls.size() - 1));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        targetW = Math.round(dm.widthPixels * 1.5f);
        targetH = Math.round(dm.heightPixels * 1.5f);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        message = Ui.text(this, "", 22, Color.WHITE);
        message.setGravity(Gravity.CENTER);
        message.setPadding(Ui.dp(this, 64), 0, Ui.dp(this, 64), 0);
        root.addView(message, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        info = Ui.text(this, "", 18, Color.WHITE);
        info.setBackgroundColor(0xB0000000);
        info.setPadding(Ui.dp(this, 32), Ui.dp(this, 14), Ui.dp(this, 32), Ui.dp(this, 14));
        root.addView(info, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        setContentView(root);
        show(index);
    }

    private void show(int i) {
        index = i;
        if (current != null) current.cancel();
        handler.removeCallbacks(nextSlide);

        message.setText("Caricamento...");
        message.setVisibility(View.VISIBLE);
        showInfo();

        current = ImageLoader.load(this, urls.get(i), mimes.get(i), targetW, targetH, true,
                new ImageLoader.Callback() {
                    @Override public void onLoaded(Bitmap bitmap) {
                        image.setImageBitmap(bitmap);
                        message.setVisibility(View.GONE);
                        preloadNext();
                        scheduleSlide(SLIDE_MS);
                    }

                    @Override public void onError(String msg) {
                        image.setImageDrawable(null);
                        message.setText(msg);
                        message.setVisibility(View.VISIBLE);
                        scheduleSlide(3000);
                    }
                });
    }

    private void scheduleSlide(long ms) {
        handler.removeCallbacks(nextSlide);
        if (slideshow) handler.postDelayed(nextSlide, ms);
    }

    private void preloadNext() {
        if (urls.size() < 2) return;
        int n = (index + 1) % urls.size();
        ImageLoader.load(this, urls.get(n), mimes.get(n), targetW, targetH, true, new ImageLoader.Callback() {
            @Override public void onLoaded(Bitmap bitmap) { }
            @Override public void onError(String message) { }
        });
    }

    private void step(int delta) {
        int n = urls.size();
        show(((index + delta) % n + n) % n);
    }

    private void showInfo() {
        String t = titles.get(index);
        info.setText((index + 1) + " / " + urls.size() + "    " + t + (slideshow ? "    ▶ Presentazione" : ""));
        info.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideInfo);
        handler.postDelayed(hideInfo, INFO_MS);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                step(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                step(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (event.getRepeatCount() == 0) {
                    slideshow = !slideshow;
                    showInfo();
                    scheduleSlide(SLIDE_MS);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_INFO:
            case KeyEvent.KEYCODE_MENU:
                showInfo();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (current != null) current.cancel();
        super.onDestroy();
    }
}
