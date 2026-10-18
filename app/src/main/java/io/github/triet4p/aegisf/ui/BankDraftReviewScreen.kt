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
import androidx.compose.material3.MaterialTheme
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
import io.github.triet4p.aegisf.data.BankDraftReviewRepository
import io.github.triet4p.aegisf.data.BankDraftReviewResult
import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.IntakeDirection
import io.github.triet4p.aegisf.data.IntakeFieldState
import io.github.triet4p.aegisf.data.IntakeParseStatus
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.IntakeRepository
import io.github.triet4p.aegisf.data.LedgerRepository
import io.github.triet4p.aegisf.data.LedgerValidationException
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.data.TransactionType
import io.github.triet4p.aegisf.data.VndMoney
import io.github.triet4p.aegisf.data.laya.LayaInferenceLifecycle
import io.github.triet4p.aegisf.data.laya.LayaInferenceOutcome
import io.github.triet4p.aegisf.data.laya.LayaRuntimeContract
import kotlinx.coroutines.CancellationException
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
    intakeRepository: IntakeRepository,
    reviewRepository: BankDraftReviewRepository,
    ledgerRepository: LedgerRepository,
    layaInferenceLifecycle: LayaInferenceLifecycle,
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
    val draftsFlow = remember(intakeRepository) { intakeRepository.observeDrafts() }
    val drafts by draftsFlow.collectAsState(initial = emptyList())
    val categoriesFlow = remember(ledgerRepository) { ledgerRepository.observeCategories() }
    val categories by categoriesFlow.collectAsState(initial = emptyList())
    val activeCategories = remember(categories) { categories.filterNot { it.archived } }
    val selectedDraft = draftId?.let { id -> drafts.firstOrNull { it.id == id } }

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
        if (draftId == null) {
            InboxContent(
                drafts = drafts,
                contentModifier = Modifier.padding(innerPadding),
                onSelectDraft = onSelectDraft,
            )
        } else if (selectedDraft == null) {
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
                draft = selectedDraft,
                allDrafts = drafts,
                categories = categories,
                reviewRepository = reviewRepository,
                ledgerRepository = ledgerRepository,
                layaInferenceLifecycle = layaInferenceLifecycle,
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
    contentModifier: Modifier,
    onSelectDraft: (Long) -> Unit,
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
            text = if (pending.isEmpty()) "No drafts waiting for review." else "${pending.size} waiting",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        pending.forEach { draft ->
            DraftInboxCard(
                draft = draft,
                possibleDuplicate = draft.possibleDuplicateOfDraftId?.let { priorId ->
                    drafts.firstOrNull { it.id == priorId }
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
    categories: List<CategoryEntity>,
    reviewRepository: BankDraftReviewRepository,
    ledgerRepository: LedgerRepository,
    layaInferenceLifecycle: LayaInferenceLifecycle,
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
    val latestObservedCategories by rememberUpdatedState(categories)
    LaunchedEffect(
        ledgerRepository,
        layaInferenceLifecycle,
        draftFormIdentity,
        memoText,
        suggestionNote,
        categories,
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
            // A present but non-meaningful note yields null on purpose rather than
            // falling back to the memo: junk in the note field must not silently
            // reuse the bank text. Only a truly empty note defers to the memo state.
            if (noteTrimmed.isEmpty() && trimmed.isEmpty()) {
                suggestionMachine.waitForDescription()
            } else {
                suggestionMachine.ignore(latestSuggestionIntent)
            }
            return@LaunchedEffect
        }
        val requestCategories = categories
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
        val decision = try {
            decideRememberedBeforeInference(
                description = description,
                activeCategories = requestCategories.filterNot { it.archived },
                findRememberedCategory = ledgerRepository::findRememberedCategoryForDescription,
                infer = { input, assignable ->
                    layaInferenceLifecycle.suggest(input, assignable)
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            val currentCategories = ledgerRepository.categories()
            suggestionMachine.unavailable(
                token = token,
                currentIntent = latestSuggestionIntent,
                currentCategories = currentCategories,
                observedCategories = latestObservedCategories,
                reason = UnavailableReason.FAILED,
            )
            return@LaunchedEffect
        }
        val currentCategories = ledgerRepository.categories()
        val observedCategories = latestObservedCategories
        val outcome = when (decision) {
            is ManualCategoryDecision.Remembered -> {
                suggestionMachine.completeRemembered(
                    token = token,
                    currentIntent = latestSuggestionIntent,
                    currentCategories = currentCategories,
                    observedCategories = observedCategories,
                    category = decision.category,
                )
                return@LaunchedEffect
            }
            is ManualCategoryDecision.Inferred -> decision.outcome
        }
        when (outcome) {
            is LayaInferenceOutcome.Completed -> suggestionMachine.complete(
                token = token,
                currentIntent = latestSuggestionIntent,
                currentCategories = currentCategories,
                observedCategories = observedCategories,
                resolution = io.github.triet4p.aegisf.data.laya.LayaCandidatePlanner.resolveCurrent(
                    outcome.value,
                    currentCategories,
                ),
            )
            is LayaInferenceOutcome.Unavailable -> suggestionMachine.unavailable(
                token = token,
                currentIntent = latestSuggestionIntent,
                currentCategories = currentCategories,
                observedCategories = observedCategories,
                reason = when (outcome.state) {
                    LayaRuntimeContract.State.Missing -> UnavailableReason.MISSING
                    is LayaRuntimeContract.State.CorruptOrIncompatible ->
                        UnavailableReason.INCOMPATIBLE
                    LayaRuntimeContract.State.Ready -> UnavailableReason.FAILED
                },
            )
            LayaInferenceOutcome.Superseded -> suggestionMachine.unavailable(
                token = token,
                currentIntent = latestSuggestionIntent,
                currentCategories = currentCategories,
                observedCategories = observedCategories,
                reason = UnavailableReason.SUPERSEDED,
            )
            LayaInferenceOutcome.Closed -> suggestionMachine.unavailable(
                token = token,
                currentIntent = latestSuggestionIntent,
                currentCategories = currentCategories,
                observedCategories = observedCategories,
                reason = UnavailableReason.CLOSED,
            )
        }
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

    fun handleResult(result: BankDraftReviewResult, action: String) {
        when (result) {
            is BankDraftReviewResult.Saved -> {
                expectedRevision = result.revision
                feedback = "Corrections saved."
            }
            is BankDraftReviewResult.Confirmed -> onNavigateUp()
            BankDraftReviewResult.Dismissed -> onNavigateUp()
            is BankDraftReviewResult.NeedsCorrection -> {
                validationRequested = true
                feedback = correctionMessage(result.fields)
            }
            BankDraftReviewResult.CategoryUnavailable -> feedback =
                "That category is no longer available. Choose an active category and try again."
            BankDraftReviewResult.AlreadyReviewed -> feedback =
                "This draft was already confirmed or dismissed. No additional transaction was added."
            BankDraftReviewResult.Changed -> feedback =
                "This draft changed elsewhere. Leave and reopen it before saving or confirming."
            BankDraftReviewResult.NotFound -> feedback = "This draft is no longer available."
        }
        if (action == "dismiss") confirmingDismiss = false
    }

    fun saveCorrections() {
        if (!writeInFlight.compareAndSet(false, true)) return
        busy = true
        feedback = null
        scope.launch {
            try {
                handleResult(
                    reviewRepository.saveReview(draft.id, expectedRevision, edits),
                    "save",
                )
            } finally {
                busy = false
                writeInFlight.set(false)
            }
        }
    }

    fun confirm() {
        if (!writeInFlight.compareAndSet(false, true)) return
        validationRequested = true
        busy = true
        feedback = null
        scope.launch {
            try {
                handleResult(
                    reviewRepository.confirmDraft(draft.id, expectedRevision, edits),
                    "confirm",
                )
            } finally {
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
                handleResult(reviewRepository.dismissDraft(draft.id), "dismiss")
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
            // Optional suggestion-only English hint. Kept in its own field so typed text
            // can never concatenate with the stored bank memo: the gate evaluates this
            // note alone, the keyboard autocomplete inside it cannot poison bank text,
            // and the note is never written to the draft or the confirmed description.
            // Autocorrect stays enabled here so the field records what the phone
            // keyboard actually delivered; the gate simply treats a diacritic note as
            // non-meaningful and leaves the draft manual.
            OutlinedTextField(
                value = suggestionNote,
                onValueChange = {
                    if (suggestionNote != it) {
                        suggestionMachine.clearSuppression()
                    }
                    suggestionNote = it
                    feedback = null
                },
                label = { Text("Suggestion note (optional English)") },
                placeholder = { Text("For example Monthly rent") },
                singleLine = true,
                supportingText = {
                    Text("Suggestion hint only. It never changes the bank memo or the saved description.")
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
                categories.filterNot { it.archived }.forEach { category ->
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
                if (categories.none { !it.archived }) {
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
                onClick = ::confirm,
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
}

/**
 * Bank review keeps the suggestion header visible for every settled machine state.
 * The ordinary bank memo is usually opaque, coded, or non-English, so the gate yields
 * null and the machine rests in Ignored/Waiting; hiding the panel there looks exactly
 * like a missing feature (S4.10-A2 user finding). Only the transient Idle (no request
 * yet, effect still to run) renders nothing. Inference still runs solely for eligible
 * English and never receives codes/OTP.
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
    // The ordinary bank memo is usually opaque, coded, or non-English, so the gate
    // yields null and the machine rests in Ignored/Waiting. Hiding the panel there
    // looks exactly like a missing feature (S4.10-A2 user finding). Keep the header
    // and a truthful reason visible instead; inference still runs only for eligible
    // English and never receives codes/OTP.
    val effectiveInput = draftSuggestionInput(draft, memoText, suggestionNote)
    val noteBlank = suggestionNote.trim().isEmpty()
    val status = when (state) {
        ManualCategorySuggestionState.Idle -> return
        ManualCategorySuggestionState.WaitingForDescription ->
            "No description to check yet. Enter the description or add a plain English " +
                "note to request ranked probabilities. The bank memo stays unchanged. " +
                "Manual choice and confirmation stay available."
        ManualCategorySuggestionState.ManuallySelected ->
            "Your category choice stays selected. The suggestion will not replace it " +
                "for this input. Edit the description or note to request again."
        ManualCategorySuggestionState.Ignored ->
            if (effectiveInput == null) {
                if (!noteBlank) {
                    "That note is not eligible English for the on-device model, so nothing " +
                        "was sent. The bank memo stays unchanged. Try a plain English note " +
                        "(for example Monthly rent), or choose a category manually. Nothing " +
                        "is confirmed or saved."
                } else {
                    "The bank memo is not eligible English for the on-device model, so nothing " +
                        "was sent. Add a plain English note to request ranked probabilities, " +
                        "or choose a category manually. The memo and saved description stay " +
                        "unchanged. Nothing is confirmed or saved."
                }
            } else {
                "Suggestion ignored. The selected category and other fields are unchanged. " +
                    "Edit the description or note to request again."
            }
        ManualCategorySuggestionState.LoadingCategories ->
            "Loading current categories. Choosing a category or confirming stays available."
        ManualCategorySuggestionState.Loading ->
            "Checking saved exact-description rules before local model inference. " +
                "Choosing a category or confirming stays available."
        ManualCategorySuggestionState.NoActiveCategories ->
            "No active categories are available. Choose No category explicitly to confirm."
        ManualCategorySuggestionState.NotApplicableToKind ->
            "Suggestions follow the description text; manual choice stays available."
        is ManualCategorySuggestionState.Suggested ->
            if (state.distribution != null) {
                "The model ranked your current categories below from the eligible " +
                    "input. Pick one to fill the category field, or leave the " +
                    "field unchanged. Nothing is confirmed or saved."
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
            if (state.distribution != null) {
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
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
