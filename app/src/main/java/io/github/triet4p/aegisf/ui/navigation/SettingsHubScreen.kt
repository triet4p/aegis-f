package io.github.triet4p.aegisf.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.triet4p.aegisf.data.AegisLanguage
import io.github.triet4p.aegisf.data.AegisThemeMode
import io.github.triet4p.aegisf.ui.components.AegisChoice
import io.github.triet4p.aegisf.ui.components.AegisChoiceSheet
import io.github.triet4p.aegisf.ui.components.AegisGlyph
import io.github.triet4p.aegisf.ui.components.AegisIcon
import io.github.triet4p.aegisf.ui.components.AegisSettingRow
import io.github.triet4p.aegisf.ui.text.LocalAegisStrings

/** The sheet the settings hub currently has open, if any. */
private enum class SettingsSheet { LANGUAGE, APPEARANCE }

/**
 * The settings hub: a short, labelled list of the configuration that already exists, each row
 * opening the screen that owns it.
 *
 * Nothing here is a new setting: the language and theme rows are this redesign's own presentation
 * choices, and the other two rows open the bank-notification and hosted-provider screens that
 * already work. Routine choices come first; advanced configuration is one tap deeper and never
 * dominates the everyday surface. Secrets, endpoints and allowlists stay on their own screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsHubScreen(
    language: AegisLanguage,
    onLanguageChange: (AegisLanguage) -> Unit,
    themeMode: AegisThemeMode,
    onThemeModeChange: (AegisThemeMode) -> Unit,
    onOpenBankNotifications: () -> Unit,
    onOpenHostedProvider: () -> Unit,
    onNavigateUp: () -> Unit,
) {
    val strings = LocalAegisStrings.current
    var openSheet by rememberSaveable { mutableStateOf<SettingsSheet?>(null) }

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
                        text = strings.settingsTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateUp,
                        modifier = Modifier.semantics {
                            contentDescription = strings.back
                        },
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
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = strings.settingsPreferences,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingsGroup {
                AegisSettingRow(
                    title = strings.settingsLanguage,
                    value = strings.languageName(language),
                    onClick = { openSheet = SettingsSheet.LANGUAGE },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AegisSettingRow(
                    title = strings.settingsAppearance,
                    value = appearanceName,
                    onClick = { openSheet = SettingsSheet.APPEARANCE },
                )
            }

            Text(
                text = strings.settingsConfiguration,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            SettingsGroup {
                AegisSettingRow(
                    title = strings.settingsBankNotifications,
                    supporting = strings.settingsBankDescription,
                    onClick = onOpenBankNotifications,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AegisSettingRow(
                    title = strings.settingsHostedProvider,
                    supporting = strings.settingsHostedDescription,
                    onClick = onOpenHostedProvider,
                )
            }
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
            title = strings.settingsAppearance,
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

/** One grouped block of setting rows on a soft card. */
@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column { content() }
    }
}
