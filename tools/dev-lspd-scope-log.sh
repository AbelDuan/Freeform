#!/system/bin/sh
L=/data/adb/lspd/log/verbose_2026-10-05T21:14:39.779714.log
echo "=== 1) scope / attach / fork 相关（新 daemon 日志） ==="
grep -iE 'scope|attach|fork|specialize|preload|no module|modules for' $L 2>/dev/null | tail -25
echo "=== 2) 提到 systemui 的行 ==="
grep -i 'systemui' $L 2>/dev/null | tail -10
echo "=== 3) 日志里出现过的模块名（去重） ==="
grep -oE '\[[a-z0-9._]+,[A-Za-z]' $L 2>/dev/null | sort -u | head -25
echo "=== 4) 日志尾部时间戳范围 ==="
head -3 $L 2>/dev/null
tail -2 $L 2>/dev/null
