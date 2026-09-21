#!/usr/bin/env bash
# TEST12: 用注入手势走"系统原生"多分屏入口
#   1) 底部中间上滑到左上角 → 原生双分屏(SoSc)
#   2) 底部中间上滑          → 原生多分屏(选择器)
# 每步截图 + 看 FocusedWindows / inputConfig
set +e
export PATH="/usr/bin:/bin:/c/Windows/System32:$PATH"
ADB="C:/android/sdk/platform-tools/adb.exe"
DEV="02040860499C3540"
PY="C:/Users/Abel/.workbuddy/binaries/python/envs/default/Scripts/python.exe"
W="C:/Users/Abel/WorkBuddy/2026-09-20-12-28-11"
LOG="$W/test12b.log"
: > "$LOG"
sh_(){ "$ADB" -s "$DEV" shell "$1" 2>/dev/null; }
ts(){ date +%H:%M:%S; }
say(){ echo "[$(ts)] $1"; echo "[$(ts)] $1" >> "$LOG"; }
launch(){ sh_ "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $1" >/dev/null; sleep 2.5; }
shot(){ sh_ "screencap -p /sdcard/sh.png"; "$ADB" -s "$DEV" pull /sdcard/sh.png "$W/$1" >/dev/null 2>&1; "$PY" -c "
from PIL import Image
im=Image.open(r'$W/$1'); im.thumbnail((860,860)); im.save(r'$W/small_$1'); print('  small_$1', im.size)"; }
fwin(){ sh_ "dumpsys input" | grep -m1 "FocusedWindows" | tr -d '\r'; }
state(){ sh_ "dumpsys activity activities" | grep -m2 -E "windowingMode=multi-window|mInSplitScreen=true" | head -2 | tr -d '\r' | cut -c1-80; }

say "=== TEST12 START ==="
DL=$(sh_ "dumpsys trust" | grep -o "deviceLocked=[01]" | head -1 | cut -d= -f2)
if [ "$DL" != "0" ]; then
  sh_ "input keyevent KEYCODE_WAKEUP" >/dev/null; sleep 0.5
  sh_ "input swipe 1182 1500 1182 400 180" >/dev/null; sleep 2.5
  sh_ "input keyevent 7 15 7 15 7 15" >/dev/null; sleep 0.6
  sh_ "input keyevent 66" >/dev/null; sleep 2
fi
sh_ "input keyevent 3" >/dev/null; sleep 2
launch "com.android.calendar"; launch "com.coolapk.market"
say "state(pre)=$(state)"

# 手势1：底部中间 → 左上角（原生双分屏）
say "--- inject swipe bottom-center -> top-left (2-split) ---"
sh_ "input swipe 836 2330 260 260 450" >/dev/null; sleep 3
shot "t12_a_swipeTL.png"; say "after swipe-TL FocusedWindows: $(fwin)"; say "state=$(state)"

# 手势2：底部中间上滑（原生多分屏/选择器）
say "--- inject swipe bottom-center -> up (multi-split) ---"
sh_ "input swipe 836 2330 836 1500 300" >/dev/null; sleep 3
shot "t12_b_swipeUp.png"; say "after swipe-up FocusedWindows: $(fwin)"; say "state=$(state)"

say "=== TEST12 END ==="
