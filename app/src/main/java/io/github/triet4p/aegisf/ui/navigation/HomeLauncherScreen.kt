package io.github.triet4p.aegisf.ui.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.triet4p.aegisf.data.LedgerAggregate
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.ui.DashboardScopeState
import io.github.triet4p.aegisf.ui.components.AegisFeatureLauncherGrid
import io.github.triet4p.aegisf.ui.components.AegisGlyph
import io.github.triet4p.aegisf.ui.components.AegisIcon
import io.github.triet4p.aegisf.ui.components.AegisLauncherItem
import io.github.triet4p.aegisf.ui.components.AegisMoneyText
import io.github.triet4p.aegisf.ui.components.AegisStatus
import io.github.triet4p.aegisf.ui.components.AegisStatusChip
import io.github.triet4p.aegisf.ui.resolveDashboardScope
import io.github.triet4p.aegisf.ui.text.LocalAegisStrings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.TimeZone

/**
 * Home as the S10.2 shell delivers it: one task — choose where to go.
 *
 * Eight labelled launchers sit directly on the surface, each opening a real destination, plus a
 * single-line exact spending glance with a deliberate mask control. Nothing here duplicates a
 * feature screen: the figures are the same confirmed-ledger aggregate the reports read, and the
 * destination opened from a launcher owns its own work. The fuller Home redesign (period switcher,
 * richer glance, removing the old dashboard cards) belongs to S10.3.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeLauncherScreen(
    queryService: WorkflowQueryService,
    reviewCount: Int?,
    onOpenLedger: () -> Unit,
    onOpenAnalytics: () -> Unit,
    onOpenNotes: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenManualRecord: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val strings = LocalAegisStrings.current
    var amountsMasked by rememberSaveable { mutableStateOf(false) }

    // The glance is this device's current month, read through the same exact aggregate the
    // reports use. It is a reading, not a stored preference: it moves by itself when a record is
    // saved, corrected, or deleted anywhere in the app.
    val monthScope = remember {
        DashboardScopeState.currentMonth(System.currentTimeMillis(), TimeZone.getDefault())
    }
    val resolved = remember(monthScope.fromText, monthScope.throughText) {
        resolveDashboardScope(monthScope)
    }
    val aggregateFlow: Flow<LedgerAggregate?> = remember(queryService, resolved.range) {
        val range = resolved.range
        if (range == null) flowOf(null) else {
            queryService.observeConfirmedLedgerAggregate(range)
        }
    }
    val aggregate: LedgerAggregate? by aggregateFlow.collectAsState(initial = null)

    val launchers = listOf(
        AegisLauncherItem(
            glyph = AegisGlyph.LEDGER,
            label = strings.launcherLedger,
            description = strings.openLauncher(strings.launcherLedger),
            onClick = onOpenLedger,
        ),
        AegisLauncherItem(
            glyph = AegisGlyph.ANALYTICS,
            label = strings.launcherAnalytics,
            description = strings.openLauncher(strings.launcherAnalytics),
            onClick = onOpenAnalytics,
        ),
        AegisLauncherItem(
            glyph = AegisGlyph.NOTES,
            label = strings.launcherNotes,
            description = strings.openLauncher(strings.launcherNotes),
            onClick = onOpenNotes,
        ),
        AegisLauncherItem(
            glyph = AegisGlyph.CHAT,
            label = strings.launcherChat,
            description = strings.openLauncher(strings.launcherChat),
            featured = true,
            onClick = onOpenChat,
        ),
        AegisLauncherItem(
            glyph = AegisGlyph.REVIEW,
            label = strings.launcherReview,
            description = strings.openLauncher(strings.launcherReview),
            badgeCount = reviewCount,
            onClick = onOpenReview,
        ),
        AegisLauncherItem(
            glyph = AegisGlyph.MANUAL,
            label = strings.launcherManual,
            description = strings.openLauncher(strings.launcherManual),
            onClick = onOpenManualRecord,
        ),
        AegisLauncherItem(
            glyph = AegisGlyph.CATEGORIES,
            label = strings.launcherCategories,
            description = strings.openLauncher(strings.launcherCategories),
            onClick = onOpenCategories,
        ),
        AegisLauncherItem(
            glyph = AegisGlyph.SETTINGS,
            label = strings.launcherSettings,
            description = strings.openLauncher(strings.launcherSettings),
            onClick = onOpenSettings,
        ),
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = strings.homeTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = strings.thisMonthSpending,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val spending = aggregate?.spending
                    if (spending == null) {
                        Text(
                            text = "…",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        AegisMoneyText(
                            amountVnd = spending.totalVnd,
                            masked = amountsMasked,
                            overflowed = spending.overflowed,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (reviewCount != null && reviewCount > 0) {
                    AegisStatusChip(
                        status = AegisStatus.NEEDS_REVIEW,
                        label = strings.pendingCount(reviewCount),
                    )
                }
                // The mask control states its action, and the amount says it is hidden.
                Column(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button) { amountsMasked = !amountsMasked }
                        .semantics {
                            contentDescription = if (amountsMasked) {
                                strings.showAmounts
                            } else {
                                strings.hideAmounts
                            }
                        }
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    AegisIcon(
                        glyph = if (amountsMasked) AegisGlyph.EYE else AegisGlyph.EYE_OFF,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        size = 22.dp,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = strings.homePrompt,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AegisFeatureLauncherGrid(items = launchers)
            }
        }
    }
}
