package com.blueberry.voice

import android.content.Context

/**
 * Release builds get exactly one transcript source: the microphone.
 *
 * The debug variant of this file adds an adb-driven injection path. Splitting it by source set
 * rather than by a `BuildConfig.DEBUG` branch means the injection code is not merely disabled in
 * release — it is not compiled into it at all.
 */
object TranscriptSourceFactory {
    fun create(context: Context): TranscriptSource = PlatformSpeechSource(context)
}
