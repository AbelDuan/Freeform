#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
PKG=com.ss.android.ugc.aweme
cp -f $R/dist/OS4FreeFromX-v0.4.19.apk /data/local/tmp/os4ffx.apk
chmod 644 /data/local/tmp/os4ffx.apk
pm install -r /data/local/tmp/os4ffx.apk 2>&1
killall com.android.systemui
sleep 17
SUI=$(pidof com.android.systemui)
echo "SystemUI=$SUI"
echo "=== 关键方法清单（Bounds/Scale/Rotation/Anim/Size/Level） ==="
logcat -d 2>/dev/null | grep -F " $SUI " | grep -F OS4FreeFromX | grep -E '尺寸链|路径探针: hook 成功|路径探针: 共挂|路径探针: 类不存在' | sed 's/.*OS4FreeFromX: //' | head -45
echo "=== 开窗 + 旋转 ==="
wm user-rotation lock 0 2>&1
sleep 4
am force-stop $PKG
sleep 3
am start --windowingMode 5 -n $PKG/.splash.SplashActivity 2>&1 | head -1
sleep 12
wm user-rotation lock 1 2>&1
sleep 20
echo "=== 旋转过程中触发的探针（真实执行的那一步） ==="
logcat -d 2>/dev/null | grep -F " $SUI " | grep -F OS4FreeFromX | grep -E '路径探针\[' | sed 's/.*OS4FreeFromX: //' | tail -25
echo "=== 复位 ==="
wm user-rotation lock 0 2>&1
sleep 3
wm user-rotation free 2>&1
