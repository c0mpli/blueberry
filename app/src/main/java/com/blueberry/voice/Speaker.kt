package com.blueberry.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Speech out. On-device `TextToSpeech`, never a cloud voice.
 *
 * Cloud voices sound better but add 200–400ms before first audio, cost per character, and fail
 * offline — and this app's whole claim is that it works in airplane mode. On-device starts in under
 * 150ms with no key, and given the latency budget that trade is not close.
 *
 * **Streaming is the point.** The model produces tokens over seconds; waiting for the last one
 * before starting to speak wastes the entire window. [speakStreaming] is fed the growing answer and
 * enqueues each sentence the moment it completes, so the first audio lands within a sentence of the
 * first token rather than after the whole answer.
 *
 * The engine is built once at app start and held for the process lifetime — constructing a
 * `TextToSpeech` binds to a remote service and costs several hundred milliseconds, which is far too
 * much to pay per utterance.
 */
class Speaker(private val context: Context) : TtsEngine {

    private var tts: TextToSpeech? = null

    @Volatile
    override var ready: Boolean = false
        private set

    override val name: String get() = "Android TextToSpeech"

    /** How much of the current answer has already been handed to the engine. */
    private var spokenUpTo = 0

    private var utteranceCount = 0

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var focusRequest: AudioFocusRequest? = null

    /** Set by the voice layer so barge-in can stop playback the moment the user speaks. */
    @Volatile
    var onDone: (() -> Unit)? = null

    override fun warmUp() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            Log.i(TAG, "TTS init status=$status ready=$ready engine=${tts?.defaultEngine}")
            if (!ready) {
                Log.w(TAG, "TTS init failed ($status) — is a speech engine installed?")
                return@TextToSpeech
            }
            // en-IN for the same reason the recogniser uses it: effectively all input here is
            // Hindi/English code-mixed, and an en-US voice reads romanised Hindi badly.
            val result = tts?.setLanguage(Locale("en", "IN"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.i(TAG, "en-IN unavailable; falling back to the default locale")
                tts?.setLanguage(Locale.getDefault())
            }
            tts?.setSpeechRate(1.05f)
            tts?.setOnUtteranceProgressListener(progress)
        }
    }

    /**
     * Feed the answer as it grows. Only whole sentences are enqueued — handing the engine a
     * fragment mid-clause makes the prosody lurch, and it cannot un-say it once queued.
     */
    override fun speakStreaming(textSoFar: String) {
        if (!ready) return
        val boundary = SentenceSplitter.lastBoundary(textSoFar, spokenUpTo)
        if (boundary <= spokenUpTo) return

        val chunk = textSoFar.substring(spokenUpTo, boundary).trim()
        spokenUpTo = boundary
        if (chunk.isNotEmpty()) enqueue(chunk)
    }

    /** The answer is complete: speak whatever is left after the last sentence boundary. */
    override fun finish(fullText: String) {
        if (!ready) return
        val tail = fullText.substring(spokenUpTo.coerceAtMost(fullText.length)).trim()
        spokenUpTo = fullText.length
        if (tail.isNotEmpty()) enqueue(tail)
    }

    /** Speak a complete short line — a clarification question, or the morning brief. */
    override fun say(text: String) {
        if (!ready || text.isBlank()) return
        reset()
        enqueue(text.trim())
    }

    /** Barge-in, or dismissal. Stops immediately and drops anything queued. */
    override fun stop() {
        tts?.stop()
        abandonFocus()
        reset()
    }

    fun reset() {
        spokenUpTo = 0
    }

    override fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        abandonFocus()
    }

    private fun enqueue(text: String) {
        Log.i(TAG, "speak: \"${text.take(60)}\"")
        requestFocus()
        val id = "blueberry-${utteranceCount++}"
        // QUEUE_ADD, not QUEUE_FLUSH: successive sentences of one answer must play back to back
        // rather than each cutting off the last.
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id)
    }

    /**
     * Duck other audio rather than stopping it. A three-second answer should not kill the music
     * the user was listening to.
     */
    private fun requestFocus() {
        if (focusRequest != null) return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
        focusRequest = request
        audioManager?.requestAudioFocus(request)
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onError(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            // Only release focus once the queue has drained, or ducking flaps between sentences.
            if (tts?.isSpeaking != true) {
                abandonFocus()
                this@Speaker.onDone?.invoke()
            }
        }
    }


    private companion object {
        const val TAG = "Speaker"

        @Suppress("unused")
        val SUPPORTS_FOCUS_REQUEST = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    }
}
