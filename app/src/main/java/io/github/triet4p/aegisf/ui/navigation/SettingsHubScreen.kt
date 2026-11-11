package io.github.triet4p.aegisf.ui.navigation

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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.triet4p.aegisf.data.AegisLanguage
import io.github.triet4p.aegisf.data.AegisThemeMode
import io.github.triet4p.aegisf.data.NotificationAccessStore
import io.github.triet4p.aegisf.notification.isNotificationAccessGranted
import io.github.triet4p.aegisf.provider.HostedProviderSettingsStore
import io.github.triet4p.aegisf.ui.components.AegisChoice
import io.github.triet4p.aegisf.ui.components.AegisChoiceSheet
import io.github.triet4p.aegisf.ui.components.AegisGlyph
import io.github.triet4p.aegisf.ui.components.AegisIcon
import io.github.triet4p.aegisf.ui.components.AegisSettingRow
import io.github.triet4p.aegisf.ui.settingsStrings
import io.github.triet4p.aegisf.ui.text.LocalAegisStrings

/** The sheet the settings hub currently has open, if any. */
private enum class SettingsSheet { LANGUAGE, APPEARANCE }

/**
 * The settings hub: a short, labelled list of destinations, never a page of forms.
 *
 * Each row opens the focused child that owns one task — the notification listener and its
 * allowlist, one hosted provider profile, the assistant's skills and stored history, or the
 * privacy/retention disclosure. Routine presentation choices stay on the hub; advanced
 * configuration is one tap deeper; destructive actions live inside the child that owns them.
 * Nothing here writes a financial record, starts a run, or reads a secret.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsHubScreen(
    language: AegisLanguage,
    onLanguageChange: (AegisLanguage) -> Unit,
    themeMode: AegisThemeMode,
    onThemeModeChange: (AegisThemeMode) -> Unit,
    notificationAccessStore: NotificationAccessStore,
    hostedProviderSettingsStore: HostedProviderSettingsStore,
    onOpenBankNotifications: () -> Unit,
    onOpenHostedProvider: () -> Unit,
    onOpenSkillsHistory: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onNavigateUp: () -> Unit,
) {
    val baseStrings = LocalAegisStrings.current
    val strings = remember(baseStrings.language) { settingsStrings(baseStrings.language) }
    val context = LocalContext.current
    var openSheet by rememberSaveable { mutableStateOf<SettingsSheet?>(null) }
    var accessGranted by remember { mutableStateOf(isNotificationAccessGranted(context)) }
    var allowedCount by remember {
        mutableIntStateOf(
            runCatching { notificationAccessStore.loadAllowedPackages().size }.getOrDefault(0),
        )
    }
    var providerName by remember {
        mutableStateOf(
            runCatching { hostedProviderSettingsStore.active()?.name }.getOrNull(),
        )
    }
    var versionName by remember {
        mutableStateOf(
            runCatching {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull().orEmpty(),
        )
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessGranted = isNotificationAccessGranted(context)
                allowedCount = runCatching {
                    notificationAccessStore.loadAllowedPackages().size
                }.getOrDefault(0)
                providerName = runCatching {
                    hostedProviderSettingsStore.active()?.name
                }.getOrNull()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val appearanceName = when (themeMode) {
        AegisThemeMode.SYSTEM -> strings.appearanceSystem
        AegisThemeMode.LIGHT -> strings.appearanceLight
        AegisThemeMode.DARK -> strings.appearanceDark
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = strings.title,
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
            HubSection(title = strings.preferencesSection) {
                AegisSettingRow(
                    title = strings.languageRow,
                    value = strings.languageName(language),
                    onClick = { openSheet = SettingsSheet.LANGUAGE },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AegisSettingRow(
                    title = strings.appearanceRow,
                    value = appearanceName,
                    onClick = { openSheet = SettingsSheet.APPEARANCE },
                )
            }

            HubSection(title = strings.configurationSection) {
                AegisSettingRow(
                    title = strings.bankRow,
                    supporting = listOf(
                        if (accessGranted) {
                            strings.bankValueOn(allowedCount)
                        } else {
                            strings.bankValueOff(allowedCount)
                        },
                        strings.bankSupporting,
                    ).joinToString(" · "),
                    onClick = onOpenBankNotifications,
                )
            }

            HubSection(title = strings.aiSection) {
                AegisSettingRow(
                    title = strings.providerRow,
                    supporting = listOf(
                        providerName ?: strings.providerNotConfigured,
                        strings.providerSupporting,
                    ).joinToString(" · "),
                    onClick = onOpenHostedProvider,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AegisSettingRow(
                    title = strings.skillsRow,
                    supporting = strings.skillsSupporting,
                    onClick = onOpenSkillsHistory,
                )
            }

            HubSection(title = strings.privacySection) {
                AegisSettingRow(
                    title = strings.privacyRow,
                    supporting = strings.privacySupporting,
                    onClick = onOpenPrivacy,
                )
            }

            Text(
                text = strings.aboutSection,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = strings.versionLabel(versionName.ifBlank { "—" }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = strings.localFirst,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }

    when (openSheet) {
        SettingsSheet.LANGUAGE -> AegisChoiceSheet(
            title = strings.chooseLanguage,
            options = AegisLanguage.entries.map { option ->
                AegisChoice(option, strings.languageName(option))
            },
            selected = language,
            onSelect = onLanguageChange,
            onDismiss = { openSheet = null },
        )

        SettingsSheet.APPEARANCE -> AegisChoiceSheet(
            title = strings.appearanceRow,
            options = listOf(
                AegisChoice(AegisThemeMode.SYSTEM, strings.appearanceSystem),
                AegisChoice(AegisThemeMode.LIGHT, strings.appearanceLight),
                AegisChoice(AegisThemeMode.DARK, strings.appearanceDark),
            ),
            selected = themeMode,
            onSelect = onThemeModeChange,
            onDismiss = { openSheet = null },
        )

        null -> Unit
    }
}

@Composable
private fun HubSection(title: String, content: @Composable () -> Unit) {
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
            Column { content() }
        }
    }
}
