#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
chmod 755 $R/tools/watcher-ratio3.sh
setsid sh $R/tools/watcher-ratio3.sh < /dev/null > /dev/null 2>&1 &
echo "watcher3 launched pid=$!"
sleep 2
ls -la $R/tools/ratio-run3.log 2>&1
cat $R/tools/ratio-run3.log 2>&1 | head -5
