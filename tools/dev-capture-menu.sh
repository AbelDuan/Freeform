#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
screencap -p -d 4639175402683733248 $R/build/menu.png
chmod 644 $R/build/menu.png
ls -la $R/build/menu.png
for i in 1 2 3 4; do
  if uiautomator dump /data/local/tmp/ui.xml 2>&1 | grep -q 'dumped to'; then break; fi
  sleep 2
done
cp -f /data/local/tmp/ui.xml $R/build/ui-menu.xml 2>/dev/null
chmod 644 $R/build/ui-menu.xml 2>/dev/null
ls -la $R/build/ui-menu.xml
echo "=== 自由窗口任务 ==="
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' | head -3
