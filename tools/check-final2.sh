#!/system/bin/sh
SUI=$(pidof com.android.systemui)
echo "=== 反射是否失败 / 重开结果 ==="
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E 'setLaunchWindowingMode|plain freeform|重开小窗失败' | tail -6
echo "=== 当前 aweme 任务模式 ==="
dumpsys activity activities 2>/dev/null | grep -E 'aweme' | grep -E 'Task\{' | head -4
echo "=== 顶层 ==="
dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity' | head -2
echo "=== 所有 freeform ==="
dumpsys activity activities 2>/dev/null | grep -c 'mode=freeform'
