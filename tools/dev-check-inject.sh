#!/system/bin/sh
echo "=== 1) 进程身份 ==="
for p in 20236 3366; do
  echo "--- pid $p ---"
  cat /proc/$p/cmdline 2>/dev/null | tr '\0' ' '
  echo
done
echo "=== 2) 注入硬证据：maps 里是否有模块 APK ==="
for p in 20236 3366; do
  n=$(grep -c 'os4freeformx' /proc/$p/maps 2>/dev/null)
  echo "pid $p -> os4freeformx maps 行数: ${n:-N/A}"
done
echo "=== 3) 当前所有含模块的进程 ==="
for d in /proc/[0-9]*; do
  p=${d#/proc/}
  if grep -q 'os4freeformx' $d/maps 2>/dev/null; then
    echo -n "pid $p: "; cat $d/cmdline 2>/dev/null | tr '\0' ' '; echo
  fi
done
echo "=== 4) 数据库复核（真实命名空间） ==="
nsenter -t 1 -m -- ls -la /data/adb/lspd/config/modules_config.db* 2>&1
