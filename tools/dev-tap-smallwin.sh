#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
input tap 741 1052
sleep 5
echo "=== 自由窗口任务 ==="
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' | head -3
echo "=== 顶层 ==="
dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity' | head -2
screencap -p -d 4639175402683733248 $R/tools/shots/win-open.png
chmod 644 $R/tools/shots/win-open.png
ls -la $R/tools/shots/win-open.png
