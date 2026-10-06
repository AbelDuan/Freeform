#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
S=$R/tools/shots
PKG=com.ss.android.ugc.aweme
echo "=== 安装 v0.4.18 ==="
cp -f $R/dist/OS4FreeFromX-v0.4.18.apk /data/local/tmp/os4ffx.apk
chmod 644 /data/local/tmp/os4ffx.apk
pm install -r /data/local/tmp/os4ffx.apk 2>&1
killall com.android.systemui
sleep 17
SUI=$(pidof com.android.systemui)
echo "SystemUI=$SUI"
echo "=== 写入明确 1:1 记忆 ==="
content call --uri content://com.abel.os4freeformx.store --method put \
  --extra k:s:"$PKG|1672x2364" --extra v:s:"0,397,1672,2069@0.66" 2>&1
echo "=== 竖屏 + 全新小窗 ==="
wm user-rotation lock 0 2>&1
sleep 5
am force-stop $PKG
sleep 4
am start --windowingMode 5 -n $PKG/.splash.SplashActivity 2>&1 | head -1
sleep 14
echo "--- 开窗后 bounds（期望正方 ~1532x1532） ---"
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -2
echo "=== 旋转到横屏 ==="
wm user-rotation lock 1 2>&1
sleep 22
echo "--- 旋转后 bounds（期望仍是正方） ---"
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -2
screencap -p $S/v0418-after.png
chmod 644 $S/v0418-after.png 2>/dev/null
echo "--- 尺寸档位日志 ---"
logcat -d 2>/dev/null | grep -F " $SUI " | grep -F OS4FreeFromX | grep -E '尺寸档位|配置变更|配置套用|记录核对' | tail -12
echo "=== 复位 ==="
wm user-rotation lock 0 2>&1
sleep 3
wm user-rotation free 2>&1
