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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.LedgerValidationException
import io.github.triet4p.aegisf.data.NotificationAccessStore
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.notification.BankNotificationListener
import io.github.triet4p.aegisf.notification.ListenerConnection
import io.github.triet4p.aegisf.notification.ListenerRebindOutcome
import io.github.triet4p.aegisf.notification.appInfoSettingsIntent
import io.github.triet4p.aegisf.notification.isIntakePermitted
import io.github.triet4p.aegisf.notification.isNotificationAccessGranted
import io.github.triet4p.aegisf.notification.notificationAccessSettingsIntent
import io.github.triet4p.aegisf.notification.requestListenerRebind
import io.github.triet4p.aegisf.ui.components.AegisGlyph
import io.github.triet4p.aegisf.ui.components.AegisIcon
import io.github.triet4p.aegisf.ui.text.LocalAegisStrings
import kotlinx.coroutines.flow.flowOf

/**
 * Bank-notification intake, as one focused destination.
 *
 * The screen has one primary action — open the system's notification-access grant — and keeps
 * everything else secondary: the live listener binding, the package allowlist, a supported
 * reconnect request, the restricted-settings path, and a read-only diagnostic block. It never
 * shows notification content, never tests intake with a fabricated notification, and never
 * writes to the ledger. Revoking access stops new intake while manual recording is unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BankNotificationSettingsScreen(
    accessStore: NotificationAccessStore,
    queryService: WorkflowQueryService,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val baseStrings = LocalAegisStrings.current
    val strings = remember(baseStrings.language) { settingsStrings(baseStrings.language) }
    val context = LocalContext.current

    var accessGranted by remember { mutableStateOf(isNotificationAccessGranted(context)) }
    var allowedPackages by remember { mutableStateOf(accessStore.loadAllowedPackages()) }
    var refreshToken by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessGranted = isNotificationAccessGranted(context)
                allowedPackages = accessStore.loadAllowedPackages()
                BankNotificationListener.updateAllowedPackages(allowedPackages)
                refreshToken += 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val connection: ListenerConnection by BankNotificationListener.connection.collectAsState()
    val pendingFlow = remember(queryService, refreshToken) {
        runCatching {
            queryService.observeDraftCandidateCount(IntakeReviewStatus.PENDING)
        }.getOrElse { flowOf(-1) }
    }
    val pendingCount by pendingFlow.collectAsState(initial = null)

    var adding by rememberSaveable { mutableStateOf(false) }
    var packageInput by rememberSaveable { mutableStateOf("") }
    var inputError by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    var rebindFeedback by rememberSaveable { mutableStateOf<String?>(null) }

    BackHandler(onBack = onNavigateUp)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = strings.bankTitle,
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = strings.bankAccessHelp,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { context.startActivity(notificationAccessSettingsIntent()) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
            ) {
                Text(
                    if (accessGranted) strings.bankReviewAccess else strings.bankOpenAccess,
                )
            }

            StatusGroup(strings = strings, content = {
                StatusSection(
                    strings = strings,
                    accessGranted = accessGranted,
                    connection = connection,
                    allowedCount = allowedPackages.size,
                    pendingCount = pendingCount?.takeIf { it >= 0 },
                )
            })

            if (accessGranted && connection is ListenerConnection.Disconnected) {
                OutlinedButton(
                    onClick = {
                        rebindFeedback = when (
                            requestListenerRebind(context, accessGranted = true)
                        ) {
                            ListenerRebindOutcome.Requested -> strings.bankReconnectRequested
                            ListenerRebindOutcome.RefusedNoAccess -> strings.bankReconnectRefused
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text(strings.bankReconnect)
                }
                rebindFeedback?.let { feedback ->
                    Text(
                        text = feedback,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = strings.bankReconnectHelp,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = strings.bankRestrictedHelp,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { context.startActivity(appInfoSettingsIntent(context)) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text(strings.bankOpenAppInfo)
                }
                OutlinedButton(
                    onClick = {
                        accessGranted = isNotificationAccessGranted(context)
                        allowedPackages = accessStore.loadAllowedPackages()
                        BankNotificationListener.updateAllowedPackages(allowedPackages)
                        refreshToken += 1
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("bank-refresh"),
                ) {
                    Text(strings.bankRefresh)
                }
            }

            HorizontalDivider()

            Text(
                text = strings.bankDiagnosticsSection,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = strings.bankDiagnosticsHelp,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            Text(
                text = strings.bankAppsSection,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = strings.bankAppsHelp,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (allowedPackages.isEmpty()) {
                Text(
                    text = strings.bankAppsEmpty,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    allowedPackages.forEach { packageName ->
                        AllowedPackageRow(
                            packageName = packageName,
                            intakeReady = isIntakePermitted(
                                accessGranted = accessGranted,
                                allowedPackages = allowedPackages,
                                packageName = packageName,
                            ),
                            readyLabel = strings.bankAppReady,
                            waitingLabel = strings.bankAppWaiting,
                            removeLabel = strings.bankRemove,
                            onRemove = { removing = packageName },
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = {
                    packageInput = ""
                    inputError = null
                    adding = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("bank-add"),
            ) {
                Text(strings.bankAddApp)
            }

            Text(
                text = strings.bankDraftDisclaimer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }

    if (adding) {
        AddBankAppDialog(
            strings = strings,
            input = packageInput,
            onInputChange = {
                packageInput = it
                inputError = null
            },
            error = inputError,
            onDismiss = { adding = false },
            onConfirm = {
                try {
                    accessStore.addPackage(packageInput)
                    allowedPackages = accessStore.loadAllowedPackages()
                    BankNotificationListener.updateAllowedPackages(allowedPackages)
                    adding = false
                    packageInput = ""
                } catch (_: LedgerValidationException) {
                    inputError = strings.bankPackageInvalid
                }
            },
        )
    }

    removing?.let { packageName ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(strings.bankRemoveTitle) },
            text = { Text(strings.bankRemoveBody(packageName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        accessStore.removePackage(packageName)
                        allowedPackages = accessStore.loadAllowedPackages()
                        BankNotificationListener.updateAllowedPackages(allowedPackages)
                        removing = null
                    },
                    modifier = Modifier.testTag("bank-remove-confirm"),
                ) {
                    Text(strings.bankRemoveConfirm)
                }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) { Text(strings.cancel) }
            },
        )
    }
}

@Composable
private fun StatusGroup(
    strings: SettingsStrings,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            Text(
                text = strings.bankStatusSection,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp),
            )
            content()
        }
    }
}

@Composable
private fun StatusSection(
    strings: SettingsStrings,
    accessGranted: Boolean,
    connection: ListenerConnection,
    allowedCount: Int,
    pendingCount: Int?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusRow(
            label = strings.bankAccessLabel,
            value = if (accessGranted) strings.bankAccessGranted else strings.bankAccessNotGranted,
        )
        StatusRow(
            label = strings.bankListenerLabel,
            value = when (connection) {
                is ListenerConnection.Connected -> strings.bankListenerConnected
                ListenerConnection.Disconnected ->
                    if (!accessGranted) {
                        strings.bankListenerDisconnectedAccess
                    } else {
                        strings.bankListenerDisconnectedWaiting
                    }
            },
        )
        StatusRow(
            label = strings.bankAllowedLabel,
            value = strings.bankAllowedCount(allowedCount),
        )
        StatusRow(
            label = strings.bankPendingLabel,
            value = pendingCount?.let(strings.bankPendingCount) ?: strings.bankPendingUnread,
        )
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp),
        )
    }
}

@Composable
private fun AllowedPackageRow(
    packageName: String,
    intakeReady: Boolean,
    readyLabel: String,
    waitingLabel: String,
    removeLabel: String,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = packageName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
            )
            Text(
                text = if (intakeReady) readyLabel else waitingLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = onRemove,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("bank-remove-$packageName"),
        ) {
            Text(removeLabel)
        }
    }
}

@Composable
private fun AddBankAppDialog(
    strings: SettingsStrings,
    input: String,
    onInputChange: (String) -> Unit,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.bankAddTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings.bankAddHelp)
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.testTag("bank-package"),
                    label = { Text(strings.bankPackageLabel) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(strings.save) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancel) }
        },
    )
}
