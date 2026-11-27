#!/usr/bin/env bash
# Makes sure a phone is connected over adb, reconnecting to an already-paired phone if needed.
# A paired phone never needs pairing again; only its port changes (on every reboot or toggle of
# Wireless debugging). The phone advertises its current port over mDNS, so we find it there.
#
# Usage: scripts/connect.sh [timeout-seconds]   (exit 0 = a device is ready)
set -uo pipefail

ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
ADB="$ANDROID_HOME/platform-tools/adb"
TIMEOUT="${1:-15}"

ready() { [[ -n $("$ADB" devices | awk 'NR>1 && $2=="device"') ]]; }

"$ADB" start-server >/dev/null 2>&1
ready && exit 0

echo "No device connected; looking for your phone on the network…" >&2
for ((i = 0; i < TIMEOUT; i++)); do
  # adb auto-connects paired phones it discovers, but nudge it in case discovery is slow.
  for svc in $("$ADB" mdns services 2>/dev/null | awk '$2 ~ /_adb-tls-connect/ {print $3}'); do
    "$ADB" connect "$svc" >/dev/null 2>&1
  done
  ready && { echo "Connected." >&2; exit 0; }
  sleep 1
done

cat >&2 <<EOF
Couldn't find the phone. Check that it's on the same Wi-Fi with Wireless debugging on
(Quick Settings tile, if you've added it). If it still isn't found, it may need pairing again:
  scripts/pair-qr.sh      (or: $ADB pair <IP:PORT> <code> from "Pair device with pairing code")
EOF
exit 1
