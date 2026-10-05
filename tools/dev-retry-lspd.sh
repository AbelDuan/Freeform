#!/system/bin/sh
echo "=== A) dmesg 里的 avc 拒绝 ==="
dmesg 2>/dev/null | grep -i avc | grep -iE 'lsp|zygote|ksu|system_server' | tail -15
echo "avc 总数: $(dmesg 2>/dev/null | grep -ci avc)"
echo "=== B) 第二次重启 daemon（在 system_server 完全就绪后） ==="
PID=$(pidof lspd); [ -n "$PID" ] && kill $PID
sleep 4
cd /data/adb/modules/zygisk_lsposed || exit 1
setsid ./daemon --force >/dev/null 2>&1 </dev/null &
sleep 25
echo "lspd: $(pidof lspd)"
echo "=== C) 重启 SystemUI ==="
killall com.android.systemui
sleep 18
SUI=$(pidof com.android.systemui)
echo "SystemUI: $SUI"
echo "=== D) 注入检查 ==="
for m in com.abel.os4freeformx com.sevtinge.hyperceiler cn.myflv.noactive; do
  echo "  $m -> $(grep -c $m /proc/$SUI/maps 2>/dev/null)"
done
echo "=== E) 最新 daemon 日志里的 scope 警告数 ==="
L=$(ls -t /data/adb/lspd/log/verbose_*.log 2>/dev/null | head -1)
echo "log=$L"
grep -c 'not ready, skip scope request' "$L" 2>/dev/null
echo "=== F) 最新 daemon 日志尾部 ==="
tail -6 "$L" 2>/dev/null
