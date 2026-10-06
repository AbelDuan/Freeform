#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
OUT=$R/tools/post-restart-0430.txt
exec > "$OUT" 2>&1
chmod 666 "$OUT" 2>/dev/null
echo "=== post-restart 0430 $(date) ==="
echo "旧 zygote=$(pidof zygote64)"
setprop ctl.restart zygote
echo "setprop rc=$?"
sleep 45
echo "45s: zygote=$(pidof zygote64) system_server=$(pidof system_server)"
sleep 55
echo "100s: systemui=$(pidof com.android.systemui) lspd=$(pidof lspd)"
echo "=== 形变探针挂载情况（LSPosed 日志） ==="
L=$(nsenter -t 1 -m -- ls -t /data/adb/lspd/log/modules_*.log 2>/dev/null | head -1)
nsenter -t 1 -m -- sh -c "grep -h -E '形变探针' $L 2>/dev/null | head -12" | sed 's/.*OS4FreeFromX.//'
echo "=== 等待侧边栏小窗出现（最多 180s） ==="
i=0
while [ $i -lt 90 ]; do
  dumpsys activity activities 2>/dev/null | grep -q 'mode=freeform' && break
  sleep 2; i=$((i+1))
done
echo "等到 t=$((i*2))s"
echo "--- 旋转前 bounds ---"
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -2
echo "=== 旋转到横屏 ==="
wm user-rotation lock 1 2>&1
sleep 16
echo "--- 旋转后 bounds ---"
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -2
echo "--- 形变探针（args -> res + 调用栈） ---"
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E '形变探针\[' | sed 's/.*OS4FreeFromX: //' | tail -25
echo "--- SystemUI 侧链路 ---"
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E '配置变更|配置套用|位置/尺寸|核对' | sed 's/.*OS4FreeFromX: //' | tail -8
wm user-rotation lock 0 2>&1
sleep 3
wm user-rotation free 2>&1
echo "=== report end $(date) ==="
chmod 666 "$OUT" 2>/dev/null
