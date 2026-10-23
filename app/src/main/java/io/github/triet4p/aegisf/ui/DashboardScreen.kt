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
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.ConfirmationStatus
import io.github.triet4p.aegisf.data.IntakeReviewStatus
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
import io.github.triet4p.aegisf.data.WorkflowQueryService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import java.util.Locale
import java.util.TimeZone

/**
 * The Dashboard destination: the exact figures of one chosen scope, and the way into the
 * records behind them.
 *
 * Every figure on this screen is read through the shared exact query surface — the same
 * confirmed-ledger aggregate and the same bounded ledger read every other reader of the
 * ledger uses — so a figure and the list behind it can never disagree, and a record saved,
 * corrected, or deleted anywhere in the app moves the figure on its own.
 *
 * The screen keeps three things apart, because mixing them would make a wrong number look
 * right: **spending** (confirmed expenses), **income** (confirmed money arriving), and
 * **internal transfers** (confirmed money moving between the user's own accounts). Drafts
 * and unconfirmed records are in none of them and are counted on their own line. No card
 * ever shows a total that another card's records would also be part of.
 *
 * The scope is the chosen calendar days plus the chosen category. The category narrows
 * spending only — the aggregate has no breakdown of income or transfers by category, and a
 * second invented figure would be worse than an honest one — and the income and transfer
 * cards say so where they are shown.
 *
 * The scope is hoisted by the shell, so it survives switching destinations, opening a
 * record, and the process being restarted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    queryService: WorkflowQueryService,
    scope: DashboardScopeState,
    onScopeChange: (DashboardScopeState) -> Unit,
    onOpenList: (DashboardListScope) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenReview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Pure derivation: the two day texts become one closed range, and a refused scope
    // names no range at all, so no figure is read over a range that was never stated.
    val resolved = remember(scope.fromText, scope.throughText) { resolveDashboardScope(scope) }
    // The figures of the scope, from the shared aggregate. The stream is hoisted so that
    // rebuilding it on every recomposition would not blank the figures each time.
    val aggregateFlow: Flow<LedgerAggregate?> = remember(
        queryService,
        resolved.range,
        scope.category,
    ) {
        val range = resolved.range
        if (range == null) {
            flowOf(null)
        } else {
            queryService.observeConfirmedLedgerAggregate(
                range,
                dailySpendingCategory = scope.category,
            )
        }
    }
    // Null until the first emission: it tells "still reading the ledger" from "this scope
    // really has nothing in it".
    val aggregate: LedgerAggregate? by aggregateFlow.collectAsState(initial = null)
    // The pending bank notifications are counted on their own line: a draft that has not
    // been confirmed is in no figure of the scope.
    val pendingDrafts: Int? by remember(queryService) {
        queryService.observeDraftCandidateCount(IntakeReviewStatus.PENDING)
    }.collectAsState(initial = null)
    // The chosen category is named by its own row, so a renamed category keeps its name
    // and an archived one keeps its marker.
    val selectedCategoryId = (scope.category as? LedgerCategoryFilter.Specific)?.categoryId
    val selectedCategory by remember(queryService, selectedCategoryId) {
        if (selectedCategoryId == null) {
            flowOf(null)
        } else {
            queryService.observeCategory(selectedCategoryId)
        }
    }.collectAsState(initial = null)
    val categoryScopeLabel = when (val category = scope.category) {
        is LedgerCategoryFilter.All -> "All categories"
        is LedgerCategoryFilter.Uncategorized -> "No category"
        is LedgerCategoryFilter.Specific -> selectedCategory
            ?.let { categoryLabel(it) }
            ?: "Unknown category"
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "DASHBOARD",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp,
                    )
                },
                actions = {
                    TextButton(onClick = onOpenSettings) { Text("Settings") }
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DashboardScopeCard(
                scope = scope,
                resolved = resolved,
                categoryLabel = categoryScopeLabel,
                categoryId = selectedCategoryId,
                categoryArchived = selectedCategory?.archived == true,
                queryService = queryService,
                onScopeChange = onScopeChange,
            )

            if (!resolved.isUsable) {
                DashboardRefusalCard(resolved = resolved)
            } else {
                SpendingCard(
                    aggregate = aggregate,
                    scope = scope,
                    categoryLabel = categoryScopeLabel,
                    onOpenList = { onOpenList(DashboardListScope.SPENDING) },
                )
                DashboardSecondaryMetrics(
                    aggregate = aggregate,
                    pendingDraftCount = pendingDrafts,
                    categoryNarrowed = scope.category !is LedgerCategoryFilter.All,
                    onOpenIncome = { onOpenList(DashboardListScope.INCOME) },
                    onOpenTransfers = { onOpenList(DashboardListScope.TRANSFERS) },
                    onOpenReview = onOpenReview,
                )
                CategoryBreakdownCard(
                    aggregate = aggregate,
                    scope = scope,
                    onCategorySelected = { category ->
                        onScopeChange(scope.copy(category = category))
                        onOpenList(DashboardListScope.SPENDING)
                    },
                )
                SpendingOverTimeCard(
                    aggregate = aggregate,
                    categoryLabel = categoryScopeLabel,
                    rangeLabel = resolved.label,
                    onDaySelected = { day ->
                        onScopeChange(
                            scope.copy(
                                fromText = day.dayText,
                                throughText = day.dayText,
                            ),
                        )
                        onOpenList(DashboardListScope.SPENDING)
                    },
                )
            }

            CoverageCard()
        }
    }
}

/**
 * The scope card: the two calendar days, and the category the spending figure is narrowed
 * to.
 *
 * A refused day text changes nothing and says why; the figures below are not read at all
 * while the scope is refused, so no figure can ever describe a range the user did not
 * choose. "This month" puts the device's current month back.
 */
@Composable
private fun DashboardScopeCard(
    scope: DashboardScopeState,
    resolved: ResolvedDashboardScope,
    categoryLabel: String,
    categoryId: Long?,
    categoryArchived: Boolean,
    queryService: WorkflowQueryService,
    onScopeChange: (DashboardScopeState) -> Unit,
) {
    var categoryDialogVisible by rememberSaveable { mutableStateOf(false) }
    var dateDialogVisible by rememberSaveable { mutableStateOf(false) }
    val dateRangeControlLabel = remember(scope.fromText, scope.throughText, resolved.range) {
        dashboardDateRangeControlLabel(scope, resolved)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                shape = MaterialTheme.shapes.small,
                onClick = { dateDialogVisible = true },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        "DATE RANGE",
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 0.6.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        dateRangeControlLabel,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            OutlinedButton(
                shape = MaterialTheme.shapes.small,
                onClick = { categoryDialogVisible = true },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (scope.category !is LedgerCategoryFilter.All) {
                        CategorySwatch(
                            categoryId = categoryId,
                            archived = categoryArchived,
                            directory = true,
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            "CATEGORY",
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 0.6.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            categoryLabel.removeSuffix(" (archived)"),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (categoryArchived) ArchivedCategoryBadge()
                    }
                }
            }
        }
        Text(
            text = "Inclusive device-local days · ${resolved.label}.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Category narrows spending only; income and transfers stay unfiltered.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (dateDialogVisible) {
        AlertDialog(
            onDismissRequest = { dateDialogVisible = false },
            title = { Text("Date range") },
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
                        label = { Text("From") },
                        placeholder = { Text("2026-10-01") },
                        singleLine = true,
                        isError = resolved.fromError != null,
                        supportingText = resolved.fromError?.let { message -> { Text(message) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = scope.throughText,
                        onValueChange = { onScopeChange(scope.copy(throughText = it)) },
                        label = { Text("Through") },
                        placeholder = { Text("2026-10-31") },
                        singleLine = true,
                        isError = resolved.throughError != null,
                        supportingText = resolved.throughError?.let { message -> { Text(message) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    resolved.rangeError?.let { message ->
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        "Both ends are inclusive calendar days in your device's time zone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { dateDialogVisible = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Done")
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
                        dateDialogVisible = false
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("This month")
                }
            },
        )
    }

    if (categoryDialogVisible) {
        CategoryFilterDialog(
            queryService = queryService,
            selected = scope.category,
            onSelect = {
                onScopeChange(scope.copy(category = it))
                categoryDialogVisible = false
            },
            onDismiss = { categoryDialogVisible = false },
        )
    }
}

/** The refusal of an unfinished scope, shown instead of any figure. */
@Composable
private fun DashboardRefusalCard(resolved: ResolvedDashboardScope) {
    DashboardCard(title = "NO FIGURES FOR THIS SCOPE") {
        Text(
            text = resolved.fromError ?: resolved.throughError ?: resolved.rangeError
                ?: "The chosen range is not usable.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = "Nothing is counted until both days are given and well formed. No " +
                "figure is read over a range that was not chosen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The spending card: the confirmed expenses of the scope, in exact whole dong.
 *
 * The figure is the aggregate's own spending, narrowed to the chosen category, so the list
 * behind the button holds exactly the records this figure is made of. Income, internal
 * transfers, drafts, and unconfirmed records are in no figure of this card, and the card
 * says so.
 */
@Composable
private fun SpendingCard(
    aggregate: LedgerAggregate?,
    scope: DashboardScopeState,
    categoryLabel: String,
    onOpenList: () -> Unit,
) {
    val spending = aggregate?.let { scopedSpending(it.spending, scope.category) }
    DashboardCard(
        title = "CONFIRMED SPENDING",
        emphasised = true,
        accent = MaterialTheme.colorScheme.error,
    ) {
        val amountStyle = MaterialTheme.typography.headlineSmall.copy(
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        )
        Text(
            text = when {
                aggregate == null -> "Reading the stored records…"
                spending == null || spending.recordCount == 0 -> "0 VND"
                else -> dashboardAmountText(spending.totalVnd, spending.overflowed)
            },
            style = amountStyle,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = when {
                aggregate == null -> ""
                spending == null || spending.recordCount == 0 ->
                    "No confirmed expenses in this scope."
                else -> dashboardRecordCountLabel(
                    spending.recordCount,
                    "confirmed expense",
                    "confirmed expenses",
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (spending?.overflowed == true) {
            Text(
                text = "The total passed the largest whole-dong value. The amount above is " +
                    "a guaranteed lower bound, not the exact total.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = "Scope: $categoryLabel · confirmed expenses only. Income, internal " +
                "transfers, drafts and unconfirmed records are excluded.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(shape = MaterialTheme.shapes.small, onClick = onOpenList,
        enabled = aggregate != null,
        modifier = Modifier.fillMaxWidth(),) { Text("See ${spending?.recordCount ?: "…"} spending records") }
    }
}

@Composable
private fun DashboardSecondaryMetrics(
    aggregate: LedgerAggregate?,
    pendingDraftCount: Int?,
    categoryNarrowed: Boolean,
    onOpenIncome: () -> Unit,
    onOpenTransfers: () -> Unit,
    onOpenReview: () -> Unit,
) {
    val reviewDescription = remember(pendingDraftCount, aggregate?.excludedDraftCount) {
        when {
            pendingDraftCount != null && aggregate != null ->
                "Open Review with ${dashboardRecordCountLabel(
                    pendingDraftCount,
                    "bank notification",
                    "bank notifications",
                )} waiting; ${
                    dashboardRecordCountLabel(
                        aggregate.excludedDraftCount,
                        "unconfirmed record",
                        "unconfirmed records",
                    )
                } in range."
            pendingDraftCount != null ->
                "Open Review with ${dashboardRecordCountLabel(
                    pendingDraftCount,
                    "bank notification",
                    "bank notifications",
                )} waiting."
            else -> "Open bank notification review."
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row {
            SecondaryMetric(
                title = "INCOME",
                amount = aggregate?.let {
                    dashboardAmountText(it.income.totalVnd, it.income.overflowed)
                } ?: "Reading…",
                detail = aggregate?.let {
                    dashboardRecordCountLabel(
                        it.income.recordCount,
                        "record",
                        "records",
                    ) + " · not spending" +
                        if (categoryNarrowed) " · all categories" else ""
                } ?: "Confirmed income",
                color = MaterialTheme.colorScheme.secondary,
                description = "Open confirmed income records; money arriving, not spending",
                onClick = onOpenIncome,
                modifier = Modifier.weight(1f),
            )
            MetricDivider()
            SecondaryMetric(
                title = "TRANSFERS",
                amount = aggregate?.let {
                    dashboardAmountText(it.transfers.totalVnd, it.transfers.overflowed)
                } ?: "Reading…",
                detail = aggregate?.let {
                    dashboardRecordCountLabel(
                        it.transfers.recordCount,
                        "movement",
                        "movements",
                    ) + " · neither spend nor income" +
                        if (categoryNarrowed) " · all categories" else ""
                } ?: "Confirmed transfers",
                color = MaterialTheme.colorScheme.primary,
                description = "Open confirmed internal transfers; neither spending nor income",
                onClick = onOpenTransfers,
                modifier = Modifier.weight(1f),
            )
            MetricDivider()
            SecondaryMetric(
                title = "REVIEW",
                amount = pendingDraftCount?.let { "$it waiting" } ?: "Reading…",
                detail = aggregate?.let {
                    "${it.excludedDraftCount} unconfirmed · in range"
                } ?: "Unconfirmed records in range",
                color = MaterialTheme.colorScheme.tertiary,
                description = reviewDescription,
                onClick = onOpenReview,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SecondaryMetric(
    title: String,
    amount: String,
    detail: String,
    color: Color,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .defaultMinSize(minHeight = 64.dp)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Text(
            text = amount,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            ),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MetricDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(64.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/**
 * Category amounts stay in the existing aggregate and every row opens that bucket's exact
 * category drill-down; no "Other" bucket changes the scope.
 */
@Composable
private fun CategoryBreakdownCard(
    aggregate: LedgerAggregate?,
    scope: DashboardScopeState,
    onCategorySelected: (LedgerCategoryFilter) -> Unit,
) {
    val spending = aggregate?.let { scopedSpending(it.spending, scope.category) }
    val buckets = spending?.categoryTotals.orEmpty()
    var showChart by rememberSaveable { mutableStateOf(true) }
    DashboardCard(title = "SPENDING BY CATEGORY") {
        when {
            aggregate == null -> Text(
                text = "Reading the stored records…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            buckets.isEmpty() -> Text(
                text = "No category has spending in this scope.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                val currentSpending = checkNotNull(spending)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { showChart = !showChart }) {
                        Text(if (showChart) "Hide chart" else "Show chart")
                    }
                }
                if (showChart && !currentSpending.overflowed &&
                    buckets.none { it.overflowed }
                ) {
                    CategoryDistributionBar(buckets, currentSpending.totalVnd)
                } else if (showChart && currentSpending.overflowed) {
                    Text(
                        text = "Proportions are unavailable because this total exceeds the " +
                            "supported exact range. The figures below are lower bounds.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (showChart && buckets.any { it.overflowed }) {
                    Text(
                        text = "Proportions are unavailable because a category total exceeds " +
                            "the supported exact range.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                buckets.forEachIndexed { index, bucket ->
                    val label = spendingBucketLabel(bucket)
                    val displayName = when {
                        bucket.categoryId == null -> "No category"
                        bucket.categoryName == null -> "Unknown category"
                        else -> bucket.categoryName
                    }
                    val category = bucket.categoryId?.let { LedgerCategoryFilter.Specific(it) }
                        ?: LedgerCategoryFilter.Uncategorized
                    val count = dashboardRecordCountLabel(
                        bucket.recordCount,
                        "confirmed expense",
                        "confirmed expenses",
                    )
                    val share = if (
                        currentSpending.overflowed || bucket.overflowed ||
                        currentSpending.totalVnd == 0L
                    ) {
                        "Share unavailable"
                    } else {
                        percentageLabel(bucket.totalVnd, currentSpending.totalVnd)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 52.dp)
                            .clickable(
                                role = Role.Button,
                                onClickLabel = "Open $label spending records",
                                onClick = { onCategorySelected(category) },
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CategorySwatch(
                            categoryId = bucket.categoryId,
                            archived = bucket.categoryArchived,
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp, end = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (bucket.categoryArchived) {
                                ArchivedCategoryBadge()
                            }
                            Text(
                                text = count,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = dashboardAmountText(bucket.totalVnd, bucket.overflowed),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                ),
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = share,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (index < buckets.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                Text(
                    text = "Select a category to open only its confirmed spending records.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CategoryDistributionBar(
    buckets: List<io.github.triet4p.aegisf.data.LedgerCategorySpending>,
    totalVnd: Long,
) {
    val spokenSummary = remember(buckets, totalVnd) {
        buckets.joinToString(separator = "; ") { bucket ->
            val count = dashboardRecordCountLabel(
                bucket.recordCount,
                "confirmed expense",
                "confirmed expenses",
            )
            "${spendingBucketLabel(bucket)}: " +
                "${dashboardAmountText(bucket.totalVnd, bucket.overflowed)}, $count, " +
                percentageLabel(bucket.totalVnd, totalVnd)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(2.dp))
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

private fun percentageLabel(part: Long, total: Long): String =
    String.format(Locale.ROOT, "%.1f%%", part.toDouble() * 100.0 / total.toDouble())

/**
 * Confirmed expenses by calendar day in the selected date/category scope. Both the compact
 * bar chart and its text alternative show the same exact daily totals and list scope.
 */
@Composable
private fun SpendingOverTimeCard(
    aggregate: LedgerAggregate?,
    categoryLabel: String,
    rangeLabel: String,
    onDaySelected: (LedgerDailySpending) -> Unit,
) {
    val days = aggregate?.dailySpending.orEmpty()
    val largestDay = days.maxOfOrNull { it.totalVnd } ?: 0L
    val chartIsExact = days.none { it.overflowed }
    var showList by rememberSaveable { mutableStateOf(false) }
    DashboardCard(title = "DAILY SPENDING TREND") {
        Text(
            text = "Confirmed expenses · $categoryLabel · $rangeLabel",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            aggregate == null -> Text(
                text = "Reading the stored records…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            days.isEmpty() -> Text(
                text = "No confirmed expenses in this scope.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                if (chartIsExact) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { showList = !showList }) {
                            Text(if (showList) "Show chart" else "Show as list")
                        }
                    }
                }
                if (showList || !chartIsExact) {
                    if (!chartIsExact) {
                        Text(
                            "Daily proportions are unavailable because at least one total " +
                                "exceeds the supported exact range. Amounts are lower bounds.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    days.forEach { day ->
                        val count = dashboardRecordCountLabel(
                            day.recordCount,
                            "confirmed expense",
                            "confirmed expenses",
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .clickable(
                                    role = Role.Button,
                                    onClickLabel = "Open spending for ${day.dayText}",
                                    onClick = { onDaySelected(day) },
                                )
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(day.dayText, style = MaterialTheme.typography.bodyMedium)
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    dashboardAmountText(day.totalVnd, day.overflowed),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    ),
                                )
                                Text(
                                    count,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                } else {
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
                            val count = dashboardRecordCountLabel(
                                day.recordCount,
                                "confirmed expense",
                                "confirmed expenses",
                            )
                            Column(
                                modifier = Modifier
                                    .width(116.dp)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .clickable(
                                        role = Role.Button,
                                        onClickLabel = "Open spending for ${day.dayText}",
                                        onClick = { onDaySelected(day) },
                                    )
                                    .padding(horizontal = 4.dp, vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    dashboardAmountText(day.totalVnd, day.overflowed),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    ),
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Box(
                                    modifier = Modifier
                                        .width(24.dp)
                                        .height(64.dp)
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.BottomCenter,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height((64.dp * fraction).coerceAtLeast(4.dp))
                                            .background(MaterialTheme.colorScheme.error),
                                    )
                                }
                                Text(
                                    day.dayText,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    count,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Text(
                    text = "Days without confirmed spending are not shown. Select a day to " +
                        "open only its confirmed spending records.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The coverage card: what the figures above do and do not cover.
 *
 * The figures are exact for the records this app holds, and they are not a bank history:
 * the app is not connected to the user's accounts, so spending that never reached this
 * app is not in these numbers. Saying that here is what keeps an exact figure from being
 * read as a complete one.
 */
@Composable
private fun CoverageCard() {
    DashboardCard(title = "WHAT THESE FIGURES COVER") {
        Text(
            text = "These figures cover the records stored in this app only. Aegis is not " +
                "connected to your bank accounts, so this is not your complete bank " +
                "history: money you spent without recording it here is not in these " +
                "numbers.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/** Flat panel with a clear editorial heading and a hairline border. */
@Composable
private fun DashboardCard(
    title: String,
    modifier: Modifier = Modifier,
    emphasised: Boolean = false,
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (accent != null) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(18.dp)
                            .background(accent),
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.0.sp,
                    color = accent ?: if (emphasised) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            content()
        }
    }
}

/**
 * The records behind one Dashboard figure: the confirmed records of the scope, in the
 * shared read's own order, with the exact total of the scope above them.
 *
 * The list is the card's own scope and no other — the same days, the same confirmation
 * state, the same type, and the same category the card shows — so the total above the
 * rows is the sum of exactly these records and can never drift from them.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DashboardScopedListScreen(
    queryService: WorkflowQueryService,
    scope: DashboardScopeState,
    listScope: DashboardListScope,
    onSelectRecord: (Long) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onNavigateUp)

    val resolved = remember(scope.fromText, scope.throughText) { resolveDashboardScope(scope) }
    val range = resolved.range
    val selectedCategoryId = (scope.category as? LedgerCategoryFilter.Specific)?.categoryId
    val selectedCategory by remember(queryService, selectedCategoryId) {
        if (selectedCategoryId == null) {
            flowOf(null)
        } else {
            queryService.observeCategory(selectedCategoryId)
        }
    }.collectAsState(initial = null)
    val categoryScopeLabel = when (val category = scope.category) {
        is LedgerCategoryFilter.All -> "all categories"
        is LedgerCategoryFilter.Uncategorized -> "no category"
        is LedgerCategoryFilter.Specific -> selectedCategory
            ?.let { categoryLabel(it) }
            ?: "unknown category"
    }

    if (range == null) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text("Records in this scope") },
                    navigationIcon = { TextButton(onClick = onNavigateUp) { Text("Back") } },
                )
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
                        ?: resolved.rangeError ?: "The chosen range is not usable.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = "No records are listed for a range that was not chosen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    // The page the list is showing. A page is an offset into the same bounded read.
    var pageIndex by rememberSaveable { mutableIntStateOf(0) }
    val recordQuery = remember(scope, range, listScope, pageIndex) {
        LedgerRecordQuery(
            type = when (listScope) {
                DashboardListScope.SPENDING -> TransactionType.EXPENSE
                DashboardListScope.INCOME -> TransactionType.INCOME
                DashboardListScope.TRANSFERS -> TransactionType.TRANSFER
            },
            // The category narrows spending only; income and transfers are listed for
            // every category of the range, exactly as their card says.
            category = when (listScope) {
                DashboardListScope.SPENDING -> scope.category
                else -> LedgerCategoryFilter.All
            },
            confirmation = ConfirmationStatus.CONFIRMED,
            occurredIn = range,
            bounds = QueryPageRequest(LIST_PAGE_SIZE, offset = pageIndex * LIST_PAGE_SIZE),
        )
    }
    val transactionFlow = remember(queryService, recordQuery) {
        queryService.observeLedgerRecords(recordQuery)
    }
    // The rows of the page and the categories they are filed under arrive together, so a
    // row is never named by a category that has since been renamed or archived.
    val recordPage: Pair<QueryPage<TransactionEntity>, List<CategoryEntity>>? by
        remember(queryService, recordQuery) {
            transactionFlow.flatMapLatest { page ->
                flow {
                    emit(
                        page to queryService.categoriesByIds(
                            page.items.mapNotNull { it.categoryId }.distinct(),
                        ),
                    )
                }
            }
        }.collectAsState(initial = null)
    val records = recordPage?.first?.items
    val categoriesById = remember(recordPage?.second) {
        recordPage?.second.orEmpty().associateBy { it.id }
    }

    // The exact total of the scope, from the same aggregate the card read: the same
    // figure, so the header and the card can never disagree.
    val aggregateFlow: Flow<LedgerAggregate?> = remember(queryService, range) {
        queryService.observeConfirmedLedgerAggregate(range)
    }
    val aggregate: LedgerAggregate? by aggregateFlow.collectAsState(initial = null)
    val scopeTotal: LedgerTotal? = when (listScope) {
        DashboardListScope.SPENDING -> aggregate?.let {
            val spending = scopedSpending(it.spending, scope.category)
            LedgerTotal(spending.totalVnd, spending.overflowed, spending.recordCount)
        }

        DashboardListScope.INCOME -> aggregate?.income
        DashboardListScope.TRANSFERS -> aggregate?.transfers
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(listScopeTitle(listScope)) },
                navigationIcon = { TextButton(onClick = onNavigateUp) { Text("Back") } },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = listScopeDescription(listScope, resolved.label, categoryScopeLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = when {
                        aggregate == null -> "Reading the stored records…"
                        scopeTotal == null || scopeTotal.recordCount == 0 ->
                            "0 VND · no records in this scope"

                        else -> dashboardAmountText(scopeTotal.totalVnd, scopeTotal.overflowed) +
                            " · " + dashboardRecordCountLabel(
                            scopeTotal.recordCount,
                            "record",
                            "records",
                        )
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = listScopeNote(listScope),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            when {
                records == null -> Text(
                    text = "Reading the stored records…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )

                records.isEmpty() -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "No records in this scope",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = listScopeEmptyText(listScope),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    PageControls(
                        pageIndex = pageIndex,
                        hasMore = recordPage?.first?.hasMore == true,
                        onPrevious = { pageIndex -= 1 },
                        onNext = { pageIndex += 1 },
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        items(records, key = { it.id }) { record ->
                            val category = record.categoryId?.let(categoriesById::get)
                            TransactionRow(
                                record = record,
                                categoryName = categoryDisplayName(record.categoryId, category),
                                categoryId = record.categoryId,
                                categoryArchived = category?.archived == true,
                                onOpen = { onSelectRecord(record.id) },
                                statusLabel = statusLabel(ConfirmationStatus.CONFIRMED),
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
    // A page that lost its rows steps back to the nearest page that still holds rows.
    ClampPageToRows(
        pageIndex = pageIndex,
        hasRows = recordPage?.first?.items?.isNotEmpty(),
        onClamp = { pageIndex = it },
    )
}

/** The title of a scoped list. */
private fun listScopeTitle(listScope: DashboardListScope): String = when (listScope) {
    DashboardListScope.SPENDING -> "Spending in this scope"
    DashboardListScope.INCOME -> "Income in this scope"
    DashboardListScope.TRANSFERS -> "Transfers in this scope"
}

/** The scope a list states above its rows. */
private fun listScopeDescription(
    listScope: DashboardListScope,
    rangeLabel: String,
    categoryScopeLabel: String,
): String = when (listScope) {
    DashboardListScope.SPENDING ->
        "Confirmed expenses · $rangeLabel · $categoryScopeLabel"

    DashboardListScope.INCOME ->
        "Confirmed income · $rangeLabel · all categories"

    DashboardListScope.TRANSFERS ->
        "Confirmed internal transfers · $rangeLabel · all categories"
}

/** What a scoped list holds, and what it does not. */
private fun listScopeNote(listScope: DashboardListScope): String = when (listScope) {
    DashboardListScope.SPENDING ->
        "These are exactly the records behind the spending figure. Income, internal " +
            "transfers and unconfirmed records are in no figure and in no row here."

    DashboardListScope.INCOME ->
        "Income is money arriving, not spending. This list is not narrowed by the " +
            "category filter."

    DashboardListScope.TRANSFERS ->
        "Internal transfers move money between your own accounts, so they are neither " +
            "spending nor income. This list is not narrowed by the category filter."
}

/** What an empty scoped list says. */
private fun listScopeEmptyText(listScope: DashboardListScope): String = when (listScope) {
    DashboardListScope.SPENDING ->
        "No confirmed expenses fall in these days for this category."

    DashboardListScope.INCOME ->
        "No confirmed income falls in these days."

    DashboardListScope.TRANSFERS ->
        "No confirmed internal transfers fall in these days."
}
