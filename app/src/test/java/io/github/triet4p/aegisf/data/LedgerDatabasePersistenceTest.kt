package io.github.triet4p.aegisf.data

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * Persistence proof for the ledger: real Room/SQLite, no fake DAO and no mock.
 *
 * The database is a file in the application's own database directory, opened through the
 * production builder [LedgerDatabase.build], so closing and reopening an instance is the
 * same thing the app does when its process is killed and restarted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LedgerDatabasePersistenceTest {

    private class AdvancingLedgerClock(var now: Long = CREATED_AT) : LedgerClock {
        override fun nowEpochMillis(): Long = now
    }

    private class Ledger(
        val database: LedgerDatabase,
        val repository: LedgerRepository,
        val query: WorkflowQueryService,
    )

    private lateinit var context: Context
    private lateinit var clock: AdvancingLedgerClock

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(LedgerDatabase.NAME)
        clock = AdvancingLedgerClock()
    }

    private fun open(): Ledger {
        val database = LedgerDatabase.build(context)
        return Ledger(
            database = database,
            query = WorkflowQueryService(database),
            repository = LedgerRepository(
                categoryDao = database.categoryDao(),
                transactionDao = database.transactionDao(),
                rememberedCategoryRuleDao = database.rememberedCategoryRuleDao(),
                captureMessageDao = database.captureMessageDao(),
                clock = clock,
            ),
        )
    }

    @Test
    fun `records survive closing and reopening the database`() = runTest {
        val first = open()
        val food = first.repository.addCategory("Food")
        val rent = first.repository.addCategory("Rent")

        val lunch = first.repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountVnd = 45_000L,
                occurredAtEpochMillis = 1_700_000_000_000L,
                description = "Lunch",
                categoryId = food,
            ),
        )
        val coffee = first.repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountVnd = 120_000L,
                occurredAtEpochMillis = 1_700_000_100_000L,
                description = "Coffee beans",
                categoryId = food,
            ),
        )
        val salary = first.repository.addTransaction(
            TransactionInput(
                type = TransactionType.INCOME,
                amountVnd = 1_000_000L,
                occurredAtEpochMillis = 1_700_000_200_000L,
                description = "Salary",
            ),
        )
        val savingsTransfer = first.repository.addTransaction(
            TransactionInput(
                type = TransactionType.TRANSFER,
                amountVnd = 500_000L,
                occurredAtEpochMillis = 1_700_000_300_000L,
                description = "Move to savings",
            ),
        )
        val draftFromBank = first.repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountVnd = 320_000L,
                occurredAtEpochMillis = 1_700_000_400_000L,
                description = "Electricity bill",
                categoryId = null,
                confirmation = ConfirmationStatus.DRAFT,
                source = RecordSource.BANK_NOTIFICATION,
            ),
        )

        val categoriesBeforeClose = first.query.categories(ALL_CATEGORIES).items
        val transactionsBeforeClose = first.query.ledgerRecords(ALL_LEDGER_RECORDS).items

        assertEquals(listOf(food, rent).sorted(), categoriesBeforeClose.map { it.id }.sorted())

        first.database.close()

        val second = open()

        assertEquals(categoriesBeforeClose, second.query.categories(ALL_CATEGORIES).items)
        assertEquals(transactionsBeforeClose, second.query.ledgerRecords(ALL_LEDGER_RECORDS).items)

        val reloadedLunch = second.query.findLedgerRecord(lunch)
        assertNotNull("the stored transaction must still be readable", reloadedLunch)
        assertEquals(45_000L, reloadedLunch!!.amountVnd)
        assertEquals(TransactionType.EXPENSE, reloadedLunch.type)
        assertEquals(food, reloadedLunch.categoryId)
        assertEquals("Lunch", reloadedLunch.description)
        assertEquals(1_700_000_000_000L, reloadedLunch.occurredAtEpochMillis)
        assertEquals(ConfirmationStatus.CONFIRMED, reloadedLunch.confirmation)
        assertEquals(RecordSource.MANUAL, reloadedLunch.source)
        assertEquals(CREATED_AT, reloadedLunch.createdAtEpochMillis)
        assertEquals(CREATED_AT, reloadedLunch.updatedAtEpochMillis)

        // A transfer keeps its own category-less shape, and a draft keeps its provenance.
        assertEquals(TransactionType.TRANSFER, second.query.findLedgerRecord(savingsTransfer)!!.type)
        assertNull(second.query.findLedgerRecord(savingsTransfer)!!.categoryId)
        assertEquals(ConfirmationStatus.DRAFT, second.query.findLedgerRecord(draftFromBank)!!.confirmation)
        assertEquals(RecordSource.BANK_NOTIFICATION, second.query.findLedgerRecord(draftFromBank)!!.source)

        assertEquals(1_000_000L, second.query.findLedgerRecord(salary)!!.amountVnd)
        assertEquals(120_000L, second.query.findLedgerRecord(coffee)!!.amountVnd)
        assertNotNull(second.query.findCategory(rent))
    }

    @Test
    fun `archiving a category keeps it attached to stored transactions`() = runTest {
        val first = open()
        val food = first.repository.addCategory("Food")
        val lunch = first.repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountVnd = 45_000L,
                occurredAtEpochMillis = 1_700_000_000_000L,
                description = "Lunch",
                categoryId = food,
            ),
        )
        first.repository.archiveCategory(food)
        first.database.close()

        val second = open()

        val archivedCategory = second.query.findCategory(food)
        assertNotNull(archivedCategory)
        assertTrue(archivedCategory!!.archived)

        // The historical record still points at the archived category.
        val storedLunch = second.query.findLedgerRecord(lunch)
        assertNotNull(storedLunch)
        assertEquals(food, storedLunch!!.categoryId)

        // The archived category is no longer offered for new assignments.
        assertTrue(second.query.categories(ALL_ASSIGNABLE_CATEGORIES).items.none { it.id == food })

        expectRejection {
            second.repository.addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountVnd = 30_000L,
                    occurredAtEpochMillis = 1_700_000_500_000L,
                    description = "Dinner",
                    categoryId = food,
                ),
            )
        }

        second.database.close()

        // The rejected write left nothing behind, and the archived link survived.
        val third = open()
        assertEquals(listOf(lunch), third.query.ledgerRecords(ALL_LEDGER_RECORDS).items.map { it.id })
        assertEquals(food, third.query.findLedgerRecord(lunch)!!.categoryId)
        assertTrue(third.query.findCategory(food)!!.archived)
    }

    @Test
    fun `a rejected write leaves the stored records unchanged`() = runTest {
        val first = open()
        val food = first.repository.addCategory("Food")
        val lunch = first.repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountVnd = 45_000L,
                occurredAtEpochMillis = 1_700_000_000_000L,
                description = "Lunch",
                categoryId = food,
            ),
        )
        val salary = first.repository.addTransaction(
            TransactionInput(
                type = TransactionType.INCOME,
                amountVnd = 1_000_000L,
                occurredAtEpochMillis = 1_700_000_200_000L,
                description = "Salary",
            ),
        )
        first.repository.archiveCategory(food)

        // Zero, negative, an unknown category, an archived category and a categorised
        // transfer are all refused before anything reaches the database.
        expectRejection {
            first.repository.addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountVnd = 0L,
                    occurredAtEpochMillis = 1_700_000_600_000L,
                    description = "Free sample",
                ),
            )
        }
        expectRejection {
            first.repository.addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountVnd = -45_000L,
                    occurredAtEpochMillis = 1_700_000_600_000L,
                    description = "Refund",
                ),
            )
        }
        expectRejection {
            first.repository.addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountVnd = 10_000L,
                    occurredAtEpochMillis = 1_700_000_600_000L,
                    description = "Unknown category",
                    categoryId = 9_999L,
                ),
            )
        }
        expectRejection {
            first.repository.addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountVnd = 10_000L,
                    occurredAtEpochMillis = 1_700_000_600_000L,
                    description = "Archived category",
                    categoryId = food,
                ),
            )
        }
        expectRejection {
            first.repository.addTransaction(
                TransactionInput(
                    type = TransactionType.TRANSFER,
                    amountVnd = 10_000L,
                    occurredAtEpochMillis = 1_700_000_600_000L,
                    description = "Categorised transfer",
                    categoryId = food,
                ),
            )
        }

        first.database.close()

        val second = open()
        val transactions = second.query.ledgerRecords(ALL_LEDGER_RECORDS).items
        assertEquals(2, transactions.size)
        assertEquals(listOf(lunch, salary).sorted(), transactions.map { it.id }.sorted())
        assertEquals(45_000L, second.query.findLedgerRecord(lunch)!!.amountVnd)
        assertEquals(1_000_000L, second.query.findLedgerRecord(salary)!!.amountVnd)
    }

    @Test
    fun `correcting a record keeps its identity and updates its revision time`() = runTest {
        val first = open()
        val food = first.repository.addCategory("Food")
        val lunch = first.repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountVnd = 120_000L,
                occurredAtEpochMillis = 1_700_000_100_000L,
                description = "Coffee beans",
                categoryId = food,
            ),
        )

        clock.now = EDITED_AT
        first.repository.editTransaction(
            lunch,
            0,
            TransactionCorrection(
                type = TransactionType.EXPENSE,
                amountVnd = 125_000L,
                occurredAtEpochMillis = 1_700_000_100_000L,
                description = "Coffee beans and filter",
                categoryId = food,
            ),
        )
        val expected = first.query.findLedgerRecord(lunch)!!
        assertEquals(1, first.query.ledgerRecords(ALL_LEDGER_RECORDS).items.size)

        first.database.close()

        val second = open()
        val corrected = second.query.findLedgerRecord(lunch)!!
        assertEquals(expected, corrected)
        assertEquals(125_000L, corrected.amountVnd)
        assertEquals("Coffee beans and filter", corrected.description)
        assertEquals(food, corrected.categoryId)
        assertEquals(EDITED_AT, corrected.updatedAtEpochMillis)
        assertEquals(CREATED_AT, corrected.createdAtEpochMillis)
    }

    @Test
    fun `a correction keeps the confirmation, provenance, and an archived category`() = runTest {
        val first = open()
        val snacks = first.repository.addCategory("Snacks")
        val capturedBill = first.repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountVnd = 120_000L,
                occurredAtEpochMillis = 1_700_000_100_000L,
                description = "Electricity bill",
                categoryId = snacks,
                confirmation = ConfirmationStatus.DRAFT,
                source = RecordSource.BANK_NOTIFICATION,
            ),
        )
        first.repository.archiveCategory(snacks)
        val beforeCorrection = first.query.findLedgerRecord(capturedBill)!!

        clock.now = EDITED_AT
        first.repository.editTransaction(
            capturedBill,
            0,
            TransactionCorrection(
                type = TransactionType.EXPENSE,
                amountVnd = 125_000L,
                occurredAtEpochMillis = 1_700_000_100_000L,
                description = "Electricity bill, corrected",
                categoryId = snacks,
            ),
        )

        first.database.close()

        val second = open()
        val corrected = second.query.findLedgerRecord(capturedBill)!!
        assertEquals(1, second.query.ledgerRecords(ALL_LEDGER_RECORDS).items.size)
        assertEquals(125_000L, corrected.amountVnd)
        assertEquals("Electricity bill, corrected", corrected.description)
        // The archived category the record was filed under is kept, not cleared and not
        // reassigned: correcting a record is not a new assignment.
        assertEquals(snacks, corrected.categoryId)
        assertTrue(second.query.findCategory(snacks)!!.archived)
        // How the record came to exist is not the correction's to rewrite.
        assertEquals(ConfirmationStatus.DRAFT, corrected.confirmation)
        assertEquals(RecordSource.BANK_NOTIFICATION, corrected.source)
        assertEquals(beforeCorrection.createdAtEpochMillis, corrected.createdAtEpochMillis)
        assertEquals(EDITED_AT, corrected.updatedAtEpochMillis)
    }

    @Test
    fun `deleting a record removes it and frees nothing else`() = runTest {
        val first = open()
        val food = first.repository.addCategory("Food")
        val lunch = first.repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountVnd = 45_000L,
                occurredAtEpochMillis = 1_700_000_000_000L,
                description = "Lunch",
                categoryId = food,
            ),
        )
        val coffee = first.repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountVnd = 120_000L,
                occurredAtEpochMillis = 1_700_000_100_000L,
                description = "Coffee beans",
                categoryId = food,
            ),
        )

        first.repository.deleteTransaction(lunch)
        first.repository.deleteTransaction(lunch) // deleting an unknown id is a no-op

        first.database.close()

        val second = open()
        assertEquals(listOf(coffee), second.query.ledgerRecords(ALL_LEDGER_RECORDS).items.map { it.id })
        assertNull(second.query.findLedgerRecord(lunch))
        assertEquals(food, second.query.findCategory(food)!!.id)
    }

    @Test
    fun `the database file carries the schema version the repository expects`() = runTest {
        val first = open()
        val storedVersion = first.database
            .openHelper
            .writableDatabase
            .query(SimpleSQLiteQuery("PRAGMA user_version"))
            .use { cursor ->
                check(cursor.moveToFirst()) { "PRAGMA user_version returned no row" }
                cursor.getLong(0)
            }

        assertEquals(LedgerDatabase.VERSION.toLong(), storedVersion)
    }

    @Test
    fun `category writes are observed without re-querying`() = runBlocking {
        val first = open()

        // One subscription for the whole test: any later refresh would prove the stream
        // is not live. Room re-emits from the invalidation tracker, on real threads, so
        // this test waits in real time instead of the virtual test clock.
        val emissions = Collections.synchronizedList(mutableListOf<List<CategoryEntity>>())
        val collector = launch(Dispatchers.IO) {
            first.query.observeCategories(ALL_CATEGORIES).map { it.items }.collect { emissions += it }
        }

        // The waits look for content (the latest emission holding a row set), never for an
        // emission index: the first emission may be the pre-write empty snapshot.
        suspend fun awaitLatest(predicate: (List<CategoryEntity>) -> Boolean) {
            withTimeout(15_000) {
                while (true) {
                    // The collector appends on another thread, so the wait reads a snapshot
                    // taken under the list's own lock instead of iterating the live list.
                    val snapshot = synchronized(emissions) { emissions.toList() }
                    if (snapshot.any(predicate)) return@withTimeout
                    delay(20)
                }
            }
        }

        val food = first.repository.addCategory("Food")
        awaitLatest { rows -> rows.map { it.id } == listOf(food) }
        val rent = first.repository.addCategory("Rent")
        awaitLatest { rows -> rows.map { it.id } == listOf(food, rent) }
        first.repository.renameCategory(rent, "Housing")
        awaitLatest { rows -> rows.map { it.name } == listOf("Food", "Housing") }
        first.repository.archiveCategory(food)
        awaitLatest { rows -> rows.map { it.archived } == listOf(true, false) }

        collector.cancelAndJoin()

        val latest = synchronized(emissions) { emissions.toList() }.last()
        assertEquals(listOf(food, rent), latest.map { it.id })
        assertEquals(listOf("Food", "Housing"), latest.map { it.name })
        assertEquals(listOf(true, false), latest.map { it.archived })

        // A new subscriber starts from the stored state, not from an empty list.
        assertEquals(emissions.last(), first.query.observeCategories(ALL_CATEGORIES).map { it.items }.first())

        first.database.close()
    }

    @Test
    fun `there is no product-imposed cap on the number of categories`() = runTest {
        val first = open()
        val created = (1..60).map { index -> first.repository.addCategory("Category $index") }

        val observed = first.query.observeCategories(ALL_CATEGORIES).map { it.items }.first()
        assertEquals(60, observed.size)
        assertEquals(created.toSet(), observed.map { it.id }.toSet())
        assertEquals(60, first.query.categories(ALL_ASSIGNABLE_CATEGORIES).items.size)

        // One more still fits: no cap is hidden behind the first page of results.
        first.repository.addCategory("One more category")
        assertEquals(61, first.query.observeCategories(ALL_CATEGORIES).map { it.items }.first().size)

        first.database.close()
    }

    @Test
    fun `transactions are observed newest first without re-querying`() = runBlocking {
        val first = open()

        // One subscription for the whole test: any later refresh would prove the stream
        // is not live. Room re-emits from the invalidation tracker, on real threads, so
        // this test waits in real time instead of the virtual test clock. The waits look
        // for content (the latest emission holding a row set), never for an emission
        // index: the first emission may be the pre-write empty snapshot.
        val emissions = Collections.synchronizedList(mutableListOf<List<TransactionEntity>>())
        val collector = launch(Dispatchers.IO) {
            first.query.observeLedgerRecords(ALL_LEDGER_RECORDS).map { it.items }.collect { emissions += it }
        }

        suspend fun awaitLatest(predicate: (List<TransactionEntity>) -> Boolean) {
            withTimeout(15_000) {
                while (true) {
                    // The collector appends on another thread, so the wait reads a snapshot
                    // taken under the list's own lock instead of iterating the live list.
                    val snapshot = synchronized(emissions) { emissions.toList() }
                    if (snapshot.any(predicate)) return@withTimeout
                    delay(20)
                }
            }
        }

        // Two records share the occurred-at instant: the stored id breaks the tie, and the
        // newest instant stays first either way.
        val older = first.repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountVnd = 45_000L,
                occurredAtEpochMillis = 1_700_000_100_000L,
                description = "Older",
            ),
        )
        awaitLatest { rows -> rows.map { it.id } == listOf(older) }
        val newer = first.repository.addTransaction(
            TransactionInput(
                type = TransactionType.INCOME,
                amountVnd = 1_000_000L,
                occurredAtEpochMillis = 1_700_000_200_000L,
                description = "Newer",
            ),
        )
        awaitLatest { rows -> rows.map { it.id } == listOf(newer, older) }
        val tie = first.repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountVnd = 120_000L,
                occurredAtEpochMillis = 1_700_000_200_000L,
                description = "Tie",
            ),
        )
        awaitLatest { rows -> rows.map { it.id } == listOf(tie, newer, older) }

        collector.cancelAndJoin()

        // A correction re-emits the changed row in place, and a deletion re-emits without
        // it — the browsing list never asks for a refresh to see either.
        val secondEmissions = Collections.synchronizedList(mutableListOf<List<TransactionEntity>>())
        val collector2 = launch(Dispatchers.IO) {
            first.query.observeLedgerRecords(ALL_LEDGER_RECORDS).map { it.items }.collect { secondEmissions += it }
        }

        suspend fun awaitSecondLatest(predicate: (List<TransactionEntity>) -> Boolean) {
            withTimeout(15_000) {
                while (secondEmissions.none(predicate)) {
                    delay(20)
                }
            }
        }

        awaitSecondLatest { rows -> rows.map { it.id } == listOf(tie, newer, older) }
        first.repository.editTransaction(
            newer,
            0,
            TransactionCorrection(
                type = TransactionType.INCOME,
                amountVnd = 1_100_000L,
                occurredAtEpochMillis = 1_700_000_000_000L,
                description = "Newer, corrected earlier",
            ),
        )
        awaitSecondLatest { rows ->
            rows.map { it.id } == listOf(tie, older, newer) &&
                rows.first { it.id == newer }.amountVnd == 1_100_000L
        }

        first.repository.deleteTransaction(older)
        awaitSecondLatest { rows -> rows.map { it.id } == listOf(tie, newer) }

        collector2.cancelAndJoin()

        // The stream survives close/reopen: it reads the same database file.
        first.database.close()
        val second = open()
        assertEquals(listOf(tie, newer), second.query.observeLedgerRecords(ALL_LEDGER_RECORDS).map { it.items }.first().map { it.id })
        second.database.close()
    }


    @Test
    fun `renaming a category keeps stored transactions attached to it`() = runTest {
        val first = open()
        val food = first.repository.addCategory("Food")
        val lunch = first.repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountVnd = 45_000L,
                occurredAtEpochMillis = 1_700_000_000_000L,
                description = "Lunch",
                categoryId = food,
            ),
        )

        first.repository.renameCategory(food, "Groceries")

        // The record keeps its link; only the category's name changed.
        assertEquals(food, first.query.findLedgerRecord(lunch)!!.categoryId)
        assertEquals("Groceries", first.query.findCategory(food)!!.name)

        first.database.close()

        val second = open()
        val renamed = second.query.findCategory(food)!!
        assertEquals("Groceries", renamed.name)
        assertTrue(!renamed.archived)
        assertEquals(food, second.query.findLedgerRecord(lunch)!!.categoryId)

        // The renamed category is still offered for new assignments.
        assertTrue(second.query.categories(ALL_ASSIGNABLE_CATEGORIES).items.any { it.id == food })

        second.database.close()
    }

    private suspend fun expectRejection(write: suspend () -> Unit): LedgerValidationException =
        try {
            write()
            throw AssertionError("Expected the write to be rejected.")
        } catch (rejection: LedgerValidationException) {
            rejection
        }

    private companion object {
        const val CREATED_AT = 1_700_000_500_000L
        const val EDITED_AT = 1_700_000_900_000L
    }
}
