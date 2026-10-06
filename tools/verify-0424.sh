#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
S=$R/tools/shots
PKG=com.ss.android.ugc.aweme
cp -f $R/dist/OS4FreeFromX-v0.4.24.apk /data/local/tmp/os4ffx.apk
chmod 644 /data/local/tmp/os4ffx.apk
pm install -r /data/local/tmp/os4ffx.apk 2>&1
killall com.android.systemui
sleep 16
SUI=$(pidof com.android.systemui)
echo "SystemUI=$SUI"
content call --uri content://com.abel.os4freeformx.store --method put \
  --extra k:s:"$PKG|1672x2364" --extra v:s:"0,397,1672,2069@0.66" >/dev/null 2>&1
echo "=== 竖屏开窗（初始可能非正方，没关系） ==="
wm user-rotation lock 0 2>&1
sleep 5
am force-stop $PKG
sleep 3
am start --windowingMode 5 -n $PKG/.splash.SplashActivity 2>&1 | head -1
sleep 12
echo "--- 旋转前 bounds ---"
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -2
echo "=== 旋转到横屏 ==="
wm user-rotation lock 1 2>&1
sleep 24
echo "--- 旋转后 bounds（期望 = 记忆 1672x1672 正方） ---"
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -2
echo "--- 重开路径与核对日志 ---"
logcat -d 2>/dev/null | grep -F " $SUI " | grep -F OS4FreeFromX | grep -E '重开|核对通过|配置套用|位置/尺寸|纯 startActivity' | sed 's/.*OS4FreeFromX: //' | tail -12
screencap -p $S/v0424-after.png
chmod 644 $S/v0424-after.png 2>/dev/null
wm user-rotation lock 0 2>&1
sleep 3
wm user-rotation free 2>&1
