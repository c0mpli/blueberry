package com.blueberry.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow

/**
 * The platform recogniser.
 *
 * One important caveat the design has to live with: `createSpeechRecognizer` resolves to whatever
 * service is in `Settings.Secure.VOICE_RECOGNITION_SERVICE`, which on most phones is Google's and
 * is **network-backed** — the AOSP javadoc says so outright. That does not survive airplane mode.
 *
 * So this prefers `createOnDeviceSpeechRecognizer` (API 31+) whenever the device reports it
 * available, and only falls back to the default service otherwise. On-device availability is an OEM
 * build-config lookup rather than a capability probe, so it genuinely varies by handset — which is
 * why [TranscriptSource] is an interface and a bundled recogniser can replace this wholesale.
 *
 * `SpeechRecognizer` is not thread-safe and its callbacks arrive on the main thread, so every
 * method here must be called from the main thread.
 */
class PlatformSpeechSource(
    private val context: Context,
    private val language: String = "en-IN",
) : TranscriptSource {

    private val _events = MutableSharedFlow<AsrEvent>(replay = 0, extraBufferCapacity = 32)
    override val events: Flow<AsrEvent> = _events

    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    /** True when the recogniser in use runs entirely on the device. */
    var onDevice: Boolean = false
        private set

    override fun prewarm() {
        if (recognizer != null) return
        recognizer = create()?.also { it.setRecognitionListener(listener) }
    }

    override fun start() {
        prewarm()
        val r = recognizer ?: run {
            emit(AsrEvent.Failed("No speech recogniser is available on this device."))
            return
        }
        if (listening) return
        listening = true
        r.startListening(recognizerIntent())
    }

    override fun stop() {
        if (!listening) return
        listening = false
        recognizer?.stopListening()
    }

    override fun release() {
        listening = false
        recognizer?.destroy()
        recognizer = null
    }

    private fun create(): SpeechRecognizer? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            onDevice = true
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            onDevice = false
            Log.i(TAG, "on-device recognition unavailable; falling back to the default service")
            SpeechRecognizer.createSpeechRecognizer(context)
        }
    } catch (e: Exception) {
        Log.e(TAG, "could not create a recogniser", e)
        null
    }

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        // en-IN handles Hindi/English code-mixing far better than en-US, and effectively all input
        // here is code-mixed.
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
        // Partials are the whole latency strategy — without them there is nothing for the partial
        // gate to run against.
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = emit(AsrEvent.Listening)

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = emit(AsrEvent.Level(rmsdB))

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            listening = false
            emit(AsrEvent.Failed(describe(error), error))
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) emit(AsrEvent.Stopped) else emit(AsrEvent.Final(text))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?: return
            if (text.isNotBlank()) emit(AsrEvent.Partial(text))
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun emit(event: AsrEvent) {
        _events.tryEmit(event)
    }

    private fun describe(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> "I didn't catch that."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't hear anything."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Blueberry needs microphone access."
        // These four all mean the same thing in practice: the recogniser in use is the network one
        // and there is no network. Retrying will not help.
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        -> "Speech recognition is offline on this device."

        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Still finishing the last one."
        else -> "Speech recognition failed."
    }

    private companion object {
        const val TAG = "SpeechSource"
    }
}
