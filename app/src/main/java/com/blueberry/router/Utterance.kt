package com.blueberry.router

/**
 * One token of a normalised transcript, with the span it came from in the raw string.
 *
 * Keeping the span is the whole point. Matching happens on normalised text so that "Note down,
 * talk to OISL!" and "note down talk to oisl" hit the same pattern, but a captured `<text>` slot
 * has to give back what the user actually said — punctuation, capitals and all — because that is
 * what gets written to the vault.
 */
data class Token(val text: String, val start: Int, val end: Int)

/**
 * A transcript, normalised once and reused. Normalisation is on the critical path: it runs on
 * every partial result, several times a second, so it stays allocation-light and does no regex.
 */
class Utterance private constructor(
    val raw: String,
    val tokens: List<Token>,
) {
    /** Space-joined normalised form. Used as the cache key. */
    val normalized: String by lazy(LazyThreadSafetyMode.NONE) {
        tokens.joinToString(" ") { it.text }
    }

    val size: Int get() = tokens.size

    fun isEmpty(): Boolean = tokens.isEmpty()

    /** Normalised text of tokens `[from, toExclusive)`. */
    fun normalizedSlice(from: Int, toExclusive: Int): String =
        tokens.subList(from, toExclusive).joinToString(" ") { it.text }

    /** Raw text spanning tokens `[from, toExclusive)`, trimmed. Punctuation preserved. */
    fun rawSlice(from: Int, toExclusive: Int): String {
        if (from >= toExclusive) return ""
        return raw.substring(tokens[from].start, tokens[toExclusive - 1].end).trim()
    }

    override fun toString(): String = "Utterance(${normalized})"

    companion object {
        /**
         * Leading words that carry no meaning for routing. Deliberately short: stripping too
         * eagerly breaks real commands ("ok google" is an app, "note" is a tool word).
         */
        private val LEADING_FILLER = setOf("hey", "ok", "okay", "yo", "um", "uh", "blueberry")

        /** Trailing politeness. Same caution applies. */
        private val TRAILING_FILLER = setOf("please", "thanks", "thankyou")

        fun of(raw: String): Utterance {
            val all = tokenize(raw)
            var lo = 0
            while (lo < all.size && all[lo].text in LEADING_FILLER) lo++
            var hi = all.size
            while (hi > lo && all[hi - 1].text in TRAILING_FILLER) hi--
            // Never strip everything — "please" alone is still an utterance worth failing on.
            val kept = if (lo == 0 && hi == all.size) all else if (lo >= hi) all else all.subList(lo, hi)
            return Utterance(raw, kept)
        }

        private fun tokenize(raw: String): List<Token> {
            val out = ArrayList<Token>(8)
            val sb = StringBuilder(16)
            var start = -1
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                when {
                    c.isLetterOrDigit() -> {
                        if (start < 0) start = i
                        sb.append(c.lowercaseChar())
                    }
                    // Apostrophes join rather than split: "what's" -> "whats", not "what s".
                    c == '\'' || c == '’' -> Unit
                    else -> {
                        if (start >= 0) {
                            out.add(Token(sb.toString(), start, i))
                            sb.setLength(0)
                            start = -1
                        }
                    }
                }
                i++
            }
            if (start >= 0) out.add(Token(sb.toString(), start, raw.length))
            return out
        }
    }
}
