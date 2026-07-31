package com.blueberry.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.blueberry.voice.VoiceAudition

/**
 * Debug-only voice audition.
 *
 * Kokoro ships 53 speakers and picking one is a listening decision, not a reasoning one — so this
 * exists to make hearing them cost a broadcast rather than a rebuild.
 *
 * ```
 * adb shell am broadcast -a com.blueberry.SAY \
 *   -n com.blueberry/com.blueberry.debug.VoiceProbeReceiver -f 0x00000020 \
 *   --ei sid 0 --es text 'the quick brown fox jumps over the lazy dog'
 * ```
 *
 * Omit `sid` to hear the current voice. Sweep with `--ei sweep 1` to hear a spread of them in turn,
 * each announced by number so the good one can be named afterwards.
 */
class VoiceProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text")
            ?: "The quick brown fox jumps over the lazy dog."
        val sid = intent.getIntExtra("sid", -1)
        val sweep = intent.getIntExtra("sweep", 0) == 1
        Log.i(TAG, "audition sid=$sid sweep=$sweep text=\"$text\"")
        VoiceAudition.request(sid, text, sweep)
    }

    private companion object { const val TAG = "VoiceProbe" }
}
