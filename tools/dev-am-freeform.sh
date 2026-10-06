#!/system/bin/sh
echo "=== A) am start 无参数用法 ==="
am start 2>&1 | head -40
echo "=== B) 解析微信/抖音启动 Activity ==="
cmd package resolve-activity --brief com.tencent.mm 2>/dev/null | tail -1
cmd package resolve-activity --brief com.ss.android.ugc.aweme 2>/dev/null | tail -1
echo "=== C) 微信按自由窗口模式启动 ==="
am start --windowingMode 5 -n com.tencent.mm/.ui.LauncherUI 2>&1 | head -3
sleep 6
echo "--- 任务模式 ---"
dumpsys activity activities 2>/dev/null | grep -E 'Task\{.*com.tencent.mm' | head -3
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A1 | grep -oE 'mBounds=Rect\([^)]*\)' | head -3
