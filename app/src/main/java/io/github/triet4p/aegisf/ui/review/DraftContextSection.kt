package io.github.triet4p.aegisf.ui.review

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.triet4p.aegisf.data.ContextLinkState
import io.github.triet4p.aegisf.data.DraftContextLink
import io.github.triet4p.aegisf.data.QueryPage
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.UnrecordedNoteEvent
import io.github.triet4p.aegisf.data.VndMoney
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.ui.PageControls
import io.github.triet4p.aegisf.ui.decideUserLink
import io.github.triet4p.aegisf.ui.isAppliedOrNoChange
import io.github.triet4p.aegisf.ui.linkFailureMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The user's own note excerpts beside one bank draft's review.
 *
 * An excerpt is provenance only: attaching, refusing or unlinking it touches the link row and
 * nothing else. The bank amount, the review fields and the ledger are never changed here, and
 * every action goes through the shared command boundary with the stored revision it read.
 */
@Composable
internal fun DraftContextSection(
    links: List<DraftContextLink>,
    loading: Boolean,
    error: String?,
    pageIndex: Int,
    hasMore: Boolean,
    active: Boolean,
    busy: Boolean,
    feedback: String?,
    feedbackIsError: Boolean,
    strings: ReviewStrings,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRetry: () -> Unit,
    onAddContext: () -> Unit,
    onAttach: (DraftContextLink) -> Unit,
    onReject: (DraftContextLink) -> Unit,
    onUnlink: (DraftContextLink) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings.noteContextSection, style = MaterialTheme.typography.titleSmall)
        Text(
            text = strings.noteContextHelp,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (loading && links.isEmpty()) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(strings.noteContextLoading, style = MaterialTheme.typography.bodyMedium)
        }
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onRetry) { Text(strings.noteContextRetry) }
        }
        if (!loading && error == null && links.isEmpty() && pageIndex == 0) {
            Text(
                text = strings.noteContextEmpty,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (pageIndex > 0 || hasMore) {
            PageControls(
                pageIndex = pageIndex,
                hasMore = hasMore,
                onPrevious = onPrevious,
                onNext = onNext,
                previousLabel = strings.previous,
                nextLabel = strings.next,
                pageLabel = strings.pageLabel(pageIndex),
            )
        }
        links.forEach { link ->
            DraftContextCard(
                link = link,
                active = active,
                busy = busy,
                strings = strings,
                onAttach = { onAttach(link) },
                onReject = { onReject(link) },
                onUnlink = { onUnlink(link) },
            )
        }
        if (feedback != null) {
            Text(
                text = feedback,
                style = MaterialTheme.typography.bodyMedium,
                color = if (feedbackIsError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
        if (active) {
            OutlinedButton(
                shape = MaterialTheme.shapes.small,
                onClick = onAddContext,
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) { Text(strings.noteContextAdd) }
        }
    }
}

@Composable
private fun DraftContextCard(
    link: DraftContextLink,
    active: Boolean,
    busy: Boolean,
    strings: ReviewStrings,
    onAttach: () -> Unit,
    onReject: () -> Unit,
    onUnlink: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = when (link.state) {
                    ContextLinkState.ACCEPTED -> strings.noteContextAttached
                    ContextLinkState.PROPOSED -> strings.noteContextProposed
                    ContextLinkState.REJECTED -> strings.noteContextRefused
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = strings.noteIdentity(link.captureId, link.eventId),
                style = MaterialTheme.typography.bodySmall,
            )
            val excerpt = link.excerpt
            if (excerpt != null) {
                Text(
                    text = strings.sourceExcerpt(excerpt),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    text = strings.excerptStale(link.captureRevision, link.eventSourceRevision),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            link.readingText?.let { reading ->
                Text(
                    text = strings.noteContextReading.format(reading),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val facts = buildList {
                link.amountVnd?.let { add(VndMoney.formatAmount(it, " ₫")) }
                link.anchorDate?.let { add(it) }
                link.merchant?.takeIf(String::isNotBlank)?.let { add(it) }
            }
            if (facts.isNotEmpty()) {
                Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = strings.linkRevisions(link.eventRevision, link.revision),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (active) {
                when (link.state) {
                    ContextLinkState.PROPOSED -> Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            shape = MaterialTheme.shapes.small,
                            onClick = onAttach,
                            enabled = !busy,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("review-attach-${link.linkId}"),
                        ) { Text(strings.noteContextAttach) }
                        OutlinedButton(
                            shape = MaterialTheme.shapes.small,
                            onClick = onReject,
                            enabled = !busy,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("review-reject-${link.linkId}"),
                        ) { Text(strings.noteContextReject) }
                    }

                    ContextLinkState.ACCEPTED -> OutlinedButton(
                        shape = MaterialTheme.shapes.small,
                        onClick = onUnlink,
                        enabled = !busy,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("review-unlink-${link.linkId}"),
                    ) { Text(strings.noteContextUnlink) }

                    ContextLinkState.REJECTED -> Text(
                        text = strings.noteContextRefusedKept,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private const val CONTEXT_CANDIDATE_PAGE_SIZE = 5

/**
 * The bounded chooser of saved note excerpts that can still be attached to this draft.
 *
 * Every attachment is one explicit user decision; the list never selects a match, and an
 * excerpt whose source moved on is shown as unavailable instead of being attached.
 */
@Composable
internal fun AddNoteContextDialog(
    draftId: Long,
    queryService: WorkflowQueryService,
    commandService: WorkflowCommandService,
    strings: ReviewStrings,
    onDismiss: () -> Unit,
    onLinked: (String) -> Unit,
) {
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    var page by remember { mutableStateOf<QueryPage<UnrecordedNoteEvent>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var actionBusy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val writeInFlight = remember { AtomicBoolean(false) }

    LaunchedEffect(queryService, offset, reload) {
        loading = true
        error = null
        try {
            page = queryService.unrecordedNoteEvents(
                QueryPageRequest(limit = CONTEXT_CANDIDATE_PAGE_SIZE, offset = offset),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = strings.addContextError
        } finally {
            loading = false
        }
    }

    fun attach(event: UnrecordedNoteEvent) {
        if (!writeInFlight.compareAndSet(false, true)) return
        actionBusy = true
        message = null
        messageIsError = false
        scope.launch {
            try {
                val outcome = decideUserLink(
                    commandService = commandService,
                    eventId = event.eventId,
                    draftId = draftId,
                    attach = true,
                    overrideNote = "User explicitly attached note context in Review.",
                )
                val applied = outcome.isAppliedOrNoChange()
                messageIsError = !applied
                message = if (applied) {
                    strings.noteAttached(event.captureId)
                } else {
                    outcome.linkFailureMessage()
                }
                if (applied) onLinked(strings.noteAttached(event.captureId))
                reload += 1
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                messageIsError = true
                message = strings.linkActionFailed
            } finally {
                actionBusy = false
                writeInFlight.set(false)
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!actionBusy) onDismiss() },
        title = { Text("${strings.addContextTitle} #$draftId") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(strings.addContextHelp, style = MaterialTheme.typography.bodySmall)
                if (loading && page == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(strings.addContextLoading, style = MaterialTheme.typography.bodyMedium)
                }
                error?.let { failure ->
                    Text(failure, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { reload += 1 }) { Text(strings.retry) }
                }
                val current = page
                if (!loading && error == null && current?.items.isNullOrEmpty() && offset == 0) {
                    Text(strings.addContextEmpty, style = MaterialTheme.typography.bodyMedium)
                }
                current?.items.orEmpty().forEach { event ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = strings.noteIdentity(event.captureId, event.eventId),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = event.excerpt?.let { strings.addContextExcerpt.format(it) }
                                    ?: strings.addContextSourceStale,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            event.normalizedText?.let { reading ->
                                Text(
                                    text = strings.noteContextReading.format(reading),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            val facts = buildList {
                                event.amountVnd?.let { add(VndMoney.formatAmount(it, " ₫")) }
                                event.anchorDate?.let { add(it) }
                                event.merchant?.takeIf(String::isNotBlank)?.let { add(it) }
                            }
                            if (facts.isNotEmpty()) {
                                Text(
                                    facts.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Button(
                                shape = MaterialTheme.shapes.small,
                                onClick = { attach(event) },
                                enabled = !actionBusy && event.excerpt != null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp),
                            ) { Text(strings.addContextAttach) }
                        }
                    }
                }
                if (current != null && (current.hasMore || offset > 0)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = {
                                offset = (offset - CONTEXT_CANDIDATE_PAGE_SIZE).coerceAtLeast(0)
                            },
                            enabled = offset > 0 && !loading && !actionBusy,
                        ) { Text(strings.previous) }
                        Text(
                            text = strings.pageLabel(offset / CONTEXT_CANDIDATE_PAGE_SIZE),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(
                            onClick = { offset += current.items.size },
                            enabled = current.hasMore && current.items.isNotEmpty() &&
                                !loading && !actionBusy,
                        ) { Text(strings.next) }
                    }
                }
                if (actionBusy) {
                    Text(strings.addContextSaving, style = MaterialTheme.typography.bodyMedium)
                }
                message?.let { text ->
                    Text(
                        text = text,
                        color = if (messageIsError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !actionBusy) { Text(strings.close) }
        },
    )
}
