package io.github.triet4p.aegisf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.ConfirmationStatus
import io.github.triet4p.aegisf.data.DeleteTransactionRequest
import io.github.triet4p.aegisf.data.TransactionEntity
import io.github.triet4p.aegisf.data.TransactionType
import io.github.triet4p.aegisf.data.VndMoney
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowCommandOutcome
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.ui.components.AegisGlyph
import io.github.triet4p.aegisf.ui.components.AegisIcon
import io.github.triet4p.aegisf.ui.components.AegisMoneyText
import io.github.triet4p.aegisf.ui.components.AegisStatus
import io.github.triet4p.aegisf.ui.components.AegisStatusChip
import io.github.triet4p.aegisf.ui.text.AegisStrings
import io.github.triet4p.aegisf.ui.text.LocalAegisStrings
import io.github.triet4p.aegisf.ui.theme.LocalAegisSemanticColors
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * One stored record as a digital receipt: its exact amount, its provenance, and the two
 * guarded routes that can change it.
 *
 * The screen reads the stored row when it opens rather than carrying it in from a list, so
 * what it shows is the stored record — including a correction that was just saved. The
 * amount is the exact whole-dong value with its direction; the rows name the type, the
 * category (marked when archived), the device-local moment, the provenance, the note, the
 * record id, and when it was recorded and last corrected. Nothing is invented: a funding
 * account or counterparty the ledger does not store is not shown.
 *
 * Edit is a separate form that writes nothing until saved. Delete is the only write on this
 * screen: it asks first, names the record it will remove, and only an explicit confirmation
 * reaches the command boundary. A record that moved on under this screen is a conflict, not
 * an overwrite, and the screen offers a reload instead of pretending the delete happened.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailScreen(
    commandService: WorkflowCommandService,
    queryService: WorkflowQueryService,
    transactionId: Long,
    onEdit: () -> Unit,
    onDeleted: () -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAegisStrings.current
    val zone = remember { TimeZone.getDefault() }
    var state by remember(transactionId) { mutableStateOf<DetailState>(DetailState.Loading) }
    var reloadToken by remember { mutableIntStateOf(0) }
    LaunchedEffect(transactionId, reloadToken) {
        state = DetailState.Loading
        state = try {
            val record = queryService.findLedgerRecord(transactionId)
            if (record == null) {
                DetailState.Missing
            } else {
                DetailState.Found(
                    record = record,
                    category = record.categoryId?.let { queryService.findCategory(it) },
                )
            }
        } catch (_: Exception) {
            DetailState.Error
        }
    }

    var confirmingDelete by rememberSaveable(transactionId) { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    // The key names this removal request: the same request under the same key removes one
    // record, so a retry cannot remove a second one.
    val deleteKey = rememberSaveable(transactionId) { "delete:$transactionId" }
    val scope = rememberCoroutineScope()

    BackHandler { if (!deleting) onNavigateUp() }

    val found = state as? DetailState.Found
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(strings.detailTitle) },
                navigationIcon = {
                    IconButton(
                        onClick = { if (!deleting) onNavigateUp() },
                        enabled = !deleting,
                        modifier = Modifier.semantics {
                            contentDescription = strings.back
                        },
                    ) {
                        AegisIcon(
                            glyph = AegisGlyph.ARROW_BACK,
                            tint = MaterialTheme.colorScheme.onSurface,
                            size = 22.dp,
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (found != null) {
                DetailActions(
                    deleting = deleting,
                    strings = strings,
                    onEdit = onEdit,
                    onDelete = { confirmingDelete = true },
                )
            }
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        when (val current = state) {
            DetailState.Loading -> DetailMessage(
                title = strings.detailLoading,
                body = null,
                contentPadding = innerPadding,
            )

            DetailState.Missing -> DetailMessage(
                title = strings.detailMissingTitle,
                body = strings.detailMissingBody,
                contentPadding = innerPadding,
            )

            DetailState.Error -> DetailMessage(
                title = strings.detailReadErrorTitle,
                body = strings.detailReadErrorBody,
                contentPadding = innerPadding,
                actionLabel = strings.retry,
                onAction = { reloadToken += 1 },
            )

            is DetailState.Found -> TransactionReceipt(
                record = current.record,
                category = current.category,
                deleteError = deleteError,
                contentPadding = innerPadding,
                strings = strings,
                zone = zone,
            )
        }
    }

    if (confirmingDelete && found != null) {
        DeleteConfirmationDialog(
            record = found.record,
            category = found.category,
            deleting = deleting,
            strings = strings,
            zone = zone,
            onDismiss = { if (!deleting) confirmingDelete = false },
            onConfirm = {
                deleting = true
                deleteError = null
                scope.launch {
                    val outcome = try {
                        commandService.deleteTransaction(
                            scope = WorkflowCallerScope.User,
                            request = DeleteTransactionRequest(
                                idempotencyKey = deleteKey,
                                transactionId = transactionId,
                                expectedRevision = found.record.revision,
                            ),
                        )
                    } catch (_: Exception) {
                        null
                    }
                    when (outcome) {
                        is WorkflowCommandOutcome.Applied,
                        is WorkflowCommandOutcome.NoChange,
                        -> onDeleted()

                        is WorkflowCommandOutcome.Conflict -> {
                            deleting = false
                            confirmingDelete = false
                            deleteError = strings.staleConflict
                            reloadToken += 1
                        }

                        is WorkflowCommandOutcome.NotFound -> {
                            deleting = false
                            confirmingDelete = false
                            deleteError = strings.recordGone
                            reloadToken += 1
                        }

                        else -> {
                            deleting = false
                            confirmingDelete = false
                            deleteError = strings.deleteFailed
                        }
                    }
                }
            },
        )
    }
}

/** What the receipt knows about the record it was asked to show. */
private sealed interface DetailState {
    data object Loading : DetailState
    data object Missing : DetailState
    data object Error : DetailState
    data class Found(val record: TransactionEntity, val category: CategoryEntity?) : DetailState
}

/**
 * The receipt: the amount and its identity at the top, the provenance below, and any
 * deletion conflict in plain words.
 *
 * The amount is the exact stored magnitude with the type's direction, and the type is named
 * in words as well, so the direction never depends on a colour or a sign alone. The receipt
 * says "Confirmed" for a settled record and "Draft" for one that is not; only confirmed
 * records are counted by the reports.
 */
@Composable
private fun TransactionReceipt(
    record: TransactionEntity,
    category: CategoryEntity?,
    deleteError: String?,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    strings: AegisStrings,
    zone: TimeZone,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            CategoryPalette.color(
                                categoryId = record.categoryId,
                                darkTheme = androidx.compose.foundation.isSystemInDarkTheme(),
                                archived = category?.archived == true,
                            ).copy(alpha = 0.16f),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    CategorySwatch(
                        categoryId = record.categoryId,
                        archived = category?.archived == true,
                        directory = true,
                        size = 22.dp,
                    )
                }
                Text(
                    text = record.description.takeIf { it.isNotEmpty() }
                        ?: strings.typeName(record.type),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                )
                AegisMoneyText(
                    amountVnd = record.amountVnd,
                    prefix = amountPrefix(record.type),
                    style = MaterialTheme.typography.headlineSmall,
                    color = when (record.type) {
                        TransactionType.EXPENSE -> LocalAegisSemanticColors.current.expense
                        TransactionType.INCOME -> LocalAegisSemanticColors.current.income
                        TransactionType.TRANSFER -> LocalAegisSemanticColors.current.transfer
                    },
                )
                if (record.confirmation == ConfirmationStatus.CONFIRMED) {
                    AegisStatusChip(status = AegisStatus.CONFIRMED)
                } else {
                    AegisStatusChip(
                        status = AegisStatus.NEEDS_REVIEW,
                        label = strings.statusDraft,
                    )
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DetailRow(strings.detailType, strings.typeName(record.type))
                DetailRow(
                    strings.detailCategory,
                    if (record.categoryId == null) {
                        strings.noCategory
                    } else {
                        localizedCategoryName(category, strings)
                    },
                )
                DetailRow(
                    strings.detailWhen,
                    weekdayMomentText(record.occurredAtEpochMillis, strings.language, zone),
                )
                DetailRow(strings.detailSource, strings.sourceName(record.source))
                if (record.description.isNotEmpty()) {
                    DetailRow(strings.detailNote, record.description)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DetailRow(strings.detailRecordId, record.id.toString())
                DetailRow(
                    strings.detailRecordedAt,
                    ManualDateTime.formatMoment(record.createdAtEpochMillis, zone),
                )
                if (record.updatedAtEpochMillis != record.createdAtEpochMillis) {
                    DetailRow(
                        strings.detailCorrectedAt,
                        ManualDateTime.formatMoment(record.updatedAtEpochMillis, zone),
                    )
                }
            }
        }

        if (deleteError != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Text(
                    text = deleteError,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        Text(
            text = strings.storedOnDevice,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The two guarded routes, anchored above the gesture bar: edit is the filled action, delete
 * is outlined and destructive. Neither is available while a removal is in flight.
 */
@Composable
private fun DetailActions(
    deleting: Boolean,
    strings: AegisStrings,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val largeText = LocalDensity.current.fontScale >= 1.3f
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 3.dp,
    ) {
        if (largeText) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                EditAction(deleting = deleting, strings = strings, onEdit = onEdit)
                DeleteAction(deleting = deleting, strings = strings, onDelete = onDelete)
            }
        } else {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                EditAction(
                    deleting = deleting,
                    strings = strings,
                    onEdit = onEdit,
                    modifier = Modifier.weight(1f),
                )
                DeleteAction(
                    deleting = deleting,
                    strings = strings,
                    onDelete = onDelete,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun EditAction(
    deleting: Boolean,
    strings: AegisStrings,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onEdit,
        enabled = !deleting,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp),
    ) {
        Text(strings.detailEdit)
    }
}

@Composable
private fun DeleteAction(
    deleting: Boolean,
    strings: AegisStrings,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onDelete,
        enabled = !deleting,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp),
    ) {
        AegisIcon(
            glyph = AegisGlyph.CROSS,
            tint = MaterialTheme.colorScheme.error,
            size = 16.dp,
        )
        Text(
            text = strings.detailDelete,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/**
 * The confirmation that names the record before it is removed. It repeats the stored amount,
 * type, description, moment and category, so the user confirms the row they were looking at
 * and not an unlabelled "are you sure".
 */
@Composable
private fun DeleteConfirmationDialog(
    record: TransactionEntity,
    category: CategoryEntity?,
    deleting: Boolean,
    strings: AegisStrings,
    zone: TimeZone,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.detailDeleteQuestion) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = amountPrefix(record.type) +
                        VndMoney.formatAmount(record.amountVnd, " ₫") +
                        " · " + strings.typeName(record.type),
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
                    text = weekdayMomentText(
                        record.occurredAtEpochMillis,
                        strings.language,
                        zone,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (record.categoryId == null) {
                        strings.noCategory
                    } else {
                        localizedCategoryName(category, strings)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = strings.detailDeleteBody,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !deleting) {
                Text(if (deleting) strings.detailDeleting else strings.detailDeleteConfirm)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !deleting) { Text(strings.cancel) }
        },
    )
}

/** A plain centered message for the receipt's loading, missing and failed states. */
@Composable
private fun DetailMessage(
    title: String,
    body: String?,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (body != null) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (actionLabel != null && onAction != null) {
            OutlinedButton(
                onClick = onAction,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(actionLabel)
            }
        }
    }
}
