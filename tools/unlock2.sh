#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
S=$R/tools/shots
echo "=== 显示映射 ==="
dumpsys display 2>/dev/null | grep -oE 'displayId=[0-9], uniqueId=.local:[0-9]+.|state (ON|OFF)' | head -6
echo "=== 唤醒 + 密码盘（内屏 logical 1） ==="
input keyevent KEYCODE_WAKEUP
sleep 0.5
input keyevent 82
sleep 1
uiautomator dump /data/local/tmp/kb.xml >/dev/null 2>&1
K0=$(grep -o 'resource-id="[^"]*key0"[^/]*' /data/local/tmp/kb.xml | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]')
K8=$(grep -o 'resource-id="[^"]*key8"[^/]*' /data/local/tmp/kb.xml | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]')
echo "key0=$K0 key8=$K8"
for b in "$K0" "$K8"; do echo "$b" | sed -E 's/^\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]$/\1 \2 \3 \4/' > /data/local/tmp/b.txt; done
echo "$K0" | sed -E 's/^\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]$/\1 \2 \3 \4/' > /data/local/tmp/b0.txt
echo "$K8" | sed -E 's/^\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]$/\1 \2 \3 \4/' > /data/local/tmp/b8.txt
C0X=$(( ($(cut -d' ' -f1 /data/local/tmp/b0.txt) + $(cut -d' ' -f3 /data/local/tmp/b0.txt)) / 2 ))
C0Y=$(( ($(cut -d' ' -f2 /data/local/tmp/b0.txt) + $(cut -d' ' -f4 /data/local/tmp/b0.txt)) / 2 ))
C8X=$(( ($(cut -d' ' -f1 /data/local/tmp/b8.txt) + $(cut -d' ' -f3 /data/local/tmp/b8.txt)) / 2 ))
C8Y=$(( ($(cut -d' ' -f2 /data/local/tmp/b8.txt) + $(cut -d' ' -f4 /data/local/tmp/b8.txt)) / 2 ))
echo "内屏点 0=($C0X,$C0Y) 8=($C8X,$C8Y)"
for i in 1 2 3; do
  input -d 1 tap $C0X $C0Y; sleep 0.3
  input -d 1 tap $C8X $C8Y; sleep 0.3
done
sleep 1.5
echo "=== 结果 ==="
dumpsys window 2>/dev/null | grep -m1 isKeyguardShowing=
screencap -p -d 4639175402683733248 $S/unlocked2.png 2>/dev/null
chmod 644 $S/unlocked2.png 2>/dev/null
ls -la $S/unlocked2.png 2>/dev/null
