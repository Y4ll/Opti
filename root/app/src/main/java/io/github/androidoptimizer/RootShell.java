package io.github.androidoptimizer;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

/** Ejecuta el script del motor de optimización como root. */
public class RootShell {

    public interface LineCallback {
        void onLine(String line);
    }

    public static boolean hasRoot() {
        try {
            Process p = new ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            p.waitFor();
            return line != null && line.trim().equals("0");
        } catch (Exception e) {
            return false;
        }
    }

    /** Copia assets/optimizer.sh a la carpeta interna de la app y devuelve su ruta. */
    public static String extractScript(Context ctx) throws Exception {
        File out = new File(ctx.getFilesDir(), "optimizer.sh");
        try (InputStream in = ctx.getAssets().open("optimizer.sh");
             OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        out.setReadable(true, false);
        return out.getAbsolutePath();
    }

    /** Ejecuta un modo del script (full, clean, speed, tune, net, anim, game, reset, status). */
    public static int runMode(Context ctx, String mode, LineCallback cb) {
        try {
            String path = extractScript(ctx);
            Process p = new ProcessBuilder("su", "-c", "sh '" + path + "' " + mode)
                    .redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) cb.onLine(line);
            return p.waitFor();
        } catch (Exception e) {
            cb.onLine("Error: " + e.getMessage());
            return -1;
        }
    }
}
