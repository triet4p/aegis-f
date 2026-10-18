package io.github.triet4p.aegisf.data.laya

/**
 * The Android Laya runtime contract: what the app owns, what it refuses, and what later
 * tasks add.
 *
 * S4.4 adds [LayaRuntime.suggest] and [LayaCandidatePlanner] to apply caller-supplied exact
 * correction rules, deterministic category-name matching, and budget-exact candidate
 * retrieval. S4.10 keeps that pipeline and additionally surfaces the model-returned
 * per-category calibrated distribution (plus the same-softmax unknown share) for
 * deliberate user choice: explicit rules and category-name matches still short-circuit
 * before inference with no fabricated probabilities, emissions always resolve against
 * current active rows by stable id, and oversized subsets stay labeled with the
 * evaluated scope rather than as full-catalog shares. S4.7 persists only deliberately
 * remembered exact-description rules in the ledger data layer and resolves an active
 * match before manual-entry inference; the runtime still receives only current category
 * metadata and never stores or learns rules.
 * [LayaInferenceLifecycle] owns one bounded background worker, latest-only pending work,
 * serialized session use, and safe release after non-cancellable native calls. S4.6
 * presents review-only suggestions for new expense entry; income, transfers, and
 * correction forms remain manual. S4.8 connects the same review-only path to
 * notification-draft review: only a meaningful English memo (or an explicit English
 * note typed in the separate suggestion field when the stored memo is opaque or
 * unsupported) is offered to the saved rule lookup and model inference. Only the
 * effective input (the note alone when present, otherwise the visible memo text) is
 * ever offered; the two are never concatenated and the stored memo is never
 * rewritten. Opaque/reference-code/non-English (including unaccented Vietnamese)/
 * identifier-only memos stay manual, and confirmation remains mandatory.
 *
 * States, in the order the app meets them:
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
     * Low-level positional graph output used by the runtime's open-time contract probe.
     * Indices address the exact caller-ordered option list and the final `unknown` no-fit option.
     * Production decisions use [LayaRuntime.suggest], which maps the winning position to a stable id.
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
