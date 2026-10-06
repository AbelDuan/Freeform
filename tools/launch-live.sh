#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
chmod 755 $R/tools/live-capture.sh
setsid sh $R/tools/live-capture.sh < /dev/null > /dev/null 2>&1 &
echo "live capture pid=$!"
sleep 2
head -2 $R/tools/live-capture.txt
