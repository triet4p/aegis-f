package io.github.triet4p.aegisf.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.triet4p.aegisf.ui.ManualDateTime
import io.github.triet4p.aegisf.ui.summarizeMonthExpenses
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

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(LedgerDatabase.NAME)
        database = LedgerDatabase.build(context)
        repository = LedgerRepository(
            categoryDao = database.categoryDao(),
            transactionDao = database.transactionDao(),
            rememberedCategoryRuleDao = database.rememberedCategoryRuleDao(),
            clock = FixedLedgerClock(),
        )
    }

    @Test
    fun `an unused category is deleted with nothing else touched`() = runTest {
        val food = repository.addCategory("Food")
        val unused = repository.addCategory("Filters")

        assertEquals(CategoryDeletionImpact(0, 0, 0), repository.categoryDeletionImpact(unused))
        assertEquals(CategoryDeletionImpact(0, 0, 0), repository.deleteCategory(unused))

        assertNull(repository.findCategory(unused))
        assertEquals(listOf(food), repository.categories().map { it.id })
        // The removed id is not handed out again: a new category gets a fresh one, so nothing
        // that still names the old id can ever resolve to a different category.
        val replacement = repository.addCategory("Filters")
        assertTrue(replacement > unused)
        assertEquals(null, repository.transactions().firstOrNull())
    }

    @Test
    fun `a referenced category keeps every record and total and only clears the link`() = runTest {
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
        val storedLunch = repository.findTransaction(lunch)!!
        val totalBefore = repository.transactions().sumOf { it.amountVnd }

        assertEquals(CategoryDeletionImpact(1, 0, 0), repository.categoryDeletionImpact(food))
        assertEquals(CategoryDeletionImpact(1, 0, 0), repository.deleteCategory(food))

        // The record is identical except for the link: no amount, type, moment, description,
        // confirmation, provenance, or timestamp is rewritten by a category deletion.
        assertEquals(storedLunch.copy(categoryId = null), repository.findTransaction(lunch))
        assertEquals(transport, repository.findTransaction(salary)!!.categoryId)
        assertEquals(listOf(lunch, salary).sorted(), repository.transactions().map { it.id }.sorted())
        assertEquals(totalBefore, repository.transactions().sumOf { it.amountVnd })

        // The month's own figures come from those same rows, so they do not move either: the
        // detached expense now sits in the uncategorized bucket with its exact amount.
        val summary = summarizeMonthExpenses(
            records = repository.transactions(),
            monthStartEpochMillis = ManualDateTime.monthStartEpochMillis(OCCURRED_AT),
            monthEndExclusiveEpochMillis = ManualDateTime.nextMonthStartEpochMillis(OCCURRED_AT),
        )
        assertEquals(1, summary.expenseCount)
        assertEquals(45_000L, summary.totalVnd)
        assertEquals(listOf(null to 45_000L), summary.categoryTotals.map { it.categoryId to it.totalVnd })

        // Gone from the category list and from the picker, while the unrelated one stays.
        assertNull(repository.findCategory(food))
        assertEquals(listOf(transport), repository.assignableCategories().map { it.id })
    }

    @Test
    fun `rules that named the category go with it and unrelated rules keep working`() = runTest {
        val food = repository.addCategory("Food")
        val transport = repository.addCategory("Transport")
        assertTrue(repository.rememberCategoryForDescription("Synthetic cafe", food))
        assertTrue(repository.rememberCategoryForDescription("Synthetic bus", transport))

        assertEquals(CategoryDeletionImpact(0, 1, 0), repository.categoryDeletionImpact(food))
        assertEquals(CategoryDeletionImpact(0, 1, 0), repository.deleteCategory(food))

        // The rule rows themselves are gone, not merely unresolvable: no stored rule still
        // names the removed category, while the unrelated one is untouched.
        assertEquals(0, database.categoryDao().countReferencingRules(food))
        assertEquals(1, database.categoryDao().countReferencingRules(transport))
        assertNull(repository.findRememberedCategoryForDescription("Synthetic cafe"))
        assertEquals(
            transport,
            repository.findRememberedCategoryForDescription("Synthetic bus")?.id,
        )
        // The freed description can be remembered again, for a category that does exist.
        assertTrue(repository.rememberCategoryForDescription("Synthetic cafe", transport))
    }

    @Test
    fun `a pending draft loses a removed pre-set category and refuses the removed id`() = runTest {
        val food = repository.addCategory("Food")
        val transport = repository.addCategory("Transport")
        val draftDao = database.pendingBankDraftDao()
        val draftId = draftDao.store(draftEntity(categoryId = food, resolved = true))
        val review = reviewRepository()
        assertEquals(0, draftDao.findById(draftId)!!.reviewRevision)

        assertEquals(CategoryDeletionImpact(0, 0, 1), repository.categoryDeletionImpact(food))
        assertEquals(CategoryDeletionImpact(0, 0, 1), repository.deleteCategory(food))

        val cleared = draftDao.findById(draftId)!!
        assertNull(cleared.reviewCategoryId)
        assertFalse(cleared.reviewCategoryResolved)
        assertEquals(1, cleared.reviewRevision)

        // An editor still holding the older revision is told the draft changed rather than
        // being allowed to write the removed id back.
        assertEquals(
            BankDraftReviewResult.Changed,
            review.saveReview(draftId, 0, edits(categoryId = food, resolved = true)),
        )
        // Even at the current revision, neither saving nor confirming can re-link the id.
        assertEquals(
            BankDraftReviewResult.CategoryUnavailable,
            review.saveReview(draftId, 1, edits(categoryId = food, resolved = true)),
        )
        assertEquals(
            BankDraftReviewResult.CategoryUnavailable,
            review.confirmDraft(draftId, 1, edits(categoryId = food, resolved = true)),
        )
        // Both refusals wrote nothing: the draft is still pending with no ledger row.
        val afterRefusals = draftDao.findById(draftId)!!
        assertEquals(IntakeReviewStatus.PENDING, afterRefusals.reviewStatus)
        assertNull(afterRefusals.reviewCategoryId)
        assertTrue(repository.transactions().isEmpty())

        // The draft stays reviewable by hand: another category, then No category, both work.
        assertEquals(
            BankDraftReviewResult.Saved(2),
            review.saveReview(draftId, 1, edits(categoryId = transport, resolved = true)),
        )
        assertEquals(
            BankDraftReviewResult.Saved(3),
            review.saveReview(draftId, 2, edits(categoryId = null, resolved = true)),
        )
        assertTrue(
            review.confirmDraft(draftId, 3, edits(categoryId = null, resolved = true)) is
                BankDraftReviewResult.Confirmed,
        )
        val created = repository.transactions().single()
        assertEquals(DRAFT_AMOUNT_VND, created.amountVnd)
        assertNull(created.categoryId)
        assertEquals(transport, repository.findCategory(transport)?.id)
    }

    @Test
    fun `an archived category is deleted outright instead of being kept for reuse`() = runTest {
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
        val stored = repository.findTransaction(lunch)!!

        assertEquals(CategoryDeletionImpact(1, 0, 0), repository.deleteCategory(food))

        // No alias or archived remnant of the deleted category survives to be re-selected.
        assertNull(repository.findCategory(food))
        assertNull(repository.categories().firstOrNull { it.name == "Food" })
        assertEquals(stored.copy(categoryId = null), repository.findTransaction(lunch))
        assertEquals(45_000L, repository.findTransaction(lunch)!!.amountVnd)
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
        val categoriesBefore = repository.categories()
        val transactionsBefore = repository.transactions()

        val rejection = expectRejection { repository.deleteCategory(9_999L) }
        assertTrue(rejection.message.orEmpty().contains("9999"))
        expectRejection { repository.categoryDeletionImpact(9_999L) }

        assertEquals(categoriesBefore, repository.categories())
        assertEquals(transactionsBefore, repository.transactions())
        assertNotNull(repository.findCategory(food))
        assertEquals(food, repository.findTransaction(lunch)!!.categoryId)
    }

    private fun reviewRepository() = BankDraftReviewRepository(
        draftDao = database.pendingBankDraftDao(),
        reviewDao = database.bankDraftReviewDao(),
        clock = FixedLedgerClock(),
    )

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
