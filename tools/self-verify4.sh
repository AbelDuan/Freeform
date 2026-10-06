#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
S=$R/tools/shots
PKG=top.funcun.dshfolk
echo "=== 写 1:1 记忆 ==="
content call --uri content://com.abel.os4freeformx.store --method put \
  --extra k:s:"$PKG|1672x2364" --extra v:s:"0,397,1672,2069@0.66" >/dev/null 2>&1
wm user-rotation lock 0 2>&1
sleep 4
echo "=== 最近任务：长按卡片唤出菜单 ==="
input keyevent 187
sleep 3
input swipe 1147 836 1147 836 900
sleep 3
for i in 1 2 3; do
  uiautomator dump /data/local/tmp/ui.xml 2>&1 | grep -q 'dumped to' && break
  sleep 2
done
L=$(grep -o 'content-desc="该应用支持小窗"[^/]*' /data/local/tmp/ui.xml 2>/dev/null | head -1)
echo "菜单项: $L"
B=$(echo "$L" | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]')
echo "$B" | sed -E 's/^\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]$/\1 \2 \3 \4/' > /data/local/tmp/xy.txt
X0=$(cut -d' ' -f1 /data/local/tmp/xy.txt); Y0=$(cut -d' ' -f2 /data/local/tmp/xy.txt)
X1=$(cut -d' ' -f3 /data/local/tmp/xy.txt); Y1=$(cut -d' ' -f4 /data/local/tmp/xy.txt)
if [ -n "$X0" ]; then
  CX=$(( (X0+X1)/2 )); CY=$(( (Y0+Y1)/2 ))
  echo "点小窗 $CX,$CY"
  input tap $CX $CY
  sleep 14
  echo "--- 竖屏 bounds / 记录 ---"
  dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -2
  logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E '记录核对|核对通过' | sed 's/.*OS4FreeFromX: //' | tail -2
  screencap -p $S/sv4-portrait.png; chmod 644 $S/sv2-portrait.png 2>/dev/null
  echo "=== 旋转横屏 ==="
  wm user-rotation lock 1 2>&1
  sleep 16
  dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -2
  logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E '记录核对|核对通过' | sed 's/.*OS4FreeFromX: //' | tail -2
  screencap -p $S/sv4-landscape.png; chmod 644 $S/sv2-landscape.png 2>/dev/null
else
  echo "!! 没找到小窗菜单项"
fi
wm user-rotation lock 0 2>&1
sleep 3
wm user-rotation free 2>&1
ls -la $S/sv4-*.png 2>/dev/null
