package io.github.triet4p.aegisf.data.laya

/**
 * The S4.2 Android decision runtime contract: what the app owns, what it refuses, and what
 * later tasks add.
 *
 * This task integrates the runtime only: loading the pinned graph with its external weights
 * through ONNX Runtime Android, the exact tokenizer/sequence/marker/calibration pipeline,
 * and reference parity. Model provisioning (getting the bundle into app-private storage,
 * S4.3), scalable candidate selection for oversized category sets (S4.4), and background
 * lifecycle integration (S4.5) are separate tasks and are not claimed here.
 *
 * States, in the order the app meets them:
 *
 * - [Missing]: no verified bundle is installed. Manual entry works unchanged; the app
 *   must not invent a suggestion.
 * - [CorruptOrIncompatible]: the bundle fails verification (hash, config, tokenizer asset,
 *   or a session that cannot open/run the graph). Manual entry works unchanged.
 * - [Ready]: a verified bundle is loaded and decisions run offline, on-device, against
 *   current active category metadata with stable category ids and an explicit `unknown`
 *   no-fit outcome. No result ever writes to the ledger on its own.
 */
object LayaRuntimeContract {
    /** Pinned export revision the runtime accepts; anything else is [State.CorruptOrIncompatible]. */
    const val PINNED_EXPORT_REVISION = "68f27dfe5a27a54fb2b1fefc432f43f972e90868"

    /** Bundle files S4.3 must place in app-private storage (weights stay out of Git). */
    val BUNDLE_FILES = listOf(
        "laya.onnx",
        "laya.onnx.data",
        "laya_config.json",
        "tokenizer/laya_tokenizer.bin",
    )

    const val INSTRUCTIONS =
        "Which of your existing expense categories best fits this spending description? " +
            "Pick unknown when none of the listed categories fit."

    const val MAX_LEN = 512
    const val HEAD_MAX_LEN = 192

    sealed interface State {
        data object Missing : State
        data class CorruptOrIncompatible(val reason: String) : State
        data object Ready : State
    }

    /**
     * One suggestion for an expense description: either a ranked choice over the live
     * candidate list (index-aligned with the input categories plus trailing `unknown`) or
     * an explicit refusal when the candidate set cannot fit the head budget. Stale
     * suggestions must never apply to a different form state (S4.6 owns the UI binding);
     * archived ids are resolved by the caller against current metadata before display.
     */
    sealed interface Suggestion {
        data class Choice(
            val probabilities: List<Double>,
            val bestIndex: Int,
            val confidence: Double,
            /** Unknown selected: the input stays reviewable and manually categorizable. */
            val isUnknown: Boolean,
        ) : Suggestion

        data class OverBudget(val options: Int, val headMaxLen: Int) : Suggestion
    }
}
