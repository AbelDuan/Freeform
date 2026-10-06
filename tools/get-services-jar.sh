#!/system/bin/sh
R=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform
D=$R/tools/miui-jars
mkdir -p $D
cp -f /system_ext/framework/miui-services.jar $D/miui-services.jar 2>/dev/null && echo "copied miui-services.jar"
chmod 644 $D/*.jar 2>/dev/null
ls -la $D
