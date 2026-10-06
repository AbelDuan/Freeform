#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
cp -f $R/dist/OS4FreeFromX-v0.4.20.apk /data/local/tmp/os4ffx.apk
chmod 644 /data/local/tmp/os4ffx.apk
pm install -r /data/local/tmp/os4ffx.apk 2>&1
dumpsys package com.abel.os4freeformx 2>/dev/null | grep -E 'versionName' | head -1
killall com.android.systemui
sleep 15
echo "systemui=$(pidof com.android.systemui)"
