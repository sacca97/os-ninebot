#!/usr/bin/env bash
# Capture the official Segway app's BLE traffic on a rooted Android phone.
# This only touches the PHONE (snoop-log settings, Bluetooth toggle, file pull).
# It never talks to the scooter.
#
#   scripts/android_capture.sh setup   # enable full HCI snoop log, restart Bluetooth
#   ... use the Segway app: connect, wait 5s, LOCK, wait 3s, UNLOCK, wait 3s, close app ...
#   scripts/android_capture.sh pull    # copy the log to ./captures/
set -euo pipefail

su_sh() { adb shell "su -c '$*'"; }

need_device() {
  adb get-state >/dev/null 2>&1 || { echo "No adb device. Plug in the phone and allow USB debugging."; exit 1; }
}

case "${1:-}" in
  setup)
    need_device
    echo "Android $(adb shell getprop ro.build.version.release | tr -d '\r')"
    # Android 10+: full (unfiltered) snoop log. Older: the settings key.
    su_sh setprop persist.bluetooth.btsnooplogmode full || true
    adb shell settings put secure bluetooth_hci_log 1 || true
    echo "snoop mode: $(adb shell getprop persist.bluetooth.btsnooplogmode | tr -d '\r')"
    echo "Restarting Bluetooth so the setting takes effect ..."
    adb shell cmd bluetooth_manager disable 2>/dev/null || su_sh svc bluetooth disable
    sleep 3
    adb shell cmd bluetooth_manager enable 2>/dev/null || su_sh svc bluetooth enable
    cat <<'EOF'

Now on the phone:
  1. open the Segway app, connect to the scooter
  2. wait 5 s, LOCK, wait 3 s, UNLOCK, wait 3 s
  3. close the app
Then run:  scripts/android_capture.sh pull
EOF
    ;;
  pull)
    need_device
    mkdir -p captures
    out="captures/btsnoop_$(date +%Y%m%d_%H%M%S).log"
    for src in /data/misc/bluetooth/logs/btsnoop_hci.log /sdcard/btsnoop_hci.log /data/log/bt/btsnoop_hci.log; do
      if su_sh "test -s $src" 2>/dev/null; then
        su_sh "cp $src /sdcard/f2_btsnoop.log && chmod 644 /sdcard/f2_btsnoop.log"
        adb pull /sdcard/f2_btsnoop.log "$out" >/dev/null
        adb shell rm -f /sdcard/f2_btsnoop.log
        echo "Saved $src -> $out"
        echo "Next:  f2 decode $out"
        exit 0
      fi
    done
    echo "Snoop log not found in the usual places. Fallback: adb bugreport, then look for"
    echo "FS/data/misc/bluetooth/logs/btsnoop_hci.log inside the zip."
    exit 1
    ;;
  *)
    sed -n '2,9p' "$0"; exit 1 ;;
esac
