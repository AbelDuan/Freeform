#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
screencap -p -d 4639175402683733248 $R/build/now2.png
chmod 644 $R/build/now2.png
ls -la $R/build/now2.png
echo "=== 在屏窗口（含自由窗口） ==="
dumpsys window windows 2>/dev/null | grep -E 'Window #|isOnScreen=true|mode=freeform' | grep -B1 -E 'isOnScreen=true' | grep -E 'Window #|freeform|aweme' | head -30
echo "=== 自由窗口任务 ==="
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' | head -5
