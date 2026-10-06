#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
S=$R/tools/shots
echo "=== 屏幕 ==="
dumpsys window 2>/dev/null | grep -m1 isKeyguardShowing=
dumpsys power 2>/dev/null | grep -m1 mWakefulness=
echo "=== 当前自由窗口 ==="
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -2
echo "=== v0.4.36 的延迟套用日志（含 removeTask/全新打开） ==="
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E 'removeTask|全新打开|延迟套用|核对通过' | sed 's/.*OS4FreeFromX: //' | tail -8
echo "=== 版本 ==="
dumpsys package com.abel.os4freeformx 2>/dev/null | grep versionName | head -1
input keyevent KEYCODE_WAKEUP
sleep 1
screencap -p $S/final2.png 2>/dev/null; chmod 644 $S/final2.png 2>/dev/null
ls -la $S/final2.png 2>/dev/null
