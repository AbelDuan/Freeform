#!/usr/bin/env bash
# TEST4: 造原生 SoSc 双分屏 -> FIRE 触发 startMultipleSplits(classloader修复) -> 验证是否真出 3+ 分屏且不崩
set +e
ADB="C:/android/sdk/platform-tools/adb.exe"
DEV="02040860499C3540"
PKG="com.abel.os4freeformx"
TAG="OS4FreeFromX"
LOG="C:/Users/Abel/WorkBuddy/2026-09-20-12-28-11/test4b.log"
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

say "=== TEST4 START ==="
checkpid "pre"
sh_ "input keyevent KEYCODE_WAKEUP" >/dev/null; sleep 0.3
sh_ "input keyevent 3" >/dev/null; sleep 2

# 1) 拉起两个应用
launch "com.android.calendar"
launch "com.coolapk.market"
CID=$(taskid com.android.calendar); say "calendar=$CID"
KID=$(taskid com.coolapk.market);   say "coolapk=$KID"

# 2) MAKEPAIR -> 原生 SoSc 双分屏
"$ADB" -s "$DEV" logcat -c >/dev/null 2>&1
say "--- MAKEPAIR:$CID|$KID ---"; tcmd "MAKEPAIR:$CID|$KID"; sleep 3.5
checkpid "after-makepair"
say "SoSc-check:"; sh_ "am stack list" 2>/dev/null | tr -d '\r' | grep -E "mInSplitScreen=true" | head -2
dumpmod "makepair"; dumpstack "makepair"

# 3) FIRE -> fourFingerAddSplit -> inSplit=true -> startMultipleSplits(cur)
"$ADB" -s "$DEV" logcat -c >/dev/null 2>&1
say "--- FIRE: ---"; tcmd "FIRE:"; sleep 5
checkpid "after-fire"
dumpmod "fire"; dumpstack "fire"

# 4) 再观察一次（让动画/quick view 落定）
sleep 3
dumpstack "fire-late"
checkpid "after-fire-late"
say "=== TEST4 END restarts=$RC ==="
