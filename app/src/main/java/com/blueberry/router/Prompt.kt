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

    /** The design budgets around sixty apps for roughly a thousand tokens. */
    const val MAX_CATALOGUE_IN_PROMPT = 60


    /**
     * Chat template markers. `parse_special = true` at tokenisation turns these into real control
     * tokens rather than literal text — get the family wrong and they stay as visible text in the
     * prompt, which quietly wrecks instruction following.
     */
    enum class Template(val user: String, val end: String, val model: String) {
        /** Qwen / ChatML. Also `/no_think` to suppress Qwen3's reasoning block, which would
         *  otherwise burn hundreds of tokens before the answer starts. */
        CHATML("<|im_start|>user\n", "<|im_end|>\n", "<|im_start|>assistant\n"),

        GEMMA("<start_of_turn>user\n", "<end_of_turn>\n", "<start_of_turn>model\n"),
    }

    @Volatile
    var template: Template = Template.CHATML

    /** Qwen3 reasons by default; `/no_think` turns that off. Nothing else in the family needs it. */
    private val noThink: String get() = if (template == Template.CHATML) " /no_think" else ""

    private val TURN_USER get() = template.user
    private val TURN_END get() = template.end
    private val TURN_MODEL get() = template.model

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
        // Labels only, and capped. Package names would double the token count and the model never
        // needs them — the app slot is resolved against the full catalogue on the Kotlin side, so
        // this list only has to help the model pick *which* app for a fuzzy request. On a phone with
        // 207 apps the uncapped list alone was over 600 tokens of prefix.
        append(ctx.catalogue.apps.take(MAX_CATALOGUE_IN_PROMPT).joinToString(", ") { it.label })
        if (ctx.catalogue.size > MAX_CATALOGUE_IN_PROMPT) append(", and others")
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
            "Request: " + transcript.trim() + noThink + "\n" +
            TURN_END + TURN_MODEL

    /** Stage two. Only the category's own tools are offered, and the grammar enforces the shape. */
    fun callSuffix(transcript: String, tools: List<ToolSpec>): String =
        "Choose one tool and fill its arguments. Reply with JSON only.\n" +
            tools.joinToString("\n") { "- ${it.name}(${it.args.joinToString(", ") { a -> a.name }})" } +
            "\nRequest: " + transcript.trim() + noThink + "\n" +
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
