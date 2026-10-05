#!/system/bin/sh
# 把 LSPosed 模块数据库搬到容器可见目录，供容器内 python 编辑
D=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform/tools/lspd-tmp
CFG=/data/adb/lspd/config
mkdir -p "$D"
chmod 777 "$D"
cp -f "$CFG/modules_config.db" "$D/modules_config.db"
cp -f "$CFG/modules_config.db-wal" "$D/modules_config.db-wal" 2>/dev/null
chmod 666 "$D"/* 2>/dev/null
ls -la "$D"
