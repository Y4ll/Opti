package io.github.androidoptimizer.noroot;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    private static final String ADB_CMD =
            "adb shell pm grant io.github.androidoptimizer.noroot android.permission.WRITE_SECURE_SETTINGS";
    private static final int REQ_UNINSTALL = 7;
    private static final int M_JUNK = 0, M_LARGE = 1, M_DUP = 2, M_APPS = 3;
    private static final int MAX_ROWS = 300;

    private interface Work {
        List<Booster.Item> run();
    }

    private Ui ui;
    private SharedPreferences prefs;
    private String screen = "home";
    private volatile boolean busy = false;

    // Resultado de la última optimización, para que sobreviva al redibujar la pantalla de inicio
    private String lastResult = null;
    private List<Booster.Item> lastJunk = null;
    private TextView progressText;
    private Button optBtn;

    private final List<String> uninstallQueue = new ArrayList<String>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = new Ui(this);
        prefs = getSharedPreferences("opt", MODE_PRIVATE);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        showHome();
    }

    @Override
    protected void onResume() {
        super.onResume();
        restoreDnd();
        if (screen.equals("home") && !busy && ui != null) showHome();
        if (screen.equals("pro") && !busy && ui != null) openPro();
    }

    @Override
    public void onBackPressed() {
        if (!screen.equals("home")) showHome();
        else super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_UNINSTALL) uninstallNext();
    }

    // =====================================================================
    // Inicio
    // =====================================================================

    private void showHome() {
        screen = "home";
        Pro.unlisten();
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(ui.dp(16), ui.dp(20), ui.dp(20), ui.dp(28));
        sv.addView(col);

        col.addView(ui.hazard(), ui.lp(Ui.MATCH, ui.dp(14), 0));

        TextView title = ui.display("ANDROID\nOPTIMIZER", 34);
        title.setLineSpacing(0, 0.88f);
        title.setPadding(0, ui.dp(14), 0, 0);
        col.addView(title);

        TextView badge = ui.mono("[ SIN ROOT ]", 12, Ui.BLUE_LIGHT);
        badge.setLetterSpacing(0.2f);
        badge.setPadding(0, ui.dp(8), 0, ui.dp(14));
        col.addView(badge);

        // ---- Puntuación ----
        int score = Booster.score(this);
        String state = score >= 75 ? "BUENO" : (score >= 50 ? "REGULAR" : "CRÍTICO");
        int stateColor = score >= 75 ? Ui.BLUE_LIGHT : (score >= 50 ? Ui.WARN : Ui.DANGER);
        LinearLayout sc = ui.card();
        ScoreRingView ring = new ScoreRingView(this);
        ring.set(score, state, stateColor);
        sc.addView(ring, new LinearLayout.LayoutParams(Ui.MATCH, ui.dp(210)));
        TextView last = ui.mono(lastOptimizationText(), 11, Ui.MUTED);
        last.setGravity(Gravity.CENTER_HORIZONTAL);
        last.setPadding(0, ui.dp(6), 0, 0);
        sc.addView(last, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        TextView note = ui.body("Puntuación orientativa: RAM libre, espacio libre y temperatura.", 11, Ui.MUTED);
        note.setGravity(Gravity.CENTER_HORIZONTAL);
        note.setPadding(0, ui.dp(2), 0, 0);
        sc.addView(note, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        col.addView(sc, ui.lp(Ui.MATCH, Ui.WRAP, 0));

        // ---- Estado del sistema ----
        col.addView(ui.label("// SISTEMA"), ui.lp(Ui.MATCH, Ui.WRAP, 18));
        LinearLayout sys = ui.card();
        long ramTotal = Booster.totalRam(this);
        long ramFree = Booster.availRam(this);
        long stTotal = Booster.totalStorage();
        long stFree = Booster.freeStorage();
        int[] bat = Booster.battery(this);
        addStat(sys, "RAM", fmt(ramFree) + " libres de " + fmt(ramTotal),
                ramTotal > 0 ? (ramTotal - ramFree) / (float) ramTotal : 0f, false);
        addStat(sys, "ALMACENAMIENTO", fmt(stFree) + " libres de " + fmt(stTotal),
                stTotal > 0 ? (stTotal - stFree) / (float) stTotal : 0f, true);
        addStat(sys, "BATERÍA", bat[0] >= 0 ? bat[0] + "%  ·  " + (bat[1] / 10f) + " °C" : "n/d",
                bat[0] >= 0 ? bat[0] / 100f : 0f, false);
        col.addView(sys, ui.lp(Ui.MATCH, Ui.WRAP, 0));

        // ---- Optimizar ----
        optBtn = ui.button("OPTIMIZAR AHORA", true, 64);
        optBtn.setTextSize(18);
        optBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                runOptimize();
            }
        });
        col.addView(optBtn, ui.lp(Ui.MATCH, Ui.WRAP, 18));

        LinearLayout pc = ui.card();
        progressText = ui.mono(lastResult == null ? "" : lastResult, 12, Ui.BLUE_LIGHT);
        progressText.setLineSpacing(0, 1.2f);
        pc.addView(progressText);
        if (lastResult != null) addResultActions(pc);
        pc.setVisibility(lastResult == null ? View.GONE : View.VISIBLE);
        col.addView(pc, ui.lp(Ui.MATCH, Ui.WRAP, 14));

        // ---- Herramientas ----
        col.addView(ui.label("// HERRAMIENTAS"), ui.lp(Ui.MATCH, Ui.WRAP, 20));
        col.addView(toolRow(
                ui.tapCard("ARCHIVOS BASURA", "Temporales, logs y miniaturas", new Runnable() {
                    public void run() { openJunk(); }
                }),
                ui.tapCard("ARCHIVOS GRANDES", "Lo que más espacio ocupa", new Runnable() {
                    public void run() { openLarge(); }
                })), ui.lp(Ui.MATCH, Ui.WRAP, 0));
        col.addView(toolRow(
                ui.tapCard("DUPLICADOS", "Mismos archivos repetidos", new Runnable() {
                    public void run() { openDuplicates(); }
                }),
                ui.tapCard("APPS SIN USAR", "Desinstala lo que no usas", new Runnable() {
                    public void run() { openApps(); }
                })), ui.lp(Ui.MATCH, Ui.WRAP, 10));
        col.addView(toolRow(
                ui.tapCard("MODO JUEGO", "No molestar y RAM libre", new Runnable() {
                    public void run() { openGames(); }
                }),
                ui.tapCard("AJUSTES RÁPIDOS", "Animaciones, batería, caché", new Runnable() {
                    public void run() { openQuick(); }
                })), ui.lp(Ui.MATCH, Ui.WRAP, 10));

        col.addView(ui.tapCard("MODO PRO  ·  SHIZUKU", Pro.ready()
                        ? "Conectado: caché real, cerrar apps, restricciones, rendimiento de juegos"
                        : "Sin PC ni root: caché real, force-stop y más. Toca para activarlo",
                new Runnable() {
                    public void run() { openPro(); }
                }), ui.lp(Ui.MATCH, Ui.WRAP, 10));

        col.addView(ui.hazard(), ui.lp(Ui.MATCH, ui.dp(14), 26));
        setContentView(sv);
    }

    private void addStat(LinearLayout parent, String name, String value, float frac, boolean warnWhenHigh) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView n = ui.mono(name, 12, Ui.MUTED);
        row.addView(n, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        TextView v = ui.mono(value, 12, Ui.TEXT);
        v.setGravity(Gravity.END);
        row.addView(v, new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP));
        parent.addView(row, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        int color = Ui.BLUE_LIGHT;
        if (warnWhenHigh && frac > 0.9f) color = Ui.DANGER;
        else if (warnWhenHigh && frac > 0.8f) color = Ui.WARN;
        parent.addView(ui.bar(frac, color), ui.lp(Ui.MATCH, ui.dp(8), 4));
        View gap = new View(this);
        parent.addView(gap, new LinearLayout.LayoutParams(Ui.MATCH, ui.dp(10)));
    }

    private LinearLayout toolRow(View a, View b) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams pa = new LinearLayout.LayoutParams(0, Ui.WRAP, 1f);
        pa.rightMargin = ui.dp(8);
        LinearLayout.LayoutParams pb = new LinearLayout.LayoutParams(0, Ui.WRAP, 1f);
        pb.leftMargin = ui.dp(2);
        r.addView(a, pa);
        r.addView(b, pb);
        return r;
    }

    private String lastOptimizationText() {
        long t = prefs.getLong("last_time", 0);
        if (t <= 0) return "ÚLTIMA OPTIMIZACIÓN: NUNCA";
        long ram = prefs.getLong("last_ram", 0);
        return "ÚLTIMA OPTIMIZACIÓN: " + ago(t).toUpperCase() + (ram > 0 ? "  ·  RAM +" + fmt(ram) : "");
    }

    // ---- Optimizar ahora: tres pasos visibles ----

    private void setProgress(final String text) {
        runOnUiThread(new Runnable() {
            public void run() {
                if (progressText != null) {
                    progressText.setText(text);
                    View p = (View) progressText.getParent();
                    if (p != null) p.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    private void runOptimize() {
        if (busy) return;
        busy = true;
        optBtn.setEnabled(false);
        optBtn.setAlpha(0.4f);
        lastResult = null;
        lastJunk = null;
        setProgress("▶ [1/3] Cerrando apps en segundo plano…");
        new Thread(new Runnable() {
            public void run() {
                StringBuilder log = new StringBuilder();
                long before = Booster.availRam(MainActivity.this);
                int n = Booster.killBackground(MainActivity.this);
                sleep(1500);
                long delta = Booster.availRam(MainActivity.this) - before;
                log.append("✔ [1/3] ").append(n).append(" apps cerradas  ·  ")
                        .append(delta > 0 ? "RAM +" + fmt(delta) : "sin cambio en RAM").append('\n');
                setProgress(log + "▶ [2/3] Limpiando caché de esta app…");

                Booster.cleanOwnCache(MainActivity.this);
                if (Pro.ready()) {
                    long free0 = Booster.freeStorage();
                    Pro.sh("pm trim-caches 999G");
                    sleep(800);
                    long gained = Booster.freeStorage() - free0;
                    log.append("✔ [2/3] Caché de TODAS las apps limpia (Pro)  ·  ")
                            .append(gained > 0 ? "+" + fmt(gained) : "ya estaba limpia").append('\n');
                } else {
                    log.append("✔ [2/3] Caché de esta app limpia  (activa Modo Pro para limpiar todas)\n");
                }

                List<Booster.Item> junk = null;
                if (hasStorageAccess()) {
                    setProgress(log + "▶ [3/3] Buscando basura…");
                    Booster.Junk j = Booster.scanJunk();
                    junk = Booster.junkItems(j);
                    long total = j.tempSize + j.thumbsSize + j.apkSize;
                    log.append(junk.isEmpty() ? "✔ [3/3] No hay basura\n"
                            : "✔ [3/3] Basura: " + junk.size() + " archivos  ·  " + fmt(total) + "\n");
                } else {
                    log.append("— [3/3] Sin permiso de archivos: no se buscó basura\n");
                }
                log.append("\nAndroid ya gestiona la RAM solo: el cambio real suele ser pequeño.");

                prefs.edit().putLong("last_time", System.currentTimeMillis())
                        .putLong("last_ram", Math.max(0, delta)).apply();
                final String result = log.toString();
                final List<Booster.Item> junkFinal = junk;
                runOnUiThread(new Runnable() {
                    public void run() {
                        busy = false;
                        lastResult = result;
                        lastJunk = junkFinal;
                        showHome();
                    }
                });
            }
        }).start();
    }

    private void addResultActions(LinearLayout card) {
        if (!hasStorageAccess()) {
            Button b = ui.button("DAR ACCESO A ARCHIVOS", false, 48);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    requestStorageAccess();
                }
            });
            card.addView(b, ui.lp(Ui.MATCH, Ui.WRAP, 10));
        } else if (lastJunk != null && !lastJunk.isEmpty()) {
            Button b = ui.button("REVISAR BASURA", false, 48);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    showReview(M_JUNK, "BASURA", "Marca lo que quieres borrar. Los APK vienen sin marcar.",
                            "BORRAR", lastJunk, null);
                }
            });
            card.addView(b, ui.lp(Ui.MATCH, Ui.WRAP, 10));
        }
    }

    // =====================================================================
    // Estructura de pantallas secundarias
    // =====================================================================

    private LinearLayout shell(String title) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(ui.dp(16), ui.dp(14), ui.dp(20), ui.dp(12));
        root.addView(ui.hazard(), new LinearLayout.LayoutParams(Ui.MATCH, ui.dp(14)));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        Button back = ui.button("‹ ATRÁS", false, 44);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showHome();
            }
        });
        bar.addView(back, new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP));
        TextView t = ui.display(title, 20);
        t.setPadding(ui.dp(16), 0, 0, 0);
        bar.addView(t, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        root.addView(bar, ui.lp(Ui.MATCH, Ui.WRAP, 12));
        return root;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void scan(String message, final Work work, final int mode, final String title,
                      final String hint, final String action, final boolean needsStorage) {
        if (busy) return;
        if (needsStorage && !hasStorageAccess()) {
            requestStorageAccess();
            return;
        }
        busy = true;
        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(message)
                .setMessage("Esto puede tardar un momento…")
                .setCancelable(false)
                .create();
        d.show();
        new Thread(new Runnable() {
            public void run() {
                final List<Booster.Item> items = work.run();
                runOnUiThread(new Runnable() {
                    public void run() {
                        busy = false;
                        d.dismiss();
                        showReview(mode, title, hint, action, items, null);
                    }
                });
            }
        }).start();
    }

    private void openJunk() {
        scan("Buscando basura", new Work() {
            public List<Booster.Item> run() {
                return Booster.junkItems(Booster.scanJunk());
            }
        }, M_JUNK, "BASURA", "Marca lo que quieres borrar. Los APK vienen sin marcar.", "BORRAR", true);
    }

    private void openLarge() {
        scan("Buscando archivos grandes", new Work() {
            public List<Booster.Item> run() {
                return Booster.findLarge(100L * 1024 * 1024, 100);
            }
        }, M_LARGE, "ARCHIVOS GRANDES",
                "Archivos de más de 100 MB, de mayor a menor. Son tuyos: revisa bien antes de borrar.",
                "BORRAR", true);
    }

    private void openDuplicates() {
        scan("Buscando duplicados", new Work() {
            public List<Booster.Item> run() {
                return Booster.findDuplicates(256L * 1024, 60000);
            }
        }, M_DUP, "DUPLICADOS",
                "Archivos con el mismo contenido. El ORIGINAL (el más antiguo) viene sin marcar.",
                "BORRAR COPIAS", true);
    }

    private void openApps() {
        if (busy) return;
        final boolean usage = Booster.hasUsageAccess(this);
        List<Booster.AppRow> apps = Booster.listApps(this);
        if (usage) {
            // Las que no se usan hace más tiempo (o nunca) van primero
            Collections.sort(apps, new Comparator<Booster.AppRow>() {
                public int compare(Booster.AppRow a, Booster.AppRow b) {
                    return Long.compare(a.lastUsed, b.lastUsed);
                }
            });
        } else {
            Collections.sort(apps, new Comparator<Booster.AppRow>() {
                public int compare(Booster.AppRow a, Booster.AppRow b) {
                    return Long.compare(a.installed, b.installed);
                }
            });
        }
        List<Booster.Item> items = new ArrayList<Booster.Item>();
        for (Booster.AppRow a : apps) {
            Booster.Item it = new Booster.Item();
            it.pkg = a.pkg;
            it.title = a.label;
            String used;
            if (a.lastUsed < 0) used = "uso: sin permiso";
            else if (a.lastUsed == 0) used = "sin uso en 6 meses";
            else used = "usada " + ago(a.lastUsed);
            it.subtitle = used + "  ·  instalada " + ago(a.installed);
            it.size = a.apkSize;
            items.add(it);
        }
        View extra = null;
        if (!usage) {
            Button b = ui.button("DAR ACCESO DE USO", false, 44);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    try {
                        startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                        toast("Activa el acceso para Android Optimizer y vuelve");
                    } catch (Exception e) {
                        toast("Tu teléfono no tiene esa pantalla");
                    }
                }
            });
            extra = b;
        }
        showReview(M_APPS, "APPS SIN USAR",
                usage ? "Ordenadas de la que más tiempo lleva sin abrirse. Android te pedirá confirmar cada desinstalación."
                        : "Sin el acceso de uso no sé cuándo abriste cada app, así que van por fecha de instalación.",
                "DESINSTALAR", items, extra);
    }

    // =====================================================================
    // Pantalla de revisión (basura, grandes, duplicados, apps)
    // =====================================================================

    private void showReview(final int mode, final String title, final String hint, final String action,
                            final List<Booster.Item> all, View extra) {
        screen = "review";
        LinearLayout root = shell(title);

        TextView h = ui.body(hint, 12, Ui.MUTED);
        h.setPadding(0, ui.dp(10), 0, ui.dp(6));
        root.addView(h, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        if (extra != null) root.addView(extra, ui.lp(Ui.WRAP, Ui.WRAP, 4));

        final TextView summary = ui.mono("", 12, Ui.BLUE_LIGHT);
        summary.setPadding(0, ui.dp(8), 0, ui.dp(6));
        root.addView(summary, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        final Button act = ui.button(action, true, 56);

        final Runnable update = new Runnable() {
            public void run() {
                int n = 0;
                long s = 0;
                for (Booster.Item it : all) {
                    if (it.checked) {
                        n++;
                        s += it.size;
                    }
                }
                String shown = all.size() > MAX_ROWS ? "  ·  mostrando " + MAX_ROWS + " de " + all.size() : "";
                summary.setText(n + " marcados  ·  " + fmt(s) + shown);
                act.setText(action + (n > 0 ? "  (" + n + ")" : ""));
            }
        };

        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));

        if (all.isEmpty()) {
            TextView empty = ui.mono("No se encontró nada.", 13, Ui.TEXT);
            empty.setPadding(0, ui.dp(24), 0, 0);
            list.addView(empty);
        }

        int shownRows = Math.min(all.size(), MAX_ROWS);
        for (int i = 0; i < shownRows; i++) {
            final Booster.Item it = all.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, ui.dp(8), 0, ui.dp(8));

            final CheckBox cb = new CheckBox(this);
            cb.setChecked(it.checked);
            int[][] st = new int[][]{{android.R.attr.state_checked}, {}};
            cb.setButtonTintList(new ColorStateList(st, new int[]{Ui.BLUE_LIGHT, Ui.MUTED}));
            cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    it.checked = on;
                    update.run();
                }
            });
            row.addView(cb, new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP));

            LinearLayout txt = new LinearLayout(this);
            txt.setOrientation(LinearLayout.VERTICAL);
            TextView t = ui.body(it.title == null ? "" : it.title, 14, Ui.TEXT);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            txt.addView(t);
            TextView s = ui.mono(it.subtitle == null ? "" : it.subtitle, 10, Ui.MUTED);
            s.setSingleLine(true);
            s.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            txt.addView(s);
            row.addView(txt, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));

            TextView sz = ui.mono(fmt(it.size), 12, Ui.BLUE_LIGHT);
            sz.setPadding(ui.dp(8), 0, 0, 0);
            row.addView(sz, new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP));

            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    cb.toggle();
                }
            });
            list.addView(row, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
            View line = new View(this);
            line.setBackgroundColor(Ui.TRACK);
            list.addView(line, new LinearLayout.LayoutParams(Ui.MATCH, ui.dp(1)));
        }

        act.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                confirmAction(mode, title, hint, action, all);
            }
        });
        root.addView(act, ui.lp(Ui.MATCH, Ui.WRAP, 10));
        update.run();
        setContentView(root);
    }

    private boolean keepsOne(List<Booster.Item> all) {
        Map<Integer, Boolean> hasKept = new HashMap<Integer, Boolean>();
        for (Booster.Item it : all) {
            Boolean prev = hasKept.get(it.group);
            hasKept.put(it.group, (prev != null && prev) || !it.checked);
        }
        for (Boolean b : hasKept.values()) {
            if (!b) return false;
        }
        return true;
    }

    private void confirmAction(final int mode, final String title, final String hint, final String action,
                               final List<Booster.Item> all) {
        final List<Booster.Item> sel = new ArrayList<Booster.Item>();
        long total = 0;
        for (Booster.Item it : all) {
            if (it.checked) {
                sel.add(it);
                total += it.size;
            }
        }
        if (sel.isEmpty()) {
            toast("No hay nada marcado");
            return;
        }
        if (mode == M_APPS) {
            uninstallQueue.clear();
            for (Booster.Item it : sel) uninstallQueue.add(it.pkg);
            uninstallNext();
            return;
        }
        if (mode == M_DUP && !keepsOne(all)) {
            toast("En un grupo marcaste todas las copias. Deja al menos una.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("¿Borrar " + sel.size() + " archivos?")
                .setMessage("Se liberarán " + fmt(total) + ". Esto no se puede deshacer.")
                .setPositiveButton("BORRAR", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        doDelete(mode, title, hint, action, all, sel);
                    }
                })
                .setNegativeButton("CANCELAR", null)
                .show();
    }

    private void doDelete(final int mode, final String title, final String hint, final String action,
                          final List<Booster.Item> all, final List<Booster.Item> sel) {
        if (busy) return;
        busy = true;
        new Thread(new Runnable() {
            public void run() {
                final long freed = Booster.delete(sel);
                final List<Booster.Item> rest = new ArrayList<Booster.Item>();
                for (Booster.Item it : all) {
                    if (it.file != null && it.file.exists()) rest.add(it);
                }
                if (mode == M_DUP) {
                    Map<Integer, Integer> count = new HashMap<Integer, Integer>();
                    for (Booster.Item it : rest) {
                        Integer c = count.get(it.group);
                        count.put(it.group, c == null ? 1 : c + 1);
                    }
                    List<Booster.Item> pruned = new ArrayList<Booster.Item>();
                    for (Booster.Item it : rest) {
                        if (count.get(it.group) >= 2) pruned.add(it);
                    }
                    rest.clear();
                    rest.addAll(pruned);
                }
                runOnUiThread(new Runnable() {
                    public void run() {
                        busy = false;
                        toast("Espacio liberado: " + fmt(freed));
                        if (rest.isEmpty()) showHome();
                        else showReview(mode, title, hint, action, rest, null);
                    }
                });
            }
        }).start();
    }

    // ---- Desinstalar de una en una ----

    private void uninstallNext() {
        if (uninstallQueue.isEmpty()) {
            if (screen.equals("review")) openApps();
            return;
        }
        String pkg = uninstallQueue.remove(0);
        try {
            Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg));
            i.putExtra(Intent.EXTRA_RETURN_RESULT, true);
            startActivityForResult(i, REQ_UNINSTALL);
        } catch (Exception e) {
            toast("No se pudo abrir la desinstalación de " + pkg);
            uninstallNext();
        }
    }

    // =====================================================================
    // Modo juego
    // =====================================================================

    private void openGames() {
        screen = "games";
        LinearLayout root = shell("MODO JUEGO");

        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, ui.dp(8));
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));

        TextView h = ui.body("Cierra las apps en segundo plano y abre el juego. Con No molestar activado, las "
                + "notificaciones se silencian mientras juegas y todo vuelve a la normalidad al regresar a esta app.",
                12, Ui.MUTED);
        h.setPadding(0, ui.dp(10), 0, ui.dp(8));
        list.addView(h);

        final boolean dnd = prefs.getBoolean("dnd_auto", false);
        final Button dndBtn = ui.button("NO MOLESTAR AL JUGAR: " + (dnd ? "ACTIVADO" : "APAGADO"), false, 52);
        dndBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                toggleDnd();
            }
        });
        list.addView(dndBtn, ui.lp(Ui.MATCH, Ui.WRAP, 4));

        List<Booster.AppRow> apps = Booster.listApps(this);
        Collections.sort(apps, new Comparator<Booster.AppRow>() {
            public int compare(Booster.AppRow a, Booster.AppRow b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        boolean anyGame = false;
        for (Booster.AppRow a : apps) {
            if (a.game) anyGame = true;
        }
        if (anyGame) {
            list.addView(ui.label("// JUEGOS DETECTADOS"), ui.lp(Ui.MATCH, Ui.WRAP, 18));
            for (Booster.AppRow a : apps) {
                if (a.game) addGameRow(list, a);
            }
        }
        list.addView(ui.label("// TODAS LAS APPS"), ui.lp(Ui.MATCH, Ui.WRAP, 18));
        for (Booster.AppRow a : apps) {
            if (!a.game) addGameRow(list, a);
        }
        setContentView(root);
    }

    private void addGameRow(LinearLayout list, final Booster.AppRow a) {
        Button b = ui.button("▶  " + a.label, false, 50);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                launchGame(a.pkg);
            }
        });
        list.addView(b, ui.lp(Ui.MATCH, Ui.WRAP, 8));
    }

    private void toggleDnd() {
        boolean now = prefs.getBoolean("dnd_auto", false);
        if (!now) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (!nm.isNotificationPolicyAccessGranted()) {
                try {
                    startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
                    toast("Concede el acceso a No molestar y vuelve a activarlo");
                } catch (Exception e) {
                    toast("Tu teléfono no tiene esa pantalla");
                }
                return;
            }
        }
        prefs.edit().putBoolean("dnd_auto", !now).apply();
        openGames();
    }

    private void launchGame(final String pkg) {
        final Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) {
            toast("No se pudo abrir la app");
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        final boolean useDnd = prefs.getBoolean("dnd_auto", false) && nm.isNotificationPolicyAccessGranted();
        if (useDnd) {
            prefs.edit().putInt("dnd_prev", nm.getCurrentInterruptionFilter()).apply();
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS);
        }
        new Thread(new Runnable() {
            public void run() {
                Booster.killBackground(MainActivity.this);
                if (prefs.getBoolean("game_perf", false) && Pro.ready() && Pro.safePkg(pkg)) {
                    List<String> skip = Pro.protectedPackages(MainActivity.this);
                    for (String o : Pro.userPackages()) {
                        if (!o.equals(pkg) && !skip.contains(o) && Pro.safePkg(o)) Pro.sh("am force-stop " + o);
                    }
                    if (Build.VERSION.SDK_INT >= 31) Pro.sh("cmd game mode performance " + pkg);
                    Pro.sh("am set-standby-bucket " + pkg + " active");
                }
                runOnUiThread(new Runnable() {
                    public void run() {
                        try {
                            startActivity(launch);
                        } catch (Exception e) {
                            toast("No se pudo abrir la app");
                            restoreDnd();
                        }
                    }
                });
            }
        }).start();
    }

    private void restoreDnd() {
        if (prefs == null) return;
        int prev = prefs.getInt("dnd_prev", -1);
        if (prev < 0) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.isNotificationPolicyAccessGranted()) {
            nm.setInterruptionFilter(prev);
            toast("No molestar restaurado");
        }
        prefs.edit().remove("dnd_prev").apply();
    }


    // =====================================================================
    // Modo Pro (Shizuku)
    // =====================================================================

    private interface ProWork {
        String run();
    }

    private void openPro() {
        screen = "pro";
        Pro.listen(new Runnable() {
            public void run() {
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (screen.equals("pro") && !busy) openPro();
                    }
                });
            }
        });
        LinearLayout root = shell("MODO PRO");
        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, ui.dp(8));
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));

        final boolean ready = Pro.ready();
        LinearLayout st = ui.card();
        st.addView(ui.label(ready ? "// SHIZUKU: CONECTADO" : "// SHIZUKU: NO CONECTADO"));
        st.addView(ui.body(ready
                ? "Esta app puede ejecutar comandos con permisos de ADB, sin PC y sin root."
                : "Shizuku es una app gratuita que da permisos de ADB a otras apps, sin root.\n\n"
                + "1. Instala Shizuku.\n"
                + "2. Actívalo: en Android 11 o más, con Depuración inalámbrica (Opciones de desarrollador).\n"
                + "3. Vuelve aquí y concede el permiso.", 13, Ui.MUTED));
        list.addView(st, ui.lp(Ui.MATCH, Ui.WRAP, 10));

        if (!ready) {
            if (!Pro.installed(this)) {
                proButton(list, "DESCARGAR SHIZUKU", new Runnable() {
                    public void run() {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")));
                        } catch (Exception e) {
                            toast("Busca 'Shizuku' en Google Play o en shizuku.rikka.app");
                        }
                    }
                });
            } else if (!Pro.running()) {
                proButton(list, "ABRIR SHIZUKU Y ACTIVARLO", new Runnable() {
                    public void run() {
                        Intent i = getPackageManager().getLaunchIntentForPackage(Pro.SHIZUKU_PKG);
                        if (i != null) startActivity(i);
                    }
                });
            } else {
                proButton(list, "DAR PERMISO A ESTA APP", new Runnable() {
                    public void run() { Pro.requestPermission(); }
                });
            }
            setContentView(root);
            return;
        }

        list.addView(ui.label("// LIMPIEZA REAL"), ui.lp(Ui.MATCH, Ui.WRAP, 16));
        proButton(list, "LIMPIAR CACHÉ DE TODAS LAS APPS", new Runnable() {
            public void run() {
                runPro("Limpiando caché…", new ProWork() {
                    public String run() {
                        long before = Booster.freeStorage();
                        String out = Pro.sh("pm trim-caches 999G");
                        sleep(800);
                        long gained = Booster.freeStorage() - before;
                        return (gained > 0 ? "Espacio liberado: " + fmt(gained)
                                : "No había caché que liberar") + (out.isEmpty() ? "" : "\n\n" + out);
                    }
                });
            }
        });
        proButton(list, "CERRAR TODAS LAS APPS (FORCE-STOP)", new Runnable() {
            public void run() {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("CERRAR TODAS LAS APPS")
                        .setMessage("Se detienen por completo todas las apps que instalaste, salvo esta, el teclado y el launcher. "
                                + "Mientras no las abras, no sonarán sus alarmas ni recibirán avisos. ¿Seguir?")
                        .setPositiveButton("CERRAR", new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                runPro("Cerrando apps…", new ProWork() {
                                    public String run() {
                                        List<String> skip = Pro.protectedPackages(MainActivity.this);
                                        int n = 0;
                                        for (String p : Pro.userPackages()) {
                                            if (skip.contains(p) || !Pro.safePkg(p)) continue;
                                            Pro.sh("am force-stop " + p);
                                            n++;
                                        }
                                        return n + " apps detenidas.";
                                    }
                                });
                            }
                        })
                        .setNegativeButton("CANCELAR", null)
                        .show();
            }
        });

        list.addView(ui.label("// VELOCIDAD"), ui.lp(Ui.MATCH, Ui.WRAP, 16));
        proButton(list, "COMPILAR APPS PARA ABRIR MÁS RÁPIDO", new Runnable() {
            public void run() {
                runPro("Iniciando…", new ProWork() {
                    public String run() {
                        Pro.sh("nohup cmd package compile -m speed-profile -a >/dev/null 2>&1 &");
                        return "Compilando en segundo plano. Puede tardar varios minutos y se nota más tras usar las apps unos días.";
                    }
                });
            }
        });
        proButton(list, "ANIMACIONES RÁPIDAS (0.5x / 1x)", new Runnable() {
            public void run() {
                runPro("Cambiando…", new ProWork() {
                    public String run() {
                        float cur = Settings.Global.getFloat(getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
                        String t = cur > 0.6f ? "0.5" : "1";
                        Pro.sh("settings put global window_animation_scale " + t
                                + "; settings put global transition_animation_scale " + t
                                + "; settings put global animator_duration_scale " + t);
                        return "Animaciones a " + t + "x";
                    }
                });
            }
        });
        final boolean perf = prefs.getBoolean("game_perf", false);
        proButton(list, "RENDIMIENTO AL ABRIR JUEGOS: " + (perf ? "ACTIVADO" : "APAGADO"), new Runnable() {
            public void run() {
                prefs.edit().putBoolean("game_perf", !prefs.getBoolean("game_perf", false)).apply();
                openPro();
                toast("Al abrir un juego desde Modo Juego se pone en modo rendimiento (Android 12+) y prioridad activa.");
            }
        });

        list.addView(ui.label("// BATERÍA"), ui.lp(Ui.MATCH, Ui.WRAP, 16));
        proButton(list, "RESTRINGIR APPS SIN USAR (30+ DÍAS)", new Runnable() {
            public void run() {
                if (!Booster.hasUsageAccess(MainActivity.this)) {
                    toast("Primero da acceso de uso en Apps sin usar");
                    openApps();
                    return;
                }
                runPro("Restringiendo…", new ProWork() {
                    public String run() {
                        List<String> skip = Pro.protectedPackages(MainActivity.this);
                        long limit = System.currentTimeMillis() - 30L * 24 * 3600 * 1000;
                        StringBuilder saved = new StringBuilder();
                        int n = 0;
                        for (Booster.AppRow a : Booster.listApps(MainActivity.this)) {
                            if (a.lastUsed <= 0 || a.lastUsed > limit) continue;
                            if (skip.contains(a.pkg) || !Pro.safePkg(a.pkg)) continue;
                            Pro.sh("cmd appops set " + a.pkg + " RUN_ANY_IN_BACKGROUND ignore; "
                                    + "am set-standby-bucket " + a.pkg + " restricted");
                            if (saved.length() > 0) saved.append(',');
                            saved.append(a.pkg);
                            n++;
                        }
                        String old = prefs.getString("restricted", "");
                        String all = old.isEmpty() ? saved.toString()
                                : (saved.length() == 0 ? old : old + "," + saved);
                        prefs.edit().putString("restricted", all).apply();
                        return n + " apps ya no pueden trabajar en segundo plano. Se pueden restaurar con el botón de abajo.";
                    }
                });
            }
        });
        proButton(list, "RESTAURAR APPS RESTRINGIDAS", new Runnable() {
            public void run() {
                runPro("Restaurando…", new ProWork() {
                    public String run() {
                        String all = prefs.getString("restricted", "");
                        int n = 0;
                        for (String p : all.split(",")) {
                            if (!Pro.safePkg(p)) continue;
                            Pro.sh("cmd appops set " + p + " RUN_ANY_IN_BACKGROUND default; "
                                    + "am set-standby-bucket " + p + " active");
                            n++;
                        }
                        prefs.edit().remove("restricted").apply();
                        return n + " apps restauradas.";
                    }
                });
            }
        });

        list.addView(ui.label("// APPS"), ui.lp(Ui.MATCH, Ui.WRAP, 16));
        proButton(list, "CONGELAR APPS (COMO HAIL)", new Runnable() {
            public void run() { openFreeze(); }
        });
        list.addView(ui.label("// MEMORIA"), ui.lp(Ui.MATCH, Ui.WRAP, 16));
        proButton(list, "QUIÉN GASTA MÁS MEMORIA", new Runnable() {
            public void run() { openMemory(); }
        });
        list.addView(ui.label("// ALMACENAMIENTO"), ui.lp(Ui.MATCH, Ui.WRAP, 16));
        proButton(list, "RESTOS DE APPS YA BORRADAS", new Runnable() {
            public void run() { findOrphans(); }
        });
        proButton(list, "ESTADO DE LA BATERÍA", new Runnable() {
            public void run() { showBattery(); }
        });
        setContentView(root);
    }


    // ---------- Congelar apps (Hail, FreezeYou) ----------

    private void openFreeze() {
        if (busy) return;
        busy = true;
        final AlertDialog d = new AlertDialog.Builder(this).setTitle("Leyendo apps…")
                .setMessage("Un momento…").setCancelable(false).create();
        d.show();
        new Thread(new Runnable() {
            public void run() {
                final PackageManager pm = getPackageManager();
                final List<String> skip = Pro.protectedPackages(MainActivity.this);
                final List<String> frozen = new ArrayList<String>();
                for (String p : Pro.disabledPackages()) {
                    if (!skip.contains(p) && Pro.safePkg(p)) frozen.add(p);
                }
                final List<String> candidates = new ArrayList<String>();
                for (String p : Pro.userPackages()) {
                    if (!skip.contains(p) && Pro.safePkg(p) && !frozen.contains(p)) candidates.add(p);
                }
                Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
                    String p = ri.activityInfo.packageName;
                    boolean system = (ri.activityInfo.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
                    if (system && !Pro.critical(p) && !skip.contains(p) && Pro.safePkg(p)
                            && !frozen.contains(p) && !candidates.contains(p)) candidates.add(p);
                }
                final Map<String, String> names = new HashMap<String, String>();
                for (String p : frozen) names.put(p, labelOf(pm, p));
                java.util.Set<String> userSet = new java.util.HashSet<String>(Pro.userPackages());
                for (String p : candidates) {
                    String n = labelOf(pm, p);
                    names.put(p, userSet.contains(p) ? n : n + "  [SISTEMA]");
                }
                Collections.sort(candidates, new Comparator<String>() {
                    public int compare(String a, String b) {
                        return names.get(a).compareToIgnoreCase(names.get(b));
                    }
                });
                runOnUiThread(new Runnable() {
                    public void run() {
                        busy = false;
                        d.dismiss();
                        showFreeze(frozen, candidates, names);
                    }
                });
            }
        }).start();
    }


    /** Muestra qué se va a congelar antes de hacerlo y recuerda la lista como "mi lista". */
    private void confirmFreeze(final List<String> sel, final Map<String, String> names) {
        StringBuilder sb = new StringBuilder();
        for (String p : sel) sb.append("• ").append(names.get(p)).append('\n');
        new AlertDialog.Builder(this)
                .setTitle("CONGELAR " + sel.size() + " APPS")
                .setMessage(sb + "\nNo se borra nada: se descongelan desde esta pantalla. "
                        + "Quedarán guardadas como 'mi lista' para congelarlas de nuevo con un toque.")
                .setPositiveButton("CONGELAR", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<String>();
                        for (String p : prefs.getString("freeze_list", "").split(",")) {
                            if (Pro.safePkg(p)) all.add(p);
                        }
                        all.addAll(sel);
                        prefs.edit().putString("freeze_list", TextUtils.join(",", all)).apply();
                        runPro("Congelando…", new ProWork() {
                            public String run() {
                                for (String p : sel) {
                                    Pro.sh("am force-stop " + p + "; pm disable-user --user 0 " + p);
                                }
                                return sel.size() + " apps congeladas. Para usarlas, descongélalas aquí.";
                            }
                        });
                    }
                })
                .setNegativeButton("CANCELAR", null)
                .show();
    }

    private String labelOf(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private void showFreeze(final List<String> frozen, final List<String> candidates, final Map<String, String> names) {
        screen = "freeze";
        LinearLayout root = shell("CONGELAR APPS");
        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));

        TextView h = ui.body("Una app congelada no corre, no gasta batería ni recibe avisos, pero sigue instalada con sus datos. "
                + "Se descongela con un toque. Las apps vitales del sistema no aparecen aquí.", 12, Ui.MUTED);
        h.setPadding(0, ui.dp(10), 0, ui.dp(6));
        list.addView(h);

        if (!frozen.isEmpty()) {
            list.addView(ui.label("// CONGELADAS (" + frozen.size() + ")"), ui.lp(Ui.MATCH, Ui.WRAP, 10));
            for (final String p : frozen) {
                Button b = ui.button("↺  DESCONGELAR  " + names.get(p), false, 48);
                b.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        runPro("Descongelando…", new ProWork() {
                            public String run() {
                                Pro.sh("pm enable " + p);
                                return names.get(p) + " está activa otra vez.";
                            }
                        });
                    }
                });
                list.addView(b, ui.lp(Ui.MATCH, Ui.WRAP, 6));
            }
            Button all = ui.button("DESCONGELAR TODAS", false, 48);
            all.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    runPro("Descongelando…", new ProWork() {
                        public String run() {
                            for (String p : frozen) Pro.sh("pm enable " + p);
                            return frozen.size() + " apps activas otra vez.";
                        }
                    });
                }
            });
            list.addView(all, ui.lp(Ui.MATCH, Ui.WRAP, 10));
        }

        final List<String> mine = new ArrayList<String>();
        for (String p : prefs.getString("freeze_list", "").split(",")) {
            if (Pro.safePkg(p) && candidates.contains(p)) mine.add(p);
        }
        if (!mine.isEmpty()) {
            Button grp = ui.button("CONGELAR MI LISTA (" + mine.size() + ")", true, 52);
            grp.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    confirmFreeze(mine, names);
                }
            });
            list.addView(grp, ui.lp(Ui.MATCH, Ui.WRAP, 12));
        }

        list.addView(ui.label("// ELIGE LAS QUE QUIERES CONGELAR"), ui.lp(Ui.MATCH, Ui.WRAP, 16));
        final List<CheckBox> boxes = new ArrayList<CheckBox>();
        final List<String> pkgs = new ArrayList<String>();
        int shown = 0;
        for (String p : candidates) {
            if (shown++ >= MAX_ROWS) break;
            CheckBox cb = new CheckBox(this);
            cb.setText(names.get(p));
            cb.setTextColor(Ui.TEXT);
            cb.setTextSize(14);
            cb.setButtonTintList(ColorStateList.valueOf(Ui.BLUE_LIGHT));
            cb.setPadding(ui.dp(4), ui.dp(10), 0, ui.dp(10));
            list.addView(cb, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
            boxes.add(cb);
            pkgs.add(p);
        }
        Button go = ui.button("CONGELAR SELECCIONADAS", true, 56);
        go.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final List<String> sel = new ArrayList<String>();
                for (int i = 0; i < boxes.size(); i++) {
                    if (boxes.get(i).isChecked()) sel.add(pkgs.get(i));
                }
                if (sel.isEmpty()) {
                    toast("No elegiste ninguna app");
                    return;
                }
                confirmFreeze(sel, names);
            }
        });
        root.addView(go, ui.lp(Ui.MATCH, Ui.WRAP, 8));
        setContentView(root);
    }

    // ---------- Memoria por app (memhogs, ProcessLens) ----------

    private void openMemory() {
        if (busy) return;
        busy = true;
        final AlertDialog d = new AlertDialog.Builder(this).setTitle("Midiendo memoria…")
                .setMessage("Un momento…").setCancelable(false).create();
        d.show();
        new Thread(new Runnable() {
            public void run() {
                String out = Pro.sh("dumpsys meminfo", 60000);
                List<String> user = Pro.userPackages();
                List<String> skip = Pro.protectedPackages(MainActivity.this);
                final List<String[]> rows = new ArrayList<String[]>();
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("^\\s*([\\d.,]+)K: (\\S+) \\(pid").matcher("");
                java.util.Set<String> seen = new java.util.HashSet<String>();
                for (String line : out.split("\n")) {
                    if (line.contains("Total RSS") || line.contains("Total PSS by OOM")) break;
                    m.reset(line);
                    if (!m.find()) continue;
                    String proc = m.group(2);
                    String pkg = proc.contains(":") ? proc.substring(0, proc.indexOf(':')) : proc;
                    if (!user.contains(pkg) || skip.contains(pkg) || !seen.add(pkg)) continue;
                    long kb;
                    try {
                        kb = Long.parseLong(m.group(1).replace(",", "").replace(".", ""));
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    rows.add(new String[]{pkg, String.valueOf(kb * 1024)});
                    if (rows.size() >= 15) break;
                }
                final PackageManager pm = getPackageManager();
                final List<String> labels = new ArrayList<String>();
                for (String[] r : rows) labels.add(labelOf(pm, r[0]));
                runOnUiThread(new Runnable() {
                    public void run() {
                        busy = false;
                        d.dismiss();
                        showMemory(rows, labels);
                    }
                });
            }
        }).start();
    }

    private void showMemory(final List<String[]> rows, List<String> labels) {
        screen = "memory";
        LinearLayout root = shell("MEMORIA POR APP");
        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));
        TextView h = ui.body(rows.isEmpty()
                ? "No hay apps tuyas usando memoria ahora mismo."
                : "Tus apps que más RAM usan ahora. Toca una para cerrarla.", 12, Ui.MUTED);
        h.setPadding(0, ui.dp(10), 0, ui.dp(6));
        list.addView(h);
        for (int i = 0; i < rows.size(); i++) {
            final String pkg = rows.get(i)[0];
            final String label = labels.get(i);
            long bytes = Long.parseLong(rows.get(i)[1]);
            Button b = ui.button(fmt(bytes) + "   " + label + "   ✕ CERRAR", false, 50);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    runPro("Cerrando…", new ProWork() {
                        public String run() {
                            Pro.sh("am force-stop " + pkg);
                            return label + " cerrada.";
                        }
                    });
                }
            });
            list.addView(b, ui.lp(Ui.MATCH, Ui.WRAP, 8));
        }
        setContentView(root);
    }

    // ---------- Restos de apps borradas (SD Maid CorpseFinder) ----------

    private void findOrphans() {
        if (busy) return;
        busy = true;
        final AlertDialog d = new AlertDialog.Builder(this).setTitle("Buscando restos…")
                .setMessage("Un momento…").setCancelable(false).create();
        d.show();
        new Thread(new Runnable() {
            public void run() {
                java.util.Set<String> installed = Pro.installedPackages();
                String out = Pro.sh("for b in data obb media; do for d in /sdcard/Android/$b/*; do "
                        + "[ -d \"$d\" ] && echo \"$b/$(basename \"$d\") $(du -sk \"$d\" 2>/dev/null | cut -f1)\"; "
                        + "done; done", 100000);
                final List<String> paths = new ArrayList<String>();
                long total = 0;
                StringBuilder sb = new StringBuilder();
                for (String line : out.split("\n")) {
                    String[] parts = line.trim().split(" ");
                    if (parts.length < 2) continue;
                    String rel = parts[0];
                    int slash = rel.indexOf('/');
                    if (slash < 0) continue;
                    String name = rel.substring(slash + 1);
                    if (!name.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+") || installed.contains(name)) continue;
                    long kb;
                    try {
                        kb = Long.parseLong(parts[parts.length - 1]);
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    paths.add(rel);
                    total += kb * 1024;
                    if (paths.size() <= 25) sb.append(name).append("  ·  ").append(fmt(kb * 1024)).append('\n');
                }
                final String listText = sb.toString();
                final long totalFinal = total;
                runOnUiThread(new Runnable() {
                    public void run() {
                        busy = false;
                        d.dismiss();
                        if (paths.isEmpty()) {
                            new AlertDialog.Builder(MainActivity.this).setTitle("TODO LIMPIO")
                                    .setMessage("No quedan carpetas de apps que ya no tienes.")
                                    .setPositiveButton("OK", null).show();
                            return;
                        }
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle(paths.size() + " carpetas · " + fmt(totalFinal))
                                .setMessage("Son datos de apps que ya no están instaladas:\n\n" + listText
                                        + (paths.size() > 25 ? "…y " + (paths.size() - 25) + " más\n" : "")
                                        + "\nSe borrarán para siempre.")
                                .setPositiveButton("BORRAR TODO", new android.content.DialogInterface.OnClickListener() {
                                    public void onClick(android.content.DialogInterface dd, int w) {
                                        runPro("Borrando…", new ProWork() {
                                            public String run() {
                                                long before = Booster.freeStorage();
                                                for (String rel : paths) {
                                                    Pro.sh("rm -rf \"/sdcard/Android/" + rel + "\"");
                                                }
                                                sleep(500);
                                                long gained = Booster.freeStorage() - before;
                                                return paths.size() + " carpetas borradas"
                                                        + (gained > 0 ? "  ·  +" + fmt(gained) : "") + ".";
                                            }
                                        });
                                    }
                                })
                                .setNegativeButton("CANCELAR", null)
                                .show();
                    }
                });
            }
        }).start();
    }

    // ---------- Batería (BatStats, Battery Monitor) ----------

    private void showBattery() {
        runPro("Leyendo batería…", new ProWork() {
            public String run() {
                String out = Pro.sh("dumpsys battery", 4000);
                String level = grab(out, "level"), temp = grab(out, "temperature");
                String volt = grab(out, "voltage"), health = grab(out, "health"), status = grab(out, "status");
                StringBuilder sb = new StringBuilder();
                sb.append("Nivel: ").append(level).append("%\n");
                try {
                    sb.append("Temperatura: ").append(Integer.parseInt(temp) / 10f).append(" °C\n");
                } catch (NumberFormatException e) {
                    sb.append("Temperatura: ").append(temp).append('\n');
                }
                sb.append("Voltaje: ").append(volt).append(" mV\n");
                sb.append("Salud: ").append(healthName(health)).append('\n');
                sb.append("Estado: ").append(statusName(status));
                return sb.toString();
            }
        });
    }

    private static String grab(String dump, String key) {
        for (String line : dump.split("\n")) {
            line = line.trim();
            if (line.startsWith(key + ":")) return line.substring(key.length() + 1).trim();
        }
        return "?";
    }

    private static String healthName(String v) {
        if ("2".equals(v)) return "buena";
        if ("3".equals(v)) return "sobrecalentada";
        if ("4".equals(v)) return "muerta";
        if ("5".equals(v)) return "sobrevoltaje";
        if ("7".equals(v)) return "fría";
        return v;
    }

    private static String statusName(String v) {
        if ("2".equals(v)) return "cargando";
        if ("3".equals(v)) return "descargando";
        if ("4".equals(v)) return "sin cargar";
        if ("5".equals(v)) return "llena";
        return v;
    }

    private void proButton(LinearLayout list, String label, final Runnable r) {
        quickButton(list, label, r);
    }

    private void runPro(String message, final ProWork w) {
        if (busy) return;
        busy = true;
        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(message)
                .setMessage("Un momento…")
                .setCancelable(false)
                .create();
        d.show();
        new Thread(new Runnable() {
            public void run() {
                final String res = w.run();
                runOnUiThread(new Runnable() {
                    public void run() {
                        busy = false;
                        d.dismiss();
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("LISTO")
                                .setMessage(res)
                                .setPositiveButton("OK", null)
                                .show();
                    }
                });
            }
        }).start();
    }

    // =====================================================================
    // Ajustes rápidos
    // =====================================================================

    private void openQuick() {
        screen = "quick";
        LinearLayout root = shell("AJUSTES RÁPIDOS");
        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));

        TextView h = ui.body("Atajos a lo que Android permite cambiar sin root.", 12, Ui.MUTED);
        h.setPadding(0, ui.dp(10), 0, ui.dp(6));
        list.addView(h);

        quickButton(list, "ANIMACIONES RÁPIDAS (0.5x / 1x)", new Runnable() {
            public void run() { toggleAnimations(); }
        });
        quickButton(list, "BORRAR CACHÉ DE TODAS LAS APPS", new Runnable() {
            public void run() { clearAppCache(); }
        });
        quickButton(list, "AHORRO DE BATERÍA", new Runnable() {
            public void run() { openSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS); }
        });
        quickButton(list, "ALMACENAMIENTO", new Runnable() {
            public void run() { openSettings(Settings.ACTION_INTERNAL_STORAGE_SETTINGS); }
        });
        quickButton(list, "APPS INSTALADAS", new Runnable() {
            public void run() { openSettings(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS); }
        });
        quickButton(list, "OPCIONES DE DESARROLLADOR", new Runnable() {
            public void run() { openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS); }
        });
        setContentView(root);
    }

    private void quickButton(LinearLayout list, String label, final Runnable r) {
        Button b = ui.button(label, false, 54);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                r.run();
            }
        });
        list.addView(b, ui.lp(Ui.MATCH, Ui.WRAP, 8));
    }

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action));
        } catch (Exception e) {
            toast("Tu teléfono no tiene esa pantalla de ajustes");
        }
    }

    private void clearAppCache() {
        try {
            startActivity(new Intent("android.os.storage.action.CLEAR_APP_CACHE"));
        } catch (Exception e) {
            toast("Tu Android no deja hacerlo desde aquí. En Almacenamiento toca 'Datos en caché'.");
            openSettings(Settings.ACTION_INTERNAL_STORAGE_SETTINGS);
        }
    }

    private void toggleAnimations() {
        ContentResolver r = getContentResolver();
        float cur = Settings.Global.getFloat(r, Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        float target = cur > 0.6f ? 0.5f : 1f;
        try {
            Settings.Global.putFloat(r, Settings.Global.WINDOW_ANIMATION_SCALE, target);
            Settings.Global.putFloat(r, Settings.Global.TRANSITION_ANIMATION_SCALE, target);
            Settings.Global.putFloat(r, Settings.Global.ANIMATOR_DURATION_SCALE, target);
            toast("Animaciones a " + target + "x" + (target < 1f ? " (más rápidas)" : " (normales)"));
        } catch (SecurityException e) {
            if (Pro.ready()) {
                Pro.sh("pm grant " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS");
                try {
                    Settings.Global.putFloat(r, Settings.Global.WINDOW_ANIMATION_SCALE, target);
                    Settings.Global.putFloat(r, Settings.Global.TRANSITION_ANIMATION_SCALE, target);
                    Settings.Global.putFloat(r, Settings.Global.ANIMATOR_DURATION_SCALE, target);
                    toast("Animaciones a " + target + "x (permiso concedido con Shizuku)");
                    return;
                } catch (SecurityException e2) {
                    // sigue al diálogo de ADB
                }
            }
            showAdbDialog();
        }
    }

    private void showAdbDialog() {
        new AlertDialog.Builder(this)
                .setTitle("PERMISO ÚNICO")
                .setMessage("Android no deja cambiar las animaciones sin un permiso especial. "
                        + "Conecta el teléfono a una PC una sola vez y ejecuta:\n\n" + ADB_CMD
                        + "\n\nDespués vuelve a pulsar el botón.")
                .setPositiveButton("COPIAR COMANDO", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("adb", ADB_CMD));
                        toast("Comando copiado");
                    }
                })
                .setNegativeButton("CERRAR", null)
                .show();
    }

    // =====================================================================
    // Permisos y utilidades
    // =====================================================================

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStorageAccess() {
        toast("Concede el acceso a archivos y vuelve a pulsar el botón");
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                openSettings(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
            }
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
    }

    private String fmt(long bytes) {
        return Formatter.formatShortFileSize(this, bytes);
    }

    private String ago(long t) {
        if (t <= 0) return "nunca";
        long m = (System.currentTimeMillis() - t) / 60000L;
        if (m < 1) return "hace un momento";
        if (m < 60) return "hace " + m + " min";
        long h = m / 60;
        if (h < 48) return "hace " + h + " h";
        long d = h / 24;
        if (d < 60) return "hace " + d + " días";
        return "hace " + (d / 30) + " meses";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
