#!/bin/bash
set -u
cd /root/workspace/Freeform || exit 1
echo "=== 凭据助手 ==="
git config --global --get credential.helper || echo "(未设置)"
echo "=== 凭据文件里的条目（不显示 token） ==="
awk -F'[:@/]+' '{print $1"://"$2"@"$NF}' ~/.git-credentials 2>/dev/null | sed 's/[A-Za-z0-9_-]\{20,\}/<token>/g'
echo "=== 备份并移除 insteadOf ==="
git config --global --get-all url."https://gh-proxy.com/https://github.com/".insteadOf > /tmp/insteadof.bak 2>/dev/null || true
wc -l < /tmp/insteadof.bak
git config --global --unset-all url."https://gh-proxy.com/https://github.com/".insteadOf 2>/dev/null || true
git config --global credential.helper store
echo "=== 推送 ==="
timeout 150 git push https://github.com/AbelDuan/Freeform.git nbi-native 2>&1 | sed -E 's#//[^@]*@#//<redacted>@#g' | tail -8
echo "=== 恢复 insteadOf ==="
if [ -s /tmp/insteadof.bak ]; then
  while IFS= read -r v; do
    [ -n "$v" ] && git config --global --add url."https://gh-proxy.com/https://github.com/".insteadOf "$v"
  done < /tmp/insteadof.bak
fi
git config --global --get-all url."https://gh-proxy.com/https://github.com/".insteadOf
