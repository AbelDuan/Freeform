#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
PKG=com.ss.android.ugc.aweme
SUI=$(pidof com.android.systemui)
echo "SystemUI=$SUI"
echo "=== 1) 强制回竖屏 ==="
wm user-rotation lock 0 2>&1
sleep 6
dumpsys display 2>/dev/null | grep -oE 'mCurrentOrientation=[0-9]' | head -2
echo "=== 2) 全新建窗（抖音，记忆=0,397,1672,2069 正方） ==="
am force-stop $PKG
sleep 4
am start --windowingMode 5 -n $PKG/.splash.SplashActivity 2>&1 | head -2
sleep 13
echo "=== 3) 开窗后 ==="
logcat -d 2>/dev/null | grep -F " $SUI " | grep -F OS4FreeFromX | grep -E '记录核对|核对|套用比例' | tail -3
echo "=== 4) 旋转到横屏 ==="
wm user-rotation lock 1 2>&1
sleep 16
echo "=== 5) 旋转后 ==="
logcat -d 2>/dev/null | grep -F " $SUI " | grep -F OS4FreeFromX | grep -E '记录核对|核对|配置' | tail -5
echo "=== 6) 配置链路日志 ==="
logcat -d 2>/dev/null | grep -F " $SUI " | grep -F OS4FreeFromX | grep -vE 'TRACE|HotArea|SplitTrace|沉浸|多分屏|小白条|AppCtx|窗口布局|hook 成功' | grep -E '配置变更|配置套用|复核|套用比例|位置/尺寸' | tail -10
echo "=== 7) 复位竖屏 ==="
wm user-rotation lock 0 2>&1
sleep 3
wm user-rotation free 2>&1
