package com.blueberry.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Everything a sherpa-onnx neural voice needs except the model config itself.
 *
 * Kokoro and Supertonic differ only in which files they load; the chunking, queueing, playback and
 * barge-in are identical, and duplicating them once was already once too often.
 *
 * The two properties that make it feel responsive rather than merely good:
 *
 *  * **A single long-lived `AudioTrack`.** Creating one per utterance costs tens of milliseconds
 *    and inserts an audible gap between chunks.
 *  * **Chunks queued on one worker.** Synthesis measured just over realtime, so once audio starts,
 *    the next chunk is ready before the current one finishes — the only wait a user experiences is
 *    the first chunk of an answer.
 *
 * Synthesis is confined to that single worker: the sherpa handle is a native pointer and is not
 * thread-safe.
 */
abstract class SherpaSpeaker(
    private val speed: Float = 1.0f,
) : TtsEngine {

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "blueberry-tts") }

    private var tts: OfflineTts? = null
    private var track: AudioTrack? = null

    /** Set when the user barges in; checked between chunks so playback stops promptly. */
    private val cancelled = AtomicBoolean(false)

    private var spokenUpTo = 0

    @Volatile
    final override var ready: Boolean = false
        private set

    /** Which voice within the model. Mutable so voices can be auditioned without a rebuild. */
    @Volatile
    var speakerId: Int = 0

    /** Supplied by the subclass; null when the model files are not present. */
    protected abstract fun modelConfig(): OfflineTtsModelConfig?

    override fun warmUp() {
        if (tts != null) return
        worker.execute {
            runCatching { build() }.onFailure { Log.e(TAG, "$name failed to load", it) }
        }
    }

    private fun build() {
        val model = modelConfig() ?: run {
            Log.i(TAG, "$name: model files missing")
            return
        }
        val engine = OfflineTts(assetManager = null, config = OfflineTtsConfig(model = model))
        tts = engine
        ready = true
        Log.i(TAG, "$name ready: ${engine.numSpeakers()} speakers, ${engine.sampleRate()} Hz, sid=$speakerId")

        // One short warm-up: the first synthesis pays graph initialisation, and the user should not.
        val t0 = SystemClock.elapsedRealtime()
        runCatching { engine.generate("ready", speakerId, speed) }
        Log.i(TAG, "$name warm-up: ${SystemClock.elapsedRealtime() - t0}ms")
    }

    // ---------------------------------------------------------------------------------------
    // Speaking
    // ---------------------------------------------------------------------------------------

    override fun speakStreaming(textSoFar: String) {
        if (!ready) return
        // The first chunk is cut eagerly — it is the only one the user waits through.
        val boundary =
            if (spokenUpTo == 0) SentenceSplitter.firstBoundary(textSoFar)
            else SentenceSplitter.lastBoundary(textSoFar, spokenUpTo)
        if (boundary <= spokenUpTo) return

        val chunk = SentenceSplitter.speakable(textSoFar.substring(spokenUpTo, boundary))
        spokenUpTo = boundary
        if (chunk.isNotEmpty()) enqueue(chunk)
    }

    override fun finish(fullText: String) {
        if (!ready) return
        val tail = SentenceSplitter.speakable(fullText.substring(spokenUpTo.coerceAtMost(fullText.length)))
        spokenUpTo = fullText.length
        if (tail.isNotEmpty()) enqueue(tail)
    }

    override fun say(text: String) {
        if (!ready || text.isBlank()) return
        spokenUpTo = 0
        SentenceSplitter.speakable(text).takeIf { it.isNotEmpty() }?.let { enqueue(it) }
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

    private fun enqueue(text: String) {
        cancelled.set(false)
        worker.execute {
            val engine = tts ?: return@execute
            runCatching {
                val began = SystemClock.elapsedRealtime()
                val audio = engine.generate(text, speakerId, speed)
                if (cancelled.get()) return@execute

                // NaN never compares greater than anything, so a plain peak scan reports 0 for a
                // blown-up model and silence and numerical failure become indistinguishable.
                var peak = 0f
                var bad = 0
                for (v in audio.samples) {
                    if (v.isNaN() || v.isInfinite()) { bad++; continue }
                    val a = kotlin.math.abs(v)
                    if (a > peak) peak = a
                }
                if (bad > 0) {
                    Log.e(TAG, "$name produced $bad non-finite samples of ${audio.samples.size} — dropping")
                    return@execute
                }

                val synthMs = SystemClock.elapsedRealtime() - began
                val audioMs = (audio.samples.size * 1000L) / audio.sampleRate
                Log.i(
                    TAG,
                    "$name \"${text.take(40)}\" synth ${synthMs}ms for ${audioMs}ms audio " +
                        "(${"%.2f".format(audioMs.toFloat() / synthMs.coerceAtLeast(1))}x realtime, peak ${"%.2f".format(peak)})"
                )

                val out = ensureTrack(audio.sampleRate)
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
            }.onFailure { Log.w(TAG, "$name synthesis failed", it) }
        }
    }

    /** One track for the process lifetime — a fresh one per chunk is an audible gap. */
    private fun ensureTrack(sampleRate: Int): AudioTrack {
        track?.let { return it }
        // A full second of float mono. getMinBufferSize returns the smallest legal size, which for
        // a stream fed in bursts underruns constantly.
        val buffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(sampleRate * BYTES_PER_FLOAT)

        return AudioTrack.Builder()
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
            .setBufferSizeInBytes(buffer)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { track = it }
    }

    protected companion object {
        const val TAG = "SherpaTts"
        const val BYTES_PER_FLOAT = 4
    }
}
