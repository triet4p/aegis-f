package io.github.triet4p.aegisf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import io.github.triet4p.aegisf.notification.BankNotificationListener
import io.github.triet4p.aegisf.notification.isNotificationAccessGranted
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.triet4p.aegisf.data.BankDraftReviewEdits
import io.github.triet4p.aegisf.data.BankDraftReviewField
import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.ConfirmDraftRequest
import io.github.triet4p.aegisf.data.DismissDraftRequest
import io.github.triet4p.aegisf.data.DraftConfirmationPreview
import io.github.triet4p.aegisf.data.IntakeDirection
import io.github.triet4p.aegisf.data.IntakeFieldState
import io.github.triet4p.aegisf.data.IntakeParseStatus
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.CategoryQuery
import io.github.triet4p.aegisf.data.CategoryState
import io.github.triet4p.aegisf.data.ContextLinkState
import io.github.triet4p.aegisf.data.DraftContextLink
import io.github.triet4p.aegisf.data.ReviewQueueConflict
import io.github.triet4p.aegisf.data.ReviewQueueEntry
import io.github.triet4p.aegisf.data.ReviewQueueGroup
import io.github.triet4p.aegisf.data.ReviewQueueOrder
import io.github.triet4p.aegisf.data.LIST_PAGE_SIZE
import io.github.triet4p.aegisf.data.PreviewDraftConfirmationRequest
import io.github.triet4p.aegisf.data.QueryPage
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowChange
import io.github.triet4p.aegisf.data.WorkflowCommandOutcome
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowDenialReason
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.data.SaveDraftReviewRequest
import io.github.triet4p.aegisf.data.LedgerValidationException
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.data.TransactionType
import io.github.triet4p.aegisf.data.UnlinkLinkRequest
import io.github.triet4p.aegisf.data.UnrecordedNoteEvent
import io.github.triet4p.aegisf.data.VndMoney
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The home-screen review inbox and one draft's correction/confirmation surface. It reads the
 * persisted drafts after process recreation; the bank's fields stay visible beside user edits,
 * and confirmation never infers a ledger type from the bank's debit/credit direction.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BankDraftReviewScreen(
    queryService: WorkflowQueryService,
    commandService: WorkflowCommandService,
    draftId: Long?,
    onSelectDraft: (Long) -> Unit,
    onNavigateUp: () -> Unit,
    /**
     * False when the inbox is a primary destination's root: there is no previous screen,
     * so the screen must not draw a Back control or consume the system back gesture. The
     * shell owns navigation out of a root destination.
     */
    showBack: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (isNotificationAccessGranted(context)) {
            BankNotificationListener.requestActiveReconciliation(context)
        }
    }
    val scope = rememberCoroutineScope()
    // The inbox shows the review queue in the order the frozen policy sets: drafts whose link
    // or bank data still needs a decision first, then drafts that still need a category, then
    // drafts ready for confirm or dismiss. The chronological override orders the same drafts
    // by arrival. The holder keeps the shown rows a snapshot, so nothing reorders the list
    // while the user reads it; the explicit Refresh, order and next-work actions replace it.
    val focus = rememberSaveable(queryService, saver = ReviewQueueFocus.saver(queryService)) {
        ReviewQueueFocus(queryService)
    }
    // The rows are read when the inbox is opened and when the user comes back from a draft
    // (a navigation refresh), never because a score changed somewhere else.
    LaunchedEffect(focus, draftId) {
        if (draftId == null) focus.refresh()
    }
    // How many drafts are waiting in all: the count describes the whole inbox, so the
    // inbox can say so whichever page it is showing.
    val waitingCount by remember(queryService) {
        queryService.observeDraftCandidateCount(IntakeReviewStatus.PENDING)
    }.collectAsState(initial = null)
    val queueEntries = focus.page?.items.orEmpty()
    // The drafts that share their evidence with the listed ones: a possible duplicate is
    // kept and shown beside its partner, and the partner may already have been reviewed.
    val relatedFlow = remember(queryService, queueEntries) {
        queryService.observeRelatedDraftCandidates(queueEntries.map { it.draft.id })
    }
    val relatedDrafts by relatedFlow.collectAsState(initial = emptyList())
    // The opened draft is read on its own, so the detail view follows the draft's own
    // decisions — a saved correction, a confirmation, a dismissal — as they are written.
    val selectedDraftFlow = remember(queryService, draftId) {
        draftId?.let { queryService.observeDraftCandidate(it) } ?: flowOf(null)
    }
    val selectedDraft by selectedDraftFlow.collectAsState(initial = null)
    // The family of the opened draft itself: the earlier draft it points at, and the later
    // drafts that point at it.
    val selectedRelatedFlow = remember(queryService, draftId) {
        draftId?.let { queryService.observeRelatedDraftCandidates(listOf(it)) }
            ?: flowOf(emptyList())
    }
    val selectedRelatedDrafts by selectedRelatedFlow.collectAsState(initial = emptyList())

    BackHandler(enabled = showBack && draftId == null) { onNavigateUp() }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (draftId == null) "REVIEW NOTIFICATIONS" else "DRAFT #$draftId",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp,
                    )
                },
                navigationIcon = {
                    if (showBack) {
                        TextButton(onClick = onNavigateUp) { Text("Back") }
                    }
                },
                actions = {
                    val openDraftId = draftId
                    if (openDraftId != null) {
                        // Advancement is this explicit action, never an automatic jump: it
                        // re-reads the queue and opens the first draft that still needs a
                        // decision, so a changed score cannot move the open draft and the
                        // user always chooses when to move on.
                        TextButton(
                            enabled = (waitingCount ?: 0) > 1,
                            onClick = {
                                scope.launch {
                                    focus.nextWork(openDraftId)?.let(onSelectDraft)
                                }
                            },
                        ) {
                            Text("Next work")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        val openedDraft = selectedDraft
        if (draftId == null) {
            InboxContent(
                queryService = queryService,
                queue = focus.page,
                reading = focus.reading || focus.page == null,
                relatedDrafts = relatedDrafts,
                waitingCount = waitingCount,
                order = focus.order,
                pageIndex = focus.pageIndex,
                contentModifier = Modifier.padding(innerPadding),
                onOrderChange = { next -> scope.launch { focus.setOrder(next) } },
                onRefresh = { scope.launch { focus.refresh() } },
                onSelectDraft = onSelectDraft,
                onPreviousPage = { scope.launch { focus.previousPage() } },
                onNextPage = { scope.launch { focus.nextPage() } },
            )
        } else if (openedDraft == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Reading this draft…", style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = onNavigateUp) { Text("Back to inbox") }
            }
        } else {
            DraftDetailContent(
                draft = openedDraft,
                allDrafts = selectedRelatedDrafts,
                queryService = queryService,
                commandService = commandService,
                contentModifier = Modifier.padding(innerPadding),
                onSelectDraft = onSelectDraft,
                onNavigateUp = onNavigateUp,
            )
        }
    }
}

@Composable
private fun InboxContent(
    queryService: WorkflowQueryService,
    queue: QueryPage<ReviewQueueEntry>?,
    reading: Boolean,
    relatedDrafts: List<PendingBankDraftEntity>,
    waitingCount: Int?,
    order: ReviewQueueOrder,
    pageIndex: Int,
    contentModifier: Modifier,
    onOrderChange: (ReviewQueueOrder) -> Unit,
    onRefresh: () -> Unit,
    onSelectDraft: (Long) -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
) {
    val entries = queue?.items.orEmpty()
    Column(
        modifier = contentModifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Pending bank notifications are evidence to review, not transactions. " +
                "Unsupported formats and fields that need attention stay here until you decide.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = when {
                waitingCount == 0 -> "No drafts waiting for review."
                waitingCount != null -> "${waitingCount} waiting"
                entries.isEmpty() && queue?.hasMore != true -> "No drafts waiting for review."
                queue?.hasMore == true -> "${entries.size} on this page — more drafts are stored."
                else -> "${entries.size} waiting"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        // The two orders read the same drafts; the review order decides which ones come
        // first, and the chronological order is the always-available override.
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = order == ReviewQueueOrder.UNCERTAINTY_FIRST,
                onClick = { onOrderChange(ReviewQueueOrder.UNCERTAINTY_FIRST) },
                shape = MaterialTheme.shapes.small,
                label = { Text("Needs attention") },
            )
            SegmentedButton(
                selected = order == ReviewQueueOrder.CHRONOLOGICAL,
                onClick = { onOrderChange(ReviewQueueOrder.CHRONOLOGICAL) },
                shape = MaterialTheme.shapes.small,
                label = { Text("Oldest first") },
            )
        }
        Text(
            text = orderExplanation(order),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Reading is an explicit action: the list below stays as it was read, so a score
        // that changed elsewhere cannot move a draft while the user reads it.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (reading) {
                    "Reading the review queue…"
                } else {
                    "This order stays as read until you refresh."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRefresh) { Text("Refresh") }
        }
        // The drafts after this page are one page further on in the same order, so every
        // waiting draft is reachable and the inbox says which page it is showing. The
        // controls sit above the drafts, so they are reachable without walking past a
        // whole page of them first.
        PageControls(
            pageIndex = pageIndex,
            hasMore = queue?.hasMore == true,
            onPrevious = onPreviousPage,
            onNext = onNextPage,
        )
        if (entries.isEmpty()) {
            if (!reading) {
                Text(
                    text = if (waitingCount == 0) {
                        "No drafts waiting for review."
                    } else {
                        "No drafts on this page."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            entries.forEachIndexed { index, entry ->
                val previous = entries.getOrNull(index - 1)
                if (previous == null || !sameQueueSection(previous, entry)) {
                    QueueSectionHeader(
                        entry = entry,
                        countOnPage = entries
                            .drop(index)
                            .takeWhile { candidate -> sameQueueSection(entry, candidate) }
                            .size,
                        continuesFromPreviousPage = previous == null && pageIndex > 0,
                    )
                }
                DraftInboxCard(
                    entry = entry,
                    possibleDuplicate = entry.draft.possibleDuplicateOfDraftId?.let { priorId ->
                        relatedDrafts.firstOrNull { it.id == priorId }
                    },
                    onSelectDraft = { onSelectDraft(entry.draft.id) },
                    onOpenDuplicate = { duplicateId -> onSelectDraft(duplicateId) },
                )
            }
        }
        Text(
            text = "Confirmed and dismissed drafts are kept for replay protection and are not " +
                "listed as pending. A possible duplicate remains a candidate; it is never discarded automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The heading of one visible queue section.
 *
 * The section is the group the policy puts the drafts in, or — inside the fresh-prediction
 * group — one comparability group, because predictions of different evaluated sets are never
 * compared. The heading states why the section is where it is and how many of its drafts this
 * page holds, so a group longer than a page is never mistaken for the whole group.
 */
@Composable
private fun QueueSectionHeader(
    entry: ReviewQueueEntry,
    countOnPage: Int,
    continuesFromPreviousPage: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = queueSectionTitle(entry),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = buildString {
                append(queueSectionExplanation(entry))
                append(" · ")
                if (continuesFromPreviousPage) append("continued, ")
                append("$countOnPage on this page")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DraftInboxCard(
    entry: ReviewQueueEntry,
    possibleDuplicate: PendingBankDraftEntity?,
    onSelectDraft: () -> Unit,
    onOpenDuplicate: (Long) -> Unit,
) {
    val draft = entry.draft
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelectDraft),
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = bankLabel(draft.bank),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (draft.parseStatus == IntakeParseStatus.UNSUPPORTED_FORMAT) {
                        "Unsupported format"
                    } else {
                        "Pending"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Why this draft is where it is in the order: the group's own reason, never a
            // new score and never a claim that a suggestion is right.
            Text(
                text = queueEntryLabel(entry),
                style = MaterialTheme.typography.bodySmall,
                color = if (entry.group == ReviewQueueGroup.NEEDS_DECISION) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text("Source: ${draft.sourcePackage}", style = MaterialTheme.typography.bodySmall)
            Text("Date and time: ${statedMomentLabel(draft)}", style = MaterialTheme.typography.bodyMedium)
            Text("Amount: ${amountLabel(draft)}", style = MaterialTheme.typography.bodyMedium)
            Text("Direction: ${directionLabel(draft.direction)}", style = MaterialTheme.typography.bodyMedium)
            Text("Memo: ${memoLabel(draft)}", style = MaterialTheme.typography.bodyMedium)
            if (possibleDuplicate != null) {
                HorizontalDivider()
                Text(
                    text = "Possible duplicate. Both drafts are kept because the evidence " +
                        "cannot establish whether these are the same transaction.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = { onOpenDuplicate(possibleDuplicate.id) }) {
                    Text("View draft #${possibleDuplicate.id} — ${statusLabel(possibleDuplicate)}")
                }
            }
            TextButton(onClick = onSelectDraft) { Text("Review draft #${draft.id}") }
        }
    }
}

/**
 * Whether two queue entries share one visible section of the page.
 *
 * A fresh-prediction section is one comparability group, so two drafts whose predictions
 * were made over different evaluated sets are never shown as one section and their
 * confidence values are never presented as comparable.
 */
private fun sameQueueSection(first: ReviewQueueEntry, second: ReviewQueueEntry): Boolean =
    first.group == second.group

/** The heading of one queue section, named by the reason the policy sorts it there. */
private fun queueSectionTitle(entry: ReviewQueueEntry): String = when (entry.group) {
    ReviewQueueGroup.NEEDS_DECISION -> "Needs your decision"
    ReviewQueueGroup.NEEDS_CATEGORY -> "Needs a category"
    ReviewQueueGroup.READY -> "Ready to confirm"
}

/** Why one queue section is where it is in the order. */
private fun queueSectionExplanation(entry: ReviewQueueEntry): String = when (entry.group) {
    ReviewQueueGroup.NEEDS_DECISION ->
        "Unresolved matches and bank data that needs attention come first."
    ReviewQueueGroup.NEEDS_CATEGORY ->
        "These drafts still need a category; the oldest is first."
    ReviewQueueGroup.READY ->
        "These drafts already have a category and wait for confirm or dismiss."
}

/** The order control's explanation of what the current order does. */
private fun orderExplanation(order: ReviewQueueOrder): String = when (order) {
    ReviewQueueOrder.UNCERTAINTY_FIRST ->
        "Needs your decision first, then drafts that still need a category, then drafts " +
            "ready to confirm. Within a section, the oldest comes first."
    ReviewQueueOrder.CHRONOLOGICAL ->
        "Every waiting draft by arrival, so older work stays reachable."
}

/** One draft's group and reason, as the inbox states it on the card. */
private fun queueEntryLabel(entry: ReviewQueueEntry): String = when (entry.group) {
    ReviewQueueGroup.NEEDS_DECISION -> if (entry.conflicts.isEmpty()) {
        "Needs your decision"
    } else {
        "Needs attention: " + entry.conflicts.joinToString(", ") { conflictLabel(it) }
    }
    ReviewQueueGroup.NEEDS_CATEGORY -> "Category not chosen yet"
    ReviewQueueGroup.READY -> if (entry.draft.reviewCategoryResolved) {
        "Category chosen — confirm or dismiss"
    } else {
        "Ready for confirm or dismiss"
    }
}

/** The unresolved fact that put one draft in the first group. */
private fun conflictLabel(conflict: ReviewQueueConflict): String = when (conflict) {
    ReviewQueueConflict.POSSIBLE_DUPLICATE -> "possible duplicate"
    ReviewQueueConflict.PROPOSED_LINK -> "note association not decided"
    ReviewQueueConflict.UNRESOLVED_BANK_DATA -> "bank data needs attention"
}

@Composable
private fun DraftDetailContent(
    draft: PendingBankDraftEntity,
    allDrafts: List<PendingBankDraftEntity>,
    queryService: WorkflowQueryService,
    commandService: WorkflowCommandService,
    contentModifier: Modifier,
    onSelectDraft: (Long) -> Unit,
    onNavigateUp: () -> Unit,
) {
    var amountText by rememberSaveable(draft.id) {
        mutableStateOf(draft.reviewAmountText ?: draft.amountVnd?.toString().orEmpty())
    }
    var dateText by rememberSaveable(draft.id) {
        mutableStateOf(draft.reviewDateText ?: draft.statedDate.orEmpty())
    }
    var timeText by rememberSaveable(draft.id) {
        mutableStateOf(draft.reviewTimeText ?: draft.statedTime.orEmpty())
    }
    var memoText by rememberSaveable(draft.id) {
        mutableStateOf(if (draft.reviewMemoEdited) draft.reviewMemo.orEmpty() else draft.memo.orEmpty())
    }
    var selectedTypeName by rememberSaveable(draft.id) {
        mutableStateOf(draft.reviewType?.name)
    }
    var selectedCategoryId by rememberSaveable(draft.id) {
        mutableStateOf(draft.reviewCategoryId)
    }
    var categoryResolved by rememberSaveable(draft.id) {
        mutableStateOf(draft.reviewCategoryResolved)
    }
    var expectedRevision by rememberSaveable(draft.id) {
        mutableIntStateOf(draft.reviewRevision)
    }
    var validationRequested by rememberSaveable(draft.id) { mutableStateOf(false) }
    // The confirmation the user is about to approve: the exact fields it would write, the
    // binding that ties them to the state they were read from, and the edits they were
    // computed from. Keeping the edits is what lets the screen notice that the form moved
    // after the preview, so a stale preview is shown as stale instead of being confirmed.
    var pendingConfirmation by remember { mutableStateOf<PendingConfirmation?>(null) }
    var busy by remember { mutableStateOf(false) }
    var previewing by remember { mutableStateOf(false) }
    var confirmingDismiss by rememberSaveable(draft.id) { mutableStateOf(false) }
    var feedback by rememberSaveable(draft.id) { mutableStateOf<String?>(null) }
    // The stored note context of this draft: the excerpts the user attached, proposed, or
    // refused, read through the shared bounded query. The reload counter is bumped after
    // every explicit link action so the section follows the stored decision it just made.
    var contextReload by remember(draft.id) { mutableIntStateOf(0) }
    var contextPageIndex by rememberSaveable(draft.id) { mutableIntStateOf(0) }
    var contextPage by remember(draft.id) { mutableStateOf<QueryPage<DraftContextLink>?>(null) }
    var contextLoading by remember(draft.id) { mutableStateOf(true) }
    var contextError by remember(draft.id) { mutableStateOf<String?>(null) }
    var contextFeedback by rememberSaveable(draft.id) { mutableStateOf<String?>(null) }
    var contextFeedbackIsError by rememberSaveable(draft.id) { mutableStateOf(false) }
    var contextActionBusy by remember { mutableStateOf(false) }
    var addingContext by rememberSaveable(draft.id) { mutableStateOf(false) }
    val selectedType = selectedTypeName?.let { TransactionType.valueOf(it) }
    val active = draft.reviewStatus == IntakeReviewStatus.PENDING &&
        draft.confirmedTransactionId == null
    val scope = rememberCoroutineScope()
    val writeInFlight = remember(draft.id) { AtomicBoolean(false) }
    val contextWriteInFlight = remember(draft.id) { AtomicBoolean(false) }
    // The whole catalog, live and read in bounded chunks: an archive or a rename must be
    // visible at once, and the choice list resolves it by stable id.
    val catalog: List<CategoryEntity>? by remember(queryService) {
        queryService.observeCategoryCatalog()
    }.collectAsState(initial = null)
    // The choice list reads the catalog page by page, so every stored category can be
    // chosen and not only the ones on the first page.
    var categoryPageIndex by rememberSaveable(draft.id) { mutableIntStateOf(0) }
    val categoryQuery = remember(categoryPageIndex) {
        CategoryQuery(
            state = CategoryState.ACTIVE,
            bounds = QueryPageRequest(LIST_PAGE_SIZE, offset = categoryPageIndex * LIST_PAGE_SIZE),
        )
    }
    val categoryPage: QueryPage<CategoryEntity>? by remember(queryService, categoryQuery) {
        queryService.observeCategories(categoryQuery)
    }.collectAsState(initial = null)
    val choiceCategories = categoryPage?.items.orEmpty()
    // The chosen category is read by its own id, so it stays visible and marked on every
    // page of the choice list.
    val chosenId = selectedCategoryId
    val chosenCategory: CategoryEntity? by remember(queryService, chosenId) {
        if (chosenId == null) {
            flowOf(null)
        } else {
            queryService.observeCategory(chosenId)
        }
    }.collectAsState(initial = null)
    // A page that lost its categories steps back to the nearest page that still holds
    // some, so the choice list never shows an empty page in the middle of the catalog.
    ClampPageToRows(
        pageIndex = categoryPageIndex,
        hasRows = categoryPage?.items?.isNotEmpty(),
        onClamp = { categoryPageIndex = it },
    )
    LaunchedEffect(queryService, draft.id, contextReload, contextPageIndex) {
        contextLoading = true
        contextError = null
        try {
            contextPage = queryService.draftContextLinks(
                draftId = draft.id,
                bounds = QueryPageRequest(
                    limit = LIST_PAGE_SIZE,
                    offset = contextPageIndex * LIST_PAGE_SIZE,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            contextError =
                "Note context could not be loaded. The bank evidence and the review fields are unchanged."
        } finally {
            contextLoading = false
        }
    }
    ClampPageToRows(
        pageIndex = contextPageIndex,
        hasRows = contextPage?.items?.isNotEmpty(),
        onClamp = { contextPageIndex = it },
    )
    val edits = BankDraftReviewEdits(
        amountText = amountText,
        dateText = dateText,
        timeText = timeText,
        memoText = memoText,
        type = selectedType,
        categoryId = selectedCategoryId,
        categoryResolved = categoryResolved,
    )
    val amountError = if (validationRequested) manualAmountError(amountText) else null
    val dateError = if (validationRequested) ManualDateTime.dateError(dateText) else null
    val timeError = if (validationRequested) ManualDateTime.timeError(timeText) else null
    val typeError = validationRequested && selectedType == null
    val categoryError = validationRequested &&
        selectedType != TransactionType.TRANSFER && !categoryResolved

    /**
     * Shows why a write was refused.
     *
     * The words are the boundary's own: a moved draft, a category that is gone, and a
     * draft that was already decided each say what happened, and none of them leaves the
     * user guessing about the money.
     */
    fun handleOutcome(outcome: WorkflowCommandOutcome, action: String) {
        when (outcome) {
            is WorkflowCommandOutcome.Applied -> {
                val change = outcome.change
                if (change is WorkflowChange.DraftReviewSaved) {
                    expectedRevision = change.revision
                    feedback = "Corrections saved."
                } else {
                    onNavigateUp()
                }
            }
            is WorkflowCommandOutcome.NoChange -> {
                val change = outcome.change
                if (change is WorkflowChange.DraftReviewSaved) {
                    expectedRevision = change.revision
                    feedback = "Corrections saved."
                } else {
                    feedback = outcome.detail
                        ?: "This draft was already confirmed or dismissed. No additional " +
                        "transaction was added."
                }
            }
            is WorkflowCommandOutcome.NeedsClarification -> {
                validationRequested = true
                feedback = correctionMessage(
                    BankDraftReviewField.entries.filter { it.name == outcome.field }.toSet(),
                )
            }
            is WorkflowCommandOutcome.Denied -> {
                feedback = when (outcome.reason) {
                    WorkflowDenialReason.CATEGORY_UNAVAILABLE ->
                        "That category is no longer available. Choose an active category " +
                            "and try again."
                    else -> "This draft could not be changed."
                }
            }
            is WorkflowCommandOutcome.Conflict -> feedback = outcome.reason
            WorkflowCommandOutcome.NotFound -> feedback = "This draft is no longer available."
            is WorkflowCommandOutcome.Failed -> feedback = outcome.reason
            WorkflowCommandOutcome.Cancelled -> feedback = "The request was cancelled."
        }
        if (action == "dismiss") confirmingDismiss = false
    }

    fun saveCorrections() {
        if (!writeInFlight.compareAndSet(false, true)) return
        busy = true
        feedback = null
        scope.launch {
            try {
                handleOutcome(
                    commandService.saveDraftReview(
                        scope = WorkflowCallerScope.User,
                        request = SaveDraftReviewRequest(
                            idempotencyKey = "draft-review:${draft.id}:$expectedRevision",
                            draftId = draft.id,
                            expectedRevision = expectedRevision,
                            edits = edits,
                        ),
                    ),
                    "save",
                )
            } finally {
                busy = false
                writeInFlight.set(false)
            }
        }
    }

    /**
     * Looks at what a confirmation would write before it writes it.
     *
     * The preview resolves the wall-clock text under the device's own zone policy and
     * names the exact fields, so the confirmation the user approves is the confirmation
     * that happens. The binding it returns is carried into the confirmation itself: if
     * anything moved on in between, the confirmation is refused instead of overwriting it.
     */
    fun preview() {
        if (!writeInFlight.compareAndSet(false, true)) return
        validationRequested = true
        busy = true
        previewing = true
        feedback = null
        scope.launch {
            try {
                when (
                    val preview = commandService.previewDraftConfirmation(
                        scope = WorkflowCallerScope.User,
                        request = PreviewDraftConfirmationRequest(
                            draftId = draft.id,
                            edits = edits,
                        ),
                    )
                ) {
                    is DraftConfirmationPreview.Ready -> {
                        // The preview is the state the confirmation will name, so adopt its
                        // revision: a check after a concurrent change makes the next save
                        // or confirmation target the state the user just saw.
                        expectedRevision = preview.expectedRevision
                        pendingConfirmation = PendingConfirmation(preview, edits)
                    }
                    is DraftConfirmationPreview.NeedsCorrection -> {
                        validationRequested = true
                        feedback = correctionMessage(preview.fields)
                    }
                    is DraftConfirmationPreview.Conflict -> feedback = preview.reason
                    is DraftConfirmationPreview.Denied -> feedback =
                        "That category is no longer available. Choose an active category " +
                            "and try again."
                    DraftConfirmationPreview.NotFound ->
                        feedback = "This draft is no longer available."
                }
            } finally {
                busy = false
                previewing = false
                writeInFlight.set(false)
            }
        }
    }

    fun confirm() {
        val pending = pendingConfirmation ?: return
        if (!writeInFlight.compareAndSet(false, true)) return
        busy = true
        feedback = null
        scope.launch {
            try {
                handleOutcome(
                    commandService.confirmDraft(
                        scope = WorkflowCallerScope.User,
                        request = ConfirmDraftRequest(
                            idempotencyKey =
                                "draft-confirm:${draft.id}:${pending.preview.expectedRevision}",
                            draftId = draft.id,
                            // The confirmation carries the revision and fields the preview
                            // was made from, so checking the current values makes a
                            // confirmation possible again after the draft moved on.
                            expectedRevision = pending.preview.expectedRevision,
                            expectedBinding = pending.preview.binding,
                            edits = pending.edits,
                        ),
                    ),
                    "confirm",
                )
            } finally {
                pendingConfirmation = null
                busy = false
                writeInFlight.set(false)
            }
        }
    }

    fun dismiss() {
        if (!writeInFlight.compareAndSet(false, true)) return
        busy = true
        feedback = null
        scope.launch {
            try {
                handleOutcome(
                    commandService.dismissDraft(
                        scope = WorkflowCallerScope.User,
                        request = DismissDraftRequest(
                            idempotencyKey = "draft-dismiss:${draft.id}:$expectedRevision",
                            draftId = draft.id,
                            expectedRevision = expectedRevision,
                        ),
                    ),
                    "dismiss",
                )
            } finally {
                busy = false
                writeInFlight.set(false)
            }
        }
    }

    /**
     * Runs one explicit note-context decision and reports what the stored row says.
     *
     * The decision goes through the shared command boundary, so a refused, stale, or
     * failed request writes nothing and its own words are shown. The bank amount, the
     * review fields, and the ledger are untouched by every branch here; only the link
     * row changes, and the section re-reads it afterwards.
     */
    fun runContextAction(
        successMessage: String,
        action: suspend () -> WorkflowCommandOutcome,
    ) {
        if (!contextWriteInFlight.compareAndSet(false, true)) return
        contextActionBusy = true
        contextFeedback = null
        scope.launch {
            try {
                val outcome = action()
                val applied = outcome.isAppliedOrNoChange()
                contextFeedback = if (applied) successMessage else outcome.linkFailureMessage()
                contextFeedbackIsError = !applied
                contextReload += 1
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                contextFeedback =
                    "The note decision did not complete. No financial amount was changed; reload and try again."
                contextFeedbackIsError = true
            } finally {
                contextActionBusy = false
                contextWriteInFlight.set(false)
            }
        }
    }

    fun unlinkContext(link: DraftContextLink) {
        runContextAction(
            successMessage = "Note excerpt unlinked. The bank amount and ledger are unchanged.",
        ) {
            commandService.unlinkLink(
                scope = WorkflowCallerScope.User,
                request = UnlinkLinkRequest(
                    idempotencyKey = UUID.randomUUID().toString(),
                    eventId = link.eventId,
                    draftId = link.draftId,
                    expectedRevision = link.revision,
                ),
            )
        }
    }

    fun attachContext(link: DraftContextLink) {
        runContextAction(
            successMessage = "Note excerpt attached. The bank amount and ledger are unchanged.",
        ) {
            decideUserLink(
                commandService = commandService,
                eventId = link.eventId,
                draftId = link.draftId,
                attach = true,
                overrideNote = "User explicitly attached note context in Review.",
            )
        }
    }

    fun rejectContext(link: DraftContextLink) {
        runContextAction(
            successMessage = "Note excerpt refused. That decision is retained.",
        ) {
            decideUserLink(
                commandService = commandService,
                eventId = link.eventId,
                draftId = link.draftId,
                attach = false,
                overrideNote = "User explicitly refused note context in Review.",
            )
        }
    }

    BackHandler { if (!busy) onNavigateUp() }

    Column(
        modifier = contentModifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(4.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(bankLabel(draft.bank), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("Review status: ${statusLabel(draft)}", style = MaterialTheme.typography.bodyMedium)
                Text("Source package: ${draft.sourcePackage}", style = MaterialTheme.typography.bodySmall)
                Text("Stated date/time: ${statedMomentLabel(draft)}", style = MaterialTheme.typography.bodyMedium)
                Text("Stated amount: ${amountLabel(draft)}", style = MaterialTheme.typography.bodyMedium)
                Text("Bank direction: ${directionLabel(draft.direction)}", style = MaterialTheme.typography.bodyMedium)
                Text("Stated memo: ${memoLabel(draft)}", style = MaterialTheme.typography.bodyMedium)
                if (draft.parseStatus == IntakeParseStatus.UNSUPPORTED_FORMAT) {
                    Text(
                        "This notification did not match a supported bank format. Review every " +
                            "required field yourself before confirmation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        val possiblePrior = draft.possibleDuplicateOfDraftId?.let { id ->
            allDrafts.firstOrNull { it.id == id }
        }
        if (possiblePrior != null) {
            DuplicateWarning(draftId = possiblePrior.id, status = statusLabel(possiblePrior)) {
                onSelectDraft(possiblePrior.id)
            }
        }
        val laterCandidates = allDrafts.filter { it.possibleDuplicateOfDraftId == draft.id }
        laterCandidates.forEach { candidate ->
            DuplicateWarning(draftId = candidate.id, status = statusLabel(candidate)) {
                onSelectDraft(candidate.id)
            }
        }

        DraftContextSection(
            links = contextPage?.items.orEmpty(),
            loading = contextLoading,
            error = contextError,
            pageIndex = contextPageIndex,
            hasMore = contextPage?.hasMore == true,
            active = active,
            busy = busy || contextActionBusy,
            feedback = contextFeedback,
            feedbackIsError = contextFeedbackIsError,
            onPrevious = { contextPageIndex -= 1 },
            onNext = { contextPageIndex += 1 },
            onRetry = { contextReload += 1 },
            onAddContext = { addingContext = true },
            onAttach = ::attachContext,
            onReject = ::rejectContext,
            onUnlink = ::unlinkContext,
        )

        if (!active) {
            Text(
                text = when (draft.reviewStatus) {
                    IntakeReviewStatus.CONFIRMED -> "This draft is terminal. Replaying its notification will not create another transaction."
                    IntakeReviewStatus.DISMISSED -> "This draft was dismissed. Replaying its notification will not reopen it."
                    IntakeReviewStatus.PENDING -> "This draft is no longer pending."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (draft.confirmedTransactionId != null) {
                Text("Linked transaction: #${draft.confirmedTransactionId}")
            }
            TextButton(onClick = onNavigateUp) { Text("Back to inbox") }
        } else {
            Text(
                text = "A debit is not automatically an expense, and a credit is not automatically " +
                    "income. Choose the ledger type that matches what happened.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            sourceReason("Amount", draft.amountState, draft.amountUnresolvedReason)
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it; feedback = null },
                label = { Text("Amount (VND)") },
                placeholder = { Text("50000") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = amountError != null,
                supportingText = {
                    Text(amountError ?: "Enter whole VND digits; no amount is filled in for you.")
                },
                modifier = Modifier.fillMaxWidth(),
            )

            sourceReason("Date and time", draft.momentState, draft.momentUnresolvedReason)
            OutlinedTextField(
                value = dateText,
                onValueChange = { dateText = it; feedback = null },
                label = { Text("Date") },
                placeholder = { Text("2026-10-16") },
                singleLine = true,
                isError = dateError != null,
                supportingText = { Text(dateError ?: "YYYY-MM-DD") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = timeText,
                onValueChange = { timeText = it; feedback = null },
                label = { Text("Time") },
                placeholder = { Text("07:38") },
                singleLine = true,
                isError = timeError != null,
                supportingText = { Text(timeError ?: "HH:mm") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "The bank supplied a wall-clock time without a time zone. On confirmation, " +
                    "Aegis F interprets this date and time in the device's current time zone. " +
                    "Correct either field if that is not right.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("Type", style = MaterialTheme.typography.labelLarge)
            TransactionType.entries.forEach { type ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedTypeName = type.name
                            if (type == TransactionType.TRANSFER) {
                                selectedCategoryId = null
                                categoryResolved = false
                            }
                            feedback = null
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selectedType == type,
                        onClick = {
                            selectedTypeName = type.name
                            if (type == TransactionType.TRANSFER) {
                                selectedCategoryId = null
                                categoryResolved = false
                            }
                            feedback = null
                        },
                    )
                    Text(typeLabel(type), style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (typeError) {
                Text("Choose Expense, Income, or Internal transfer.", color = MaterialTheme.colorScheme.error)
            }

            sourceReason("Memo", draft.memoState, draft.memoUnresolvedReason)
            OutlinedTextField(
                value = memoText,
                onValueChange = {
                    memoText = it
                    feedback = null
                },
                label = { Text("Description (optional)") },
                placeholder = { Text("Leave blank if unknown") },
                singleLine = true,
                supportingText = {
                    Text(if (draft.memoState != IntakeFieldState.PRESENT && !draft.reviewMemoEdited) {
                        "The bank memo remains unresolved; you may leave it blank."
                    } else {
                        "Optional"
                    })
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (selectedType == TransactionType.TRANSFER) {
                Text(
                    "An internal transfer carries no category, so no category is stored here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selectedType == TransactionType.TRANSFER) {
                Text(
                    "An internal transfer moves money between your own holdings and carries no category.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("Category", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Choose a category or explicitly choose No category.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CategoryChoice(
                    name = "No category",
                    selected = categoryResolved && selectedCategoryId == null,
                ) {
                    selectedCategoryId = null
                    categoryResolved = true
                    feedback = null
                }
                // The categories after this page are one page further on in the same read,
                // so every stored category can be chosen from here. The controls sit above
                // the choices, so they are reachable without walking past a whole page of
                // them first.
                PageControls(
                    pageIndex = categoryPageIndex,
                    hasMore = categoryPage?.hasMore == true,
                    onPrevious = { categoryPageIndex -= 1 },
                    onNext = { categoryPageIndex += 1 },
                )
                val chosen = chosenCategory
                if (chosen != null && choiceCategories.none { it.id == chosen.id }) {
                    CategoryChoice(
                        name = chosen.name,
                        categoryId = chosen.id,
                        archived = chosen.archived,
                        selected = true,
                    ) {
                        selectedCategoryId = chosen.id
                        categoryResolved = true
                        feedback = null
                    }
                }
                choiceCategories.forEach { category ->
                    CategoryChoice(
                        name = category.name,
                        categoryId = category.id,
                        archived = category.archived,
                        selected = category.id == selectedCategoryId,
                    ) {
                        selectedCategoryId = category.id
                        categoryResolved = true
                        feedback = null
                    }
                }
                val knownCatalog = catalog
                if (knownCatalog != null && knownCatalog.none { !it.archived }) {
                    Text(
                        "There are no active categories. You can explicitly choose No category.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (categoryError) {
                    Text("Choose a category or explicitly choose No category.", color = MaterialTheme.colorScheme.error)
                }
            }

            if (feedback != null) {
                Text(
                    text = feedback.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (feedback!!.startsWith("Corrections saved")) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            Button(shape = MaterialTheme.shapes.small, onClick = ::preview,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),) { Text(
                when {
                    previewing -> "Checking exact values…"
                    busy -> "Working…"
                    else -> "Confirm transaction"
                },
            ) }
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = ::saveCorrections,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),) { Text("Save corrections") }
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { confirmingDismiss = true },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),) { Text("Dismiss draft") }
        }
    }

    if (confirmingDismiss && active) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmingDismiss = false },
            title = { Text("Dismiss draft #${draft.id}?") },
            text = {
                Text("It will not create a ledger record or change totals. Its replay identity stays terminal.")
            },
            confirmButton = {
                TextButton(onClick = ::dismiss, enabled = !busy) {
                    Text(if (busy) "Dismissing…" else "Dismiss draft")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDismiss = false }, enabled = !busy) {
                    Text("Keep reviewing")
                }
            },
        )
    }

    val pending = pendingConfirmation
    if (pending != null) {
        // A preview is a promise about one exact state. When the draft's own revision or
        // the form's values moved after it was made, the promise is stale: say so and offer
        // a fresh check instead of letting Confirm carry an outdated binding.
        val preview = pending.preview
        val staleReason = when {
            !active ->
                "This draft was already confirmed or dismissed after the preview. " +
                    "Nothing was written here."
            draft.reviewRevision != preview.expectedRevision ->
                "This draft changed after the preview (review revision " +
                    "${preview.expectedRevision} → ${draft.reviewRevision}). " +
                    "Nothing has been written."
            pending.edits != edits ->
                "The review fields changed after the preview, so the values below are no " +
                    "longer what would be written. Nothing has been written."
            else -> null
        }
        AlertDialog(
            onDismissRequest = { if (!busy) pendingConfirmation = null },
            title = {
                Text(if (staleReason == null) "Confirm this record?" else "Preview is stale")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (staleReason != null) {
                        Text(
                            text = staleReason,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            "The bank notification, the draft, and your unsaved fields are " +
                                "still here. Check the current values before confirming.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "These are the exact values that will be stored:",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text("Amount: ${VndMoney.formatAmount(preview.fields.amountVnd)}")
                    Text("Type: ${typeLabel(preview.fields.type)}")
                    Text(
                        "When: ${ManualDateTime.formatMoment(preview.fields.occurredAtEpochMillis)} " +
                            "device time",
                    )
                    Text(
                        "Description: " +
                            (preview.fields.description.ifEmpty { "(none)" }),
                    )
                    Text(
                        "Category: " + (
                            preview.fields.categoryId?.let { categoryId ->
                                catalog?.firstOrNull { it.id == categoryId }?.name ?: "unknown"
                            } ?: "no category"
                            ),
                    )
                    val risk = preview.duplicateRisk
                    if (risk != null) {
                        Text(
                            text = "A stored record may already be this money: " +
                                "${VndMoney.formatAmount(risk.amountVnd)} on " +
                                "${ManualDateTime.formatMoment(risk.occurredAtEpochMillis)}" +
                                (if (risk.description.isEmpty()) "" else " (${risk.description})") +
                                ". Confirming adds a second record; the first one stays.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        "The bank notification stays stored as evidence either way.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                if (staleReason == null) {
                    TextButton(
                        onClick = ::confirm,
                        enabled = !busy,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(if (busy) "Confirming…" else "Confirm")
                    }
                } else {
                    TextButton(
                        onClick = ::preview,
                        enabled = !busy && active,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(if (previewing) "Checking…" else "Check current values")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingConfirmation = null },
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Back to review")
                }
            },
        )
    }

    if (addingContext && active) {
        AddNoteContextDialog(
            draftId = draft.id,
            queryService = queryService,
            commandService = commandService,
            onDismiss = { addingContext = false },
            onLinked = { message ->
                contextFeedback = message
                contextFeedbackIsError = false
                contextReload += 1
            },
        )
    }
}

/**
 * One preview the user can approve: the exact fields it would write and the form values
 * they were computed from.
 *
 * The edits are kept beside the preview so the screen can notice that the form moved
 * after the preview. A stale preview is then shown as stale and its Confirm action is
 * withheld; it is never carried into a confirmation that would write other values.
 */
private data class PendingConfirmation(
    val preview: DraftConfirmationPreview.Ready,
    val edits: BankDraftReviewEdits,
)

/**
 * The user's own note excerpts beside one bank draft's review.
 *
 * These are the words the user saved, not a match the app invented: each card names the
 * exact source span, the reading, and the stored revisions, and the actions are the
 * user's explicit decisions through the shared command boundary. Attaching, refusing,
 * or unlinking an excerpt touches only the link row — the bank amount, the review fields,
 * and the ledger are never changed by any of them.
 */
@Composable
private fun DraftContextSection(
    links: List<DraftContextLink>,
    loading: Boolean,
    error: String?,
    pageIndex: Int,
    hasMore: Boolean,
    active: Boolean,
    busy: Boolean,
    feedback: String?,
    feedbackIsError: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRetry: () -> Unit,
    onAddContext: () -> Unit,
    onAttach: (DraftContextLink) -> Unit,
    onReject: (DraftContextLink) -> Unit,
    onUnlink: (DraftContextLink) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Note context", style = MaterialTheme.typography.labelLarge)
        Text(
            "Your own saved words beside the bank's statement. An excerpt is provenance " +
                "only: attaching or removing one never changes the bank amount, the review " +
                "fields, or the ledger.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (loading && links.isEmpty()) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("Loading note excerpts…", style = MaterialTheme.typography.bodyMedium)
        }
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onRetry) { Text("Retry note context") }
        }
        if (!loading && error == null && links.isEmpty() && pageIndex == 0) {
            Text(
                text = "No note excerpt is attached. The bank evidence stands on its own, so " +
                    "you can review and confirm without one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (pageIndex > 0 || hasMore) {
            PageControls(
                pageIndex = pageIndex,
                hasMore = hasMore,
                onPrevious = onPrevious,
                onNext = onNext,
            )
        }
        links.forEach { link ->
            DraftContextCard(
                link = link,
                active = active,
                busy = busy,
                onAttach = { onAttach(link) },
                onReject = { onReject(link) },
                onUnlink = { onUnlink(link) },
            )
        }
        if (feedback != null) {
            Text(
                text = feedback,
                style = MaterialTheme.typography.bodyMedium,
                color = if (feedbackIsError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
        if (active) {
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = onAddContext,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),) { Text("Add note context") }
        }
    }
}

@Composable
private fun DraftContextCard(
    link: DraftContextLink,
    active: Boolean,
    busy: Boolean,
    onAttach: () -> Unit,
    onReject: () -> Unit,
    onUnlink: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = when (link.state) {
                    ContextLinkState.ACCEPTED -> "Attached note excerpt"
                    ContextLinkState.PROPOSED -> "Proposed note excerpt"
                    ContextLinkState.REJECTED -> "Refused note excerpt (decision retained)"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Saved note #${link.captureId?.toString() ?: "unavailable"} · " +
                    "event #${link.eventId}",
                style = MaterialTheme.typography.bodySmall,
            )
            val excerpt = link.excerpt
            if (excerpt != null) {
                Text(
                    text = "Source excerpt: “$excerpt”",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    text = "The source changed after this reading (saved note revision " +
                        "${link.captureRevision?.toString() ?: "gone"} vs reading revision " +
                        "${link.eventSourceRevision?.toString() ?: "gone"}), so it is not " +
                        "shown as your words.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            link.readingText?.let { reading ->
                Text("Reading: $reading", style = MaterialTheme.typography.bodySmall)
            }
            val facts = buildList {
                link.amountVnd?.let { add(VndMoney.formatAmount(it)) }
                link.anchorDate?.let { add(it) }
                link.merchant?.takeIf(String::isNotBlank)?.let { add(it) }
            }
            if (facts.isNotEmpty()) {
                Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = "Reading revision ${link.eventRevision?.toString() ?: "gone"} · " +
                    "link revision ${link.revision}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (active) {
                when (link.state) {
                    ContextLinkState.PROPOSED -> Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(shape = MaterialTheme.shapes.small, onClick = onAttach, enabled = !busy) { Text("Attach") }
                        OutlinedButton(shape = MaterialTheme.shapes.small, onClick = onReject, enabled = !busy) { Text("Refuse") }
                    }
                    ContextLinkState.ACCEPTED -> OutlinedButton(shape = MaterialTheme.shapes.small, onClick = onUnlink,
                    enabled = !busy,) { Text("Unlink excerpt") }
                    ContextLinkState.REJECTED -> Text(
                        text = "This pair cannot be proposed again; the refusal is kept.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private const val CONTEXT_CANDIDATE_PAGE_SIZE = 5

/**
 * The bounded list of saved note excerpts that can still be attached to this draft.
 *
 * The list never selects a match automatically, and every attachment is one explicit
 * user decision through the shared command boundary. An excerpt that is no longer current
 * is shown as such and cannot be attached, and the bank amount and ledger are never
 * changed by a link decision.
 */
@Composable
private fun AddNoteContextDialog(
    draftId: Long,
    queryService: WorkflowQueryService,
    commandService: WorkflowCommandService,
    onDismiss: () -> Unit,
    onLinked: (String) -> Unit,
) {
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    var page by remember { mutableStateOf<QueryPage<UnrecordedNoteEvent>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var actionBusy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val writeInFlight = remember { AtomicBoolean(false) }

    LaunchedEffect(queryService, offset, reload) {
        loading = true
        error = null
        try {
            page = queryService.unrecordedNoteEvents(
                QueryPageRequest(limit = CONTEXT_CANDIDATE_PAGE_SIZE, offset = offset),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "Saved note excerpts could not be loaded. No link was changed."
        } finally {
            loading = false
        }
    }

    fun attach(event: UnrecordedNoteEvent) {
        if (!writeInFlight.compareAndSet(false, true)) return
        actionBusy = true
        message = null
        messageIsError = false
        scope.launch {
            try {
                val outcome = decideUserLink(
                    commandService = commandService,
                    eventId = event.eventId,
                    draftId = draftId,
                    attach = true,
                    overrideNote = "User explicitly attached note context in Review.",
                )
                val applied = outcome.isAppliedOrNoChange()
                messageIsError = !applied
                message = if (applied) {
                    "Saved note #${event.captureId} attached as context. The bank amount " +
                        "and ledger are unchanged."
                } else {
                    outcome.linkFailureMessage()
                }
                if (applied) {
                    onLinked(
                        "Saved note #${event.captureId} attached as context. The bank " +
                            "amount and ledger are unchanged.",
                    )
                }
                reload += 1
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                messageIsError = true
                message = "Attach failed. No financial amount was changed; reload and try again."
            } finally {
                actionBusy = false
                writeInFlight.set(false)
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!actionBusy) onDismiss() },
        title = { Text("Add note context to draft #$draftId") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Only saved notes whose reading is complete and not already " +
                        "linked are offered. The list is bounded and never selects a match " +
                        "automatically. Attaching an excerpt never changes the bank amount, " +
                        "the review fields, or the ledger.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (loading && page == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Loading saved note excerpts…", style = MaterialTheme.typography.bodyMedium)
                }
                error?.let { failure ->
                    Text(failure, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { reload += 1 }) { Text("Retry") }
                }
                val current = page
                if (!loading && error == null && current?.items.isNullOrEmpty() && offset == 0) {
                    Text(
                        text = "No saved note excerpt is available to attach. Save a note in " +
                            "Chat first; a complete reading appears here.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                current?.items.orEmpty().forEach { event ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "Saved note #${event.captureId} · event #${event.eventId}",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = event.excerpt?.let { "Excerpt: “$it”" }
                                    ?: "The source text is no longer current; this excerpt " +
                                    "cannot be attached.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            event.normalizedText?.let { reading ->
                                Text("Reading: $reading", style = MaterialTheme.typography.bodySmall)
                            }
                            val facts = buildList {
                                event.amountVnd?.let { add(VndMoney.formatAmount(it)) }
                                event.anchorDate?.let { add(it) }
                                event.merchant?.takeIf(String::isNotBlank)?.let { add(it) }
                            }
                            if (facts.isNotEmpty()) {
                                Text(
                                    facts.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Button(shape = MaterialTheme.shapes.small, onClick = { attach(event) },
                            enabled = !actionBusy && event.excerpt != null,
                            modifier = Modifier.fillMaxWidth(),) { Text("Attach as context") }
                        }
                    }
                }
                if (current != null && (current.hasMore || offset > 0)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(
                            onClick = {
                                offset = (offset - CONTEXT_CANDIDATE_PAGE_SIZE).coerceAtLeast(0)
                            },
                            enabled = offset > 0 && !loading && !actionBusy,
                        ) { Text("Previous excerpts") }
                        Text(
                            text = "${offset + 1}–${offset + current.items.size}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(
                            onClick = { offset += current.items.size },
                            enabled = current.hasMore && current.items.isNotEmpty() &&
                                !loading && !actionBusy,
                        ) { Text("Next excerpts") }
                    }
                }
                if (actionBusy) {
                    Text("Saving the explicit link decision…", style = MaterialTheme.typography.bodyMedium)
                }
                message?.let { text ->
                    Text(
                        text = text,
                        color = if (messageIsError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !actionBusy) { Text("Close") }
        },
    )
}



@Composable
private fun DuplicateWarning(draftId: Long, status: String, onOpen: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Possible duplicate — this does not prove the transactions are the same. Neither candidate was discarded.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onOpen) {
                Text("Open draft #$draftId — $status")
            }
        }
    }
}

@Composable
private fun CategoryChoice(
    name: String,
    categoryId: Long? = null,
    archived: Boolean = false,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        CategorySwatch(
            categoryId = categoryId,
            archived = archived,
            directory = true,
        )
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        )
        if (archived) {
            ArchivedCategoryBadge()
        }
    }
}

@Composable
private fun sourceReason(label: String, state: IntakeFieldState, reason: String?) {
    if (state == IntakeFieldState.PRESENT) return
    val explanation = when (state) {
        IntakeFieldState.PRESENT -> return
        IntakeFieldState.MISSING -> "not stated"
        IntakeFieldState.UNRESOLVED -> when (reason) {
            "REDACTED" -> "masked by the bank"
            "AMBIGUOUS" -> "conflicting values were stated"
            "UNREADABLE" -> "stated but not readable"
            else -> "stated but unresolved"
        }
    }
    Text(
        "$label: $explanation. Correct it before confirmation when required.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

private fun manualAmountError(text: String): String? = try {
    VndMoney.parseManualAmount(text)
    null
} catch (rejection: LedgerValidationException) {
    rejection.message
}

private fun correctionMessage(fields: Set<BankDraftReviewField>): String = buildList {
    if (BankDraftReviewField.AMOUNT in fields) add("amount")
    if (BankDraftReviewField.DATE in fields) add("date")
    if (BankDraftReviewField.TIME in fields) add("time")
    if (BankDraftReviewField.TYPE in fields) add("type")
    if (BankDraftReviewField.CATEGORY in fields) add("category")
}.joinToString(prefix = "Resolve before confirmation: ", separator = ", ").plus(".")

private fun bankLabel(bank: String): String = when (bank) {
    "VIETINBANK" -> "VietinBank"
    else -> "Unsupported bank"
}

private fun statusLabel(draft: PendingBankDraftEntity): String = when (draft.reviewStatus) {
    IntakeReviewStatus.PENDING -> "Pending"
    IntakeReviewStatus.CONFIRMED -> "Confirmed"
    IntakeReviewStatus.DISMISSED -> "Dismissed"
}

private fun directionLabel(direction: IntakeDirection): String = when (direction) {
    IntakeDirection.DEBIT -> "Debit (money left the account)"
    IntakeDirection.CREDIT -> "Credit (money entered the account)"
    IntakeDirection.UNKNOWN -> "Unknown"
}

private fun statedMomentLabel(draft: PendingBankDraftEntity): String {
    val date = draft.statedDate
    val time = draft.statedTime
    return if (date != null && time != null) "$date $time (bank wall-clock time)" else {
        val reason = fieldReason(draft.momentState, draft.momentUnresolvedReason)
        "Not available — $reason"
    }
}

private fun amountLabel(draft: PendingBankDraftEntity): String =
    draft.amountVnd?.let(VndMoney::formatAmount)
        ?: "Not available — ${fieldReason(draft.amountState, draft.amountUnresolvedReason)}"

private fun memoLabel(draft: PendingBankDraftEntity): String = when {
    draft.memoState == IntakeFieldState.PRESENT && draft.memo != null -> draft.memo
    else -> "Unresolved — ${fieldReason(draft.memoState, draft.memoUnresolvedReason)}"
}

private fun fieldReason(state: IntakeFieldState, reason: String?): String = when (state) {
    IntakeFieldState.PRESENT -> "stated"
    IntakeFieldState.MISSING -> "not stated"
    IntakeFieldState.UNRESOLVED -> when (reason) {
        "REDACTED" -> "masked by the bank"
        "AMBIGUOUS" -> "conflicting values"
        "UNREADABLE" -> "unreadable value"
        else -> "unresolved value"
    }
}
