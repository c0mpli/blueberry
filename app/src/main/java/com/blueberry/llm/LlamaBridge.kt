package com.blueberry.llm

/**
 * Raw JNI surface. Handle-based, and none of it is thread-safe — [LocalLlm] confines every call to
 * a single worker thread and nothing else in the app may touch this directly.
 */
object LlamaBridge {

    @Volatile
    var available: Boolean = false
        private set

    init {
        available = try {
            System.loadLibrary("blueberry_llama")
            true
        } catch (e: UnsatisfiedLinkError) {
            // A build without the native library still has to run — the resolution cache covers
            // most of daily use on its own, and the drawer covers the rest.
            false
        }
    }

    external fun nativeInit()
    external fun nativeSystemInfo(): String

    external fun nativeLoadModel(path: String): Long
    external fun nativeFreeModel(model: Long)

    external fun nativeNewContext(model: Long, nCtx: Int, nThreads: Int): Long
    external fun nativeFreeContext(ctx: Long)
    external fun nativeContextSize(ctx: Long): Int

    external fun nativeTokenize(model: Long, text: String, addSpecial: Boolean, parseSpecial: Boolean): IntArray?
    external fun nativeTokenToPiece(model: Long, token: Int): String
    external fun nativeIsEog(model: Long, token: Int): Boolean

    external fun nativeDecode(ctx: Long, tokens: IntArray): Int
    external fun nativeClearKv(ctx: Long)

    external fun nativeNewSampler(
        model: Long,
        grammar: String,
        root: String,
        temp: Float,
        topK: Int,
        topP: Float,
        seed: Int,
    ): Long

    external fun nativeFreeSampler(sampler: Long)
    external fun nativeSample(ctx: Long, sampler: Long): Int
    external fun nativeAccept(sampler: Long, token: Int)
    external fun nativeResetSampler(sampler: Long)

    external fun nativeSaveState(ctx: Long, path: String, tokens: IntArray): Long
    external fun nativeLoadState(ctx: Long, path: String): IntArray?
    external fun nativeTrimTo(ctx: Long, keep: Int)
}
