#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
cmd statusbar expand-notifications 2>&1
sleep 3
for i in 1 2 3; do
  if uiautomator dump /data/local/tmp/ui.xml 2>&1 | grep -q 'dumped to'; then break; fi
  sleep 2
done
cp -f /data/local/tmp/ui.xml $R/build/ui-shade.xml 2>/dev/null
chmod 644 $R/build/ui-shade.xml 2>/dev/null
ls -la $R/build/ui-shade.xml
screencap -p -d 4639175402683733248 $R/build/shade.png
chmod 644 $R/build/shade.png
ls -la $R/build/shade.png
