#!/system/bin/sh
# 设备侧：推送新 APK + 备份模块数据（卸载重装前）
PKG=com.abel.os4freeformx
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
cp -f "$R/dist/OS4FreeFromX-v0.4.6.apk" /data/local/tmp/os4ffx.apk
chmod 644 /data/local/tmp/os4ffx.apk
echo "--- apk ---"
ls -la /data/local/tmp/os4ffx.apk
echo "--- backup data ---"
rm -rf /data/local/tmp/os4ffx-data-backup
cp -a /data/data/$PKG /data/local/tmp/os4ffx-data-backup
du -sh /data/local/tmp/os4ffx-data-backup
echo "--- shared_prefs ---"
ls -la /data/local/tmp/os4ffx-data-backup/shared_prefs/ 2>/dev/null || echo "(no shared_prefs)"
echo "--- lspd db ---"
ls -la /data/adb/lspd/config/modules_config.db 2>/dev/null || echo "(no /data/adb/lspd/config)"
