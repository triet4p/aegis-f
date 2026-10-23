package io.github.triet4p.aegisf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.triet4p.aegisf.data.AttachLinkRequest
import io.github.triet4p.aegisf.data.CaptureEventQuery
import io.github.triet4p.aegisf.data.CaptureMessageEntity
import io.github.triet4p.aegisf.data.ContextLinkState
import io.github.triet4p.aegisf.data.DraftCandidateQuery
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.LinkOperation
import io.github.triet4p.aegisf.data.LinkOperationQuery
import io.github.triet4p.aegisf.data.NoteEventEntity
import io.github.triet4p.aegisf.data.NoteEventInterpretation
import io.github.triet4p.aegisf.data.NoteEventStatus
import io.github.triet4p.aegisf.data.NoteEventTimeAnchor
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.data.ProposeLinkRequest
import io.github.triet4p.aegisf.data.QueryPage
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.RejectLinkRequest
import io.github.triet4p.aegisf.data.UnlinkLinkRequest
import io.github.triet4p.aegisf.data.UpdateEventRequest
import io.github.triet4p.aegisf.data.VndMoney
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowChange
import io.github.triet4p.aegisf.data.WorkflowCommandOutcome
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.TimeZone
import java.util.UUID

private const val EVENT_PAGE_SIZE = 20
private const val CANDIDATE_PAGE_SIZE = 10
private const val MANUAL_EXTRACTION_VERSION = "user-manual-v1"

private const val EVENT_LINK_PAGE_SIZE = 20

@Composable
internal fun QuickCaptureEventPanel(
    capture: CaptureMessageEntity,
    queryService: WorkflowQueryService,
    commandService: WorkflowCommandService,
    onRecordManually: (Long) -> Unit,
    onContextChanged: () -> Unit,
) {
    var eventOffset by rememberSaveable(capture.id) { mutableIntStateOf(0) }
    var eventReload by remember { mutableIntStateOf(0) }
    var events by remember(capture.id) { mutableStateOf(emptyList<NoteEventEntity>()) }
    var eventHasMore by remember(capture.id) { mutableStateOf(false) }
    var eventLoading by remember(capture.id) { mutableStateOf(true) }
    var eventError by remember(capture.id) { mutableStateOf<String?>(null) }
    var creatingInterpretation by rememberSaveable(capture.id) { mutableStateOf(false) }
    var editingEventId by rememberSaveable(capture.id) { mutableStateOf<Long?>(null) }
    var candidateEventId by rememberSaveable(capture.id) { mutableStateOf<Long?>(null) }

    LaunchedEffect(queryService, capture.id, capture.revision, eventOffset, eventReload) {
        eventLoading = true
        eventError = null
        try {
            val page = queryService.captureEvents(
                CaptureEventQuery(
                    captureId = capture.id,
                    bounds = QueryPageRequest(limit = EVENT_PAGE_SIZE, offset = eventOffset),
                ),
            )
            if (page.items.isEmpty() && eventOffset > 0) {
                eventOffset = (eventOffset - EVENT_PAGE_SIZE).coerceAtLeast(0)
            } else {
                events = page.items
                eventHasMore = page.hasMore
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            eventError = "Event interpretations could not be loaded. Try again."
        } finally {
            eventLoading = false
        }
    }

    CapturePanel(
        modifier = Modifier.fillMaxWidth(),
        accent = MaterialTheme.colorScheme.primary,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Event interpretations", style = MaterialTheme.typography.titleMedium)
            Text(
                "Choose an exact excerpt from the saved source for each occurrence. Saving an " +
                    "interpretation does not create a ledger record or select a bank draft.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Source note #${capture.id}: ${capture.bodyText.orEmpty()}")
            Button(shape = MaterialTheme.shapes.small, onClick = {
                creatingInterpretation = true
                editingEventId = null
            },
            enabled = !capture.tombstone && !capture.bodyText.isNullOrBlank(),
            modifier = Modifier.fillMaxWidth(),) { Text("Add event interpretation") }
            when {
                eventLoading && events.isEmpty() -> Text("Loading event interpretations…")
                eventError != null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(eventError.orEmpty(), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { eventReload++ }) { Text("Retry") }
                }
                events.isEmpty() -> Text("No event interpretations yet. The saved note stays unchanged.")
            }
            events.forEach { event ->
                QuickCaptureEventCard(
                    event = event,
                    capture = capture,
                    queryService = queryService,
                    commandService = commandService,
                    linkRefreshKey = eventReload,
                    onBrowseCandidates = { candidateEventId = event.id },
                    onRecordManually = onRecordManually,
                    onEdit = {
                        editingEventId = event.id
                        creatingInterpretation = false
                    },
                    onContextChanged = {
                        eventReload++
                        onContextChanged()
                    },
                )
            }
            if (eventOffset > 0 || eventHasMore) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        onClick = { eventOffset = (eventOffset - EVENT_PAGE_SIZE).coerceAtLeast(0) },
                        enabled = eventOffset > 0 && !eventLoading,
                    ) { Text("Previous events") }
                    Text("Events ${eventOffset + 1}–${eventOffset + events.size}")
                    TextButton(
                        onClick = { eventOffset += events.size },
                        enabled = eventHasMore && events.isNotEmpty() && !eventLoading,
                    ) { Text("Next events") }
                }
            }
        }
    }

    if (creatingInterpretation || editingEventId != null) {
        val editingEvent = editingEventId?.let { id -> events.firstOrNull { it.id == id } }
        if (editingEventId != null && editingEvent == null) {
            AlertDialog(
                onDismissRequest = { editingEventId = null },
                title = { Text("Interpretation unavailable") },
                text = { Text("This event changed or is no longer on the current page. Reload it before editing.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            editingEventId = null
                            eventReload++
                        },
                    ) { Text("Reload") }
                },
                dismissButton = { TextButton(onClick = { editingEventId = null }) { Text("Close") } },
            )
        } else {
            InterpretationEditorDialog(
                capture = capture,
                event = editingEvent,
                existingEvents = events,
                commandService = commandService,
                onDismiss = {
                    creatingInterpretation = false
                    editingEventId = null
                },
                onSaved = {
                    creatingInterpretation = false
                    editingEventId = null
                    eventReload++
                    onContextChanged()
                },
                onConflict = {
                    eventReload++
                    onContextChanged()
                },
            )
        }
    }

    candidateEventId?.let { eventId ->
        val event = events.firstOrNull { it.id == eventId }
        if (event == null) {
            candidateEventId = null
        } else {
            CandidateDraftBrowserDialog(
                event = event,
                capture = capture,
                queryService = queryService,
                commandService = commandService,
                onDismiss = { candidateEventId = null },
                onContextChanged = {
                    eventReload++
                    onContextChanged()
                },
                onRecordManually = { selectedEventId ->
                    candidateEventId = null
                    onRecordManually(selectedEventId)
                },
            )
        }
    }
}

@Composable
private fun QuickCaptureEventCard(
    event: NoteEventEntity,
    capture: CaptureMessageEntity,
    queryService: WorkflowQueryService,
    commandService: WorkflowCommandService,
    onBrowseCandidates: () -> Unit,
    onRecordManually: (Long) -> Unit,
    onEdit: () -> Unit,
    linkRefreshKey: Int,
    onContextChanged: () -> Unit,
) {
    var links by remember(event.id) { mutableStateOf(emptyList<LinkOperation>()) }
    var linkLoading by remember(event.id) { mutableStateOf(true) }
    var linkError by remember(event.id) { mutableStateOf<String?>(null) }
    var linkReload by remember(event.id) { mutableIntStateOf(0) }
    var actionMessage by remember(event.id) { mutableStateOf<String?>(null) }
    var actionInProgress by remember(event.id) { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(queryService, event.id, event.revision, capture.revision, linkRefreshKey, linkReload) {
        linkLoading = true
        linkError = null
        try {
            links = queryService.linkOperations(
                LinkOperationQuery(
                    eventId = event.id,
                    bounds = QueryPageRequest(limit = EVENT_LINK_PAGE_SIZE),
                ),
            ).items
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            linkError = "Link decisions could not be loaded."
        } finally {
            linkLoading = false
        }
    }

    val body = capture.bodyText
    val currentExcerpt = body?.takeIf {
        event.sourceRevision == capture.revision &&
            event.spanStart >= 0 &&
            event.spanEnd > event.spanStart &&
            event.spanEnd <= it.length
    }?.substring(event.spanStart, event.spanEnd)
    val staleSource = event.sourceRevision != capture.revision || event.normalizedText == null
    val activeLink = links.firstOrNull {
        it.state == ContextLinkState.PROPOSED || it.state == ContextLinkState.ACCEPTED
    }
    val manuallyRecordable =
        !staleSource &&
            event.unresolvedReason == null &&
            event.amountVnd != null &&
            event.resolvedTransactionId == null &&
            activeLink == null

    val eventAccent = when (event.status) {
        NoteEventStatus.INTERPRETED -> MaterialTheme.colorScheme.primary
        NoteEventStatus.UNRESOLVED -> MaterialTheme.colorScheme.tertiary
        NoteEventStatus.NO_MATCH -> MaterialTheme.colorScheme.outlineVariant
    }
    CapturePanel(
        modifier = Modifier.fillMaxWidth(),
        accent = eventAccent,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Event #${event.id}", fontWeight = FontWeight.SemiBold)
            Text(
                when (event.status) {
                    NoteEventStatus.INTERPRETED -> "Interpretation ready · revision ${event.revision}"
                    NoteEventStatus.UNRESOLVED -> "Unresolved · ${event.unresolvedReason ?: "needs review"}"
                    NoteEventStatus.NO_MATCH -> "Unmatched · no active bank draft"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (staleSource) {
                Text(
                    "Source conflict: this reading came from capture revision ${event.sourceRevision}, " +
                        "but the current source is revision ${capture.revision}. The old reading " +
                        "was cleared; re-read current source text before linking or recording.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text("Selected source excerpt: “${currentExcerpt.orEmpty()}”")
                Text("Reading: ${event.normalizedText.orEmpty()}")
            }
            event.amountVnd?.let { amount -> Text("Interpretation amount: ${VndMoney.formatAmount(amount)}") }
            event.merchant?.let { Text("Merchant: $it") }
            if (event.anchorDate != null || event.anchorTime != null ||
                event.intervalEndDate != null || event.intervalEndTime != null
            ) {
                val intervalEnd = listOfNotNull(event.intervalEndDate, event.intervalEndTime)
                    .joinToString(" ")
                Text(
                    "Stated date/time: ${event.anchorDate.orEmpty()} ${event.anchorTime.orEmpty()}" +
                        if (intervalEnd.isBlank()) "" else " to $intervalEnd",
                )
            }
            if (event.resolvedTransactionId != null) {
                Text("Recorded manually as ledger record #${event.resolvedTransactionId}.")
            }
            if (linkLoading) {
                Text("Loading link decisions…", style = MaterialTheme.typography.bodySmall)
            }
            linkError?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { linkReload++ }) { Text("Retry link read") }
            }
            links.forEach { link ->
                StoredLinkDecision(
                    link = link,
                    queryService = queryService,
                    enabled = !actionInProgress,
                    onUnlink = {
                        actionInProgress = true
                        actionMessage = null
                        coroutineScope.launch {
                            try {
                                val outcome = commandService.unlinkLink(
                                    WorkflowCallerScope.User,
                                    UnlinkLinkRequest(
                                        idempotencyKey = UUID.randomUUID().toString(),
                                        eventId = event.id,
                                        draftId = link.draftId,
                                        expectedRevision = link.revision,
                                    ),
                                )
                                if (outcome.isAppliedOrNoChange()) {
                                    actionMessage = "Draft unlinked. The event can be attached to another draft or recorded manually."
                                    linkReload++
                                    onContextChanged()
                                } else {
                                    actionMessage = outcome.linkFailureMessage()
                                    linkReload++
                                    onContextChanged()
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                actionMessage = "Unlink failed. The stored decision is unchanged; try again."
                            } finally {
                                actionInProgress = false
                            }
                        }
                    },
                )
            }
            if (actionInProgress) Text("Saving link decision…")
            actionMessage?.let { message ->
                Text(
                    message,
                    color = if (message.startsWith("Draft unlinked")) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = onBrowseCandidates,
                enabled = !staleSource && !eventLoadingLink(links, linkLoading),) { Text("Browse drafts") }
                if (manuallyRecordable) {
                    Button(shape = MaterialTheme.shapes.small, onClick = { onRecordManually(event.id) },
                    enabled = !actionInProgress,) { Text("Record manually") }
                }
            }
            if (!staleSource && activeLink == null && event.resolvedTransactionId == null) {
                TextButton(
                    onClick = onEdit,
                    enabled = !actionInProgress,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Edit interpretation") }
            }
            if (links.none { it.state == ContextLinkState.PROPOSED || it.state == ContextLinkState.ACCEPTED } &&
                event.resolvedTransactionId == null
            ) {
                Text(
                    "No draft is attached. Missing or multiple candidates stay unresolved until you choose one.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun StoredLinkDecision(
    link: LinkOperation,
    queryService: WorkflowQueryService,
    enabled: Boolean,
    onUnlink: () -> Unit,
) {
    val draft by remember(queryService, link.draftId) {
        queryService.observeDraftCandidate(link.draftId)
    }.collectAsState(initial = null)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            when (link.state) {
                ContextLinkState.PROPOSED -> "Proposed · bank draft #${link.draftId} (not attached)"
                ContextLinkState.ACCEPTED -> "Attached · bank draft #${link.draftId}"
                ContextLinkState.REJECTED -> "Rejected · bank draft #${link.draftId} (decision retained)"
            },
            fontWeight = FontWeight.Medium,
        )
        draft?.let { Text(candidateDraftSummary(it), style = MaterialTheme.typography.bodySmall) }
        when (link.state) {
            ContextLinkState.PROPOSED -> Text("Choose Attach or Reject in Browse drafts to resolve this proposal.")
            ContextLinkState.ACCEPTED -> OutlinedButton(shape = MaterialTheme.shapes.small, onClick = onUnlink, enabled = enabled) { Text("Unlink draft #${link.draftId}") }
            ContextLinkState.REJECTED -> Text(
                "This refusal cannot be undone. You may browse a different draft.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun InterpretationEditorDialog(
    capture: CaptureMessageEntity,
    event: NoteEventEntity?,
    existingEvents: List<NoteEventEntity>,
    commandService: WorkflowCommandService,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onConflict: () -> Unit,
) {
    val body = capture.bodyText.orEmpty()
    val initialExcerpt = event?.let { currentSourceExcerpt(it, capture) }.orEmpty()
    var selectedExcerpt by rememberSaveable(capture.id, event?.id) {
        mutableStateOf(initialExcerpt)
    }
    var readingText by rememberSaveable(capture.id, event?.id) {
        mutableStateOf(event?.normalizedText.orEmpty())
    }
    var amountText by rememberSaveable(capture.id, event?.id) {
        mutableStateOf(event?.amountVnd?.toString().orEmpty())
    }
    var merchantText by rememberSaveable(capture.id, event?.id) {
        mutableStateOf(event?.merchant.orEmpty())
    }
    var dateText by rememberSaveable(capture.id, event?.id) {
        mutableStateOf(event?.anchorDate.orEmpty())
    }
    var timeText by rememberSaveable(capture.id, event?.id) {
        mutableStateOf(event?.anchorTime.orEmpty())
    }

    var intervalEndDateText by rememberSaveable(capture.id, event?.id) {
        mutableStateOf(event?.intervalEndDate.orEmpty())
    }
    var intervalEndTimeText by rememberSaveable(capture.id, event?.id) {
        mutableStateOf(event?.intervalEndTime.orEmpty())
    }
    var unresolvedReason by rememberSaveable(capture.id, event?.id) {
        mutableStateOf(event?.unresolvedReason.orEmpty())
    }
    var requestKey by rememberSaveable(capture.id, event?.id) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var message by rememberSaveable(capture.id, event?.id) { mutableStateOf<String?>(null) }
    var conflicted by rememberSaveable(capture.id, event?.id) { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val span = uniqueSourceSpan(body, selectedExcerpt)
    val excerptOccurs = selectedExcerpt.isNotBlank() && body.contains(selectedExcerpt)
    val excerptAmbiguous = excerptOccurs && span == null
    val amountValue = if (amountText.isBlank()) null else amountText.toLongOrNull()
    val amountProblem = when {
        amountText.isNotBlank() && amountValue == null -> "Enter a whole VND amount using digits only."
        amountValue != null && amountValue <= 0L -> "Amount must be greater than zero."
        amountValue == null && unresolvedReason.isBlank() -> "Explain why the amount is missing."
        else -> null
    }
    val dateProblem = dateText.takeIf(String::isNotBlank)?.let(ManualDateTime::dateError)
    val timeProblem = when {
        timeText.isBlank() -> null
        dateText.isBlank() -> "A stated time needs its date."
        else -> ManualDateTime.timeError(timeText)
    }
    val intervalProblem = when {
        intervalEndDateText.isNotBlank() && dateText.isBlank() ->
            "An interval end needs its start date."
        intervalEndDateText.isNotBlank() && dateProblem != null -> dateProblem
        intervalEndDateText.isNotBlank() &&
            ManualDateTime.dateError(intervalEndDateText) != null ->
            "The interval end date must use YYYY-MM-DD."
        intervalEndDateText.isNotBlank() && intervalEndDateText < dateText ->
            "An interval cannot end before it starts."
        intervalEndTimeText.isNotBlank() && intervalEndDateText.isBlank() ->
            "An interval end time needs its date."
        intervalEndTimeText.isNotBlank() && timeText.isBlank() ->
            "An interval end time needs its start time."
        intervalEndTimeText.isNotBlank() && ManualDateTime.timeError(intervalEndTimeText) != null ->
            "The interval end time must use HH:mm."
        intervalEndDateText == dateText && intervalEndTimeText.isNotBlank() &&
            timeText.isNotBlank() && intervalEndTimeText < timeText ->
            "An interval cannot end before it starts."
        else -> null
    }
    val canSave =
        (event != null || span != null) &&
            readingText.isNotBlank() &&
            amountProblem == null &&
            dateProblem == null &&
            timeProblem == null &&
            intervalProblem == null &&
            !saving &&
            !conflicted

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (event == null) "Add event interpretation" else "Edit event interpretation") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Original source note #${capture.id}: $body")
                if (conflicted) {
                    Text(
                        "Conflict: the source or interpretation changed while this form was open. " +
                            "Your values remain visible; close this form and re-read the current source.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (event == null) {
                    OutlinedTextField(
                        value = selectedExcerpt,
                        onValueChange = {
                            val previousExcerpt = selectedExcerpt
                            selectedExcerpt = it
                            if (readingText.isBlank() || readingText == previousExcerpt) {
                                readingText = it
                            }
                            requestKey = null
                            message = null
                        },
                        label = { Text("Relevant source text") },
                        supportingText = {
                            Text("Enter an exact, unique excerpt from the note. The source itself stays unchanged.")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    when {
                        selectedExcerpt.isBlank() -> Unit
                        !excerptOccurs -> Text(
                            "This excerpt is not present in the saved source.",
                            color = MaterialTheme.colorScheme.error,
                        )
                        excerptAmbiguous -> Text(
                            "This excerpt appears more than once. Add nearby source text to choose one occurrence.",
                            color = MaterialTheme.colorScheme.error,
                        )
                        span != null -> Text("Selected source characters ${span.start}–${span.end}.")
                    }
                } else {
                    Text("Selected source excerpt: “$initialExcerpt”")
                    Text(
                        "The source range stays fixed while you revise this interpretation.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    value = readingText,
                    onValueChange = { readingText = it; requestKey = null; message = null },
                    label = { Text("Event interpretation") },
                    supportingText = { Text("Describe only what this saved excerpt says; no matching is automatic.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it; requestKey = null; message = null },
                    label = { Text("Amount in VND (optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = amountProblem != null,
                    supportingText = amountProblem?.let { reason -> { Text(reason) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = merchantText,
                    onValueChange = { merchantText = it; requestKey = null; message = null },
                    label = { Text("Merchant (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = dateText,
                    onValueChange = { dateText = it; requestKey = null; message = null },
                    label = { Text("Stated date (optional)") },
                    placeholder = { Text("2026-10-23") },
                    isError = dateProblem != null,
                    supportingText = dateProblem?.let { reason -> { Text(reason) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = timeText,
                    onValueChange = { timeText = it; requestKey = null; message = null },
                    label = { Text("Stated time (optional)") },
                    placeholder = { Text("18:30") },
                    isError = timeProblem != null,
                    supportingText = timeProblem?.let { reason -> { Text(reason) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = intervalEndDateText,
                    onValueChange = {
                        intervalEndDateText = it
                        requestKey = null
                        message = null
                    },
                    label = { Text("Interval end date (optional)") },
                    placeholder = { Text("2026-10-23") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = intervalEndTimeText,
                    onValueChange = {
                        intervalEndTimeText = it
                        requestKey = null
                        message = null
                    },
                    label = { Text("Interval end time (optional)") },
                    placeholder = { Text("19:30") },
                    modifier = Modifier.fillMaxWidth(),
                )
                intervalProblem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(
                    value = unresolvedReason,
                    onValueChange = { unresolvedReason = it; requestKey = null; message = null },
                    label = { Text("Why this reading is incomplete (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (event == null && span != null && existingEvents.any {
                        it.spanStart == span.start && it.spanEnd == span.end &&
                            it.sourceRevision == capture.revision
                    }
                ) {
                    Text(
                        "This exact source range already has a current interpretation. Edit that event instead of adding a duplicate.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                message?.let { conflict ->
                    Text(
                        conflict,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            Button(shape = MaterialTheme.shapes.small, enabled = canSave && !(event == null && span != null && existingEvents.any {
                it.spanStart == span.start && it.spanEnd == span.end &&
                    it.sourceRevision == capture.revision
            }),
            onClick = {
                val chosenSpan = if (event == null) span else SourceSpan(event.spanStart, event.spanEnd)
                val resolvedSpan = chosenSpan ?: return@Button
                val invalidDate = dateText.takeIf(String::isNotBlank)?.let(ManualDateTime::dateError)
                val invalidTime = when {
                    timeText.isBlank() -> null
                    dateText.isBlank() -> "A stated time needs its date."
                    else -> ManualDateTime.timeError(timeText)
                }
                if (invalidDate != null || invalidTime != null || intervalProblem != null) {
                    return@Button
                }
                val amount = if (amountText.isBlank()) null else amountText.toLongOrNull()
                if ((amountText.isNotBlank() && amount == null) || (amount != null && amount <= 0L) ||
                    (amount == null && unresolvedReason.isBlank())
                ) return@Button
            
                saving = true
                message = null
                val key = requestKey ?: UUID.randomUUID().toString().also { requestKey = it }
                coroutineScope.launch {
                    try {
                        val outcome = commandService.updateEventInterpretation(
                            WorkflowCallerScope.User,
                            UpdateEventRequest(
                                idempotencyKey = key,
                                captureId = capture.id,
                                expectedCaptureRevision = capture.revision,
                                expectedEventRevision = event?.revision,
                                interpretation = NoteEventInterpretation(
                                    spanStart = resolvedSpan.start,
                                    spanEnd = resolvedSpan.end,
                                    normalizedText = readingText.trim(),
                                    amountVnd = amount,
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
                                    unresolvedReason = unresolvedReason.trim().takeIf(String::isNotEmpty),
                                ),
                                extractionVersion = event?.extractionVersion ?: MANUAL_EXTRACTION_VERSION,
                            ),
                        )
                        if (outcome.isEventSaved()) {
                            onSaved()
                        } else {
                            requestKey = null
                            message = outcome.interpretationFailureMessage()
                            if (outcome is WorkflowCommandOutcome.Conflict) {
                                conflicted = true
                                onConflict()
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        requestKey = null
                        message = "Save failed. Your interpretation is still here; retry after checking the source."
                    } finally {
                        saving = false
                    }
                }
            },) { Text(if (saving) "Saving…" else "Save interpretation") }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun CandidateDraftBrowserDialog(
    event: NoteEventEntity,
    capture: CaptureMessageEntity,
    queryService: WorkflowQueryService,
    commandService: WorkflowCommandService,
    onDismiss: () -> Unit,
    onContextChanged: () -> Unit,
    onRecordManually: (Long) -> Unit,
) {
    var candidateOffset by rememberSaveable(event.id) { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    var candidatePage by remember(event.id) { mutableStateOf<QueryPage<PendingBankDraftEntity>?>(null) }
    var loading by remember(event.id) { mutableStateOf(true) }
    var error by remember(event.id) { mutableStateOf<String?>(null) }
    var links by remember(event.id) { mutableStateOf(emptyList<LinkOperation>()) }
    var actionMessage by remember(event.id) { mutableStateOf<String?>(null) }
    var actionInProgress by remember(event.id) { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(queryService, event.id, event.revision, capture.revision, candidateOffset, reload) {
        loading = true
        error = null
        try {
            candidatePage = queryService.draftCandidates(
                DraftCandidateQuery(
                    reviewStatus = IntakeReviewStatus.PENDING,
                    bounds = QueryPageRequest(limit = CANDIDATE_PAGE_SIZE, offset = candidateOffset),
                ),
            )
            links = queryService.linkOperations(
                LinkOperationQuery(
                    eventId = event.id,
                    bounds = QueryPageRequest(limit = EVENT_LINK_PAGE_SIZE),
                ),
            ).items
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "Pending bank drafts could not be loaded. No match was chosen."
        } finally {
            loading = false
        }
    }

    val page = candidatePage
    val sourceIsCurrent = event.sourceRevision == capture.revision && event.normalizedText != null
    val activeLink = links.firstOrNull {
        it.state == ContextLinkState.PROPOSED || it.state == ContextLinkState.ACCEPTED
    }
    val manualAllowed =
        sourceIsCurrent &&
            event.unresolvedReason == null &&
            event.amountVnd != null &&
            event.resolvedTransactionId == null &&
            activeLink == null

    AlertDialog(
        onDismissRequest = { if (!actionInProgress) onDismiss() },
        title = { Text("Pending drafts for event #${event.id}") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Source note #${capture.id} · interpretation: ${event.normalizedText.orEmpty()}")
                Text(
                    "The list is bounded and never selects a match automatically. Rejecting a " +
                        "draft is permanent for this event/draft pair.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!sourceIsCurrent) {
                    Text(
                        "Source conflict: this interpretation no longer matches the current note. Re-read it before linking.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (loading && page == null) Text("Loading pending drafts…")
                error?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { reload++ }) { Text("Retry candidates") }
                }
                if (!loading && error == null && page?.items.isNullOrEmpty() && candidateOffset == 0) {
                    Text(
                        "No pending bank drafts are available. This event stays unmatched; you can record it manually now without waiting for a notification.",
                    )
                }
                page?.items.orEmpty().forEach { candidate ->
                    CandidateDraftCard(
                        draft = candidate,
                        links = links,
                        enabled = sourceIsCurrent && !actionInProgress,
                        onAttach = {
                            actionInProgress = true
                            actionMessage = null
                            coroutineScope.launch {
                                try {
                                    val outcome = decideUserLink(
                                        commandService = commandService,
                                        eventId = event.id,
                                        draftId = candidate.id,
                                        attach = true,
                                        overrideNote = "User explicitly selected this draft in Quick Capture.",
                                    )
                                    actionMessage = if (outcome.isAppliedOrNoChange()) {
                                        "Draft #${candidate.id} attached. The bank amount and ledger are unchanged."
                                    } else {
                                        outcome.linkFailureMessage()
                                    }
                                    reload++
                                    onContextChanged()
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    actionMessage = "Attach failed. No financial amount was changed; try again after reloading."
                                } finally {
                                    actionInProgress = false
                                }
                            }
                        },
                        onReject = {
                            actionInProgress = true
                            actionMessage = null
                            coroutineScope.launch {
                                try {
                                    val outcome = decideUserLink(
                                        commandService = commandService,
                                        eventId = event.id,
                                        draftId = candidate.id,
                                        attach = false,
                                        overrideNote = "User explicitly rejected this draft in Quick Capture.",
                                    )
                                    actionMessage = if (outcome.isAppliedOrNoChange()) {
                                        "Draft #${candidate.id} rejected. That decision is retained."
                                    } else {
                                        outcome.linkFailureMessage()
                                    }
                                    reload++
                                    onContextChanged()
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    actionMessage = "Reject failed. No decision was changed; reload and try again."
                                } finally {
                                    actionInProgress = false
                                }
                            }
                        },
                    )
                }
                if (page != null && (page.hasMore || candidateOffset > 0)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(
                            onClick = { candidateOffset = (candidateOffset - CANDIDATE_PAGE_SIZE).coerceAtLeast(0) },
                            enabled = candidateOffset > 0 && !loading && !actionInProgress,
                        ) { Text("Previous drafts") }
                        Text("Drafts ${candidateOffset + 1}–${candidateOffset + page.items.size}")
                        TextButton(
                            onClick = { candidateOffset += page.items.size },
                            enabled = page.hasMore && page.items.isNotEmpty() && !loading && !actionInProgress,
                        ) { Text("Next drafts") }
                    }
                }
                if (actionInProgress) Text("Saving explicit decision…")
                actionMessage?.let { message ->
                    Text(
                        message,
                        color = if (message.contains("attached.") || message.contains("rejected.")) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        },
        confirmButton = {
            if (manualAllowed) {
                Button(shape = MaterialTheme.shapes.small, enabled = !actionInProgress,
                onClick = { onRecordManually(event.id) },) { Text("Record manually") }
            } else {
                TextButton(onClick = onDismiss, enabled = !actionInProgress) { Text("Close") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !actionInProgress) { Text("Close") } },
    )
}

@Composable
private fun CandidateDraftCard(
    draft: PendingBankDraftEntity,
    links: List<LinkOperation>,
    enabled: Boolean,
    onAttach: () -> Unit,
    onReject: () -> Unit,
) {
    val pair = links.firstOrNull { it.draftId == draft.id }
    val otherActive = links.any {
        it.draftId != draft.id &&
            (it.state == ContextLinkState.PROPOSED || it.state == ContextLinkState.ACCEPTED)
    }
    CapturePanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("${draft.bank} · bank draft #${draft.id}", fontWeight = FontWeight.SemiBold)
            Text(candidateDraftSummary(draft))
            Text("Bank amount: ${draft.amountVnd?.let { VndMoney.formatAmount(it) } ?: "unresolved"} · ${draft.direction}")
            Text(
                "Bank date/time: ${draft.statedDate.orEmpty()} ${draft.statedTime.orEmpty()}".trim(),
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Bank text: ${draft.memo ?: "No bank memo"}", style = MaterialTheme.typography.bodySmall)
            when (pair?.state) {
                ContextLinkState.ACCEPTED -> Text("Already attached to this event.")
                ContextLinkState.PROPOSED -> Text("Proposed; choose Attach or Reject to resolve it.")
                ContextLinkState.REJECTED -> Text("Previously rejected. This pair cannot be proposed again.")
                null -> if (otherActive) Text("Unlink the current draft before choosing another.")
            }
            if (pair?.state != ContextLinkState.REJECTED && pair?.state != ContextLinkState.ACCEPTED) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(shape = MaterialTheme.shapes.small, onClick = onAttach,
                    enabled = enabled && !otherActive,) { Text("Attach draft") }
                    OutlinedButton(shape = MaterialTheme.shapes.small, onClick = onReject,
                    enabled = enabled && !otherActive,) { Text("Reject permanently") }
                }
            }
        }
    }
}

internal suspend fun decideUserLink(
    commandService: WorkflowCommandService,
    eventId: Long,
    draftId: Long,
    attach: Boolean,
    overrideNote: String,
): WorkflowCommandOutcome {
    val proposed = commandService.proposeLink(
        WorkflowCallerScope.User,
        ProposeLinkRequest(
            idempotencyKey = UUID.randomUUID().toString(),
            eventId = eventId,
            draftId = draftId,
        ),
    )
    val link = proposed.linkDecision() ?: return proposed
    if (link.state == ContextLinkState.REJECTED) {
        return WorkflowCommandOutcome.Denied(
            io.github.triet4p.aegisf.data.WorkflowDenialReason.LINK_ALREADY_REJECTED,
        )
    }
    if (attach && link.state == ContextLinkState.ACCEPTED) {
        return WorkflowCommandOutcome.NoChange(link, "The link is already attached.")
    }
    return if (attach) {
        commandService.attachLink(
            WorkflowCallerScope.User,
            AttachLinkRequest(
                idempotencyKey = UUID.randomUUID().toString(),
                eventId = eventId,
                draftId = draftId,
                expectedRevision = link.revision,
                userOverride = overrideNote,
            ),
        )
    } else {
        commandService.rejectLink(
            WorkflowCallerScope.User,
            RejectLinkRequest(
                idempotencyKey = UUID.randomUUID().toString(),
                eventId = eventId,
                draftId = draftId,
                expectedRevision = link.revision,
                userOverride = overrideNote,
            ),
        )
    }
}

private fun WorkflowCommandOutcome.linkDecision(): WorkflowChange.LinkDecided? = when (this) {
    is WorkflowCommandOutcome.Applied -> change as? WorkflowChange.LinkDecided
    is WorkflowCommandOutcome.NoChange -> change as? WorkflowChange.LinkDecided
    else -> null
}

private fun WorkflowCommandOutcome.isEventSaved(): Boolean = when (this) {
    is WorkflowCommandOutcome.Applied -> change is WorkflowChange.EventStored ||
        change is WorkflowChange.EventRevised
    is WorkflowCommandOutcome.NoChange -> change is WorkflowChange.EventStored ||
        change is WorkflowChange.EventRevised
    else -> false
}

internal fun WorkflowCommandOutcome.isAppliedOrNoChange(): Boolean =
    this is WorkflowCommandOutcome.Applied || this is WorkflowCommandOutcome.NoChange

private fun WorkflowCommandOutcome.interpretationFailureMessage(): String = when (this) {
    is WorkflowCommandOutcome.Conflict -> "Conflict: $reason. Your interpretation remains here; review the refreshed source before retrying."
    is WorkflowCommandOutcome.NeedsClarification -> reason
    is WorkflowCommandOutcome.Denied -> "The interpretation was not saved: ${reason.name.lowercase().replace('_', ' ')}."
    is WorkflowCommandOutcome.Failed -> reason
    WorkflowCommandOutcome.NotFound -> "The saved capture no longer exists. Your interpretation remains here."
    WorkflowCommandOutcome.Cancelled -> "Save was cancelled. Your interpretation remains here."
    is WorkflowCommandOutcome.Applied,
    is WorkflowCommandOutcome.NoChange,
    -> "The interpretation was not saved. Your input remains here."
}

internal fun WorkflowCommandOutcome.linkFailureMessage(): String = when (this) {
    is WorkflowCommandOutcome.Conflict -> "Conflict: $reason. The decision was not changed; reload and try again."
    is WorkflowCommandOutcome.Denied -> when (reason) {
        io.github.triet4p.aegisf.data.WorkflowDenialReason.LINK_ALREADY_REJECTED ->
            "This event/draft pair was already rejected; that decision cannot be undone."
        io.github.triet4p.aegisf.data.WorkflowDenialReason.MULTIPLE_ACTIVE_DRAFTS ->
            "Unlink the currently active draft before selecting another."
        io.github.triet4p.aegisf.data.WorkflowDenialReason.EVENT_ALREADY_RECORDED ->
            "This event is already recorded manually and cannot be linked again."
        io.github.triet4p.aegisf.data.WorkflowDenialReason.EVENT_UNAVAILABLE ->
            "This interpretation is unavailable. Re-read the current source before linking."
        else -> "The link decision was refused: ${reason.name.lowercase().replace('_', ' ')}."
    }
    is WorkflowCommandOutcome.Failed -> reason
    WorkflowCommandOutcome.NotFound -> "The event or draft is no longer available."
    WorkflowCommandOutcome.Cancelled -> "The link decision was cancelled."
    is WorkflowCommandOutcome.NeedsClarification -> reason
    is WorkflowCommandOutcome.Applied,
    is WorkflowCommandOutcome.NoChange,
    -> "The link decision did not complete. Reload and try again."
}

private fun currentSourceExcerpt(event: NoteEventEntity, capture: CaptureMessageEntity): String? {
    val source = capture.bodyText ?: return null
    if (event.sourceRevision != capture.revision || event.spanStart < 0 ||
        event.spanEnd <= event.spanStart || event.spanEnd > source.length
    ) return null
    return source.substring(event.spanStart, event.spanEnd)
}

private data class SourceSpan(val start: Int, val end: Int)

private fun uniqueSourceSpan(source: String, excerpt: String): SourceSpan? {
    if (excerpt.isBlank()) return null
    val first = source.indexOf(excerpt)
    if (first < 0 || source.indexOf(excerpt, first + 1) >= 0) return null
    return SourceSpan(first, first + excerpt.length)
}

private fun candidateDraftSummary(draft: PendingBankDraftEntity): String = buildString {
    append(draft.reviewStatus.name.lowercase().replace('_', ' '))
    draft.memo?.takeIf(String::isNotBlank)?.let { append(" · ").append(it) }
}

private fun eventLoadingLink(links: List<LinkOperation>, loading: Boolean): Boolean =
    loading && links.isEmpty()
