#!/usr/bin/env bash
# TEST11: immersive 已置 false，重启 SystemUI 后重建三分屏，检查 FocusedWindows 是否恢复
set +e
export PATH="/usr/bin:/bin:/c/Windows/System32:$PATH"
ADB="C:/android/sdk/platform-tools/adb.exe"
DEV="02040860499C3540"
PKG="com.abel.os4freeformx"
PY="C:/Users/Abel/.workbuddy/binaries/python/envs/default/Scripts/python.exe"
W="C:/Users/Abel/WorkBuddy/2026-09-20-12-28-11"
LOG="$W/test11b.log"
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
fwin(){ sh_ "dumpsys input" | grep -A3 "FocusedWindows" | head -5 | tr -d '\r'; }
foc(){ sh_ "dumpsys window" | grep -m2 "mCurrentFocus" | tr -d '\r' | tr '\n' ' '; }

say "=== TEST11 START ==="
say "--- restart SystemUI to reload cfg ---"
sh_ "am crash com.android.systemui"; sleep 9
say "sysui pid=$(sh_ 'pidof com.android.systemui')"
say "hook immersive line: $(sh_ 'logcat -d -s OS4FreeFromX:V' | grep -m1 'installSystemUi: immersive' | tr -d '\r')"

DL=$(sh_ "dumpsys trust" | grep -o "deviceLocked=[01]" | head -1 | cut -d= -f2)
if [ "$DL" != "0" ]; then
  sh_ "input keyevent KEYCODE_WAKEUP" >/dev/null; sleep 0.5
  sh_ "input swipe 1182 1500 1182 400 180" >/dev/null; sleep 2.5
  sh_ "input keyevent 7 15 7 15 7 15" >/dev/null; sleep 0.6
  sh_ "input keyevent 66" >/dev/null; sleep 2
fi
sh_ "input keyevent 3" >/dev/null; sleep 2
launch "com.android.calendar"; launch "com.coolapk.market"; launch "com.android.deskclock"
CID=$(taskid com.android.calendar); KID=$(taskid com.coolapk.market); DID=$(taskid com.android.deskclock)
say "ids cal=$CID coolapk=$KID desk=$DID"

"$ADB" -s "$DEV" logcat -c >/dev/null 2>&1
say "--- MULTIQ:$CID|$KID|$DID (immersive=false) ---"
tcmd "MULTIQ:$CID|$KID|$DID"; sleep 4
say "focused windows:"
say "$(fwin)"
say "mCurrentFocus: $(foc)"
shot "t11_3split_immoff.png"
say "--- now tap calendar center to see if focus moves ---"
sh_ "input tap 609 750" >/dev/null; sleep 1.5
say "after tap, mCurrentFocus: $(foc)"
say "after tap, focused windows:"
say "$(fwin)"
say "=== TEST11 END ==="
