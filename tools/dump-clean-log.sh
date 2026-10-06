#!/system/bin/sh
echo "=== 干净链路（剔除 TRACE） ==="
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -vE 'TRACE|SplitTrace|沉浸:|多分屏|分屏吸附|小白条|AppCtx|窗口布局|hook 成功|HotArea' | grep -E '配置变更|配置套用|核对|位置/尺寸|套用比例|记忆 key|横竖屏|回原始|遗忘|落盘|记录|记忆|重开|比例' | tail -45
echo "=== 是否有 配置变更 出现过（计数） ==="
logcat -d 2>/dev/null | grep -c '配置变更'
echo "=== 是否有 配置套用 出现过（计数） ==="
logcat -d 2>/dev/null | grep -c '配置套用'
echo "=== SystemUI 当前 pid / 存活时长 ==="
ps -o pid,etime,args -p $(pidof com.android.systemui) 2>&1 | tail -2
echo "=== 模块版本 ==="
dumpsys package com.abel.os4freeformx 2>/dev/null | grep versionName | head -1
