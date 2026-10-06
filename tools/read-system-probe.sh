#!/system/bin/sh
echo "=== 开机完成 ==="
getprop sys.boot_completed
echo "=== 模块版本 / SystemUI ==="
dumpsys package com.abel.os4freeformx 2>/dev/null | grep versionName | head -1
pidof com.android.systemui
echo "=== system_server 侧探针清单 ==="
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E '系统侧探针|^.*   [a-zA-Z]+\(' | sed 's/.*OS4FreeFromX: //' | head -70
