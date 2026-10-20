package io.github.triet4p.aegisf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.LedgerRepository
import io.github.triet4p.aegisf.data.TransactionEntity
import io.github.triet4p.aegisf.data.VndMoney
import io.github.triet4p.aegisf.data.WorkflowQueryService
import kotlinx.coroutines.launch

/**
 * One stored record: everything the ledger holds for it, and the two things the user can do
 * with it — correct it, or delete it.
 *
 * The values are read from the database when the screen opens rather than carried in from the
 * list, so what is on screen is the stored row, including a correction that was just saved.
 * The record is named as stored: its amount, type, moment, description, category (marked when
 * that category has since been archived), confirmation, provenance, and creation time.
 *
 * Correcting is a separate screen that writes nothing until it is saved. Deleting is the only
 * write here, and it happens only after a confirmation that names the record it will remove:
 * opening that confirmation, backing out of this screen, or dismissing the dialog removes
 * nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailScreen(
    repository: LedgerRepository,
    queryService: WorkflowQueryService,
    transactionId: Long,
    onEdit: () -> Unit,
    onDeleted: () -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var state by remember(transactionId) { mutableStateOf<DetailState>(DetailState.Loading) }
    LaunchedEffect(transactionId) {
        val record = queryService.findLedgerRecord(transactionId)
        state = if (record == null) {
            DetailState.Missing
        } else {
            DetailState.Found(
                record = record,
                category = record.categoryId?.let { queryService.findCategory(it) },
            )
        }
    }

    var confirmingDelete by rememberSaveable(transactionId) { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    BackHandler { if (!deleting) onNavigateUp() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Transaction") },
                navigationIcon = {
                    TextButton(onClick = onNavigateUp, enabled = !deleting) { Text("Back") }
                },
            )
        },
    ) { innerPadding ->
        when (val current = state) {
            DetailState.Loading -> LoadingRecord(Modifier.padding(innerPadding))

            DetailState.Missing -> MissingRecord(Modifier.padding(innerPadding))

            is DetailState.Found -> TransactionDetail(
                record = current.record,
                category = current.category,
                deleting = deleting,
                contentPadding = innerPadding,
                onEdit = onEdit,
                onDelete = { confirmingDelete = true },
            )
        }
    }

    val found = state as? DetailState.Found
    if (confirmingDelete && found != null) {
        DeleteConfirmationDialog(
            record = found.record,
            category = found.category,
            deleting = deleting,
            onDismiss = { if (!deleting) confirmingDelete = false },
            onConfirm = {
                // The row was read from storage, so the id the delete names is the row that
                // was shown; nothing else in the ledger is touched.
                deleting = true
                scope.launch {
                    repository.deleteTransaction(transactionId)
                    onDeleted()
                }
            },
        )
    }
}

/** What the screen knows about the record it was asked to show. */
private sealed interface DetailState {
    data object Loading : DetailState

    data object Missing : DetailState

    data class Found(val record: TransactionEntity, val category: CategoryEntity?) : DetailState
}

@Composable
private fun TransactionDetail(
    record: TransactionEntity,
    category: CategoryEntity?,
    deleting: Boolean,
    contentPadding: PaddingValues,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = VndMoney.formatAmount(record.amountVnd),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        DetailRow("Type", typeLabel(record.type))
        DetailRow(
            label = "When",
            value = ManualDateTime.formatMoment(record.occurredAtEpochMillis) + " device time",
        )
        if (record.description.isNotEmpty()) {
            DetailRow("Description", record.description)
        }
        DetailRow("Category", categoryLabel(category))
        DetailRow("Status", statusLabel(record.confirmation))
        DetailRow("Source", sourceLabel(record.source))
        DetailRow("Recorded at", ManualDateTime.formatMoment(record.createdAtEpochMillis))
        if (record.updatedAtEpochMillis != record.createdAtEpochMillis) {
            DetailRow("Last corrected", ManualDateTime.formatMoment(record.updatedAtEpochMillis))
        }

        Button(
            onClick = onEdit,
            enabled = !deleting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Edit")
        }
        OutlinedButton(
            onClick = onDelete,
            enabled = !deleting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Delete")
        }
    }
}

/**
 * The confirmation that names the record before it is removed. It repeats the stored amount,
 * type, description, moment, and category, so the user confirms the row they were looking at
 * and not an unlabelled "are you sure".
 */
@Composable
private fun DeleteConfirmationDialog(
    record: TransactionEntity,
    category: CategoryEntity?,
    deleting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete this transaction?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = VndMoney.formatAmount(record.amountVnd) + " · " +
                        typeLabel(record.type),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (record.description.isNotEmpty()) {
                    Text(
                        text = record.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Text(
                    text = ManualDateTime.formatMoment(record.occurredAtEpochMillis) +
                        " device time",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = categoryLabel(category),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Deleting removes this record from the ledger. This cannot be " +
                        "undone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !deleting,
            ) {
                Text(if (deleting) "Deleting…" else "Delete transaction")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !deleting) { Text("Cancel") }
        },
    )
}

@Composable
private fun LoadingRecord(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 24.dp),
    ) {
        Text(
            text = "Loading transaction…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MissingRecord(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "This transaction no longer exists",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "It is not in the ledger, so there is nothing to show or delete here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
