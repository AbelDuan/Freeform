#!/system/bin/sh
echo "=== 1) kmsg.log 里的 avc 拒绝（lsp/zygote/ksu 相关） ==="
grep -i 'avc' /data/adb/lspd/log/kmsg.log 2>/dev/null | grep -iE 'lsp|zygote|ksu|adb/lspd' | tail -25
echo "=== 2) kmsg 里 avc 总数 ==="
grep -ci 'avc' /data/adb/lspd/log/kmsg.log 2>/dev/null
echo "=== 3) zygote 侧映射（zygisk / lsp / arm64-v8a） ==="
Z=$(pidof zygote64); echo "zygote64 pid=$Z"
grep -iE 'zygisk|lsp|arm64-v8a|modules/zygisk' /proc/$Z/maps 2>/dev/null | head -10
echo "--- zygote maps 总行数 ---"
wc -l /proc/$Z/maps 2>&1
echo "=== 4) zygote 启动时长 ==="
ps -o pid,etime,args -p $Z 2>&1 | head -3
echo "=== 5) 崩溃日志尾部（旧 daemon 为何崩） ==="
tail -25 /data/adb/lspd/log.old/daemon-15595-crash-2026-10-05T21:14:37+08:00.log 2>&1
