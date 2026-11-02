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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.unit.sp
import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.CategoryQuery
import io.github.triet4p.aegisf.data.CategoryState
import io.github.triet4p.aegisf.data.CorrectTransactionRequest
import io.github.triet4p.aegisf.data.LIST_PAGE_SIZE
import io.github.triet4p.aegisf.data.QueryPage
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.LedgerValidationException
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
import io.github.triet4p.aegisf.data.refusalMessage
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

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
 * New expense creation writes only what the user states; nothing is classified on the device
 * and no suggestion changes the category field. Income, transfers, and correction forms are
 * equally manual. The live category list backs the picker, so a category renamed or archived
 * during an edit cannot remain eligible; an existing record keeps its archived category link
 * unless the user chooses another category.
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
    commandService: WorkflowCommandService,
    queryService: WorkflowQueryService,
    transactionId: Long?,
    onNavigateUp: () -> Unit,
    sourceEventId: Long? = null,
    modifier: Modifier = Modifier,
) {
    if (transactionId == null) {
        TransactionFormScreen(
            commandService = commandService,
            queryService = queryService,
            storedRecord = null,
            storedCategory = null,
            sourceEventId = sourceEventId,
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
            commandService = commandService,
            queryService = queryService,
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
    commandService: WorkflowCommandService,
    queryService: WorkflowQueryService,
    storedRecord: TransactionEntity?,
    storedCategory: CategoryEntity?,
    onNavigateUp: () -> Unit,
    sourceEventId: Long? = null,
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
    var savedRecordId by rememberSaveable { mutableStateOf<Long?>(null) }
    var savedRecord by remember { mutableStateOf<TransactionEntity?>(null) }

    // The key names this save request: the same request under the same key stores one
    // record, so a retry after a rotation or a restart cannot write the record twice.
    var saveKey by rememberSaveable { mutableStateOf(newSaveRequestKey()) }

    // The note this record is written from, when the user records an unmatched note by
    // hand. The saved record resolves that note, so a bank notification that arrives later
    // cannot consume the same occurrence again.
    var sourceEvent by remember(sourceEventId) {
        mutableStateOf<UnrecordedNoteEvent?>(null)
    }
    var sourceEventLoading by remember(sourceEventId) {
        mutableStateOf(sourceEventId != null)
    }
    var sourceEventLoadError by rememberSaveable(sourceEventId) {
        mutableStateOf<String?>(null)
    }
    var sourcePrefillApplied by rememberSaveable(sourceEventId) {
        mutableStateOf(false)
    }
    var notePickerVisible by rememberSaveable { mutableStateOf(false) }

    // The whole catalog, live and read in bounded chunks: an archive or a rename must be
    // visible at once, and the picker reads the catalog page by page so every stored
    // category is reachable there.
    val catalog: List<CategoryEntity>? by remember(queryService) {
        queryService.observeCategoryCatalog()
    }.collectAsState(initial = null)

    LaunchedEffect(queryService, sourceEventId) {
        val eventId = sourceEventId ?: return@LaunchedEffect
        sourceEventLoading = true
        sourceEventLoadError = null
        try {
            val note = queryService.unrecordedNoteEvent(eventId)
            if (note == null) {
                sourceEventLoadError =
                    "The saved event is no longer available for manual recording. " +
                        "Choose another saved event or cancel; no transaction was saved."
            } else {
                sourceEvent = note
                if (!sourcePrefillApplied) {
                    note.amountVnd?.let { amountText = it.toString() }
                    note.anchorDate?.let { dateText = it }
                    note.anchorTime?.let { timeText = it }
                    descriptionText = note.normalizedText.orEmpty()
                    sourcePrefillApplied = true
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            sourceEventLoadError =
                "The saved event could not be loaded. Your form is unchanged; retry or choose another note."
        } finally {
            sourceEventLoading = false
        }
    }

    // Kept as an id so the confirmation survives a configuration change: the record is
    // re-read from storage instead of being held in unsaveable state.
    LaunchedEffect(savedRecordId) {
        val id = savedRecordId
        savedRecord = if (id == null) null else queryService.findLedgerRecord(id)
    }

    val scope = rememberCoroutineScope()
    val writeInFlight = remember { AtomicBoolean(false) }

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
        if (sourceEventLoading) {
            saveError = "The saved event is still loading. No transaction was saved."
            writeInFlight.set(false)
            return
        }
        if (sourceEventId != null && sourceEvent == null && sourceEventLoadError != null) {
            saveError = sourceEventLoadError
            writeInFlight.set(false)
            return
        }

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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
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
                    val nowMillis = System.currentTimeMillis()
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
                    selectedType = it
                    categoryError = null
                },
                dateText = dateText,
                onDateChange = { dateText = it; dateError = null },
                dateError = dateError,
                timeText = timeText,
                onTimeChange = { timeText = it; timeError = null },
                timeError = timeError,
                descriptionText = descriptionText,
                onDescriptionChange = { descriptionText = it },
                categoryName = categoryButtonLabel,
                categoryId = selectedCategoryId,
                categoryArchived = currentSelectedCategory?.archived
                    ?: (storedCategory?.archived == true &&
                        selectedCategoryId == storedCategory.id),
                categoryNote = categoryNote,
                categoryError = categoryError,
                onPickCategory = { categoryPickerVisible = true },
                onChooseNoCategory = {
                    selectedCategoryId = null
                    selectedCategoryName = null
                    categoryError = null
                },
                sourceEvent = sourceEvent,
                sourceEventLoading = sourceEventLoading,
                sourceEventLoadError = sourceEventLoadError,
                onPickNote = { notePickerVisible = true },
                onClearNote = {
                    sourceEvent = null
                    sourceEventLoadError = null
                },
                saveError = saveError,
                saving = saving || sourceEventLoading,
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
                selectedCategoryId = id
                selectedCategoryName = name
                categoryError = null
                categoryPickerVisible = false
            },
            onDismiss = { categoryPickerVisible = false },
        )
    }

    if (notePickerVisible) {
        NoteEventPickerDialog(
            queryService = queryService,
            onSelect = { note ->
                // Selecting a saved event fills the fields it actually states; every value
                // remains editable and nothing is written until the user presses Save.
                sourceEvent = note
                sourceEventLoadError = null
                sourcePrefillApplied = true
                note.amountVnd?.let { amount -> amountText = amount.toString() }
                note.anchorDate?.let { day -> dateText = day }
                note.anchorTime?.let { time -> timeText = time }
                descriptionText = note.normalizedText.orEmpty()
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
                            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { onSelect(note) },
                            modifier = Modifier.fillMaxWidth(),) { Text(noteLabel(note)) }
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
        title = {
            Text(
                title.uppercase(Locale.ROOT),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
            )
        },
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
    categoryId: Long?,
    categoryArchived: Boolean,
    categoryNote: String?,
    categoryError: String?,
    onPickCategory: () -> Unit,
    onChooseNoCategory: () -> Unit,
    sourceEvent: UnrecordedNoteEvent?,
    sourceEventLoading: Boolean,
    sourceEventLoadError: String?,
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
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
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
            textStyle = MaterialTheme.typography.headlineSmall.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            ),
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
        Text(
            text = when (selectedType) {
                TransactionType.EXPENSE -> "Money leaving · counted as spending after save"
                TransactionType.INCOME -> "Money arriving · kept separate from spending"
                TransactionType.TRANSFER -> "Between your own accounts · neither spend nor income"
            },
            style = MaterialTheme.typography.bodySmall,
            color = when (selectedType) {
                TransactionType.EXPENSE -> MaterialTheme.colorScheme.error
                TransactionType.INCOME -> MaterialTheme.colorScheme.secondary
                TransactionType.TRANSFER -> MaterialTheme.colorScheme.primary
            },
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
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = onPickCategory,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),) { Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CategorySwatch(
                    categoryId = categoryId,
                    archived = categoryArchived,
                    directory = true,
                )
                Text(
                    categoryName?.removeSuffix(" (archived)")
                        ?: "Select category (optional)",
                    modifier = Modifier.weight(1f),
                )
                if (categoryArchived) {
                    ArchivedCategoryBadge()
                }
            } }
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
        if (!editing) {
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = onPickNote,
            enabled = !saving,
            modifier = Modifier.fillMaxWidth(),) { Text("Record from a saved note") }
            if (sourceEventLoading) {
                Text("Loading the selected saved event before manual recording…")
            }
            sourceEventLoadError?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
            }
            if (sourceEvent != null) {
                Text(
                    "Provenance reference: saved note #${sourceEvent.captureId}, " +
                        "event #${sourceEvent.eventId}.",
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = "This record will resolve the saved event “${noteLabel(sourceEvent)}”. " +
                        "It is only saved after you press Save transaction; a later bank " +
                        "notification will not be matched to that event again.",
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

        Button(shape = MaterialTheme.shapes.small, onClick = onSave,
        enabled = !saving,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),) { Text(
            text = when {
                saving -> "Saving…"
                editing -> "Save changes"
                else -> "Save transaction"
            },
        ) }
        OutlinedButton(shape = MaterialTheme.shapes.small, onClick = onCancel,
        enabled = !saving,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),) { Text("Cancel") }
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
                shape = MaterialTheme.shapes.small,
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
            text = "This is the stored record. Open Saved transactions in the Manual " +
                "destination to view, correct, or delete it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(shape = MaterialTheme.shapes.small, onClick = onRecordAnother,
        modifier = Modifier.fillMaxWidth(),) { Text("Record another") }
        OutlinedButton(shape = MaterialTheme.shapes.small, onClick = onDone,
        modifier = Modifier.fillMaxWidth(),) { Text("Done") }
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
                            text = retainedCategory.name,
                            categoryId = retainedCategory.id,
                            archived = retainedCategory.archived,
                            selected = retainedCategory.id == selectedCategoryId,
                            onSelect = {
                                onSelect(retainedCategory.id, retainedCategory.name)
                            },
                        )
                    }
                    val chosen = selectedRow
                    if (chosen != null && categories.none { it.id == chosen.id }) {
                        CategoryOption(
                            text = chosen.name,
                            categoryId = chosen.id,
                            archived = chosen.archived,
                            selected = true,
                            onSelect = { onSelect(chosen.id, chosen.name) },
                        )
                    }
                    categories.forEach { category ->
                        CategoryOption(
                            text = category.name,
                            categoryId = category.id,
                            archived = category.archived,
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
private fun CategoryOption(
    text: String,
    categoryId: Long? = null,
    archived: Boolean = false,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        CategorySwatch(
            categoryId = categoryId,
            archived = archived,
            directory = true,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        )
        if (archived) {
            ArchivedCategoryBadge()
        }
    }
}
