package io.github.triet4p.aegisf.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The ledger's description search is a literal substring, not a pattern.
 *
 * The user types words they remember — a merchant, a note, a label — and the ledger must
 * return exactly the records whose stored description contains them. `%` and `_` are the
 * characters the user typed and never wildcards, an empty search is no filter at all, and
 * the search composes with the other dimensions and the page bounds instead of replacing
 * them.
 *
 * The database is the production Room/SQLite file, opened through the production builder,
 * so the test pins the statement the screens actually read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LedgerSearchTest {

    private lateinit var context: Context
    private lateinit var database: LedgerDatabase
    private lateinit var repository: LedgerRepository
    private lateinit var query: WorkflowQueryService

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(LedgerDatabase.NAME)
        database = LedgerDatabase.build(context)
        repository = LedgerRepository(
            categoryDao = database.categoryDao(),
            transactionDao = database.transactionDao(),
            rememberedCategoryRuleDao = database.rememberedCategoryRuleDao(),
            captureMessageDao = database.captureMessageDao(),
            clock = LedgerClock { 1_700_000_000_000L },
        )
        query = WorkflowQueryService(database)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(LedgerDatabase.NAME)
    }

    private fun add(
        description: String,
        type: TransactionType = TransactionType.EXPENSE,
        amountVnd: Long = 10_000L,
        occurredAtEpochMillis: Long = 1_700_000_000_000L,
    ): Long = runBlocking {
        repository.addTransaction(
            TransactionInput(
                type = type,
                amountVnd = amountVnd,
                occurredAtEpochMillis = occurredAtEpochMillis,
                description = description,
            ),
        )
    }

    private fun search(
        text: String?,
        type: TransactionType? = null,
        bounds: QueryPageRequest = QueryPageRequest(LIST_PAGE_SIZE),
    ): QueryPage<TransactionEntity> = runBlocking {
        query.ledgerRecords(
            LedgerRecordQuery(
                type = type,
                searchText = text,
                bounds = bounds,
            ),
        )
    }

    @Test
    fun `search matches a literal substring of the description`() {
        add("Phở Thìn Bờ Hồ")
        add("Highlands Coffee")
        add("WinMart groceries")

        assertEquals(listOf("Phở Thìn Bờ Hồ"), search("Thìn").items.map { it.description })
        assertEquals(listOf("WinMart groceries"), search("Mart").items.map { it.description })
    }

    @Test
    fun `search folds ASCII case and keeps the stored text`() {
        add("Highlands Coffee")

        assertEquals(listOf("Highlands Coffee"), search("COFFEE").items.map { it.description })
        assertEquals(listOf("Highlands Coffee"), search("coffee").items.map { it.description })
    }

    @Test
    fun `percent and underscore are characters the user typed`() {
        add("50% off")
        add("a_b transfer")
        add("axb transfer")

        assertEquals(listOf("50% off"), search("%").items.map { it.description })
        assertEquals(listOf("a_b transfer"), search("a_b").items.map { it.description })
        assertEquals(listOf("a_b transfer"), search("_").items.map { it.description })
    }

    @Test
    fun `blank and whitespace searches keep the full list`() {
        add("Phở Thìn Bờ Hồ")
        add("Highlands Coffee")

        assertEquals(2, search(null).items.size)
        assertEquals(2, search("").items.size)
        assertEquals(2, search("   ").items.size)
    }

    @Test
    fun `search composes with a type dimension`() {
        add("Salary October", type = TransactionType.INCOME)
        add("Salary advance", type = TransactionType.EXPENSE)
        add("Groceries", type = TransactionType.EXPENSE)

        val incomeMatches = search("salary", type = TransactionType.INCOME)
        assertEquals(listOf("Salary October"), incomeMatches.items.map { it.description })
        assertFalse(incomeMatches.hasMore)
    }

    @Test
    fun `search keeps the page bounds and reports more rows`() {
        repeat(3) { index ->
            add("S103 page $index", occurredAtEpochMillis = 1_700_000_000_000L + index)
        }

        val firstPage = search("S103 page", bounds = QueryPageRequest(2))
        assertEquals(2, firstPage.items.size)
        assertTrue(firstPage.hasMore)
        assertEquals(2, firstPage.nextOffset)

        val secondPage = search("S103 page", bounds = QueryPageRequest(2, offset = 2))
        assertEquals(1, secondPage.items.size)
        assertFalse(secondPage.hasMore)
    }
}
