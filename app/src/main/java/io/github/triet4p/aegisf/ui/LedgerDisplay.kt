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
import io.github.triet4p.aegisf.data.TransactionType

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
