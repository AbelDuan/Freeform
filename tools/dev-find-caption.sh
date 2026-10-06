#!/system/bin/sh
echo "=== 1) 自由窗口装饰/标题栏窗口 ==="
dumpsys window windows 2>/dev/null | grep -iE 'Window\{.*(freeform|caption|decoration|MultiTask)' | head -12
echo "=== 2) 抖音任务窗口 frame ==="
dumpsys window windows 2>/dev/null | grep -A12 'com.ss.android.ugc.aweme' | grep -E 'Window\{|mFrame|frame=|mBounds|isOnScreen' | head -20
echo "=== 3) 所有含 Miui 的窗口名 ==="
dumpsys window windows 2>/dev/null | grep -oE 'Window\{[^}]*\}' | grep -iE 'miui|freeform|dot|caption' | head -15
