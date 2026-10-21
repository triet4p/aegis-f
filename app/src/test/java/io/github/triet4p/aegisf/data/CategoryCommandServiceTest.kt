package io.github.triet4p.aegisf.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.triet4p.aegisf.notification.PermittedNotificationContent
import io.github.triet4p.aegisf.ui.ManualDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/**
 * The behavior of the category and saved-rule commands.
 *
 * The category catalog and the saved rules are the user's own decisions, so these
 * commands run against the real Room file: an applied change, a refused request, a
 * conflict, and a recorded operation are observed the way the app observes them after a
 * restart.
 *
 * The properties pinned here are the ones the screens depend on: a name that another
 * active category carries is refused, an archived category leaves the assignable pool
 * while the records filed under it stay, a removal keeps every record and its totals
 * and reports what it cleared, a saved rule names one exact description and one active
 * category, and a repeated request under one key changes nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CategoryCommandServiceTest {

    private val zone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(LedgerDatabase.NAME)
        WorkflowClaimGuard.reset()
    }

    private fun open(): LedgerDatabase = LedgerDatabase.build(context)

    private fun commands(database: LedgerDatabase): WorkflowCommandService = WorkflowCommandService(
        database = database,
        timezoneId = { zone.id },
        engine = object : ClassificationEngine {
            override suspend fun classify(
                context: io.github.triet4p.aegisf.data.laya.LayaEffectiveContext,
                categories: List<CategoryEntity>,
            ): ClassificationEngineResult = ClassificationEngineResult.Failed("not used here")
        },
    )

    @Test
    fun `an added category is stored once and a repeated request adds no second one`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val applied = commands.addCategory(
                scope = WorkflowCallerScope.User,
                request = AddCategoryRequest(idempotencyKey = "add-food", name = "Food"),
            )
            assertTrue(applied is WorkflowCommandOutcome.Applied)
            val change = (applied as WorkflowCommandOutcome.Applied).change
            assertTrue(change is WorkflowChange.CategoryAdded)
            val categoryId = (change as WorkflowChange.CategoryAdded).categoryId
            assertEquals("Food", database.categoryDao().findById(categoryId)?.name)

            val repeated = commands.addCategory(
                scope = WorkflowCallerScope.User,
                request = AddCategoryRequest(idempotencyKey = "add-food", name = "Food"),
            )
            assertTrue("a repeated request changes nothing", repeated is WorkflowCommandOutcome.NoChange)
            assertEquals(1, database.categoryDao().findAll().size)

            val otherName = commands.addCategory(
                scope = WorkflowCallerScope.User,
                request = AddCategoryRequest(idempotencyKey = "add-housing", name = "Housing"),
            )
            assertTrue(otherName is WorkflowCommandOutcome.Applied)
            assertEquals(2, database.categoryDao().findAll().size)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a name another active category carries is refused and stores nothing`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            commands.addCategory(
                scope = WorkflowCallerScope.User,
                request = AddCategoryRequest(idempotencyKey = "add-food", name = "Food"),
            )
            val refused = commands.addCategory(
                scope = WorkflowCallerScope.User,
                request = AddCategoryRequest(idempotencyKey = "add-food-again", name = "food"),
            )
            assertTrue("the same active name is refused", refused is WorkflowCommandOutcome.Conflict)
            assertEquals(1, database.categoryDao().findAll().size)

            val blank = commands.addCategory(
                scope = WorkflowCallerScope.User,
                request = AddCategoryRequest(idempotencyKey = "add-blank", name = "   "),
            )
            assertTrue(
                "an empty name asks the caller to state one",
                blank is WorkflowCommandOutcome.NeedsClarification,
            )
            assertEquals(1, database.categoryDao().findAll().size)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a rename keeps the id and the records filed under the category`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val added = commands.addCategory(
                scope = WorkflowCallerScope.User,
                request = AddCategoryRequest(idempotencyKey = "add-food", name = "Food"),
            ) as WorkflowCommandOutcome.Applied
            val categoryId = (added.change as WorkflowChange.CategoryAdded).categoryId
            storeRecord(database, categoryId)

            val renamed = commands.renameCategory(
                scope = WorkflowCallerScope.User,
                request = RenameCategoryRequest(
                    idempotencyKey = "rename-food",
                    categoryId = categoryId,
                    name = "Eating out",
                ),
            )
            assertTrue(renamed is WorkflowCommandOutcome.Applied)
            assertEquals("Eating out", database.categoryDao().findById(categoryId)?.name)
            assertEquals(
                "the record keeps the link to the same category",
                categoryId,
                database.transactionDao().findAll().single().categoryId,
            )
            assertEquals(
                "the record keeps its own values",
                45_000L,
                database.transactionDao().findAll().single().amountVnd,
            )

            val missing = commands.renameCategory(
                scope = WorkflowCallerScope.User,
                request = RenameCategoryRequest(
                    idempotencyKey = "rename-missing",
                    categoryId = 999L,
                    name = "Nothing",
                ),
            )
            assertTrue(missing is WorkflowCommandOutcome.NotFound)
        } finally {
            database.close()
        }
    }

    @Test
    fun `an archived category leaves the assignable pool and keeps the records filed under it`() =
        runTest {
            val database = open()
            try {
                val commands = commands(database)
                val added = commands.addCategory(
                    scope = WorkflowCallerScope.User,
                    request = AddCategoryRequest(idempotencyKey = "add-food", name = "Food"),
                ) as WorkflowCommandOutcome.Applied
                val categoryId = (added.change as WorkflowChange.CategoryAdded).categoryId
                storeRecord(database, categoryId)

                val archived = commands.archiveCategory(
                    scope = WorkflowCallerScope.User,
                    request = ArchiveCategoryRequest(
                        idempotencyKey = "archive-food",
                        categoryId = categoryId,
                    ),
                )
                assertTrue(archived is WorkflowCommandOutcome.Applied)
                assertTrue(database.categoryDao().findById(categoryId)!!.archived)
                assertEquals(
                    "the record keeps its category under an archived one",
                    categoryId,
                    database.transactionDao().findAll().single().categoryId,
                )
                assertEquals(
                    "an archived category is no longer assignable",
                    emptyList<CategoryEntity>(),
                    WorkflowQueryService(database)
                        .categories(
                            CategoryQuery(state = CategoryState.ACTIVE, bounds = QueryPageRequest(10)),
                        )
                        .items,
                )

                val again = commands.archiveCategory(
                    scope = WorkflowCallerScope.User,
                    request = ArchiveCategoryRequest(
                        idempotencyKey = "archive-food-2",
                        categoryId = categoryId,
                    ),
                )
                assertTrue(
                    "archiving an archived category changes nothing",
                    again is WorkflowCommandOutcome.NoChange,
                )
            } finally {
                database.close()
            }
        }

    @Test
    fun `a removal keeps every record and its totals and reports what it cleared`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val added = commands.addCategory(
                scope = WorkflowCallerScope.User,
                request = AddCategoryRequest(idempotencyKey = "add-food", name = "Food"),
            ) as WorkflowCommandOutcome.Applied
            val categoryId = (added.change as WorkflowChange.CategoryAdded).categoryId
            storeRecord(database, categoryId)
            commands.rememberCategoryRule(
                scope = WorkflowCallerScope.User,
                request = RememberCategoryRuleRequest(
                    idempotencyKey = "remember-food",
                    description = "Synthetic cafe",
                    categoryId = categoryId,
                ),
            )
            val draftId = storeDraft(database, categoryId)

            val impact = WorkflowQueryService(database).categoryDeletionImpact(categoryId)
            assertNotNull(impact)
            assertEquals(1, impact!!.uncategorizedTransactionCount)
            assertEquals(1, impact.removedRuleCount)
            assertEquals(1, impact.clearedPendingReviewCount)

            val removed = commands.deleteCategory(
                scope = WorkflowCallerScope.User,
                request = DeleteCategoryRequest(idempotencyKey = "delete-food", categoryId = categoryId),
            )
            assertTrue(removed is WorkflowCommandOutcome.Applied)
            val change = (removed as WorkflowCommandOutcome.Applied).change
            assertTrue(change is WorkflowChange.CategoryDeleted)
            val applied = (change as WorkflowChange.CategoryDeleted).impact
            assertEquals(1, applied.uncategorizedTransactionCount)
            assertEquals(1, applied.removedRuleCount)
            assertEquals(1, applied.clearedPendingReviewCount)

            assertNull(database.categoryDao().findById(categoryId))
            val record = database.transactionDao().findAll().single()
            assertEquals(
                "the record survives the removal and only loses the link",
                null,
                record.categoryId,
            )
            assertEquals(45_000L, record.amountVnd)
            assertEquals("Synthetic cafe", record.description)
            assertEquals(
                "the totals are untouched",
                45_000L,
                WorkflowQueryService(database).confirmedLedgerAggregate(monthRange()).spending.totalVnd,
            )
            assertTrue(
                "the rule that named the category is gone with it",
                database.rememberedCategoryRuleDao().findAll().isEmpty(),
            )
            assertEquals(
                "the pending draft keeps its own state and loses only the pre-set category",
                null,
                database.pendingBankDraftDao().findById(draftId)?.reviewCategoryId,
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun `a saved rule names one exact description and one active category`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val added = commands.addCategory(
                scope = WorkflowCallerScope.User,
                request = AddCategoryRequest(idempotencyKey = "add-food", name = "Food"),
            ) as WorkflowCommandOutcome.Applied
            val categoryId = (added.change as WorkflowChange.CategoryAdded).categoryId

            val remembered = commands.rememberCategoryRule(
                scope = WorkflowCallerScope.User,
                request = RememberCategoryRuleRequest(
                    idempotencyKey = "remember-cafe",
                    description = "Synthetic cafe",
                    categoryId = categoryId,
                ),
            )
            assertTrue(remembered is WorkflowCommandOutcome.Applied)
            assertEquals(
                categoryId,
                WorkflowQueryService(database).findRememberedCategory("synthetic  cafe")?.id,
            )

            val repeated = commands.rememberCategoryRule(
                scope = WorkflowCallerScope.User,
                request = RememberCategoryRuleRequest(
                    idempotencyKey = "remember-cafe-2",
                    description = "Synthetic cafe",
                    categoryId = categoryId,
                ),
            )
            assertTrue(
                "a rule that already stands changes nothing",
                repeated is WorkflowCommandOutcome.NoChange,
            )
            assertEquals(1, database.rememberedCategoryRuleDao().findAll().size)

            val archived = commands.archiveCategory(
                scope = WorkflowCallerScope.User,
                request = ArchiveCategoryRequest(
                    idempotencyKey = "archive-food",
                    categoryId = categoryId,
                ),
            )
            assertTrue(archived is WorkflowCommandOutcome.Applied)
            assertNull(
                "a rule whose category is archived no longer answers",
                WorkflowQueryService(database).findRememberedCategory("Synthetic cafe"),
            )

            val removed = commands.removeCategoryRule(
                scope = WorkflowCallerScope.User,
                request = RemoveCategoryRuleRequest(
                    idempotencyKey = "remove-cafe",
                    description = "Synthetic cafe",
                ),
            )
            assertTrue(removed is WorkflowCommandOutcome.Applied)
            assertTrue(database.rememberedCategoryRuleDao().findAll().isEmpty())

            val nothing = commands.removeCategoryRule(
                scope = WorkflowCallerScope.User,
                request = RemoveCategoryRuleRequest(
                    idempotencyKey = "remove-cafe-2",
                    description = "Synthetic cafe",
                ),
            )
            assertTrue(
                "removing a rule that is not there changes nothing",
                nothing is WorkflowCommandOutcome.NoChange,
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun `another caller may not change the catalog or the saved rules`() = runTest {
        val database = open()
        try {
            val commands = commands(database)
            val added = commands.addCategory(
                scope = WorkflowCallerScope.User,
                request = AddCategoryRequest(idempotencyKey = "add-food", name = "Food"),
            ) as WorkflowCommandOutcome.Applied
            val categoryId = (added.change as WorkflowChange.CategoryAdded).categoryId
            val agent = WorkflowCallerScope.AgentSession(
                scopeId = "session-1",
                captureIds = emptySet(),
                draftIds = emptySet(),
            )
            val refusals = listOf(
                commands.addCategory(
                    scope = agent,
                    request = AddCategoryRequest(idempotencyKey = "agent-add", name = "Housing"),
                ),
                commands.renameCategory(
                    scope = agent,
                    request = RenameCategoryRequest(
                        idempotencyKey = "agent-rename",
                        categoryId = categoryId,
                        name = "Eating out",
                    ),
                ),
                commands.archiveCategory(
                    scope = agent,
                    request = ArchiveCategoryRequest(
                        idempotencyKey = "agent-archive",
                        categoryId = categoryId,
                    ),
                ),
                commands.deleteCategory(
                    scope = agent,
                    request = DeleteCategoryRequest(
                        idempotencyKey = "agent-delete",
                        categoryId = categoryId,
                    ),
                ),
                commands.rememberCategoryRule(
                    scope = agent,
                    request = RememberCategoryRuleRequest(
                        idempotencyKey = "agent-remember",
                        description = "Synthetic cafe",
                        categoryId = categoryId,
                    ),
                ),
                commands.removeCategoryRule(
                    scope = agent,
                    request = RemoveCategoryRuleRequest(
                        idempotencyKey = "agent-remove",
                        description = "Synthetic cafe",
                    ),
                ),
            )
            for (refusal in refusals) {
                assertTrue(
                    "the user's own decisions are refused for another caller",
                    refusal is WorkflowCommandOutcome.Denied,
                )
                assertEquals(
                    WorkflowDenialReason.USER_DECISION_REQUIRED,
                    (refusal as WorkflowCommandOutcome.Denied).reason,
                )
            }
            assertEquals(1, database.categoryDao().findAll().size)
            assertFalse(database.categoryDao().findById(categoryId)!!.archived)
            assertTrue(database.rememberedCategoryRuleDao().findAll().isEmpty())
        } finally {
            database.close()
        }
    }

    // --- Fixtures ---------------------------------------------------------------

    /** Stores one confirmed record filed under the given category. */
    private suspend fun storeRecord(database: LedgerDatabase, categoryId: Long) {
        val moment = ManualDateTime.toEpochMillis("2026-10-15", "12:00", zone)
        database.transactionDao().insert(
            TransactionEntity(
                type = TransactionType.EXPENSE,
                amountVnd = 45_000L,
                occurredAtEpochMillis = moment,
                description = "Synthetic cafe",
                categoryId = categoryId,
                confirmation = ConfirmationStatus.CONFIRMED,
                source = RecordSource.MANUAL,
                createdAtEpochMillis = moment,
                updatedAtEpochMillis = moment,
            ),
        )
    }

    /** Stores one pending bank draft whose review is pre-set to the given category. */
    private suspend fun storeDraft(database: LedgerDatabase, categoryId: Long): Long {
        val postedAt = ManualDateTime.toEpochMillis("2026-10-15", "12:00", zone)
        val intake = IntakeRepository(database)
        val draftId = intake.storePermitted(
            sourcePackage = "com.example.bank",
            notificationKey = "key-1",
            postedAtEpochMillis = postedAt,
            content = PermittedNotificationContent(
                title = "Synthetic bank",
                text = "GD: 45,000 dong",
                bigText = null,
            ),
        )
        database.bankDraftReviewDao().saveReview(
            id = draftId,
            expectedRevision = 0,
            edits = BankDraftReviewEdits(
                amountText = "45,000",
                dateText = "2026-10-15",
                timeText = "12:00",
                memoText = "Synthetic cafe",
                type = TransactionType.EXPENSE,
                categoryId = categoryId,
                categoryResolved = true,
            ),
        )
        return draftId
    }

    private fun monthRange(): LedgerDateRange = LedgerDateRange.closed(
        fromEpochMillis = ManualDateTime.monthStartEpochMillis(
            ManualDateTime.toEpochMillis("2026-10-15", "12:00", zone),
            zone,
        ),
        throughExclusiveEpochMillis = ManualDateTime.nextMonthStartEpochMillis(
            ManualDateTime.monthStartEpochMillis(
                ManualDateTime.toEpochMillis("2026-10-15", "12:00", zone),
                zone,
            ),
            zone,
        ),
        zoneId = zone.id,
    )
}
