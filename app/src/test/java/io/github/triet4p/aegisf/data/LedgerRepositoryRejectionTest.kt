package io.github.triet4p.aegisf.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Domain boundaries of the ledger repository, checked against the real database so a
 * rejection is proven not to leave partial state behind.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LedgerRepositoryRejectionTest {

    private class FixedLedgerClock(private val now: Long = 1_700_000_500_000L) : LedgerClock {
        override fun nowEpochMillis(): Long = now
    }

    private lateinit var context: Context
    private lateinit var repository: LedgerRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(LedgerDatabase.NAME)
        val database = LedgerDatabase.build(context)
        repository = LedgerRepository(
            categoryDao = database.categoryDao(),
            transactionDao = database.transactionDao(),
            rememberedCategoryRuleDao = database.rememberedCategoryRuleDao(),
            captureMessageDao = database.captureMessageDao(),
            clock = FixedLedgerClock(),
        )
    }

    @Test
    fun `zero and negative amounts are refused`() = runTest {
        expectRejection {
            repository.addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountVnd = 0L,
                    occurredAtEpochMillis = 1_700_000_000_000L,
                    description = "Free sample",
                ),
            )
        }
        expectRejection {
            repository.addTransaction(
                TransactionInput(
                    type = TransactionType.INCOME,
                    amountVnd = -1_000_000L,
                    occurredAtEpochMillis = 1_700_000_000_000L,
                    description = "Reversal",
                ),
            )
        }
        expectRejection {
            repository.addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountVnd = Long.MIN_VALUE,
                    occurredAtEpochMillis = 1_700_000_000_000L,
                    description = "Wrapped amount",
                ),
            )
        }

        assertTrue(repository.transactions().isEmpty())
    }

    @Test
    fun `one dong is storable and stays exact`() = runTest {
        val id = repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountVnd = 1L,
                occurredAtEpochMillis = 1_700_000_000_000L,
                description = "Rounded coffee",
            ),
        )

        assertEquals(1L, repository.findTransaction(id)!!.amountVnd)
    }

    @Test
    fun `the largest whole dong magnitude is stored without wrapping`() = runTest {
        val id = repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountVnd = Long.MAX_VALUE,
                occurredAtEpochMillis = 1_700_000_000_000L,
                description = "Whole-dong ceiling",
            ),
        )

        assertEquals(Long.MAX_VALUE, repository.findTransaction(id)!!.amountVnd)
    }

    @Test
    fun `an internal transfer cannot carry a category`() = runTest {
        val food = repository.addCategory("Food")

        expectRejection {
            repository.addTransaction(
                TransactionInput(
                    type = TransactionType.TRANSFER,
                    amountVnd = 500_000L,
                    occurredAtEpochMillis = 1_700_000_000_000L,
                    description = "Move to savings",
                    categoryId = food,
                ),
            )
        }

        assertTrue(repository.transactions().isEmpty())
    }

    @Test
    fun `an unknown category is refused instead of stored as an orphan`() = runTest {
        expectRejection {
            repository.addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountVnd = 10_000L,
                    occurredAtEpochMillis = 1_700_000_000_000L,
                    description = "Unknown category",
                    categoryId = 9_999L,
                ),
            )
        }

        assertTrue(repository.transactions().isEmpty())
    }

    @Test
    fun `a category needs a name and active names stay unambiguous`() = runTest {
        expectRejection { repository.addCategory("   ") }

        val food = repository.addCategory("Food")
        expectRejection { repository.addCategory("Food") }
        // Case differences are the same name for assignment purposes.
        expectRejection { repository.addCategory(" food ") }

        assertEquals(listOf(food), repository.categories().map { it.id })
        assertEquals("Food", repository.findCategory(food)!!.name)
    }

    @Test
    fun `renaming to another active category is refused and keeps the stored name`() = runTest {
        val food = repository.addCategory("Food")
        val rent = repository.addCategory("Rent")

        expectRejection { repository.renameCategory(rent, "food") }

        assertEquals("Rent", repository.findCategory(rent)!!.name)
        assertEquals(listOf(food, rent).sorted(), repository.categories().map { it.id }.sorted())

        // Renaming to the category's own name is allowed and changes nothing.
        repository.renameCategory(rent, "Rent")
        assertEquals("Rent", repository.findCategory(rent)!!.name)
    }

    @Test
    fun `archiving a category releases its name for a new category`() = runTest {
        val food = repository.addCategory("Food")
        repository.archiveCategory(food)

        assertEquals(listOf(food), repository.categories().map { it.id })
        assertTrue(repository.findCategory(food)!!.archived)

        // The archived name becomes free again for a brand-new category.
        val newFood = repository.addCategory("Food")
        assertFalse(repository.findCategory(newFood)!!.archived)
        assertEquals(2, repository.categories().size)
        assertEquals(listOf(newFood), repository.assignableCategories().filter { it.name == "Food" }.map { it.id })
    }

    @Test
    fun `deleting a category with stored records detaches them instead of refusing`() = runTest {
        val food = repository.addCategory("Food")
        val lunch = repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountVnd = 45_000L,
                occurredAtEpochMillis = 1_700_000_000_000L,
                description = "Lunch",
                categoryId = food,
            ),
        )

        val impact = repository.deleteCategory(food)

        // The record keeps its amount and description and only loses the category link; the
        // category row is gone, and a second delete of the same id is refused.
        assertEquals(1, impact.uncategorizedTransactionCount)
        val stored = repository.findTransaction(lunch)!!
        assertEquals(45_000L, stored.amountVnd)
        assertEquals("Lunch", stored.description)
        assertNull(stored.categoryId)
        assertNull(repository.findCategory(food))
        expectRejection { repository.deleteCategory(food) }
    }

    @Test
    fun `archived categories keep their stored records attached`() = runTest {
        val food = repository.addCategory("Food")
        val lunch = repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountVnd = 45_000L,
                occurredAtEpochMillis = 1_700_000_000_000L,
                description = "Lunch",
                categoryId = food,
            ),
        )

        repository.archiveCategory(food)

        // The record is still readable under the archived category.
        assertEquals(food, repository.findTransaction(lunch)!!.categoryId)
        assertTrue(repository.categories().none { !it.archived && it.id == food })
    }

    @Test
    fun `a rejected correction leaves the stored record unchanged`() = runTest {
        val food = repository.addCategory("Food")
        val snacks = repository.addCategory("Snacks")
        val lunch = repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountVnd = 45_000L,
                occurredAtEpochMillis = 1_700_000_000_000L,
                description = "Lunch",
                categoryId = food,
            ),
        )
        repository.archiveCategory(snacks)
        val storedBefore = repository.findTransaction(lunch)!!

        // Zero, a categorised transfer, and an archived category this record is not filed
        // under are all refused before anything reaches the database.
        expectRejection {
            repository.editTransaction(
                lunch,
                TransactionCorrection(
                    type = TransactionType.EXPENSE,
                    amountVnd = 0L,
                    occurredAtEpochMillis = 1_700_000_600_000L,
                    description = "Free sample",
                ),
            )
        }
        expectRejection {
            repository.editTransaction(
                lunch,
                TransactionCorrection(
                    type = TransactionType.TRANSFER,
                    amountVnd = 10_000L,
                    occurredAtEpochMillis = 1_700_000_600_000L,
                    description = "Categorised transfer",
                    categoryId = food,
                ),
            )
        }
        expectRejection {
            repository.editTransaction(
                lunch,
                TransactionCorrection(
                    type = TransactionType.EXPENSE,
                    amountVnd = 10_000L,
                    occurredAtEpochMillis = 1_700_000_600_000L,
                    description = "Archived category",
                    categoryId = snacks,
                ),
            )
        }

        assertEquals(storedBefore, repository.findTransaction(lunch))
    }

    @Test
    fun `correcting an unknown record is refused`() = runTest {
        expectRejection {
            repository.editTransaction(
                9_999L,
                TransactionCorrection(
                    type = TransactionType.EXPENSE,
                    amountVnd = 45_000L,
                    occurredAtEpochMillis = 1_700_000_000_000L,
                    description = "Lunch",
                ),
            )
        }
        expectRejection { repository.renameCategory(9_999L, "Food") }
        expectRejection { repository.archiveCategory(9_999L) }
        expectRejection { repository.deleteCategory(9_999L) }

        assertTrue(repository.transactions().isEmpty())
        assertTrue(repository.categories().isEmpty())
    }

    private suspend fun expectRejection(write: suspend () -> Unit): LedgerValidationException =
        try {
            write()
            throw AssertionError("Expected the write to be rejected.")
        } catch (rejection: LedgerValidationException) {
            rejection
        }
}
