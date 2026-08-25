#!/system/bin/sh

SKIPUNZIP=0

ui_print "- Installing Live Dashboard root reporter"
mkdir -p /data/adb/live_dashboard
chmod 700 /data/adb/live_dashboard
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/bin/live-dashboard-daemon" 0 0 0755
