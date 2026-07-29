package com.blueberry.voice

import android.content.Context
import com.blueberry.debug.InjectedTranscriptSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge

/**
 * Debug builds listen to the microphone *and* to injected transcripts, so the router path can be
 * driven from a script on an emulator that has no working microphone.
 */
object TranscriptSourceFactory {
    fun create(context: Context): TranscriptSource = DebugTranscriptSource(PlatformSpeechSource(context))
}

private class DebugTranscriptSource(private val mic: TranscriptSource) : TranscriptSource {
    override val events: Flow<AsrEvent> = merge(mic.events, InjectedTranscriptSource.INSTANCE.events)
    override fun prewarm() = mic.prewarm()
    override fun start() = mic.start()
    override fun stop() = mic.stop()
    override fun release() = mic.release()
}
