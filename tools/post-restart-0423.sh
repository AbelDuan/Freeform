#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
OUT=$R/tools/post-restart-0423.txt
exec > "$OUT" 2>&1
chmod 666 "$OUT" 2>/dev/null
echo "=== post-restart 0423 $(date) ==="
echo "旧 zygote=$(pidof zygote64)"
setprop ctl.restart zygote
echo "setprop rc=$?"
sleep 45
echo "45s: zygote=$(pidof zygote64) system_server=$(pidof system_server)"
sleep 55
echo "100s: systemui=$(pidof com.android.systemui) lspd=$(pidof lspd)"
echo "=== 探针挂载情况（LSPosed 日志） ==="
L=$(nsenter -t 1 -m -- ls -t /data/adb/lspd/log/modules_*.log 2>/dev/null | head -1)
nsenter -t 1 -m -- sh -c "grep -h -E '系统探针: hook 成功|系统探针: 共挂|系统侧探针: 方法数' $L 2>/dev/null" | sed 's/.*OS4FreeFromX.//' | head -16
echo "=== 1:1 旋转测试 ==="
PKG=com.ss.android.ugc.aweme
content call --uri content://com.abel.os4freeformx.store --method put \
  --extra k:s:"$PKG|1672x2364" --extra v:s:"0,397,1672,2069@0.66" >/dev/null 2>&1
wm user-rotation lock 0 2>&1
sleep 5
am force-stop $PKG
sleep 3
am start --windowingMode 5 -n $PKG/.splash.SplashActivity 2>&1 | head -1
sleep 12
echo "--- 旋转前 bounds ---"
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -2
wm user-rotation lock 1 2>&1
sleep 18
echo "--- 旋转后 bounds（期望仍正方） ---"
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -2
echo "--- 系统探针 / 修复杆日志 ---"
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E '系统探针|skipAutoLayout' | sed 's/.*OS4FreeFromX: //' | tail -25
echo "--- SystemUI 侧链路 ---"
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E '配置变更|配置套用|尺寸档位' | sed 's/.*OS4FreeFromX: //' | tail -6
screencap -p $R/tools/shots/after-0423.png 2>/dev/null
chmod 644 $R/tools/shots/after-0423.png 2>/dev/null
wm user-rotation lock 0 2>&1
sleep 3
wm user-rotation free 2>&1
echo "=== report end $(date) ==="
chmod 666 "$OUT" 2>/dev/null
