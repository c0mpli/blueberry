package com.blueberry.router

/**
 * The router's ports. Every one of these is implemented twice: once for real in the Android layer,
 * once as a fake in the tests. Nothing in this file knows that Android exists.
 */

/** Where captures go. The Android implementation writes to the Obsidian vault over SAF. */
fun interface NoteSink {
    fun append(text: String): SaveResult
}

sealed interface SaveResult {
    /** [target] is shown to the user — "Inbox.md", "Daily/2026-07-29.md", "local notes". */
    data class Ok(val target: String) : SaveResult
    data class Failed(val reason: String) : SaveResult
}

/**
 * Learned choices, per category rather than per phrase. Settings and the clarification loop write
 * to the same store; there is no second mechanism.
 */
interface DefaultsStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun clear(key: String)

    companion object {
        /** Backing for tests and for the seeded state before settings has ever been opened. */
        fun inMemory(initial: Map<String, String> = emptyMap()): DefaultsStore =
            object : DefaultsStore {
                private val map = LinkedHashMap(initial)
                override fun get(key: String): String? = map[key]
                override fun put(key: String, value: String) { map[key] = value }
                override fun clear(key: String) { map.remove(key) }
            }
    }
}

/** Keys for [DefaultsStore]. Categories, not phrases — "play X" asks once, ever. */
object DefaultKeys {
    const val MUSIC = "app.music"
    const val MESSAGING = "app.messaging"
    const val NAVIGATION = "app.navigation"
    const val BROWSER = "app.browser"
    const val REMIND_AT_TIME = "intent.remind_at_time"
    const val REMIND_ON_DATE = "intent.remind_on_date"
    const val REMIND_NO_TIME = "intent.remind_no_time"
}

/**
 * The local model. Kept behind an interface from day one — v1 ships the on-device implementation,
 * and a remote one taking a base URL and a bearer token drops in later without touching anything
 * else. See the "premium, parked" section of the design.
 */
interface Llm {
    suspend fun route(transcript: String, ctx: RouteContext, session: Session): LlmOutcome

    /** Before the weights have downloaded, and in unit tests. */
    object Unavailable : Llm {
        override suspend fun route(transcript: String, ctx: RouteContext, session: Session) =
            LlmOutcome.Unavailable
    }
}

sealed interface LlmOutcome {
    data class Call(val call: ToolCall) : LlmOutcome
    data class Speak(val text: String) : LlmOutcome
    data class Ask(val question: String, val options: List<ClarifyOption>, val pending: PendingCall) : LlmOutcome
    data object Unavailable : LlmOutcome
    data class Error(val reason: String) : LlmOutcome
}

/** Everything a route needs. Assembled per turn; cheap to build. */
class RouteContext(
    val catalogue: Catalogue,
    val notes: NoteSink,
    val defaults: DefaultsStore = DefaultsStore.inMemory(),
    val contacts: ContactResolver = ContactResolver.NONE,
) {
    val matchContext: MatchContext = MatchContext(catalogue, contacts)
}

/**
 * One continuous exchange. Sessions end on home press, card dismiss, lock activity finish, or 60s
 * idle; nothing persists across them.
 */
class Session(
    /** Cap at three clarifications, then give up and open the app. */
    val maxClarifications: Int = 3,
) {
    private val turns = ArrayList<Turn>()
    var clarifications: Int = 0
        private set

    val history: List<Turn> get() = turns

    fun record(turn: Turn) {
        turns.add(turn)
        if (turn.result is RouterResult.Clarify) clarifications++
    }

    fun clarificationBudgetSpent(): Boolean = clarifications >= maxClarifications

    fun reset() {
        turns.clear()
        clarifications = 0
    }

    data class Turn(val transcript: String, val result: RouterResult)
}
