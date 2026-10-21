package io.github.triet4p.aegisf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
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
import io.github.triet4p.aegisf.data.BankDraftReviewEdits
import io.github.triet4p.aegisf.data.BankDraftReviewField
import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.ClassificationRequest
import io.github.triet4p.aegisf.data.ConfirmDraftRequest
import io.github.triet4p.aegisf.data.DismissDraftRequest
import io.github.triet4p.aegisf.data.DraftConfirmationPreview
import io.github.triet4p.aegisf.data.IntakeDirection
import io.github.triet4p.aegisf.data.IntakeFieldState
import io.github.triet4p.aegisf.data.IntakeParseStatus
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.CategoryQuery
import io.github.triet4p.aegisf.data.CategoryState
import io.github.triet4p.aegisf.data.DraftCandidateQuery
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
import io.github.triet4p.aegisf.data.PredictionSource
import io.github.triet4p.aegisf.data.TransactionType
import io.github.triet4p.aegisf.data.VndMoney
import io.github.triet4p.aegisf.data.laya.LayaCandidatePlanner
import io.github.triet4p.aegisf.data.reviewClassificationRequestKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (isNotificationAccessGranted(context)) {
            BankNotificationListener.requestActiveReconciliation(context)
        }
    }
    // The inbox lists the drafts that are still waiting for a decision. The read is bounded
    // and states its own scope, and the screen pages through the drafts of that read, so
    // every waiting draft is reachable and not only the first page of them.
    var draftPageIndex by rememberSaveable { mutableIntStateOf(0) }
    val draftQuery = remember(draftPageIndex) {
        DraftCandidateQuery(
            reviewStatus = IntakeReviewStatus.PENDING,
            bounds = QueryPageRequest(LIST_PAGE_SIZE, offset = draftPageIndex * LIST_PAGE_SIZE),
        )
    }
    val draftsFlow = remember(queryService, draftQuery) {
        queryService.observeDraftCandidates(draftQuery)
    }
    val draftPage by draftsFlow.collectAsState(initial = null)
    val drafts = draftPage?.items.orEmpty()
    // How many drafts are waiting in all: the count describes the whole inbox, so the
    // inbox can say so whichever page it is showing.
    val waitingCount by remember(queryService) {
        queryService.observeDraftCandidateCount(IntakeReviewStatus.PENDING)
    }.collectAsState(initial = null)
    // A page that lost its drafts steps back to the nearest page that still holds some,
    // so the inbox never shows an empty page in the middle of the list.
    ClampPageToRows(
        pageIndex = draftPageIndex,
        hasRows = draftPage?.items?.isNotEmpty(),
        onClamp = { draftPageIndex = it },
    )
    // The drafts that share their evidence with the listed ones: a possible duplicate is
    // kept and shown beside its partner, and the partner may already have been reviewed.
    val relatedFlow = remember(queryService, drafts) {
        queryService.observeRelatedDraftCandidates(drafts.map { it.id })
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

    BackHandler(enabled = draftId == null) { onNavigateUp() }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(if (draftId == null) "Review bank drafts" else "Draft #$draftId") },
                navigationIcon = {
                    TextButton(onClick = onNavigateUp) { Text("Back") }
                },
            )
        },
    ) { innerPadding ->
        val openedDraft = selectedDraft
        if (draftId == null) {
            InboxContent(
                drafts = drafts,
                relatedDrafts = relatedDrafts,
                waitingCount = waitingCount,
                pageIndex = draftPageIndex,
                hasMore = draftPage?.hasMore == true,
                contentModifier = Modifier.padding(innerPadding),
                onSelectDraft = onSelectDraft,
                onPreviousPage = { draftPageIndex -= 1 },
                onNextPage = { draftPageIndex += 1 },
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
    drafts: List<PendingBankDraftEntity>,
    relatedDrafts: List<PendingBankDraftEntity>,
    waitingCount: Int?,
    pageIndex: Int,
    hasMore: Boolean,
    contentModifier: Modifier,
    onSelectDraft: (Long) -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
) {
    val pending = remember(drafts) {
        drafts.filter { it.reviewStatus == IntakeReviewStatus.PENDING }
    }
    Column(
        modifier = contentModifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
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
                pending.isEmpty() && !hasMore -> "No drafts waiting for review."
                hasMore -> "${pending.size} waiting on this page — more drafts are stored."
                else -> "${pending.size} waiting"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        // The drafts after this page are one page further on in the same read, so every
        // waiting draft is reachable and the inbox says which page it is showing. The
        // controls sit above the drafts, so they are reachable without walking past a
        // whole page of them first.
        PageControls(
            pageIndex = pageIndex,
            hasMore = hasMore,
            onPrevious = onPreviousPage,
            onNext = onNextPage,
        )
        pending.forEach { draft ->
            DraftInboxCard(
                draft = draft,
                possibleDuplicate = draft.possibleDuplicateOfDraftId?.let { priorId ->
                    relatedDrafts.firstOrNull { it.id == priorId }
                },
                onSelectDraft = { onSelectDraft(draft.id) },
                onOpenDuplicate = { duplicateId -> onSelectDraft(duplicateId) },
            )
        }
        Text(
            text = "Confirmed and dismissed drafts are kept for replay protection and are not " +
                "listed as pending. A possible duplicate remains a candidate; it is never discarded automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DraftInboxCard(
    draft: PendingBankDraftEntity,
    possibleDuplicate: PendingBankDraftEntity?,
    onSelectDraft: () -> Unit,
    onOpenDuplicate: (Long) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelectDraft),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
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
    // Optional English hint for the suggestion gate only. Starts empty on every draft,
    // is never prepopulated from the bank memo, and never becomes the saved description.
    var suggestionNote by rememberSaveable(draft.id) { mutableStateOf("") }
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
    // The confirmation the user is about to approve: the exact fields it would write and
    // the binding that ties them to the state they were read from.
    var pendingConfirmation by remember { mutableStateOf<DraftConfirmationPreview.Ready?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmingDismiss by rememberSaveable(draft.id) { mutableStateOf(false) }
    var feedback by rememberSaveable(draft.id) { mutableStateOf<String?>(null) }
    val selectedType = selectedTypeName?.let { TransactionType.valueOf(it) }
    val active = draft.reviewStatus == IntakeReviewStatus.PENDING &&
        draft.confirmedTransactionId == null
    val scope = rememberCoroutineScope()
    val writeInFlight = remember(draft.id) { AtomicBoolean(false) }
    // Review-only suggestion identity: one draft's memo text plus the current category
    // snapshot. Any change invalidates the pending result; nothing here writes a record
    // or changes the category field itself. The memo and the optional note stay strictly
    // separate: the note is evaluated alone and never concatenated with the bank memo.
    val draftFormIdentity = remember(draft.id) { Any() }
    val suggestionMachine = remember(draftFormIdentity) { ManualCategorySuggestionMachine() }
    var suggestionRevision by remember(draftFormIdentity) { mutableStateOf(0) }
    val suggestionIntent = remember(draftFormIdentity, memoText, suggestionNote) {
        val effective = draftSuggestionInput(draft, memoText, suggestionNote)
        ManualSuggestionIntent(draftFormIdentity, effective.orEmpty(), TransactionType.EXPENSE)
    }
    val latestSuggestionIntent by rememberUpdatedState(suggestionIntent)
    // The whole catalog, live and read in bounded chunks: the model's candidate ranking
    // needs every active category, and an archive or a rename of any of them has to
    // invalidate a suggestion that was made against the catalog.
    val catalog: List<CategoryEntity>? by remember(queryService) {
        queryService.observeCategoryCatalog()
    }.collectAsState(initial = null)
    val latestObservedCategories by rememberUpdatedState(catalog)
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
    LaunchedEffect(
        commandService,
        draftFormIdentity,
        memoText,
        suggestionNote,
        catalog,
        suggestionRevision,
    ) {
        if (!active) {
            suggestionMachine.reset()
            return@LaunchedEffect
        }
        val trimmed = memoText.trim()
        val noteTrimmed = suggestionNote.trim()
        val effectiveInput = draftSuggestionInput(draft, memoText, suggestionNote)
        if (effectiveInput == null) {
            // A present but safety-blocked note yields null on purpose rather than
            // falling back to the memo: a code/OTP in the note field must not silently
            // reuse the bank text. Only a truly empty note defers to the memo state.
            if (noteTrimmed.isEmpty() && trimmed.isEmpty()) {
                suggestionMachine.waitForDescription()
            } else {
                suggestionMachine.ignore(latestSuggestionIntent)
            }
            return@LaunchedEffect
        }
        val requestCategories = catalog ?: return@LaunchedEffect
        if (requestCategories.any { it.id <= 0L || it.name.trim().isEmpty() }) {
            suggestionMachine.ignore(latestSuggestionIntent)
            return@LaunchedEffect
        }
        if (requestCategories.none { !it.archived }) {
            suggestionMachine.noActiveCategories()
            return@LaunchedEffect
        }
        val description = checkNotNull(effectiveInput)
        val token = suggestionMachine.begin(
            ManualSuggestionRequest(suggestionIntent, requestCategories),
        ) ?: return@LaunchedEffect
        // The classification is asked for through the application's one command boundary.
        // The boundary decides whether the model is asked at all, composes the effective
        // context of THIS draft, and stores the prediction against the state it was made
        // in. The screen only shows what came back.
        val outcome = try {
            commandService.requestClassification(
                scope = WorkflowCallerScope.User,
                request = ClassificationRequest(
                    idempotencyKey = reviewClassificationRequestKey(
                        draftId = draft.id,
                        formIdentity = System.identityHashCode(draftFormIdentity),
                        revision = suggestionRevision,
                        description = description,
                        categories = requestCategories,
                    ),
                    draftId = draft.id,
                    description = description,
                    expectedReviewRevision = draft.reviewRevision,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            suggestionMachine.unavailable(
                token = token,
                currentIntent = latestSuggestionIntent,
                currentCategories = queryService.categoryCatalog(),
                observedCategories = latestObservedCategories,
                reason = UnavailableReason.FAILED,
            )
            return@LaunchedEffect
        }
        val currentCategories = queryService.categoryCatalog()
        val observedCategories = latestObservedCategories
        val change = when (outcome) {
            is WorkflowCommandOutcome.Applied -> outcome.change
            is WorkflowCommandOutcome.NoChange -> outcome.change
            else -> null
        }
        if (change !is WorkflowChange.Classified) {
            suggestionMachine.unavailable(
                token = token,
                currentIntent = latestSuggestionIntent,
                currentCategories = currentCategories,
                observedCategories = observedCategories,
                reason = when (outcome) {
                    is WorkflowCommandOutcome.Cancelled -> UnavailableReason.SUPERSEDED
                    else -> UnavailableReason.FAILED
                },
            )
            return@LaunchedEffect
        }
        if (change.source == PredictionSource.REMEMBERED_RULE) {
            // A saved exact-description rule answered; the model was never asked.
            val remembered = currentCategories.firstOrNull { it.id == change.suggestedCategoryId }
            if (remembered == null) {
                suggestionMachine.unavailable(
                    token = token,
                    currentIntent = latestSuggestionIntent,
                    currentCategories = currentCategories,
                    observedCategories = observedCategories,
                    reason = UnavailableReason.FAILED,
                )
                return@LaunchedEffect
            }
            suggestionMachine.completeRemembered(
                token = token,
                currentIntent = latestSuggestionIntent,
                currentCategories = currentCategories,
                observedCategories = observedCategories,
                category = remembered,
            )
            return@LaunchedEffect
        }
        val decision = change.decision
        if (decision == null) {
            // The request was already made under this key, so its stored prediction is the
            // answer and no new decision was produced to show.
            suggestionMachine.reset()
            return@LaunchedEffect
        }
        suggestionMachine.complete(
            token = token,
            currentIntent = latestSuggestionIntent,
            currentCategories = currentCategories,
            observedCategories = observedCategories,
            resolution = LayaCandidatePlanner.resolveCurrent(decision.decision, currentCategories),
        )
    }
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
                        pendingConfirmation = preview
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
                            idempotencyKey = "draft-confirm:${draft.id}:$expectedRevision",
                            draftId = draft.id,
                            expectedRevision = expectedRevision,
                            expectedBinding = pending.binding,
                            edits = edits,
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

    BackHandler { if (!busy) onNavigateUp() }

    Column(
        modifier = contentModifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
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
                    if (memoText != it) {
                        suggestionMachine.clearSuppression()
                    }
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
            // Optional suggestion-only hint: English works best because the pinned model
            // is English-first, but any language is tried. Kept in its own field so typed
            // text can never concatenate with the stored bank memo: the gate evaluates
            // this note alone, the keyboard autocomplete inside it cannot poison bank
            // text, and the note is never written to the draft or the confirmed
            // description. Only OTP/security-like text and reference codes stay manual.
            OutlinedTextField(
                value = suggestionNote,
                onValueChange = {
                    if (suggestionNote != it) {
                        suggestionMachine.clearSuppression()
                    }
                    suggestionNote = it
                    feedback = null
                },
                label = { Text("Suggestion note (optional, English works best)") },
                placeholder = { Text("For example Monthly rent") },
                singleLine = true,
                supportingText = {
                    Text("Hint only, any language is tried. It never changes the bank memo or the saved description.")
                },
                modifier = Modifier.fillMaxWidth(),
            )
            DraftSuggestionPanel(
                draft = draft,
                state = suggestionMachine.state,
                enabled = !busy,
                memoText = memoText,
                suggestionNote = suggestionNote,
                onUseSuggestion = {
                    suggestionMachine.acceptSuggestion()?.let { category ->
                        selectedCategoryId = category.id
                        categoryResolved = true
                        feedback = null
                    }
                },
                onUseRememberedRule = {
                    suggestionMachine.acceptRememberedRule()?.let { remembered ->
                        selectedCategoryId = remembered.id
                        categoryResolved = true
                        feedback = null
                    }
                },
                onIgnoreSuggestion = {
                    suggestionMachine.ignore(suggestionIntent)
                    suggestionRevision += 1
                },
                onChooseManually = {
                    suggestionMachine.chooseManually(suggestionIntent)
                    suggestionRevision += 1
                },
                onChooseDistributionOption = { categoryId ->
                    suggestionMachine.chooseDistributionOption(categoryId)?.let { category ->
                        selectedCategoryId = category.id
                        categoryResolved = true
                        feedback = null
                    }
                },
                onChooseNoCategory = {
                    if (suggestionMachine.chooseNoCategoryOption()) {
                        selectedCategoryId = null
                        categoryResolved = true
                        feedback = null
                    }
                },
            )
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
                    suggestionMachine.chooseManually(suggestionIntent)
                    suggestionRevision += 1
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
                        selected = true,
                    ) {
                        suggestionMachine.chooseManually(suggestionIntent)
                        suggestionRevision += 1
                        selectedCategoryId = chosen.id
                        categoryResolved = true
                        feedback = null
                    }
                }
                choiceCategories.forEach { category ->
                    CategoryChoice(
                        name = category.name,
                        selected = category.id == selectedCategoryId,
                    ) {
                        suggestionMachine.chooseManually(suggestionIntent)
                        suggestionRevision += 1
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
            Button(
                onClick = ::preview,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (busy) "Saving…" else "Confirm transaction")
            }
            OutlinedButton(
                onClick = ::saveCorrections,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save corrections")
            }
            OutlinedButton(
                onClick = { confirmingDismiss = true },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Dismiss draft")
            }
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
    if (pending != null && active) {
        AlertDialog(
            onDismissRequest = { if (!busy) pendingConfirmation = null },
            title = { Text("Confirm this record?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "These are the exact values that will be stored:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("Amount: ${VndMoney.formatAmount(pending.fields.amountVnd)}")
                    Text("Type: ${typeLabel(pending.fields.type)}")
                    Text(
                        "When: ${ManualDateTime.formatMoment(pending.fields.occurredAtEpochMillis)} " +
                            "device time",
                    )
                    Text(
                        "Description: " +
                            (pending.fields.description.ifEmpty { "(none)" }),
                    )
                    Text(
                        "Category: " + (
                            pending.fields.categoryId?.let { categoryId ->
                                catalog?.firstOrNull { it.id == categoryId }?.name ?: "unknown"
                            } ?: "no category"
                            ),
                    )
                    val risk = pending.duplicateRisk
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
                TextButton(onClick = ::confirm, enabled = !busy) {
                    Text(if (busy) "Confirming…" else "Confirm")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirmation = null }, enabled = !busy) {
                    Text("Back to review")
                }
            },
        )
    }
}

/**
 * Bank review keeps the suggestion header visible for every settled machine state.
 * Only the transient Idle (no request yet, effect still to run) renders nothing.
 * Inference runs for any non-blank note/memo except narrow OTP/security/reference-code
 * blocks, and never receives codes/OTP.
 */
internal fun isBankDraftSuggestionPanelVisible(state: ManualCategorySuggestionState): Boolean =
    state != ManualCategorySuggestionState.Idle

@Composable
private fun DraftSuggestionPanel(
    draft: PendingBankDraftEntity,
    state: ManualCategorySuggestionState,
    enabled: Boolean,
    memoText: String,
    suggestionNote: String,
    onUseSuggestion: () -> Unit,
    onUseRememberedRule: () -> Unit,
    onIgnoreSuggestion: () -> Unit,
    onChooseManually: () -> Unit,
    onChooseDistributionOption: (Long) -> Unit = {},
    onChooseNoCategory: () -> Unit = {},
) {
    if (!isBankDraftSuggestionPanelVisible(state)) return
    // The ordinary bank memo is usually opaque or coded, so the gate may yield null
    // and the machine rests in Ignored/Waiting. Hiding the panel there looks exactly
    // like a missing feature (S4.10-A2 user finding). Keep the header and a truthful
    // reason visible instead. Loading states below show indeterminate progress while
    // the lifecycle opens/verifies the model or runs inference.
    val effectiveInput = draftSuggestionInput(draft, memoText, suggestionNote)
    val noteBlank = suggestionNote.trim().isEmpty()
    val status = when (state) {
        ManualCategorySuggestionState.Idle -> return
        ManualCategorySuggestionState.WaitingForDescription ->
            "No description to check yet. Enter the description or add a suggestion " +
                "note (English works best; any language is tried) to request ranked " +
                "probabilities. The bank memo stays unchanged. " +
                "Manual choice and confirmation stay available."
        ManualCategorySuggestionState.ManuallySelected ->
            "Your category choice stays selected. The suggestion will not replace it " +
                "for this input. Edit the description or note to request again."
        ManualCategorySuggestionState.Ignored ->
            if (effectiveInput == null) {
                if (!noteBlank) {
                    "That note was held back for safety (possible code or security " +
                        "text), so nothing was sent. The bank memo stays unchanged. " +
                        "Edit the note, or choose a category manually. Nothing " +
                        "is confirmed or saved."
                } else {
                    "That text was held back for safety (possible code or security " +
                        "text), so nothing was sent. The memo and saved description stay " +
                        "unchanged. Nothing is confirmed or saved."
                }
            } else {
                "Suggestion ignored. The selected category and other fields are unchanged. " +
                    "Edit the description or note to request again."
            }
        ManualCategorySuggestionState.LoadingCategories ->
            "Loading current categories. Choosing a category or confirming stays available."
        ManualCategorySuggestionState.Loading ->
            "Working: checking saved rules, then running the local model if needed. " +
                "Choosing a category or confirming stays available."
        ManualCategorySuggestionState.NoActiveCategories ->
            "No active categories are available. Choose No category explicitly to confirm."
        ManualCategorySuggestionState.NotApplicableToKind ->
            "Suggestions follow the description text; manual choice stays available."
        is ManualCategorySuggestionState.Suggested ->
            if (state.distribution != null) {
                "The model ranked your current categories below from the note or memo text " +
                    "(English works best; any language is tried). Pick one to fill the " +
                    "category field, or leave the field unchanged. Nothing is confirmed or saved."
            } else {
                "The model suggests a current category. It will not change the selected " +
                    "category unless you accept it."
            }
        is ManualCategorySuggestionState.Remembered ->
            if (state.accepted) {
                "The saved exact-description rule was accepted. The category field changed " +
                    "only at your request; nothing has been saved. This is a saved rule, not " +
                    "a model score."
            } else {
                "A saved exact-description rule matches. It does not change the category " +
                    "field until you accept it. This is a saved rule, not a model score."
            }
        ManualCategorySuggestionState.RuleRemoved ->
            "The saved rule was removed."
        is ManualCategorySuggestionState.Accepted ->
            "The suggestion is selected at your request. Review it before confirming; " +
                "nothing has been saved."
        is ManualCategorySuggestionState.NoFit ->
            if (state.reason == LayaCandidatePlanner.ManualReason.EFFECTIVE_INPUT_OVER_BUDGET) {
                effectiveInputOverBudgetMessage()
            } else if (state.distribution != null) {
                "Unknown scored highest, so nothing was preselected. The ranked " +
                    "categories below stay selectable. Nothing is confirmed or saved."
            } else {
                "No category suggestion is available for this description. Choose one manually " +
                    "or choose No category explicitly."
            }
        is ManualCategorySuggestionState.Stale ->
            "Category data changed while this suggestion was checked. It was not applied; " +
                "choose from current categories instead."
        is ManualCategorySuggestionState.Unavailable ->
            "A suggestion is unavailable right now. Manual choice and confirmation still work."
        ManualCategorySuggestionState.NoCategory ->
            "Unknown / no fit is selected: no category will be filed unless you pick " +
                "one. Nothing is confirmed or saved."
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Category suggestion",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val busy = state == ManualCategorySuggestionState.LoadingCategories ||
            state == ManualCategorySuggestionState.Loading
        if (busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        val uncertainty = when (state) {
            is ManualCategorySuggestionState.Suggested -> state.uncertainty
            is ManualCategorySuggestionState.Accepted -> state.uncertainty
            is ManualCategorySuggestionState.NoFit -> state.uncertainty
            else -> null
        }
        if (uncertainty != null && uncertainty.omittedCategoryCount > 0) {
            Text(
                text = "Offered ${uncertainty.offeredCategoryCount} of " +
                    "${uncertainty.activeCategoryCount} active categories; " +
                    "${uncertainty.omittedCategoryCount} were omitted, so a better fit " +
                    "may be missing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (state) {
            is ManualCategorySuggestionState.Suggested -> {
                val distribution = state.distribution
                if (distribution != null) {
                    ManualDistributionList(
                        distribution = distribution,
                        enabled = enabled,
                        onChooseDistributionOption = onChooseDistributionOption,
                        onChooseNoCategory = onChooseNoCategory,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onIgnoreSuggestion, enabled = enabled) {
                            Text("Ignore")
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onUseSuggestion, enabled = enabled) {
                            Text("Use suggestion")
                        }
                        OutlinedButton(onClick = onIgnoreSuggestion, enabled = enabled) {
                            Text("Ignore")
                        }
                    }
                }
            }
            is ManualCategorySuggestionState.NoFit -> {
                val distribution = state.distribution
                if (distribution != null) {
                    ManualDistributionList(
                        distribution = distribution,
                        enabled = enabled,
                        onChooseDistributionOption = onChooseDistributionOption,
                        onChooseNoCategory = onChooseNoCategory,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onIgnoreSuggestion, enabled = enabled) {
                            Text("Ignore")
                        }
                    }
                }
            }
            is ManualCategorySuggestionState.Remembered -> {
                if (!state.accepted) {
                    Button(
                        onClick = onUseRememberedRule,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Use remembered category")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onIgnoreSuggestion, enabled = enabled) {
                        Text("Ignore")
                    }
                }
            }
            is ManualCategorySuggestionState.Accepted -> {
                OutlinedButton(onClick = onChooseManually, enabled = enabled) {
                    Text("Keep manual choice")
                }
            }
            is ManualCategorySuggestionState.NoCategory -> {
                OutlinedButton(onClick = onChooseManually, enabled = enabled) {
                    Text("Keep manual choice")
                }
            }
            else -> Unit
        }
    }
}

@Composable
private fun DuplicateWarning(draftId: Long, status: String, onOpen: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer,
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
private fun CategoryChoice(name: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(name, style = MaterialTheme.typography.bodyLarge)
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
