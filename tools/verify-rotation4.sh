#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
PKG=com.ss.android.ugc.aweme
echo "=== 0) 当前记忆 ==="
nsenter -t 1 -m -- cat /data/user/0/com.abel.os4freeformx/shared_prefs/os4freeformx_bounds.xml 2>&1
echo "=== 1) 杀进程，确保全新建窗 ==="
am force-stop $PKG
sleep 4
echo "=== 2) 命令开小窗 ==="
am start --windowingMode 5 -n $PKG/.splash.SplashActivity 2>&1 | head -2
sleep 13
echo "=== 3) 开窗后真实/可视 ==="
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E '记录核对' | tail -2
echo "=== 4) 旋转 90° ==="
wm user-rotation lock 1 2>&1
sleep 16
echo "=== 5) 旋转后真实/可视（比例应保持正方形） ==="
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E '记录核对' | tail -2
echo "=== 6) 旋转后模块链路日志 ==="
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -vE 'TRACE|HotArea|SplitTrace|沉浸|多分屏|小白条|AppCtx|窗口布局|hook 成功' | grep -E '配置变更|配置套用|复核|套用比例|位置/尺寸' | tail -12
echo "=== 7) 复位旋转 ==="
wm user-rotation free 2>&1
