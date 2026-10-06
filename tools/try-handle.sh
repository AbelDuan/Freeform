#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
OUT=$R/tools/handle-try.log
: > $OUT
logcat -c
sleep 1
echo "--- 长按把手 (1036,591) ---"
input swipe 1036 591 1036 591 1000
sleep 2
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E '比例行已注入' | tail -2 >> $OUT
echo "--- 双击把手 ---"
input tap 1036 591; sleep 0.12; input tap 1036 591
sleep 2
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E '比例行已注入' | tail -2 >> $OUT
echo "--- 长按可视中心稍下 (1036,620) ---"
input swipe 1036 620 1036 620 1000
sleep 2
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E '比例行已注入' | tail -2 >> $OUT
echo "=== 结果 ==="
wc -l < $OUT
cat $OUT
echo "=== 截图 ==="
screencap -p -d 4639175402683733248 $R/tools/shots/handle-try.png
chmod 644 $R/tools/shots/handle-try.png 2>/dev/null
