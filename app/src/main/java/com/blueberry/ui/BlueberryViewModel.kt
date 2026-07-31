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
import com.blueberry.voice.Speaker
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

    /**
     * Built at startup, not per utterance: constructing a TextToSpeech binds to a remote service and
     * costs several hundred milliseconds.
     */
    private val speaker = Speaker(app).also { it.warmUp() }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _catalogue = MutableStateFlow(Catalogue.EMPTY)
    val catalogue: StateFlow<Catalogue> = _catalogue.asStateFlow()

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
        speaking = speaker.shouldSpeak()
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
        speaking = speaker.shouldSpeak()
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
        speakIfAppropriate(result)
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
        val MODEL_PRESET = ModelRepo.Preset.QWEN3_0_6B

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
