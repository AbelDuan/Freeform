#!/usr/bin/env bash
# 主机侧自检：小窗比例调整算法（纯算术，不需要设备）。
# 用法： ./check.sh
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/env.sh"
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

"$KOTLINC" -nowarn -d "$OUT" \
    "$HERE/app/src/main/kotlin/com/abel/os4freeformx/Ratio.kt" \
    "$HERE/test/RatioCheck.kt"
java -cp "$OUT:$KOTLIN_STDLIB" com.abel.os4freeformx.RatioCheckKt
