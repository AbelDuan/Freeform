#!/system/bin/sh
echo "=== 1) 触发包变更（重装本模块） ==="
pm install -r /data/local/tmp/os4ffx.apk 2>&1
sleep 20
L=$(ls -t /data/adb/lspd/log/verbose_*.log 2>/dev/null | head -1)
echo "=== 2) daemon 日志尾部（看 bridge 是否复活） ==="
tail -8 "$L" 2>/dev/null
echo "=== 3) 重启 SystemUI 并检查注入 ==="
killall com.android.systemui
sleep 18
SUI=$(pidof com.android.systemui)
echo "SystemUI: $SUI"
for m in com.abel.os4freeformx com.sevtinge.hyperceiler; do
  echo "  $m -> $(grep -c $m /proc/$SUI/maps 2>/dev/null)"
done
echo "=== 4) 模块数据库行（重装后是否还在） ==="
sh /data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform/tools/lspd-dump.sh >/dev/null 2>&1
echo dumped
