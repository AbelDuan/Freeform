#!/usr/bin/env bash
# TEST6 (rev): MULTIQ 重建 3 分屏后立刻抓窗口输入状态，正确解析 flags
set +e
ADB="C:/android/sdk/platform-tools/adb.exe"
DEV="02040860499C3540"
PKG="com.abel.os4freeformx"
TAG="OS4FreeFromX"
LOG="C:/Users/Abel/WorkBuddy/2026-09-20-12-28-11/test6b.log"
: > "$LOG"
sh_(){ "$ADB" -s "$DEV" shell "$1" 2>/dev/null; }
ts(){ date +%H:%M:%S; }
say(){ echo "[$(ts)] $1"; echo "[$(ts)] $1" >> "$LOG"; }
syspid(){ sh_ "pidof com.android.systemui" | tr -d '\r' | tr '\n' ','; }
BASE=""; RC=0
checkpid(){
  local step="$1"; local p; p=$(syspid)
  if [ -z "$BASE" ]; then BASE="$p"; say "BASELINE SystemUI pid=$p"
  elif [ "$p" != "$BASE" ]; then RC=$((RC+1)); say "*** SystemUI RESTART @ $step: $BASE -> $p (#$RC) ***"; BASE="$p"
  else say "$step: pid=$p (stable)"; fi
}
taskid(){ sh_ "am stack list" | tr -d '\r' | grep -E "taskId=[0-9]+: $1/" | head -1 | sed -E 's/.*taskId=([0-9]+):.*/\1/'; }
launch(){ sh_ "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $1" >/dev/null; sleep 2.5; }
tcmd(){ sh_ "am start -n $PKG/.PickActivity --es test '$1'" >/dev/null; sleep 1; }
dumpmod(){ { echo "----- MODLOG ($1) -----"; "$ADB" -s "$DEV" logcat -d -v time -s "$TAG:V" AndroidRuntime:E 2>/dev/null; } >> "$LOG"; }
dumpwin(){ local f="$1"; sh_ "dumpsys window windows" > "$f" 2>/dev/null; }

say "=== TEST6 REV START ==="
checkpid "pre"
DL=$(sh_ "dumpsys trust" | grep -o "deviceLocked=[01]" | head -1 | cut -d= -f2)
if [ "$DL" != "0" ]; then
  sh_ "input keyevent KEYCODE_WAKEUP" >/dev/null; sleep 0.5
  sh_ "input swipe 1182 1500 1182 400 180" >/dev/null; sleep 2.5
  sh_ "input keyevent 7 15 7 15 7 15" >/dev/null; sleep 0.6
  sh_ "input keyevent 66" >/dev/null; sleep 2
fi
sh_ "input keyevent 3" >/dev/null; sleep 2

launch "com.android.calendar"
launch "com.android.deskclock"
launch "com.miui.calculator"
CID=$(taskid com.android.calendar); say "calendar=$CID"
DID=$(taskid com.android.deskclock); say "deskclock=$DID"
XID=$(taskid com.miui.calculator); say "calculator=$XID"

"$ADB" -s "$DEV" logcat -c >/dev/null 2>&1
say "--- MULTIQ:$CID|$DID|$XID ---"; tcmd "MULTIQ:$CID|$DID|$XID"; sleep 2
checkpid "after-multi"
dumpwin "C:/Users/Abel/WorkBuddy/2026-09-20-12-28-11/test6_win1.txt"
sleep 2
dumpwin "C:/Users/Abel/WorkBuddy/2026-09-20-12-28-11/test6_win2.txt"
dumpmod "multi"
say "=== window dumps saved (win1/win2) ==="
checkpid "after-late"
say "=== TEST6 REV END restarts=$RC ==="
