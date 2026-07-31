package com.blueberry.voice

import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import java.io.File

/**
 * Supertonic 3. Four ONNX graphs rather than one, at 145 MB int8 against Kokoro's 325 MB fp32.
 *
 * Worth having for two reasons that have nothing to do with which sounds nicer in English, where
 * Kokoro wins on listener evidence and Supertone's own engineer has said naturalness was not the
 * objective — *"our model cannot yet provide human-like reactions or controllable expressions. So
 * far, we have focused on computational efficiency."*
 *
 *  * **Speed.** Efficiency is the thing it was built for, and synthesis is currently the tail of
 *    every spoken turn.
 *  * **Code-mixing.** It is reported to detect language mid-sentence and blend accordingly, which
 *    is precisely the Hindi/English case Kokoro has no mechanism for.
 */
class SupertonicSpeaker(
    private val modelDir: File,
    speakerId: Int = 0,
    speed: Float = 1.0f,
) : SherpaSpeaker(speed) {

    init {
        this.speakerId = speakerId
    }

    override val name: String get() = "Supertonic-3"

    override fun modelConfig(): OfflineTtsModelConfig? {
        if (!isInstalled(modelDir)) return null
        fun path(n: String) = File(modelDir, n).absolutePath

        return OfflineTtsModelConfig(
            supertonic = OfflineTtsSupertonicModelConfig(
                durationPredictor = path("duration_predictor.int8.onnx"),
                textEncoder = path("text_encoder.int8.onnx"),
                vectorEstimator = path("vector_estimator.int8.onnx"),
                vocoder = path("vocoder.int8.onnx"),
                ttsJson = path("tts.json"),
                unicodeIndexer = path("unicode_indexer.bin"),
                voiceStyle = path("voice.bin"),
            ),
            numThreads = 3,
            debug = false,
        )
    }

    companion object {
        private val REQUIRED_FILES = listOf(
            "duration_predictor.int8.onnx",
            "text_encoder.int8.onnx",
            "vector_estimator.int8.onnx",
            "vocoder.int8.onnx",
            "tts.json",
            "unicode_indexer.bin",
            "voice.bin",
        )

        fun isInstalled(dir: File): Boolean =
            dir.isDirectory && REQUIRED_FILES.all { File(dir, it).exists() }
    }
}
