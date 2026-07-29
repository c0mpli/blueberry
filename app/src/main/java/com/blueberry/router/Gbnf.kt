package com.blueberry.router

/**
 * GBNF grammars for constrained decoding.
 *
 * This is the mechanism that makes small-model tool calling work at all. A 2B-class model asked
 * politely for JSON will produce malformed JSON some fraction of the time; a model decoding under a
 * grammar *cannot*, because the tokens that would break it are masked out before sampling. It is
 * also a large latency win — a constrained call is around 20 tokens instead of 60, and decode is
 * the bottleneck on a phone.
 *
 * Two grammars, matching the two-stage routing the design calls for:
 *
 *  1. [category] — a single word from a closed set. One cheap decision that narrows the tool list
 *     before the expensive call, because small models degrade sharply with tool count.
 *  2. [toolCall] — one alternative per tool with that tool's exact arguments inlined, so the tool
 *     name and its argument names cannot disagree. There is no production where they do.
 *
 * Pure Kotlin, so the generated grammars are unit tested without a model or a device.
 */
object Gbnf {

    /** Stage one: pick a category. */
    fun category(): String =
        "root ::= " + ToolCategory.entries.joinToString(" | ") { lit(it.name.lowercase()) } + "\n"

    /** Stage two: call exactly one of [tools], with exactly its own arguments. */
    fun toolCall(tools: List<ToolSpec>): String {
        require(tools.isNotEmpty()) { "a tool-call grammar needs at least one tool" }

        return buildString {
            append("root ::= ")
            append(tools.joinToString(" | ") { ruleName(it) })
            append("\n\n")

            for (tool in tools) {
                append(ruleName(tool)).append(" ::= ")
                append(lit("""{"tool":"${tool.name}","args":{"""))

                tool.args.forEachIndexed { index, arg ->
                    if (index > 0) append(' ').append(lit(","))
                    append(' ').append(lit("\"${arg.name}\":"))
                    append(' ').append(if (arg.type == ArgType.INT) "integer" else "string")
                }

                append(' ').append(lit("}}")).append('\n')
            }

            append('\n').append(VALUE_RULES)
        }
    }

    /** `open_app` -> `call-open-app`. GBNF rule names cannot contain underscores. */
    private fun ruleName(tool: ToolSpec): String = "call-" + tool.name.replace('_', '-')

    /** Wrap [text] as a GBNF string literal, escaping what GBNF cares about. */
    private fun lit(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * Lifted from llama.cpp's own `grammars/json.gbnf`. The control-character exclusion matters:
     * without it a model can emit a raw newline inside a string and produce JSON that satisfies the
     * grammar but not a JSON parser.
     */
    private val VALUE_RULES = """
        string ::= "\"" ( [^"\\\x7F\x00-\x1F] | "\\" (["\\bfnrt/] | "u" hex hex hex hex) )* "\""
        hex ::= [0-9a-fA-F]
        integer ::= "-"? ("0" | [1-9] [0-9]*)
    """.trimIndent() + "\n"
}
