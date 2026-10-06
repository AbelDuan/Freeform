#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
S=$R/tools/shots
echo "=== 按菜单写法：三个应用都写 1:1 且 scale=1.0（applyRatio 就是这么写的） ==="
for p in com.tencent.mm com.ss.android.ugc.aweme top.funcun.dshfolk; do
  content call --uri content://com.abel.os4freeformx.store --method put \
    --extra k:s:"$p|1672x2364" --extra v:s:"0,397,1672,2069@1.0" >/dev/null 2>&1
  echo "  $p -> 0,397,1672,2069@1.0"
done
wm user-rotation lock 0 2>&1
sleep 4
echo "=== 最近任务 → 长按卡片 → 小窗（MIUI 原生） ==="
input keyevent 187
sleep 3
input swipe 1147 836 1147 836 900
sleep 3
for i in 1 2 3; do uiautomator dump /data/local/tmp/ui.xml 2>&1 | grep -q 'dumped to' && break; sleep 2; done
L=$(grep -o 'content-desc="该应用支持小窗"[^/]*' /data/local/tmp/ui.xml 2>/dev/null | head -1)
B=$(echo "$L" | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]')
echo "$B" | sed -E 's/^\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]$/\1 \2 \3 \4/' > /data/local/tmp/xy.txt
X0=$(cut -d' ' -f1 /data/local/tmp/xy.txt); Y0=$(cut -d' ' -f2 /data/local/tmp/xy.txt)
X1=$(cut -d' ' -f3 /data/local/tmp/xy.txt); Y1=$(cut -d' ' -f4 /data/local/tmp/xy.txt)
CX=$(( (X0+X1)/2 )); CY=$(( (Y0+Y1)/2 ))
echo "点小窗 $CX,$CY"
input tap $CX $CY
sleep 14
echo "--- 竖屏 ---"
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -2
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E '核对通过|记录核对' | sed 's/.*OS4FreeFromX: //' | tail -2
screencap -p $S/sv5-portrait.png; chmod 644 $S/sv5-portrait.png 2>/dev/null
echo "=== 旋转 ==="
wm user-rotation lock 1 2>&1
sleep 18
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -2
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E '核对通过|记录核对' | sed 's/.*OS4FreeFromX: //' | tail -2
screencap -p $S/sv5-landscape.png; chmod 644 $S/sv5-landscape.png 2>/dev/null
wm user-rotation lock 0 2>&1
sleep 3
wm user-rotation free 2>&1
ls -la $S/sv5-*.png 2>/dev/null
