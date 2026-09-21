#!/system/bin/sh
# 把 immersive 开关改为 false（用于隔离"沉浸式 hook 是否导致多分屏无焦点"）
CFG1=/data/data/com.abel.os4freeformx/shared_prefs/os4freeformx_cfg.xml
CFG2=/data/user_de/0/com.abel.os4freeformx/shared_prefs/os4freeformx_cfg.xml
for f in "$CFG1" "$CFG2"; do
  [ -f "$f" ] || continue
  sed -i 's/name="immersive" value="true"/name="immersive" value="false"/' "$f"
  echo "== $f =="
  grep immersive "$f"
done
