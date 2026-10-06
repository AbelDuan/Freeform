#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
S=$R/tools/shots
PKG=com.ss.android.ugc.aweme
echo "=== 安装 v0.4.16 ==="
cp -f $R/dist/OS4FreeFromX-v0.4.16.apk /data/local/tmp/os4ffx.apk
chmod 644 /data/local/tmp/os4ffx.apk
pm install -r /data/local/tmp/os4ffx.apk 2>&1
dumpsys package com.abel.os4freeformx 2>/dev/null | grep -E 'versionName' | head -1
killall com.android.systemui
sleep 16
SUI=$(pidof com.android.systemui)
echo "SystemUI=$SUI"
echo "=== 竖屏 ==="
wm user-rotation lock 0 2>&1
sleep 5
echo "=== 全新建窗（aweme 记忆 1:1） ==="
am force-stop $PKG
sleep 4
am start --windowingMode 5 -n $PKG/.splash.SplashActivity 2>&1 | head -1
sleep 14
screencap -p $S/v0416-before.png
chmod 644 $S/v0416-before.png 2>/dev/null
echo "--- 开窗后 bounds ---"
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -2
echo "=== 旋转到横屏 ==="
wm user-rotation lock 1 2>&1
sleep 25
screencap -p $S/v0416-after.png
chmod 644 $S/v0416-after.png 2>/dev/null
echo "--- 旋转后 bounds ---"
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -2
echo "--- 旋转后链路 ---"
logcat -d 2>/dev/null | grep -F " $SUI " | grep -F OS4FreeFromX | grep -vE 'TRACE|HotArea|SplitTrace|沉浸|多分屏|小白条|AppCtx|窗口布局|hook 成功' | grep -E '配置变更|配置套用|重开|复核|记录核对' | tail -8
echo "=== 复位竖屏 ==="
wm user-rotation lock 0 2>&1
sleep 3
wm user-rotation free 2>&1
