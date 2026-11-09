package io.github.triet4p.aegisf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.ConfirmationStatus
import io.github.triet4p.aegisf.data.RecordSource
import io.github.triet4p.aegisf.data.TransactionEntity
import io.github.triet4p.aegisf.data.TransactionType
import io.github.triet4p.aegisf.ui.text.AegisStrings
import java.util.Locale
import java.util.TimeZone

/**
 * How a stored ledger record is worded on screen: the English names of the stored enums, the
 * category label, and the one label/value row.
 *
 * Every screen that shows a record — the list, the detail view, and the editor's confirmation
 * panel — reads these, so the same stored value can never be described two different ways.
 */

/** The English name of a stored [TransactionType]. */
internal fun typeLabel(type: TransactionType): String = when (type) {
    TransactionType.EXPENSE -> "Expense"
    TransactionType.INCOME -> "Income"
    TransactionType.TRANSFER -> "Internal transfer"
}

/** The English name of a stored [ConfirmationStatus]. */
internal fun statusLabel(status: ConfirmationStatus): String = when (status) {
    ConfirmationStatus.CONFIRMED -> "Confirmed"
    ConfirmationStatus.DRAFT -> "Draft"
}

/** The English name of a stored [RecordSource]. */
internal fun sourceLabel(source: RecordSource): String = when (source) {
    RecordSource.MANUAL -> "Manual"
    RecordSource.BANK_NOTIFICATION -> "Bank notification"
}

/**
 * The category a record is filed under, as shown on screen. An archived category is named as
 * archived rather than hidden, because the records filed under it keep it: the label must not
 * suggest the link is gone, and it must not suggest the category is still assignable either.
 */
internal fun categoryLabel(category: CategoryEntity?): String = when {
    category == null -> "No category"
    category.archived -> "${category.name} (archived)"
    else -> category.name
}

/** A row-facing category name that keeps an unresolved stored link distinct from no category. */
internal fun categoryDisplayName(categoryId: Long?, category: CategoryEntity?): String = when {
    categoryId == null -> "No category"
    category == null -> "Unknown category"
    else -> category.name
}

/** One stored field: its name on the left, its value on the right. */
@Composable
internal fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

/** One calendar day of a list, in the read's own newest-first order. */
internal data class TransactionDayGroup(
    val dayText: String,
    val records: List<TransactionEntity>,
)

/**
 * Groups the rows of one bounded page by the device-local day they happened on.
 *
 * The group order is the order the rows arrived in, so a page still reads newest first and
 * a group never re-sorts its own rows. Two rows a millisecond apart across local midnight
 * fall into two groups, because they happened on two days.
 */
internal fun groupTransactionsByDay(
    records: List<TransactionEntity>,
    zone: TimeZone = TimeZone.getDefault(),
): List<TransactionDayGroup> {
    val groups = LinkedHashMap<String, MutableList<TransactionEntity>>()
    records.forEach { record ->
        val dayText = ManualDateTime.dateFieldText(record.occurredAtEpochMillis, zone)
        groups.getOrPut(dayText) { mutableListOf() }.add(record)
    }
    return groups.map { (dayText, rows) -> TransactionDayGroup(dayText, rows) }
}

/**
 * The heading of one day group: "Today", "Yesterday", or the calendar day it names.
 *
 * The comparison is between the group's own device-local day text and the device clock's
 * own day text, so the words stay right across midnight without re-deriving the day from a
 * fixed 24-hour step.
 */
internal fun dayHeadingText(
    dayText: String,
    strings: AegisStrings,
    nowEpochMillis: Long = System.currentTimeMillis(),
    zone: TimeZone = TimeZone.getDefault(),
): String {
    val todayText = ManualDateTime.dateFieldText(nowEpochMillis, zone)
    val yesterdayText = ManualDateTime.dateFieldText(
        ManualDateTime.dayStartEpochMillis(todayText, zone) - 1L,
        zone,
    )
    return when (dayText) {
        todayText -> strings.today
        yesterdayText -> strings.yesterday
        else -> dayMonthYearText(
            ManualDateTime.dayStartEpochMillis(dayText, zone),
            zone,
        )
    }
}

/** The stored category named in this language; an archived one keeps its marker. */
internal fun localizedCategoryName(
    category: CategoryEntity?,
    strings: AegisStrings,
): String = when {
    category == null -> strings.noCategory
    category.archived -> "${category.name} (${strings.archivedLabel.lowercase(Locale.ROOT)})"
    else -> category.name
}

/**
 * The category a row is filed under, keeping "no category", "unknown category" and a named
 * category distinct: an unresolved stored link is not the same thing as no link at all.
 */
internal fun localizedRowCategory(
    categoryId: Long?,
    category: CategoryEntity?,
    strings: AegisStrings,
): String = when {
    categoryId == null -> strings.noCategory
    category == null -> strings.unknownCategory
    else -> localizedCategoryName(category, strings)
}

/** The direction sign a receipt amount carries: out, in, or neither for an internal move. */
internal fun amountPrefix(type: TransactionType): String = when (type) {
    TransactionType.EXPENSE -> "-"
    TransactionType.INCOME -> "+"
    TransactionType.TRANSFER -> ""
}
