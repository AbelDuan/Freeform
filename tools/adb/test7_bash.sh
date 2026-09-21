#!/usr/bin/env bash
# TEST7: 双分屏(MAKEPAIR) → 三分屏(MULTIQ) → 四分屏(MULTIQ) 全链路
# 同时用「tap 应用中心坐标 → 看 mCurrentFocus 是否落到该应用」验证触摸是否可达
set +e
ADB="C:/android/sdk/platform-tools/adb.exe"
DEV="02040860499C3540"
PKG="com.abel.os4freeformx"
TAG="OS4FreeFromX"
W="C:/Users/Abel/WorkBuddy/2026-09-20-12-28-11"
LOG="$W/test7b.log"
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
dumpwin(){ local f="$1"; sh_ "dumpsys window windows" > "$f" 2>/dev/null; }
# 解析某包在 window dump 里的 mFrame，取中心，input tap，再读 mCurrentFocus
tapfocus(){
  local dumpf="$1" pkg="$2" label="$3"
  python3 - "$dumpf" "$pkg" <<'PY'
import sys,re,subprocess,sys
dumpf,pkg=sys.argv[1],sys.argv[2]
txt=open(dumpf,encoding="utf-8",errors="replace").read()
blocks=re.split(r'\n  Window #', txt)
target=None
for b in blocks[1:]:
    first=b.splitlines()[0].strip()[:140]
    if pkg not in first: continue
    m=re.search(r'mFrame=\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]', b)
    if m:
        l,t,r,b=int(m.group(1)),int(m.group(2)),int(m.group(3)),int(m.group(4))
        cx,cy=(l+r)//2,(t+b)//2
        print(f"FRAME {pkg}: [{l},{t}][{r},{b}] center=({cx},{cy})")
        # tap
        subprocess.run(["C:/android/sdk/platform-tools/adb.exe","-s","02040860499C3540","shell","input","tap",str(cx),str(cy)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        import time; time.sleep(1.5)
        out=subprocess.run(["C:/android/sdk/platform-tools/adb.exe","-s","02040860499C3540","shell","dumpsys window windows"],capture_output=True,text=True).stdout
        for line in out.splitlines():
            if "mCurrentFocus" in line or "mFocusedApp" in line:
                print("FOCUS:",line.strip()[:160]); break
        break
else:
    print(f"FRAME {pkg}: NOT FOUND in dump")
PY
}

say "=== TEST7 START (immersive currently ON) ==="
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
launch "com.coolapk.market"
launch "com.android.deskclock"
launch "com.miui.calculator"
CID=$(taskid com.android.calendar); say "calendar=$CID"
KID=$(taskid com.coolapk.market); say "coolapk=$KID"
DID=$(taskid com.android.deskclock); say "deskclock=$DID"
XID=$(taskid com.miui.calculator); say "calculator=$XID"

"$ADB" -s "$DEV" logcat -c >/dev/null 2>&1

# 1) 双分屏（原生 SoSc）
say "--- MAKEPAIR:$CID|$KID ---"
tcmd "MAKEPAIR:$CID|$KID"; sleep 2.5
checkpid "after-2split"
dumpwin "$W/test7_win2.txt"

# 2) 三分屏（quickView=false，直接铺，无选择器干扰）
say "--- MULTIQ:$CID|$KID|$DID ---"
tcmd "MULTIQ:$CID|$KID|$DID"; sleep 2.5
checkpid "after-3split"
dumpwin "$W/test7_win3.txt"
say ">>> tap deskclock (in 3-split) to test touch"
tapfocus "$W/test7_win3.txt" "com.android.deskclock" "3split-tap"

# 3) 四分屏
say "--- MULTIQ:$CID|$KID|$DID|$XID ---"
tcmd "MULTIQ:$CID|$KID|$DID|$XID"; sleep 2.5
checkpid "after-4split"
dumpwin "$W/test7_win4.txt"
say ">>> tap calculator (in 4-split) to test touch"
tapfocus "$W/test7_win4.txt" "com.miui.calculator" "4split-tap"

{ echo "----- MODLOG -----"; "$ADB" -s "$DEV" logcat -d -v time -s "$TAG:V" AndroidRuntime:E 2>/dev/null; } >> "$LOG"
checkpid "final"
say "=== TEST7 END restarts=$RC ==="
