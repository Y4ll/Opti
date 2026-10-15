package io.github.androidoptimizer;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    // Estilo brutalista: negro, azul eléctrico, esquinas cortadas, sombra dura y bordes de 3 dp
    private static final int BG = Color.parseColor("#000000");
    private static final int CARD = Color.parseColor("#05080F");
    private static final int BLUE = Color.parseColor("#1F6BFF");
    private static final int BLUE_DARK = Color.parseColor("#0B3A99");
    private static final int BLUE_LIGHT = Color.parseColor("#6FB3FF");
    private static final int TEXT = Color.parseColor("#FFFFFF");
    private static final int MUTED = Color.parseColor("#5F78A8");
    private static final int DANGER = Color.parseColor("#FF3B3B");

    private TextView log;
    private ScrollView logScroll;
    private TextView rootBadge;
    private final List<Button> buttons = new ArrayList<Button>();
    private volatile boolean busy = false;
    private boolean root = false;
    private int index = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        ScrollView page = new ScrollView(this);
        page.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(20), dp(20), dp(28));
        page.addView(col);

        col.addView(new HazardView(this), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(14)));

        TextView title = new TextView(this);
        title.setText("ANDROID\nOPTIMIZER");
        title.setTextColor(TEXT);
        title.setTextSize(40);
        title.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        title.setLetterSpacing(0.06f);
        title.setLineSpacing(0, 0.85f);
        title.setPadding(0, dp(16), 0, 0);
        col.addView(title);

        rootBadge = new TextView(this);
        rootBadge.setText("[ COMPROBANDO ROOT ]");
        rootBadge.setTextColor(MUTED);
        rootBadge.setTextSize(12);
        rootBadge.setLetterSpacing(0.2f);
        rootBadge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        rootBadge.setPadding(0, dp(10), 0, dp(14));
        col.addView(rootBadge);

        col.addView(sectionLabel("// MÓDULOS"));

        addButton(col, "OPTIMIZAR TODO", "full", true);
        addButton(col, "LIMPIAR CACHÉ Y RAM", "clean", false);
        addButton(col, "ACELERAR APPS", "speed", false);
        addButton(col, "MEMORIA Y ALMACENAMIENTO", "tune", false);
        addButton(col, "RED  TCP BBR", "net", false);
        addButton(col, "ANIMACIONES RÁPIDAS", "anim", false);
        addButton(col, "MODO JUEGO", "game", false);
        addButton(col, "RESTAURAR ORIGINAL", "reset", false);
        addButton(col, "ESTADO DEL SISTEMA", "status", false);

        SharedPreferences sp = getSharedPreferences("opt", MODE_PRIVATE);
        Switch boot = new Switch(this);
        boot.setText("REAPLICAR AL ENCENDER");
        boot.setTextColor(TEXT);
        boot.setTextSize(13);
        boot.setLetterSpacing(0.12f);
        boot.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        boot.setChecked(sp.getBoolean("boot", false));
        boot.setPadding(dp(4), dp(20), dp(4), dp(12));
        int[][] states = new int[][]{{android.R.attr.state_checked}, {}};
        boot.setThumbTintList(new ColorStateList(states, new int[]{BLUE_LIGHT, MUTED}));
        boot.setTrackTintList(new ColorStateList(states, new int[]{BLUE, Color.parseColor("#1A2233")}));
        boot.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                getSharedPreferences("opt", MODE_PRIVATE).edit().putBoolean("boot", on).apply();
            }
        });
        col.addView(boot);

        Button storage = new Button(this);
        storage.setText("//  AJUSTES DE ALMACENAMIENTO (SIN ROOT)");
        styleButton(storage, false);
        storage.setTextSize(12);
        storage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS));
            }
        });
        col.addView(storage, buttonParams());

        TextView termLabel = sectionLabel("// TERMINAL");
        termLabel.setPadding(0, dp(22), 0, dp(6));
        col.addView(termLabel);

        logScroll = new ScrollView(this);
        log = new TextView(this);
        log.setTextColor(BLUE_LIGHT);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextSize(12);
        log.setPadding(dp(14), dp(14), dp(14), dp(14));
        log.setText("> LISTO\n");
        logScroll.addView(log);
        logScroll.setBackground(new ChamferDrawable(Color.BLACK, BLUE, BLUE_DARK, dp(16), dp(3), dp(4)));
        logScroll.setPadding(0, 0, dp(4), dp(4));
        col.addView(logScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(270)));

        col.addView(new HazardView(this), blockParams(dp(14), dp(22)));

        setContentView(page);

        setButtonsEnabled(false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean r = RootShell.hasRoot();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        root = r;
                        rootBadge.setText(r ? "[ ROOT ACTIVO ]" : "[ SIN ROOT: LA APP LO NECESITA ]");
                        rootBadge.setTextColor(r ? BLUE_LIGHT : DANGER);
                        setButtonsEnabled(r);
                        if (r) runMode("status");
                    }
                });
            }
        }).start();
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(MUTED);
        t.setTextSize(12);
        t.setLetterSpacing(0.25f);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setPadding(0, dp(4), 0, dp(6));
        return t;
    }

    private void addButton(LinearLayout col, String label, final String mode, boolean primary) {
        index++;
        Button b = new Button(this);
        b.setText((index < 10 ? "0" : "") + index + "  //  " + label);
        styleButton(b, primary);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runMode(mode);
            }
        });
        buttons.add(b);
        col.addView(b, buttonParams());
    }

    private void styleButton(Button b, boolean primary) {
        int cut = dp(14), sw = dp(3), off = dp(4);
        StateListDrawable bg = new StateListDrawable();
        if (primary) {
            bg.addState(new int[]{android.R.attr.state_pressed},
                    new ChamferDrawable(TEXT, TEXT, BLUE_DARK, cut, sw, off));
            bg.addState(new int[]{},
                    new ChamferDrawable(BLUE, BLUE, BLUE_DARK, cut, sw, off));
            b.setTextColor(Color.BLACK);
        } else {
            bg.addState(new int[]{android.R.attr.state_pressed},
                    new ChamferDrawable(BLUE, BLUE_LIGHT, BLUE_DARK, cut, sw, off));
            bg.addState(new int[]{},
                    new ChamferDrawable(CARD, BLUE, BLUE_DARK, cut, sw, off));
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
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
        lp.topMargin = dp(8);
        return lp;
    }

    private LinearLayout.LayoutParams blockParams(int h, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, h);
        lp.topMargin = top;
        return lp;
    }

    private void setButtonsEnabled(boolean on) {
        for (Button b : buttons) {
            b.setEnabled(on);
            b.setAlpha(on ? 1f : 0.35f);
        }
    }

    private void append(final String line) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                log.append(line + "\n");
                logScroll.post(new Runnable() {
                    @Override
                    public void run() { logScroll.fullScroll(View.FOCUS_DOWN); }
                });
            }
        });
    }

    private void runMode(final String mode) {
        if (busy || !root) return;
        busy = true;
        setButtonsEnabled(false);
        append("\n> " + mode.toUpperCase());
        new Thread(new Runnable() {
            @Override
            public void run() {
                int code = RootShell.runMode(MainActivity.this, mode, new RootShell.LineCallback() {
                    @Override
                    public void onLine(String line) { append(line); }
                });
                append(code == 0 ? "> TERMINADO" : "> ERROR (código " + code + ")");
                busy = false;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() { setButtonsEnabled(true); }
                });
            }
        }).start();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
