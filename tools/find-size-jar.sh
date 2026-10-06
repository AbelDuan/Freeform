#!/system/bin/sh
echo "=== 候选框架 jar ==="
ls /system/framework/*.jar /system_ext/framework/*.jar 2>/dev/null | head -30
echo "=== 哪个 jar 含 MiuiFreeformModeUtils / getDefaultFreeformBounds ==="
for f in /system/framework/framework.jar /system/framework/services.jar /system_ext/framework/miui-framework.jar /system_ext/framework/MiuiFramework.jar /system/framework/miui-framework.jar /system_ext/framework/miui-services.jar /system/framework/miui-services.jar; do
  if [ -f "$f" ]; then
    a=$(grep -c -a 'MiuiFreeformModeUtils' "$f" 2>/dev/null)
    b=$(grep -c -a 'getDefaultFreeformBounds' "$f" 2>/dev/null)
    c=$(grep -c -a 'MiuiMultiWindowUtils' "$f" 2>/dev/null)
    echo "$f : ModeUtils=$a DefaultBounds=$b MultiWindowUtils=$c"
  fi
done
