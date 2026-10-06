#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
echo "=== 1) 锁屏/亮屏 ==="
dumpsys window 2>/dev/null | grep -E 'isKeyguardShowing=' | head -1
dumpsys power 2>/dev/null | grep -E 'mWakefulness=' | head -1
echo "=== 2) 当前小窗任务 ==="
dumpsys activity activities 2>/dev/null | grep -iE 'freeform|Freeform' | head -10
echo "=== 3) 顶层 Activity ==="
dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity|mResumedActivity' | head -3
echo "=== 4) 显示映射 ==="
dumpsys display 2>/dev/null | grep -E 'mDisplayId=|uniqueId=' | head -6
echo "=== 5) 截图（内屏 1672x2364） ==="
screencap -p -d 4639175402683733248 $R/build/now-inner.png 2>&1 || screencap -p $R/build/now-inner.png
chmod 644 $R/build/now-inner.png
ls -la $R/build/now-inner.png
echo "=== 6) 截图（外屏） ==="
screencap -p -d 4639175068132267009 $R/build/now-outer.png 2>&1
chmod 644 $R/build/now-outer.png
ls -la $R/build/now-outer.png
