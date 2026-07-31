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
        CHATML(
            "<|im_start|>user\n",
            "<|im_end|>\n",
            // The pre-closed think block is deliberate. Qwen3 reasons by default, and "/no_think"
            // is only a hint it can ignore — when it does emit <think>, the grammar has no
            // production for it and llama.cpp aborts the whole process on the empty stack.
            // Opening the assistant turn with the block already closed removes the possibility.
            "<|im_start|>assistant\n<think>\n\n</think>\n\n",
        ),

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
            // Identity first. Without it the model has nothing to be on a conversational turn, and
            // a prompt that only describes routing makes it echo the question back rather than
            // answer — "what is your name" came back as "What is your name?".
            "You are Blueberry, a voice assistant built into an Android phone.\n" +
                "You do two things: you carry out requests by calling one tool, and you answer " +
                "questions by talking.\n" +
                "Your name is Blueberry. If asked your name, say it.\n" +
                "When you are talking, reply as yourself in one or two short spoken sentences. " +
                "Never repeat the question back. Never narrate what you are doing. Do not greet " +
                "the user unless they greeted you first.\n" +
                "Input is often a mix of English and Hindi written in Latin script; answer in the " +
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

        append("\nHOW TO CLASSIFY A REQUEST\n")
        append(
            // Fenced and labelled because this block lives in the prefix that the *chat* path also
            // sees, and a small model will happily lift a line out of it and say it back. Asked the
            // time, it answered "What is your name?" — copied straight from here.
            "These lines are examples of CATEGORY LABELLING ONLY. They are not things to say, and " +
                "they are never answers. Never repeat one back to the user.\n" +
                "open spotify -> action\n" +
                "play sapphire -> action\n" +
                "navigate to the airport -> action\n" +
                "note down buy milk -> capture\n" +
                "remind me to call vivek -> capture\n" +
                "hello -> chat\n" +
                "what is your name -> chat\n" +
                "can you hear me -> chat\n" +
                "how are you -> chat\n" +
                "what is a transformer -> chat\n" +
                "explain how attention works -> chat\n" +
                "chart my spending by month -> visual\n" +
                "draw me a bar chart of x -> visual\n" +
                "Anything that is a greeting, a question, or a request for an explanation is chat. " +
                "Only use visual when the user explicitly asks for a chart, graph or diagram.\n" +
                "END OF EXAMPLES.\n"
        )

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
            "Follow the examples above. Default to chat unless the user clearly asked for " +
            "something to happen.\n" +
            "Request: " + transcript.trim() + noThink + "\n" +
            TURN_END + TURN_MODEL

    /** Stage two. Only the category's own tools are offered, and the grammar enforces the shape. */
    fun callSuffix(transcript: String, tools: List<ToolSpec>): String =
        "Choose one tool and fill its arguments. Reply with JSON only.\n" +
            tools.joinToString("\n") { "- ${it.name}(${it.args.joinToString(", ") { a -> a.name }})" } +
            "\nRequest: " + transcript.trim() + noThink + "\n" +
            TURN_END + TURN_MODEL

    /**
     * A **standalone** prompt for conversation, sharing nothing with the routing prefix.
     *
     * The routing prefix carries a tool list, sixty app names and a block of category examples.
     * All of it is noise when the user is simply talking, and worse than noise: a small model lifts
     * lines out of it and says them back. Asked the time, it answered "What is your name?" — copied
     * verbatim from an example. Fencing the block with warnings did not stop it, because the model
     * is not reliable enough to be told what to ignore.
     *
     * So conversation gets its own short context. It costs a prefill of roughly eighty tokens
     * instead of a cache hit, which is a real price — but answering the wrong question instantly is
     * not a trade worth making.
     */
    fun conversationPrompt(transcript: String): String =
        TURN_USER +
            "You are Blueberry, a voice assistant on this phone. Your name is Blueberry.\n" +
            "Answer in one or two short spoken sentences. Be direct and warm.\n" +
            "Never repeat the question back. Never ask a question of your own unless you genuinely " +
            "need to know something. No markdown, no lists, no emoji.\n\n" +
            transcript.trim() + noThink + "\n" +
            TURN_END + TURN_MODEL

    /** Kept for the tool-call path, which does need the catalogue in context. */
    fun explainSuffix(transcript: String): String =
        // Addressed directly rather than as "Request: <text>". A labelled field invites a small
        // model to continue the transcript instead of responding to it — "what is your name" came
        // back as "What is your name?".
        transcript.trim() + noThink + "\n\n" +
            "Answer that question out loud in one or two short sentences. Ignore the classification " +
            "examples entirely — they are not answers. Never repeat the question back, never ask a " +
            "question of your own, and never use markdown.\n" +
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
