package io.github.triet4p.aegisf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.triet4p.aegisf.data.CaptureMessageEntity
import io.github.triet4p.aegisf.data.ReviseCaptureRequest
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowCommandOutcome
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.ui.components.AegisDiscardChangesDialog
import io.github.triet4p.aegisf.ui.components.AegisGlyph
import io.github.triet4p.aegisf.ui.components.AegisIcon
import io.github.triet4p.aegisf.ui.text.LocalAegisStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The focused editor for one saved note: its text, one Save, and a truthful conflict path.
 *
 * The editor reads the stored note when it opens and compares the revision it saw on save, so a
 * note that changed elsewhere is refused rather than overwritten. The typed text stays in the
 * form on every refusal; reloading the stored note is an explicit choice that says it replaces
 * the form's text. Leaving with changes asks first, and only an explicit discard throws them
 * away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteEditorScreen(
    queryService: WorkflowQueryService,
    commandService: WorkflowCommandService,
    captureId: Long,
    onSaved: () -> Unit,
    onNavigateUp: () -> Unit,
) {
    val strings = LocalAegisStrings.current
    var state by remember(captureId) { mutableStateOf<NoteEditorLoad>(NoteEditorLoad.Loading) }
    var reloadToken by remember { mutableIntStateOf(0) }

    var editText by rememberSaveable(captureId) { mutableStateOf("") }
    var loadedText by rememberSaveable(captureId) { mutableStateOf("") }
    var revision by rememberSaveable(captureId) { mutableStateOf<Int?>(null) }
    var initialized by rememberSaveable(captureId) { mutableStateOf(false) }
    var saveRequestKey by rememberSaveable(captureId) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by rememberSaveable(captureId) { mutableStateOf<String?>(null) }
    var conflicted by rememberSaveable(captureId) { mutableStateOf(false) }
    var discardPending by rememberSaveable(captureId) { mutableStateOf(false) }
    var reloadPending by rememberSaveable(captureId) { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    LaunchedEffect(captureId, reloadToken) {
        state = NoteEditorLoad.Loading
        try {
            val capture = withContext(Dispatchers.IO) { queryService.sourceExcerpt(captureId) }
            if (capture == null || capture.tombstone || capture.bodyText == null) {
                state = NoteEditorLoad.Missing
            } else {
                // The form is initialized before it is shown, so the first rendered button
                // already knows the revision it must compare against. A reload after a
                // conflict resets [initialized] and replaces the form from the stored row.
                if (!initialized) {
                    editText = capture.bodyText.orEmpty()
                    loadedText = capture.bodyText.orEmpty()
                    revision = capture.revision
                    initialized = true
                } else if (revision == null) {
                    revision = capture.revision
                }
                state = NoteEditorLoad.Found(capture)
            }
        } catch (_: Exception) {
            state = NoteEditorLoad.Error
        }
    }

    val found = state as? NoteEditorLoad.Found

    val dirty = initialized && editText != loadedText

    fun requestLeave() {
        if (saving) return
        if (dirty) discardPending = true else onNavigateUp()
    }

    fun save() {
        val capture = found?.capture ?: return
        val expectedRevision = revision ?: return
        if (editText.isBlank() || saving) return
        val requestKey = saveRequestKey ?: UUID.randomUUID().toString().also {
            saveRequestKey = it
        }
        saving = true
        error = null
        conflicted = false
        scope.launch {
            try {
                val outcome = commandService.reviseCapture(
                    WorkflowCallerScope.User,
                    ReviseCaptureRequest(
                        idempotencyKey = requestKey,
                        captureId = captureId,
                        expectedRevision = expectedRevision,
                        bodyText = editText,
                    ),
                )
                when (outcome) {
                    is WorkflowCommandOutcome.Applied,
                    is WorkflowCommandOutcome.NoChange,
                    -> onSaved()

                    is WorkflowCommandOutcome.Conflict -> {
                        conflicted = true
                        error = strings.noteStale
                    }

                    WorkflowCommandOutcome.NotFound -> {
                        state = NoteEditorLoad.Missing
                    }

                    else -> error = strings.noteUpdateFailed
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = strings.noteUpdateFailed
            } finally {
                saving = false
            }
        }
    }

    BackHandler { requestLeave() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(strings.noteEditTitle) },
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
            if (found != null) {
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
                        enabled = editText.isNotBlank() && !saving && !conflicted,
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
                            Text(strings.noteSaveChanges)
                        }
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        when (val current = state) {
            NoteEditorLoad.Loading -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
            ) { NoteStateMessage(strings.noteLoading, null) }

            NoteEditorLoad.Missing -> Column(
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

            NoteEditorLoad.Error -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
            ) {
                NoteStateMessage(
                    title = strings.noteReadErrorTitle,
                    body = strings.noteReadErrorBody,
                    actionLabel = strings.retry,
                    onAction = { reloadToken += 1 },
                )
            }

            is NoteEditorLoad.Found -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = editText,
                    onValueChange = {
                        editText = it
                        saveRequestKey = null
                        error = null
                        conflicted = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp),
                    enabled = !saving,
                    label = { Text(strings.noteEditLabel) },
                )
                Text(
                    text = strings.noteEditPolicy,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (conflicted) {
                    Text(
                        text = strings.noteStale,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                    ) {
                        TextButton(
                            onClick = { reloadPending = true },
                            enabled = !saving,
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                        ) { Text(strings.noteReloadSaved) }
                    }
                } else {
                    error?.let { message ->
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
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
            title = { Text(strings.noteReloadConfirmTitle) },
            text = { Text(strings.noteReloadConfirmBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        reloadPending = false
                        initialized = false
                        editText = ""
                        error = null
                        conflicted = false
                        saveRequestKey = null
                        reloadToken += 1
                    },
                ) { Text(strings.reload) }
            },
            dismissButton = {
                TextButton(onClick = { reloadPending = false }) { Text(strings.cancel) }
            },
        )
    }
}

private sealed interface NoteEditorLoad {
    data object Loading : NoteEditorLoad
    data object Missing : NoteEditorLoad
    data object Error : NoteEditorLoad
    data class Found(val capture: CaptureMessageEntity) : NoteEditorLoad
}
