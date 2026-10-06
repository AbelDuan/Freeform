#!/system/bin/sh
# 自己开小窗：最近任务 → 长按卡片 → 从菜单里解析"小窗"坐标并点它
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
input keyevent 187
sleep 3
input swipe 1147 836 1147 836 900
sleep 3
for i in 1 2 3; do
  uiautomator dump /data/local/tmp/ui.xml 2>&1 | grep -q 'dumped to' && break
  sleep 2
done
LINE=$(grep -o 'content-desc="该应用支持小窗"[^/]*' /data/local/tmp/ui.xml 2>/dev/null | head -1)
echo "menu line: $LINE"
B=$(echo "$LINE" | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]')
echo "bounds: $B"
X0=$(echo "$B" | sed -E 's/^\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]$/\1/')
Y0=$(echo "$B" | sed -E 's/^\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]$/\2/')
X1=$(echo "$B" | sed -E 's/^\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]$/\3/')
Y1=$(echo "$B" | sed -E 's/^\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]$/\4/')
if [ -n "$X0" ]; then
  CX=$(( (X0 + X1) / 2 )); CY=$(( (Y0 + Y1) / 2 ))
  echo "tap 小窗 at $CX,$CY"
  input tap $CX $CY
else
  echo "!! 未找到「该应用支持小窗」"
fi
sleep 7
echo "=== 自由窗口 ==="
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' | head -2
echo "=== 模块日志（恢复链路） ==="
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -vE 'TRACE|SplitTrace|沉浸:' | tail -18
