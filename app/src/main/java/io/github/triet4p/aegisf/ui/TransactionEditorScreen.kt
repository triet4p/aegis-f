package io.github.triet4p.aegisf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.LedgerRepository
import io.github.triet4p.aegisf.data.LedgerValidationException
import io.github.triet4p.aegisf.data.TransactionCorrection
import io.github.triet4p.aegisf.data.TransactionEntity
import io.github.triet4p.aegisf.data.TransactionInput
import io.github.triet4p.aegisf.data.TransactionType
import io.github.triet4p.aegisf.data.VndMoney
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manual transaction editor: one stored record per Save.
 *
 * With [transactionId] null this is the creation form: the fields start empty, a Save writes
 * one confirmed manual record through [LedgerRepository.addTransaction], and the form is
 * replaced by a confirmation panel that reads the persisted row back. With a stored id it is
 * the correction form: the amount, type, moment, description, and category are read from the
 * database and prefilled, and a Save replaces exactly those fields on the same row through
 * [LedgerRepository.editTransaction] before the screen returns to the detail view, which shows
 * the saved values. The record keeps its id, its creation time, its confirmation, and its
 * provenance, because a correction cannot write those.
 *
 * The form collects the user-supplied fields — amount, type, date, time, description, and
 * category where applicable — and writes them through the production [LedgerRepository],
 * which owns every data rule. A refused write stores nothing and shows the repository's (or
 * the field grammar's) reason under the field it belongs to, with the typed values intact.
 * The one-shot category list comes from [LedgerRepository.assignableCategories], so an
 * archived category is never newly offered; a record that is already filed under an archived
 * category keeps it, and the field says so rather than silently dropping the link.
 *
 * While a write is in flight the guard ignores further taps and the form cannot be left, so an
 * impatient double tap cannot turn one record into two or into none. Leaving without pressing
 * Save — Back, Cancel, or the system back gesture — writes nothing at all.
 */
@Composable
fun TransactionEditorScreen(
    repository: LedgerRepository,
    transactionId: Long?,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (transactionId == null) {
        TransactionFormScreen(
            repository = repository,
            storedRecord = null,
            storedCategory = null,
            onNavigateUp = onNavigateUp,
            modifier = modifier,
        )
        return
    }

    // The stored row is re-read here rather than handed in by the caller: what fills the form
    // is what the database holds, so a stale caller cannot put another record's values in it.
    var loaded by remember(transactionId) {
        mutableStateOf<StoredTransactionState>(StoredTransactionState.Loading)
    }
    LaunchedEffect(transactionId) {
        val record = repository.findTransaction(transactionId)
        loaded = if (record == null) {
            StoredTransactionState.Missing
        } else {
            StoredTransactionState.Found(
                record = record,
                category = record.categoryId?.let { repository.findCategory(it) },
            )
        }
    }

    when (val stored = loaded) {
        StoredTransactionState.Loading -> LoadingTransaction(onNavigateUp, modifier)

        StoredTransactionState.Missing -> MissingTransaction(onNavigateUp, modifier)

        is StoredTransactionState.Found -> TransactionFormScreen(
            repository = repository,
            storedRecord = stored.record,
            storedCategory = stored.category,
            onNavigateUp = onNavigateUp,
            modifier = modifier,
        )
    }
}

/** What the editor knows about the record it was asked to correct. */
private sealed interface StoredTransactionState {
    data object Loading : StoredTransactionState

    data object Missing : StoredTransactionState

    data class Found(val record: TransactionEntity, val category: CategoryEntity?) :
        StoredTransactionState
}

/**
 * The form of one record: the creation flow when [storedRecord] is null, the correction flow
 * otherwise, when [storedCategory] is the category the stored row already carries.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionFormScreen(
    repository: LedgerRepository,
    storedRecord: TransactionEntity?,
    storedCategory: CategoryEntity?,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val editing = storedRecord != null
    val openedAtMillis = remember { System.currentTimeMillis() }
    val initialMomentMillis = storedRecord?.occurredAtEpochMillis ?: openedAtMillis
    var amountText by rememberSaveable {
        mutableStateOf(storedRecord?.amountVnd?.toString() ?: "")
    }
    var selectedType by rememberSaveable {
        mutableStateOf(storedRecord?.type ?: TransactionType.EXPENSE)
    }
    var dateText by rememberSaveable {
        mutableStateOf(ManualDateTime.dateFieldText(initialMomentMillis))
    }
    var timeText by rememberSaveable {
        mutableStateOf(ManualDateTime.timeFieldText(initialMomentMillis))
    }
    var descriptionText by rememberSaveable { mutableStateOf(storedRecord?.description ?: "") }
    var selectedCategoryId by rememberSaveable { mutableStateOf(storedRecord?.categoryId) }
    var selectedCategoryName by rememberSaveable { mutableStateOf(storedCategory?.name) }
    var categoryPickerVisible by rememberSaveable { mutableStateOf(false) }

    var amountError by remember { mutableStateOf<String?>(null) }
    var dateError by remember { mutableStateOf<String?>(null) }
    var timeError by remember { mutableStateOf<String?>(null) }
    var categoryError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var savedRecordId by rememberSaveable { mutableStateOf<Long?>(null) }
    var savedRecord by remember { mutableStateOf<TransactionEntity?>(null) }

    // One snapshot of the assignable categories per screen: the form never mutates
    // categories, and the repository still refuses an archived or unknown category at
    // write time, so the list only decides what is offered.
    var assignableCategories by remember { mutableStateOf<List<CategoryEntity>?>(null) }
    LaunchedEffect(repository) {
        assignableCategories = repository.assignableCategories()
    }

    // Kept as an id so the confirmation survives a configuration change: the record is
    // re-read from storage instead of being held in unsaveable state.
    LaunchedEffect(savedRecordId) {
        val id = savedRecordId
        savedRecord = if (id == null) null else repository.findTransaction(id)
    }

    val scope = rememberCoroutineScope()
    val writeInFlight = remember { AtomicBoolean(false) }

    BackHandler { if (!saving) onNavigateUp() }

    // The stored category keeps its archived marker; a category chosen from the picker is
    // shown by the name that picker offered, and "No category" is the absence of a name.
    val categoryButtonLabel = when {
        selectedCategoryId == null -> null
        storedCategory != null && selectedCategoryId == storedCategory.id ->
            categoryLabel(storedCategory)

        else -> selectedCategoryName
    }
    val categoryNote = storedCategory
        ?.takeIf { it.archived && selectedCategoryId == it.id }
        ?.let {
            "\"${it.name}\" is archived. This record keeps it unless you choose another " +
                "category."
        }

    fun save() {
        amountError = null
        dateError = null
        timeError = null
        categoryError = null

        val amountVnd = try {
            VndMoney.parseManualAmount(amountText)
        } catch (rejection: LedgerValidationException) {
            amountError = rejection.message
            writeInFlight.set(false)
            return
        }
        val dateProblem = ManualDateTime.dateError(dateText)
        val timeProblem = ManualDateTime.timeError(timeText)
        if (dateProblem != null || timeProblem != null) {
            dateError = dateProblem
            timeError = timeProblem
            writeInFlight.set(false)
            return
        }

        saving = true
        scope.launch {
            try {
                val type = selectedType
                val occurredAtEpochMillis = ManualDateTime.toEpochMillis(dateText, timeText)
                val description = descriptionText.trim()
                // A transfer carries no category, so the type decides whether the chosen one
                // is written at all; the stored category is otherwise kept as it is.
                val categoryId = selectedCategoryId.takeIf { type != TransactionType.TRANSFER }
                val storedId = storedRecord?.id
                if (storedId == null) {
                    savedRecordId = repository.addTransaction(
                        TransactionInput(
                            type = type,
                            amountVnd = amountVnd,
                            occurredAtEpochMillis = occurredAtEpochMillis,
                            description = description,
                            categoryId = categoryId,
                        ),
                    )
                } else {
                    repository.editTransaction(
                        storedId,
                        TransactionCorrection(
                            type = type,
                            amountVnd = amountVnd,
                            occurredAtEpochMillis = occurredAtEpochMillis,
                            description = description,
                            categoryId = categoryId,
                        ),
                    )
                    // The corrected values are now the detail view's to show: it reads the row
                    // back out of storage instead of this form reporting its own inputs.
                    onNavigateUp()
                }
            } catch (rejection: LedgerValidationException) {
                // The amount grammar was already checked, so a refusal at write time can only
                // come from the chosen category: it was archived or removed meanwhile.
                val categoryChosen = selectedCategoryId != null &&
                    selectedType != TransactionType.TRANSFER
                if (categoryChosen) {
                    categoryError = rejection.message
                } else {
                    amountError = rejection.message
                }
            } finally {
                saving = false
                writeInFlight.set(false)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            EditorTopBar(
                title = if (editing) "Edit transaction" else "New transaction",
                onNavigateUp = onNavigateUp,
                backEnabled = !saving,
            )
        },
    ) { innerPadding ->
        val saved = savedRecord
        if (saved != null) {
            SavedTransactionPanel(
                record = saved,
                categoryName = selectedCategoryName,
                onRecordAnother = {
                    // A further record is prefilled with the current moment, not the moment
                    // this screen was first opened.
                    val nowMillis = System.currentTimeMillis()
                    amountText = ""
                    selectedType = TransactionType.EXPENSE
                    dateText = ManualDateTime.dateFieldText(nowMillis)
                    timeText = ManualDateTime.timeFieldText(nowMillis)
                    descriptionText = ""
                    selectedCategoryId = null
                    selectedCategoryName = null
                    savedRecordId = null
                    savedRecord = null
                },
                onDone = onNavigateUp,
                modifier = Modifier.padding(innerPadding),
            )
        } else {
            TransactionForm(
                amountText = amountText,
                onAmountChange = { amountText = it; amountError = null },
                amountError = amountError,
                selectedType = selectedType,
                onTypeChange = {
                    selectedType = it
                    categoryError = null
                },
                dateText = dateText,
                onDateChange = { dateText = it; dateError = null },
                dateError = dateError,
                timeText = timeText,
                onTimeChange = { timeText = it; timeError = null },
                timeError = timeError,
                descriptionText = descriptionText,
                onDescriptionChange = { descriptionText = it },
                categoryName = categoryButtonLabel,
                categoryNote = categoryNote,
                categoryError = categoryError,
                onPickCategory = { categoryPickerVisible = true },
                saving = saving,
                editing = editing,
                // The guard is checked before the coroutine starts, so two taps inside one
                // frame still produce a single write; the button state only makes it visible.
                onSave = { if (writeInFlight.compareAndSet(false, true)) save() },
                onCancel = onNavigateUp,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }

    if (categoryPickerVisible) {
        CategoryPickerDialog(
            categories = assignableCategories,
            retainedCategory = storedCategory?.takeIf { it.archived },
            selectedCategoryId = selectedCategoryId,
            onSelect = { id, name ->
                selectedCategoryId = id
                selectedCategoryName = name
                categoryError = null
                categoryPickerVisible = false
            },
            onDismiss = { categoryPickerVisible = false },
        )
    }
}

/** The top bar of every editor state: one title and one Back control. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorTopBar(
    title: String,
    onNavigateUp: () -> Unit,
    backEnabled: Boolean = true,
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            TextButton(
                onClick = onNavigateUp,
                enabled = backEnabled,
            ) { Text("Back") }
        },
    )
}

/** The moment between asking for a stored record and having it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoadingTransaction(onNavigateUp: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onNavigateUp)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { EditorTopBar("Transaction", onNavigateUp) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            Text(
                text = "Loading transaction…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A record that was asked for but is no longer in the ledger. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MissingTransaction(onNavigateUp: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onNavigateUp)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { EditorTopBar("Transaction", onNavigateUp) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "This transaction no longer exists",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "It is not in the ledger, so there is nothing to show or change here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The fields of one manual record, empty for creation and prefilled for a correction. */
@Composable
private fun TransactionForm(
    amountText: String,
    onAmountChange: (String) -> Unit,
    amountError: String?,
    selectedType: TransactionType,
    onTypeChange: (TransactionType) -> Unit,
    dateText: String,
    onDateChange: (String) -> Unit,
    dateError: String?,
    timeText: String,
    onTimeChange: (String) -> Unit,
    timeError: String?,
    descriptionText: String,
    onDescriptionChange: (String) -> Unit,
    categoryName: String?,
    categoryNote: String?,
    categoryError: String?,
    onPickCategory: () -> Unit,
    saving: Boolean,
    editing: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = if (editing) {
                "Change what this record holds in exact whole dong. Nothing is stored until " +
                    "you save, and leaving now changes nothing."
            } else {
                "Record what happened in exact whole dong. Nothing is stored until you save."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val amountPreview = try {
            VndMoney.formatAmount(VndMoney.parseManualAmount(amountText))
        } catch (rejection: LedgerValidationException) {
            null
        }
        OutlinedTextField(
            value = amountText,
            onValueChange = onAmountChange,
            label = { Text("Amount (VND)") },
            placeholder = { Text("45000") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = amountError != null,
            supportingText = amountError?.let { message -> { Text(message) } }
                ?: amountPreview?.let { formatted -> { Text("Saves as $formatted") } },
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = "Type",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TransactionTypePicker(
            selectedType = selectedType,
            onTypeChange = onTypeChange,
        )

        OutlinedTextField(
            value = dateText,
            onValueChange = onDateChange,
            label = { Text("Date") },
            placeholder = { Text("2026-10-15") },
            singleLine = true,
            isError = dateError != null,
            supportingText = dateError?.let { message -> { Text(message) } },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = timeText,
            onValueChange = onTimeChange,
            label = { Text("Time") },
            placeholder = { Text("18:30") },
            singleLine = true,
            isError = timeError != null,
            supportingText = timeError?.let { message -> { Text(message) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "Dates and times use your device's time zone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = descriptionText,
            onValueChange = onDescriptionChange,
            label = { Text("Description (optional)") },
            placeholder = { Text("Lunch") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (selectedType == TransactionType.TRANSFER) {
            Text(
                text = "An internal transfer moves money between your own accounts, so it " +
                    "carries no category.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            OutlinedButton(
                onClick = onPickCategory,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(categoryName ?: "Select category (optional)")
            }
            if (categoryNote != null) {
                Text(
                    text = categoryNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (categoryError != null) {
                Text(
                    text = categoryError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Button(
            onClick = onSave,
            enabled = !saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = when {
                    saving -> "Saving…"
                    editing -> "Save changes"
                    else -> "Save transaction"
                },
            )
        }
        OutlinedButton(
            onClick = onCancel,
            enabled = !saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Cancel")
        }
    }
}

/**
 * Expense / Income / Internal transfer: the selected type of one record.
 *
 * All three segments share a single content-driven height: the row measures the tallest
 * intrinsic label — on a narrow phone "Internal transfer" already fills two lines — and
 * every segment fills that height, so the selected checkmark, the borders and the touch
 * targets stay aligned at the top and the bottom instead of the two-line segment sticking
 * out of a row of shorter ones.
 */
@Composable
internal fun TransactionTypePicker(
    selectedType: TransactionType,
    onTypeChange: (TransactionType) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(
        modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min),
    ) {
        TransactionType.entries.forEachIndexed { index, type ->
            SegmentedButton(
                selected = type == selectedType,
                onClick = { onTypeChange(type) },
                modifier = Modifier.fillMaxHeight(),
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = TransactionType.entries.size,
                ),
                label = { Text(typeLabel(type)) },
            )
        }
    }
}

/**
 * The stored record after a successful save, read back from the database rather than from
 * the form, so the visible success is the persisted row's own values.
 */
@Composable
private fun SavedTransactionPanel(
    record: TransactionEntity,
    categoryName: String?,
    onRecordAnother: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Transaction saved",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = VndMoney.formatAmount(record.amountVnd),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        DetailRow("Type", typeLabel(record.type))
        DetailRow(
            label = "When",
            value = ManualDateTime.formatMoment(record.occurredAtEpochMillis) + " device time",
        )
        if (record.description.isNotEmpty()) {
            DetailRow("Description", record.description)
        }
        DetailRow("Category", categoryName ?: "No category")
        DetailRow("Status", statusLabel(record.confirmation))
        DetailRow("Source", sourceLabel(record.source))
        DetailRow("Recorded at", ManualDateTime.formatMoment(record.createdAtEpochMillis))
        Text(
            text = "This is the stored record. Open Saved transactions from the home screen " +
                "to view, correct, or delete it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onRecordAnother,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Record another")
        }
        OutlinedButton(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Done")
        }
    }
}

/**
 * Single-choice category picker; "No category" keeps a manual record saveable.
 *
 * [retainedCategory] is the archived category the record being corrected already carries. It
 * is listed — marked archived — so the current filing is visible and can be kept, while no
 * other archived category is offered for a new assignment.
 */
@Composable
private fun CategoryPickerDialog(
    categories: List<CategoryEntity>?,
    retainedCategory: CategoryEntity?,
    selectedCategoryId: Long?,
    onSelect: (Long?, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Category") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (categories == null) {
                    Text(
                        text = "Loading categories…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    CategoryOption(
                        text = "No category",
                        selected = selectedCategoryId == null,
                        onSelect = { onSelect(null, null) },
                    )
                    if (retainedCategory != null) {
                        CategoryOption(
                            text = categoryLabel(retainedCategory),
                            selected = retainedCategory.id == selectedCategoryId,
                            onSelect = {
                                onSelect(retainedCategory.id, retainedCategory.name)
                            },
                        )
                    }
                    categories.forEach { category ->
                        CategoryOption(
                            text = category.name,
                            selected = category.id == selectedCategoryId,
                            onSelect = { onSelect(category.id, category.name) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

@Composable
private fun CategoryOption(text: String, selected: Boolean, onSelect: () -> Unit) {
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
