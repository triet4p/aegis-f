package io.github.triet4p.aegisf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.CategoryQuery
import io.github.triet4p.aegisf.data.CategoryState
import io.github.triet4p.aegisf.data.CorrectTransactionRequest
import io.github.triet4p.aegisf.data.LIST_PAGE_SIZE
import io.github.triet4p.aegisf.data.QueryPage
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.LedgerRepository
import io.github.triet4p.aegisf.data.LedgerValidationException
import io.github.triet4p.aegisf.data.ManualCategoryDecision
import io.github.triet4p.aegisf.data.ManualTransactionSource
import io.github.triet4p.aegisf.data.SaveManualTransactionRequest
import io.github.triet4p.aegisf.data.TransactionEntity
import io.github.triet4p.aegisf.data.TransactionType
import io.github.triet4p.aegisf.data.UnrecordedNoteEvent
import io.github.triet4p.aegisf.data.VndMoney
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowChange
import io.github.triet4p.aegisf.data.WorkflowCommandOutcome
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowDenialReason
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.data.decideRememberedBeforeInference
import io.github.triet4p.aegisf.data.laya.LayaCandidatePlanner
import io.github.triet4p.aegisf.data.laya.LayaEffectiveContext
import io.github.triet4p.aegisf.data.laya.LayaInferenceLifecycle
import io.github.triet4p.aegisf.data.laya.LayaInferenceOutcome
import io.github.triet4p.aegisf.data.laya.LayaRuntimeContract
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Calibrated model shares render as whole percents; values are never fabricated. */
internal fun formatProbability(probability: Double): String {
    val percent = (probability * 100.0).coerceIn(0.0, 100.0)
    return String.format(Locale.US, "%.0f%%", percent)
}

/**
 * Manual transaction editor: one stored record per Save.
 *
 * With [transactionId] null this is the creation form: the fields start empty, a Save writes
 * one confirmed manual record through the application's one command boundary, and the form is
 * replaced by a confirmation panel that reads the persisted row back. With a stored id it is
 * the correction form: the amount, type, moment, description, and category are read from the
 * database and prefilled, and a Save replaces exactly those fields on the same row through
 * the same boundary before the screen returns to the detail view, which shows the saved
 * values. The record keeps its id, its creation time, its confirmation, and its
 * provenance, because a correction cannot write those.
 *
 * The form collects the user-supplied fields — amount, type, date, time, description, and
 * category where applicable — and writes them through the command boundary, which owns every
 * data rule and the caller's authority. A refused write stores nothing and shows the reason
 * under the field it belongs to, with the typed values intact.
 * New expense creation first checks for an explicit remembered exact-description rule, then
 * may show a process-owned Laya suggestion; either changes the category field only after
 * explicit acceptance and never writes a transaction. Income, transfers, and correction forms
 * remain manual. The live category list backs the picker and rule/suggestion resolution, so a
 * category renamed or archived during a request cannot remain eligible; an existing record
 * keeps its archived category link unless the user chooses another category.
 *
 * A record may be written from a saved note the user recorded by hand: the note event is
 * named, the note's own amount and moment fill the form, and the saved record resolves that
 * note — so a bank notification that arrives later cannot consume the same occurrence again.
 *
 * While a write is in flight the guard ignores further taps and the form cannot be left, so an
 * impatient double tap cannot turn one record into two or into none. Leaving without pressing
 * Save — Back, Cancel, or the system back gesture — writes nothing at all.
 */
@Composable
fun TransactionEditorScreen(
    repository: LedgerRepository,
    commandService: WorkflowCommandService,
    queryService: WorkflowQueryService,
    layaInferenceLifecycle: LayaInferenceLifecycle,
    transactionId: Long?,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (transactionId == null) {
        TransactionFormScreen(
            repository = repository,
            commandService = commandService,
            queryService = queryService,
            layaInferenceLifecycle = layaInferenceLifecycle,
            storedRecord = null,
            storedCategory = null,
            onNavigateUp = onNavigateUp,
            modifier = modifier,
        )
        return
    }

    // The stored row is re-read here rather than handed in by the caller: what fills the form
    // is what the database holds, so a stale caller cannot put another record's values in it.
    var loaded by remember(transactionId) {
        mutableStateOf<StoredTransactionState>(StoredTransactionState.Loading)
    }
    LaunchedEffect(transactionId) {
        val record = queryService.findLedgerRecord(transactionId)
        loaded = if (record == null) {
            StoredTransactionState.Missing
        } else {
            StoredTransactionState.Found(
                record = record,
                category = record.categoryId?.let { queryService.findCategory(it) },
            )
        }
    }

    when (val stored = loaded) {
        StoredTransactionState.Loading -> LoadingTransaction(onNavigateUp, modifier)

        StoredTransactionState.Missing -> MissingTransaction(onNavigateUp, modifier)

        is StoredTransactionState.Found -> TransactionFormScreen(
            repository = repository,
            commandService = commandService,
            queryService = queryService,
            layaInferenceLifecycle = layaInferenceLifecycle,
            storedRecord = stored.record,
            storedCategory = stored.category,
            onNavigateUp = onNavigateUp,
            modifier = modifier,
        )
    }
}

/** What the editor knows about the record it was asked to correct. */
private sealed interface StoredTransactionState {
    data object Loading : StoredTransactionState

    data object Missing : StoredTransactionState

    data class Found(val record: TransactionEntity, val category: CategoryEntity?) :
        StoredTransactionState
}

/**
 * The form of one record: the creation flow when [storedRecord] is null, the correction flow
 * otherwise, when [storedCategory] is the category the stored row already carries.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionFormScreen(
    repository: LedgerRepository,
    commandService: WorkflowCommandService,
    queryService: WorkflowQueryService,
    layaInferenceLifecycle: LayaInferenceLifecycle,
    storedRecord: TransactionEntity?,
    storedCategory: CategoryEntity?,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val editing = storedRecord != null
    val openedAtMillis = remember { System.currentTimeMillis() }
    val initialMomentMillis = storedRecord?.occurredAtEpochMillis ?: openedAtMillis
    var amountText by rememberSaveable {
        mutableStateOf(storedRecord?.amountVnd?.toString() ?: "")
    }
    var selectedType by rememberSaveable {
        mutableStateOf(storedRecord?.type ?: TransactionType.EXPENSE)
    }
    var dateText by rememberSaveable {
        mutableStateOf(ManualDateTime.dateFieldText(initialMomentMillis))
    }
    var timeText by rememberSaveable {
        mutableStateOf(ManualDateTime.timeFieldText(initialMomentMillis))
    }
    var descriptionText by rememberSaveable { mutableStateOf(storedRecord?.description ?: "") }
    var selectedCategoryId by rememberSaveable { mutableStateOf(storedRecord?.categoryId) }
    var selectedCategoryName by rememberSaveable { mutableStateOf(storedCategory?.name) }
    var categoryPickerVisible by rememberSaveable { mutableStateOf(false) }

    var amountError by remember { mutableStateOf<String?>(null) }
    var dateError by remember { mutableStateOf<String?>(null) }
    var timeError by remember { mutableStateOf<String?>(null) }
    var categoryError by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var ruleActionInFlight by remember { mutableStateOf(false) }
    var ruleActionError by remember { mutableStateOf<String?>(null) }
    var savedRecordId by rememberSaveable { mutableStateOf<Long?>(null) }
    var savedRecord by remember { mutableStateOf<TransactionEntity?>(null) }

    // The key names this save request: the same request under the same key stores one
    // record, so a retry after a rotation or a restart cannot write the record twice.
    var saveKey by rememberSaveable { mutableStateOf(newSaveRequestKey()) }

    // The note this record is written from, when the user records an unmatched note by
    // hand. The saved record resolves that note, so a bank notification that arrives later
    // cannot consume the same occurrence again.
    var sourceEvent by remember { mutableStateOf<UnrecordedNoteEvent?>(null) }
    var notePickerVisible by rememberSaveable { mutableStateOf(false) }

    // The whole catalog, live and read in bounded chunks: the model's candidate ranking
    // needs every active category, and an archive or a rename of any of them has to
    // invalidate a suggestion that was made against the catalog. The picker itself reads
    // the catalog page by page, so every stored category is reachable there.
    val catalog: List<CategoryEntity>? by remember(queryService) {
        queryService.observeCategoryCatalog()
    }.collectAsState(initial = null)
    val activeCategories = remember(catalog) {
        catalog?.filterNot { it.archived }
    }
    var formIdentity by remember { mutableStateOf(Any()) }
    val suggestionMachine = remember(formIdentity) { ManualCategorySuggestionMachine() }
    var suggestionRevision by remember { mutableStateOf(0) }
    val suggestionIntent = remember(formIdentity, descriptionText, selectedType) {
        ManualSuggestionIntent(formIdentity, descriptionText, selectedType)
    }
    val latestSuggestionIntent by rememberUpdatedState(suggestionIntent)
    val latestObservedCategories by rememberUpdatedState(catalog)

    // Kept as an id so the confirmation survives a configuration change: the record is
    // re-read from storage instead of being held in unsaveable state.
    LaunchedEffect(savedRecordId) {
        val id = savedRecordId
        savedRecord = if (id == null) null else queryService.findLedgerRecord(id)
    }

    val scope = rememberCoroutineScope()
    val writeInFlight = remember { AtomicBoolean(false) }
    LaunchedEffect(
        repository,
        layaInferenceLifecycle,
        formIdentity,
        editing,
        descriptionText,
        selectedType,
        catalog,
        suggestionRevision,
        savedRecordId,
    ) {
        if (editing || savedRecordId != null) {
            suggestionMachine.reset()
            return@LaunchedEffect
        }
        if (selectedType != TransactionType.EXPENSE) {
            if (selectedType == TransactionType.INCOME) {
                suggestionMachine.notApplicableToKind()
            } else {
                suggestionMachine.reset()
            }
            return@LaunchedEffect
        }
        val requestCategories = catalog
        if (requestCategories == null) {
            suggestionMachine.waitForCategories()
            return@LaunchedEffect
        }
        if (descriptionText.isBlank()) {
            suggestionMachine.waitForDescription()
            return@LaunchedEffect
        }
        val requestActiveCategories = checkNotNull(activeCategories)
        if (requestActiveCategories.isEmpty()) {
            suggestionMachine.noActiveCategories()
            return@LaunchedEffect
        }

        val token = suggestionMachine.begin(
            ManualSuggestionRequest(suggestionIntent, requestCategories),
        ) ?: return@LaunchedEffect
        val decision = try {
            decideRememberedBeforeInference(
                description = descriptionText,
                context = LayaEffectiveContext.of(descriptionText),
                activeCategories = requestActiveCategories,
                findRememberedCategory = repository::findRememberedCategoryForDescription,
                infer = { context, categories ->
                    layaInferenceLifecycle.suggest(context, categories)
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            val currentCategories = queryService.categoryCatalog()
            suggestionMachine.unavailable(
                token = token,
                currentIntent = latestSuggestionIntent,
                currentCategories = currentCategories,
                observedCategories = latestObservedCategories,
                reason = UnavailableReason.FAILED,
            )
            return@LaunchedEffect
        }
        val currentCategories = queryService.categoryCatalog()
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
                resolution = LayaCandidatePlanner.resolveCurrent(
                    outcome.value.decision,
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

    BackHandler { if (!saving) onNavigateUp() }


    // Prefer the current row name when a category changes while the form is open.
    val currentSelectedCategory = catalog
        ?.firstOrNull { it.id == selectedCategoryId }
    val categoryButtonLabel = when {
        selectedCategoryId == null -> null
        currentSelectedCategory != null -> categoryLabel(currentSelectedCategory)
        storedCategory != null && selectedCategoryId == storedCategory.id ->
            categoryLabel(storedCategory)
        else -> selectedCategoryName
    }
    val rememberableCategoryName = currentSelectedCategory
        ?.takeIf {
            !editing && selectedType == TransactionType.EXPENSE && descriptionText.isNotBlank()
        }
        ?.takeUnless { it.archived }
        ?.name
    val categoryNote = when {
        currentSelectedCategory?.archived == true &&
            selectedCategoryId != storedRecord?.categoryId ->
            "\"${currentSelectedCategory.name}\" was archived while this form was open. " +
                "Choose an active category; saving will refuse this assignment."
        // A category deleted while this form was open leaves its id selected but no row
        // behind it. Saying so is what keeps the stale name from looking like a live choice;
        // saving it is refused by the repository's own category check.
        catalog != null && selectedCategoryId != null && currentSelectedCategory == null ->
            "The category this form had selected no longer exists: it was deleted. Choose " +
                "another category or No category; saving will refuse the removed one."
        storedCategory != null && storedCategory.archived &&
            selectedCategoryId == storedCategory.id ->
            "\"${storedCategory.name}\" is archived. This record keeps it unless you choose " +
                "another category."
        else -> null
    }

    /**
     * Shows why a write was refused.
     *
     * The reason belongs under the field it is about, so the typed values stay where the
     * user put them and the form can be corrected in place.
     */
    fun showRefusal(outcome: WorkflowCommandOutcome) {
        when (outcome) {
            is WorkflowCommandOutcome.NeedsClarification -> {
                if (outcome.field == "categoryId") {
                    categoryError = outcome.reason
                } else {
                    amountError = outcome.reason
                }
            }
            is WorkflowCommandOutcome.Denied -> {
                if (outcome.reason == WorkflowDenialReason.CATEGORY_UNAVAILABLE) {
                    categoryError = "That category is no longer available. Choose an active " +
                        "category and try again."
                } else {
                    saveError = "This record could not be saved."
                }
            }
            is WorkflowCommandOutcome.Conflict -> saveError = outcome.reason
            is WorkflowCommandOutcome.Failed -> saveError = outcome.reason
            WorkflowCommandOutcome.NotFound -> saveError = "That record is no longer available."
            WorkflowCommandOutcome.Cancelled -> Unit
            is WorkflowCommandOutcome.Applied,
            is WorkflowCommandOutcome.NoChange,
            -> Unit
        }
    }

    fun save() {
        amountError = null
        dateError = null
        timeError = null
        categoryError = null
        saveError = null

        val amountVnd = try {
            VndMoney.parseManualAmount(amountText)
        } catch (rejection: LedgerValidationException) {
            amountError = rejection.message
            writeInFlight.set(false)
            return
        }
        val dateProblem = ManualDateTime.dateError(dateText)
        val timeProblem = ManualDateTime.timeError(timeText)
        if (dateProblem != null || timeProblem != null) {
            dateError = dateProblem
            timeProblem?.let { timeError = it }
            writeInFlight.set(false)
            return
        }

        saving = true
        scope.launch {
            try {
                val type = selectedType
                val occurredAtEpochMillis = ManualDateTime.toEpochMillis(dateText, timeText)
                val description = descriptionText.trim()
                // A transfer carries no category, so the type decides whether the chosen one
                // is written at all; the stored category is otherwise kept as it is.
                val categoryId = selectedCategoryId.takeIf { type != TransactionType.TRANSFER }
                val stored = storedRecord
                val storedId = stored?.id
                if (storedId == null || stored == null) {
                    val outcome = commandService.saveManualTransaction(
                        scope = WorkflowCallerScope.User,
                        request = SaveManualTransactionRequest(
                            idempotencyKey = saveKey,
                            type = type,
                            amountVnd = amountVnd,
                            occurredAtEpochMillis = occurredAtEpochMillis,
                            description = description,
                            categoryId = categoryId,
                            // The note and the reading it was read from travel together:
                            // the record may only be written against the exact reading
                            // the user picked.
                            source = sourceEvent?.let { note ->
                                ManualTransactionSource.NoteEvent(
                                    eventId = note.eventId,
                                    expectedRevision = note.revision,
                                )
                            } ?: ManualTransactionSource.None,
                        ),
                    )
                    when (outcome) {
                        is WorkflowCommandOutcome.Applied -> {
                            savedRecordId = (outcome.change as? WorkflowChange.TransactionSaved)
                                ?.transactionId
                        }
                        is WorkflowCommandOutcome.NoChange -> {
                            // The same request was already made under this key: the record
                            // that was made then is the record this form is about.
                            savedRecordId = (outcome.change as? WorkflowChange.TransactionSaved)
                                ?.transactionId
                        }
                        else -> showRefusal(outcome)
                    }
                } else {
                    val outcome = commandService.correctTransaction(
                        scope = WorkflowCallerScope.User,
                        request = CorrectTransactionRequest(
                            idempotencyKey = saveKey,
                            transactionId = storedId,
                            expectedRevision = stored.revision,
                            type = type,
                            amountVnd = amountVnd,
                            occurredAtEpochMillis = occurredAtEpochMillis,
                            description = description,
                            categoryId = categoryId,
                        ),
                    )
                    when (outcome) {
                        is WorkflowCommandOutcome.Applied,
                        is WorkflowCommandOutcome.NoChange,
                        -> {
                            // The corrected values are now the detail view's to show: it reads
                            // the row back out of storage instead of this form reporting its
                            // own inputs.
                            onNavigateUp()
                        }
                        else -> showRefusal(outcome)
                    }
                }
            } finally {
                saving = false
                writeInFlight.set(false)
            }
        }
    }

    fun rememberSelectedCategoryRule() {
        val categoryId = selectedCategoryId ?: return
        val intent = suggestionIntent
        if (editing || selectedType != TransactionType.EXPENSE ||
            descriptionText.isBlank() ||
            activeCategories?.any { it.id == categoryId } != true
        ) {
            return
        }
        ruleActionInFlight = true
        ruleActionError = null
        scope.launch {
            try {
                val created = repository.rememberCategoryForDescription(
                    description = intent.description,
                    categoryId = categoryId,
                )
                if (latestSuggestionIntent == intent) {
                    if (created) {
                        suggestionMachine.clearSuppression()
                        suggestionRevision += 1
                    } else {
                        ruleActionError =
                            "A saved rule already applies to this exact description. Remove it " +
                                "before saving a different category."
                    }
                }
            } catch (rejection: LedgerValidationException) {
                if (latestSuggestionIntent == intent) {
                    ruleActionError = rejection.message
                }
            } finally {
                ruleActionInFlight = false
            }
        }
    }

    fun removeRememberedCategoryRule() {
        val intent = suggestionIntent
        ruleActionInFlight = true
        ruleActionError = null
        scope.launch {
            try {
                val removed = repository.removeRememberedCategoryRule(intent.description)
                if (latestSuggestionIntent == intent) {
                    if (removed) {
                        suggestionMachine.ruleRemoved(intent)
                    } else {
                        ruleActionError = "This saved rule is no longer available."
                    }
                    suggestionRevision += 1
                }
            } finally {
                ruleActionInFlight = false
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            EditorTopBar(
                title = if (editing) "Edit transaction" else "New transaction",
                onNavigateUp = onNavigateUp,
                backEnabled = !saving,
            )
        },
    ) { innerPadding ->
        val saved = savedRecord
        if (saved != null) {
            SavedTransactionPanel(
                record = saved,
                categoryName = selectedCategoryName,
                onRecordAnother = {
                    // A further record is a new suggestion context, with the current moment.
                    val nowMillis = System.currentTimeMillis()
                    formIdentity = Any()
                    amountText = ""
                    selectedType = TransactionType.EXPENSE
                    dateText = ManualDateTime.dateFieldText(nowMillis)
                    timeText = ManualDateTime.timeFieldText(nowMillis)
                    descriptionText = ""
                    selectedCategoryId = null
                    selectedCategoryName = null
                    savedRecordId = null
                    savedRecord = null
                    // A further record is a further request: it gets its own key, and it
                    // records no note until the user names one again.
                    saveKey = newSaveRequestKey()
                    sourceEvent = null
                },
                onDone = onNavigateUp,
                modifier = Modifier.padding(innerPadding),
            )
        } else {
            TransactionForm(
                amountText = amountText,
                onAmountChange = { amountText = it; amountError = null },
                amountError = amountError,
                selectedType = selectedType,
                onTypeChange = {
                    if (selectedType != it) suggestionMachine.clearSuppression()
                    selectedType = it
                    categoryError = null
                    ruleActionError = null
                },
                dateText = dateText,
                onDateChange = { dateText = it; dateError = null },
                dateError = dateError,
                timeText = timeText,
                onTimeChange = { timeText = it; timeError = null },
                timeError = timeError,
                descriptionText = descriptionText,
                onDescriptionChange = {
                    if (descriptionText != it) {
                        suggestionMachine.clearSuppression()
                        ruleActionError = null
                    }
                    descriptionText = it
                },
                categoryName = categoryButtonLabel,
                categoryNote = categoryNote,
                categoryError = categoryError,
                rememberableCategoryName = rememberableCategoryName,
                ruleActionError = ruleActionError,
                ruleActionInFlight = ruleActionInFlight,
                onPickCategory = { categoryPickerVisible = true },
                suggestionState = suggestionMachine.state,
                onAcceptSuggestion = {
                    suggestionMachine.acceptSuggestion()?.let { category ->
                        selectedCategoryId = category.id
                        selectedCategoryName = category.name
                        categoryError = null
                    }
                },
                onAcceptRememberedRule = {
                    suggestionMachine.acceptRememberedRule()?.let { rememberedCategory ->
                        val currentCategory = activeCategories
                            ?.firstOrNull { it.id == rememberedCategory.id }
                        if (currentCategory == null) {
                            suggestionMachine.clearSuppression()
                            suggestionRevision += 1
                        } else {
                            selectedCategoryId = currentCategory.id
                            selectedCategoryName = currentCategory.name
                            categoryError = null
                        }
                    }
                },
                onIgnoreSuggestion = {
                    suggestionMachine.ignore(suggestionIntent)
                    suggestionRevision += 1
                },
                onRememberCategoryRule = ::rememberSelectedCategoryRule,
                onRemoveRememberedRule = ::removeRememberedCategoryRule,
                onChooseDistributionOption = { categoryId ->
                    suggestionMachine.chooseDistributionOption(categoryId)?.let { category ->
                        selectedCategoryId = category.id
                        selectedCategoryName = category.name
                        categoryError = null
                    }
                },
                onChooseNoCategory = {
                    if (suggestionMachine.chooseNoCategoryOption()) {
                        selectedCategoryId = null
                        selectedCategoryName = null
                        categoryError = null
                    }
                },
                sourceEvent = sourceEvent,
                onPickNote = { notePickerVisible = true },
                onClearNote = { sourceEvent = null },
                saveError = saveError,
                saving = saving,
                editing = editing,
                // The guard is checked before the coroutine starts, so two taps inside one
                // frame still produce a single write; the button state only makes it visible.
                onSave = { if (writeInFlight.compareAndSet(false, true)) save() },
                onCancel = onNavigateUp,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }

    if (categoryPickerVisible) {
        CategoryPickerDialog(
            queryService = queryService,
            // The retained option is the record's own archived category, and it is only
            // offered while a row for it still exists: the stored snapshot is used just
            // while the live list is still loading, so a category deleted during this form
            // cannot come back as a picker choice.
            retainedCategory = storedCategory
                ?.takeIf { it.archived }
                ?.let { stored ->
                    when (val loaded = catalog) {
                        null -> stored
                        else -> loaded.firstOrNull { it.id == stored.id }
                    }
                },
            selectedCategoryId = selectedCategoryId,
            onSelect = { id, name ->
                suggestionMachine.chooseManually(suggestionIntent)
                suggestionRevision += 1
                selectedCategoryId = id
                selectedCategoryName = name
                categoryError = null
                ruleActionError = null
                categoryPickerVisible = false
            },
            onDismiss = { categoryPickerVisible = false },
        )
    }

    if (notePickerVisible) {
        NoteEventPickerDialog(
            queryService = queryService,
            onSelect = { note ->
                // The note states the money and the day; the moment and the rest stay the
                // user's own to fill in.
                sourceEvent = note
                note.amountVnd?.let { amount -> amountText = amount.toString() }
                note.anchorDate?.let { day -> dateText = day }
                notePickerVisible = false
            },
            onDismiss = { notePickerVisible = false },
        )
    }
}

/**
 * The saved notes that are still waiting to be recorded by hand.
 *
 * Only the notes that hold a complete reading and are not already recorded or linked to
 * a bank notification are offered: a note that is spent is not a candidate, and a note
 * whose bank notification is waiting is that notification's business.
 */
@Composable
private fun NoteEventPickerDialog(
    queryService: WorkflowQueryService,
    onSelect: (UnrecordedNoteEvent) -> Unit,
    onDismiss: () -> Unit,
) {
    var notes by remember { mutableStateOf<List<UnrecordedNoteEvent>?>(null) }
    LaunchedEffect(queryService) {
        notes = queryService.unrecordedNoteEvents(QueryPageRequest(LIST_PAGE_SIZE)).items
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record from a saved note") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (val loaded = notes) {
                    null -> Text("Loading your saved notes…")
                    else -> if (loaded.isEmpty()) {
                        Text("No saved note is waiting to be recorded.")
                    } else {
                        loaded.forEach { note ->
                            OutlinedButton(
                                onClick = { onSelect(note) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(noteLabel(note))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

/** How one saved note is named in the picker. */
private fun noteLabel(note: UnrecordedNoteEvent): String {
    val reading = note.normalizedText ?: note.captureText ?: ""
    val amount = note.amountVnd?.let { VndMoney.formatAmount(it) }
    return listOfNotNull(
        reading.takeIf { it.isNotBlank() },
        amount,
        note.anchorDate,
        note.anchorTime,
    ).joinToString(" · ")
}

/** A key that names one save request; the same key stores one record. */
private fun newSaveRequestKey(): String = "manual-save:" + UUID.randomUUID()

/** The top bar of every editor state: one title and one Back control. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorTopBar(
    title: String,
    onNavigateUp: () -> Unit,
    backEnabled: Boolean = true,
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            TextButton(
                onClick = onNavigateUp,
                enabled = backEnabled,
            ) { Text("Back") }
        },
    )
}

/** The moment between asking for a stored record and having it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoadingTransaction(onNavigateUp: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onNavigateUp)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { EditorTopBar("Transaction", onNavigateUp) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            Text(
                text = "Loading transaction…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A record that was asked for but is no longer in the ledger. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MissingTransaction(onNavigateUp: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onNavigateUp)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { EditorTopBar("Transaction", onNavigateUp) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "This transaction no longer exists",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "It is not in the ledger, so there is nothing to show or change here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The fields of one manual record, empty for creation and prefilled for a correction. */
@Composable
private fun TransactionForm(
    amountText: String,
    onAmountChange: (String) -> Unit,
    amountError: String?,
    selectedType: TransactionType,
    onTypeChange: (TransactionType) -> Unit,
    dateText: String,
    onDateChange: (String) -> Unit,
    dateError: String?,
    timeText: String,
    onTimeChange: (String) -> Unit,
    timeError: String?,
    descriptionText: String,
    onDescriptionChange: (String) -> Unit,
    categoryName: String?,
    categoryNote: String?,
    categoryError: String?,
    rememberableCategoryName: String?,
    ruleActionError: String?,
    ruleActionInFlight: Boolean,
    onPickCategory: () -> Unit,
    suggestionState: ManualCategorySuggestionState,
    onAcceptSuggestion: () -> Unit,
    onAcceptRememberedRule: () -> Unit,
    onRememberCategoryRule: () -> Unit,
    onRemoveRememberedRule: () -> Unit,
    onIgnoreSuggestion: () -> Unit,
    onChooseDistributionOption: (Long) -> Unit,
    onChooseNoCategory: () -> Unit,
    sourceEvent: UnrecordedNoteEvent?,
    onPickNote: () -> Unit,
    onClearNote: () -> Unit,
    saveError: String?,
    saving: Boolean,
    editing: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = if (editing) {
                "Change what this record holds in exact whole dong. Nothing is stored until " +
                    "you save, and leaving now changes nothing."
            } else {
                "Record what happened in exact whole dong. Nothing is stored until you save."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val amountPreview = try {
            VndMoney.formatAmount(VndMoney.parseManualAmount(amountText))
        } catch (rejection: LedgerValidationException) {
            null
        }
        OutlinedTextField(
            value = amountText,
            onValueChange = onAmountChange,
            label = { Text("Amount (VND)") },
            placeholder = { Text("45000") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = amountError != null,
            supportingText = amountError?.let { message -> { Text(message) } }
                ?: amountPreview?.let { formatted -> { Text("Saves as $formatted") } },
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = "Type",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TransactionTypePicker(
            selectedType = selectedType,
            onTypeChange = onTypeChange,
        )

        OutlinedTextField(
            value = dateText,
            onValueChange = onDateChange,
            label = { Text("Date") },
            placeholder = { Text("2026-10-15") },
            singleLine = true,
            isError = dateError != null,
            supportingText = dateError?.let { message -> { Text(message) } },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = timeText,
            onValueChange = onTimeChange,
            label = { Text("Time") },
            placeholder = { Text("18:30") },
            singleLine = true,
            isError = timeError != null,
            supportingText = timeError?.let { message -> { Text(message) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "Dates and times use your device's time zone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = descriptionText,
            onValueChange = onDescriptionChange,
            label = { Text("Description (optional)") },
            placeholder = { Text("Lunch") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (selectedType == TransactionType.TRANSFER) {
            Text(
                text = "An internal transfer moves money between your own accounts, so it " +
                    "carries no category.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            OutlinedButton(
                onClick = onPickCategory,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(categoryName ?: "Select category (optional)")
            }
            if (categoryNote != null) {
                Text(
                    text = categoryNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (categoryError != null) {
                Text(
                    text = categoryError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (!editing && selectedType != TransactionType.TRANSFER) {
            ManualSuggestionPanel(
                state = suggestionState,
                enabled = !saving && !ruleActionInFlight,
                rememberableCategoryName = rememberableCategoryName,
                ruleActionError = ruleActionError,
                onAccept = onAcceptSuggestion,
                onAcceptRememberedRule = onAcceptRememberedRule,
                onIgnore = onIgnoreSuggestion,
                onRemember = onRememberCategoryRule,
                onRemoveRememberedRule = onRemoveRememberedRule,
                onChooseDistributionOption = onChooseDistributionOption,
                onChooseNoCategory = onChooseNoCategory,
            )
        }

        if (!editing) {
            OutlinedButton(
                onClick = onPickNote,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Record from a saved note")
            }
            if (sourceEvent != null) {
                Text(
                    text = "This record resolves the saved note “${noteLabel(sourceEvent)}”. " +
                        "A bank notification that arrives later will not be matched to that " +
                        "note again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onClearNote, enabled = !saving) {
                    Text("Use no note")
                }
            }
        }

        if (saveError != null) {
            Text(
                text = saveError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Button(
            onClick = onSave,
            enabled = !saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = when {
                    saving -> "Saving…"
                    editing -> "Save changes"
                    else -> "Save transaction"
                },
            )
        }
        OutlinedButton(
            onClick = onCancel,
            enabled = !saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Cancel")
        }
    }
}
@Composable
private fun ManualSuggestionPanel(
    state: ManualCategorySuggestionState,
    enabled: Boolean,
    rememberableCategoryName: String?,
    ruleActionError: String?,
    onAccept: () -> Unit,
    onAcceptRememberedRule: () -> Unit,
    onIgnore: () -> Unit,
    onRemember: () -> Unit,
    onRemoveRememberedRule: () -> Unit,
    onChooseDistributionOption: (Long) -> Unit = {},
    onChooseNoCategory: () -> Unit = {},
) {
    if (state == ManualCategorySuggestionState.Idle) return

    val status = when (state) {
        ManualCategorySuggestionState.Idle -> return
        ManualCategorySuggestionState.LoadingCategories ->
            "Loading current categories. Manual entry remains available."
        ManualCategorySuggestionState.WaitingForDescription ->
            "Enter a description to request a suggestion. You can still choose a category or " +
                "save without one."
        ManualCategorySuggestionState.Loading ->
            "Working: checking saved rules, then running the local model if needed. Manual " +
                "category selection and saving remain available."
        ManualCategorySuggestionState.NoActiveCategories ->
            "No active categories are available. You can save without a category."
        ManualCategorySuggestionState.NotApplicableToKind ->
            "Laya category suggestions are for spending descriptions. Choose an income category " +
                "manually; normal entry and saving remain available."
        is ManualCategorySuggestionState.Suggested ->
            if (state.distribution != null) {
                "The model ranked your current categories below. Pick one to fill the " +
                    "category field, or leave the field unchanged. Nothing is saved."
            } else {
                "Laya suggests “${state.category.name}” from your current categories. It will not " +
                    "change the selected category unless you accept it."
            }
        is ManualCategorySuggestionState.Remembered ->
            if (state.manualSelection) {
                "Your manual category choice stays selected. The saved rule for " +
                    "“${state.category.name}” remains separate and is not overwritten."
            } else if (state.accepted) {
                "The saved exact-description rule for “${state.category.name}” was accepted. " +
                    "The category field changed only at your request; nothing has been saved. " +
                    "This is a saved rule, not a model score."
            } else {
                "A saved exact-description rule matches “${state.category.name}”. It does not " +
                    "change the category field until you accept it. This is a saved rule, not " +
                    "a model score."
            }
        ManualCategorySuggestionState.RuleRemoved ->
            "The saved rule was removed. No remembered rule applies to this description now."
        is ManualCategorySuggestionState.Accepted ->
            if (state.distribution != null) {
                "“${state.category.name}” from the ranked model list is selected in the " +
                    "category field at your request. Review it before saving; nothing has " +
                    "been saved."
            } else {
                "“${state.category.name}” is selected in the category field at your request. " +
                    "Review it before saving; nothing has been saved."
            }
        is ManualCategorySuggestionState.NoFit -> when (state.reason) {
            LayaCandidatePlanner.ManualReason.MODEL_NO_FIT ->
                if (state.distribution != null) {
                    "Unknown scored highest, so nothing was preselected. The ranked " +
                        "categories below stay selectable. Nothing is saved."
                } else {
                    "The model returned no fitting category. Choose one manually or save without " +
                        "a category."
                }
            LayaCandidatePlanner.ManualReason.NO_METADATA_EVIDENCE ->
                "The current category names did not identify candidates to check. Choose a " +
                    "category manually or save without one."
            LayaCandidatePlanner.ManualReason.SEQUENCE_UNFIT ->
                "The current category choices did not fit the model input budget. No " +
                    "suggestion was made; manual entry remains available."
            LayaCandidatePlanner.ManualReason.EMPTY_DESCRIPTION ->
                "Enter a description to request a suggestion. You can still choose a category " +
                    "or save without one."
            LayaCandidatePlanner.ManualReason.NO_ACTIVE_CATEGORIES ->
                "No active categories are available. You can save without a category."
            LayaCandidatePlanner.ManualReason.EFFECTIVE_INPUT_OVER_BUDGET ->
                effectiveInputOverBudgetMessage()
            else ->
                "No category suggestion is available for this description and category list. " +
                    "Choose one manually or save without one."
        }
        is ManualCategorySuggestionState.Stale -> when (state.reason) {
            LayaCandidatePlanner.StaleReason.MISSING_OR_ARCHIVED ->
                "The suggested category is no longer active. It was not applied; choose from " +
                    "current categories instead."
            LayaCandidatePlanner.StaleReason.RENAMED ->
                "A category was renamed while this suggestion was checked. It was not applied."
            LayaCandidatePlanner.StaleReason.DUPLICATE_ID, null ->
                "Category data changed while this suggestion was checked. It was not applied."
        }
        is ManualCategorySuggestionState.Unavailable -> when (state.reason) {
            UnavailableReason.MISSING ->
                "The local model is not installed. Manual category selection and saving still work."
            UnavailableReason.INCOMPATIBLE ->
                "The local model could not be verified. Manual category selection and saving " +
                    "still work."
            UnavailableReason.SUPERSEDED, UnavailableReason.CLOSED, UnavailableReason.FAILED ->
                "A suggestion is unavailable right now. Manual category selection and saving " +
                    "still work."
        }
        ManualCategorySuggestionState.ManuallySelected ->
            "Your category choice stays selected. Laya will not replace it for this description."
        ManualCategorySuggestionState.Ignored ->
            "Suggestion ignored. The selected category and other record fields are unchanged."
        ManualCategorySuggestionState.NoCategory ->
            "No category will be filed for this description unless you pick one. " +
                "Saving without a category remains available."
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
        val uncertainty = when (val current = state) {
            is ManualCategorySuggestionState.Suggested -> current.uncertainty
            is ManualCategorySuggestionState.Accepted -> current.uncertainty
            is ManualCategorySuggestionState.NoFit -> current.uncertainty
            else -> null
        }
        val pendingDistribution = when (val current = state) {
            is ManualCategorySuggestionState.Suggested -> current.distribution
            is ManualCategorySuggestionState.NoFit -> current.distribution
            else -> null
        }
        if (uncertainty != null && uncertainty.omittedCategoryCount > 0) {
            Text(
                text = "The suggestion process offered ${uncertainty.offeredCategoryCount} of " +
                    "${uncertainty.activeCategoryCount} active categories; " +
                    "${uncertainty.omittedCategoryCount} were omitted, so a better fit may be " +
                    "missing.",
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
                        OutlinedButton(onClick = onIgnore, enabled = enabled) {
                            Text("Ignore")
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onAccept, enabled = enabled) {
                            Text("Use suggestion")
                        }
                        OutlinedButton(onClick = onIgnore, enabled = enabled) {
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
                        OutlinedButton(onClick = onIgnore, enabled = enabled) {
                            Text("Ignore")
                        }
                    }
                }
            }
            is ManualCategorySuggestionState.Remembered -> {
                if (!state.accepted || state.manualSelection) {
                    Button(
                        onClick = onAcceptRememberedRule,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Use remembered category")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onIgnore, enabled = enabled) {
                        Text("Ignore")
                    }
                    OutlinedButton(onClick = onRemoveRememberedRule, enabled = enabled) {
                        Text("Remove rule")
                    }
                }
            }
            is ManualCategorySuggestionState.Accepted -> Unit
            is ManualCategorySuggestionState.NoCategory -> Unit
            is ManualCategorySuggestionState.Stale -> Unit
            is ManualCategorySuggestionState.Unavailable -> Unit
            ManualCategorySuggestionState.ManuallySelected -> Unit
            ManualCategorySuggestionState.Ignored -> Unit
            ManualCategorySuggestionState.LoadingCategories -> Unit
            ManualCategorySuggestionState.WaitingForDescription -> Unit
            ManualCategorySuggestionState.Loading -> Unit
            ManualCategorySuggestionState.NoActiveCategories -> Unit
            ManualCategorySuggestionState.NotApplicableToKind -> Unit
            ManualCategorySuggestionState.RuleRemoved -> Unit
            ManualCategorySuggestionState.Idle -> Unit
        }
        if (state !is ManualCategorySuggestionState.Suggested &&
            state !is ManualCategorySuggestionState.NoFit &&
            state !is ManualCategorySuggestionState.Remembered &&
            rememberableCategoryName != null
        ) {
            Text(
                text = "This rule matches only the description as written (case and repeated " +
                    "spaces are ignored). No other descriptions or records change.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onRemember,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Remember ${rememberableCategoryName} for this description")
            }
        }
        if (ruleActionError != null) {
            Text(
                text = ruleActionError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * Ranked model distribution shared by manual entry and bank review. Rows list the
 * evaluated categories in descending calibrated probability plus the unknown/no-fit
 * row from the same softmax; selecting any evaluated row only stages it for the
 * caller's explicit field update and never saves.
 */
@Composable
internal fun ManualDistributionList(
    distribution: LayaCandidatePlanner.ScoredDistribution,
    enabled: Boolean,
    onChooseDistributionOption: (Long) -> Unit,
    onChooseNoCategory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Model probabilities over the evaluated options; confidence " +
                "${formatProbability(distribution.confidence)} is the overall " +
                "sharpness, not a per-category guarantee.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        distribution.options.forEach { option ->
            val current = option.currentCategory
            OutlinedButton(
                onClick = { onChooseDistributionOption(option.categoryId) },
                enabled = enabled && current != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (current != null) {
                        "${current.name} — ${formatProbability(option.probability)}"
                    } else {
                        "${option.snapshotName} — ${formatProbability(option.probability)} (unavailable)"
                    },
                )
            }
        }
        OutlinedButton(
            onClick = onChooseNoCategory,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Unknown / no fit — ${formatProbability(distribution.unknownProbability)}")
        }
    }
}

/**
 * Expense / Income / Internal transfer: the selected type of one record.
 *
 * All three segments share a single content-driven height: the row measures the tallest
 * intrinsic label — on a narrow phone "Internal transfer" already fills two lines — and
 * every segment fills that height, so the selected checkmark, the borders and the touch
 * targets stay aligned at the top and the bottom instead of the two-line segment sticking
 * out of a row of shorter ones.
 */
@Composable
internal fun TransactionTypePicker(
    selectedType: TransactionType,
    onTypeChange: (TransactionType) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(
        modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min),
    ) {
        TransactionType.entries.forEachIndexed { index, type ->
            SegmentedButton(
                selected = type == selectedType,
                onClick = { onTypeChange(type) },
                modifier = Modifier.fillMaxHeight(),
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = TransactionType.entries.size,
                ),
                label = { Text(typeLabel(type)) },
            )
        }
    }
}

/**
 * The stored record after a successful save, read back from the database rather than from
 * the form, so the visible success is the persisted row's own values.
 */
@Composable
private fun SavedTransactionPanel(
    record: TransactionEntity,
    categoryName: String?,
    onRecordAnother: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Transaction saved",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
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
        DetailRow("Category", categoryName ?: "No category")
        DetailRow("Status", statusLabel(record.confirmation))
        DetailRow("Source", sourceLabel(record.source))
        DetailRow("Recorded at", ManualDateTime.formatMoment(record.createdAtEpochMillis))
        Text(
            text = "This is the stored record. Open Saved transactions from the home screen " +
                "to view, correct, or delete it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onRecordAnother,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Record another")
        }
        OutlinedButton(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Done")
        }
    }
}

/**
 * Single-choice category picker; "No category" keeps a manual record saveable.
 *
 * [retainedCategory] is the archived category the record being corrected already carries. It
 * is listed — marked archived — so the current filing is visible and can be kept, while no
 * other archived category is offered for a new assignment.
 *
 * The catalog is read page by page, so every stored category can be picked, not only the
 * ones on the first page. The chosen category is read by its own id and stays visible and
 * marked on every page, so walking the catalog never hides the current choice.
 */
@Composable
private fun CategoryPickerDialog(
    queryService: WorkflowQueryService,
    retainedCategory: CategoryEntity?,
    selectedCategoryId: Long?,
    onSelect: (Long?, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var pageIndex by rememberSaveable { mutableIntStateOf(0) }
    val categoryQuery = remember(pageIndex) {
        CategoryQuery(
            state = CategoryState.ACTIVE,
            bounds = QueryPageRequest(LIST_PAGE_SIZE, offset = pageIndex * LIST_PAGE_SIZE),
        )
    }
    val categoryPage: QueryPage<CategoryEntity>? by remember(queryService, categoryQuery) {
        queryService.observeCategories(categoryQuery)
    }.collectAsState(initial = null)
    val categories = categoryPage?.items
    // The chosen category is read by its own id, so it is named and marked on whichever
    // page of the catalog the picker is showing.
    val selectedRow: CategoryEntity? by remember(queryService, selectedCategoryId) {
        if (selectedCategoryId == null) {
            flowOf(null)
        } else {
            queryService.observeCategory(selectedCategoryId)
        }
    }.collectAsState(initial = null)
    // A page that lost its categories steps back to the nearest page that still holds
    // some, so the picker never shows an empty page in the middle of the catalog.
    ClampPageToRows(
        pageIndex = pageIndex,
        hasRows = categoryPage?.items?.isNotEmpty(),
        onClamp = { pageIndex = it },
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Category") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (categories == null) {
                    Text(
                        text = "Loading categories…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    PageControls(
                        pageIndex = pageIndex,
                        hasMore = categoryPage?.hasMore == true,
                        onPrevious = { pageIndex -= 1 },
                        onNext = { pageIndex += 1 },
                    )
                    CategoryOption(
                        text = "No category",
                        selected = selectedCategoryId == null,
                        onSelect = { onSelect(null, null) },
                    )
                    if (retainedCategory != null) {
                        CategoryOption(
                            text = categoryLabel(retainedCategory),
                            selected = retainedCategory.id == selectedCategoryId,
                            onSelect = {
                                onSelect(retainedCategory.id, retainedCategory.name)
                            },
                        )
                    }
                    val chosen = selectedRow
                    if (chosen != null && categories.none { it.id == chosen.id }) {
                        CategoryOption(
                            text = categoryLabel(chosen),
                            selected = true,
                            onSelect = { onSelect(chosen.id, chosen.name) },
                        )
                    }
                    categories.forEach { category ->
                        CategoryOption(
                            text = category.name,
                            selected = category.id == selectedCategoryId,
                            onSelect = { onSelect(category.id, category.name) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

@Composable
private fun CategoryOption(text: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
