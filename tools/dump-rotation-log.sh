#!/system/bin/sh
echo "=== 1) 当前小窗 ==="
dumpsys activity activities 2>/dev/null | grep -E 'Task\{.*mode=freeform' | head -3
echo "=== 2) 记忆文件 ==="
nsenter -t 1 -m -- cat /data/user/0/com.abel.os4freeformx/shared_prefs/os4freeformx_bounds.xml 2>&1
echo "=== 3) 配置变更/旋转/比例/记忆 链路日志（最近 60 条） ==="
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E '配置变更|配置套用|旋转|比例|记忆|核对|位置/尺寸|重开|套用|遗忘|落盘|回原始|横竖屏|屏' | tail -60
