package io.github.triet4p.aegisf.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.triet4p.aegisf.MainActivity
import io.github.triet4p.aegisf.data.AegisLanguage
import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.LedgerDatabase
import io.github.triet4p.aegisf.data.TransactionEntity
import io.github.triet4p.aegisf.data.TransactionType
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.ui.theme.AegisTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import java.util.TimeZone

/**
 * The S10.3 screens at the largest supported app-scoped text scale, on the real phone.
 *
 * The screens are composed against an in-memory Room database seeded by this test, so no
 * user record, category or draft is touched. The 1.5× font scale is applied to this process
 * only: the system font-scale setting remains the owner's. The test proves the exact amounts,
 * chart labels and guarded receipt actions are still composed and reachable, and the written
 * screenshots are the rendered evidence for the matching phone walkthrough.
 */
@RunWith(AndroidJUnit4::class)
class S103BrowsingLayoutInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var database: LedgerDatabase
    private lateinit var queryService: WorkflowQueryService
    private lateinit var commandService: WorkflowCommandService
    private val zone: TimeZone = TimeZone.getDefault()
    private lateinit var monthScope: DashboardScopeState
    private var lunchId: Long = 0L

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        queryService = WorkflowQueryService(database)
        commandService = WorkflowCommandService(database = database)
        monthScope = DashboardScopeState.currentMonth(System.currentTimeMillis(), zone)

        val createdAt = System.currentTimeMillis()
        val food = runBlocking {
            database.categoryDao().insert(
                CategoryEntity(
                    name = "Food",
                    createdAtEpochMillis = createdAt,
                    updatedAtEpochMillis = createdAt,
                ),
            )
        }
        val shopping = runBlocking {
            database.categoryDao().insert(
                CategoryEntity(
                    name = "Shopping",
                    createdAtEpochMillis = createdAt,
                    updatedAtEpochMillis = createdAt,
                ),
            )
        }
        lunchId = runBlocking {
            database.transactionDao().insert(
                record(
                    type = TransactionType.EXPENSE,
                    amountVnd = 65_000L,
                    description = "Phở Thìn Bờ Hồ",
                    categoryId = food,
                    occurredAt = dayAt(1, hour = 12),
                    createdAt = createdAt,
                ),
            )
        }
        runBlocking {
            database.transactionDao().insert(
                record(
                    type = TransactionType.EXPENSE,
                    amountVnd = 899_000L,
                    description = "Uniqlo Đồng Khởi",
                    categoryId = shopping,
                    occurredAt = dayAt(1, hour = 15),
                    createdAt = createdAt,
                ),
            )
            database.transactionDao().insert(
                record(
                    type = TransactionType.INCOME,
                    amountVnd = 28_500_000L,
                    description = "Salary October",
                    categoryId = null,
                    occurredAt = dayAt(2, hour = 9),
                    createdAt = createdAt,
                ),
            )
            database.transactionDao().insert(
                record(
                    type = TransactionType.TRANSFER,
                    amountVnd = 5_000_000L,
                    description = "Move to savings",
                    categoryId = null,
                    occurredAt = dayAt(2, hour = 18),
                    createdAt = createdAt,
                ),
            )
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun analyticsShowsExactFiguresAndChartLabelsAtOneAndAHalfTextScale() {
        compose.activity.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = base.density, fontScale = 1.5f),
            ) {
                AegisTheme(
                    themeMode = io.github.triet4p.aegisf.data.AegisThemeMode.LIGHT,
                    language = AegisLanguage.ENGLISH,
                ) {
                    AnalyticsScreen(
                        queryService = queryService,
                        scope = monthScope,
                        onScopeChange = {},
                        onOpenRecords = { _, _ -> },
                        onAddRecord = {},
                        onNavigateUp = {},
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("964,000 ₫", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        assertTrue(exists("+28,500,000 ₫"))
        assertTrue(exists("+27,536,000 ₫"))
        assertTrue(exists("5,000,000 ₫"))
        assertTrue(exists("Food"))
        assertTrue(exists("Shopping"))
        assertTrue(
            compose.onAllNodesWithContentDescription("Previous month")
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )
        writeScreenshot(
            "analytics-1.5x",
            compose.onRoot().captureToImage().asAndroidBitmap(),
        )
    }

    @Test
    fun ledgerSearchNarrowsTheSameReadAtOneAndAHalfTextScale() {
        compose.activity.setContent {
            val base = LocalDensity.current
            var filters by remember { mutableStateOf(TransactionFilterState()) }
            CompositionLocalProvider(
                LocalDensity provides Density(density = base.density, fontScale = 1.5f),
            ) {
                AegisTheme(
                    themeMode = io.github.triet4p.aegisf.data.AegisThemeMode.LIGHT,
                    language = AegisLanguage.ENGLISH,
                ) {
                    LedgerScreen(
                        queryService = queryService,
                        filters = filters,
                        onFiltersChange = { filters = it },
                        filtersExpanded = false,
                        onFiltersExpandedChange = {},
                        onSelect = {},
                        onCreate = {},
                        onNavigateUp = {},
                        showBack = false,
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 10_000) {
            exists("Move to savings") && exists("Salary October")
        }

        compose.onNode(hasSetTextAction()).performTextInput("uniqlo")
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Uniqlo Đồng Khởi").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("Move to savings")
                    .fetchSemanticsNodes()
                    .isEmpty()
        }
        assertTrue(exists("-899,000 ₫", substring = false))
        writeScreenshot(
            "ledger-search-1.5x",
            compose.onRoot().captureToImage().asAndroidBitmap(),
        )
    }

    @Test
    fun receiptShowsProvenanceAndGuardedActionsAtOneAndAHalfTextScale() {
        compose.activity.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = base.density, fontScale = 1.5f),
            ) {
                AegisTheme(
                    themeMode = io.github.triet4p.aegisf.data.AegisThemeMode.LIGHT,
                    language = AegisLanguage.ENGLISH,
                ) {
                    TransactionDetailScreen(
                        commandService = commandService,
                        queryService = queryService,
                        transactionId = lunchId,
                        onEdit = {},
                        onDeleted = {},
                        onNavigateUp = {},
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("-65,000 ₫", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        assertTrue(exists("Phở Thìn Bờ Hồ"))
        assertTrue(exists("Food"))
        assertTrue(exists("Manual"))
        assertTrue(exists("Confirmed"))

        val minimumPx = 48 * compose.activity.resources.displayMetrics.density
        listOf("Edit transaction", "Delete transaction").forEach { label ->
            // The action is the clickable button, not its inner text node: at 1.5x the label
            // text can be a hair under 48dp while the button it belongs to is not.
            val node = compose.onAllNodes(hasText(label) and hasClickAction())
                .fetchSemanticsNodes()
                .firstOrNull()
            assertTrue("action '$label' is missing at 1.5x", node != null)
            val bounds = node!!.boundsInRoot
            assertTrue(
                "'$label' is smaller than 48dp: ${bounds.width}x${bounds.height}px",
                bounds.width >= minimumPx && bounds.height >= minimumPx,
            )
        }

        compose.onNode(hasText("Delete transaction") and hasClickAction()).performClick()
        compose.waitForIdle()
        assertTrue(exists("Delete this transaction?"))
        // Dismissing changes nothing, and the receipt is still there.
        compose.onNode(hasText("Cancel") and hasClickAction()).performClick()
        compose.waitForIdle()
        assertTrue(exists("-65,000 ₫", substring = true))
        writeScreenshot(
            "receipt-1.5x",
            compose.onRoot().captureToImage().asAndroidBitmap(),
        )
    }

    @Test
    fun ledgerPaginationWalksTheRestOfTheSameRead() {
        runBlocking {
            repeat(501) { index ->
                database.transactionDao().insert(
                    record(
                        type = TransactionType.EXPENSE,
                        amountVnd = 1_000L,
                        description = "S103 filler $index",
                        categoryId = null,
                        occurredAt = dayAt(0, hour = 0) + index * 60_000L,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
        compose.activity.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = base.density, fontScale = 1.5f),
            ) {
                AegisTheme(
                    themeMode = io.github.triet4p.aegisf.data.AegisThemeMode.LIGHT,
                    language = AegisLanguage.ENGLISH,
                ) {
                    LedgerScreen(
                        queryService = queryService,
                        filters = TransactionFilterState(),
                        onFiltersChange = {},
                        filtersExpanded = false,
                        onFiltersExpandedChange = {},
                        onSelect = {},
                        onCreate = {},
                        onNavigateUp = {},
                        showBack = false,
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.waitUntil(timeoutMillis = 10_000) {
            exists("Page 1") && exists("Move to savings")
        }
        compose.onNode(hasText("Next") and hasClickAction()).performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            exists("Page 2") && exists("S103 filler 4")
        }
        assertTrue(
            compose.onAllNodesWithText("S103 filler 500").fetchSemanticsNodes().isEmpty(),
        )
        writeScreenshot(
            "ledger-page-2-1.5x",
            compose.onRoot().captureToImage().asAndroidBitmap(),
        )
    }

    @Test
    fun analyticsEmptyPeriodIsTruthful() {
        compose.activity.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = base.density, fontScale = 1.5f),
            ) {
                AegisTheme(
                    themeMode = io.github.triet4p.aegisf.data.AegisThemeMode.LIGHT,
                    language = AegisLanguage.ENGLISH,
                ) {
                    AnalyticsScreen(
                        queryService = queryService,
                        scope = monthScope.adjacentMonth(-1, zone),
                        onScopeChange = {},
                        onOpenRecords = { _, _ -> },
                        onAddRecord = {},
                        onNavigateUp = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 10_000) {
            exists("No confirmed records in this period")
        }
        assertTrue(exists("Add record"))
        writeScreenshot(
            "analytics-empty-1.5x",
            compose.onRoot().captureToImage().asAndroidBitmap(),
        )
    }

    @Test
    fun receiptRefusesAStaleDeleteAndReloadsTheStoredRow() {
        compose.activity.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = base.density, fontScale = 1.5f),
            ) {
                AegisTheme(
                    themeMode = io.github.triet4p.aegisf.data.AegisThemeMode.LIGHT,
                    language = AegisLanguage.ENGLISH,
                ) {
                    TransactionDetailScreen(
                        commandService = commandService,
                        queryService = queryService,
                        transactionId = lunchId,
                        onEdit = {},
                        onDeleted = {},
                        onNavigateUp = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 10_000) {
            exists("-65,000 ₫", substring = true)
        }

        // The row moves on under the open receipt: a correction bumped its revision and
        // amount. Deleting against the revision the screen read must be refused, and the
        // receipt must reload what is actually stored.
        database.openHelper.writableDatabase.execSQL(
            "UPDATE transactions SET amountVnd = 66000, revision = revision + 1 " +
                "WHERE id = $lunchId",
        )
        compose.onNode(hasText("Delete transaction") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNode(hasText("Delete") and hasClickAction()).performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            exists("This record changed elsewhere. Reload it before deleting.")
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            exists("-66,000 ₫", substring = true)
        }
        writeScreenshot(
            "receipt-stale-1.5x",
            compose.onRoot().captureToImage().asAndroidBitmap(),
        )
    }

    @Test
    fun analyticsReadErrorShowsRecoveryAtTheUiBoundary() {
        val failing = failingDatabase()
        try {
            val failingQuery = WorkflowQueryService(failing)
            compose.activity.setContent {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density = base.density, fontScale = 1.5f),
                ) {
                    AegisTheme(
                        themeMode = io.github.triet4p.aegisf.data.AegisThemeMode.LIGHT,
                        language = AegisLanguage.ENGLISH,
                    ) {
                        AnalyticsScreen(
                            queryService = failingQuery,
                            scope = monthScope,
                            onScopeChange = {},
                            onOpenRecords = { _, _ -> },
                            onAddRecord = {},
                            onNavigateUp = {},
                        )
                    }
                }
            }
            compose.waitForIdle()
            compose.waitUntil(timeoutMillis = 10_000) {
                exists("The stored records could not be read. Nothing was changed.")
            }
            assertTrue(exists("Try again"))
            writeScreenshot(
                "analytics-read-error-1.5x",
                compose.onRoot().captureToImage().asAndroidBitmap(),
            )
        } finally {
            failing.close()
        }
    }

    @Test
    fun receiptReadErrorShowsRecoveryAtTheUiBoundary() {
        val failing = failingDatabase()
        try {
            compose.activity.setContent {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density = base.density, fontScale = 1.5f),
                ) {
                    AegisTheme(
                        themeMode = io.github.triet4p.aegisf.data.AegisThemeMode.LIGHT,
                        language = AegisLanguage.ENGLISH,
                    ) {
                        TransactionDetailScreen(
                            commandService = WorkflowCommandService(database = failing),
                            queryService = WorkflowQueryService(failing),
                            transactionId = 1L,
                            onEdit = {},
                            onDeleted = {},
                            onNavigateUp = {},
                        )
                    }
                }
            }
            compose.waitForIdle()
            compose.waitUntil(timeoutMillis = 10_000) {
                exists("Couldn't read this transaction")
            }
            assertTrue(exists("The stored record could not be read. Nothing was changed."))
            assertTrue(exists("Try again"))
            writeScreenshot(
                "receipt-read-error-1.5x",
                compose.onRoot().captureToImage().asAndroidBitmap(),
            )
        } finally {
            failing.close()
        }
    }

    private fun exists(text: String, substring: Boolean = false): Boolean =
        compose.onAllNodesWithText(text, substring = substring)
            .fetchSemanticsNodes()
            .isNotEmpty()

    private fun record(
        type: TransactionType,
        amountVnd: Long,
        description: String,
        categoryId: Long?,
        occurredAt: Long,
        createdAt: Long,
    ): TransactionEntity = TransactionEntity(
        type = type,
        amountVnd = amountVnd,
        occurredAtEpochMillis = occurredAt,
        description = description,
        categoryId = categoryId,
        createdAtEpochMillis = createdAt,
        updatedAtEpochMillis = createdAt,
    )

    /** A moment on day [offsetDays] of the current month at [hour], in the device zone. */
    private fun dayAt(offsetDays: Int, hour: Int): Long {
        val start = ManualDateTime.dayStartEpochMillis(monthScope.fromText, zone)
        return Calendar.getInstance(zone).apply {
            timeInMillis = start
            add(Calendar.DATE, offsetDays)
            set(Calendar.HOUR_OF_DAY, hour)
        }.timeInMillis
    }

    /**
     * A separate in-memory database whose ledger table is gone, so the real query fails at
     * the UI boundary. No production database and no user row is involved.
     */
    private fun failingDatabase(): LedgerDatabase {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database.openHelper.writableDatabase.execSQL("DROP TABLE IF EXISTS transactions")
        return database
    }

    private fun writeScreenshot(name: String, bitmap: Bitmap) {
        val directory = InstrumentationRegistry.getInstrumentation()
            .targetContext.getExternalFilesDir("s103")
            ?.apply { mkdirs() }
            ?: return
        FileOutputStream(File(directory, "$name.png")).use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }
}
