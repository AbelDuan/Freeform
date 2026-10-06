#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
input swipe 818 1520 818 1520 1000
sleep 3
for i in 1 2 3; do
  if uiautomator dump /data/local/tmp/ui.xml 2>&1 | grep -q 'dumped to'; then break; fi
  sleep 2
done
cp -f /data/local/tmp/ui.xml $R/build/ui-iconmenu.xml 2>/dev/null
chmod 644 $R/build/ui-iconmenu.xml 2>/dev/null
ls -la $R/build/ui-iconmenu.xml
