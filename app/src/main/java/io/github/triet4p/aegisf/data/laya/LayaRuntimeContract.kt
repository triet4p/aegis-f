package io.github.triet4p.aegisf.data.laya

/**
 * The Android Laya runtime contract: what the app owns, what it refuses, and what later
 * tasks add.
 *
 * S4.2 integrates the pinned graph, tokenizer, sequence, marker, and calibration pipeline.
 * S4.4 adds [LayaRuntime.suggest] and [LayaCandidatePlanner] to apply caller-supplied exact
 * correction rules, deterministic category-name matching, and budget-exact candidate
 * retrieval. The runtime accepts caller-owned correction rules but does not store or learn
 * them; explicit persistence and remember/remove controls belong to S4.7. Background
 * lifecycle integration remains S4.5.
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
     * Low-level positional graph output for parity verification: indices address the exact
     * caller-ordered option list plus a final `unknown` no-fit outcome. Production candidate
     * decisions use [LayaRuntime.suggest], which maps the winning position to a stable id.
     * A choice is never a ledger write, and any candidate result must be re-resolved against
     * current category metadata before display.
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
