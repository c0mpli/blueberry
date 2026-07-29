package com.blueberry.router

/**
 * Everything the router is allowed to decide.
 *
 * This file — and everything else in this package — is pure Kotlin. No `android.*`, no
 * `androidx.*`. The router describes what should happen; the Android layer is what makes it
 * happen. That boundary is enforced by [com.blueberry.router.RouterPurityTest].
 */
sealed interface RouterResult {

    /** True when firing this result needs the device unlocked. Capture is free; acting is not. */
    val requiresUnlock: Boolean

    /** Build, confirm, fire, done. */
    data class Action(
        val spec: ActionSpec,
        val label: String,
        override val requiresUnlock: Boolean = true,
    ) : RouterResult

    /** Ask, reopen the mic, continue. [pending] carries enough state to resume the turn. */
    data class Clarify(
        val question: String,
        val options: List<ClarifyOption>,
        val pending: PendingCall,
        override val requiresUnlock: Boolean = false,
    ) : RouterResult

    /** Speak the first sentences, show the rest. */
    data class Answer(
        val text: String,
        override val requiresUnlock: Boolean = false,
    ) : RouterResult

    /** Render in the canvas, narrate alongside. Degrades to [Answer] on the lock screen. */
    data class Visual(
        val title: String,
        val chart: ChartSpec,
        val narration: String,
        override val requiresUnlock: Boolean = true,
    ) : RouterResult

    /** Written to the vault. A tick is faster than speech. */
    data class Saved(
        val text: String,
        val target: String,
        override val requiresUnlock: Boolean = false,
    ) : RouterResult

    /** Show it, offer the app drawer. */
    data class Failed(
        val reason: String,
        override val requiresUnlock: Boolean = false,
    ) : RouterResult
}

/**
 * One tappable chip in a [RouterResult.Clarify].
 *
 * [defaultKey] is what makes "it never asks again" work: when the user picks this option, the
 * pair ([defaultKey], [defaultValue]) is written to the defaults store, per category rather than
 * per phrase. A null [defaultKey] means this choice is a one-off and must not be remembered.
 */
data class ClarifyOption(
    val label: String,
    val value: String,
    val defaultKey: String? = null,
    val defaultValue: String? = null,
)

/** A tool call the router could not complete, held open across a clarification. */
data class PendingCall(
    val tool: String,
    val args: Map<String, String>,
    /** The argument the clarification is trying to fill. */
    val missingArg: String,
)

/** The fixed set of shapes the canvas can draw. The model picks one and supplies data. */
data class ChartSpec(
    val kind: ChartKind,
    val series: List<ChartSeries>,
    val labels: List<String> = emptyList(),
)

enum class ChartKind { BAR, LINE, TIMELINE, TABLE, STEPS, GRAPH }

data class ChartSeries(val name: String, val values: List<Double>)
