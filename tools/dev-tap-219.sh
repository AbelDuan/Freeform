#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
echo "=== 点击 21:9 ==="
input tap 675 295
sleep 7
echo "=== 模块日志（带时间戳，比例链路） ==="
logcat -d -v time 2>/dev/null | grep -F OS4FreeFromX | grep -vE 'TRACE|SplitTrace' | tail -26
echo "=== 自由窗口 mBounds ==="
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A1 | grep -oE 'mBounds=Rect\([^)]*\)' | head -3
echo "=== 截图 ==="
screencap -p -d 4639175402683733248 $R/build/after219.png
chmod 644 $R/build/after219.png
ls -la $R/build/after219.png
