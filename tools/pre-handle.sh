#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
echo "=== 1) 旋转复位到 0 ==="
wm user-rotation lock 0 2>&1
sleep 4
echo "=== 2) 命令开抖音小窗 ==="
am start --windowingMode 5 -n com.ss.android.ugc.aweme/.splash.SplashActivity 2>&1 | head -2
sleep 9
echo "=== 3) 模块记录（真实/可视/scale） ==="
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E '记录核对|记忆 key' | tail -5
echo "=== 4) 自由窗口任务 ==="
dumpsys activity activities 2>/dev/null | grep -E 'Task\{.*mode=freeform' | head -3
echo "=== 5) 截图 ==="
screencap -p -d 4639175402683733248 $R/tools/shots/pre-handle.png
chmod 644 $R/tools/shots/pre-handle.png 2>/dev/null
ls -la $R/tools/shots/pre-handle.png
