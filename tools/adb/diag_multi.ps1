# OS4FreeFromX 诊断：FIRE 驱动逐级分屏，捕获 startMultipleSplits 失败分支 + SystemUI 重启
$adb = "C:\android\sdk\platform-tools\adb.exe"
$dev = "02040860499C3540"
$PKG = "com.abel.os4freeformx"
$MODLOG = "C:\Users\Abel\WorkBuddy\2026-09-20-12-28-11\diag_multi.log"
$restartCount = 0
$script:baselinePid = $null

function Log($m){ "[$(Get-Date -f 'HH:mm:ss')] $m" | Out-File -FilePath $MODLOG -Encoding utf8 -Append }
function SysUiPid { (& $adb -s $dev shell pidof com.android.systemui 2>&1) -join "" }
function CheckPid($step){
    $p = SysUiPid
    if ($script:baselinePid -eq $null){ $script:baselinePid = $p; Log "BASELINE SystemUI pid=$p" }
    elseif ($p -ne $script:baselinePid){
        $restartCount++
        Log "*** SystemUI RESTART @ $step : baseline=$script:baselinePid now=$p (restart #$restartCount) ***"
        $script:baselinePid = $p
    } else { Log "$step : SystemUI pid=$p (stable)" }
}
function DumpModule($tag){
    (& $adb -s $dev logcat -d -v time "OS4FreeFromX:V" "*:S" 2>&1) | Out-File -FilePath $MODLOG -Encoding utf8 -Append
    Log "module log dumped [$tag]"
}
function ClearLog { & $adb -s $dev logcat -c 2>&1 | Out-Null }
function Launch($pkg){ & $adb -s $dev shell "monkey -p $pkg -c android.intent.category.LAUNCHER 1" 2>&1 | Out-Null; Start-Sleep -Seconds 2 }
function Fire { & $adb -s $dev shell "am start -n $PKG/.PickActivity --es test 'FIRE:'" 2>&1 | Out-Null; Start-Sleep -Seconds 1 }
function Home { & $adb -s $dev shell "input keyevent 3" 2>&1 | Out-Null; Start-Sleep -Seconds 2 }

"" | Out-File -FilePath $MODLOG -Encoding utf8
ClearLog
Log "=== DIAG START ==="
CheckPid "start"
Launch "com.android.settings"
Log "--- FIRE#1 (single->double) ---"
Fire
Start-Sleep -Seconds 4
CheckPid "after-fire1"
DumpModule "fire1"
Log "--- FIRE#2 (double->triple) ---"
Fire
Start-Sleep -Seconds 4
CheckPid "after-fire2"
DumpModule "fire2"
Log "--- FIRE#3 (triple->quad) ---"
Fire
Start-Sleep -Seconds 4
CheckPid "after-fire3"
DumpModule "fire3"
Home
CheckPid "after-home"
Log "=== DIAG END restarts=$restartCount ==="
