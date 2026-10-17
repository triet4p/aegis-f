package io.github.triet4p.aegisf.data.laya

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * The pinned English Laya output interpretation: per-cardinality temperature scaling,
 * softmax over the question's own options, and the Jev sharpness confidence.
 *
 * Ported from the checkpoint's `temp_bucket` / `confidence_from_probs` via
 * `tools/laya/reference_runner.py`. Raw logits are never probabilities: the caller divides
 * by [temperatureFor] before [softmax], and [confidence] is a distribution sharpness score
 * (`1 - H(p)/ln(k)`, rounded to 4 decimals like the reference API), not an accuracy
 * guarantee. Schema-valid output and high confidence never confirm a financial record;
 * confirmation stays an explicit user action in later tasks.
 */
object LayaCalibration {
    /** Pinned `laya_config.json` per-qtype temperatures: choice, score, noul. */
    const val CHOICE_TEMPERATURE = 1.6369030475616455
    const val SCORE_TEMPERATURE = 1.2514300346374512
    const val NOUL_TEMPERATURE = 1.983399510383606

    /** Pinned `temperature_by_options` buckets for the single `choice` question used here. */
    fun temperatureFor(optionCount: Int): Double = when {
        optionCount <= 2 -> 1.9063563346862793
        optionCount <= 5 -> 1.7601518630981445
        optionCount <= 10 -> 1.0000158548355103
        else -> 0.10058280825614929
    }

    fun softmax(scaledLogits: List<Double>): List<Double> {
        require(scaledLogits.isNotEmpty()) { "softmax needs at least one logit" }
        val peak = scaledLogits.max()
        val exps = scaledLogits.map { exp(it - peak) }
        val total = exps.sum()
        return exps.map { it / total }
    }

    /** Jev confidence `1 - H(p)/ln(k)`; 1.0 for a single option, like the reference. */
    fun confidence(probabilities: List<Double>): Double {
        if (probabilities.size < 2) return 1.0
        var entropy = 0.0
        for (probability in probabilities) {
            entropy -= probability * ln(maxOf(probability, 1e-12))
        }
        return 1.0 - entropy / ln(probabilities.size.toDouble())
    }

    /** Reference rounding: 4 decimals, matching the upstream API surface. */
    fun round4(value: Double): Double = (value * 10_000.0).roundToInt() / 10_000.0

    /**
     * Interprets one row of raw choice [logits]: scales by the pinned bucket temperature,
     * softmaxes over exactly these options, and reports the best index with its rounded
     * probability and the rounded Jev confidence.
     */
    fun decide(logits: List<Double>): Decision {
        require(logits.size >= 2) { "a choice needs at least two options" }
        val temperature = temperatureFor(logits.size)
        val probabilities = softmax(logits.map { it / temperature })
        var best = 0
        for (i in 1 until probabilities.size) {
            if (probabilities[i] > probabilities[best]) best = i
        }
        return Decision(
            bestIndex = best,
            probabilities = probabilities.map(::round4),
            confidence = round4(confidence(probabilities)),
            temperature = temperature,
        )
    }

    data class Decision(
        val bestIndex: Int,
        val probabilities: List<Double>,
        val confidence: Double,
        val temperature: Double,
    )
}
