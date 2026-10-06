#!/system/bin/sh
SUI=$(pidof com.android.systemui)
echo "=== SystemUI=$SUI 启动时的候选入口清单 ==="
logcat -d 2>/dev/null | grep -F " $SUI " | grep -F OS4FreeFromX | grep -E '候选入口|候选入口类不存在|hook 成功|共挂|小窗 bounds' | head -40
