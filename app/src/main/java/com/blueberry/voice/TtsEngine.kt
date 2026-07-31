package com.blueberry.voice

/**
 * Speech out, behind an interface because Blueberry ships two of them.
 *
 * [KokoroSpeaker] is a neural model and sounds like a person; it needs a ~130 MB download before it
 * can say anything. [PlatformSpeaker] wraps Android's built-in engine, which is available the
 * instant the app starts and sounds like a satnav. So the platform engine covers first run and any
 * device where the model has not arrived, and Kokoro takes over the moment it is ready.
 *
 * Every implementation is fed the *growing* answer through [speakStreaming]. Waiting for the last
 * token before speaking the first word throws away the whole decode window, which on a phone is
 * seconds.
 */
interface TtsEngine {

    /** Build whatever is expensive to build, off the critical path. */
    fun warmUp()

    /** True once this engine can actually produce audio. */
    val ready: Boolean

    /** Human-readable, for settings and logs. */
    val name: String

    /**
     * Feed the answer as it grows. Implementations speak each sentence as it completes and must
     * ignore text they have already spoken.
     */
    fun speakStreaming(textSoFar: String)

    /** The answer is finished: speak whatever follows the last sentence boundary. */
    fun finish(fullText: String)

    /** Speak a complete short line — a clarification, or the morning brief. */
    fun say(text: String)

    /** Barge-in. Stop immediately and drop anything queued. */
    fun stop()

    fun release()
}

/**
 * Where to cut a growing string into speakable sentences.
 *
 * Shared, because getting it wrong is the same bug in both engines: hand a synthesiser a fragment
 * and the prosody lurches, and neither engine can un-say something once it is queued.
 */
object SentenceSplitter {

    /**
     * Index just past the last speakable boundary in `text[from..]`, or [from] when there is none.
     *
     * Clauses count, not just sentences. A neural synthesiser on a phone runs close to real time —
     * Kokoro measured 1.17x — so waiting for a full sentence means waiting out its entire spoken
     * duration before a single word is heard. Cutting at commas as well gets the first audio out in
     * a fraction of that, and the pieces still play back-to-back because they are queued in order.
     *
     * Sentence terminators only count when followed by whitespace or end-of-string, so decimals and
     * abbreviations ("3:00 PM.", "1.5") do not split mid-number.
     */
    fun lastBoundary(text: String, from: Int): Int {
        var best = from
        var i = from
        while (i < text.length) {
            val c = text[i]
            val terminal = c == '.' || c == '!' || c == '?' || c == '\n' || c == '।'
            val clause = c == ',' || c == ';' || c == ':' || c == '—'
            if (terminal || clause) {
                val next = text.getOrNull(i + 1)
                if (next == null || next.isWhitespace()) {
                    // Never emit a scrap. A two-word fragment synthesises with no prosody and
                    // sounds worse than the wait it saved.
                    if (i + 1 - from >= MIN_CHUNK) best = i + 1
                }
            }
            i++
        }
        return best
    }

    /**
     * Strip what cannot be spoken, and report whether anything speakable remains.
     *
     * Models emit emoji. Handing "🌊" to a synthesiser measured a full second of compute for a
     * chunk with no words in it — pure latency for either silence or a mispronounced symbol name.
     */
    fun speakable(text: String): String {
        val cleaned = buildString(text.length) {
            for (c in text) {
                // Keep letters, digits, whitespace and the punctuation that shapes prosody.
                if (c.isLetterOrDigit() || c.isWhitespace() || c in KEEP_PUNCTUATION) append(c)
            }
        }.replace(REPEATED_SPACE, " ").trim()
        return if (cleaned.any { it.isLetterOrDigit() }) cleaned else ""
    }

    private const val KEEP_PUNCTUATION = ".,!?;:'\u2019-\u2014"
    private val REPEATED_SPACE = Regex("\\s{2,}")

    /** Characters, not words: below this a chunk is a scrap rather than a clause. */
    private const val MIN_CHUNK = 12

    /**
     * Where to cut the **first** chunk of an answer, which is the only one whose synthesis the user
     * actually waits through.
     *
     * Synthesis runs at about 1.17x realtime, so once audio is playing the pipeline stays ahead of
     * it and every later chunk is ready before the previous finishes. The whole perceived lag is
     * therefore the first chunk alone: waiting for a clause means waiting to synthesise a clause.
     * Cutting at the first word boundary past a few words gets speech started in a fraction of that,
     * and nothing is lost downstream because the rest catches up on its own.
     */
    fun firstBoundary(text: String): Int {
        if (text.length < FIRST_CHUNK_MIN) return 0
        // The EARLIEST break past the minimum, never the latest: preferring the last punctuation in
        // range meant a short sentence was returned whole, which is the wait this exists to avoid.
        var i = FIRST_CHUNK_MIN
        while (i < text.length && i < FIRST_CHUNK_MAX) {
            if (text[i].isWhitespace()) return i
            i++
        }
        // No boundary yet and the text is still growing: wait rather than split a word.
        return if (text.length >= FIRST_CHUNK_MAX) FIRST_CHUNK_MAX else 0
    }

    private const val FIRST_CHUNK_MIN = 14
    private const val FIRST_CHUNK_MAX = 48
}
