#!/system/bin/sh

MODDIR=${0%/*}
DATA_DIR=/data/adb/live_dashboard
LOG_FILE=$DATA_DIR/daemon.log
PID_FILE=$DATA_DIR/daemon.pid

mkdir -p "$DATA_DIR"
chmod 700 "$DATA_DIR"

while [ "$(getprop sys.boot_completed)" != "1" ]; do
  sleep 2
done

if [ -f "$PID_FILE" ]; then
  OLD_PID=$(cat "$PID_FILE" 2>/dev/null)
  if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
    exit 0
  fi
fi

while true; do
  "$MODDIR/bin/live-dashboard-daemon" >>"$LOG_FILE" 2>&1 &
  DAEMON_PID=$!
  echo "$DAEMON_PID" >"$PID_FILE"
  wait "$DAEMON_PID"
  sleep 5
done
