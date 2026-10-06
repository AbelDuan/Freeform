#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
S=$R/tools/shots
PKG=com.tencent.mm
echo "=== 写 1:1 记忆（scale=0.66，= 菜单现在的写法） ==="
for p in com.tencent.mm com.ss.android.ugc.aweme top.funcun.dshfolk; do
  content call --uri content://com.abel.os4freeformx.store --method put \
    --extra k:s:"$p|1672x2364" --extra v:s:"0,397,1672,2069@0.66" >/dev/null 2>&1
done
echo "--- 记忆现状 ---"
nsenter -t 1 -m -- cat /data/user/0/com.abel.os4freeformx/shared_prefs/os4freeformx_bounds.xml 2>/dev/null | grep -E '1672x2364' | head -4
wm user-rotation lock 0 2>&1
sleep 4
echo "=== 1) 原生开窗 ==="
input keyevent 187; sleep 3
input swipe 1147 836 1147 836 900; sleep 3
uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
L=$(grep -o 'content-desc="该应用支持小窗"[^/]*' /data/local/tmp/ui.xml | head -1)
B=$(echo "$L" | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]')
echo "$B" | sed -E 's/^\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]$/\1 \2 \3 \4/' > /data/local/tmp/xy.txt
X0=$(cut -d' ' -f1 /data/local/tmp/xy.txt); Y0=$(cut -d' ' -f2 /data/local/tmp/xy.txt)
X1=$(cut -d' ' -f3 /data/local/tmp/xy.txt); Y1=$(cut -d' ' -f4 /data/local/tmp/xy.txt)
input tap $(( (X0+X1)/2 )) $(( (Y0+Y1)/2 ))
sleep 14
echo "--- 开窗后 ---"
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -1
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E '核对通过|记录核对' | sed 's/.*OS4FreeFromX: //' | tail -1
echo "=== 2) 关闭（最近任务上滑） ==="
input keyevent 187; sleep 3
input swipe 1170 900 1170 200 300; sleep 4
echo "=== 3) 再打开 ==="
input keyevent 187; sleep 3
input swipe 1147 836 1147 836 900; sleep 3
uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
L=$(grep -o 'content-desc="该应用支持小窗"[^/]*' /data/local/tmp/ui.xml | head -1)
B=$(echo "$L" | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]')
echo "$B" | sed -E 's/^\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]$/\1 \2 \3 \4/' > /data/local/tmp/xy.txt
X0=$(cut -d' ' -f1 /data/local/tmp/xy.txt); Y0=$(cut -d' ' -f2 /data/local/tmp/xy.txt)
X1=$(cut -d' ' -f3 /data/local/tmp/xy.txt); Y1=$(cut -d' ' -f4 /data/local/tmp/xy.txt)
input tap $(( (X0+X1)/2 )) $(( (Y0+Y1)/2 ))
sleep 14
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -1
screencap -p $S/sv6-portrait.png; chmod 644 $S/sv6-portrait.png 2>/dev/null
echo "=== 4) 旋转 ==="
wm user-rotation lock 1 2>&1
sleep 18
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -1
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E '核对通过|记录核对|配置变更|不重开' | sed 's/.*OS4FreeFromX: //' | tail -3
screencap -p $S/sv6-landscape.png; chmod 644 $S/sv6-landscape.png 2>/dev/null
wm user-rotation lock 0 2>&1
sleep 3
wm user-rotation free 2>&1
ls -la $S/sv6-*.png 2>/dev/null
