#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
echo "=== 1) 模块记忆文件内容 ==="
nsenter -t 1 -m -- cat /data/user/0/com.abel.os4freeformx/shared_prefs/os4freeformx_bounds.xml 2>&1
echo "=== 2) 重启 SystemUI 载入 v0.4.7 ==="
killall com.android.systemui
sleep 18
SUI=$(pidof com.android.systemui)
echo "systemui=$SUI"
echo "新 APK 路径映射数(koHPPoyF...): $(grep -c 'koHPPoyFA1WqrOzuXyJIvw' /proc/$SUI/maps 2>/dev/null)"
echo "模块映射数: $(grep -c os4freeformx /proc/$SUI/maps 2>/dev/null)"
echo "=== 3) 注入里程碑 ==="
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E 'installSystemUi|共挂|手势功能已移除|不介入通知|比例行已注入' | tail -6
