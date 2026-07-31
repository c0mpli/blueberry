package com.blueberry.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blueberry.data.AppCatalogue
import com.blueberry.data.PrefsRepo
import com.blueberry.data.VaultRepo
import com.blueberry.router.CacheHit
import com.blueberry.router.Catalogue
import com.blueberry.router.PartialDecision
import com.blueberry.router.RouteContext
import com.blueberry.router.Router
import com.blueberry.router.RouterResult
import com.blueberry.router.Session
import com.blueberry.llm.LocalLlm
import com.blueberry.llm.ModelRepo
import com.blueberry.llm.SwappableLlm
import com.blueberry.tools.IntentFactory
import com.blueberry.voice.AsrEvent
import com.blueberry.voice.KokoroSpeaker
import com.blueberry.voice.Speaker
import com.blueberry.voice.SpeechPolicy
import com.blueberry.voice.SupertonicSpeaker
import com.blueberry.voice.TtsEngine
import com.blueberry.voice.VoiceAudition
import com.blueberry.voice.TranscriptSourceFactory
import com.blueberry.voice.TranscriptSource
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The loop, shared by every entry point.
 *
 * Nothing here knows whether it is running on the home screen, over the keyguard, or above another
 * app — that is the point of keeping the router pure, and this class inherits the property by only
 * ever handing it strings.
 */
class BlueberryViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = PrefsRepo(app, viewModelScope).also { it.load() }
    private val appCatalogue = AppCatalogue(app)
    private val vault = VaultRepo(app, prefs)
    private val intents = IntentFactory(app, appCatalogue)
    private val modelRepo = ModelRepo(app)

    /** Filled in once the weights are loaded; empty until then. */
    private val llmSlot = SwappableLlm()

    private val router = Router(llm = llmSlot)

    val modelStatus: StateFlow<ModelRepo.Status> = modelRepo.status

    /**
     * The microphone in release builds; the microphone plus adb-injected transcripts in debug ones.
     * Which it is, is decided by source set — see `voice/TranscriptSourceFactory.kt`.
     */
    private val transcripts: TranscriptSource = TranscriptSourceFactory.create(app)

    private val policy = SpeechPolicy(app)

    /**
     * Kokoro when its weights are on the device, the platform engine otherwise.
     *
     * Built at startup either way: constructing a TextToSpeech binds to a remote service, and
     * loading Kokoro reads 113 MB of int8 weights — neither belongs on the critical path.
     */
    private val speaker: TtsEngine = run {
        // Created here so the directories are owned by the app: one made by `adb push` belongs to
        // shell, and the app then cannot read into it.
        val ttsRoot = File(app.getExternalFilesDir(null), "tts").apply { mkdirs() }
        val kokoroDir = File(ttsRoot, "kokoro-int8-multi-lang-v1_0").apply { mkdirs() }
        val supertonicDir = File(ttsRoot, "supertonic-3").apply { mkdirs() }

        val engine: TtsEngine = when {
            ENGINE == TtsChoice.SUPERTONIC && SupertonicSpeaker.isInstalled(supertonicDir) ->
                SupertonicSpeaker(supertonicDir)
            ENGINE == TtsChoice.KOKORO && KokoroSpeaker.isInstalled(kokoroDir) ->
                KokoroSpeaker(kokoroDir)
            // Always available, needs no download, sounds like a satnav.
            else -> Speaker(app)
        }
        Log.i(TAG, "speech engine: ${engine.name}")
        engine.also { it.warmUp() }
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _catalogue = MutableStateFlow(Catalogue.EMPTY)
    val catalogue: StateFlow<Catalogue> = _catalogue.asStateFlow()

    private val _conversation = MutableStateFlow<List<Exchange>>(emptyList())

    /** Recent turns, oldest first. A companion should remember the last thing that was said. */
    val conversation: StateFlow<List<Exchange>> = _conversation.asStateFlow()

    private val _screen = MutableStateFlow(Screen.HOME)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private var session = Session()
    private var stabilityJob: Job? = null
    private var lastPartial: String = ""
    private var catalogueCallback: android.content.pm.LauncherApps.Callback? = null
    private var local: LocalLlm? = null

    /** True while this turn is allowed to make noise. Decided once, at the start of the turn. */
    private var speaking = false

    /** Set when the partial gate started routing speculatively, so the final does not redo it. */
    private var speculated: String? = null

    init {
        viewModelScope.launch { refreshCatalogue() }
        catalogueCallback = appCatalogue.observeChanges {
            viewModelScope.launch { refreshCatalogue() }
        }
        viewModelScope.launch {
            transcripts.events.collect(::onAsrEvent)
        }
        viewModelScope.launch(Dispatchers.IO) { prepareModel() }

        // Debug builds can audition voices over adb; in release nothing ever calls this.
        VoiceAudition.handler = { sid, text, sweep -> audition(sid, text, sweep) }
    }

    /**
     * Speak [text] in one voice, or sweep a spread of them announcing each by number.
     *
     * Which of 53 voices sounds most human is a listening decision, so this makes hearing them cost
     * a broadcast instead of a rebuild.
     */
    private fun audition(sid: Int, text: String, sweep: Boolean) {
        val kokoro = speaker as? KokoroSpeaker ?: run {
            Log.w(TAG, "audition ignored: engine is ${speaker.name}")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val voices = if (sweep) SWEEP_VOICES else listOf(sid.takeIf { it >= 0 } ?: kokoro.speakerId)
            for (v in voices) {
                kokoro.speakerId = v
                kokoro.say("Voice $v. $text")
                // Serial, so they can be told apart; synthesis is roughly realtime.
                delay(AUDITION_GAP_MS)
            }
        }
    }

    /**
     * Bring the on-device model up, without ever blocking the interaction path. Blueberry has to be
     * usable on the seeded resolution cache alone while this runs — that is the entire reason the
     * cache exists.
     */
    private suspend fun prepareModel() {
        val preset = MODEL_PRESET
        var status = modelRepo.refresh(preset)

        if (status !is ModelRepo.Status.Ready) {
            // First run downloads the weights, but only on an unmetered connection — 253 MB is not
            // something to spend on someone's mobile data without asking.
            if (!unmetered()) {
                Log.i(TAG, "model absent and the connection is metered; leaving it for settings")
                return
            }
            status = modelRepo.download(preset)
        }

        val file = (status as? ModelRepo.Status.Ready)?.file ?: return
        val llm = LocalLlm(
            modelFile = file,
            contextTokens = preset.contextTokens,
            stateDir = File(getApplication<Application>().filesDir, "kv"),
            template = preset.template,
        )
        if (llm.load()) {
            // Show the answer as it is produced. A phone decodes fast enough to read along with,
            // and waiting for the last token before showing the first is what made this feel hung.
            llm.onPartialAnswer = { partial ->
                if (partial.isNotBlank()) {
                    _uiState.value = UiState.Done(RouterResult.Answer(partial), fired = false)
                    // Speak each sentence the moment it completes rather than waiting for the last
                    // token — first audio lands a sentence after the first token, not an answer later.
                    if (speaking) speaker.speakStreaming(partial)
                }
            }
            llmSlot.delegate = llm
            local = llm
            Log.i(TAG, "on-device model ready")
            llm.warmPrefix(context())
        } else {
            Log.w(TAG, "model failed to load: ${llm.lastError}")
        }
    }

    private fun unmetered(): Boolean {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Exposed so settings can report status and trigger a download by hand. */
    fun downloadModel() {
        viewModelScope.launch(Dispatchers.IO) { prepareModel() }
    }

    private fun refreshCatalogue() {
        val next = appCatalogue.refresh()
        _catalogue.value = next
        // A cached open_app for something uninstalled must not survive.
        router.onCatalogueChanged(next.hash)
    }

    private fun context(): RouteContext = RouteContext(
        catalogue = _catalogue.value,
        notes = vault,
        defaults = prefs,
    )

    // -------------------------------------------------------------------------------------
    // Input
    // -------------------------------------------------------------------------------------

    /** Tap-down. Bind the recogniser while the finger is still travelling. */
    fun onPressStart() {
        transcripts.prewarm()
    }

    /** Tap-up. The surface is already showing "listening" by the time this returns. */
    fun onPressComplete() {
        session = Session()
        lastPartial = ""
        speculated = null
        // Barge-in: a new turn silences the old answer immediately, before the mic even opens.
        speaker.stop()
        speaking = policy.shouldSpeak()
        _uiState.value = UiState.Listening("", 0f)
        transcripts.start()
    }

    fun onDismiss() {
        stabilityJob?.cancel()
        speaker.stop()
        transcripts.stop()
        session.reset()
        _uiState.value = UiState.Idle
    }

    fun openDrawer() {
        _screen.value = Screen.DRAWER
    }

    fun closeDrawer() {
        _screen.value = Screen.HOME
    }

    /** Home pressed while already home. Reset to idle rather than doing nothing. */
    fun onHomeReentered() {
        closeDrawer()
        onDismiss()
        // Pressing home ends the session, and the design says nothing persists across sessions.
        _conversation.value = emptyList()
    }

    fun icon(packageName: String) = appCatalogue.icon(packageName)

    fun launchApp(packageName: String) {
        if (!appCatalogue.launch(packageName)) {
            _uiState.value = UiState.Done(RouterResult.Failed("Couldn't open that."), fired = false)
        }
    }

    // -------------------------------------------------------------------------------------
    // The loop
    // -------------------------------------------------------------------------------------

    private fun onAsrEvent(event: AsrEvent) {
        when (event) {
            is AsrEvent.Listening -> _uiState.value = UiState.Listening("", 0f)

            is AsrEvent.Level -> {
                val state = _uiState.value
                if (state is UiState.Listening) _uiState.value = state.copy(level = event.rms)
            }

            is AsrEvent.Partial -> onPartial(event.text)

            is AsrEvent.Final -> onFinal(event.text)

            is AsrEvent.Failed -> {
                stabilityJob?.cancel()
                _uiState.value = UiState.Done(RouterResult.Failed(event.reason), fired = false)
            }

            AsrEvent.Stopped -> if (_uiState.value is UiState.Listening) _uiState.value = UiState.Idle
        }
    }

    /**
     * Runs on every partial update, several times a second. A cache lookup is cheap enough for
     * that, which is exactly why this can fire before endpointing rather than after it.
     */
    private fun onPartial(text: String) {
        val state = _uiState.value
        if (state is UiState.Listening) _uiState.value = state.copy(partial = text)

        if (text == lastPartial) return
        lastPartial = text

        val ctx = context()
        when (val decision = router.preRouter.onPartial(text, ctx, unchangedForMs = 0L)) {
            is PartialDecision.Fire -> {
                stabilityJob?.cancel()
                transcripts.stop()
                dispatch(text, decision.hit, ctx)
            }

            PartialDecision.Wait -> armStabilityTimer(text, ctx)

            // Nothing seeded can match, so this turn belongs to the model. Rather than wait out
            // the recogniser's ~1s silence timeout, start inference as soon as the partial stops
            // changing. On-device there is no quota to burn and no request to waste — a discarded
            // decode costs a few hundred milliseconds of CPU and nothing else.
            PartialDecision.Miss -> armSpeculativeRoute(text, ctx)
        }
    }

    /**
     * A partial that is plausible but not certain fires once the transcript stops changing. Nothing
     * re-evaluates it on its own — no further ASR event arrives while the user is silent — so the
     * re-check has to be scheduled.
     */
    private fun armStabilityTimer(text: String, ctx: RouteContext) {
        stabilityJob?.cancel()
        stabilityJob = viewModelScope.launch {
            delay(STABILITY_MS)
            if (lastPartial != text) return@launch
            val decision = router.preRouter.onPartial(text, ctx, unchangedForMs = STABILITY_MS)
            if (decision is PartialDecision.Fire) {
                transcripts.stop()
                dispatch(text, decision.hit, ctx)
            }
        }
    }

    /**
     * Route on a settled partial, before the recogniser has decided the user stopped talking.
     * [speculated] records what was routed so the final transcript does not run the same turn twice.
     */
    private fun armSpeculativeRoute(text: String, ctx: RouteContext) {
        stabilityJob?.cancel()
        stabilityJob = viewModelScope.launch {
            delay(SPECULATE_AFTER_MS)
            if (lastPartial != text || speculated == text) return@launch
            speculated = text
            runTurn(text, ctx)
        }
    }

    private fun onFinal(text: String) {
        stabilityJob?.cancel()
        // The partial gate may already have fired this turn.
        if (_uiState.value is UiState.Done) return
        // Speculative routing already started this exact utterance; let it finish.
        if (speculated == text) return
        viewModelScope.launch { runTurn(text, context()) }
    }

    private suspend fun runTurn(text: String, ctx: RouteContext) {
        remember(Exchange(fromUser = true, text = text))
        speaking = policy.shouldSpeak()
        Log.i(TAG, "turn \"$text\" speaking=$speaking")
        _uiState.value = UiState.Thinking(text)
        run {
            val result = withTimeoutOrNull(MODEL_TIMEOUT_MS) { router.route(text, ctx, session) }
                ?: RouterResult.Failed("That took too long — try again, or use the app drawer.")
            session.record(Session.Turn(text, result))
            // A streamed answer has already been shown token by token; replacing it with the same
            // text would flicker the card.
            if (result is RouterResult.Answer && _uiState.value.let { it is UiState.Done && it.result is RouterResult.Answer }) {
                // Already streamed to the card; just settle on the final text and speak whatever
                // sentence fragment came after the last boundary.
                _uiState.value = UiState.Done(result, fired = false)
                speakIfAppropriate(result)
            } else {
                complete(text, result, ctx)
            }
        }
    }

    private fun dispatch(transcript: String, hit: CacheHit, ctx: RouteContext) {
        val result = router.execute(hit, ctx)
        session.record(Session.Turn(transcript, result))
        // Write through on confirm, and firing an action the user asked for *is* the confirmation
        // for a cache hit that came from a seed.
        if (result !is RouterResult.Failed) {
            router.confirm(transcript, hit.call, ctx.catalogue.hash)
        }
        complete(transcript, result, ctx)
    }

    /**
     * What gets spoken, and what does not. Restraint is the difference between a conversation and an
     * assistant that narrates: a clarification has to be heard, an answer is worth hearing, and
     * "opening Spotify" is just noise in front of the thing you asked for.
     */
    private fun speakIfAppropriate(result: RouterResult) {
        if (!speaking) return
        when (result) {
            is RouterResult.Clarify -> speaker.say(result.question)
            is RouterResult.Answer -> speaker.finish(result.text)
            is RouterResult.Visual -> speaker.say(result.narration)
            // Action, Saved and Failed are all faster to see than to hear.
            else -> Unit
        }
    }

    private fun complete(transcript: String, result: RouterResult, ctx: RouteContext) {
        var fired = false
        if (result is RouterResult.Action) {
            fired = intents.fire(result.spec)
            if (!fired) {
                Log.w(TAG, "nothing handled ${result.spec}")
                _uiState.value = UiState.Done(
                    RouterResult.Failed("Nothing on this phone can do that."),
                    fired = false,
                )
                return
            }
        }
        _uiState.value = UiState.Done(result, fired)
        remember(Exchange(fromUser = false, text = describe(result)))
        speakIfAppropriate(result)
    }

    /** What the companion "said", for the transcript. */
    private fun describe(result: RouterResult): String = when (result) {
        is RouterResult.Answer -> result.text
        is RouterResult.Action -> result.label
        is RouterResult.Saved -> "Saved — ${result.text}"
        is RouterResult.Clarify -> result.question
        is RouterResult.Visual -> result.narration
        is RouterResult.Failed -> result.reason
    }

    private fun remember(exchange: Exchange) {
        if (exchange.text.isBlank()) return
        // Bounded: this is a companion's short-term memory on screen, not a chat log. Sessions end
        // and nothing persists across them.
        _conversation.value = (_conversation.value + exchange).takeLast(MAX_VISIBLE_TURNS)
    }

    override fun onCleared() {
        catalogueCallback?.let { appCatalogue.stopObserving(it) }
        local?.close()
        speaker.release()
        transcripts.release()
        super.onCleared()
    }

    companion object {
        private const val TAG = "Blueberry"
        const val STABILITY_MS = 300L

        /**
         * How long a non-matching partial must hold still before the model is started on it.
         * Shorter than the platform's ~1s endpointing, which is the whole point.
         */
        const val SPECULATE_AFTER_MS = 350L

        /** A spread across Kokoro's English female and male voices, not every one of the 53. */
        val SWEEP_VOICES = listOf(0, 2, 3, 6, 9, 11, 14)
        const val AUDITION_GAP_MS = 4_000L

        /** Enough to feel continuous, few enough to stay glanceable. */
        const val MAX_VISIBLE_TURNS = 6
        val MODEL_PRESET = ModelRepo.Preset.QWEN3_0_6B

        /**
         * Which neural voice to use, or PLATFORM for none.
         *
         * Kokoro wins on English naturalness by a wide margin in listener comparisons, and af_bella
         * was picked here by ear. Supertonic is the counterweight: less natural by its own maker's
         * admission, but built for speed, half the size, and reported to handle language mixing
         * mid-sentence — which is the Hindi/English case Kokoro has no mechanism for.
         *
         * Historical note kept because it cost a lot to establish: Kokoro loads and reports 53
         * speakers at 24 kHz, but was once suspected of
         * actually measured on a clean device.
         *
         * It was briefly blamed for an LLM prefill regression (1380ms -> ~15000ms for the same 47
         * tokens). That was wrong on four counts: the regression persisted with Kokoro gated off,
         * with its files deleted from the device, with the CPU governor forced to 1.78GHz, and it
         * then disappeared on its own with no code change at all (back to 1091ms) once the handset
         * was no longer under sustained load. It was device state, not a second model.
         */
        // Kokoro, af_bella. Chosen by listening: Supertonic is about twice as fast and less than
        // half the size, but its voice was rejected on the phone — which matches both the listener
        // evidence and its own maker's statement that naturalness was not the objective.
        val ENGINE = TtsChoice.KOKORO

        /** Includes the one-off cold prefill on the first turn after a catalogue change. */
        const val MODEL_TIMEOUT_MS = 20_000L
    }
}

enum class Screen { HOME, DRAWER }

sealed interface UiState {
    /** App name, faint. Whole surface tappable. */
    data object Idle : UiState

    /** Amplitude-reactive indicator, partial transcript in large type as it arrives. */
    data class Listening(val partial: String, val level: Float) : UiState

    /** Transcript settled, indeterminate indicator. */
    data class Thinking(val transcript: String) : UiState

    /** Confirmation card, or answer text, or a tick. */
    data class Done(val result: RouterResult, val fired: Boolean) : UiState
}

/** Which speech engine to use. Swapped by rebuild for now; belongs in settings later. */
enum class TtsChoice { PLATFORM, KOKORO, SUPERTONIC }

/** One line of the visible conversation. */
data class Exchange(val fromUser: Boolean, val text: String)
