#!/system/bin/sh
# 框架级重启 + 重启后自动取证（脱离 App 进程树，uid 0 存活）
# 结果落盘到容器可见路径，供 App 重启后读取
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
LOG=$R/tools/post-restart-report.txt
exec > "$LOG" 2>&1
chmod 666 "$LOG" 2>/dev/null

echo "=== post-restart report $(date) ==="
OLDZ=$(pidof zygote64)
OLDS=$(pidof system_server)
echo "旧 zygote64=$OLDZ system_server=$OLDS"
sleep 3

echo "--- 1) 触发框架级重启 ---"
setprop ctl.restart zygote
echo "setprop 返回码=$?"
sleep 35
NEWZ=$(pidof zygote64)
echo "35s 后 zygote64=$NEWZ"
if [ "$NEWZ" = "$OLDZ" ]; then
  echo "!! zygote 未变，回退：kill -9 system_server"
  kill -9 "$OLDS" 2>/dev/null
  sleep 40
  echo "回退后 zygote64=$(pidof zygote64) system_server=$(pidof system_server)"
else
  echo "zygote 已重启 ✓"
fi

echo "--- 2) 等待框架起来 ---"
sleep 60
echo "zygote64=$(pidof zygote64) system_server=$(pidof system_server) systemui=$(pidof com.android.systemui) lspd=$(pidof lspd)"

echo "--- 3) 在 lspd 就绪后重启 SystemUI ---"
killall com.android.systemui
sleep 25
SUI=$(pidof com.android.systemui)
echo "新 systemui=$SUI"
echo "  os4freeformx 映射行数: $(grep -c os4freeformx /proc/$SUI/maps 2>/dev/null)"
echo "  hyperceiler 映射行数: $(grep -c hyperceiler /proc/$SUI/maps 2>/dev/null)"

echo "--- 4) 模块注入里程碑（logcat） ---"
logcat -d 2>/dev/null | grep -F 'OS4FreeFromX' | grep -E 'installSystemUi|installSystemServer|hook 成功|共挂|手势功能已移除|不介入通知' | tail -12

echo "--- 5) daemon 日志尾部 ---"
L=$(ls -t /data/adb/lspd/log/verbose_*.log 2>/dev/null | head -1)
echo "log=$L"
tail -12 "$L" 2>/dev/null
echo "  scope 警告数: $(grep -c 'not ready, skip scope request' "$L" 2>/dev/null)"

echo "--- 6) 导出 LSPosed 数据库供复核 ---"
sh $R/tools/lspd-dump.sh

echo "--- 7) 模块 App 数据目录 ---"
nsenter -t 1 -m -- ls -la /data/user/0/com.abel.os4freeformx/shared_prefs/ 2>&1

echo "=== report end $(date) ==="
chmod 666 "$LOG" 2>/dev/null
