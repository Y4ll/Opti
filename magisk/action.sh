#!/system/bin/sh
# Boton "Accion" de Magisk: menu con teclas de volumen
MODDIR=${0%/*}
S=/data/local/tmp/opt_gamemode

echo "=============================="
echo "   ANDROID OPTIMIZER"
echo "=============================="
if [ -f $S ]; then
  echo " Modo juego: ACTIVO"
  echo " Vol +  = Optimizar todo"
  echo " Vol -  = APAGAR modo juego"
else
  echo " Modo juego: apagado"
  echo " Vol +  = Optimizar todo"
  echo " Vol -  = ENCENDER modo juego"
fi
echo " (15 s sin tocar = Optimizar todo)"
echo "------------------------------"

KEY=$(timeout 15 getevent -ql 2>/dev/null | grep -m1 -E 'KEY_VOLUME(UP|DOWN) +DOWN')

case "$KEY" in
  *VOLUMEDOWN*)
    if [ -f $S ]; then
      sh $MODDIR/optimizer.sh reset && rm -f $S
    else
      sh $MODDIR/optimizer.sh game && touch $S
    fi ;;
  *)
    sh $MODDIR/optimizer.sh ;;
esac
