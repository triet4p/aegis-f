package io.github.triet4p.aegisf.data.laya

import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.ClassificationEngine
import io.github.triet4p.aegisf.data.ClassificationEngineResult

/**
 * The local model's answer to one classification request.
 *
 * The engine is the only part of the classification path that touches the model. It asks
 * the process-owned inference lifecycle for one decision about the effective context it
 * is given and reports what came back; it never writes, never selects a category, and
 * never asks for a second decision.
 *
 * The lifecycle's own outcomes are reported honestly: a model that is not installed is
 * *unavailable* rather than a guess, a request that a newer one replaced is
 * *superseded*, and a runtime that is closed fails rather than pretending to answer.
 */
class LayaClassificationEngine(
    private val lifecycle: LayaInferenceLifecycle,
) : ClassificationEngine {
    override suspend fun classify(
        context: LayaEffectiveContext,
        categories: List<CategoryEntity>,
    ): ClassificationEngineResult = when (val outcome = lifecycle.suggest(context, categories)) {
        is LayaInferenceOutcome.Completed -> ClassificationEngineResult.Decided(outcome.value)
        is LayaInferenceOutcome.Unavailable -> ClassificationEngineResult.Unavailable(outcome.state)
        LayaInferenceOutcome.Superseded -> ClassificationEngineResult.Superseded
        LayaInferenceOutcome.Closed -> ClassificationEngineResult.Failed(
            "The local model runtime is closed.",
        )
    }
}
