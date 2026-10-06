#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
S=$R/tools/shots
PKG=com.ss.android.ugc.aweme
SUI=$(pidof com.android.systemui)
echo "SystemUI=$SUI"
echo "=== 1) 强制竖屏 ==="
wm user-rotation lock 0 2>&1
sleep 6
echo "=== 2) 全新建窗（记忆 0,397,1672,2069 = 1:1 正方形） ==="
am force-stop $PKG
sleep 4
am start --windowingMode 5 -n $PKG/.splash.SplashActivity 2>&1 | head -2
sleep 14
screencap -p -d 4639175402683733248 $S/v0412-portrait.png
chmod 644 $S/v0412-portrait.png 2>/dev/null
echo "--- 开窗后 ---"
logcat -d 2>/dev/null | grep -F " $SUI " | grep -F OS4FreeFromX | grep -E '记录核对|核对|套用比例' | tail -3
echo "=== 3) 旋转到横屏 ==="
wm user-rotation lock 1 2>&1
sleep 20
screencap -p -d 4639175402683733248 $S/v0412-landscape.png
chmod 644 $S/v0412-landscape.png 2>/dev/null
echo "--- 旋转后 ---"
logcat -d 2>/dev/null | grep -F " $SUI " | grep -F OS4FreeFromX | grep -E '记录核对|核对|配置变更|配置套用|复核|重开小窗' | tail -8
echo "=== 4) 复位竖屏 ==="
wm user-rotation lock 0 2>&1
sleep 3
wm user-rotation free 2>&1
