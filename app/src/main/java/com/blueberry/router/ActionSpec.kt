package com.blueberry.router

/**
 * A description of an Android action, with no Android types in it.
 *
 * The router emits these; `tools/IntentFactory` turns them into real `Intent`s. Keeping the
 * description pure is what lets the whole routing layer be unit-tested on a plain JVM, and it
 * keeps the "no UI automation" boundary visible: there is no shape here that can express a tap
 * at a coordinate. Only documented intents, published shortcuts, and URI schemes.
 */
sealed interface ActionSpec {

    /** A documented platform intent. [uri] is the data URI; [packageName] targets one app. */
    data class Launch(
        val action: String,
        val uri: String? = null,
        val mimeType: String? = null,
        val packageName: String? = null,
        val extras: List<Extra> = emptyList(),
    ) : ActionSpec

    /** Resolve the app's own launcher activity and start it. */
    data class OpenApp(
        val packageName: String,
        val label: String,
    ) : ActionSpec

    /**
     * Start a shortcut the app published. Launchers get the id, label and icon but never the
     * underlying intent, so this is launched by id and the intent is never constructed here.
     */
    data class Shortcut(
        val packageName: String,
        val shortcutId: String,
        val label: String,
    ) : ActionSpec

    /** Nothing to fire — the tool did its work in-process (a note, a read). */
    data object None : ActionSpec
}

/** A typed intent extra. Kept closed so the Android mapper is exhaustive. */
sealed interface Extra {
    val key: String

    data class Text(override val key: String, val value: String) : Extra
    data class Number(override val key: String, val value: Int) : Extra
    data class Flag(override val key: String, val value: Boolean) : Extra
    data class Timestamp(override val key: String, val value: Long) : Extra
}
