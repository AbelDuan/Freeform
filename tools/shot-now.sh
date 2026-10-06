#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
S=$R/tools/shots
echo "=== 当前自由窗口（真实/可视/scale 以模块记录为准） ==="
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E '记录核对' | sed 's/.*OS4FreeFromX: //' | tail -3
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -2
echo "=== 屏幕状态 ==="
dumpsys display 2>/dev/null | grep -oE 'uniqueId="local:[0-9]+", [0-9]+ x [0-9]+|state (ON|OFF)' | head -4
screencap -p $S/now-user.png 2>/dev/null
chmod 644 $S/now-user.png 2>/dev/null
ls -la $S/now-user.png 2>/dev/null
