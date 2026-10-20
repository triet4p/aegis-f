package io.github.triet4p.aegisf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.triet4p.aegisf.data.CategoryCatalogCounts
import io.github.triet4p.aegisf.data.CategoryDeletionImpact
import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.CategoryQuery
import io.github.triet4p.aegisf.data.CategoryState
import io.github.triet4p.aegisf.data.LIST_PAGE_SIZE
import io.github.triet4p.aegisf.data.LedgerRepository
import io.github.triet4p.aegisf.data.LedgerValidationException
import io.github.triet4p.aegisf.data.QueryPage
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.WorkflowQueryService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Category management: add, rename, archive, and permanently delete.
 *
 * The list is backed by the shared category read, a live Room stream, so
 * every write the user makes appears without a manual refresh. Every rule that protects
 * stored data — a category needs a name, an active name is unique without regard to
 * case, archiving keeps historical records attached — is enforced by the repository and
 * surfaced here as an explicit refusal, never as a silent no-op or a lost write.
 *
 * Delete and archive are deliberately separate actions with separate wording, and neither
 * pretends to be the other. Archive keeps the category for the records already filed under
 * it. Delete removes the row for good: the confirmation names what that will change (records
 * that become uncategorized, remembered rules that go with the category, drafts whose
 * pre-set category is cleared) from counts read just before it, and cancelling changes
 * nothing at all. A failed delete keeps the category and reports the reason instead of
 * looking like a success.
 *
 * Archived categories stay listed with a marker: the user must still be able to see and
 * rename them, because their past records remain filed under them, and they can be deleted
 * like any other. There is no cap on how many categories can exist: the list reads the
 * catalog page by page and says which page it shows, so every stored category can be
 * renamed, archived, and deleted, not only the ones on the first page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryManagerScreen(
    repository: LedgerRepository,
    queryService: WorkflowQueryService,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The page of the catalog the list is showing. A page is an offset into the same
    // bounded read, so every stored category is reachable, however many there are.
    var pageIndex by rememberSaveable { mutableIntStateOf(0) }
    // Hoisted so the collected flow has a stable identity: building it on every
    // recomposition would restart the collection (and blank the list) each time.
    val categoryQuery = remember(pageIndex) {
        CategoryQuery(
            state = CategoryState.ALL,
            bounds = QueryPageRequest(LIST_PAGE_SIZE, offset = pageIndex * LIST_PAGE_SIZE),
        )
    }
    val categoryFlow = remember(queryService, categoryQuery) {
        queryService.observeCategories(categoryQuery)
    }
    // Null until the first emission: it distinguishes "loading" from "nothing stored".
    val categoryPage: QueryPage<CategoryEntity>? by
        categoryFlow.collectAsState(initial = null)
    val categories: List<CategoryEntity>? = categoryPage?.items
    // How many categories exist, and how many of them are archived: the counts describe
    // the whole catalog, so they stay right whichever page the list is showing.
    val catalogCounts: CategoryCatalogCounts? by remember(queryService) {
        queryService.observeCategoryCatalogCounts()
    }.collectAsState(initial = null)
    // A page that lost its categories steps back to the nearest page that still holds
    // some, so the list never shows an empty page in the middle of the catalog.
    ClampPageToRows(
        pageIndex = pageIndex,
        hasRows = categoryPage?.items?.isNotEmpty(),
        onClamp = { pageIndex = it },
    )

    var dialog: CategoryDialogState? by rememberSaveable { mutableStateOf(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    BackHandler(onBack = onNavigateUp)

    LaunchedEffect(statusMessage) {
        val message = statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        statusMessage = null
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Categories") },
                navigationIcon = {
                    TextButton(onClick = onNavigateUp) { Text("Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { dialog = CategoryDialogState(CategoryDialogMode.ADD) },
            ) {
                Text("Add category")
            }
        },
    ) { innerPadding ->
        when (val loaded = categories) {
            null -> LoadingCategories(Modifier.padding(innerPadding))
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                // The rows after this page are one page further on in the same read: the
                // list says which page it shows and how to walk the rest, so every stored
                // category can be managed, not only the ones on the first page. The
                // controls sit above the rows, so they are reachable without walking
                // past a whole page of them first.
                PageControls(
                    pageIndex = pageIndex,
                    hasMore = categoryPage?.hasMore == true,
                    onPrevious = { pageIndex -= 1 },
                    onNext = { pageIndex += 1 },
                )
                CategoryList(
                    categories = loaded,
                    contentPadding = PaddingValues(0.dp),
                    totalCount = catalogCounts?.total ?: loaded.size,
                    archivedCount = catalogCounts?.archived ?: loaded.count { it.archived },
                    catalogIsEmpty = catalogCounts?.total == 0,
                    onRename = { category ->
                        dialog = CategoryDialogState(
                            mode = CategoryDialogMode.RENAME,
                            categoryId = category.id,
                            categoryName = category.name,
                        )
                    },
                    onArchive = { category ->
                        dialog = CategoryDialogState(
                            mode = CategoryDialogMode.ARCHIVE,
                            categoryId = category.id,
                            categoryName = category.name,
                        )
                    },
                    onDelete = { category ->
                        dialog = CategoryDialogState(
                            mode = CategoryDialogMode.DELETE,
                            categoryId = category.id,
                            categoryName = category.name,
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
                // The rows after this page are one page further on in the same read: the
                // list says which page it shows and how to walk the rest, so every stored
                // category can be managed, not only the first ones.
                PageControls(
                    pageIndex = pageIndex,
                    hasMore = categoryPage?.hasMore == true,
                    onPrevious = { pageIndex -= 1 },
                    onNext = { pageIndex += 1 },
                )
            }
        }
    }

    dialog?.let { state ->
        when (state.mode) {
            CategoryDialogMode.ADD, CategoryDialogMode.RENAME -> CategoryNameDialog(
                key = state.mode to state.categoryId,
                title = if (state.mode == CategoryDialogMode.ADD) {
                    "New category"
                } else {
                    "Rename category"
                },
                initialName = state.categoryName,
                onDismiss = { dialog = null },
                save = { name ->
                    when (state.mode) {
                        CategoryDialogMode.ADD -> repository.addCategory(name)
                        CategoryDialogMode.RENAME ->
                            repository.renameCategory(state.categoryId, name)
                        CategoryDialogMode.ARCHIVE -> Unit
                        CategoryDialogMode.DELETE -> Unit
                    }
                },
                onSaved = { trimmedName ->
                    dialog = null
                    statusMessage = if (state.mode == CategoryDialogMode.ADD) {
                        "Added \"$trimmedName\"."
                    } else {
                        "Renamed to \"$trimmedName\"."
                    }
                },
            )

            CategoryDialogMode.ARCHIVE -> ArchiveCategoryDialog(
                categoryName = state.categoryName,
                onDismiss = { dialog = null },
                onArchive = {
                    try {
                        repository.archiveCategory(state.categoryId)
                        "Archived \"${state.categoryName}\"."
                    } catch (rejection: LedgerValidationException) {
                        rejection.message ?: "The category could not be archived."
                    }
                },
                onArchived = { message ->
                    dialog = null
                    statusMessage = message
                },
            )

            CategoryDialogMode.DELETE -> DeleteCategoryDialog(
                categoryId = state.categoryId,
                categoryName = state.categoryName,
                onDismiss = { dialog = null },
                loadImpact = repository::categoryDeletionImpact,
                delete = repository::deleteCategory,
                onDeleted = { message ->
                    dialog = null
                    statusMessage = message
                },
            )
        }
    }
}

/** Which editing flow a dialog is running. */
private enum class CategoryDialogMode { ADD, RENAME, ARCHIVE, DELETE }

/**
 * The open dialog plus the row it acts on. Held in primitives so the selection survives
 * a configuration change.
 */
private data class CategoryDialogState(
    val mode: CategoryDialogMode,
    val categoryId: Long = 0L,
    val categoryName: String = "",
) : java.io.Serializable

@Composable
private fun CategoryList(
    categories: List<CategoryEntity>,
    contentPadding: PaddingValues,
    totalCount: Int,
    archivedCount: Int,
    catalogIsEmpty: Boolean,
    onRename: (CategoryEntity) -> Unit,
    onArchive: (CategoryEntity) -> Unit,
    onDelete: (CategoryEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (catalogIsEmpty) {
        EmptyCategories(Modifier.padding(contentPadding))
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item(key = "summary") {
            Text(
                text = summaryLabel(totalCount, archivedCount),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 8.dp),
            )
        }
        items(categories, key = { it.id }) { category ->
            CategoryRow(
                category = category,
                onRename = { onRename(category) },
                onArchive = { onArchive(category) },
                onDelete = { onDelete(category) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun CategoryRow(
    category: CategoryEntity,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = category.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (category.archived) {
                Text(
                    text = "Archived",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = onRename) { Text("Rename") }
        TextButton(onClick = onArchive) { Text("Archive") }
        // Kept visually apart from Archive: this one cannot be undone.
        TextButton(onClick = onDelete) {
            Text("Delete", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun LoadingCategories(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyCategories(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "No categories yet",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "Add a category to group transactions, for example Food, Rent, or Transport.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Add / rename dialog. A refused write keeps the dialog open with the typed name intact
 * and the repository's reason shown under the field, so nothing is stored and the user
 * can correct the entry.
 */
@Composable
private fun CategoryNameDialog(
    key: Pair<CategoryDialogMode, Long>,
    title: String,
    initialName: String,
    onDismiss: () -> Unit,
    save: suspend (String) -> Unit,
    onSaved: (String) -> Unit,
) {
    var name by rememberSaveable(key) { mutableStateOf(initialName) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var saving by remember(key) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    error = null
                },
                label = { Text("Category name") },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { message -> { Text(message) } },
            )
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        try {
                            save(name)
                            onSaved(name.trim())
                        } catch (rejection: LedgerValidationException) {
                            error = rejection.message
                            saving = false
                        }
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
        },
    )
}

/** Archive confirmation. [onArchive] performs the write and reports what happened. */
@Composable
private fun ArchiveCategoryDialog(
    categoryName: String,
    onDismiss: () -> Unit,
    onArchive: suspend () -> String,
    onArchived: (String) -> Unit,
) {
    var archiving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!archiving) onDismiss() },
        title = { Text("Archive \"$categoryName\"") },
        text = {
            Text(
                text = "An archived category stays attached to past records, so their " +
                    "history is kept, but it can no longer be selected for new ones.",
            )
        },
        confirmButton = {
            TextButton(
                enabled = !archiving,
                onClick = {
                    archiving = true
                    scope.launch {
                        val message = onArchive()
                        onDismiss()
                        onArchived(message)
                    }
                },
            ) { Text("Archive category") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !archiving) { Text("Cancel") }
        },
    )
}

/**
 * Permanent-delete confirmation. The impact counts are read from the repository when the
 * dialog opens, so the wording names what this specific delete will change instead of a
 * generic warning; [delete] performs the write and the counts it returns are what the
 * status message reports.
 *
 * Nothing is written until **Delete permanently** is pressed, and cancelling writes nothing.
 * A refused or failed delete keeps the dialog (and the category) in place and shows the
 * reason, so a failure can never look like a success.
 */
@Composable
private fun DeleteCategoryDialog(
    categoryId: Long,
    categoryName: String,
    onDismiss: () -> Unit,
    loadImpact: suspend (Long) -> CategoryDeletionImpact,
    delete: suspend (Long) -> CategoryDeletionImpact,
    onDeleted: (String) -> Unit,
) {
    var impact by remember(categoryId) { mutableStateOf<CategoryDeletionImpact?>(null) }
    var loadError by remember(categoryId) { mutableStateOf<String?>(null) }
    var deleteError by remember(categoryId) { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(categoryId) {
        try {
            impact = loadImpact(categoryId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejection: LedgerValidationException) {
            loadError = rejection.message ?: "The category is no longer stored."
        } catch (_: Exception) {
            // The button stays disabled while the impact is unknown: no delete is offered on
            // effects that could not be read, and nothing was changed.
            loadError = "What deleting this category would change could not be read, so no " +
                "delete is offered. Nothing was changed."
        }
    }

    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        title = { Text("Delete \"$categoryName\" permanently?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val loaded = impact
                when {
                    loadError != null -> Text(
                        text = loadError.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                    )
                    loaded == null -> Text("Checking what this category is attached to…")
                    else -> Text(deletionConsequences(categoryName, loaded))
                }
                deleteError?.let { message ->
                    Text(text = message, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                // No deletion before the impact is known: the confirmation must not promise
                // effects it has not read.
                enabled = impact != null && !deleting,
                onClick = {
                    deleting = true
                    deleteError = null
                    scope.launch {
                        try {
                            val applied = delete(categoryId)
                            onDeleted(deletionSummary(categoryName, applied))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (rejection: LedgerValidationException) {
                            deleteError = rejection.message
                                ?: "The category could not be deleted; it is still stored."
                            deleting = false
                        } catch (_: Exception) {
                            deleteError = "The category could not be deleted. The delete is " +
                                "one database transaction, so nothing was changed."
                            deleting = false
                        }
                    }
                },
            ) { Text(if (deleting) "Deleting…" else "Delete permanently") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !deleting) { Text("Cancel") }
        },
    )
}

/**
 * What this delete will do, spelled out from the counts read immediately before it. It is
 * deliberately explicit about what is *not* deleted, because the point of the action is that
 * records, amounts, and totals survive it.
 */
private fun deletionConsequences(categoryName: String, impact: CategoryDeletionImpact): String =
    buildString {
        append("This cannot be undone. \"")
        append(categoryName)
        append("\" is removed for good, and no placeholder keeps its name or id. ")
        if (impact.uncategorizedTransactionCount == 0) {
            append("No saved transaction is filed under it. ")
        } else {
            append(impact.uncategorizedTransactionCount)
            append(" saved transaction")
            if (impact.uncategorizedTransactionCount == 1) append(" stays") else append("s stay")
            append(
                " in your ledger with the same amount, date, and description, and " +
                    "becomes uncategorized. ",
            )
        }
        if (impact.removedRuleCount == 0) {
            append("No remembered rule uses it. ")
        } else {
            append(impact.removedRuleCount)
            append(" remembered rule")
            if (impact.removedRuleCount == 1) append(" is") else append("s are")
            append(" removed with it. ")
        }
        if (impact.clearedPendingReviewCount > 0) {
            append(impact.clearedPendingReviewCount)
            append(" draft")
            if (impact.clearedPendingReviewCount == 1) {
                append(" awaiting review was")
            } else {
                append("s awaiting review were")
            }
            append(" pre-set to it; that choice is cleared and must be made again. ")
        }
        append("Monthly totals do not change. Archive instead keeps the category for past records.")
    }

/** What the delete that just ran actually changed, reported to the user verbatim. */
private fun deletionSummary(categoryName: String, applied: CategoryDeletionImpact): String {
    val changes = buildList {
        if (applied.uncategorizedTransactionCount > 0) {
            add(
                "${countLabel(applied.uncategorizedTransactionCount, "transaction is", "transactions are")} " +
                    "now uncategorized",
            )
        }
        if (applied.removedRuleCount > 0) {
            add(
                "${countLabel(applied.removedRuleCount, "remembered rule was", "remembered rules were")} " +
                    "removed",
            )
        }
        if (applied.clearedPendingReviewCount > 0) {
            add(
                "${countLabel(applied.clearedPendingReviewCount, "pending draft needs", "pending drafts need")} " +
                    "its category chosen again",
            )
        }
    }
    val headline = "Deleted \"$categoryName\" permanently."
    return if (changes.isEmpty()) headline else "$headline ${changes.joinToString("; ")}."
}

private fun countLabel(count: Int, singular: String, plural: String): String =
    if (count == 1) "1 $singular" else "$count $plural"

private fun summaryLabel(total: Int, archived: Int): String {
    val categories = if (total == 1) "1 category" else "$total categories"
    return if (archived > 0) "$categories, $archived archived" else categories
}
