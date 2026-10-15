#!/system/bin/sh
# Optimizador Android con ROOT - un solo archivo, todo automático.
# Uso (Termux o adb shell):
#   su -c "sh /sdcard/optimizer_root.sh"          -> optimización completa
#   su -c "sh /sdcard/optimizer_root.sh game"     -> modo juego (CPU al máximo, temporal)
#   su -c "sh /sdcard/optimizer_root.sh reset"    -> restaura valores originales
# Todo es temporal (se pierde al reiniciar) salvo las animaciones.

BK=/data/local/tmp/opt_backup
mkdir -p $BK

[ "$(id -u)" != "0" ] && { echo "Ejecuta como root: su -c 'sh $0'"; exit 1; }

say() { echo "▶ $*"; }
# escribe solo si el archivo existe y es escribible
w() { [ -w "$2" ] && echo "$1" > "$2" 2>/dev/null; }
# guarda el valor original una sola vez
save() {
  n="$(echo $1 | tr / %)"
  [ -r "$1" ] && [ ! -f "$BK/$n" ] && cat "$1" > "$BK/$n" 2>/dev/null
}

free_mb() { awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo; }

clean() {
  say "RAM libre antes: $(free_mb) MB"
  say "Limpiando caché de apps..."
  pm trim-caches 999G >/dev/null 2>&1
  rm -rf /data/data/*/cache/* /data/data/*/code_cache/* 2>/dev/null
  rm -rf /data/user_de/0/*/cache/* 2>/dev/null
  rm -rf /sdcard/Android/data/*/cache/* 2>/dev/null
  say "Borrando logs, tombstones y temporales..."
  rm -rf /data/anr/* /data/tombstones/* /data/local/tmp/*.tmp /cache/* 2>/dev/null
  rm -rf /data/system/dropbox/* /data/system/usagestats/*.tmp 2>/dev/null
  logcat -c 2>/dev/null
  say "Cerrando procesos en segundo plano..."
  am kill-all 2>/dev/null
  say "Liberando caché de kernel y compactando memoria..."
  sync
  w 3 /proc/sys/vm/drop_caches
  w 1 /proc/sys/vm/compact_memory
  say "RAM libre después: $(free_mb) MB"
}

tune_vm() {
  say "Ajustando memoria virtual..."
  for f in /proc/sys/vm/swappiness /proc/sys/vm/vfs_cache_pressure /proc/sys/vm/dirty_ratio /proc/sys/vm/dirty_background_ratio; do save $f; done
  w 60 /proc/sys/vm/swappiness
  w 80 /proc/sys/vm/vfs_cache_pressure
  w 20 /proc/sys/vm/dirty_ratio
  w 5  /proc/sys/vm/dirty_background_ratio
}

tune_io() {
  say "Ajustando lectura de almacenamiento..."
  for q in /sys/block/*/queue; do
    case "$q" in */loop*|*/ram*|*/zram*) continue;; esac
    save $q/read_ahead_kb
    w 512 $q/read_ahead_kb
    save $q/nr_requests
    w 128 $q/nr_requests
  done
  say "TRIM de particiones..."
  for p in /data /cache /system /vendor /product; do fstrim -v $p 2>/dev/null; done
  sm fstrim 2>/dev/null
}

speed() {
  say "Recompilando apps (speed-profile)..."
  cmd package compile -m speed-profile -a >/dev/null 2>&1
  cmd package bg-dexopt-job >/dev/null 2>&1
}

anim() {
  say "Animaciones a 0.5x..."
  settings put global window_animation_scale 0.5
  settings put global transition_animation_scale 0.5
  settings put global animator_duration_scale 0.5
}

game() {
  say "MODO JUEGO (temporal)..."
  clean
  for g in /sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_governor; do
    save $g
    w performance $g
  done
  for t in /sys/class/kgsl/kgsl-3d0/devfreq/governor; do save $t; w performance $t; done
  w 0 /sys/module/lowmemorykiller/parameters/enable_adaptive_lmk
  say "Listo. Abre tu juego. Revierte con: sh $0 reset"
  say "Aviso: consume más batería y calienta más."
}

reset() {
  say "Restaurando valores originales..."
  for f in $BK/*; do
    [ -f "$f" ] || continue
    path="$(basename $f | tr % /)"
    [ -w "$path" ] && cat "$f" > "$path" 2>/dev/null
  done
  settings put global window_animation_scale 1
  settings put global transition_animation_scale 1
  settings put global animator_duration_scale 1
  say "Hecho."
}

case "$1" in
  boot)  sleep 30; tune_vm; tune_io ;;
  game)  game ;;
  reset) reset ;;
  *)     clean; tune_vm; tune_io; anim; speed; say "✔ Optimización completa." ;;
esac
