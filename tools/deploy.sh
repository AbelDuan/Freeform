#!/usr/bin/env bash
# 一键部署小窗模块（容器内运行；走 dsh-native shell，不依赖 adb）
#
#   build_dexswap → pm install → **修 LSPosed modules.apk_path** → 重启 SystemUI → 校验注入
#
# 为什么必须修 LSPosed 表：每次 `pm install -r` 都会换 codePath，而 LSPosed v2.2.0
# 不会跟着更新 `modules.apk_path` ⇒ 症状是"模块装了但完全不注入"（真机 2026-10-01 踩过：
# logcat 里 OS4FreeFromX 一条都没有）。AGENTS 里记的是同一个坑。
#
# 路径对照：容器的 /root/workspace  ==  设备的 $DEVROOT/root/workspace
# 用法： tools/deploy.sh
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd); cd "$HERE"
PKG=com.abel.os4freeformx
DEVROOT=/data/data/top.funcun.dshfolk/files/rootfs
CSTAGE=/root/workspace/deploy                 # 容器侧
DSTAGE=$DEVROOT/root/workspace/deploy         # 设备侧（同一个目录）
APK=$HERE/dist/OS4FreeFromX-v0.4.0-dexswap.apk
mkdir -p "$CSTAGE"

echo "== 1/5 构建 =="
./build_dexswap.sh >/dev/null
echo "APK md5: $(md5sum "$APK" | cut -d' ' -f1)"

echo "== 2/5 安装 + 导出 LSPosed 表 =="
dsh-native shell --timeout 240000 --reason "部署小窗模块：安装并把 LSPosed 表拷出来" -- "
mkdir -p $DSTAGE
cp -f $DEVROOT$APK /data/local/tmp/os4-new.apk
chmod 644 /data/local/tmp/os4-new.apk
pm install -r /data/local/tmp/os4-new.apk | tail -1
pm path $PKG | sed 's/package://' > $DSTAGE/newpath
cp -f /data/adb/lspd/config/modules_config.db $DSTAGE/lspd.db
chmod 666 $DSTAGE/newpath $DSTAGE/lspd.db
echo installed" 2>&1 | tail -3

NEW=$(tr -d '\r\n' < "$CSTAGE/newpath")
echo "实际安装路径: $NEW"

echo "== 3/5 改表 apk_path =="
python3 - "$NEW" "$CSTAGE/lspd.db" <<'PY'
import sqlite3, os, sys
new, src = sys.argv[1], sys.argv[2]
dst = "/tmp/lspd-fixed.db"
if os.path.exists(dst): os.remove(dst)
ro = sqlite3.connect(f"file:{src}?mode=ro&immutable=1", uri=True)
d = sqlite3.connect(dst)
ro.backup(d)
d.execute("update modules set apk_path=? where module_pkg_name='com.abel.os4freeformx'", (new,))
d.commit()
d.execute("pragma wal_checkpoint(TRUNCATE)"); d.close()
chk = sqlite3.connect(f"file:{dst}?mode=ro&immutable=1", uri=True)
print("表已更新:", list(chk.execute("select module_pkg_name,apk_path from modules where module_pkg_name='com.abel.os4freeformx'")))
PY
cp -f /tmp/lspd-fixed.db "$CSTAGE/lspd-fixed.db"

echo "== 4/5 写回表 + 重启 SystemUI =="
dsh-native shell --timeout 240000 --reason "写回修好的 LSPosed 表并重启 SystemUI" -- "
CFG=/data/adb/lspd/config
cp -f \$CFG/modules_config.db \$CFG/modules_config.db.bak-\$(date +%s)
cp -f $DSTAGE/lspd-fixed.db \$CFG/modules_config.db
rm -f \$CFG/modules_config.db-wal \$CFG/modules_config.db-shm \$CFG/modules_config.db-journal
chown root:root \$CFG/modules_config.db; chmod 600 \$CFG/modules_config.db
killall com.android.systemui; sleep 10
echo \"SystemUI=\$(pidof com.android.systemui)\"" 2>&1 | tail -2

echo "== 5/5 校验注入 =="
dsh-native shell --timeout 120000 --reason "校验模块是否注入" -- '
echo -n "Loaded module 次数: "; logcat -d | grep -ac "Loaded module com.abel.os4freeformx"
logcat -d -s OS4FreeFromX | grep -aE "模块已加载|installSystemUi.*remember" | tail -2' 2>&1 | tail -4
