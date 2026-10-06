#!/system/bin/sh
echo "=== A) am start 帮助（前 45 行） ==="
am start --help 2>&1 | head -45
echo "=== B) 最近任务 dump 是否成功 ==="
input keyevent 187
sleep 3
uiautomator dump /data/local/tmp/uiA.xml 2>&1 | tail -1
echo "含未加锁行数: $(grep -c '未加锁' /data/local/tmp/uiA.xml 2>/dev/null)"
echo "=== C) 右缘内滑唤出侧边栏 ==="
input swipe 2350 836 1750 836 500
sleep 3
uiautomator dump /data/local/tmp/uiB.xml 2>&1 | tail -1
grep -oE 'content-desc="[^"]{1,20}"' /data/local/tmp/uiB.xml 2>/dev/null | sort -u | head -25
