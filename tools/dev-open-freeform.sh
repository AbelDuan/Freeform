#!/system/bin/sh
echo "=== 1) am start 是否支持 windowingMode ==="
am start --help 2>&1 | grep -iE 'windowing|freeform' | head -10
echo "=== 2) 尝试以自由窗口模式启动计算器 ==="
am start --windowingMode 5 -n com.miui.calculator/.Calculator 2>&1 | head -5
sleep 5
echo "=== 3) 当前任务里的自由窗口 ==="
dumpsys activity activities 2>/dev/null | grep -iE 'freeform|windowingMode=5' | head -8
echo "=== 4) 顶层 ==="
dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity' | head -2
