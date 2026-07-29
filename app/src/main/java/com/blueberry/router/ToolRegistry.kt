package com.blueberry.router

/** A tool call, produced either by the resolution cache or by the model. */
data class ToolCall(val tool: String, val args: Map<String, SlotValue>) {
    operator fun get(name: String): SlotValue? = args[name]
}

/**
 * One flat registry. How a tool builds its action is its own business — some construct a platform
 * intent, some launch a shortcut, some fire a URI, some do their work in-process and return
 * [RouterResult.Saved]. The model sees a single list.
 *
 * Adding a tool is adding an entry here and a builder function. There is deliberately no registry
 * file format and no JSON asset to hold hypothetical future ones.
 */
class ToolRegistry(private val tools: Map<String, Tool>) {

    val names: Set<String> get() = tools.keys

    fun has(name: String): Boolean = name in tools

    fun build(call: ToolCall, ctx: RouteContext): RouterResult {
        val tool = tools[call.tool]
            ?: return RouterResult.Failed("No tool called ${call.tool}.")
        return tool.build(call, ctx)
    }

    companion object {
        /** The tools wired for the first milestones. */
        fun default(): ToolRegistry = ToolRegistry(
            mapOf(
                Tools.OPEN_APP to Tool(::buildOpenApp),
                Tools.SAVE_NOTE to Tool(::buildSaveNote),
                Tools.PLAY_MEDIA to Tool(::buildPlayMedia),
                Tools.NAVIGATE to Tool(::buildNavigate),
                Tools.SEARCH_WEB to Tool(::buildSearchWeb),
                Tools.SHOW_CHART to Tool(::buildShowChart),
            )
        )
    }
}

fun interface Tool {
    fun build(call: ToolCall, ctx: RouteContext): RouterResult
}

object Tools {
    const val OPEN_APP = "open_app"
    const val SAVE_NOTE = "save_note"
    const val PLAY_MEDIA = "play_media"
    const val NAVIGATE = "navigate"
    const val SEARCH_WEB = "search_web"
    const val SHOW_CHART = "show_chart"
}

/** Platform action strings, spelled out here so the router never imports `android.content`. */
object AndroidActions {
    const val VIEW = "android.intent.action.VIEW"
    const val WEB_SEARCH = "android.intent.action.WEB_SEARCH"
    const val MEDIA_PLAY_FROM_SEARCH = "android.media.action.MEDIA_PLAY_FROM_SEARCH"
    const val EXTRA_QUERY = "query"
}

// ---------------------------------------------------------------------------------------------
// Builders
// ---------------------------------------------------------------------------------------------

private fun buildOpenApp(call: ToolCall, ctx: RouteContext): RouterResult =
    when (val app = call["app"]) {
        is SlotValue.App ->
            RouterResult.Action(
                spec = ActionSpec.OpenApp(app.entry.packageName, app.entry.label),
                label = "Open ${app.entry.label}",
            )

        is SlotValue.AmbiguousApp ->
            RouterResult.Clarify(
                question = "Which ${app.spoken}?",
                options = app.candidates.map {
                    ClarifyOption(label = it.label, value = it.packageName)
                },
                pending = PendingCall(Tools.OPEN_APP, mapOf("app" to app.spoken), missingArg = "app"),
            )

        is SlotValue.UnknownApp ->
            RouterResult.Failed("No app called ${app.spoken}.")

        else -> RouterResult.Failed("open_app needs an app.")
    }

private fun buildSaveNote(call: ToolCall, ctx: RouteContext): RouterResult {
    val text = call["text"]?.spoken?.trim().orEmpty()
    if (text.isEmpty()) return RouterResult.Failed("Nothing to note down.")
    return when (val outcome = ctx.notes.append(text)) {
        is SaveResult.Ok -> RouterResult.Saved(text, outcome.target)
        is SaveResult.Failed -> RouterResult.Failed(outcome.reason)
    }
}

private fun buildPlayMedia(call: ToolCall, ctx: RouteContext): RouterResult {
    val query = call["query"]?.spoken?.trim().orEmpty()
    if (query.isEmpty()) return RouterResult.Failed("Play what?")

    // One intent covers every music app on the phone. Only narrow it to a package when the user
    // has already chosen a default — otherwise let Android ask, which it does better than we would.
    val preferred = ctx.defaults.get(DefaultKeys.MUSIC)
    val label = preferred?.let { ctx.catalogue.byPackage(it)?.label }
    return RouterResult.Action(
        spec = ActionSpec.Launch(
            action = AndroidActions.MEDIA_PLAY_FROM_SEARCH,
            packageName = preferred,
            extras = listOf(Extra.Text(AndroidActions.EXTRA_QUERY, query)),
        ),
        label = if (label != null) "Play $query on $label" else "Play $query",
    )
}

private fun buildNavigate(call: ToolCall, ctx: RouteContext): RouterResult {
    val place = call["place"]?.spoken?.trim().orEmpty()
    if (place.isEmpty()) return RouterResult.Failed("Navigate where?")
    return RouterResult.Action(
        spec = ActionSpec.Launch(action = AndroidActions.VIEW, uri = "google.navigation:q=${encode(place)}"),
        label = "Navigate to $place",
    )
}

/**
 * The canvas. The model picks a shape and supplies data; Blueberry draws it.
 *
 * Deliberately not "emit HTML": that would be a thousand-plus tokens of markup, a minute of decode
 * on a phone, frequently malformed, and impossible to constrain with a grammar. Emitting ~30 tokens
 * of data into a fixed template is short, grammar-constrainable, and can never render broken.
 *
 * The trade is that arbitrary custom visuals are gone — six shapes done well instead of infinite
 * shapes done unreliably.
 */
private fun buildShowChart(call: ToolCall, ctx: RouteContext): RouterResult {
    val kindName = call["kind"]?.spoken?.trim()?.uppercase().orEmpty()
    val kind = ChartKind.entries.firstOrNull { it.name == kindName } ?: ChartKind.BAR
    val title = call["title"]?.spoken?.trim().orEmpty().ifEmpty { "Chart" }

    val labels = call["labels"]?.spoken.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val values = call["values"]?.spoken.orEmpty().split(',').mapNotNull { it.trim().toDoubleOrNull() }
    if (values.isEmpty()) return RouterResult.Failed("I couldn't draw that.")

    return RouterResult.Visual(
        title = title,
        chart = ChartSpec(kind = kind, series = listOf(ChartSeries(title, values)), labels = labels),
        narration = call["narration"]?.spoken?.trim().orEmpty().ifEmpty { title },
    )
}

private fun buildSearchWeb(call: ToolCall, ctx: RouteContext): RouterResult {
    val query = call["query"]?.spoken?.trim().orEmpty()
    if (query.isEmpty()) return RouterResult.Failed("Search for what?")
    return RouterResult.Action(
        spec = ActionSpec.Launch(
            action = AndroidActions.WEB_SEARCH,
            extras = listOf(Extra.Text(AndroidActions.EXTRA_QUERY, query)),
        ),
        label = "Search for $query",
    )
}

/**
 * Percent-encoding for URI query values. Hand-rolled because `java.net.URLEncoder` encodes spaces
 * as `+`, which `google.navigation:` and `upi://` both mishandle.
 */
internal fun encode(value: String): String {
    val out = StringBuilder(value.length + 8)
    for (byte in value.encodeToByteArray()) {
        val c = byte.toInt().toChar()
        if (c.isLetterOrDigit() && byte.toInt() in 0..127 || c in "-_.~") {
            out.append(c)
        } else {
            out.append('%').append(HEX[(byte.toInt() shr 4) and 0xF]).append(HEX[byte.toInt() and 0xF])
        }
    }
    return out.toString()
}

private const val HEX = "0123456789ABCDEF"
