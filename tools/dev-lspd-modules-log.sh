#!/system/bin/sh
echo "=== 1) lspd 启动日志里与本模块相关的行 ==="
nsenter -t 1 -m -- sh -c "grep -n 'os4freeformx' /data/adb/lspd/log/modules_*.log 2>/dev/null | head -20" 2>&1
echo "=== 2) lspd 日志开头（模块清单/scope 决策） ==="
nsenter -t 1 -m -- sh -c "head -40 /data/adb/lspd/log/modules_*.log 2>/dev/null" 2>&1
echo "=== 3) verbose 日志里与本模块相关 ==="
nsenter -t 1 -m -- sh -c "grep -i 'os4freeformx' /data/adb/lspd/log/verbose_*.log 2>/dev/null | tail -15" 2>&1
echo "=== 4) 重新导出数据库 ==="
sh /data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform/tools/lspd-dump.sh
