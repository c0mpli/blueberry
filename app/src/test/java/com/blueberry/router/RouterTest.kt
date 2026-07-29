package com.blueberry.router

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterTest {

    private val notes = RecordingNoteSink()
    private val ctx = testContext(notes = notes)
    private val router = Router()

    // -----------------------------------------------------------------------------------------
    // Milestone 4: a cache hit lands a note in the vault with no inference at all.
    // -----------------------------------------------------------------------------------------

    @Test
    fun `note down lands in the vault verbatim`() = runTest {
        val result = router.route("note down talk to OISL about success criteria", ctx)

        assertTrue("expected Saved, got $result", result is RouterResult.Saved)
        result as RouterResult.Saved
        assertEquals("Inbox.md", result.target)
        assertEquals(listOf("talk to OISL about success criteria"), notes.captured)
    }

    @Test
    fun `capture works without the model`() = runTest {
        // The router here has Llm.Unavailable. If this ever needs inference, it has regressed.
        val result = router.route("jot down pick up the parcel", ctx)
        assertTrue(result is RouterResult.Saved)
        assertEquals(listOf("pick up the parcel"), notes.captured)
    }

    @Test
    fun `capture is free while locked`() = runTest {
        val result = router.route("note down check the boiler", ctx)
        assertTrue("save_note must not require unlock", !result.requiresUnlock)
    }

    @Test
    fun `a failing vault write surfaces as a failure rather than a silent success`() = runTest {
        notes.failWith = "Vault permission was revoked."
        val result = router.route("note down anything", ctx)
        assertEquals(RouterResult.Failed("Vault permission was revoked."), result)
    }

    // -----------------------------------------------------------------------------------------
    // Actions
    // -----------------------------------------------------------------------------------------

    @Test
    fun `open app builds a launch action and needs unlocking`() = runTest {
        val result = router.route("open spotify", ctx)
        assertTrue(result is RouterResult.Action)
        result as RouterResult.Action
        assertEquals(ActionSpec.OpenApp("com.spotify.music", "Spotify"), result.spec)
        assertEquals("Open Spotify", result.label)
        assertTrue("firing an intent trips the keyguard", result.requiresUnlock)
    }

    @Test
    fun `an uninstalled app fails clearly`() = runTest {
        val result = router.route("open telegram", ctx)
        assertEquals(RouterResult.Failed("No app called telegram."), result)
    }

    @Test
    fun `play media stays app-agnostic until a default is set`() = runTest {
        val result = router.route("play sapphire", ctx) as RouterResult.Action
        val spec = result.spec as ActionSpec.Launch
        assertNull("no default music app yet, so Android should choose", spec.packageName)
        assertEquals("Play sapphire", result.label)
    }

    @Test
    fun `play media targets the chosen app once a default exists`() = runTest {
        val defaults = DefaultsStore.inMemory(mapOf(DefaultKeys.MUSIC to "com.spotify.music"))
        val withDefault = testContext(notes = notes, defaults = defaults)

        val result = router.route("play sapphire", withDefault) as RouterResult.Action
        assertEquals("com.spotify.music", (result.spec as ActionSpec.Launch).packageName)
        assertEquals("Play sapphire on Spotify", result.label)
    }

    @Test
    fun `navigation percent-encodes the destination`() = runTest {
        val result = router.route("navigate to indiranagar metro", ctx) as RouterResult.Action
        assertEquals("google.navigation:q=indiranagar%20metro", (result.spec as ActionSpec.Launch).uri)
    }

    // -----------------------------------------------------------------------------------------
    // Cache behaviour
    // -----------------------------------------------------------------------------------------

    @Test
    fun `unmatched input falls through to the model`() = runTest {
        val result = router.route("whats a mamba ssm", ctx)
        assertEquals(RouterResult.Failed(Router.NO_MODEL), result)
    }

    @Test
    fun `confirmed resolutions are remembered and corrections drop them`() {
        val call = ToolCall(Tools.OPEN_APP, mapOf("app" to SlotValue.App(testCatalogue().resolve("spotify").first(), "spotify")))
        val hash = ctx.catalogue.hash

        router.confirm("fire up my tunes", call, hash)
        assertEquals(1, router.cache.learnedCount)

        val hit = router.cache.lookup(Utterance.of("fire up my tunes"), ctx)
        assertEquals(CacheHit.Source.LEARNED, hit?.source)
        assertEquals(Tools.OPEN_APP, hit?.call?.tool)

        router.correct("fire up my tunes")
        assertEquals(0, router.cache.learnedCount)
    }

    @Test
    fun `a learned entry pointing at an uninstalled app is dropped rather than served`() {
        val call = ToolCall(Tools.OPEN_APP, mapOf("app" to SlotValue.App(testCatalogue().resolve("spotify").first(), "spotify")))
        router.confirm("fire up my tunes", call, ctx.catalogue.hash)

        // Spotify gets uninstalled.
        val without = testContext(catalogue = Catalogue(testCatalogue().apps.filterNot { it.packageName == "com.spotify.music" }))
        assertNull(router.cache.lookup(Utterance.of("fire up my tunes"), without))
        assertEquals("the stale entry should have been evicted", 0, router.cache.learnedCount)
    }

    @Test
    fun `catalogue changes invalidate entries learned against the old one`() {
        val call = ToolCall(Tools.OPEN_APP, mapOf("app" to SlotValue.App(testCatalogue().resolve("spotify").first(), "spotify")))
        router.confirm("fire up my tunes", call, "old-hash")

        router.onCatalogueChanged("new-hash")
        assertEquals(0, router.cache.learnedCount)
    }

    @Test
    fun `catalogue hash is stable and content-addressed`() {
        assertEquals(testCatalogue().hash, testCatalogue().hash)
        val fewer = Catalogue(testCatalogue().apps.drop(1))
        assertNotEquals(testCatalogue().hash, fewer.hash)
    }

    @Test
    fun `empty transcripts fail rather than routing`() = runTest {
        assertTrue(router.route("   ", ctx) is RouterResult.Failed)
    }

    @Test
    fun `every seeded pattern parses and names a registered tool`() {
        val registry = ToolRegistry.default()
        for (seed in ResolutionCache.SEEDS) {
            assertTrue(
                "seed '${seed.pattern.source}' names unknown tool '${seed.tool}'",
                registry.has(seed.tool),
            )
        }
    }
}
