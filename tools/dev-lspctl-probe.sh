#!/system/bin/sh
D=/data/adb/modules/zygisk_lsposed
echo "=== 1) lspctl --help ==="
BOOTCLASSPATH=$(getprop ro.boot.bootclasspath 2>/dev/null)
export BOOTCLASSPATH
sh $D/lspctl --help 2>&1 | head -30
echo "=== 2) lspctl status ==="
sh $D/lspctl status 2>&1 | head -20
echo "=== 3) props.txt 里是否有 bridge 名 ==="
grep -o 'lspbridge[^ ]*' /data/adb/lspd/log/props.txt 2>/dev/null | sort -u | head
echo "=== 4) kmsg.log 里是否有 bridge ==="
grep -o 'lspbridge[^ ]*' /data/adb/lspd/log/kmsg.log 2>/dev/null | sort -u | head
echo "=== 5) 新 daemon verbose 日志尾部 ==="
tail -12 /data/adb/lspd/log/verbose_2026-10-05T21:14:39.779714.log 2>&1
