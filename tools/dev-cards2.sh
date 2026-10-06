#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
input keyevent 4
sleep 2
for i in 1 2 3; do
  if uiautomator dump /data/local/tmp/ui.xml 2>&1 | grep -q 'dumped to'; then break; fi
  sleep 2
done
cp -f /data/local/tmp/ui.xml $R/build/ui-cards2.xml 2>/dev/null
chmod 644 $R/build/ui-cards2.xml 2>/dev/null
ls -la $R/build/ui-cards2.xml
