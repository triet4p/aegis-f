package io.github.triet4p.aegisf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.CorrectTransactionRequest
import io.github.triet4p.aegisf.data.LIST_PAGE_SIZE
import io.github.triet4p.aegisf.data.LedgerValidationException
import io.github.triet4p.aegisf.data.ManualTransactionSource
import io.github.triet4p.aegisf.data.QueryPageRequest
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
import io.github.triet4p.aegisf.ui.components.AegisDiscardChangesDialog
import io.github.triet4p.aegisf.ui.components.AegisGlyph
import io.github.triet4p.aegisf.ui.components.AegisIcon
import io.github.triet4p.aegisf.ui.components.AegisMoneyText
import io.github.triet4p.aegisf.ui.components.AegisStatus
import io.github.triet4p.aegisf.ui.components.AegisStatusChip
import io.github.triet4p.aegisf.ui.text.AegisStrings
import io.github.triet4p.aegisf.ui.text.LocalAegisStrings
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Manual transaction editor: one focused form, one Save.
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
 * The screen owns one task and one primary action: the sticky Save bar. Amount, type, category,
 * moment, note, and the optional saved-note source are the record's supported fields; the
 * category, moment, and saved-note choosers are focused bottom sheets, never stacked panels.
 * Leaving without Save — close, Back, or the system back gesture — writes nothing and asks
 * before discarding typed values.
 *
 * A record may be written from a saved note the user recorded by hand: the note event is
 * named, the note's own amount, moment, and text fill the form, and the saved record resolves
 * that note — so a bank notification that arrives later cannot consume the same occurrence
 * again. A note that was already recorded or changed under the form is refused with the typed
 * values intact.
 *
 * While a write is in flight the guard ignores further taps and the form cannot be left, so an
 * impatient double tap cannot turn one record into two or into none.
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

    val strings = manualRecordStrings(LocalAegisStrings.current.language)
    var loadToken by remember { mutableIntStateOf(0) }
    var loaded by remember(transactionId, loadToken) {
        mutableStateOf<StoredTransactionState>(StoredTransactionState.Loading)
    }
    LaunchedEffect(transactionId, loadToken) {
        loaded = StoredTransactionState.Loading
        loaded = try {
            val record = queryService.findLedgerRecord(transactionId)
            if (record == null) {
                StoredTransactionState.Missing
            } else {
                StoredTransactionState.Found(
                    record = record,
                    category = record.categoryId?.let { queryService.findCategory(it) },
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            StoredTransactionState.Error
        }
    }

    when (val stored = loaded) {
        StoredTransactionState.Loading -> ManualStateScaffold(
            title = strings.loadingTitle,
            strings = strings,
            onClose = onNavigateUp,
            modifier = modifier,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp))
                Text(strings.loadingBody, style = MaterialTheme.typography.bodyLarge)
            }
        }

        StoredTransactionState.Missing -> ManualStateScaffold(
            title = strings.loadingTitle,
            strings = strings,
            onClose = onNavigateUp,
            modifier = modifier,
        ) {
            Text(strings.missingTitle, style = MaterialTheme.typography.titleMedium)
            Text(
                text = strings.missingBody,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onNavigateUp) { Text(strings.close) }
        }

        StoredTransactionState.Error -> ManualStateScaffold(
            title = strings.loadingTitle,
            strings = strings,
            onClose = onNavigateUp,
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
                TextButton(onClick = { loadToken += 1 }) { Text(strings.retry) }
                TextButton(onClick = onNavigateUp) { Text(strings.close) }
            }
        }

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

    data object Error : StoredTransactionState

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
    val baseStrings = LocalAegisStrings.current
    val strings = manualRecordStrings(baseStrings.language)
    val editing = storedRecord != null
    val zone = remember { TimeZone.getDefault() }
    val initialMomentMillis = remember(storedRecord) {
        storedRecord?.occurredAtEpochMillis ?: System.currentTimeMillis()
    }
    var amountText by rememberSaveable {
        mutableStateOf(storedRecord?.amountVnd?.toString() ?: "")
    }
    var selectedType by rememberSaveable {
        mutableStateOf(storedRecord?.type ?: TransactionType.EXPENSE)
    }
    var dateText by rememberSaveable {
        mutableStateOf(ManualDateTime.dateFieldText(initialMomentMillis, zone))
    }
    var timeText by rememberSaveable {
        mutableStateOf(ManualDateTime.timeFieldText(initialMomentMillis, zone))
    }
    var descriptionText by rememberSaveable { mutableStateOf(storedRecord?.description ?: "") }
    var selectedCategoryId by rememberSaveable { mutableStateOf(storedRecord?.categoryId) }
    var selectedCategoryName by rememberSaveable { mutableStateOf(storedCategory?.name) }

    var categorySheetVisible by rememberSaveable { mutableStateOf(false) }
    var momentSheetVisible by rememberSaveable { mutableStateOf(false) }
    var noteSheetVisible by rememberSaveable { mutableStateOf(false) }

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
    // cannot consume the same occurrence again. The event id and reading revision are the
    // saveable identity; the loaded row itself is read back after a configuration change.
    var chosenSourceEventId by rememberSaveable(sourceEventId) {
        mutableStateOf(sourceEventId)
    }
    var chosenSourceRevision by rememberSaveable(sourceEventId) {
        mutableStateOf<Int?>(null)
    }
    var sourceEvent by remember { mutableStateOf<UnrecordedNoteEvent?>(null) }
    var sourceEventLoading by remember {
        mutableStateOf(chosenSourceEventId != null)
    }
    var sourceEventLoadError by rememberSaveable(sourceEventId) {
        mutableStateOf<String?>(null)
    }
    var sourcePrefillApplied by rememberSaveable(sourceEventId) {
        mutableStateOf(false)
    }
    var sourceLoadToken by remember { mutableIntStateOf(0) }

    // Whether the user has changed anything on this form, and whether leaving it is waiting
    // for the discard confirmation. Values prefilled from a saved note are not edits until
    // the user changes something.
    var editedSinceOpen by rememberSaveable { mutableStateOf(false) }
    var discardPending by rememberSaveable { mutableStateOf(false) }

    /**
     * Leaves the form only when it holds nothing the user would lose, or after an explicit
     * discard. A write in flight is never interrupted.
     */
    fun requestLeave() {
        if (saving) return
        if (editedSinceOpen) {
            discardPending = true
        } else {
            onNavigateUp()
        }
    }

    // The whole live catalog: an archive, rename, or deletion must be visible at once, and
    // the chooser can reach every stored category without paging.
    val catalog: List<CategoryEntity>? by remember(queryService) {
        queryService.observeCategoryCatalog()
    }.collectAsState(initial = null)

    LaunchedEffect(
        queryService,
        chosenSourceEventId,
        chosenSourceRevision,
        sourceLoadToken,
    ) {
        val eventId = chosenSourceEventId ?: run {
            sourceEventLoading = false
            return@LaunchedEffect
        }
        // A row already loaded for this exact reading needs no second read; this also stops
        // the revision learned from the note from restarting the effect once.
        if (sourceEvent?.let { it.eventId == eventId && it.revision == chosenSourceRevision } == true) {
            sourceEventLoading = false
            return@LaunchedEffect
        }
        sourceEventLoading = true
        sourceEventLoadError = null
        try {
            val note = queryService.unrecordedNoteEvent(eventId)
            if (note == null) {
                sourceEvent = null
                sourceEventLoadError = strings.sourceLoadError
            } else {
                sourceEvent = note
                chosenSourceRevision = note.revision
                if (!sourcePrefillApplied && !editedSinceOpen) {
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
            sourceEvent = null
            sourceEventLoadError = strings.sourceLoadError
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

    BackHandler { requestLeave() }

    val selectedCategoryRow = catalog?.firstOrNull { it.id == selectedCategoryId }
        ?: storedCategory?.takeIf { it.id == selectedCategoryId }
    val categoryNote = when {
        selectedCategoryRow?.archived == true && selectedCategoryId != storedRecord?.categoryId ->
            strings.categoryArchivedNow.format(selectedCategoryRow.name)
        catalog != null && selectedCategoryId != null && selectedCategoryRow == null ->
            strings.categoryDeletedNow
        storedCategory != null && storedCategory.archived &&
            selectedCategoryId == storedCategory.id ->
            strings.categoryRetained.format(storedCategory.name)
        else -> null
    }
    val parsedAmount = parseManualAmountOrNull(amountText)
    val amountWords = parsedAmount?.let { amountInWords(it, baseStrings.language) }
    val momentDisplay = remember(dateText, timeText, zone, baseStrings.language) {
        momentDisplayText(dateText, timeText, zone)
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
                when (outcome.field) {
                    "categoryId" -> categoryError = strings.categoryUnavailable
                    "amountVnd" -> amountError = strings.amountInvalid
                    "occurredAtEpochMillis" -> {
                        dateError = strings.dateError
                        timeError = strings.timeError
                    }
                    else -> saveError = strings.saveError
                }
            }

            is WorkflowCommandOutcome.Denied -> {
                when (outcome.reason) {
                    WorkflowDenialReason.CATEGORY_UNAVAILABLE ->
                        categoryError = strings.categoryUnavailable
                    WorkflowDenialReason.EVENT_UNAVAILABLE ->
                        sourceEventLoadError = strings.eventUnavailable
                    else -> saveError = strings.saveError
                }
            }

            is WorkflowCommandOutcome.Conflict ->
                saveError = if (editing) strings.staleRecord else strings.staleEvent

            is WorkflowCommandOutcome.Failed -> saveError = strings.saveError
            WorkflowCommandOutcome.NotFound ->
                saveError = if (editing) strings.recordMissingSave else strings.saveError
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
            saveError = strings.sourceLoading
            writeInFlight.set(false)
            return
        }
        if (chosenSourceEventId != null && sourceEvent == null) {
            saveError = sourceEventLoadError ?: strings.sourceLoadError
            writeInFlight.set(false)
            return
        }

        val amountVnd = try {
            VndMoney.parseManualAmount(amountText)
        } catch (rejection: LedgerValidationException) {
            amountError = if (amountText.isBlank()) {
                strings.amountRequired
            } else {
                strings.amountInvalid
            }
            writeInFlight.set(false)
            return
        }
        val dateProblem = ManualDateTime.dateError(dateText)
        val timeProblem = ManualDateTime.timeError(timeText)
        if (dateProblem != null || timeProblem != null) {
            dateError = if (dateProblem != null) strings.dateError else null
            timeError = if (timeProblem != null) strings.timeError else null
            writeInFlight.set(false)
            return
        }

        saving = true
        scope.launch {
            try {
                val type = selectedType
                val occurredAtEpochMillis = ManualDateTime.toEpochMillis(dateText, timeText, zone)
                val description = descriptionText.trim()
                // A transfer carries no category, so the type decides whether the chosen one
                // is written at all; the stored category is otherwise kept as it is.
                val categoryId = selectedCategoryId.takeIf { type != TransactionType.TRANSFER }
                val stored = storedRecord
                if (stored == null) {
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
                            // The record is stored: there is no unsaved edit left to protect.
                            editedSinceOpen = false
                        }

                        is WorkflowCommandOutcome.NoChange -> {
                            // The same request was already made under this key: the record
                            // that was made then is the record this form is about.
                            savedRecordId = (outcome.change as? WorkflowChange.TransactionSaved)
                                ?.transactionId
                            editedSinceOpen = false
                        }

                        else -> showRefusal(outcome)
                    }
                } else {
                    val outcome = commandService.correctTransaction(
                        scope = WorkflowCallerScope.User,
                        request = CorrectTransactionRequest(
                            idempotencyKey = saveKey,
                            transactionId = stored.id,
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
                            editedSinceOpen = false
                            onNavigateUp()
                        }

                        else -> showRefusal(outcome)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A closed or unreadable database must not look like a successful write. The
                // typed values stay on the form, and pressing Save again is the retry.
                saveError = strings.saveError
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
                title = when {
                    savedRecord != null -> strings.savedTitle
                    editing -> strings.titleEdit
                    else -> strings.titleNew
                },
                closeLabel = strings.close,
                editing = editing,
                onClose = ::requestLeave,
                closeEnabled = !saving,
            )
        },
        bottomBar = {
            if (savedRecord == null) {
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
                            onClick = {
                                // Checked before the coroutine starts, so two taps inside one
                                // frame still produce a single write.
                                if (writeInFlight.compareAndSet(false, true)) save()
                            },
                            enabled = !saving && !(chosenSourceEventId != null &&
                                sourceEvent == null),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .testTag("manual-save"),
                        ) {
                            Text(
                                text = when {
                                    saving -> strings.saving
                                    editing -> strings.saveEdit
                                    else -> strings.saveNew
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
        val saved = savedRecord
        if (saved != null) {
            SavedTransactionPanel(
                record = saved,
                categoryName = selectedCategoryName,
                strings = strings,
                baseStrings = baseStrings,
                zone = zone,
                onRecordAnother = {
                    val nowMillis = System.currentTimeMillis()
                    amountText = ""
                    selectedType = TransactionType.EXPENSE
                    dateText = ManualDateTime.dateFieldText(nowMillis, zone)
                    timeText = ManualDateTime.timeFieldText(nowMillis, zone)
                    descriptionText = ""
                    selectedCategoryId = null
                    selectedCategoryName = null
                    savedRecordId = null
                    savedRecord = null
                    // A further record is a further request: it gets its own key, and it
                    // records no note until the user names one again.
                    saveKey = newSaveRequestKey()
                    chosenSourceEventId = null
                    chosenSourceRevision = null
                    sourceEvent = null
                    sourceEventLoadError = null
                    editedSinceOpen = false
                    discardPending = false
                },
                onDone = onNavigateUp,
                modifier = Modifier.padding(innerPadding),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                HeroAmountCard(
                    amountText = amountText,
                    onAmountChange = {
                        amountText = it
                        amountError = null
                        editedSinceOpen = true
                    },
                    amountError = amountError,
                    amountWords = amountWords,
                    strings = strings,
                    scope = scope,
                )

                TransactionTypePicker(
                    selectedType = selectedType,
                    onTypeChange = {
                        selectedType = it
                        categoryError = null
                        editedSinceOpen = true
                    },
                    baseStrings = baseStrings,
                )
                if (selectedType == TransactionType.TRANSFER) {
                    Text(
                        text = strings.transferNoCategory,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = if (selectedType == TransactionType.EXPENSE) {
                            strings.typeExpenseHelp
                        } else {
                            strings.typeIncomeHelp
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CategorySection(
                        selectedCategoryId = selectedCategoryId,
                        selectedCategoryRow = selectedCategoryRow,
                        catalog = catalog,
                        strings = strings,
                        archivedLabel = baseStrings.archivedLabel,
                        onSelect = { category ->
                            selectedCategoryId = category.id
                            selectedCategoryName = category.name
                            categoryError = null
                            editedSinceOpen = true
                        },
                        onChooseNone = {
                            selectedCategoryId = null
                            selectedCategoryName = null
                            categoryError = null
                            editedSinceOpen = true
                        },
                        onOpenAll = { categorySheetVisible = true },
                        note = categoryNote,
                        error = categoryError,
                    )
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = strings.detailsSection.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        MomentRow(
                            label = strings.momentLabel,
                            value = momentDisplay,
                            onClick = { momentSheetVisible = true },
                        )
                        if (dateError != null || timeError != null) {
                            Text(
                                text = listOfNotNull(dateError, timeError).joinToString(" "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        OutlinedTextField(
                            value = descriptionText,
                            onValueChange = {
                                descriptionText = it
                                editedSinceOpen = true
                            },
                            label = { Text(strings.memoLabel) },
                            placeholder = { Text(strings.memoPlaceholder) },
                            minLines = 2,
                            maxLines = 4,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                            ),
                            supportingText = { Text(strings.memoHelp) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("manual-memo")
                                .focusedFieldBringIntoView(scope),
                        )
                    }
                }

                if (!editing) {
                    SourceSection(
                        sourceEvent = sourceEvent,
                        chosenSourceEventId = chosenSourceEventId,
                        loading = sourceEventLoading,
                        error = sourceEventLoadError,
                        strings = strings,
                        zone = zone,
                        onChoose = { noteSheetVisible = true },
                        onClear = {
                            chosenSourceEventId = null
                            chosenSourceRevision = null
                            sourceEvent = null
                            sourceEventLoadError = null
                            editedSinceOpen = true
                        },
                        onRetry = { sourceLoadToken += 1 },
                    )
                }

                if (saveError != null) {
                    Text(
                        text = saveError.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                // The sticky bar is the only primary action; this spacer keeps the last
                // field clear of it while scrolling.
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }

    if (discardPending) {
        AegisDiscardChangesDialog(
            onKeepEditing = { discardPending = false },
            onDiscard = onNavigateUp,
        )
    }

    if (categorySheetVisible) {
        CategoryChoiceSheet(
            catalog = catalog,
            selectedCategoryId = selectedCategoryId,
            retainedCategory = storedCategory,
            strings = strings,
            archivedLabel = baseStrings.archivedLabel,
            onSelect = { id, name ->
                selectedCategoryId = id
                selectedCategoryName = name
                categoryError = null
                categorySheetVisible = false
                editedSinceOpen = true
            },
            onDismiss = { categorySheetVisible = false },
        )
    }

    if (momentSheetVisible) {
        MomentSheet(
            dateText = dateText,
            timeText = timeText,
            strings = strings,
            zone = zone,
            onApply = { day, time ->
                dateText = day
                timeText = time
                dateError = null
                timeError = null
                momentSheetVisible = false
                editedSinceOpen = true
            },
            onDismiss = { momentSheetVisible = false },
        )
    }

    if (noteSheetVisible) {
        NoteEventSheet(
            queryService = queryService,
            strings = strings,
            zone = zone,
            onSelect = { note ->
                // Selecting a saved event fills the fields it actually states; every value
                // remains editable and nothing is written until the user presses Save.
                sourceEvent = note
                sourceEventLoadError = null
                chosenSourceEventId = note.eventId
                chosenSourceRevision = note.revision
                sourcePrefillApplied = true
                note.amountVnd?.let { amount -> amountText = amount.toString() }
                note.anchorDate?.let { day -> dateText = day }
                note.anchorTime?.let { time -> timeText = time }
                descriptionText = note.normalizedText.orEmpty()
                noteSheetVisible = false
                editedSinceOpen = true
            },
            onDismiss = { noteSheetVisible = false },
        )
    }
}

/**
 * The hero amount: one large field on its own card, with the exact amount written out below.
 *
 * The field accepts the hardware/software number keyboard. Digits are the stored value; the
 * words are only a reading of them. A refused amount says why under the same card, and the
 * typed digits stay in place.
 */
@Composable
private fun HeroAmountCard(
    amountText: String,
    onAmountChange: (String) -> Unit,
    amountError: String?,
    amountWords: String?,
    strings: ManualRecordStrings,
    scope: CoroutineScope,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = strings.amountSection.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            BasicTextField(
                value = amountText,
                onValueChange = onAmountChange,
                textStyle = MaterialTheme.typography.displaySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .testTag("manual-amount")
                    .semantics { contentDescription = strings.amountSection }
                    .focusedFieldBringIntoView(scope),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (amountText.isEmpty()) {
                            Text(
                                text = strings.amountPlaceholder,
                                style = MaterialTheme.typography.displaySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                                textAlign = TextAlign.Center,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            Text(
                text = amountError
                    ?: amountWords
                    ?: strings.amountHint,
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    amountError != null -> MaterialTheme.colorScheme.error
                    amountWords != null -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The quick category grid plus the route to every stored category. */
@Composable
private fun CategorySection(
    selectedCategoryId: Long?,
    selectedCategoryRow: CategoryEntity?,
    catalog: List<CategoryEntity>?,
    strings: ManualRecordStrings,
    archivedLabel: String,
    onSelect: (CategoryEntity) -> Unit,
    onChooseNone: () -> Unit,
    onOpenAll: () -> Unit,
    note: String?,
    error: String?,
) {
    val activeCategories = catalog?.filterNot { it.archived }.orEmpty()
    val visible = mutableListOf<CategoryEntity>()
    activeCategories.take(6).let(visible::addAll)
    if (selectedCategoryRow != null && visible.none { it.id == selectedCategoryRow.id }) {
        visible.add(0, selectedCategoryRow)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = strings.categorySection.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            catalog == null -> Text(
                text = strings.categoryLoading,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            visible.isEmpty() -> Text(
                text = strings.categoryEmpty,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> {
                visible.chunked(2).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { category ->
                            CategoryTile(
                                category = category,
                                selected = category.id == selectedCategoryId,
                                archivedLabel = archivedLabel,
                                onClick = { onSelect(category) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (row.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onOpenAll,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("manual-category-all"),
            ) { Text(strings.categoryAll) }
            if (selectedCategoryId != null) {
                TextButton(
                    onClick = onChooseNone,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(strings.categoryClear) }
            }
        }
        Text(
            text = note ?: strings.categoryOptional,
            style = MaterialTheme.typography.bodySmall,
            color = if (note != null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun CategoryTile(
    category: CategoryEntity,
    selected: Boolean,
    archivedLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentDescription = buildString {
        append(category.name)
        if (category.archived) append(", ").append(archivedLabel)
    }
    Surface(
        modifier = modifier
            .defaultMinSize(minHeight = 56.dp)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription },
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        },
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(
                        color = CategoryPalette.color(
                            categoryId = category.id,
                            darkTheme = androidx.compose.foundation.isSystemInDarkTheme(),
                            archived = category.archived,
                            directory = true,
                        ).copy(alpha = 0.18f),
                        shape = RoundedCornerShape(9.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                CategorySwatch(
                    categoryId = category.id,
                    archived = category.archived,
                    directory = true,
                    size = 14.dp,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = category.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (category.archived) {
                    Text(
                        text = archivedLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (selected) {
                AegisIcon(
                    glyph = AegisGlyph.CHECK,
                    tint = MaterialTheme.colorScheme.primary,
                    size = 18.dp,
                )
            }
        }
    }
}

/** The date and time row: one focused value that opens its own sheet. */
@Composable
private fun MomentRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { contentDescription = "$label: $value" }
            .testTag("manual-moment"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AegisIcon(
                glyph = AegisGlyph.CHEVRON_RIGHT,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 18.dp,
            )
        }
    }
}

/** The optional saved-note source, shown as one card with its own chooser. */
@Composable
private fun SourceSection(
    sourceEvent: UnrecordedNoteEvent?,
    chosenSourceEventId: Long?,
    loading: Boolean,
    error: String?,
    strings: ManualRecordStrings,
    zone: TimeZone,
    onChoose: () -> Unit,
    onClear: () -> Unit,
    onRetry: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = strings.sourceSection.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                loading -> Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    Text(strings.sourceLoading, style = MaterialTheme.typography.bodyMedium)
                }

                sourceEvent != null -> {
                    Text(
                        text = noteLabel(sourceEvent, zone),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = strings.sourceSelectedHelp,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = onChoose,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(strings.sourceChange) }
                        TextButton(
                            onClick = onClear,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(strings.sourceClear) }
                    }
                }

                else -> {
                    if (error != null) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (chosenSourceEventId != null) {
                        Text(
                            text = strings.sourceEmpty,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = strings.sourceHelp,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onChoose,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("manual-source-choose"),
                        ) { Text(strings.sourceChoose) }
                        if (error != null) {
                            TextButton(
                                onClick = onRetry,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text(strings.retry) }
                        }
                        if (chosenSourceEventId != null) {
                            TextButton(
                                onClick = onClear,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text(strings.sourceClear) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The category chooser over the whole live catalog.
 *
 * "No category" keeps a manual record saveable. An archived category the record already
 * carries is listed and marked so the existing filing is visible and can be kept, while no
 * other archived category is offered for a new assignment.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryChoiceSheet(
    catalog: List<CategoryEntity>?,
    selectedCategoryId: Long?,
    retainedCategory: CategoryEntity?,
    strings: ManualRecordStrings,
    archivedLabel: String,
    onSelect: (Long?, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .testTag("manual-category-sheet"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(strings.categorySheetTitle, style = MaterialTheme.typography.titleMedium)
            Text(
                text = strings.categorySheetHelp,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CategoryChoiceRow(
                name = strings.categoryNone,
                archived = false,
                archivedLabel = archivedLabel,
                selected = selectedCategoryId == null,
                onSelect = { onSelect(null, null) },
            )
            when {
                catalog == null -> Text(
                    text = strings.categoryLoading,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )

                catalog.isEmpty() -> Text(
                    text = strings.categorySheetEmpty,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )

                else -> {
                    val retained = retainedCategory?.takeIf { it.archived }
                    val active = catalog.filterNot { it.archived }
                    if (retained == null && active.isEmpty()) {
                        Text(
                            text = strings.categorySheetEmpty,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    } else {
                        if (retained != null) {
                            CategoryChoiceRow(
                                name = retained.name,
                                archived = true,
                                archivedLabel = archivedLabel,
                                selected = retained.id == selectedCategoryId,
                                onSelect = { onSelect(retained.id, retained.name) },
                            )
                        }
                        active.forEach { category ->
                            CategoryChoiceRow(
                                name = category.name,
                                archived = false,
                                archivedLabel = archivedLabel,
                                selected = category.id == selectedCategoryId,
                                onSelect = { onSelect(category.id, category.name) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryChoiceRow(
    name: String,
    archived: Boolean,
    archivedLabel: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (archived) {
            ArchivedCategoryBadge(label = archivedLabel)
        }
    }
}

/** The focused date/time sheet; applying a well-formed moment changes nothing by itself. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MomentSheet(
    dateText: String,
    timeText: String,
    strings: ManualRecordStrings,
    zone: TimeZone,
    onApply: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var date by rememberSaveable { mutableStateOf(dateText) }
    var time by rememberSaveable { mutableStateOf(timeText) }
    val scope = rememberCoroutineScope()
    val dateProblem = ManualDateTime.dateError(date)
    val timeProblem = ManualDateTime.timeError(time)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .testTag("manual-moment-sheet"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(strings.momentSheetTitle, style = MaterialTheme.typography.titleMedium)
            Text(
                text = strings.momentSheetHelp,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = date,
                onValueChange = { date = it },
                label = { Text(strings.dateLabel) },
                placeholder = { Text(ManualDateTime.DATE_FORMAT) },
                singleLine = true,
                isError = dateProblem != null,
                supportingText = {
                    Text(if (dateProblem != null) strings.dateError else ManualDateTime.DATE_FORMAT)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("manual-date")
                    .focusedFieldBringIntoView(scope),
            )
            OutlinedTextField(
                value = time,
                onValueChange = { time = it },
                label = { Text(strings.timeLabel) },
                placeholder = { Text(ManualDateTime.TIME_FORMAT) },
                singleLine = true,
                isError = timeProblem != null,
                supportingText = {
                    Text(if (timeProblem != null) strings.timeError else ManualDateTime.TIME_FORMAT)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("manual-time")
                    .focusedFieldBringIntoView(scope),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val nowMillis = System.currentTimeMillis()
                TextButton(
                    onClick = { date = ManualDateTime.dateFieldText(nowMillis, zone) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(strings.momentToday) }
                TextButton(
                    onClick = { time = ManualDateTime.timeFieldText(nowMillis, zone) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(strings.momentNow) }
            }
            Button(
                onClick = { onApply(date, time) },
                enabled = dateProblem == null && timeProblem == null,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag("manual-moment-apply"),
            ) { Text(strings.momentApply) }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) { Text(strings.momentCancel) }
        }
    }
}

/** The focused chooser of saved notes still waiting to be recorded by hand. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteEventSheet(
    queryService: WorkflowQueryService,
    strings: ManualRecordStrings,
    zone: TimeZone,
    onSelect: (UnrecordedNoteEvent) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var notes by remember { mutableStateOf<List<UnrecordedNoteEvent>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(queryService) {
        notes = try {
            queryService.unrecordedNoteEvents(QueryPageRequest(LIST_PAGE_SIZE)).items
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
            null
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .testTag("manual-source-sheet"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(strings.sourceSheetTitle, style = MaterialTheme.typography.titleMedium)
            Text(
                text = strings.sourceSheetHelp,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val loaded = notes
            when {
                failed -> Text(
                    text = strings.sourceLoadError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )

                loaded == null -> Text(
                    text = strings.sourceLoading,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                loaded.isEmpty() -> Text(
                    text = strings.sourceEmpty,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> loaded.forEach { note ->
                    OutlinedButton(
                        onClick = { onSelect(note) },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text(
                            text = noteLabel(note, zone),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Start,
                        )
                    }
                }
            }
        }
    }
}

/** How one saved note is named in the chooser and on its source card. */
private fun noteLabel(note: UnrecordedNoteEvent, zone: TimeZone): String {
    val reading = note.normalizedText ?: note.captureText ?: ""
    val amount = note.amountVnd?.let { VndMoney.formatAmount(it, " ₫") }
    val date = note.anchorDate?.let { text ->
        runCatching { ManualDateTime.dayStartEpochMillis(text, zone) }
            .getOrNull()
            ?.let { dayMonthYearText(it, zone) }
            ?: text
    }
    return listOfNotNull(
        reading.takeIf { it.isNotBlank() },
        amount,
        date,
        note.anchorTime,
    ).joinToString(" · ")
}

/** A key that names one save request; the same key stores one record. */
private fun newSaveRequestKey(): String = "manual-save:" + UUID.randomUUID()

/** The amount stored by the form, or null when the text is not an exact positive amount. */
private fun parseManualAmountOrNull(text: String): Long? = try {
    VndMoney.parseManualAmount(text)
} catch (_: LedgerValidationException) {
    null
}

/** The moment as the form shows it, falling back to the two field texts if they are invalid. */
private fun momentDisplayText(dateText: String, timeText: String, zone: TimeZone): String {
    val millis = runCatching {
        ManualDateTime.toEpochMillis(dateText, timeText, zone)
    }.getOrNull() ?: return "$dateText · $timeText"
    return dayMonthYearText(millis, zone) + " · " + ManualDateTime.timeFieldText(millis, zone)
}

/** The top bar of every editor state: one title and one close/back control. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorTopBar(
    title: String,
    closeLabel: String,
    editing: Boolean,
    onClose: () -> Unit,
    closeEnabled: Boolean = true,
) {
    CenterAlignedTopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            IconButton(
                onClick = onClose,
                enabled = closeEnabled,
                modifier = Modifier.semantics { contentDescription = closeLabel },
            ) {
                AegisIcon(
                    glyph = if (editing) AegisGlyph.ARROW_BACK else AegisGlyph.CROSS,
                    tint = MaterialTheme.colorScheme.onSurface,
                    size = 22.dp,
                )
            }
        },
    )
}

/** A loading, missing, or unreadable record, with one way to leave it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualStateScaffold(
    title: String,
    strings: ManualRecordStrings,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BackHandler(onBack = onClose)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            EditorTopBar(
                title = title,
                closeLabel = strings.close,
                editing = true,
                onClose = onClose,
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            content()
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
    strings: ManualRecordStrings,
    baseStrings: AegisStrings,
    zone: TimeZone,
    onRecordAnother: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = strings.savedTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                AegisMoneyText(
                    amountVnd = record.amountVnd,
                    prefix = amountPrefix(record.type),
                    style = MaterialTheme.typography.headlineSmall,
                )
                AegisStatusChip(
                    status = if (record.confirmation ==
                        io.github.triet4p.aegisf.data.ConfirmationStatus.CONFIRMED
                    ) {
                        AegisStatus.CONFIRMED
                    } else {
                        AegisStatus.NEEDS_REVIEW
                    },
                    label = if (record.confirmation ==
                        io.github.triet4p.aegisf.data.ConfirmationStatus.CONFIRMED
                    ) {
                        baseStrings.statusConfirmed
                    } else {
                        baseStrings.statusDraft
                    },
                )
            }
        }

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
                SavedDetailRow(strings.savedType, baseStrings.typeName(record.type))
                SavedDetailRow(
                    strings.savedWhen,
                    weekdayMomentText(record.occurredAtEpochMillis, baseStrings.language, zone),
                )
                if (record.description.isNotEmpty()) {
                    SavedDetailRow(strings.savedDescription, record.description)
                }
                SavedDetailRow(
                    strings.savedCategory,
                    categoryName ?: baseStrings.noCategory,
                )
                SavedDetailRow(strings.savedSource, baseStrings.sourceName(record.source))
                SavedDetailRow(
                    strings.savedRecordedAt,
                    weekdayMomentText(record.createdAtEpochMillis, baseStrings.language, zone),
                )
            }
        }
        Text(
            text = strings.savedBody,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onDone,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
        ) { Text(strings.done, style = MaterialTheme.typography.titleMedium) }
        TextButton(
            onClick = onRecordAnother,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) { Text(strings.recordAnother) }
    }
}

@Composable
private fun SavedDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(104.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Expense / Income / Internal transfer: the selected type of one record.
 *
 * At the normal text scale the three segments sit in one row and share the tallest intrinsic
 * label, so a two-line "Internal transfer" does not leave the selected segment shorter than its
 * neighbours. At a large accessibility scale three equal columns would clip the Vietnamese
 * labels, so the same segments stack as full-width rows instead of truncating their words. Each
 * segment is a 48dp target.
 */
@Composable
internal fun TransactionTypePicker(
    selectedType: TransactionType,
    onTypeChange: (TransactionType) -> Unit,
    baseStrings: AegisStrings,
    modifier: Modifier = Modifier,
) {
    val stack = LocalDensity.current.fontScale >= 1.3f
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        if (stack) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf(TransactionType.EXPENSE, TransactionType.INCOME).forEach { type ->
                        TransactionTypeSegment(
                            type = type,
                            selected = type == selectedType,
                            baseStrings = baseStrings,
                            onTypeChange = onTypeChange,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                }
                TransactionTypeSegment(
                    type = TransactionType.TRANSFER,
                    selected = selectedType == TransactionType.TRANSFER,
                    baseStrings = baseStrings,
                    onTypeChange = onTypeChange,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp)
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TransactionType.entries.forEach { type ->
                    TransactionTypeSegment(
                        type = type,
                        selected = type == selectedType,
                        baseStrings = baseStrings,
                        onTypeChange = onTypeChange,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun TransactionTypeSegment(
    type: TransactionType,
    selected: Boolean,
    baseStrings: AegisStrings,
    onTypeChange: (TransactionType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = { onTypeChange(type) },
            )
            .testTag("manual-type-${type.name}"),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0f)
        },
        border = if (selected) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        } else {
            null
        },
        tonalElevation = if (selected) 1.dp else 0.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = baseStrings.typeName(type),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Keeps a focused edit field above the sticky Save bar once the keyboard has opened.
 *
 * The bar itself lifts above the keyboard, which shrinks the scrolling viewport after the
 * field already has focus. A request made only at focus time would aim at the old geometry,
 * so the field asks again after the inset animation has settled.
 */
@Composable
private fun Modifier.focusedFieldBringIntoView(scope: CoroutineScope): Modifier {
    val requester = remember { BringIntoViewRequester() }
    return this
        .bringIntoViewRequester(requester)
        .onFocusChanged { state ->
            if (state.isFocused) {
                scope.launch {
                    delay(280)
                    requester.bringIntoView()
                }
            }
        }
}
