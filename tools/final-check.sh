#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
echo "=== 当前自由窗口 bounds（应为 1532x1532 正方） ==="
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -3
echo "=== 模块版本 ==="
dumpsys package com.abel.os4freeformx 2>/dev/null | grep versionName | head -1
echo "=== 记忆 ==="
nsenter -t 1 -m -- cat /data/user/0/com.abel.os4freeformx/shared_prefs/os4freeformx_bounds.xml 2>&1 | head -8
screencap -p -d 4639175402683733248 $R/tools/shots/final-0432.png 2>/dev/null
chmod 644 $R/tools/shots/final-0432.png 2>/dev/null
ls -la $R/tools/shots/final-0432.png 2>/dev/null
