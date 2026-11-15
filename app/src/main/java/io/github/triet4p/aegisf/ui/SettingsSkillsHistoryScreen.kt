package io.github.triet4p.aegisf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.triet4p.aegisf.agent.AgentRunHistory
import io.github.triet4p.aegisf.agent.WorkflowSkill
import io.github.triet4p.aegisf.agent.WorkflowSkillCatalog
import io.github.triet4p.aegisf.agent.WorkflowSkillId
import io.github.triet4p.aegisf.ui.chat.chatVisibleText
import io.github.triet4p.aegisf.ui.components.AegisGlyph
import io.github.triet4p.aegisf.ui.components.AegisIcon
import io.github.triet4p.aegisf.ui.text.LocalAegisStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings child: the assistant's bounded workflows and the conversations this device stored.
 *
 * The skills are the bundled catalog the app already validates at load time; this screen only
 * reports which workflows exist, their versions and what each one does. It grants nothing and
 * cannot select a skill. The history is the same durable run list AI Chat reads; this screen
 * never resumes, sends, or deletes a run. A read failure says nothing changed and offers retry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSkillsHistoryScreen(
    agentRunHistory: AgentRunHistory,
    onOpenChat: () -> Unit,
    onNavigateUp: () -> Unit,
    canClearHistory: Boolean,
    onHistoryCleared: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val baseStrings = LocalAegisStrings.current
    val strings = remember(baseStrings.language) { settingsStrings(baseStrings.language) }
    val context = LocalContext.current

    var skills by remember { mutableStateOf<List<WorkflowSkill>?>(null) }
    var skillsFailed by remember { mutableStateOf(false) }
    var skillsRetry by remember { mutableIntStateOf(0) }
    var historyRows by remember { mutableStateOf<List<ChatHistoryRow>?>(null) }
    var historyFailed by remember { mutableStateOf(false) }
    var historyRetry by remember { mutableIntStateOf(0) }
    var confirmHistoryClear by remember { mutableStateOf(false) }
    var clearingHistory by remember { mutableStateOf(false) }
    var historyClearFailed by remember { mutableStateOf(false) }
    var historyClearSucceeded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(skillsRetry) {
        skillsFailed = false
        try {
            skills = withContext(Dispatchers.IO) {
                WorkflowSkillCatalog.load(context.assets::open).all()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            skillsFailed = true
        }
    }
    LaunchedEffect(agentRunHistory, historyRetry) {
        historyFailed = false
        try {
            val runs = withContext(Dispatchers.IO) { agentRunHistory.recentRuns(20) }
            historyRows = historyRowsOf(runs, activeRunId = null, busy = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            historyFailed = true
        }
    }

    BackHandler(onBack = onNavigateUp)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = strings.skillsTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        AegisIcon(
                            glyph = AegisGlyph.ARROW_BACK,
                            tint = MaterialTheme.colorScheme.onSurface,
                            size = 22.dp,
                            modifier = Modifier.semantics {
                                contentDescription = strings.back
                            },
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = strings.skillsSection,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = strings.skillsHelp,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                skillsFailed -> ReadFailure(
                    message = strings.skillsReadError,
                    retry = strings.skillsRetry,
                    onRetry = { skillsRetry += 1 },
                )
                skills == null -> LoadingRow(strings.skillsLoading)
                else -> Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column {
                        skills.orEmpty().forEachIndexed { index, skill ->
                            if (index > 0) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                            SkillRow(skill = skill, strings = strings)
                        }
                    }
                }
            }

            Text(
                text = strings.historySection,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = strings.historyHelp,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                historyFailed -> ReadFailure(
                    message = strings.historyError,
                    retry = strings.historyRetry,
                    onRetry = { historyRetry += 1 },
                )
                historyRows == null -> LoadingRow(strings.historyLoading)
                historyRows.orEmpty().isEmpty() -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = strings.historyEmptyTitle,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = strings.historyEmptyBody,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column {
                        historyRows.orEmpty().forEachIndexed { index, row ->
                            if (index > 0) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                            HistoryRow(row = row, strings = strings)
                        }
                    }
                }
            }
            if (!historyFailed && historyRows.orEmpty().isNotEmpty()) {
                Button(
                    onClick = {
                        confirmHistoryClear = true
                        historyClearFailed = false
                        historyClearSucceeded = false
                    },
                    enabled = canClearHistory && !clearingHistory,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .testTag("skills-clear-history"),
                ) {
                    Text(strings.historyClearAction)
                }
                if (!canClearHistory) {
                    Text(
                        text = strings.historyClearBusy,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (clearingHistory) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(strings.historyClearing)
                }
            }
            if (historyClearFailed) {
                Text(
                    text = strings.historyClearFailed,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (historyClearSucceeded) {
                Text(
                    text = strings.historyClearSucceeded,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = strings.historyOpenHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onOpenChat,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag("skills-open-chat")
                    .padding(bottom = 12.dp),
            ) {
                Text(strings.historyOpenChat)
            }
        }
    }
    if (confirmHistoryClear) {
        AlertDialog(
            onDismissRequest = { if (!clearingHistory) confirmHistoryClear = false },
            title = { Text(strings.historyClearTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings.historyClearBody)
                    if (!canClearHistory) {
                        Text(
                            text = strings.historyClearBusy,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmHistoryClear = false
                        clearingHistory = true
                        historyClearFailed = false
                        historyClearSucceeded = false
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    agentRunHistory.deleteAllHistory()
                                }
                                historyRows = emptyList()
                                onHistoryCleared()
                                historyClearSucceeded = true
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                historyClearFailed = true
                            } finally {
                                clearingHistory = false
                            }
                        }
                    },
                    enabled = canClearHistory && !clearingHistory,
                ) {
                    Text(strings.historyClearConfirm)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmHistoryClear = false },
                    enabled = !clearingHistory,
                ) {
                    Text(strings.cancel)
                }
            },
        )
    }
}

@Composable
private fun SkillRow(skill: WorkflowSkill, strings: SettingsStrings) {
    val (title, body) = when (skill.id) {
        WorkflowSkillId.EVENT_READING -> strings.skillsEventReading to strings.skillsEventReadingBody
        WorkflowSkillId.DRAFT_SEARCH -> strings.skillsDraftSearch to strings.skillsDraftSearchBody
        WorkflowSkillId.LINK_EVENT_DRAFT ->
            strings.skillsLinkEventDraft to strings.skillsLinkEventDraftBody
        WorkflowSkillId.DRAFT_REVIEW -> strings.skillsDraftReview to strings.skillsDraftReviewBody
        WorkflowSkillId.OPERATION_REPLAY ->
            strings.skillsOperationReplay to strings.skillsOperationReplayBody
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = strings.skillsVersion(skill.version),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HistoryRow(row: ChatHistoryRow, strings: SettingsStrings) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = chatVisibleText(row.title, "•••"),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = weekdayMomentText(row.atMillis, strings.language),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = historyStatusLabel(row.status, strings),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun historyStatusLabel(status: ChatHistoryStatus, strings: SettingsStrings): String =
    when (status) {
        ChatHistoryStatus.COMPLETED -> strings.historyCompleted
        ChatHistoryStatus.STOPPED -> strings.historyStopped
        ChatHistoryStatus.FAILED -> strings.historyFailed
        ChatHistoryStatus.NEEDS_ANSWER -> strings.historyNeedsAnswer
        ChatHistoryStatus.IN_PROGRESS -> strings.historyInProgress
        ChatHistoryStatus.INTERRUPTED -> strings.historyInterrupted
    }

@Composable
private fun LoadingRow(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(20.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ReadFailure(message: String, retry: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(retry)
        }
    }
}
