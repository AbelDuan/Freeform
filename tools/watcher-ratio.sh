#!/system/bin/sh
# 等三点菜单出现 → 截图(①取证) → 点 21:9(②计时) → 再截图
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
S=$R/tools/shots
mkdir -p $S
OUT=$R/tools/ratio-run.log
: > $OUT
logcat -c
sleep 1
logcat -v time -s OS4FreeFromX:V > $OUT 2>&1 &
LP=$!
echo ">>> WATCHER_START $(date +%H:%M:%S)" >> $OUT
i=0
while [ $i -lt 1500 ]; do
  if grep -q '比例行已注入三点菜单' $OUT 2>/dev/null; then break; fi
  sleep 0.2; i=$((i+1))
done
if [ $i -ge 1500 ]; then echo ">>> TIMEOUT_NO_MENU" >> $OUT; kill $LP 2>/dev/null; exit 0; fi
echo ">>> MENU_SEEN $(date +%H:%M:%S)" >> $OUT
screencap -p -d 4639175402683733248 $S/v047-menu.png
echo ">>> SHOT_BEFORE $(date +%H:%M:%S)" >> $OUT
sleep 0.4
input tap 675 295
echo ">>> TAP_219 $(date +%H:%M:%S)" >> $OUT
sleep 1
screencap -p -d 4639175402683733248 $S/v047-tap1.png
sleep 9
screencap -p -d 4639175402683733248 $S/v047-after.png
kill $LP 2>/dev/null
echo ">>> DONE $(date +%H:%M:%S)" >> $OUT
chmod 644 $S/*.png 2>/dev/null
ls -la $S >> $OUT 2>&1
