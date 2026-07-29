package com.blueberry.llm

import android.os.SystemClock
import android.util.Log
import com.blueberry.router.ArgType
import com.blueberry.router.Gbnf
import com.blueberry.router.Llm
import com.blueberry.router.LlmOutcome
import com.blueberry.router.Prompt
import com.blueberry.router.RouteContext
import com.blueberry.router.Session
import com.blueberry.router.SlotValue
import com.blueberry.router.ToolCall
import com.blueberry.router.ToolCategory
import com.blueberry.router.ToolSpecs
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.Executors

/**
 * The on-device router model.
 *
 * Two mechanisms carry the whole design, and neither is optional — without both, local inference is
 * slower than a network call and the entire plan inverts:
 *
 *  * **KV cache restore.** The system prompt is the installed-app catalogue plus the tool schemas —
 *    around a thousand tokens of fixed prefix that would otherwise be prefilled on every single
 *    turn, costing seconds. It is prefilled once, saved to disk, and restored per request, so each
 *    turn only prefills the user's twenty-odd tokens.
 *
 *  * **Grammar-constrained decoding.** The tool call is decoded under a GBNF grammar generated from
 *    the tool specs, so it cannot be malformed and it is short.
 *
 * Routing runs in two stages. First a category, decoded under a tiny grammar; then the tool call,
 * with only that category's two or three tools in the grammar. Small models degrade sharply as tool
 * count rises and this recovers most of it.
 *
 * Everything is confined to one worker thread — llama.cpp contexts are not thread-safe and the
 * handles here are raw pointers.
 */
class LocalLlm(
    private val modelFile: File,
    private val stateDir: File,
    private val contextTokens: Int = 2048,
    /** Chat markers differ by model family; the wrong ones survive as literal prompt text. */
    private val template: Prompt.Template = Prompt.Template.CHATML,
    private val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
) : Llm {

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "blueberry-llm").apply { priority = Thread.NORM_PRIORITY + 1 }
    }
    private val dispatcher = worker.asCoroutineDispatcher()

    private var model = 0L
    private var lctx = 0L

    /** The prompt whose KV state is currently resident, and how many tokens it occupies. */
    private var loadedPrefixHash: String? = null
    private var prefixTokens = 0

    @Volatile
    var lastError: String? = null
        private set

    /**
     * Called on the worker thread with the answer so far, every few tokens, for the prose path.
     *
     * Without this the screen shows nothing until the whole answer is decoded, which on a phone is
     * several seconds of apparently-hung UI. The design's rule is that the response starts before
     * the text finishes; this is the hook that makes that possible.
     */
    @Volatile
    var onPartialAnswer: ((String) -> Unit)? = null

    val ready: Boolean get() = model != 0L && lctx != 0L

    // ---------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------

    suspend fun load(): Boolean = withContext(dispatcher) { loadBlocking() }

    private fun loadBlocking(): Boolean {
        if (ready) return true
        if (!LlamaBridge.available) {
            lastError = "The native library is not in this build."
            return false
        }
        if (!modelFile.exists()) {
            lastError = "The model has not been downloaded yet."
            return false
        }

        Prompt.template = template
        LlamaBridge.nativeInit()
        model = LlamaBridge.nativeLoadModel(modelFile.absolutePath)
        if (model == 0L) {
            lastError = "Could not load ${modelFile.name}."
            return false
        }
        lctx = LlamaBridge.nativeNewContext(model, contextTokens, threads)
        if (lctx == 0L) {
            LlamaBridge.nativeFreeModel(model)
            model = 0L
            lastError = "Could not create an inference context."
            return false
        }
        Log.i(TAG, "loaded ${modelFile.name} with $threads threads, ctx=$contextTokens")
        return true
    }

    fun close() {
        worker.execute {
            if (lctx != 0L) LlamaBridge.nativeFreeContext(lctx)
            if (model != 0L) LlamaBridge.nativeFreeModel(model)
            lctx = 0L
            model = 0L
        }
        worker.shutdown()
    }

    // ---------------------------------------------------------------------------------------
    // Routing
    // ---------------------------------------------------------------------------------------

    override suspend fun route(transcript: String, ctx: RouteContext, session: Session): LlmOutcome =
        withContext(dispatcher) {
            if (!loadBlocking()) return@withContext LlmOutcome.Unavailable

            try {
                val turnStart = SystemClock.elapsedRealtime()
                Log.i(TAG, "route start: \"$transcript\"")
                val prefix = Prompt.systemPrefix(ctx)
                ensurePrefix(prefix)
                Log.i(TAG, "prefix ready in ${SystemClock.elapsedRealtime() - turnStart}ms")

                val category = classify(transcript)
                Log.i(TAG, "category=$category for \"$transcript\"")

                val tools = ToolSpecs.inCategory(category)
                if (category == ToolCategory.CHAT || tools.isEmpty()) {
                    val text = explain(transcript)
                    // An empty generation rendered as an empty card, which looks exactly like a
                    // hang. Say something rather than nothing.
                    return@withContext if (text.isBlank()) {
                        LlmOutcome.Error("I didn't get an answer for that one.")
                    } else {
                        LlmOutcome.Speak(text)
                    }
                }

                val json = generate(
                    suffix = Prompt.callSuffix(transcript, tools),
                    grammar = Gbnf.toolCall(tools),
                    maxTokens = MAX_CALL_TOKENS,
                )
                parseCall(json)?.let { LlmOutcome.Call(it) }
                    ?: LlmOutcome.Error("I couldn't work out what to do with that.")
            } catch (e: Exception) {
                Log.w(TAG, "routing failed", e)
                LlmOutcome.Error("Something went wrong on the way to the model.")
            }
        }

    private fun classify(transcript: String): ToolCategory {
        val word = generate(
            suffix = Prompt.categorySuffix(transcript),
            grammar = Gbnf.category(),
            maxTokens = MAX_CATEGORY_TOKENS,
        ).trim().lowercase()
        return ToolCategory.entries.firstOrNull { it.name.lowercase() == word } ?: ToolCategory.CHAT
    }

    private fun explain(transcript: String): String =
        generate(
            suffix = Prompt.explainSuffix(transcript),
            // Unconstrained: prose has no schema to enforce. n_predict is the guard instead.
            grammar = "",
            maxTokens = MAX_PROSE_TOKENS,
            stream = true,
        ).trim()

    // ---------------------------------------------------------------------------------------
    // The KV prefix — the thing that makes this fast enough to exist
    // ---------------------------------------------------------------------------------------

    /**
     * Make sure [prefix] is prefilled and resident. Prefilling ~1K tokens costs seconds on a phone,
     * so it happens once per catalogue and is restored from disk after that.
     */
    private fun ensurePrefix(prefix: String) {
        val hash = fnv1a(prefix)
        if (loadedPrefixHash == hash) return

        val stateFile = File(stateDir.apply { mkdirs() }, "kv-$hash.bin")

        if (stateFile.exists()) {
            Log.i(TAG, "restoring saved KV state (${stateFile.length() / 1024}KB)...")
            val restored = LlamaBridge.nativeLoadState(lctx, stateFile.absolutePath)
            if (restored != null && restored.isNotEmpty()) {
                prefixTokens = restored.size
                loadedPrefixHash = hash
                Log.i(TAG, "prefix restored from disk: $prefixTokens tokens")
                return
            }
            // Stale or written by a different model. Rebuild rather than fail the turn.
            stateFile.delete()
        }

        LlamaBridge.nativeClearKv(lctx)
        val tokens = LlamaBridge.nativeTokenize(model, prefix, /* addSpecial = */ true, /* parseSpecial = */ true)
            ?: error("could not tokenize the system prefix")

        Log.i(TAG, "prefilling ${tokens.size} prefix tokens...")
        val t0 = SystemClock.elapsedRealtime()
        val rc = LlamaBridge.nativeDecode(lctx, tokens)
        check(rc == 0) { "prefill failed ($rc)" }
        val prefillMs = SystemClock.elapsedRealtime() - t0

        prefixTokens = tokens.size
        loadedPrefixHash = hash

        val t1 = SystemClock.elapsedRealtime()
        val bytes = LlamaBridge.nativeSaveState(lctx, stateFile.absolutePath, tokens)
        Log.i(TAG, "prefix: ${tokens.size} tokens, prefill ${prefillMs}ms, " +
            "state ${bytes / 1024}KB written in ${SystemClock.elapsedRealtime() - t1}ms")

        // Anything cached for an older catalogue is now wrong.
        stateDir.listFiles { f -> f.name.startsWith("kv-") && f.name != stateFile.name }
            ?.forEach { it.delete() }
    }

    /**
     * Decode [suffix] on top of the resident prefix and sample until the grammar completes.
     *
     * Rewinds the KV cache to the end of the prefix first, which is what makes the saved state
     * reusable turn after turn instead of once.
     */
    private fun generate(
        suffix: String,
        grammar: String,
        maxTokens: Int,
        stream: Boolean = false,
        budgetMs: Long = DEFAULT_BUDGET_MS,
    ): String {
        val deadline = SystemClock.elapsedRealtime() + budgetMs
        LlamaBridge.nativeTrimTo(lctx, prefixTokens)

        val t0 = SystemClock.elapsedRealtime()
        val tokens = LlamaBridge.nativeTokenize(model, suffix, false, true) ?: return ""
        if (LlamaBridge.nativeDecode(lctx, tokens) != 0) return ""
        val prefillMs = SystemClock.elapsedRealtime() - t0

        val ts = SystemClock.elapsedRealtime()
        val sampler = LlamaBridge.nativeNewSampler(model, grammar, "root", TEMP, TOP_K, TOP_P, SEED)
        Log.i(TAG, "sampler built in ${SystemClock.elapsedRealtime() - ts}ms (grammar ${grammar.length} chars)")
        if (sampler == 0L) {
            Log.e(TAG, "sampler could not be built; refusing to decode unconstrained")
            return ""
        }

        return try {
            val out = StringBuilder()
            var produced = 0
            val decodeStart = SystemClock.elapsedRealtime()
            for (i in 0 until maxTokens) {
                if (SystemClock.elapsedRealtime() > deadline) {
                    Log.w(TAG, "generation hit its ${budgetMs}ms budget after $produced tokens")
                    break
                }
                val tSample = SystemClock.elapsedRealtime()
                val token = LlamaBridge.nativeSample(lctx, sampler)
                if (i < 3) Log.i(TAG, "  sample[$i] ${SystemClock.elapsedRealtime() - tSample}ms -> $token")
                // `repeat` only skipped one iteration here — it did not stop generation, so an
                // end-of-turn token was ignored and decoding ran to the cap every single time.
                if (token < 0 || LlamaBridge.nativeIsEog(model, token)) break
                LlamaBridge.nativeAccept(sampler, token)
                out.append(LlamaBridge.nativeTokenToPiece(model, token))
                if (stream && (i % STREAM_EVERY == 0)) onPartialAnswer?.invoke(out.toString())
                produced++
                val tDec = SystemClock.elapsedRealtime()
                val drc = LlamaBridge.nativeDecode(lctx, intArrayOf(token))
                if (i < 3) Log.i(TAG, "  decode[$i] ${SystemClock.elapsedRealtime() - tDec}ms")
                if (drc != 0) break
            }
            val decodeMs = SystemClock.elapsedRealtime() - decodeStart
            Log.i(TAG, "prefill ${prefillMs}ms (${tokens.size} tok), decode ${decodeMs}ms ($produced tok)")
            if (stream) onPartialAnswer?.invoke(out.toString())
            out.toString()
        } finally {
            LlamaBridge.nativeFreeSampler(sampler)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Parsing
    // ---------------------------------------------------------------------------------------

    /**
     * The grammar guarantees the shape, so this is a formality rather than a defence — but a
     * truncated decode (hitting the token cap mid-object) still produces valid-prefix-invalid-JSON,
     * so it stays wrapped.
     */
    private fun parseCall(text: String): ToolCall? {
        val json = text.substringAfter('{', "").let { if (it.isEmpty()) return null else "{$it" }
        return try {
            val root = JSON.parseToJsonElement(json).jsonObject
            val name = root["tool"]?.jsonPrimitive?.content ?: return null
            val spec = ToolSpecs.byName(name) ?: return null
            val args: JsonObject = root["args"]?.jsonObject ?: JsonObject(emptyMap())

            val bound = LinkedHashMap<String, SlotValue>()
            for (arg in spec.args) {
                val raw = args[arg.name]?.jsonPrimitive?.content
                if (raw == null) {
                    if (arg.required) return null else continue
                }
                bound[arg.name] = when (arg.type) {
                    ArgType.INT -> SlotValue.Number(raw.toIntOrNull() ?: return null, raw)
                    ArgType.STRING -> SlotValue.Text(raw)
                }
            }
            ToolCall(name, bound)
        } catch (e: Exception) {
            Log.w(TAG, "could not parse tool call: $text", e)
            null
        }
    }

    private fun fnv1a(text: String): String {
        var acc = 0xcbf29ce484222325UL
        for (ch in text) {
            acc = acc xor ch.code.toULong()
            acc *= 0x100000001b3UL
        }
        return acc.toString(16).padStart(16, '0')
    }

    private companion object {
        const val TAG = "LocalLlm"
        val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        // Routing is classification, not creative writing. Greedy is both the most accurate and
        // the fastest, and it makes the turn log reproducible.
        const val TEMP = 0.0f
        const val TOP_K = 0
        const val TOP_P = 1.0f
        const val SEED = 0

        // Caps matter: a constrained call is under 40 tokens, and letting n_predict run to 512
        // invites a runaway that burns seconds for nothing.
        const val MAX_CATEGORY_TOKENS = 8
        const val MAX_CALL_TOKENS = 64
        // Two or three spoken sentences. 320 was three times longer than anything that gets read
        // aloud, and every extra token is decode time the user waits through.
        const val MAX_PROSE_TOKENS = 112
        const val STREAM_EVERY = 4

        /**
         * Hard wall-clock ceiling for one generation. The turn is one long blocking native call
         * with no suspension points, so a coroutine timeout around it cannot interrupt anything —
         * the only thing that can stop a runaway decode is the loop checking a clock itself.
         */
        const val DEFAULT_BUDGET_MS = 9_000L
    }
}
