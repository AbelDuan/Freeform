#!/system/bin/sh
SKIPUNZIP=0
ui_print "- OS4 Rust 桌面手势判定放宽"
ui_print "- 目标进程: com.miui.home"
set_perm_recursive $MODPATH 0 0 0755 0644
