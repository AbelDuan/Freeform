#!/system/bin/sh
echo "=== 1) 显示与旋转状态 ==="
dumpsys display 2>/dev/null | grep -E 'mDisplayId=|logicalFrame=|mCurrentOrientation|rotation' | head -8
echo "=== 2) 折叠状态（内/外屏哪个 ON） ==="
dumpsys display 2>/dev/null | grep -oE 'uniqueId="local:[0-9]+", [0-9]+ x [0-9]+' | head -4
dumpsys display 2>/dev/null | grep -oE 'state (ON|OFF)' | head -4
echo "=== 3) 当前自由窗口 ==="
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' | head -3
echo "=== 4) 全部 配置变更 / 配置套用 日志 ==="
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E '配置变更|配置套用' | tail -10
echo "=== 5) 最近的 记录核对 ==="
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E '记录核对' | tail -3
