#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
echo "=== 1) 各自由窗口任务 + 其 bounds ==="
dumpsys activity activities 2>/dev/null | grep -E 'Task\{[a-z0-9]+ #[0-9]+ .*mode=freeform' | head -4
echo "---"
dumpsys activity activities 2>/dev/null | grep -B6 'mWindowingMode=freeform' | grep -E 'Task\{|mBounds=Rect' | head -12
echo "=== 2) 模块的 真实/可视/scale 记录 ==="
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E '记录核对|落盘' | tail -6
echo "=== 3) uiautomator 里与标题栏菜单有关的节点 ==="
uiautomator dump /data/local/tmp/uiC.xml 2>&1 | tail -1
grep -oE 'content-desc="[^"]{1,12}"' /data/local/tmp/uiC.xml 2>/dev/null | sort -u | head -20
echo "=== 4) 截图 ==="
screencap -p -d 4639175402683733248 $R/tools/shots/state2.png
chmod 644 $R/tools/shots/state2.png 2>/dev/null
ls -la $R/tools/shots/state2.png
