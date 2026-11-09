package io.github.triet4p.aegisf.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.triet4p.aegisf.agent.AgentRunHistory
import io.github.triet4p.aegisf.agent.BoundedWorkflowExecutor
import io.github.triet4p.aegisf.agent.WorkflowIntent
import io.github.triet4p.aegisf.agent.WorkflowRunHost
import io.github.triet4p.aegisf.agent.WorkflowRunOutcome
import io.github.triet4p.aegisf.agent.WorkflowRunProgress
import io.github.triet4p.aegisf.agent.WorkflowRunProgressSink
import io.github.triet4p.aegisf.agent.WorkflowRunRequest
import io.github.triet4p.aegisf.data.CaptureKind
import io.github.triet4p.aegisf.data.CaptureSourceKind
import io.github.triet4p.aegisf.data.DraftCandidateQuery
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.LIST_PAGE_SIZE
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.SourceQuery
import io.github.triet4p.aegisf.data.SourceState
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.privacy.MaskingBlockedException
import io.github.triet4p.aegisf.provider.HostedProviderConfig
import io.github.triet4p.aegisf.provider.HostedProviderSettingsStore
import io.github.triet4p.aegisf.ui.CapturePanel
import io.github.triet4p.aegisf.ui.ChatAgentRunState
import io.github.triet4p.aegisf.ui.ChatOutcomeSection
import io.github.triet4p.aegisf.ui.text.LocalAegisStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

/**
 * The AI Chat root destination during the S10.2 navigation cutover: the existing hosted-agent
 * exchange, moved out of the combined capture screen so a note and a conversation are two
 * separate destinations.
 *
 * The screen deliberately keeps the existing conversation behaviour rather than redesigning it:
 * the run's transient state stays in the shell-owned [ChatAgentRunState], so leaving the tab does
 * not restart or lose a run, and only verified application effects are reported as changes. The
 * dedicated streaming chat redesign is S10.5's scope.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiChatScreen(
    queryService: WorkflowQueryService,
    commandService: WorkflowCommandService,
    hostedProviderSettingsStore: HostedProviderSettingsStore,
    createHostedWorkflowHost: (WorkflowCallerScope.AgentSession, HostedProviderConfig) -> WorkflowRunHost,
    agentRunHistory: AgentRunHistory,
    agentRunState: ChatAgentRunState,
    agentRunScope: CoroutineScope,
    onRecordEvent: (Long) -> Unit,
) {
    var agentPrompt by rememberSaveable { mutableStateOf("") }
    var selectedIntents by remember { mutableStateOf(setOf(WorkflowIntent.INTERPRET_EVENTS)) }
    var clarificationAnswer by rememberSaveable { mutableStateOf("") }

    // The run's scope names the notes it may read. It is read here, bounded, at the moment the
    // screen is shown, instead of inheriting whatever page the note journal happened to open.
    var scopedCaptureIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    LaunchedEffect(queryService, agentRunState.projectionRevision) {
        scopedCaptureIds = try {
            withContext(Dispatchers.IO) {
                queryService.sourceExcerpts(
                    SourceQuery(
                        kind = CaptureKind.USER_NOTE,
                        sourceKind = CaptureSourceKind.USER_QUICK_CAPTURE,
                        state = SourceState.ACTIVE,
                        bounds = QueryPageRequest(limit = LIST_PAGE_SIZE, offset = 0),
                    ),
                ).items.map { it.id }.toSet()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptySet()
        }
    }

    val title = LocalAegisStrings.current.chatTitle.uppercase(Locale.ROOT)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                    )
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                val activeProvider = runCatching { hostedProviderSettingsStore.active() }.getOrNull()
                CapturePanel(
                    modifier = Modifier.fillMaxWidth(),
                    accent = MaterialTheme.colorScheme.secondary,
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            activeProvider?.let { "HOSTED AGENT · ${it.name} · ${it.model}" }
                                ?: "HOSTED AGENT · NOT CONFIGURED",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        Text(
                            "Hosted requests require internet access and send locally masked context to the selected endpoint. " +
                                "Masking is not anonymity; remaining context may still identify you. Check the endpoint and provider terms before sending. " +
                                "Without an active provider, Chat remains a local note journal; manual entry and intake work offline.",
                        )
                    }
                }
            }
            item {
                CapturePanel(Modifier.fillMaxWidth(), MaterialTheme.colorScheme.primary) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("ASK THE HOSTED AGENT", style = MaterialTheme.typography.labelLarge)
                        Text("Choose its allowed workflow skills. Only verified application effects are reported as changes.")
                        WorkflowIntent.values().forEach { intent ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = intent in selectedIntents,
                                    onCheckedChange = { enabled ->
                                        selectedIntents = if (enabled) selectedIntents + intent
                                        else selectedIntents - intent
                                    },
                                    enabled = !agentRunState.busy,
                                )
                                Text(intent.name.replace('_', ' '))
                            }
                        }
                        OutlinedTextField(
                            value = agentPrompt,
                            onValueChange = { agentPrompt = it },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                            enabled = !agentRunState.busy,
                            label = { Text("Ask about your notes or pending drafts") },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val prompt = agentPrompt.trim()
                                    if (prompt.isBlank() || selectedIntents.isEmpty()) {
                                        agentRunState.progress = "Enter a request and choose at least one workflow skill."
                                    } else {
                                        agentRunState.busy = true
                                        agentRunState.outcome = null
                                        agentRunState.streamText = ""
                                        agentRunState.streamNote = null
                                        agentRunState.progress = "Preparing a scoped, privacy-masked run…"
                                        agentRunState.job = agentRunScope.launch {
                                            try {
                                                val provider = hostedProviderSettingsStore.active()
                                                    ?: error("No active hosted provider. Configure one in Settings.")
                                                val pendingDraftPage = queryService.draftCandidates(
                                                    DraftCandidateQuery(
                                                        reviewStatus = IntakeReviewStatus.PENDING,
                                                        bounds = QueryPageRequest(limit = 32, offset = 0),
                                                    ),
                                                )
                                                val pendingDrafts = pendingDraftPage.items
                                                val sessionScope = WorkflowCallerScope.AgentSession(
                                                    scopeId = "chat-${UUID.randomUUID()}",
                                                    captureIds = scopedCaptureIds,
                                                    draftIds = pendingDrafts.map { it.id }.toSet(),
                                                    draftScopeComplete = !pendingDraftPage.hasMore,
                                                )
                                                val runRequest = WorkflowRunRequest(
                                                    intents = selectedIntents,
                                                    userRequest = prompt,
                                                    // The checkboxes are the allowed-skill
                                                    // allowlist, not a declared list of effects:
                                                    // the user's conversational request decides
                                                    // which of them the run applies, and only
                                                    // verified effects are ever reported.
                                                    requiredIntents = emptySet(),
                                                )
                                                val host = createHostedWorkflowHost(
                                                    sessionScope,
                                                    provider.validatedConfig(sessionScope.scopeId),
                                                )
                                                val executor = BoundedWorkflowExecutor(host)
                                                val admitted = agentRunHistory.admitRun(
                                                    sessionScope,
                                                    runRequest,
                                                    host.boundary,
                                                    provider.maxOutputTokens,
                                                )
                                                agentRunState.runId = admitted.runId
                                                agentRunState.executor = executor
                                                agentRunState.lastScope = sessionScope
                                                agentRunState.lastPrompt = prompt
                                                agentRunState.lastIntents = selectedIntents
                                                agentRunState.followUpSelection = null
                                                agentRunState.followUpInput = ""
                                                agentRunState.progress = "Generating with ${provider.name} · ${provider.model}…"
                                                agentRunState.outcome = executor.startTracked(
                                                    admitted = admitted,
                                                    request = runRequest,
                                                    history = agentRunHistory,
                                                    maxOutputTokens = provider.maxOutputTokens,
                                                    progress = runProgressSink(
                                                        onText = { agentRunState.streamText += it },
                                                        onNote = { agentRunState.streamNote = it },
                                                    ),
                                                )
                                                agentRunState.followUp = agentRunState.outcome?.followUp
                                                agentRunState.continuation = (agentRunState.outcome as? WorkflowRunOutcome.Clarifying)?.continuation
                                            } catch (_: MaskingBlockedException) {
                                                agentRunState.progress = "Privacy protection stopped this request before it was sent. No application change was made."
                                            } catch (_: Exception) {
                                                agentRunState.progress = "The hosted request could not start. Check provider settings, network access, and the selected workflow scope."
                                            } finally {
                                                agentRunState.busy = false
                                                agentRunState.job = null
                                                agentRunState.projectionRevision += 1
                                            }
                                        }
                                    }
                                },
                                enabled = !agentRunState.busy,
                            ) { Text("Send") }
                            if (agentRunState.busy) {
                                OutlinedButton(
                                    onClick = {
                                        val runId = agentRunState.runId
                                        val executor = agentRunState.executor
                                        if (runId != null && executor != null) {
                                            agentRunScope.launch {
                                                executor.cancelTracked(agentRunHistory, runId, "USER_CANCELLED")
                                                agentRunState.job?.cancel()
                                                agentRunState.busy = false
                                                agentRunState.progress = "Canceled. Any application change already stored remains in place."
                                            }
                                        } else {
                                            agentRunState.job?.cancel()
                                            agentRunState.busy = false
                                            agentRunState.progress = "Canceled before the provider run started."
                                        }
                                    },
                                ) { Text("Cancel") }
                                CircularProgressIndicator(Modifier.size(20.dp))
                            }
                        }
                        agentRunState.progress?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (agentRunState.busy) {
                            agentRunState.streamNote?.let {
                                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (agentRunState.streamText.isNotBlank()) {
                                Text(agentRunState.streamText, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        when (val outcome = agentRunState.outcome) {
                            is WorkflowRunOutcome.Completed -> ChatOutcomeSection(
                                outcome = outcome,
                                agentRunState = agentRunState,
                                agentRunScope = agentRunScope,
                                queryService = queryService,
                                commandService = commandService,
                                onRecordEvent = onRecordEvent,
                                onContextChanged = { agentRunState.projectionRevision += 1 },
                                onRetry = { prompt, _ ->
                                    agentPrompt = prompt
                                    agentRunState.progress = "Retry the request above to start a new run with the same prompt."
                                },
                            )
                            is WorkflowRunOutcome.Clarifying -> {
                                Text("Clarification needed: ${outcome.question}")
                                OutlinedTextField(
                                    clarificationAnswer,
                                    { clarificationAnswer = it },
                                    Modifier.fillMaxWidth(),
                                    label = { Text("Your answer") },
                                    enabled = !agentRunState.busy,
                                )
                                Button(
                                    enabled = !agentRunState.busy && clarificationAnswer.isNotBlank(),
                                    onClick = {
                                        val runId = agentRunState.runId
                                        val executor = agentRunState.executor
                                        val continuation = agentRunState.continuation
                                        if (runId == null || executor == null || continuation == null) {
                                            agentRunState.progress = "This clarification is no longer available. Start a new request."
                                        } else {
                                            agentRunState.busy = true
                                            agentRunState.streamText = ""
                                            agentRunState.streamNote = null
                                            agentRunState.progress = "Resuming the same provider and scoped run…"
                                            val answer = clarificationAnswer
                                            agentRunState.job = agentRunScope.launch {
                                                try {
                                                    agentRunState.outcome = executor.resumeTracked(
                                                        agentRunHistory,
                                                        runId,
                                                        continuation,
                                                        answer,
                                                        progress = runProgressSink(
                                                            onText = { agentRunState.streamText += it },
                                                            onNote = { agentRunState.streamNote = it },
                                                        ),
                                                    )
                                                    agentRunState.continuation = (agentRunState.outcome as? WorkflowRunOutcome.Clarifying)?.continuation
                                                    agentRunState.followUp = agentRunState.outcome?.followUp
                                                    clarificationAnswer = ""
                                                    agentRunState.progress = if (agentRunState.outcome == null) {
                                                        "The clarification could not be resumed (the run stayed on its current attempt). Start a new request with a fresh prompt."
                                                    } else null
                                                } catch (cancelled: CancellationException) {
                                                    throw cancelled
                                                } catch (exception: Exception) {
                                                    val consumed = exception is IllegalStateException || exception is IllegalArgumentException
                                                    if (consumed) agentRunState.continuation = null
                                                    agentRunState.progress = "The clarification could not be resumed (${exception::class.simpleName ?: "error"}). The run stayed on its current attempt; try Continue once more or start a new request."
                                                } finally {
                                                    agentRunState.busy = false
                                                    agentRunState.job = null
                                                    agentRunState.projectionRevision += 1
                                                }
                                            }
                                        }
                                    },
                                ) { Text("Continue") }
                            }
                            is WorkflowRunOutcome.Refused -> Text("Request refused: ${outcome.reason}", color = MaterialTheme.colorScheme.error)
                            is WorkflowRunOutcome.Incomplete -> ChatOutcomeSection(
                                outcome = outcome,
                                agentRunState = agentRunState,
                                agentRunScope = agentRunScope,
                                queryService = queryService,
                                commandService = commandService,
                                onRecordEvent = onRecordEvent,
                                onContextChanged = { agentRunState.projectionRevision += 1 },
                                onRetry = { prompt, _ ->
                                    agentPrompt = prompt
                                    agentRunState.progress = "Retry the request above to start a new run with the same prompt."
                                },
                            )
                            null -> Unit
                        }
                    }
                }
            }
        }
    }
}

/**
 * The chat screen's live progress sink.
 *
 * Answer text arrives already filtered by the masked gateway's incremental public gate, so it
 * is safe to display as it grows. Tool activity is a declared tool name only; argument text and
 * private reasoning never reach this sink. A route without honest streaming reports that fact
 * instead of typing out the final answer after the fact.
 */
private fun runProgressSink(
    onText: (String) -> Unit,
    onNote: (String) -> Unit,
): WorkflowRunProgressSink = { item ->
    when (item) {
        is WorkflowRunProgress.PublicText -> onText(item.text)
        is WorkflowRunProgress.ToolActivity -> onNote(
            if (item.completed) {
                "Tool ${item.name}: arguments complete; host validation follows."
            } else {
                "Tool ${item.name}: calling…"
            },
        )

        is WorkflowRunProgress.StreamingUnavailable ->
            onNote("Live answer streaming is unavailable for this provider route; run progress only.")
    }
}
