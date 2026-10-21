package io.github.triet4p.aegisf.data

import io.github.triet4p.aegisf.data.laya.LayaEffectiveContext

/**
 * The order in which a category suggestion is decided.
 *
 * The order is frozen: a valid saved rule answers first, and the model is asked only when
 * no rule answers. It lives in the data layer because every caller of it must keep that
 * order — the manual entry screen and the command boundary alike — and one order in one
 * place is what keeps the two from drifting apart.
 */
sealed interface ManualCategoryDecision<out T> {
    /** A saved exact-description rule answered. */
    data class Remembered(val category: CategoryEntity) : ManualCategoryDecision<Nothing>

    /** The question was put to the model. */
    data class Inferred<T>(val outcome: T) : ManualCategoryDecision<T>
}

/**
 * A valid saved rule short-circuits inference; a stale/inactive rule cannot become a choice.
 *
 * The saved rule is looked up by the request's own description — the text the user is looking
 * at — and never by the composed effective context: a rule is remembered for one exact
 * description, so appending stored readings to it would make the rule unreachable and would
 * count the same rule twice. The rule therefore answers before the model runs, and the model
 * never sees the rule as a correction.
 */
suspend fun <T> decideRememberedBeforeInference(
    description: String,
    context: LayaEffectiveContext,
    activeCategories: List<CategoryEntity>,
    findRememberedCategory: suspend (String) -> CategoryEntity?,
    infer: suspend (LayaEffectiveContext, List<CategoryEntity>) -> T,
): ManualCategoryDecision<T> {
    val remembered = findRememberedCategory(description)
    val current = remembered?.let { rule ->
        activeCategories.firstOrNull { it.id == rule.id && !it.archived }
    }
    return if (current != null) {
        ManualCategoryDecision.Remembered(current)
    } else {
        ManualCategoryDecision.Inferred(infer(context, activeCategories))
    }
}
