#!/bin/bash
# Boot the AVD and wait until it is genuinely usable.
set -euo pipefail
source "$(dirname "$0")/env.sh"

emulator -list-avds | grep -qx blueberry || \
  avdmanager create avd -n blueberry -k "system-images;android-36;google_apis;arm64-v8a" -d pixel_6 --force

if ! adb devices | grep -q "$ADB_SERIAL"; then
  # -allow-host-audio is the only way the guest mic hears anything at all; without it it reads
  # digital silence and any recogniser times out with ERROR_NO_MATCH rather than failing loudly.
  nohup emulator -avd blueberry -no-snapshot-save -gpu swiftshader_indirect \
    -no-boot-anim -allow-host-audio > /tmp/blueberry-emulator.log 2>&1 &
fi

adb -s "$ADB_SERIAL" wait-for-device
# wait-for-device only waits for adbd, not for the framework. Poll both properties, and strip the
# CR that `adb shell getprop` emits or the comparison never matches.
until [ "$(adb -s "$ADB_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
until [ "$(adb -s "$ADB_SERIAL" shell getprop init.svc.bootanim 2>/dev/null | tr -d '\r')" = "stopped" ]; do sleep 2; done
echo "booted: API $(adb -s "$ADB_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
