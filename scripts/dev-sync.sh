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

PINNED=${ANDROID_SERIAL:-}   # a device the user chose explicitly; otherwise re-picked each build

# Picks the phone to deploy to, connecting first if needed (the port changes on every reboot).
pick_device() {
  scripts/connect.sh "$1" || return 1
  [[ -n $PINNED ]] && return 0
  local devices
  devices=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')
  if [[ $(wc -l <<<"$devices") -gt 1 ]]; then
    # A wirelessly-paired phone often shows up twice (IP:port and its mDNS name). Collapse
    # entries for the same hardware, preferring the mDNS name since it survives port changes.
    devices=$(for d in $devices; do
      echo "$("$ADB" -s "$d" shell getprop ro.serialno 2>/dev/null | tr -d '\r') $([[ $d == *_adb-tls-connect* ]] && echo 0 || echo 1) $d"
    done | sort -k1,1 -k2,2 | awk '!seen[$1]++ {print $3}')
    if [[ $(wc -l <<<"$devices") -gt 1 ]]; then
      echo "Several devices connected; pick one with ANDROID_SERIAL=<id>:"; echo "$devices"; return 1
    fi
  fi
  export ANDROID_SERIAL=$devices
}

pick_device 15 || exit 1
echo "Device: $ANDROID_SERIAL ($("$ADB" shell getprop ro.product.model | tr -d '\r'))"

STAMP=$(mktemp)
trap 'rm -f "$STAMP"' EXIT

deploy() {
  local start=$SECONDS out
  echo "[$(date +%H:%M:%S)] Building & installing…"
  # The phone may have dropped off (sleep, reboot, Wi-Fi blip) since the last build; pick it back up.
  pick_device 30 || return
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
