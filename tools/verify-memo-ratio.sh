#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
PKG=com.ss.android.ugc.aweme
KEY="$PKG|1672x2364"
echo "=== 1) 写入 21:9 记忆（1170x501, scale=1.0） ==="
content call --uri content://com.abel.os4freeformx.store --method put \
  --extra k:s:"$KEY" --extra v:s:"416,140,1586,641@1.0" 2>&1
echo "--- 读回 ---"
content call --uri content://com.abel.os4freeformx.store --method get --extra k:s:"$KEY" 2>&1
echo "=== 2) 命令开抖音小窗（新窗口） ==="
am start --windowingMode 5 -n com.ss.android.ugc.aweme/.splash.SplashActivity 2>&1 | head -2
sleep 10
echo "=== 3) 窗口真实 bounds ==="
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A1 | grep -oE 'mBounds=Rect\([^)]*\)' | head -3
echo "=== 4) 恢复链路日志 ==="
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E '位置/尺寸|套用比例|重新拉起|记忆 key|核对' | tail -10
