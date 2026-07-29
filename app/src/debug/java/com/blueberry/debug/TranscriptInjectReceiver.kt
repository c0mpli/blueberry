package com.blueberry.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.blueberry.voice.AsrEvent
import com.blueberry.voice.TranscriptSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow

/**
 * Debug-only transcript injection.
 *
 * Real speech cannot be exercised on an emulator: the guest microphone reads digital silence unless
 * the emulator is started with `-allow-host-audio`, and even then "host audio" is the Mac's default
 * input device — there is no flag that accepts a WAV file. Rather than pretend otherwise, the
 * transcript is modelled as a source ([TranscriptSource]) with more than one implementation, and
 * this one is driven from adb:
 *
 * ```
 * adb shell am broadcast -a com.blueberry.INJECT_TRANSCRIPT \
 *   -n com.blueberry/com.blueberry.debug.TranscriptInjectReceiver -f 0x00000020 \
 *   --es text "open spotify" --es kind partial
 * ```
 *
 * That makes the whole milestone-5 path — partial in, intent out, before any endpointing — testable
 * from a script, on any image, with no audio dependency at all. It lives in `src/debug`, so it
 * cannot ship.
 */
class TranscriptInjectReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        val kind = intent.getStringExtra(EXTRA_KIND) ?: "final"
        Log.i(TAG, "inject kind=$kind text=$text")

        val event = when (kind) {
            "partial" -> AsrEvent.Partial(text)
            "level" -> AsrEvent.Level(text.toFloatOrNull() ?: 0f)
            "listening" -> AsrEvent.Listening
            else -> AsrEvent.Final(text)
        }
        InjectedTranscriptSource.INSTANCE.inject(event)
    }

    companion object {
        const val TAG = "TranscriptInject"
        const val ACTION = "com.blueberry.INJECT_TRANSCRIPT"
        const val EXTRA_TEXT = "text"
        const val EXTRA_KIND = "kind"
    }
}

/**
 * A [TranscriptSource] fed by the receiver above. A process-wide singleton because a
 * `BroadcastReceiver` is constructed by the framework and has no other way to reach the view model.
 */
class InjectedTranscriptSource private constructor() : TranscriptSource {

    private val _events = MutableSharedFlow<AsrEvent>(replay = 0, extraBufferCapacity = 32)
    override val events: Flow<AsrEvent> = _events

    fun inject(event: AsrEvent) {
        _events.tryEmit(event)
    }

    override fun prewarm() = Unit
    override fun start() { inject(AsrEvent.Listening) }
    override fun stop() = Unit
    override fun release() = Unit

    companion object {
        val INSTANCE = InjectedTranscriptSource()
    }
}
