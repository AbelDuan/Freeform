#!/usr/bin/env bash
# TEST13: 原生 drag-to-split 入口 startIconDragSplitScreen 各种 hotArea 试
#   1) MAKEPAIR 造 SoSc 2 分屏
#   2) DRAGADD:<hot>|<pkg> 试 1 / 9 / 7
# 每步截图 + FocusedWindows + 应用窗口 inputConfig
set +e
export PATH="/usr/bin:/bin:/c/Windows/System32:$PATH"
ADB="C:/android/sdk/platform-tools/adb.exe"
DEV="02040860499C3540"
PKG="com.abel.os4freeformx"
PY="C:/Users/Abel/.workbuddy/binaries/python/envs/default/Scripts/python.exe"
W="C:/Users/Abel/WorkBuddy/2026-09-20-12-28-11"
LOG="$W/test13b.log"
: > "$LOG"
sh_(){ "$ADB" -s "$DEV" shell "$1" 2>/dev/null; }
ts(){ date +%H:%M:%S; }
say(){ echo "[$(ts)] $1"; echo "[$(ts)] $1" >> "$LOG"; }
taskid(){ sh_ "am stack list" | tr -d '\r' | grep -E "taskId=[0-9]+: $1/" | head -1 | sed -E 's/.*taskId=([0-9]+):.*/\1/'; }
launch(){ sh_ "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $1" >/dev/null; sleep 2.5; }
tcmd(){ sh_ "am start -n $PKG/.PickActivity --es test '$1'" >/dev/null; sleep 1; }
shot(){ sh_ "screencap -p /sdcard/sh.png"; "$ADB" -s "$DEV" pull /sdcard/sh.png "$W/$1" >/dev/null 2>&1; "$PY" -c "
from PIL import Image
im=Image.open(r'$W/$1'); im.thumbnail((860,860)); im.save(r'$W/small_$1'); print('  small_$1', im.size)"; }
fwin(){ sh_ "dumpsys input" | grep -m2 -A2 "FocusedWindows" | tr -d '\r'; }
appcfg(){ sh_ "dumpsys input" | grep -m2 -E "name=[0-9a-f]+ (com.android.calendar|com.coolapk.market|com.android.deskclock)" | sed -E 's/, globalScale.*inputConfig=/ -> /; s/, touchable.*//' | cut -c1-150; }

say "=== TEST13 START ==="
DL=$(sh_ "dumpsys trust" | grep -o "deviceLocked=[01]" | head -1 | cut -d= -f2)
if [ "$DL" != "0" ]; then
  sh_ "input keyevent KEYCODE_WAKEUP" >/dev/null; sleep 0.5
  sh_ "input swipe 1182 1500 1182 400 180" >/dev/null; sleep 2.5
  sh_ "input keyevent 7 15 7 15 7 15" >/dev/null; sleep 0.6
  sh_ "input keyevent 66" >/dev/null; sleep 2
fi
sh_ "input keyevent 3" >/dev/null; sleep 2
launch "com.android.calendar"; launch "com.coolapk.market"; launch "com.miui.calculator"
CID=$(taskid com.android.calendar); KID=$(taskid com.coolapk.market); XID=$(taskid com.miui.calculator)
say "ids cal=$CID coolapk=$KID calc=$XID"

"$ADB" -s "$DEV" logcat -c >/dev/null 2>&1
say "--- MAKEPAIR:$CID|$KID ---"
tcmd "MAKEPAIR:$CID|$KID"; sleep 3.5
shot "t13_1_makepair.png"; say "  fwin: $(fwin | tr '\n' ' ')"
say "  appcfg: $(appcfg | tr '\n' ' ')"

for HOT in 1 9 7 19; do
  say "--- DRAGADD:$HOT|com.miui.calculator ---"
  tcmd "DRAGADD:$HOT|com.miui.calculator"; sleep 3.5
  shot "t13_drag_$HOT.png"
  say "  fwin: $(fwin | tr '\n' ' ')"
  say "  appcfg: $(appcfg | tr '\n' ' ')"
  # 回桌面清理，再重新造 SoSc
  sh_ "input keyevent 3" >/dev/null; sleep 1.5
  tcmd "MAKEPAIR:$CID|$KID"; sleep 2.5
done

{ echo "----- MODLOG -----"; "$ADB" -s "$DEV" logcat -d -v time -s "OS4FreeFromX:V" AndroidRuntime:E 2>/dev/null | tail -80; } >> "$LOG"
say "=== TEST13 END ==="
