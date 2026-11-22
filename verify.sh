#!/usr/bin/env bash
# 真机验证一条龙：模块是否加载 / hook 是否挂上 / 记忆写入与恢复 / 存储值 / 当前小窗真实 bounds
# 用法： ./verify.sh            # 看最近 12 条相关日志 + 存储 + 当前 bounds
#        ./verify.sh hook       # 只看当前进程的 hook 安装情况
set -uo pipefail
UI=$(timeout 20 adb shell pidof com.android.systemui 2>/dev/null | tr -d '\r')
SP=$(timeout 20 adb shell pidof system_server 2>/dev/null | tr -d '\r')
L=$(timeout 25 adb shell "su -c 'ls -t /data/adb/lspd/log/modules_*.log | head -1'" 2>/dev/null | tr -d '\r')
mode=${1:-all}

if [ "$mode" = "hook" ]; then
  echo "== $UI(com.android.systemui) hook 安装 =="
  timeout 25 adb shell "su -c 'grep -a OS4FreeFromX $L | grep -a \": *$UI:\" | grep -aE \"installSystemUi|hook 成功\"'" 2>/dev/null | tr -d '\r' | sed 's/.*OS4FreeFromX[^]]*\] //' | cut -c1-120
  exit 0
fi

echo "== system_server($SP) =="
timeout 25 adb shell "su -c 'grep -a OS4FreeFromX $L | grep -a \": *$SP:\" | grep -aE \"SystemServerStarting|installSystemServer|小窗 bounds 计算共挂\"'" 2>/dev/null | tr -d '\r' | sed 's/.*OS4FreeFromX[^]]*\] //' | cut -c1-120
echo "== SystemUI($UI) 最近事件 =="
timeout 25 adb shell "su -c 'grep -a OS4FreeFromX $L | grep -a \": *$UI:\" | grep -aE \"缩放手柄|立即写入|记住|恢复|记录跳过|手势后|触摸探针\" | tail -12'" 2>/dev/null | tr -d '\r' | sed 's/.*OS4FreeFromX[^]]*\] //' | cut -c1-150
echo "== 存储（记忆） =="
timeout 25 adb shell "su -c 'grep string /data/data/com.abel.os4freeformx/shared_prefs/os4freeformx_bounds.xml 2>/dev/null'" 2>/dev/null | tr -d '\r'
echo "== 当前 freeform 真实 bounds =="
timeout 25 adb shell "dumpsys activity activities | grep -A2 'mode=freeform' | grep -E 'A=[0-9]+:|mBounds' | head -6" 2>/dev/null | tr -d '\r'
