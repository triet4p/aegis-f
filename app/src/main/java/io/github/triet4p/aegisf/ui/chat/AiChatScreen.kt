package io.github.triet4p.aegisf.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.triet4p.aegisf.agent.AgentRunFailure
import io.github.triet4p.aegisf.agent.AgentRunHistory
import io.github.triet4p.aegisf.agent.BoundedWorkflowExecutor
import io.github.triet4p.aegisf.agent.UncertainWriteVerdict
import io.github.triet4p.aegisf.agent.WorkflowIntent
import io.github.triet4p.aegisf.agent.WorkflowRunHost
import io.github.triet4p.aegisf.agent.WorkflowRunOutcome
import io.github.triet4p.aegisf.agent.WorkflowRunProgress
import io.github.triet4p.aegisf.agent.WorkflowRunProgressSink
import io.github.triet4p.aegisf.agent.WorkflowRunRequest
import io.github.triet4p.aegisf.agent.WorkflowRunStopReason
import io.github.triet4p.aegisf.data.CaptureKind
import io.github.triet4p.aegisf.data.CaptureSourceKind
import io.github.triet4p.aegisf.data.DraftCandidateQuery
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.LIST_PAGE_SIZE
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.SourceQuery
import io.github.triet4p.aegisf.data.SourceState
import io.github.triet4p.aegisf.data.VndMoney
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.privacy.MaskingBlockedException
import io.github.triet4p.aegisf.provider.HostedProviderConfig
import io.github.triet4p.aegisf.provider.SavedHostedProvider
import io.github.triet4p.aegisf.ui.ChatAnswerState
import io.github.triet4p.aegisf.ui.ChatAgentRunState
import io.github.triet4p.aegisf.ui.ChatNoteContext
import io.github.triet4p.aegisf.ui.ChatHistoryStatus
import io.github.triet4p.aegisf.ui.ChatOutcomeSection
import io.github.triet4p.aegisf.ui.ChatStatusTone
import io.github.triet4p.aegisf.ui.ChatTranscriptEntry
import io.github.triet4p.aegisf.ui.ChatDraftContext
import io.github.triet4p.aegisf.ui.chatRequestText
import io.github.triet4p.aegisf.ui.draftContextEvidence
import io.github.triet4p.aegisf.ui.historyStatusOf
import io.github.triet4p.aegisf.ui.text.AegisStrings
import io.github.triet4p.aegisf.ui.text.LocalAegisStrings
import io.github.triet4p.aegisf.ui.components.AegisGlyph
import io.github.triet4p.aegisf.ui.components.AegisIcon
import io.github.triet4p.aegisf.ui.theme.LocalAegisSemanticColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The workflow skills one chat request may use. The assistant decides which of them the request
 * actually needs; naming all of them keeps the conversation from exposing internal skill switches.
 */
private val CHAT_INTENTS: Set<WorkflowIntent> = WorkflowIntent.entries.toSet()

/** The provider the Chat screen may send to, or why it could not read one. */
private data class ProviderSnapshot(
    val provider: SavedHostedProvider?,
    val readFailed: Boolean,
)

/** What the attached note's current stored text says. */
private sealed interface AttachedContextCheck {
    data object NoContext : AttachedContextCheck
    data object Checking : AttachedContextCheck
    data object Current : AttachedContextCheck
    data object Unreadable : AttachedContextCheck
    data object Missing : AttachedContextCheck
    data class Changed(val currentText: String) : AttachedContextCheck
}

/** What the pending bank draft attached from Review currently is. */
private sealed interface DraftContextCheck {
    data object NoDraft : DraftContextCheck
    data object Checking : DraftContextCheck
    data class Current(val draft: PendingBankDraftEntity) : DraftContextCheck
    data object Missing : DraftContextCheck
    data object Decided : DraftContextCheck
    data object Unreadable : DraftContextCheck
}

/**
 * The dedicated AI Chat destination: one conversation, one primary action.
 *
 * The screen sends an explicit request, shows the answer while it is still arriving, and offers
 * Stop while it runs. It never configures a provider inline (that is a focused child route), never
 * sends an attached note on its own, and never synthesizes answer text: every visible character
 * is either the user's own words, text the masked provider route actually delivered, or a
 * lifecycle line. A stopped exchange keeps the text already received; a late chunk has no token
 * and is dropped. History is read from the durable run rows and replaces the transcript instead of
 * appending to it, so re-entry and recreation cannot duplicate a turn or imply an automatic resume.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiChatScreen(
    queryService: WorkflowQueryService,
    commandService: WorkflowCommandService,
    activeProvider: () -> SavedHostedProvider?,
    networkAvailable: Boolean,
    createHostedWorkflowHost: (WorkflowCallerScope.AgentSession, HostedProviderConfig) -> WorkflowRunHost,
    agentRunHistory: AgentRunHistory,
    agentRunState: ChatAgentRunState,
    agentRunScope: CoroutineScope,
    onOpenProviderSettings: () -> Unit,
    onRecordEvent: (Long) -> Unit,
) {
    val baseStrings = LocalAegisStrings.current
    val strings = remember(baseStrings.language) { chatStrings(baseStrings.language) }
    val outcomeStrings = remember(baseStrings.language) { chatOutcomeStrings(baseStrings.language) }
    val semantic = LocalAegisSemanticColors.current
    var prompt by rememberSaveable { mutableStateOf("") }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var providerAttempt by remember { mutableIntStateOf(0) }
    val providerSnapshot = remember(providerAttempt) {
        runCatching { activeProvider() }.fold(
            onSuccess = { ProviderSnapshot(it, readFailed = false) },
            onFailure = { ProviderSnapshot(null, readFailed = true) },
        )
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) providerAttempt += 1
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var contextCheck by remember { mutableStateOf<AttachedContextCheck>(AttachedContextCheck.NoContext) }
    LaunchedEffect(agentRunState.noteContext?.captureId, agentRunState.noteContext?.text) {
        val context = agentRunState.noteContext
        if (context == null) {
            contextCheck = AttachedContextCheck.NoContext
            return@LaunchedEffect
        }
        contextCheck = AttachedContextCheck.Checking
        contextCheck = readAttachedContext(queryService, context)
    }
    // The attached draft is read live, so a confirmation or dismissal elsewhere becomes visible
    // on the card at once and a stale snapshot is never sent as if it were current evidence.
    var draftCheck by remember { mutableStateOf<DraftContextCheck>(DraftContextCheck.NoDraft) }
    val attachedDraftId = agentRunState.draftContext?.draftId
    LaunchedEffect(queryService, attachedDraftId) {
        if (attachedDraftId == null) {
            draftCheck = DraftContextCheck.NoDraft
            return@LaunchedEffect
        }
        draftCheck = DraftContextCheck.Checking
        try {
            queryService.observeDraftCandidate(attachedDraftId).collect { draft ->
                draftCheck = when {
                    draft == null -> DraftContextCheck.Missing
                    draft.reviewStatus != IntakeReviewStatus.PENDING ||
                        draft.confirmedTransactionId != null -> DraftContextCheck.Decided
                    else -> DraftContextCheck.Current(draft)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            draftCheck = DraftContextCheck.Unreadable
        }
    }

    val listState = rememberLazyListState()
    var autoFollow by remember { mutableStateOf(true) }
    var autoScrolling by remember { mutableStateOf(false) }
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            info.totalItemsCount == 0 ||
                (last.index >= info.totalItemsCount - 1 &&
                    last.offset + last.size <= info.viewportEndOffset + 8)
        }
    }
    suspend fun scrollToLatest() {
        val target = listState.layoutInfo.totalItemsCount - 1
        if (target < 0) return
        autoScrolling = true
        try {
            listState.animateScrollToItem(target)
        } finally {
            autoScrolling = false
        }
    }
    // A manual drag pauses following so reading an older turn is not yanked away. Scrolling
    // back to the bottom, or pressing the jump action, resumes it.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && !autoScrolling) autoFollow = false
        }
    }
    LaunchedEffect(atBottom) {
        if (atBottom) autoFollow = true
    }

    // Re-read the newest durable conversation once, after recreation. This is a read: no run is
    // claimed, no provider request starts, and a stale RUNNING row is shown as interrupted.
    LaunchedEffect(agentRunHistory) {
        if (agentRunState.initialLoadAttempted) return@LaunchedEffect
        agentRunState.initialLoadAttempted = true
        if (agentRunState.messages.isNotEmpty() || agentRunState.busy) return@LaunchedEffect
        try {
            val latest = withContext(Dispatchers.IO) { agentRunHistory.recentRuns(1).firstOrNull() }
                ?: return@LaunchedEffect
            val lines = withContext(Dispatchers.IO) {
                agentRunHistory.conversationForRun(latest.runId)
            }
            if (lines.isNotEmpty()) {
                agentRunState.restoreConversation(
                    run = latest,
                    lines = lines,
                    interruptedLine = strings.interruptedLine,
                    clarificationExpiredLine = strings.clarificationExpiredLine,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A failed read leaves the empty composer usable; it never fabricates a transcript.
        }
    }

    // Follow the stream only while the reader is already at the bottom. Each visible chunk
    // changes the transcript state, so this effect re-anchors the newest turn without waiting
    // for a new list item. An empty transcript stays at the top: the explicit attached context
    // must be fully readable before the first send, not scrolled under the app bar.
    LaunchedEffect(
        agentRunState.messages,
        agentRunState.outcome,
    ) {
        if (autoFollow && agentRunState.messages.isNotEmpty()) {
            scrollToLatest()
        }
    }

    fun settle(token: Int, outcome: WorkflowRunOutcome, runId: String) {
        when (outcome) {
            is WorkflowRunOutcome.Completed -> {
                agentRunState.completeTurn(token, outcome.answer, runId)
                agentRunState.outcome = outcome
                agentRunState.followUp = outcome.followUp
            }

            is WorkflowRunOutcome.Clarifying -> {
                agentRunState.clarifyTurn(token, outcome.question, runId)
                agentRunState.outcome = outcome
                agentRunState.continuation = outcome.continuation
                agentRunState.followUp = outcome.followUp
                agentRunState.clarificationAnswer = ""
            }

            is WorkflowRunOutcome.Refused ->
                agentRunState.failTurn(token, strings.errorRefused, runId)

            is WorkflowRunOutcome.Incomplete ->
                agentRunState.failTurn(token, incompleteMessage(outcome, strings), runId)
        }
    }

    fun startRun(
        promptText: String,
        attached: ChatNoteContext?,
        provider: SavedHostedProvider,
        draftEvidence: String? = null,
    ) {
        autoFollow = true
        val token = agentRunState.beginTurn(promptText)
        prompt = ""
        agentRunState.job = agentRunScope.launch {
            var admittedRunId: String? = null
            var executor: BoundedWorkflowExecutor? = null
            try {
                val pendingDraftPage = withContext(Dispatchers.IO) {
                    queryService.draftCandidates(
                        DraftCandidateQuery(
                            reviewStatus = IntakeReviewStatus.PENDING,
                            bounds = QueryPageRequest(limit = 32, offset = 0),
                        ),
                    )
                }
                val captureIds = withContext(Dispatchers.IO) {
                    queryService.sourceExcerpts(
                        SourceQuery(
                            kind = CaptureKind.USER_NOTE,
                            sourceKind = CaptureSourceKind.USER_QUICK_CAPTURE,
                            state = SourceState.ACTIVE,
                            bounds = QueryPageRequest(limit = LIST_PAGE_SIZE, offset = 0),
                        ),
                    ).items.map { it.id }.toSet()
                }
                val sessionScope = WorkflowCallerScope.AgentSession(
                    scopeId = "chat-${UUID.randomUUID()}",
                    captureIds = captureIds,
                    draftIds = pendingDraftPage.items.map { it.id }.toSet(),
                    draftScopeComplete = !pendingDraftPage.hasMore,
                )
                val request = WorkflowRunRequest(
                    intents = CHAT_INTENTS,
                    // The visible attached note and draft are named only here, at the moment
                    // the user sends; removing either card removes it from the next request.
                    userRequest = chatRequestText(promptText, attached, draftEvidence),
                    requiredIntents = emptySet(),
                )
                val host = createHostedWorkflowHost(
                    sessionScope,
                    provider.validatedConfig(sessionScope.scopeId),
                )
                val activeExecutor = BoundedWorkflowExecutor(host)
                executor = activeExecutor
                val admitted = agentRunHistory.admitRun(
                    sessionScope,
                    request,
                    host.boundary,
                    provider.maxOutputTokens,
                )
                admittedRunId = admitted.runId
                agentRunState.runId = admitted.runId
                agentRunState.executor = activeExecutor
                agentRunState.lastScope = sessionScope
                agentRunState.lastPrompt = promptText
                agentRunState.lastIntents = request.intents
                agentRunState.lastRequest = request
                agentRunState.lastAttempt = admitted.attempt
                agentRunState.lastBoundary = host.boundary
                agentRunState.lastMaxOutputTokens = provider.maxOutputTokens
                agentRunState.progress = strings.waiting
                val outcome = activeExecutor.startTracked(
                    admitted = admitted,
                    request = request,
                    history = agentRunHistory,
                    maxOutputTokens = provider.maxOutputTokens,
                    progress = runProgressSink(token, agentRunState, strings),
                )
                if (agentRunState.isActive(token)) {
                    settle(token, outcome, admitted.runId)
                }
            } catch (cancelled: CancellationException) {
                admittedRunId?.let { runId ->
                    withContext(NonCancellable) {
                        runCatching {
                            executor?.cancelTracked(agentRunHistory, runId, "USER_CANCELLED")
                        }
                    }
                }
                throw cancelled
            } catch (_: MaskingBlockedException) {
                if (agentRunState.isActive(token)) {
                    agentRunState.failTurn(token, strings.errorMasking, agentRunState.runId)
                }
            } catch (_: Exception) {
                if (agentRunState.isActive(token)) {
                    agentRunState.failTurn(token, strings.errorGeneric, agentRunState.runId)
                }
            } finally {
                if (agentRunState.job == coroutineContext[Job]) agentRunState.job = null
                agentRunState.progress = null
                agentRunState.projectionRevision += 1
            }
        }
    }

    fun submit() {
        val text = prompt.trim()
        if (text.isEmpty() || agentRunState.busy || !networkAvailable) return
        val provider = providerSnapshot.provider
        if (provider == null) {
            prompt = text
            return
        }
        val draftContext = agentRunState.draftContext
        val draftEvidence = if (draftContext == null) {
            null
        } else {
            val checked = draftCheck
            if (checked !is DraftContextCheck.Current) {
                // Confirmed, dismissed, missing or still being checked: the visible card says
                // why, and nothing is sent with a context the user has not resolved.
                return
            }
            draftContextEvidence(checked.draft)
        }
        val context = agentRunState.noteContext
        if (context == null) {
            startRun(text, null, provider, draftEvidence)
            return
        }
        agentRunScope.launch {
            contextCheck = AttachedContextCheck.Checking
            val current = withContext(Dispatchers.IO) { readAttachedContext(queryService, context) }
            contextCheck = current
            when (current) {
                AttachedContextCheck.Current -> startRun(text, context, provider, draftEvidence)
                AttachedContextCheck.NoContext -> startRun(text, null, provider, draftEvidence)
                // Changed or missing: nothing is sent until the user resolves the visible card.
                else -> Unit
            }
        }
    }

    fun stop() {
        if (!agentRunState.stopTurn(strings.stoppedLine, strings.stoppedBeforeReply)) return
        agentRunState.job?.cancel()
        agentRunState.job = null
        agentRunState.progress = null
        agentRunState.projectionRevision += 1
        // The coroutine's cancellation path cancels the durable run under NonCancellable. This
        // extra call covers a run already admitted when the job was between callbacks.
        val runId = agentRunState.runId
        val executor = agentRunState.executor
        if (runId != null && executor != null) {
            agentRunScope.launch(Dispatchers.IO) {
                runCatching { executor.cancelTracked(agentRunHistory, runId, "USER_CANCELLED") }
            }
        }
    }

    fun retryLast() {
        if (agentRunState.busy) return
        val promptText = agentRunState.lastUserText ?: return
        val request = agentRunState.lastRequest ?: return
        val scope = agentRunState.lastScope ?: return
        val executor = agentRunState.executor ?: return
        val boundary = agentRunState.lastBoundary ?: return
        val runId = agentRunState.runId ?: return
        val attempt = agentRunState.lastAttempt ?: return
        val provider = providerSnapshot.provider ?: return
        autoFollow = true
        val token = agentRunState.beginTurn(promptText)
        agentRunState.runId = runId
        agentRunState.job = agentRunScope.launch {
            try {
                val verdict = agentRunHistory.reconcileUncertainWrite(runId, attempt)
                if (verdict !is UncertainWriteVerdict.NotApplied) {
                    agentRunState.failTurn(token, strings.retryUncertain, runId)
                    return@launch
                }
                val reopened = agentRunHistory.reopenForRetry(scope, runId)
                if (reopened == null) {
                    agentRunState.failTurn(token, strings.retryUnavailable, runId)
                    return@launch
                }
                agentRunState.lastAttempt = reopened.attempt
                agentRunState.progress = strings.waiting
                val outcome = executor.startWithAttemptKey(
                    request = request,
                    attemptKey = reopened.attemptKey,
                    maxOutputTokens = provider.maxOutputTokens,
                    progress = runProgressSink(token, agentRunState, strings),
                )
                agentRunHistory.recordAttemptOutcome(
                    runId = runId,
                    attempt = reopened.attempt,
                    outcome = outcome,
                    boundary = boundary,
                )
                if (agentRunState.isActive(token)) settle(token, outcome, runId)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    runCatching { executor.cancelTracked(agentRunHistory, runId, "USER_CANCELLED") }
                }
                throw cancelled
            } catch (_: Exception) {
                if (agentRunState.isActive(token)) {
                    agentRunState.failTurn(token, strings.errorGeneric, runId)
                }
            } finally {
                if (agentRunState.job == coroutineContext[Job]) agentRunState.job = null
                agentRunState.progress = null
                agentRunState.projectionRevision += 1
            }
        }
    }

    fun continueClarification() {
        if (agentRunState.busy) return
        val answer = agentRunState.clarificationAnswer.trim()
        if (answer.isEmpty()) return
        val runId = agentRunState.runId
        val executor = agentRunState.executor
        val continuation = agentRunState.continuation
        if (runId == null || executor == null || continuation == null) {
            agentRunState.progress = strings.clarificationUnavailable
            return
        }
        autoFollow = true
        val token = agentRunState.beginTurn(answer)
        agentRunState.runId = runId
        agentRunState.clarificationAnswer = ""
        agentRunState.job = agentRunScope.launch {
            try {
                agentRunState.progress = strings.waiting
                val outcome = executor.resumeTracked(
                    history = agentRunHistory,
                    runId = runId,
                    continuation = continuation,
                    userAnswer = answer,
                    progress = runProgressSink(token, agentRunState, strings),
                )
                if (outcome == null) {
                    if (agentRunState.isActive(token)) {
                        agentRunState.failTurn(token, strings.clarificationUnavailable, runId)
                    }
                } else if (agentRunState.isActive(token)) {
                    settle(token, outcome, runId)
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    runCatching { executor.cancelTracked(agentRunHistory, runId, "USER_CANCELLED") }
                }
                throw cancelled
            } catch (_: IllegalStateException) {
                if (agentRunState.isActive(token)) {
                    agentRunState.failTurn(token, strings.clarificationUnavailable, runId)
                }
            } catch (_: IllegalArgumentException) {
                if (agentRunState.isActive(token)) {
                    agentRunState.failTurn(token, strings.clarificationUnavailable, runId)
                }
            } catch (_: Exception) {
                if (agentRunState.isActive(token)) {
                    agentRunState.failTurn(token, strings.errorGeneric, runId)
                }
            } finally {
                if (agentRunState.job == coroutineContext[Job]) agentRunState.job = null
                agentRunState.progress = null
                agentRunState.projectionRevision += 1
            }
        }
    }

    fun startNewConversation() {
        if (agentRunState.busy) return
        agentRunState.startNewConversation()
        prompt = ""
        agentRunScope.launch { listState.scrollToItem(0) }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = strings.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp,
                    )
                },
                actions = {
                    IconButton(
                        onClick = { historyOpen = true },
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        AegisIcon(
                            glyph = AegisGlyph.HISTORY,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.semantics {
                                contentDescription = strings.historyAction
                            },
                        )
                    }
                    IconButton(
                        onClick = { startNewConversation() },
                        enabled = !agentRunState.busy,
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        AegisIcon(
                            glyph = AegisGlyph.ADD,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.semantics {
                                contentDescription = strings.newChatAction
                            },
                        )
                    }
                },
            )
        },
        bottomBar = {
            ChatComposerBar(
                value = prompt,
                onValueChange = { prompt = it },
                provider = providerSnapshot,
                strings = strings,
                online = networkAvailable,
                busy = agentRunState.busy,
                contextBlocked = (agentRunState.noteContext != null &&
                    (contextCheck is AttachedContextCheck.Changed ||
                        contextCheck is AttachedContextCheck.Missing ||
                        contextCheck is AttachedContextCheck.Unreadable ||
                        contextCheck is AttachedContextCheck.Checking)) ||
                    (agentRunState.draftContext != null &&
                        draftCheck !is DraftContextCheck.Current),
                clarificationPending = agentRunState.outcome is WorkflowRunOutcome.Clarifying &&
                    agentRunState.continuation != null,
                onSend = ::submit,
                onStop = ::stop,
                onOpenProviderSettings = onOpenProviderSettings,
                onRefreshProvider = { providerAttempt += 1 },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("chat-transcript"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!networkAvailable) {
                    item(key = "offline") {
                        OfflineBanner(strings = strings)
                    }
                }
                item(key = "provider-strip") {
                    ProviderStrip(
                        snapshot = providerSnapshot,
                        strings = strings,
                    )
                }
                val context = agentRunState.noteContext
                if (context != null) {
                    item(key = "context") {
                        AttachedContextCard(
                            context = context,
                            check = contextCheck,
                            strings = strings,
                            baseStrings = baseStrings,
                            enabled = !agentRunState.busy,
                            onRemove = {
                                agentRunState.noteContext = null
                                contextCheck = AttachedContextCheck.NoContext
                            },
                            onUseCurrent = { current ->
                                agentRunState.noteContext = context.copy(text = current)
                            },
                        )
                    }
                }
                if (agentRunState.draftContext != null) {
                    item(key = "draft-context") {
                        AttachedDraftContextCard(
                            check = draftCheck,
                            strings = strings,
                            enabled = !agentRunState.busy,
                            onRemove = {
                                agentRunState.draftContext = null
                                draftCheck = DraftContextCheck.NoDraft
                            },
                        )
                    }
                }
                agentRunState.restoredRunState?.let { state ->
                    item(key = "restored") {
                        RestoredConversationLine(runState = state, strings = strings)
                    }
                }
                if (agentRunState.messages.isEmpty() && !agentRunState.busy &&
                    agentRunState.outcome == null
                ) {
                    item(key = "empty") {
                        EmptyChatCard(strings = strings, semantic = semantic)
                    }
                }
                items(
                    count = agentRunState.messages.size,
                    key = { index -> agentRunState.messages[index].key },
                ) { index ->
                    ChatTranscriptMessage(
                        entry = agentRunState.messages[index],
                        language = baseStrings.language,
                        strings = strings,
                        live = agentRunState.isActive(agentRunState.activeToken ?: -1) &&
                            agentRunState.messages[index].key == agentRunState.activeAssistantKey,
                    )
                }
                val outcome = agentRunState.outcome
                if (outcome is WorkflowRunOutcome.Clarifying) {
                    item(key = "clarification") {
                        ClarificationCard(
                            agentRunState = agentRunState,
                            strings = strings,
                            onContinue = ::continueClarification,
                        )
                    }
                }
                if (outcome != null) {
                    item(key = "outcome") {
                        ChatOutcomeSection(
                            outcome = outcome,
                            agentRunState = agentRunState,
                            agentRunScope = agentRunScope,
                            queryService = queryService,
                            commandService = commandService,
                            onRecordEvent = onRecordEvent,
                            onContextChanged = { agentRunState.projectionRevision += 1 },
                            onRetry = { _, _ -> retryLast() },
                            answerAlreadyShown = true,
                            strings = outcomeStrings,
                        )
                    }
                }
                agentRunState.messages.lastOrNull()?.let { last ->
                    if (last is ChatTranscriptEntry.Assistant &&
                        last.state == ChatAnswerState.ERROR &&
                        agentRunState.executor != null &&
                        agentRunState.lastRequest != null
                    ) {
                        item(key = "retry") {
                            OutlinedButton(
                                onClick = ::retryLast,
                                enabled = !agentRunState.busy,
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            ) { Text(strings.retry) }
                        }
                    }
                }
                item(key = "bottom-space") { Spacer(Modifier.height(8.dp)) }
            }

            if (!atBottom && agentRunState.messages.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp,
                    shadowElevation = 6.dp,
                ) {
                    TextButton(
                        onClick = {
                            autoFollow = true
                            agentRunScope.launch { scrollToLatest() }
                        },
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("chat-jump-latest")
                            .semantics { contentDescription = strings.jumpLatest },
                    ) {
                        Text(strings.jumpLatest)
                    }
                }
            }
        }
    }

    if (historyOpen) {
        ChatHistorySheet(
            open = historyOpen,
            onDismiss = { historyOpen = false },
            history = agentRunHistory,
            strings = strings,
            busy = agentRunState.busy,
            activeRunId = agentRunState.runId,
            onOpen = { row ->
                agentRunScope.launch {
                    try {
                        val run = withContext(Dispatchers.IO) { agentRunHistory.runForId(row.runId) }
                            ?: return@launch
                        val lines = withContext(Dispatchers.IO) {
                            agentRunHistory.conversationForRun(row.runId)
                        }
                        agentRunState.restoreConversation(
                            run = run,
                            lines = lines,
                            interruptedLine = strings.interruptedLine,
                            clarificationExpiredLine = strings.clarificationExpiredLine,
                        )
                        historyOpen = false
                        listState.scrollToItem(0)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The sheet keeps its own error state; a failed read changes nothing.
                    }
                }
            },
        )
    }
}

private fun incompleteMessage(outcome: WorkflowRunOutcome.Incomplete, strings: ChatStrings): String {
    if (outcome.reason == WorkflowRunStopReason.MASKING_BLOCKED) return strings.errorMasking
    if (outcome.reason == WorkflowRunStopReason.PROVIDER_FAILURE) {
        return when (val failure = AgentRunFailure.ofStop(outcome.reason, outcome.detail)) {
            is AgentRunFailure.Transport,
            is AgentRunFailure.Timeout,
            is AgentRunFailure.RateLimited,
            is AgentRunFailure.Unavailable,
            -> strings.errorTransport

            else -> strings.errorGeneric
        }
    }
    return strings.errorGeneric
}

private suspend fun readAttachedContext(
    queryService: WorkflowQueryService,
    context: ChatNoteContext,
): AttachedContextCheck = try {
    val current = withContext(Dispatchers.IO) { queryService.sourceExcerpt(context.captureId) }
    val body = current?.bodyText
    when {
        body == null -> AttachedContextCheck.Missing
        body == context.text -> AttachedContextCheck.Current
        else -> AttachedContextCheck.Changed(body)
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    AttachedContextCheck.Checking
}

/**
 * The chat's live progress sink.
 *
 * Answer text arrives already filtered by the masked gateway's incremental public gate. A tool
 * event becomes a plain task label; the declared tool name, arguments and private reasoning never
 * reach the screen. Every callback is gated by the exchange token, so a stopped exchange cannot
 * receive another visible chunk.
 */
private fun runProgressSink(
    token: Int,
    agentRunState: ChatAgentRunState,
    strings: ChatStrings,
): WorkflowRunProgressSink = { item ->
    if (agentRunState.isActive(token)) {
        when (item) {
            is WorkflowRunProgress.PublicText -> agentRunState.appendChunk(token, item.text)

            // The model is producing private reasoning: show that it is working, never the
            // reasoning itself. This replaces the unqualified waiting line as soon as the
            // provider starts working, which is usually long before the first answer token.
            is WorkflowRunProgress.Reasoning ->
                agentRunState.setReasoningActivity(token, strings.thinking)

            // A tool step stays named while the provider streams its arguments and while the
            // local call runs. The provider's argument-completion event does not end it.
            is WorkflowRunProgress.ToolActivity ->
                agentRunState.setActivity(token, strings.toolActivity(item.name))

            // The executor reports the applied result; only then is that named work done.
            is WorkflowRunProgress.ToolStepFinished -> agentRunState.setActivity(token, null)

            is WorkflowRunProgress.StreamingUnavailable ->
                agentRunState.setActivity(token, strings.streamingUnavailable)
        }
    }
}

@Composable
private fun ProviderStrip(
    snapshot: ProviderSnapshot,
    strings: ChatStrings,
) {
    val provider = snapshot.provider ?: return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AegisIcon(
                glyph = AegisGlyph.CHAT,
                tint = MaterialTheme.colorScheme.primary,
                size = 18.dp,
            )
            Text(
                text = strings.providerLine(provider.name, provider.model),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun OfflineBanner(strings: ChatStrings) {
    val semantic = LocalAegisSemanticColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = semantic.warningContainer,
        contentColor = semantic.onWarningContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(strings.offlineTitle, style = MaterialTheme.typography.labelLarge)
            Text(strings.offlineBody, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AttachedContextCard(
    context: ChatNoteContext,
    check: AttachedContextCheck,
    strings: ChatStrings,
    baseStrings: AegisStrings,
    enabled: Boolean,
    onRemove: () -> Unit,
    onUseCurrent: (String) -> Unit,
) {
    val semantic = LocalAegisSemanticColors.current
    val stale = check is AttachedContextCheck.Changed || check is AttachedContextCheck.Missing
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (stale) semantic.warningContainer else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (stale) semantic.onWarningContainer else MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(baseStrings.chatAttachedNote, style = MaterialTheme.typography.labelLarge)
            Text(
                text = context.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when (check) {
                    AttachedContextCheck.Checking -> strings.contextChecking
                    AttachedContextCheck.Current, AttachedContextCheck.NoContext ->
                        baseStrings.chatAttachedNoteHelp

                    AttachedContextCheck.Missing,
                    is AttachedContextCheck.Changed,
                    -> strings.contextStale

                    AttachedContextCheck.Unreadable -> strings.contextUnreadable
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (check is AttachedContextCheck.Changed) {
                    TextButton(
                        onClick = { onUseCurrent(check.currentText) },
                        enabled = enabled,
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    ) { Text(strings.contextUseCurrent) }
                }
                TextButton(
                    onClick = onRemove,
                    enabled = enabled,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) { Text(strings.contextRemove) }
            }
        }
    }
}

@Composable
private fun AttachedDraftContextCard(
    check: DraftContextCheck,
    strings: ChatStrings,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    val semantic = LocalAegisSemanticColors.current
    val stale = check !is DraftContextCheck.Current && check !is DraftContextCheck.Checking
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("chat-draft-context"),
        shape = MaterialTheme.shapes.large,
        color = if (stale) semantic.warningContainer else semantic.infoContainer,
        contentColor = if (stale) {
            semantic.onWarningContainer
        } else {
            semantic.onInfoContainer
        },
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(strings.draftContextTitle, style = MaterialTheme.typography.labelLarge)
            when (check) {
                DraftContextCheck.Checking -> Text(
                    text = strings.draftContextChecking,
                    style = MaterialTheme.typography.bodySmall,
                )

                is DraftContextCheck.Current -> {
                    val draft = check.draft
                    val moment = listOfNotNull(draft.statedDate, draft.statedTime)
                        .filter(String::isNotBlank)
                        .joinToString(" ")
                        .ifEmpty { strings.draftContextNotStated }
                    Text(
                        text = "${strings.draftContextBank}: " +
                            draft.bank.ifBlank { strings.draftContextNotStated },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "${strings.draftContextAmount}: " +
                            (draft.amountVnd?.let { VndMoney.formatAmount(it, " ₫") }
                                ?: strings.draftContextNotStated),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "${strings.draftContextMoment}: $moment",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "${strings.draftContextDescription}: " +
                            (draft.memo?.takeIf(String::isNotBlank)
                                ?: strings.draftContextNotStated),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = strings.draftContextHelp,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                DraftContextCheck.Missing -> Text(
                    text = strings.draftContextMissing,
                    style = MaterialTheme.typography.bodySmall,
                )

                DraftContextCheck.Decided -> Text(
                    text = strings.draftContextDecided,
                    style = MaterialTheme.typography.bodySmall,
                )

                DraftContextCheck.Unreadable -> Text(
                    text = strings.draftContextUnreadable,
                    style = MaterialTheme.typography.bodySmall,
                )

                DraftContextCheck.NoDraft -> Unit
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onRemove,
                    enabled = enabled,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) { Text(strings.contextRemove) }
            }
        }
    }
}

@Composable
private fun RestoredConversationLine(
    runState: String,
    strings: ChatStrings,
) {
    val status = historyStatusOf(runState, isActive = false)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = strings.savedConversation,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            shape = MaterialTheme.shapes.small,
            color = when (status) {
                ChatHistoryStatus.COMPLETED -> MaterialTheme.colorScheme.secondaryContainer
                ChatHistoryStatus.INTERRUPTED, ChatHistoryStatus.FAILED,
                ChatHistoryStatus.NEEDS_ANSWER,
                -> LocalAegisSemanticColors.current.warningContainer

                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ) {
            Text(
                text = strings.historyStatus(status),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyChatCard(
    strings: ChatStrings,
    semantic: io.github.triet4p.aegisf.ui.theme.AegisSemanticColors,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = semantic.infoContainer,
                    contentColor = semantic.onInfoContainer,
                ) {
                    Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                        AegisIcon(AegisGlyph.CHAT, tint = semantic.onInfoContainer, size = 22.dp)
                    }
                }
                Text(
                    text = strings.emptyTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = strings.emptyBody,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChatTranscriptMessage(
    entry: ChatTranscriptEntry,
    language: io.github.triet4p.aegisf.data.AegisLanguage,
    strings: ChatStrings,
    live: Boolean,
) {
    when (entry) {
        is ChatTranscriptEntry.User -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(0.86f),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = strings.youName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Text(
                        text = chatVisibleText(entry.text, strings.maskedValue),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    text = chatRelativeMoment(entry.atMillis, language),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is ChatTranscriptEntry.Assistant -> Column(
            modifier = Modifier.fillMaxWidth(0.94f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = strings.assistantName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                shape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp),
                color = if (entry.state == ChatAnswerState.ERROR) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
                tonalElevation = 1.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val text = when {
                        entry.text.isNotEmpty() -> entry.text
                        // A specific, truthful phase label below already says the model is
                        // working; repeating the generic waiting line would contradict it.
                        entry.state == ChatAnswerState.WAITING && entry.statusLine == null ->
                            strings.waiting

                        else -> ""
                    }
                    if (text.isNotEmpty()) {
                        StreamingAnswerText(
                            text = text,
                            state = entry.state,
                            live = live,
                            strings = strings,
                        )
                    }
                    entry.statusLine?.let { line ->
                        LifecycleLine(line = line, state = entry.state)
                    }
                }
            }
            Text(
                text = chatRelativeMoment(entry.atMillis, language),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is ChatTranscriptEntry.Status -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = when (entry.tone) {
                    ChatStatusTone.WARNING -> LocalAegisSemanticColors.current.warningContainer
                    ChatStatusTone.ERROR -> MaterialTheme.colorScheme.errorContainer
                    ChatStatusTone.INFO -> LocalAegisSemanticColors.current.infoContainer
                    ChatStatusTone.MUTED -> MaterialTheme.colorScheme.surfaceVariant
                },
            ) {
                Text(
                    text = chatVisibleText(entry.text, strings.maskedValue),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StreamingAnswerText(
    text: String,
    state: ChatAnswerState,
    live: Boolean,
    strings: ChatStrings,
) {
    Text(
        text = chatMarkdown(
            chatVisibleText(
                if (live && state == ChatAnswerState.STREAMING) "$text ▍" else text,
                strings.maskedValue,
            ),
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag("chat-assistant-text"),
    )
    if (live && state == ChatAnswerState.STREAMING) {
        Text(
            text = strings.streaming,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LifecycleLine(line: String, state: ChatAnswerState) {
    val tone = when (state) {
        ChatAnswerState.ERROR, ChatAnswerState.STOPPED -> ChatStatusTone.WARNING
        ChatAnswerState.INTERRUPTED -> ChatStatusTone.WARNING
        else -> ChatStatusTone.INFO
    }
    val semantic = LocalAegisSemanticColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (tone == ChatStatusTone.WARNING) {
                    semantic.warningContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = MaterialTheme.shapes.small,
            )
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .background(
                    color = if (tone == ChatStatusTone.WARNING) {
                        semantic.warning
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    shape = RoundedCornerShape(4.dp),
                ),
        )
        Text(
            text = line,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ClarificationCard(
    agentRunState: ChatAgentRunState,
    strings: ChatStrings,
    onContinue: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("chat-clarification"),
        shape = MaterialTheme.shapes.large,
        color = LocalAegisSemanticColors.current.infoContainer,
        contentColor = LocalAegisSemanticColors.current.onInfoContainer,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The question is already the assistant's turn directly above this card; repeating
            // it here would show the same sentence twice on one screen.
            Text(strings.clarificationHeading, style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = agentRunState.clarificationAnswer,
                onValueChange = { agentRunState.clarificationAnswer = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("chat-clarification-answer"),
                label = { Text(strings.clarificationAnswerLabel) },
                minLines = 1,
                maxLines = 4,
                enabled = !agentRunState.busy,
            )
            Button(
                onClick = onContinue,
                enabled = !agentRunState.busy && agentRunState.clarificationAnswer.isNotBlank(),
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) { Text(strings.clarificationContinue) }
        }
    }
}

@Composable
private fun ChatComposerBar(
    value: String,
    onValueChange: (String) -> Unit,
    provider: ProviderSnapshot,
    strings: ChatStrings,
    online: Boolean,
    busy: Boolean,
    contextBlocked: Boolean,
    clarificationPending: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenProviderSettings: () -> Unit,
    onRefreshProvider: () -> Unit,
) {
    val semantic = LocalAegisSemanticColors.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (provider.provider == null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = semantic.warningContainer,
                    contentColor = semantic.onWarningContainer,
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = if (provider.readFailed) {
                                strings.providerReadError
                            } else {
                                strings.noProviderTitle
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = strings.noProviderBody,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = onOpenProviderSettings) {
                                Text(strings.openProviderSettings)
                            }
                            if (provider.readFailed) {
                                TextButton(onClick = onRefreshProvider) {
                                    Text(strings.historyRetry)
                                }
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = strings.maskingNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp)
                        .testTag("chat-composer"),
                    placeholder = { Text(strings.composerPlaceholder) },
                    minLines = 1,
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    keyboardActions = KeyboardActions(),
                )
                if (busy) {
                    Button(
                        onClick = onStop,
                        modifier = Modifier
                            .defaultMinSize(minWidth = 64.dp, minHeight = 52.dp)
                            .testTag("chat-stop"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .background(MaterialTheme.colorScheme.onError, RoundedCornerShape(2.dp)),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(strings.stop)
                    }
                } else {
                    // While the assistant waits for one answer, the clarification card is the
                    // only primary action; a second send would abandon the open question.
                    val enabled = value.isNotBlank() && provider.provider != null &&
                        online && !contextBlocked && !clarificationPending
                    Button(
                        onClick = onSend,
                        enabled = enabled,
                        modifier = Modifier
                            .defaultMinSize(minWidth = 64.dp, minHeight = 52.dp)
                            .testTag("chat-send"),
                    ) { Text(strings.send) }
                }
            }
        }
    }
}
