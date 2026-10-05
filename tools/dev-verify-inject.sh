#!/system/bin/sh
sleep 12
echo "=== 1) SystemUI 进程 ==="
pidof com.android.systemui
echo "=== 2) 模块注入日志（新 SystemUI 启动后） ==="
logcat -d 2>/dev/null | grep -F 'OS4FreeFromX' | grep -E 'installSystemUi|installSystemServer|hook 成功|手势功能已移除|不介入通知小窗|共挂' | tail -20
echo "=== 3) 最近 30 条模块日志 ==="
logcat -d 2>/dev/null | grep -F 'OS4FreeFromX' | tail -30
echo "=== 4) 模块 App 数据目录（真实命名空间） ==="
nsenter -t 1 -m -- ls -la /data/user/0/com.abel.os4freeformx/shared_prefs/ 2>&1
echo "=== 5) LSPosed 数据库复核 ==="
nsenter -t 1 -m -- ls -la /data/adb/lspd/config/modules_config.db 2>&1
