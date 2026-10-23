package io.github.triet4p.aegisf.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.triet4p.aegisf.data.laya.LayaModelProvisioner
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The user-controlled, offline path for importing and verifying the pinned model locally. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayaProvisioningScreen(
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val provisioner = remember(context) {
        LayaModelProvisioner(context.filesDir) {
            context.assets.open("laya/laya_tokenizer.bin")
        }
    }
    val screenState = remember { MutableStateFlow<ScreenState>(ScreenState.Checking) }
    val current by screenState.collectAsState()
    val scope = rememberCoroutineScope()
    var cancellationJob by remember { mutableStateOf<Job?>(null) }

    fun requestCancellation() {
        val job = cancellationJob ?: return
        val operation = when (current) {
            ScreenState.Checking -> ScreenState.Operation.Check
            is ScreenState.Copying -> ScreenState.Operation.Import
            else -> return
        }
        screenState.value = ScreenState.Cancelling(
            operation = operation,
            progress = (current as? ScreenState.Copying)?.progress,
        )
        job.cancel()
    }

    LaunchedEffect(provisioner) {
        val operationCancellation = Job()
        cancellationJob = operationCancellation
        screenState.value = ScreenState.Checking
        try {
            val status = withContext(Dispatchers.IO) {
                provisioner.inspect(operationCancellation)
            }
            screenState.value = statusToScreenState(status)
        } catch (cancelled: CancellationException) {
            if (!operationCancellation.isCancelled) throw cancelled
            screenState.value = ScreenState.Cancelled(ScreenState.Operation.Check)
        } finally {
            if (cancellationJob === operationCancellation) cancellationJob = null
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { selectedTree ->
        if (selectedTree != null) {
            scope.launch {
                val operationCancellation = Job()
                cancellationJob = operationCancellation
                screenState.value = ScreenState.Copying(
                    LayaModelProvisioner.Progress("Preparing transfer", 0L, MODEL_TOTAL_BYTES),
                )
                try {
                    val result = withContext(Dispatchers.IO) {
                        val sources = selectedBundleSources(context, selectedTree)
                        provisioner.provision(sources, operationCancellation) { progress ->
                            screenState.update { state ->
                                if (state is ScreenState.Cancelling) {
                                    state
                                } else {
                                    ScreenState.Copying(progress)
                                }
                            }
                        }
                    }
                    screenState.value = statusToScreenState(result)
                } catch (cancelled: CancellationException) {
                    if (!operationCancellation.isCancelled) throw cancelled
                    screenState.value = ScreenState.Cancelled(ScreenState.Operation.Import)
                } catch (error: Exception) {
                    screenState.value = ScreenState.CorruptOrIncompatible(
                        "Cannot read the selected folder: " +
                            (error.message ?: error.javaClass.simpleName),
                    )
                } finally {
                    if (cancellationJob === operationCancellation) cancellationJob = null
                }
            }
        }
    }

    val busy = current is ScreenState.Checking ||
        current is ScreenState.Copying ||
        current is ScreenState.Cancelling
    BackHandler(enabled = busy) { requestCancellation() }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Offline model") },
                navigationIcon = {
                    TextButton(onClick = onNavigateUp, enabled = !busy) { Text("Back") }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = "Local model status",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            when (val snapshot = current) {
                ScreenState.Checking -> {
                    CircularProgressIndicator()
                    Text("Checking the pinned files and opening the local runtime…")
                    TextButton(
                        onClick = { requestCancellation() },
                        enabled = cancellationJob?.isActive == true,
                    ) { Text("Cancel check") }
                }

                is ScreenState.Copying -> {
                    Text(
                        if (snapshot.progress.copiedBytes >= snapshot.progress.totalBytes) {
                            "Files verified. Checking that this model opens correctly…"
                        } else {
                            "Copying ${snapshot.progress.file}"
                        },
                    )
                    LinearProgressIndicator(
                        progress = {
                            (snapshot.progress.copiedBytes.toFloat() /
                                snapshot.progress.totalBytes.toFloat()).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${snapshot.progress.copiedBytes.formatBytes()} of " +
                            "${snapshot.progress.totalBytes.formatBytes()}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(
                        onClick = { requestCancellation() },
                        enabled = cancellationJob?.isActive == true,
                    ) { Text("Cancel import") }
                }

                is ScreenState.Cancelling -> {
                    CircularProgressIndicator()
                    Text(
                        if (snapshot.operation == ScreenState.Operation.Import) {
                            "Cancellation requested. Waiting for the current folder/file read; " +
                                "a native runtime open cannot be interrupted mid-call, so the " +
                                "page stays busy until it returns."
                        } else {
                            "Cancellation requested. The native runtime open cannot be " +
                                "interrupted mid-call; waiting for it or the active file hash."
                        },
                    )
                    snapshot.progress?.let { progress ->
                        LinearProgressIndicator(
                            progress = {
                                (progress.copiedBytes.toFloat() /
                                    progress.totalBytes.toFloat()).coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${progress.copiedBytes.formatBytes()} of " +
                                "${progress.totalBytes.formatBytes()}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                is ScreenState.Cancelled -> {
                    StatusCard(
                        title = if (snapshot.operation == ScreenState.Operation.Import) {
                            "Import cancelled"
                        } else {
                            "Model check cancelled"
                        },
                        body = if (snapshot.operation == ScreenState.Operation.Import) {
                            "The incomplete staging copy was discarded and was not published. " +
                                "Any model that was already installed remains unchanged."
                        } else {
                            "The check stopped without changing the installed model. Manual " +
                                "transaction entry remains available in the Manual destination."
                        },
                    )
                }

                ScreenState.Ready -> {
                    StatusCard(
                        title = "Ready — verified on this device",
                        body = "The pinned model files passed their size and SHA-256 checks, " +
                            "and the local ONNX runtime opened and completed its compatibility " +
                            "probe. Model inference does not require a network connection. " +
                            "This does not add suggestions or change ledger records.",
                    )
                    Text(
                        "The installed model is kept in app-private storage. Import is disabled " +
                            "while a verified copy is present, so it is not replaced by accident.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is ScreenState.Missing -> {
                    StatusCard(
                        title = "Model missing or incomplete",
                        body = if (snapshot.files.isEmpty()) {
                            "The pinned model is not installed. Manual transaction entry remains " +
                                "available in the Manual destination."
                        } else {
                            "These required files are missing: ${snapshot.files.joinToString()}. " +
                                "Manual transaction entry remains available in the Manual destination."
                        },
                    )
                    ImportInstructions()
                    Button(
                        onClick = { folderPicker.launch(null) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Choose model folder") }
                }

                is ScreenState.CorruptOrIncompatible -> {
                    StatusCard(
                        title = "Model corrupt or incompatible",
                        body = snapshot.reason + " Manual transaction entry remains available " +
                            "from the Manual destination.",
                        isError = true,
                    )
                    ImportInstructions()
                    Button(
                        onClick = { folderPicker.launch(null) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Replace with verified model") }
                }
            }

            OutlinedButton(
                onClick = onNavigateUp,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Return to ledger") }
        }
    }
}

@Composable
private fun ImportInstructions() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Prepare a local model folder", fontWeight = FontWeight.SemiBold)
            Text(
                "Place laya.onnx, laya.onnx.data, and laya_config.json together in one folder, " +
                    "then choose that folder. Use the pinned English export described in " +
                    "tools/laya/PROVENANCE.md. The app copies these files and its bundled " +
                    "tokenizer into private storage, verifies each pinned size and SHA-256, " +
                    "and publishes only after the runtime compatibility probe succeeds. " +
                    "The import itself uses the files you select; the app does not download " +
                    "or infer over the network.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun StatusCard(title: String, body: String, isError: Boolean = false) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isError) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

private fun statusToScreenState(status: LayaModelProvisioner.Status): ScreenState = when (status) {
    LayaModelProvisioner.Status.Ready -> ScreenState.Ready
    is LayaModelProvisioner.Status.Missing -> ScreenState.Missing(status.files)
    is LayaModelProvisioner.Status.CorruptOrIncompatible ->
        ScreenState.CorruptOrIncompatible(status.reason)
}

private fun selectedBundleSources(context: Context, treeUri: Uri): Map<String, () -> InputStream?> {
    val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocumentId)
    val columns = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    )
    val expectedNames = setOf("laya.onnx", "laya.onnx.data", "laya_config.json")
    val documents = mutableMapOf<String, Uri>()
    val ambiguousNames = mutableSetOf<String>()

    context.contentResolver.query(childrenUri, columns, null, null, null)?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        while (cursor.moveToNext()) {
            val name = cursor.getString(nameColumn) ?: continue
            if (name !in expectedNames) continue
            val documentId = cursor.getString(idColumn) ?: continue
            val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            if (documents.put(name, documentUri) != null) ambiguousNames += name
        }
    }
    ambiguousNames.forEach(documents::remove)
    return documents.mapValues { (_, documentUri) ->
        { context.contentResolver.openInputStream(documentUri) }
    }
}

private sealed interface ScreenState {
    data object Checking : ScreenState
    data object Ready : ScreenState
    data class Missing(val files: List<String>) : ScreenState
    data class CorruptOrIncompatible(val reason: String) : ScreenState
    data class Copying(val progress: LayaModelProvisioner.Progress) : ScreenState
    data class Cancelling(
        val operation: Operation,
        val progress: LayaModelProvisioner.Progress?,
    ) : ScreenState
    data class Cancelled(val operation: Operation) : ScreenState

    enum class Operation {
        Check,
        Import,
    }
}

private fun Long.formatBytes(): String = when {
    this >= 1024L * 1024L * 1024L -> "%.2f GiB".format(this / (1024.0 * 1024.0 * 1024.0))
    this >= 1024L * 1024L -> "%.0f MiB".format(this / (1024.0 * 1024.0))
    else -> "$this B"
}

private const val MODEL_TOTAL_BYTES = 1_690_383_278L
