package io.github.triet4p.aegisf.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.triet4p.aegisf.data.CaptureMessageEntity
import io.github.triet4p.aegisf.data.ContextLinkState
import io.github.triet4p.aegisf.data.DraftCandidateQuery
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.LinkOperation
import io.github.triet4p.aegisf.data.LinkOperationQuery
import io.github.triet4p.aegisf.data.NoteEventEntity
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.ui.components.AegisGlyph
import io.github.triet4p.aegisf.ui.components.AegisIcon
import io.github.triet4p.aegisf.ui.text.AegisStrings
import io.github.triet4p.aegisf.ui.text.LocalAegisStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val NOTE_DRAFT_PAGE_SIZE = 10

/**
 * The focused chooser for one detail's bank notification.
 *
 * The pending bank notifications are a bounded page and nothing is selected automatically. Each
 * row can be attached or refused explicitly; a refusal is permanent for the pair and is shown as
 * such. Neither action confirms the bank notification or changes a ledger record.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteDraftChooserScreen(
    queryService: WorkflowQueryService,
    commandService: WorkflowCommandService,
    captureId: Long,
    eventId: Long,
    onRecordManually: (Long) -> Unit,
    onNavigateUp: () -> Unit,
) {
    val strings = LocalAegisStrings.current
    val scope = rememberCoroutineScope()

    var phase by remember(captureId, eventId) {
        mutableStateOf<NoteDraftChooserPhase>(NoteDraftChooserPhase.Loading)
    }
    var capture by remember(captureId, eventId) { mutableStateOf<CaptureMessageEntity?>(null) }
    var event by remember(captureId, eventId) { mutableStateOf<NoteEventEntity?>(null) }
    var links by remember(captureId, eventId) { mutableStateOf(emptyList<LinkOperation>()) }
    var drafts by remember(captureId, eventId) { mutableStateOf(emptyList<PendingBankDraftEntity>()) }
    var pageOffset by rememberSaveable(captureId, eventId) { mutableStateOf(0) }
    var hasMore by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by rememberSaveable(captureId, eventId) { mutableStateOf<String?>(null) }
    var failed by rememberSaveable(captureId, eventId) { mutableStateOf(false) }

    LaunchedEffect(captureId, eventId, pageOffset, reloadToken) {
        phase = NoteDraftChooserPhase.Loading
        try {
            val loadedCapture = withContext(Dispatchers.IO) { queryService.sourceExcerpt(captureId) }
            if (loadedCapture == null || loadedCapture.tombstone || loadedCapture.bodyText == null) {
                phase = NoteDraftChooserPhase.Missing
                return@LaunchedEffect
            }
            val loadedEvent = withContext(Dispatchers.IO) { queryService.noteEvent(eventId) }
            if (loadedEvent == null || loadedEvent.captureId != captureId) {
                phase = NoteDraftChooserPhase.Missing
                return@LaunchedEffect
            }
            val page = withContext(Dispatchers.IO) {
                queryService.draftCandidates(
                    DraftCandidateQuery(
                        reviewStatus = IntakeReviewStatus.PENDING,
                        bounds = QueryPageRequest(
                            limit = NOTE_DRAFT_PAGE_SIZE,
                            offset = pageOffset,
                        ),
                    ),
                )
            }
            if (page.items.isEmpty() && pageOffset > 0) {
                pageOffset = (pageOffset - NOTE_DRAFT_PAGE_SIZE).coerceAtLeast(0)
                return@LaunchedEffect
            }
            val loadedLinks = withContext(Dispatchers.IO) {
                queryService.linkOperations(
                    LinkOperationQuery(
                        eventId = eventId,
                        bounds = QueryPageRequest(limit = 20),
                    ),
                ).items
            }
            capture = loadedCapture
            event = loadedEvent
            drafts = page.items
            hasMore = page.hasMore
            links = loadedLinks
            phase = NoteDraftChooserPhase.Ready
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            phase = NoteDraftChooserPhase.Error
        }
    }

    val activeLink = links.firstOrNull {
        it.state == ContextLinkState.ACCEPTED || it.state == ContextLinkState.PROPOSED
    }
    val sourceCurrent = event != null && capture != null &&
        event?.sourceRevision == capture?.revision &&
        event?.normalizedText != null
    val manualAllowed = sourceCurrent &&
        event?.unresolvedReason == null &&
        event?.amountVnd != null &&
        event?.resolvedTransactionId == null &&
        activeLink == null

    fun decide(draft: PendingBankDraftEntity, attach: Boolean) {
        busy = true
        message = null
        failed = false
        scope.launch {
            try {
                val outcome = decideUserLink(
                    commandService = commandService,
                    eventId = eventId,
                    draftId = draft.id,
                    attach = attach,
                    overrideNote = if (attach) {
                        "User explicitly attached this bank notification in Notes."
                    } else {
                        "User explicitly refused this bank notification in Notes."
                    },
                )
                if (outcome.isAppliedOrNoChange()) {
                    message = if (attach) strings.noteDraftAttachedMsg else strings.noteDraftRefusedMsg
                    failed = false
                } else {
                    message = strings.noteDraftActionFailed
                    failed = true
                }
                reloadToken += 1
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = strings.noteDraftActionFailed
                failed = true
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(strings.noteDraftTitle) },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateUp,
                        enabled = !busy,
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
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = strings.noteDetailWhatItSays,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            text = event?.normalizedText.orEmpty(),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = strings.noteDraftHelp,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            when (val current = phase) {
                NoteDraftChooserPhase.Loading -> item {
                    Text(strings.noteDraftLoading, style = MaterialTheme.typography.bodyMedium)
                }

                NoteDraftChooserPhase.Missing -> item {
                    NoteStateMessage(
                        title = strings.noteDetailMissingTitle,
                        body = strings.noteDetailMissingBody,
                    )
                }

                NoteDraftChooserPhase.Error -> item {
                    NoteStateMessage(
                        title = strings.noteDraftLoadError,
                        body = null,
                        actionLabel = strings.retry,
                        onAction = { reloadToken += 1 },
                    )
                }

                NoteDraftChooserPhase.Ready -> {
                    if (drafts.isEmpty() && pageOffset == 0) {
                        item {
                            NoteStateMessage(
                                title = strings.noteDraftEmpty,
                                body = null,
                            )
                        }
                    }
                    items(drafts, key = { it.id }) { draft ->
                        NoteDraftCandidateCard(
                            draft = draft,
                            strings = strings,
                            links = links,
                            enabled = !busy,
                            onAttach = { decide(draft, attach = true) },
                            onReject = { decide(draft, attach = false) },
                        )
                    }
                    if (drafts.isNotEmpty() && (pageOffset > 0 || hasMore)) {
                        item {
                            val pageIndex = pageOffset / NOTE_DRAFT_PAGE_SIZE
                            PageControls(
                                pageIndex = pageIndex,
                                hasMore = hasMore,
                                onPrevious = {
                                    pageOffset =
                                        (pageOffset - NOTE_DRAFT_PAGE_SIZE).coerceAtLeast(0)
                                },
                                onNext = { pageOffset += NOTE_DRAFT_PAGE_SIZE },
                                previousLabel = strings.pagePrevious,
                                nextLabel = strings.pageNext,
                                pageLabel = strings.pageNumber(pageIndex),
                            )
                        }
                    }
                    if (manualAllowed) {
                        item {
                            OutlinedButton(
                                onClick = { onRecordManually(eventId) },
                                enabled = !busy,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp),
                            ) { Text(strings.noteRecordTransaction) }
                        }
                    }
                }
            }

            if (busy) {
                item {
                    Text(
                        text = strings.noteDraftDeciding,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            message?.let { text ->
                item {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }
        }
    }
}

private sealed interface NoteDraftChooserPhase {
    data object Loading : NoteDraftChooserPhase
    data object Missing : NoteDraftChooserPhase
    data object Error : NoteDraftChooserPhase
    data object Ready : NoteDraftChooserPhase
}

@Composable
private fun NoteDraftCandidateCard(
    draft: PendingBankDraftEntity,
    strings: AegisStrings,
    links: List<LinkOperation>,
    enabled: Boolean,
    onAttach: () -> Unit,
    onReject: () -> Unit,
) {
    val pair = links.firstOrNull { it.draftId == draft.id }
    val otherActive = links.any {
        it.draftId != draft.id &&
            (it.state == ContextLinkState.ACCEPTED || it.state == ContextLinkState.PROPOSED)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = draft.bank,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = draftSummary(draft, strings, includeBank = false),
                style = MaterialTheme.typography.bodyMedium,
            )
            when (pair?.state) {
                ContextLinkState.ACCEPTED -> Text(strings.noteDraftAlreadyAttached)
                ContextLinkState.PROPOSED -> Text(strings.noteDraftProposedState)
                ContextLinkState.REJECTED -> Text(strings.noteDraftRefusedState)
                null -> if (otherActive) Text(strings.noteDraftOtherActive)
            }
            if (pair?.state != ContextLinkState.REJECTED &&
                pair?.state != ContextLinkState.ACCEPTED
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onAttach,
                        enabled = enabled && !otherActive,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp),
                    ) { Text(strings.noteDetailAttach) }
                    OutlinedButton(
                        onClick = onReject,
                        enabled = enabled && !otherActive,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp),
                    ) { Text(strings.noteDetailReject) }
                }
            }
        }
    }
}
