#!/usr/bin/env bash
# TEST14: FW:(openWindowFromFullscreen) 造 2 分屏 → DRAGADD 试原生加层
set +e
export PATH="/usr/bin:/bin:/c/Windows/System32:$PATH"
ADB="C:/android/sdk/platform-tools/adb.exe"
DEV="02040860499C3540"
PKG="com.abel.os4freeformx"
PY="C:/Users/Abel/.workbuddy/binaries/python/envs/default/Scripts/python.exe"
W="C:/Users/Abel/WorkBuddy/2026-09-20-12-28-11"
LOG="$W/test14b.log"; : > "$LOG"
sh_(){ "$ADB" -s "$DEV" shell "$1" 2>/dev/null; }
ts(){ date +%H:%M:%S; }
say(){ echo "[$(ts)] $1"; echo "[$(ts)] $1" >> "$LOG"; }
launch(){ sh_ "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $1" >/dev/null; sleep 2.5; }
tcmd(){ sh_ "am start -n $PKG/.PickActivity --es test '$1'" >/dev/null; sleep 1; }
shot(){ sh_ "screencap -p /sdcard/sh.png"; "$ADB" -s "$DEV" pull /sdcard/sh.png "$W/$1" >/dev/null 2>&1; "$PY" -c "
from PIL import Image
im=Image.open(r'$W/$1'); im.thumbnail((860,860)); im.save(r'$W/small_$1'); print('  small_$1',im.size)"; }
fwin(){ sh_ "dumpsys input" | grep -m1 "FocusedWindows" -A1 | tr '\n' ' ' | tr -d '\r' | cut -c1-120; }
dumpsplit(){ sh_ "dumpsys window" | grep -m1 "mCurrentFocus" | tr -d '\r'; }

say "=== TEST14 START ==="
DL=$(sh_ "dumpsys trust" | grep -o "deviceLocked=[01]" | head -1 | cut -d= -f2)
if [ "$DL" != "0" ]; then
  sh_ "input keyevent KEYCODE_WAKEUP" >/dev/null; sleep 0.5
  sh_ "input swipe 1182 1500 1182 400 180" >/dev/null; sleep 2.5
  sh_ "input keyevent 7 15 7 15 7 15" >/dev/null; sleep 0.6
  sh_ "input keyevent 66" >/dev/null; sleep 2
fi
sh_ "input keyevent 3" >/dev/null; sleep 2
launch "com.coolapk.market"; launch "com.android.calendar"   # calendar 前台
"$ADB" -s "$DEV" logcat -c >/dev/null 2>&1

say "--- FW: (openWindowFromFullscreen on 前台 calendar) ---"
tcmd "FW:"; sleep 4
shot "t14_1_fw.png"; say "  focus=$(dumpsplit)"
say "  log: $(sh_ 'logcat -d -s OS4FreeFromX:V' | grep -E 'openWindowFromFullscreen|进分屏' | tail -2 | tr -d '\r' | tr '\n' ' ')"

for HOT in 1 9 7; do
  say "--- DRAGADD:$HOT|com.miui.calculator ---"
  tcmd "DRAGADD:$HOT|com.miui.calculator"; sleep 4
  shot "t14_drag_$HOT.png"
  say "  focus=$(dumpsplit)  fwin=$(fwin)"
  say "  log: $(sh_ 'logcat -d -s OS4FreeFromX:V' | grep -E 'dragAddSplit|加分屏\(drag\)' | tail -2 | tr -d '\r' | tr '\n' ' ')"
  sh_ "input keyevent 3" >/dev/null; sleep 1.5
  tcmd "FW:"; sleep 3
done

{ echo "----- MODLOG -----"; "$ADB" -s "$DEV" logcat -d -v time -s "OS4FreeFromX:V" AndroidRuntime:E 2>/dev/null | tail -90; } >> "$LOG"
say "=== TEST14 END ==="
