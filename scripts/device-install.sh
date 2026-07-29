#!/bin/bash
# Install Blueberry onto a real phone over wifi.
#
#   ./scripts/device-install.sh                 # auto-discover an already-paired device
#   ./scripts/device-install.sh 192.168.29.57:37419
#   ./scripts/device-install.sh <serial> --launcher   # also make it the default launcher
#
# Pairing is a one-time interactive step and is NOT done here — see README, "Testing on a phone".
set -uo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh

TARGET="${1:-}"
shift 2>/dev/null || true
SET_LAUNCHER=0
for arg in "$@"; do [ "$arg" = "--launcher" ] && SET_LAUNCHER=1; done

if [ -z "$TARGET" ]; then
  # Wireless debugging advertises itself over mDNS as _adb-tls-connect._tcp
  TARGET=$(adb mdns services 2>/dev/null | awk '/_adb-tls-connect/ {print $3; exit}')
  [ -n "$TARGET" ] && echo "discovered $TARGET"
fi

if [ -z "$TARGET" ]; then
  echo "No device given and none discovered."
  echo "On the phone: Developer options > Wireless debugging, then read the IP:PORT off that screen."
  echo "If you have never paired this Mac, pair first (see README)."
  exit 1
fi

adb connect "$TARGET" | tr -d '\r'
# The emulator is probably still attached, so every command from here is explicitly targeted.
adb -s "$TARGET" wait-for-device || { echo "could not reach $TARGET"; exit 1; }

echo "device: $(adb -s "$TARGET" shell getprop ro.product.model | tr -d '\r') / Android $(adb -s "$TARGET" shell getprop ro.build.version.release | tr -d '\r') (API $(adb -s "$TARGET" shell getprop ro.build.version.sdk | tr -d '\r'))"

./gradlew :app:assembleDebug --console=plain -q || exit 1

# -r keeps the install in place, which preserves any persisted SAF vault grant.
# -g pre-grants RECORD_AUDIO so the first tap goes straight to listening.
# Never `adb uninstall` to reinstall — that revokes the vault grant and forces a re-pick.
adb -s "$TARGET" install -r -g app/build/outputs/apk/debug/app-debug.apk | tail -2

if [ "$SET_LAUNCHER" = "1" ]; then
  echo "setting default launcher: $(adb -s "$TARGET" shell cmd package set-home-activity "$PKG/.ui.HomeActivity" | tr -d '\r')"
  echo "to undo: Settings > Apps > Default apps > Home app"
fi

echo
echo "Is on-device speech available on this phone?  (tap once, speak, then:)"
echo "  adb -s $TARGET logcat -d | grep -iE 'SpeechSource|Soda|on-device'"
echo
echo "Inject a transcript without speaking:"
echo "  ADB_SERIAL=$TARGET ./scripts/inject.sh partial 'open spotify'"
echo
echo "Read captured notes (no vault configured yet):"
echo "  adb -s $TARGET shell run-as $PKG cat files/notes/Inbox.md"
