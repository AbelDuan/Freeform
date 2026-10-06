#!/system/bin/sh
# 全自动开小窗：遍历最近任务卡片 → 长按 → 若菜单含"该应用支持小窗"则点它
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
input keyevent 187
sleep 3
for i in 1 2 3; do uiautomator dump /data/local/tmp/ui.xml 2>&1 | grep -q 'dumped to' && break; sleep 2; done
grep -o 'content-desc="[^"]*,未加锁"[^/]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' /data/local/tmp/ui.xml > /data/local/tmp/cards.txt
N=$(wc -l < /data/local/tmp/cards.txt)
echo "卡片数: $N"
i=1
while [ $i -le $N ]; do
  B=$(sed -n "${i}p" /data/local/tmp/cards.txt | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]')
  echo "$B" | sed -E 's/^\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]$/\1 \2 \3 \4/' > /data/local/tmp/xy.txt
  X0=$(cut -d' ' -f1 /data/local/tmp/xy.txt); Y0=$(cut -d' ' -f2 /data/local/tmp/xy.txt)
  X1=$(cut -d' ' -f3 /data/local/tmp/xy.txt); Y1=$(cut -d' ' -f4 /data/local/tmp/xy.txt)
  CX=$(( (X0 + X1) / 2 )); CY=$(( (Y0 + Y1) / 2 ))
  echo "--- 卡片 $i bounds=$B 中心=$CX,$CY ---"
  input swipe $CX $CY $CX $CY 900
  sleep 2.5
  for j in 1 2 3; do uiautomator dump /data/local/tmp/ui2.xml 2>&1 | grep -q 'dumped to' && break; sleep 2; done
  L=$(grep -o 'content-desc="该应用支持小窗"[^/]*' /data/local/tmp/ui2.xml 2>/dev/null | head -1)
  if [ -n "$L" ]; then
    echo "命中：卡片 $i 支持小窗"
    MB=$(echo "$L" | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]')
    echo "$MB" | sed -E 's/^\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]$/\1 \2 \3 \4/' > /data/local/tmp/xy2.txt
    MX0=$(cut -d' ' -f1 /data/local/tmp/xy2.txt); MY0=$(cut -d' ' -f2 /data/local/tmp/xy2.txt)
    MX1=$(cut -d' ' -f3 /data/local/tmp/xy2.txt); MY1=$(cut -d' ' -f4 /data/local/tmp/xy2.txt)
    MCX=$(( (MX0 + MX1) / 2 )); MCY=$(( (MY0 + MY1) / 2 ))
    echo "点小窗 at $MCX,$MCY"
    input tap $MCX $MCY
    break
  fi
  input keyevent 4
  sleep 1.5
  i=$((i + 1))
done
sleep 7
echo "=== 自由窗口 ==="
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' | head -2
echo "=== 模块恢复链路日志 ==="
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -vE 'TRACE|SplitTrace|沉浸:|多分屏|分屏吸附|小白条|AppCtx' | tail -20
