#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
D=$R/tools/miui-jars
mkdir -p $D
echo "=== 1) 找 WindowManager-Shell / freeform 相关 jar ==="
find /system /system_ext /product /apex -maxdepth 4 -name '*WindowManager*' 2>/dev/null | head -20
echo "=== 2) 找含 MultiTasking/Freeform 的 jar ==="
find /system /system_ext /product -maxdepth 4 -name '*.jar' 2>/dev/null | grep -iE 'multitask|freeform|wmshell|windowmanager' | head -20
echo "=== 3) 复制到容器可见目录 ==="
for f in $(find /system /system_ext /product -maxdepth 4 -name '*.jar' 2>/dev/null | grep -iE 'multitask|freeform|wmshell|windowmanager' | head -6); do
  b=$(basename $f)
  cp -f $f $D/$b 2>/dev/null && echo "copied $b"
done
chmod 644 $D/*.jar 2>/dev/null
ls -la $D
