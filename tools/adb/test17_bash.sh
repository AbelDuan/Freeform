#!/system/bin/sh
# -*- 测试：走系统自己的 Dock 路径做「双分屏 → 三分屏 → 四分屏」 -*-
# 用法: bash test17_bash.sh
#
# 链路（框架反编译 + 用户真机演示日志确认）：
#   1. DRAGADD:1  → startIconDragSplitScreen(pi, hotArea=1) → 真·原生 SoSc 双分屏（可触摸）
#   2. DOCKADD:<pkg> → MultipleSplitController.dockMultipleSplitTasks(bundle) → 系统收起分屏、桌面出现
#                    → 1.2s 后启动 <pkg> → 系统自行 DOCK_EXIT_SOSC_TO_THREE → 原生三分屏
#   3. DOCKADD:<pkg> 再来一次 → dockMultipleTasks() → 原生四分屏
set -u

ADB="${ADB:-C:/android/sdk/platform-tools/adb.exe}"
# 自动探测设备（不再硬编码序列号）；可用 DEV=<serial> 覆盖
DEV="${DEV:-$("$ADB" devices 2>/dev/null | awk 'NR>1 && $2=="device"{print $1; exit}')}"
if [ -z "${DEV:-}" ]; then
  echo "!!! 没有可用设备。请接上手机并确认 USB 调试已授权。"
  exit 1
fi
# 产物落盘目录（可用 OUT=<dir> 覆盖）；PY 仅在生成缩略图时需要
W="${OUT:-$(cd "$(dirname "$0")/../.." && pwd)/../_out}"
mkdir -p "$W"
PY="${PY:-C:/Users/Abel/.workbuddy/binaries/python/envs/default/Scripts/python.exe}"
PKG="com.abel.os4freeformx"

A() { "$ADB" -s "$DEV" "$@"; }
SAY() { echo ""; echo "----- $* -----"; }
LOG() { A shell "logcat -d -s OS4FreeFromX:V" 2>/dev/null | tr -d '\r'; }

echo ">>> device = $DEV"
echo ">>> $(A shell getprop ro.product.model 2>/dev/null | tr -d '\r') / $(A shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')"
echo ">>> uptime = $(A shell cat /proc/uptime 2>/dev/null | cut -d' ' -f1)   (不得重启！)"
echo ">>> root   = $(A shell 'su -c id' 2>/dev/null | head -1)"

SAY "0) 解锁"
if [ "$(A shell 'dumpsys trust' 2>/dev/null | grep -o 'deviceLocked=[01]' | head -1 | cut -d= -f2)" != "0" ]; then
  A shell "input keyevent KEYCODE_WAKEUP" >/dev/null 2>&1; sleep 0.6
  A shell "input swipe 836 2000 836 700 200" >/dev/null 2>&1; sleep 2
fi
A shell "dumpsys trust" 2>/dev/null | grep -o "deviceLocked=[01]" | head -1

SAY "1) 清日志 + 回桌面 + 起一个全屏应用"
A shell "logcat -c" >/dev/null 2>&1
A shell "input keyevent 3" >/dev/null 2>&1; sleep 1.5
A shell "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p com.miui.calculator" >/dev/null 2>&1
sleep 2.5
echo "  前台: $(A shell 'dumpsys activity activities' 2>/dev/null | grep -m1 -oE 'topResumedActivity.*' | cut -c1-90)"

SAY "2) DRAGADD:1 → 造真·原生 SoSc 双分屏"
A shell "am start -n $PKG/.PickActivity --es test 'DRAGADD:1'" >/dev/null 2>&1
sleep 4
echo "  mInSplitScreen : $(A shell 'dumpsys window windows' 2>/dev/null | grep -m1 -oE 'mInSplitScreen=(true|false)')"
echo "  FocusedWindows : $(A shell dumpsys input 2>/dev/null | grep -m1 'FocusedWindows' -A1 | tail -1 | tr -d '\r' | cut -c1-100)"
A shell "screencap -p /sdcard/t17_1_sosc.png" >/dev/null 2>&1
A pull /sdcard/t17_1_sosc.png "$W/t17_1_sosc.png" >/dev/null 2>&1

SAY "3) DOCKADD:com.xingin.xhs → 系统自己的 双分屏→三分屏"
A shell "am start -n $PKG/.PickActivity --es test 'DOCKADD:com.xingin.xhs'" >/dev/null 2>&1
sleep 3
echo "  [dock 过渡中] 窗口:"
A shell "dumpsys window windows" 2>/dev/null | grep -m3 -oE "Task=[0-9]+|windowingMode=[0-9]+" | head -6
A shell "screencap -p /sdcard/t17_2_docked.png" >/dev/null 2>&1
A pull /sdcard/t17_2_docked.png "$W/t17_2_docked.png" >/dev/null 2>&1
sleep 5
echo "  [启动第3个应用后] 状态:"
echo "  mInSplitScreen : $(A shell 'dumpsys window windows' 2>/dev/null | grep -m1 -oE 'mInSplitScreen=(true|false)')"
echo "  FocusedWindows : $(A shell dumpsys input 2>/dev/null | grep -m1 'FocusedWindows' -A1 | tail -1 | tr -d '\r' | cut -c1-110)"
A shell "screencap -p /sdcard/t17_3_three.png" >/dev/null 2>&1
A pull /sdcard/t17_3_three.png "$W/t17_3_three.png" >/dev/null 2>&1

SAY "4) 三分屏各列 bounds / 输入配置"
A shell "dumpsys window windows" 2>/dev/null > "$W/t17_win3.txt"
grep -aE "mBounds=Rect|inputConfig=" "$W/t17_win3.txt" | head -20

SAY "5) 触摸自检：点每一列中心，看焦点是否转移"
"$PY" - "$W/t17_win3.txt" <<'PYEOF' > "$W/t17_frames.txt" 2>&1
import sys, re
txt = open(sys.argv[1], encoding="utf-8", errors="replace").read()
for b in re.split(r"\n  Window #", txt)[1:]:
    head = b.splitlines()[0]
    if not re.match(r".*\{u0 [a-z]", head):
        pass
    m = re.search(r"mBounds=Rect\((-?\d+), (-?\d+) - (-?\d+), (-?\d+)\)", b)
    if not m:
        continue
    l, t, r, bo = map(int, m.groups())
    if (r - l) < 300 or (r - l) > 1300 or (bo - t) < 800:
        continue
    print(f"{(l+r)//2} {(t+bo)//2} {l},{t}-{r},{bo}")
PYEOF
cat "$W/t17_frames.txt"
n=0
while read -r X Y B; do
  [ -z "${X:-}" ] && continue
  n=$((n+1))
  A shell "input tap $X $Y" >/dev/null 2>&1; sleep 1.2
  echo "  tap#$n ($X,$Y) [$B] → focus: $(A shell dumpsys input 2>/dev/null | grep -m1 'FocusedWindows' -A1 | tail -1 | tr -d '\r' | cut -c1-95)"
done < "$W/t17_frames.txt"

SAY "6) DOCKADD:com.android.deskclock → 三分屏→四分屏"
A shell "am start -n $PKG/.PickActivity --es test 'DOCKADD:com.android.deskclock'" >/dev/null 2>&1
sleep 8
echo "  mInSplitScreen : $(A shell 'dumpsys window windows' 2>/dev/null | grep -m1 -oE 'mInSplitScreen=(true|false)')"
A shell "screencap -p /sdcard/t17_4_four.png" >/dev/null 2>&1
A pull /sdcard/t17_4_four.png "$W/t17_4_four.png" >/dev/null 2>&1

SAY "7) 模块日志（关键行）"
LOG | grep -aE "测试入口|加分屏\(dock\)|DOCKADD|startMultipleSplits|dragAddSplit" | tail -25

SAY "8) 设备健康"
echo "  sysui pid : $(A shell 'pidof com.android.systemui' 2>/dev/null)"
echo "  uptime    : $(A shell cat /proc/uptime 2>/dev/null | cut -d' ' -f1)"
echo "  root      : $(A shell 'su -c id' 2>/dev/null | head -1 | cut -d' ' -f1)"

SAY "9) 缩略图"
for f in t17_1_sosc t17_2_docked t17_3_three t17_4_four; do
  [ -f "$W/$f.png" ] && "$PY" -c "
from PIL import Image
im=Image.open(r'$W/$f.png'); im.thumbnail((820,820)); im.save(r'$W/small_$f.png'); print('  small_$f.png', im.size)"
done
echo ""
echo "=== TEST17 DONE ==="
