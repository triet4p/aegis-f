package io.github.triet4p.aegisf.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.triet4p.aegisf.agent.AgentRunHistory
import io.github.triet4p.aegisf.ui.ChatHistoryRow
import io.github.triet4p.aegisf.ui.ChatHistoryStatus
import io.github.triet4p.aegisf.ui.historyRowsOf
import io.github.triet4p.aegisf.ui.components.AegisGlyph
import io.github.triet4p.aegisf.ui.components.AegisIcon
import io.github.triet4p.aegisf.ui.theme.LocalAegisSemanticColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Chat destination's history sheet: the durable conversations the app actually stored.
 *
 * It reads the run rows and their conversation lines, then hands the chosen run to the screen,
 * which replaces the visible transcript with the stored one. It never resumes a run, never sends
 * a message and never appends a stored turn to the conversation already on screen. Selecting a
 * row while a reply is still arriving is disabled: stopping is an explicit action, not a side
 * effect of browsing history.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatHistorySheet(
    open: Boolean,
    onDismiss: () -> Unit,
    history: AgentRunHistory,
    strings: ChatStrings,
    busy: Boolean,
    activeRunId: String?,
    onOpen: (ChatHistoryRow) -> Unit,
) {
    if (!open) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var rows by remember { mutableStateOf<List<ChatHistoryRow>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(history, reload) {
        loading = true
        failed = false
        try {
            val runs = withContext(Dispatchers.IO) { history.recentRuns(HISTORY_PAGE) }
            rows = historyRowsOf(runs, activeRunId, busy)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = strings.historyTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) { Text(strings.close) }
            }
            if (busy) {
                Text(
                    text = strings.historyBusyNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                loading -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Text(strings.historyLoading, style = MaterialTheme.typography.bodyMedium)
                }

                failed -> Column(
                    modifier = Modifier.padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = strings.historyError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(
                        onClick = { reload += 1 },
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    ) { Text(strings.historyRetry) }
                }

                rows.isNullOrEmpty() -> Column(
                    modifier = Modifier.padding(vertical = 20.dp),
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

                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(rows.orEmpty(), key = { it.runId }) { row ->
                        HistoryRow(
                            row = row,
                            strings = strings,
                            enabled = !busy,
                            onClick = { onOpen(row) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    row: ChatHistoryRow,
    strings: ChatStrings,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val semantic = LocalAegisSemanticColors.current
    val title = chatVisibleText(row.title, strings.maskedValue)
    val statusColor = when (row.status) {
        ChatHistoryStatus.COMPLETED -> MaterialTheme.colorScheme.secondaryContainer
        ChatHistoryStatus.STOPPED -> MaterialTheme.colorScheme.surfaceVariant
        ChatHistoryStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
        ChatHistoryStatus.NEEDS_ANSWER, ChatHistoryStatus.INTERRUPTED -> semantic.warningContainer
        ChatHistoryStatus.IN_PROGRESS -> semantic.infoContainer
    }
    val glyph = when (row.status) {
        ChatHistoryStatus.COMPLETED -> AegisGlyph.CHECK
        ChatHistoryStatus.FAILED -> AegisGlyph.CROSS
        ChatHistoryStatus.NEEDS_ANSWER, ChatHistoryStatus.INTERRUPTED -> AegisGlyph.WARNING
        else -> AegisGlyph.CHAT
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "$title. ${strings.historyStatus(row.status)}"
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = chatRelativeMoment(row.atMillis, strings.language),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                shape = MaterialTheme.shapes.small,
                color = statusColor,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AegisIcon(
                        glyph = glyph,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        size = 12.dp,
                    )
                    Text(
                        text = strings.historyStatus(row.status),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private const val HISTORY_PAGE = 40
