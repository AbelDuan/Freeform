#!/system/bin/sh
echo "=== LSPosed 日志文件 ==="
nsenter -t 1 -m -- ls -t /data/adb/lspd/log/modules_*.log 2>/dev/null | head -3
L=$(nsenter -t 1 -m -- ls -t /data/adb/lspd/log/modules_*.log 2>/dev/null | head -1)
echo "读: $L"
echo "=== installSystemServer / 系统侧探针 ==="
nsenter -t 1 -m -- sh -c "grep -h -E 'installSystemServer|系统侧探针' $L 2>/dev/null | head -60" | sed 's/.*OS4FreeFromX.//'
