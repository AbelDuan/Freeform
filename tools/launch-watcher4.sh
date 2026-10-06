#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
chmod 755 $R/tools/watcher-ratio4.sh
setsid sh $R/tools/watcher-ratio4.sh < /dev/null > /dev/null 2>&1 &
echo "watcher4 launched pid=$!"
sleep 20
echo "=== 日志 ==="
grep -vE 'TRACE|SplitTrace|沉浸:|多分屏|分屏吸附|小白条|AppCtx|窗口布局' $R/tools/ratio-run4.log | tail -14
