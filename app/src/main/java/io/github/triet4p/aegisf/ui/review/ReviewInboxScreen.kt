package io.github.triet4p.aegisf.ui.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.triet4p.aegisf.data.IntakeParseStatus
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.data.ReviewQueueConflict
import io.github.triet4p.aegisf.data.ReviewQueueEntry
import io.github.triet4p.aegisf.data.ReviewQueueGroup
import io.github.triet4p.aegisf.data.ReviewQueueOrder
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.ui.PageControls
import io.github.triet4p.aegisf.ui.ReviewQueueFocus
import io.github.triet4p.aegisf.ui.components.AegisGlyph
import io.github.triet4p.aegisf.ui.components.AegisIcon
import io.github.triet4p.aegisf.ui.components.AegisMoneyText
import io.github.triet4p.aegisf.ui.text.LocalAegisStrings
import io.github.triet4p.aegisf.ui.theme.LocalAegisSemanticColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Review's root: the inbox that turns waiting bank notifications into one draft to inspect.
 *
 * The screen's one task is choosing work. Drafts are grouped by why they are waiting — a
 * conflict, a possible duplicate, a missing category, or ready to confirm — and the uncertainty
 * first order keeps the decisions that need a person above the confirmations. Tap opens the one
 * draft's focus screen; this screen never confirms anything itself, so a tap can never move money.
 *
 * The page, order and scroll position belong to the inbox, so returning from a draft or refreshing
 * the queue leaves the reader where they were. Rows update live; a row that leaves its group makes
 * the bounded page re-read, so the sections never show a stale position.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewInboxScreen(
    queryService: WorkflowQueryService,
    onOpenDraft: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = reviewStrings(LocalAegisStrings.current.language)
    val scope = rememberCoroutineScope()
    val focus = rememberSaveable(queryService, saver = ReviewQueueFocus.saver(queryService)) {
        ReviewQueueFocus(queryService)
    }
    var readError by remember { mutableStateOf(false) }
    var retryToken by rememberSaveable { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val waitingCount by remember(queryService) {
        runCatching {
            queryService.observeDraftCandidateCount(IntakeReviewStatus.PENDING)
        }.getOrElse { flowOf(-1) }
    }.collectAsState(initial = null)
    // A negative count means the live count could not be read; the queue's own read error is
    // the authority for saying so.
    val knownWaitingCount = waitingCount?.takeIf { it >= 0 }
    val entries = focus.page?.items.orEmpty()
    val queueWaiting = knownWaitingCount == null || knownWaitingCount > 0
    var categoryNames by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }

    fun runRead(block: suspend () -> Unit) {
        scope.launch {
            try {
                readError = false
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                readError = true
            }
        }
    }

    // The inbox is read when it is opened and when a draft screen returns to it.
    LaunchedEffect(focus, retryToken) {
        try {
            readError = false
            focus.refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            readError = true
        }
    }
    // Watch the current page's membership and each visible row, so a background review decision
    // is reflected without moving the reader.
    LaunchedEffect(focus, focus.order, focus.pageIndex) {
        try {
            focus.observePageIds().collect { focus.updatePageMembership(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            readError = true
        }
    }
    val visibleDraftIds = entries.map { it.draft.id }
    LaunchedEffect(focus, visibleDraftIds) {
        if (visibleDraftIds.isEmpty()) return@LaunchedEffect
        try {
            coroutineScope {
                visibleDraftIds.forEach { id ->
                    launch {
                        queryService.observeReviewQueueEntry(id).collect { entry ->
                            focus.updateVisibleEntry(id, entry)
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            readError = true
        }
    }
    val relatedFlow = remember(queryService, entries) {
        runCatching {
            queryService.observeRelatedDraftCandidates(entries.map { it.draft.id })
        }.getOrElse { flowOf(emptyList<PendingBankDraftEntity>()) }
    }
    val relatedDrafts by relatedFlow.collectAsState(initial = emptyList())

    val categoryIds = entries.mapNotNull { it.draft.reviewCategoryId }.distinct()
    LaunchedEffect(queryService, categoryIds) {
        if (categoryIds.isEmpty()) {
            categoryNames = emptyMap()
            return@LaunchedEffect
        }
        categoryNames = try {
            queryService.categoriesByIds(categoryIds).associate { it.id to it.name }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyMap()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = strings.inboxTitle,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    TextButton(
                        onClick = { retryToken += 1 },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(strings.refresh) }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("review-inbox"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "lead") {
                Text(
                    text = strings.inboxLead,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (queueWaiting && !readError) {
                item(key = "count") {
                    Text(
                        text = knownWaitingCount?.let(strings::waiting) ?: strings.inboxReading,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            item(key = "order-help") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = if (focus.order == ReviewQueueOrder.UNCERTAINTY_FIRST) {
                            strings.orderNeedsFirstHelp
                        } else {
                            strings.orderOldestFirstHelp
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            runRead {
                                focus.setOrder(
                                    if (focus.order == ReviewQueueOrder.UNCERTAINTY_FIRST) {
                                        ReviewQueueOrder.CHRONOLOGICAL
                                    } else {
                                        ReviewQueueOrder.UNCERTAINTY_FIRST
                                    },
                                )
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.End)
                            .semantics { contentDescription = strings.orderChange },
                    ) {
                        Text(
                            text = if (focus.order == ReviewQueueOrder.UNCERTAINTY_FIRST) {
                                strings.orderNeedsFirst
                            } else {
                                strings.orderOldestFirst
                            },
                            maxLines = 1,
                        )
                    }
                }
            }
            item(key = "read-state") {
                when {
                    readError -> Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = strings.inboxReadError,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = { retryToken += 1 }) { Text(strings.retry) }
                    }

                    focus.reading || (focus.page == null && !readError) -> Text(
                        text = strings.inboxReading,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> Text(
                        text = strings.inboxLoaded,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (queueWaiting && !readError) {
                item(key = "page") {
                    PageControls(
                        pageIndex = focus.pageIndex,
                        hasMore = focus.page?.hasMore == true,
                        onPrevious = { runRead { focus.previousPage() } },
                        onNext = { runRead { focus.nextPage() } },
                        previousLabel = strings.previous,
                        nextLabel = strings.next,
                        pageLabel = strings.pageLabel(focus.pageIndex),
                    )
                }
            }
            if (entries.isEmpty()) {
                if (!focus.reading && !readError) {
                    item(key = "empty") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = if (queueWaiting) {
                                    strings.inboxEmptyPage
                                } else {
                                    strings.inboxEmptyTitle
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (!queueWaiting) {
                                Text(
                                    text = strings.inboxEmptyBody,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            } else {
                itemsIndexed(
                    items = entries,
                    key = { _, entry -> entry.draft.id },
                ) { index, entry ->
                    val previous = entries.getOrNull(index - 1)
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (previous == null || previous.group != entry.group) {
                            QueueSectionHeader(
                                entry = entry,
                                countOnPage = entries
                                    .drop(index)
                                    .takeWhile { candidate -> candidate.group == entry.group }
                                    .size,
                                continuesFromPreviousPage = previous == null &&
                                    focus.pageIndex > 0,
                                strings = strings,
                            )
                        }
                        DraftInboxCard(
                            entry = entry,
                            strings = strings,
                            categoryName = entry.draft.reviewCategoryId?.let {
                                categoryNames[it]
                            },
                            possibleDuplicate = entry.draft.possibleDuplicateOfDraftId?.let { id ->
                                relatedDrafts.firstOrNull { it.id == id }
                            },
                            onOpenDraft = { onOpenDraft(entry.draft.id) },
                            onOpenDuplicate = { duplicateId -> onOpenDraft(duplicateId) },
                        )
                    }
                }
            }
            item(key = "footer") {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = strings.inboxFooter,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun QueueSectionHeader(
    entry: ReviewQueueEntry,
    countOnPage: Int,
    continuesFromPreviousPage: Boolean,
    strings: ReviewStrings,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = strings.sectionTitle(entry.group),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = buildString {
                append(strings.sectionHelp(entry.group))
                append(" · ")
                if (continuesFromPreviousPage) append("${strings.continued}, ")
                append(strings.countOnPage(countOnPage))
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DraftInboxCard(
    entry: ReviewQueueEntry,
    strings: ReviewStrings,
    categoryName: String?,
    possibleDuplicate: PendingBankDraftEntity?,
    onOpenDraft: () -> Unit,
    onOpenDuplicate: (Long) -> Unit,
) {
    val draft = entry.draft
    val semantic = LocalAegisSemanticColors.current
    val amountColor = when (draft.direction) {
        io.github.triet4p.aegisf.data.IntakeDirection.CREDIT -> semantic.income
        io.github.triet4p.aegisf.data.IntakeDirection.DEBIT -> semantic.expense
        io.github.triet4p.aegisf.data.IntakeDirection.UNKNOWN -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("review-draft-${draft.id}")
            .clickable(role = Role.Button, onClick = onOpenDraft),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = reviewBankName(draft.bank),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (draft.amountVnd != null) {
                    AegisMoneyText(
                        amountVnd = draft.amountVnd,
                        prefix = strings.directionPrefix(draft.direction),
                        style = MaterialTheme.typography.titleMedium,
                        color = amountColor,
                    )
                } else {
                    Text(
                        text = strings.amountUnavailable,
                        style = MaterialTheme.typography.labelMedium,
                        color = semantic.expense,
                        maxLines = 1,
                    )
                }
            }
            Text(
                text = strings.statedMoment(draft),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = strings.memo(draft),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (draft.parseStatus == IntakeParseStatus.UNSUPPORTED_FORMAT) {
                ConflictBadge(text = strings.formatUnsupportedShort)
            }
            entry.conflicts.forEach { conflict ->
                if (conflict == ReviewQueueConflict.UNRESOLVED_BANK_DATA &&
                    draft.parseStatus == IntakeParseStatus.UNSUPPORTED_FORMAT
                ) {
                    return@forEach
                }
                ConflictBadge(text = strings.conflict(conflict))
            }
            Text(
                text = strings.categoryCardLine(categoryName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (possibleDuplicate != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = strings.possibleDuplicateLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.onWarningContainer,
                )
                TextButton(onClick = { onOpenDuplicate(possibleDuplicate.id) }) {
                    Text(
                        strings.openDuplicate(
                            possibleDuplicate.id,
                            strings.status(possibleDuplicate.reviewStatus),
                        ),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = strings.reviewDraft,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                AegisIcon(
                    glyph = AegisGlyph.CHEVRON_RIGHT,
                    tint = MaterialTheme.colorScheme.primary,
                    size = 18.dp,
                )
            }
        }
    }
}

@Composable
private fun ConflictBadge(text: String) {
    val semantic = LocalAegisSemanticColors.current
    Surface(
        shape = MaterialTheme.shapes.small,
        color = semantic.warningContainer,
        contentColor = semantic.onWarningContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AegisIcon(
                glyph = AegisGlyph.WARNING,
                tint = semantic.onWarningContainer,
                size = 14.dp,
            )
            Text(text = text, style = MaterialTheme.typography.labelSmall)
        }
    }
}
