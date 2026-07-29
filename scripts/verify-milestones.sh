#!/bin/bash
# Milestones 1-5 of the definition of done, asserted end to end on a booted emulator.
#
#   ./scripts/emulator-up.sh && ./scripts/verify-milestones.sh
#
# Milestone 3 is a JVM test and needs no device. Milestones 4 and 5 are driven through injected
# transcripts rather than real speech — see the note at the bottom for why that is not a cop-out.
set -uo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh

PASS=0
FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS+1)); }
bad()  { echo "  FAIL  $1"; FAIL=$((FAIL+1)); }
check(){ if [ "$1" = "0" ]; then ok "$2"; else bad "$2 -- $3"; fi }
adbs() { adb -s "$ADB_SERIAL" "$@"; }
inject(){ adbs shell "am broadcast -a com.blueberry.INJECT_TRANSCRIPT -n $PKG/com.blueberry.debug.TranscriptInjectReceiver -f 0x00000020 --es kind '$1' --es text '$2'" >/dev/null 2>&1; }
resumed(){ adbs shell dumpsys activity activities | grep -E 'topResumedActivity=' | head -1 | tr -d '\r'; }
home()   { adbs shell am force-stop "$PKG"; sleep 1; adbs shell input keyevent KEYCODE_HOME; sleep 3; }

mkdir -p artifacts

echo "== build =="
./gradlew :app:assembleDebug --console=plain -q || { echo "build failed"; exit 1; }
adbs install -r -g app/build/outputs/apk/debug/app-debug.apk >/dev/null 2>&1
echo "  installed"

echo
echo "== M3: router is pure Kotlin and passes plain JVM tests =="
./gradlew :app:testDebugUnitTest --console=plain -q >/dev/null 2>&1
check $? "unit tests (includes RouterPurityTest)" "see app/build/reports/tests"

echo
echo "== M1: default launcher, home press, blank tap-to-speak surface =="
OUT=$(adbs shell cmd package set-home-activity "$PKG/.ui.HomeActivity" | tr -d '\r')
[ "$OUT" = "Success" ]; check $? "set-home-activity" "$OUT"

HOLDER=$(adbs shell cmd role get-role-holders android.app.role.HOME | tr -d '\r')
[ "$HOLDER" = "$PKG" ]; check $? "holds android.app.role.HOME" "holder=$HOLDER"

adbs shell am start -a android.settings.SETTINGS >/dev/null 2>&1; sleep 2
adbs shell input keyevent KEYCODE_HOME; sleep 2
resumed | grep -q "$PKG"; check $? "HOME returns to Blueberry" "$(resumed)"

adbs shell input keyevent KEYCODE_BACK; sleep 1
adbs shell dumpsys window | grep -E 'mCurrentFocus=' | grep -q "$PKG"
check $? "BACK does not exit the launcher" "focus left Blueberry"

adbs exec-out screencap -p > artifacts/m1-home.png
[ -s artifacts/m1-home.png ]; check $? "home screenshot captured" "empty file"

echo
echo "== M2: swipe up gives a searchable app drawer =="
home
adbs shell input swipe 540 1900 540 800 250; sleep 2
adbs exec-out screencap -p > artifacts/m2-drawer.png
# The drawer is only non-empty because the manifest declares <queries> for MAIN/LAUNCHER.
# LauncherApps.getActivityList does NOT bypass package visibility; without it this is blank.
SIZE=$(wc -c < artifacts/m2-drawer.png)
[ "$SIZE" -gt 100000 ]; check $? "drawer renders a populated list" "screenshot only ${SIZE}b, likely empty"

echo
echo "== M5: 'open chrome' fires on the PARTIAL transcript, no final sent =="
home
inject listening x
inject partial "open chrome"
sleep 3
resumed | grep -q "com.android.chrome"; check $? "partial alone launched Chrome" "$(resumed)"

echo
echo "== M4: 'note down ...' lands in the vault with no network =="
adbs shell cmd connectivity airplane-mode enable >/dev/null 2>&1; sleep 2
[ "$(adbs shell settings get global airplane_mode_on | tr -d '\r')" = "1" ]
check $? "airplane mode engaged" "could not enable"

home
NOTE="talk to OISL about success criteria"
inject listening x
inject partial "note down $NOTE"
sleep 3
adbs shell run-as "$PKG" cat files/notes/Inbox.md 2>/dev/null | tr -d '\r' | grep -q "$NOTE"
check $? "note captured verbatim, offline" "$(adbs shell run-as $PKG cat files/notes/Inbox.md 2>&1 | tr -d '\r')"
adbs exec-out screencap -p > artifacts/m4-saved.png
adbs shell cmd connectivity airplane-mode disable >/dev/null 2>&1

echo
echo "-------------------------------------------"
echo "  $PASS passed, $FAIL failed"
echo "-------------------------------------------"
# Milestones 4 and 5 assert the router path, not the microphone. Real speech cannot be exercised on
# an emulator: the guest mic reads digital silence without -allow-host-audio, and even with it
# "host audio" is the Mac's default input device, which no flag can point at a fixture file. The
# recogniser itself has to be verified by hand on a real phone.
[ "$FAIL" = "0" ]
