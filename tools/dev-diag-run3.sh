#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
echo "=== 日志文件 ==="
ls -la $R/tools/ratio-run3.log
echo "=== 原始内容行数 ==="
wc -l $R/tools/ratio-run3.log
echo "=== 尾部 12 行（不过滤） ==="
tail -12 $R/tools/ratio-run3.log
echo "=== 自由窗口 ==="
dumpsys activity activities 2>/dev/null | grep -E 'mode=freeform' | head -2
echo "=== logcat 里模块行数 ==="
logcat -d 2>/dev/null | grep -c OS4FreeFromX
