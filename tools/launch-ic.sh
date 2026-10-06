#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
chmod 755 $R/tools/install-and-capture.sh
setsid sh $R/tools/install-and-capture.sh < /dev/null > /dev/null 2>&1 &
echo "launched pid=$!"
sleep 25
cat $R/tools/noreopen-test.txt 2>/dev/null | tail -5
