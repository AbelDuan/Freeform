#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
chmod 755 $R/tools/watcher-ratio.sh
setsid sh $R/tools/watcher-ratio.sh < /dev/null > /dev/null 2>&1 &
echo "watcher launched pid=$!"
sleep 2
cat $R/tools/ratio-run.log 2>&1
