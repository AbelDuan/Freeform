#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
chmod 755 $R/tools/sidebar-capture.sh
setsid sh $R/tools/sidebar-capture.sh < /dev/null > /dev/null 2>&1 &
echo "capture launched pid=$!"
sleep 2
head -3 $R/tools/sidebar-test.txt
