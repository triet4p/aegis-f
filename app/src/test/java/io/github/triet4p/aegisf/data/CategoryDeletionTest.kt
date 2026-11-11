package io.github.triet4p.aegisf.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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

/**
 * Permanent category deletion against the real database: what survives, what is cleared, and
 * what a refused delete leaves behind.
 *
 * The delete is one Room transaction, so each case asserts the whole post-state rather than
 * only the row that changed: removing a category must not cost the user a transaction, a
 * draft, an amount, a description, or a monthly total, and it must not leave a rule or a
 * pre-set choice pointing at an id that no longer exists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CategoryDeletionTest {

    private class FixedLedgerClock(private val now: Long = CREATED_AT) : LedgerClock {
        override fun nowEpochMillis(): Long = now
    }

    private lateinit var context: Context
    private lateinit var database: LedgerDatabase
    private lateinit var repository: LedgerRepository
    private lateinit var query: WorkflowQueryService

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(LedgerDatabase.NAME)
        database = LedgerDatabase.build(context)
        query = WorkflowQueryService(database)
        repository = LedgerRepository(
            categoryDao = database.categoryDao(),
            transactionDao = database.transactionDao(),
            rememberedCategoryRuleDao = database.rememberedCategoryRuleDao(),
            captureMessageDao = database.captureMessageDao(),
            clock = FixedLedgerClock(),
        )
    }

    @Test
    fun `an unused category is deleted with nothing else touched`() = runTest {
        val food = repository.addCategory("Food")
        val unused = repository.addCategory("Filters")

        assertEquals(CategoryDeletionImpact(0, 0, 0), repository.categoryDeletionImpact(unused))
        assertEquals(CategoryDeletionResult.Deleted(unused), repository.deleteCategory(unused))

        assertNull(query.findCategory(unused))
        assertEquals(listOf(food), query.categories(ALL_CATEGORIES).items.map { it.id })
        // The removed id is not handed out again: a new category gets a fresh one, so nothing
        // that still names the old id can ever resolve to a different category.
        val replacement = repository.addCategory("Filters")
        assertTrue(replacement > unused)
        assertEquals(null, query.ledgerRecords(ALL_LEDGER_RECORDS).items.firstOrNull())
    }

    @Test
    fun `a referenced category is refused and every record keeps its link and total`() = runTest {
        val food = repository.addCategory("Food")
        val transport = repository.addCategory("Transport")
        val lunch = repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountVnd = 45_000L,
                occurredAtEpochMillis = OCCURRED_AT,
                description = "Lunch at a cafe",
                categoryId = food,
            ),
        )
        val salary = repository.addTransaction(
            TransactionInput(
                type = TransactionType.INCOME,
                amountVnd = 12_000_000L,
                occurredAtEpochMillis = OCCURRED_AT,
                description = "Salary",
                categoryId = transport,
            ),
        )
        val storedLunch = query.findLedgerRecord(lunch)!!
        val totalBefore = query.ledgerRecords(ALL_LEDGER_RECORDS).items.sumOf { it.amountVnd }

        assertEquals(CategoryDeletionImpact(1, 0, 0), repository.categoryDeletionImpact(food))
        assertEquals(
            CategoryDeletionResult.Blocked(CategoryDeletionImpact(1, 0, 0)),
            repository.deleteCategory(food),
        )

        // Refused means refused: the record keeps its exact values and its category, the
        // category row remains, and the month's rows and total do not move.
        assertEquals(storedLunch, query.findLedgerRecord(lunch))
        assertEquals(transport, query.findLedgerRecord(salary)!!.categoryId)
        assertEquals(listOf(lunch, salary).sorted(), query.ledgerRecords(ALL_LEDGER_RECORDS).items.map { it.id }.sorted())
        assertEquals(totalBefore, query.ledgerRecords(ALL_LEDGER_RECORDS).items.sumOf { it.amountVnd })
        assertNotNull(query.findCategory(food))
        assertEquals(food, query.findLedgerRecord(lunch)!!.categoryId)
    }

    @Test
    fun `rules that name the category block deletion and stay stored`() = runTest {
        val food = repository.addCategory("Food")
        val transport = repository.addCategory("Transport")
        assertTrue(repository.rememberCategoryForDescription("Synthetic cafe", food))
        assertTrue(repository.rememberCategoryForDescription("Synthetic bus", transport))

        assertEquals(CategoryDeletionImpact(0, 1, 0), repository.categoryDeletionImpact(food))
        assertEquals(
            CategoryDeletionResult.Blocked(CategoryDeletionImpact(0, 1, 0)),
            repository.deleteCategory(food),
        )

        assertEquals(1, database.categoryDao().countReferencingRules(food))
        assertEquals(1, database.categoryDao().countReferencingRules(transport))
        assertEquals(food, repository.findRememberedCategoryForDescription("Synthetic cafe")?.id)
        assertEquals(
            transport,
            repository.findRememberedCategoryForDescription("Synthetic bus")?.id,
        )
        assertNotNull(query.findCategory(food))
    }

    @Test
    fun `a pending draft pre-set to the category blocks deletion and keeps its choice`() = runTest {
        val food = repository.addCategory("Food")
        val draftDao = database.pendingBankDraftDao()
        val draftId = draftDao.store(draftEntity(categoryId = food, resolved = true))
        val before = draftDao.findById(draftId)!!

        assertEquals(CategoryDeletionImpact(0, 0, 1), repository.categoryDeletionImpact(food))
        assertEquals(
            CategoryDeletionResult.Blocked(CategoryDeletionImpact(0, 0, 1)),
            repository.deleteCategory(food),
        )

        assertEquals(before, draftDao.findById(draftId))
        assertNotNull(query.findCategory(food))
        assertTrue(query.ledgerRecords(ALL_LEDGER_RECORDS).items.isEmpty())
    }

    @Test
    fun `an archived referenced category is still refused and keeps its history`() = runTest {
        val food = repository.addCategory("Food")
        val lunch = repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountVnd = 45_000L,
                occurredAtEpochMillis = OCCURRED_AT,
                description = "Lunch at a cafe",
                categoryId = food,
            ),
        )
        repository.archiveCategory(food)
        val stored = query.findLedgerRecord(lunch)!!

        assertEquals(
            CategoryDeletionResult.Blocked(CategoryDeletionImpact(1, 0, 0)),
            repository.deleteCategory(food),
        )

        assertNotNull(query.findCategory(food))
        assertEquals(stored, query.findLedgerRecord(lunch))
        assertEquals(45_000L, query.findLedgerRecord(lunch)!!.amountVnd)
        assertTrue(query.findCategory(food)!!.archived)
    }

    @Test
    fun `deleting a category that is not stored is refused and changes nothing`() = runTest {
        val food = repository.addCategory("Food")
        val lunch = repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountVnd = 45_000L,
                occurredAtEpochMillis = OCCURRED_AT,
                description = "Lunch at a cafe",
                categoryId = food,
            ),
        )
        val categoriesBefore = query.categories(ALL_CATEGORIES).items
        val transactionsBefore = query.ledgerRecords(ALL_LEDGER_RECORDS).items

        val rejection = expectRejection { repository.deleteCategory(9_999L) }
        assertTrue(rejection.message.orEmpty().contains("9999"))
        expectRejection { repository.categoryDeletionImpact(9_999L) }

        assertEquals(categoriesBefore, query.categories(ALL_CATEGORIES).items)
        assertEquals(transactionsBefore, query.ledgerRecords(ALL_LEDGER_RECORDS).items)
        assertNotNull(query.findCategory(food))
        assertEquals(food, query.findLedgerRecord(lunch)!!.categoryId)
    }

    private fun reviewRepository() = reviewCommands(database, FixedLedgerClock())

    /** One pending draft whose only category reference is the pre-set review choice. */
    private fun draftEntity(categoryId: Long?, resolved: Boolean) = PendingBankDraftEntity(
        deliveryId = draftDeliveryId(BANK_PACKAGE, "synthetic-key", POSTED_AT),
        sourcePackage = BANK_PACKAGE,
        notificationKey = "synthetic-key",
        postedAtEpochMillis = POSTED_AT,
        bank = "SYNTHETIC",
        parseStatus = IntakeParseStatus.PARSED,
        amountState = IntakeFieldState.PRESENT,
        amountVnd = DRAFT_AMOUNT_VND,
        amountUnresolvedReason = null,
        direction = IntakeDirection.DEBIT,
        momentState = IntakeFieldState.PRESENT,
        statedDate = "2026-10-16",
        statedTime = "07:38",
        momentUnresolvedReason = null,
        memoState = IntakeFieldState.PRESENT,
        memo = "Synthetic cafe",
        memoUnresolvedReason = null,
        createdAtEpochMillis = CREATED_AT,
        reviewType = TransactionType.EXPENSE,
        reviewCategoryId = categoryId,
        reviewCategoryResolved = resolved,
    )

    private fun edits(categoryId: Long?, resolved: Boolean) = BankDraftReviewEdits(
        amountText = DRAFT_AMOUNT_VND.toString(),
        dateText = "2026-10-16",
        timeText = "07:38",
        memoText = "Synthetic cafe",
        type = TransactionType.EXPENSE,
        categoryId = categoryId,
        categoryResolved = resolved,
    )

    private suspend fun expectRejection(write: suspend () -> Unit): LedgerValidationException =
        try {
            write()
            throw AssertionError("Expected the write to be rejected.")
        } catch (rejection: LedgerValidationException) {
            rejection
        }
}

private const val OCCURRED_AT = 1_700_000_000_000L
private const val CREATED_AT = 1_700_000_500_000L
private const val POSTED_AT = 1_700_000_400_000L
private const val DRAFT_AMOUNT_VND = 50_000L
private const val BANK_PACKAGE = "com.synthetic.bank"
