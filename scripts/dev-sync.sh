#!/usr/bin/env bash
# Watches the app's sources and, on every change, rebuilds, installs over adb
# (USB or wireless debugging) and relaunches the app. Ctrl+C to stop.
#
# Usage: scripts/dev-sync.sh            # uses the only connected device
#        ANDROID_SERIAL=<id> scripts/dev-sync.sh   # pick one of several
set -uo pipefail

cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
ADB="$ANDROID_HOME/platform-tools/adb"
APP=com.example.musicplayer/.MainActivity
WATCH=(app/src/main app/build.gradle.kts gradle/libs.versions.toml)

devices=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')
if [[ -z $devices ]]; then
  cat <<EOF
No device connected. To connect your phone over Wi-Fi (one-time setup):
  1. Settings > About phone > tap "Build number" 7 times (enables Developer options)
  2. Settings > System > Developer options > turn on "Wireless debugging"
  3. Tap "Wireless debugging" > "Pair device with pairing code", then run:
       $ADB pair <IP:PORT shown in that dialog> <6-digit code>
  4. Back on the Wireless debugging screen, note "IP address & port" and run:
       $ADB connect <IP:PORT>
Then run this script again.
EOF
  exit 1
fi
if [[ -z ${ANDROID_SERIAL:-} && $(wc -l <<<"$devices") -gt 1 ]]; then
  # A wirelessly-paired phone often shows up twice (IP:port and its mDNS name). Collapse
  # entries for the same hardware, preferring the mDNS name since it survives port changes.
  unique=$(for d in $devices; do
    echo "$("$ADB" -s "$d" shell getprop ro.serialno 2>/dev/null | tr -d '\r') $([[ $d == *_adb-tls-connect* ]] && echo 0 || echo 1) $d"
  done | sort -k1,1 -k2,2 | awk '!seen[$1]++ {print $3}')
  if [[ $(wc -l <<<"$unique") -gt 1 ]]; then
    echo "Several devices connected; pick one with ANDROID_SERIAL=<id>:"; echo "$unique"; exit 1
  fi
  devices=$unique
fi
export ANDROID_SERIAL="${ANDROID_SERIAL:-$devices}"
echo "Device: $ANDROID_SERIAL ($("$ADB" shell getprop ro.product.model | tr -d '\r'))"

STAMP=$(mktemp)
trap 'rm -f "$STAMP"' EXIT

deploy() {
  local start=$SECONDS out
  echo "[$(date +%H:%M:%S)] Building & installing…"
  if out=$(./gradlew installDebug -q --console=plain 2>&1); then
    "$ADB" shell am start -n "$APP" >/dev/null
    echo "[$(date +%H:%M:%S)] ✓ Updated on device ($((SECONDS - start))s)"
  else
    echo "[$(date +%H:%M:%S)] ✗ Build failed:"
    grep -E "^e: |error:|What went wrong" -A2 <<<"$out" | head -25
  fi
}

touch "$STAMP"
deploy
echo "Watching for changes… (Ctrl+C to stop)"
# The stamp is only advanced when a change is seen, so edits made during a build trigger another one.
while true; do
  sleep 1
  if [[ -n $(find "${WATCH[@]}" -newer "$STAMP" 2>/dev/null | head -1) ]]; then
    # Wait for edits to settle (editors often write several files in a burst).
    while sleep 1.5; [[ -n $(find "${WATCH[@]}" -newer "$STAMP" 2>/dev/null | head -1) ]]; do
      touch "$STAMP"
    done
    deploy
  fi
done
