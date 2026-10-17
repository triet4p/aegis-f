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
    ) : ManualCategorySuggestionState
    data class Accepted(
        val category: CategoryEntity,
        val uncertainty: LayaCandidatePlanner.RetrievalUncertainty?,
    ) : ManualCategorySuggestionState
    data class NoFit(
        val reason: LayaCandidatePlanner.ManualReason,
        val uncertainty: LayaCandidatePlanner.RetrievalUncertainty?,
    ) : ManualCategorySuggestionState
    data class Stale(val reason: LayaCandidatePlanner.StaleReason? = null) :
        ManualCategorySuggestionState
    data class Unavailable(val reason: UnavailableReason) : ManualCategorySuggestionState
    data object ManuallySelected : ManualCategorySuggestionState
    data object Ignored : ManualCategorySuggestionState
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
                )
            is LayaCandidatePlanner.CurrentResolution.ManualReview ->
                ManualCategorySuggestionState.NoFit(resolution.reason, resolution.uncertainty)
            is LayaCandidatePlanner.CurrentResolution.StaleCategory ->
                ManualCategorySuggestionState.Stale(resolution.reason)
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
        state = ManualCategorySuggestionState.Accepted(suggested.category, suggested.uncertainty)
        return suggested.category
    }

    fun chooseManually(intent: ManualSuggestionIntent) {
        suppress(intent, ManualCategorySuggestionState.ManuallySelected)
    }

    fun ignore(intent: ManualSuggestionIntent) {
        suppress(intent, ManualCategorySuggestionState.Ignored)
    }

    fun clearSuppression() {
        suppression = null
        active = null
        state = ManualCategorySuggestionState.Idle
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
