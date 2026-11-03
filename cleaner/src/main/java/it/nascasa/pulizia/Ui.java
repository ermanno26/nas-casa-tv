package it.nascasa.pulizia;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

final class Ui {
    private Ui() {}

    static final int BG = 0xFF0F1115;
    static final int ACCENT = 0xFF3D8BFF;
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

    private static GradientDrawable rect(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r);
        return g;
    }

    static TextView button(Context c, String label, View.OnClickListener l) {
        TextView b = text(c, label, 20, Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(c, 28), dp(c, 14), dp(c, 28), dp(c, 14));
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, rect(ACCENT, dp(c, 12)));
        s.addState(new int[]{}, rect(0xFF2A303B, dp(c, 12)));
        b.setBackground(s);
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(l);
        return b;
    }
}
