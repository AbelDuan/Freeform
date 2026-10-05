#!/system/bin/sh
echo "=== 1) 当前用户 / 用户列表 ==="
am get-current-user 2>&1
pm list users 2>&1
echo "=== 2) /data/user 与 /data/user_de ==="
ls -la /data/user/ 2>&1
ls /data/user/0/ 2>/dev/null | wc -l
ls /data/user_de/0/ 2>/dev/null | wc -l
echo "=== 3) /data/app 项数（对照） ==="
ls /data/app/ 2>/dev/null | wc -l
echo "=== 4) /data 顶层 ==="
ls -la /data/ 2>&1
echo "=== 5) /data/user 相关挂载 ==="
cat /proc/mounts 2>/dev/null | grep -E ' /data' | head -20
echo "=== 6) 我们自己的命名空间 ==="
readlink /proc/self/ns/mnt 2>&1
readlink /proc/1/ns/mnt 2>&1
