#!/system/bin/sh
echo "=== 1) 新 daemon 的 SELinux 域 ==="
P=$(pidof lspd)
cat /proc/$P/attr/current 2>&1
echo "=== 2) sepolicy.rule ==="
cat /data/adb/modules/zygisk_lsposed/sepolicy.rule 2>&1
echo "=== 3) monitor 文件 ==="
cat /data/adb/lspd/monitor 2>&1; echo
echo "=== 4) 日志里出现过的 bridge socket 名 ==="
grep -rhoE 'lspbridge-[0-9a-f-]+' /data/adb/lspd/log/ /data/adb/lspd/log.old/ 2>/dev/null | sort -u | head
echo "=== 5) log.old 内容 ==="
ls -la /data/adb/lspd/log.old/ 2>&1 | head
echo "=== 6) zygote 里是否加载了 liblspd ==="
for z in $(pidof zygote64 zygote); do
  echo -n "pid $z: "; grep -c 'lspd\|lsposed' /proc/$z/maps 2>/dev/null
done
echo "=== 7) zygote 进程与域 ==="
for z in $(pidof zygote64 zygote); do
  echo -n "pid $z ctx="; cat /proc/$z/attr/current 2>&1
done
echo "=== 8) 当前所有 lsp 相关 socket ==="
grep -E 'lspbridge|lsp_dex2oat' /proc/net/unix 2>/dev/null | wc -l
