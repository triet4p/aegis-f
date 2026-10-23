package io.github.triet4p.aegisf.data

import io.github.triet4p.aegisf.ui.ManualDateTime
import java.util.TimeZone

/**
 * One exact whole-dong figure and the number of records it is made of.
 *
 * The amount is always a whole number of dong: no floating-point money, no rounding, and
 * no sign — the direction lives in the record's type, not in the amount.
 *
 * [overflowed] says the figure could not be summed exactly. When it is true, [totalVnd] is
 * the largest whole-dong value, which is a *proven lower bound*: a total made of whole dong
 * above that limit is certainly at least that large. It is never a wrapped, negative, or
 * invented figure, and the overflow is reported instead of being hidden.
 */
data class LedgerTotal(
    val totalVnd: Long,
    val overflowed: Boolean,
    val recordCount: Int,
)

/**
 * The confirmed spending of one range, and the buckets it is made of.
 *
 * Only records that are [TransactionType.EXPENSE], are [ConfirmationStatus.CONFIRMED], and
 * occurred inside the range enter it. Income and internal transfers can never inflate
 * spending, a draft is not yet spending, and a record outside the range belongs to that
 * range.
 */
data class LedgerSpending(
    val totalVnd: Long,
    val overflowed: Boolean,
    val recordCount: Int,
    /** One bucket per category that has spending in the range, largest first. */
    val categoryTotals: List<LedgerCategorySpending>,
)

/**
 * The confirmed spending of one category in one range.
 *
 * The bucket is keyed by the stable category id and not by the name: a renamed category
 * keeps its spending on the same bucket, and an archived one keeps the spending its records
 * contributed. [categoryName] and [categoryArchived] are the stored category's own name and
 * state, resolved when the aggregate was computed; a bucket with a null id is spending
 * filed under no category, which is a bucket of its own and is never dropped from the
 * breakdown.
 */
data class LedgerCategorySpending(
    val categoryId: Long?,
    /** The stored category's name; null when this bucket was not resolved against the catalog. */
    val categoryName: String? = null,
    /** Whether the stored category is archived; false when it was not resolved. */
    val categoryArchived: Boolean = false,
    val totalVnd: Long,
    val overflowed: Boolean,
    val recordCount: Int,
)

/**
 * The confirmed spending of one calendar day inside a range.
 *
 * The day is the calendar day of the range's own zone: [dayStartEpochMillis] is the first
 * instant of that day in that zone and [dayText] is the day it names. Two records an
 * instant apart can therefore fall into two different days, and a record never falls into
 * a day of another zone.
 */
data class LedgerDailySpending(
    val dayStartEpochMillis: Long,
    val dayText: String,
    val totalVnd: Long,
    val overflowed: Boolean,
    val recordCount: Int,
)

/**
 * The exact confirmed-ledger figures of one explicit range.
 *
 * The aggregate is a derived view of the stored rows and never a second source of truth:
 * it is built from the same records the drill-down returns, so the two always agree.
 *
 * [spending] counts confirmed expenses only. [income] and [transfers] are reported
 * separately so that the exclusion is visible rather than implied: an internal transfer
 * moves money between the user's own holdings and changes neither spending nor income, and
 * it is never added to either figure. [excludedDraftCount] counts the records in the range
 * that are not confirmed and are therefore in no figure above. [dailySpendingCategory]
 * narrows only [dailySpending]; the overall [spending] total remains for the full range.
 */
data class LedgerAggregate(
    val range: LedgerDateRange,
    val spending: LedgerSpending,
    val income: LedgerTotal,
    val transfers: LedgerTotal,
    /** The records in the range that are not confirmed and are in no figure above. */
    val excludedDraftCount: Int,
    /** One entry per day of confirmed spending matching [dailySpendingCategory], in day order. */
    val dailySpending: List<LedgerDailySpending>,
    /** The category selector used for [dailySpending]. */
    val dailySpendingCategory: LedgerCategoryFilter = LedgerCategoryFilter.All,
)

/**
 * The running figures of one range's confirmed records.
 *
 * The fold is what makes an exact figure without holding the records. Every record of the
 * range is added once and then forgotten, so what the figure needs in memory is the size
 * of its own breakdown — one bucket per category that has spending and one per day that
 * has spending — and never the number of records the range holds. A range with a million
 * records needs the same memory as a range with ten, unless the range really has a million
 * categories or a million days in it, which is the size of the answer and not of the work.
 *
 * The rules are the ledger's own: only confirmed records of the range are counted, the sum
 * is whole dong, and a sum that passes the largest whole-dong value is reported as a proven
 * lower bound with the overflow marked instead of being wrapped.
 *
 * The zone is optional and is only needed for the day buckets: a figure that counts no days
 * does not need one, and a fold without a zone reports no days at all. The category selector
 * narrows only those daily buckets; totals and category totals still cover the full range.
 */
class LedgerRangeFold(
    /** The first instant of the range; it belongs to the range. */
    val fromEpochMillis: Long,
    /** The first instant after the range; it does not belong to the range. */
    val throughExclusiveEpochMillis: Long,
    private val zone: TimeZone? = null,
    private val dailySpendingCategory: LedgerCategoryFilter = LedgerCategoryFilter.All,
) {
    init {
        require(fromEpochMillis < throughExclusiveEpochMillis) {
            "A ledger range must start before it ends."
        }
    }

    private val totalsByType = LinkedHashMap<TransactionType, RunningTotal>()
    private val totalsByCategory = LinkedHashMap<Long?, RunningTotal>()
    private val totalsByDay = LinkedHashMap<String, RunningTotal>()
    private val dayStarts = LinkedHashMap<String, Long>()
    private var excludedDraftCount = 0

    /**
     * Adds one stored record to the running figures.
     *
     * A record that is not confirmed, or that happened outside the range, is not part of
     * any figure of this range and is not counted at all.
     */
    fun add(record: TransactionEntity) {
        if (record.confirmation != ConfirmationStatus.CONFIRMED) return
        if (record.occurredAtEpochMillis < fromEpochMillis) return
        if (record.occurredAtEpochMillis >= throughExclusiveEpochMillis) return
        totalsByType.getOrPut(record.type) { RunningTotal() }.add(record.amountVnd)
        if (record.type != TransactionType.EXPENSE) return
        totalsByCategory.getOrPut(record.categoryId) { RunningTotal() }.add(record.amountVnd)
        if (!matchesDailySpendingCategory(record.categoryId)) return
        val dayZone = zone ?: return
        val dayText = ManualDateTime.dateFieldText(record.occurredAtEpochMillis, dayZone)
        totalsByDay.getOrPut(dayText) { RunningTotal() }.add(record.amountVnd)
        dayStarts.getOrPut(dayText) { ManualDateTime.dayStartEpochMillis(dayText, dayZone) }
    }

    /** How many records of the range are not confirmed and are in no figure. */
    fun setExcludedDraftCount(count: Int) {
        excludedDraftCount = count
    }

    /** The confirmed total of one record type inside the range. */
    fun total(type: TransactionType): LedgerTotal {
        val total = totalsByType[type] ?: RunningTotal()
        return LedgerTotal(
            totalVnd = total.totalVnd,
            overflowed = total.overflowed,
            recordCount = total.recordCount,
        )
    }

    /**
     * The confirmed spending of the range and the buckets it is made of.
     *
     * The breakdown order is by descending bucket total, then by stable identity (named
     * categories by id, then the uncategorised bucket), so it never depends on the order
     * the records were added in.
     */
    fun spending(): LedgerSpending {
        val total = totalsByType[TransactionType.EXPENSE] ?: RunningTotal()
        val categories = totalsByCategory
            .map { (categoryId, bucket) ->
                LedgerCategorySpending(
                    categoryId = categoryId,
                    categoryName = null,
                    categoryArchived = false,
                    totalVnd = bucket.totalVnd,
                    overflowed = bucket.overflowed,
                    recordCount = bucket.recordCount,
                )
            }
            .sortedWith(BREAKDOWN_ORDER)
        return LedgerSpending(
            totalVnd = total.totalVnd,
            overflowed = total.overflowed,
            recordCount = total.recordCount,
            categoryTotals = categories,
        )
    }

    /** The confirmed spending of each matching calendar day of the range, in day order. */
    fun dailySpending(): List<LedgerDailySpending> = totalsByDay
        .map { (dayText, bucket) ->
            LedgerDailySpending(
                dayStartEpochMillis = dayStarts.getValue(dayText),
                dayText = dayText,
                totalVnd = bucket.totalVnd,
                overflowed = bucket.overflowed,
                recordCount = bucket.recordCount,
            )
        }
        .sortedBy { it.dayStartEpochMillis }

    private fun matchesDailySpendingCategory(categoryId: Long?): Boolean =
        when (dailySpendingCategory) {
            LedgerCategoryFilter.All -> true
            LedgerCategoryFilter.Uncategorized -> categoryId == null
            is LedgerCategoryFilter.Specific -> categoryId == dailySpendingCategory.categoryId
        }

    /** The whole figure of the range, with the category names still unresolved. */
    fun build(range: LedgerDateRange): LedgerAggregate = LedgerAggregate(
        range = range,
        spending = spending(),
        income = total(TransactionType.INCOME),
        transfers = total(TransactionType.TRANSFER),
        excludedDraftCount = excludedDraftCount,
        dailySpendingCategory = dailySpendingCategory,
        dailySpending = dailySpending(),
    )
}

/**
 * Builds the confirmed spending of the half-open range
 * `[fromEpochMillis, throughExclusiveEpochMillis)` from [records].
 *
 * The two bounds are explicit parameters so a caller can pin any range and any zone
 * without a clock, and so the range a figure describes is never implied.
 *
 * [records] keeps its input order for the counting and for the sums; the reported bound of
 * an overflowed total is deliberately independent of that order. The breakdown order is by
 * descending bucket total, then by stable identity (named categories by id, then the
 * uncategorised bucket), so it never depends on the input order of a tie.
 */
fun summarizeConfirmedSpending(
    records: List<TransactionEntity>,
    fromEpochMillis: Long,
    throughExclusiveEpochMillis: Long,
): LedgerSpending {
    require(fromEpochMillis < throughExclusiveEpochMillis) {
        "A spending range must start before it ends."
    }
    val fold = LedgerRangeFold(fromEpochMillis, throughExclusiveEpochMillis)
    records.forEach { fold.add(it) }
    return fold.spending()
}

/**
 * The confirmed total of one record type inside a range.
 *
 * The same rules as the spending figure: only confirmed records of that type inside the
 * range are counted, the sum is whole dong, and a sum that passes the largest whole-dong
 * value is reported as a proven lower bound with the overflow marked.
 */
fun summarizeConfirmedTotal(
    records: List<TransactionEntity>,
    type: TransactionType,
    fromEpochMillis: Long,
    throughExclusiveEpochMillis: Long,
): LedgerTotal {
    require(fromEpochMillis < throughExclusiveEpochMillis) {
        "A total range must start before it ends."
    }
    val fold = LedgerRangeFold(fromEpochMillis, throughExclusiveEpochMillis)
    records.forEach { fold.add(it) }
    return fold.total(type)
}

/**
 * The confirmed spending of one calendar day of a range, in the range's own zone.
 *
 * The day a record belongs to is the calendar day its instant falls on in that zone, so
 * the bucket is computed from the stored instant and the stated zone and never from the
 * machine's own clock. Only the days that actually have spending are reported.
 */
fun summarizeDailySpending(
    records: List<TransactionEntity>,
    fromEpochMillis: Long,
    throughExclusiveEpochMillis: Long,
    zone: TimeZone,
): List<LedgerDailySpending> {
    require(fromEpochMillis < throughExclusiveEpochMillis) {
        "A spending range must start before it ends."
    }
    val fold = LedgerRangeFold(fromEpochMillis, throughExclusiveEpochMillis, zone)
    records.forEach { fold.add(it) }
    return fold.dailySpending()
}

/** Largest bucket total first, then the stable category id, then the uncategorised bucket. */
private val BREAKDOWN_ORDER =
    compareByDescending<LedgerCategorySpending> { it.totalVnd }
        .thenBy { it.categoryId == null }
        .thenBy { it.categoryId ?: 0L }

/**
 * A positive whole-dong sum that cannot wrap.
 *
 * Every add goes through the shared [VndMoney.checkedAddToTotal], so the ledger's one
 * overflow rule is what the aggregate obeys. When that rule refuses an add, the running
 * total becomes the largest whole-dong value — a proven lower bound — and the state is
 * recorded instead of the sum being wrapped; the remaining amounts are not added, because a
 * total that already passed the limit can only pass it again.
 */
private class RunningTotal {
    private var total = 0L
    private var hasOverflowed = false
    private var count = 0

    val totalVnd: Long get() = total
    val overflowed: Boolean get() = hasOverflowed
    val recordCount: Int get() = count

    fun add(amountVnd: Long) {
        count++
        if (hasOverflowed) return
        total = try {
            VndMoney.checkedAddToTotal(total, amountVnd)
        } catch (_: ArithmeticException) {
            hasOverflowed = true
            Long.MAX_VALUE
        }
    }
}
