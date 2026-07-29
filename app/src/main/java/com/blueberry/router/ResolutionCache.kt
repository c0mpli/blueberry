package com.blueberry.router

/** A seeded phrase shape and the tool it resolves to. Slot names are the tool's argument names. */
data class SeedRule(val pattern: Pattern, val tool: String) {
    constructor(source: String, tool: String) : this(Pattern.parse(source), tool)
}

/** What the cache found, and how. */
data class CacheHit(
    val call: ToolCall,
    val source: Source,
    /** Null for learned entries, which are looked up by string rather than matched. */
    val match: Match? = null,
) {
    enum class Source { SEED, LEARNED }
}

/**
 * Not a fallback path — a cache, and the single biggest latency mechanism in the app.
 *
 * Two layers. Seeded slot patterns cover the phrasings that dominate daily use, so day one is a
 * warm cache rather than a week of misses. Learned entries are written on confirm, so it gets
 * better at your phrasing without any training.
 *
 * A lookup is cheap enough to run on every partial transcript update, several times a second,
 * which is the whole reason [PreRouter] can fire before endpointing.
 */
class ResolutionCache(
    private val seeds: List<SeedRule> = SEEDS,
    private val learned: MutableMap<String, LearnedEntry> = LinkedHashMap(),
    private val maxLearned: Int = 500,
) {

    /** Learned entries store what was *said*, and re-resolve against the live catalogue. */
    data class LearnedEntry(
        val tool: String,
        val args: Map<String, LearnedArg>,
        val catalogueHash: String,
    )

    data class LearnedArg(val type: SlotType, val spoken: String)

    val seedCount: Int get() = seeds.size
    val learnedCount: Int get() = learned.size

    /**
     * Resolve [u] with no inference at all. Returns null on a miss, which is the model's cue.
     */
    fun lookup(u: Utterance, ctx: RouteContext): CacheHit? {
        if (u.isEmpty()) return null

        learned[u.normalized]?.let { entry ->
            rehydrate(entry, ctx)?.let { return CacheHit(it, CacheHit.Source.LEARNED) }
            // Re-resolution failed — the app it pointed at is probably gone. Drop it rather than
            // serve a cached action for something uninstalled.
            learned.remove(u.normalized)
        }

        for (seed in seeds) {
            val match = seed.pattern.match(u, ctx.matchContext) ?: continue
            // "open <spotify>" that resolves to nothing is still a hit — the user clearly asked to
            // open something and deserves "no app called spotify" rather than a model round trip.
            // A bare "<app>" that resolves to nothing is not a hit at all; it is just a sentence.
            if (!seed.pattern.hasLiteralAnchor && !match.resolvedEverySlot) continue
            return CacheHit(ToolCall(seed.tool, match.bindings), CacheHit.Source.SEED, match)
        }
        return null
    }

    /**
     * True when [u] is a viable *prefix* of some seeded pattern — the user is probably mid-sentence.
     * Distinguishing this from an outright miss is what lets the partial gate keep waiting instead
     * of handing a half-sentence to the model.
     */
    fun couldStillMatch(u: Utterance, ctx: RouteContext): Boolean {
        if (u.isEmpty()) return true
        // Anchorless patterns are viable prefixes of literally everything, so they are excluded —
        // see Pattern.hasLiteralAnchor. Saying just an app name still resolves on the final
        // transcript; it simply does not get to hold the partial gate open.
        return seeds.any { it.pattern.hasLiteralAnchor && it.pattern.couldMatch(u, ctx.matchContext) }
    }

    /** Write through on confirm. Corrections replace rather than accumulate. */
    fun remember(u: Utterance, call: ToolCall, catalogueHash: String) {
        if (u.isEmpty()) return
        val args = call.args.mapValues { (_, v) -> LearnedArg(typeOf(v), v.spoken) }
        if (learned.size >= maxLearned && u.normalized !in learned) {
            learned.remove(learned.keys.first())
        }
        learned[u.normalized] = LearnedEntry(call.tool, args, catalogueHash)
    }

    fun forget(u: Utterance) {
        learned.remove(u.normalized)
    }

    /** Package install or removal changes what a cached `open_app` means. Drop anything stale. */
    fun invalidate(catalogueHash: String) {
        learned.entries.removeAll { it.value.catalogueHash != catalogueHash }
    }

    fun snapshot(): Map<String, LearnedEntry> = LinkedHashMap(learned)

    fun restore(entries: Map<String, LearnedEntry>) {
        learned.clear()
        learned.putAll(entries)
    }

    private fun rehydrate(entry: LearnedEntry, ctx: RouteContext): ToolCall? {
        val args = LinkedHashMap<String, SlotValue>(entry.args.size)
        for ((name, arg) in entry.args) {
            val value: SlotValue = when (arg.type) {
                SlotType.REST, SlotType.WORD -> SlotValue.Text(arg.spoken)
                SlotType.NUMBER -> arg.spoken.toIntOrNull()?.let { SlotValue.Number(it, arg.spoken) } ?: return null
                SlotType.APP -> {
                    val hits = ctx.catalogue.resolve(arg.spoken)
                    when {
                        hits.size == 1 -> SlotValue.App(hits.first(), arg.spoken)
                        // An ambiguous or missing app means the catalogue moved under us. Re-route.
                        else -> return null
                    }
                }
                SlotType.CONTACT -> {
                    val hits = ctx.contacts.resolve(arg.spoken)
                    if (hits.size == 1) SlotValue.Contact(hits.first(), arg.spoken) else return null
                }
            }
            args[name] = value
        }
        return ToolCall(entry.tool, args)
    }

    private fun typeOf(v: SlotValue): SlotType = when (v) {
        is SlotValue.App, is SlotValue.AmbiguousApp, is SlotValue.UnknownApp -> SlotType.APP
        is SlotValue.Contact, is SlotValue.AmbiguousContact, is SlotValue.UnknownContact -> SlotType.CONTACT
        is SlotValue.Number -> SlotType.NUMBER
        is SlotValue.Text -> SlotType.REST
    }

    companion object {
        /**
         * The shipped seeds. A few dozen entries covering the phrasings that dominate daily use,
         * as patterns with slots rather than literal strings.
         *
         * Order matters: the first match wins, so more specific patterns come first. "note down
         * <text>" must be tried before anything that could swallow the same words.
         */
        val SEEDS: List<SeedRule> = listOf(
            SeedRule("(note down|note|write down|jot down|make a note|remember) <text>", Tools.SAVE_NOTE),
            SeedRule("(note to self|memo) <text>", Tools.SAVE_NOTE),

            SeedRule("(open|launch|start|go to|switch to) <app>", Tools.OPEN_APP),
            SeedRule("<app>", Tools.OPEN_APP),

            SeedRule("(play|put on) <query>", Tools.PLAY_MEDIA),

            SeedRule("(navigate to|directions to|take me to|drive to) <place>", Tools.NAVIGATE),

            SeedRule("(search for|google|look up|search) <query>", Tools.SEARCH_WEB),
        )
    }
}
