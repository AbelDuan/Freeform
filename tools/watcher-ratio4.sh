#!/system/bin/sh
# 点标题栏把手唤出菜单 → 按可视矩形算出的偏移扫比例按钮 → 命中即停
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
S=$R/tools/shots
OUT=$R/tools/ratio-run4.log
: > $OUT
logcat -c
sleep 1
logcat -v time -s OS4FreeFromX:V > $OUT 2>&1 &
LP=$!
# 可视矩形左上（来自模块"记录核对"）：left=650 top=563
VL=650
VT=563
HX=$(( VL + 386 ))   # 可视水平中心 = left + 772/2
HY=$(( VT + 28 ))
echo ">>> HANDLE $HX,$HY  $(date +%H:%M:%S)" >> $OUT
input tap $HX $HY
i=0
while [ $i -lt 150 ]; do
  grep -q '比例行已注入三点菜单' $OUT 2>/dev/null && break
  sleep 0.2; i=$((i+1))
done
if [ $i -ge 150 ]; then echo ">>> NO_MENU" >> $OUT; kill $LP 2>/dev/null; exit 0; fi
echo ">>> MENU_SEEN $(date +%H:%M:%S)" >> $OUT
sleep 0.5
for DX in 127 224 321; do
  X=$(( VL + DX ))
  Y=$(( VT + 239 ))
  echo ">>> TAP $X,$Y $(date +%H:%M:%S)" >> $OUT
  input tap $X $Y
  sleep 1.5
  grep -q '比例调整(比例' $OUT 2>/dev/null && { echo ">>> HIT $X,$Y" >> $OUT; break; }
done
sleep 8
screencap -p -d 4639175402683733248 $S/after-ratio.png
chmod 644 $S/*.png 2>/dev/null
kill $LP 2>/dev/null
echo ">>> DONE $(date +%H:%M:%S)" >> $OUT
