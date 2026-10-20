package io.github.triet4p.aegisf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.triet4p.aegisf.data.LedgerAggregate
import io.github.triet4p.aegisf.data.LedgerCategorySpending
import io.github.triet4p.aegisf.data.LedgerDateRange
import io.github.triet4p.aegisf.data.LedgerSpending
import io.github.triet4p.aegisf.data.VndMoney
import io.github.triet4p.aegisf.data.WorkflowQueryService
import kotlinx.coroutines.delay
import java.util.TimeZone

/**
 * This calendar month's confirmed spending, on the home screen: the exact whole-dong total
 * and one row per category it is made of.
 *
 * The panel reads the shared confirmed-ledger aggregate of the same database file every
 * launch opens, so saving, correcting, or deleting a record anywhere changes the total and
 * the breakdown on its own, and closing and reopening the app shows the same figures
 * because they are derived from the same stored records. The figure itself is the shared
 * aggregate, not a second summary of its own: the same exact whole-dong total, the same
 * exclusion of income, transfers, and drafts, and the same category breakdown every other
 * reader of the ledger gets.
 *
 * The month is the device-local calendar month from [ManualDateTime], the same zone the
 * editor writes dates in, and the panel names it (`October 2026`) so the figure is never
 * *implied* to be some other month. The month it names is the one the device is in now: the
 * reading is taken when the panel first appears, when the screen resumes after the app was
 * in the background (so a phone that crossed a month boundary, or moved, while the app was
 * away reports the month it is actually in), and at the first midnight of the next month for
 * a screen that is left open across it. It counts the whole ledger: the Saved transactions
 * filters are a browsing preference held by that screen, and they neither read nor write
 * anything here, so the summary can never be silently narrowed to a filtered subset. It is
 * also deliberately not a second ledger — it stores nothing, and every figure it shows is
 * recomputed from the stored rows.
 *
 * The layout is one compact card: the month, the total, the count it is made of, and the
 * category rows. The breakdown only lists categories that actually have spending this month,
 * so an ordinary month adds a handful of short rows to a home screen that already scrolls.
 */
@Composable
fun CurrentMonthExpenseSummary(
    queryService: WorkflowQueryService,
    modifier: Modifier = Modifier,
) {
    // The month on screen is a snapshot of the device clock and the device's zone, taken
    // together so the heading, the range, and the records can never come from different
    // readings of either. It is re-taken when the screen resumes — an app that spent time in
    // the background reports the month (and the zone) the device is in now, not the one it
    // computed before the pause — and at the next month boundary, so a screen left open
    // across the first midnight of a month follows the calendar without a restart.
    var shownMonth by remember { mutableStateOf(ShownMonth.read()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                shownMonth = ShownMonth.read()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(shownMonth) {
        val wait = shownMonth.endExclusiveEpochMillis - System.currentTimeMillis()
        delay(wait.coerceAtLeast(1L))
        shownMonth = ShownMonth.read()
    }

    // The figure is the shared confirmed-ledger aggregate of the shown month: the same
    // exact whole-dong sum, the same exclusion of income, transfers, and drafts, and the
    // same category breakdown every other reader of the ledger gets. Hoisted so the
    // collected stream has a stable identity: building one on every recomposition would
    // restart the collection and blank the figures each time.
    val range = remember(shownMonth) {
        LedgerDateRange.closed(
            fromEpochMillis = shownMonth.startEpochMillis,
            throughExclusiveEpochMillis = shownMonth.endExclusiveEpochMillis,
            zoneId = shownMonth.zoneId,
        )
    }
    val aggregateFlow = remember(queryService, range) {
        queryService.observeConfirmedLedgerAggregate(range)
    }
    // Null until the first emission: it distinguishes "still reading the ledger" from
    // "this month really has nothing in it".
    val aggregate: LedgerAggregate? by aggregateFlow.collectAsState(initial = null)

    ExpenseSummaryCard(
        monthLabel = shownMonth.label,
        summary = aggregate?.spending,
        loaded = aggregate != null,
        modifier = modifier,
    )
}

/**
 * The month the panel is showing: the half-open local range it counts and the heading it
 * names, read from one clock reading and one zone reading so the label, the range, and the
 * rows can never disagree — and so a resume can replace all three together, even when the
 * clock reading alone happens to be identical to the last one (a device that changed zone
 * while the app was away).
 */
private data class ShownMonth(
    val startEpochMillis: Long,
    val endExclusiveEpochMillis: Long,
    val zoneId: String,
    val label: String,
) {
    companion object {
        /** The device-local month right now, in the zone the device is in right now. */
        fun read(): ShownMonth {
            val nowEpochMillis = System.currentTimeMillis()
            val zone = TimeZone.getDefault()
            val startEpochMillis = ManualDateTime.monthStartEpochMillis(nowEpochMillis, zone)
            return ShownMonth(
                startEpochMillis = startEpochMillis,
                endExclusiveEpochMillis = ManualDateTime.nextMonthStartEpochMillis(startEpochMillis, zone),
                zoneId = zone.id,
                label = ManualDateTime.monthFieldText(startEpochMillis, zone),
            )
        }
    }
}

/**
 * The card itself: month, total, how many records make it up, and the category breakdown.
 *
 * A total that ran past the largest whole-dong value is shown with a proven lower bound and
 * says so in the error color, instead of being wrapped, invented, or crashing the screen.
 */
@Composable
private fun ExpenseSummaryCard(
    monthLabel: String,
    summary: LedgerSpending?,
    loaded: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "THIS MONTH",
                style = MaterialTheme.typography.labelLarge,
                letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = monthLabel,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            when {
                !loaded -> Text(
                    text = "Reading the stored records…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )

                summary == null || summary.recordCount == 0 -> Text(
                    text = "No confirmed expenses this month.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )

                else -> ExpenseSummaryFigures(summary = summary)
            }
            Text(
                text = "Confirmed expenses only. Income, internal transfers, and drafts are " +
                    "not included, records outside $monthLabel are not counted, and the " +
                    "Saved transactions filters do not change this summary.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun ExpenseSummaryFigures(
    summary: LedgerSpending,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = "Expenses",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(
            text = amountText(summary.totalVnd, summary.overflowed),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
    Text(
        text = expenseCountLabel(summary.recordCount),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
    )
    if (summary.overflowed) {
        Text(
            text = "This month's total passed the largest whole-dong value. The amount above " +
                "is a guaranteed lower bound, not the exact total.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    summary.categoryTotals.forEach { bucket ->
        DetailRow(
            label = bucketLabel(bucket),
            value = amountText(bucket.totalVnd, bucket.overflowed),
        )
    }
}

/**
 * The category a spending bucket is filed under, as shown on screen.
 *
 * An archived category is named as archived rather than hidden, because the records filed
 * under it keep it; a bucket with no category is spending filed under no category, which is
 * a bucket of its own and is never merged into a named one.
 */
private fun bucketLabel(bucket: LedgerCategorySpending): String = when {
    bucket.categoryId == null -> "No category"
    bucket.categoryName == null -> "Unknown category"
    bucket.categoryArchived -> "${bucket.categoryName} (archived)"
    else -> bucket.categoryName
}

/** "1 confirmed expense" / "N confirmed expenses" — what the total above is made of. */
private fun expenseCountLabel(count: Int): String =
    if (count == 1) "1 confirmed expense" else "$count confirmed expenses"

/**
 * A whole-dong amount for the summary. An amount that could not be summed exactly is marked
 * as the lower bound it is, so a reader can never mistake it for the exact figure.
 */
private fun amountText(amountVnd: Long, overflowed: Boolean): String {
    val formatted = VndMoney.formatAmount(amountVnd)
    return if (overflowed) "$formatted or more" else formatted
}
