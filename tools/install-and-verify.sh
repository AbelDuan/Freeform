#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
S=$R/tools/shots
PKG=com.ss.android.ugc.aweme
echo "=== 安装 v0.4.13 ==="
cp -f $R/dist/OS4FreeFromX-v0.4.13.apk /data/local/tmp/os4ffx.apk
chmod 644 /data/local/tmp/os4ffx.apk
pm install -r /data/local/tmp/os4ffx.apk 2>&1
dumpsys package com.abel.os4freeformx 2>/dev/null | grep -E 'versionName' | head -1
killall com.android.systemui
sleep 18
SUI=$(pidof com.android.systemui)
echo "SystemUI=$SUI"
echo "=== 强制竖屏 ==="
wm user-rotation lock 0 2>&1
sleep 5
echo "=== 全新建窗（记忆 0,397,1672,2069 = 1:1） ==="
am force-stop $PKG
sleep 4
am start --windowingMode 5 -n $PKG/.splash.SplashActivity 2>&1 | head -1
sleep 14
screencap -p -d 4639175402683733248 $S/v0413-portrait.png
chmod 644 $S/v0413-portrait.png 2>/dev/null
echo "--- 开窗后 ---"
logcat -d 2>/dev/null | grep -F " $SUI " | grep -F OS4FreeFromX | grep -E '记录核对|核对通过|位置/尺寸|套用比例' | tail -3
echo "=== 旋转到横屏 ==="
wm user-rotation lock 1 2>&1
sleep 22
screencap -p -d 4639175402683733248 $S/v0413-landscape.png
chmod 644 $S/v0413-landscape.png 2>/dev/null
echo "--- 旋转后 ---"
logcat -d 2>/dev/null | grep -F " $SUI " | grep -F OS4FreeFromX | grep -E '记录核对|核对通过|位置/尺寸|配置变更|配置套用|重开小窗' | tail -8
echo "=== 复位竖屏 ==="
wm user-rotation lock 0 2>&1
sleep 3
wm user-rotation free 2>&1
