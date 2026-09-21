#!/usr/bin/env bash
# 重装 APK 后，LSPosed(v2.2.0) 的库不会跟着更新模块的 apk_path / enabled / scope 三张表，
# 结果就是"模块装了但完全不注入"。这个脚本把三张表按当前真实安装路径修好，然后重启 lspd 与 SystemUI。
#
# 用法： ./tools/fix-lsposed-module.sh
set -euo pipefail
PKG=com.abel.os4freeformx
TMP=/tmp/lspdb; mkdir -p "$TMP"; rm -f "$TMP"/f.db*

NEW=$(adb shell "pm path $PKG" | tr -d '\r' | sed 's/package://')
echo "真实 APK 路径: $NEW"

adb shell "su -c 'cp -f /data/adb/lspd/config/modules_config.db /data/local/tmp/f.db; cp -f /data/adb/lspd/config/modules_config.db-wal /data/local/tmp/f.db-wal 2>/dev/null; chmod 666 /data/local/tmp/f.db*'" >/dev/null
adb pull /data/local/tmp/f.db "$TMP/f.db" >/dev/null
adb pull /data/local/tmp/f.db-wal "$TMP/f.db-wal" >/dev/null 2>&1 || true
[ -s "$TMP/f.db-wal" ] || rm -f "$TMP/f.db-wal"

PKG="$PKG" NEW="$NEW" DB="$TMP/f.db" python3 - <<'PY'
import os, sqlite3
pkg, new, db = os.environ["PKG"], os.environ["NEW"], os.environ["DB"]
c = sqlite3.connect(db)
c.execute("update modules set apk_path=? where module_pkg_name=?", (new, pkg))
c.execute("insert or replace into modules_state(module_pkg_name,user_id,enabled,scope_request_blocked) values(?,0,1,0)", (pkg,))
c.execute("insert or replace into scope(module_pkg_name,app_pkg_name,user_id) values(?,?,0)", (pkg, "system"))
c.execute("insert or replace into scope(module_pkg_name,app_pkg_name,user_id) values(?,?,0)", (pkg, "com.android.systemui"))
c.commit()
print("module:", list(c.execute("select * from modules where module_pkg_name=?", (pkg,))))
print("state :", list(c.execute("select * from modules_state where module_pkg_name=?", (pkg,))))
print("scope :", list(c.execute("select * from scope where module_pkg_name=?", (pkg,))))
c.execute("pragma wal_checkpoint(TRUNCATE)"); c.close()
PY

cat > "$TMP/apply.sh" <<'EOS2'
#!/system/bin/sh
CFG=/data/adb/lspd/config
PID=$(pidof lspd); [ -n "$PID" ] && kill $PID
sleep 2
cp -f $CFG/modules_config.db /data/local/tmp/lspd-db-backup.db
rm -f $CFG/modules_config.db-shm $CFG/modules_config.db-wal
cp -f /data/local/tmp/f-fixed.db $CFG/modules_config.db
chown root:root $CFG/modules_config.db; chmod 600 $CFG/modules_config.db
cd /data/adb/modules/zygisk_lsposed
setsid ./daemon --force >/dev/null 2>&1 </dev/null &
sleep 3
echo "lspd: $(pidof lspd)"
EOS2
adb push "$TMP/f.db" /data/local/tmp/f-fixed.db >/dev/null
adb push "$TMP/apply.sh" /data/local/tmp/apply.sh >/dev/null
adb shell "su -c 'chmod 755 /data/local/tmp/apply.sh; chmod 644 /data/local/tmp/f-fixed.db; sh /data/local/tmp/apply.sh'"

adb shell "su -c 'killall com.android.systemui'"
echo "SystemUI 已重启；等 30 秒后可用 tools/verify-gestures.sh 取证。"
