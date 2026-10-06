#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
OUT=$R/tools/live-capture.txt
: > $OUT
chmod 666 $OUT 2>/dev/null
logcat -c
sleep 1
logcat -v time -s OS4FreeFromX:V > $OUT 2>&1 &
LP=$!
echo ">>> LIVE_START $(date +%H:%M:%S) —— 请现在旋转小窗" >> $OUT
i=0
while [ $i -lt 120 ]; do
  echo "--- t=$i s $(date +%H:%M:%S)" >> $OUT
  dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -2 >> $OUT
  sleep 1; i=$((i+1))
done
kill $LP 2>/dev/null
echo ">>> LIVE_DONE $(date +%H:%M:%S)" >> $OUT
chmod 666 $OUT 2>/dev/null
