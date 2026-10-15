package io.github.androidoptimizer.noroot;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends Activity {

    private static final int BG = Color.parseColor("#000000");
    private static final int CARD = Color.parseColor("#05080F");
    private static final int BLUE = Color.parseColor("#1F6BFF");
    private static final int BLUE_DARK = Color.parseColor("#0B3A99");
    private static final int BLUE_LIGHT = Color.parseColor("#6FB3FF");
    private static final int TEXT = Color.parseColor("#FFFFFF");
    private static final int MUTED = Color.parseColor("#5F78A8");

    private static final String ADB_CMD =
            "adb shell pm grant io.github.androidoptimizer.noroot android.permission.WRITE_SECURE_SETTINGS";

    private TextView log;
    private TextView status;
    private ScrollView logScroll;
    private final List<Button> buttons = new ArrayList<Button>();
    private volatile boolean busy = false;
    private int index = 0;

    private static class App {
        String pkg;
        String label;
    }

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

        TextView badge = new TextView(this);
        badge.setText("[ SIN ROOT ]");
        badge.setTextColor(BLUE_LIGHT);
        badge.setTextSize(12);
        badge.setLetterSpacing(0.2f);
        badge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        badge.setPadding(0, dp(10), 0, dp(14));
        col.addView(badge);

        col.addView(sectionLabel("// SISTEMA"));
        status = new TextView(this);
        status.setTextColor(TEXT);
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextSize(12);
        status.setLineSpacing(0, 1.25f);
        status.setPadding(dp(14), dp(12), dp(18), dp(16));
        status.setBackground(new ChamferDrawable(CARD, BLUE, BLUE_DARK, dp(14), dp(3), dp(4)));
        col.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView mods = sectionLabel("// MÓDULOS");
        mods.setPadding(0, dp(20), 0, dp(6));
        col.addView(mods);

        addButton(col, "OPTIMIZAR TODO", true, new Runnable() {
            public void run() { optimizeAll(); }
        });
        addButton(col, "LIBERAR RAM", false, new Runnable() {
            public void run() { freeRamAction(); }
        });
        addButton(col, "LIMPIAR ARCHIVOS BASURA", false, new Runnable() {
            public void run() { junkFlow(); }
        });
        addButton(col, "BORRAR CACHÉ DE APPS", false, new Runnable() {
            public void run() { clearAppCache(); }
        });
        addButton(col, "MODO JUEGO", false, new Runnable() {
            public void run() { pickGame(); }
        });
        addButton(col, "ANIMACIONES RÁPIDAS", false, new Runnable() {
            public void run() { toggleAnimations(); }
        });
        addButton(col, "AHORRO DE BATERÍA", false, new Runnable() {
            public void run() { openSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS); }
        });
        addButton(col, "APPS INSTALADAS", false, new Runnable() {
            public void run() { openSettings(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS); }
        });
        addButton(col, "ALMACENAMIENTO", false, new Runnable() {
            public void run() { openSettings(Settings.ACTION_INTERNAL_STORAGE_SETTINGS); }
        });
        addButton(col, "OPCIONES DE DESARROLLADOR", false, new Runnable() {
            public void run() { openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS); }
        });

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

        LinearLayout.LayoutParams hz = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(14));
        hz.topMargin = dp(22);
        col.addView(new HazardView(this), hz);

        setContentView(page);
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (status != null) refreshStatus();
    }

    // ---------- Estado ----------

    private String fmt(long bytes) {
        return Formatter.formatShortFileSize(this, bytes);
    }

    private void refreshStatus() {
        long ramFree = Booster.availRam(this);
        long ramTotal = Booster.totalRam(this);
        long stFree = Booster.freeStorage();
        long stTotal = Booster.totalStorage();
        int[] bat = Booster.battery(this);
        int ramUsedPct = ramTotal > 0 ? Math.round((ramTotal - ramFree) * 100f / ramTotal) : 0;
        int stUsedPct = stTotal > 0 ? Math.round((stTotal - stFree) * 100f / stTotal) : 0;
        String batTxt = bat[0] >= 0 ? bat[0] + "%  " + (bat[1] / 10f) + " °C" : "n/d";
        status.setText(
                "RAM       " + fmt(ramFree) + " libres de " + fmt(ramTotal) + "  (" + ramUsedPct + "% en uso)\n" +
                "ALMACEN.  " + fmt(stFree) + " libres de " + fmt(stTotal) + "  (" + stUsedPct + "% en uso)\n" +
                "BATERÍA   " + batTxt);
    }

    // ---------- Acciones ----------

    private void optimizeAll() {
        if (busy) return;
        startBusy("OPTIMIZAR TODO");
        new Thread(new Runnable() {
            public void run() {
                freeRamBlocking();
                Booster.cleanOwnCache(MainActivity.this);
                runOnUiThread(new Runnable() {
                    public void run() {
                        endBusy();
                        refreshStatus();
                        junkFlow();
                    }
                });
            }
        }).start();
    }

    private void freeRamAction() {
        if (busy) return;
        startBusy("LIBERAR RAM");
        new Thread(new Runnable() {
            public void run() {
                freeRamBlocking();
                runOnUiThread(new Runnable() {
                    public void run() {
                        endBusy();
                        refreshStatus();
                    }
                });
            }
        }).start();
    }

    /** Debe llamarse fuera del hilo principal. */
    private void freeRamBlocking() {
        long before = Booster.availRam(this);
        int n = Booster.killBackground(this);
        append("▶ Se pidió cerrar " + n + " apps en segundo plano");
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long after = Booster.availRam(this);
        if (after > before) {
            append("▶ RAM liberada: " + fmt(after - before));
        } else {
            append("▶ Sin cambio notable: Android ya tenía la RAM bien organizada");
        }
        append("▶ RAM libre ahora: " + fmt(after));
    }

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStorageAccess() {
        append("▶ Falta el permiso de acceso a archivos.");
        append("▶ Concédelo y vuelve a pulsar el botón.");
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
    }

    private void junkFlow() {
        if (busy) return;
        if (!hasStorageAccess()) {
            requestStorageAccess();
            return;
        }
        startBusy("BUSCANDO BASURA");
        new Thread(new Runnable() {
            public void run() {
                final Booster.Junk j = Booster.scanJunk();
                runOnUiThread(new Runnable() {
                    public void run() {
                        endBusy();
                        showJunkDialog(j);
                    }
                });
            }
        }).start();
    }

    private void showJunkDialog(final Booster.Junk j) {
        if (j.isEmpty()) {
            append("▶ No se encontró basura");
            return;
        }
        final String[] items = new String[]{
                "Temporales y logs  ·  " + j.temp.size() + " archivos  ·  " + fmt(j.tempSize),
                "Miniaturas  ·  " + j.thumbs.size() + " archivos  ·  " + fmt(j.thumbsSize),
                "APK descargados  ·  " + j.apks.size() + " archivos  ·  " + fmt(j.apkSize)
        };
        final boolean[] checked = new boolean[]{!j.temp.isEmpty(), !j.thumbs.isEmpty(), false};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("ARCHIVOS BASURA")
                .setMultiChoiceItems(items, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    public void onClick(DialogInterface d, int which, boolean isChecked) {
                        checked[which] = isChecked;
                    }
                })
                .setPositiveButton("BORRAR", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        deleteSelected(j, checked);
                    }
                })
                .setNegativeButton("CANCELAR", null)
                .show();
    }

    private void deleteSelected(final Booster.Junk j, final boolean[] checked) {
        startBusy("BORRANDO");
        new Thread(new Runnable() {
            public void run() {
                long freed = 0;
                if (checked[0]) freed += Booster.delete(j.temp);
                if (checked[1]) freed += Booster.delete(j.thumbs);
                if (checked[2]) freed += Booster.delete(j.apks);
                append("▶ Espacio liberado: " + fmt(freed));
                runOnUiThread(new Runnable() {
                    public void run() {
                        endBusy();
                        refreshStatus();
                    }
                });
            }
        }).start();
    }

    private void clearAppCache() {
        append("\n> BORRAR CACHÉ DE APPS");
        try {
            startActivity(new Intent("android.os.storage.action.CLEAR_APP_CACHE"));
            append("▶ Confirma en la ventana del sistema");
        } catch (Exception e) {
            append("▶ Tu Android no deja hacerlo desde aquí.");
            append("▶ Abriendo Ajustes > Almacenamiento: toca 'Datos en caché'.");
            openSettings(Settings.ACTION_INTERNAL_STORAGE_SETTINGS);
        }
    }

    private void pickGame() {
        if (busy) return;
        PackageManager pm = getPackageManager();
        final List<App> apps = new ArrayList<App>();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            if (ai.packageName.equals(getPackageName())) continue;
            if (pm.getLaunchIntentForPackage(ai.packageName) == null) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = String.valueOf(pm.getApplicationLabel(ai));
            apps.add(a);
        }
        if (apps.isEmpty()) {
            append("▶ No se encontraron apps instaladas");
            return;
        }
        Collections.sort(apps, new Comparator<App>() {
            public int compare(App x, App y) {
                return x.label.compareToIgnoreCase(y.label);
            }
        });
        String[] labels = new String[apps.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = apps.get(i).label;

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("ELIGE EL JUEGO")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        launchGame(apps.get(which));
                    }
                })
                .setNegativeButton("CANCELAR", null)
                .show();
    }

    private void launchGame(final App app) {
        startBusy("MODO JUEGO: " + app.label.toUpperCase());
        new Thread(new Runnable() {
            public void run() {
                freeRamBlocking();
                runOnUiThread(new Runnable() {
                    public void run() {
                        endBusy();
                        refreshStatus();
                        Intent launch = getPackageManager().getLaunchIntentForPackage(app.pkg);
                        if (launch != null) {
                            append("▶ Abriendo " + app.label);
                            startActivity(launch);
                        }
                    }
                });
            }
        }).start();
    }

    private void toggleAnimations() {
        append("\n> ANIMACIONES");
        ContentResolver r = getContentResolver();
        float cur = Settings.Global.getFloat(r, Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        float target = cur > 0.6f ? 0.5f : 1f;
        try {
            Settings.Global.putFloat(r, Settings.Global.WINDOW_ANIMATION_SCALE, target);
            Settings.Global.putFloat(r, Settings.Global.TRANSITION_ANIMATION_SCALE, target);
            Settings.Global.putFloat(r, Settings.Global.ANIMATOR_DURATION_SCALE, target);
            append("▶ Animaciones a " + target + "x" + (target < 1f ? " (más rápidas)" : " (normales)"));
        } catch (SecurityException e) {
            append("▶ Falta un permiso especial (se da una sola vez por ADB)");
            showAdbDialog();
        }
    }

    private void showAdbDialog() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("PERMISO ÚNICO")
                .setMessage("Android no deja cambiar las animaciones sin un permiso especial. "
                        + "Conecta el teléfono a una PC una sola vez y ejecuta:\n\n" + ADB_CMD
                        + "\n\nDespués vuelve a pulsar el botón.")
                .setPositiveButton("COPIAR COMANDO", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("adb", ADB_CMD));
                        append("▶ Comando copiado");
                    }
                })
                .setNegativeButton("CERRAR", null)
                .show();
    }

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action));
        } catch (Exception e) {
            append("▶ Tu teléfono no tiene esa pantalla de ajustes");
        }
    }

    // ---------- Interfaz ----------

    private void startBusy(String label) {
        busy = true;
        setButtonsEnabled(false);
        append("\n> " + label);
    }

    private void endBusy() {
        busy = false;
        setButtonsEnabled(true);
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

    private void addButton(LinearLayout col, String label, boolean primary, final Runnable action) {
        index++;
        Button b = new Button(this);
        b.setText((index < 10 ? "0" : "") + index + "  //  " + label);
        styleButton(b, primary);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                action.run();
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

    private void setButtonsEnabled(boolean on) {
        for (Button b : buttons) {
            b.setEnabled(on);
            b.setAlpha(on ? 1f : 0.35f);
        }
    }

    private void append(final String line) {
        runOnUiThread(new Runnable() {
            public void run() {
                log.append(line + "\n");
                logScroll.post(new Runnable() {
                    public void run() { logScroll.fullScroll(View.FOCUS_DOWN); }
                });
            }
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
