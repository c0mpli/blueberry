package com.blueberry.llm

import com.blueberry.router.Llm
import com.blueberry.router.LlmOutcome
import com.blueberry.router.RouteContext
import com.blueberry.router.Session

/**
 * A slot the real model drops into once it has finished loading.
 *
 * The router is built at app start; the weights are ~253 MB and load asynchronously, and on first
 * run they may not be on the device at all. Rather than make [com.blueberry.router.Router] mutable
 * or block startup on a model, the router holds this and it starts out empty — so every turn before
 * the model is ready resolves through the seeded cache, and only genuinely novel phrasings report
 * that the model is not up yet.
 */
class SwappableLlm : Llm {

    @Volatile
    var delegate: Llm? = null

    val ready: Boolean get() = delegate != null

    override suspend fun route(transcript: String, ctx: RouteContext, session: Session): LlmOutcome =
        delegate?.route(transcript, ctx, session) ?: LlmOutcome.Unavailable
}
