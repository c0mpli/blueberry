package com.blueberry.voice

/**
 * A seam for auditioning voices from adb, registered by the view model.
 *
 * It lives in `main` rather than `debug` only because `main` cannot see `debug` — the receiver that
 * drives it ships in debug builds alone, so in release this object exists and is never called.
 */
object VoiceAudition {

    @Volatile
    var handler: ((sid: Int, text: String, sweep: Boolean) -> Unit)? = null

    fun request(sid: Int, text: String, sweep: Boolean) {
        handler?.invoke(sid, text, sweep)
    }
}
