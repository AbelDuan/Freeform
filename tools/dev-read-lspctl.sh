#!/system/bin/sh
D=/data/adb/modules/zygisk_lsposed
for f in lspctl action.sh emulated-soft-reboot.sh service.sh lspd; do
  echo "=== $f ==="
  cat "$D/$f" 2>&1
done
echo "=== bin/ ==="
ls -la $D/bin/
