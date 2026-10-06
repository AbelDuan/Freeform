#!/system/bin/sh
echo "=== 1) 把 @1.0 的旧记忆改写成 @0.66（MIUI 实际缩放） ==="
for k in "com.ss.android.ugc.aweme|1168x1712" "com.tencent.mm|1168x1712" "com.ss.android.ugc.aweme|1672x2364" "com.tencent.mm|1672x2364" "top.funcun.dshfolk|1672x2364"; do
  cur=$(content call --uri content://com.abel.os4freeformx.store --method get --extra k:s:"$k" 2>/dev/null | sed -n 's/.*v=\([^}]*\).*/\1/p')
  base=$(echo "$cur" | sed 's/@.*//')
  if [ -n "$base" ]; then
    content call --uri content://com.abel.os4freeformx.store --method put --extra k:s:"$k" --extra v:s:"${base}@0.66" >/dev/null 2>&1
    echo "  $k : $cur -> ${base}@0.66"
  fi
done
echo "=== 2) 改写后 ==="
nsenter -t 1 -m -- cat /data/user/0/com.abel.os4freeformx/shared_prefs/os4freeformx_bounds.xml 2>/dev/null | grep -E 'string name'
echo "=== 3) 触发重新套用（旋转一个来回） ==="
wm user-rotation lock 1 2>&1
sleep 14
wm user-rotation lock 0 2>&1
sleep 14
echo "--- 结果 ---"
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -2
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E '核对通过|记录核对|位置/尺寸' | sed 's/.*OS4FreeFromX: //' | tail -4
wm user-rotation free 2>&1
