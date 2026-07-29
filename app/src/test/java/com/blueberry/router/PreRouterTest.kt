package com.blueberry.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Milestone 5: "open spotify" fires on the partial transcript, before the silence timeout elapses.
 *
 * These tests replay a partial transcript the way the recogniser delivers it — growing a word at a
 * time — and assert exactly where the gate commits.
 */
class PreRouterTest {

    private val ctx = testContext()
    private val gate = PreRouter(ResolutionCache(), stabilityMs = 300L)

    /** Fresh partial: the text just changed, so no stability has accrued. */
    private fun partial(text: String, unchangedForMs: Long = 0L) =
        gate.onPartial(text, ctx, unchangedForMs)

    @Test
    fun `fires immediately on an exact unambiguous app`() {
        val decision = partial("open spotify")
        assertTrue("expected Fire, got $decision", decision is PartialDecision.Fire)
        decision as PartialDecision.Fire
        assertEquals(PartialDecision.Reason.EXACT, decision.reason)
        assertEquals(Tools.OPEN_APP, decision.hit.call.tool)
        assertEquals(
            "com.spotify.music",
            (decision.hit.call["app"] as SlotValue.App).entry.packageName,
        )
    }

    @Test
    fun `waits through the partials that precede it`() {
        // This is the actual sequence a recogniser emits for "open spotify".
        for (prefix in listOf("op", "open", "open spot", "open spotif")) {
            assertEquals("should still be waiting at '$prefix'", PartialDecision.Wait, partial(prefix))
        }
        assertTrue(partial("open spotify") is PartialDecision.Fire)
    }

    @Test
    fun `does not fire early when a longer app label starts with the same words`() {
        // "Google" resolves exactly, but "Google Maps" is still a live possibility.
        assertEquals(PartialDecision.Wait, partial("open google"))
    }

    @Test
    fun `fires on stability once the transcript stops growing`() {
        val decision = partial("open google", unchangedForMs = 300L)
        assertTrue("expected Fire, got $decision", decision is PartialDecision.Fire)
        assertEquals(PartialDecision.Reason.STABLE, (decision as PartialDecision.Fire).reason)
        assertEquals("com.google.android.googlequicksearchbox", (decision.hit.call["app"] as SlotValue.App).entry.packageName)
    }

    @Test
    fun `keeps waiting for google maps if the user was mid-phrase`() {
        val decision = partial("open google maps")
        assertTrue(decision is PartialDecision.Fire)
        assertEquals(
            "com.google.android.apps.maps",
            ((decision as PartialDecision.Fire).hit.call["app"] as SlotValue.App).entry.packageName,
        )
    }

    @Test
    fun `free text never fires early however confident the match looks`() {
        // The note body is still growing by definition, so there is no confident partial for it.
        assertEquals(PartialDecision.Wait, partial("note down talk to OISL"))
        assertEquals(PartialDecision.Wait, partial("note down talk to OISL about"))

        val settled = partial("note down talk to OISL about success criteria", unchangedForMs = 300L)
        assertTrue(settled is PartialDecision.Fire)
        assertEquals(PartialDecision.Reason.STABLE, (settled as PartialDecision.Fire).reason)
    }

    @Test
    fun `a sentence no seed can match is handed straight to the model`() {
        assertEquals(PartialDecision.Miss, partial("whats a mamba ssm"))
    }

    @Test
    fun `an anchorless app name does not hold the gate open`() {
        // "<app>" is a viable prefix of every sentence ever spoken; it must not cause Wait.
        assertEquals(PartialDecision.Miss, partial("explain how a transformer works"))
    }

    @Test
    fun `bare app name still resolves on its own`() {
        val decision = partial("spotify")
        assertTrue("expected Fire, got $decision", decision is PartialDecision.Fire)
        assertEquals(Tools.OPEN_APP, (decision as PartialDecision.Fire).hit.call.tool)
    }

    @Test
    fun `an uninstalled app is a clear failure rather than a model round trip`() {
        val hit = gate.onFinal("open telegram", ctx)
        assertEquals(Tools.OPEN_APP, hit?.call?.tool)
        assertTrue(hit!!.call["app"] is SlotValue.UnknownApp)
    }

    @Test
    fun `empty partials wait`() {
        assertEquals(PartialDecision.Wait, partial(""))
    }
}
