#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
PKG=com.ss.android.ugc.aweme
cp -f $R/dist/OS4FreeFromX-v0.4.33.apk /data/local/tmp/os4ffx.apk
chmod 644 /data/local/tmp/os4ffx.apk
pm install -r /data/local/tmp/os4ffx.apk 2>&1
killall com.android.systemui
sleep 16
SUI=$(pidof com.android.systemui)
echo "SystemUI=$SUI"
content call --uri content://com.abel.os4freeformx.store --method put \
  --extra k:s:"$PKG|1672x2364" --extra v:s:"0,397,1672,2069@0.66" >/dev/null 2>&1
wm user-rotation lock 0 2>&1
sleep 5
am force-stop $PKG
sleep 3
am start --windowingMode 5 -n $PKG/.splash.SplashActivity 2>&1 | head -1
sleep 13
echo "--- 开窗后（期望经对账纠正为 1532x1532） ---"
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -1
echo "--- 旋转到横屏 ---"
wm user-rotation lock 1 2>&1
sleep 16
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -1
echo "--- 转回竖屏 ---"
wm user-rotation lock 0 2>&1
sleep 16
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -1
echo "--- 链路日志 ---"
logcat -d 2>/dev/null | grep -F " $SUI " | grep -F OS4FreeFromX | grep -E '配置变更|不重开|核对通过|位置/尺寸|套用比例目标' | sed 's/.*OS4FreeFromX: //' | tail -10
wm user-rotation free 2>&1
