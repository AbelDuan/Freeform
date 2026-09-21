#!/usr/bin/env bash
# TEST16: 在"原生 SoSc 2分屏"基础上，用 DRAGADD 各 hotArea 叠加第3个，找可触摸的三分屏入口
set +e
export PATH="/usr/bin:/bin:/c/Windows/System32:$PATH"
ADB="C:/android/sdk/platform-tools/adb.exe"; DEV="02040860499C3540"
PKG="com.abel.os4freeformx"
PY="C:/Users/Abel/.workbuddy/binaries/python/envs/default/Scripts/python.exe"
W="C:/Users/Abel/WorkBuddy/2026-09-20-12-28-11"
LOG="$W/test16b.log"; : > "$LOG"
sh_(){ "$ADB" -s "$DEV" shell "$1" 2>/dev/null; }
say(){ echo "[$(date +%H:%M:%S)] $1" | tee -a "$LOG"; }
launch(){ sh_ "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $1" >/dev/null; sleep 2.2; }
tcmd(){ sh_ "am start -n $PKG/.PickActivity --es test '$1'" >/dev/null; sleep 1; }
shot(){ sh_ "screencap -p /sdcard/sh.png"; "$ADB" -s "$DEV" pull /sdcard/sh.png "$W/$1" >/dev/null 2>&1; "$PY" -c "
from PIL import Image;im=Image.open(r'$W/$1');im.thumbnail((860,860));im.save(r'$W/small_$1');print('  shot->small_$1')"; }
pid(){ sh_ "pidof com.android.systemui" | tr -d '\r'; }
fwin(){ sh_ "dumpsys input" | grep -m1 "FocusedWindows" -A1 | tail -1 | tr -d '\r' | cut -c1-100; }
st(){ sh_ "logcat -d -s OS4FreeFromX:V" | grep -aE "加分屏\(drag\)|dragAddSplit" | tail -2 | tr -d '\r' | tr '\n' '|'; }

# 解锁（swipe-only keyguard）
if [ "$(sh_ 'dumpsys trust' | grep -o 'deviceLocked=[01]' | head -1 | cut -d= -f2)" != "0" ]; then
  sh_ "input keyevent KEYCODE_WAKEUP" >/dev/null; sleep 0.6
  sh_ "input swipe 836 2000 836 700 200" >/dev/null; sleep 2
fi

say "=== TEST16 START pid=$(pid) -- 重建原生2分屏 ==="
sh_ "input keyevent 3" >/dev/null; sleep 1.5
launch "com.miui.calculator"
sh_ "logcat -c" >/dev/null 2>&1
say "--- 建基座: DRAGADD:1|com.coolapk.market (全屏→SoSc2分屏) ---"
tcmd "DRAGADD:1|com.coolapk.market"; sleep 4
say "  pid=$(pid) fwin=$(fwin)"
shot "t16_0_base.png"
say "  基座状态: $(st)"

for HOT in 1 6 8 9 10 7 19; do
  say "--- DRAGADD:$HOT|com.android.deskclock (在2分屏上叠加) ---"
  tcmd "DRAGADD:$HOT|com.android.deskclock"; sleep 4
  P=$(pid)
  say "  pid=$P fwin=$(fwin)"
  say "  log: $(st)"
  shot "t16_hot_${HOT}.png"
  # 若 SystemUI 重启（PID 变了），重建基座
  if [ "$P" != "$(pid)" ]; then
    say "  !! SystemUI 重启，重建基座"
    launch "com.miui.calculator"; sleep 1
    tcmd "DRAGADD:1|com.coolapk.market"; sleep 4
  fi
done

{ echo "----- MODLOG -----"; "$ADB" -s "$DEV" logcat -d -v time -s "OS4FreeFromX:V" AndroidRuntime:E 2>/dev/null | tail -80; } >> "$LOG"
say "=== TEST16 END pid=$(pid) ==="
