package io.github.triet4p.aegisf.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.TransactionType
import io.github.triet4p.aegisf.data.laya.LayaCandidatePlanner

/** One form's decision context; changing any part invalidates its pending result. */
internal data class ManualSuggestionIntent(
    val formIdentity: Any,
    val description: String,
    val type: TransactionType,
)

/** All category metadata used by a single suggestion request, including archived rows. */
internal data class ManualSuggestionRequest(
    val intent: ManualSuggestionIntent,
    val categories: List<CategoryEntity>,
)

/**
 * The message shown when a request could not be asked of the model because its effective
 * input does not fit the model's actual input budget.
 *
 * The model was never asked, so nothing was scored and nothing was suggested. The user's own
 * text is not shortened, and the category the user chose and the other fields of the record
 * stay exactly as they are.
 */
internal fun effectiveInputOverBudgetMessage(): String =
    "This description is longer than the model's input budget allows, so no suggestion was " +
        "made and the model was not asked. Shorten the description to ask again; your " +
        "category choice and the other fields stay unchanged."

internal sealed interface ManualCategorySuggestionState {
    data object Idle : ManualCategorySuggestionState
    data object LoadingCategories : ManualCategorySuggestionState
    data object WaitingForDescription : ManualCategorySuggestionState
    data object Loading : ManualCategorySuggestionState
    data object NoActiveCategories : ManualCategorySuggestionState
    data object NotApplicableToKind : ManualCategorySuggestionState
    data class Suggested(
        val category: CategoryEntity,
        val uncertainty: LayaCandidatePlanner.RetrievalUncertainty?,
        val distribution: LayaCandidatePlanner.ScoredDistribution? = null,
    ) : ManualCategorySuggestionState
    data class Remembered(
        val category: CategoryEntity,
        val accepted: Boolean = false,
        val manualSelection: Boolean = false,
    ) : ManualCategorySuggestionState
    data object RuleRemoved : ManualCategorySuggestionState
    data class Accepted(
        val category: CategoryEntity,
        val uncertainty: LayaCandidatePlanner.RetrievalUncertainty?,
        val distribution: LayaCandidatePlanner.ScoredDistribution? = null,
    ) : ManualCategorySuggestionState
    data class NoFit(
        val reason: LayaCandidatePlanner.ManualReason,
        val uncertainty: LayaCandidatePlanner.RetrievalUncertainty?,
        val distribution: LayaCandidatePlanner.ScoredDistribution? = null,
    ) : ManualCategorySuggestionState
    data class Stale(val reason: LayaCandidatePlanner.StaleReason? = null) :
        ManualCategorySuggestionState
    data class Unavailable(val reason: UnavailableReason) : ManualCategorySuggestionState
    data object ManuallySelected : ManualCategorySuggestionState
    data object Ignored : ManualCategorySuggestionState
    data object NoCategory : ManualCategorySuggestionState
}

internal enum class UnavailableReason { MISSING, INCOMPATIBLE, SUPERSEDED, CLOSED, FAILED }

/**
 * UI-side request gate. Completion is accepted only for the exact current form, text, kind,
 * and category snapshot. It never writes a record or changes the category field itself.
 */
internal class ManualCategorySuggestionMachine {
    var state: ManualCategorySuggestionState by mutableStateOf(ManualCategorySuggestionState.Idle)
        private set

    internal class Token internal constructor(val request: ManualSuggestionRequest)

    private data class Suppression(
        val intent: ManualSuggestionIntent,
        val state: ManualCategorySuggestionState,
    )

    private var active: Token? = null
    private var suppression: Suppression? = null

    fun begin(request: ManualSuggestionRequest): Token? {
        suppression?.let { suppressed ->
            if (suppressed.intent == request.intent) {
                active = null
                state = suppressed.state
                return null
            }
        }
        suppression = null
        val token = Token(request)
        active = token
        state = ManualCategorySuggestionState.Loading
        return token
    }

    fun complete(
        token: Token,
        currentIntent: ManualSuggestionIntent,
        currentCategories: List<CategoryEntity>,
        observedCategories: List<CategoryEntity>?,
        resolution: LayaCandidatePlanner.CurrentResolution,
    ): Boolean {
        if (!takeIfCurrent(token, currentIntent, currentCategories, observedCategories)) return false
        state = when (resolution) {
            is LayaCandidatePlanner.CurrentResolution.ActiveCategory ->
                ManualCategorySuggestionState.Suggested(
                    category = resolution.category,
                    uncertainty = resolution.uncertainty,
                    distribution = resolution.distribution,
                )
            is LayaCandidatePlanner.CurrentResolution.ManualReview ->
                ManualCategorySuggestionState.NoFit(
                    resolution.reason,
                    resolution.uncertainty,
                    resolution.distribution,
                )
            is LayaCandidatePlanner.CurrentResolution.StaleCategory ->
                ManualCategorySuggestionState.Stale(resolution.reason)
        }
        return true
    }

    fun completeRemembered(
        token: Token,
        currentIntent: ManualSuggestionIntent,
        currentCategories: List<CategoryEntity>,
        observedCategories: List<CategoryEntity>?,
        category: CategoryEntity,
    ): Boolean {
        if (!takeIfCurrent(token, currentIntent, currentCategories, observedCategories)) return false
        val current = currentCategories.firstOrNull { it.id == category.id && !it.archived }
        if (current == null) {
            state = ManualCategorySuggestionState.Stale(
                LayaCandidatePlanner.StaleReason.MISSING_OR_ARCHIVED,
            )
        } else {
            state = ManualCategorySuggestionState.Remembered(current)
        }
        return true
    }


    fun unavailable(
        token: Token,
        currentIntent: ManualSuggestionIntent,
        currentCategories: List<CategoryEntity>,
        observedCategories: List<CategoryEntity>?,
        reason: UnavailableReason,
    ): Boolean {
        if (!takeIfCurrent(token, currentIntent, currentCategories, observedCategories)) return false
        state = ManualCategorySuggestionState.Unavailable(reason)
        return true
    }

    fun acceptSuggestion(): CategoryEntity? {
        val suggested = state as? ManualCategorySuggestionState.Suggested ?: return null
        // The user's choice ends the pending request: a result that arrives afterwards
        // describes an older state and must not replace what the user just chose.
        active = null
        state = ManualCategorySuggestionState.Accepted(
            suggested.category,
            suggested.uncertainty,
            suggested.distribution,
        )
        return suggested.category
    }

    /**
     * Explicit per-row choice from the ranked distribution. Only an evaluated row whose
     * stable id still resolves to a current active category may be staged; an invalid,
     * missing, or stale row returns null and changes nothing. Unknown/manual/ignore
     * never assign a category. The caller still owns the field update and the save.
     * Works from both the top-hit state and the unknown-wins state, since both carry
     * the same selectable distribution.
     */
    fun chooseDistributionOption(categoryId: Long): CategoryEntity? {
        val suggested = state as? ManualCategorySuggestionState.Suggested
        val noFit = state as? ManualCategorySuggestionState.NoFit
        if (suggested == null && noFit == null) return null
        val uncertainty = suggested?.uncertainty ?: noFit?.uncertainty
        val distribution = suggested?.distribution ?: noFit?.distribution ?: return null
        val target = distribution.options.firstOrNull { it.categoryId == categoryId }
            ?.currentCategory ?: return null
        // The user's choice ends the pending request: a result that arrives afterwards
        // describes an older state and must not replace what the user just chose.
        active = null
        state = ManualCategorySuggestionState.Accepted(
            category = target,
            uncertainty = uncertainty,
            distribution = distribution,
        )
        return target
    }

    /** Explicit unknown/no-fit choice: stages no category and never assigns one. */
    fun chooseNoCategoryOption(): Boolean {
        val suggested = state as? ManualCategorySuggestionState.Suggested
        val noFit = state as? ManualCategorySuggestionState.NoFit
        if (suggested?.distribution == null && noFit?.distribution == null) return false
        // The user's choice ends the pending request: a result that arrives afterwards
        // describes an older state and must not replace what the user just chose.
        active = null
        state = ManualCategorySuggestionState.NoCategory
        return true
    }

    fun acceptRememberedRule(): CategoryEntity? {
        val remembered = state as? ManualCategorySuggestionState.Remembered ?: return null
        suppression = null
        state = remembered.copy(accepted = true, manualSelection = false)
        return remembered.category
    }


    fun chooseManually(intent: ManualSuggestionIntent) {
        val remembered = state as? ManualCategorySuggestionState.Remembered
        if (remembered == null) {
            suppress(intent, ManualCategorySuggestionState.ManuallySelected)
            return
        }
        active = null
        val preservedRule = remembered.copy(manualSelection = true)
        suppression = Suppression(intent, preservedRule)
        state = preservedRule
    }

    fun ignore(intent: ManualSuggestionIntent) {
        suppress(intent, ManualCategorySuggestionState.Ignored)
    }

    fun clearSuppression() {
        suppression = null
        active = null
        state = ManualCategorySuggestionState.Idle
    }

    fun ruleRemoved(intent: ManualSuggestionIntent) {
        suppress(intent, ManualCategorySuggestionState.RuleRemoved)
    }

    fun reset() {
        active = null
        state = ManualCategorySuggestionState.Idle
    }

    fun notApplicableToKind() {
        active = null
        state = ManualCategorySuggestionState.NotApplicableToKind
    }

    fun waitForCategories() {
        active = null
        state = ManualCategorySuggestionState.LoadingCategories
    }

    fun waitForDescription() {
        active = null
        state = ManualCategorySuggestionState.WaitingForDescription
    }

    fun noActiveCategories() {
        active = null
        state = ManualCategorySuggestionState.NoActiveCategories
    }

    private fun suppress(intent: ManualSuggestionIntent, suppressedState: ManualCategorySuggestionState) {
        active = null
        suppression = Suppression(intent, suppressedState)
        state = suppressedState
    }

    private fun takeIfCurrent(
        token: Token,
        currentIntent: ManualSuggestionIntent,
        currentCategories: List<CategoryEntity>,
        observedCategories: List<CategoryEntity>?,
    ): Boolean {
        if (active !== token) return false
        active = null
        if (token.request.intent != currentIntent) {
            state = ManualCategorySuggestionState.Idle
            return false
        }
        if (token.request.categories != currentCategories ||
            (observedCategories != null && token.request.categories != observedCategories)
        ) {
            state = ManualCategorySuggestionState.Stale()
            return false
        }
        return true
    }
}
