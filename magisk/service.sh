#!/system/bin/sh
MODDIR=${0%/*}
rm -f /data/local/tmp/opt_gamemode
sh $MODDIR/optimizer.sh boot > /data/local/tmp/optimizer_boot.log 2>&1 &
