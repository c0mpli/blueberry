package com.blueberry.router

/**
 * Prompt construction, split along exactly one line: what is fixed, and what changes per turn.
 *
 * That split is not cosmetic. [systemPrefix] is the ~1K-token block whose KV state gets prefilled
 * once and restored from disk on every subsequent turn; everything in [categorySuffix],
 * [callSuffix] and [explainSuffix] is the twenty-odd tokens that actually get prefilled per
 * request. Anything that varies per turn must not leak into the prefix, or the cache is invalidated
 * every time and the whole latency argument for running locally collapses.
 *
 * Pure Kotlin, so prompt shape is unit tested without a model.
 */
object Prompt {

    // Gemma-family turn markers. `parse_special = true` at tokenisation turns these into the real
    // control tokens rather than literal text.
    private const val TURN_USER = "<start_of_turn>user\n"
    private const val TURN_END = "<end_of_turn>\n"
    private const val TURN_MODEL = "<start_of_turn>model\n"

    /**
     * The fixed prefix: who the model is, what tools exist, what is installed, what the user has
     * already chosen. Stable for a given catalogue and defaults set — its hash is the KV state's
     * filename.
     */
    fun systemPrefix(ctx: RouteContext): String = buildString {
        append(TURN_USER)
        append(
            "You route short spoken commands on an Android phone into one tool call.\n" +
                "Input is often a mix of English and Hindi written in Latin script. Answer in the " +
                "language the request arrived in.\n\n"
        )

        append("TOOLS\n")
        for (spec in ToolSpecs.ALL) {
            append("- ").append(spec.name).append('(')
            append(spec.args.joinToString(", ") { it.name })
            append(") [").append(spec.category.name.lowercase()).append("] ")
            append(spec.description).append('\n')
        }

        append("\nINSTALLED APPS\n")
        // Labels only. Package names would double the token count and the model never needs them —
        // the app slot is resolved against the catalogue on the Kotlin side.
        append(ctx.catalogue.apps.joinToString(", ") { it.label })
        append('\n')

        val defaults = describeDefaults(ctx.defaults)
        if (defaults.isNotEmpty()) {
            // Every default listed here is a clarification round trip that never happens.
            append("\nUSER DEFAULTS\n").append(defaults).append('\n')
        }

        append('\n')
    }

    /** Stage one. One word, decoded under a closed grammar. */
    fun categorySuffix(transcript: String): String =
        "Classify this request into one category.\n" +
            "action = do something in an app. capture = write something down. " +
            "query = read the user's own data. visual = the answer is better drawn than said. " +
            "chat = just answer or explain in words.\n" +
            "Most requests are chat. Only pick a tool category when the user actually asked for " +
            "something to happen.\n" +
            "Request: " + transcript.trim() + "\n" +
            TURN_END + TURN_MODEL

    /** Stage two. Only the category's own tools are offered, and the grammar enforces the shape. */
    fun callSuffix(transcript: String, tools: List<ToolSpec>): String =
        "Choose one tool and fill its arguments. Reply with JSON only.\n" +
            tools.joinToString("\n") { "- ${it.name}(${it.args.joinToString(", ") { a -> a.name }})" } +
            "\nRequest: " + transcript.trim() + "\n" +
            TURN_END + TURN_MODEL

    /** The explain path. Shallower than a frontier model, and knowingly so. */
    fun explainSuffix(transcript: String): String =
        "Answer in two or three short sentences, spoken aloud, no markdown.\n" +
            "Request: " + transcript.trim() + "\n" +
            TURN_END + TURN_MODEL

    private fun describeDefaults(defaults: DefaultsStore): String {
        val lines = buildList {
            defaults.get(DefaultKeys.MUSIC)?.let { add("music app: $it") }
            defaults.get(DefaultKeys.MESSAGING)?.let { add("messaging app: $it") }
            defaults.get(DefaultKeys.NAVIGATION)?.let { add("navigation app: $it") }
            defaults.get(DefaultKeys.BROWSER)?.let { add("browser: $it") }
            defaults.get(DefaultKeys.REMIND_AT_TIME)?.let { add("\"remind me at <time>\" means: $it") }
            defaults.get(DefaultKeys.REMIND_ON_DATE)?.let { add("\"remind me on <date>\" means: $it") }
            defaults.get(DefaultKeys.REMIND_NO_TIME)?.let { add("\"remind me to <task>\" means: $it") }
        }
        return lines.joinToString("\n")
    }
}
