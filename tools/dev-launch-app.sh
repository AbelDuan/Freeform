#!/system/bin/sh
PKG=com.abel.os4freeformx
echo "=== 1) 原生启动模块 App（触发惰性数据目录创建） ==="
am start -n $PKG/.SettingsActivity 2>&1
sleep 4
echo "=== 2) 数据目录 ==="
ls -la /data/user/0/$PKG/ 2>&1
echo "=== 3) shared_prefs ==="
ls -la /data/user/0/$PKG/shared_prefs/ 2>&1
echo "=== 4) 进程 ==="
ps -A 2>/dev/null | grep -i os4freeform
echo "=== 5) keyguard 状态 ==="
dumpsys window 2>/dev/null | grep -E 'isKeyguardShowing=' | head -2
