#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
chmod 755 $R/tools/post-restart-0430.sh
setsid sh $R/tools/post-restart-0430.sh < /dev/null > /dev/null 2>&1 &
echo "launched pid=$!"
sleep 2
head -2 $R/tools/post-restart-0430.txt
