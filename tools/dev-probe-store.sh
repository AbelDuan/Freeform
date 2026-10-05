#!/system/bin/sh
PKG=com.abel.os4freeformx
echo "=== 1) 直接调 Provider selfcheck ==="
content call --uri content://com.abel.os4freeformx.store --method selfcheck \
    --extra k:s:selftest_probe --extra v:s:300,500,1200,1400@1.0 2>&1
echo "=== 2) 调用后数据目录是否出现 ==="
ls -la /data/data/$PKG/shared_prefs/ 2>&1
echo "=== 3) 模块日志（最近 500 行内） ==="
logcat -d -t 500 2>/dev/null | grep -iE 'os4freeformx|FreeFromX|记住 |bounds 写入|恢复\(' | tail -30
echo "=== 4) 模块进程 ==="
ps -A 2>/dev/null | grep -i os4freeform
echo "=== 5) SystemUI 内是否有模块类被加载 ==="
logcat -d -t 800 2>/dev/null | grep -iE 'LSPosed|Xposed' | tail -15
