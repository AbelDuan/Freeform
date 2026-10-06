#!/system/bin/sh
# 等三点菜单出现 → 依次尝试 21:9 / 17.5:9 / 16:9 坐标，命中即停 → 取证
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
S=$R/tools/shots
mkdir -p $S
OUT=$R/tools/ratio-run2.log
: > $OUT
logcat -c
sleep 1
logcat -v time -s OS4FreeFromX:V > $OUT 2>&1 &
LP=$!
echo ">>> START $(date +%H:%M:%S)" >> $OUT
i=0
while [ $i -lt 1500 ]; do
  grep -q '比例行已注入三点菜单' $OUT 2>/dev/null && break
  sleep 0.2; i=$((i+1))
done
if [ $i -ge 1500 ]; then echo ">>> TIMEOUT" >> $OUT; kill $LP 2>/dev/null; exit 0; fi
echo ">>> MENU $(date +%H:%M:%S)" >> $OUT
sleep 0.5
for X in 542 639 736; do
  echo ">>> TAP x=$X $(date +%H:%M:%S)" >> $OUT
  input tap $X 369
  sleep 2.5
  if grep -q '比例调整(比例' $OUT 2>/dev/null; then echo ">>> HIT x=$X" >> $OUT; break; fi
done
sleep 8
screencap -p -d 4639175402683733248 $S/v047-ratio-applied.png
chmod 644 $S/*.png 2>/dev/null
kill $LP 2>/dev/null
echo ">>> DONE $(date +%H:%M:%S)" >> $OUT
