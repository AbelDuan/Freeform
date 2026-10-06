#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
chmod 755 $R/tools/watcher-ratio2.sh
setsid sh $R/tools/watcher-ratio2.sh < /dev/null > /dev/null 2>&1 &
echo "watcher2 launched pid=$!"
sleep 2
cat $R/tools/ratio-run2.log 2>&1
