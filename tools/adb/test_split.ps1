# OS4FreeFromX 自动化测试：FIRE 驱动四指逐级分屏 + 监控 SystemUI 重启
$adb = "C:\android\sdk\platform-tools\adb.exe"
$dev = "02040860499C3540"
$PKG = "com.abel.os4freeformx"
$LOG = "C:\Users\Abel\WorkBuddy\2026-09-20-12-28-11\test_split.log"
$MODLOG = "C:\Users\Abel\WorkBuddy\2026-09-20-12-28-11\test_split_module.log"
$restartCount = 0
$baselinePid = $null

function Log($m){ "[$(Get-Date -f 'HH:mm:ss')] $m" | Out-File -FilePath $LOG -Encoding utf8 -Append }
function SysUiPid { (& $adb -s $dev shell pidof com.android.systemui 2>&1) -join "" }
function Launch($pkg){ & $adb -s $dev shell "monkey -p $pkg -c android.intent.category.LAUNCHER 1" 2>&1 | Out-Null; Start-Sleep -Seconds 2 }
function Fire { & $adb -s $dev shell "am start -n $PKG/.PickActivity --es test 'FIRE:'" 2>&1 | Out-Null; Start-Sleep -Seconds 1 }
function Home { & $adb -s $dev shell "input keyevent 3" 2>&1 | Out-Null; Start-Sleep -Seconds 2 }
function CheckPid($step){
    $p = SysUiPid
    if ($baselinePid -eq $null){ $baselinePid = $p; Log "BASELINE SystemUI pid=$p" }
    elseif ($p -ne $baselinePid){
        $restartCount++
        Log "*** SystemUI RESTART @ $step : baseline=$baselinePid now=$p (restart #$restartCount) ***"
        $baselinePid = $p
    } else {
        Log "$step : SystemUI pid=$p (stable)"
    }
}
function DumpModule($tag){ (& $adb -s $dev logcat -d -v time "OS4FreeFromX:V" "*:S" 2>&1) | Out-File -FilePath $MODLOG -Encoding utf8 -Append; Log "module log dumped [$tag]" }

# reset logs
"" | Out-File -FilePath $LOG -Encoding utf8
"" | Out-File -FilePath $MODLOG -Encoding utf8
& $adb -s $dev logcat -c 2>&1 | Out-Null
Log "=== TEST START (FIRE-driven four-finger progressive split) ==="
CheckPid "start"

# ---- Phase 1: enter/exit/re-enter 2-split (crash-repro path) ----
$apps = @("com.android.settings","com.android.deskclock","com.android.calendar")
foreach ($a in $apps){
    Log "--- launch $a ---"
    Launch $a
    CheckPid "after-launch-$a"
    Log "--- FIRE (single->double split) ---"
    Fire
    Start-Sleep -Seconds 4
    CheckPid "after-fire-$a"
    DumpModule "fire-$a"
    Log "--- HOME (exit/dissolve split) ---"
    Home
    CheckPid "after-home-$a"
    DumpModule "home-$a"
}

# ---- Phase 2: try progressive 3/4 split in an already-formed split ----
Log "=== Phase 2: progressive (need a formed split first) ==="
Launch "com.android.settings"
Fire
Start-Sleep -Seconds 4
CheckPid "p2-fire1"
DumpModule "p2-fire1"
# second fire should add a layer (3-split) if a split now exists
Log "--- FIRE again (expect add-layer -> 3-split) ---"
Fire
Start-Sleep -Seconds 4
CheckPid "p2-fire2"
DumpModule "p2-fire2"
Log "--- FIRE again (expect 4-split) ---"
Fire
Start-Sleep -Seconds 4
CheckPid "p2-fire3"
DumpModule "p2-fire3"
Log "--- HOME (dissolve all) ---"
Home
CheckPid "p2-home"
DumpModule "p2-home"

Log "=== TEST END : SystemUI restarts detected = $restartCount ==="
