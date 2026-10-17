package io.github.triet4p.aegisf.data.laya

/**
 * The pinned English Laya sequence contract, ported from the checkpoint's
 * `rl_common.build_sequence` (eval order) via `tools/laya/reference_runner.py`.
 *
 * One `choice` question — "which existing category fits this expense description?" —
 * laid out as:
 *
 * `[CLS] choice question: <instructions> [SEP] [MASK] <opt0> [MASK] <opt1> … [SEP]
 * <state> [SEP]`
 *
 * Rules reproduced exactly:
 *
 * - option texts render `name: description` in label-index order with an explicit final
 *   `unknown: none of the listed categories fit this expense` no-fit option;
 * - each option is encoded as `" " + scrubbed` truncated to 48 tokens, prefixed by its own
 *   `[MASK]` marker; logits are read at those marker positions only;
 * - the literal `[MASK]` text is scrubbed from instructions/options/state before encoding,
 *   so prompt-like descriptions cannot inject a marker;
 * - when the option head exceeds [headMaxLen], every option shrinks evenly to
 *   `max(4, (headMaxLen - 16) / n)` tokens and the head keeps at least 8 tokens; a question
 *   that still does not fit is refused ([OverBudget]) instead of silently truncated;
 * - the state is cut to whatever room [maxLen] leaves after the question header.
 *
 * Category identity is positional: option *i* (before `unknown`) is the *i*-th entry of the
 * input category list, so callers keep stable category ids alongside the names.
 */
object LayaSequence {
    const val MAX_OPTION_TOKENS = 48
    const val HEAD_MIN_BUDGET = 16
    const val HEAD_MIN_KEPT = 8
    const val HEAD_MIN_OPTION_TOKENS = 4

    data class Built(
        val inputIds: List<Int>,
        val markerPositions: List<Int>,
    )

    sealed interface Outcome {
        data class Ready(val built: Built) : Outcome
        data class OverBudget(val options: Int, val markers: Int, val headMaxLen: Int) : Outcome
    }

    fun renderOption(name: String, description: String): String {
        val trimmed = description.trim()
        return if (trimmed.isEmpty()) name else "$name: $trimmed"
    }

    fun renderUnknown(): String =
        "${LayaTokenizer.UNKNOWN_OPTION}: ${LayaTokenizer.UNKNOWN_DESCRIPTION}"

    fun scrub(text: String): String =
        text.replace(LayaTokenizer.MASK_SURFACE, " ")

    fun build(
        tokenizer: LayaTokenizer,
        instructions: String,
        optionTexts: List<String>,
        state: String,
        maxLen: Int,
        headMaxLen: Int,
    ): Outcome {
        var headIds = tokenizer.encode("choice question: ${scrub(instructions)}")
        var optionIds = optionTexts.map { option ->
            listOf(tokenizer.maskId) + tokenizer.encode(" ${scrub(option)}").take(MAX_OPTION_TOKENS)
        }
        var budget = headMaxLen - optionIds.sumOf { it.size }
        if (budget < HEAD_MIN_BUDGET) {
            val perOption = maxOf(
                HEAD_MIN_OPTION_TOKENS,
                (headMaxLen - HEAD_MIN_BUDGET) / maxOf(1, optionIds.size),
            )
            optionIds = optionIds.map { it.take(perOption) }
            budget = headMaxLen - optionIds.sumOf { it.size }
        }
        headIds = headIds.take(maxOf(HEAD_MIN_KEPT, budget))
        val ids = ArrayList<Int>(maxLen)
        ids.add(tokenizer.clsId)
        ids.addAll(headIds)
        ids.add(tokenizer.sepId)
        val markers = ArrayList<Int>(optionIds.size)
        for (option in optionIds) {
            markers.add(ids.size)
            ids.addAll(option)
        }
        ids.add(tokenizer.sepId)
        val room = maxOf(0, maxLen - ids.size - 1)
        val stateIds = tokenizer.encode(scrub(state)).take(room)
        ids.addAll(stateIds)
        ids.add(tokenizer.sepId)
        val trimmed = ids.take(maxLen)
        val keptMarkers = markers.filter { it < maxLen }
        if (keptMarkers.size != optionTexts.size) {
            return Outcome.OverBudget(
                options = optionTexts.size,
                markers = keptMarkers.size,
                headMaxLen = headMaxLen,
            )
        }
        return Outcome.Ready(Built(inputIds = trimmed, markerPositions = keptMarkers))
    }
}
