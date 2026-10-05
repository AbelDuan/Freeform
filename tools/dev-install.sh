#!/system/bin/sh
PKG=com.abel.os4freeformx
echo "=== 1) 卸载旧签名版本 ==="
pm uninstall $PKG 2>&1
echo "=== 2) 安装 v0.4.6 ==="
pm install /data/local/tmp/os4ffx.apk 2>&1
echo "=== 3) 复核包状态 ==="
pm path $PKG 2>&1
dumpsys package $PKG 2>/dev/null | grep -E 'versionName|versionCode|codePath|firstInstallTime' | head
echo "=== 4) 数据目录是否已重建 ==="
ls -la /data/user/0/$PKG/ 2>&1
