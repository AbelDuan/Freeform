#!/system/bin/sh
P=$(pidof lspd)
echo "lspd pid: $P"
echo "=== 1) exe / cmdline ==="
readlink /proc/$P/exe 2>&1
cat /proc/$P/cmdline 2>/dev/null | tr '\0' ' '; echo
echo "=== 2) 模块目录内容 ==="
nsenter -t 1 -m -- ls -la /data/adb/modules/zygisk_lsposed/ 2>&1 | head -25
echo "=== 3) /data/adb/lspd 内容 ==="
nsenter -t 1 -m -- ls -la /data/adb/lspd/ 2>&1
echo "=== 4) unix socket 里是否有 lsp ==="
grep -iE 'lsp|posed' /proc/net/unix 2>/dev/null | head -10
echo "=== 5) lspd 打开的 fd ==="
ls -l /proc/$P/fd 2>/dev/null | head -20
echo "=== 6) 日志文件 mtime ==="
nsenter -t 1 -m -- ls -la --time-style=full-iso /data/adb/lspd/log/ 2>&1 | tail -6
