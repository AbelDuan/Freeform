#!/system/bin/sh
echo "=== 1) system_server(16194) maps 里的模块痕迹 ==="
grep -iE 'lumacurve|hyperceiler|lspd|zygisk' /proc/16194/maps 2>/dev/null | head -10
echo "--- 计数 ---"
echo "lumacurve: $(grep -c lumacurve /proc/16194/maps 2>/dev/null)"
echo "=== 2) 新 SystemUI(13546) 全部非 system/app 映射里是否有模块 ==="
grep -E '/data/(app|adb)' /proc/13546/maps 2>/dev/null | head -10
echo "--- 计数 ---"
echo "data/app|adb: $(grep -cE '/data/(app|adb)' /proc/13546/maps 2>/dev/null)"
echo "=== 3) 已注入进程清单（含各模块） ==="
for d in /proc/[0-9]*; do
  p=${d#/proc/}
  if grep -qE 'lumacurve|hyperceiler|os4freeformx|noactive|hyperosglass|hyperplus|hyperisland|wechatlive|guise' $d/maps 2>/dev/null; then
    echo -n "pid $p: "; cat $d/cmdline 2>/dev/null | tr '\0' ' '; echo
  fi
done
echo "=== 4) 当前 SystemUI pid ==="
pidof com.android.systemui
