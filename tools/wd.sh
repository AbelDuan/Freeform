#!/usr/bin/env bash
# adb 包装：默认走**有线**（USB，序列号见下），USB 不在时才回退无线。
# 有线优先的原因：无线 adb 的连接在宿主每次调用后都会随 adb server 被清理而失效，且有
# "more than one device" 的歧义（无线 transport 残留成 offline 条目）。
# 用法: tools/wd.sh shell "dumpsys ..."   /  tools/wd.sh push a b
ADB="${ADB_BIN:-C:/android/sdk/platform-tools/adb.exe}"
USB="${ADB_USB:-02040860499C3540}"
WIFI="${ADB_IP:-192.168.0.115:5555}"
export MSYS_NO_PATHCONV=1

"$ADB" start-server >/dev/null 2>&1

pick() { "$ADB" devices 2>/dev/null | awk 'NR>1 && $2=="device"{print $1}'; }

SERIAL=""
for _ in 1 2 3 4 5; do
  if pick | grep -qx "$USB"; then SERIAL="$USB"; break; fi
  sleep 0.3
done
if [ -z "$SERIAL" ]; then
  # 无线回退
  for _ in 1 2 3 4 5; do
    if pick | grep -qx "$WIFI"; then SERIAL="$WIFI"; break; fi
    "$ADB" connect "$WIFI" >/dev/null 2>&1
    sleep 0.4
  done
fi
if [ -z "$SERIAL" ]; then
  echo "wd.sh: 没有可用设备（USB=$USB / 无线=$WIFI）" >&2
  "$ADB" devices >&2
  exit 1
fi

exec "$ADB" -s "$SERIAL" "$@"
