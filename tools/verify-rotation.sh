#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
echo "=== 1) 以自由窗口模式启动微信 ==="
am start --windowingMode 5 -n com.tencent.mm/.ui.LauncherUI 2>&1 | head -2
sleep 9
echo "=== 2) 恢复链路日志（v0.4.8） ==="
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -vE 'TRACE|SplitTrace|沉浸:|多分屏|分屏吸附|小白条|AppCtx|窗口布局' | tail -14
echo "=== 3) 旋转前 bounds ==="
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A1 | grep -oE 'mBounds=Rect\([^)]*\)' | head -3
echo "=== 4) wm user-rotation 支持情况 ==="
wm help 2>&1 | grep -iE 'rotation' | head -3
echo "=== 5) 旋转到 90° ==="
wm user-rotation lock 1 2>&1
sleep 8
echo "=== 6) 旋转后 bounds ==="
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A1 | grep -oE 'mBounds=Rect\([^)]*\)' | head -3
echo "=== 7) 旋转后日志 ==="
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E '配置变更|配置套用|核对|位置/尺寸|重开|套用比例|记忆 key' | tail -12
screencap -p -d 4639175402683733248 $R/tools/shots/rot.png
chmod 644 $R/tools/shots/rot.png 2>/dev/null
ls -la $R/tools/shots/rot.png
