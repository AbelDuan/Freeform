#!/system/bin/sh
PKG=com.abel.os4freeformx
echo "=== 1) Android 认为的数据目录 ==="
dumpsys package $PKG 2>/dev/null | grep -iE 'dataDir|userId|codePath|primaryCpuAbi|flags=' | head
echo "=== 2) /data/user/0 前 20 项 ==="
ls -la /data/user/0/ 2>&1 | head -20
echo "=== 3) /data/user/0 总项数 ==="
ls /data/user/0/ 2>/dev/null | wc -l
echo "=== 4) 直接找 os4freeform ==="
ls -d /data/user/0/*os4freeform* /data/user_de/0/*os4freeform* /data/data/*os4freeform* 2>&1
echo "=== 5) root 试着建目录 ==="
mkdir -p /data/user/0/$PKG/shared_prefs 2>&1 && echo "mkdir ok"
ls -la /data/user/0/$PKG 2>&1
echo "=== 6) 建完是否可见 ==="
ls -la /data/user/0/ 2>/dev/null | grep -i os4freeform
echo "=== 7) SELinux 模式 ==="
getenforce 2>&1
