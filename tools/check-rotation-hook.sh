#!/system/bin/sh
echo "=== 旋转前后，模块建窗钩子是否被问过 ==="
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E '小窗 bounds 计算|套用比例目标|恢复\(|ffr-none' | sed 's/.*OS4FreeFromX: //' | tail -20
echo "=== 形变探针是否有任何输出 ==="
logcat -d 2>/dev/null | grep -c '形变探针'
echo "=== 系统探针是否有输出 ==="
logcat -d 2>/dev/null | grep -c '系统探针\['
echo "=== 当前 bounds ==="
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -2
