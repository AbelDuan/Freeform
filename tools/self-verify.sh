#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
S=$R/tools/shots
PKG=com.ss.android.ugc.aweme
echo "=== 写入 21:9 记忆（1672x716，宽扁一眼可辨） ==="
content call --uri content://com.abel.os4freeformx.store --method put \
  --extra k:s:"$PKG|1672x2364" --extra v:s:"0,140,1672,856@0.66" 2>&1
echo "=== 竖屏开窗 ==="
wm user-rotation lock 0 2>&1
sleep 5
am force-stop $PKG
sleep 3
am start --windowingMode 5 -n $PKG/.splash.SplashActivity 2>&1 | head -1
sleep 14
echo "--- 竖屏 bounds / 记录 ---"
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -2
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E '记录核对|核对通过' | sed 's/.*OS4FreeFromX: //' | tail -2
screencap -p $S/sv-portrait.png
chmod 644 $S/sv-portrait.png 2>/dev/null
echo "=== 旋转横屏 ==="
wm user-rotation lock 1 2>&1
sleep 16
echo "--- 横屏 bounds / 记录 ---"
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -2
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E '记录核对|核对通过' | sed 's/.*OS4FreeFromX: //' | tail -2
screencap -p $S/sv-landscape.png
chmod 644 $S/sv-landscape.png 2>/dev/null
ls -la $S/sv-portrait.png $S/sv-landscape.png 2>/dev/null
