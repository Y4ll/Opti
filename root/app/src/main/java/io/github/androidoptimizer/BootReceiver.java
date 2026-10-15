package io.github.androidoptimizer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Si el usuario lo activó, reaplica los ajustes de memoria y almacenamiento al arrancar. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        SharedPreferences sp = context.getSharedPreferences("opt", Context.MODE_PRIVATE);
        if (!sp.getBoolean("boot", false)) return;
        final PendingResult pr = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    RootShell.runMode(context.getApplicationContext(), "tune", new RootShell.LineCallback() {
                        @Override
                        public void onLine(String line) { /* sin salida */ }
                    });
                } finally {
                    pr.finish();
                }
            }
        }).start();
    }
}
