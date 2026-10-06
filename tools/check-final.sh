#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
S=$R/tools/shots
SUI=$(pidof com.android.systemui)
echo "=== 当前屏幕 ==="
dumpsys display 2>/dev/null | grep -oE 'uniqueId="local:[0-9]+", [0-9]+ x [0-9]+|state (ON|OFF)' | head -4
echo "=== 自由窗口 bounds ==="
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -3
echo "=== 模块最新链路 ==="
logcat -d 2>/dev/null | grep -F " $SUI " | grep -F OS4FreeFromX | grep -vE 'TRACE|HotArea|SplitTrace|沉浸|多分屏|小白条|AppCtx|窗口布局|hook 成功' | grep -E '配置变更|配置套用|重开|核对|位置/尺寸|记录核对' | tail -8
echo "=== 截图（外屏） ==="
screencap -p -d 4639175068132267009 $S/final-outer.png 2>/dev/null
chmod 644 $S/final-outer.png 2>/dev/null
ls -la $S/final-outer.png 2>/dev/null
