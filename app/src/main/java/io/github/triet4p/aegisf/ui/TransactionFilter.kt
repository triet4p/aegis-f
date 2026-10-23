package io.github.triet4p.aegisf.ui

import io.github.triet4p.aegisf.data.LedgerCategoryFilter
import io.github.triet4p.aegisf.data.LedgerDateRange
import io.github.triet4p.aegisf.data.TransactionType
import java.util.TimeZone

/**
 * The browsing filters of the saved-transactions list: one state for every filter dimension.
 *
 * The three dimensions — transaction type, category, and inclusive From/Through calendar days —
 * combine as AND: a record is shown only when it satisfies every dimension that is constrained.
 * An unconstrained dimension matches everything, so the default state (null type,
 * [LedgerCategoryFilter.All], empty dates) returns the full list, and clearing every
 * dimension returns it too.
 *
 * The category dimension is keyed by the stable database id, never by name: renaming a
 * category does not lose the selection, and an archived category stays selectable — through
 * [LedgerCategoryFilter.Specific] — so records filed under it remain discoverable.
 * [LedgerCategoryFilter.All] and [LedgerCategoryFilter.Uncategorized] are distinct
 * selections: the first shows every record, the second shows only records filed under no
 * category.
 *
 * The state is held as saveable primitives (a nullable enum, a serializable sealed
 * interface, two strings), so it survives a configuration change and stays put while the user
 * opens a record's detail view or its editor and comes back. It is deliberately not written to
 * disk: filters are a browsing preference, not ledger data.
 */
data class TransactionFilterState(
    val type: TransactionType? = null,
    val category: LedgerCategoryFilter = LedgerCategoryFilter.All,
    val fromText: String = "",
    val throughText: String = "",
) : java.io.Serializable {

    /**
     * How many of the four dimensions are set — type, category, From, Through. Zero is the
     * default state: nothing constrains the list. A date end counts as set as soon as
     * anything is typed, including a malformed day that is refused rather than applied, so
     * the count never hides input the user can still see and correct.
     */
    val activeDimensionCount: Int
        get() = (if (type != null) 1 else 0) +
            (if (category !is LedgerCategoryFilter.All) 1 else 0) +
            (if (fromText.isNotEmpty()) 1 else 0) +
            (if (throughText.isNotEmpty()) 1 else 0)

    /** Whether any dimension constrains the list; false means the full list is shown. */
    val isActive: Boolean
        get() = activeDimensionCount > 0
}

/**
 * The date dimension after validation: per-field refusals, a reversed-range refusal, and the
 * explicit range, which is non-null only when nothing is refused.
 *
 * Empty ends are open ends, not errors. A refused dimension is never applied: the list keeps
 * the other dimensions' narrowing and says so on screen, rather than silently broadening the
 * search or crashing on an unparseable date.
 */
data class ResolvedDateFilter(
    val fromError: String?,
    val throughError: String?,
    val rangeError: String?,
    val range: LedgerDateRange?,
)

/**
 * Validates the From/Through date texts and converts them to a [LedgerDateRange].
 *
 * Each non-empty end must satisfy [ManualDateTime.dateError]; when both ends are present and
 * valid, From must not be after Through (compared through their local-day epoch boundaries,
 * so accepted non-zero-padded dates are ordered by date rather than text). Either end may be
 * empty. [zone] is the device's current default zone in production and an explicit oracle zone
 * in tests.
 * The range carries that zone with it, so the day a record is counted in is the day the
 * device clock showed when it happened and never a day of some other zone.
 */
fun resolveDateFilter(
    fromText: String,
    throughText: String,
    zone: TimeZone = TimeZone.getDefault(),
): ResolvedDateFilter {
    val fromError = if (fromText.isEmpty()) null else ManualDateTime.dateError(fromText)
    val throughError = if (throughText.isEmpty()) null else ManualDateTime.dateError(throughText)
    val fromStartEpochMillis = fromText
        .takeIf { it.isNotEmpty() && fromError == null }
        ?.let { ManualDateTime.dayStartEpochMillis(it, zone) }
    val throughExclusiveEpochMillis = throughText
        .takeIf { it.isNotEmpty() && throughError == null }
        ?.let { ManualDateTime.dayAfterStartEpochMillis(it, zone) }
    val rangeError =
        if (fromStartEpochMillis != null && throughExclusiveEpochMillis != null &&
            fromStartEpochMillis >= throughExclusiveEpochMillis
        ) {
            "The From date is after the Through date."
        } else {
            null
        }
    val range = if (fromError != null || throughError != null || rangeError != null) {
        null
    } else {
        LedgerDateRange(
            fromEpochMillis = fromStartEpochMillis,
            throughExclusiveEpochMillis = throughExclusiveEpochMillis,
            zoneId = zone.id,
        )
    }
    return ResolvedDateFilter(
        fromError = fromError,
        throughError = throughError,
        rangeError = rangeError,
        range = range,
    )
}
