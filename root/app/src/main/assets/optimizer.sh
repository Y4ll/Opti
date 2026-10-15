#!/system/bin/sh
# Motor de optimización (se ejecuta como root desde la app o desde terminal).
# Modos: full | clean | speed | tune | net | anim | game | reset | status
# Todo se revierte con "reset"; los ajustes de kernel se pierden al reiniciar.

BK=/data/local/tmp/opt_backup
mkdir -p $BK

[ "$(id -u)" != "0" ] && { echo "Se necesita root."; exit 1; }

say() { echo "▶ $*"; }
# escribe solo si el archivo existe y es escribible
w() { [ -w "$2" ] && echo "$1" > "$2" 2>/dev/null; }
# guarda el valor original una sola vez
save() {
  n="$(echo $1 | tr / %)"
  [ -r "$1" ] && [ ! -f "$BK/$n" ] && cat "$1" > "$BK/$n" 2>/dev/null
}

free_mb() { awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo; }
total_mb() { awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo; }

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

tune_net() {
  c=/proc/sys/net/ipv4/tcp_congestion_control
  a=/proc/sys/net/ipv4/tcp_available_congestion_control
  save $c
  if [ -r $a ] && grep -qw bbr $a; then
    w bbr $c
    say "Red: control de congestión TCP = bbr"
  else
    say "Red: bbr no disponible en tu kernel, se omite"
  fi
}

speed() {
  say "Recompilando apps (speed-profile), puede tardar varios minutos..."
  cmd package compile -m speed-profile -a >/dev/null 2>&1
  cmd package bg-dexopt-job >/dev/null 2>&1
  say "Mantenimiento en reposo del sistema..."
  cmd activity idle-maintenance >/dev/null 2>&1
  cmd package art cleanup >/dev/null 2>&1
}

anim() {
  say "Animaciones a 0.5x y sin desenfoque de ventanas..."
  settings put global window_animation_scale 0.5
  settings put global transition_animation_scale 0.5
  settings put global animator_duration_scale 0.5
  settings put global disable_window_blurs 1 2>/dev/null
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
  say "Listo. Abre tu juego. Aviso: consume más batería y calienta más."
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
  settings put global disable_window_blurs 0 2>/dev/null
  say "Hecho."
}

status() {
  say "RAM libre: $(free_mb) MB de $(total_mb) MB"
  say "Swap usado: $(awk '/SwapTotal/ {t=$2} /SwapFree/ {f=$2} END {print int((t-f)/1024)}' /proc/meminfo) MB"
  say "Almacenamiento /data: $(df -h /data 2>/dev/null | awk 'NR==2 {print $3" usados de "$2" ("$5")"}')"
  say "Batería: $(dumpsys battery 2>/dev/null | awk '/level/ {l=$2} /temperature/ {t=$2/10} END {print l"%  "t" °C"}')"
  g=/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor
  [ -r $g ] && say "CPU governor: $(cat $g)"
  [ -r /proc/sys/net/ipv4/tcp_congestion_control ] && say "TCP: $(cat /proc/sys/net/ipv4/tcp_congestion_control)"
}

case "$1" in
  clean)  clean ;;
  speed)  speed ;;
  tune)   tune_vm; tune_io ;;
  net)    tune_net ;;
  anim)   anim ;;
  game)   game ;;
  reset)  reset ;;
  status) status ;;
  full|*) clean; tune_vm; tune_io; tune_net; anim; speed; say "✔ Optimización completa."; status ;;
esac
