#!/system/bin/sh
echo "=== 强制杀死旧进程，制造全新 fork ==="
am force-stop com.android.settings
am force-stop com.android.externalstorage
sleep 3
am start -n com.android.settings/.MainSettings >/dev/null 2>&1
sleep 8
for P in com.android.settings com.android.externalstorage; do
  PID=$(pidof $P | tr ' ' '\n' | head -1)
  if [ -z "$PID" ]; then echo "$P: 未运行"; continue; fi
  echo "--- $P pid=$PID ---"
  ps -o pid,etime -p $PID 2>/dev/null | tail -1
  echo "  模块映射数: $(grep -cE 'hyperceiler|noactive|os4freeformx|hyperosglass|hyperplus' /proc/$PID/maps 2>/dev/null)"
  echo "  映射到的模块:"
  grep -oE '/data/app/[^ ]*/(com\.sevtinge[^ ]*|cn\.myflv[^ ]*|com\.abel[^ ]*|io\.github[^ ]*)base.apk' /proc/$PID/maps 2>/dev/null | sort -u | head -5
done
echo "=== 最新 daemon 日志尾部 ==="
L=$(ls -t /data/adb/lspd/log/verbose_*.log 2>/dev/null | head -1)
tail -6 "$L" 2>/dev/null
