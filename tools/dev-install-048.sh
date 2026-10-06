#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
cp -f $R/dist/OS4FreeFromX-v0.4.8.apk /data/local/tmp/os4ffx.apk
chmod 644 /data/local/tmp/os4ffx.apk
echo "=== 覆盖安装 ==="
pm install -r /data/local/tmp/os4ffx.apk 2>&1
dumpsys package com.abel.os4freeformx 2>/dev/null | grep -E 'versionName|versionCode' | head -2
echo "=== 记忆文件（应保留） ==="
nsenter -t 1 -m -- cat /data/user/0/com.abel.os4freeformx/shared_prefs/os4freeformx_bounds.xml 2>&1
echo "=== 重启 SystemUI ==="
killall com.android.systemui
sleep 18
echo "systemui=$(pidof com.android.systemui)"
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E '共挂|手势功能已移除|不介入通知' | tail -3
