#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
S=$R/tools/shots
echo "=== 哪块屏在亮？ ==="
dumpsys display 2>/dev/null | grep -oE "uniqueId='local:[0-9]+', [0-9]+ x [0-9]+.*state (ON|OFF)" | sed -E "s/.*local:([0-9]+)', ([0-9]+) x ([0-9]+).*state (ON|OFF).*/\1 \2x\3 \4/" | head -4
echo "=== 用外屏坐标 + logical 1 解锁（080808） ==="
input keyevent KEYCODE_WAKEUP; sleep 0.5
input keyevent 82; sleep 1
for i in 1 2 3; do
  input -d 1 tap 584 1397; sleep 0.3
  input -d 1 tap 584 1152; sleep 0.3
done
sleep 1.5
dumpsys window 2>/dev/null | grep -m1 isKeyguardShowing=
screencap -p -d 4639175068132267009 $S/outer.png 2>/dev/null; chmod 644 $S/outer.png 2>/dev/null
screencap -p -d 4639175402683733248 $S/inner.png 2>/dev/null; chmod 644 $S/inner.png 2>/dev/null
ls -la $S/outer.png $S/inner.png 2>/dev/null
