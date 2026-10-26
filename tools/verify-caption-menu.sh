#!/usr/bin/env bash
# 抓「全屏应用顶部 ··· 把手菜单」的截图 + uiautomator dump，用来数按钮个数。
#
# 用法：
#   bash tools/verify-caption-menu.sh <输出前缀> [x y]
#   例：bash tools/verify-caption-menu.sh build/full   # 全屏态
#
# 说明：
#   - 默认走有线 adb（02040860499C3540）；
#   - 只读操作：开酷安、点把手、截图、dump，不改任何系统状态；
#   - 截图落在 <输出前缀>.png，dump 落在 <输出前缀>.xml。
set -u
export MSYS_NO_PATHCONV=1

ADB=${ADB:-/c/android/sdk/platform-tools/adb.exe}
SERIAL=${ADB_SERIAL:-02040860499C3540}
PREFIX=${1:-build/menu}
HX=${2:-835}
HY=${3:-70}

a() { "$ADB" -s "$SERIAL" "$@"; }

if ! a get-state >/dev/null 2>&1; then
  echo "!! 设备 $SERIAL 未连接" >&2
  exit 1
fi

echo "== 屏幕尺寸 =="
a shell wm size

echo "== 唤醒 + 常亮 =="
a shell "input keyevent KEYCODE_WAKEUP; svc power stayon true; input keyevent KEYCODE_HOME" >/dev/null 2>&1
sleep 1

echo "== 启动酷安（全屏） =="
a shell "monkey -p com.coolapk.market -c android.intent.category.LAUNCHER 1" 2>&1 | tail -2
sleep 4

echo "== 顶部呼出把手 =="
a shell "input swipe $((HX)) 1 $((HX)) 1 300" >/dev/null 2>&1
sleep 1

echo "== 点 ··· 把手 ($HX,$HY) =="
a shell "input tap $HX $HY" >/dev/null 2>&1
sleep 2

echo "== 截图 =="
a exec-out screencap -p > "${PREFIX}.png" 2>/dev/null
ls -la "${PREFIX}.png"

echo "== uiautomator dump =="
for i in 1 2 3; do
  if a shell uiautomator dump /data/local/tmp/ui-menu.xml 2>&1 | grep -q dumped; then break; fi
  sleep 2
done
a pull /data/local/tmp/ui-menu.xml "${PREFIX}.xml" 2>&1 | tail -1

echo "== 菜单窗口 =="
a shell "dumpsys window windows" 2>/dev/null | grep -E "Window\{.*(Caption|Handle|Dot)" | head -5

echo "== 当前前台任务 windowingMode =="
a shell "dumpsys activity activities" 2>/dev/null | grep -E "mResumedActivity|windowingMode" | head -5

echo "DONE -> ${PREFIX}.png / ${PREFIX}.xml"
