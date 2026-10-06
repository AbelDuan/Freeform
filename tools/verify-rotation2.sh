#!/system/bin/sh
# 旋转保比例验证：写入 21:9 记忆 → 命令开小窗 → 旋转 → 看比例是否保住（跑完还原记忆）
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
PKG=com.tencent.mm
KEY="$PKG|1672x2364"
echo "=== 0) 备份原记忆 ==="
OLD=$(content call --uri content://com.abel.os4freeformx.store --method get --extra k:s:"$KEY" 2>/dev/null | sed -n 's/.*v=\([^}]*\).*/\1/p')
echo "原记忆 = [$OLD]"
echo "=== 1) 写入 21:9 测试记忆（1170x501@1.0） ==="
content call --uri content://com.abel.os4freeformx.store --method put --extra k:s:"$KEY" --extra v:s:"416,140,1586,641@1.0" 2>&1
echo "=== 2) 命令开微信小窗 ==="
am start --windowingMode 5 -n com.tencent.mm/.ui.LauncherUI 2>&1 | head -2
sleep 10
echo "=== 3) 开窗后（应恢复成 1170x501 = 21:9） ==="
dumpsys activity activities 2>/dev/null | grep -A3 'Task\{.*com.tencent.mm.*mode=freeform' | grep -oE 'mBounds=Rect\([^)]*\)' | head -2
echo "=== 4) 旋转 90° ==="
wm user-rotation lock 1 2>&1
sleep 12
echo "=== 5) 旋转后（比例应仍是 21:9；尺寸可能等比缩放） ==="
dumpsys activity activities 2>/dev/null | grep -A3 'Task\{.*com.tencent.mm.*mode=freeform' | grep -oE 'mBounds=Rect\([^)]*\)' | head -2
echo "=== 6) 模块日志（配置变更/套用/复核） ==="
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -vE 'TRACE|SplitTrace|沉浸:|多分屏|分屏吸附|小白条|AppCtx|窗口布局|hook 成功|HotArea' | grep -E '配置变更|配置套用|套用比例|重新拉起|复核|位置/尺寸|核对' | tail -18
echo "=== 7) 还原旋转与记忆 ==="
wm user-rotation free 2>&1
if [ -n "$OLD" ]; then
  content call --uri content://com.abel.os4freeformx.store --method put --extra k:s:"$KEY" --extra v:s:"$OLD" 2>&1
  echo "已还原为 [$OLD]"
else
  content call --uri content://com.abel.os4freeformx.store --method remove --arg "$KEY" 2>&1
  echo "原记忆为空，已删除测试键"
fi
nsenter -t 1 -m -- cat /data/user/0/com.abel.os4freeformx/shared_prefs/os4freeformx_bounds.xml 2>&1
