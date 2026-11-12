package io.github.triet4p.aegisf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.CategoryQuery
import io.github.triet4p.aegisf.data.CategoryState
import io.github.triet4p.aegisf.data.ConfirmationStatus
import io.github.triet4p.aegisf.data.LedgerCategoryFilter
import io.github.triet4p.aegisf.data.LIST_PAGE_SIZE
import io.github.triet4p.aegisf.data.LedgerRecordQuery
import io.github.triet4p.aegisf.data.QueryPage
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.TransactionEntity
import io.github.triet4p.aegisf.data.TransactionType
import io.github.triet4p.aegisf.data.VndMoney
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.ui.components.AegisGlyph
import io.github.triet4p.aegisf.ui.components.AegisIcon
import io.github.triet4p.aegisf.ui.components.AegisStatus
import io.github.triet4p.aegisf.ui.components.AegisStatusChip
import io.github.triet4p.aegisf.ui.text.AegisStrings
import io.github.triet4p.aegisf.ui.text.LocalAegisStrings
import io.github.triet4p.aegisf.ui.theme.LocalAegisSemanticColors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import java.util.Locale
import java.util.TimeZone

/**
 * Ledger: the authoritative record book, and the single place records are browsed.
 *
 * The screen owns one task — find a stored record and open it. It searches the record's own
 * description (the merchant, note or label the user wrote), narrows by type, category and
 * inclusive calendar days, and pages through the rest of the same bounded read. It never
 * re-sorts, invents a row, or writes: creating a record and correcting one are separate
 * focused forms, and deleting is the receipt's own guarded action.
 *
 * The list says which day each row happened on and, when a stored record is still a draft,
 * says so on the row; the page's rows are exactly the read's page, so the page controls
 * walk the rest instead of silently dropping it. The filters are hoisted by the shell, so
 * opening a record and coming Back keeps the search, the chips, the days and the page.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)
@Composable
fun LedgerScreen(
    queryService: WorkflowQueryService,
    filters: TransactionFilterState,
    onFiltersChange: (TransactionFilterState) -> Unit,
    filtersExpanded: Boolean,
    onFiltersExpandedChange: (Boolean) -> Unit,
    onSelect: (Long) -> Unit,
    onCreate: () -> Unit,
    onNavigateUp: () -> Unit,
    showBack: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAegisStrings.current
    val zone = remember { TimeZone.getDefault() }
    val resolvedDates = remember(filters.fromText, filters.throughText) {
        resolveDateFilter(filters.fromText, filters.throughText)
    }
    var pageIndex by rememberSaveable { mutableIntStateOf(0) }
    var retryToken by remember { mutableIntStateOf(0) }
    // A new filter is a new read: its pages are not the pages of the old one, so the list
    // starts again at its first page instead of showing an empty later one.
    LaunchedEffect(filters, resolvedDates.range) {
        pageIndex = 0
    }
    val recordQuery = remember(filters, resolvedDates.range, pageIndex) {
        LedgerRecordQuery(
            type = filters.type,
            category = filters.category,
            occurredIn = resolvedDates.range,
            searchText = filters.effectiveSearchText,
            bounds = QueryPageRequest(LIST_PAGE_SIZE, offset = pageIndex * LIST_PAGE_SIZE),
        )
    }
    val loadFlow: Flow<LedgerLoad> = remember(queryService, recordQuery, retryToken) {
        // A failed flow construction (closed or unreadable store) is reported by this screen's
        // own error state rather than escaping composition.
        runCatching {
            queryService.observeLedgerRecords(recordQuery)
                .flatMapLatest { page ->
                    flow<LedgerLoad> {
                        emit(
                            LedgerLoad.Ready(
                                page = page,
                                categories = queryService.categoriesByIds(
                                    page.items.mapNotNull { it.categoryId }.distinct(),
                                ),
                            ),
                        )
                    }
                }
                .onStart { emit(LedgerLoad.Loading) }
                .catch { emit(LedgerLoad.Error) }
        }.getOrElse { flowOf(LedgerLoad.Error) }
    }
    val load: LedgerLoad by loadFlow.collectAsState(initial = LedgerLoad.Loading)

    // Whether the ledger itself is empty, which the screen tells apart from "the filters
    // match nothing": one bounded row is enough to know, and the empty state and the
    // no-match state say different things to the user.
    val anyRecordFlow = remember(queryService, retryToken) {
        runCatching {
            queryService.observeLedgerRecords(
                LedgerRecordQuery(bounds = QueryPageRequest(1)),
            ).catch {
                emit(
                    QueryPage(
                        items = emptyList<TransactionEntity>(),
                        offset = 0,
                        limit = 1,
                        hasMore = false,
                    ),
                )
            }
        }.getOrElse {
            flowOf(QueryPage(items = emptyList<TransactionEntity>(), offset = 0, limit = 1, hasMore = false))
        }
    }
    val anyRecordPage: QueryPage<TransactionEntity>? by anyRecordFlow.collectAsState(initial = null)
    val ledgerIsEmpty = anyRecordPage?.items?.isEmpty() == true

    val selectedCategoryId = (filters.category as? LedgerCategoryFilter.Specific)?.categoryId
    val selectedCategory: CategoryEntity? by remember(queryService, selectedCategoryId) {
        if (selectedCategoryId == null) {
            flowOf<CategoryEntity?>(null)
        } else {
            queryService.observeCategory(selectedCategoryId)
        }
    }.collectAsState(initial = null)

    BackHandler(enabled = showBack, onBack = onNavigateUp)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(strings.tabLedger) },
                navigationIcon = {
                    if (showBack) {
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
                    }
                },
                actions = {
                    IconButton(
                        onClick = onCreate,
                        modifier = Modifier.semantics {
                            contentDescription = strings.addRecord
                        },
                    ) {
                        AegisIcon(
                            glyph = AegisGlyph.MANUAL,
                            tint = MaterialTheme.colorScheme.primary,
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
                .padding(innerPadding)
                .imePadding(),
        ) {
            SearchField(
                searchText = filters.searchText,
                strings = strings,
                onSearchTextChange = { onFiltersChange(filters.copy(searchText = it)) },
            )
            TypeFilterChips(
                selected = filters.type,
                strings = strings,
                onSelect = { onFiltersChange(filters.copy(type = it)) },
            )
            FilterToggleRow(
                expanded = filtersExpanded,
                activeDimensionCount = filters.activeDimensionCount,
                strings = strings,
                onExpandedChange = onFiltersExpandedChange,
            )
            if (filtersExpanded) {
                FilterPanel(
                    filters = filters,
                    onFiltersChange = onFiltersChange,
                    queryService = queryService,
                    selectedCategory = selectedCategory,
                    resolvedDates = resolvedDates,
                    strings = strings,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            when (val current = load) {
                LedgerLoad.Loading -> Text(
                    text = strings.loadingReading,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )

                LedgerLoad.Error -> Column(
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

                is LedgerLoad.Ready -> {
                    val records = current.page.items
                    val categoriesById = remember(current.categories) {
                        current.categories.associateBy { it.id }
                    }
                    when {
                        ledgerIsEmpty -> EmptyLedger(strings, Modifier.weight(1f))

                        records.isEmpty() -> NoMatchingTransactions(
                            strings = strings,
                            onClearFilters = { onFiltersChange(TransactionFilterState()) },
                            modifier = Modifier.weight(1f),
                        )

                        else -> {
                            PageControls(
                                pageIndex = pageIndex,
                                hasMore = current.page.hasMore,
                                onPrevious = { pageIndex -= 1 },
                                onNext = { pageIndex += 1 },
                                previousLabel = strings.pagePrevious,
                                nextLabel = strings.pageNext,
                                pageLabel = strings.pageNumber(pageIndex),
                            )
                            Text(
                                text = strings.storedOnDevice,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                            )
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                            ) {
                                groupTransactionsByDay(records, zone).forEach { group ->
                                    item(key = "day-${group.dayText}") {
                                        Text(
                                            text = dayHeadingText(
                                                group.dayText,
                                                strings,
                                                zone = zone,
                                            ),
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
                                            onOpen = { onSelect(record.id) },
                                            strings = strings,
                                            showStatus = record.confirmation !=
                                                ConfirmationStatus.CONFIRMED,
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
    }
    // A page that lost its rows — a record was deleted, a filter narrowed — steps back to
    // the nearest page that still holds rows instead of showing an empty page that looks
    // like the end of the data.
    ClampPageToRows(
        pageIndex = pageIndex,
        hasRows = (load as? LedgerLoad.Ready)?.page?.items?.isNotEmpty(),
        onClamp = { pageIndex = it },
    )
}

/** What the ledger knows: reading, the page, or a failed read. */
private sealed interface LedgerLoad {
    data object Loading : LedgerLoad
    data object Error : LedgerLoad
    data class Ready(
        val page: QueryPage<TransactionEntity>,
        val categories: List<CategoryEntity>,
    ) : LedgerLoad
}

/**
 * The search field: the record's description is what is searched, so the placeholder says
 * exactly that. Clearing it restores the full list through the same state the read uses.
 */
@Composable
private fun SearchField(
    searchText: String,
    strings: AegisStrings,
    onSearchTextChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = searchText,
        onValueChange = onSearchTextChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("ledger-search")
            .semantics { contentDescription = strings.searchDescription },
        singleLine = true,
        placeholder = { Text(strings.searchPlaceholder) },
        trailingIcon = {
            if (searchText.isNotEmpty()) {
                IconButton(
                    onClick = { onSearchTextChange("") },
                    modifier = Modifier.semantics {
                        contentDescription = strings.clearSearch
                    },
                ) {
                    AegisIcon(
                        glyph = AegisGlyph.CROSS,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        size = 18.dp,
                    )
                }
            }
        },
        shape = RoundedCornerShape(12.dp),
    )
}

/** The type dimension as wrapping chips: every label keeps its own readable width. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypeFilterChips(
    selected: TransactionType?,
    strings: AegisStrings,
    onSelect: (TransactionType?) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FilterChip(
            selected = selected == null,
            shape = MaterialTheme.shapes.small,
            onClick = { onSelect(null) },
            label = { Text(strings.allTypes) },
        )
        TransactionType.entries.forEach { type ->
            FilterChip(
                selected = selected == type,
                shape = MaterialTheme.shapes.small,
                onClick = { onSelect(type) },
                label = { Text(strings.typeName(type)) },
            )
        }
    }
}

/**
 * The one-line control that owns the filter panel: it says what the tap will do, and reports
 * how many dimensions are set so the filters stay legible while the panel is shut.
 */
@Composable
private fun FilterToggleRow(
    expanded: Boolean,
    activeDimensionCount: Int,
    strings: AegisStrings,
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
            Text(if (expanded) strings.filtersHide else strings.filtersShow)
        }
        if (activeDimensionCount > 0) {
            Text(
                text = strings.activeFilterCount(activeDimensionCount),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
    }
}

/**
 * The remaining filters: one category selector and the inclusive From/Through calendar days.
 *
 * Every control keeps the unconstrained choice ("All categories", empty days) and "Clear
 * all" returns the full list. The category lists every stored category, archived ones
 * marked archived; "No category" is a separate choice that matches only unfiled records.
 * The body scrolls on its own, so opening the panel never puts a control out of reach.
 */
@Composable
private fun FilterPanel(
    filters: TransactionFilterState,
    onFiltersChange: (TransactionFilterState) -> Unit,
    queryService: WorkflowQueryService,
    selectedCategory: CategoryEntity?,
    resolvedDates: ResolvedDateFilter,
    strings: AegisStrings,
    modifier: Modifier = Modifier,
) {
    var categoryDialogVisible by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
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
                Text(strings.clearAll)
            }
        }

        Text(
            text = strings.filterDates,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = strings.dateHelp,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = filters.fromText,
            onValueChange = { onFiltersChange(filters.copy(fromText = it)) },
            label = { Text(strings.fromDate) },
            placeholder = { Text("YYYY-MM-DD") },
            singleLine = true,
            isError = resolvedDates.fromError != null,
            supportingText = resolvedDates.fromError?.let { message -> { Text(message) } },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = filters.throughText,
            onValueChange = { onFiltersChange(filters.copy(throughText = it)) },
            label = { Text(strings.throughDate) },
            placeholder = { Text("YYYY-MM-DD") },
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

        Text(
            text = strings.filterCategory,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            shape = MaterialTheme.shapes.small,
            onClick = { categoryDialogVisible = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = when (filters.category) {
                    is LedgerCategoryFilter.All -> strings.allCategories
                    is LedgerCategoryFilter.Uncategorized -> strings.noCategory
                    is LedgerCategoryFilter.Specific -> if (selectedCategory == null) {
                        strings.unknownCategory
                    } else {
                        localizedCategoryName(selectedCategory, strings)
                    }
                },
            )
        }
    }

    if (categoryDialogVisible) {
        CategoryFilterDialog(
            queryService = queryService,
            selected = filters.category,
            strings = strings,
            onSelect = {
                onFiltersChange(filters.copy(category = it))
                categoryDialogVisible = false
            },
            onDismiss = { categoryDialogVisible = false },
        )
    }
}

/**
 * Single-choice category selector over the stored categories. "All categories" shows every
 * record, "No category" shows only unfiled ones, and each stored category — including
 * archived ones — selects its own records by stable id. The catalog is read page by page,
 * so every stored category is reachable.
 */
@Composable
internal fun CategoryFilterDialog(
    queryService: WorkflowQueryService,
    selected: LedgerCategoryFilter,
    strings: AegisStrings,
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
    val selectedId = (selected as? LedgerCategoryFilter.Specific)?.categoryId
    val selectedRow: CategoryEntity? by remember(queryService, selectedId) {
        if (selectedId == null) {
            flowOf(null)
        } else {
            queryService.observeCategory(selectedId)
        }
    }.collectAsState(initial = null)
    ClampPageToRows(
        pageIndex = pageIndex,
        hasRows = categoryPage?.items?.isNotEmpty(),
        onClamp = { pageIndex = it },
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.filterCategory) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                PageControls(
                    pageIndex = pageIndex,
                    hasMore = categoryPage?.hasMore == true,
                    onPrevious = { pageIndex -= 1 },
                    onNext = { pageIndex += 1 },
                    previousLabel = strings.pagePrevious,
                    nextLabel = strings.pageNext,
                    pageLabel = strings.pageNumber(pageIndex),
                )
                CategoryFilterOption(
                    text = strings.allCategories,
                    selected = selected is LedgerCategoryFilter.All,
                    showSwatch = false,
                    strings = strings,
                    onSelect = { onSelect(LedgerCategoryFilter.All) },
                )
                CategoryFilterOption(
                    text = strings.noCategory,
                    selected = selected is LedgerCategoryFilter.Uncategorized,
                    strings = strings,
                    onSelect = { onSelect(LedgerCategoryFilter.Uncategorized) },
                )
                val retainedRow = selectedRow
                if (retainedRow != null && categories.none { it.id == retainedRow.id }) {
                    CategoryFilterOption(
                        text = localizedCategoryName(retainedRow, strings),
                        categoryId = retainedRow.id,
                        archived = retainedRow.archived,
                        selected = true,
                        strings = strings,
                        onSelect = { onSelect(LedgerCategoryFilter.Specific(retainedRow.id)) },
                    )
                }
                categories.forEach { category ->
                    CategoryFilterOption(
                        text = localizedCategoryName(category, strings),
                        categoryId = category.id,
                        archived = category.archived,
                        selected = selected is LedgerCategoryFilter.Specific &&
                            selected.categoryId == category.id,
                        strings = strings,
                        onSelect = { onSelect(LedgerCategoryFilter.Specific(category.id)) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(strings.close) }
        },
    )
}

@Composable
private fun CategoryFilterOption(
    text: String,
    selected: Boolean,
    strings: AegisStrings,
    categoryId: Long? = null,
    archived: Boolean = false,
    showSwatch: Boolean = true,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .defaultMinSize(minHeight = 48.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        if (showSwatch) {
            CategorySwatch(
                categoryId = categoryId,
                archived = archived,
                directory = true,
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        )
        if (archived) {
            ArchivedCategoryBadge(label = strings.archivedLabel.uppercase(Locale.ROOT))
        }
    }
}

/**
 * One stored record in a list: what it was, the category it is filed under, the moment and
 * provenance, and the exact signed amount.
 *
 * [showStatus] adds the record's own lifecycle state when it is not the ordinary confirmed
 * one, so an older draft record can never be mistaken for a settled row. The amount sign is
 * the stored type's direction; an internal transfer carries no sign because it is neither
 * money out nor money in.
 */
@Composable
internal fun TransactionRow(
    record: TransactionEntity,
    categoryName: String,
    categoryId: Long?,
    categoryArchived: Boolean,
    onOpen: () -> Unit,
    strings: AegisStrings,
    showStatus: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    CategoryPalette.color(
                        categoryId = categoryId,
                        darkTheme = androidx.compose.foundation.isSystemInDarkTheme(),
                        archived = categoryArchived,
                    ).copy(alpha = 0.16f),
                ),
            contentAlignment = Alignment.Center,
        ) {
            CategorySwatch(
                categoryId = categoryId,
                archived = categoryArchived,
                directory = true,
                size = 16.dp,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = record.description.takeIf { it.isNotEmpty() }
                        ?: strings.typeName(record.type),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = amountPrefix(record.type) +
                        VndMoney.formatAmount(record.amountVnd, " ₫"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = when (record.type) {
                        TransactionType.EXPENSE -> LocalAegisSemanticColors.current.expense
                        TransactionType.INCOME -> LocalAegisSemanticColors.current.income
                        TransactionType.TRANSFER -> LocalAegisSemanticColors.current.transfer
                    },
                    maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                text = categoryName +
                    " · " + ManualDateTime.timeFieldText(record.occurredAtEpochMillis) +
                    " · " + strings.sourceName(record.source),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (showStatus) {
                AegisStatusChip(
                    status = AegisStatus.NEEDS_REVIEW,
                    label = strings.statusDraft,
                )
            }
        }
    }
}

@Composable
private fun EmptyLedger(strings: AegisStrings, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = strings.emptyLedgerTitle,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = strings.emptyLedgerBody,
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
private fun NoMatchingTransactions(
    strings: AegisStrings,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = strings.noMatchesTitle,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = strings.noMatchesBody,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            shape = MaterialTheme.shapes.small,
            onClick = onClearFilters,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(strings.clearFilters)
        }
    }
}
