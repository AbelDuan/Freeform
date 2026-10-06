#!/system/bin/sh
echo "=== 当前自由窗口任务 ==="
dumpsys activity activities 2>/dev/null | grep -oE 'Task\{[a-z0-9]+ #[0-9]+ [^}]*mode=freeform[^}]*\}' | head -3
echo "=== 尝试把 task 56 调到前台 ==="
am task move-to-front 56 2>&1 | head -3
sleep 5
echo "=== 之后状态 ==="
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' | head -2
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A1 | grep -oE 'mBounds=Rect\([^)]*\)' | head -3
echo "=== 顶层 ==="
dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity' | head -2
