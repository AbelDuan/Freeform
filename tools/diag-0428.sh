#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
PKG=com.ss.android.ugc.aweme
cp -f $R/dist/OS4FreeFromX-v0.4.28.apk /data/local/tmp/os4ffx.apk
chmod 644 /data/local/tmp/os4ffx.apk
pm install -r /data/local/tmp/os4ffx.apk 2>&1
killall com.android.systemui
sleep 16
SUI=$(pidof com.android.systemui)
echo "SystemUI=$SUI"
content call --uri content://com.abel.os4freeformx.store --method put \
  --extra k:s:"$PKG|1672x2364" --extra v:s:"0,397,1672,2069@0.66" >/dev/null 2>&1
am force-stop $PKG
sleep 3
am start --windowingMode 5 -n $PKG/.splash.SplashActivity 2>&1 | head -1
sleep 12
echo "=== MIUI 入口调用的真实失败原因 ==="
logcat -d 2>/dev/null | grep -F " $SUI " | grep -F OS4FreeFromX | grep -E '重开\(MIUI入口\)|重开:|位置/尺寸对齐|核对通过' | sed 's/.*OS4FreeFromX: //' | tail -12
echo "=== 当前 bounds ==="
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -2
