package it.nascasa.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import it.nascasa.tv.dlna.MediaEntry;

/** Piccoli attrezzi per costruire le schermate senza file di layout. */
final class Ui {
    private Ui() {}

    static final int BG = 0xFF0F1115;
    static final int CARD = 0xFF1B2029;
    static final int ACCENT = 0xFF3D8BFF;
    static final int ACCENT_LIGHT = 0xFF8DBBFF;
    static final int MUTED = 0xFF9AA4B2;

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context c, String t, float sp, int color) {
        TextView v = new TextView(c);
        v.setText(t);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        return v;
    }

    private static GradientDrawable rect(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    /** Pulsante comandabile con il telecomando: si illumina quando e' selezionato. */
    static TextView button(Context c, String label, View.OnClickListener l) {
        TextView b = text(c, label, 18, Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 22), dp(c, 12), dp(c, 22), dp(c, 12));
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, rect(ACCENT, dp(c, 10)));
        s.addState(new int[]{}, rect(0xFF2A303B, dp(c, 10)));
        b.setBackground(s);
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(l);
        return b;
    }

    /** Cornice che evidenzia l'elemento selezionato nelle liste e nella griglia. */
    static Drawable selector(Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x223D8BFF);
        g.setStroke(dp(c, 3), ACCENT_LIGHT);
        g.setCornerRadius(dp(c, 10));
        return g;
    }

    /** Icona disegnata a mano mostrata finche' la miniatura non arriva (o se non esiste). */
    static final class Placeholder extends Drawable {
        private final MediaEntry.Kind kind;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Placeholder(MediaEntry.Kind kind) {
            this.kind = kind;
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            p.setStyle(Paint.Style.FILL);
            p.setColor(CARD);
            c.drawRect(b, p);
            float cx = b.exactCenterX(), cy = b.exactCenterY();
            float s = Math.min(b.width(), b.height()) * 0.30f;
            switch (kind) {
                case FOLDER: {
                    p.setColor(0xFF4C8DF6);
                    c.drawRoundRect(new RectF(cx - s * 1.1f, cy - s * 0.95f, cx - s * 0.1f, cy - s * 0.4f), s * 0.15f, s * 0.15f, p);
                    c.drawRoundRect(new RectF(cx - s * 1.1f, cy - s * 0.6f, cx + s * 1.1f, cy + s * 0.8f), s * 0.18f, s * 0.18f, p);
                    break;
                }
                case VIDEO: {
                    p.setColor(0xFF6AA6FF);
                    Path t = new Path();
                    t.moveTo(cx - s * 0.5f, cy - s * 0.8f);
                    t.lineTo(cx - s * 0.5f, cy + s * 0.8f);
                    t.lineTo(cx + s * 0.8f, cy);
                    t.close();
                    c.drawPath(t, p);
                    break;
                }
                case AUDIO: {
                    p.setColor(0xFF6AA6FF);
                    c.drawCircle(cx - s * 0.35f, cy + s * 0.55f, s * 0.38f, p);
                    c.drawRect(cx - s * 0.02f, cy - s * 0.85f, cx + s * 0.14f, cy + s * 0.55f, p);
                    c.drawRect(cx - s * 0.02f, cy - s * 0.85f, cx + s * 0.75f, cy - s * 0.55f, p);
                    break;
                }
                case PHOTO: {
                    p.setColor(0xFF3B4658);
                    Path m = new Path();
                    m.moveTo(cx - s, cy + s * 0.7f);
                    m.lineTo(cx - s * 0.3f, cy - s * 0.3f);
                    m.lineTo(cx + s * 0.1f, cy + s * 0.2f);
                    m.lineTo(cx + s * 0.5f, cy - s * 0.1f);
                    m.lineTo(cx + s, cy + s * 0.7f);
                    m.close();
                    c.drawPath(m, p);
                    c.drawCircle(cx + s * 0.55f, cy - s * 0.6f, s * 0.2f, p);
                    break;
                }
                default: {
                    p.setColor(0xFF3B4658);
                    c.drawRoundRect(new RectF(cx - s * 0.6f, cy - s * 0.8f, cx + s * 0.6f, cy + s * 0.8f), s * 0.1f, s * 0.1f, p);
                }
            }
        }

        @Override public void setAlpha(int alpha) { p.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }
}
