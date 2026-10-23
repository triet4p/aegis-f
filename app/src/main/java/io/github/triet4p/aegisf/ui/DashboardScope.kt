package io.github.triet4p.aegisf.ui

import io.github.triet4p.aegisf.data.LedgerCategoryFilter
import io.github.triet4p.aegisf.data.LedgerCategorySpending
import io.github.triet4p.aegisf.data.LedgerDateRange
import io.github.triet4p.aegisf.data.LedgerSpending
import io.github.triet4p.aegisf.data.VndMoney
import java.io.Serializable
import java.util.TimeZone

/**
 * The scope the Dashboard destination reads: the calendar days it covers, and the category
 * its spending figure is narrowed to.
 *
 * The scope is held as plain text plus a stable category id, so it can be saved with the
 * rest of the shell's state: the chosen days, the chosen category, and the way the user
 * left them survive switching to another destination and coming back, and survive the
 * process being restarted.
 *
 * The category is an id and never a name, exactly as everywhere else in the app: renaming a
 * category keeps the selection, and an archived category stays selectable so the records
 * filed under it remain readable.
 *
 * The category narrows **spending only**. Income and internal transfers have no breakdown
 * by category in the shared aggregate, so a category-scoped income figure would have to be a
 * second, invented number; instead, the income and transfer cards say plainly that they
 * cover every category of the range.
 */
data class DashboardScopeState(
    val fromText: String,
    val throughText: String,
    val category: LedgerCategoryFilter = LedgerCategoryFilter.All,
) : Serializable {

    companion object {

        /**
         * The device-local calendar month the Dashboard opens on.
         *
         * The month is read from one clock reading and one zone reading, so the two day
         * texts name the same month the range counts. The last day is the last instant of
         * the month, read in the same zone, so a 28-, 29-, 30-, or 31-day month each
         * contribute their own last day.
         */
        fun currentMonth(nowEpochMillis: Long, zone: TimeZone): DashboardScopeState {
            val monthStart = ManualDateTime.monthStartEpochMillis(nowEpochMillis, zone)
            val nextMonthStart = ManualDateTime.nextMonthStartEpochMillis(monthStart, zone)
            return DashboardScopeState(
                fromText = ManualDateTime.dateFieldText(monthStart, zone),
                throughText = ManualDateTime.dateFieldText(nextMonthStart - 1L, zone),
                category = LedgerCategoryFilter.All,
            )
        }
    }
}

/**
 * Which figure of the Dashboard a scoped list shows.
 *
 * The list behind a card is that card's own scope and no other, so the two can never
 * disagree about what they are showing.
 */
enum class DashboardListScope : Serializable {
    /** The confirmed expenses of the scope. */
    SPENDING,

    /** The confirmed income of the scope. */
    INCOME,

    /** The confirmed internal transfers of the scope. */
    TRANSFERS,
}

/**
 * The scope after validation: the refusals of the two day texts, and the closed range the
 * figures are read over.
 *
 * The range is closed or it does not exist: a figure that does not know where it ends
 * cannot say what it counted, so an unfinished or refused scope shows no figures at all
 * and says why, rather than quietly widening to something else.
 */
data class ResolvedDashboardScope(
    val fromError: String?,
    val throughError: String?,
    val rangeError: String?,
    /** The closed range the figures are read over; null when the scope is refused. */
    val range: LedgerDateRange?,
    /** The canonical device-local From day; null when the scope is refused. */
    val canonicalFromText: String?,
    /** The canonical device-local Through day; null when the scope is refused. */
    val canonicalThroughText: String?,
    /** The two inclusive day texts as one readable range, e.g. `2026-10-01 through 2026-10-31`. */
    val label: String,
) {
    /** Whether the scope names a range the figures can be read over. */
    val isUsable: Boolean
        get() = range != null
}

/**
 * Validates the Dashboard's two day texts and converts them into one closed range.
 *
 * Both ends are required — a figure is counted over a range that ends — and each end must
 * be a well-formed `YYYY-MM-DD` day. From must not be after Through.
 *
 * The two ends are ordered by the calendar days they name, never by their text. The date
 * grammar accepts a single-digit month or day, so `2026-10-3` sorts *after* `2026-10-16` as
 * text while naming an earlier day; ordering the texts would then hand the aggregator a
 * range that starts after it ends. Reading the day first makes a leniently written date
 * either a usable range or a refusal, and never a range the ledger cannot read.
 *
 * The range is the half-open `[startOf(From), startOf(dayAfter(Through)))` of the device's
 * own zone, the same conversion the saved-transactions filters use, so a record belongs to
 * the day the device clock showed when it happened and a day boundary is exact.
 */
fun resolveDashboardScope(
    scope: DashboardScopeState,
    zone: TimeZone = TimeZone.getDefault(),
): ResolvedDashboardScope {
    val fromError = when {
        scope.fromText.isEmpty() -> "Choose a From date."
        else -> ManualDateTime.dateError(scope.fromText)
    }
    val throughError = when {
        scope.throughText.isEmpty() -> "Choose a Through date."
        else -> ManualDateTime.dateError(scope.throughText)
    }
    val rangeError: String?
    val range: LedgerDateRange?
    if (fromError != null || throughError != null) {
        rangeError = null
        range = null
    } else {
        val fromStart = ManualDateTime.dayStartEpochMillis(scope.fromText, zone)
        val throughExclusive = ManualDateTime.dayAfterStartEpochMillis(scope.throughText, zone)
        if (fromStart >= throughExclusive) {
            rangeError = "The From date is after the Through date."
            range = null
        } else {
            rangeError = null
            range = LedgerDateRange.closed(
                fromEpochMillis = fromStart,
                throughExclusiveEpochMillis = throughExclusive,
                zoneId = zone.id,
            )
        }
    }
    val canonicalFromText = range?.let {
        ManualDateTime.dateFieldText(
            requireNotNull(it.fromEpochMillis),
            zone,
        )
    }
    val canonicalThroughText = range?.let {
        ManualDateTime.dateFieldText(
            requireNotNull(it.throughExclusiveEpochMillis) - 1L,
            zone,
        )
    }
    val label = if (canonicalFromText != null && canonicalThroughText != null) {
        "$canonicalFromText through $canonicalThroughText"
    } else {
        "${scope.fromText} through ${scope.throughText}"
    }
    return ResolvedDashboardScope(
        fromError = fromError,
        throughError = throughError,
        rangeError = rangeError,
        range = range,
        canonicalFromText = canonicalFromText,
        canonicalThroughText = canonicalThroughText,
        label = label,
    )
}

/**
 * The compact date-control label, based on the validated device-local days rather than the
 * user's raw field text. Refused input stays visible and unchanged until it is corrected.
 */
internal fun dashboardDateRangeControlLabel(
    scope: DashboardScopeState,
    resolved: ResolvedDashboardScope,
): String {
    if (resolved.range == null) {
        return "${scope.fromText} → ${scope.throughText}"
    }
    val fromText = requireNotNull(resolved.canonicalFromText)
    val throughText = requireNotNull(resolved.canonicalThroughText)
    return if (fromText.substring(0, 7) == throughText.substring(0, 7)) {
        "${fromText.substring(5, 7)}/${fromText.substring(8, 10)}–" +
            throughText.substring(8, 10)
    } else {
        "${fromText.substring(5)}–${throughText.substring(5)}"
    }
}

/**
 * The spending figure of one scope: the range's confirmed spending, narrowed to the
 * chosen category.
 *
 * The narrowed figure is the aggregate's own bucket, not a second sum of the bucket's
 * records: the bucket is exactly the records the drill-down behind it returns, so the card
 * and the list always show the same whole dong.
 *
 * A category with no spending in the range has no bucket, and its figure is an empty one:
 * zero dong over zero records, which is what the drill-down of that scope holds as well.
 */
fun scopedSpending(
    spending: LedgerSpending,
    category: LedgerCategoryFilter,
): LedgerSpending = when (category) {
    is LedgerCategoryFilter.All -> spending
    is LedgerCategoryFilter.Uncategorized -> spending.categoryTotals
        .firstOrNull { it.categoryId == null }
        ?.let { bucket -> LedgerSpending(bucket.totalVnd, bucket.overflowed, bucket.recordCount, listOf(bucket)) }
        ?: EMPTY_SPENDING
    is LedgerCategoryFilter.Specific -> spending.categoryTotals
        .firstOrNull { it.categoryId == category.categoryId }
        ?.let { bucket -> LedgerSpending(bucket.totalVnd, bucket.overflowed, bucket.recordCount, listOf(bucket)) }
        ?: EMPTY_SPENDING
}

/** The spending figure of a scope that holds nothing: zero dong over zero records. */
private val EMPTY_SPENDING = LedgerSpending(
    totalVnd = 0L,
    overflowed = false,
    recordCount = 0,
    categoryTotals = emptyList(),
)

/**
 * The category a spending bucket is filed under, as shown on screen.
 *
 * An archived category is named as archived rather than hidden, because the records filed
 * under it keep it; a bucket with no category is spending filed under no category, which is
 * a bucket of its own and is never merged into a named one.
 */
internal fun spendingBucketLabel(bucket: LedgerCategorySpending): String = when {
    bucket.categoryId == null -> "No category"
    bucket.categoryName == null -> "Unknown category"
    bucket.categoryArchived -> "${bucket.categoryName} (archived)"
    else -> bucket.categoryName
}

/**
 * A whole-dong amount for the Dashboard.
 *
 * An amount that could not be summed exactly is marked as the lower bound it is, so a
 * reader can never mistake it for the exact figure.
 */
internal fun dashboardAmountText(amountVnd: Long, overflowed: Boolean): String {
    val formatted = VndMoney.formatAmount(amountVnd)
    return if (overflowed) "$formatted or more" else formatted
}

/** The visual width of one non-negative amount relative to its largest chart bucket. */
internal fun dashboardBarFraction(amountVnd: Long, maximumVnd: Long): Float {
    if (amountVnd <= 0L || maximumVnd <= 0L) return 0f
    return (amountVnd.toDouble() / maximumVnd.toDouble()).toFloat().coerceIn(0f, 1f)
}

/** "1 confirmed expense" / "N confirmed expenses" — what a spending figure is made of. */
internal fun dashboardRecordCountLabel(count: Int, singular: String, plural: String): String =
    if (count == 1) "1 $singular" else "$count $plural"
