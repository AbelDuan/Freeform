#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
cp -f $R/dist/OS4FreeFromX-v0.4.17.apk /data/local/tmp/os4ffx.apk
chmod 644 /data/local/tmp/os4ffx.apk
pm install -r /data/local/tmp/os4ffx.apk 2>&1
killall com.android.systemui
sleep 18
SUI=$(pidof com.android.systemui)
echo "SystemUI=$SUI"
logcat -d 2>/dev/null | grep -F " $SUI " | grep -F OS4FreeFromX | grep -E '尺寸链|^.*   [a-zA-Z]+' | grep -E '尺寸链|   ' | head -80
