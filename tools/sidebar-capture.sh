#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
OUT=$R/tools/sidebar-test.txt
: > $OUT
chmod 666 $OUT 2>/dev/null
logcat -c
sleep 1
logcat -v time -s OS4FreeFromX:V > $OUT 2>&1 &
LP=$!
echo ">>> CAPTURE_START $(date +%H:%M:%S)" >> $OUT
i=0
while [ $i -lt 50 ]; do
  echo "--- t=$((i*2))s $(date +%H:%M:%S) bounds:" >> $OUT
  dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -3 >> $OUT
  sleep 2; i=$((i+1))
done
kill $LP 2>/dev/null
echo ">>> DONE $(date +%H:%M:%S)" >> $OUT
chmod 666 $OUT 2>/dev/null
