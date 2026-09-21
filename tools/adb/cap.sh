#!/system/bin/sh
# 抓取"分屏/多分屏"相关框架日志 + 模块探针（OS4FreeFromX），过滤后落盘
logcat -b all -c
logcat -v time -b all | grep -iE 'MulWin|MultiWin|MiuiMultiWin|Split|multiple|HotArea|hot_area|HotSpot|hotspot|Transit|TaskOrganizer|SoSc|freeform|inset|HotAreaType|MultiTasking|miuimultiwinswitch|OS4FreeFromX|TRACE' > /data/local/tmp/cap.txt
