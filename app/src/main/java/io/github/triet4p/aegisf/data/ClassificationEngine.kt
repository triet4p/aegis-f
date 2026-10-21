package io.github.triet4p.aegisf.data

import io.github.triet4p.aegisf.data.laya.LayaContextDecision
import io.github.triet4p.aegisf.data.laya.LayaEffectiveContext
import io.github.triet4p.aegisf.data.laya.LayaRuntimeContract

/**
 * The bounded classifier the command boundary asks for a decision.
 *
 * The engine is a collaborator of the boundary, not a part of it: the boundary decides
 * whether the model is asked at all, and the engine only answers the question it is
 * asked. It is asked with the effective context the boundary composed and with the
 * categories that may be chosen, and it never writes anything.
 */
fun interface ClassificationEngine {
    /** Asks for one review-only decision about the given context. */
    suspend fun classify(
        context: LayaEffectiveContext,
        categories: List<CategoryEntity>,
    ): ClassificationEngineResult
}

/** What the classifier answered. */
sealed interface ClassificationEngineResult {
    /** The classifier produced a decision. */
    data class Decided(val decision: LayaContextDecision) : ClassificationEngineResult

    /** The local model is not installed, or cannot be used on this device. */
    data class Unavailable(val state: LayaRuntimeContract.State) : ClassificationEngineResult

    /** A newer request replaced this one before it was answered. */
    data object Superseded : ClassificationEngineResult

    /** The request could not be carried out. */
    data class Failed(val reason: String) : ClassificationEngineResult
}
