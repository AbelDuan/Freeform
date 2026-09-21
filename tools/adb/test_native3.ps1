# 测试原生 3+ 分屏路径：MAKEPAIR 造原生 2-split -> ADDSPLIT 加第 3 个应用（transferSoScToMultipleSplit + insertMultipleSplitByTask）
$adb = "C:\android\sdk\platform-tools\adb.exe"
$dev = "02040860499C3540"
$PKG = "com.abel.os4freeformx"
$MODLOG = "C:\Users\Abel\WorkBuddy\2026-09-20-12-28-11\test_native3.log"
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
function Launch($pkg){
    & $adb -s $dev shell "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $pkg" 2>&1 | Out-Null
    Start-Sleep -Seconds 2
}
function TaskId($pkg){
    $r = (& $adb -s $dev shell am stack list 2>&1) -join "`n"
    $m = [regex]::Match($r, "taskId=(\d+):\s*$pkg")
    if ($m.Success) { return $m.Groups[1].Value }
    return $null
}
function TestCmd($v){
    & $adb -s $dev shell "am start -n $PKG/.PickActivity --es test '$v'" 2>&1 | Out-Null
    Start-Sleep -Seconds 1
}

"" | Out-File -FilePath $MODLOG -Encoding utf8
& $adb -s $dev logcat -c 2>&1 | Out-Null
Log "=== NATIVE 3+ TEST START ==="
CheckPid "start"
# 1) 拉起两个支持分屏的应用，拿到 task id
Launch "com.android.calendar"
$cid = TaskId "com.android.calendar"
Log "calendar taskId=$cid"
Launch "com.coolapk.market"
$kid = TaskId "com.coolapk.market"
Log "coolapk taskId=$kid"
# 2) MAKEPAIR 造原生 SoSc 2-split
Log "--- MAKEPAIR:$cid|$kid ---"
TestCmd "MAKEPAIR:$cid|$kid"
Start-Sleep -Seconds 3
CheckPid "after-makepair"
DumpModule "makepair"
# 3) 拉起第 3 个应用，ADDSPLIT 加进去（走 transferSoScToMultipleSplit + insertMultipleSplitByTask）
Launch "com.xingin.xhs"
$xid = TaskId "com.xingin.xhs"
Log "xhs taskId=$xid"
Log "--- ADDSPLIT:com.xingin.xhs|$xid ---"
TestCmd "ADDSPLIT:com.xingin.xhs|$xid"
Start-Sleep -Seconds 4
CheckPid "after-addsplit"
DumpModule "addsplit"
# 4) 再 ADDSPLIT 第 4 个（尝试 4-split）
Launch "com.microsoft.emmx"
$eid = TaskId "com.microsoft.emmx"
Log "edge taskId=$eid"
Log "--- ADDSPLIT:com.microsoft.emmx|$eid ---"
TestCmd "ADDSPLIT:com.microsoft.emmx|$eid"
Start-Sleep -Seconds 4
CheckPid "after-addsplit2"
DumpModule "addsplit2"
Log "=== NATIVE 3+ TEST END restarts=$restartCount ==="
