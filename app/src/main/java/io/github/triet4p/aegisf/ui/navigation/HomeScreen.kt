package io.github.triet4p.aegisf.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.triet4p.aegisf.data.LedgerAggregate
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.ui.DashboardScopeState
import io.github.triet4p.aegisf.ui.components.AegisFeatureLauncherGrid
import io.github.triet4p.aegisf.ui.components.AegisGlyph
import io.github.triet4p.aegisf.ui.components.AegisIcon
import io.github.triet4p.aegisf.ui.components.AegisLauncherItem
import io.github.triet4p.aegisf.ui.components.AegisMoneyText
import io.github.triet4p.aegisf.ui.resolveDashboardScope
import io.github.triet4p.aegisf.ui.text.LocalAegisStrings
import io.github.triet4p.aegisf.ui.text.ShellTab
import io.github.triet4p.aegisf.ui.theme.LocalAegisSemanticColors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import java.util.Calendar
import java.util.TimeZone

/**
 * Home: one task — choose where to go.
 *
 * The screen is a launcher, not a dashboard. It carries one compact, exact spending glance
 * with a deliberate mask control and a direct route into the reports, and the eight labelled
 * feature launchers sit directly on the surface. There is no hero, no enclosing launcher
 * card, no recent-record feed, no separate warning card and no second shortcut block: the
 * Review waiting count is badged where Review is, and nowhere else competes for the thumb.
 *
 * The glance reads the same confirmed-ledger aggregate the reports read over the device's
 * current month, so it moves by itself when a record is saved, corrected or deleted, and an
 * unconfirmed draft is in no figure of it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
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

    val monthScope = remember {
        DashboardScopeState.currentMonth(System.currentTimeMillis(), TimeZone.getDefault())
    }
    val resolved = remember(monthScope.fromText, monthScope.throughText) {
        resolveDashboardScope(monthScope)
    }
    var retryToken by remember { mutableIntStateOf(0) }
    val glanceFlow: Flow<HomeGlance> = remember(queryService, resolved.range, retryToken) {
        // Creating the Room flow can itself fail (a closed or unreadable database), so it is
        // folded into the same read state instead of escaping composition.
        runCatching {
            val range = resolved.range
            if (range == null) {
                flowOf(HomeGlance.Error)
            } else {
                queryService.observeConfirmedLedgerAggregate(range)
                    .map<LedgerAggregate, HomeGlance> { HomeGlance.Ready(it) }
                    .onStart { emit(HomeGlance.Loading) }
                    .catch { emit(HomeGlance.Error) }
            }
        }.getOrElse { flowOf(HomeGlance.Error) }
    }
    val glance: HomeGlance by glanceFlow.collectAsState(initial = HomeGlance.Loading)

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

    val hourOfDay = remember {
        Calendar.getInstance(TimeZone.getDefault()).get(Calendar.HOUR_OF_DAY)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = strings.greeting(hourOfDay),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    ReviewBell(
                        count = reviewCount,
                        description = strings.tabDescription(ShellTab.REVIEW, reviewCount),
                        onClick = onOpenReview,
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
            SpendingGlance(
                glance = glance,
                masked = amountsMasked,
                onToggleMask = { amountsMasked = !amountsMasked },
                onOpenReports = onOpenAnalytics,
                onRetry = { retryToken += 1 },
            )

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

/** What the one-line glance knows: it is reading, the exact figure, or the read failed. */
private sealed interface HomeGlance {
    data object Loading : HomeGlance
    data object Error : HomeGlance
    data class Ready(val aggregate: LedgerAggregate) : HomeGlance
}

/**
 * The compact financial glance: this month's confirmed spending, one deliberate mask control,
 * and the direct route into the reports.
 *
 * The figure is exact whole dong and is the same aggregate the reports count. Masking hides
 * the digits while keeping the line and its place, and the control always says what the next
 * tap will do. The label says "Spending", not "balance": income, internal transfers and
 * unconfirmed drafts are not spending and are not added here; the reports own that breakdown.
 */
@Composable
private fun SpendingGlance(
    glance: HomeGlance,
    masked: Boolean,
    onToggleMask: () -> Unit,
    onOpenReports: () -> Unit,
    onRetry: () -> Unit,
) {
    val strings = LocalAegisStrings.current
    val semantic = LocalAegisSemanticColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    AegisIcon(
                        glyph = AegisGlyph.LEDGER,
                        tint = MaterialTheme.colorScheme.primary,
                        size = 22.dp,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = strings.thisMonthSpending,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    when (glance) {
                        HomeGlance.Loading -> Text(
                            text = strings.loadingReading,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        HomeGlance.Error -> Text(
                            text = strings.readErrorStored,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )

                        is HomeGlance.Ready -> AegisMoneyText(
                            amountVnd = glance.aggregate.spending.totalVnd,
                            masked = masked,
                            overflowed = glance.aggregate.spending.overflowed,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onToggleMask)
                        .semantics {
                            contentDescription = if (masked) {
                                strings.showAmounts
                            } else {
                                strings.hideAmounts
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    AegisIcon(
                        glyph = if (masked) AegisGlyph.EYE else AegisGlyph.EYE_OFF,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        size = 22.dp,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (glance is HomeGlance.Error) {
                    TextButton(onClick = onRetry) { Text(strings.retry) }
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(
                            role = Role.Button,
                            onClickLabel = strings.openLauncher(strings.launcherAnalytics),
                            onClick = onOpenReports,
                        )
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = strings.launcherAnalytics,
                        style = MaterialTheme.typography.labelLarge,
                        color = semantic.info,
                        maxLines = 1,
                    )
                    AegisIcon(
                        glyph = AegisGlyph.CHEVRON_RIGHT,
                        tint = semantic.info,
                        size = 16.dp,
                    )
                }
            }
        }
    }
}

/** The Review bell: one status affordance, opening the same inbox as the Review launcher. */
@Composable
private fun ReviewBell(
    count: Int?,
    description: String,
    onClick: () -> Unit,
) {
    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.semantics { contentDescription = description },
        ) {
            AegisIcon(
                glyph = AegisGlyph.REVIEW,
                tint = MaterialTheme.colorScheme.onSurface,
                size = 22.dp,
            )
        }
        if (count != null && count > 0) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 4.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.tertiary,
            ) {
                Text(
                    text = count.toString(),
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiary,
                )
            }
        }
    }
}
