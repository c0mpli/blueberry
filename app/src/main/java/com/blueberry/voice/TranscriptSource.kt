package com.blueberry.voice

import kotlinx.coroutines.flow.Flow

/**
 * Where transcripts come from.
 *
 * Deliberately an interface with more than one implementation, and not only for testing. Real
 * speech cannot be exercised on an emulator — the guest microphone reads digital silence unless the
 * emulator is launched with `-allow-host-audio`, and even then "host audio" means the Mac's default
 * input device, with no way to feed it a fixture file. So the router path is verified through an
 * injected transcript, and the platform recogniser is verified by hand on a real phone.
 *
 * The same seam is where a bundled ASR (sherpa-onnx, whisper.cpp) would drop in if the platform
 * recogniser's offline story proves insufficient.
 */
interface TranscriptSource {

    val events: Flow<AsrEvent>

    /**
     * Called on tap-*down*, before the user has committed to speaking. Constructing a
     * `SpeechRecognizer` binds to a remote service asynchronously, and doing that work while the
     * finger is still travelling is the cheapest latency win available.
     */
    fun prewarm()

    fun start()

    fun stop()

    fun release()
}

sealed interface AsrEvent {
    /** The recogniser is bound and the mic is live. This is the sub-100ms milestone. */
    data object Listening : AsrEvent

    /** A partial transcript. Fires several times a second; the partial gate runs on every one. */
    data class Partial(val text: String) : AsrEvent

    data class Final(val text: String) : AsrEvent

    /** Input level, for the amplitude-reactive indicator. */
    data class Level(val rms: Float) : AsrEvent

    data class Failed(val reason: String, val code: Int = -1) : AsrEvent

    data object Stopped : AsrEvent
}
