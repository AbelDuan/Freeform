#!/system/bin/sh
echo "=== 1) 各进程启动时长（判断是否 daemon 重启后 fork） ==="
ps -o pid,etime,args -p 22804 -p 30884 -p 22387 -p 16194 -p 13546 2>&1
echo "=== 2) com.android.settings 里映射的是哪个模块 ==="
grep -oE '/data/app/[^ ]*(base.apk)' /proc/22804/maps 2>/dev/null | sort -u | head -8
echo "=== 3) 最新 daemon 日志里提到 systemui / 13546 的行 ==="
L=$(ls -t /data/adb/lspd/log/verbose_*.log 2>/dev/null | head -1)
echo "log=$L"
grep -iE 'systemui|13546' "$L" 2>/dev/null | tail -8
echo "=== 4) 日志里是否记录了进程 attach（任意进程名） ==="
grep -oE '\([a-z0-9._:]+\)\[' "$L" 2>/dev/null | sort -u | head -20
echo "=== 5) 新 fork 一个普通 App 做对照：启动 settings ==="
am start -n com.android.settings/.MainSettings >/dev/null 2>&1
sleep 6
SP=$(pidof com.android.settings | tr ' ' '\n' | head -1)
echo "settings pid=$SP"
echo "  模块映射数: $(grep -cE 'hyperceiler|os4freeformx' /proc/$SP/maps 2>/dev/null)"
