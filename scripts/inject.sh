#!/bin/bash
# Inject a transcript into a debug build.  usage: inject.sh <partial|final|listening|level> <text>
# Quoted twice on purpose: adb concatenates argv and the device shell re-parses it, so an unquoted
# multi-word transcript silently becomes `pkg=<secondword>` on the intent.
source "$(dirname "$0")/env.sh"
adb -s "$ADB_SERIAL" shell \
  "am broadcast -a com.blueberry.INJECT_TRANSCRIPT \
   -n $PKG/com.blueberry.debug.TranscriptInjectReceiver -f 0x00000020 \
   --es kind '$1' --es text '$2'" > /dev/null
