#!/system/bin/sh
echo "=== 下拉通知 ==="
input swipe 1200 400 1200 1300 900
sleep 4
echo "=== 是否出现自由窗口 ==="
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' | head -4
echo "=== 顶层 ==="
dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity' | head -2
