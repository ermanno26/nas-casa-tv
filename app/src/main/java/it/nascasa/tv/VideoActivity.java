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

import androidx.media3.common.AudioAttributes;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
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
    private String audioInfo = "";
    private String decoderInfo = "";
    private int underruns;

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
        final int mode = Prefs.audioMode(this);
        final boolean soft = mode != 1;
        DefaultRenderersFactory factory = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true)
                .setMediaCodecSelector(new MediaCodecSelector() {
                    @Override
                    public List<MediaCodecInfo> getDecoderInfos(String mimeType, boolean secure, boolean tunneling)
                            throws MediaCodecUtil.DecoderQueryException {
                        List<MediaCodecInfo> all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling);
                        if (!soft || mimeType == null || !mimeType.startsWith("audio/")) return all;
                        // audio: prima i decoder software (Google), poi gli altri come riserva
                        List<MediaCodecInfo> sorted = new ArrayList<>();
                        for (MediaCodecInfo i : all) if (i.softwareOnly) sorted.add(i);
                        for (MediaCodecInfo i : all) if (!i.softwareOnly) sorted.add(i);
                        return sorted;
                    }
                });
        player = new ExoPlayer.Builder(this, factory)
                .setSeekBackIncrementMs(10000)
                .setSeekForwardIncrementMs(30000)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(mode == 2 ? C.AUDIO_CONTENT_TYPE_MUSIC : C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(), true)
                .build();
        playerView.setPlayer(player);
        player.addAnalyticsListener(new AnalyticsListener() {
            public void onAudioDecoderInitialized(AnalyticsListener.EventTime t, String name, long a, long b) {
                decoderInfo = "decoder: " + name + " -";
                showTitle();
            }

            public void onAudioUnderrun(AnalyticsListener.EventTime t, int size, long sizeMs, long sinceMs) {
                underruns++;
            }
        });

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
            public void onTracksChanged(Tracks tracks) {
                audioInfo = "";
                for (Tracks.Group g : tracks.getGroups()) {
                    if (g.getType() != C.TRACK_TYPE_AUDIO) continue;
                    for (int t = 0; t < g.length; t++) {
                        if (!g.isTrackSelected(t)) continue;
                        Format f = g.getTrackFormat(t);
                        String mime = f.sampleMimeType == null ? "?" : f.sampleMimeType.replace("audio/", "");
                        audioInfo = "Audio: " + mime + ", " + f.sampleRate + " Hz, " + f.channelCount + " canali";
                    }
                }
                showTitle();
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

    /** Cambia tra decodifica audio software e hardware e riparte dallo stesso punto. */
    private void toggleAudioDecoder() {
        if (player == null) return;
        int i = player.getCurrentMediaItemIndex();
        long pos = player.getCurrentPosition();
        stopPlayer();
        Prefs.setAudioMode(this, (Prefs.audioMode(this) + 1) % 3);
        underruns = 0;
        if (i >= 0 && i < urls.size()) {
            startIndex = i;
            Prefs.setPosition(this, urls.get(i), pos);
        }
        startPlayer();
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
        int m = Prefs.audioMode(this);
        String mode = m == 0 ? "modo 1/3: audio software" : m == 1 ? "modo 2/3: audio hardware"
                : "modo 3/3: audio software + profilo musica";
        titleView.setText(titles.get(i) + "\n" + (audioInfo.isEmpty() ? "" : audioInfo + " - ") + mode
                + "\n" + decoderInfo + " buchi audio: " + underruns + "   (tasto Menu = cambia modo)");
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
                case KeyEvent.KEYCODE_MENU:
                case KeyEvent.KEYCODE_INFO:
                    if (down && event.getRepeatCount() == 0) toggleAudioDecoder();
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
