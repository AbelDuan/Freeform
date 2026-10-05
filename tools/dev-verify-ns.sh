#!/system/bin/sh
PKG=com.abel.os4freeformx
echo "=== 1) 通过 Provider 读全部记忆（走 App 自己的命名空间） ==="
content call --uri content://com.abel.os4freeformx.store --method getAll 2>&1
echo "=== 2) 走 PID 1 的真实命名空间看文件系统 ==="
if command -v nsenter >/dev/null 2>&1; then
  nsenter -t 1 -m -- ls -la /data/user/0/$PKG/ 2>&1
  nsenter -t 1 -m -- ls -la /data/user/0/$PKG/shared_prefs/ 2>&1
  nsenter -t 1 -m -- cat /data/user/0/$PKG/shared_prefs/os4freeformx_bounds.xml 2>&1
else
  echo "(no nsenter)"
fi
echo "=== 3) 清理我在 tmpfs 桩里误建的目录 ==="
rmdir /data/user/0/$PKG/shared_prefs /data/user/0/$PKG 2>&1
ls -la /data/user/0/ 2>/dev/null
echo "=== 4) 当前安装的 ceDataInode ==="
dumpsys package $PKG 2>/dev/null | grep -E 'ceDataInode|notLaunched'
