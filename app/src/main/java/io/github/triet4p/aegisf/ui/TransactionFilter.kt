package io.github.triet4p.aegisf.ui

import io.github.triet4p.aegisf.data.TransactionEntity
import io.github.triet4p.aegisf.data.TransactionType
import java.util.TimeZone

/**
 * The browsing filters of the saved-transactions list: one state for every filter dimension.
 *
 * The three dimensions — transaction type, category, and inclusive From/Through calendar days —
 * combine as AND: a record is shown only when it satisfies every dimension that is constrained.
 * An unconstrained dimension matches everything, so the default state (null type, [All][CategoryFilter],
 * empty dates) returns the full list, and clearing every dimension returns it too.
 *
 * The category dimension is keyed by the stable database id, never by name: renaming a
 * category does not lose the selection, and an archived category stays selectable — through
 * [CategoryFilter.Specific] — so records filed under it remain discoverable. [CategoryFilter.All]
 * and [CategoryFilter.Uncategorized] are distinct selections: the first shows every record, the
 * second shows only records filed under no category.
 *
 * The state is held as saveable primitives (a nullable enum, a serializable sealed interface,
 * two strings), so it survives a configuration change and stays put while the user opens a
 * record's detail view or its editor and comes back. It is deliberately not written to disk:
 * filters are a browsing preference, not ledger data.
 */
data class TransactionFilterState(
    val type: TransactionType? = null,
    val category: CategoryFilter = CategoryFilter.All,
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
            (if (category !is CategoryFilter.All) 1 else 0) +
            (if (fromText.isNotEmpty()) 1 else 0) +
            (if (throughText.isNotEmpty()) 1 else 0)

    /** Whether any dimension constrains the list; false means the full list is shown. */
    val isActive: Boolean
        get() = activeDimensionCount > 0
}

/**
 * The category dimension of the browsing filters.
 *
 * [Specific] carries the stable database id of one category, including an archived one: the
 * stored records keep pointing at it, so it must stay selectable here even though the editor
 * no longer offers it for new assignments. [Uncategorized] matches only records with no
 * category; it is never conflated with [All].
 */
sealed interface CategoryFilter : java.io.Serializable {
    data object All : CategoryFilter

    data object Uncategorized : CategoryFilter

    data class Specific(val categoryId: Long) : CategoryFilter
}

/**
 * An inclusive From/Through pair of calendar days as a half-open epoch range.
 *
 * `fromEpochMillis` is local midnight at the start of the From day; null leaves the start
 * open. `throughExclusiveEpochMillis` is local midnight at the start of the day after the
 * Through day; null leaves the end open. Both ends use the device's current default zone —
 * the same zone [ManualDateTime] writes records in — so a record belongs to the day the
 * device clock showed when it happened, never to a guessed UTC day.
 */
data class EpochRange(
    val fromEpochMillis: Long?,
    val throughExclusiveEpochMillis: Long?,
)

/**
 * The date dimension after validation: per-field refusals, a reversed-range refusal, and the
 * epoch range, which is non-null only when nothing is refused.
 *
 * Empty ends are open ends, not errors. A refused dimension is never applied: the list keeps
 * the other dimensions' narrowing and says so on screen, rather than silently broadening the
 * search or crashing on an unparseable date.
 */
data class ResolvedDateFilter(
    val fromError: String?,
    val throughError: String?,
    val rangeError: String?,
    val range: EpochRange?,
)

/**
 * Validates the From/Through date texts and converts them to an [EpochRange].
 *
 * Each non-empty end must satisfy [ManualDateTime.dateError]; when both ends are present and
 * valid, From must not be after Through (compared as `YYYY-MM-DD` text, which orders the
 * same as the calendar days). Either end may be empty. [zone] is the device's current
 * default zone in production and an explicit oracle zone in tests.
 */
fun resolveDateFilter(
    fromText: String,
    throughText: String,
    zone: TimeZone = TimeZone.getDefault(),
): ResolvedDateFilter {
    val fromError = if (fromText.isEmpty()) null else ManualDateTime.dateError(fromText)
    val throughError = if (throughText.isEmpty()) null else ManualDateTime.dateError(throughText)
    val rangeError =
        if (fromError == null && throughError == null &&
            fromText.isNotEmpty() && throughText.isNotEmpty() && fromText > throughText
        ) {
            "The From date is after the Through date."
        } else {
            null
        }
    val range = if (fromError != null || throughError != null || rangeError != null) {
        null
    } else {
        EpochRange(
            fromEpochMillis = fromText.ifEmpty { null }?.let {
                ManualDateTime.dayStartEpochMillis(it, zone)
            },
            throughExclusiveEpochMillis = throughText.ifEmpty { null }?.let {
                ManualDateTime.dayAfterStartEpochMillis(it, zone)
            },
        )
    }
    return ResolvedDateFilter(
        fromError = fromError,
        throughError = throughError,
        rangeError = rangeError,
        range = range,
    )
}

/**
 * The records of [records] that satisfy every constrained dimension.
 *
 * The input order is preserved — callers pass the repository's newest-first list, so ties on
 * the occurred-at instant keep the stored-id tie-breaker — and no dimension re-sorts, caps,
 * or fabricates rows. A null [range] end is an open end.
 */
fun applyTransactionFilters(
    records: List<TransactionEntity>,
    type: TransactionType?,
    category: CategoryFilter,
    range: EpochRange,
): List<TransactionEntity> = records.filter { record ->
    (type == null || record.type == type) &&
        when (category) {
            is CategoryFilter.All -> true
            is CategoryFilter.Uncategorized -> record.categoryId == null
            is CategoryFilter.Specific -> record.categoryId == category.categoryId
        } &&
        (range.fromEpochMillis == null || record.occurredAtEpochMillis >= range.fromEpochMillis) &&
        (
            range.throughExclusiveEpochMillis == null ||
                record.occurredAtEpochMillis < range.throughExclusiveEpochMillis
            )
}
