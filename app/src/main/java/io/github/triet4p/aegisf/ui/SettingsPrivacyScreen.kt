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
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings child: a truthful privacy and retention disclosure for this release.
 *
 * Manual export is available without a restore control. It warns before opening Android's
 * document picker and repeats the plaintext/sensitive-content notice in the exported JSON. The
 * screen never renders credentials, endpoint details, or diagnostic records.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsPrivacyScreen(
    onNavigateUp: () -> Unit,
    exporter: CompleteStateExporter,
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
