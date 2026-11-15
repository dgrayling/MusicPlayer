#!/usr/bin/env bash
# Pairs a phone for wireless debugging by QR code (same flow Android Studio uses):
# on the phone, Developer options > Wireless debugging > "Pair device with QR code".
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
ADB="$ANDROID_HOME/platform-tools/adb"

NAME="studio-$(openssl rand -hex 4)"
PASS=$(openssl rand -hex 6)
PAYLOAD="WIFI:T:ADB;S:$NAME;P:$PASS;;"

PNG="${TMPDIR:-/tmp}/adb-pair-qr.png"
qrencode -s 10 -m 2 -o "$PNG" "$PAYLOAD"
open "$PNG"   # opens in Preview; easier to scan than a terminal QR
qrencode -t ANSIUTF8 -m 2 "$PAYLOAD"
echo "On the phone: Wireless debugging > Pair device with QR code, then scan."
echo "Waiting for the phone (2 min)…"

# Once scanned, the phone advertises a pairing service named $NAME over mDNS.
for _ in $(seq 1 120); do
  addr=$("$ADB" mdns services | awk -v n="$NAME" '$1 == n && $2 ~ /_adb-tls-pairing/ {print $3}')
  if [[ -n $addr ]]; then
    echo "Found phone at $addr, pairing…"
    "$ADB" pair "$addr" "$PASS"
    # The phone's connect service lives on the same IP, different port.
    ip=${addr%:*}
    for _ in $(seq 1 15); do
      conn=$("$ADB" mdns services | awk -v ip="$ip" '$2 ~ /_adb-tls-connect/ && index($3, ip ":") == 1 {print $3; exit}')
      [[ -n $conn ]] && { "$ADB" connect "$conn"; break; }
      sleep 1
    done
    "$ADB" devices -l
    exit 0
  fi
  sleep 1
done
echo "Timed out waiting for the phone to scan the code." >&2
exit 1
