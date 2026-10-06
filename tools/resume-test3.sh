#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
S=$R/tools/shots
echo "=== 状态 ==="
dumpsys window 2>/dev/null | grep -m1 isKeyguardShowing=
dumpsys power 2>/dev/null | grep -m1 mWakefulness=
dumpsys display 2>/dev/null | grep -oE "uniqueId='local:[0-9]+', [0-9]+ x [0-9]+" | head -3
KG=$(dumpsys window 2>/dev/null | grep -m1 -o 'isKeyguardShowing=[a-z]*')
if echo "$KG" | grep -q 'true'; then
  echo "!! 仍是锁屏 —— 自测无法进行"
  screencap -p -d 4639175402689345216 $S/x.png 2>/dev/null
  exit 0
fi
echo "=== 安装 v0.4.37 ==="
cp -f $R/dist/OS4FreeFromX-v0.4.37.apk /data/local/tmp/os4ffx.apk
chmod 644 /data/local/tmp/os4ffx.apk
pm install -r /data/local/tmp/os4ffx.apk 2>&1
dumpsys package com.abel.os4freeformx 2>/dev/null | grep versionName | head -1
killall com.android.systemui
sleep 16
echo "=== 开始自测 ==="
PKG=com.tencent.mm
for p in com.tencent.mm com.ss.android.ugc.aweme top.funcun.dshfolk; do
  content call --uri content://com.abel.os4freeformx.store --method put \
    --extra k:s:"$p|1672x2364" --extra v:s:"0,397,1672,2069@0.66" >/dev/null 2>&1
done
wm user-rotation lock 0 2>&1
sleep 4
echo "--- 最近任务 → 长按 → 小窗 ---"
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
echo "--- 竖屏 ---"
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -1
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E '核对通过|记录核对' | sed 's/.*OS4FreeFromX: //' | tail -1
screencap -p $S/rt3-portrait.png; chmod 644 $S/rt-portrait.png 2>/dev/null
echo "--- 旋转（等 12s） ---"
wm user-rotation lock 1 2>&1
sleep 20
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -1
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E '核对通过|记录核对|延迟套用|配置变更' | sed 's/.*OS4FreeFromX: //' | tail -4
screencap -p $S/rt3-landscape.png; chmod 644 $S/rt-landscape.png 2>/dev/null
wm user-rotation lock 0 2>&1; sleep 3; wm user-rotation free 2>&1
ls -la $S/rt3-*.png 2>/dev/null
