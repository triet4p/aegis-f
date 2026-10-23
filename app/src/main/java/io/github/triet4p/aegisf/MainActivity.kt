package io.github.triet4p.aegisf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.triet4p.aegisf.data.NotificationAccessStore
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.notification.BankNotificationListener
import io.github.triet4p.aegisf.notification.isNotificationAccessGranted
import io.github.triet4p.aegisf.ui.AegisAppShell

/**
 * The app's single activity: it owns the process-wide read/write services and hands the
 * four-destination shell one of each. Navigation, per-destination routes and the unsaved
 * state they carry live in [AegisAppShell], above the individual screens.
 */
class MainActivity : ComponentActivity() {

    private val layaInferenceLifecycle by lazy {
        (application as AegisApplication).container.layaInferenceLifecycle
    }

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
            AegisTheme {
                AegisAppShell(
                    queryService = workflowQueryService,
                    commandService = workflowCommandService,
                    layaInferenceLifecycle = layaInferenceLifecycle,
                    notificationAccessStore = notificationAccessStore,
                )
            }
        }
    }
}

private val AegisShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(2.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(4.dp),
    extraLarge = RoundedCornerShape(4.dp),
)

@Composable
private fun AegisTheme(content: @Composable () -> Unit) {
    val colorScheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFF0EA5E9),
            onPrimary = Color(0xFF07121A),
            primaryContainer = Color(0xFF113042),
            onPrimaryContainer = Color(0xFFBAE6FD),
            secondary = Color(0xFF10B981),
            onSecondary = Color(0xFF052E23),
            tertiary = Color(0xFFF59E0B),
            onTertiary = Color(0xFF291900),
            error = Color(0xFFEF4444),
            background = Color(0xFF0C1017),
            onBackground = Color(0xFFF8FAFC),
            surface = Color(0xFF131923),
            onSurface = Color(0xFFF8FAFC),
            surfaceVariant = Color(0xFF0A0D13),
            onSurfaceVariant = Color(0xFF94A3B8),
            outline = Color(0xFF232D3F),
            outlineVariant = Color(0xFF1C2433),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF0369A1),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFDCECF5),
            onPrimaryContainer = Color(0xFF0F172A),
            secondary = Color(0xFF047857),
            onSecondary = Color.White,
            tertiary = Color(0xFF92400E),
            onTertiary = Color.White,
            error = Color(0xFFE11D48),
            background = Color(0xFFF4F6F8),
            onBackground = Color(0xFF0F172A),
            surface = Color.White,
            onSurface = Color(0xFF0F172A),
            surfaceVariant = Color(0xFFF0F3F6),
            onSurfaceVariant = Color(0xFF475569),
            outline = Color(0xFFD1D9E0),
            outlineVariant = Color(0xFFE2E8F0),
        )
    }

    MaterialTheme(colorScheme = colorScheme, shapes = AegisShapes, content = content)
}
