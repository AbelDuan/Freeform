#!/system/bin/sh
echo "--- 当前自由窗口 bounds ---"
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -2
echo "--- 最近模块记录 ---"
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E '核对通过|记录核对|配置变更|不重开|比例调整' | sed 's/.*OS4FreeFromX: //' | tail -6
echo "--- 记忆现状（@1.0 是旧的坏值） ---"
nsenter -t 1 -m -- cat /data/user/0/com.abel.os4freeformx/shared_prefs/os4freeformx_bounds.xml 2>&1 | grep -E 'string name'
