package io.github.triet4p.aegisf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.ConfirmationStatus
import io.github.triet4p.aegisf.data.LedgerAggregate
import io.github.triet4p.aegisf.data.LedgerCategoryFilter
import io.github.triet4p.aegisf.data.LedgerDailySpending
import io.github.triet4p.aegisf.data.LedgerRecordQuery
import io.github.triet4p.aegisf.data.LedgerTotal
import io.github.triet4p.aegisf.data.LIST_PAGE_SIZE
import io.github.triet4p.aegisf.data.QueryPage
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.TransactionEntity
import io.github.triet4p.aegisf.data.TransactionType
import io.github.triet4p.aegisf.data.VndMoney
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.ui.components.AegisGlyph
import io.github.triet4p.aegisf.ui.components.AegisIcon
import io.github.triet4p.aegisf.ui.components.AegisMoneyText
import io.github.triet4p.aegisf.ui.text.AegisStrings
import io.github.triet4p.aegisf.ui.text.LocalAegisStrings
import io.github.triet4p.aegisf.ui.theme.LocalAegisSemanticColors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import java.util.Locale
import java.util.TimeZone

/**
 * Reports: the confirmed figures of one chosen period, and the way into the records behind
 * them.
 *
 * The screen owns one task — inspect the period — and it says four separate things, never
 * one blurred number: **spending** (confirmed expenses), **income** (confirmed money
 * arriving), **internal transfers** (money moving between the user's own accounts, neither
 * spending nor income) and **net cash flow** (income minus spending, which deliberately
 * does not count a transfer). A draft that has not been confirmed is in none of them.
 *
 * The period is a calendar month the chevrons walk, or an explicitly chosen inclusive
 * range. The category breakdown and the daily chart are the same aggregate's own buckets,
 * so a row and the records it opens over can never disagree. Every drills into a scoped
 * ledger with its own scope snapshot, so coming Back returns to the same period and the
 * same figures.
 *
 * A refused custom range reads no figure at all and says why, rather than quietly widening
 * to something the user did not choose.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(
    queryService: WorkflowQueryService,
    scope: DashboardScopeState,
    onScopeChange: (DashboardScopeState) -> Unit,
    onOpenRecords: (DashboardListScope, DashboardScopeState) -> Unit,
    onAddRecord: () -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAegisStrings.current
    val zone = remember { TimeZone.getDefault() }
    val resolved = remember(scope.fromText, scope.throughText) { resolveDashboardScope(scope) }
    var retryToken by remember { mutableIntStateOf(0) }
    var customRangeVisible by rememberSaveable { mutableStateOf(false) }

    val loadFlow: Flow<AnalyticsLoad> = remember(queryService, resolved.range, retryToken) {
        val range = resolved.range
        if (range == null) {
            flowOf(AnalyticsLoad.Error)
        } else {
            queryService.observeConfirmedLedgerAggregate(range)
                .map<LedgerAggregate, AnalyticsLoad> { AnalyticsLoad.Ready(it) }
                .onStart { emit(AnalyticsLoad.Loading) }
                .catch { emit(AnalyticsLoad.Error) }
        }
    }
    val load: AnalyticsLoad by loadFlow.collectAsState(initial = AnalyticsLoad.Loading)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(strings.analyticsTitle) },
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
                actions = {
                    TextButton(onClick = { customRangeVisible = true }) {
                        Text(strings.analyticsCustomRange)
                    }
                },
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PeriodCard(
                scope = scope,
                resolved = resolved,
                strings = strings,
                zone = zone,
                onScopeChange = onScopeChange,
            )

            when (val current = load) {
                AnalyticsLoad.Loading -> Text(
                    text = strings.loadingReading,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                AnalyticsLoad.Error -> AnalyticsErrorCard(
                    strings = strings,
                    onRetry = { retryToken += 1 },
                )

                is AnalyticsLoad.Ready -> {
                    val aggregate = current.aggregate
                    if (aggregate.isEmptyPeriod()) {
                        EmptyPeriodCard(
                            strings = strings,
                            onAddRecord = onAddRecord,
                        )
                    } else {
                        KpiSection(
                            aggregate = aggregate,
                            strings = strings,
                            onOpenRecords = { listScope ->
                                onOpenRecords(listScope, scope.copy(category = LedgerCategoryFilter.All))
                            },
                        )
                        CashflowCard(aggregate = aggregate, strings = strings)
                        CategorySection(
                            aggregate = aggregate,
                            strings = strings,
                            zone = zone,
                            onOpenCategory = { category ->
                                onOpenRecords(
                                    DashboardListScope.SPENDING,
                                    scope.copy(category = category),
                                )
                            },
                        )
                        DailySection(
                            aggregate = aggregate,
                            strings = strings,
                            scope = scope,
                            zone = zone,
                            onOpenDay = { day ->
                                onOpenRecords(
                                    DashboardListScope.SPENDING,
                                    scope.copy(
                                        fromText = day.dayText,
                                        throughText = day.dayText,
                                    ),
                                )
                            },
                        )
                    }
                }
            }

            Text(
                text = strings.storedOnDevice,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (customRangeVisible) {
        CustomRangeDialog(
            scope = scope,
            resolved = resolved,
            strings = strings,
            onScopeChange = onScopeChange,
            onDismiss = { customRangeVisible = false },
        )
    }
}

/** What the reports know about the chosen period: reading, the figures, or a failed read. */
private sealed interface AnalyticsLoad {
    data object Loading : AnalyticsLoad
    data object Error : AnalyticsLoad
    data class Ready(val aggregate: LedgerAggregate) : AnalyticsLoad
}

/** Whether the period holds no confirmed record of any kind. */
private fun LedgerAggregate.isEmptyPeriod(): Boolean =
    spending.recordCount == 0 && income.recordCount == 0 && transfers.recordCount == 0

/**
 * The period control: the month the figures cover, walked by two chevrons, with the exact
 * inclusive days and the number of days named below it.
 *
 * The chevrons move by whole calendar months of the scope's own From day; a custom range
 * stays custom until the user walks it or resets it. The period is the only scope of the
 * screen, so every figure under it describes the same days.
 */
@Composable
private fun PeriodCard(
    scope: DashboardScopeState,
    resolved: ResolvedDashboardScope,
    strings: AegisStrings,
    zone: TimeZone,
    onScopeChange: (DashboardScopeState) -> Unit,
) {
    val anchorEpochMillis = resolved.range?.fromEpochMillis ?: System.currentTimeMillis()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { onScopeChange(scope.adjacentMonth(-1, zone)) },
                modifier = Modifier.semantics {
                    contentDescription = strings.analyticsPreviousMonth
                },
            ) {
                AegisIcon(
                    glyph = AegisGlyph.CHEVRON_LEFT,
                    tint = MaterialTheme.colorScheme.primary,
                    size = 20.dp,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = monthYearText(anchorEpochMillis, strings.language, zone),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val rangeText = inclusiveRangeText(resolved, zone) ?: resolved.label
                val days = inclusiveDayCount(resolved, zone)
                Text(
                    text = if (days != null) {
                        "$rangeText · ${strings.dayCount(days)}"
                    } else {
                        rangeText
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = { onScopeChange(scope.adjacentMonth(1, zone)) },
                modifier = Modifier.semantics {
                    contentDescription = strings.analyticsNextMonth
                },
            ) {
                AegisIcon(
                    glyph = AegisGlyph.CHEVRON_RIGHT,
                    tint = MaterialTheme.colorScheme.primary,
                    size = 20.dp,
                )
            }
        }
    }
}

/**
 * The custom range: two inclusive device-local days, and nothing read until both are well
 * formed. A refused field names itself; a reversed pair is refused as a pair. "This month"
 * restores the device's current month without touching any stored record.
 */
@Composable
private fun CustomRangeDialog(
    scope: DashboardScopeState,
    resolved: ResolvedDashboardScope,
    strings: AegisStrings,
    onScopeChange: (DashboardScopeState) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.analyticsCustomRange) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = scope.fromText,
                    onValueChange = { onScopeChange(scope.copy(fromText = it)) },
                    label = { Text(strings.fromDate) },
                    placeholder = { Text("YYYY-MM-DD") },
                    singleLine = true,
                    isError = resolved.fromError != null,
                    supportingText = resolved.fromError?.let { message ->
                        { Text(message) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = scope.throughText,
                    onValueChange = { onScopeChange(scope.copy(throughText = it)) },
                    label = { Text(strings.throughDate) },
                    placeholder = { Text("YYYY-MM-DD") },
                    singleLine = true,
                    isError = resolved.throughError != null,
                    supportingText = resolved.throughError?.let { message ->
                        { Text(message) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (resolved.rangeError != null) {
                    Text(
                        text = resolved.rangeError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = strings.dateHelp,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(strings.close)
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onScopeChange(
                        DashboardScopeState.currentMonth(
                            System.currentTimeMillis(),
                            TimeZone.getDefault(),
                        ),
                    )
                    onDismiss()
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(strings.thisMonth)
            }
        },
    )
}

/**
 * Spending and income side by side, each the aggregate's own exact figure over the whole
 * period.
 *
 * The category breakdown is deliberately not part of these figures: the total spending of
 * the period is the sum of every category, and the rows below it drill into one category
 * at a time without changing the total.
 */
@Composable
private fun KpiSection(
    aggregate: LedgerAggregate,
    strings: AegisStrings,
    onOpenRecords: (DashboardListScope) -> Unit,
) {
    val largeText = LocalDensity.current.fontScale >= LARGE_TEXT_THRESHOLD
    val expenseCard: @Composable () -> Unit = {
        KpiCard(
            title = strings.kpiSpending,
            recordCount = aggregate.spending.recordCount,
            strings = strings,
            onClick = {
                onOpenRecords(DashboardListScope.SPENDING)
            },
        ) {
            AegisMoneyText(
                amountVnd = aggregate.spending.totalVnd,
                overflowed = aggregate.spending.overflowed,
                style = MaterialTheme.typography.titleSmall,
                color = LocalAegisSemanticColors.current.expense,
            )
        }
    }
    val incomeCard: @Composable () -> Unit = {
        KpiCard(
            title = strings.kpiIncome,
            recordCount = aggregate.income.recordCount,
            strings = strings,
            onClick = {
                onOpenRecords(DashboardListScope.INCOME)
            },
        ) {
            AegisMoneyText(
                amountVnd = aggregate.income.totalVnd,
                overflowed = aggregate.income.overflowed,
                prefix = if (aggregate.income.recordCount > 0) "+" else "",
                style = MaterialTheme.typography.titleSmall,
                color = LocalAegisSemanticColors.current.income,
            )
        }
    }
    if (largeText) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            expenseCard()
            incomeCard()
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.weight(1f)) { expenseCard() }
            Box(modifier = Modifier.weight(1f)) { incomeCard() }
        }
    }
}

@Composable
private fun KpiCard(
    title: String,
    recordCount: Int,
    strings: AegisStrings,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .clickable(role = Role.Button, onClickLabel = title, onClick = onClick)
                .defaultMinSize(minHeight = 96.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            content()
            Text(
                text = strings.recordCount(recordCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Net cash flow and internal transfers, separated on purpose.
 *
 * Net is income minus spending. An internal transfer moves money between the user's own
 * accounts and changes neither side, so it is never added to net and is shown on its own
 * line. If either figure overflowed, the difference would not be an exact number, and the
 * screen says so instead of printing something that looks exact.
 */
@Composable
private fun CashflowCard(
    aggregate: LedgerAggregate,
    strings: AegisStrings,
) {
    val net = netCashflowText(aggregate)
    val netColor = when {
        net == null -> MaterialTheme.colorScheme.onSurfaceVariant
        aggregate.income.totalVnd > aggregate.spending.totalVnd ->
            LocalAegisSemanticColors.current.income

        aggregate.income.totalVnd < aggregate.spending.totalVnd ->
            LocalAegisSemanticColors.current.expense

        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CashflowLine(
                label = strings.kpiNet,
                note = strings.netNote,
                value = net ?: strings.netUnavailable,
                valueColor = netColor,
                largeText = LocalDensity.current.fontScale >= LARGE_TEXT_THRESHOLD,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            CashflowLine(
                label = strings.kpiTransfers,
                note = strings.transfersNote,
                value = dashboardAmountText(
                    aggregate.transfers.totalVnd,
                    aggregate.transfers.overflowed,
                    strings.orMore,
                ),
                valueColor = LocalAegisSemanticColors.current.transfer,
                largeText = LocalDensity.current.fontScale >= LARGE_TEXT_THRESHOLD,
                valueStyle = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/**
 * One cashflow line: the label and its note beside the exact value at ordinary text sizes,
 * and above it when the text scale would squeeze the label into a word per line.
 */
@Composable
private fun CashflowLine(
    label: String,
    note: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color,
    largeText: Boolean,
    valueStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleMedium,
) {
    if (largeText) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = valueStyle,
                fontWeight = FontWeight.Bold,
                color = valueColor,
            )
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = value,
                style = valueStyle,
                fontWeight = FontWeight.Bold,
                color = valueColor,
                textAlign = TextAlign.End,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

/** Income minus spending, exact; null when either figure is only a proven lower bound. */
private fun netCashflowText(aggregate: LedgerAggregate): String? {
    if (aggregate.income.overflowed || aggregate.spending.overflowed) return null
    val difference = aggregate.income.totalVnd - aggregate.spending.totalVnd
    return when {
        difference > 0L -> "+" + VndMoney.formatAmount(difference, " ₫")
        difference < 0L -> "-" + VndMoney.formatAmount(-difference, " ₫")
        else -> VndMoney.formatAmount(0L, " ₫")
    }
}

/**
 * Spending by category: one proportional bar and one row per category that has spending.
 *
 * Every row is the aggregate's own bucket, so its amount, its share, and the records it
 * opens are all the same set. An archived category is named and marked; an uncategorised
 * bucket is its own row and is never merged into a named one.
 */
@Composable
private fun CategorySection(
    aggregate: LedgerAggregate,
    strings: AegisStrings,
    zone: TimeZone,
    onOpenCategory: (LedgerCategoryFilter) -> Unit,
) {
    val spending = aggregate.spending
    val buckets = spending.categoryTotals
    Text(
        text = strings.categorySection,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (buckets.isEmpty()) {
        Text(
            text = strings.noCategorySpending,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    if (!spending.overflowed && buckets.none { it.overflowed }) {
        CategoryDistributionBar(
            buckets = buckets,
            totalVnd = spending.totalVnd,
            orMore = strings.orMore,
        )
    } else {
        Text(
            text = strings.netUnavailable,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    buckets.forEach { bucket ->
        val displayName = when {
            bucket.categoryId == null -> strings.noCategory
            bucket.categoryName == null -> strings.unknownCategory
            else -> bucket.categoryName
        }
        val filter = bucket.categoryId
            ?.let { LedgerCategoryFilter.Specific(it) }
            ?: LedgerCategoryFilter.Uncategorized
        val share = if (
            spending.overflowed || bucket.overflowed || spending.totalVnd == 0L
        ) {
            "—"
        } else {
            percentageLabel(bucket.totalVnd, spending.totalVnd)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(
                    role = Role.Button,
                    onClickLabel = "${strings.openCategoryRecords}: $displayName",
                    onClick = { onOpenCategory(filter) },
                )
                .padding(vertical = 8.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(34.dp)
                    .background(
                        CategoryPalette.color(
                            categoryId = bucket.categoryId,
                            darkTheme = androidx.compose.foundation.isSystemInDarkTheme(),
                            archived = bucket.categoryArchived,
                        ),
                        RoundedCornerShape(2.dp),
                    ),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (bucket.categoryArchived) {
                        ArchivedCategoryBadge(
                            label = strings.archivedLabel.uppercase(Locale.ROOT),
                        )
                    }
                }
                Text(
                    text = "$share · ${strings.recordCount(bucket.recordCount)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = dashboardAmountText(bucket.totalVnd, bucket.overflowed, strings.orMore),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End,
                )
                AegisIcon(
                    glyph = AegisGlyph.CHEVRON_RIGHT,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 16.dp,
                )
            }
        }
    }
    Text(
        text = strings.drillHint,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CategoryDistributionBar(
    buckets: List<io.github.triet4p.aegisf.data.LedgerCategorySpending>,
    totalVnd: Long,
    orMore: String,
) {
    val spokenSummary = remember(buckets, totalVnd, orMore) {
        buckets.joinToString(separator = "; ") { bucket ->
            val name = bucket.categoryName ?: "—"
            "$name: ${dashboardAmountText(bucket.totalVnd, bucket.overflowed, orMore)}, " +
                percentageLabel(bucket.totalVnd, totalVnd)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics {
                contentDescription = "Proportional category distribution. $spokenSummary"
            },
    ) {
        buckets.forEach { bucket ->
            val fraction = (bucket.totalVnd.toDouble() / totalVnd.toDouble()).toFloat()
            Box(
                modifier = Modifier
                    .weight(fraction)
                    .fillMaxHeight()
                    .background(
                        CategoryPalette.color(
                            categoryId = bucket.categoryId,
                            darkTheme = androidx.compose.foundation.isSystemInDarkTheme(),
                            archived = bucket.categoryArchived,
                        ),
                    ),
            )
        }
    }
}

/**
 * Confirmed spending by calendar day, as a bar chart or as the same values in a list.
 *
 * Both forms are the same aggregate daily buckets and open the same one-day scope. The bar
 * widths grow with the text scale, so a large-font phone scrolls the chart rather than
 * clipping an amount, and the list form is always one tap away.
 */
@Composable
private fun DailySection(
    aggregate: LedgerAggregate,
    strings: AegisStrings,
    scope: DashboardScopeState,
    zone: TimeZone,
    onOpenDay: (LedgerDailySpending) -> Unit,
) {
    val days = aggregate.dailySpending
    var asList by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = strings.dailySection,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (days.isNotEmpty()) {
            TextButton(onClick = { asList = !asList }) {
                Text(if (asList) strings.showAsChart else strings.showAsList)
            }
        }
    }
    if (days.isEmpty()) {
        Text(
            text = strings.noDailySpending,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    if (asList) {
        days.forEach { day ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = "${strings.openCategoryRecords}: ${day.dayText}",
                        onClick = { onOpenDay(day) },
                    )
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = dayMonthYearText(day.dayStartEpochMillis, zone),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = strings.recordCount(day.recordCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = dashboardAmountText(day.totalVnd, day.overflowed, strings.orMore),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Text(
            text = strings.drillHint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val largestDay = days.maxOfOrNull { it.totalVnd } ?: 0L
    val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 1.6f)
    val columnWidth = 116.dp * fontScale
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        days.forEach { day ->
            val fraction = dashboardBarFraction(day.totalVnd, largestDay)
            Column(
                modifier = Modifier
                    .width(columnWidth)
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(
                        role = Role.Button,
                        onClickLabel = "${strings.openCategoryRecords}: ${day.dayText}",
                        onClick = { onOpenDay(day) },
                    )
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = dashboardAmountText(day.totalVnd, day.overflowed, strings.orMore),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    modifier = Modifier
                        .width(24.dp)
                        .height(64.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(4.dp),
                        ),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height((64.dp * fraction).coerceAtLeast(4.dp))
                            .background(
                                LocalAegisSemanticColors.current.expense,
                                RoundedCornerShape(4.dp),
                            ),
                    )
                }
                Text(
                    text = shortDayText(day.dayStartEpochMillis, zone),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
    Text(
        text = strings.drillHint,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun EmptyPeriodCard(
    strings: AegisStrings,
    onAddRecord: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = strings.emptyPeriodTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = strings.emptyPeriodBody,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onAddRecord,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(strings.addRecord)
            }
        }
    }
}

@Composable
private fun AnalyticsErrorCard(
    strings: AegisStrings,
    onRetry: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = strings.readErrorStored,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            OutlinedButton(
                onClick = onRetry,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(strings.retry)
            }
        }
    }
}

/** One point of the reports' proportional labels. */
private fun percentageLabel(part: Long, total: Long): String =
    String.format(Locale.ROOT, "%.1f%%", part.toDouble() * 100.0 / total.toDouble())

/** The text scale at which a two-column figure row would have to squeeze an amount. */
private const val LARGE_TEXT_THRESHOLD = 1.3f

// ---------------------------------------------------------------------------------------
// Scoped ledger: the records behind one reports figure.
// ---------------------------------------------------------------------------------------

/** What the scoped ledger knows: reading, the page, or a failed read. */
private sealed interface ScopedLoad {
    data object Loading : ScopedLoad
    data object Error : ScopedLoad
    data class Ready(
        val page: QueryPage<TransactionEntity>,
        val categories: List<CategoryEntity>,
    ) : ScopedLoad
}

/** The exact total of the scoped list's own scope. */
private sealed interface TotalLoad {
    data object Loading : TotalLoad
    data object Error : TotalLoad
    data class Ready(val total: LedgerTotal) : TotalLoad
}

/**
 * The confirmed records behind one reports figure, in that figure's own scope.
 *
 * The header states the type, the period, the category where one applies, and the exact
 * total the same aggregate computed — so the list and the row it was opened from cannot
 * disagree. The rows are the shared read's own bound page, grouped by device-local day;
 * paging walks the rest of the read without changing the filters, and a row opens the
 * receipt with edit and delete still available there.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)
@Composable
fun ScopedLedgerScreen(
    queryService: WorkflowQueryService,
    scope: DashboardScopeState,
    listScope: DashboardListScope,
    onSelectRecord: (Long) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAegisStrings.current
    val zone = remember { TimeZone.getDefault() }
    BackHandler(onBack = onNavigateUp)

    val resolved = remember(scope.fromText, scope.throughText) { resolveDashboardScope(scope) }
    val range = resolved.range
    if (range == null) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text(scopedTitle(listScope, strings)) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateUp) {
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
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = resolved.fromError ?: resolved.throughError
                        ?: resolved.rangeError ?: strings.readErrorStored,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = strings.emptyScopeBody,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    var pageIndex by rememberSaveable { mutableIntStateOf(0) }
    var retryToken by remember { mutableIntStateOf(0) }
    val recordQuery = remember(scope, range, listScope, pageIndex) {
        LedgerRecordQuery(
            type = when (listScope) {
                DashboardListScope.SPENDING -> TransactionType.EXPENSE
                DashboardListScope.INCOME -> TransactionType.INCOME
                DashboardListScope.TRANSFERS -> TransactionType.TRANSFER
            },
            // The category narrows spending only; income and transfers are listed for
            // every category of the range, exactly as their cards say.
            category = when (listScope) {
                DashboardListScope.SPENDING -> scope.category
                else -> LedgerCategoryFilter.All
            },
            confirmation = ConfirmationStatus.CONFIRMED,
            occurredIn = range,
            bounds = QueryPageRequest(LIST_PAGE_SIZE, offset = pageIndex * LIST_PAGE_SIZE),
        )
    }
    val loadFlow: Flow<ScopedLoad> = remember(queryService, recordQuery, retryToken) {
        queryService.observeLedgerRecords(recordQuery)
            .flatMapLatest { page ->
                flow<ScopedLoad> {
                    emit(
                        ScopedLoad.Ready(
                            page = page,
                            categories = queryService.categoriesByIds(
                                page.items.mapNotNull { it.categoryId }.distinct(),
                            ),
                        ),
                    )
                }
            }
            .onStart { emit(ScopedLoad.Loading) }
            .catch { emit(ScopedLoad.Error) }
    }
    val load: ScopedLoad by loadFlow.collectAsState(initial = ScopedLoad.Loading)

    val totalFlow: Flow<TotalLoad> = remember(queryService, range, listScope, scope.category, retryToken) {
        queryService.observeConfirmedLedgerAggregate(range)
            .map<LedgerAggregate, TotalLoad> { aggregate ->
                TotalLoad.Ready(scopedTotal(aggregate, listScope, scope.category))
            }
            .onStart { emit(TotalLoad.Loading) }
            .catch { emit(TotalLoad.Error) }
    }
    val total: TotalLoad by totalFlow.collectAsState(initial = TotalLoad.Loading)
    val selectedCategoryId = (scope.category as? LedgerCategoryFilter.Specific)?.categoryId
    val selectedCategory: CategoryEntity? by remember(queryService, selectedCategoryId) {
        if (selectedCategoryId == null) {
            flowOf<CategoryEntity?>(null)
        } else {
            queryService.observeCategory(selectedCategoryId)
        }
    }.collectAsState(initial = null)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(scopedTitle(listScope, strings)) },
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
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = scopeDescription(scope, resolved, strings, zone, selectedCategory),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (val currentTotal = total) {
                    TotalLoad.Loading -> Text(
                        text = strings.loadingReading,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    TotalLoad.Error -> Text(
                        text = strings.readErrorStored,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )

                    is TotalLoad.Ready -> Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AegisMoneyText(
                            amountVnd = currentTotal.total.totalVnd,
                            overflowed = currentTotal.total.overflowed,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = strings.recordCount(currentTotal.total.recordCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                }
                Text(
                    text = scopedNote(listScope, strings),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = strings.storedOnDevice,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            when (val current = load) {
                ScopedLoad.Loading -> Text(
                    text = strings.loadingReading,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )

                ScopedLoad.Error -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = strings.readErrorStored,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(
                        onClick = { retryToken += 1 },
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(strings.retry)
                    }
                }

                is ScopedLoad.Ready -> {
                    val records = current.page.items
                    val categoriesById = remember(current.categories) {
                        current.categories.associateBy { it.id }
                    }
                    if (records.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = strings.emptyScopeBody,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        PageControls(
                            pageIndex = pageIndex,
                            hasMore = current.page.hasMore,
                            onPrevious = { pageIndex -= 1 },
                            onNext = { pageIndex += 1 },
                            previousLabel = strings.pagePrevious,
                            nextLabel = strings.pageNext,
                            pageLabel = strings.pageNumber(pageIndex),
                        )
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        ) {
                            groupTransactionsByDay(records, zone).forEach { group ->
                                item(key = "day-${group.dayText}") {
                                    Text(
                                        text = dayHeadingText(group.dayText, strings, zone = zone),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(
                                            start = 16.dp,
                                            end = 16.dp,
                                            top = 12.dp,
                                            bottom = 4.dp,
                                        ),
                                    )
                                }
                                items(group.records, key = { it.id }) { record ->
                                    val category = record.categoryId?.let(categoriesById::get)
                                    TransactionRow(
                                        record = record,
                                        categoryName = localizedRowCategory(
                                            record.categoryId,
                                            category,
                                            strings,
                                        ),
                                        categoryId = record.categoryId,
                                        categoryArchived = category?.archived == true,
                                        onOpen = { onSelectRecord(record.id) },
                                        strings = strings,
                                    )
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    ClampPageToRows(
        pageIndex = pageIndex,
        hasRows = (load as? ScopedLoad.Ready)?.page?.items?.isNotEmpty(),
        onClamp = { pageIndex = it },
    )
}

/** The exact total of one scoped list, from the same aggregate the reports read. */
private fun scopedTotal(
    aggregate: LedgerAggregate,
    listScope: DashboardListScope,
    category: LedgerCategoryFilter,
): LedgerTotal = when (listScope) {
    DashboardListScope.SPENDING -> {
        val spending = scopedSpending(aggregate.spending, category)
        LedgerTotal(spending.totalVnd, spending.overflowed, spending.recordCount)
    }

    DashboardListScope.INCOME -> aggregate.income
    DashboardListScope.TRANSFERS -> aggregate.transfers
}

private fun scopedTitle(
    listScope: DashboardListScope,
    strings: AegisStrings,
): String = when (listScope) {
    DashboardListScope.SPENDING -> strings.scopedSpendingTitle
    DashboardListScope.INCOME -> strings.scopedIncomeTitle
    DashboardListScope.TRANSFERS -> strings.scopedTransfersTitle
}

private fun scopedNote(
    listScope: DashboardListScope,
    strings: AegisStrings,
): String = when (listScope) {
    DashboardListScope.SPENDING -> strings.scopedSpendingNote
    DashboardListScope.INCOME -> strings.scopedIncomeNote
    DashboardListScope.TRANSFERS -> strings.scopedTransfersNote
}

/** The period, the exact days, and the category where the list is narrowed to one. */
private fun scopeDescription(
    scope: DashboardScopeState,
    resolved: ResolvedDashboardScope,
    strings: AegisStrings,
    zone: TimeZone,
    selectedCategory: CategoryEntity?,
): String {
    val rangeText = inclusiveRangeText(resolved, zone) ?: resolved.label
    val categoryText = when (scope.category) {
        is LedgerCategoryFilter.All -> strings.allCategories
        is LedgerCategoryFilter.Uncategorized -> strings.noCategory
        is LedgerCategoryFilter.Specific -> if (selectedCategory == null) {
            strings.unknownCategory
        } else {
            localizedCategoryName(selectedCategory, strings)
        }
    }
    return "$rangeText · $categoryText"
}
