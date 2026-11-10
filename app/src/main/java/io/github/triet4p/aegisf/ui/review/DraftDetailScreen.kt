package io.github.triet4p.aegisf.ui.review

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.triet4p.aegisf.data.AegisLanguage
import io.github.triet4p.aegisf.data.BankDraftReviewEdits
import io.github.triet4p.aegisf.data.BankDraftReviewField
import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.CategoryQuery
import io.github.triet4p.aegisf.data.CategoryState
import io.github.triet4p.aegisf.data.ConfirmDraftRequest
import io.github.triet4p.aegisf.data.ContextLinkState
import io.github.triet4p.aegisf.data.DismissDraftRequest
import io.github.triet4p.aegisf.data.DraftConfirmationPreview
import io.github.triet4p.aegisf.data.DraftContextLink
import io.github.triet4p.aegisf.data.IntakeDirection
import io.github.triet4p.aegisf.data.IntakeFieldState
import io.github.triet4p.aegisf.data.IntakeParseStatus
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.LIST_PAGE_SIZE
import io.github.triet4p.aegisf.data.LedgerValidationException
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.data.PreviewDraftConfirmationRequest
import io.github.triet4p.aegisf.data.QueryPage
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.SaveDraftReviewRequest
import io.github.triet4p.aegisf.data.TransactionType
import io.github.triet4p.aegisf.data.UnlinkLinkRequest
import io.github.triet4p.aegisf.data.VndMoney
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowChange
import io.github.triet4p.aegisf.data.WorkflowCommandOutcome
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowDenialReason
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.ui.ArchivedCategoryBadge
import io.github.triet4p.aegisf.ui.CategorySwatch
import io.github.triet4p.aegisf.ui.ClampPageToRows
import io.github.triet4p.aegisf.ui.ManualDateTime
import io.github.triet4p.aegisf.ui.components.AegisDiscardChangesDialog
import io.github.triet4p.aegisf.ui.components.AegisGlyph
import io.github.triet4p.aegisf.ui.components.AegisIcon
import io.github.triet4p.aegisf.ui.decideUserLink
import io.github.triet4p.aegisf.ui.isAppliedOrNoChange
import io.github.triet4p.aegisf.ui.linkFailureMessage
import io.github.triet4p.aegisf.ui.text.AegisStrings
import io.github.triet4p.aegisf.ui.text.LocalAegisStrings
import io.github.triet4p.aegisf.ui.theme.LocalAegisSemanticColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** What reading one draft currently shows. */
private sealed interface DraftLoad {
    data object Loading : DraftLoad
    data class Ready(val draft: PendingBankDraftEntity) : DraftLoad
    data object Missing : DraftLoad
    data object Error : DraftLoad
}

/**
 * One draft's focused review: its bank evidence, one category, its edits, the note context, and
 * the single explicit financial confirmation.
 *
 * Nothing on this screen writes the ledger by itself. Corrections and link decisions touch only
 * the draft and its link rows; Confirm first asks for the exact fields and binding it will write,
 * then refuses safely when the draft or the form moved after that preview. Dismiss records no
 * transaction. The bottom navigation is hidden by the shell because this is a child of Review.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftDetailScreen(
    queryService: WorkflowQueryService,
    commandService: WorkflowCommandService,
    draftId: Long,
    onOpenDraft: (Long) -> Unit,
    onDiscussDraft: (Long) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = reviewStrings(LocalAegisStrings.current.language)
    var retryToken by remember { mutableIntStateOf(0) }
    val loadFlow = remember(queryService, draftId, retryToken) {
        // Creating the Room flow can itself fail (a closed or unreadable database), so it is
        // folded into the same read state instead of escaping composition.
        runCatching {
            queryService.observeDraftCandidate(draftId)
                .map { draft -> if (draft == null) DraftLoad.Missing else DraftLoad.Ready(draft) }
                .onStart { emit(DraftLoad.Loading) }
                .catch { emit(DraftLoad.Error) }
        }.getOrElse { flowOf<DraftLoad>(DraftLoad.Error) }
    }
    val load by loadFlow.collectAsState(initial = DraftLoad.Loading)

    when (val current = load) {
        DraftLoad.Loading -> DetailStateScaffold(
            title = strings.detailTitle,
            strings = strings,
            onNavigateUp = onNavigateUp,
            modifier = modifier,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.heightIn(max = 24.dp))
                Text(strings.loading, style = MaterialTheme.typography.bodyLarge)
            }
        }

        DraftLoad.Missing -> DetailStateScaffold(
            title = strings.detailTitle,
            strings = strings,
            onNavigateUp = onNavigateUp,
            modifier = modifier,
        ) {
            Text(strings.missingTitle, style = MaterialTheme.typography.titleMedium)
            Text(
                text = strings.missingBody,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onNavigateUp) { Text(strings.backToInbox) }
        }

        DraftLoad.Error -> DetailStateScaffold(
            title = strings.detailTitle,
            strings = strings,
            onNavigateUp = onNavigateUp,
            modifier = modifier,
        ) {
            Text(
                text = strings.readErrorTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = strings.readErrorBody,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { retryToken += 1 }) { Text(strings.retry) }
                TextButton(onClick = onNavigateUp) { Text(strings.backToInbox) }
            }
        }

        is DraftLoad.Ready -> DraftReviewContent(
            draft = current.draft,
            queryService = queryService,
            commandService = commandService,
            strings = strings,
            onOpenDraft = onOpenDraft,
            onDiscussDraft = onDiscussDraft,
            onNavigateUp = onNavigateUp,
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailStateScaffold(
    title: String,
    strings: ReviewStrings,
    onNavigateUp: () -> Unit,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onNavigateUp)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateUp,
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DraftReviewContent(
    draft: PendingBankDraftEntity,
    queryService: WorkflowQueryService,
    commandService: WorkflowCommandService,
    strings: ReviewStrings,
    onOpenDraft: (Long) -> Unit,
    onDiscussDraft: (Long) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier,
) {
    val baseStrings = LocalAegisStrings.current
    // The stored review fields the form opened with. Comparing the form to this is what makes
    // Back protect half-written corrections without ever warning about an untouched draft.
    val storedEdits = remember(draft) { editsOf(draft) }
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
    var expectedRevision by rememberSaveable(draft.id) { mutableIntStateOf(draft.reviewRevision) }
    var validationRequested by rememberSaveable(draft.id) { mutableStateOf(false) }
    var pendingConfirmation by remember { mutableStateOf<PendingConfirmation?>(null) }
    var busy by remember { mutableStateOf(false) }
    var previewing by remember { mutableStateOf(false) }
    var confirmingDismiss by rememberSaveable(draft.id) { mutableStateOf(false) }
    var feedback by rememberSaveable(draft.id) { mutableStateOf<String?>(null) }
    var feedbackIsError by rememberSaveable(draft.id) { mutableStateOf(false) }
    var showDiscard by rememberSaveable(draft.id) { mutableStateOf(false) }
    var categorySheetOpen by rememberSaveable(draft.id) { mutableStateOf(false) }
    var addingContext by rememberSaveable(draft.id) { mutableStateOf(false) }
    var contextReload by remember(draft.id) { mutableIntStateOf(0) }
    var contextPageIndex by rememberSaveable(draft.id) { mutableIntStateOf(0) }
    var contextPage by remember(draft.id) { mutableStateOf<QueryPage<DraftContextLink>?>(null) }
    var contextLoading by remember(draft.id) { mutableStateOf(true) }
    var contextError by remember(draft.id) { mutableStateOf<String?>(null) }
    var contextFeedback by rememberSaveable(draft.id) { mutableStateOf<String?>(null) }
    var contextFeedbackIsError by rememberSaveable(draft.id) { mutableStateOf(false) }
    var contextActionBusy by remember { mutableStateOf(false) }
    var categoryPageIndex by rememberSaveable(draft.id) { mutableIntStateOf(0) }

    val selectedType = selectedTypeName?.let { runCatching { TransactionType.valueOf(it) }.getOrNull() }
    val active = draft.reviewStatus == IntakeReviewStatus.PENDING &&
        draft.confirmedTransactionId == null
    val scope = rememberCoroutineScope()
    val writeInFlight = remember(draft.id) { AtomicBoolean(false) }
    val contextWriteInFlight = remember(draft.id) { AtomicBoolean(false) }

    val catalog: List<CategoryEntity>? by remember(queryService) {
        queryService.observeCategoryCatalog()
    }.collectAsState(initial = null)
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
    val chosenId = selectedCategoryId
    val chosenCategory: CategoryEntity? by remember(queryService, chosenId) {
        if (chosenId == null) flowOf(null) else queryService.observeCategory(chosenId)
    }.collectAsState(initial = null)
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
            contextError = strings.noteContextError
        } finally {
            contextLoading = false
        }
    }
    ClampPageToRows(
        pageIndex = contextPageIndex,
        hasRows = contextPage?.items?.isNotEmpty(),
        onClamp = { contextPageIndex = it },
    )

    val relatedFlow = remember(queryService, draft.id) {
        queryService.observeRelatedDraftCandidates(listOf(draft.id))
    }
    val relatedDrafts by relatedFlow.collectAsState(initial = emptyList())
    val possiblePrior = draft.possibleDuplicateOfDraftId?.let { id ->
        relatedDrafts.firstOrNull { it.id == id }
    }
    val laterCandidates = relatedDrafts.filter { it.possibleDuplicateOfDraftId == draft.id }

    val edits = BankDraftReviewEdits(
        amountText = amountText,
        dateText = dateText,
        timeText = timeText,
        memoText = memoText,
        type = selectedType,
        categoryId = selectedCategoryId,
        categoryResolved = categoryResolved,
    )
    val dirty = active && edits != storedEdits
    val amountError = if (validationRequested && parseAmount(amountText) == null) {
        strings.amountError
    } else {
        null
    }
    val dateError = if (validationRequested && ManualDateTime.dateError(dateText) != null) {
        strings.dateError
    } else {
        null
    }
    val timeError = if (validationRequested && ManualDateTime.timeError(timeText) != null) {
        strings.timeError
    } else {
        null
    }
    val typeError = validationRequested && selectedType == null
    val categoryError = validationRequested &&
        selectedType != TransactionType.TRANSFER && !categoryResolved

    fun report(message: String, isError: Boolean) {
        feedback = message
        feedbackIsError = isError
    }

    fun handleOutcome(outcome: WorkflowCommandOutcome, action: String) {
        when (outcome) {
            is WorkflowCommandOutcome.Applied -> {
                val change = outcome.change
                if (change is WorkflowChange.DraftReviewSaved) {
                    expectedRevision = change.revision
                    report(strings.correctionsSaved, isError = false)
                } else {
                    onNavigateUp()
                }
            }

            is WorkflowCommandOutcome.NoChange -> {
                val change = outcome.change
                if (change is WorkflowChange.DraftReviewSaved) {
                    expectedRevision = change.revision
                    report(strings.correctionsSaved, isError = false)
                } else {
                    report(strings.alreadyDecided, isError = true)
                }
            }

            is WorkflowCommandOutcome.NeedsClarification -> {
                validationRequested = true
                report(
                    strings.correction(
                        BankDraftReviewField.entries
                            .filter { it.name == outcome.field }
                            .toSet(),
                    ),
                    isError = true,
                )
            }

            is WorkflowCommandOutcome.Denied -> report(
                if (outcome.reason == WorkflowDenialReason.CATEGORY_UNAVAILABLE) {
                    strings.categoryUnavailable
                } else {
                    strings.requestFailed
                },
                isError = true,
            )

            is WorkflowCommandOutcome.Conflict -> report(
                strings.correctionsConflict.format(outcome.reason),
                isError = true,
            )

            WorkflowCommandOutcome.NotFound -> report(strings.draftUnavailable, isError = true)
            is WorkflowCommandOutcome.Failed -> report(outcome.reason, isError = true)
            WorkflowCommandOutcome.Cancelled -> report(strings.requestCancelled, isError = true)
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
                        request = PreviewDraftConfirmationRequest(draftId = draft.id, edits = edits),
                    )
                ) {
                    is DraftConfirmationPreview.Ready -> {
                        expectedRevision = preview.expectedRevision
                        pendingConfirmation = PendingConfirmation(preview, edits)
                    }

                    is DraftConfirmationPreview.NeedsCorrection -> {
                        validationRequested = true
                        report(strings.correction(preview.fields), isError = true)
                    }

                    is DraftConfirmationPreview.Conflict ->
                        report(strings.correctionsConflict.format(preview.reason), isError = true)

                    is DraftConfirmationPreview.Denied -> report(
                        strings.categoryUnavailable,
                        isError = true,
                    )

                    DraftConfirmationPreview.NotFound ->
                        report(strings.draftUnavailable, isError = true)
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

    fun requestLeave() {
        if (busy) return
        if (dirty) showDiscard = true else onNavigateUp()
    }

    fun runContextAction(successMessage: String, action: suspend () -> WorkflowCommandOutcome) {
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
                contextFeedback = strings.linkActionFailed
                contextFeedbackIsError = true
            } finally {
                contextActionBusy = false
                contextWriteInFlight.set(false)
            }
        }
    }

    fun attachContext(link: DraftContextLink) {
        runContextAction(strings.noteActionAttached) {
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
        runContextAction(strings.noteActionRejected) {
            decideUserLink(
                commandService = commandService,
                eventId = link.eventId,
                draftId = link.draftId,
                attach = false,
                overrideNote = "User explicitly refused note context in Review.",
            )
        }
    }

    fun unlinkContext(link: DraftContextLink) {
        runContextAction(strings.noteActionUnlinked) {
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

    BackHandler(enabled = true) { requestLeave() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = strings.detailTitle,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = ::requestLeave,
                        modifier = Modifier.semantics { contentDescription = strings.back },
                    ) {
                        AegisIcon(
                            glyph = AegisGlyph.ARROW_BACK,
                            tint = MaterialTheme.colorScheme.onSurface,
                            size = 22.dp,
                        )
                    }
                },
                actions = {
                    if (active) {
                        TextButton(
                            onClick = { confirmingDismiss = true },
                            enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(
                                text = strings.dismiss,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (active) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Button(
                            onClick = ::preview,
                            enabled = !busy,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .testTag("review-confirm"),
                        ) {
                            Text(
                                text = when {
                                    previewing -> strings.checking
                                    busy -> strings.working
                                    else -> strings.confirmAction
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .then(if (active) Modifier else Modifier.navigationBarsPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            DraftReceiptCard(draft = draft, strings = strings)

            if (draft.parseStatus == IntakeParseStatus.UNSUPPORTED_FORMAT && active) {
                Text(
                    text = strings.unsupportedWarning,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            val sourceReason = strings.correctedBefore(
                strings.evidenceAmount,
                draft.amountState,
                draft.amountUnresolvedReason,
            )
            if (sourceReason.isNotEmpty() && active) {
                Text(
                    text = sourceReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            possiblePrior?.let { prior ->
                DuplicateWarningCard(
                    body = strings.duplicateLine,
                    actionLabel = strings.openDuplicate(
                        prior.id,
                        strings.status(prior.reviewStatus),
                    ),
                    onOpen = { onOpenDraft(prior.id) },
                )
            }
            laterCandidates.forEach { candidate ->
                DuplicateWarningCard(
                    body = strings.duplicateLine,
                    actionLabel = strings.openDuplicate(
                        candidate.id,
                        strings.status(candidate.reviewStatus),
                    ),
                    onOpen = { onOpenDraft(candidate.id) },
                )
            }

            if (active) {
                if (expectedRevision != draft.reviewRevision) {
                    Text(
                        text = strings.changedElsewhere(expectedRevision, draft.reviewRevision),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalAegisSemanticColors.current.onWarningContainer,
                    )
                }

                CategorySection(
                    draft = draft,
                    strings = strings,
                    selectedType = selectedType,
                    selectedCategoryId = selectedCategoryId,
                    categoryResolved = categoryResolved,
                    chosenCategory = chosenCategory,
                    onChange = { categorySheetOpen = true },
                )

                EditSection(
                    amountText = amountText,
                    onAmountChange = { amountText = it; feedback = null },
                    amountError = amountError,
                    dateText = dateText,
                    onDateChange = { dateText = it; feedback = null },
                    dateError = dateError,
                    timeText = timeText,
                    onTimeChange = { timeText = it; feedback = null },
                    timeError = timeError,
                    memoText = memoText,
                    onMemoChange = { memoText = it; feedback = null },
                    descriptionUnresolved = draft.memoState != IntakeFieldState.PRESENT &&
                        !draft.reviewMemoEdited,
                    selectedType = selectedType,
                    typeError = typeError,
                    onTypeChange = { type ->
                        selectedTypeName = type.name
                        if (type == TransactionType.TRANSFER) {
                            selectedCategoryId = null
                            categoryResolved = false
                        }
                        feedback = null
                    },
                    categoryError = categoryError,
                    baseStrings = baseStrings,
                    strings = strings,
                )

                OutlinedButton(
                    onClick = ::saveCorrections,
                    enabled = !busy,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("review-save"),
                ) {
                    Text(if (busy) strings.saving else strings.saveCorrections)
                }
            } else {
                Text(
                    text = when (draft.reviewStatus) {
                        IntakeReviewStatus.CONFIRMED -> strings.terminalConfirmed
                        IntakeReviewStatus.DISMISSED -> strings.terminalDismissed
                        IntakeReviewStatus.PENDING -> strings.terminalPending
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                draft.confirmedTransactionId?.let { transactionId ->
                    Text(
                        text = strings.linkedRecord(transactionId),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (feedback != null) {
                Text(
                    text = feedback.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (feedbackIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }

            EvidenceSection(draft = draft, strings = strings)

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
                strings = strings,
                onPrevious = { contextPageIndex -= 1 },
                onNext = { contextPageIndex += 1 },
                onRetry = { contextReload += 1 },
                onAddContext = { addingContext = true },
                onAttach = ::attachContext,
                onReject = ::rejectContext,
                onUnlink = ::unlinkContext,
            )

            if (active) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TextButton(
                    onClick = { onDiscussDraft(draft.id) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(strings.aiContext) }
                Text(
                    text = strings.aiContextHelp,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!active) {
                TextButton(onClick = onNavigateUp) { Text(strings.backToInbox) }
            }
        }
    }

    if (confirmingDismiss && active) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmingDismiss = false },
            title = { Text(strings.dismissTitle.format(draft.id)) },
            text = { Text(strings.dismissBody) },
            confirmButton = {
                TextButton(onClick = ::dismiss, enabled = !busy) {
                    Text(if (busy) strings.dismissing else strings.dismissConfirm)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDismiss = false }, enabled = !busy) {
                    Text(strings.keepReviewing)
                }
            },
        )
    }

    if (showDiscard) {
        AegisDiscardChangesDialog(
            onKeepEditing = { showDiscard = false },
            onDiscard = {
                showDiscard = false
                onNavigateUp()
            },
        )
    }

    val pending = pendingConfirmation
    if (pending != null) {
        val preview = pending.preview
        val staleReason = when {
            !active -> strings.previewStaleTerminal
            draft.reviewRevision != preview.expectedRevision -> strings.changedElsewhere(
                preview.expectedRevision,
                draft.reviewRevision,
            )

            pending.edits != edits -> strings.previewStaleFields
            else -> null
        }
        val previewCategory = preview.fields.categoryId?.let { categoryId ->
            catalog?.firstOrNull { it.id == categoryId }?.name
                ?: chosenCategory?.takeIf { it.id == categoryId }?.name
                ?: strings.previewUnknownCategory
        } ?: strings.previewNoCategory
        AlertDialog(
            onDismissRequest = { if (!busy) pendingConfirmation = null },
            title = {
                Text(
                    if (staleReason == null) strings.previewTitle else strings.previewStaleTitle,
                )
            },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (staleReason != null) {
                        Text(
                            text = staleReason,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = strings.previewStaleHelp,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = strings.previewStaleValues,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(strings.previewIntro, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        "${strings.previewAmount}: " +
                            VndMoney.formatAmount(preview.fields.amountVnd, " ₫"),
                    )
                    Text("${strings.previewType}: ${typeName(preview.fields.type, baseStrings)}")
                    Text(
                        "${strings.previewWhen}: " +
                            ManualDateTime.formatMoment(preview.fields.occurredAtEpochMillis) +
                            if (strings.language == AegisLanguage.ENGLISH) {
                                " device time"
                            } else {
                                " theo giờ thiết bị"
                            },
                    )
                    Text(
                        "${strings.previewDescription}: " +
                            preview.fields.description.ifEmpty {
                                if (strings.language == AegisLanguage.ENGLISH) "(none)" else "(không có)"
                            },
                    )
                    Text("${strings.previewCategory}: $previewCategory")
                    preview.duplicateRisk?.let { risk ->
                        Text(
                            text = strings.duplicateRisk(
                                risk,
                                ManualDateTime.formatMoment(risk.occurredAtEpochMillis),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        text = strings.previewEvidenceKept,
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
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("review-preview-confirm"),
                    ) {
                        Text(if (busy) strings.confirming else strings.confirm)
                    }
                } else {
                    TextButton(
                        onClick = ::preview,
                        enabled = !busy && active,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(if (previewing) strings.checking else strings.checkCurrentValues)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingConfirmation = null },
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(strings.backToReview) }
            },
        )
    }

    if (addingContext && active) {
        AddNoteContextDialog(
            draftId = draft.id,
            queryService = queryService,
            commandService = commandService,
            strings = strings,
            onDismiss = { addingContext = false },
            onLinked = { message ->
                contextFeedback = message
                contextFeedbackIsError = false
                contextReload += 1
            },
        )
    }

    if (categorySheetOpen && active) {
        ModalBottomSheet(
            onDismissRequest = { categorySheetOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 16.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("review-category-sheet"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = strings.categorySheetTitle,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = strings.categorySheetHelp,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (categoryPageIndex > 0 || categoryPage?.hasMore == true) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { categoryPageIndex -= 1 },
                            enabled = categoryPageIndex > 0,
                        ) { Text(strings.previous) }
                        Text(
                            strings.pageLabel(categoryPageIndex),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(
                            onClick = { categoryPageIndex += 1 },
                            enabled = categoryPage?.hasMore == true,
                        ) { Text(strings.next) }
                    }
                }
                CategoryChoiceRow(
                    name = strings.categoryNoCategory,
                    categoryId = null,
                    rowId = "none",
                    selected = categoryResolved && selectedCategoryId == null,
                    onSelect = {
                        selectedCategoryId = null
                        categoryResolved = true
                        categorySheetOpen = false
                        feedback = null
                    },
                )
                val chosen = chosenCategory
                if (chosen != null && choiceCategories.none { it.id == chosen.id }) {
                    CategoryChoiceRow(
                        name = chosen.name,
                        categoryId = chosen.id,
                        rowId = chosen.id.toString(),
                        archived = chosen.archived,
                        selected = chosen.id == selectedCategoryId,
                        onSelect = {
                            selectedCategoryId = chosen.id
                            categoryResolved = true
                            categorySheetOpen = false
                            feedback = null
                        },
                    )
                }
                choiceCategories.forEach { category ->
                    CategoryChoiceRow(
                        name = category.name,
                        categoryId = category.id,
                        rowId = category.id.toString(),
                        archived = category.archived,
                        selected = category.id == selectedCategoryId,
                        onSelect = {
                            selectedCategoryId = category.id
                            categoryResolved = true
                            categorySheetOpen = false
                            feedback = null
                        },
                    )
                }
                val knownCatalog = catalog
                if (knownCatalog != null && knownCatalog.none { !it.archived }) {
                    Text(
                        text = strings.categoryNoActive,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The exact review fields a draft currently stores, used to detect unsaved form edits. */
private fun editsOf(draft: PendingBankDraftEntity): BankDraftReviewEdits = BankDraftReviewEdits(
    amountText = draft.reviewAmountText ?: draft.amountVnd?.toString().orEmpty(),
    dateText = draft.reviewDateText ?: draft.statedDate.orEmpty(),
    timeText = draft.reviewTimeText ?: draft.statedTime.orEmpty(),
    memoText = if (draft.reviewMemoEdited) draft.reviewMemo.orEmpty() else draft.memo.orEmpty(),
    type = draft.reviewType,
    categoryId = draft.reviewCategoryId,
    categoryResolved = draft.reviewCategoryResolved,
)

private fun parseAmount(text: String): Long? = try {
    VndMoney.parseManualAmount(text)
} catch (_: LedgerValidationException) {
    null
}

private fun typeName(type: TransactionType, strings: AegisStrings): String = when (type) {
    TransactionType.EXPENSE -> strings.typeExpense
    TransactionType.INCOME -> strings.typeIncome
    TransactionType.TRANSFER -> strings.typeTransfer
}

/** One preview the user can approve: its exact fields and the form values they came from. */
private data class PendingConfirmation(
    val preview: DraftConfirmationPreview.Ready,
    val edits: BankDraftReviewEdits,
)

@Composable
private fun CategorySection(
    draft: PendingBankDraftEntity,
    strings: ReviewStrings,
    selectedType: TransactionType?,
    selectedCategoryId: Long?,
    categoryResolved: Boolean,
    chosenCategory: CategoryEntity?,
    onChange: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(strings.categorySection, style = MaterialTheme.typography.titleSmall)
        if (selectedType == TransactionType.TRANSFER) {
            Text(
                text = strings.categoryTransferNote,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (categoryResolved) {
                    CategorySwatch(
                        categoryId = draft.reviewCategoryId,
                        archived = chosenCategory?.archived == true,
                        directory = true,
                    )
                    Text(
                        text = chosenCategory?.name ?: strings.categoryNoCategory,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (chosenCategory?.archived == true) ArchivedCategoryBadge()
                } else {
                    Text(
                        text = strings.categoryNotChosen,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(
                onClick = onChange,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("review-category"),
            ) { Text(strings.categoryChange) }
        }
        val origin = when {
            !categoryResolved -> strings.categoryOriginNone
            draft.reviewCategoryResolved &&
                draft.reviewCategoryId == selectedCategoryId -> strings.categorySource(draft)
            // A local change has not been stored yet; the user made this choice.
            else -> strings.categoryOriginUser
        }
        Text(
            text = origin,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CategoryChoiceRow(
    name: String,
    categoryId: Long?,
    rowId: String,
    selected: Boolean,
    onSelect: () -> Unit,
    archived: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("review-category-choice-$rowId")
            .clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        CategorySwatch(categoryId = categoryId, archived = archived, directory = true)
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (archived) ArchivedCategoryBadge()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditSection(
    amountText: String,
    onAmountChange: (String) -> Unit,
    amountError: String?,
    dateText: String,
    onDateChange: (String) -> Unit,
    dateError: String?,
    timeText: String,
    onTimeChange: (String) -> Unit,
    timeError: String?,
    memoText: String,
    onMemoChange: (String) -> Unit,
    descriptionUnresolved: Boolean,
    selectedType: TransactionType?,
    typeError: Boolean,
    onTypeChange: (TransactionType) -> Unit,
    categoryError: Boolean,
    baseStrings: AegisStrings,
    strings: ReviewStrings,
) {
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings.editSection, style = MaterialTheme.typography.titleSmall)
        Text(
            text = strings.typeHelp,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(strings.typeLabel, style = MaterialTheme.typography.labelLarge)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            TransactionType.entries.forEach { type ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(
                            selected = selectedType == type,
                            role = Role.RadioButton,
                            onClick = { onTypeChange(type) },
                        )
                        .testTag("review-type-${type.name}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    RadioButton(selected = selectedType == type, onClick = null)
                    Text(
                        text = typeName(type, baseStrings),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
        if (typeError) {
            Text(strings.typeRequired, color = MaterialTheme.colorScheme.error)
        }
        OutlinedTextField(
            value = amountText,
            onValueChange = onAmountChange,
            label = { Text(strings.amountLabel) },
            placeholder = { Text(strings.amountPlaceholder) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = amountError != null,
            supportingText = { Text(amountError ?: strings.amountHint) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("review-amount")
                .focusedFieldBringIntoView(scope),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = dateText,
                onValueChange = onDateChange,
                label = { Text(strings.dateLabel) },
                placeholder = { Text(strings.datePlaceholder) },
                singleLine = true,
                isError = dateError != null,
                supportingText = { Text(dateError ?: "YYYY-MM-DD") },
                modifier = Modifier
                    .weight(1.2f)
                    .testTag("review-date")
                    .focusedFieldBringIntoView(scope),
            )
            OutlinedTextField(
                value = timeText,
                onValueChange = onTimeChange,
                label = { Text(strings.timeLabel) },
                placeholder = { Text(strings.timePlaceholder) },
                singleLine = true,
                isError = timeError != null,
                supportingText = { Text(timeError ?: "HH:mm") },
                modifier = Modifier
                    .weight(1f)
                    .testTag("review-time")
                    .focusedFieldBringIntoView(scope),
            )
        }
        Text(
            text = strings.timeHelp,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = memoText,
            onValueChange = onMemoChange,
            label = { Text(strings.descriptionLabel) },
            placeholder = { Text(strings.descriptionPlaceholder) },
            singleLine = true,
            supportingText = {
                Text(
                    if (descriptionUnresolved) {
                        strings.descriptionUnresolved
                    } else {
                        strings.descriptionOptional
                    },
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("review-description")
                .focusedFieldBringIntoView(scope),
        )
        if (categoryError) {
            Text(strings.categoryRequired, color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * Keeps a focused edit field above the sticky confirmation bar once the keyboard has opened.
 *
 * The bar itself lifts above the keyboard, which shrinks the scrolling viewport after the field
 * already has focus. A request made only at focus time would aim at the old geometry, so the
 * field asks again after the inset animation has settled.
 */
@Composable
private fun Modifier.focusedFieldBringIntoView(scope: CoroutineScope): Modifier {
    val requester = remember { BringIntoViewRequester() }
    return this
        .onFocusChanged { state ->
            if (state.isFocused) {
                scope.launch {
                    delay(280)
                    requester.bringIntoView()
                    delay(320)
                    requester.bringIntoView()
                }
            }
        }
        .bringIntoViewRequester(requester)
}

@Composable
private fun EvidenceSection(draft: PendingBankDraftEntity, strings: ReviewStrings) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(strings.evidenceSection, style = MaterialTheme.typography.titleSmall)
            Text(
                text = strings.evidenceHelp,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            EvidenceRow(strings.evidenceBank, reviewBankName(draft.bank))
            EvidenceRow(strings.evidenceSource, draft.sourcePackage)
            EvidenceRow(
                strings.evidenceFormat,
                if (draft.parseStatus == IntakeParseStatus.UNSUPPORTED_FORMAT) {
                    strings.evidenceFormatUnsupported
                } else {
                    strings.evidenceFormatSupported
                },
            )
            EvidenceRow(strings.evidenceAmount, strings.amount(draft))
            EvidenceRow(strings.evidenceDirection, strings.direction(draft.direction))
            EvidenceRow(strings.evidenceMoment, strings.statedMoment(draft))
            EvidenceRow(strings.evidenceMemo, strings.memo(draft))
            EvidenceRow(strings.evidenceArrived, strings.instant(draft.postedAtEpochMillis))
        }
    }
}

@Composable
private fun EvidenceRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
