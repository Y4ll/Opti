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
                log.append("✔ [2/3] Caché de esta app limpia\n");

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
