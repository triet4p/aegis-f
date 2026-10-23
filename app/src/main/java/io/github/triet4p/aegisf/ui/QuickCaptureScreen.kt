package io.github.triet4p.aegisf.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.triet4p.aegisf.data.CaptureContextSummary
import io.github.triet4p.aegisf.data.CaptureKind
import io.github.triet4p.aegisf.data.CaptureMessageEntity
import io.github.triet4p.aegisf.data.CaptureSourceKind
import io.github.triet4p.aegisf.data.DeleteCaptureRequest
import io.github.triet4p.aegisf.data.LIST_PAGE_SIZE
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.ReviseCaptureRequest
import io.github.triet4p.aegisf.data.SaveCaptureRequest
import io.github.triet4p.aegisf.data.SourceQuery
import io.github.triet4p.aegisf.data.SourceState
import io.github.triet4p.aegisf.data.UserCaptureKind
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowChange
import io.github.triet4p.aegisf.data.WorkflowCommandOutcome
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickCaptureScreen(
    queryService: WorkflowQueryService,
    commandService: WorkflowCommandService,
    onOpenManual: () -> Unit,
    onRecordTransaction: () -> Unit,
    onRecordEvent: (Long) -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var saveRequestKey by rememberSaveable { mutableStateOf<String?>(null) }
    var saveMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var saveInProgress by remember { mutableStateOf(false) }

    var historyOffset by rememberSaveable { mutableStateOf(0) }
    var historyRevision by remember { mutableStateOf(0) }
    var captures by remember { mutableStateOf(emptyList<CaptureMessageEntity>()) }
    var contextSummaries by remember {
        mutableStateOf(emptyMap<Long, CaptureContextSummary>())
    }
    var hasMore by remember { mutableStateOf(false) }

    var expandedCaptureId by rememberSaveable { mutableStateOf<Long?>(null) }

    var historyLoading by remember { mutableStateOf(true) }
    var historyError by remember { mutableStateOf<String?>(null) }

    var editingCaptureId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editingRevision by rememberSaveable { mutableStateOf<Int?>(null) }
    var editText by rememberSaveable { mutableStateOf("") }
    var editRequestKey by rememberSaveable { mutableStateOf<String?>(null) }
    var editMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var editInProgress by remember { mutableStateOf(false) }

    var deletingCaptureId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deletingRevision by rememberSaveable { mutableStateOf<Int?>(null) }
    var deleteRequestKey by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteInProgress by remember { mutableStateOf(false) }
    var deleteMessage by rememberSaveable { mutableStateOf<String?>(null) }

    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(queryService, historyOffset, historyRevision) {
        historyLoading = true
        historyError = null
        try {
            val page = queryService.sourceExcerpts(
                SourceQuery(
                    kind = CaptureKind.USER_NOTE,
                    sourceKind = CaptureSourceKind.USER_QUICK_CAPTURE,
                    state = SourceState.ACTIVE,
                    bounds = QueryPageRequest(limit = LIST_PAGE_SIZE, offset = historyOffset),
                ),
            )
            if (page.items.isEmpty() && historyOffset > 0) {
                historyOffset = (historyOffset - LIST_PAGE_SIZE).coerceAtLeast(0)
            } else {
                val summaries = queryService.captureContextSummaries(page.items.map { it.id })
                captures = page.items
                contextSummaries = summaries
                hasMore = page.hasMore
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            historyError = "Saved captures could not be loaded. Try again."
        } finally {
            historyLoading = false
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "CHAT / QUICK CAPTURE",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                    )
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                CapturePanel(
                    modifier = Modifier.fillMaxWidth(),
                    accent = MaterialTheme.colorScheme.primary,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "NEW QUICK CAPTURE",
                            style = MaterialTheme.typography.labelLarge,
                            letterSpacing = 1.2.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "Save any note locally. It stays source text until you take " +
                                "a separate manual action.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedTextField(
                            value = draft,
                            onValueChange = {
                                draft = it
                                saveRequestKey = null
                                saveMessage = null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 144.dp),
                            enabled = !saveInProgress,
                            label = { Text("Write anything") },
                            supportingText = { Text("No financial fields are required.") },
                        )
                        Button(
                            shape = MaterialTheme.shapes.small,
                            onClick = {
                                val text = draft
                                val requestKey = saveRequestKey
                                    ?: UUID.randomUUID().toString().also { key ->
                                        saveRequestKey = key
                                    }
                                saveInProgress = true
                                saveMessage = null
                                coroutineScope.launch {
                                    try {
                                        val outcome = commandService.saveCapture(
                                            WorkflowCallerScope.User,
                                            SaveCaptureRequest(
                                                idempotencyKey = requestKey,
                                                bodyText = text,
                                                captureKind = UserCaptureKind.QUICK_CAPTURE,
                                            ),
                                        )
                                        val captureId = outcome.savedCaptureId()
                                        if (captureId != null) {
                                            draft = ""
                                            saveRequestKey = null
                                            saveMessage = "Saved. No ledger record or automatic " +
                                                "match was created."
                                            historyRevision += 1
                                        } else {
                                            saveMessage = outcome.captureFailureMessage()
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        saveMessage = "Save failed. Your note is still here; " +
                                            "retry to finish the same request."
                                    } finally {
                                        saveInProgress = false
                                    }
                                }
                            },
                            enabled = draft.isNotBlank() && !saveInProgress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                        ) {
                            if (saveInProgress) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                                Text("Saving…")
                            } else {
                                Text("Save capture")
                            }
                        }
                        saveMessage?.let { message ->
                            Text(
                                text = message,
                                color = if (saveMessageIsError(message)) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            item {
                CapturePanel(
                    modifier = Modifier.fillMaxWidth(),
                    accent = MaterialTheme.colorScheme.tertiary,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Availability", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Quick Capture does not run model extraction or matching. " +
                                "Manual recording remains available; saving a note never creates " +
                                "a financial record.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                shape = MaterialTheme.shapes.small,
                                onClick = onRecordTransaction,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Record a transaction")
                            }
                            TextButton(
                                onClick = onOpenManual,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Open Manual")
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "RECENT CAPTURES & PROVENANCE",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (historyLoading && captures.isEmpty()) {
                item { Text("Loading saved captures…") }
            } else if (captures.isEmpty() && historyError == null) {
                item { Text("No saved captures yet.") }
            }

            historyError?.let { error ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(error, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { historyRevision += 1 }) {
                            Text("Retry loading captures")
                        }
                    }
                }
            }


            items(captures, key = { it.id }) { capture ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickCaptureHistoryCard(
                        capture = capture,
                        context = contextSummaries[capture.id],
                        onEdit = {
                            editingCaptureId = capture.id
                            editingRevision = capture.revision
                            editText = capture.bodyText.orEmpty()
                            editRequestKey = null
                            editMessage = null
                        },
                        onDelete = {
                            deletingCaptureId = capture.id
                            deletingRevision = capture.revision
                            deleteRequestKey = null
                            deleteMessage = null
                        },
                        onManageEvents = {
                            expandedCaptureId =
                                if (expandedCaptureId == capture.id) null else capture.id
                        },
                        eventsExpanded = expandedCaptureId == capture.id,
                    )
                    if (expandedCaptureId == capture.id) {
                        QuickCaptureEventPanel(
                            capture = capture,
                            queryService = queryService,
                            commandService = commandService,
                            onRecordManually = onRecordEvent,
                            onContextChanged = { historyRevision++ },
                        )
                    }
                }
            }

            if ((hasMore || historyOffset > 0) && captures.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = {
                                historyOffset = (historyOffset - LIST_PAGE_SIZE).coerceAtLeast(0)
                            },
                            enabled = historyOffset > 0 && !historyLoading,
                        ) { Text("Previous") }
                        Text("Captures ${historyOffset + 1}–${historyOffset + captures.size}")
                        TextButton(
                            onClick = { historyOffset += captures.size },
                            enabled = hasMore && captures.isNotEmpty() && !historyLoading,
                        ) { Text("Next") }
                    }
                }
            }
        }
    }

    if (editingCaptureId != null) {
        AlertDialog(
            onDismissRequest = {
                if (!editInProgress) {
                    editingCaptureId = null
                    editingRevision = null
                    editRequestKey = null
                    historyRevision += 1
                }
            },
            title = { Text("Edit saved capture") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = editText,
                        onValueChange = {
                            editText = it
                            editRequestKey = null
                            editMessage = null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                        enabled = !editInProgress,
                        label = { Text("Capture text") },
                    )
                    Text(
                        text = sourceChangePolicyMessage(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    editMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(
                    shape = MaterialTheme.shapes.small,
                    enabled = editText.isNotBlank() && !editInProgress,
                    onClick = {
                        val captureId = editingCaptureId ?: return@Button
                        val revision = editingRevision ?: return@Button
                        val requestKey = editRequestKey
                            ?: UUID.randomUUID().toString().also { key ->
                                editRequestKey = key
                            }
                        editInProgress = true
                        editMessage = null
                        coroutineScope.launch {
                            try {
                                val outcome = commandService.reviseCapture(
                                    WorkflowCallerScope.User,
                                    ReviseCaptureRequest(
                                        idempotencyKey = requestKey,
                                        captureId = captureId,
                                        expectedRevision = revision,
                                        bodyText = editText,
                                    ),
                                )
                                if (outcome.revisedCaptureId() == captureId) {
                                    editingCaptureId = null
                                    editingRevision = null
                                    editRequestKey = null
                                    editMessage = null
                                    saveMessage = "Capture text saved. Provenance is retained."
                                    historyRevision += 1
                                } else {
                                    editMessage = outcome.captureFailureMessage()
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                editMessage = "Update failed. Your changes are still here; " +
                                    "retry to finish the same request."
                            } finally {
                                editInProgress = false
                            }
                        }
                    },
                ) {
                    Text(if (editInProgress) "Saving…" else "Save changes")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !editInProgress,
                    onClick = {
                        editingCaptureId = null
                        editingRevision = null
                        editRequestKey = null
                        historyRevision += 1
                    },
                ) { Text("Cancel") }
            },
        )
    }

    if (deletingCaptureId != null) {
        val captureId = checkNotNull(deletingCaptureId)
        val revision = checkNotNull(deletingRevision)
        val capture = captures.firstOrNull { it.id == captureId }
        AlertDialog(
            onDismissRequest = {
                if (!deleteInProgress) {
                    deletingCaptureId = null
                    deletingRevision = null
                    deleteRequestKey = null
                    deleteMessage = null
                }
            },
            title = { Text("Delete saved capture?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    capture?.bodyText?.let { text ->
                        Text(text, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    } ?: Text(
                        "This capture is no longer in active history. Confirm again to " +
                            "resolve the saved deletion request.",
                    )
                    Text(
                        text = sourceChangePolicyMessage(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    deleteMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(
                    shape = MaterialTheme.shapes.small,
                    enabled = !deleteInProgress,
                    onClick = {
                        val requestKey = deleteRequestKey ?: UUID.randomUUID().toString().also {
                            deleteRequestKey = it
                        }
                        deleteInProgress = true
                        deleteMessage = null
                        coroutineScope.launch {
                            try {
                                val outcome = commandService.deleteCapture(
                                    WorkflowCallerScope.User,
                                    DeleteCaptureRequest(
                                        idempotencyKey = requestKey,
                                        captureId = captureId,
                                        expectedRevision = revision,
                                    ),
                                )
                                if (outcome.deletedCaptureId() == captureId) {
                                    deletingCaptureId = null
                                    deletingRevision = null
                                    deleteRequestKey = null
                                    deleteMessage = null
                                    saveMessage = "Capture deleted. Provenance remains as a " +
                                        "text-free tombstone."
                                    historyRevision += 1
                                } else {
                                    deleteMessage = outcome.captureFailureMessage()
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                deleteMessage = "Delete failed. The same request is retained; " +
                                    "retry to finish it safely."
                            } finally {
                                deleteInProgress = false
                            }
                        }
                    },
                ) { Text(if (deleteInProgress) "Deleting…" else "Delete capture") }
            },
            dismissButton = {
                TextButton(
                    enabled = !deleteInProgress,
                    onClick = {
                        deletingCaptureId = null
                        deletingRevision = null
                        deleteRequestKey = null
                        deleteMessage = null
                        historyRevision += 1
                    },
                ) { Text("Cancel") }
            },
        )
    }
}

@Composable
internal fun CapturePanel(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(accent ?: MaterialTheme.colorScheme.outlineVariant),
            )
            content()
        }
    }
}
@Composable
private fun QuickCaptureHistoryCard(
    capture: CaptureMessageEntity,
    context: CaptureContextSummary?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onManageEvents: () -> Unit,
    eventsExpanded: Boolean,
) {
    val accent = when {
        context?.acceptedLinkCount?.let { it > 0 } == true -> MaterialTheme.colorScheme.secondary
        context?.proposedLinkCount?.let { it > 0 } == true -> MaterialTheme.colorScheme.tertiary
        context?.resolvedEventCount?.let { it > 0 } == true -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    CapturePanel(modifier = Modifier.fillMaxWidth(), accent = accent) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(capture.bodyText.orEmpty(), style = MaterialTheme.typography.bodyLarge)
            Text(
                context?.let(::captureStatusText) ?: "Saved · current status unavailable",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                captureTimestamp(capture),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onManageEvents) {
                    Text(if (eventsExpanded) "Hide events" else "Manage events")
                }
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

private fun captureStatusText(summary: CaptureContextSummary): String = when {
    summary.acceptedLinkCount > 0 -> "Linked · ${summary.acceptedLinkCount} bank draft(s)"
    summary.proposedLinkCount > 0 -> "Link proposed · not accepted"
    summary.resolvedEventCount > 0 -> "Recorded manually · ${summary.resolvedEventCount} ledger event(s)"
    summary.eventCount == 0 -> "Saved · unresolved · no interpretation recorded"
    summary.unresolvedEventCount > 0 -> "Saved · unresolved · interpretation needs review"
    summary.rejectedLinkCount > 0 -> "Unresolved · a previous link was refused"
    summary.unmatchedEventCount > 0 -> "Unmatched · no active bank link"
    else -> "Interpreted · not linked"
}

private fun sourceChangePolicyMessage(): String =
    "If this text has saved interpretations, changing or deleting it clears their derived " +
        "content and removes active links. Refused link decisions remain. The capture identity " +
        "and provenance stay; ledger records are not changed."

private fun captureTimestamp(capture: CaptureMessageEntity): String {
    val instant = capture.capturedAtEpochMillis ?: return "Capture time unavailable"
    val zoneId = capture.captureTimezone ?: return "Saved ${Date(instant)} · timezone unknown"
    val timezone = TimeZone.getTimeZone(zoneId)
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm z", Locale.ROOT).apply {
        timeZone = timezone
    }
    return "Saved ${formatter.format(Date(instant))} · $zoneId"
}

private fun WorkflowCommandOutcome.savedCaptureId(): Long? = when (this) {
    is WorkflowCommandOutcome.Applied -> (change as? WorkflowChange.CaptureSaved)?.captureId
    is WorkflowCommandOutcome.NoChange -> (change as? WorkflowChange.CaptureSaved)?.captureId
    else -> null
}

private fun WorkflowCommandOutcome.revisedCaptureId(): Long? = when (this) {
    is WorkflowCommandOutcome.Applied -> (change as? WorkflowChange.CaptureRevised)?.captureId
    is WorkflowCommandOutcome.NoChange -> (change as? WorkflowChange.CaptureRevised)?.captureId
    else -> null
}

private fun WorkflowCommandOutcome.deletedCaptureId(): Long? = when (this) {
    is WorkflowCommandOutcome.Applied -> (change as? WorkflowChange.CaptureDeleted)?.captureId
    is WorkflowCommandOutcome.NoChange -> (change as? WorkflowChange.CaptureDeleted)?.captureId
    else -> null
}

private fun WorkflowCommandOutcome.captureFailureMessage(): String = when (this) {
    is WorkflowCommandOutcome.Conflict -> reason
    is WorkflowCommandOutcome.Denied -> "This capture cannot be changed under the source policy."
    is WorkflowCommandOutcome.Failed -> reason
    WorkflowCommandOutcome.NotFound -> "This capture is no longer stored."
    WorkflowCommandOutcome.Cancelled -> "The request was cancelled; your text is still here."
    is WorkflowCommandOutcome.NeedsClarification -> reason
    is WorkflowCommandOutcome.Applied,
    is WorkflowCommandOutcome.NoChange,
    -> "The capture change did not complete. Your text is still here."
}

private fun saveMessageIsError(message: String): Boolean =
    !message.startsWith("Saved.") &&
        !message.startsWith("Capture text saved.") &&
        !message.startsWith("Capture updated.") &&
        !message.startsWith("Capture deleted.")
