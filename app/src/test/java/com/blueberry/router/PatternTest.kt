package com.blueberry.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternTest {

    private val ctx = MatchContext(testCatalogue())

    private fun match(pattern: String, said: String): Match? =
        Pattern.parse(pattern).match(Utterance.of(said), ctx)

    @Test
    fun `literal and app slot`() {
        val m = match("open <app>", "open spotify")
        assertNotNull("open spotify should match 'open <app>'", m)
        assertEquals("com.spotify.music", (m!!["app"] as SlotValue.App).entry.packageName)
    }

    @Test
    fun `alternation matches any listed form`() {
        for (verb in listOf("open", "launch", "start", "go to", "switch to")) {
            assertNotNull("$verb should match", match("(open|launch|start|go to|switch to) <app>", "$verb chrome"))
        }
    }

    @Test
    fun `app slot prefers the longest resolvable span`() {
        val m = match("open <app>", "open google maps")!!
        val app = m["app"] as SlotValue.App
        assertEquals("com.google.android.apps.maps", app.entry.packageName)
    }

    @Test
    fun `unresolvable app in final position binds as unknown`() {
        val m = match("open <app>", "open telegram")!!
        assertTrue(m["app"] is SlotValue.UnknownApp)
        assertFalse(m.resolvedEverySlot)
    }

    @Test
    fun `free text captures the raw remainder`() {
        val m = match("(note down|note) <text>", "Note down: talk to OISL about success criteria")!!
        assertEquals("talk to OISL about success criteria", (m["text"] as SlotValue.Text).spoken)
    }

    @Test
    fun `longer alternation is preferred when listed first`() {
        val m = match("(note down|note) <text>", "note down buy milk")!!
        assertEquals("buy milk", (m["text"] as SlotValue.Text).spoken)
    }

    @Test
    fun `trailing words that the pattern cannot consume are not a match`() {
        // "open <app>" must not silently swallow the tail of a longer sentence.
        assertNull(match("open <app>", "open spotify and play sapphire"))
    }

    @Test
    fun `alias resolves to the aliased package`() {
        val m = match("(play|put on) <app>", "play youtube music")!!
        assertEquals("com.google.android.apps.youtube.music", (m["app"] as SlotValue.App).entry.packageName)
    }

    @Test
    fun `couldMatch treats a prefix as still viable`() {
        val p = Pattern.parse("(note down|note) <text>")
        assertTrue(p.couldMatch(Utterance.of("note"), ctx))
        assertTrue(p.couldMatch(Utterance.of("note down"), ctx))
        assertFalse(p.couldMatch(Utterance.of("open spotify"), ctx))
    }

    @Test
    fun `anchorless patterns are flagged`() {
        assertFalse(Pattern.parse("<app>").hasLiteralAnchor)
        assertTrue(Pattern.parse("open <app>").hasLiteralAnchor)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a free text slot must be last`() {
        Pattern.parse("note <text> to <app>")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unclosed slot is rejected at parse time`() {
        Pattern.parse("open <app")
    }
}
