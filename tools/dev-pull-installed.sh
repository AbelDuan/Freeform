#!/system/bin/sh
# 把设备上已安装的模块 APK 复制到容器可见路径，供比对
SRC=/data/app/~~8xKJnjgPMdIkWUfT-k-Png==/com.abel.os4freeformx-oC-qN1VfDHomnBpoRe6Huw==/base.apk
DST=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace/Freeform/build/installed.apk
cp -f "$SRC" "$DST"
chmod 644 "$DST"
ls -la "$DST"
