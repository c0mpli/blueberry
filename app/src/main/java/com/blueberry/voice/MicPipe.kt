package com.blueberry.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.IOException
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Blueberry owns the microphone and feeds the recogniser through a pipe.
 *
 * This exists to solve three problems at once, which is why it is worth the extra moving parts:
 *
 *  1. **No beep.** The start and end tones are played by the recognition service's own process when
 *     *it* opens the microphone. There is no API to silence them — nothing in `RecognizerIntent`
 *     controls tones, and muting audio streams only hides them while trampling the user's media
 *     volume. Supply the audio yourself and the service never opens a mic, so there is no tone to
 *     play. AOSP confirms the intent: `RecognitionService` skips its microphone-permission
 *     preflight entirely when `EXTRA_AUDIO_SOURCE` is present.
 *  2. **Real amplitude.** `onRmsChanged` reports opaque dB from a process we do not control. Owning
 *     the PCM means the waveform on screen is driven by the actual signal.
 *  3. **Barge-in becomes possible.** Two ordinary apps cannot capture audio concurrently — the
 *     loser silently receives zeros — so a separate VAD `AudioRecord` alongside the recogniser was
 *     never going to work. One capture, teed, is the only shape that can support interruption.
 *
 * API 33+ only. Below that the recogniser opens the mic itself and [BeepSuppressor] is the fallback.
 */
class MicPipe(
    private val sampleRate: Int = SAMPLE_RATE,
) {

    private val pipe = ParcelFileDescriptor.createPipe()

    /** Handed to the recogniser as `EXTRA_AUDIO_SOURCE`. */
    val readSide: ParcelFileDescriptor = pipe[0]

    private val out = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])

    private var record: AudioRecord? = null
    private var pump: Thread? = null

    @Volatile
    private var running = false

    /**
     * Start capturing and pumping. [onLevel] receives a 0..1 amplitude for the indicator.
     *
     * The caller must hold `RECORD_AUDIO`; with a supplied audio source the recogniser no longer
     * checks it, so this class is now the only thing that will fail without it.
     */
    fun start(onLevel: (Float) -> Unit): Boolean {
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, CHANNEL, ENCODING)
        if (minBuffer <= 0) {
            Log.e(TAG, "AudioRecord reports no usable buffer size")
            return false
        }

        val recorder = try {
            // VOICE_RECOGNITION rather than MIC: it applies the platform's recognition-tuned
            // acoustic echo cancellation and noise suppression, which is what the recogniser would
            // have picked itself.
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                CHANNEL,
                ENCODING,
                minBuffer * 2,
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "no microphone permission", e)
            return false
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialise")
            recorder.release()
            return false
        }

        record = recorder
        recorder.startRecording()
        running = true

        pump = Thread({
            val buffer = ByteArray(minBuffer)
            try {
                while (running) {
                    val n = recorder.read(buffer, 0, buffer.size)
                    if (n <= 0) continue
                    // Reading from a live mic is realtime by construction, so there is no risk of
                    // outrunning a network recogniser's rate limit.
                    out.write(buffer, 0, n)
                    out.flush()
                    onLevel(level(buffer, n))
                }
            } catch (e: IOException) {
                // The recogniser closed its end; normal at the end of a turn.
                Log.i(TAG, "pipe closed: ${e.message}")
            }
        }, "blueberry-mic").also { it.start() }

        return true
    }

    /** Closing the write end is how the recogniser is told the utterance is over. */
    fun stop() {
        running = false
        pump?.join(250)
        pump = null
        record?.runCatching {
            stop()
            release()
        }
        record = null
        runCatching { out.close() }
        runCatching { readSide.close() }
    }

    /** RMS of a 16-bit PCM block, mapped onto 0..1 for the indicator. */
    private fun level(buffer: ByteArray, length: Int): Float {
        var sum = 0.0
        var i = 0
        while (i + 1 < length) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val value = sample.toShort().toInt()
            sum += (value * value).toDouble()
            i += 2
        }
        val samples = length / 2
        if (samples == 0) return 0f
        val rms = sqrt(sum / samples)
        if (rms < 1.0) return 0f
        // ~-60dBFS floor up to full scale, which lines up with how speech actually sits.
        val db = 20.0 * log10(rms / Short.MAX_VALUE.toDouble())
        return ((db + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
    }

    companion object {
        private const val TAG = "MicPipe"

        /** What the recogniser expects by default, and what Kokoro-free ASR models want. */
        const val SAMPLE_RATE = 16_000
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
    }
}
