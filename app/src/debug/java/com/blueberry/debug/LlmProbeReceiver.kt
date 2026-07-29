package com.blueberry.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.blueberry.data.AppCatalogue
import com.blueberry.llm.LlamaBridge
import com.blueberry.llm.LocalLlm
import com.blueberry.llm.ModelRepo
import com.blueberry.router.DefaultsStore
import com.blueberry.router.LlmOutcome
import com.blueberry.router.NoteSink
import com.blueberry.router.Prompt
import com.blueberry.router.RouteContext
import com.blueberry.router.SaveResult
import com.blueberry.router.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug-only probe for the on-device model.
 *
 * Inference is the one part of the app that cannot be exercised through the UI without speaking at
 * it, and it is also the part with the most to go wrong — a missing model file, a grammar that will
 * not parse, a KV state written by a different model. This runs the whole path from adb and logs
 * per-stage timings, which are the numbers the design's latency table is a hypothesis about.
 *
 * ```
 * adb shell am broadcast -a com.blueberry.PROBE_LLM \
 *   -n com.blueberry/com.blueberry.debug.LlmProbeReceiver -f 0x00000020 \
 *   --es text 'play sapphire'
 * adb logcat -s LlmProbe
 * ```
 */
class LlmProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val transcript = intent.getStringExtra("text") ?: "play sapphire"
        val repeats = intent.getIntExtra("repeats", 1)
        val app = context.applicationContext

        CoroutineScope(Dispatchers.Default).launch {
            try {
                probe(app, transcript, repeats)
            } catch (e: Throwable) {
                Log.e(TAG, "probe failed", e)
            }
        }
    }

    private suspend fun probe(context: Context, transcript: String, repeats: Int) {
        Log.i(TAG, "=========================================================")
        Log.i(TAG, "native library available: ${LlamaBridge.available}")
        if (!LlamaBridge.available) return

        val repo = ModelRepo(context)
        val preset = ModelRepo.Preset.FUNCTION_GEMMA_270M
        val status = repo.refresh(preset)
        Log.i(TAG, "model status: $status")
        val file = (status as? ModelRepo.Status.Ready)?.file ?: run {
            Log.e(TAG, "no model at ${repo.fileFor(preset).absolutePath}")
            return
        }

        val catalogue = AppCatalogue(context).refresh()
        Log.i(TAG, "catalogue: ${catalogue.size} apps, hash ${catalogue.hash}")

        val routeCtx = RouteContext(
            catalogue = catalogue,
            notes = NoteSink { SaveResult.Ok("probe") },
            defaults = DefaultsStore.inMemory(),
        )
        Log.i(TAG, "system prefix: ${Prompt.systemPrefix(routeCtx).length} chars")

        val llm = LocalLlm(
            modelFile = file,
            contextTokens = preset.contextTokens,
            stateDir = File(context.filesDir, "kv"),
        )

        val loadStart = SystemClock.elapsedRealtime()
        val loaded = llm.load()
        Log.i(TAG, "load: ${SystemClock.elapsedRealtime() - loadStart} ms (ok=$loaded, ${llm.lastError ?: "no error"})")
        if (!loaded) return
        Log.i(TAG, LlamaBridge.nativeSystemInfo().trim())

        repeat(repeats) { i ->
            val start = SystemClock.elapsedRealtime()
            val outcome = llm.route(transcript, routeCtx, Session())
            val elapsed = SystemClock.elapsedRealtime() - start
            // Turn 1 includes the cold prefill; every turn after it restores the saved KV state,
            // which is the whole point of the exercise. Watch the gap between them.
            Log.i(TAG, "turn ${i + 1}: ${elapsed} ms -> ${describe(outcome)}")
        }

        llm.close()
        Log.i(TAG, "=========================================================")
    }

    private fun describe(outcome: LlmOutcome): String = when (outcome) {
        is LlmOutcome.Call -> "CALL ${outcome.call.tool} ${outcome.call.args.mapValues { it.value.spoken }}"
        is LlmOutcome.Speak -> "SPEAK ${outcome.text.take(160)}"
        is LlmOutcome.Ask -> "ASK ${outcome.question}"
        is LlmOutcome.Error -> "ERROR ${outcome.reason}"
        LlmOutcome.Unavailable -> "UNAVAILABLE"
    }

    private companion object {
        const val TAG = "LlmProbe"
    }
}
