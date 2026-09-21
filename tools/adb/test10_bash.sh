#!/usr/bin/env bash
# TEST10: 3分屏 → 截图 + 逐窗 hasFocus + tap 每个 pane 中心看焦点是否跟随
set +e
export PATH="/usr/bin:/bin:/c/Windows/System32:$PATH"
ADB="C:/android/sdk/platform-tools/adb.exe"
DEV="02040860499C3540"
PKG="com.abel.os4freeformx"
PY="C:/Users/Abel/.workbuddy/binaries/python/envs/default/Scripts/python.exe"
W="C:/Users/Abel/WorkBuddy/2026-09-20-12-28-11"
LOG="$W/test10b.log"
: > "$LOG"
sh_(){ "$ADB" -s "$DEV" shell "$1" 2>/dev/null; }
ts(){ date +%H:%M:%S; }
say(){ echo "[$(ts)] $1"; echo "[$(ts)] $1" >> "$LOG"; }
taskid(){ sh_ "am stack list" | tr -d '\r' | grep -E "taskId=[0-9]+: $1/" | head -1 | sed -E 's/.*taskId=([0-9]+):.*/\1/'; }
launch(){ sh_ "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $1" >/dev/null; sleep 2.5; }
tcmd(){ sh_ "am start -n $PKG/.PickActivity --es test '$1'" >/dev/null; sleep 1; }
shot(){ sh_ "screencap -p /sdcard/sh.png"; "$ADB" -s "$DEV" pull /sdcard/sh.png "$W/$1" >/dev/null 2>&1; "$PY" -c "
from PIL import Image
im=Image.open(r'$W/$1'); im.thumbnail((860,860)); im.save(r'$W/small_$1')
print('shot saved small_$1', im.size)
"; }
dump(){ sh_ "dumpsys window windows" > "$W/$1"; }

say "=== TEST10 START wm=$(sh_ 'wm size' | tr -d '\r') ==="
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
CID=$(taskid com.android.calendar); KID=$(taskid com.coolapk.market); DID=$(taskid com.android.deskclock)
say "ids cal=$CID coolapk=$KID desk=$DID"

"$ADB" -s "$DEV" logcat -c >/dev/null 2>&1
say "--- MULTIQ:$CID|$KID|$DID ---"
tcmd "MULTIQ:$CID|$KID|$DID"; sleep 3.5
shot "t10_3split.png"
dump "t10_win.txt"

# 逐窗解析 mBounds + hasFocus + tap 中心
"$PY" - "$W/t10_win.txt" "$ADB" "$DEV" <<'PY'
import sys,re,subprocess,time
dumpf,ADB,DEV=sys.argv[1],sys.argv[2],sys.argv[3]
txt=open(dumpf,encoding="utf-8",errors="replace").read()
blocks=re.split(r'\n  Window #', txt)
apps={}
for b in blocks[1:]:
    first=b.splitlines()[0]
    pkg=None
    for p in ["com.android.calendar","com.coolapk.market","com.android.deskclock"]:
        if p in first: pkg=p
    if not pkg: continue
    m=re.search(r'mBounds=Rect\((\d+),\s*(\d+) - (\d+),\s*(\d+)\)', b)
    if not m: continue
    l,t,r,bo=map(int,m.groups())
    hasf = "hasFocus=true" in b
    apps[pkg]=(l,t,r,bo,hasf)
    print(f"{pkg}: bounds=[{l},{t}][{r},{bo}] center=({(l+r)//2},{(t+bo)//2}) hasFocus={hasf}")
for pkg,(l,t,r,bo,_) in apps.items():
    cx,cy=(l+r)//2,(t+bo)//2
    subprocess.run([ADB,"-s",DEV,"shell","input","tap",str(cx),str(cy)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    time.sleep(1.3)
    out=subprocess.run([ADB,"-s",DEV,"shell","dumpsys window"],capture_output=True,text=True).stdout
    fl=[x.strip() for x in out.splitlines() if "mCurrentFocus" in x]
    # which app hasFocus now
    out2=subprocess.run([ADB,"-s",DEV,"shell","dumpsys window windows"],capture_output=True,text=True).stdout
    focused=[p for p in apps if f"com.android.calendar" in p]
    hits=[]
    for b2 in re.split(r'\n  Window #', out2)[1:]:
        f0=b2.splitlines()[0]
        if "hasFocus=true" in b2:
            for p in ["com.android.calendar","com.coolapk.market","com.android.deskclock"]:
                if p in f0: hits.append(p)
    print(f"TAP {pkg} center=({cx},{cy}) -> focusLines={fl[:2]} hasFocusNow={hits}")
PY
say "=== TEST10 END ==="
