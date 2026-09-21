# OS4FreeFromX 诊断 v2：清理残留 -> 可靠拉起前台 -> FIRE 逐级分屏，每次打印前台包名 + 监控 SystemUI 重启
$adb = "C:\android\sdk\platform-tools\adb.exe"
$dev = "02040860499C3540"
$PKG = "com.abel.os4freeformx"
$MODLOG = "C:\Users\Abel\WorkBuddy\2026-09-20-12-28-11\diag2.log"
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
function Foreground {
    $r = (& $adb -s $dev shell "dumpsys activity activities" 2>&1) -join "`n"
    if ($r -match "mResumedActivity.*\{(.*?)\}") {
        $m = [regex]::Match($r, "mResumedActivity.*?([a-z0-9.]+)/")
        if ($m.Success) { return $m.Groups[1].Value }
    }
    return "?"
}
function Launch($pkg){
    & $adb -s $dev shell "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $pkg" 2>&1 | Out-Null
    Start-Sleep -Seconds 2
}
function Fire {
    $fg = Foreground
    Log "    前台=$fg"
    & $adb -s $dev shell "am start -n $PKG/.PickActivity --es test 'FIRE:'" 2>&1 | Out-Null
    Start-Sleep -Seconds 1
}
function Home { & $adb -s $dev shell "input keyevent 3" 2>&1 | Out-Null; Start-Sleep -Seconds 3 }

"" | Out-File -FilePath $MODLOG -Encoding utf8
ClearLog
Log "=== DIAG2 START ==="
CheckPid "start"
Home   # 清理上一次残留的分屏/小窗
Log "--- 清理后前台=$(Foreground) ---"
# ---- Phase 1: 单->双->(尝试加层) 跨 3 个应用，复现"再进双分屏失败" ----
$apps = @("com.android.calendar","com.xingin.xhs","com.coolapk.market")
foreach ($a in $apps){
    Log "--- launch $a ---"
    Launch $a
    Log "--- 前台=$(Foreground) ---"
    CheckPid "after-launch-$a"
    Log "--- FIRE#1 (single->double) ---"
    Fire
    Start-Sleep -Seconds 4
    CheckPid "after-fire1-$a"
    DumpModule "fire1-$a"
    Log "--- FIRE#2 (try add-layer -> 3-split) ---"
    Fire
    Start-Sleep -Seconds 4
    CheckPid "after-fire2-$a"
    DumpModule "fire2-$a"
    Home
    CheckPid "after-home-$a"
    DumpModule "home-$a"
}
Log "=== DIAG2 END restarts=$restartCount ==="
