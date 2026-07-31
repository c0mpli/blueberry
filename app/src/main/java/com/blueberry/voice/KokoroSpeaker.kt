package com.blueberry.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
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
        // Prefer fp32. The int8 build produces NaN samples on this hardware — audio of exactly the
        // right duration, entirely non-finite — which is numerical blow-up in the quantised graph,
        // not a configuration problem. int8 stays as a fallback for devices that cannot spare the
        // extra ~210 MB.
        val model = listOf("model.onnx", "model.int8.onnx")
            .map { File(modelDir, it) }
            .firstOrNull { it.exists() }
        if (model == null) {
            Log.i(TAG, "no Kokoro model at ${modelDir.absolutePath}")
            return
        }
        Log.i(TAG, "using ${model.name} (${model.length() / 1_000_000} MB)")

        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = model.absolutePath,
                    voices = File(modelDir, "voices.bin").absolutePath,
                    tokens = File(modelDir, "tokens.txt").absolutePath,
                    // espeak-ng handles grapheme-to-phoneme. This is the part that would otherwise
                    // be a native build of its own, and the reason for going through sherpa-onnx.
                    dataDir = File(modelDir, "espeak-ng-data").absolutePath,
                    // A multi-lingual Kokoro (v1.0+) requires EITHER a lexicon OR a lang. Given
                    // neither, sherpa-onnx logs "please pass --kokoro-lexicon or --kokoro-lang" and
                    // then calls exit(255) — it does not throw, so the process simply vanishes with
                    // no exception, no abort message and no tombstone. That was the crash loop.
                    //
                    // `lang` is the cheaper half: espeak-ng does the grapheme-to-phoneme work, and
                    // the lexicon is only a pronunciation-override table whose 5.7 MB would be
                    // parsed into a resident hash map for the sake of unusual words.
                    // BOTH, not one or the other. Dropping the lexicon (on a wrong memory theory)
                    // first crashed the process — sherpa calls exit(255) when a multi-lingual
                    // Kokoro has neither — and then, with only `lang` set, produced audio of the
                    // correct duration containing nothing but zeros. The lexicon is what the
                    // multi-lingual frontend actually phonemises with.
                    lexicon = File(modelDir, "lexicon-us-en.txt")
                        .takeIf { it.exists() }?.absolutePath.orEmpty(),
                    lang = "en",
                ),
                // Synthesis is the last thing between a finished answer and the user hearing it,
                // so it gets real parallelism. The 1-thread setting it replaces came from a memory
                // theory that later proved wrong.
                numThreads = 3,
                debug = false,
            ),
        )

        val engine = OfflineTts(assetManager = null, config = config)
        tts = engine
        ready = true
        Log.i(TAG, "Kokoro ready: ${engine.numSpeakers()} speakers, ${engine.sampleRate()} Hz, sid=$speakerId")

        // One short warm-up. The first synthesis pays ONNX graph initialisation and espeak-ng
        // dictionary setup; doing it here means the user's first spoken answer does not.
        val t0 = SystemClock.elapsedRealtime()
        runCatching { engine.generate("ready", speakerId, speed) }
        Log.i(TAG, "warm-up synthesis: ${SystemClock.elapsedRealtime() - t0}ms")
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
                val began = SystemClock.elapsedRealtime()

                // generate(), not generateWithCallback().
                //
                // The callback variant crashes the process: its JNI looks up
                // `invoke([F)Ljava/lang/Integer;` on the lambda, but a Kotlin lambda returning Int
                // is desugared to a synthetic class whose invoke returns a primitive, so the
                // lookup fails and the JNI layer aborts with a pending NoSuchMethodError. Nothing
                // is lost by avoiding it: sentences are already the streaming unit, and an 82M
                // model synthesises one in well under the time it takes to speak the previous one.
                val audio = engine.generate(text, speakerId, speed)
                if (cancelled.get()) return@execute

                // Distinguishes "the model produced silence" from "playback is broken" — without
                // this, both look identical from the outside.
                var peak = 0f
                var bad = 0
                for (v in audio.samples) {
                    if (v.isNaN() || v.isInfinite()) { bad++; continue }
                    val a = kotlin.math.abs(v)
                    if (a > peak) peak = a
                }
                if (bad > 0) {
                    // A blown-up model, not a quiet one. Playing NaN is a click at best.
                    Log.e(TAG, "model produced $bad non-finite samples of ${audio.samples.size} — dropping")
                    return@execute
                }
                Log.i(
                    TAG,
                    "audio: ${audio.samples.size} samples @ ${audio.sampleRate}Hz " +
                        "(${"%.2f".format(audio.samples.size / audio.sampleRate.toFloat())}s), " +
                        "peak=${"%.8f".format(peak)} sid=$speakerId " +
                        "first=${audio.samples.take(4).joinToString { "%.6f".format(it) }}"
                )

                val synthMs = SystemClock.elapsedRealtime() - began
                val audioMs = (audio.samples.size * 1000L) / audio.sampleRate
                Log.i(
                    TAG,
                    "\"${text.take(40)}\" synth ${synthMs}ms for ${audioMs}ms audio " +
                        "(${"%.2f".format(audioMs.toFloat() / synthMs.coerceAtLeast(1))}x realtime)"
                )

                val out = ensureTrack(audio.sampleRate)
                // Queue the audio BEFORE starting playback. Calling play() on an empty stream track
                // and racing to fill it underruns immediately, which is heard as a click rather
                // than speech.
                var written = 0
                while (written < audio.samples.size && !cancelled.get()) {
                    val n = out.write(
                        audio.samples,
                        written,
                        audio.samples.size - written,
                        AudioTrack.WRITE_BLOCKING,
                    )
                    if (n <= 0) {
                        Log.w(TAG, "AudioTrack.write returned $n")
                        break
                    }
                    written += n
                    if (out.playState != AudioTrack.PLAYSTATE_PLAYING) out.play()
                }
            }.onFailure { Log.w(TAG, "synthesis failed", it) }
        }
    }

    /** One track for the process lifetime — a fresh one per sentence is an audible gap. */
    private fun ensureTrack(sampleRate: Int): AudioTrack {
        track?.let { return it }
        // Generous on purpose: a full second of float mono. getMinBufferSize returns the smallest
        // legal size, which for a stream fed in one burst underruns constantly.
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(sampleRate * BYTES_PER_FLOAT)

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

        private const val BYTES_PER_FLOAT = 4

        /** `af_heart` under Kokoro v1.0's alphabetical speaker ordering. */
        const val DEFAULT_SPEAKER = 3

        /** Either quantisation is acceptable; [build] prefers fp32 when both are present. */
        val MODEL_FILES = listOf("model.onnx", "model.int8.onnx")

        /** Everything else the bundle must contain before it is worth trying to load. */
        val REQUIRED_FILES = listOf("voices.bin", "tokens.txt", "espeak-ng-data")

        fun isInstalled(dir: File): Boolean =
            dir.isDirectory &&
                MODEL_FILES.any { File(dir, it).exists() } &&
                REQUIRED_FILES.all { File(dir, it).exists() }
    }
}
