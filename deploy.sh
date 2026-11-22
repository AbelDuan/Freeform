#!/bin/sh
# 部署 os4freeformx：编译 → 安装(换 codePath) → 同步 apk_path → 重启 SystemUI
set -e
cd /root/Freeform_tmp
./build.sh > /tmp/fb.log 2>&1 || { tail -25 /tmp/fb.log; exit 1; }
APK=/root/Freeform_tmp/dist/OS4FreeFromX-v0.7.75.apk
echo "APK: $(md5sum $APK | cut -d' ' -f1) $(stat -c%s $APK)B"

NEW=$(dsh-native shell --timeout 120000 --reason "安装模块新版本并取新 codePath" -- \
  "cp -f /data/data/top.funcun.dshfolk/files/rootfs$APK /data/local/tmp/os4-new.apk; chmod 644 /data/local/tmp/os4-new.apk; pm install -r /data/local/tmp/os4-new.apk 2>&1 | tail -1; pm path com.abel.os4freeformx | sed 's/package://'" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['stdout'].strip().splitlines()[-1])")
echo "NEW=$NEW"

BASE=/root/lspd-fixed.db
[ -f "$BASE" ] || BASE=/root/workspace/deploy/lspd.db
python3 - "$NEW" "$BASE" <<'EOF'
import sqlite3, shutil, sys
new, base = sys.argv[1], sys.argv[2]
shutil.copy(base, '/tmp/lspd-deploy.db')
c = sqlite3.connect('/tmp/lspd-deploy.db')
c.execute("UPDATE modules SET apk_path=? WHERE module_pkg_name=?", (new, 'com.abel.os4freeformx'))
c.commit(); c.close()
shutil.copy('/tmp/lspd-deploy.db', '/root/lspd-fixed.db')
print('db updated')
EOF

dsh-native shell --timeout 120000 --reason "写回数据库并重启 SystemUI 加载新 dex" -- \
  "cp -f /data/data/top.funcun.dshfolk/files/rootfs/root/lspd-fixed.db /data/adb/lspd/config/modules_config.db; \
   rm -f /data/adb/lspd/config/modules_config.db-wal /data/adb/lspd/config/modules_config.db-shm /data/adb/lspd/config/modules_config.db-journal; \
   chown root:root /data/adb/lspd/config/modules_config.db; chmod 600 /data/adb/lspd/config/modules_config.db; \
   echo -n db命中:; grep -ac \"$(echo $NEW | sed 's|.*/~~||;s|/.*||')\" /data/adb/lspd/config/modules_config.db; \
   logcat -c; killall com.android.systemui; sleep 15; echo sysui=\$(pidof com.android.systemui)"
