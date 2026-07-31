package com.blueberry.voice

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File

/**
 * Kokoro-82M. The best-rated open on-device voice for English naturalness by some distance —
 * roughly three to one among people who have compared it with the alternatives.
 *
 * Its weakness is the other half of this app's requirement: outside English it degrades, and it has
 * no mechanism for detecting a language mid-sentence, which Hindi/English code-mixing needs.
 */
class KokoroSpeaker(
    private val modelDir: File,
    speakerId: Int = DEFAULT_SPEAKER,
    speed: Float = 1.0f,
) : SherpaSpeaker(speed) {

    init {
        this.speakerId = speakerId
    }

    override val name: String get() = "Kokoro-82M"

    override fun modelConfig(): OfflineTtsModelConfig? {
        // fp32 preferred: the int8 build emits NaN on this hardware — the right sample count,
        // entirely non-finite — which is numerical blow-up in the quantised graph, not config.
        val model = MODEL_FILES.map { File(modelDir, it) }.firstOrNull { it.exists() } ?: return null
        Log.i(TAG, "Kokoro using ${model.name} (${model.length() / 1_000_000} MB)")

        return OfflineTtsModelConfig(
            kokoro = OfflineTtsKokoroModelConfig(
                model = model.absolutePath,
                voices = File(modelDir, "voices.bin").absolutePath,
                tokens = File(modelDir, "tokens.txt").absolutePath,
                // espeak-ng does grapheme-to-phoneme; this is the work sherpa-onnx saves us.
                dataDir = File(modelDir, "espeak-ng-data").absolutePath,
                // A multi-lingual Kokoro needs a lexicon OR a lang. With neither, sherpa calls
                // exit(255) — no exception, no tombstone, the process simply vanishes. With only
                // lang, it emits audio of the right duration containing nothing but zeros.
                lexicon = File(modelDir, "lexicon-us-en.txt").takeIf { it.exists() }?.absolutePath.orEmpty(),
                lang = "en",
            ),
            numThreads = 3,
            debug = false,
        )
    }

    companion object {
        /** Either quantisation loads; fp32 is preferred when both are present. */
        val MODEL_FILES = listOf("model.onnx", "model.int8.onnx")

        private val REQUIRED_FILES = listOf("voices.bin", "tokens.txt", "espeak-ng-data")

        /**
         * `af_bella` under Kokoro v1.0's alphabetical speaker ordering.
         *
         * Chosen by listening, which is the only way it can be chosen. It replaced `af_heart`,
         * which came from a grades table that turned out to be an unattributed SEO page and which
         * sounded robotic when actually played.
         */
        const val DEFAULT_SPEAKER = 2

        fun isInstalled(dir: File): Boolean =
            dir.isDirectory &&
                MODEL_FILES.any { File(dir, it).exists() } &&
                REQUIRED_FILES.all { File(dir, it).exists() }
    }
}
