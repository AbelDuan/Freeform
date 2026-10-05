#!/system/bin/sh
echo "=== 1) 确认 lspd 已就绪 ==="
pidof lspd
nsenter -t 1 -m -- ls -la /data/adb/lspd/log/ 2>&1 | tail -4
echo "=== 2) 重启 SystemUI（本次在 lspd 就绪之后） ==="
killall com.android.systemui
sleep 18
echo "=== 3) 新 SystemUI pid ==="
pidof com.android.systemui
echo "=== 4) 注入硬证据（maps） ==="
for p in $(pidof com.android.systemui); do
  n=$(grep -c 'os4freeformx' /proc/$p/maps 2>/dev/null)
  echo "pid $p -> 模块 APK 映射行数: ${n:-N/A}"
done
echo "=== 5) logcat 里的注入里程碑 ==="
logcat -d 2>/dev/null | grep -F 'OS4FreeFromX' | grep -E 'installSystemUi|installSystemServer|hook 成功|共挂|手势功能已移除|不介入通知' | tail -15
