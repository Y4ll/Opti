package io.github.androidoptimizer.noroot;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.Process;
import android.os.StatFs;
import android.provider.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Todo lo que se puede hacer sin root: estado, cerrar apps en segundo plano, buscar y borrar archivos. */
public class Booster {

    /** Una fila de las pantallas de revisión: archivo o app. */
    public static class Item {
        public String title;
        public String subtitle;
        public long size;
        public boolean checked;
        public File file;
        public String pkg;
        /** Para duplicados: archivos con el mismo contenido comparten grupo. */
        public int group = -1;
    }

    public static class Junk {
        public final List<File> temp = new ArrayList<File>();
        public final List<File> thumbs = new ArrayList<File>();
        public final List<File> apks = new ArrayList<File>();
        public long tempSize, thumbsSize, apkSize;

        public boolean isEmpty() {
            return temp.isEmpty() && thumbs.isEmpty() && apks.isEmpty();
        }
    }

    public static class AppRow {
        public String pkg;
        public String label;
        /** Última vez que se usó: -1 si no hay permiso de uso, 0 si no se usó en el periodo medido. */
        public long lastUsed;
        public long installed;
        public long apkSize;
        public boolean game;
    }

    private interface Visitor {
        /** Devuelve false para detener el recorrido. */
        boolean visit(File f);
    }

    // ---------- Estado ----------

    private static ActivityManager.MemoryInfo mem(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi;
    }

    public static long availRam(Context c) {
        return mem(c).availMem;
    }

    public static long totalRam(Context c) {
        return mem(c).totalMem;
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

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /** Puntuación orientativa de 0 a 100: RAM libre, espacio libre y temperatura. */
    public static int score(Context c) {
        long rt = totalRam(c);
        float ramPct = rt > 0 ? availRam(c) * 100f / rt : 50f;
        long st = totalStorage();
        float stPct = st > 0 ? freeStorage() * 100f / st : 50f;
        float temp = battery(c)[1] / 10f;
        float ramS = clamp01((ramPct - 10f) / 40f);
        float stS = clamp01((stPct - 5f) / 25f);
        float tS = temp <= 0f ? 1f : clamp01((45f - temp) / 10f);
        return Math.round(100f * (0.4f * ramS + 0.4f * stS + 0.2f * tS));
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

    private static void walk(File dir, int depth, Visitor v, boolean[] stop) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (stop[0]) return;
            if (f.isDirectory()) {
                // Android/data y Android/obb son datos de otras apps: no se tocan
                if (depth == 0 && f.getName().equals("Android")) continue;
                if (depth < 12) walk(f, depth + 1, v, stop);
            } else if (!v.visit(f)) {
                stop[0] = true;
                return;
            }
        }
    }

    private static void walkAll(Visitor v) {
        walk(Environment.getExternalStorageDirectory(), 0, v, new boolean[]{false});
    }

    public static String rel(File f) {
        String root = Environment.getExternalStorageDirectory().getAbsolutePath();
        String p = f.getAbsolutePath();
        return p.startsWith(root) ? p.substring(root.length() + 1) : p;
    }

    // ----- Basura -----

    public static Junk scanJunk() {
        final Junk j = new Junk();
        walkAll(new Visitor() {
            public boolean visit(File f) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (n.endsWith(".apk")) {
                    j.apks.add(f);
                } else if (n.startsWith(".thumbdata") || n.startsWith("thumbdata")
                        || (f.getParentFile() != null && f.getParentFile().getName().equals(".thumbnails"))) {
                    j.thumbs.add(f);
                } else if (n.endsWith(".tmp") || n.endsWith(".temp") || n.endsWith(".log") || n.endsWith(".dmp")) {
                    j.temp.add(f);
                }
                return true;
            }
        });
        j.tempSize = sum(j.temp);
        j.thumbsSize = sum(j.thumbs);
        j.apkSize = sum(j.apks);
        return j;
    }

    private static long sum(List<File> files) {
        long s = 0;
        for (File f : files) s += f.length();
        return s;
    }

    public static List<Item> junkItems(Junk j) {
        List<Item> out = new ArrayList<Item>();
        addAll(out, j.temp, "Temporal", true);
        addAll(out, j.thumbs, "Miniatura", true);
        addAll(out, j.apks, "APK", false);
        sortBySize(out);
        return out;
    }

    private static void addAll(List<Item> out, List<File> files, String kind, boolean checked) {
        for (File f : files) {
            Item it = new Item();
            it.file = f;
            it.title = f.getName();
            it.subtitle = kind + "  ·  " + rel(f);
            it.size = f.length();
            it.checked = checked;
            out.add(it);
        }
    }

    private static void sortBySize(List<Item> items) {
        Collections.sort(items, new Comparator<Item>() {
            public int compare(Item a, Item b) {
                return Long.compare(b.size, a.size);
            }
        });
    }

    // ----- Archivos grandes -----

    public static List<Item> findLarge(final long minBytes, int max) {
        final List<Item> all = new ArrayList<Item>();
        walkAll(new Visitor() {
            public boolean visit(File f) {
                long len = f.length();
                if (len >= minBytes) {
                    Item it = new Item();
                    it.file = f;
                    it.title = f.getName();
                    it.subtitle = rel(f);
                    it.size = len;
                    all.add(it);
                }
                return true;
            }
        });
        sortBySize(all);
        return all.size() > max ? new ArrayList<Item>(all.subList(0, max)) : all;
    }

    // ----- Duplicados -----

    private static String md5(File f) {
        InputStream in = null;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            in = new FileInputStream(f);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** Busca archivos con el mismo contenido. El más antiguo de cada grupo queda sin marcar. */
    public static List<Item> findDuplicates(final long minBytes, long budgetMs) {
        final Map<Long, List<File>> bySize = new HashMap<Long, List<File>>();
        final int[] seen = {0};
        walkAll(new Visitor() {
            public boolean visit(File f) {
                seen[0]++;
                long len = f.length();
                if (len >= minBytes && len <= 500L * 1024 * 1024) {
                    List<File> l = bySize.get(len);
                    if (l == null) {
                        l = new ArrayList<File>();
                        bySize.put(len, l);
                    }
                    l.add(f);
                }
                return seen[0] < 60000;
            }
        });

        long deadline = System.currentTimeMillis() + budgetMs;
        List<List<File>> groups = new ArrayList<List<File>>();
        for (Map.Entry<Long, List<File>> e : bySize.entrySet()) {
            if (e.getValue().size() < 2) continue;
            if (System.currentTimeMillis() > deadline) break;
            Map<String, List<File>> byHash = new HashMap<String, List<File>>();
            for (File f : e.getValue()) {
                String h = md5(f);
                if (h == null) continue;
                List<File> l = byHash.get(h);
                if (l == null) {
                    l = new ArrayList<File>();
                    byHash.put(h, l);
                }
                l.add(f);
            }
            for (List<File> g : byHash.values()) {
                if (g.size() >= 2) groups.add(g);
            }
        }
        Collections.sort(groups, new Comparator<List<File>>() {
            public int compare(List<File> a, List<File> b) {
                return Long.compare(b.get(0).length(), a.get(0).length());
            }
        });

        List<Item> items = new ArrayList<Item>();
        int gi = 0;
        for (List<File> g : groups) {
            Collections.sort(g, new Comparator<File>() {
                public int compare(File a, File b) {
                    return Long.compare(a.lastModified(), b.lastModified());
                }
            });
            for (int i = 0; i < g.size(); i++) {
                Item it = new Item();
                it.file = g.get(i);
                it.title = it.file.getName();
                it.subtitle = (i == 0 ? "ORIGINAL  ·  " : "COPIA  ·  ") + rel(it.file);
                it.size = it.file.length();
                it.group = gi;
                it.checked = i != 0;
                items.add(it);
            }
            gi++;
        }
        return items;
    }

    /** Borra los archivos marcados y devuelve los bytes liberados. */
    public static long delete(List<Item> items) {
        long freed = 0;
        for (Item it : items) {
            if (it.file != null && it.file.delete()) freed += it.size;
        }
        return freed;
    }

    // ---------- Apps ----------

    public static boolean hasUsageAccess(Context c) {
        AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
        int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private static Map<String, Long> lastUsedMap(Context c) {
        Map<String, Long> out = new HashMap<String, Long>();
        UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return out;
        long now = System.currentTimeMillis();
        Map<String, UsageStats> m = usm.queryAndAggregateUsageStats(now - 180L * 86400000L, now);
        if (m == null) return out;
        for (Map.Entry<String, UsageStats> e : m.entrySet()) {
            out.put(e.getKey(), e.getValue().getLastTimeUsed());
        }
        return out;
    }

    @SuppressWarnings("deprecation")
    private static boolean isGame(ApplicationInfo ai) {
        if (Build.VERSION.SDK_INT >= 26) return ai.category == ApplicationInfo.CATEGORY_GAME;
        return (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0;
    }

    /** Apps instaladas por el usuario que se pueden abrir. */
    public static List<AppRow> listApps(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, Long> used = hasUsageAccess(c) ? lastUsedMap(c) : null;
        List<AppRow> out = new ArrayList<AppRow>();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            if (ai.packageName.equals(c.getPackageName())) continue;
            if (pm.getLaunchIntentForPackage(ai.packageName) == null) continue;
            AppRow r = new AppRow();
            r.pkg = ai.packageName;
            r.label = String.valueOf(pm.getApplicationLabel(ai));
            try {
                PackageInfo pi = pm.getPackageInfo(ai.packageName, 0);
                r.installed = pi.firstInstallTime;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            r.apkSize = ai.sourceDir == null ? 0 : new File(ai.sourceDir).length();
            if (used == null) {
                r.lastUsed = -1;
            } else {
                Long t = used.get(ai.packageName);
                r.lastUsed = t == null ? 0 : t;
            }
            r.game = isGame(ai);
            out.add(r);
        }
        return out;
    }
}
