package io.github.triet4p.aegisf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.triet4p.aegisf.data.BankDraftReviewRepository
import io.github.triet4p.aegisf.data.IntakeRepository
import io.github.triet4p.aegisf.data.LedgerRepository
import io.github.triet4p.aegisf.data.NotificationAccessStore
import io.github.triet4p.aegisf.ui.BankDraftReviewScreen
import io.github.triet4p.aegisf.ui.BankNotificationSettingsScreen
import io.github.triet4p.aegisf.ui.CategoryManagerScreen
import io.github.triet4p.aegisf.ui.CurrentMonthExpenseSummary
import io.github.triet4p.aegisf.ui.TransactionDetailScreen
import io.github.triet4p.aegisf.ui.TransactionEditorScreen
import io.github.triet4p.aegisf.ui.TransactionFilterState
import io.github.triet4p.aegisf.ui.TransactionListScreen
/** Which screen the launcher is showing; the home screen itself is the default. */
private sealed interface LaunchDestination : java.io.Serializable {
    data object Home : LaunchDestination

    data object Categories : LaunchDestination

    data object Transactions : LaunchDestination
    data object NewTransaction : LaunchDestination
    data object BankNotifications : LaunchDestination

    data object BankDraftInbox : LaunchDestination

    data class BankDraftDetail(val draftId: Long) : LaunchDestination

    /**
     * The record-bearing destinations carry the stored id, never a loaded row: the screen
     * reads the record it shows out of the database, so it is correct after a process restart
     * too, and the current destination survives a configuration change.
     */
    data class Detail(val transactionId: Long) : LaunchDestination

    data class Edit(val transactionId: Long) : LaunchDestination
}

class MainActivity : ComponentActivity() {

    private val ledgerRepository: LedgerRepository by lazy {
        (application as AegisApplication).container.ledgerRepository
    }

    private val notificationAccessStore: NotificationAccessStore by lazy {
        (application as AegisApplication).container.notificationAccessStore
    }

    private val intakeRepository: IntakeRepository by lazy {
        (application as AegisApplication).container.intakeRepository
    }

    private val bankDraftReviewRepository: BankDraftReviewRepository by lazy {
        (application as AegisApplication).container.bankDraftReviewRepository
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AegisTheme {
                var destination by rememberSaveable {
                    mutableStateOf<LaunchDestination>(LaunchDestination.Home)
                }
                // The browsing filters live with the navigation state — above the list
                // screen — so opening a record's detail view or its editor and coming back
                // keeps them as they were. They are saveable primitives, not ledger data.
                var transactionFilters by rememberSaveable {
                    mutableStateOf(TransactionFilterState())
                }
                // The filter panel's expansion is held the same way, for the same reason: a
                // configuration change and the way back from a record's detail view or editor
                // must leave the list looking as it was left, collapsed on first entry.
                var transactionFiltersExpanded by rememberSaveable {
                    mutableStateOf(false)
                }
                when (val current = destination) {
                    LaunchDestination.Home -> LaunchScreen(
                        repository = ledgerRepository,
                        onManageCategories = { destination = LaunchDestination.Categories },
                        onRecordTransaction = { destination = LaunchDestination.NewTransaction },
                        onBrowseTransactions = { destination = LaunchDestination.Transactions },
                        onBankNotifications = { destination = LaunchDestination.BankNotifications },
                        onReviewBankDrafts = { destination = LaunchDestination.BankDraftInbox },
                    )

                    LaunchDestination.BankNotifications -> BankNotificationSettingsScreen(
                        accessStore = notificationAccessStore,
                        onNavigateUp = { destination = LaunchDestination.Home },
                    )

                    LaunchDestination.BankDraftInbox -> BankDraftReviewScreen(
                        intakeRepository = intakeRepository,
                        reviewRepository = bankDraftReviewRepository,
                        ledgerRepository = ledgerRepository,
                        draftId = null,
                        onSelectDraft = { id -> destination = LaunchDestination.BankDraftDetail(id) },
                        onNavigateUp = { destination = LaunchDestination.Home },
                    )

                    is LaunchDestination.BankDraftDetail -> BankDraftReviewScreen(
                        intakeRepository = intakeRepository,
                        reviewRepository = bankDraftReviewRepository,
                        ledgerRepository = ledgerRepository,
                        draftId = current.draftId,
                        onSelectDraft = { id -> destination = LaunchDestination.BankDraftDetail(id) },
                        onNavigateUp = { destination = LaunchDestination.BankDraftInbox },
                    )

                    LaunchDestination.Categories -> CategoryManagerScreen(
                        repository = ledgerRepository,
                        onNavigateUp = { destination = LaunchDestination.Home },
                    )

                    LaunchDestination.Transactions -> TransactionListScreen(
                        repository = ledgerRepository,
                        filters = transactionFilters,
                        onFiltersChange = { transactionFilters = it },
                        filtersExpanded = transactionFiltersExpanded,
                        onFiltersExpandedChange = { transactionFiltersExpanded = it },
                        onSelect = { recordId -> destination = LaunchDestination.Detail(recordId) },
                        onNavigateUp = { destination = LaunchDestination.Home },
                    )

                    LaunchDestination.NewTransaction -> TransactionEditorScreen(
                        repository = ledgerRepository,
                        transactionId = null,
                        onNavigateUp = { destination = LaunchDestination.Home },
                    )

                    is LaunchDestination.Detail -> TransactionDetailScreen(
                        repository = ledgerRepository,
                        transactionId = current.transactionId,
                        onEdit = { destination = LaunchDestination.Edit(current.transactionId) },
                        onDeleted = { destination = LaunchDestination.Transactions },
                        onNavigateUp = { destination = LaunchDestination.Transactions },
                    )

                    is LaunchDestination.Edit -> TransactionEditorScreen(
                        repository = ledgerRepository,
                        transactionId = current.transactionId,
                        // A saved correction and a cancelled one both lead back to the detail
                        // view, which re-reads the stored row: only a save has changed it.
                        onNavigateUp = {
                            destination = LaunchDestination.Detail(current.transactionId)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AegisTheme(content: @Composable () -> Unit) {
    val colorScheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFF9BD8BD),
            onPrimary = Color(0xFF00382C),
            primaryContainer = Color(0xFF15513F),
            onPrimaryContainer = Color(0xFFD0F0DD),
            background = Color(0xFF101512),
            onBackground = Color(0xFFE1E8E3),
            surface = Color(0xFF101512),
            onSurface = Color(0xFFE1E8E3),
            surfaceVariant = Color(0xFF3A4A41),
            onSurfaceVariant = Color(0xFFBDCCC3),
            outline = Color(0xFF87988F),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF14624F),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFD5EADF),
            onPrimaryContainer = Color(0xFF092B21),
            background = Color(0xFFF7F8F5),
            onBackground = Color(0xFF18201B),
            surface = Color(0xFFF7F8F5),
            onSurface = Color(0xFF18201B),
            surfaceVariant = Color(0xFFE1E9E3),
            onSurfaceVariant = Color(0xFF46564D),
            outline = Color(0xFF707F76),
        )
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}

/**
 * The home screen: the current month's confirmed spending, then the ways into recording,
 * browsing, reviewing bank drafts, and categorising.
 *
 * The summary reads the ledger itself through [repository], so it appears before any other
 * action is taken and is refreshed by the same live stream every other screen uses. Nothing
 * on this screen writes to the ledger.
 */
@Composable
private fun LaunchScreen(
    repository: LedgerRepository,
    onManageCategories: () -> Unit,
    onRecordTransaction: () -> Unit,
    onBrowseTransactions: () -> Unit,
    onBankNotifications: () -> Unit,
    onReviewBankDrafts: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Aegis F",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "Personal finance, with clarity.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // The month's own figure, above every action: it is what the user came to check,
            // and it counts the whole ledger rather than anything a browsing filter selects.
            CurrentMonthExpenseSummary(repository = repository)

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = "CURRENCY CONTEXT",
                        style = MaterialTheme.typography.labelLarge,
                        letterSpacing = 1.2.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = "Vietnamese dong",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "VND",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = "₫",
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "TRANSACTIONS",
                        style = MaterialTheme.typography.labelLarge,
                        letterSpacing = 1.2.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Record what you spend, earn, and move between your own " +
                            "accounts, in exact whole dong. Open a saved record to correct " +
                            "or delete it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onRecordTransaction,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Record a transaction")
                    }
                    OutlinedButton(
                        onClick = onBrowseTransactions,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Saved transactions")
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "BANK NOTIFICATIONS",
                        style = MaterialTheme.typography.labelLarge,
                        letterSpacing = 1.2.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Optionally let notifications from your bank apps become " +
                            "reviewable drafts. Manual recording works with or without it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = onReviewBankDrafts,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Review bank drafts")
                    }
                    OutlinedButton(
                        onClick = onBankNotifications,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Bank notifications")
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "CATEGORIES",
                        style = MaterialTheme.typography.labelLarge,
                        letterSpacing = 1.2.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Add, rename, and archive the categories your transactions are " +
                            "filed under.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onManageCategories,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Manage categories")
                    }
                }
            }
            Text(
                text = "An uncluttered starting point for your financial life.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
