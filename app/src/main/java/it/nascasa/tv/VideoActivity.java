package it.nascasa.tv;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Riproduzione video/audio con ExoPlayer (decodifica hardware: 4K, HEVC, HDR dove la TV lo supporta).
 * Senza barra visibile: Sinistra/Destra = -10 s / +30 s, OK = pausa. Su/Giu mostra la barra di controllo.
 * Quando riapri un video riprende da dove eri arrivato.
 */
public class VideoActivity extends Activity {

    static final String EXTRA_INDEX = "index";

    private final Handler handler = new Handler(Looper.getMainLooper());

    private List<String> urls;
    private List<String> titles;
    private int startIndex;

    private PlayerView playerView;
    private TextView titleView;
    private TextView errorView;
    private ExoPlayer player;

    private final Runnable hideTitle = new Runnable() {
        @Override public void run() { titleView.setVisibility(View.GONE); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        urls = Shared.mediaUrls;
        titles = Shared.mediaTitles;
        if (urls == null || urls.isEmpty()) {
            finish();
            return;
        }
        startIndex = Math.max(0, Math.min(getIntent().getIntExtra(EXTRA_INDEX, 0), urls.size() - 1));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        playerView = new PlayerView(this);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        playerView.setControllerShowTimeoutMs(4000);
        root.addView(playerView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        titleView = Ui.text(this, "", 20, Color.WHITE);
        titleView.setBackgroundColor(0xB0000000);
        titleView.setPadding(Ui.dp(this, 32), Ui.dp(this, 14), Ui.dp(this, 32), Ui.dp(this, 14));
        root.addView(titleView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        errorView = Ui.text(this, "", 22, Color.WHITE);
        errorView.setGravity(Gravity.CENTER);
        errorView.setBackgroundColor(0xCC000000);
        errorView.setPadding(Ui.dp(this, 48), Ui.dp(this, 32), Ui.dp(this, 48), Ui.dp(this, 32));
        errorView.setVisibility(View.GONE);
        root.addView(errorView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (urls == null || urls.isEmpty()) return;
        startPlayer();
    }

    @Override
    protected void onStop() {
        stopPlayer();
        super.onStop();
    }

    private void startPlayer() {
        if (player != null) return;
        player = new ExoPlayer.Builder(this)
                .setSeekBackIncrementMs(10000)
                .setSeekForwardIncrementMs(30000)
                .build();
        playerView.setPlayer(player);

        List<MediaItem> items = new ArrayList<>();
        for (int i = 0; i < urls.size(); i++) {
            items.add(new MediaItem.Builder()
                    .setUri(urls.get(i))
                    .setMediaMetadata(new MediaMetadata.Builder().setTitle(titles.get(i)).build())
                    .build());
        }
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                String detail = error.getErrorCodeName();
                Throwable cause = error.getCause();
                if (cause != null && cause.getMessage() != null) detail += "\n" + cause.getMessage();
                errorView.setText("Impossibile riprodurre questo file.\n" + detail);
                errorView.setVisibility(View.VISIBLE);
            }

            @Override
            public void onMediaItemTransition(MediaItem mediaItem, int reason) {
                errorView.setVisibility(View.GONE);
                showTitle();
            }
        });

        long resume = Prefs.position(this, urls.get(startIndex));
        player.setMediaItems(items, startIndex, resume);
        player.prepare();
        player.setPlayWhenReady(true);
        showTitle();
    }

    private void stopPlayer() {
        if (player == null) return;
        try {
            int i = player.getCurrentMediaItemIndex();
            long pos = player.getCurrentPosition();
            long dur = player.getDuration();
            if (i >= 0 && i < urls.size()) {
                boolean worthSaving = pos > 15000 && (dur <= 0 || pos < dur - 20000);
                Prefs.setPosition(this, urls.get(i), worthSaving ? pos : 0);
            }
        } catch (Exception ignored) {
            // il lettore e' gia' chiuso
        }
        playerView.setPlayer(null);
        player.release();
        player = null;
    }

    private void showTitle() {
        int i = player != null ? player.getCurrentMediaItemIndex() : startIndex;
        if (i < 0 || i >= titles.size()) return;
        titleView.setText(titles.get(i));
        titleView.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideTitle);
        handler.postDelayed(hideTitle, 4000);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (player != null && !playerView.isControllerFullyVisible()) {
            int code = event.getKeyCode();
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (down) player.seekBack();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) player.seekForward();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (down && event.getRepeatCount() == 0) {
                        if (player.isPlaying()) player.pause(); else player.play();
                        playerView.showController();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) playerView.showController();
                    return true;
                default:
                    break;
            }
        }
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
