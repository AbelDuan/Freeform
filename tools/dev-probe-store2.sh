#!/system/bin/sh
PKG=com.abel.os4freeformx
echo "=== 1) /data/user/0 里的模块目录 ==="
ls -la /data/user/0/ 2>/dev/null | grep -i os4freeform
echo "=== 2) 递归看数据目录 ==="
ls -laR /data/user/0/$PKG 2>&1 | head -40
echo "=== 3) 全盘找 bounds 文件 ==="
find /data -name 'os4freeformx*' 2>/dev/null | head -20
echo "=== 4) 模块日志 ==="
logcat -d 2>/dev/null | grep -F 'OS4FreeFromX' | tail -25
echo "=== 5) 模块日志条数 ==="
logcat -d 2>/dev/null | grep -cF 'OS4FreeFromX'
