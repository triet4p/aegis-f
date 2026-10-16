package io.github.triet4p.aegisf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.triet4p.aegisf.data.LedgerValidationException
import io.github.triet4p.aegisf.data.NotificationAccessStore
import io.github.triet4p.aegisf.notification.BankNotificationListener
import io.github.triet4p.aegisf.notification.ListenerConnection
import io.github.triet4p.aegisf.notification.ListenerRebindOutcome
import io.github.triet4p.aegisf.notification.appInfoSettingsIntent
import io.github.triet4p.aegisf.notification.isIntakePermitted
import io.github.triet4p.aegisf.notification.isNotificationAccessGranted
import io.github.triet4p.aegisf.notification.notificationAccessSettingsIntent
import io.github.triet4p.aegisf.notification.requestListenerRebind

/**
 * Bank-notification opt-in: access status, the allowlist, and revocation handling.
 *
 * Everything here is a choice the user makes deliberately. Notification access is granted
 * or revoked only in the system settings — this screen opens that settings page and
 * re-reads the system's answer when it resumes. The allowlist is the set of bank package
 * names the user's notifications may come from: adding takes a user-typed package name,
 * storing it only when it has the application-id shape (never a display name or a
 * guess); removing narrows future intake and never touches stored records.
 *
 * The status line names what the app sees: access granted or not, the live listener
 * binding, and which packages are allowlisted. Revoking access in system settings stops
 * intake — nothing new may be processed — while manual recording keeps working exactly
 * as before. When access is granted but the listener is disconnected, one explicit
 * button asks the system to reconnect through the supported rebind; the system answers
 * asynchronously, and the status line keeps telling the truth meanwhile. Reconnecting
 * only re-reads the notifications still showing on the phone — never dismissed history
 * and never a bank statement — and drafts already saved stay exactly as they are.
 *
 * This screen requests no permission itself. Notification access is a system settings
 * grant, not the POST_NOTIFICATIONS runtime permission (which only lets an app show its
 * own notifications); the status copy says so explicitly. No notification content is read
 * or shown here, and nothing here writes to the ledger.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BankNotificationSettingsScreen(
    accessStore: NotificationAccessStore,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // The system's answer, re-read on every resume: a revocation made in settings while
    // the user was away must show the moment this screen is visible again. The stored
    // allowlist is re-read alongside it, so it can never go stale on return.
    var accessGranted by remember { mutableStateOf(isNotificationAccessGranted(context)) }
    var allowedPackages by remember { mutableStateOf(accessStore.loadAllowedPackages()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessGranted = isNotificationAccessGranted(context)
                allowedPackages = accessStore.loadAllowedPackages()
                BankNotificationListener.updateAllowedPackages(allowedPackages)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val connection: ListenerConnection by
        BankNotificationListener.connection.collectAsState()
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
                title = { Text("Bank notifications") },
                navigationIcon = {
                    TextButton(onClick = onNavigateUp) { Text("Back") }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            StatusSection(
                accessGranted = accessGranted,
                connection = connection,
                allowedCount = allowedPackages.size,
            )

            Text(
                text = "Notification access lets the app see notifications from the bank " +
                    "apps you choose, so it can prepare transaction drafts. This is the " +
                    "system notification-access grant — not the permission an app uses " +
                    "to show its own notifications. Nothing is captured until access is " +
                    "granted and at least one bank app is listed below. Revoking access " +
                    "stops new intake while manual recording keeps working.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = {
                    context.startActivity(notificationAccessSettingsIntent())
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (accessGranted) "Review notification access" else "Open notification settings")
            }

            if (accessGranted && connection is ListenerConnection.Disconnected) {
                OutlinedButton(
                    onClick = {
                        rebindFeedback = when (
                            requestListenerRebind(context, accessGranted = true)
                        ) {
                            ListenerRebindOutcome.Requested ->
                                "Reconnection requested. The system reconnects in its own " +
                                    "time — the listener status above shows when it is back."
                            ListenerRebindOutcome.RefusedNoAccess ->
                                "Notification access is off, so nothing was requested. " +
                                    "Grant access in the system settings first."
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Ask the system to reconnect")
                }
                rebindFeedback?.let { feedback ->
                    Text(
                        text = feedback,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "Reconnecting only re-reads the notifications still showing on " +
                        "the phone. Anything already dismissed is not recovered — drafts " +
                        "already saved stay exactly as they are.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = "If the switch for Aegis F cannot be turned on — the phone says the " +
                    "setting is restricted for this install — open the app info page, choose " +
                    "the three-dot menu, and allow restricted settings there first; that is " +
                    "the phone's own protection for sideloaded apps. Then return to the " +
                    "notification settings above.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { context.startActivity(appInfoSettingsIntent(context)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Open app info")
            }

            HorizontalDivider()

            Text(
                text = "BANK APPS",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Only notifications from these packages may become drafts. " +
                    "Type the package name the phone shows for the bank app — not its " +
                    "display name. Nothing is captured from any other app.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (allowedPackages.isEmpty()) {
                Text(
                    text = "No bank apps listed yet. Manual recording works unchanged; " +
                        "automatic drafts start only after a package is added.",
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
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Add a bank app")
            }

            HorizontalDivider()

            Text(
                text = "These drafts are incomplete evidence, not a bank statement: the " +
                    "app cannot see past notifications and misses anything the bank " +
                    "does not send. Every draft stays a draft until it is reviewed and " +
                    "confirmed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (adding) {
        AddBankAppDialog(
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
                } catch (rejection: LedgerValidationException) {
                    // The typed value stays so it can be corrected; nothing is stored.
                    inputError = rejection.message ?: "That package name cannot be stored."
                }
            },
        )
    }

    removing?.let { packageName ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove this bank app?") },
            text = {
                Text(
                    "Notifications from $packageName will no longer become drafts. " +
                        "Stored records are unchanged.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        accessStore.removePackage(packageName)
                        allowedPackages = accessStore.loadAllowedPackages()
                        BankNotificationListener.updateAllowedPackages(allowedPackages)
                        removing = null
                    },
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) { Text("Cancel") }
            },
        )
    }
}

/**
 * What the app sees right now: the system grant, the live listener binding, and the
 * allowlist size. Each row names state, never content — no notification text appears.
 */
@Composable
private fun StatusSection(
    accessGranted: Boolean,
    connection: ListenerConnection,
    allowedCount: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "STATUS",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StatusRow(
            label = "Notification access",
            value = if (accessGranted) "Granted" else "Not granted",
        )
        val binding = when (connection) {
            is ListenerConnection.Connected -> "Listener connected"
            ListenerConnection.Disconnected ->
                if (!accessGranted) {
                    "Listener disconnected — access revoked"
                } else {
                    "Listener disconnected — waiting for the system"
                }
        }
        StatusRow(label = "Listener", value = binding)
        StatusRow(
            label = "Bank apps listed",
            value = if (allowedCount == 1) "1 package" else "$allowedCount packages",
        )
        if (!accessGranted) {
            Text(
                text = if (allowedCount == 0) {
                    "Access is off and no bank app is listed: nothing is captured, and " +
                        "manual recording works unchanged."
                } else {
                    "Access is off: nothing is captured, even from the listed " +
                        "packages. Manual recording works unchanged."
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        } else if (connection is ListenerConnection.Disconnected) {
            Text(
                text = "Access is on but the system has not connected the listener: " +
                    "nothing is captured until it reconnects. Drafts already saved " +
                    "are unchanged.",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        } else if (allowedCount == 0) {
            Text(
                text = "Access is on but no bank app is listed: nothing is captured yet.",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
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
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
private fun AllowedPackageRow(
    packageName: String,
    intakeReady: Boolean,
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
            )
            Text(
                text = if (intakeReady) "Ready" else "Waiting for access",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRemove) { Text("Remove") }
    }
}

@Composable
private fun AddBankAppDialog(
    input: String,
    onInputChange: (String) -> Unit,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a bank app") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Enter the bank app's package name exactly as it appears on the " +
                        "phone — for example from the system's app info for that bank " +
                        "app. Display names are not accepted, and nothing here guesses " +
                        "a package from a bank name.",
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    label = { Text("Package name") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
