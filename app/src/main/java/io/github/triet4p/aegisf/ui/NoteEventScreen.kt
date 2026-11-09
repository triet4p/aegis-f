package io.github.triet4p.aegisf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.triet4p.aegisf.data.CaptureEventQuery
import io.github.triet4p.aegisf.data.CaptureMessageEntity
import io.github.triet4p.aegisf.data.ContextLinkState
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.LinkOperation
import io.github.triet4p.aegisf.data.LinkOperationQuery
import io.github.triet4p.aegisf.data.NoteEventEntity
import io.github.triet4p.aegisf.data.NoteEventInterpretation
import io.github.triet4p.aegisf.data.NoteEventTimeAnchor
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.UpdateEventRequest
import io.github.triet4p.aegisf.data.VndMoney
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowChange
import io.github.triet4p.aegisf.data.WorkflowCommandOutcome
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.ui.components.AegisDiscardChangesDialog
import io.github.triet4p.aegisf.ui.components.AegisGlyph
import io.github.triet4p.aegisf.ui.components.AegisIcon
import io.github.triet4p.aegisf.ui.text.AegisStrings
import io.github.triet4p.aegisf.ui.text.LocalAegisStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.TimeZone
import java.util.UUID

/**
 * One detail read from a saved note, as a focused child screen.
 *
 * The screen shows the exact note text this detail was read from and the fields the user can
 * state. A new detail must name an exact, unique excerpt of the current note, so the stored
 * reading can always be traced back to the text it came from. Saving changes one stored reading
 * by compare-and-set; a note or detail that moved on is refused and the typed values remain.
 *
 * A detail that no longer matches the note is shown as such and offers a new detail from the
 * current text instead of editing a reading whose source is gone. The bank-notification link
 * decisions below are explicit and never confirm a draft or change a ledger record.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteEventScreen(
    queryService: WorkflowQueryService,
    commandService: WorkflowCommandService,
    captureId: Long,
    eventId: Long?,
    onSaved: () -> Unit,
    onChooseDraft: (Long) -> Unit,
    onRecordManually: (Long) -> Unit,
    onAddFromCurrent: (Long) -> Unit,
    onNavigateUp: () -> Unit,
) {
    val strings = LocalAegisStrings.current
    val scope = rememberCoroutineScope()

    var phase by remember(captureId, eventId) { mutableStateOf<NoteEventPhase>(NoteEventPhase.Loading) }
    var capture by remember(captureId, eventId) { mutableStateOf<CaptureMessageEntity?>(null) }
    var event by remember(captureId, eventId) { mutableStateOf<NoteEventEntity?>(null) }
    var sourceText by remember(captureId, eventId) { mutableStateOf("") }
    var others by remember(captureId, eventId) { mutableStateOf(emptyList<NoteEventEntity>()) }
    var links by remember(captureId, eventId) { mutableStateOf(emptyList<NoteLinkView>()) }
    var eventReload by remember { mutableIntStateOf(0) }
    var populateOnLoad by rememberSaveable(captureId, eventId) { mutableStateOf(true) }
    var captureOnlyRefresh by rememberSaveable(captureId, eventId) { mutableStateOf(false) }
    var loadedCaptureRevision by rememberSaveable(captureId, eventId) {
        mutableStateOf<Int?>(null)
    }

    var selectedExcerpt by rememberSaveable(captureId, eventId) { mutableStateOf("") }
    var readingText by rememberSaveable(captureId, eventId) { mutableStateOf("") }
    var amountText by rememberSaveable(captureId, eventId) { mutableStateOf("") }
    var merchantText by rememberSaveable(captureId, eventId) { mutableStateOf("") }
    var dateText by rememberSaveable(captureId, eventId) { mutableStateOf("") }
    var timeText by rememberSaveable(captureId, eventId) { mutableStateOf("") }
    var intervalEndDateText by rememberSaveable(captureId, eventId) { mutableStateOf("") }
    var intervalEndTimeText by rememberSaveable(captureId, eventId) { mutableStateOf("") }
    var unresolvedReason by rememberSaveable(captureId, eventId) { mutableStateOf("") }
    var edited by rememberSaveable(captureId, eventId) { mutableStateOf(false) }
    var requestKey by rememberSaveable(captureId, eventId) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var message by rememberSaveable(captureId, eventId) { mutableStateOf<String?>(null) }
    var conflicted by rememberSaveable(captureId, eventId) { mutableStateOf(false) }
    var discardPending by rememberSaveable(captureId, eventId) { mutableStateOf(false) }
    var reloadPending by rememberSaveable(captureId, eventId) { mutableStateOf(false) }
    var linkBusy by remember { mutableStateOf(false) }
    var linkMessage by rememberSaveable(captureId, eventId) { mutableStateOf<String?>(null) }
    var linkError by rememberSaveable(captureId, eventId) { mutableStateOf<String?>(null) }

    LaunchedEffect(captureId, eventId, eventReload) {
        phase = NoteEventPhase.Loading
        try {
            val loadedCapture = withContext(Dispatchers.IO) { queryService.sourceExcerpt(captureId) }
            if (loadedCapture == null || loadedCapture.tombstone || loadedCapture.bodyText == null) {
                phase = NoteEventPhase.MissingNote
                return@LaunchedEffect
            }
            sourceText = loadedCapture.bodyText
            val loadedEvent = eventId?.let {
                withContext(Dispatchers.IO) { queryService.noteEvent(it) }
            }
            if (eventId != null && (loadedEvent == null || loadedEvent.captureId != captureId)) {
                phase = NoteEventPhase.MissingDetail
                return@LaunchedEffect
            }
            val loadedLinks = if (loadedEvent != null) {
                withContext(Dispatchers.IO) { queryService.linkViews(loadedEvent.id) }
            } else {
                emptyList()
            }
            val loadedOthers = if (loadedEvent == null) {
                withContext(Dispatchers.IO) {
                    queryService.captureEvents(
                        CaptureEventQuery(
                            captureId = captureId,
                            bounds = QueryPageRequest(limit = QueryPageRequest.MAX_PAGE_SIZE),
                        ),
                    ).items
                }
            } else {
                emptyList()
            }
            capture = loadedCapture
            event = loadedEvent
            links = loadedLinks
            others = loadedOthers
            if (populateOnLoad) {
                selectedExcerpt = loadedEvent?.let {
                    currentSourceExcerpt(it, loadedCapture).orEmpty()
                }.orEmpty()
                readingText = loadedEvent?.normalizedText.orEmpty()
                amountText = loadedEvent?.amountVnd?.toString().orEmpty()
                merchantText = loadedEvent?.merchant.orEmpty()
                dateText = loadedEvent?.anchorDate.orEmpty()
                timeText = loadedEvent?.anchorTime.orEmpty()
                intervalEndDateText = loadedEvent?.intervalEndDate.orEmpty()
                intervalEndTimeText = loadedEvent?.intervalEndTime.orEmpty()
                unresolvedReason = loadedEvent?.unresolvedReason.orEmpty()
                loadedCaptureRevision = loadedCapture.revision
                populateOnLoad = false
                edited = false
            } else if (captureOnlyRefresh) {
                loadedCaptureRevision = loadedCapture.revision
                captureOnlyRefresh = false
            }
            if (conflicted) {
                conflicted = false
                message = null
            }
            phase = NoteEventPhase.Ready
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            phase = NoteEventPhase.Error
        }
    }

    val currentCapture = capture
    val currentEvent = event
    val staleStored = currentEvent != null && currentCapture != null &&
        (currentEvent.sourceRevision != currentCapture.revision ||
            currentEvent.normalizedText == null)

    val span = if (currentEvent == null) {
        uniqueSourceSpan(sourceText, selectedExcerpt)
    } else {
        SourceSpan(currentEvent.spanStart, currentEvent.spanEnd)
    }
    val excerptOccurs = selectedExcerpt.isNotBlank() && sourceText.contains(selectedExcerpt)
    val excerptAmbiguous = excerptOccurs && span == null
    val amountValue = amountText.trim().takeIf(String::isNotEmpty)?.toLongOrNull()
    val amountProblem = when {
        amountText.trim().isNotEmpty() && amountValue == null -> strings.noteDetailAmountError
        amountValue != null && amountValue <= 0L -> strings.noteDetailAmountError
        amountValue == null && unresolvedReason.isBlank() -> strings.noteDetailMissingReason
        else -> null
    }
    val amountErrorVisible = amountProblem != null &&
        (amountText.isNotBlank() || unresolvedReason.isNotBlank())
    val dateProblem = dateText.takeIf(String::isNotBlank)?.let {
        if (ManualDateTime.dateError(it) != null) strings.noteDetailDateFormat else null
    }
    val timeProblem = when {
        timeText.isBlank() -> null
        dateText.isBlank() -> strings.noteDetailTimeNeedsDate
        ManualDateTime.timeError(timeText) != null -> strings.noteDetailTimeFormat
        else -> null
    }
    val intervalProblem = when {
        intervalEndDateText.isNotBlank() && dateText.isBlank() ->
            strings.noteDetailIntervalError
        intervalEndDateText.isNotBlank() &&
            ManualDateTime.dateError(intervalEndDateText) != null ->
            strings.noteDetailDateFormat
        intervalEndDateText.isNotBlank() && intervalEndDateText < dateText ->
            strings.noteDetailIntervalError
        intervalEndTimeText.isNotBlank() && intervalEndDateText.isBlank() ->
            strings.noteDetailIntervalError
        intervalEndTimeText.isNotBlank() &&
            ManualDateTime.timeError(intervalEndTimeText) != null ->
            strings.noteDetailTimeFormat
        intervalEndDateText == dateText && intervalEndTimeText.isNotBlank() &&
            timeText.isNotBlank() && intervalEndTimeText < timeText ->
            strings.noteDetailIntervalError
        else -> null
    }
    val duplicateRange = currentEvent == null && span != null &&
        others.any {
            it.spanStart == span.start && it.spanEnd == span.end &&
                it.sourceRevision == currentCapture?.revision
        }
    val canSave = !staleStored &&
        (currentEvent != null || span != null) &&
        readingText.isNotBlank() &&
        amountProblem == null &&
        dateProblem == null &&
        timeProblem == null &&
        intervalProblem == null &&
        !duplicateRange &&
        !saving &&
        !conflicted

    val activeLink = links.firstOrNull {
        it.link.state == ContextLinkState.ACCEPTED ||
            it.link.state == ContextLinkState.PROPOSED
    }
    val manuallyRecordable = !staleStored &&
        currentEvent != null &&
        currentEvent.unresolvedReason == null &&
        currentEvent.amountVnd != null &&
        currentEvent.resolvedTransactionId == null &&
        activeLink == null

    fun clearMessages() {
        message = null
        conflicted = false
        requestKey = null
    }

    fun save() {
        val captureRevision = loadedCaptureRevision ?: return
        val chosenSpan = span ?: return
        if (!canSave) return
        saving = true
        message = null
        conflicted = false
        val key = requestKey ?: UUID.randomUUID().toString().also { requestKey = it }
        scope.launch {
            try {
                val outcome = commandService.updateEventInterpretation(
                    WorkflowCallerScope.User,
                    UpdateEventRequest(
                        idempotencyKey = key,
                        captureId = captureId,
                        expectedCaptureRevision = captureRevision,
                        expectedEventRevision = currentEvent?.revision,
                        interpretation = NoteEventInterpretation(
                            spanStart = chosenSpan.start,
                            spanEnd = chosenSpan.end,
                            normalizedText = readingText.trim(),
                            amountVnd = amountValue,
                            merchant = merchantText.trim().takeIf(String::isNotEmpty),
                            timeAnchor = if (
                                dateText.isNotBlank() ||
                                    timeText.isNotBlank() ||
                                    intervalEndDateText.isNotBlank() ||
                                    intervalEndTimeText.isNotBlank()
                            ) {
                                NoteEventTimeAnchor(
                                    date = dateText.trim().takeIf(String::isNotEmpty),
                                    time = timeText.trim().takeIf(String::isNotEmpty),
                                    timezoneId = TimeZone.getDefault().id.takeIf {
                                        timeText.isNotBlank()
                                    },
                                    intervalEndDate = intervalEndDateText.trim()
                                        .takeIf(String::isNotEmpty),
                                    intervalEndTime = intervalEndTimeText.trim()
                                        .takeIf(String::isNotEmpty),
                                )
                            } else {
                                null
                            },
                            unresolvedReason = unresolvedReason.trim()
                                .takeIf(String::isNotEmpty),
                        ),
                        extractionVersion = currentEvent?.extractionVersion
                            ?: MANUAL_EXTRACTION_VERSION,
                    ),
                )
                when (outcome) {
                    is WorkflowCommandOutcome.Applied,
                    is WorkflowCommandOutcome.NoChange,
                    -> if (outcome.isEventSaved()) onSaved()
                    else message = strings.noteDetailSaveFailed

                    is WorkflowCommandOutcome.Conflict -> {
                        requestKey = null
                        conflicted = true
                        message = strings.noteDetailConflict
                    }

                    WorkflowCommandOutcome.NotFound -> {
                        requestKey = null
                        message = strings.noteDetailMissingTitle
                    }

                    else -> {
                        requestKey = null
                        message = strings.noteDetailSaveFailed
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                requestKey = null
                message = strings.noteDetailSaveFailed
            } finally {
                saving = false
            }
        }
    }

    fun requestLeave() {
        if (saving) return
        if (edited) discardPending = true else onNavigateUp()
    }

    fun unlink(link: LinkOperation) {
        linkBusy = true
        linkMessage = null
        linkError = null
        scope.launch {
            try {
                val outcome = commandService.unlinkLink(
                    WorkflowCallerScope.User,
                    io.github.triet4p.aegisf.data.UnlinkLinkRequest(
                        idempotencyKey = UUID.randomUUID().toString(),
                        eventId = link.eventId,
                        draftId = link.draftId,
                        expectedRevision = link.revision,
                    ),
                )
                if (outcome.isAppliedOrNoChange()) {
                    linkMessage = strings.noteDetailUnlinked
                } else {
                    linkError = strings.noteDetailUnlinkFailed
                }
                eventReload += 1
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                linkError = strings.noteDetailUnlinkFailed
            } finally {
                linkBusy = false
            }
        }
    }

    BackHandler { requestLeave() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (eventId == null) strings.noteDetailAddTitle
                        else strings.noteDetailEditTitle,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = ::requestLeave,
                        enabled = !saving,
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
            if (phase == NoteEventPhase.Ready && !staleStored) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding(),
                    color = MaterialTheme.colorScheme.background,
                    tonalElevation = 3.dp,
                ) {
                    Button(
                        onClick = ::save,
                        enabled = canSave,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                            .defaultMinSize(minHeight = 48.dp),
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Text(strings.noteSaving)
                        } else {
                            Text(strings.noteDetailSave)
                        }
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        when (val current = phase) {
            NoteEventPhase.Loading -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
            ) { NoteStateMessage(strings.noteLoading, null) }

            NoteEventPhase.MissingNote -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
            ) {
                NoteStateMessage(
                    title = strings.noteMissingTitle,
                    body = strings.noteMissingBody,
                )
            }

            NoteEventPhase.MissingDetail -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
            ) {
                NoteStateMessage(
                    title = strings.noteDetailMissingTitle,
                    body = strings.noteDetailMissingBody,
                )
            }

            NoteEventPhase.Error -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
            ) {
                NoteStateMessage(
                    title = strings.noteReadErrorTitle,
                    body = strings.noteReadErrorBody,
                    actionLabel = strings.retry,
                    onAction = { eventReload += 1 },
                )
            }

            NoteEventPhase.Ready -> if (staleStored) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    NoteStateMessage(
                        title = strings.noteDetailOutdated,
                        body = strings.noteDetailOutdatedBody,
                        actionLabel = strings.noteDetailAddFromCurrent,
                        onAction = { onAddFromCurrent(captureId) },
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (conflicted) {
                        Text(
                            text = strings.noteDetailConflict,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(
                            onClick = {
                                if (currentEvent == null) {
                                    captureOnlyRefresh = true
                                    message = null
                                    conflicted = false
                                    eventReload += 1
                                } else {
                                    reloadPending = true
                                }
                            },
                            enabled = !saving,
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                        ) {
                            Text(
                                if (currentEvent == null) strings.noteReloadSaved
                                else strings.noteDetailReload,
                            )
                        }
                    } else {
                        message?.let { text ->
                            Text(
                                text = text,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    NoteSourceCard(
                        strings = strings,
                        sourceText = sourceText,
                    )

                    if (currentEvent == null) {
                        OutlinedTextField(
                            value = selectedExcerpt,
                            onValueChange = {
                                val previous = selectedExcerpt
                                selectedExcerpt = it
                                if (readingText.isBlank() || readingText == previous) {
                                    readingText = it
                                }
                                edited = true
                                clearMessages()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !saving,
                            label = { Text(strings.noteDetailExcerpt) },
                            supportingText = { Text(strings.noteDetailExcerptHelp) },
                        )
                        when {
                            selectedExcerpt.isBlank() -> Unit
                            !excerptOccurs -> Text(
                                text = strings.noteDetailExcerptMissing,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            excerptAmbiguous -> Text(
                                text = strings.noteDetailExcerptAmbiguous,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    } else {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = strings.noteDetailExcerpt,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                SelectionContainer {
                                    Text(
                                        text = selectedExcerpt,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                Text(
                                    text = strings.noteDetailExcerptFixed,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    if (duplicateRange) {
                        Text(
                            text = strings.noteDetailDuplicateRange,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    OutlinedTextField(
                        value = readingText,
                        onValueChange = {
                            readingText = it
                            edited = true
                            clearMessages()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !saving,
                        label = { Text(strings.noteDetailReading) },
                        supportingText = { Text(strings.noteDetailReadingHelp) },
                    )
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = {
                            amountText = it
                            edited = true
                            clearMessages()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !saving,
                        label = { Text(strings.noteDetailAmountField) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = amountErrorVisible,
                        supportingText = {
                            val helper = when {
                                amountErrorVisible -> amountProblem
                                amountText.isBlank() && unresolvedReason.isBlank() ->
                                    strings.noteDetailAmountHint
                                else -> null
                            }
                            if (helper != null) Text(helper)
                        },
                    )
                    OutlinedTextField(
                        value = merchantText,
                        onValueChange = {
                            merchantText = it
                            edited = true
                            clearMessages()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !saving,
                        label = { Text(strings.noteDetailMerchantField) },
                    )
                    OutlinedTextField(
                        value = dateText,
                        onValueChange = {
                            dateText = it
                            edited = true
                            clearMessages()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !saving,
                        label = { Text(strings.noteDetailDateField) },
                        placeholder = { Text("2026-10-23") },
                        isError = dateProblem != null,
                        supportingText = { Text(dateProblem ?: "YYYY-MM-DD") },
                    )
                    OutlinedTextField(
                        value = timeText,
                        onValueChange = {
                            timeText = it
                            edited = true
                            clearMessages()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !saving,
                        label = { Text(strings.noteDetailTimeField) },
                        placeholder = { Text("18:30") },
                        isError = timeProblem != null,
                        supportingText = { Text(timeProblem ?: "HH:mm") },
                    )
                    OutlinedTextField(
                        value = intervalEndDateText,
                        onValueChange = {
                            intervalEndDateText = it
                            edited = true
                            clearMessages()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !saving,
                        label = { Text(strings.noteDetailIntervalDateField) },
                        placeholder = { Text("2026-10-23") },
                    )
                    OutlinedTextField(
                        value = intervalEndTimeText,
                        onValueChange = {
                            intervalEndTimeText = it
                            edited = true
                            clearMessages()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !saving,
                        label = { Text(strings.noteDetailIntervalTimeField) },
                        placeholder = { Text("19:30") },
                    )
                    intervalProblem?.let { problem ->
                        Text(
                            text = problem,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    OutlinedTextField(
                        value = unresolvedReason,
                        onValueChange = {
                            unresolvedReason = it
                            edited = true
                            clearMessages()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !saving,
                        label = { Text(strings.noteDetailMissingField) },
                    )

                    if (currentEvent != null) {
                        NoteEventLinks(
                            strings = strings,
                            event = currentEvent,
                            capture = currentCapture,
                            links = links,
                            busy = linkBusy,
                            message = linkMessage,
                            error = linkError,
                            onUnlink = ::unlink,
                            onChooseDraft = { onChooseDraft(currentEvent.id) },
                        )
                        if (manuallyRecordable) {
                            OutlinedButton(
                                onClick = { onRecordManually(currentEvent.id) },
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp),
                            ) { Text(strings.noteRecordTransaction) }
                        } else {
                            Text(
                                text = strings.noteDetailManualHelp,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (discardPending) {
        AegisDiscardChangesDialog(
            onKeepEditing = { discardPending = false },
            onDiscard = {
                discardPending = false
                onNavigateUp()
            },
        )
    }

    if (reloadPending) {
        AlertDialog(
            onDismissRequest = { reloadPending = false },
            title = { Text(strings.noteDetailReloadConfirmTitle) },
            text = { Text(strings.noteDetailReloadConfirmBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        reloadPending = false
                        populateOnLoad = true
                        message = null
                        conflicted = false
                        requestKey = null
                        eventReload += 1
                    },
                ) { Text(strings.reload) }
            },
            dismissButton = {
                TextButton(onClick = { reloadPending = false }) { Text(strings.cancel) }
            },
        )
    }
}

private sealed interface NoteEventPhase {
    data object Loading : NoteEventPhase
    data object MissingNote : NoteEventPhase
    data object MissingDetail : NoteEventPhase
    data object Error : NoteEventPhase
    data object Ready : NoteEventPhase
}

internal data class NoteLinkView(
    val link: LinkOperation,
    val draft: PendingBankDraftEntity?,
)

private suspend fun WorkflowQueryService.linkViews(eventId: Long): List<NoteLinkView> {
    val links = linkOperations(
        LinkOperationQuery(
            eventId = eventId,
            bounds = QueryPageRequest(limit = 20),
        ),
    ).items
    return links.map { link -> NoteLinkView(link, draftCandidate(link.draftId)) }
}

@Composable
private fun NoteSourceCard(
    strings: AegisStrings,
    sourceText: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = strings.noteEditLabel,
                style = MaterialTheme.typography.labelMedium,
            )
            SelectionContainer {
                Text(
                    text = sourceText,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun NoteEventLinks(
    strings: AegisStrings,
    event: NoteEventEntity,
    capture: CaptureMessageEntity?,
    links: List<NoteLinkView>,
    busy: Boolean,
    message: String?,
    error: String?,
    onUnlink: (LinkOperation) -> Unit,
    onChooseDraft: () -> Unit,
) {
    val acceptedLink = links.firstOrNull { it.link.state == ContextLinkState.ACCEPTED }
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
            Text(
                text = strings.noteDetailLinkSection,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = strings.noteDetailLinkHelp,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (links.isEmpty()) {
                Text(
                    text = strings.noteDetailNoLinks,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            links.forEach { view ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AegisIcon(
                            glyph = when (view.link.state) {
                                ContextLinkState.ACCEPTED -> AegisGlyph.CHECK
                                ContextLinkState.PROPOSED -> AegisGlyph.LINK
                                ContextLinkState.REJECTED -> AegisGlyph.CROSS
                            },
                            tint = when (view.link.state) {
                                ContextLinkState.ACCEPTED -> MaterialTheme.colorScheme.secondary
                                ContextLinkState.PROPOSED -> MaterialTheme.colorScheme.primary
                                ContextLinkState.REJECTED -> MaterialTheme.colorScheme.error
                            },
                            size = 16.dp,
                        )
                        Text(
                            text = when (view.link.state) {
                                ContextLinkState.ACCEPTED -> strings.noteDetailAttached
                                ContextLinkState.PROPOSED -> strings.noteDetailProposed
                                ContextLinkState.REJECTED -> strings.noteDetailRefused
                            },
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    view.draft?.let { draft ->
                        Text(
                            text = draftSummary(draft, strings),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (view.link.state == ContextLinkState.ACCEPTED &&
                        view.draft?.reviewStatus == IntakeReviewStatus.PENDING
                    ) {
                        OutlinedButton(
                            onClick = { onUnlink(view.link) },
                            enabled = !busy,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                        ) { Text(strings.noteDetailUnlink) }
                    }
                }
            }
            if (acceptedLink == null) {
                Button(
                    onClick = onChooseDraft,
                    enabled = !busy &&
                        !event.tombstoneSource(capture) &&
                        event.resolvedTransactionId == null,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp),
                ) { Text(strings.noteDetailChooseDraft) }
                if (event.resolvedTransactionId != null) {
                    Text(
                        text = strings.noteDetailRecorded,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    text = strings.noteDetailUnlinkFirst,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (busy) {
                Text(strings.noteDraftDeciding, style = MaterialTheme.typography.bodySmall)
            }
            message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun NoteEventEntity.tombstoneSource(capture: CaptureMessageEntity?): Boolean =
    capture == null || sourceRevision != capture.revision || normalizedText == null

internal fun draftSummary(
    draft: PendingBankDraftEntity,
    strings: AegisStrings,
    includeBank: Boolean = true,
): String {
    val direction = when (draft.direction.name) {
        "DEBIT" -> strings.noteDraftDirectionOut
        "CREDIT" -> strings.noteDraftDirectionIn
        else -> strings.noteDraftDirectionUnknown
    }
    val amount = draft.amountVnd
        ?.let { VndMoney.formatAmount(it, " ₫") }
        ?: strings.noteDraftAmountUnread
    val moment = listOfNotNull(draft.statedDate, draft.statedTime)
        .filter(String::isNotBlank)
        .joinToString(" ")
        .ifBlank { strings.noteDraftTimeUnread }
    val memo = draft.memo?.takeIf(String::isNotBlank) ?: strings.noteDraftNoMemo
    return buildList {
        if (includeBank) add(draft.bank)
        add(direction)
        add(amount)
        add(moment)
        add(memo)
    }.joinToString(" · ")
}

private fun WorkflowCommandOutcome.isEventSaved(): Boolean = when (this) {
    is WorkflowCommandOutcome.Applied -> change is WorkflowChange.EventStored ||
        change is WorkflowChange.EventRevised
    is WorkflowCommandOutcome.NoChange -> change is WorkflowChange.EventStored ||
        change is WorkflowChange.EventRevised
    else -> false
}
