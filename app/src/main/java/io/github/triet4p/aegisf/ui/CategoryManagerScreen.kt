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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.LedgerRepository
import io.github.triet4p.aegisf.data.LedgerValidationException
import kotlinx.coroutines.launch

/**
 * Category management: add, rename, archive.
 *
 * The list is backed by [LedgerRepository.observeCategories], a live Room stream, so
 * every write the user makes appears without a manual refresh. Every rule that protects
 * stored data — a category needs a name, an active name is unique without regard to
 * case, archiving keeps historical records attached — is enforced by the repository and
 * surfaced here as an explicit refusal, never as a silent no-op or a lost write.
 *
 * Archived categories stay listed with a marker: the user must still be able to see and
 * rename them, because their past records remain filed under them. There is no cap on
 * how many categories can exist; the list scrolls through all of them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryManagerScreen(
    repository: LedgerRepository,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Hoisted so the collected flow has a stable identity: building it on every
    // recomposition would restart the collection (and blank the list) each time.
    val categoryFlow = remember(repository) { repository.observeCategories() }
    // Null until the first emission: it distinguishes "loading" from "nothing stored".
    val categories: List<CategoryEntity>? by
        categoryFlow.collectAsState(initial = null)

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
            else -> CategoryList(
                categories = loaded,
                contentPadding = innerPadding,
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
            )
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
        }
    }
}

/** Which editing flow a dialog is running. */
private enum class CategoryDialogMode { ADD, RENAME, ARCHIVE }

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
    onRename: (CategoryEntity) -> Unit,
    onArchive: (CategoryEntity) -> Unit,
) {
    if (categories.isEmpty()) {
        EmptyCategories(Modifier.padding(contentPadding))
        return
    }

    val archivedCount = categories.count { it.archived }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item(key = "summary") {
            Text(
                text = summaryLabel(categories.size, archivedCount),
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

private fun summaryLabel(total: Int, archived: Int): String {
    val categories = if (total == 1) "1 category" else "$total categories"
    return if (archived > 0) "$categories, $archived archived" else categories
}
