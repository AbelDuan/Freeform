#!/usr/bin/env bash
# 原生 3+ 分屏测试：MAKEPAIR 造原生双分屏 -> FIRE(classloader修复路径) / ADDSPLIT(原生加层路径)
# 全程监控 SystemUI PID 重启
set +e
ADB="C:/android/sdk/platform-tools/adb.exe"
DEV="02040860499C3540"
PKG="com.abel.os4freeformx"
TAG="OS4FreeFromX"
LOG="C:/Users/Abel/WorkBuddy/2026-09-20-12-28-11/test3b.log"
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
dl(){ sh_ "dumpsys trust" | grep -o "deviceLocked=[01]" | head -1 | cut -d= -f2; }
unlock(){
  sh_ "input keyevent KEYCODE_WAKEUP" >/dev/null; sleep 0.5
  sh_ "input swipe 1182 1500 1182 400 180" >/dev/null; sleep 2.5
  sh_ "input keyevent 7 15 7 15 7 15" >/dev/null; sleep 0.6
  sh_ "input keyevent 66" >/dev/null; sleep 2
}

say "=== TEST3-B START ==="
checkpid "pre-crash"
say "--- restart SystemUI (清零 Logx 计数, 非重启设备) ---"
sh_ "am crash com.android.systemui" >/dev/null; sleep 7
checkpid "after-suirestart"
say "deviceLocked=$(dl)"
if [ "$(dl)" != "0" ]; then unlock; say "after re-unlock deviceLocked=$(dl)"; fi
"$ADB" -s "$DEV" logcat -c >/dev/null 2>&1

# 1) 拉起三个应用建立 task
launch "com.xingin.xhs"
launch "com.android.calendar"
launch "com.coolapk.market"
XID=$(taskid com.xingin.xhs);    say "xhs=$XID"
CID=$(taskid com.android.calendar); say "calendar=$CID"
KID=$(taskid com.coolapk.market);   say "coolapk=$KID"

# 2) MAKEPAIR -> 原生 SoSc 双分屏
say "--- MAKEPAIR:$CID|$KID ---"; tcmd "MAKEPAIR:$CID|$KID"; sleep 3.5
checkpid "after-makepair"; dumpmod "makepair"; dumpstack "makepair"

# 3) FIRE -> startMultipleSplits（classloader 修复后的路径）
say "--- FIRE: ---"; tcmd "FIRE:"; sleep 4.5
checkpid "after-fire"; dumpmod "fire"; dumpstack "fire"

# 4) 回桌面复位
say "--- reset home ---"; sh_ "input keyevent 3" >/dev/null; sleep 2.5

# 5) 重新 MAKEPAIR -> ADDSPLIT（transferSoScToMultipleSplit + insertMultipleSplitByTask）
say "--- MAKEPAIR:$CID|$KID (2) ---"; tcmd "MAKEPAIR:$CID|$KID"; sleep 3.5
checkpid "after-makepair2"
say "--- ADDSPLIT:com.xingin.xhs|$XID ---"; tcmd "ADDSPLIT:com.xingin.xhs|$XID"; sleep 4.5
checkpid "after-addsplit"; dumpmod "addsplit"; dumpstack "addsplit"
say "=== TEST3-B END restarts=$RC ==="
