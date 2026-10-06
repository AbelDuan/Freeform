#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
echo "=== 1) 推送 v0.4.7 ==="
cp -f $R/dist/OS4FreeFromX-v0.4.7.apk /data/local/tmp/os4ffx.apk
chmod 644 /data/local/tmp/os4ffx.apk
ls -la /data/local/tmp/os4ffx.apk
echo "=== 2) 覆盖安装（同签名 -r） ==="
pm install -r /data/local/tmp/os4ffx.apk 2>&1
echo "=== 3) 包状态 ==="
dumpsys package com.abel.os4freeformx 2>/dev/null | grep -E 'versionName|versionCode|codePath' | head -3
echo "=== 4) 模块数据目录（应保留） ==="
nsenter -t 1 -m -- ls -la /data/user/0/com.abel.os4freeformx/shared_prefs/ 2>&1
echo "=== 5) 导出 LSPosed 数据库 ==="
sh $R/tools/lspd-dump.sh >/dev/null 2>&1 && echo dumped
