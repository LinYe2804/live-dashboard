#!/system/bin/sh

PID_FILE=/data/adb/live_dashboard/daemon.pid
if [ -f "$PID_FILE" ]; then
  DAEMON_PID=$(cat "$PID_FILE" 2>/dev/null)
  [ -n "$DAEMON_PID" ] && kill "$DAEMON_PID" 2>/dev/null
fi

# Configuration and unsent health records are intentionally retained in
# /data/adb/live_dashboard so reinstalling the module does not lose data.
