package com.blueberry.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grammar is what makes a small model structurally incapable of emitting a malformed tool call,
 * so a broken grammar is a silent correctness hole rather than a crash — llama.cpp would refuse to
 * build the sampler and the turn would just fail. These assert the generated text directly.
 */
class GbnfTest {

    @Test
    fun `category grammar offers every classifiable category`() {
        val expected = Gbnf.CLASSIFIABLE.joinToString(" | ") { "\"${it.name.lowercase()}\"" }
        assertEquals("root ::= $expected\n", Gbnf.category())
    }

    @Test
    fun `the model is never offered VISUAL`() {
        // "Is this better drawn than said" is a judgement a 0.6B gets wrong constantly — it
        // classified "hello" as visual and tried to chart it. LocalLlm decides that from an
        // explicit keyword instead, so the grammar must not let the model reach for it.
        assertFalse(Gbnf.CLASSIFIABLE.contains(ToolCategory.VISUAL))
        assertFalse(Gbnf.category().contains("visual"))
    }

    @Test
    fun `every category the grammar can emit is one the router understands`() {
        val emitted = Regex("\"([a-z]+)\"").findAll(Gbnf.category()).map { it.groupValues[1] }.toList()
        assertEquals(Gbnf.CLASSIFIABLE.size, emitted.size)
        for (word in emitted) {
            assertTrue("$word is not a ToolCategory", ToolCategory.entries.any { it.name.lowercase() == word })
        }
    }

    @Test
    fun `chat is the first alternative the classifier sees`() {
        // A small model leans on the first alternative when unsure, and chat is the safe default.
        assertTrue(Gbnf.category().startsWith("root ::= \"chat\""))
    }

    @Test
    fun `tool call grammar inlines each tool's own arguments`() {
        val g = Gbnf.toolCall(listOf(ToolSpecs.byName(Tools.OPEN_APP)!!))

        assertTrue(g, g.contains("root ::= call-open-app"))
        // The tool name and its argument name appear in one production, so they cannot disagree.
        assertTrue(g, g.contains("""call-open-app ::= "{\"tool\":\"open_app\",\"args\":{" "\"app\":" string "}}""""))
    }

    @Test
    fun `multiple tools become alternatives`() {
        val g = Gbnf.toolCall(ToolSpecs.inCategory(ToolCategory.ACTION))
        assertTrue(g, g.startsWith("root ::= call-open-app | call-play-media | call-navigate | call-search-web"))
    }

    @Test
    fun `rule names never contain underscores`() {
        // GBNF rule names are [a-zA-Z0-9-]; an underscore makes llama.cpp fail to parse the whole
        // grammar, which surfaces as "sampler could not be built" and nothing more specific.
        val g = Gbnf.toolCall(ToolSpecs.ALL)
        val ruleNames = Regex("^([a-z0-9-]+) ::=", RegexOption.MULTILINE).findAll(g).map { it.groupValues[1] }
        assertTrue(ruleNames.count() > 1)
        assertTrue(ruleNames.none { '_' in it })
    }

    @Test
    fun `string rule excludes raw control characters`() {
        // Without this a model can emit a literal newline inside a string: valid per a naive
        // grammar, invalid to every JSON parser.
        assertTrue(Gbnf.toolCall(ToolSpecs.ALL).contains("""[^"\\\x7F\x00-\x1F]"""))
    }

    @Test
    fun `every tool spec is buildable into a grammar`() {
        for (spec in ToolSpecs.ALL) {
            val g = Gbnf.toolCall(listOf(spec))
            assertTrue("${spec.name} produced no root", g.contains("root ::="))
            for (arg in spec.args) {
                assertTrue("${spec.name} lost arg ${arg.name}", g.contains("\\\"${arg.name}\\\":"))
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty tool list is rejected rather than producing an unmatchable grammar`() {
        Gbnf.toolCall(emptyList())
    }

    @Test
    fun `every registered tool has a spec and every spec has a builder`() {
        val registry = ToolRegistry.default()
        for (spec in ToolSpecs.ALL) {
            assertTrue("no builder for ${spec.name}", registry.has(spec.name))
        }
        for (name in registry.names) {
            assertTrue("no spec for $name", ToolSpecs.byName(name) != null)
        }
    }

    @Test
    fun `the categories that are wired have tools`() {
        // QUERY is deliberately absent for now: read_calendar / read_notes / read_notifications are
        // not built yet. An empty category is not a crash — LocalLlm falls through to the explain
        // path when a category yields no tools — but it must never be empty for a category the
        // classifier is likely to pick for an actionable request.
        assertFalse(ToolSpecs.inCategory(ToolCategory.ACTION).isEmpty())
        assertFalse(ToolSpecs.inCategory(ToolCategory.CAPTURE).isEmpty())
    }

    @Test
    fun `an empty category degrades instead of producing a broken grammar`() {
        // The guard that keeps the above from being a latent crash: building a grammar for an
        // empty tool list throws, so the caller is forced to handle it rather than pass "" to the
        // sampler and silently decode unconstrained.
        assertTrue(ToolSpecs.inCategory(ToolCategory.QUERY).isEmpty())
        runCatching { Gbnf.toolCall(ToolSpecs.inCategory(ToolCategory.QUERY)) }
            .onSuccess { error("expected an empty tool list to be rejected") }
    }
}
