#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
input tap 700 137
sleep 1
screencap -p -d 4639175402683733248 $R/build/cap1.png
chmod 644 $R/build/cap1.png
echo "=== 截图1完成 ==="
ls -la $R/build/cap1.png
