#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
input keyevent 187
sleep 3
echo "--- 长按中间卡片 ---"
input swipe 836 1100 836 1100 900
sleep 3
for i in 1 2 3; do
  if uiautomator dump /data/local/tmp/ui.xml 2>&1 | grep -q 'dumped to'; then break; fi
  sleep 2
done
cp -f /data/local/tmp/ui.xml $R/build/ui-cardmenu.xml 2>/dev/null
chmod 644 $R/build/ui-cardmenu.xml 2>/dev/null
ls -la $R/build/ui-cardmenu.xml
screencap -p -d 4639175402683733248 $R/build/cardmenu.png
chmod 644 $R/build/cardmenu.png
ls -la $R/build/cardmenu.png
