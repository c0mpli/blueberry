package com.blueberry.router

/**
 * A seeded phrase pattern with typed slots.
 *
 * Patterns are written in a deliberately small DSL:
 *
 * ```
 * "open <app>"
 * "(note down|note|write down|jot down|make a note) <text>"
 * "(call|phone|ring|dial) <contact>"
 * ```
 *
 * `(a|b|c)` is a set of interchangeable word sequences; `<name>` is a slot whose type is inferred
 * from its name, or stated explicitly as `<name:type>`. That is the entire grammar. It is not a
 * regex engine and should not become one — a pattern that needs more expressiveness than this is a
 * pattern that should be going to the model instead.
 */
class Pattern private constructor(
    val source: String,
    val elements: List<PatternElement>,
) {

    /** Slot names in the order they appear. */
    val slots: List<String> = elements.filterIsInstance<PatternElement.Slot>().map { it.name }

    /**
     * True when the pattern contains at least one literal word.
     *
     * An anchorless pattern — `"<app>"`, so that saying just "spotify" opens it — matches any
     * utterance at all, since an APP slot in final position falls back to [SlotValue.UnknownApp].
     * That is fine as a *resolution*, but disastrous as a *prediction*: without this flag the cache
     * would claim every utterance and the model would never see one. Callers use it to require
     * that anchorless patterns actually resolve before they count.
     */
    val hasLiteralAnchor: Boolean = elements.any { it is PatternElement.Words }

    /**
     * Match [u] against this pattern. Returns null when the utterance cannot be this pattern at
     * all — including when it is merely a *prefix* of it, which matters: partial transcripts are
     * matched with [matchPrefix].
     */
    fun match(u: Utterance, ctx: MatchContext): Match? = walk(u, ctx, 0, 0, LinkedHashMap(), false)

    /**
     * True when [u] could still grow into this pattern. Used to tell "the user is mid-sentence"
     * apart from "this is not the command they are saying", so the partial gate knows whether to
     * keep waiting.
     */
    fun couldMatch(u: Utterance, ctx: MatchContext): Boolean =
        walk(u, ctx, 0, 0, LinkedHashMap(), true) != null

    private fun walk(
        u: Utterance,
        ctx: MatchContext,
        ei: Int,
        ti: Int,
        bound: LinkedHashMap<String, SlotValue>,
        prefixMode: Boolean,
    ): Match? {
        if (ei == elements.size) {
            // Every element consumed. A full match requires every token consumed too, otherwise
            // "open spotify now please" would silently match "open <app>".
            return if (ti == u.size) Match(this, LinkedHashMap(bound)) else null
        }
        // In prefix mode we have run out of transcript but not out of pattern: still viable.
        if (prefixMode && ti == u.size) return Match(this, LinkedHashMap(bound))

        return when (val el = elements[ei]) {
            is PatternElement.Words -> {
                for (alt in el.alternatives) {
                    var k = 0
                    var ok = true
                    while (k < alt.size) {
                        val t = ti + k
                        if (t >= u.size) {
                            // Ran out mid-literal. Only viable if we are matching a prefix.
                            ok = prefixMode
                            break
                        }
                        val token = u.tokens[t].text
                        if (token != alt[k]) {
                            // A partial transcript can end mid-word — recognisers emit "op" on the
                            // way to "open". The final token being a prefix of what we expect means
                            // the user is still saying it, which is viable rather than a miss.
                            if (prefixMode && t == u.size - 1 && alt[k].startsWith(token)) {
                                return Match(this, LinkedHashMap(bound))
                            }
                            ok = false
                            break
                        }
                        k++
                    }
                    if (ok) {
                        if (ti + alt.size > u.size) return Match(this, LinkedHashMap(bound))
                        walk(u, ctx, ei + 1, ti + alt.size, bound, prefixMode)?.let { return it }
                    }
                }
                null
            }

            is PatternElement.Slot -> matchSlot(u, ctx, ei, ti, bound, prefixMode, el)
        }
    }

    private fun matchSlot(
        u: Utterance,
        ctx: MatchContext,
        ei: Int,
        ti: Int,
        bound: LinkedHashMap<String, SlotValue>,
        prefixMode: Boolean,
        slot: PatternElement.Slot,
    ): Match? {
        if (ti >= u.size) return if (prefixMode) Match(this, LinkedHashMap(bound)) else null
        val isLast = ei == elements.size - 1

        fun bind(value: SlotValue, consumed: Int): Match? {
            val prev = bound.put(slot.name, value)
            val result = walk(u, ctx, ei + 1, ti + consumed, bound, prefixMode)
            if (prev == null) bound.remove(slot.name) else bound[slot.name] = prev
            return result
        }

        return when (slot.type) {
            // Free text always runs to the end of the utterance. Anything after it in the pattern
            // would be unreachable, which the parser rejects.
            SlotType.REST -> bind(SlotValue.Text(u.rawSlice(ti, u.size)), u.size - ti)

            SlotType.WORD -> bind(SlotValue.Text(u.rawSlice(ti, ti + 1)), 1)

            SlotType.NUMBER -> {
                val t = u.tokens[ti].text
                val n = t.toIntOrNull()
                if (n != null) bind(SlotValue.Number(n, t), 1) else null
            }

            SlotType.APP -> {
                // Longest span first: "open google maps" must not settle for "Google".
                val maxSpan = minOf(MAX_APP_TOKENS, u.size - ti)
                for (span in maxSpan downTo 1) {
                    val spoken = u.normalizedSlice(ti, ti + span)
                    val hits = ctx.catalogue.resolve(spoken)
                    if (hits.isEmpty()) continue
                    val value = if (hits.size == 1) {
                        SlotValue.App(hits.first(), spoken)
                    } else {
                        SlotValue.AmbiguousApp(hits, spoken)
                    }
                    bind(value, span)?.let { return it }
                }
                // Nothing in the catalogue matched. Bind the raw text anyway — but only if it is
                // short enough to plausibly *be* an app name, so "open telegram" gets a clean "no
                // app called telegram" while "open spotify and play sapphire" falls through to the
                // model instead of being mangled into a nonsense package lookup.
                val remaining = u.size - ti
                if (isLast && remaining <= MAX_UNKNOWN_APP_TOKENS) {
                    bind(SlotValue.UnknownApp(u.normalizedSlice(ti, u.size)), remaining)
                } else {
                    null
                }
            }

            SlotType.CONTACT -> {
                val maxSpan = minOf(MAX_CONTACT_TOKENS, u.size - ti)
                for (span in maxSpan downTo 1) {
                    val spoken = u.normalizedSlice(ti, ti + span)
                    val hits = ctx.contacts.resolve(spoken)
                    if (hits.isEmpty()) continue
                    val value = if (hits.size == 1) {
                        SlotValue.Contact(hits.first(), spoken)
                    } else {
                        SlotValue.AmbiguousContact(hits, spoken)
                    }
                    bind(value, span)?.let { return it }
                }
                val remaining = u.size - ti
                if (isLast && remaining <= MAX_UNKNOWN_CONTACT_TOKENS) {
                    bind(SlotValue.UnknownContact(u.normalizedSlice(ti, u.size)), remaining)
                } else {
                    null
                }
            }
        }
    }

    override fun toString(): String = "Pattern($source)"

    companion object {
        private const val MAX_APP_TOKENS = 5
        private const val MAX_CONTACT_TOKENS = 3

        /**
         * How many tokens an *unresolved* app or contact slot may swallow. Deliberately tighter
         * than the resolved limits: a real label can be five words, but an unrecognised span that
         * long is a sentence, and a sentence belongs to the model rather than to a failed lookup.
         */
        private const val MAX_UNKNOWN_APP_TOKENS = 3
        private const val MAX_UNKNOWN_CONTACT_TOKENS = 2

        fun parse(source: String): Pattern {
            val elements = ArrayList<PatternElement>()
            val words = ArrayList<String>()

            fun flushWords() {
                if (words.isNotEmpty()) {
                    elements.add(PatternElement.Words(listOf(words.toList())))
                    words.clear()
                }
            }

            var i = 0
            while (i < source.length) {
                when (val c = source[i]) {
                    '(' -> {
                        flushWords()
                        val close = source.indexOf(')', i)
                        require(close > 0) { "unclosed '(' in pattern: $source" }
                        val alts = source.substring(i + 1, close)
                            .split('|')
                            .map { alt -> alt.trim().split(' ').filter { it.isNotBlank() }.map { it.lowercase() } }
                            .filter { it.isNotEmpty() }
                        require(alts.isNotEmpty()) { "empty alternation in pattern: $source" }
                        elements.add(PatternElement.Words(alts))
                        i = close + 1
                    }

                    '<' -> {
                        flushWords()
                        val close = source.indexOf('>', i)
                        require(close > 0) { "unclosed '<' in pattern: $source" }
                        val body = source.substring(i + 1, close).trim()
                        val name = body.substringBefore(':').trim()
                        val explicit = if (':' in body) body.substringAfter(':').trim() else null
                        require(name.isNotEmpty()) { "unnamed slot in pattern: $source" }
                        elements.add(PatternElement.Slot(name, slotTypeFor(name, explicit, source)))
                        i = close + 1
                    }

                    ' ' -> i++

                    else -> {
                        val end = source.indexOfFirst(i) { it == ' ' || it == '(' || it == '<' }
                        words.add(source.substring(i, end).lowercase())
                        i = end
                    }
                }
            }
            flushWords()
            require(elements.isNotEmpty()) { "empty pattern" }

            // A REST slot swallows the remainder, so anything after it can never match.
            val restAt = elements.indexOfFirst { it is PatternElement.Slot && it.type == SlotType.REST }
            require(restAt < 0 || restAt == elements.size - 1) {
                "a <text> slot must be last — nothing after it is reachable: $source"
            }
            return Pattern(source, elements)
        }

        private fun slotTypeFor(name: String, explicit: String?, source: String): SlotType {
            if (explicit != null) {
                return SlotType.entries.firstOrNull { it.name.equals(explicit, ignoreCase = true) }
                    ?: throw IllegalArgumentException("unknown slot type '$explicit' in $source")
            }
            return when (name.lowercase()) {
                "app" -> SlotType.APP
                "contact", "person", "who" -> SlotType.CONTACT
                "amount", "count" -> SlotType.NUMBER
                "text", "note", "query", "place", "message", "rest" -> SlotType.REST
                else -> SlotType.REST
            }
        }

        private inline fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
            var j = from
            while (j < length && !predicate(this[j])) j++
            return j
        }
    }
}

sealed interface PatternElement {
    /** One of several interchangeable literal word sequences. */
    data class Words(val alternatives: List<List<String>>) : PatternElement

    data class Slot(val name: String, val type: SlotType) : PatternElement
}

enum class SlotType {
    /** Everything to the end of the utterance, raw. */
    REST,

    /** Exactly one token. */
    WORD,

    /** One numeric token. */
    NUMBER,

    /** One to five tokens resolved against the installed-app catalogue. */
    APP,

    /** One to three tokens resolved against contacts. */
    CONTACT,
}

/** A bound slot. Carries what was said as well as what it resolved to. */
sealed interface SlotValue {
    val spoken: String

    data class Text(override val spoken: String) : SlotValue
    data class Number(val value: Int, override val spoken: String) : SlotValue

    data class App(val entry: AppEntry, override val spoken: String) : SlotValue
    data class AmbiguousApp(val candidates: List<AppEntry>, override val spoken: String) : SlotValue
    data class UnknownApp(override val spoken: String) : SlotValue

    data class Contact(val ref: ContactRef, override val spoken: String) : SlotValue
    data class AmbiguousContact(val candidates: List<ContactRef>, override val spoken: String) : SlotValue
    data class UnknownContact(override val spoken: String) : SlotValue
}

data class Match(val pattern: Pattern, val bindings: Map<String, SlotValue>) {
    operator fun get(slot: String): SlotValue? = bindings[slot]

    /** True when every bound slot resolved to exactly one thing. */
    val isUnambiguous: Boolean
        get() = bindings.values.none {
            it is SlotValue.AmbiguousApp || it is SlotValue.AmbiguousContact ||
                it is SlotValue.UnknownApp || it is SlotValue.UnknownContact
        }

    /** True when nothing was left dangling — ambiguity is allowed here, absence is not. */
    val resolvedEverySlot: Boolean
        get() = bindings.values.none { it is SlotValue.UnknownApp || it is SlotValue.UnknownContact }

    /** Slots whose value is still growing as the user speaks. */
    val hasFreeText: Boolean
        get() = pattern.elements.any { it is PatternElement.Slot && it.type == SlotType.REST }
}

data class ContactRef(val name: String, val number: String? = null, val lookupKey: String? = null)

/** Everything a pattern needs to resolve its slots. Supplied by the Android layer, faked in tests. */
class MatchContext(
    val catalogue: Catalogue,
    val contacts: ContactResolver = ContactResolver.NONE,
) {
    companion object {
        val EMPTY = MatchContext(Catalogue.EMPTY)
    }
}

fun interface ContactResolver {
    fun resolve(spoken: String): List<ContactRef>

    companion object {
        val NONE = ContactResolver { emptyList() }
    }
}
