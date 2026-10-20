package io.github.triet4p.aegisf.data.laya

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import io.github.triet4p.aegisf.data.CategoryEntity
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream

/**
 * The on-device English Laya decision runtime: one ONNX Runtime session over the pinned
 * graph plus the exact tokenizer/sequence/calibration pipeline and S4.4's stable-id candidate
 * planning. Suggestions are review-only; current category rows must be resolved before display.
 *
 * Threading: production requests are confined by [LayaInferenceLifecycle] to one worker.
 * A process-wide ORT lock also protects session calls, open/probe, and close from competing
 * verifier sessions that share ORT's environment.
 *
 * Verification order in [open]: bundle presence, `laya_config.json` budget pins,
 * tokenizer asset load, session creation, then a single real probe inference that also
 * guards the input/output contract (5 inputs, `logits`/`act_probs` outputs). A failure at
 * any step reports [LayaRuntimeContract.State.CorruptOrIncompatible] with the measured
 * cause instead of a suggestion.
 */
class LayaRuntime private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    private val tokenizer: LayaTokenizer,
    val maxLen: Int,
    val headMaxLen: Int,
) : Closeable {


    /**
     * Produces a review-only suggestion from current category metadata. Explicit exact-description
     * corrections outrank exact metadata matches, which outrank model inference. Oversized sets
     * use a deterministic metadata-ranked shortlist; callers must resolve the result against fresh
     * category rows before displaying or applying it.
     *
     * The request's effective context is composed first, under the model's actual input budget,
     * and the result says what was composed: the text the model saw, the stored readings that
     * were part of it, and the ones that did not fit.
     *
     * A composed input that does not fit the room the model leaves for its state is refused
     * before any model work: the decision is
     * [LayaCandidatePlanner.ManualReason.EFFECTIVE_INPUT_OVER_BUDGET], no evaluation is
     * produced, and the text is not shortened to make it fit.
     */
    fun suggest(
        context: LayaEffectiveContext,
        categories: List<CategoryEntity>,
        correctionRules: List<LayaCategoryCorrectionRule> = emptyList(),
    ): LayaContextDecision = synchronized(ORT_ACCESS_LOCK) {
        val effectiveInput = LayaEffectiveContextComposer.compose(
            tokenizer = tokenizer,
            context = context,
            maxLen = maxLen,
            headMaxLen = headMaxLen,
        )
        if (!effectiveInput.fitsRoom) {
            // The composed input does not fit the room the model leaves for its state: the
            // request is refused here, before any candidate set is planned and before the
            // model is asked. The text stays exactly as it was composed.
            return@synchronized LayaContextDecision(
                decision = LayaCandidatePlanner.Decision.Unknown(
                    LayaCandidatePlanner.ManualReason.EFFECTIVE_INPUT_OVER_BUDGET,
                ),
                effectiveInput = effectiveInput,
                evaluation = null,
            )
        }
        val plan = LayaCandidatePlanner.plan(
            tokenizer = tokenizer,
            description = effectiveInput.text,
            categories = categories,
            correctionRules = correctionRules,
            maxLen = maxLen,
            headMaxLen = headMaxLen,
            descriptionTokenCount = effectiveInput.tokenCount,
        )
        val decision = LayaCandidatePlanner.decide(plan) { built -> runLogits(built) }
        LayaContextDecision(
            decision = decision,
            effectiveInput = effectiveInput,
            evaluation = evaluationOf(plan, decision),
        )
    }

    /**
     * The model evaluation of one decision: the categories the model evaluated, in the order it
     * evaluated them, with their calibrated shares, the calibrated no-fit share, and the one
     * Jev sharpness value of the same distribution.
     *
     * A direct decision never ran the model, so it has no evaluation and therefore no share
     * and no sharpness.
     */
    private fun evaluationOf(
        plan: LayaCandidatePlanner.Plan,
        decision: LayaCandidatePlanner.Decision,
    ): LayaEvaluation? {
        val model = plan as? LayaCandidatePlanner.Plan.Model ?: return null
        val scores = when (decision) {
            is LayaCandidatePlanner.Decision.Category -> decision.scores
            is LayaCandidatePlanner.Decision.Unknown -> decision.scores
        } ?: return null
        val shareById = scores.ranked.associate { it.categoryId to it.probability }
        val categoryIds = model.categories.map { it.id }
        val probabilities = categoryIds.map { id ->
            shareById[id] ?: error("an evaluated category is missing its calibrated share")
        }
        return LayaEvaluation(
            categoryIds = categoryIds,
            probabilities = probabilities,
            unknownShare = scores.unknownProbability,
            confidence = scores.confidence,
        )
    }

    /** Low-level positional scoring used by the runtime's open-time contract probe. */
    internal fun suggestOptions(
        description: String,
        categories: List<CategoryOption>,
    ): LayaRuntimeContract.Suggestion = synchronized(ORT_ACCESS_LOCK) {
        val optionTexts = categories.map { LayaSequence.renderOption(it.name, it.description) } +
            LayaSequence.renderUnknown()
        when (
            val outcome = LayaSequence.build(
                tokenizer = tokenizer,
                instructions = LayaRuntimeContract.INSTRUCTIONS,
                optionTexts = optionTexts,
                state = description,
                maxLen = maxLen,
                headMaxLen = headMaxLen,
            )
        ) {
            is LayaSequence.Outcome.OverBudget ->
                LayaRuntimeContract.Suggestion.OverBudget(
                    options = outcome.options,
                    headMaxLen = outcome.headMaxLen,
                )
            is LayaSequence.Outcome.Ready -> {
                val logits = runLogits(outcome.built)
                val decision = LayaCalibration.decide(logits)
                LayaRuntimeContract.Suggestion.Choice(
                    probabilities = decision.probabilities,
                    bestIndex = decision.bestIndex,
                    confidence = decision.confidence,
                    isUnknown = decision.bestIndex == categories.size,
                )
            }
        }
    }

    private fun runLogits(built: LayaSequence.Built): List<Double> = synchronized(ORT_ACCESS_LOCK) {
        val length = built.inputIds.size
        val optionCount = built.markerPositions.size
        val inputIds = Array(1) { LongArray(length) { i -> built.inputIds[i].toLong() } }
        val attentionMask = Array(1) { LongArray(length) { 1L } }
        val markerPos = Array(1) { LongArray(optionCount) { i -> built.markerPositions[i].toLong() } }
        val markerMask = Array(1) { BooleanArray(optionCount) { true } }
        val qtype = longArrayOf(QTYPE_CHOICE)
        // Tensors and the result are per-call; session access and close share this lock.
        OnnxTensor.createTensor(environment, inputIds).use { ids ->
            OnnxTensor.createTensor(environment, attentionMask).use { mask ->
                OnnxTensor.createTensor(environment, markerPos).use { positions ->
                    OnnxTensor.createTensor(environment, markerMask).use { markers ->
                        OnnxTensor.createTensor(environment, qtype).use { type ->
                            val inputs = mapOf(
                                "input_ids" to ids,
                                "attention_mask" to mask,
                                "marker_pos" to positions,
                                "marker_mask" to markers,
                                "qtype" to type,
                            )
                            session.run(inputs).use { result ->
                                @Suppress("UNCHECKED_CAST")
                                val logits = result.get("logits").get().value as Array<FloatArray>
                                return@synchronized logits[0].take(optionCount).map { it.toDouble() }
                            }
                        }
                    }
                }
            }
        }
        error("unreachable")
    }

    /**
     * Guards the graph's input/output contract with one real probe inference: two live
     * options plus `unknown`, then checks the suggestion shape (3 probabilities, best in
     * range, confidence in [0, 1]). Throws on any failure so [open] reports the bundle
     * corrupt or incompatible instead of serving silently wrong decisions.
     */
    private fun probe() {
        val suggestion = suggestOptions(
            description = "Monthly rent",
            categories = listOf(
                CategoryOption(id = 1L, name = "Food", description = "meals and groceries"),
                CategoryOption(id = 2L, name = "Housing", description = "rent and utilities"),
            ),
        )
        val choice = suggestion as? LayaRuntimeContract.Suggestion.Choice
            ?: error("probe refused with $suggestion")
        require(choice.probabilities.size == 3) { "probe arity ${choice.probabilities.size}" }
        require(choice.bestIndex in 0..2) { "probe best ${choice.bestIndex}" }
        require(choice.confidence in 0.0..1.0) { "probe confidence ${choice.confidence}" }
        val total = choice.probabilities.sum()
        require(total > 0.999 && total < 1.001) { "probe probabilities sum to $total" }
    }

    /** Closes this runtime's session; ORT's shared environment remains process-owned. */
    override fun close() = synchronized(ORT_ACCESS_LOCK) {
        session.close()
    }

    companion object {
        private const val QTYPE_CHOICE = 0L
        private val ORT_ACCESS_LOCK = Any()

        /**
         * The session is owned and released here. ORT's shared environment is process-wide
         * and is left for process teardown; closing one runtime must not invalidate another.
         */
        fun open(modelDir: File): Result = synchronized(ORT_ACCESS_LOCK) {
            openLocked(modelDir)
        }

        private fun openLocked(modelDir: File): Result {
            if (!modelDir.isDirectory) {
                return Result.Unavailable(LayaRuntimeContract.State.Missing)
            }
            val missing = LayaRuntimeContract.BUNDLE_FILES
                .map { modelDir.resolve(it.replace('/', File.separatorChar)) }
                .filterNot { it.isFile }
            if (missing.isNotEmpty()) {
                return Result.Unavailable(LayaRuntimeContract.State.Missing)
            }
            val (maxLen, headMaxLen) = try {
                readBudget(modelDir.resolve("laya_config.json"))
            } catch (error: Exception) {
                return Result.Unavailable(
                    LayaRuntimeContract.State.CorruptOrIncompatible(
                        "unreadable laya_config.json: ${error.message}",
                    ),
                )
            }
            if (maxLen != LayaRuntimeContract.MAX_LEN || headMaxLen != LayaRuntimeContract.HEAD_MAX_LEN) {
                return Result.Unavailable(
                    LayaRuntimeContract.State.CorruptOrIncompatible(
                        "budget mismatch: max_len=$maxLen head_max_len=$headMaxLen, " +
                            "pinned ${LayaRuntimeContract.MAX_LEN}/${LayaRuntimeContract.HEAD_MAX_LEN}",
                    ),
                )
            }
            val tokenizer = try {
                DataInputStream(FileInputStream(modelDir.resolve("tokenizer/laya_tokenizer.bin"))).use {
                    LayaTokenizer.load(it)
                }
            } catch (error: Exception) {
                return Result.Unavailable(
                    LayaRuntimeContract.State.CorruptOrIncompatible(
                        "unreadable tokenizer asset: ${error.message}",
                    ),
                )
            }
            val environment = try {
                OrtEnvironment.getEnvironment()
            } catch (error: Exception) {
                return Result.Unavailable(
                    LayaRuntimeContract.State.CorruptOrIncompatible(
                        "ORT environment failed: ${error.message}",
                    ),
                )
            }
            val session = try {
                environment.createSession(
                    modelDir.resolve("laya.onnx").absolutePath,
                    OrtSession.SessionOptions(),
                )
            } catch (error: Exception) {
                return Result.Unavailable(
                    LayaRuntimeContract.State.CorruptOrIncompatible(
                        "session failed: ${error.message}",
                    ),
                )
            }
            val runtime = LayaRuntime(environment, session, tokenizer, maxLen, headMaxLen)
            val probe = try {
                runtime.probe()
                null
            } catch (error: Exception) {
                "probe inference failed: ${error.message}"
            }
            if (probe != null) {
                runtime.close()
                return Result.Unavailable(
                    LayaRuntimeContract.State.CorruptOrIncompatible(probe),
                )
            }
            return Result.Opened(runtime)
        }

        private fun readBudget(config: File): Pair<Int, Int> {
            val text = config.readText(Charsets.UTF_8)
            val maxLen = Regex("\"max_len\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toInt()
                ?: error("max_len absent")
            val headMaxLen = Regex("\"head_max_len\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toInt()
                ?: error("head_max_len absent")
            return Pair(maxLen, headMaxLen)
        }
    }

    sealed interface Result {
        data class Opened(val runtime: LayaRuntime) : Result
        data class Unavailable(val state: LayaRuntimeContract.State) : Result
    }
}

/** One live candidate: the stable ledger id travels alongside, never through the model. */
data class CategoryOption(
    val id: Long,
    val name: String,
    val description: String = "",
)
