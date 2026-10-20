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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.CategoryQuery
import io.github.triet4p.aegisf.data.CategoryState
import io.github.triet4p.aegisf.data.LedgerCategoryFilter
import io.github.triet4p.aegisf.data.LIST_PAGE_SIZE
import io.github.triet4p.aegisf.data.LedgerRecordQuery
import io.github.triet4p.aegisf.data.QueryPage
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.TransactionEntity
import io.github.triet4p.aegisf.data.TransactionType
import io.github.triet4p.aegisf.data.VndMoney
import io.github.triet4p.aegisf.data.WorkflowQueryService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * The stored records, newest first, narrowed by the browsing filters — and the way into one
 * of them.
 *
 * This is the single browsing surface: it reads the live transaction and category streams
 * from the same database file every launch opens, so records saved in any earlier session,
 * corrections, deletions, renames, and archives appear on their own, and a tap still opens
 * that record's detail view, where it can be corrected or deleted. The list order is the
 * shared read's newest-occurred-at-first order with the stored id as the tie-breaker; the
 * filters narrow what is shown but never re-sort or fabricate rows. The read is bounded:
 * it asks for one page of rows and says which page it shows, and the page controls walk
 * the rest of the rows of the same read, so a record after the first page is reached like
 * every other one instead of being dropped without a word.
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)
@Composable
fun TransactionListScreen(
    queryService: WorkflowQueryService,
    filters: TransactionFilterState,
    onFiltersChange: (TransactionFilterState) -> Unit,
    filtersExpanded: Boolean,
    onFiltersExpandedChange: (Boolean) -> Unit,
    onSelect: (Long) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Pure derivation, never a composition write: a refused date dimension reports its
    // refusal and is treated as open, so the other dimensions still narrow honestly.
    val resolvedDates = remember(filters.fromText, filters.throughText) {
        resolveDateFilter(filters.fromText, filters.throughText)
    }
    // The page the list is showing. A page is an offset into the same bounded read, so
    // the rows after the first page are reached the same way the first ones are.
    var pageIndex by rememberSaveable { mutableIntStateOf(0) }
    // A new filter is a new read: its pages are not the pages of the old one, so the list
    // starts again at its first page instead of showing an empty later one.
    LaunchedEffect(filters, resolvedDates.range) {
        pageIndex = 0
    }
    // The filters are the query itself: the shared read applies the same dimensions the
    // screen shows, so what is listed is what the read returned and not a second
    // interpretation of the same filters.
    val recordQuery = remember(filters, resolvedDates.range, pageIndex) {
        LedgerRecordQuery(
            type = filters.type,
            category = filters.category,
            occurredIn = resolvedDates.range,
            bounds = QueryPageRequest(LIST_PAGE_SIZE, offset = pageIndex * LIST_PAGE_SIZE),
        )
    }
    // Hoisted so each collected flow has a stable identity: building one on every
    // recomposition would restart the collection (and blank the list) each time.
    val transactionFlow = remember(queryService, recordQuery) {
        queryService.observeLedgerRecords(recordQuery)
    }
    // The rows of the page and the categories they are filed under arrive together: a
    // row is named by the category it is filed under — even when that category is not on
    // the page of the catalog the filter panel browses — and the two are never on screen
    // disagreeing about it.
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
    // Null until the first emission: it distinguishes "loading" from "nothing stored".
    val records: List<TransactionEntity>? = recordPage?.first?.items
    val visible: List<TransactionEntity> = records.orEmpty()
    val categoriesById: Map<Long, CategoryEntity> =
        remember(recordPage?.second) { recordPage?.second.orEmpty().associateBy { it.id } }
    // The category the filter names, read by its own id: a renamed category keeps its
    // name and an archived one keeps its marker, whichever page of the catalog the
    // filter panel is browsing.
    val selectedCategoryId = (filters.category as? LedgerCategoryFilter.Specific)?.categoryId
    val selectedCategory by remember(queryService, selectedCategoryId) {
        if (selectedCategoryId == null) {
            flowOf(null)
        } else {
            queryService.observeCategory(selectedCategoryId)
        }
    }.collectAsState(initial = null)
    // Whether the ledger itself is empty, which the screen tells apart from "the filters
    // match nothing": one bounded row is enough to know, and the empty state and the
    // no-match state say different things to the user.
    val anyRecordFlow = remember(queryService) {
        queryService.observeLedgerRecords(
            LedgerRecordQuery(bounds = QueryPageRequest(1)),
        )
    }
    val anyRecordPage: QueryPage<TransactionEntity>? by anyRecordFlow.collectAsState(initial = null)
    val ledgerIsEmpty = anyRecordPage?.items?.isEmpty() == true

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
        if (loaded == null) {
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
                        queryService = queryService,
                        selectedCategory = selectedCategory,
                        resolvedDates = resolvedDates,
                        // The open panel takes only the height it needs, and at most half
                        // of what is left, so the results stay on screen and both halves
                        // keep scrolling instead of one squeezing the other to nothing.
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                when {
                    ledgerIsEmpty -> EmptyLedger(Modifier.weight(1f))

                    visible.isEmpty() -> NoMatchingTransactions(
                        onClearFilters = { onFiltersChange(TransactionFilterState()) },
                        modifier = Modifier.weight(1f),
                    )

                    else -> {
                        // The rows after this page are one page further on in the same
                        // read: the list says which page it shows and how to walk the
                        // rest, instead of dropping the rows it did not hold. The
                        // controls sit above the rows, so they are reachable without
                        // walking past a whole page of them first.
                        PageControls(
                            pageIndex = pageIndex,
                            hasMore = recordPage?.first?.hasMore == true,
                            onPrevious = { pageIndex -= 1 },
                            onNext = { pageIndex += 1 },
                        )
                        TransactionList(
                            records = visible,
                            categoriesById = categoriesById,
                            onSelect = onSelect,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
    // A page that lost its rows — a record was deleted, a filter narrowed — steps back to
    // the nearest page that still holds rows instead of showing an empty one.
    ClampPageToRows(
        pageIndex = pageIndex,
        hasRows = recordPage?.first?.items?.isNotEmpty(),
        onClamp = { pageIndex = it },
    )
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
    queryService: WorkflowQueryService,
    selectedCategory: CategoryEntity?,
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
            Text(categorySelectionLabel(filters.category, selectedCategory))
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
            queryService = queryService,
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
 *
 * The category is named by its own row, so the name is right whichever page of the
 * catalog the selector happens to be showing.
 */
private fun categorySelectionLabel(
    selection: LedgerCategoryFilter,
    selectedCategory: CategoryEntity?,
): String = when (selection) {
    is LedgerCategoryFilter.All -> "All categories"
    is LedgerCategoryFilter.Uncategorized -> "No category"
    is LedgerCategoryFilter.Specific -> selectedCategory
        ?.let { categoryLabel(it) }
        ?: "Unknown category"
}

/**
 * Single-choice category selector over the stored categories. "All categories" shows every
 * record, "No category" shows only unfiled ones, and each stored category — including
 * archived ones — selects its own records by stable id. Choosing here only narrows the
 * list; it never assigns a category to a record.
 *
 * The catalog is read page by page, so every stored category is reachable: the selector
 * walks the pages of the one bounded read instead of showing the first page and stopping
 * there. The chosen category is named by its own row, so a choice made on one page is
 * still shown, and still marked, on another.
 */
@Composable
private fun CategoryFilterDialog(
    queryService: WorkflowQueryService,
    selected: LedgerCategoryFilter,
    onSelect: (LedgerCategoryFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    var pageIndex by rememberSaveable { mutableIntStateOf(0) }
    val categoryQuery = remember(pageIndex) {
        CategoryQuery(
            state = CategoryState.ALL,
            bounds = QueryPageRequest(LIST_PAGE_SIZE, offset = pageIndex * LIST_PAGE_SIZE),
        )
    }
    val categoryPage: QueryPage<CategoryEntity>? by remember(queryService, categoryQuery) {
        queryService.observeCategories(categoryQuery)
    }.collectAsState(initial = null)
    val categories = categoryPage?.items.orEmpty()
    // The chosen category is read by its own id, so it is named and marked whether or not
    // it is on the page the selector is showing.
    val selectedId = (selected as? LedgerCategoryFilter.Specific)?.categoryId
    val selectedRow: CategoryEntity? by remember(queryService, selectedId) {
        if (selectedId == null) {
            flowOf(null)
        } else {
            queryService.observeCategory(selectedId)
        }
    }.collectAsState(initial = null)
    // A page that lost its categories steps back to the nearest page that still holds
    // some, so the selector never shows an empty page in the middle of the catalog.
    ClampPageToRows(
        pageIndex = pageIndex,
        hasRows = categoryPage?.items?.isNotEmpty(),
        onClamp = { pageIndex = it },
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Category") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                PageControls(
                    pageIndex = pageIndex,
                    hasMore = categoryPage?.hasMore == true,
                    onPrevious = { pageIndex -= 1 },
                    onNext = { pageIndex += 1 },
                )
                CategoryFilterOption(
                    text = "All categories",
                    selected = selected is LedgerCategoryFilter.All,
                    onSelect = { onSelect(LedgerCategoryFilter.All) },
                )
                CategoryFilterOption(
                    text = "No category",
                    selected = selected is LedgerCategoryFilter.Uncategorized,
                    onSelect = { onSelect(LedgerCategoryFilter.Uncategorized) },
                )
                val retainedRow = selectedRow
                if (retainedRow != null && categories.none { it.id == retainedRow.id }) {
                    CategoryFilterOption(
                        text = categoryLabel(retainedRow),
                        selected = true,
                        onSelect = { onSelect(LedgerCategoryFilter.Specific(retainedRow.id)) },
                    )
                }
                categories.forEach { category ->
                    CategoryFilterOption(
                        text = categoryLabel(category),
                        selected = selected is LedgerCategoryFilter.Specific &&
                            selected.categoryId == category.id,
                        onSelect = { onSelect(LedgerCategoryFilter.Specific(category.id)) },
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
