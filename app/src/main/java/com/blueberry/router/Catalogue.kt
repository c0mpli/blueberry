package com.blueberry.router

/** A shortcut an app publishes. Launchers see the id and label; never the intent behind it. */
data class ShortcutEntry(val id: String, val label: String)

/** One launchable app, plus whatever it publishes to the long-press menu. */
data class AppEntry(
    val packageName: String,
    val label: String,
    val shortcuts: List<ShortcutEntry> = emptyList(),
)

/**
 * The installed-app catalogue, as data.
 *
 * Two consumers with opposite needs: the model gets it serialised into the system prompt as the
 * fixed prefix that the saved KV state is built from, and the resolution cache queries it on every
 * partial transcript. So it is built once per package-change and indexed for lookup, never scanned.
 *
 * [hash] identifies the contents. It goes in the turn log and invalidates both the KV state and any
 * learned cache entry that resolved to a package — a cached `open_app` for something uninstalled
 * must not survive.
 */
class Catalogue(
    val apps: List<AppEntry>,
    /** Spoken-form overrides: normalised alias -> package name. "yt music" -> com.google.android.apps.youtube.music */
    private val aliases: Map<String, String> = emptyMap(),
) {

    private val byPackage: Map<String, AppEntry> = apps.associateBy { it.packageName }

    /** Normalised label -> entries. A list because two apps can share a label. */
    private val byLabel: Map<String, List<AppEntry>> = buildMap<String, MutableList<AppEntry>> {
        for (app in apps) {
            getOrPut(normalizeLabel(app.label)) { mutableListOf() }.add(app)
        }
    }

    /** Sorted normalised labels, so prefix queries are a binary search rather than a scan. */
    private val sortedLabels: List<String> = byLabel.keys.sorted()

    val size: Int get() = apps.size

    fun byPackage(packageName: String): AppEntry? = byPackage[packageName]

    /** Exact match on the spoken form, after aliases. Empty when nothing matches. */
    fun resolve(spoken: String): List<AppEntry> {
        val key = normalizeLabel(spoken)
        if (key.isEmpty()) return emptyList()
        aliases[key]?.let { pkg -> byPackage[pkg]?.let { return listOf(it) } }
        return byLabel[key].orEmpty()
    }

    /**
     * True when some *other* label strictly extends [spoken] — "google" is extended by "google
     * maps". The partial gate uses this to decide whether an exact match is safe to fire on
     * immediately or whether the user is probably still talking.
     */
    fun hasLongerLabelStartingWith(spoken: String): Boolean {
        val key = normalizeLabel(spoken)
        if (key.isEmpty()) return false
        val idx = sortedLabels.binarySearch(key).let { if (it < 0) -it - 1 else it + 1 }
        if (idx >= sortedLabels.size) return false
        val next = sortedLabels[idx]
        return next.length > key.length && next.startsWith(key) && next[key.length] == ' '
    }

    /** Every label beginning with [prefix], for the drawer's search field. */
    fun labelsStartingWith(prefix: String, limit: Int = 20): List<AppEntry> {
        val key = normalizeLabel(prefix)
        if (key.isEmpty()) return emptyList()
        val out = ArrayList<AppEntry>(limit)
        var idx = sortedLabels.binarySearch(key).let { if (it < 0) -it - 1 else it }
        while (idx < sortedLabels.size && out.size < limit) {
            val label = sortedLabels[idx]
            if (!label.startsWith(key)) break
            byLabel[label]?.let { out.addAll(it) }
            idx++
        }
        return out
    }

    /** Stable, order-independent identity of the catalogue's contents. */
    val hash: String by lazy(LazyThreadSafetyMode.NONE) {
        var acc = 0xcbf29ce484222325UL
        for (line in apps.map { app ->
            buildString {
                append(app.packageName).append('|').append(app.label)
                for (s in app.shortcuts.sortedBy { it.id }) append('|').append(s.id)
            }
        }.sorted()) {
            for (ch in line) {
                acc = acc xor ch.code.toULong()
                acc *= 0x100000001b3UL
            }
            acc = acc xor 0x0aUL
            acc *= 0x100000001b3UL
        }
        acc.toString(16).padStart(16, '0')
    }

    companion object {
        val EMPTY = Catalogue(emptyList())

        /** Same rules as [Utterance] tokenisation, applied to a label. */
        fun normalizeLabel(label: String): String =
            Utterance.of(label).normalized
    }
}
