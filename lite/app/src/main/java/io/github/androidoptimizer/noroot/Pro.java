package io.github.androidoptimizer.noroot;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import rikka.shizuku.Shizuku;

/**
 * Modo Pro: usa Shizuku para ejecutar comandos con permisos de ADB (shell) sin root y sin PC.
 * Todo lo que hay aquí es reversible o no destructivo.
 */
final class Pro {

    static final int REQ = 4242;
    static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";

    private Pro() {
    }

    /** Shizuku está en marcha (el servicio responde). */
    static boolean running() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Shizuku está en marcha y esta app ya tiene permiso. */
    static boolean ready() {
        try {
            return Shizuku.pingBinder() && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean installed(Context c) {
        try {
            c.getPackageManager().getPackageInfo(SHIZUKU_PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static Shizuku.OnRequestPermissionResultListener permL;
    private static Shizuku.OnBinderReceivedListener binderL;

    /** Avisa a la pantalla cuando Shizuku se conecta o el usuario responde al permiso. */
    static void listen(final Runnable onChange) {
        unlisten();
        try {
            permL = new Shizuku.OnRequestPermissionResultListener() {
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    onChange.run();
                }
            };
            binderL = new Shizuku.OnBinderReceivedListener() {
                public void onBinderReceived() {
                    onChange.run();
                }
            };
            Shizuku.addRequestPermissionResultListener(permL);
            Shizuku.addBinderReceivedListener(binderL);
        } catch (Throwable ignored) {
        }
    }

    static void unlisten() {
        try {
            if (permL != null) Shizuku.removeRequestPermissionResultListener(permL);
            if (binderL != null) Shizuku.removeBinderReceivedListener(binderL);
        } catch (Throwable ignored) {
        }
        permL = null;
        binderL = null;
    }

    static void requestPermission() {
        try {
            Shizuku.requestPermission(REQ);
        } catch (Throwable ignored) {
        }
    }

    /** Ejecuta un comando de shell con permisos de shell. Devuelve la salida (stdout + stderr). */
    static String sh(String cmd) {
        return sh(cmd, 4000);
    }

    static String sh(String cmd, int limit) {
        try {
            Method m = Shizuku.class.getDeclaredMethod("newProcess",
                    String[].class, String[].class, String.class);
            m.setAccessible(true);
            Process p = (Process) m.invoke(null, new Object[]{
                    new String[]{"sh", "-c", "(" + cmd + ") 2>&1"}, null, null});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                if (sb.length() < limit) sb.append(line).append('\n');
            }
            p.waitFor();
            return sb.toString().trim();
        } catch (Throwable t) {
            return "ERROR: " + t.getClass().getSimpleName();
        }
    }

    /** Paquetes de terceros instalados (los que se pueden tocar con seguridad). */
    static List<String> userPackages() {
        List<String> out = new ArrayList<String>();
        for (String line : sh("pm list packages -3", 200000).split("\n")) {
            line = line.trim();
            if (line.startsWith("package:")) out.add(line.substring(8));
        }
        return out;
    }

    /** Paquetes que nunca se deben cerrar ni restringir: esta app, Shizuku, el launcher y el teclado. */
    static List<String> protectedPackages(Context c) {
        List<String> p = new ArrayList<String>();
        p.add(c.getPackageName());
        p.add(SHIZUKU_PKG);
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : c.getPackageManager().queryIntentActivities(home, 0)) {
            p.add(ri.activityInfo.packageName);
        }
        String ime = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (ime != null && ime.contains("/")) p.add(ime.substring(0, ime.indexOf('/')));
        return p;
    }

    /** Evita inyectar texto raro en el shell: un nombre de paquete solo tiene letras, números, punto y guion bajo. */
    static boolean safePkg(String s) {
        return s != null && s.matches("[A-Za-z0-9._]+");
    }

    private static final String[] CRITICAL = {
            "systemui", "settings", "packageinstaller", "permissioncontroller", "telephony", "phone",
            "dialer", "contacts", "mms", "messag", "launcher", "inputmethod", "keyboard", "gms", "gsf",
            "vending", "webview", "bluetooth", "nfc", "providers", "shell", "android.server",
            "networkstack", "camera", "wallpaper", "emergency", "cellbroadcast", "ims", "carrier",
            "overlay", "framework", "sim", "nearby", "cts", "clock", "deskclock", "calendar"};

    /** Apps del sistema que no se deben congelar nunca. */
    static boolean critical(String pkg) {
        String p = pkg.toLowerCase();
        if (p.equals("android")) return true;
        for (String c : CRITICAL) {
            if (p.contains(c)) return true;
        }
        return false;
    }

    /** Paquetes inhabilitados (congelados) en este momento. */
    static List<String> disabledPackages() {
        List<String> out = new ArrayList<String>();
        for (String line : sh("pm list packages -d", 20000).split("\n")) {
            line = line.trim();
            if (line.startsWith("package:")) out.add(line.substring(8));
        }
        return out;
    }

    /** Todos los paquetes instalados. */
    static java.util.Set<String> installedPackages() {
        java.util.Set<String> out = new java.util.HashSet<String>();
        for (String line : sh("pm list packages", 200000).split("\n")) {
            line = line.trim();
            if (line.startsWith("package:")) out.add(line.substring(8));
        }
        return out;
    }
}
