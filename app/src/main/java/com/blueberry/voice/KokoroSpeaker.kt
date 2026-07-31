package com.blueberry.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kokoro-82M through sherpa-onnx: a neural voice that runs entirely on the phone.
 *
 * The built-in Android engine tops out at Google's concatenative voices — every offline voice on a
 * Galaxy S23 reports `quality=400` — and no amount of rate or pitch tuning makes those sound like a
 * person. This is the alternative that does, at 113 MB of int8 weights.
 *
 * Two things make it feel fast rather than merely good:
 *
 *  * **`generateWithCallback` streams audio out during synthesis**, so playback starts on the first
 *    chunk instead of after the last one. Combined with the model's sentence-by-sentence output,
 *    audio begins a fraction of a second after the first token rather than after the full answer.
 *  * **A single `AudioTrack` in stream mode**, kept alive across sentences. Creating one per
 *    utterance costs tens of milliseconds and inserts an audible gap between sentences.
 *
 * Synthesis is confined to one worker thread: the sherpa-onnx handle is a native pointer and is not
 * thread-safe.
 */
class KokoroSpeaker(
    private val modelDir: File,
    /**
     * Kokoro v1.0 orders speakers alphabetically, so index 3 is `af_heart` — the highest-graded
     * voice in Kokoro's own table. [numSpeakers] is logged at load so this can be checked.
     */
    private val speakerId: Int = DEFAULT_SPEAKER,
    private val speed: Float = 1.0f,
) : TtsEngine {

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "blueberry-tts") }

    private var tts: OfflineTts? = null
    private var track: AudioTrack? = null

    /** Set when the user barges in; the synthesis callback checks it and bails mid-sentence. */
    private val cancelled = AtomicBoolean(false)

    private var spokenUpTo = 0

    @Volatile
    override var ready: Boolean = false
        private set

    override val name: String get() = "Kokoro-82M"

    override fun warmUp() {
        if (tts != null) return
        worker.execute {
            runCatching { build() }
                .onFailure { Log.e(TAG, "Kokoro failed to load", it) }
        }
    }

    private fun build() {
        val model = File(modelDir, "model.int8.onnx")
        if (!model.exists()) {
            Log.i(TAG, "no Kokoro model at ${modelDir.absolutePath}")
            return
        }

        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = model.absolutePath,
                    voices = File(modelDir, "voices.bin").absolutePath,
                    tokens = File(modelDir, "tokens.txt").absolutePath,
                    // espeak-ng handles grapheme-to-phoneme. This is the part that would otherwise
                    // be a native build of its own, and the reason for going through sherpa-onnx.
                    dataDir = File(modelDir, "espeak-ng-data").absolutePath,
                    // No lexicon. espeak-ng already does grapheme-to-phoneme; the lexicon is only
                    // a pronunciation override table, and parsing 5.7 MB of it into a hash map cost
                    // enough resident memory to evict the LLM's mmap'd weights — its prefill went
                    // from 1380ms to 15319ms for the same 47 tokens with Kokoro loaded alongside.
                    // Two neural models on an 8 GB phone is tight; this is the cheapest thing to
                    // give up, because it only affects pronunciation of unusual words.
                    lexicon = "",
                ),
                // One thread: the LLM already takes four, and synthesis is not the bottleneck.
                numThreads = 1,
                debug = false,
            ),
        )

        val engine = OfflineTts(assetManager = null, config = config)
        tts = engine
        ready = true
        Log.i(TAG, "Kokoro ready: ${engine.numSpeakers()} speakers, ${engine.sampleRate()} Hz, sid=$speakerId")
    }

    // ---------------------------------------------------------------------------------------
    // Speaking
    // ---------------------------------------------------------------------------------------

    override fun speakStreaming(textSoFar: String) {
        if (!ready) return
        val boundary = SentenceSplitter.lastBoundary(textSoFar, spokenUpTo)
        if (boundary <= spokenUpTo) return
        val chunk = textSoFar.substring(spokenUpTo, boundary).trim()
        spokenUpTo = boundary
        if (chunk.isNotEmpty()) enqueue(chunk)
    }

    override fun finish(fullText: String) {
        if (!ready) return
        val tail = fullText.substring(spokenUpTo.coerceAtMost(fullText.length)).trim()
        spokenUpTo = fullText.length
        if (tail.isNotEmpty()) enqueue(tail)
    }

    override fun say(text: String) {
        if (!ready || text.isBlank()) return
        spokenUpTo = 0
        enqueue(text.trim())
    }

    override fun stop() {
        cancelled.set(true)
        spokenUpTo = 0
        runCatching {
            track?.pause()
            track?.flush()
        }
    }

    override fun release() {
        stop()
        worker.execute {
            runCatching { tts?.release() }
            runCatching {
                track?.stop()
                track?.release()
            }
            tts = null
            track = null
            ready = false
        }
        worker.shutdown()
    }

    /**
     * Sentences are queued onto the worker rather than synthesised inline, so several completing in
     * quick succession play back to back instead of overlapping.
     */
    private fun enqueue(text: String) {
        cancelled.set(false)
        worker.execute {
            val engine = tts ?: return@execute
            runCatching {
                val out = ensureTrack(engine.sampleRate())
                out.play()
                Log.i(TAG, "synthesising: \"${text.take(60)}\"")
                engine.generateWithCallback(text = text, sid = speakerId, speed = speed) { samples ->
                    if (cancelled.get()) {
                        // Returning 0 tells sherpa-onnx to abandon the rest of this utterance,
                        // which is what makes barge-in feel immediate rather than "after this
                        // sentence finishes".
                        0
                    } else {
                        out.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                        1
                    }
                }
            }.onFailure { Log.w(TAG, "synthesis failed", it) }
        }
    }

    /** One track for the process lifetime — a fresh one per sentence is an audible gap. */
    private fun ensureTrack(sampleRate: Int): AudioTrack {
        track?.let { return it }
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(sampleRate * 2)

        val created = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = created
        return created
    }

    companion object {
        private const val TAG = "Kokoro"

        /** `af_heart` under Kokoro v1.0's alphabetical speaker ordering. */
        const val DEFAULT_SPEAKER = 3

        /** Everything the bundle must contain before it is worth trying to load. */
        val REQUIRED_FILES = listOf("model.int8.onnx", "voices.bin", "tokens.txt", "espeak-ng-data")

        fun isInstalled(dir: File): Boolean =
            dir.isDirectory && REQUIRED_FILES.all { File(dir, it).exists() }
    }
}
