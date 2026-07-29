package com.blueberry.router

/**
 * Transcript in, [RouterResult] out.
 *
 * Three entry points call this — the home screen, the lock screen, and the assist gesture — and it
 * must not know which. That is the one architectural rule, and it is why this whole package has no
 * Android dependency: a thing that cannot import `android.app.Activity` cannot accidentally start
 * caring where it was called from.
 */
class Router(
    val cache: ResolutionCache = ResolutionCache(),
    private val registry: ToolRegistry = ToolRegistry.default(),
    private val llm: Llm = Llm.Unavailable,
    stabilityMs: Long = 300L,
) {

    /** The partial-transcript gate. Held here so callers get one object per session. */
    val preRouter: PreRouter = PreRouter(cache, stabilityMs)

    /** Build the result for a hit the gate already committed to. No further decisions. */
    fun execute(hit: CacheHit, ctx: RouteContext): RouterResult = registry.build(hit.call, ctx)

    /**
     * The full path, from a final transcript. Cache first — a hit costs no inference at all — then
     * the model.
     */
    suspend fun route(
        transcript: String,
        ctx: RouteContext,
        session: Session = Session(),
    ): RouterResult {
        val u = Utterance.of(transcript)
        if (u.isEmpty()) return RouterResult.Failed("I didn't catch that.")

        cache.lookup(u, ctx)?.let { return registry.build(it.call, ctx) }

        return when (val outcome = llm.route(transcript, ctx, session)) {
            is LlmOutcome.Call -> registry.build(outcome.call, ctx)

            is LlmOutcome.Speak -> RouterResult.Answer(outcome.text)

            is LlmOutcome.Ask ->
                // An interface that asks four questions is worse than one that gives up and opens
                // the app.
                if (session.clarificationBudgetSpent()) {
                    RouterResult.Failed("I'm not sure what you meant.")
                } else {
                    RouterResult.Clarify(outcome.question, outcome.options, outcome.pending)
                }

            is LlmOutcome.Error -> RouterResult.Failed(outcome.reason)

            LlmOutcome.Unavailable -> RouterResult.Failed(NO_MODEL)
        }
    }

    /**
     * Write through on confirm, not on route. A cached entry should mean "the user accepted this",
     * which is what makes corrections work: fix what it did and the correction replaces the entry.
     */
    fun confirm(transcript: String, call: ToolCall, catalogueHash: String) {
        cache.remember(Utterance.of(transcript), call, catalogueHash)
    }

    /** The user rejected what it did. Drop the entry so the next attempt re-routes. */
    fun correct(transcript: String) {
        cache.forget(Utterance.of(transcript))
    }

    /** Package install or removal. Anything cached that pointed at a package must not survive. */
    fun onCatalogueChanged(catalogueHash: String) {
        cache.invalidate(catalogueHash)
    }

    companion object {
        const val NO_MODEL =
            "I don't know that one yet — the on-device model hasn't been set up."
    }
}
