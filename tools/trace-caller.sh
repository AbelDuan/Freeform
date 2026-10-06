#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
PKG=com.ss.android.ugc.aweme
cp -f $R/dist/OS4FreeFromX-v0.4.31.apk /data/local/tmp/os4ffx.apk
chmod 644 /data/local/tmp/os4ffx.apk
pm install -r /data/local/tmp/os4ffx.apk 2>&1
killall com.android.systemui
sleep 16
SUI=$(pidof com.android.systemui)
echo "SystemUI=$SUI"
wm user-rotation lock 0 2>&1
sleep 4
am force-stop $PKG
sleep 3
am start --windowingMode 5 -n $PKG/.splash.SplashActivity 2>&1 | head -1
sleep 12
wm user-rotation lock 1 2>&1
sleep 14
echo "=== 调用栈（谁在用它算出的矩形） ==="
logcat -d 2>/dev/null | grep -F " $SUI " | grep -F OS4FreeFromX | grep -E '小窗 bounds 调用栈' | sed 's/.*OS4FreeFromX: //' | tail -8
echo "=== 旋转后 bounds ==="
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -2
wm user-rotation lock 0 2>&1
sleep 3
wm user-rotation free 2>&1
