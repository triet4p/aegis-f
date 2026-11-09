package io.github.triet4p.aegisf.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.triet4p.aegisf.data.CaptureContextSummary
import io.github.triet4p.aegisf.data.CaptureMessageEntity
import io.github.triet4p.aegisf.data.SaveCaptureRequest
import io.github.triet4p.aegisf.data.UserCaptureKind
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowChange
import io.github.triet4p.aegisf.data.WorkflowCommandOutcome
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.ui.components.AegisDiscardChangesDialog
import io.github.triet4p.aegisf.ui.components.AegisGlyph
import io.github.triet4p.aegisf.ui.components.AegisIcon
import io.github.triet4p.aegisf.ui.components.AegisSettingRow
import io.github.triet4p.aegisf.ui.text.AegisStrings
import io.github.triet4p.aegisf.ui.text.LocalAegisStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Notes: one task — write one note and keep it on this device.
 *
 * The composer is the only primary action here. The saved notes open as their own focused browse
 * child, so editing, deleting, interpreting and handing a note to AI never stack under the
 * composer. Saving is a local write and starts no hosted request and no ledger record.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickCaptureScreen(
    queryService: WorkflowQueryService,
    commandService: WorkflowCommandService,
    notice: String?,
    onDismissNotice: () -> Unit,
    onOpenSavedNotes: () -> Unit,
) {
    val strings = LocalAegisStrings.current
    var draft by rememberSaveable { mutableStateOf("") }
    var saveRequestKey by rememberSaveable { mutableStateOf<String?>(null) }
    var saveMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var saveFailed by rememberSaveable { mutableStateOf(false) }
    var saveInProgress by remember { mutableStateOf(false) }
    var discardPending by rememberSaveable { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    fun saveNote() {
        val text = draft
        if (text.isBlank() || saveInProgress) return
        val requestKey = saveRequestKey ?: UUID.randomUUID().toString().also {
            saveRequestKey = it
        }
        saveInProgress = true
        saveMessage = null
        saveFailed = false
        coroutineScope.launch {
            try {
                val outcome = commandService.saveCapture(
                    WorkflowCallerScope.User,
                    SaveCaptureRequest(
                        idempotencyKey = requestKey,
                        bodyText = text,
                        captureKind = UserCaptureKind.QUICK_CAPTURE,
                    ),
                )
                if (outcome.savedCaptureId() != null) {
                    draft = ""
                    saveRequestKey = null
                    saveFailed = false
                    saveMessage = strings.noteSaved
                } else {
                    saveFailed = true
                    saveMessage = outcome.noteFailureMessage(strings, strings.noteSaveFailed)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                saveFailed = true
                saveMessage = strings.noteSaveFailed
            } finally {
                saveInProgress = false
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = strings.notesTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                },
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
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

            item {
                NoteComposer(
                    strings = strings,
                    draft = draft,
                    onDraftChange = {
                        draft = it
                        saveRequestKey = null
                        saveMessage = null
                        saveFailed = false
                    },
                    saving = saveInProgress,
                    message = saveMessage,
                    messageIsError = saveFailed,
                    onSave = ::saveNote,
                    onClear = { discardPending = true },
                )
            }

            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    AegisSettingRow(
                        title = strings.noteSavedSection,
                        supporting = strings.noteBrowseHelp,
                        onClick = onOpenSavedNotes,
                    )
                }
            }
        }
    }

    if (discardPending) {
        AegisDiscardChangesDialog(
            onKeepEditing = { discardPending = false },
            onDiscard = {
                discardPending = false
                draft = ""
                saveRequestKey = null
                saveFailed = false
                saveMessage = strings.noteDraftCleared
            },
        )
    }
}

@Composable
private fun NoteComposer(
    strings: AegisStrings,
    draft: String,
    onDraftChange: (String) -> Unit,
    saving: Boolean,
    message: String?,
    messageIsError: Boolean,
    onSave: () -> Unit,
    onClear: () -> Unit,
) {
    val largeText = LocalDensity.current.fontScale >= 1.3f
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = strings.noteComposerLabel.uppercase(Locale.ROOT),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.2.sp,
            )
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp),
                enabled = !saving,
                label = { Text(strings.noteEditLabel) },
                placeholder = { Text(strings.noteComposerPlaceholder) },
                supportingText = { Text(strings.noteComposerHelp) },
            )
            val save: @Composable (Modifier) -> Unit = { buttonModifier ->
                Button(
                    onClick = onSave,
                    enabled = draft.isNotBlank() && !saving,
                    shape = MaterialTheme.shapes.small,
                    modifier = buttonModifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp),
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Text(strings.noteSaving)
                    } else {
                        Text(strings.noteSave)
                    }
                }
            }
            if (largeText) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    save(Modifier)
                    if (draft.isNotBlank()) {
                        TextButton(
                            onClick = onClear,
                            enabled = !saving,
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp),
                        ) { Text(strings.noteDraftClear) }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    save(Modifier.weight(1f))
                    if (draft.isNotBlank()) {
                        TextButton(
                            onClick = onClear,
                            enabled = !saving,
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                        ) { Text(strings.noteDraftClear) }
                    }
                }
            }
            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (messageIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
    }
}

/** "No details yet" is a heading of its own; a saved note without details shows no summary line. */
internal fun noteSummaryText(
    summary: CaptureContextSummary?,
    strings: AegisStrings,
): String? {
    if (summary == null || summary.eventCount == 0) return null
    val parts = buildList {
        add(strings.noteDetailCount(summary.eventCount))
        if (summary.acceptedLinkCount > 0) add(strings.noteLinkedCount(summary.acceptedLinkCount))
        if (summary.proposedLinkCount > 0) add(strings.noteProposedCount(summary.proposedLinkCount))
        if (summary.resolvedEventCount > 0) add(strings.noteRecordedCount(summary.resolvedEventCount))
    }
    return parts.joinToString(" · ")
}

/** The saved moment, or the truth that the stored row did not record one. */
internal fun noteMomentText(note: CaptureMessageEntity, strings: AegisStrings): String {
    val instant = note.capturedAtEpochMillis ?: return strings.noteTimeUnknown
    val zone = note.captureTimezone
        ?.let { TimeZone.getTimeZone(it) }
        ?: TimeZone.getDefault()
    return weekdayMomentText(instant, strings.language, zone)
}

@Composable
internal fun NoteAiHandoffBanner(
    title: String,
    supporting: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = if (enabled) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (enabled) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = title }
            .then(
                if (enabled) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            ),
        shape = MaterialTheme.shapes.large,
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 64.dp)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (enabled) {
                AegisIcon(
                    glyph = AegisGlyph.CHEVRON_RIGHT,
                    tint = content,
                    size = 20.dp,
                )
            }
        }
    }
}

@Composable
internal fun NoteNoticeBanner(
    message: String,
    closeLabel: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AegisIcon(
                glyph = AegisGlyph.CHECK,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                size = 18.dp,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { contentDescription = closeLabel },
            ) {
                AegisIcon(
                    glyph = AegisGlyph.CROSS,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    size = 18.dp,
                )
            }
        }
    }
}

@Composable
internal fun NoteStateMessage(
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    bodyStyle: Boolean = false,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = if (bodyStyle) {
                    MaterialTheme.typography.bodyMedium
                } else {
                    MaterialTheme.typography.titleMedium
                },
                color = if (bodyStyle) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (body != null) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (actionLabel != null && onAction != null) {
                TextButton(
                    onClick = onAction,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) { Text(actionLabel) }
            }
        }
    }
}

/** Why a note command did not complete, in the user's own language. */
internal fun WorkflowCommandOutcome.noteFailureMessage(
    strings: AegisStrings,
    fallback: String,
): String = when (this) {
    is WorkflowCommandOutcome.Conflict -> strings.noteStale
    WorkflowCommandOutcome.NotFound -> strings.noteMissingTitle
    else -> fallback
}

/**
 * The shared framed surface used by the existing chat and outcome cards, kept for the flows that
 * are not redesigned yet. Its accent strip names the section without carrying meaning by color
 * alone: every caller has a heading of its own.
 */
@Composable
internal fun CapturePanel(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(accent ?: MaterialTheme.colorScheme.outlineVariant),
            )
            content()
        }
    }
}

private fun WorkflowCommandOutcome.savedCaptureId(): Long? = when (this) {
    is WorkflowCommandOutcome.Applied -> (change as? WorkflowChange.CaptureSaved)?.captureId
    is WorkflowCommandOutcome.NoChange -> (change as? WorkflowChange.CaptureSaved)?.captureId
    else -> null
}
