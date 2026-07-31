package com.blueberry.voice

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * Silences the recogniser's start and end tones — the "du-dun" the phone makes when the mic opens.
 *
 * Those beeps come from the recognition *service*, not from Blueberry, so there is no extra to turn
 * them off with; the only lever is muting the streams they play on for the duration of listening.
 * They are also actively harmful here: the whole design is that you tap and talk immediately, and a
 * tone playing over the first syllable is both slower and worse than silence.
 *
 * Muting the device's audio streams is a hostile thing to leave switched on, so this is written
 * defensively:
 *
 *  * [restore] is idempotent and safe to call from anywhere, including error paths.
 *  * It restores on every terminal recogniser callback, not just the happy one.
 *  * `STREAM_MUSIC` *is* muted, because that is where the tone actually plays on current Android.
 *    It is safe only because [restore] runs on `onResults`/`onError`, which always precede
 *    speaking — the mute covers listening, never the answer.
 *
 * Only used on API 29-32. From 33 the app supplies its own audio and the tone never happens; see
 * [MicPipe]. Note also that mute requests are released automatically if the process dies, so a
 * crash cannot leave the phone permanently silent.
 */
class BeepSuppressor(context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)

    /**
     * Streams the recogniser tones play on. Which one it is varies by OEM — Google's recogniser
     * uses `STREAM_MUSIC` on most handsets, Samsung's uses the system stream — so all four are
     * muted rather than guessing.
     *
     * Muting `STREAM_MUSIC` is safe here only because [restore] runs on `onResults`/`onError`,
     * which always precede speaking: the mute covers listening, never the answer.
     */
    private val streams = intArrayOf(
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_SYSTEM,
        AudioManager.STREAM_NOTIFICATION,
        AudioManager.STREAM_RING,
    )

    private var muted = false

    fun mute() {
        if (muted || audio == null) return
        muted = true
        for (stream in streams) {
            runCatching { audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0) }
                .onFailure { Log.w(TAG, "could not mute stream $stream", it) }
        }
    }

    fun restore() {
        if (!muted || audio == null) return
        muted = false
        for (stream in streams) {
            runCatching { audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0) }
                .onFailure { Log.w(TAG, "could not unmute stream $stream", it) }
        }
    }

    private companion object { const val TAG = "BeepSuppressor" }
}
