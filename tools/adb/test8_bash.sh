#!/usr/bin/env bash
# TEST8: 重建干净三分屏（quickView=false，无选择器），逐应用 tap 中心 → 看 mCurrentFocus 是否落到该应用
set +e
ADB="C:/android/sdk/platform-tools/adb.exe"
DEV="02040860499C3540"
PKG="com.abel.os4freeformx"
W="C:/Users/Abel/WorkBuddy/2026-09-20-12-28-11"
LOG="$W/test8b.log"
: > "$LOG"
sh_(){ "$ADB" -s "$DEV" shell "$1" 2>/dev/null; }
ts(){ date +%H:%M:%S; }
say(){ echo "[$(ts)] $1"; echo "[$(ts)] $1" >> "$LOG"; }
taskid(){ sh_ "am stack list" | tr -d '\r' | grep -E "taskId=[0-9]+: $1/" | head -1 | sed -E 's/.*taskId=([0-9]+):.*/\1/'; }
launch(){ sh_ "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $1" >/dev/null; sleep 2.5; }
tcmd(){ sh_ "am start -n $PKG/.PickActivity --es test '$1'" >/dev/null; sleep 1; }
dumpwin(){ sh_ "dumpsys window windows" > "$1" 2>/dev/null; }

say "=== TEST8 START ==="
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
CID=$(taskid com.android.calendar); say "calendar=$CID"
KID=$(taskid com.coolapk.market); say "coolapk=$KID"
DID=$(taskid com.android.deskclock); say "deskclock=$DID"

"$ADB" -s "$DEV" logcat -c >/dev/null 2>&1
say "--- MULTIQ:$CID|$KID|$DID ---"
tcmd "MULTIQ:$CID|$KID|$DID"; sleep 3
dumpwin "$W/test8_win.txt"

# 逐应用解析 mBounds 并 tap，读 mCurrentFocus
for pkg in com.android.calendar com.coolapk.market com.android.deskclock; do
python3 - "$W/test8_win.txt" "$pkg" <<'PY'
import sys,re,subprocess,time
dumpf,pkg=sys.argv[1],sys.argv[2]
txt=open(dumpf,encoding="utf-8",errors="replace").read()
blocks=re.split(r'\n  Window #', txt)
for b in blocks[1:]:
    if pkg not in b.splitlines()[0]: continue
    m=re.search(r'mBounds=Rect\((\d+),\s*(\d+) - (\d+),\s*(\d+)\)', b)
    if not m: continue
    l,t,r,b=int(m.group(1)),int(m.group(2)),int(m.group(3)),int(m.group(4))
    cx,cy=(l+r)//2,(t+b)//2
    print(f"TAP {pkg}: bounds=[{l},{t}][{r},{b}] center=({cx},{cy})")
    subprocess.run(["C:/android/sdk/platform-tools/adb.exe","-s","02040860499C3540","shell","input","tap",str(cx),str(cy)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    time.sleep(1.2)
    out=subprocess.run(["C:/android/sdk/platform-tools/adb.exe","-s","02040860499C3540","shell","dumpsys window windows"],capture_output=True,text=True).stdout
    for line in out.splitlines():
        if "mCurrentFocus" in line:
            print("  FOCUS:",line.strip()[:150]); break
    break
else:
    print(f"TAP {pkg}: bounds NOT FOUND")
PY
done
say "=== TEST8 END ==="
