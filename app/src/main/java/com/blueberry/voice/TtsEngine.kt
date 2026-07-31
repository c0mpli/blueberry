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
     * Index just past the last sentence terminator in `text[from..]`, or [from] when there is none.
     *
     * A terminator only counts when followed by whitespace or the end of the string, so decimals
     * and abbreviations do not split a sentence mid-number.
     */
    fun lastBoundary(text: String, from: Int): Int {
        var best = from
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (c == '.' || c == '!' || c == '?' || c == '\n' || c == '।') {
                val next = text.getOrNull(i + 1)
                if (next == null || next.isWhitespace()) best = i + 1
            }
            i++
        }
        return best
    }
}
