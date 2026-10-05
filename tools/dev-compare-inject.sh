#!/system/bin/sh
SUI=$(pidof com.android.systemui)
echo "SystemUI pid: $SUI"
echo "=== 各模块在 SystemUI 里的映射行数（对照实验） ==="
for m in com.sevtinge.hyperceiler cn.myflv.noactive com.abel.hyperosglass cn.dsr213.hyperplus com.abel.os4freeformx io.github.hyperisland; do
  n=$(grep -c "$m" /proc/$SUI/maps 2>/dev/null)
  echo "  $m -> ${n:-N/A}"
done
echo "=== gms(16863) 对照 ==="
for m in com.sevtinge.hyperceiler cn.myflv.noactive com.abel.os4freeformx; do
  n=$(grep -c "$m" /proc/16863/maps 2>/dev/null)
  echo "  $m -> ${n:-N/A}"
done
echo "=== LSPosed verbose 日志里 systemui 相关 ==="
nsenter -t 1 -m -- sh -c "grep -iE 'systemui' /data/adb/lspd/log/verbose_*.log 2>/dev/null | tail -12" 2>&1
echo "=== lspd 存活时长 ==="
ps -o pid,etime,args -p $(pidof lspd) 2>&1 | head -3
