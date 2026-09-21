#!/usr/bin/env bash
# TEST9: 逐阶段截图，肉眼确认 双分屏/三分屏/选择器 的真实界面 + 触摸焦点
set +e
export PATH="/usr/bin:/bin:/c/Windows/System32:$PATH"
ADB="C:/android/sdk/platform-tools/adb.exe"
DEV="02040860499C3540"
PKG="com.abel.os4freeformx"
W="C:/Users/Abel/WorkBuddy/2026-09-20-12-28-11"
LOG="$W/test9b.log"
: > "$LOG"
sh_(){ "$ADB" -s "$DEV" shell "$1" 2>/dev/null; }
ts(){ date +%H:%M:%S; }
say(){ echo "[$(ts)] $1"; echo "[$(ts)] $1" >> "$LOG"; }
syspid(){ sh_ "pidof com.android.systemui" | tr -d '\r' | tr '\n' ','; }
taskid(){ sh_ "am stack list" | tr -d '\r' | grep -E "taskId=[0-9]+: $1/" | head -1 | sed -E 's/.*taskId=([0-9]+):.*/\1/'; }
launch(){ sh_ "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $1" >/dev/null; sleep 2.5; }
tcmd(){ sh_ "am start -n $PKG/.PickActivity --es test '$1'" >/dev/null; sleep 1; }
shot(){ "$ADB" -s "$DEV" exec-out screencap -p > "$W/$1" 2>/dev/null; echo "  shot $1: $(stat -c%s "$W/$1" 2>/dev/null) bytes"; }
focus(){ sh_ "dumpsys window" | grep -m1 "mCurrentFocus" | sed 's/^ *//'; }

say "=== TEST9 START sysui=$(syspid) ==="
DL=$(sh_ "dumpsys trust" | grep -o "deviceLocked=[01]" | head -1 | cut -d= -f2)
if [ "$DL" != "0" ]; then
  sh_ "input keyevent KEYCODE_WAKEUP" >/dev/null; sleep 0.5
  sh_ "input swipe 1182 1500 1182 400 180" >/dev/null; sleep 2.5
  sh_ "input keyevent 7 15 7 15 7 15" >/dev/null; sleep 0.6
  sh_ "input keyevent 66" >/dev/null; sleep 2
fi
launch "com.coolapk.market"
launch "com.android.deskclock"
launch "com.android.calendar"   # 最后启动 = 前台
CID=$(taskid com.android.calendar); say "calendar=$CID"
KID=$(taskid com.coolapk.market); say "coolapk=$KID"
DID=$(taskid com.android.deskclock); say "deskclock=$DID"
say "focus(pre)=$(focus)"

# 阶段1：把前台应用打成自由半屏（模块"进分屏"的第一步）
say "--- FW: (openWindowFromFullscreen on 前台 calendar) ---"
tcmd "FW:"; sleep 3.5
shot "t9_1_fw.png"; say "focus(fw)=$(focus) pid=$(syspid)"

# 阶段2：原生配对 SoSc 双分屏
say "--- MAKEPAIR:$CID|$KID ---"
tcmd "MAKEPAIR:$CID|$KID"; sleep 3.5
shot "t9_2_makepair.png"; say "focus(makepair)=$(focus) pid=$(syspid)"

# 阶段3：三分屏 quickView=false（直接铺，无选择器）
say "--- MULTIQ:$CID|$KID|$DID ---"
tcmd "MULTIQ:$CID|$KID|$DID"; sleep 3.5
shot "t9_3_multi3.png"; say "focus(multi3)=$(focus) pid=$(syspid)"

# 阶段4：三分屏 quickView=true（原生"进另一个界面选应用"）
say "--- MULTI:$CID|$KID (qv=true) ---"
tcmd "MULTI:$CID|$KID"; sleep 3.5
shot "t9_4_multi_qv.png"; say "focus(multi_qv)=$(focus) pid=$(syspid)"

{ echo "----- MODLOG -----"; "$ADB" -s "$DEV" logcat -d -v time -s "OS4FreeFromX:V" AndroidRuntime:E 2>/dev/null | tail -70; } >> "$LOG"
say "=== TEST9 END pid=$(syspid) ==="
