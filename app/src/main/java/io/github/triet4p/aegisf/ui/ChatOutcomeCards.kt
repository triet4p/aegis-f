package io.github.triet4p.aegisf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.triet4p.aegisf.agent.WorkflowIntent
import io.github.triet4p.aegisf.agent.WorkflowRunOutcome
import io.github.triet4p.aegisf.agent.WorkflowVerifiedEffect
import io.github.triet4p.aegisf.data.CaptureMessageEntity
import io.github.triet4p.aegisf.data.ContextLinkState
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.LinkOperation
import io.github.triet4p.aegisf.data.NoteEventEntity
import io.github.triet4p.aegisf.data.NoteEventStatus
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.data.RejectLinkRequest
import io.github.triet4p.aegisf.data.UnlinkLinkRequest
import io.github.triet4p.aegisf.data.VndMoney
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowCommandOutcome
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * One visible reference a Chat outcome actually shows. Follow-up messages resolve only
 * against these cards: a reference that names no visible card never mutates an arbitrary
 * draft, and an ambiguous reference asks instead of choosing.
 */
internal enum class ChatVisibleRefKind { EVENT, DRAFT, LINK }

internal data class ChatVisibleRef(
    val kind: ChatVisibleRefKind,
    val id: Long,
    val label: String,
)

/**
 * The cards one settled outcome makes visible, in display order. Only the effects the
 * executor verified (or truthfully settled as no-effect) appear; model prose never adds
 * a reference.
 */
internal fun visibleRefsOf(outcome: WorkflowRunOutcome?): List<ChatVisibleRef> {
    val effects = (outcome as? WorkflowRunOutcome.Completed)?.verifiedEffects ?: return emptyList()
    val refs = ArrayList<ChatVisibleRef>()
    effects.forEach { effect ->
        when (effect) {
            is WorkflowVerifiedEffect.EventReading ->
                refs += ChatVisibleRef(ChatVisibleRefKind.EVENT, effect.eventId, "event #${effect.eventId}")
            is WorkflowVerifiedEffect.LinkState -> {
                refs += ChatVisibleRef(ChatVisibleRefKind.LINK, effect.linkId, "link #${effect.linkId}")
                refs += ChatVisibleRef(ChatVisibleRefKind.DRAFT, effect.draftId, "draft #${effect.draftId}")
            }
            is WorkflowVerifiedEffect.DraftCategory ->
                refs += ChatVisibleRef(ChatVisibleRefKind.DRAFT, effect.draftId, "draft #${effect.draftId}")
            is WorkflowVerifiedEffect.ReplayIdentity -> Unit
            is WorkflowVerifiedEffect.NoEffect -> Unit
        }
    }
    return refs.distinct()
}

/**
 * Resolves a follow-up message against the visible cards.
 *
 * A kind-qualified reference (`event #3`, `draft #5`) resolves only to that exact visible
 * card; naming a row that is not visible resolves to nothing. A bare `#7` resolves only
 * when exactly one visible card carries that id. A bare pronoun (`that one`, `it`)
 * resolves only when exactly one card is visible. Anything else is ambiguous and returns
 * null so the UI asks instead of mutating an arbitrary draft.
 */
internal fun resolveFollowUpReference(
    input: String,
    visible: List<ChatVisibleRef>,
): ChatVisibleRef? {
    if (visible.isEmpty()) return null
    val qualifiedMentions =
        Regex("""\b(event|draft|link)\s*#?\s*(\d+)""", RegexOption.IGNORE_CASE)
            .findAll(input)
            .toList()
    if (qualifiedMentions.isNotEmpty()) {
        val matched = qualifiedMentions.mapNotNull { mention ->
            val kind = when (mention.groupValues[1].lowercase()) {
                "event" -> ChatVisibleRefKind.EVENT
                "draft" -> ChatVisibleRefKind.DRAFT
                else -> ChatVisibleRefKind.LINK
            }
            val id = mention.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            visible.firstOrNull { ref -> ref.kind == kind && ref.id == id }
        }.distinct()
        return if (matched.size == 1 && matched.size == qualifiedMentions.size) matched.single() else null
    }
    val bare = Regex("""#(\d+)""").findAll(input)
        .mapNotNull { it.groupValues[1].toLongOrNull() }
        .toSet()
    if (bare.isNotEmpty()) {
        val named = visible.filter { ref -> ref.id in bare }
        return if (named.size == 1) named.single() else null
    }
    val pronoun = Regex(
        """\b(that one|that|this one|this|it)\b""",
        RegexOption.IGNORE_CASE,
    ).containsMatchIn(input)
    if (pronoun && visible.size == 1) return visible.single()
    return null
}

/**
 * The actionable Chat result: the hosted answer plus one live card per verified effect,
 * the durable run state, and the explicit actions the current state allows. Every card
 * re-reads its rows from the query surface, so a card always reports what is stored
 * now — not what the run claimed before a later edit, rejection, or restart.
 */
@Composable
internal fun ChatOutcomeSection(
    outcome: WorkflowRunOutcome,
    agentRunState: ChatAgentRunState,
    agentRunScope: CoroutineScope,
    queryService: WorkflowQueryService,
    commandService: WorkflowCommandService,
    onRecordEvent: (Long) -> Unit,
    onContextChanged: () -> Unit,
    onRetry: (String, Set<WorkflowIntent>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (outcome) {
            is WorkflowRunOutcome.Completed -> {
                Text(outcome.answer, style = MaterialTheme.typography.bodyMedium)
                ChatRunStateLine(
                    agentRunState = agentRunState,
                    queryService = queryService,
                )
                if (outcome.verifiedEffects.isEmpty()) {
                    Text(
                        "Verified application changes: none.",
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                outcome.verifiedEffects.forEach { effect ->
                    ChatEffectCard(
                        effect = effect,
                        queryService = queryService,
                        commandService = commandService,
                        onRecordEvent = onRecordEvent,
                        onContextChanged = onContextChanged,
                    )
                }
            }
            is WorkflowRunOutcome.Incomplete -> {
                Text(
                    "Run incomplete: ${outcome.reason}. You can retry or continue manually.",
                    color = MaterialTheme.colorScheme.error,
                )
                ChatRunStateLine(
                    agentRunState = agentRunState,
                    queryService = queryService,
                )
                val lastPrompt = agentRunState.lastPrompt
                if (lastPrompt != null) {
                    Button(
                        onClick = { onRetry(lastPrompt, agentRunState.lastIntents) },
                        enabled = !agentRunState.busy,
                    ) { Text("Retry last request") }
                    Text(
                        "Retry re-sends the same request through the same provider and scope; " +
                            "stored rows are re-read first, so a repeated retry cannot duplicate an event, " +
                            "link, or ledger record.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            else -> Unit
        }
        if (outcome.followUp != null) {
            ChatFollowUpRow(
                agentRunState = agentRunState,
                agentRunScope = agentRunScope,
            )
        }
    }
}

@Composable
private fun ChatRunStateLine(
    agentRunState: ChatAgentRunState,
    queryService: WorkflowQueryService,
) {
    val runId = agentRunState.runId
    var storedState by remember(runId) { mutableStateOf<String?>(null) }
    LaunchedEffect(runId) {
        storedState = null
        if (runId == null) return@LaunchedEffect
        storedState = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                queryService.runState(runId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }
    Text(
        when {
            runId == null -> "Run state: not admitted."
            storedState == null -> "Run state: unavailable (run ${runId.takeLast(8)})."
            else -> "Run state: ${storedState!!.lowercase()} (run ${runId.takeLast(8)})."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ChatEffectCard(
    effect: WorkflowVerifiedEffect,
    queryService: WorkflowQueryService,
    commandService: WorkflowCommandService,
    onRecordEvent: (Long) -> Unit,
    onContextChanged: () -> Unit,
) {
    when (effect) {
        is WorkflowVerifiedEffect.EventReading -> ChatEventCard(
            effect = effect,
            queryService = queryService,
            commandService = commandService,
            onRecordEvent = onRecordEvent,
            onContextChanged = onContextChanged,
        )
        is WorkflowVerifiedEffect.LinkState -> ChatLinkCard(
            effect = effect,
            queryService = queryService,
            commandService = commandService,
            onContextChanged = onContextChanged,
        )
        is WorkflowVerifiedEffect.DraftCategory -> ChatCategoryCard(
            effect = effect,
            queryService = queryService,
        )
        is WorkflowVerifiedEffect.ReplayIdentity -> ChatReplayCard(
            effect = effect,
            queryService = queryService,
        )
        is WorkflowVerifiedEffect.NoEffect.EmptySearch -> CapturePanel(
            modifier = Modifier.fillMaxWidth(),
            accent = MaterialTheme.colorScheme.outlineVariant,
        ) {
            Text(
                "No stored draft matched the bounded search. Nothing was attached.",
                modifier = Modifier.padding(12.dp),
            )
        }
        is WorkflowVerifiedEffect.NoEffect.LinkNoMatch -> CapturePanel(
            modifier = Modifier.fillMaxWidth(),
            accent = MaterialTheme.colorScheme.outlineVariant,
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Event #${effect.eventId} stayed unlinked.")
                Text(
                    "${effect.candidatesExamined} candidate(s) examined; none was attachable. " +
                        "Choose one explicitly or record the event manually.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        is WorkflowVerifiedEffect.NoEffect.PolicyRefusal -> Text(
            "Request refused as outside the declared catalog. No application change was made.",
            color = MaterialTheme.colorScheme.error,
        )
        is WorkflowVerifiedEffect.NoEffect.AlreadyInterpreted -> Text(
            "Event #${effect.eventId} already carries that verified reading " +
                "(revision ${effect.eventRevision}). Nothing was rewritten.",
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ChatEventCard(
    effect: WorkflowVerifiedEffect.EventReading,
    queryService: WorkflowQueryService,
    commandService: WorkflowCommandService,
    onRecordEvent: (Long) -> Unit,
    onContextChanged: () -> Unit,
) {
    var event by remember(effect.eventId) { mutableStateOf<NoteEventEntity?>(null) }
    var capture by remember(effect.captureId) { mutableStateOf<CaptureMessageEntity?>(null) }
    var reload by remember(effect.eventId) { mutableIntStateOf(0) }
    var loadError by remember(effect.eventId) { mutableStateOf(false) }
    var showEditor by remember(effect.eventId) { mutableStateOf(false) }
    var showBrowser by remember(effect.eventId) { mutableStateOf(false) }

    LaunchedEffect(queryService, effect.eventId, effect.captureId, reload) {
        loadError = false
        try {
            event = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                queryService.noteEvent(effect.eventId)
            }
            capture = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                queryService.sourceExcerpt(effect.captureId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadError = true
        }
    }
    CapturePanel(
        modifier = Modifier.fillMaxWidth(),
        accent = MaterialTheme.colorScheme.primary,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Event #${effect.eventId}", fontWeight = FontWeight.SemiBold)
            val currentEvent = event
            val currentCapture = capture
            when {
                loadError -> {
                    Text("The stored reading could not be loaded.", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { reload++ }) { Text("Reload event") }
                }
                currentEvent == null -> Text(
                    "This reading is no longer stored. A later correction replaced it; " +
                        "the card shows the run receipt only.",
                    color = MaterialTheme.colorScheme.error,
                )
                else -> {
                    val sourceCurrent = currentCapture != null &&
                        currentEvent.sourceRevision == currentCapture.revision &&
                        currentEvent.spanStart >= 0 &&
                        currentEvent.spanEnd > currentEvent.spanStart &&
                        currentEvent.spanEnd <= (currentCapture.bodyText?.length ?: -1)
                    val excerpt = if (sourceCurrent) {
                        currentCapture!!.bodyText!!.substring(currentEvent.spanStart, currentEvent.spanEnd)
                    } else {
                        null
                    }
                    Text(
                        when (currentEvent.status) {
                            NoteEventStatus.INTERPRETED -> "Interpretation ready · revision ${currentEvent.revision}"
                            NoteEventStatus.UNRESOLVED -> "Unresolved · ${currentEvent.unresolvedReason ?: "needs review"}"
                            NoteEventStatus.NO_MATCH -> "Unmatched · no active bank draft"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (effect.revision != currentEvent.revision) {
                        Text(
                            "Changed since this run: the run stored revision ${effect.revision}, " +
                                "the current revision is ${currentEvent.revision}. Actions below " +
                                "use the current revision.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (!sourceCurrent) {
                        Text(
                            "Source conflict: this reading came from capture revision " +
                                "${currentEvent.sourceRevision}. Re-read the current source before " +
                                "linking or recording.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text("Selected source excerpt: \u201c${excerpt.orEmpty()}\u201d")
                        Text("Reading: ${currentEvent.normalizedText.orEmpty()}")
                    }
                    currentEvent.amountVnd?.let { amount ->
                        Text("Interpretation amount: ${VndMoney.formatAmount(amount)}")
                    }
                    currentEvent.merchant?.let { Text("Merchant: $it") }
                    val timeLine = eventTimeLine(currentEvent)
                    if (timeLine != null) Text(timeLine)
                    if (currentEvent.resolvedTransactionId != null) {
                        Text("Recorded manually as ledger record #${currentEvent.resolvedTransactionId}.")
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            shape = MaterialTheme.shapes.small,
                            onClick = { showEditor = true },
                        ) { Text(if (sourceCurrent) "Edit interpretation" else "Review source") }
                        OutlinedButton(
                            shape = MaterialTheme.shapes.small,
                            onClick = { showBrowser = true },
                            enabled = sourceCurrent && currentEvent.resolvedTransactionId == null,
                        ) { Text("Browse drafts") }
                    }
                    val recordable = sourceCurrent &&
                        currentEvent.unresolvedReason == null &&
                        currentEvent.amountVnd != null &&
                        currentEvent.resolvedTransactionId == null
                    if (recordable) {
                        Button(
                            shape = MaterialTheme.shapes.small,
                            onClick = { onRecordEvent(currentEvent.id) },
                        ) { Text("Record manually") }
                    }
                }
            }
        }
    }

    val dialogEvent = event
    val dialogCapture = capture
    if (showEditor && dialogEvent != null && dialogCapture != null) {
        InterpretationEditorDialog(
            capture = dialogCapture,
            event = dialogEvent,
            existingEvents = listOf(dialogEvent),
            commandService = commandService,
            onDismiss = { showEditor = false },
            onSaved = {
                showEditor = false
                reload++
                onContextChanged()
            },
            onConflict = {
                reload++
                onContextChanged()
            },
        )
    }
    if (showBrowser && dialogEvent != null && dialogCapture != null) {
        CandidateDraftBrowserDialog(
            event = dialogEvent,
            capture = dialogCapture,
            queryService = queryService,
            commandService = commandService,
            onDismiss = { showBrowser = false },
            onContextChanged = {
                reload++
                onContextChanged()
            },
            onRecordManually = { eventId ->
                showBrowser = false
                onRecordEvent(eventId)
            },
        )
    }
}

@Composable
private fun ChatLinkCard(
    effect: WorkflowVerifiedEffect.LinkState,
    queryService: WorkflowQueryService,
    commandService: WorkflowCommandService,
    onContextChanged: () -> Unit,
) {
    var link by remember(effect.linkId) { mutableStateOf<LinkOperation?>(null) }
    var draft by remember(effect.draftId) { mutableStateOf<PendingBankDraftEntity?>(null) }
    var reload by remember(effect.linkId) { mutableIntStateOf(0) }
    var loadError by remember(effect.linkId) { mutableStateOf(false) }
    var actionMessage by remember(effect.linkId) { mutableStateOf<String?>(null) }
    var actionInProgress by remember(effect.linkId) { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(queryService, effect.linkId, effect.draftId, reload) {
        loadError = false
        try {
            link = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                queryService.linkOperation(effect.linkId)
            }
            draft = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                queryService.draftCandidate(effect.draftId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadError = true
        }
    }

    CapturePanel(
        modifier = Modifier.fillMaxWidth(),
        accent = when (effect.state) {
            ContextLinkState.ACCEPTED -> MaterialTheme.colorScheme.secondary
            ContextLinkState.PROPOSED -> MaterialTheme.colorScheme.tertiary
            ContextLinkState.REJECTED -> MaterialTheme.colorScheme.outlineVariant
        },
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val live = link
            Text(
                when (live?.state ?: effect.state) {
                    ContextLinkState.PROPOSED -> "Proposed · event #${effect.eventId} to bank draft #${effect.draftId} (not attached)"
                    ContextLinkState.ACCEPTED -> "Attached · event #${effect.eventId} to bank draft #${effect.draftId}"
                    ContextLinkState.REJECTED -> "Rejected · event #${effect.eventId} to bank draft #${effect.draftId} (decision retained)"
                },
                fontWeight = FontWeight.Medium,
            )
            if (draft != null) {
                Text(chatDraftLine(draft!!), style = MaterialTheme.typography.bodySmall)
            } else if (!loadError) {
                Text(
                    "Loading bank draft #${effect.draftId}…",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text("Bank draft #${effect.draftId} could not be loaded.", style = MaterialTheme.typography.bodySmall)
            }
            if (loadError) {
                Text("The stored link could not be loaded.", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { reload++ }) { Text("Reload link") }
            }
            if (live != null && live.revision != effect.revision) {
                Text(
                    "Changed since this run: the run reported revision ${effect.revision}, " +
                        "the current revision is ${live.revision}. Actions below use the " +
                        "current revision.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val storedEvidence = live?.evidence
            val evidenceLine = when {
                draft == null -> null
                !storedEvidence.isNullOrBlank() -> storedEvidence
                else -> "recorded with this proposal (no conflicting evidence)."
            }
            evidenceLine?.let {
                Text("Stored evidence: $it", style = MaterialTheme.typography.bodySmall)
            }
            live?.userOverride?.takeIf { it.isNotBlank() }?.let {
                Text("Decision note: $it", style = MaterialTheme.typography.bodySmall)
            }
            if (actionInProgress) Text("Saving link decision…")
            actionMessage?.let { message ->
                Text(
                    message,
                    color = if (message.startsWith("Draft #") && message.contains("attached.")) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            val current = live
            if (current != null && !actionInProgress) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (current.state == ContextLinkState.PROPOSED) {
                        Button(
                            shape = MaterialTheme.shapes.small,
                            onClick = {
                                actionInProgress = true
                                actionMessage = null
                                coroutineScope.launch {
                                    try {
                                        val outcome = decideUserLink(
                                            commandService = commandService,
                                            eventId = current.eventId,
                                            draftId = current.draftId,
                                            attach = true,
                                            overrideNote = "User explicitly attached this draft in Chat.",
                                        )
                                        actionMessage = if (outcome.isAppliedOrNoChange()) {
                                            "Draft #${current.draftId} attached. The bank amount and ledger are unchanged."
                                        } else {
                                            outcome.linkFailureMessage()
                                        }
                                        reload++
                                        onContextChanged()
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        actionMessage = "Attach failed. No financial amount was changed; reload and try again."
                                    } finally {
                                        actionInProgress = false
                                    }
                                }
                            },
                        ) { Text("Attach draft") }
                        OutlinedButton(
                            shape = MaterialTheme.shapes.small,
                            onClick = {
                                actionInProgress = true
                                actionMessage = null
                                coroutineScope.launch {
                                    try {
                                        val outcome = commandService.rejectLink(
                                            WorkflowCallerScope.User,
                                            RejectLinkRequest(
                                                idempotencyKey = UUID.randomUUID().toString(),
                                                eventId = current.eventId,
                                                draftId = current.draftId,
                                                expectedRevision = current.revision,
                                                userOverride = "User explicitly rejected this draft in Chat.",
                                            ),
                                        )
                                        actionMessage = if (outcome.isAppliedOrNoChange()) {
                                            "Draft #${current.draftId} rejected. That decision is retained."
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
                        ) { Text("Reject") }
                    }
                    if (current.state == ContextLinkState.ACCEPTED) {
                        when (draft?.reviewStatus) {
                            IntakeReviewStatus.PENDING -> OutlinedButton(
                                shape = MaterialTheme.shapes.small,
                                onClick = {
                                    actionInProgress = true
                                    actionMessage = null
                                    coroutineScope.launch {
                                        try {
                                            val outcome = commandService.unlinkLink(
                                                WorkflowCallerScope.User,
                                                UnlinkLinkRequest(
                                                    idempotencyKey = UUID.randomUUID().toString(),
                                                    eventId = current.eventId,
                                                    draftId = current.draftId,
                                                    expectedRevision = current.revision,
                                                ),
                                            )
                                            actionMessage = if (outcome.isAppliedOrNoChange()) {
                                                "Draft unlinked. The event can be attached to another draft or recorded manually."
                                            } else {
                                                outcome.linkFailureMessage()
                                            }
                                            reload++
                                            onContextChanged()
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            actionMessage = "Unlink failed. The stored decision is unchanged; try again."
                                        } finally {
                                            actionInProgress = false
                                        }
                                    }
                                },
                            ) { Text("Unlink draft #${current.draftId}") }
                            null -> Text(
                                "Loading bank draft #${current.draftId}…",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            else -> Text(
                                "This draft is a confirmed ledger record. The note reference is " +
                                    "kept as provenance and the ledger is unchanged.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            if (current?.state == ContextLinkState.REJECTED) {
                Text(
                    "This refusal cannot be undone. You may browse a different draft from the event card.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ChatCategoryCard(
    effect: WorkflowVerifiedEffect.DraftCategory,
    queryService: WorkflowQueryService,
) {
    var draft by remember(effect.draftId) { mutableStateOf<PendingBankDraftEntity?>(null) }
    var categoryName by remember(effect.categoryId) { mutableStateOf<String?>(null) }
    LaunchedEffect(queryService, effect.draftId, effect.categoryId) {
        try {
            draft = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                queryService.draftCandidate(effect.draftId)
            }
            categoryName = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                queryService.findCategory(effect.categoryId)?.name
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            draft = null
        }
    }
    CapturePanel(
        modifier = Modifier.fillMaxWidth(),
        accent = MaterialTheme.colorScheme.secondary,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Category saved on draft #${effect.draftId}", fontWeight = FontWeight.Medium)
            Text(
                when {
                    categoryName != null -> "Chosen category: $categoryName (review state only)."
                    else -> "Chosen category #${effect.categoryId} is no longer in the catalog; " +
                        "the stored choice was refused or superseded."
                },
            )
            Text(
                "Draft review status: ${draft?.reviewStatus?.name?.lowercase() ?: "unavailable"} · " +
                    "the draft stays pending and no ledger record was written.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Change the category in Review notifications; category choice never confirms a transaction.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ChatReplayCard(
    effect: WorkflowVerifiedEffect.ReplayIdentity,
    queryService: WorkflowQueryService,
) {
    var detail by remember(effect.operationKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(queryService, effect.operationKey) {
        detail = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val record = queryService.workflowOperation(effect.operationKey)
                when {
                    record == null -> "No journal row is stored for this receipt."
                    else -> "Journal ${record.command} · outcome ${record.outcome}."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                "The journal receipt could not be loaded."
            }
        }
    }
    CapturePanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Replayed operation", fontWeight = FontWeight.Medium)
            Text(
                detail ?: "Loading journal receipt…",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (effect.currentEffectMatches) {
                    "The original effect still matches the stored state; nothing was rewritten."
                } else {
                    "The original effect no longer matches: a later correction or deletion " +
                        "superseded it. The receipt stays historical."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ChatFollowUpRow(
    agentRunState: ChatAgentRunState,
    agentRunScope: CoroutineScope,
) {
    val outcome = agentRunState.outcome
    val visible = remember(outcome) { visibleRefsOf(outcome) }
    var sending by remember { mutableStateOf(false) }
    var askChoice by remember(outcome) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Follow up within the visible scope", fontWeight = FontWeight.Medium)
        if (visible.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                visible.forEach { ref ->
                    OutlinedButton(
                        shape = MaterialTheme.shapes.small,
                        onClick = {
                            agentRunState.followUpSelection =
                                if (agentRunState.followUpSelection == ref.label) null else ref.label
                            askChoice = false
                        },
                    ) {
                        Text(
                            (if (agentRunState.followUpSelection == ref.label) "✓ " else "") + ref.label,
                        )
                    }
                }
            }
        }
        OutlinedTextField(
            value = agentRunState.followUpInput,
            onValueChange = {
                agentRunState.followUpInput = it
                askChoice = false
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !sending && !agentRunState.busy,
            label = { Text("Ask about what you see above") },
        )
        if (askChoice) {
            Text(
                "Which one do you mean? Choose one of the cards above, or name its " +
                    "event, draft, or link number — nothing was changed.",
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = {
                val input = agentRunState.followUpInput.trim()
                if (input.isBlank() || sending) return@Button
                val selection = agentRunState.followUpSelection?.let { label ->
                    visible.firstOrNull { ref -> ref.label == label }
                }
                val resolved = selection ?: resolveFollowUpReference(input, visible)
                if (selection == null && resolved == null && visible.isNotEmpty()) {
                    askChoice = true
                    return@Button
                }
                val executor = agentRunState.executor
                val handle = agentRunState.followUp
                if (executor == null || handle == null) {
                    agentRunState.progress =
                        "This follow-up is no longer available. Start a new request with a fresh prompt."
                    return@Button
                }
                val message = if (resolved != null) "$input (about ${resolved.label})" else input
                sending = true
                agentRunState.progress = "Sending follow-up in the same run…"
                agentRunScope.launch {
                    try {
                        agentRunState.outcome = executor.followUp(
                            handle,
                            message,
                            progress = null,
                        )
                        agentRunState.followUp = agentRunState.outcome?.followUp
                        agentRunState.continuation =
                            (agentRunState.outcome as? WorkflowRunOutcome.Clarifying)?.continuation
                        agentRunState.followUpInput = ""
                        agentRunState.followUpSelection = null
                        agentRunState.progress = null
                        agentRunState.projectionRevision += 1
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: IllegalStateException) {
                        agentRunState.followUp = null
                        agentRunState.progress =
                            "This follow-up was already used. Start a new request with a fresh prompt."
                    } catch (_: IllegalArgumentException) {
                        agentRunState.followUp = null
                        agentRunState.progress =
                            "The follow-up belongs to another run. Start a new request with a fresh prompt."
                    } catch (_: Exception) {
                        agentRunState.progress =
                            "The follow-up could not be sent. The run stayed on its current attempt."
                    } finally {
                        sending = false
                        agentRunState.job = null
                    }
                }
            },
            enabled = !sending && !agentRunState.busy && agentRunState.followUpInput.isNotBlank(),
        ) {
            if (sending) {
                CircularProgressIndicator(Modifier.padding(end = 8.dp))
                Text("Sending…")
            } else {
                Text("Send follow-up")
            }
        }
    }
}

private fun eventTimeLine(event: NoteEventEntity): String? {
    val anchor = listOfNotNull(event.anchorDate, event.anchorTime).joinToString(" ")
    val end = listOfNotNull(event.intervalEndDate, event.intervalEndTime).joinToString(" ")
    if (anchor.isBlank() && end.isBlank() && event.anchorTimezone.isNullOrBlank()) return null
    return buildString {
        append("Stated date/time: ")
        append(anchor.ifBlank { "unspecified" })
        if (end.isNotBlank()) append(" to $end")
        event.anchorTimezone?.let { append(" · $it") }
    }.toString()
}

private fun chatDraftLine(draft: PendingBankDraftEntity): String = buildString {
    append(draft.reviewStatus.name.lowercase().replace('_', ' '))
    append(" · bank draft #${draft.id}")
    draft.amountVnd?.let { append(" · ${VndMoney.formatAmount(it)}") }
    draft.memo?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
}
