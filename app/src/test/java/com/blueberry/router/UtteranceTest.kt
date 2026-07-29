package com.blueberry.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UtteranceTest {

    @Test
    fun `normalises case and punctuation`() {
        assertEquals("note down talk to oisl", Utterance.of("Note down: talk to OISL!").normalized)
    }

    @Test
    fun `apostrophes join rather than split`() {
        assertEquals("whats on today", Utterance.of("What's on today?").normalized)
    }

    @Test
    fun `raw slice preserves what the user actually said`() {
        val u = Utterance.of("Note down: talk to OISL about success criteria.")
        // Tokens 2.. are the note body. Capitals and the internal punctuation must survive, because
        // this string is what gets written to the vault.
        assertEquals("talk to OISL about success criteria", u.rawSlice(2, u.size))
    }

    @Test
    fun `strips leading and trailing filler`() {
        assertEquals("open spotify", Utterance.of("hey blueberry open spotify please").normalized)
    }

    @Test
    fun `never strips an utterance down to nothing`() {
        assertTrue(Utterance.of("please").size > 0)
    }

    @Test
    fun `handles empty and whitespace input`() {
        assertTrue(Utterance.of("").isEmpty())
        assertTrue(Utterance.of("   ,, ").isEmpty())
    }

    @Test
    fun `digits survive tokenisation`() {
        assertEquals("pay jash 100", Utterance.of("Pay Jash ₹100").normalized)
    }
}
