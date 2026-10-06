#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
S=$R/tools/shots
OUT=$R/tools/menu5.log
: > $OUT
logcat -c
sleep 1
logcat -v time -s OS4FreeFromX:V > $OUT 2>&1 &
LP=$!
echo ">>> TAP_HANDLE 921,160 $(date +%H:%M:%S)" >> $OUT
input tap 921 160
i=0
while [ $i -lt 100 ]; do
  grep -q '比例行已注入三点菜单' $OUT 2>/dev/null && break
  sleep 0.2; i=$((i+1))
done
if [ $i -ge 100 ]; then echo ">>> NO_MENU_921_160" >> $OUT; fi
screencap -p -d 4639175402683733248 $S/menu5.png
chmod 644 $S/menu5.png 2>/dev/null
kill $LP 2>/dev/null
echo ">>> DONE" >> $OUT
grep -E '>>>|比例行已注入' $OUT | tail -6
