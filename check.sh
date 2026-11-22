#!/usr/bin/env bash
# 主机侧自检：纯逻辑（不需要设备）。
#   1) Ratio —— 小窗比例调整算法
#   2) Nbi   —— 沉浸导航栏名单：合并 / JSON 生成 / 回读
# 用法： ./check.sh
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/env.sh"
OUT=$(mktemp -d)
OUT2=$(mktemp -d)
trap 'rm -rf "$OUT" "$OUT2"' EXIT

# ⚠ 本机 kotlinc 是用 Maven jar 自包的 wrapper，**不会**像官方 distribution 脚本那样
#   自动把 kotlin-stdlib 加进编译期 classpath —— 必须显式 -cp，否则报
#   "cannot access built-in declaration 'kotlin.Int'"。
"$KOTLINC" -nowarn -no-stdlib -cp "$KOTLIN_STDLIB" -d "$OUT" \
    "$HERE/app/src/main/kotlin/com/abel/os4freeformx/Ratio.kt" \
    "$HERE/test/RatioCheck.kt"
java -cp "$OUT:$KOTLIN_STDLIB" com.abel.os4freeformx.RatioCheckKt

"$KOTLINC" -nowarn -no-stdlib -cp "$KOTLIN_STDLIB" -d "$OUT2" \
    "$HERE/app/src/main/kotlin/com/abel/os4freeformx/Nbi.kt" \
    "$HERE/test/NbiCheck.kt"
java -cp "$OUT2:$KOTLIN_STDLIB" com.abel.os4freeformx.NbiCheck
