package io.github.triet4p.aegisf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import io.github.triet4p.aegisf.data.AegisUiPreferencesStore
import io.github.triet4p.aegisf.data.NotificationAccessStore
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.notification.BankNotificationListener
import io.github.triet4p.aegisf.notification.isNotificationAccessGranted
import io.github.triet4p.aegisf.provider.HostedProviderSettingsStore
import io.github.triet4p.aegisf.ui.navigation.AegisAppShell
import io.github.triet4p.aegisf.ui.theme.AegisTheme
import io.github.triet4p.aegisf.ui.theme.resolveAegisDarkTheme

/**
 * The app's single activity: it owns the process-wide read/write services and hands the
 * five-destination shell one of each. Navigation, per-destination routes and the unsaved
 * state they carry live in [AegisAppShell], above the individual screens.
 *
 * The interface language and light/dark choice are the user's own presentation settings. They
 * are read once per activity creation from [AegisUiPreferencesStore] and written back the moment
 * they change, so a configuration change or a process restart comes back in the same language
 * and theme without touching the ledger.
 */
class MainActivity : ComponentActivity() {

    private val notificationAccessStore: NotificationAccessStore by lazy {
        (application as AegisApplication).container.notificationAccessStore
    }

    /** The app's one read surface: the typed, bounded queries the screens read through. */
    private val workflowQueryService: WorkflowQueryService by lazy {
        (application as AegisApplication).container.workflowQueryService
    }

    private val workflowCommandService: WorkflowCommandService by lazy {
        (application as AegisApplication).container.workflowCommandService
    }

    private val hostedProviderSettingsStore: HostedProviderSettingsStore by lazy {
        (application as AegisApplication).container.hostedProviderSettingsStore
    }

    private val uiPreferencesStore: AegisUiPreferencesStore by lazy {
        (application as AegisApplication).container.uiPreferencesStore
    }

    override fun onResume() {
        super.onResume()
        if (isNotificationAccessGranted(this)) {
            BankNotificationListener.requestActiveReconciliation(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var language by remember { mutableStateOf(uiPreferencesStore.loadLanguage()) }
            var themeMode by remember { mutableStateOf(uiPreferencesStore.loadThemeMode()) }
            val dark = resolveAegisDarkTheme(themeMode, isSystemInDarkTheme())
            // The system bars follow the app's own theme, not only the device's: an in-app dark
            // choice must not leave dark icons on a dark bar.
            LaunchedEffect(dark) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
            AegisTheme(themeMode = themeMode, language = language) {
                AegisAppShell(
                    queryService = workflowQueryService,
                    commandService = workflowCommandService,
                    notificationAccessStore = notificationAccessStore,
                    hostedProviderSettingsStore = hostedProviderSettingsStore,
                    createHostedWorkflowHost = (application as AegisApplication).container::createHostedWorkflowHost,
                    agentRunHistory = (application as AegisApplication).container.agentRunHistory,
                    language = language,
                    onLanguageChange = { selected ->
                        language = selected
                        uiPreferencesStore.saveLanguage(selected)
                    },
                    themeMode = themeMode,
                    onThemeModeChange = { selected ->
                        themeMode = selected
                        uiPreferencesStore.saveThemeMode(selected)
                    },
                )
            }
        }
    }
}
