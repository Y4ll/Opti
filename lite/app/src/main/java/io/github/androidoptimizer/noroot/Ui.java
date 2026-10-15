package io.github.androidoptimizer.noroot;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Piezas de interfaz del estilo azul y negro: esquinas cortadas, sombra dura, bordes gruesos. */
final class Ui {

    static final int BG = 0xFF000000;
    static final int CARD = 0xFF05080F;
    static final int BLUE = 0xFF1F6BFF;
    static final int BLUE_DARK = 0xFF0B3A99;
    static final int BLUE_LIGHT = 0xFF6FB3FF;
    static final int TEXT = 0xFFFFFFFF;
    static final int MUTED = 0xFF8196BD;
    static final int DANGER = 0xFFFF4D4D;
    static final int WARN = 0xFFFFC24D;
    static final int TRACK = 0xFF0F1A33;

    static final int MATCH = LinearLayout.LayoutParams.MATCH_PARENT;
    static final int WRAP = LinearLayout.LayoutParams.WRAP_CONTENT;

    private final Context ctx;
    private final float density;

    Ui(Context ctx) {
        this.ctx = ctx;
        this.density = ctx.getResources().getDisplayMetrics().density;
    }

    int dp(int v) {
        return Math.round(v * density);
    }

    // ---------- Texto ----------

    TextView text(String s, int sp, int color, Typeface tf, float spacing) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setTypeface(tf);
        t.setLetterSpacing(spacing);
        return t;
    }

    TextView display(String s, int sp) {
        return text(s, sp, TEXT, Typeface.create("sans-serif-black", Typeface.NORMAL), 0.04f);
    }

    TextView body(String s, int sp, int color) {
        return text(s, sp, color, Typeface.DEFAULT, 0f);
    }

    TextView mono(String s, int sp, int color) {
        return text(s, sp, color, Typeface.MONOSPACE, 0f);
    }

    TextView label(String s) {
        TextView t = text(s, 12, MUTED, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD), 0.25f);
        t.setPadding(0, dp(4), 0, dp(6));
        return t;
    }

    // ---------- Contenedores ----------

    ChamferDrawable box(int fill, int stroke) {
        return new ChamferDrawable(fill, stroke, BLUE_DARK, dp(14), dp(3), dp(4));
    }

    /** Tarjeta vertical con esquinas cortadas. El relleno derecho e inferior deja sitio a la sombra. */
    LinearLayout card() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(box(CARD, BLUE));
        l.setPadding(dp(14), dp(12), dp(18), dp(16));
        return l;
    }

    /** Tarjeta que se puede tocar: al presionar se rellena de azul. */
    LinearLayout tapCard(String title, String sub, final Runnable onTap) {
        LinearLayout l = card();
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed}, box(BLUE, BLUE_LIGHT));
        bg.addState(new int[]{}, box(CARD, BLUE));
        l.setBackground(bg);
        l.setClickable(true);
        l.setFocusable(true);
        l.setMinimumHeight(dp(96));
        l.addView(display(title, 15));
        TextView s = body(sub, 12, MUTED);
        s.setPadding(0, dp(4), 0, 0);
        l.addView(s);
        l.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onTap.run();
            }
        });
        return l;
    }

    Button button(String label, boolean primary, int heightDp) {
        Button b = new Button(ctx);
        b.setText(label);
        StateListDrawable bg = new StateListDrawable();
        if (primary) {
            bg.addState(new int[]{android.R.attr.state_pressed}, new ChamferDrawable(TEXT, TEXT, BLUE_DARK, dp(14), dp(3), dp(4)));
            bg.addState(new int[]{}, new ChamferDrawable(BLUE, BLUE, BLUE_DARK, dp(14), dp(3), dp(4)));
            b.setTextColor(Color.BLACK);
        } else {
            bg.addState(new int[]{android.R.attr.state_pressed}, new ChamferDrawable(BLUE, BLUE_LIGHT, BLUE_DARK, dp(14), dp(3), dp(4)));
            bg.addState(new int[]{}, new ChamferDrawable(CARD, BLUE, BLUE_DARK, dp(14), dp(3), dp(4)));
            int[][] st = new int[][]{{android.R.attr.state_pressed}, {}};
            b.setTextColor(new ColorStateList(st, new int[]{Color.BLACK, TEXT}));
        }
        b.setBackground(bg);
        b.setStateListAnimator(null);
        b.setAllCaps(true);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(dp(16), 0, dp(20), dp(4));
        b.setTextSize(14);
        b.setLetterSpacing(0.1f);
        b.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        b.setMinHeight(dp(heightDp));
        b.setMinimumHeight(dp(heightDp));
        return b;
    }

    // ---------- Medidas ----------

    LinearLayout.LayoutParams lp(int w, int h, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(topDp);
        return p;
    }

    /** Barra de progreso plana: la parte llena ocupa `frac` del ancho. */
    View bar(float frac, int color) {
        float f = Math.max(0.02f, Math.min(1f, frac));
        LinearLayout b = new LinearLayout(ctx);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setBackgroundColor(TRACK);
        View fill = new View(ctx);
        fill.setBackgroundColor(color);
        b.addView(fill, new LinearLayout.LayoutParams(0, MATCH, f));
        b.addView(new View(ctx), new LinearLayout.LayoutParams(0, MATCH, 1f - f));
        return b;
    }

    View hazard() {
        return new HazardView(ctx);
    }
}
