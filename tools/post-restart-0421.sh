#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
OUT=$R/tools/post-restart-0421.txt
exec > "$OUT" 2>&1
chmod 666 "$OUT" 2>/dev/null
echo "=== post-restart 0421 $(date) ==="
echo "旧 zygote=$(pidof zygote64)"
setprop ctl.restart zygote
echo "setprop rc=$?"
sleep 45
echo "45s: zygote=$(pidof zygote64) system_server=$(pidof system_server)"
sleep 55
echo "100s: systemui=$(pidof com.android.systemui) lspd=$(pidof lspd)"
echo "=== 1) 方法名清单（一行） ==="
L=$(nsenter -t 1 -m -- ls -t /data/adb/lspd/log/modules_*.log 2>/dev/null | head -1)
echo "log=$L"
nsenter -t 1 -m -- sh -c "grep -h -E '系统侧探针|系统探针: hook 成功|系统探针: 共挂' $L 2>/dev/null" | sed 's/.*OS4FreeFromX.//' | head -20
echo "=== 2) 旋转测试（1:1 抖音） ==="
PKG=com.ss.android.ugc.aweme
wm user-rotation lock 0 2>&1
sleep 5
am force-stop $PKG
sleep 3
am start --windowingMode 5 -n $PKG/.splash.SplashActivity 2>&1 | head -1
sleep 12
wm user-rotation lock 1 2>&1
sleep 18
echo "--- 窗口 bounds ---"
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' -A3 | grep -oE 'mBounds=Rect\([^)]*\)' | head -2
echo "--- 系统探针触发记录（哪些真的执行了） ---"
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E '系统探针\[' | sed 's/.*OS4FreeFromX: //' | tail -20
echo "--- 模块链路 ---"
logcat -d 2>/dev/null | grep -F OS4FreeFromX | grep -E '配置变更|配置套用|尺寸档位' | sed 's/.*OS4FreeFromX: //' | tail -8
wm user-rotation lock 0 2>&1
sleep 3
wm user-rotation free 2>&1
echo "=== report end $(date) ==="
chmod 666 "$OUT" 2>/dev/null
