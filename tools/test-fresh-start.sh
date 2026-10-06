#!/system/bin/sh
PKG=com.ss.android.ugc.aweme
echo "=== 1) 写入明确的 1:1 记忆（1672x1672） ==="
content call --uri content://com.abel.os4freeformx.store --method put \
  --extra k:s:"$PKG|1672x2364" --extra v:s:"0,397,1672,2069@1.0" 2>&1
echo "=== 2) 完全关闭 + 全新以自由窗口启动 ==="
am force-stop $PKG
sleep 4
am start --windowingMode 5 -n $PKG/.splash.SplashActivity 2>&1 | head -1
sleep 13
echo "=== 3) 实际 bounds（期望 1672x1672 正方） ==="
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -2
echo "=== 4) 模块日志 ==="
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -vE 'TRACE|HotArea|SplitTrace|沉浸|多分屏|小白条|AppCtx|窗口布局|hook 成功' | grep -E '套用比例|核对|记录核对' | tail -4
