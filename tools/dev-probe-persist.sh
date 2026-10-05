#!/system/bin/sh
PKG=com.abel.os4freeformx
echo "=== 1) 写一条 probe（不删除） ==="
content call --uri content://com.abel.os4freeformx.store --method put \
    --extra k:s:probe_persist --extra v:s:111,222,333,444@1.0 2>&1
echo "=== 2) 目录/文件是否落盘 ==="
ls -la /data/user/0/$PKG/ 2>&1
ls -la /data/user/0/$PKG/shared_prefs/ 2>&1
echo "=== 3) 文件内容 ==="
cat /data/user/0/$PKG/shared_prefs/os4freeformx_bounds.xml 2>&1
echo "=== 4) 再读回（跨调用，验证真落盘） ==="
content call --uri content://com.abel.os4freeformx.store --method get --extra k:s:probe_persist 2>&1
echo "=== 5) 清理 probe ==="
content call --uri content://com.abel.os4freeformx.store --method remove --arg probe_persist 2>&1
