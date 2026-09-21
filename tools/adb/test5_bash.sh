#!/usr/bin/env bash
# TEST5: 直接驱动 startMultipleSplits(MULTI:idA|idB|idC) 隔离验证高层多分屏接口是否真出 3+ 分屏且不崩
set +e
ADB="C:/android/sdk/platform-tools/adb.exe"
DEV="02040860499C3540"
PKG="com.abel.os4freeformx"
TAG="OS4FreeFromX"
LOG="C:/Users/Abel/WorkBuddy/2026-09-20-12-28-11/test5b.log"
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
dumpstack(){ { echo "----- STACK ($1) -----"; sh_ "am stack list" | tr -d '\r' | grep -E "taskId=[0-9]+: |mInSplitScreen=" ; } >> "$LOG"; }

say "=== TEST5 START ==="
checkpid "pre"
# unlock if locked
DL=$(sh_ "dumpsys trust" | grep -o "deviceLocked=[01]" | head -1 | cut -d= -f2)
if [ "$DL" != "0" ]; then
  sh_ "input keyevent KEYCODE_WAKEUP" >/dev/null; sleep 0.5
  sh_ "input swipe 1182 1500 1182 400 180" >/dev/null; sleep 2.5
  sh_ "input keyevent 7 15 7 15 7 15" >/dev/null; sleep 0.6
  sh_ "input keyevent 66" >/dev/null; sleep 2
  say "unlocked deviceLocked=$(sh_ "dumpsys trust" | grep -o 'deviceLocked=[01]' | head -1)"
fi
sh_ "input keyevent 3" >/dev/null; sleep 2

# 拉起三个应用
launch "com.android.calendar"
launch "com.coolapk.market"
launch "com.xingin.xhs"
CID=$(taskid com.android.calendar); say "calendar=$CID"
KID=$(taskid com.coolapk.market);   say "coolapk=$KID"
XID=$(taskid com.xingin.xhs);       say "xhs=$XID"

"$ADB" -s "$DEV" logcat -c >/dev/null 2>&1
say "--- MULTI:$CID|$KID|$XID ---"; tcmd "MULTI:$CID|$KID|$XID"; sleep 5
checkpid "after-multi"
dumpmod "multi"; dumpstack "multi"
sleep 3
dumpstack "multi-late"
checkpid "after-multi-late"
say "=== TEST5 END restarts=$RC ==="
