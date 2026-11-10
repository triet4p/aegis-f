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
import androidx.compose.ui.platform.testTag
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
import io.github.triet4p.aegisf.ui.chat.ChatFollowUpStrings
import io.github.triet4p.aegisf.ui.chat.ChatLinkStrings
import io.github.triet4p.aegisf.ui.chat.ChatOutcomeStrings
import io.github.triet4p.aegisf.ui.chat.ChatReplayStrings
import io.github.triet4p.aegisf.ui.chat.ChatRunStrings
import io.github.triet4p.aegisf.ui.chat.chatOutcomeStrings
import io.github.triet4p.aegisf.ui.text.LocalAegisStrings
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
    val qualifiedMentions = QUALIFIED_REFERENCE.findAll(input).toList()
    if (qualifiedMentions.isNotEmpty()) {
        val matched = qualifiedMentions.mapNotNull { mention ->
            val kind = referenceKindOf(mention.groupValues[1]) ?: return@mapNotNull null
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
    if (PRONOUN.containsMatchIn(input) && visible.size == 1) return visible.single()
    return null
}

/** A kind word in either interface language, as the cards and the follow-up chips display it. */
private const val REFERENCE_KIND_WORDS =
    "sự kiện|sự-kiện|su kien|sukien|bản nháp|ban nhap|liên kết|lien ket|event|draft|link"

private val QUALIFIED_REFERENCE = Regex(
    """(?:^|\s)($REFERENCE_KIND_WORDS)\s*#?\s*(\d+)""",
    RegexOption.IGNORE_CASE,
)

private val PRONOUN = Regex(
    """(?:^|\s)(that one|that|this one|this|it|cái đó|cái này|nó|đó)(?=\s|[.,!?;:]|$)""",
    RegexOption.IGNORE_CASE,
)

private fun referenceKindOf(word: String): ChatVisibleRefKind? = when (word.lowercase()) {
    "event", "sự kiện", "sự-kiện", "su kien", "sukien" -> ChatVisibleRefKind.EVENT
    "draft", "bản nháp", "ban nhap" -> ChatVisibleRefKind.DRAFT
    "link", "liên kết", "lien ket" -> ChatVisibleRefKind.LINK
    else -> null
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
    /** True when the screen already shows the answer in its own transcript bubble. */
    answerAlreadyShown: Boolean = false,
    strings: ChatOutcomeStrings = chatOutcomeStrings(LocalAegisStrings.current.language),
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (outcome) {
            is WorkflowRunOutcome.Completed -> {
                if (!answerAlreadyShown) {
                    Text(outcome.answer, style = MaterialTheme.typography.bodyMedium)
                }
                ChatRunStateLine(
                    agentRunState = agentRunState,
                    queryService = queryService,
                    strings = strings.run,
                )
                if (!answerAlreadyShown && outcome.verifiedEffects.isEmpty()) {
                    Text(
                        strings.noneVerified,
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
                        strings = strings,
                    )
                }
            }
            is WorkflowRunOutcome.Incomplete -> {
                if (!answerAlreadyShown) {
                    Text(
                        strings.incomplete.message(outcome.reason),
                        color = MaterialTheme.colorScheme.error,
                    )
                    ChatRunStateLine(
                        agentRunState = agentRunState,
                        queryService = queryService,
                        strings = strings.run,
                    )
                    val lastPrompt = agentRunState.lastPrompt
                    if (lastPrompt != null) {
                        Button(
                            onClick = { onRetry(lastPrompt, agentRunState.lastIntents) },
                            enabled = !agentRunState.busy,
                        ) { Text(strings.incomplete.retryLast) }
                        Text(
                            strings.incomplete.explanation,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            else -> Unit
        }
        // The follow-up authority is meaningful only beside a visible result. A plain answer
        // keeps the screen's one composer as the only input; it never stacks a second form.
        val visibleResult = (outcome as? WorkflowRunOutcome.Completed)
            ?.verifiedEffects
            ?.isNotEmpty() == true
        if (outcome.followUp != null && visibleResult) {
            ChatFollowUpRow(
                agentRunState = agentRunState,
                agentRunScope = agentRunScope,
                strings = strings.followUp,
            )
        }
    }
}

@Composable
private fun ChatRunStateLine(
    agentRunState: ChatAgentRunState,
    queryService: WorkflowQueryService,
    strings: ChatRunStrings,
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
            runId == null -> strings.notStored
            storedState == null -> strings.unreadable
            else -> strings.storedState(storedState)
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
    strings: ChatOutcomeStrings,
) {
    when (effect) {
        is WorkflowVerifiedEffect.EventReading -> ChatEventCard(
            effect = effect,
            queryService = queryService,
            commandService = commandService,
            onRecordEvent = onRecordEvent,
            onContextChanged = onContextChanged,
            strings = strings,
        )
        is WorkflowVerifiedEffect.LinkState -> ChatLinkCard(
            effect = effect,
            queryService = queryService,
            commandService = commandService,
            onContextChanged = onContextChanged,
            strings = strings,
        )
        is WorkflowVerifiedEffect.DraftCategory -> ChatCategoryCard(
            effect = effect,
            queryService = queryService,
            strings = strings,
        )
        is WorkflowVerifiedEffect.ReplayIdentity -> ChatReplayCard(
            effect = effect,
            queryService = queryService,
            strings = strings.replay,
        )
        is WorkflowVerifiedEffect.NoEffect.EmptySearch -> CapturePanel(
            modifier = Modifier.fillMaxWidth(),
            accent = MaterialTheme.colorScheme.outlineVariant,
        ) {
            Text(
                strings.noEffect.emptySearch,
                modifier = Modifier.padding(12.dp),
            )
        }
        is WorkflowVerifiedEffect.NoEffect.LinkNoMatch -> CapturePanel(
            modifier = Modifier.fillMaxWidth(),
            accent = MaterialTheme.colorScheme.outlineVariant,
        ) {
            Text(
                strings.noEffect.linkNoMatch(
                    eventId = effect.eventId,
                    candidatesExamined = effect.candidatesExamined,
                    language = strings.language,
                ),
                modifier = Modifier.padding(12.dp),
            )
        }
        is WorkflowVerifiedEffect.NoEffect.PolicyRefusal -> Text(
            strings.noEffect.policyRefusal,
            color = MaterialTheme.colorScheme.error,
        )
        is WorkflowVerifiedEffect.NoEffect.AlreadyInterpreted -> Text(
            strings.noEffect.alreadyInterpreted(
                eventId = effect.eventId,
                revision = effect.eventRevision,
                language = strings.language,
            ),
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
    strings: ChatOutcomeStrings,
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
            Text(strings.eventTitle(effect.eventId), fontWeight = FontWeight.SemiBold)
            val currentEvent = event
            val currentCapture = capture
            when {
                loadError -> {
                    Text(strings.event.loadError, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { reload++ }) { Text(strings.event.reload) }
                }
                currentEvent == null -> Text(
                    strings.event.missing,
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
                            NoteEventStatus.INTERPRETED ->
                                strings.event.interpreted(currentEvent.revision)
                            NoteEventStatus.UNRESOLVED ->
                                strings.event.unresolved(
                                    currentEvent.unresolvedReason ?: strings.event.needsReview,
                                )
                            NoteEventStatus.NO_MATCH -> strings.event.noMatch()
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (effect.revision != currentEvent.revision) {
                        Text(
                            strings.event.changedSinceRun(
                                reported = effect.revision,
                                current = currentEvent.revision,
                            ),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (!sourceCurrent) {
                        Text(
                            strings.event.sourceConflict(currentEvent.sourceRevision),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text(strings.event.excerpt(excerpt.orEmpty()))
                        Text(strings.event.reading(currentEvent.normalizedText.orEmpty()))
                    }
                    currentEvent.amountVnd?.let { amount ->
                        Text(strings.event.amount(VndMoney.formatAmount(amount)))
                    }
                    currentEvent.merchant?.let { Text(strings.event.merchant(it)) }
                    val timeLine = eventTimeLine(currentEvent, strings)
                    if (timeLine != null) Text(timeLine)
                    if (currentEvent.resolvedTransactionId != null) {
                        Text(strings.event.recordedManually(currentEvent.resolvedTransactionId))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            shape = MaterialTheme.shapes.small,
                            onClick = { showEditor = true },
                        ) {
                            Text(
                                if (sourceCurrent) {
                                    strings.event.editInterpretation
                                } else {
                                    strings.event.reviewSource
                                },
                            )
                        }
                        OutlinedButton(
                            shape = MaterialTheme.shapes.small,
                            onClick = { showBrowser = true },
                            enabled = sourceCurrent && currentEvent.resolvedTransactionId == null,
                        ) { Text(strings.event.browseDrafts) }
                    }
                    val recordable = sourceCurrent &&
                        currentEvent.unresolvedReason == null &&
                        currentEvent.amountVnd != null &&
                        currentEvent.resolvedTransactionId == null
                    if (recordable) {
                        Button(
                            shape = MaterialTheme.shapes.small,
                            onClick = { onRecordEvent(currentEvent.id) },
                        ) { Text(strings.event.recordManually) }
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
    strings: ChatOutcomeStrings,
) {
    var link by remember(effect.linkId) { mutableStateOf<LinkOperation?>(null) }
    var draft by remember(effect.draftId) { mutableStateOf<PendingBankDraftEntity?>(null) }
    var reload by remember(effect.linkId) { mutableIntStateOf(0) }
    var loadError by remember(effect.linkId) { mutableStateOf(false) }
    var actionMessage by remember(effect.linkId) { mutableStateOf<String?>(null) }
    var actionSucceeded by remember(effect.linkId) { mutableStateOf<Boolean?>(null) }
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
        modifier = Modifier
            .fillMaxWidth()
            .testTag("chat-link-${effect.linkId}"),
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
                strings.link.state(
                    state = live?.state ?: effect.state,
                    eventId = effect.eventId,
                    draftId = effect.draftId,
                ),
                fontWeight = FontWeight.Medium,
            )
            if (draft != null) {
                Text(chatDraftLine(draft!!, strings.link), style = MaterialTheme.typography.bodySmall)
            } else if (!loadError) {
                Text(
                    strings.link.loading(effect.draftId),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    strings.link.draftLoadFailed(effect.draftId),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (loadError) {
                Text(strings.link.loadError, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { reload++ }) { Text(strings.link.reload) }
            }
            // Only a same-state revision drift is a concurrent change worth warning about. A
            // revision the user's own Attach/Reject just advanced is the expected outcome, not
            // a stale card.
            if (live != null && live.state == effect.state && live.revision != effect.revision) {
                Text(
                    strings.link.changedSinceRun(
                        reported = effect.revision,
                        current = live.revision,
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // The stored canonical evidence is an internal proof, not user copy: it is
            // deliberately rendered as one plain sentence instead of the encoded blob.
            val evidenceLine = when {
                draft == null -> null
                !live?.evidence.isNullOrBlank() -> strings.link.matchedByStoredEvidence()
                else -> strings.link.noConflictingEvidence()
            }
            evidenceLine?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            live?.userOverride
                ?.takeIf { it.isNotBlank() }
                ?.let(strings.link::decisionNote)
                ?.let { note -> Text(note, style = MaterialTheme.typography.bodySmall) }
            if (actionInProgress) Text(strings.link.saving)
            actionMessage?.let { message ->
                Text(
                    message,
                    color = if (actionSucceeded == true) {
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
                            modifier = Modifier.testTag("chat-link-${effect.linkId}-attach"),
                            onClick = {
                                actionInProgress = true
                                actionMessage = null
                                actionSucceeded = null
                                coroutineScope.launch {
                                    try {
                                        val outcome = decideUserLink(
                                            commandService = commandService,
                                            eventId = current.eventId,
                                            draftId = current.draftId,
                                            attach = true,
                                            overrideNote = "User explicitly attached this draft in Chat.",
                                        )
                                        actionSucceeded = outcome.isAppliedOrNoChange()
                                        actionMessage = if (actionSucceeded == true) {
                                            strings.link.attachedMessage(current.draftId)
                                        } else {
                                            strings.link.failure(outcome)
                                        }
                                        reload++
                                        onContextChanged()
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        actionSucceeded = false
                                        actionMessage = strings.link.attachFailed
                                    } finally {
                                        actionInProgress = false
                                    }
                                }
                            },
                        ) { Text(strings.link.attachAction) }
                        OutlinedButton(
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.testTag("chat-link-${effect.linkId}-reject"),
                            onClick = {
                                actionInProgress = true
                                actionMessage = null
                                actionSucceeded = null
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
                                        actionSucceeded = outcome.isAppliedOrNoChange()
                                        actionMessage = if (actionSucceeded == true) {
                                            strings.link.rejectedMessage(current.draftId)
                                        } else {
                                            strings.link.failure(outcome)
                                        }
                                        reload++
                                        onContextChanged()
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        actionSucceeded = false
                                        actionMessage = strings.link.rejectFailed
                                    } finally {
                                        actionInProgress = false
                                    }
                                }
                            },
                        ) { Text(strings.link.rejectAction) }
                    }
                    if (current.state == ContextLinkState.ACCEPTED) {
                        when (draft?.reviewStatus) {
                            IntakeReviewStatus.PENDING -> OutlinedButton(
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.testTag("chat-link-${effect.linkId}-unlink"),
                                onClick = {
                                    actionInProgress = true
                                    actionMessage = null
                                    actionSucceeded = null
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
                                            actionSucceeded = outcome.isAppliedOrNoChange()
                                            actionMessage = if (actionSucceeded == true) {
                                                strings.link.unlinkedMessage()
                                            } else {
                                                strings.link.failure(outcome)
                                            }
                                            reload++
                                            onContextChanged()
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            actionSucceeded = false
                                            actionMessage = strings.link.unlinkFailed
                                        } finally {
                                            actionInProgress = false
                                        }
                                    }
                                },
                            ) { Text(strings.link.unlinkAction(current.draftId)) }
                            null -> Text(
                                strings.link.loading(current.draftId),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            else -> Text(
                                strings.link.confirmedLedgerRecord(),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            if (current?.state == ContextLinkState.REJECTED) {
                Text(
                    strings.link.rejectionFinal,
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
    strings: ChatOutcomeStrings,
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
            Text(strings.category.saved(effect.draftId), fontWeight = FontWeight.Medium)
            Text(
                categoryName?.let(strings.category::chosen)
                    ?: strings.category.unavailable(effect.categoryId),
            )
            Text(
                strings.category.reviewStatus(
                    strings.link.reviewStatus(draft?.reviewStatus),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                strings.category.changeInReview,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ChatReplayCard(
    effect: WorkflowVerifiedEffect.ReplayIdentity,
    queryService: WorkflowQueryService,
    strings: ChatReplayStrings,
) {
    var detail by remember(effect.operationKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(queryService, effect.operationKey) {
        detail = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val record = queryService.workflowOperation(effect.operationKey)
                when {
                    record == null -> strings.missing
                    else -> strings.journal(record.command, record.outcome)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                strings.loadError
            }
        }
    }
    CapturePanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(strings.title, fontWeight = FontWeight.Medium)
            Text(
                detail ?: strings.loading,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (effect.currentEffectMatches) strings.stillMatches else strings.superseded,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ChatFollowUpRow(
    agentRunState: ChatAgentRunState,
    agentRunScope: CoroutineScope,
    strings: ChatFollowUpStrings,
) {
    val outcome = agentRunState.outcome
    val visible = remember(outcome) { visibleRefsOf(outcome) }
    var sending by remember { mutableStateOf(false) }
    var askChoice by remember(outcome) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(strings.heading, fontWeight = FontWeight.Medium)
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
                            (if (agentRunState.followUpSelection == ref.label) "✓ " else "") +
                                followUpRefLabel(ref, strings),
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
            label = { Text(strings.askLabel) },
        )
        if (askChoice) {
            Text(
                strings.ambiguous,
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
                    agentRunState.progress = strings.unavailable
                    return@Button
                }
                // The provider sees the canonical reference, so a localized chip cannot change
                // the meaning of the follow-up it resolves to.
                val message = if (resolved != null) {
                    strings.about(resolved.label, input)
                } else {
                    input
                }
                sending = true
                agentRunState.progress = strings.sending
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
                        agentRunState.progress = strings.alreadyUsed
                    } catch (_: IllegalArgumentException) {
                        agentRunState.followUp = null
                        agentRunState.progress = strings.otherRun
                    } catch (_: Exception) {
                        agentRunState.progress = strings.failed
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
                Text(strings.sending)
            } else {
                Text(strings.send)
            }
        }
    }
}

private fun followUpRefLabel(ref: ChatVisibleRef, strings: ChatFollowUpStrings): String =
    when (ref.kind) {
        ChatVisibleRefKind.EVENT -> strings.eventRef(ref.id)
        ChatVisibleRefKind.DRAFT -> strings.draftRef(ref.id)
        ChatVisibleRefKind.LINK -> strings.linkRef(ref.id)
    }

private fun eventTimeLine(event: NoteEventEntity, strings: ChatOutcomeStrings): String? {
    val anchor = listOfNotNull(event.anchorDate, event.anchorTime).joinToString(" ")
    val end = listOfNotNull(event.intervalEndDate, event.intervalEndTime).joinToString(" ")
    if (anchor.isBlank() && end.isBlank() && event.anchorTimezone.isNullOrBlank()) return null
    return strings.event.statedDateTime(
        anchor = anchor,
        end = end,
        zone = event.anchorTimezone,
    )
}

private fun chatDraftLine(draft: PendingBankDraftEntity, strings: ChatLinkStrings): String =
    strings.draftLine(
        status = draft.reviewStatus,
        draftId = draft.id,
        amount = draft.amountVnd?.let { VndMoney.formatAmount(it) },
        memo = draft.memo,
    )
