package com.blueberry.router

/**
 * What a tool looks like *to the model*, as opposed to what it does.
 *
 * Kept separate from the [Tool] builders so the grammar generator and the prompt builder have
 * something declarative to read, and so both stay in the pure package where they can be unit
 * tested without a model or a device.
 */
data class ToolSpec(
    val name: String,
    val category: ToolCategory,
    val args: List<ArgSpec>,
    /** One line, in the system prompt. Small models pay more attention to short descriptions. */
    val description: String,
)

data class ArgSpec(val name: String, val type: ArgType, val required: Boolean = true)

enum class ArgType { STRING, INT }

/**
 * Never hand a small model seventeen tools — accuracy degrades sharply with tool count. The router
 * classifies the category first, then presents only the two or three tools inside it. This enum is
 * that first, one-token decision.
 */
enum class ToolCategory {
    /** Fires an intent at another app. Terminal. */
    ACTION,

    /** Writes something down. Terminal, and works while locked. */
    CAPTURE,

    /** Reads local data and loops back to the model to answer in prose. */
    QUERY,

    /** Draws an answer instead of only speaking it. */
    VISUAL,

    /** No tool — just converse. This is the default, and most turns should land here. */
    CHAT,
}

object ToolSpecs {

    val ALL: List<ToolSpec> = listOf(
        ToolSpec(
            name = Tools.OPEN_APP,
            category = ToolCategory.ACTION,
            args = listOf(ArgSpec("app", ArgType.STRING)),
            description = "Open an installed app by name.",
        ),
        ToolSpec(
            name = Tools.PLAY_MEDIA,
            category = ToolCategory.ACTION,
            args = listOf(ArgSpec("query", ArgType.STRING)),
            description = "Play a song, artist or album.",
        ),
        ToolSpec(
            name = Tools.NAVIGATE,
            category = ToolCategory.ACTION,
            args = listOf(ArgSpec("place", ArgType.STRING)),
            description = "Start navigation to a place.",
        ),
        ToolSpec(
            name = Tools.SEARCH_WEB,
            category = ToolCategory.ACTION,
            args = listOf(ArgSpec("query", ArgType.STRING)),
            description = "Search the web.",
        ),
        ToolSpec(
            name = Tools.SAVE_NOTE,
            category = ToolCategory.CAPTURE,
            args = listOf(ArgSpec("text", ArgType.STRING)),
            description = "Write a note down.",
        ),
        ToolSpec(
            name = Tools.SHOW_CHART,
            category = ToolCategory.VISUAL,
            args = listOf(
                ArgSpec("kind", ArgType.STRING),
                ArgSpec("title", ArgType.STRING),
                ArgSpec("labels", ArgType.STRING),
                ArgSpec("values", ArgType.STRING),
                ArgSpec("narration", ArgType.STRING, required = false),
            ),
            description = "Draw a chart: kind is one of bar, line, timeline, table, steps, graph. " +
                "labels and values are comma-separated. Only when a picture beats a sentence.",
        ),
    )

    fun inCategory(category: ToolCategory): List<ToolSpec> = ALL.filter { it.category == category }

    fun byName(name: String): ToolSpec? = ALL.firstOrNull { it.name == name }
}
