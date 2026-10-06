#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
PKG=com.tencent.mm
KEY="$PKG|1672x2364"
OLD=$(content call --uri content://com.abel.os4freeformx.store --method get --extra k:s:"$KEY" 2>/dev/null | sed -n 's/.*v=\([^}]*\).*/\1/p')
echo "原记忆=[$OLD]"
echo "=== 1) 写入 applyRatio 口径的 21:9 目标（0,140,1672,856 = 1672x716） ==="
content call --uri content://com.abel.os4freeformx.store --method put --extra k:s:"$KEY" --extra v:s:"0,140,1672,856@1.0" 2>&1
echo "=== 2) 杀进程，确保全新建窗 ==="
am force-stop $PKG
sleep 3
echo "=== 3) 命令开小窗 ==="
am start --windowingMode 5 -n com.tencent.mm/.ui.LauncherUI 2>&1 | head -2
sleep 12
echo "=== 4) 实际 bounds（期望 1672x716 附近） ==="
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -3
echo "=== 5) 模块日志 ==="
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -vE 'TRACE|HotArea|SplitTrace' | grep -E '套用比例|重新拉起|核对|位置/尺寸|复核|记录核对' | tail -8
echo "=== 6) 旋转 90° ==="
wm user-rotation lock 1 2>&1
sleep 14
echo "=== 7) 旋转后 bounds ==="
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -3
echo "=== 8) 旋转后日志 ==="
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -vE 'TRACE|HotArea|SplitTrace' | grep -E '配置变更|配置套用|复核|套用比例|位置/尺寸' | tail -10
echo "=== 9) 还原 ==="
wm user-rotation free 2>&1
if [ -n "$OLD" ]; then
  content call --uri content://com.abel.os4freeformx.store --method put --extra k:s:"$KEY" --extra v:s:"$OLD" 2>&1
  echo "已还原 [$OLD]"
fi
