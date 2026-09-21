#!/usr/bin/env bash
# TEST15: 直接从全屏调 DRAGADD:1（SPLIT_LEFT_OR_TOP）—— 看能否造出真 SoSc 2分屏，再叠加
set +e
export PATH="/usr/bin:/bin:/c/Windows/System32:$PATH"
ADB="C:/android/sdk/platform-tools/adb.exe"; DEV="02040860499C3540"
PKG="com.abel.os4freeformx"
PY="C:/Users/Abel/.workbuddy/binaries/python/envs/default/Scripts/python.exe"
W="C:/Users/Abel/WorkBuddy/2026-09-20-12-28-11"
LOG="$W/test15b.log"; : > "$LOG"
sh_(){ "$ADB" -s "$DEV" shell "$1" 2>/dev/null; }
say(){ echo "[$(date +%H:%M:%S)] $1" | tee -a "$LOG"; }
launch(){ sh_ "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $1" >/dev/null; sleep 2.5; }
tcmd(){ sh_ "am start -n $PKG/.PickActivity --es test '$1'" >/dev/null; sleep 1; }
shot(){ sh_ "screencap -p /sdcard/sh.png"; "$ADB" -s "$DEV" pull /sdcard/sh.png "$W/$1" >/dev/null 2>&1; "$PY" -c "
from PIL import Image;im=Image.open(r'$W/$1');im.thumbnail((860,860));im.save(r'$W/small_$1');print('  small_$1')"; }
pid(){ sh_ "pidof com.android.systemui" | tr -d '\r'; }
fwin(){ sh_ "dumpsys input" | grep -m1 "FocusedWindows" -A1 | tr '\n' ' ' | tr -d '\r' | cut -c1-110; }
st(){ sh_ "logcat -d -s OS4FreeFromX:V" | grep -aE "dragAddSplit|加分屏\(drag\)" | tail -2 | tr -d '\r' | tr '\n' ' '; }

say "=== TEST15 START pid=$(pid) ==="
DL=$(sh_ "dumpsys trust" | grep -o "deviceLocked=[01]" | head -1 | cut -d= -f2)
if [ "$DL" != "0" ]; then
  sh_ "input keyevent KEYCODE_WAKEUP" >/dev/null; sleep 0.5
  sh_ "input swipe 1182 1500 1182 400 180" >/dev/null; sleep 2.5
  sh_ "input keyevent 7 15 7 15 7 15" >/dev/null; sleep 0.6; sh_ "input keyevent 66" >/dev/null; sleep 2
fi
sh_ "input keyevent 3" >/dev/null; sleep 2
launch "com.android.calendar"   # 前台全屏
"$ADB" -s "$DEV" logcat -c >/dev/null 2>&1
say "--- DRAGADD:1|com.coolapk.market (from fullscreen) ---"
tcmd "DRAGADD:1|com.coolapk.market"; sleep 4
say "  pid=$(pid)  fwin=$(fwin)"
say "  st=$(st)"
shot "t15_a_drag1.png"
say "--- DRAGADD:1|com.miui.calculator (add layer) ---"
tcmd "DRAGADD:1|com.miui.calculator"; sleep 4
say "  pid=$(pid)  fwin=$(fwin)"
say "  st=$(st)"
shot "t15_b_add.png"
{ echo "----- MODLOG -----"; "$ADB" -s "$DEV" logcat -d -v time -s "OS4FreeFromX:V" AndroidRuntime:E 2>/dev/null | tail -60; } >> "$LOG"
say "=== TEST15 END pid=$(pid) ==="
