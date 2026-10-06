#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
S=$R/tools/shots
echo "=== 1) 唤醒 + 唤出密码盘 ==="
input keyevent KEYCODE_WAKEUP
sleep 0.5
input keyevent 82
sleep 1
uiautomator dump /data/local/tmp/kb.xml >/dev/null 2>&1
echo "--- 找 key0 / key8 ---"
for k in key0 key8; do
  L=$(grep -o "resource-id=\"[^\"]*$k\"[^/]*" /data/local/tmp/kb.xml 2>/dev/null | head -1)
  B=$(echo "$L" | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]')
  echo "$B" | sed -E 's/^\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]$/\1 \2 \3 \4/' > /data/local/tmp/kb_$k.txt
  echo "  $k bounds=$B"
done
K0X=$(cut -d' ' -f1 /data/local/tmp/kb_key0.txt); K0Y=$(cut -d' ' -f2 /data/local/tmp/kb_key0.txt)
K0X1=$(cut -d' ' -f3 /data/local/tmp/kb_key0.txt); K0Y1=$(cut -d' ' -f4 /data/local/tmp/kb_key0.txt)
K8X=$(cut -d' ' -f1 /data/local/tmp/kb_key8.txt); K8Y=$(cut -d' ' -f2 /data/local/tmp/kb_key8.txt)
K8X1=$(cut -d' ' -f3 /data/local/tmp/kb_key8.txt); K8Y1=$(cut -d' ' -f4 /data/local/tmp/kb_key8.txt)
if [ -n "$K0X" ] && [ -n "$K8X" ]; then
  C0X=$(( (K0X+K0X1)/2 )); C0Y=$(( (K0Y+K0Y1)/2 ))
  C8X=$(( (K8X+K8X1)/2 )); C8Y=$(( (K8Y+K8Y1)/2 ))
  echo "点 080808: 0=($C0X,$C0Y) 8=($C8X,$C8Y)"
  for i in 1 2 3; do
    input tap $C0X $C0Y; sleep 0.25
    input tap $C8X $C8Y; sleep 0.25
  done
else
  echo "!! 没找到键盘节点，用兜底坐标（外屏 0=(584,1397) 8=(584,1152)）"
  for i in 1 2 3; do input tap 584 1397; sleep 0.25; input tap 584 1152; sleep 0.25; done
fi
sleep 1.5
echo "=== 2) 解锁结果 ==="
dumpsys window 2>/dev/null | grep -m1 isKeyguardShowing=
dumpsys power 2>/dev/null | grep -m1 mWakefulness=
screencap -p $S/after-unlock.png; chmod 644 $S/after-unlock.png 2>/dev/null
ls -la $S/after-unlock.png 2>/dev/null
