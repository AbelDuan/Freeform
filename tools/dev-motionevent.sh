#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
echo "=== input 是否支持 motionevent ==="
input --help 2>&1 | grep -i motionevent
echo "=== 展开通知栏 ==="
cmd statusbar expand-notifications
sleep 3
echo "=== 长按通知再下拉 ==="
input motionevent DOWN 1200 380
sleep 1
input motionevent MOVE 1200 500
sleep 1
input motionevent MOVE 1200 800
sleep 1
input motionevent MOVE 1200 1200
sleep 1
input motionevent UP 1200 1500
sleep 4
echo "=== 自由窗口 ==="
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' | head -3
echo "=== 顶层 ==="
dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity' | head -2
screencap -p -d 4639175402683733248 $R/build/notifwin.png
chmod 644 $R/build/notifwin.png
ls -la $R/build/notifwin.png
