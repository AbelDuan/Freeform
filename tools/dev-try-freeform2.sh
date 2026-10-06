#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
echo "=== 1) 解析可用 Activity ==="
for p in com.android.settings com.miui.notes com.android.documentsui com.miui.gallery; do
  echo "$p -> $(cmd package resolve-activity --brief $p 2>/dev/null | tail -1)"
done
echo "=== 2) 尝试 --windowingMode 5 ==="
ACT=$(cmd package resolve-activity --brief com.android.settings 2>/dev/null | tail -1)
am start --windowingMode 5 -n "$ACT" 2>&1 | head -3
sleep 6
echo "--- 是否有自由窗口任务 ---"
dumpsys activity activities 2>/dev/null | grep -iE 'mode=freeform|windowingMode=5' | head -5
echo "--- 顶层 ---"
dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity' | head -2
echo "=== 3) 若没有，试 MIUI 上滑悬停拖左上角 ==="
dumpsys activity activities 2>/dev/null | grep -q 'mode=freeform' || {
  input swipe 836 2280 300 500 2500
  sleep 4
  dumpsys activity activities 2>/dev/null | grep -iE 'mode=freeform' | head -3
}
echo "=== 4) 截图 ==="
screencap -p -d 4639175402683733248 $R/build/try-freeform.png
chmod 644 $R/build/try-freeform.png
ls -la $R/build/try-freeform.png
