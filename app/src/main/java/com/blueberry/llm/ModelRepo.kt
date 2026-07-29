package com.blueberry.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * One weights file, downloaded once over wifi.
 *
 * The APK ships small and the model arrives on first run, into app-specific external storage so it
 * does not count against the app's internal quota and is removed cleanly on uninstall. Blueberry
 * must stay usable on the seeded resolution cache alone while this is downloading — nothing here is
 * allowed to block the interaction path.
 */
class ModelRepo(private val context: Context) {

    /**
     * Sizes and paths verified against the Hugging Face API on 2026-07-29.
     *
     * The default is deliberately the smallest. FunctionGemma is built to be fine-tuned for tool
     * calling rather than used as a general model, which is exactly the shape of this problem: the
     * router needs one narrow skill done in a few hundred milliseconds, and the turn log is what
     * will eventually fine-tune it. Qwen3 is there for when the explain path matters more than
     * latency.
     *
     * Gemma 4 E2B is deliberately absent: the only official GGUF is 3.35 GB, well past the ~2 GB
     * resident budget an 8 GB phone tolerates before Android starts evicting.
     */
    enum class Preset(
        val label: String,
        val repo: String,
        val file: String,
        val approxBytes: Long,
        val contextTokens: Int,
        val template: com.blueberry.router.Prompt.Template,
    ) {
        /**
         * The default. Q4_0 rather than Q4_K_M on purpose: KleidiAI only ships kernels for Q4_0 and
         * Q8_0, so a K-quant silently falls back to the generic path on exactly the hardware this
         * runs on. Small enough that the weights plus KV cache do not push an 8 GB phone into swap,
         * which is what killed the 1.7B.
         */
        QWEN3_0_6B(
            label = "Qwen3 0.6B (Q4_0)",
            repo = "ggml-org/Qwen3-0.6B-GGUF",
            file = "Qwen3-0.6B-Q4_0.gguf",
            approxBytes = 429_000_000L,
            contextTokens = 2048,
            template = com.blueberry.router.Prompt.Template.CHATML,
        ),
        FUNCTION_GEMMA_270M(
            label = "FunctionGemma 270M (Q4_K_M)",
            repo = "unsloth/functiongemma-270m-it-GGUF",
            file = "functiongemma-270m-it-Q4_K_M.gguf",
            approxBytes = 253_000_000L,
            contextTokens = 2048,
            template = com.blueberry.router.Prompt.Template.GEMMA,
        ),
        QWEN3_1_7B(
            label = "Qwen3 1.7B (Q4_K_M)",
            repo = "ggml-org/Qwen3-1.7B-GGUF",
            file = "Qwen3-1.7B-Q4_K_M.gguf",
            approxBytes = 1_282_000_000L,
            contextTokens = 2048,
            template = com.blueberry.router.Prompt.Template.CHATML,
        );

        val url: String get() = "https://huggingface.co/$repo/resolve/main/$file?download=true"
    }

    sealed interface Status {
        data object Absent : Status
        data class Downloading(val bytes: Long, val total: Long) : Status {
            val fraction: Float get() = if (total > 0) bytes.toFloat() / total else 0f
        }
        data class Ready(val file: File, val preset: Preset) : Status
        data class Failed(val reason: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Absent)
    val status: StateFlow<Status> = _status.asStateFlow()

    private fun modelsDir(): File =
        File(context.getExternalFilesDir(null), "models").apply { mkdirs() }

    fun fileFor(preset: Preset): File = File(modelsDir(), preset.file)

    /** Cheap enough to call at startup. Does not touch the network. */
    fun refresh(preset: Preset): Status {
        val file = fileFor(preset)
        // A partial download left by a killed process must not be mistaken for a usable model —
        // llama.cpp would fail deep inside the loader with a much less obvious message.
        val complete = file.exists() && file.length() >= preset.approxBytes * 9 / 10
        _status.value = if (complete) Status.Ready(file, preset) else Status.Absent
        return _status.value
    }

    suspend fun download(preset: Preset): Status = withContext(Dispatchers.IO) {
        val target = fileFor(preset)
        if (refresh(preset) is Status.Ready) return@withContext _status.value

        val part = File(target.parentFile, target.name + ".part")
        try {
            val connection = (URL(preset.url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 60_000
                // Resume where a previous attempt stopped rather than starting a 253 MB download over.
                if (part.exists()) setRequestProperty("Range", "bytes=${part.length()}-")
            }
            connection.connect()

            val resumed = connection.responseCode == HttpURLConnection.HTTP_PARTIAL
            if (connection.responseCode !in listOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) {
                return@withContext fail("Download failed (HTTP ${connection.responseCode}).", part)
            }

            val already = if (resumed) part.length() else 0L
            val total = already + connection.contentLengthLong.coerceAtLeast(0L)
            var written = already

            connection.inputStream.use { input ->
                java.io.FileOutputStream(part, /* append = */ resumed).use { out ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        written += n
                        _status.value = Status.Downloading(written, total)
                    }
                }
            }

            if (!part.renameTo(target)) return@withContext fail("Could not finalise the download.", part)
            Log.i(TAG, "model ready: ${target.absolutePath} (${target.length()} bytes)")
            _status.value = Status.Ready(target, preset)
        } catch (e: Exception) {
            Log.w(TAG, "model download failed", e)
            // The .part file is kept on purpose so the next attempt resumes.
            _status.value = Status.Failed(e.message ?: "Download failed.")
        }
        _status.value
    }

    private fun fail(reason: String, part: File): Status {
        part.delete()
        _status.value = Status.Failed(reason)
        return _status.value
    }

    fun delete(preset: Preset) {
        fileFor(preset).delete()
        File(modelsDir(), preset.file + ".part").delete()
        _status.value = Status.Absent
    }

    private companion object {
        const val TAG = "ModelRepo"
    }
}
