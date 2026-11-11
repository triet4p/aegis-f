package io.github.triet4p.aegisf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.triet4p.aegisf.data.AddCategoryRequest
import io.github.triet4p.aegisf.data.ArchiveCategoryRequest
import io.github.triet4p.aegisf.data.CategoryDeletionImpact
import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.CategoryQuery
import io.github.triet4p.aegisf.data.CategoryRule
import io.github.triet4p.aegisf.data.CategoryRuleQuery
import io.github.triet4p.aegisf.data.CategoryState
import io.github.triet4p.aegisf.data.DeleteCategoryRequest
import io.github.triet4p.aegisf.data.LIST_PAGE_SIZE
import io.github.triet4p.aegisf.data.QueryPage
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.RememberCategoryRuleRequest
import io.github.triet4p.aegisf.data.RemoveCategoryRuleRequest
import io.github.triet4p.aegisf.data.RenameCategoryRequest
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowCommandOutcome
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowDenialReason
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.ui.components.AegisGlyph
import io.github.triet4p.aegisf.ui.components.AegisIcon
import io.github.triet4p.aegisf.ui.text.LocalAegisStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.Serializable
import java.util.UUID

/**
 * Categories & rules: two focused task views behind one destination.
 *
 * The Categories tab is the user's catalog. Every category — including any that came with the
 * app — can be renamed, archived or deleted; the backend has no locked default, so this screen
 * presents none. Archive keeps every past record attached. Delete is a separately worded,
 * explicitly confirmed destructive action that first reads what the category is attached to and
 * never offers the delete until that read succeeded; it also offers Archive as the safe route.
 *
 * The Rules tab is the user's standing decisions for exact descriptions. A rule is not a prefix
 * or wildcard matcher: the data layer stores one canonical exact description, and the screen
 * says so. Adding and editing are one sheet each; removing is its own confirmation. Neither tab
 * records a financial change or confirms a draft.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoryRulesScreen(
    commandService: WorkflowCommandService,
    queryService: WorkflowQueryService,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val baseStrings = LocalAegisStrings.current
    val strings = remember(baseStrings.language) { categoryRulesStrings(baseStrings.language) }
    val scope = rememberCoroutineScope()

    var tab by rememberSaveable { mutableStateOf(RuleTab.CATEGORIES) }
    var categoryFilter by rememberSaveable { mutableStateOf(CategoryState.ALL) }
    var categoryPageIndex by rememberSaveable { mutableIntStateOf(0) }
    var rulePageIndex by rememberSaveable { mutableIntStateOf(0) }
    var retryToken by rememberSaveable { mutableIntStateOf(0) }

    val categoryQuery = remember(categoryFilter, categoryPageIndex) {
        CategoryQuery(
            state = categoryFilter,
            bounds = QueryPageRequest(LIST_PAGE_SIZE, offset = categoryPageIndex * LIST_PAGE_SIZE),
        )
    }
    val ruleQuery = remember(rulePageIndex) {
        CategoryRuleQuery(
            bounds = QueryPageRequest(LIST_PAGE_SIZE, offset = rulePageIndex * LIST_PAGE_SIZE),
        )
    }

    var categoryPage by remember { mutableStateOf<QueryPage<CategoryEntity>?>(null) }
    var categoryCounts by remember { mutableStateOf<Int?>(null) }
    var archivedCounts by remember { mutableStateOf<Int?>(null) }
    var categoryReadFailed by remember { mutableStateOf(false) }
    var rulePage by remember { mutableStateOf<QueryPage<CategoryRule>?>(null) }
    var ruleCount by remember { mutableStateOf<Int?>(null) }
    var ruleReadFailed by remember { mutableStateOf(false) }

    LaunchedEffect(queryService, categoryQuery, retryToken) {
        categoryReadFailed = false
        try {
            queryService.observeCategories(categoryQuery).collect { categoryPage = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            categoryReadFailed = true
        }
    }
    LaunchedEffect(queryService, retryToken) {
        try {
            queryService.observeCategoryCatalogCounts().collect { counts ->
                categoryCounts = counts.total
                archivedCounts = counts.archived
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The page itself reports a read failure; the count stays unknown so the screen
            // never states a number it could not read.
        }
    }
    LaunchedEffect(queryService, ruleQuery, retryToken) {
        ruleReadFailed = false
        try {
            queryService.observeCategoryRules(ruleQuery).collect { rulePage = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ruleReadFailed = true
        }
    }
    LaunchedEffect(queryService, retryToken) {
        try {
            queryService.observeCategoryRuleCount().collect { ruleCount = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Unknown count; the page's own read error is what the user sees.
        }
    }
    ClampPageToRows(
        pageIndex = categoryPageIndex,
        hasRows = categoryPage?.items?.isNotEmpty(),
        onClamp = { categoryPageIndex = it },
    )
    ClampPageToRows(
        pageIndex = rulePageIndex,
        hasRows = rulePage?.items?.isNotEmpty(),
        onClamp = { rulePageIndex = it },
    )

    var categorySheet by rememberSaveable { mutableStateOf<CategorySheet?>(null) }
    var ruleSheet by rememberSaveable { mutableStateOf<RuleSheet?>(null) }
    var confirm by rememberSaveable { mutableStateOf<RuleConfirm?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    BackHandler(onBack = onNavigateUp)
    LaunchedEffect(statusMessage) {
        val message = statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        statusMessage = null
    }

    val knownCategoryCount = categoryCounts ?: categoryPage?.items?.size ?: 0
    val knownRuleCount = ruleCount ?: rulePage?.items?.size ?: 0
    val categories = categoryPage?.items.orEmpty()
    val rules = rulePage?.items.orEmpty()
    val showingCategories = tab == RuleTab.CATEGORIES
    val readFailed = if (showingCategories) categoryReadFailed else ruleReadFailed
    val loading = if (showingCategories) {
        categoryPage == null && !categoryReadFailed
    } else {
        rulePage == null && !ruleReadFailed
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = strings.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        AegisIcon(
                            glyph = AegisGlyph.ARROW_BACK,
                            tint = MaterialTheme.colorScheme.onSurface,
                            size = 22.dp,
                            modifier = Modifier.semantics {
                                contentDescription = strings.back
                            },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.background,
                tonalElevation = 3.dp,
            ) {
                Button(
                    enabled = !loading && !readFailed,
                    onClick = {
                        if (showingCategories) {
                            categorySheet = CategorySheet(
                                mode = CategorySheetMode.ADD,
                                categoryId = 0L,
                                initialName = "",
                                requestKey = UUID.randomUUID().toString(),
                                archived = false,
                            )
                        } else {
                            ruleSheet = RuleSheet(
                                editing = false,
                                originalDescription = "",
                                originalCategoryId = 0L,
                                requestKey = UUID.randomUUID().toString(),
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .heightIn(min = 52.dp)
                        .testTag(if (showingCategories) "category-add" else "rule-add"),
                ) {
                    Text(if (showingCategories) strings.addCategory else strings.addRule)
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TaskTabs(
                selected = tab,
                categoriesLabel = if (categoryCounts == null) {
                    strings.categoriesTab
                } else {
                    strings.categoriesTabWithCount(knownCategoryCount)
                },
                rulesLabel = if (ruleCount == null) {
                    strings.rulesTab
                } else {
                    strings.rulesTabWithCount(knownRuleCount)
                },
                onSelect = { tab = it },
            )

            Box(modifier = Modifier.weight(1f)) {
                when {
                    loading -> LoadingState(strings.loading)
                    readFailed -> ReadErrorState(
                        title = strings.readErrorTitle,
                        body = strings.readErrorBody,
                        retry = strings.retry,
                        onRetry = { retryToken += 1 },
                    )
                    showingCategories -> CategoryContent(
                    page = categoryPage,
                    archivedCount = archivedCounts,
                    totalCount = knownCategoryCount,
                    filter = categoryFilter,
                    onFilterChange = {
                        categoryFilter = it
                        categoryPageIndex = 0
                    },
                    pageIndex = categoryPageIndex,
                    pageLabel = strings.pageLabel(categoryPageIndex + 1),
                    previousLabel = strings.pagePrevious,
                    nextLabel = strings.pageNext,
                    onPrevious = { categoryPageIndex -= 1 },
                    onNext = { categoryPageIndex += 1 },
                    allFilterLabel = strings.filterAll,
                    activeFilterLabel = strings.filterActive,
                    archivedFilterLabel = strings.filterArchived,
                    archivedCountLabel = strings.archivedCountLabel,
                    archivedBadgeLabel = strings.statusArchived,
                    onEdit = { category ->
                        categorySheet = CategorySheet(
                            mode = CategorySheetMode.EDIT,
                            categoryId = category.id,
                            initialName = category.name,
                            requestKey = UUID.randomUUID().toString(),
                            archived = category.archived,
                        )
                    },
                    onDelete = { category ->
                        confirm = RuleConfirm.DeleteCategory(
                            categoryId = category.id,
                            name = category.name,
                            archived = category.archived,
                        )
                    },
                    emptyTitle = strings.emptyCategoriesTitle,
                    emptyBody = strings.emptyCategoriesBody,
                    emptyFilterTitle = strings.emptyFilterTitle,
                    emptyFilterBody = strings.emptyFilterBody,
                    activeLabel = strings.categoryActive,
                    archivedLabel = strings.categoryArchived,
                    editLabel = strings.edit,
                    deleteLabel = strings.delete,
                    help = strings.categoriesHelp,
                )
                else -> RuleContent(
                    page = rulePage,
                    pageIndex = rulePageIndex,
                    pageLabel = strings.pageLabel(rulePageIndex + 1),
                    previousLabel = strings.pagePrevious,
                    nextLabel = strings.pageNext,
                    onPrevious = { rulePageIndex -= 1 },
                    onNext = { rulePageIndex += 1 },
                    onEdit = { rule ->
                        ruleSheet = RuleSheet(
                            editing = true,
                            originalDescription = rule.patternKey,
                            originalCategoryId = rule.categoryId,
                            requestKey = UUID.randomUUID().toString(),
                        )
                    },
                    onRemove = { rule ->
                        confirm = RuleConfirm.RemoveRule(
                            description = rule.patternKey,
                            categoryName = rule.categoryName,
                        )
                    },
                    emptyTitle = strings.emptyRulesTitle,
                    emptyBody = strings.emptyRulesBody,
                    editLabel = strings.edit,
                    removeLabel = strings.ruleRemove,
                    help = strings.rulesHelp,
                    archivedLabel = strings.statusArchived,
                        moreRules = strings.moreRules,
                    )
                }
            }
        }
    }

    categorySheet?.let { sheet ->
        CategorySheetContent(
            sheet = sheet,
            strings = strings,
            commandService = commandService,
            onDismiss = { categorySheet = null },
            onSaved = { added, name ->
                categorySheet = null
                statusMessage = if (added) strings.addedNotice else strings.renamedNotice
                // A new category is active. Leaving an Archived-only filter in place would hide
                // the category the user just created, so return the list to a state that shows it.
                if (added && categoryFilter == CategoryState.ARCHIVED) {
                    categoryFilter = CategoryState.ALL
                    categoryPageIndex = 0
                }
            },
            onArchiveRequest = {
                categorySheet = null
                confirm = RuleConfirm.ArchiveCategory(sheet.categoryId, sheet.initialName)
            },
        )
    }

    ruleSheet?.let { sheet ->
        RuleSheetContent(
            sheet = sheet,
            strings = strings,
            queryService = queryService,
            commandService = commandService,
            onDismiss = { ruleSheet = null },
            onSaved = { updated ->
                ruleSheet = null
                statusMessage = if (updated) strings.ruleUpdatedNotice else strings.ruleAddedNotice
            },
            onPartial = {
                ruleSheet = null
                statusMessage = strings.ruleEditPartial
            },
        )
    }

    when (val action = confirm) {
        is RuleConfirm.ArchiveCategory -> ArchiveCategoryDialog(
            strings = strings,
            name = action.name,
            commandService = commandService,
            categoryId = action.categoryId,
            onDismiss = { confirm = null },
            onArchived = {
                confirm = null
                statusMessage = strings.archivedNotice
            },
        )
        is RuleConfirm.DeleteCategory -> DeleteCategoryDialog(
            strings = strings,
            categoryId = action.categoryId,
            name = action.name,
            archived = action.archived,
            queryService = queryService,
            commandService = commandService,
            onDismiss = { confirm = null },
            onDeleted = {
                confirm = null
                statusMessage = strings.deletedNotice
            },
            onArchived = {
                confirm = null
                statusMessage = strings.archivedNotice
            },
        )
        is RuleConfirm.RemoveRule -> RemoveRuleDialog(
            strings = strings,
            description = action.description,
            categoryName = action.categoryName,
            commandService = commandService,
            onDismiss = { confirm = null },
            onRemoved = {
                confirm = null
                statusMessage = strings.ruleRemovedNotice
            },
        )
        null -> Unit
    }
}

private enum class RuleTab { CATEGORIES, RULES }

private enum class CategorySheetMode { ADD, EDIT }

private data class CategorySheet(
    val mode: CategorySheetMode,
    val categoryId: Long,
    val initialName: String,
    val requestKey: String,
    val archived: Boolean = false,
) : Serializable

private data class RuleSheet(
    val editing: Boolean,
    val originalDescription: String,
    val originalCategoryId: Long,
    val requestKey: String,
) : Serializable

private sealed interface RuleConfirm : Serializable {
    data class ArchiveCategory(val categoryId: Long, val name: String) : RuleConfirm
    data class DeleteCategory(
        val categoryId: Long,
        val name: String,
        val archived: Boolean,
    ) : RuleConfirm
    data class RemoveRule(val description: String, val categoryName: String) : RuleConfirm
}

@Composable
private fun TaskTabs(
    selected: RuleTab,
    categoriesLabel: String,
    rulesLabel: String,
    onSelect: (RuleTab) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
        ) {
            TaskTab(
                label = categoriesLabel,
                selected = selected == RuleTab.CATEGORIES,
                onClick = { onSelect(RuleTab.CATEGORIES) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("category-tab-categories"),
            )
            TaskTab(
                label = rulesLabel,
                selected = selected == RuleTab.RULES,
                onClick = { onSelect(RuleTab.RULES) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("category-tab-rules"),
            )
        }
    }
}

@Composable
private fun TaskTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(4.dp)
            .heightIn(min = 48.dp)
            .background(
                color = if (selected) {
                    MaterialTheme.colorScheme.surface
                } else {
                    androidx.compose.ui.graphics.Color.Transparent
                },
                shape = RoundedCornerShape(12.dp),
            )
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 2,
            overflow = TextOverflow.Clip,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun LoadingState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(22.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ReadErrorState(
    title: String,
    body: String,
    retry: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(retry)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryContent(
    page: QueryPage<CategoryEntity>?,
    archivedCount: Int?,
    totalCount: Int,
    filter: CategoryState,
    onFilterChange: (CategoryState) -> Unit,
    pageIndex: Int,
    pageLabel: String,
    previousLabel: String,
    nextLabel: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    allFilterLabel: String,
    activeFilterLabel: String,
    archivedFilterLabel: String,
    archivedCountLabel: (Int) -> String,
    archivedBadgeLabel: String,
    onEdit: (CategoryEntity) -> Unit,
    onDelete: (CategoryEntity) -> Unit,
    emptyTitle: String,
    emptyBody: String,
    emptyFilterTitle: String,
    emptyFilterBody: String,
    activeLabel: String,
    archivedLabel: String,
    editLabel: String,
    deleteLabel: String,
    help: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = help,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CategoryFilterChip(
                label = allFilterLabel,
                selected = filter == CategoryState.ALL,
                onClick = { onFilterChange(CategoryState.ALL) },
                tag = "category-filter-all",
                accessibleLabel = allFilterLabel,
            )
            CategoryFilterChip(
                label = activeFilterLabel,
                selected = filter == CategoryState.ACTIVE,
                onClick = { onFilterChange(CategoryState.ACTIVE) },
                tag = "category-filter-active",
                accessibleLabel = activeFilterLabel,
            )
            CategoryFilterChip(
                label = archivedFilterLabel,
                selected = filter == CategoryState.ARCHIVED,
                onClick = { onFilterChange(CategoryState.ARCHIVED) },
                tag = "category-filter-archived",
                accessibleLabel = archivedFilterLabel,
            )
        }
        if (archivedCount != null && archivedCount > 0) {
            Text(
                text = archivedCountLabel(archivedCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (page != null && page.items.isEmpty()) {
            if (totalCount > 0) {
                EmptyState(title = emptyFilterTitle, body = emptyFilterBody)
            } else {
                EmptyState(title = emptyTitle, body = emptyBody)
            }
        } else {
            if (pageIndex > 0 || page?.hasMore == true) {
                PageControls(
                    pageIndex = pageIndex,
                    hasMore = page?.hasMore == true,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    previousLabel = previousLabel,
                    nextLabel = nextLabel,
                    pageLabel = pageLabel,
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(page?.items.orEmpty(), key = { it.id }) { category ->
                    CategoryRow(
                        category = category,
                        activeLabel = activeLabel,
                        archivedLabel = archivedLabel,
                        archivedBadgeLabel = archivedBadgeLabel,
                        editLabel = editLabel,
                        deleteLabel = deleteLabel,
                        onEdit = { onEdit(category) },
                        onDelete = { onDelete(category) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun CategoryFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    tag: String,
    accessibleLabel: String,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            selectedLabelColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag(tag)
            .semantics { contentDescription = accessibleLabel },
    )
}

/**
 * A category's stable colour and one neutral catalog glyph.
 *
 * The catalog stores a name and identity, not an icon, so the mark uses the category's own
 * stable colour with the shared categories glyph. Archived categories are desaturated and also
 * carry a word badge; the state is never hue-only.
 */
@Composable
private fun CategoryMark(
    categoryId: Long,
    archived: Boolean,
    size: androidx.compose.ui.unit.Dp = 40.dp,
) {
    val color = CategoryPalette.color(
        categoryId = categoryId,
        darkTheme = isSystemInDarkTheme(),
        archived = archived,
        directory = true,
    )
    Box(
        modifier = Modifier
            .size(size)
            .background(color.copy(alpha = if (archived) 0.10f else 0.16f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        AegisIcon(
            glyph = AegisGlyph.CATEGORIES,
            tint = color,
            size = size * 0.55f,
        )
    }
}

@Composable
private fun CategoryRow(
    category: CategoryEntity,
    activeLabel: String,
    archivedLabel: String,
    archivedBadgeLabel: String,
    editLabel: String,
    deleteLabel: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag("category-row-${category.id}"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CategoryMark(
                categoryId = category.id,
                archived = category.archived,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = category.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (category.archived) {
                        ArchivedCategoryBadge(label = archivedBadgeLabel)
                    }
                    Text(
                        text = if (category.archived) archivedLabel else activeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onEdit,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("category-edit-${category.id}"),
            ) {
                Text(editLabel)
            }
            TextButton(
                onClick = onDelete,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("category-delete-${category.id}"),
            ) {
                Text(deleteLabel, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun RuleContent(
    page: QueryPage<CategoryRule>?,
    pageIndex: Int,
    pageLabel: String,
    previousLabel: String,
    nextLabel: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onEdit: (CategoryRule) -> Unit,
    onRemove: (CategoryRule) -> Unit,
    emptyTitle: String,
    emptyBody: String,
    editLabel: String,
    removeLabel: String,
    help: String,
    archivedLabel: String,
    moreRules: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = help,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (page != null && page.items.isEmpty()) {
            EmptyState(title = emptyTitle, body = emptyBody)
        } else {
            if (pageIndex > 0 || page?.hasMore == true) {
                PageControls(
                    pageIndex = pageIndex,
                    hasMore = page?.hasMore == true,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    previousLabel = previousLabel,
                    nextLabel = nextLabel,
                    pageLabel = pageLabel,
                )
            }
            if (page?.hasMore == true) {
                Text(
                    text = moreRules,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(page?.items.orEmpty(), key = { it.patternKey }) { rule ->
                    RuleRow(
                        rule = rule,
                        editLabel = editLabel,
                        removeLabel = removeLabel,
                        archivedLabel = archivedLabel,
                        onEdit = { onEdit(rule) },
                        onRemove = { onRemove(rule) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun RuleRow(
    rule: CategoryRule,
    editLabel: String,
    removeLabel: String,
    archivedLabel: String,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag("rule-row-${rule.patternKey}"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CategoryMark(
                categoryId = rule.categoryId,
                archived = rule.categoryArchived,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "\"${rule.patternKey}\"",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "→ ${rule.categoryName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (rule.categoryArchived) {
                        ArchivedCategoryBadge(label = archivedLabel)
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onEdit,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("rule-edit-${rule.patternKey}"),
            ) {
                Text(editLabel)
            }
            TextButton(
                onClick = onRemove,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("rule-remove-${rule.patternKey}"),
            ) {
                Text(removeLabel, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategorySheetContent(
    sheet: CategorySheet,
    strings: CategoryRulesStrings,
    commandService: WorkflowCommandService,
    onDismiss: () -> Unit,
    onSaved: (added: Boolean, name: String) -> Unit,
    onArchiveRequest: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by rememberSaveable(sheet.requestKey) { mutableStateOf(sheet.initialName) }
    var error by remember(sheet.requestKey) { mutableStateOf<String?>(null) }
    var saving by remember(sheet.requestKey) { mutableStateOf(false) }
    val adding = sheet.mode == CategorySheetMode.ADD

    ModalBottomSheet(
        onDismissRequest = { if (!saving) onDismiss() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .testTag("category-sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (adding) strings.addCategoryTitle else strings.editCategoryTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = strings.categorySheetHelp,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    error = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("category-name"),
                label = { Text(strings.nameLabel) },
                placeholder = { Text(strings.namePlaceholder) },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { message -> { Text(message) } },
            )
            Button(
                onClick = {
                    saving = true
                    error = null
                    scope.launch {
                        val refusal = try {
                            val outcome = if (adding) {
                                commandService.addCategory(
                                    WorkflowCallerScope.User,
                                    AddCategoryRequest(
                                        idempotencyKey = sheet.requestKey,
                                        name = name,
                                    ),
                                )
                            } else {
                                commandService.renameCategory(
                                    WorkflowCallerScope.User,
                                    RenameCategoryRequest(
                                        idempotencyKey = sheet.requestKey,
                                        categoryId = sheet.categoryId,
                                        name = name,
                                    ),
                                )
                            }
                            categoryRefusal(outcome, strings)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            strings.categorySaveFailed
                        }
                        if (refusal == null) {
                            onSaved(adding, name.trim())
                        } else {
                            error = refusal
                            saving = false
                        }
                    }
                },
                enabled = !saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag("category-save"),
            ) {
                Text(if (saving) strings.saving else strings.save)
            }
            if (!adding && !sheet.archived) {
                OutlinedButton(
                    onClick = onArchiveRequest,
                    enabled = !saving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("category-archive"),
                ) {
                    Text(strings.archiveCategory)
                }
            }
            TextButton(
                onClick = onDismiss,
                enabled = !saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(strings.cancel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleSheetContent(
    sheet: RuleSheet,
    strings: CategoryRulesStrings,
    queryService: WorkflowQueryService,
    commandService: WorkflowCommandService,
    onDismiss: () -> Unit,
    onSaved: (updated: Boolean) -> Unit,
    onPartial: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var description by rememberSaveable(sheet.requestKey) {
        mutableStateOf(sheet.originalDescription)
    }
    var selectedCategoryId by rememberSaveable(sheet.requestKey) {
        mutableStateOf(sheet.originalCategoryId)
    }
    var activeCategories by remember(sheet.requestKey) {
        mutableStateOf<List<CategoryEntity>?>(null)
    }
    var categoryReadFailed by remember(sheet.requestKey) { mutableStateOf(false) }
    var error by remember(sheet.requestKey) { mutableStateOf<String?>(null) }
    var saving by remember(sheet.requestKey) { mutableStateOf(false) }

    LaunchedEffect(queryService, sheet.requestKey) {
        try {
            activeCategories = queryService.categoryCatalog().filterNot { it.archived }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            categoryReadFailed = true
        }
    }
    val originalCategory = activeCategories?.firstOrNull { it.id == sheet.originalCategoryId }
    val originalArchived = sheet.editing &&
        activeCategories != null &&
        originalCategory == null &&
        sheet.originalCategoryId != 0L

    ModalBottomSheet(
        onDismissRequest = { if (!saving) onDismiss() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .testTag("rule-sheet"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = if (sheet.editing) strings.ruleEditTitle else strings.ruleAddTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = strings.ruleSheetHelp,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = strings.exactDescriptionHelp,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = description,
                onValueChange = {
                    description = it
                    error = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("rule-description"),
                label = { Text(strings.ruleDescriptionLabel) },
                placeholder = { Text(strings.ruleDescriptionPlaceholder) },
                singleLine = true,
                isError = error == strings.ruleDescriptionRequired,
            )
            Text(
                text = strings.ruleCategoryLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                categoryReadFailed -> Text(
                    text = strings.ruleCategoryReadFailed,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                activeCategories == null -> Text(
                    text = strings.loading,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                activeCategories.orEmpty().isEmpty() -> Text(
                    text = strings.ruleNoActiveCategories,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Column(
                    modifier = Modifier.selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    activeCategories.orEmpty().forEach { category ->
                        CategoryChoiceRow(
                            category = category,
                            selected = selectedCategoryId == category.id,
                            onClick = {
                                selectedCategoryId = category.id
                                error = null
                            },
                        )
                    }
                }
            }
            if (originalArchived) {
                Text(
                    text = strings.ruleArchivedCategoryWarning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = {
                    val trimmed = description.trim()
                    val categoryAvailable = activeCategories.orEmpty()
                        .any { it.id == selectedCategoryId }
                    val localError = when {
                        trimmed.isEmpty() -> strings.ruleDescriptionRequired
                        !categoryAvailable -> strings.ruleCategoryRequired
                        else -> null
                    }
                    if (localError != null) {
                        error = localError
                        return@Button
                    }
                    saving = true
                    error = null
                    scope.launch {
                        val result = try {
                            saveRule(
                                commandService = commandService,
                                editing = sheet.editing,
                                originalDescription = sheet.originalDescription,
                                originalCategoryId = sheet.originalCategoryId,
                                description = trimmed,
                                categoryId = selectedCategoryId,
                                requestKey = sheet.requestKey,
                                strings = strings,
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            RuleSaveResult.Failed(strings.ruleSaveFailed)
                        }
                        when (result) {
                            RuleSaveResult.Saved -> onSaved(sheet.editing)
                            RuleSaveResult.Partial -> onPartial()
                            is RuleSaveResult.Failed -> {
                                error = result.message
                                if (result.categoryUnavailable) {
                                    // The sheet's cached choices are stale. Drop the unavailable
                                    // category from this sheet so the reader chooses an active
                                    // one instead of pressing Save on a refused row again.
                                    activeCategories = activeCategories
                                        ?.filterNot { it.id == selectedCategoryId }
                                    selectedCategoryId = 0L
                                }
                                saving = false
                            }
                        }
                    }
                },
                enabled = !saving &&
                    !categoryReadFailed &&
                    activeCategories.orEmpty().isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag("rule-save"),
            ) {
                Text(if (saving) strings.saving else strings.save)
            }
            TextButton(
                onClick = onDismiss,
                enabled = !saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(strings.cancel)
            }
        }
    }
}

@Composable
private fun CategoryChoiceRow(
    category: CategoryEntity,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .testTag("rule-category-${category.id}")
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CategoryMark(
            categoryId = category.id,
            archived = category.archived,
            size = 28.dp,
        )
        Text(
            text = category.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            AegisIcon(
                glyph = AegisGlyph.CHECK,
                tint = MaterialTheme.colorScheme.primary,
                size = 18.dp,
            )
        }
    }
}

@Composable
private fun ArchiveCategoryDialog(
    strings: CategoryRulesStrings,
    name: String,
    categoryId: Long,
    commandService: WorkflowCommandService,
    onDismiss: () -> Unit,
    onArchived: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(strings.archiveTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings.archiveBody)
                Text(name, style = MaterialTheme.typography.titleSmall)
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val outcome = commandService.archiveCategory(
                                WorkflowCallerScope.User,
                                ArchiveCategoryRequest(
                                    idempotencyKey = "archive-$categoryId-${UUID.randomUUID()}",
                                    categoryId = categoryId,
                                ),
                            )
                            when (outcome) {
                                is WorkflowCommandOutcome.Applied -> onArchived()
                                is WorkflowCommandOutcome.NoChange -> {
                                    // An already-archived category is the state the user asked
                                    // for; the list already shows it. Nothing else changed.
                                    onArchived()
                                }
                                else -> {
                                    error = strings.archiveFailed
                                    busy = false
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            error = strings.archiveFailed
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) strings.archiving else strings.archiveConfirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(strings.cancel) }
        },
    )
}

@Composable
private fun DeleteCategoryDialog(
    strings: CategoryRulesStrings,
    categoryId: Long,
    name: String,
    archived: Boolean,
    queryService: WorkflowQueryService,
    commandService: WorkflowCommandService,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit,
    onArchived: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var impact by remember { mutableStateOf<CategoryDeletionImpact?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var archiving by remember { mutableStateOf(false) }

    LaunchedEffect(categoryId) {
        try {
            impact = queryService.categoryDeletionImpact(categoryId)
            if (impact == null) loadError = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadError = true
        }
    }

    val loaded = impact
    val referenced = loaded != null && (
        loaded.uncategorizedTransactionCount > 0 ||
            loaded.removedRuleCount > 0 ||
            loaded.clearedPendingReviewCount > 0
        )
    AlertDialog(
        onDismissRequest = { if (!deleting && !archiving) onDismiss() },
        title = {
            Text(if (referenced) strings.deleteBlockedTitle else strings.deleteTitle)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                when {
                    loadError -> Text(
                        strings.deleteImpactError,
                        color = MaterialTheme.colorScheme.error,
                    )
                    loaded == null -> Text(strings.deleteChecking)
                    else -> {
                        Text(
                            when {
                                !referenced -> strings.deleteBodyUnreferenced
                                archived -> strings.deleteBodyReferencedArchived
                                else -> strings.deleteBodyReferenced
                            },
                        )
                        if (loaded.uncategorizedTransactionCount > 0) {
                            Text(strings.deleteRecords(loaded.uncategorizedTransactionCount))
                        }
                        if (loaded.removedRuleCount > 0) {
                            Text(strings.deleteRules(loaded.removedRuleCount))
                        }
                        if (loaded.clearedPendingReviewCount > 0) {
                            Text(strings.deleteDrafts(loaded.clearedPendingReviewCount))
                        }
                        Text(
                            text = strings.deleteTotalsKept,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (referenced && !archived) {
                            OutlinedButton(
                                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.primary,
                                ),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                ),
                                onClick = {
                                    archiving = true
                                    scope.launch {
                                        try {
                                            val outcome = commandService.archiveCategory(
                                                WorkflowCallerScope.User,
                                                ArchiveCategoryRequest(
                                                    idempotencyKey = "archive-instead-$categoryId-${UUID.randomUUID()}",
                                                    categoryId = categoryId,
                                                ),
                                            )
                                            if (outcome is WorkflowCommandOutcome.Applied ||
                                                outcome is WorkflowCommandOutcome.NoChange
                                            ) {
                                                onArchived()
                                            } else {
                                                deleteError = strings.archiveFailed
                                                archiving = false
                                            }
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            deleteError = strings.archiveFailed
                                            archiving = false
                                        }
                                    }
                                },
                                enabled = !deleting && !archiving,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp),
                            ) {
                                Text(if (archiving) strings.archiving else strings.deleteArchiveInstead)
                            }
                        }
                    }
                }
                deleteError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (referenced || loaded == null || loadError) {
                // A referenced category is not deletable, and an impact that could not be read
                // never offers a destructive action. The dialog's only action is to close.
                TextButton(onClick = onDismiss) { Text(strings.cancel) }
            } else {
                TextButton(
                    enabled = !deleting && !archiving,
                    onClick = {
                        deleting = true
                        deleteError = null
                        scope.launch {
                            try {
                                when (val outcome = commandService.deleteCategory(
                                    WorkflowCallerScope.User,
                                    DeleteCategoryRequest(
                                        idempotencyKey = "delete-$categoryId-${UUID.randomUUID()}",
                                        categoryId = categoryId,
                                    ),
                                )) {
                                    is WorkflowCommandOutcome.Applied -> onDeleted()
                                    is WorkflowCommandOutcome.Conflict -> {
                                        // A reference appeared after the impact read. The same
                                        // transaction refused the delete, so nothing moved; re-read
                                        // the impact so the dialog switches to the blocked state
                                        // (no destructive button, Archive instead).
                                        impact = queryService.categoryDeletionImpact(categoryId)
                                            ?: impact
                                        deleteError = strings.deleteBlockedNow
                                        deleting = false
                                    }
                                    else -> {
                                        deleteError = strings.deleteFailed
                                        deleting = false
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                deleteError = strings.deleteFailed
                                deleting = false
                            }
                        }
                    },
                ) {
                    Text(
                        text = if (deleting) strings.deleting else strings.deleteConfirm,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        dismissButton = if (referenced || loaded == null || loadError) {
            null
        } else {
            {
                TextButton(
                    onClick = onDismiss,
                    enabled = !deleting && !archiving,
                ) { Text(strings.cancel) }
            }
        },
    )
}

@Composable
private fun RemoveRuleDialog(
    strings: CategoryRulesStrings,
    description: String,
    categoryName: String,
    commandService: WorkflowCommandService,
    onDismiss: () -> Unit,
    onRemoved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(strings.ruleRemoveTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings.ruleRemoveBody)
                Text(
                    text = strings.ruleRowDescription(description, categoryName),
                    style = MaterialTheme.typography.titleSmall,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val outcome = commandService.removeCategoryRule(
                                WorkflowCallerScope.User,
                                RemoveCategoryRuleRequest(
                                    idempotencyKey = "remove-rule-${UUID.randomUUID()}",
                                    description = description,
                                ),
                            )
                            if (outcome is WorkflowCommandOutcome.Applied ||
                                outcome is WorkflowCommandOutcome.NoChange
                            ) {
                                onRemoved()
                            } else {
                                error = strings.ruleRemoveFailed
                                busy = false
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            error = strings.ruleRemoveFailed
                            busy = false
                        }
                    }
                },
            ) {
                Text(
                    text = if (busy) strings.ruleRemoving else strings.ruleRemoveConfirm,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(strings.cancel) }
        },
    )
}

/** The localized reason a category write was refused, or null when it was applied. */
private fun categoryRefusal(
    outcome: WorkflowCommandOutcome,
    strings: CategoryRulesStrings,
): String? = when (outcome) {
    is WorkflowCommandOutcome.Applied,
    is WorkflowCommandOutcome.NoChange,
    is WorkflowCommandOutcome.Cancelled,
    -> null
    is WorkflowCommandOutcome.NeedsClarification -> strings.nameRequired
    is WorkflowCommandOutcome.Conflict -> strings.nameTaken
    is WorkflowCommandOutcome.Denied -> when (outcome.reason) {
        WorkflowDenialReason.CATEGORY_UNAVAILABLE -> strings.categoryUnavailable
        else -> strings.categorySaveFailed
    }
    WorkflowCommandOutcome.NotFound -> strings.categoryGone
    is WorkflowCommandOutcome.Failed -> strings.categorySaveFailed
}

private sealed interface RuleSaveResult {
    data object Saved : RuleSaveResult
    data object Partial : RuleSaveResult
    data class Failed(
        val message: String,
        val categoryUnavailable: Boolean = false,
    ) : RuleSaveResult
}

/**
 * Applies one rule edit through the existing command boundary.
 *
 * A description change adds the new rule first and removes the old one only after the add
 * applied, so a refused replacement leaves the old rule in place. A category-only change must
 * remove first because the command layer never silently replaces a standing rule; if the new
 * rule is then refused, the screen says the old rule was removed instead of pretending the
 * edit succeeded.
 */
private suspend fun saveRule(
    commandService: WorkflowCommandService,
    editing: Boolean,
    originalDescription: String,
    originalCategoryId: Long,
    description: String,
    categoryId: Long,
    requestKey: String,
    strings: CategoryRulesStrings,
): RuleSaveResult {
    if (!editing) {
        val outcome = commandService.rememberCategoryRule(
            WorkflowCallerScope.User,
            RememberCategoryRuleRequest(
                idempotencyKey = "$requestKey-add",
                description = description,
                categoryId = categoryId,
            ),
        )
        return when (outcome) {
            is WorkflowCommandOutcome.Applied -> RuleSaveResult.Saved
            is WorkflowCommandOutcome.NoChange -> RuleSaveResult.Failed(strings.ruleAlreadyExists)
            is WorkflowCommandOutcome.NeedsClarification ->
                RuleSaveResult.Failed(strings.ruleDescriptionRequired)
            is WorkflowCommandOutcome.Denied -> RuleSaveResult.Failed(
                if (outcome.reason == WorkflowDenialReason.CATEGORY_UNAVAILABLE) {
                    strings.categoryUnavailable
                } else {
                    strings.ruleSaveFailed
                },
                categoryUnavailable = outcome.reason == WorkflowDenialReason.CATEGORY_UNAVAILABLE,
            )
            WorkflowCommandOutcome.NotFound -> RuleSaveResult.Failed(
                strings.categoryGone,
                categoryUnavailable = true,
            )
            is WorkflowCommandOutcome.Conflict,
            is WorkflowCommandOutcome.Failed,
            -> RuleSaveResult.Failed(strings.ruleSaveFailed)
            is WorkflowCommandOutcome.Cancelled -> RuleSaveResult.Failed(strings.ruleSaveFailed)
        }
    }

    val descriptionChanged = description != originalDescription
    val categoryChanged = categoryId != originalCategoryId
    if (!descriptionChanged && !categoryChanged) {
        // The sheet may have been open while the category was archived elsewhere. Re-ask the
        // command boundary for the standing rule instead of treating "no fields changed" as a
        // successful save: an archived category is refused and the sheet stays open.
        val outcome = commandService.rememberCategoryRule(
            WorkflowCallerScope.User,
            RememberCategoryRuleRequest(
                idempotencyKey = "$requestKey-recheck",
                description = description,
                categoryId = categoryId,
            ),
        )
        return when (outcome) {
            is WorkflowCommandOutcome.Applied,
            is WorkflowCommandOutcome.NoChange,
            -> RuleSaveResult.Saved
            is WorkflowCommandOutcome.Denied -> RuleSaveResult.Failed(
                if (outcome.reason == WorkflowDenialReason.CATEGORY_UNAVAILABLE) {
                    strings.categoryUnavailable
                } else {
                    strings.ruleSaveFailed
                },
                categoryUnavailable = outcome.reason == WorkflowDenialReason.CATEGORY_UNAVAILABLE,
            )
            WorkflowCommandOutcome.NotFound -> RuleSaveResult.Failed(
                strings.categoryGone,
                categoryUnavailable = true,
            )
            else -> RuleSaveResult.Failed(strings.ruleSaveFailed)
        }
    }

    if (descriptionChanged) {
        val added = commandService.rememberCategoryRule(
            WorkflowCallerScope.User,
            RememberCategoryRuleRequest(
                idempotencyKey = "$requestKey-add",
                description = description,
                categoryId = categoryId,
            ),
        )
        if (added !is WorkflowCommandOutcome.Applied) {
            return RuleSaveResult.Failed(
                when (added) {
                    is WorkflowCommandOutcome.NoChange -> strings.ruleAlreadyExists
                    is WorkflowCommandOutcome.NeedsClarification -> strings.ruleDescriptionRequired
                    is WorkflowCommandOutcome.Denied -> strings.categoryUnavailable
                    WorkflowCommandOutcome.NotFound -> strings.categoryGone
                    else -> strings.ruleSaveFailed
                },
                categoryUnavailable = added is WorkflowCommandOutcome.Denied ||
                    added == WorkflowCommandOutcome.NotFound,
            )
        }
        val removed = commandService.removeCategoryRule(
            WorkflowCallerScope.User,
            RemoveCategoryRuleRequest(
                idempotencyKey = "$requestKey-remove-old",
                description = originalDescription,
            ),
        )
        return if (removed is WorkflowCommandOutcome.Applied) {
            RuleSaveResult.Saved
        } else {
            RuleSaveResult.Partial
        }
    }

    // Same exact description, a different category. Removing first is required by the command
    // contract, which never replaces a standing rule under the same key.
    val removed = commandService.removeCategoryRule(
        WorkflowCallerScope.User,
        RemoveCategoryRuleRequest(
            idempotencyKey = "$requestKey-remove-old",
            description = originalDescription,
        ),
    )
    if (removed !is WorkflowCommandOutcome.Applied &&
        removed !is WorkflowCommandOutcome.NoChange
    ) {
        return RuleSaveResult.Failed(strings.ruleSaveFailed)
    }
    val added = commandService.rememberCategoryRule(
        WorkflowCallerScope.User,
        RememberCategoryRuleRequest(
            idempotencyKey = "$requestKey-add",
            description = description,
            categoryId = categoryId,
        ),
    )
    return when (added) {
        is WorkflowCommandOutcome.Applied -> RuleSaveResult.Saved
        is WorkflowCommandOutcome.NoChange -> RuleSaveResult.Failed(strings.ruleAlreadyExists)
        is WorkflowCommandOutcome.NeedsClarification ->
            RuleSaveResult.Failed(strings.ruleDescriptionRequired)
        is WorkflowCommandOutcome.Denied -> RuleSaveResult.Failed(
            strings.categoryUnavailable,
            categoryUnavailable = true,
        )
        WorkflowCommandOutcome.NotFound -> RuleSaveResult.Failed(
            strings.categoryGone,
            categoryUnavailable = true,
        )
        else -> RuleSaveResult.Partial
    }
}
