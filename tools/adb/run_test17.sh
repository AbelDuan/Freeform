#!/bin/bash
# test17：验证 Dock 路径 2→3→4 分屏可触摸（2026-09-21 平板 turner 实测通过）
#
# ⚠ 钩子写入路径：**必须经 PickActivity**（`--es test "<钩子>"`），它在模块 App 进程内用
#   AppPrefs.putString 写 PREFS_CFG。直接 `content call put` 或改 xml 都**无效** ——
#   put 只写 PREFS_BOUNDS、外部改 xml 会被模块进程的内存缓存覆盖（实测踩过）。
#
# ⚠ adb 传参**尾冒号会被吞**（`--es test DOCKSTATE:` → Binding not well formed），
#   所以一律传**不带尾冒号**的钩子；模块侧会自动补。
#
# 用法: bash tools/adb/run_test17.sh <步骤>
#   state  : 打印 dock 守卫值（不改变状态）
#   fire   : 生产入口，等价四指上滑（全屏→2分屏；已在分屏→选择器→Dock 加层）
#   soc    : 仅建原生 SoSc 双分屏
#   dock   : SoSc + Dock 加一层 → 三分屏（2→3）
#   add    : 在已有分屏上 Dock 加一层（3→4 用这个）
set -u
ADB=/c/android/sdk/platform-tools/adb.exe
PKG=com.abel.os4freeformx

log() { echo "[$(date +%H:%M:%S)] $*"; }
hook() { $ADB shell "am start -n $PKG/.PickActivity --es test '$1'" >/dev/null 2>&1; }

STEP="${1:-state}"
case "$STEP" in
  state) H="DOCKSTATE" ;;
  fire)  H="FIRE" ;;
  soc)   H="SOSC:1" ;;
  dock)  H="SOSCDOCK:1|com.android.contacts" ;;
  add)   H="DOCKADD:com.android.calendar" ;;
  *)     echo "未知步骤 $STEP"; exit 1 ;;
esac

log "=== test17 step=$STEP hook=$H ==="

# 前置：保活 + 唤醒 + 解锁（锁屏态下 NotificationShade 吃焦点，是假象非分屏问题）
$ADB shell "svc power stayon true" >/dev/null 2>&1
$ADB shell "input keyevent KEYCODE_WAKEUP" >/dev/null 2>&1
sleep 1
$ADB shell "input keyevent 82" >/dev/null 2>&1
$ADB shell "input swipe 1504 1800 1504 300 200" >/dev/null 2>&1
sleep 2
$ADB shell "dumpsys window | grep -E 'mDreamingLockscreen|mCurrentFocus' | head -3"

# ★ 测试钩子由 `test_hook` 门控（**默认关闭** —— 生产不做常驻跨进程轮询）。
#   先打开：轮询每轮都从 StoreProvider 的 getCfg 读真实开关值，所以**不需要重启 SystemUI**；
#   但门控关闭时轮询是 5s 一轮，打开后最多 5s 生效 → 等 8s 稳妥。
#   ⚠ 别用 Cfg.testHook 判断：LSPosed remote prefs 是快照，hook 进程永远读到旧值。
log "打开测试钩子门控 ..."
$ADB shell "am start -n $PKG/.PickActivity --es test 'TESTHOOK:1'" >/dev/null 2>&1
sleep 8

$ADB shell "su -c 'logcat -c'" >/dev/null 2>&1
log "触发钩子 $H ..."
hook "$H"
sleep 8

log "=== 模块日志 ==="
$ADB shell "su -c 'logcat -d | grep -E \"OS4FreeFromX: (测试入口|四指上滑|Dock加层|加分屏|SOSCDOCK|原生SoSc)\"'" 2>/dev/null | tail -25

log "=== 系统过渡 ==="
$ADB shell "su -c 'logcat -d | grep -E \"SOSC_STATE_FULL_OPEN|DOCK_EXIT|splitTasks\"'" 2>/dev/null | tail -6

log "=== 触摸验证（三列）==="
for spec in "左:493" "中:1503" "右:2513"; do
  n="${spec%%:*}"; x="${spec##*:}"
  $ADB shell "input tap $x 900" >/dev/null 2>&1
  sleep 2
  printf "tap %s x=%s → %s\n" "$n" "$x" \
    "$($ADB shell 'dumpsys window | grep mCurrentFocus | head -1' | sed 's/.*=//')"
done

log "=== SystemUI 存活 ==="
$ADB shell "pidof com.android.systemui"
exit 0
