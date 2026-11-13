package io.github.triet4p.aegisf.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.triet4p.aegisf.ui.components.AegisGlyph
import io.github.triet4p.aegisf.ui.components.AegisIcon
import io.github.triet4p.aegisf.ui.text.LocalAegisStrings
import io.github.triet4p.aegisf.data.CompleteStateExporter
import io.github.triet4p.aegisf.data.CompleteStateRestorer
import io.github.triet4p.aegisf.data.RestorePreview
import io.github.triet4p.aegisf.data.RestoreRecoveryCopy
import io.github.triet4p.aegisf.data.RestoreRecoveryOutcome
import io.github.triet4p.aegisf.data.RestoreRejection
import io.github.triet4p.aegisf.data.RestoreRejectionCode
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings child: a truthful privacy and retention disclosure for this release.
 *
 * Manual export and validated restore are both available here. Export warns before opening
 * Android's document picker and repeats the plaintext/sensitive-content notice in the exported
 * JSON. Restore warns once, then shows exactly what the picked file contains, requires an
 * explicit replacement confirmation, and keeps an app-private copy of the state it replaced.
 * The screen never renders credentials, endpoint details, or diagnostic records.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsPrivacyScreen(
    onNavigateUp: () -> Unit,
    exporter: CompleteStateExporter,
    restorer: CompleteStateRestorer,
    modifier: Modifier = Modifier,
) {
    val baseStrings = LocalAegisStrings.current
    val strings = remember(baseStrings.language) { settingsStrings(baseStrings.language) }
    BackHandler(onBack = onNavigateUp)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showExportWarning by rememberSaveable { mutableStateOf(false) }
    var isExporting by rememberSaveable { mutableStateOf(false) }
    var exportStatus by rememberSaveable { mutableStateOf<String?>(null) }
    var showRestoreWarning by rememberSaveable { mutableStateOf(false) }
    var isRestoring by rememberSaveable { mutableStateOf(false) }
    var restoreStatus by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreFailureCode by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreFailureDetail by rememberSaveable { mutableStateOf<String?>(null) }
    // The previewed bytes are the applied bytes: confirming never re-reads the picked location.
    var pendingBackup by remember { mutableStateOf<ByteArray?>(null) }
    var pendingPreview by remember { mutableStateOf<RestorePreview?>(null) }
    // Only the explicit previous-state action may start over an interrupted journal.
    var pendingIsRecoveryCopy by remember { mutableStateOf(false) }
    var recoveryCopies by remember { mutableStateOf<List<RestoreRecoveryCopy>>(emptyList()) }

    val exportPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            isExporting = true
            exportStatus = null
            scope.launch {
                val succeeded = try {
                    withContext(Dispatchers.IO) {
                        val output = requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) {
                            "The selected file could not be opened."
                        }
                        output.use { exporter.writeTo(it) }
                    }
                    true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                isExporting = false
                exportStatus = if (succeeded) {
                    strings.privacyExportSucceeded
                } else {
                    strings.privacyExportFailed
                }
            }
        }
    }

    val restorePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            isRestoring = true
            restoreStatus = null
            restoreFailureCode = null
            restoreFailureDetail = null
            scope.launch {
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        val stream = context.contentResolver.openInputStream(uri)
                            ?: throw RestoreRejection(
                                RestoreRejectionCode.INVALID,
                                "The selected file could not be opened. Nothing was changed.",
                            )
                        stream.use { restorer.readBackup(it) }
                    }
                    val preview = withContext(Dispatchers.IO) { restorer.preview(bytes) }
                    pendingBackup = bytes
                    pendingPreview = preview
                    pendingIsRecoveryCopy = false
                } catch (rejection: RestoreRejection) {
                    restoreFailureCode = rejection.code.name
                    restoreFailureDetail = rejection.message
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    restoreFailureCode = RestoreRejectionCode.INVALID.name
                    restoreFailureDetail = strings.privacyRestoreFailed
                } finally {
                    isRestoring = false
                }
            }
        }
    }

    fun clearPendingRestore() {
        pendingBackup = null
        pendingPreview = null
        pendingIsRecoveryCopy = false
    }

    fun confirmPendingRestore() {
        val bytes = pendingBackup ?: return
        isRestoring = true
        restoreStatus = null
        restoreFailureCode = null
        restoreFailureDetail = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    restorer.restore(bytes, allowPendingReplacement = pendingIsRecoveryCopy)
                }
                restoreStatus = buildString {
                    append(strings.privacyRestoreSucceeded)
                    if (result.hostedProfiles.imported > 0) {
                        append(' ')
                        append(
                            strings.privacyRestoreProfilesImported(
                                result.hostedProfiles.imported,
                                result.hostedProfiles.withoutCredential,
                            ),
                        )
                    }
                }
                recoveryCopies = withContext(Dispatchers.IO) { restorer.recoveryCopies() }
            } catch (rejection: RestoreRejection) {
                restoreFailureCode = rejection.code.name
                restoreFailureDetail = rejection.message
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                restoreFailureCode = RestoreRejectionCode.INVALID.name
                restoreFailureDetail = strings.privacyRestoreFailed
            } finally {
                isRestoring = false
                clearPendingRestore()
            }
        }
    }

    fun previewRecoveryCopy(copy: RestoreRecoveryCopy) {
        isRestoring = true
        restoreStatus = null
        restoreFailureCode = null
        restoreFailureDetail = null
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { restorer.readRecoveryCopy(copy) }
                val preview = withContext(Dispatchers.IO) { restorer.preview(bytes) }
                pendingBackup = bytes
                pendingPreview = preview
                pendingIsRecoveryCopy = true
            } catch (rejection: RestoreRejection) {
                restoreFailureCode = rejection.code.name
                restoreFailureDetail = rejection.message
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                restoreFailureCode = RestoreRejectionCode.INVALID.name
                restoreFailureDetail = strings.privacyRestoreFailed
            } finally {
                isRestoring = false
            }
        }
    }

    LaunchedEffect(Unit) {
        val outcome = withContext(Dispatchers.IO) { restorer.completeInterruptedRestore() }
        if (outcome is RestoreRecoveryOutcome.Blocked) {
            restoreFailureCode = RestoreRejectionCode.RECOVERY_PENDING.name
            restoreFailureDetail = outcome.detail
        }
        recoveryCopies = withContext(Dispatchers.IO) { restorer.recoveryCopies() }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = strings.privacyTitle,
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
            DisclosureSection(
                title = strings.privacyDeviceSection,
                body = strings.privacyDeviceBody,
            )
            DisclosureSection(
                title = strings.privacyCredentialsSection,
                body = strings.privacyCredentialsBody,
            )
            DisclosureSection(
                title = strings.privacyHostedSection,
                body = strings.privacyHostedBody,
            )
            DisclosureSection(
                title = strings.privacyRetentionSection,
                body = strings.privacyRetentionBody,
            )
            DisclosureSection(
                title = strings.privacyControlSection,
                body = strings.privacyControlBody,
            )
            DisclosureSection(
                title = strings.privacyExportSection,
                body = strings.privacyExportBody,
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !isExporting,
                onClick = { showExportWarning = true },
            ) {
                Text(text = if (isExporting) strings.privacyExporting else strings.privacyExportAction)
            }
            exportStatus?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DisclosureSection(
                title = strings.privacyRestoreSection,
                body = strings.privacyRestoreBody,
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !isRestoring && !isExporting,
                onClick = { showRestoreWarning = true },
            ) {
                Text(text = if (isRestoring) strings.privacyRestoring else strings.privacyRestoreAction)
            }
            restoreStatus?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            restoreFailureCode?.let { code ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = restoreFailureHeadline(strings, code),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    restoreFailureDetail?.let { detail ->
                        Text(
                            text = "${strings.privacyRestoreDetails}: $detail",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (recoveryCopies.isNotEmpty()) {
                DisclosureSection(
                    title = strings.privacyRecoverySection,
                    body = strings.privacyRecoveryBody,
                )
                val newest = recoveryCopies.first()
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = formatRestoreInstant(newest.createdAtEpochMillis),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isRestoring && !isExporting,
                            onClick = { previewRecoveryCopy(newest) },
                        ) {
                            Text(text = strings.privacyRecoveryAction)
                        }
                    }
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = strings.privacyBackupDisabled,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = strings.privacyNoBackupControls,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = strings.privacyReleaseNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
    if (showExportWarning) {
        AlertDialog(
            onDismissRequest = { showExportWarning = false },
            title = { Text(text = strings.privacyExportWarningTitle) },
            text = { Text(text = strings.privacyExportWarningBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExportWarning = false
                        exportPicker.launch("aegis-f-state.json")
                    },
                ) {
                    Text(text = strings.privacyExportContinue)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportWarning = false }) {
                    Text(text = strings.cancel)
                }
            },
        )
    }
    if (showRestoreWarning) {
        AlertDialog(
            onDismissRequest = { showRestoreWarning = false },
            title = { Text(text = strings.privacyRestoreWarningTitle) },
            text = { Text(text = strings.privacyRestoreWarningBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestoreWarning = false
                        restorePicker.launch(
                            arrayOf("application/json", "text/plain", "application/octet-stream"),
                        )
                    },
                ) {
                    Text(text = strings.privacyRestoreContinue)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreWarning = false }) {
                    Text(text = strings.cancel)
                }
            },
        )
    }
    val preview = pendingPreview
    if (preview != null) {
        AlertDialog(
            onDismissRequest = {
                if (!isRestoring) clearPendingRestore()
            },
            title = { Text(text = strings.privacyRestorePreviewTitle) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = strings.privacyRestorePreviewIntro,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "${strings.privacyRestorePreviewExportedAt}: " +
                            formatRestoreInstant(preview.exportedAtEpochMillis),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PreviewCount(strings.privacyRestorePreviewCategories, preview.categoryCount)
                    PreviewCount(strings.privacyRestorePreviewRules, preview.ruleCount)
                    PreviewCount(strings.privacyRestorePreviewTransactions, preview.transactionCount)
                    PreviewCount(
                        strings.privacyRestorePreviewDrafts,
                        preview.draftCount,
                        detail = "${strings.privacyRestorePreviewPendingDrafts}: " +
                            preview.pendingDraftCount,
                    )
                    PreviewCount(strings.privacyRestorePreviewCaptures, preview.captureCount)
                    PreviewCount(strings.privacyRestorePreviewEvents, preview.eventCount)
                    PreviewCount(strings.privacyRestorePreviewLinks, preview.linkCount)
                    PreviewCount(
                        strings.privacyRestorePreviewSnapshots,
                        preview.historicalSnapshotCount,
                    )
                    PreviewCount(strings.privacyRestorePreviewProfiles, preview.hostedProfileCount)
                    if (preview.reopenedHostedCategoryCount > 0) {
                        PreviewCount(
                            strings.privacyRestorePreviewReopened,
                            preview.reopenedHostedCategoryCount,
                        )
                    }
                    Text(
                        text = strings.privacyRestorePreviewReplacement,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = strings.privacyRestorePreviewRecovery,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = strings.privacyRestorePreviewCredentials,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = strings.privacyRestorePreviewSnapshotsPolicy,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isRestoring,
                    onClick = ::confirmPendingRestore,
                ) {
                    Text(
                        text = strings.privacyRestoreConfirm,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isRestoring,
                    onClick = ::clearPendingRestore,
                ) {
                    Text(text = strings.cancel)
                }
            },
        )
    }
}

private fun restoreFailureHeadline(strings: SettingsStrings, code: String): String = when (code) {
    RestoreRejectionCode.TOO_LARGE.name -> strings.privacyRestoreTooLarge
    RestoreRejectionCode.RECOVERY_PENDING.name -> strings.privacyRestoreRecoveryPending
    RestoreRejectionCode.UNSUPPORTED_FORMAT.name,
    RestoreRejectionCode.UNSUPPORTED_VERSION.name,
    RestoreRejectionCode.UNSUPPORTED_SCHEMA.name,
    -> strings.privacyRestoreUnsupported

    else -> strings.privacyRestoreInvalid
}

private fun formatRestoreInstant(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMillis))

@Composable
private fun PreviewCount(label: String, count: Int, detail: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            text = "$label: $count",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        detail?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DisclosureSection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
