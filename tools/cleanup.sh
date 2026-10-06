#!/system/bin/sh
echo "=== 1) 旋转恢复为自动 ==="
wm user-rotation free 2>&1
echo "=== 2) 删除我注入的 aweme 合成记忆（避免污染用户记忆） ==="
content call --uri content://com.abel.os4freeformx.store --method remove --arg "com.ss.android.ugc.aweme|1672x2364" 2>&1
echo "--- 剩余记忆 ---"
nsenter -t 1 -m -- cat /data/user/0/com.abel.os4freeformx/shared_prefs/os4freeformx_bounds.xml 2>&1
echo "=== 3) 当前自由窗口 ==="
dumpsys activity activities 2>/dev/null | grep -E 'Task\{.*mode=freeform' | head -3
echo "=== 4) 当前用户旋转设置 ==="
settings get system user_rotation 2>&1
settings get system accelerometer_rotation 2>&1
