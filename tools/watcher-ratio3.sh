#!/system/bin/sh
# 全自动：点标题栏三点把手唤出菜单 → 扫描坐标点比例按钮 → 命中即停
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
S=$R/tools/shots
mkdir -p $S
OUT=$R/tools/ratio-run3.log
: > $OUT
logcat -c
sleep 1
logcat -v time -s OS4FreeFromX:V > $OUT 2>&1 &
LP=$!
echo ">>> START $(date +%H:%M:%S)" >> $OUT
input tap 717 160
echo ">>> TAP_HANDLE $(date +%H:%M:%S)" >> $OUT
i=0
while [ $i -lt 200 ]; do
  grep -q '比例行已注入三点菜单' $OUT 2>/dev/null && break
  sleep 0.2; i=$((i+1))
done
if [ $i -ge 200 ]; then echo ">>> NO_MENU_AFTER_HANDLE" >> $OUT; fi
screencap -p -d 4639175402683733248 $S/menu3.png
for Y in 369 360 380 350; do
  for X in 542 639 736; do
    echo ">>> TAP $X,$Y $(date +%H:%M:%S)" >> $OUT
    input tap $X $Y
    sleep 1.5
    grep -q '比例调整(比例' $OUT 2>/dev/null && { echo ">>> HIT $X,$Y" >> $OUT; break 2; }
  done
done
sleep 8
screencap -p -d 4639175402683733248 $S/ratio-applied3.png
kill $LP 2>/dev/null
echo ">>> DONE $(date +%H:%M:%S)" >> $OUT
chmod 644 $S/*.png 2>/dev/null
