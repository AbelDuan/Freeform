#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
echo "=== 1) am start 全部帮助里的窗口选项 ==="
am start --help 2>&1 | grep -iE '^ *--' | head -40
echo "=== 2) 进入最近任务 ==="
input keyevent 187
sleep 3
uiautomator dump /data/local/tmp/ui.xml 2>&1 | tail -1
cp -f /data/local/tmp/ui.xml $R/build/ui-recents.xml 2>/dev/null
chmod 644 $R/build/ui-recents.xml 2>/dev/null
ls -la $R/build/ui-recents.xml
echo "=== 3) 截图 ==="
screencap -p -d 4639175402683733248 $R/build/recents.png
chmod 644 $R/build/recents.png
ls -la $R/build/recents.png
