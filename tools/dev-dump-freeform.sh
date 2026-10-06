#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
uiautomator dump /data/local/tmp/ui.xml 2>&1 | tail -1
cp -f /data/local/tmp/ui.xml $R/build/ui-freeform.xml
chmod 644 $R/build/ui-freeform.xml
echo "=== 模块日志（小窗相关） ==="
logcat -d 2>/dev/null | grep -F 'OS4FreeFromX' | grep -vE 'TRACE|SplitTrace' | tail -25
echo "=== 自由窗口任务 ==="
dumpsys activity activities 2>/dev/null | grep -E 'Task\{.*mode=freeform' | head -3
