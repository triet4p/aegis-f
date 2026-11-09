package io.github.triet4p.aegisf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.triet4p.aegisf.data.CaptureContextSummary
import io.github.triet4p.aegisf.data.CaptureEventQuery
import io.github.triet4p.aegisf.data.CaptureMessageEntity
import io.github.triet4p.aegisf.data.ContextLinkState
import io.github.triet4p.aegisf.data.DeleteCaptureRequest
import io.github.triet4p.aegisf.data.LinkOperationQuery
import io.github.triet4p.aegisf.data.NoteEventEntity
import io.github.triet4p.aegisf.data.NoteEventStatus
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowCommandOutcome
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.ui.components.AegisGlyph
import io.github.triet4p.aegisf.ui.components.AegisIcon
import io.github.triet4p.aegisf.ui.components.AegisMoneyText
import io.github.triet4p.aegisf.ui.text.AegisStrings
import io.github.triet4p.aegisf.ui.text.LocalAegisStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

internal const val NOTE_EVENT_PAGE_SIZE = 20

/**
 * One saved note: its text, when it was kept, the details read from it, and the ways to use it.
 *
 * The note is read from storage when the screen opens, so it shows the stored text — including
 * an edit that was just saved. Every detail is its own row and its own focused screen; this
 * screen never grows a second inline form. Deleting asks first, names the note and its policy,
 * and a note that moved on under this screen is a conflict with a reload rather than a silent
 * overwrite.
 *
 * The AI handoff attaches the note to the separate Chat destination and sends nothing. Manual
 * recording stays a distinct route, so no conversation can write a ledger record.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteDetailScreen(
    queryService: WorkflowQueryService,
    commandService: WorkflowCommandService,
    captureId: Long,
    notice: String?,
    onDismissNotice: () -> Unit,
    onEdit: (Long) -> Unit,
    onAddDetail: (Long) -> Unit,
    onOpenDetail: (Long, Long) -> Unit,
    onRecordManually: () -> Unit,
    onDiscussWithAi: (Long, String) -> Unit,
    onDeleted: () -> Unit,
    onNavigateUp: () -> Unit,
) {
    val strings = LocalAegisStrings.current
    var state by remember(captureId) { mutableStateOf<NoteDetailLoad>(NoteDetailLoad.Loading) }
    var reloadToken by remember { mutableIntStateOf(0) }

    var events by remember(captureId) { mutableStateOf(emptyList<NoteEventEntity>()) }
    var eventPageOffset by rememberSaveable(captureId) { mutableStateOf(0) }
    var eventHasMore by remember(captureId) { mutableStateOf(false) }
    var eventsLoading by remember(captureId) { mutableStateOf(true) }
    var eventsError by remember(captureId) { mutableStateOf(false) }
    var eventReload by remember { mutableIntStateOf(0) }

    var confirmingDelete by rememberSaveable(captureId) { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    val deleteKey = rememberSaveable(captureId) { "note-delete:$captureId" }
    val scope = rememberCoroutineScope()

    LaunchedEffect(captureId, reloadToken) {
        state = NoteDetailLoad.Loading
        state = try {
            val capture = withContext(Dispatchers.IO) { queryService.sourceExcerpt(captureId) }
            if (capture == null || capture.tombstone || capture.bodyText == null) {
                NoteDetailLoad.Missing
            } else {
                val summary = withContext(Dispatchers.IO) {
                    queryService.captureContextSummaries(listOf(captureId))[captureId]
                        ?: CaptureContextSummary()
                }
                NoteDetailLoad.Found(capture, summary)
            }
        } catch (_: Exception) {
            NoteDetailLoad.Error
        }
    }

    val found = state as? NoteDetailLoad.Found
    LaunchedEffect(captureId, found?.capture?.revision, eventPageOffset, eventReload) {
        if (found == null) return@LaunchedEffect
        eventsLoading = true
        eventsError = false
        try {
            val page = withContext(Dispatchers.IO) {
                queryService.captureEvents(
                    CaptureEventQuery(
                        captureId = captureId,
                        bounds = QueryPageRequest(
                            limit = NOTE_EVENT_PAGE_SIZE,
                            offset = eventPageOffset,
                        ),
                    ),
                )
            }
            if (page.items.isEmpty() && eventPageOffset > 0) {
                eventPageOffset = (eventPageOffset - NOTE_EVENT_PAGE_SIZE).coerceAtLeast(0)
            } else {
                events = page.items
                eventHasMore = page.hasMore
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            eventsError = true
        } finally {
            eventsLoading = false
        }
    }

    BackHandler { if (!deleting) onNavigateUp() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(strings.noteDetailTitle) },
                navigationIcon = {
                    IconButton(
                        onClick = { if (!deleting) onNavigateUp() },
                        enabled = !deleting,
                        modifier = Modifier.semantics { contentDescription = strings.back },
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
                NoteDetailActions(
                    deleting = deleting,
                    strings = strings,
                    onEdit = { onEdit(captureId) },
                    onDelete = {
                        deleteError = null
                        confirmingDelete = true
                    },
                )
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        when (val current = state) {
            NoteDetailLoad.Loading -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            ) {
                item { NoteStateMessage(strings.noteLoading, null) }
            }

            NoteDetailLoad.Missing -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            ) {
                item {
                    NoteStateMessage(
                        title = strings.noteMissingTitle,
                        body = strings.noteMissingBody,
                    )
                }
            }

            NoteDetailLoad.Error -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            ) {
                item {
                    NoteStateMessage(
                        title = strings.noteReadErrorTitle,
                        body = strings.noteReadErrorBody,
                        actionLabel = strings.retry,
                        onAction = { reloadToken += 1 },
                    )
                }
            }

            is NoteDetailLoad.Found -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 16.dp,
                    bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                notice?.let { message ->
                    item {
                        NoteNoticeBanner(
                            message = message,
                            closeLabel = strings.close,
                            onClose = onDismissNotice,
                        )
                    }
                }

                item {
                    SavedNoteCard(
                        capture = current.capture,
                        summary = current.summary,
                        strings = strings,
                    )
                }

                item {
                    NoteAiHandoffBanner(
                        title = strings.noteDiscussAi,
                        supporting = strings.noteDiscussAiHelp,
                        enabled = current.capture.bodyText != null,
                        onClick = {
                            current.capture.bodyText?.let { text ->
                                onDiscussWithAi(captureId, text)
                            }
                        },
                    )
                }

                item {
                    OutlinedButton(
                        onClick = onRecordManually,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp),
                    ) { Text(strings.noteRecordTransaction) }
                }

                item {
                    if (LocalDensity.current.fontScale >= 1.3f) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = strings.noteDetailsSection,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            TextButton(
                                onClick = { onAddDetail(captureId) },
                                enabled = current.capture.bodyText != null,
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            ) { Text(strings.noteAddDetail) }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = strings.noteDetailsSection,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = { onAddDetail(captureId) },
                                enabled = current.capture.bodyText != null,
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            ) { Text(strings.noteAddDetail) }
                        }
                    }
                }

                deleteError?.let { message ->
                    item {
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                if (eventsLoading && events.isEmpty()) {
                    item { Text(strings.noteDetailsLoading) }
                }

                if (eventsError) {
                    item {
                        NoteStateMessage(
                            title = strings.noteDetailsError,
                            body = null,
                            actionLabel = strings.retry,
                            onAction = {
                                eventsError = false
                                eventReload += 1
                            },
                        )
                    }
                }

                if (!eventsLoading && !eventsError && events.isEmpty()) {
                    item {
                        NoteStateMessage(
                            title = strings.noteDetailsEmpty,
                            body = null,
                            bodyStyle = true,
                        )
                    }
                }

                items(events, key = { it.id }) { event ->
                    NoteDetailRow(
                        event = event,
                        capture = current.capture,
                        strings = strings,
                        queryService = queryService,
                        onOpen = { onOpenDetail(captureId, event.id) },
                    )
                }

                if (events.isNotEmpty() && (eventPageOffset > 0 || eventHasMore)) {
                    item {
                        val pageIndex = eventPageOffset / NOTE_EVENT_PAGE_SIZE
                        PageControls(
                            pageIndex = pageIndex,
                            hasMore = eventHasMore,
                            onPrevious = {
                                eventPageOffset =
                                    (eventPageOffset - NOTE_EVENT_PAGE_SIZE).coerceAtLeast(0)
                            },
                            onNext = { eventPageOffset += NOTE_EVENT_PAGE_SIZE },
                            previousLabel = strings.pagePrevious,
                            nextLabel = strings.pageNext,
                            pageLabel = strings.pageNumber(pageIndex),
                        )
                    }
                }
            }
        }
    }

    if (confirmingDelete && found != null) {
        NoteDeleteDialog(
            capture = found.capture,
            strings = strings,
            deleting = deleting,
            error = deleteError,
            onDismiss = { if (!deleting) confirmingDelete = false },
            onConfirm = {
                deleting = true
                deleteError = null
                scope.launch {
                    val outcome = try {
                        commandService.deleteCapture(
                            WorkflowCallerScope.User,
                            DeleteCaptureRequest(
                                idempotencyKey = deleteKey,
                                captureId = captureId,
                                expectedRevision = found.capture.revision,
                            ),
                        )
                    } catch (_: Exception) {
                        null
                    }
                    when (outcome) {
                        is WorkflowCommandOutcome.Applied,
                        is WorkflowCommandOutcome.NoChange,
                        -> onDeleted()

                        null -> {
                            deleting = false
                            deleteError = strings.noteDeleteFailed
                        }

                        else -> {
                            deleting = false
                            confirmingDelete = false
                            deleteError = outcome.noteFailureMessage(
                                strings,
                                strings.noteDeleteFailed,
                            )
                            reloadToken += 1
                            eventReload += 1
                        }
                    }
                }
            },
        )
    }
}

private sealed interface NoteDetailLoad {
    data object Loading : NoteDetailLoad
    data object Missing : NoteDetailLoad
    data object Error : NoteDetailLoad
    data class Found(
        val capture: CaptureMessageEntity,
        val summary: CaptureContextSummary,
    ) : NoteDetailLoad
}

@Composable
private fun SavedNoteCard(
    capture: CaptureMessageEntity,
    summary: CaptureContextSummary,
    strings: AegisStrings,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SelectionContainer {
                Text(
                    text = capture.bodyText.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Text(
                text = "${strings.detailSource}: ${strings.noteSourceValue}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${strings.detailRecordedAt}: ${noteMomentText(capture, strings)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            noteSummaryText(summary, strings)?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NoteDetailRow(
    event: NoteEventEntity,
    capture: CaptureMessageEntity,
    strings: AegisStrings,
    queryService: WorkflowQueryService,
    onOpen: () -> Unit,
) {
    var linkState by remember(event.id) { mutableStateOf<ContextLinkState?>(null) }
    LaunchedEffect(queryService, event.id, event.revision, capture.revision) {
        linkState = try {
            withContext(Dispatchers.IO) {
                val links = queryService.linkOperations(
                    LinkOperationQuery(
                        eventId = event.id,
                        bounds = QueryPageRequest(limit = 20),
                    ),
                ).items
                links.firstOrNull {
                    it.state == ContextLinkState.ACCEPTED || it.state == ContextLinkState.PROPOSED
                }?.state ?: links.firstOrNull()?.state
            }
        } catch (_: Exception) {
            null
        }
    }

    val stale = event.sourceRevision != capture.revision || event.normalizedText == null
    val status = eventStatus(event, stale, linkState, strings)
    val reading = event.normalizedText.orEmpty()
    val semanticsLabel = buildString {
        append(strings.noteDetailWhatItSays)
        append(": ")
        append(reading)
        append(". ")
        append(status.label)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = semanticsLabel }
            .clickable(role = Role.Button, onClick = onOpen),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 64.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = if (reading.isBlank()) strings.noteDetailOutdated else reading,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            event.amountVnd?.let { amount ->
                AegisMoneyText(
                    amountVnd = amount,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            statedTimeText(event)?.let { stated ->
                Text(
                    text = "${strings.noteDetailStatedTime}: $stated",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AegisIcon(
                    glyph = status.glyph,
                    tint = status.color,
                    size = 16.dp,
                )
                Text(
                    text = status.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = status.color,
                )
            }
        }
    }
}

private data class NoteEventStatusText(
    val glyph: AegisGlyph,
    val label: String,
    val color: Color,
)

@Composable
private fun eventStatus(
    event: NoteEventEntity,
    stale: Boolean,
    linkState: ContextLinkState?,
    strings: AegisStrings,
): NoteEventStatusText {
    val scheme = MaterialTheme.colorScheme
    return when {
        stale -> NoteEventStatusText(
            AegisGlyph.WARNING,
            strings.noteDetailOutdated,
            scheme.tertiary,
        )

        event.resolvedTransactionId != null -> NoteEventStatusText(
            AegisGlyph.CHECK,
            strings.noteDetailRecorded,
            scheme.secondary,
        )

        linkState == ContextLinkState.ACCEPTED -> NoteEventStatusText(
            AegisGlyph.LINK,
            strings.noteDetailAttached,
            scheme.secondary,
        )

        linkState == ContextLinkState.PROPOSED -> NoteEventStatusText(
            AegisGlyph.LINK,
            strings.noteDetailProposed,
            scheme.primary,
        )

        linkState == ContextLinkState.REJECTED -> NoteEventStatusText(
            AegisGlyph.CROSS,
            strings.noteDetailRefused,
            scheme.error,
        )

        event.status == NoteEventStatus.UNRESOLVED -> NoteEventStatusText(
            AegisGlyph.WARNING,
            strings.noteDetailNeedsInfo,
            scheme.tertiary,
        )

        event.status == NoteEventStatus.NO_MATCH -> NoteEventStatusText(
            AegisGlyph.CROSS,
            strings.noteDetailNoMatch,
            scheme.onSurfaceVariant,
        )

        else -> NoteEventStatusText(
            AegisGlyph.CHECK,
            strings.noteDetailReady,
            scheme.primary,
        )
    }
}

private fun statedTimeText(event: NoteEventEntity): String? {
    val start = listOfNotNull(event.anchorDate, event.anchorTime)
        .filter(String::isNotBlank)
        .joinToString(" ")
    if (start.isBlank()) return null
    val end = listOfNotNull(event.intervalEndDate, event.intervalEndTime)
        .filter(String::isNotBlank)
        .joinToString(" ")
    return if (end.isBlank()) start else "$start – $end"
}

@Composable
private fun NoteDetailActions(
    deleting: Boolean,
    strings: AegisStrings,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            EditNoteAction(deleting, strings, onEdit, Modifier.weight(1f))
            DeleteNoteAction(deleting, strings, onDelete, Modifier.weight(1f))
        }
    }
}

@Composable
private fun EditNoteAction(
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
    ) { Text(strings.noteEdit) }
}

@Composable
private fun DeleteNoteAction(
    deleting: Boolean,
    strings: AegisStrings,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onDelete,
        enabled = !deleting,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error,
        ),
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp),
    ) { Text(strings.detailDeleteConfirm) }
}

@Composable
private fun NoteDeleteDialog(
    capture: CaptureMessageEntity,
    strings: AegisStrings,
    deleting: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.noteDeleteQuestion) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = capture.bodyText.orEmpty(),
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(strings.noteDeleteBody)
                Text(
                    text = strings.noteChangePolicy,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (error != null) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !deleting,
                shape = MaterialTheme.shapes.small,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text(if (deleting) strings.detailDeleting else strings.noteDelete) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !deleting) { Text(strings.cancel) }
        },
    )
}
