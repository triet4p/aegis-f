package io.github.triet4p.aegisf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.LedgerRepository
import io.github.triet4p.aegisf.data.TransactionEntity
import io.github.triet4p.aegisf.data.TransactionType
import io.github.triet4p.aegisf.data.VndMoney

/**
 * The stored records, newest first, narrowed by the browsing filters — and the way into one
 * of them.
 *
 * This is the single browsing surface: it reads the live transaction and category streams
 * from the same database file every launch opens, so records saved in any earlier session,
 * corrections, deletions, renames, and archives appear on their own, and a tap still opens
 * that record's detail view, where it can be corrected or deleted. The list order is the
 * repository's newest-occurred-at-first order with the stored id as the tie-breaker; the
 * filters narrow what is shown but never re-sort, cap, or fabricate rows.
 *
 * The [filters] state is hoisted by the caller (the launcher holds it), so opening a
 * record's detail view or its editor and coming back keeps the filters as they were; they
 * are a browsing preference held in saveable primitives, not ledger data, and they never
 * write. Type, category, and date dimensions combine as AND; the default state returns the
 * full list.
 *
 * The controls themselves start collapsed behind a one-line "Show filters" / "Hide filters"
 * toggle that also reports how many dimensions are set, so the results own nearly the whole
 * screen until the controls are asked for. [filtersExpanded] is hoisted beside [filters] for
 * the same reason: opening a record and coming back, or a configuration change, must not move
 * the panel. Collapsing writes nothing and drops nothing — the selections and the typed date
 * texts (including a refused one and its message) live in [filters], so reopening the panel
 * shows exactly what was left, over the same result rows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionListScreen(
    repository: LedgerRepository,
    filters: TransactionFilterState,
    onFiltersChange: (TransactionFilterState) -> Unit,
    filtersExpanded: Boolean,
    onFiltersExpandedChange: (Boolean) -> Unit,
    onSelect: (Long) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Hoisted so each collected flow has a stable identity: building one on every
    // recomposition would restart the collection (and blank the list) each time.
    val transactionFlow = remember(repository) { repository.observeTransactions() }
    val categoryFlow = remember(repository) { repository.observeCategories() }
    // Null until the first emission: it distinguishes "loading" from "nothing stored".
    val records: List<TransactionEntity>? by transactionFlow.collectAsState(initial = null)
    val categories: List<CategoryEntity>? by categoryFlow.collectAsState(initial = null)

    // Pure derivation, never a composition write: a refused date dimension reports its
    // refusal and is treated as open, so the other dimensions still narrow honestly.
    val resolvedDates = remember(filters.fromText, filters.throughText) {
        resolveDateFilter(filters.fromText, filters.throughText)
    }
    val visible = remember(records, filters, resolvedDates.range) {
        val stored = records ?: emptyList()
        applyTransactionFilters(
            records = stored,
            type = filters.type,
            category = filters.category,
            range = resolvedDates.range ?: EpochRange(null, null),
        )
    }

    BackHandler(onBack = onNavigateUp)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Saved transactions") },
                navigationIcon = {
                    TextButton(onClick = onNavigateUp) { Text("Back") }
                },
            )
        },
    ) { innerPadding ->
        val loaded = records
        val listed = categories
        if (loaded == null || listed == null) {
            LoadingLedger(Modifier.padding(innerPadding))
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                FilterToggleRow(
                    expanded = filtersExpanded,
                    activeDimensionCount = filters.activeDimensionCount,
                    onExpandedChange = onFiltersExpandedChange,
                )
                if (filtersExpanded) {
                    FilterPanel(
                        filters = filters,
                        onFiltersChange = onFiltersChange,
                        categories = listed,
                        resolvedDates = resolvedDates,
                        // The open panel takes only the height it needs, and at most half
                        // of what is left, so the results stay on screen and both halves
                        // keep scrolling instead of one squeezing the other to nothing.
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                when {
                    loaded.isEmpty() -> EmptyLedger(Modifier.weight(1f))

                    visible.isEmpty() -> NoMatchingTransactions(
                        onClearFilters = { onFiltersChange(TransactionFilterState()) },
                        modifier = Modifier.weight(1f),
                    )

                    else -> TransactionList(
                        records = visible,
                        categoriesById = listed.associateBy { it.id },
                        onSelect = onSelect,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * The one-line control that owns the filter panel: it says only what the tap will do, and
 * reports how many dimensions are set so the filters stay legible while the panel is shut.
 * Nothing is shown for the default state — no dimensions set means nothing to report.
 */
@Composable
private fun FilterToggleRow(
    expanded: Boolean,
    activeDimensionCount: Int,
    onExpandedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onExpandedChange(!expanded) }) {
            Text(if (expanded) "Hide filters" else "Show filters")
        }
        if (activeDimensionCount > 0) {
            Text(
                text = activeFilterCountLabel(activeDimensionCount),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
    }
}

/** "1 active filter" / "2 active filters"; the caller shows nothing below one. */
private fun activeFilterCountLabel(count: Int): String =
    if (count == 1) "1 active filter" else "$count active filters"

/**
 * The browsing filters: type chips, the category selector, and the From/Through calendar
 * days.
 *
 * Every dimension offers its unconstrained choice first — "All types", "All categories" —
 * and "Clear all" returns the full list whenever anything constrains it. The category
 * selector lists every stored category, including archived ones marked archived, and keeps
 * "No category" as a separate choice that matches only unfiled records.
 *
 * The body scrolls on its own: opening the panel on a short screen must not put any control,
 * "Clear all" included, out of reach.
 */
@Composable
private fun FilterPanel(
    filters: TransactionFilterState,
    onFiltersChange: (TransactionFilterState) -> Unit,
    categories: List<CategoryEntity>,
    resolvedDates: ResolvedDateFilter,
    modifier: Modifier = Modifier,
) {
    var categoryDialogVisible by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { onFiltersChange(TransactionFilterState()) },
                enabled = filters.isActive,
            ) {
                Text("Clear all")
            }
        }

        Text(
            text = "Type",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TypeFilterChips(
            selected = filters.type,
            onSelect = { onFiltersChange(filters.copy(type = it)) },
        )

        Text(
            text = "Category",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { categoryDialogVisible = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(categorySelectionLabel(filters.category, categories))
        }

        Text(
            text = "Dates are calendar days (YYYY-MM-DD) in your device's time zone, " +
                "From through Through, both ends included. Either end may be left empty.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = filters.fromText,
            onValueChange = { onFiltersChange(filters.copy(fromText = it)) },
            label = { Text("From") },
            placeholder = { Text("2026-10-01") },
            singleLine = true,
            isError = resolvedDates.fromError != null,
            supportingText = resolvedDates.fromError?.let { message -> { Text(message) } },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = filters.throughText,
            onValueChange = { onFiltersChange(filters.copy(throughText = it)) },
            label = { Text("Through") },
            placeholder = { Text("2026-10-15") },
            singleLine = true,
            isError = resolvedDates.throughError != null,
            supportingText = resolvedDates.throughError?.let { message -> { Text(message) } },
            modifier = Modifier.fillMaxWidth(),
        )
        if (resolvedDates.rangeError != null) {
            Text(
                text = resolvedDates.rangeError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    if (categoryDialogVisible) {
        CategoryFilterDialog(
            categories = categories,
            selected = filters.category,
            onSelect = {
                onFiltersChange(filters.copy(category = it))
                categoryDialogVisible = false
            },
            onDismiss = { categoryDialogVisible = false },
        )
    }
}

/**
 * The type dimension as wrapping chips rather than a cramped segmented row: every label —
 * including "Internal transfer" — keeps its own readable width at narrow phone widths and
 * enlarged fonts.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypeFilterChips(
    selected: TransactionType?,
    onSelect: (TransactionType?) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text("All types") },
        )
        TransactionType.entries.forEach { type ->
            FilterChip(
                selected = selected == type,
                onClick = { onSelect(type) },
                label = { Text(typeLabel(type)) },
            )
        }
    }
}

/**
 * The current category selection, named by stable id: a renamed category keeps its
 * selection, and an archived one is marked archived rather than hidden, because its
 * records remain discoverable through it.
 */
private fun categorySelectionLabel(
    selection: CategoryFilter,
    categories: List<CategoryEntity>,
): String = when (selection) {
    is CategoryFilter.All -> "All categories"
    is CategoryFilter.Uncategorized -> "No category"
    is CategoryFilter.Specific -> categories.firstOrNull { it.id == selection.categoryId }
        ?.let { categoryLabel(it) }
        ?: "Unknown category"
}

/**
 * Single-choice category selector over the stored categories. "All categories" shows every
 * record, "No category" shows only unfiled ones, and each stored category — including
 * archived ones — selects its own records by stable id. Choosing here only narrows the
 * list; it never assigns a category to a record.
 */
@Composable
private fun CategoryFilterDialog(
    categories: List<CategoryEntity>,
    selected: CategoryFilter,
    onSelect: (CategoryFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Category") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                CategoryFilterOption(
                    text = "All categories",
                    selected = selected is CategoryFilter.All,
                    onSelect = { onSelect(CategoryFilter.All) },
                )
                CategoryFilterOption(
                    text = "No category",
                    selected = selected is CategoryFilter.Uncategorized,
                    onSelect = { onSelect(CategoryFilter.Uncategorized) },
                )
                categories.forEach { category ->
                    CategoryFilterOption(
                        text = categoryLabel(category),
                        selected = selected is CategoryFilter.Specific &&
                            selected.categoryId == category.id,
                        onSelect = { onSelect(CategoryFilter.Specific(category.id)) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

@Composable
private fun CategoryFilterOption(text: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun LoadingLedger(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        Text(
            text = "Loading transactions…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TransactionList(
    records: List<TransactionEntity>,
    categoriesById: Map<Long, CategoryEntity>,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(records, key = { it.id }) { record ->
            TransactionRow(
                record = record,
                categoryName = categoryLabel(record.categoryId?.let { categoriesById[it] }),
                onOpen = { onSelect(record.id) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun TransactionRow(
    record: TransactionEntity,
    categoryName: String,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = typeLabel(record.type),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (record.description.isNotEmpty()) {
                Text(
                    text = record.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                text = categoryName + " · " +
                    ManualDateTime.formatMoment(record.occurredAtEpochMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = VndMoney.formatAmount(record.amountVnd),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun EmptyLedger(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "No transactions yet",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "Records you save appear here, newest first. Record a transaction from the " +
                "home screen to add the first one.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A filtered list with no rows is not an empty ledger: records exist, and clearing the
 * filters shows them again.
 */
@Composable
private fun NoMatchingTransactions(onClearFilters: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "No matching transactions",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "No saved records match these filters. Clearing them shows everything again.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onClearFilters,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Clear filters")
        }
    }
}
