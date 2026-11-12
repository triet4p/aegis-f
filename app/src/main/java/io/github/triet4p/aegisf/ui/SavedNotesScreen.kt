package io.github.triet4p.aegisf.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.triet4p.aegisf.data.CaptureContextSummary
import io.github.triet4p.aegisf.data.CaptureKind
import io.github.triet4p.aegisf.data.CaptureMessageEntity
import io.github.triet4p.aegisf.data.CaptureSourceKind
import io.github.triet4p.aegisf.data.LIST_PAGE_SIZE
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.SourceQuery
import io.github.triet4p.aegisf.data.SourceState
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.ui.components.AegisGlyph
import io.github.triet4p.aegisf.ui.components.AegisIcon
import io.github.triet4p.aegisf.ui.text.AegisStrings
import io.github.triet4p.aegisf.ui.text.LocalAegisStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Everything saved on this device, as one focused browse task.
 *
 * The list is a bounded, newest-first read of the same active user notes the capture screen
 * saved, so a bank-evidence capture or a deleted tombstone is in no row here. A row is a route
 * into that note's own screen; this list never edits, deletes or sends a note. Deleting a note
 * from its own screen returns here with the completed feedback, and the list reloads from the
 * stored rows rather than from a remembered copy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SavedNotesScreen(
    queryService: WorkflowQueryService,
    notice: String?,
    onDismissNotice: () -> Unit,
    onOpenNote: (Long) -> Unit,
    onNavigateUp: () -> Unit,
) {
    val strings = LocalAegisStrings.current
    var pageOffset by rememberSaveable { mutableStateOf(0) }
    var historyRevision by remember { mutableIntStateOf(0) }
    var notes by remember { mutableStateOf(emptyList<CaptureMessageEntity>()) }
    var summaries by remember { mutableStateOf(emptyMap<Long, CaptureContextSummary>()) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    val pageIndex = pageOffset / LIST_PAGE_SIZE

    LaunchedEffect(queryService, pageOffset, historyRevision) {
        loading = true
        loadError = false
        try {
            val page = withContext(Dispatchers.IO) {
                queryService.sourceExcerpts(
                    SourceQuery(
                        kind = CaptureKind.USER_NOTE,
                        sourceKind = CaptureSourceKind.USER_QUICK_CAPTURE,
                        state = SourceState.ACTIVE,
                        bounds = QueryPageRequest(limit = LIST_PAGE_SIZE, offset = pageOffset),
                    ),
                )
            }
            if (page.items.isEmpty() && pageOffset > 0) {
                pageOffset = (pageOffset - LIST_PAGE_SIZE).coerceAtLeast(0)
            } else {
                val loadedSummaries = withContext(Dispatchers.IO) {
                    queryService.captureContextSummaries(page.items.map { it.id })
                }
                notes = page.items
                summaries = loadedSummaries
                hasMore = page.hasMore
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadError = true
        } finally {
            loading = false
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = strings.noteSavedSection,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateUp,
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
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            notice?.let { message ->
                item {
                    NoteNoticeBanner(
                        message = message,
                        closeLabel = strings.close,
                        onClose = onDismissNotice,
                    )
                }
            }

            if (!loading && !loadError && notes.isNotEmpty() && pageOffset == 0 && !hasMore) {
                item {
                    Text(
                        text = strings.noteCount(notes.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (loading && notes.isEmpty()) {
                item { Text(strings.noteListLoading, style = MaterialTheme.typography.bodyMedium) }
            }

            if (loadError) {
                item {
                    NoteStateMessage(
                        title = strings.noteListError,
                        body = null,
                        actionLabel = strings.retry,
                        onAction = { historyRevision += 1 },
                    )
                }
            }

            if (!loading && !loadError && notes.isEmpty()) {
                item {
                    NoteStateMessage(
                        title = strings.noteEmptyTitle,
                        body = strings.noteEmptyBody,
                    )
                }
            }

            items(notes, key = { it.id }) { note ->
                SavedNoteRow(
                    note = note,
                    summary = summaries[note.id],
                    strings = strings,
                    onOpen = { onOpenNote(note.id) },
                )
            }

            if (notes.isNotEmpty() && (pageOffset > 0 || hasMore)) {
                item {
                    PageControls(
                        pageIndex = pageIndex,
                        hasMore = hasMore,
                        onPrevious = {
                            pageOffset = (pageOffset - LIST_PAGE_SIZE).coerceAtLeast(0)
                        },
                        onNext = { pageOffset += LIST_PAGE_SIZE },
                        previousLabel = strings.pagePrevious,
                        nextLabel = strings.pageNext,
                        pageLabel = strings.pageNumber(pageIndex),
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedNoteRow(
    note: CaptureMessageEntity,
    summary: CaptureContextSummary?,
    strings: AegisStrings,
    onOpen: () -> Unit,
) {
    val preview = note.bodyText.orEmpty()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "${strings.noteOpen}: $preview" }
            .clickable(role = Role.Button, onClick = onOpen),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 64.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = preview,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = noteMomentText(note, strings),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            noteSummaryText(summary, strings)?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
