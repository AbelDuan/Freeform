#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
chmod 755 $R/tools/post-restart-0423.sh
setsid sh $R/tools/post-restart-0423.sh < /dev/null > /dev/null 2>&1 &
echo "launched pid=$!"
sleep 2
ls -la $R/tools/post-restart-0423.txt
