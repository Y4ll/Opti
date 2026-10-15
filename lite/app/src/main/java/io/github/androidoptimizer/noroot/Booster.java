package io.github.androidoptimizer.noroot;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.BatteryManager;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Todo lo que se puede hacer sin root: estado, cerrar apps en segundo plano y borrar basura. */
public class Booster {

    public static class Junk {
        public final List<File> temp = new ArrayList<File>();
        public final List<File> thumbs = new ArrayList<File>();
        public final List<File> apks = new ArrayList<File>();
        public long tempSize, thumbsSize, apkSize;

        public boolean isEmpty() {
            return temp.isEmpty() && thumbs.isEmpty() && apks.isEmpty();
        }
    }

    // ---------- Estado ----------

    public static long availRam(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem;
    }

    public static long totalRam(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.totalMem;
    }

    public static long freeStorage() {
        return new StatFs(Environment.getDataDirectory().getPath()).getAvailableBytes();
    }

    public static long totalStorage() {
        return new StatFs(Environment.getDataDirectory().getPath()).getTotalBytes();
    }

    /** Devuelve {nivel %, temperatura en décimas de °C}. */
    public static int[] battery(Context c) {
        Intent i = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i == null) return new int[]{-1, 0};
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
        int pct = (level >= 0 && scale > 0) ? Math.round(level * 100f / scale) : -1;
        return new int[]{pct, temp};
    }

    // ---------- RAM ----------

    /**
     * Pide a Android que cierre los procesos en segundo plano de las apps instaladas por el usuario.
     * No toca apps del sistema, el lanzador, el teclado ni esta app.
     */
    public static int killBackground(Context c) {
        PackageManager pm = c.getPackageManager();
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);

        Set<String> skip = new HashSet<String>();
        skip.add(c.getPackageName());

        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        if (ri != null && ri.activityInfo != null) skip.add(ri.activityInfo.packageName);

        String ime = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (ime != null) {
            ComponentName cn = ComponentName.unflattenFromString(ime);
            if (cn != null) skip.add(cn.getPackageName());
        }

        int n = 0;
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            if (skip.contains(ai.packageName)) continue;
            am.killBackgroundProcesses(ai.packageName);
            n++;
        }
        return n;
    }

    // ---------- Archivos ----------

    public static void cleanOwnCache(Context c) {
        deleteChildren(c.getCacheDir());
        File ext = c.getExternalCacheDir();
        if (ext != null) deleteChildren(ext);
    }

    private static void deleteChildren(File dir) {
        File[] kids = dir == null ? null : dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) deleteChildren(f);
            f.delete();
        }
    }

    public static Junk scanJunk() {
        Junk j = new Junk();
        scan(Environment.getExternalStorageDirectory(), j, 0);
        j.tempSize = sum(j.temp);
        j.thumbsSize = sum(j.thumbs);
        j.apkSize = sum(j.apks);
        return j;
    }

    private static void scan(File dir, Junk j, int depth) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            String name = f.getName();
            if (f.isDirectory()) {
                // Android/data y Android/obb son datos de otras apps: no se tocan
                if (depth == 0 && name.equals("Android")) continue;
                if (name.equals(".thumbnails")) {
                    collectFiles(f, j.thumbs, 0);
                } else if (depth < 10) {
                    scan(f, j, depth + 1);
                }
            } else {
                String n = name.toLowerCase(Locale.ROOT);
                if (n.endsWith(".apk")) {
                    j.apks.add(f);
                } else if (n.startsWith(".thumbdata") || n.startsWith("thumbdata")) {
                    j.thumbs.add(f);
                } else if (n.endsWith(".tmp") || n.endsWith(".temp") || n.endsWith(".log") || n.endsWith(".dmp")) {
                    j.temp.add(f);
                }
            }
        }
    }

    private static void collectFiles(File dir, List<File> out, int depth) {
        File[] kids = dir.listFiles();
        if (kids == null || depth > 5) return;
        for (File f : kids) {
            if (f.isDirectory()) collectFiles(f, out, depth + 1);
            else out.add(f);
        }
    }

    private static long sum(List<File> files) {
        long s = 0;
        for (File f : files) s += f.length();
        return s;
    }

    /** Borra los archivos y devuelve los bytes liberados. */
    public static long delete(List<File> files) {
        long freed = 0;
        for (File f : files) {
            long len = f.length();
            if (f.delete()) freed += len;
        }
        return freed;
    }
}
