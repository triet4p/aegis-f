package io.github.triet4p.aegisf.ui

import io.github.triet4p.aegisf.data.ConfirmationStatus
import io.github.triet4p.aegisf.data.TransactionEntity
import io.github.triet4p.aegisf.data.TransactionType
import io.github.triet4p.aegisf.data.VndMoney

/**
 * The confirmed spending of one device-local calendar month, grouped by the category its
 * records are filed under.
 *
 * This is a derived view of the stored rows, never a second source of truth: it is built
 * from the same live Room stream the saved-records list reads, so a new record, a
 * correction, or a deletion is reflected on its own. Only records that are
 * [TransactionType.EXPENSE], are [ConfirmationStatus.CONFIRMED], and occurred in
 * `[monthStartEpochMillis, monthEndExclusiveEpochMillis)` enter it — income and internal
 * transfers can never inflate spending, a draft is not yet spending, a row in another month
 * belongs to that month, and a deleted row is simply absent from the stream.
 *
 * Grouping is by the stable category id and not by name: a renamed category keeps its
 * spending on the same bucket and an archived one keeps the spending its records
 * contributed, which is what keeps historical attribution intact. Displaying the name, the
 * `(archived)` marker, and `No category` stays a screen concern.
 *
 * Money is summed with [VndMoney.checkedAddToTotal], never with unchecked arithmetic or a
 * floating-point conversion, so a total that passes the largest whole-dong value cannot wrap
 * into a small or negative number. That case is reported rather than hidden: [totalOverflowed]
 * (and [CategoryExpenseTotal.overflowed]) is true, and the amount is the largest whole-dong
 * value, which is a proven lower bound — a total made of whole dong above it is certainly at
 * least that — never a fabricated or wrapped figure, and never a crash.
 */
data class ExpenseSummary(
    val monthStartEpochMillis: Long,
    val monthEndExclusiveEpochMillis: Long,
    /** How many stored records the month actually contains: the denominator of the total. */
    val expenseCount: Int,
    /** The exact month total, or a proven lower bound while [totalOverflowed] is true. */
    val totalVnd: Long,
    val totalOverflowed: Boolean,
    /** One bucket per category that has spending this month, largest first. */
    val categoryTotals: List<CategoryExpenseTotal>,
)

/**
 * The confirmed spending of one category in a month.
 *
 * [categoryId] is null for spending filed under no category: uncategorised spending is a
 * bucket of its own, never merged into a named category and never dropped from the
 * breakdown, so its share of the total stays visible.
 */
data class CategoryExpenseTotal(
    val categoryId: Long?,
    /** The exact bucket total, or a proven lower bound while [overflowed] is true. */
    val totalVnd: Long,
    val overflowed: Boolean,
    val recordCount: Int,
)

/**
 * Builds the summary of the half-open device-local month
 * `[monthStartEpochMillis, monthEndExclusiveEpochMillis)` from [records].
 *
 * The two bounds come from [ManualDateTime.monthStartEpochMillis] and
 * [ManualDateTime.nextMonthStartEpochMillis] in production, so the month is the one the
 * device clock shows; they are explicit parameters here so a test can pin any month and zone
 * without a clock.
 *
 * [records] keeps its input order for the counting and for the sums; the reported bound of an
 * overflowed total is deliberately independent of that order. The breakdown order is by
 * descending bucket total, then by stable identity (named categories by id, then the
 * uncategorised bucket), so it never depends on the input order of a tie.
 */
fun summarizeMonthExpenses(
    records: List<TransactionEntity>,
    monthStartEpochMillis: Long,
    monthEndExclusiveEpochMillis: Long,
): ExpenseSummary {
    require(monthStartEpochMillis < monthEndExclusiveEpochMillis) {
        "A month range must start before it ends."
    }
    val monthTotal = RunningTotal()
    val totalsByCategory = LinkedHashMap<Long?, RunningTotal>()
    var expenseCount = 0
    for (record in records) {
        if (record.type != TransactionType.EXPENSE) continue
        if (record.confirmation != ConfirmationStatus.CONFIRMED) continue
        if (record.occurredAtEpochMillis < monthStartEpochMillis) continue
        if (record.occurredAtEpochMillis >= monthEndExclusiveEpochMillis) continue
        expenseCount++
        monthTotal.add(record.amountVnd)
        totalsByCategory.getOrPut(record.categoryId) { RunningTotal() }.add(record.amountVnd)
    }
    return ExpenseSummary(
        monthStartEpochMillis = monthStartEpochMillis,
        monthEndExclusiveEpochMillis = monthEndExclusiveEpochMillis,
        expenseCount = expenseCount,
        totalVnd = monthTotal.totalVnd,
        totalOverflowed = monthTotal.overflowed,
        categoryTotals = totalsByCategory
            .map { (categoryId, total) ->
                CategoryExpenseTotal(
                    categoryId = categoryId,
                    totalVnd = total.totalVnd,
                    overflowed = total.overflowed,
                    recordCount = total.recordCount,
                )
            }
            .sortedWith(BREAKDOWN_ORDER),
    )
}

/** Largest bucket total first, then the stable category id, then the uncategorised bucket. */
private val BREAKDOWN_ORDER =
    compareByDescending<CategoryExpenseTotal> { it.totalVnd }
        .thenBy { it.categoryId == null }
        .thenBy { it.categoryId ?: 0L }

/**
 * A positive whole-dong sum that cannot wrap.
 *
 * Every add goes through the shared [VndMoney.checkedAddToTotal], so the ledger's one
 * overflow rule is what the summary obeys. When that rule refuses an add, the running total
 * becomes the largest whole-dong value — a proven lower bound — and the state is recorded
 * instead of the sum being wrapped; the remaining amounts are not added, because a total that
 * already passed the limit can only pass it again.
 */
private class RunningTotal {
    var totalVnd = 0L
        private set

    var overflowed = false
        private set

    var recordCount = 0
        private set

    fun add(amountVnd: Long) {
        recordCount++
        if (overflowed) return
        val increased = try {
            VndMoney.checkedAddToTotal(totalVnd, amountVnd)
        } catch (_: ArithmeticException) {
            null
        }
        if (increased == null) {
            // The shared checked addition refused the sum, so the month total is past the
            // largest whole-dong value: Long.MAX_VALUE is then a *proven* lower bound, because
            // every amount is at least one dong, and unlike the partial sum it does not depend
            // on the order the rows arrived in. Reporting that bound beats wrapping, inventing
            // a figure, or crashing the screen.
            totalVnd = Long.MAX_VALUE
            overflowed = true
        } else {
            totalVnd = increased
        }
    }
}
