package com.blueberry.router

/**
 * The partial gate: the single largest latency win in the app.
 *
 * The platform waits about a second of silence to decide you have finished speaking. For a command
 * it already understands, that wait is pure waste. So the resolution cache runs against the
 * *partial* transcript on every update, and the instant a partial resolves confidently the turn
 * executes — endpointing never happens at all.
 *
 * The whole difficulty is deciding *when* a partial is confident enough, because firing early on a
 * half-heard sentence is worse than waiting. Three guards:
 *
 *  1. **Free text never fires early.** "note down …" is still growing by definition; there is no
 *     such thing as a confident partial for it. It waits for stability or for the final transcript.
 *  2. **An app name that another app extends never fires early.** "open google" is an exact match
 *     for Google, but Google Maps starts with the same word, so the user may not be done. Fires on
 *     stability instead.
 *  3. **Ambiguity never fires.** Two matching contacts, two matching apps — that is a
 *     clarification, and clarifications are decided on the complete utterance.
 *
 * Everything that passes all three fires immediately, which is the "open spotify" case and most of
 * daily use.
 */
class PreRouter(
    private val cache: ResolutionCache,
    /**
     * How long a partial must stop changing before a merely-plausible match is fired. Chosen to sit
     * under local VAD endpointing (~250–300ms) so this path still beats waiting for silence, and
     * well under platform endpointing (~1s), which it exists to avoid.
     */
    private val stabilityMs: Long = 300L,
) {

    /**
     * @param transcript the latest partial from the recogniser
     * @param unchangedForMs how long [transcript] has been the same text. The voice layer tracks
     *   this; the router does not read a clock.
     */
    fun onPartial(transcript: String, ctx: RouteContext, unchangedForMs: Long): PartialDecision {
        val u = Utterance.of(transcript)
        if (u.isEmpty()) return PartialDecision.Wait

        val hit = cache.lookup(u, ctx)
        if (hit == null) {
            // Nothing matched. Is the user mid-sentence, or is this genuinely a model turn?
            return if (cache.couldStillMatch(u, ctx)) PartialDecision.Wait else PartialDecision.Miss
        }

        val match = hit.match
            // A learned entry is an exact string the user has confirmed before. Nothing to second
            // guess — fire.
            ?: return PartialDecision.Fire(hit, PartialDecision.Reason.LEARNED)

        if (!match.isUnambiguous) return PartialDecision.Wait
        if (match.hasFreeText) return stableOr(hit, unchangedForMs)
        if (!allSlotsSettled(match, ctx)) return stableOr(hit, unchangedForMs)

        return PartialDecision.Fire(hit, PartialDecision.Reason.EXACT)
    }

    /** The final transcript. No gating — whatever the cache says, goes. */
    fun onFinal(transcript: String, ctx: RouteContext): CacheHit? =
        cache.lookup(Utterance.of(transcript), ctx)

    private fun stableOr(hit: CacheHit, unchangedForMs: Long): PartialDecision =
        if (unchangedForMs >= stabilityMs) {
            PartialDecision.Fire(hit, PartialDecision.Reason.STABLE)
        } else {
            PartialDecision.Wait
        }

    /** False when some longer app label starts with what was said — the user may still be talking. */
    private fun allSlotsSettled(match: Match, ctx: RouteContext): Boolean =
        match.bindings.values.none { value ->
            value is SlotValue.App && ctx.catalogue.hasLongerLabelStartingWith(value.spoken)
        }
}

sealed interface PartialDecision {
    /** Execute now, without waiting for endpointing. */
    data class Fire(val hit: CacheHit, val reason: Reason) : PartialDecision

    /** Could still become a command. Keep listening, do not hand it to the model yet. */
    data object Wait : PartialDecision

    /**
     * No seeded pattern can match this. The model owns the turn — and since a discarded local
     * decode costs a few hundred milliseconds of CPU and nothing else, this is the cue to start
     * speculative routing rather than to wait for silence.
     */
    data object Miss : PartialDecision

    enum class Reason {
        /** Every slot resolved to exactly one thing and nothing could extend it. */
        EXACT,

        /** Plausible, and the transcript stopped changing. */
        STABLE,

        /** An exact phrase the user has confirmed before. */
        LEARNED,
    }
}
