#!/system/bin/sh
PKG=com.abel.os4freeformx
echo "--- pm path ---"
pm path $PKG 2>&1
echo "--- pm list ---"
pm list packages 2>/dev/null | grep -i 'os4freeform\|freeform'
echo "--- dumpsys package ---"
dumpsys package $PKG 2>/dev/null | grep -E 'versionName|versionCode|codePath|firstInstallTime|lastUpdateTime' | head
echo "--- userdata dirs ---"
ls -d /data/user/0/$PKG /data/data/$PKG 2>&1
echo "--- lspd db files ---"
ls -la /data/adb/lspd/config/
echo "--- lspd running ---"
pidof lspd
