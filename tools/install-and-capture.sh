#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
cp -f $R/dist/OS4FreeFromX-v0.4.32.apk /data/local/tmp/os4ffx.apk
chmod 644 /data/local/tmp/os4ffx.apk
pm install -r /data/local/tmp/os4ffx.apk 2>&1
killall com.android.systemui
sleep 16
echo "systemui=$(pidof com.android.systemui)"
OUT=$R/tools/noreopen-test.txt
: > $OUT
chmod 666 $OUT 2>/dev/null
exec >> "$OUT" 2>&1
echo "=== v0.4.32 旋转不重开测试 $(date) ==="
logcat -c
wm user-rotation lock 0 2>&1
echo "=== 等待侧边栏小窗（最多 180s） ==="
i=0
while [ $i -lt 90 ]; do
  dumpsys activity activities 2>/dev/null | grep -q 'mode=freeform' && break
  sleep 2; i=$((i+1))
done
echo "等到 t=$((i*2))s"
echo "--- 旋转前 bounds ---"
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -2
wm user-rotation lock 1 2>&1
sleep 14
echo "--- 旋转后 bounds（期望保持原形状） ---"
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -2
echo "--- 模块链路 ---"
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E '配置变更|不重开|套用比例|小窗 bounds 计算' | sed 's/.*OS4FreeFromX: //' | tail -10
wm user-rotation lock 0 2>&1
sleep 3
wm user-rotation free 2>&1
echo "=== end $(date) ==="
