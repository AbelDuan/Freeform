#!/system/bin/sh
echo "=== 1) gms 里匹配到的原行 ==="
grep 'os4freeformx' /proc/16863/maps 2>/dev/null | head -3
echo "=== 2) securitycenter.remote 原行 ==="
grep 'os4freeformx' /proc/19218/maps 2>/dev/null | head -3
echo "=== 3) SystemUI maps 可读性 ==="
wc -l /proc/3366/maps 2>&1
head -2 /proc/3366/maps 2>&1
echo "=== 4) LSPosed 日志目录 ==="
nsenter -t 1 -m -- ls -la /data/adb/lspd/log/ 2>&1
echo "=== 5) LSPosed 日志里与本模块/systemui 相关 ==="
nsenter -t 1 -m -- sh -c "grep -h -iE 'os4freeformx|systemui' /data/adb/lspd/log/*.log 2>/dev/null | tail -25" 2>&1
echo "=== 6) lspd 进程 ==="
pidof lspd
