#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
chmod 755 $R/tools/post-restart.sh
setsid sh $R/tools/post-restart.sh < /dev/null > /dev/null 2>&1 &
echo "launched detached pid=$!"
sleep 2
echo "--- 报告文件 ---"
ls -la $R/tools/post-restart-report.txt 2>&1
