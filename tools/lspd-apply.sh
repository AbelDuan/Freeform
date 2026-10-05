#!/system/bin/sh
# 把容器内编辑好的数据库写回 LSPosed，重启 lspd 与 SystemUI
D=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform/tools/lspd-tmp
CFG=/data/adb/lspd/config
cp -f "$D/modules_config.db" /data/local/tmp/f-fixed.db
chmod 644 /data/local/tmp/f-fixed.db
cp -f "$CFG/modules_config.db" "$CFG/modules_config.db.bak-prefix" 2>/dev/null
PID=$(pidof lspd); [ -n "$PID" ] && kill $PID
sleep 2
rm -f "$CFG/modules_config.db-shm" "$CFG/modules_config.db-wal"
cp -f /data/local/tmp/f-fixed.db "$CFG/modules_config.db"
chown root:root "$CFG/modules_config.db"
chmod 600 "$CFG/modules_config.db"
cd /data/adb/modules/zygisk_lsposed || exit 1
setsid ./daemon --force >/dev/null 2>&1 </dev/null &
sleep 3
echo "lspd: $(pidof lspd)"
killall com.android.systemui
echo "SystemUI 已重启"
