package com.blueberry.router

/** A catalogue shaped like a real phone, including the collisions that matter. */
fun testCatalogue(): Catalogue = Catalogue(
    listOf(
        AppEntry("com.spotify.music", "Spotify", listOf(ShortcutEntry("search", "Search"))),
        AppEntry("com.google.android.apps.youtube.music", "YT Music"),
        // "Google" and "Google Maps" collide on the first word. This is the case that must not
        // fire on a partial transcript.
        AppEntry("com.google.android.googlequicksearchbox", "Google"),
        AppEntry("com.google.android.apps.maps", "Google Maps"),
        AppEntry("com.whatsapp", "WhatsApp", listOf(ShortcutEntry("newchat", "New chat"))),
        AppEntry("com.android.settings", "Settings"),
        AppEntry("com.android.chrome", "Chrome"),
        AppEntry("md.obsidian", "Obsidian"),
    ),
    aliases = mapOf("youtube music" to "com.google.android.apps.youtube.music"),
)

/** Records what was captured so a test can assert on the exact text that reached the vault. */
class RecordingNoteSink(private val target: String = "Inbox.md") : NoteSink {
    val captured = mutableListOf<String>()
    var failWith: String? = null

    override fun append(text: String): SaveResult {
        failWith?.let { return SaveResult.Failed(it) }
        captured += text
        return SaveResult.Ok(target)
    }
}

fun testContext(
    catalogue: Catalogue = testCatalogue(),
    notes: NoteSink = RecordingNoteSink(),
    defaults: DefaultsStore = DefaultsStore.inMemory(),
    contacts: ContactResolver = ContactResolver.NONE,
) = RouteContext(catalogue, notes, defaults, contacts)
