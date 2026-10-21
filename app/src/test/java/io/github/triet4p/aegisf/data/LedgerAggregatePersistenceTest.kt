package io.github.triet4p.aegisf.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.triet4p.aegisf.ui.ManualDateTime
import io.github.triet4p.aegisf.ui.categoryLabel
import io.github.triet4p.aegisf.data.ConfirmationStatus
import io.github.triet4p.aegisf.data.LedgerClock
import io.github.triet4p.aegisf.data.LedgerDatabase
import io.github.triet4p.aegisf.data.LedgerRepository
import io.github.triet4p.aegisf.data.RecordSource
import io.github.triet4p.aegisf.data.TransactionCorrection
import io.github.triet4p.aegisf.data.TransactionEntity
import io.github.triet4p.aegisf.data.TransactionInput
import io.github.triet4p.aegisf.data.TransactionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.TimeZone

/**
 * The month summary against the real database file, through the stream the panel collects.
 *
 * No fake DAO and no mock: the database is a file in the application's own database directory,
 * opened through the production builder [LedgerDatabase.build], and each summary is built from
 * an emission of `observeTransactions()` — the same path the home screen uses. The sprint's
 * acceptance scenario is the first test: two expenses of 45,000 and 120,000 are 165,000 while
 * income, an internal transfer, a draft, and neighbouring-month records are present but
 * excluded; correcting the second to 125,000 gives 170,000; renaming and archiving its
 * category leaves that spending attached to the same bucket; deleting the first gives 125,000;
 * and reopening the database reports the same figure.
 *
 * The second test drives a month total across the largest whole-dong value on real stored rows,
 * because that is the one case where a naive sum silently wraps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LedgerAggregatePersistenceTest {

    private class FixedLedgerClock(var now: Long = CREATED_AT) : LedgerClock {
        override fun nowEpochMillis(): Long = now
    }

    private class Ledger(
        val database: LedgerDatabase,
        val repository: LedgerRepository,
        val query: WorkflowQueryService,
    )

    private val zone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
    private val monthStartEpochMillis =
        ManualDateTime.monthStartEpochMillis(at("2026-10-15", "12:00"), zone)
    private val monthEndExclusiveEpochMillis =
        ManualDateTime.nextMonthStartEpochMillis(monthStartEpochMillis, zone)

    private lateinit var context: Context
    private lateinit var clock: FixedLedgerClock

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(LedgerDatabase.NAME)
        clock = FixedLedgerClock()
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

    private fun at(date: String, time: String): Long =
        ManualDateTime.toEpochMillis(date, time, zone)

    private fun summarize(rows: List<TransactionEntity>) =
        summarizeConfirmedSpending(rows, monthStartEpochMillis, monthEndExclusiveEpochMillis)

    @Test
    fun `a correction, a rename, an archive, and a deletion refresh the month summary`() =
        runBlocking {
            val first = open()
            val transport = first.repository.addCategory("Transport")

            val lunch = first.repository.addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountVnd = 45_000L,
                    occurredAtEpochMillis = at("2026-10-02", "09:00"),
                    description = "Lunch",
                ),
            )
            val coffee = first.repository.addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountVnd = 120_000L,
                    occurredAtEpochMillis = at("2026-10-15", "12:00"),
                    description = "Coffee beans",
                    categoryId = transport,
                ),
            )
            first.repository.addTransaction(
                TransactionInput(
                    type = TransactionType.INCOME,
                    amountVnd = 1_000_000L,
                    occurredAtEpochMillis = at("2026-10-15", "13:00"),
                    description = "Salary",
                ),
            )
            first.repository.addTransaction(
                TransactionInput(
                    type = TransactionType.TRANSFER,
                    amountVnd = 500_000L,
                    occurredAtEpochMillis = at("2026-10-15", "14:00"),
                    description = "Move to savings",
                ),
            )
            first.repository.addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountVnd = 320_000L,
                    occurredAtEpochMillis = at("2026-10-15", "15:00"),
                    description = "Electricity bill",
                    confirmation = ConfirmationStatus.DRAFT,
                    source = RecordSource.BANK_NOTIFICATION,
                ),
            )
            first.repository.addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountVnd = 99_000L,
                    occurredAtEpochMillis = at("2026-09-30", "23:59"),
                    description = "Last month",
                ),
            )
            first.repository.addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountVnd = 77_000L,
                    occurredAtEpochMillis = at("2026-11-01", "00:00"),
                    description = "Next month",
                ),
            )

            // One subscription for the whole test, over the real Room stream: a summary that
            // only changed when the screen asked again would be a stale figure. Room re-emits
            // on real threads, so this waits in real time and looks for content, never for an
            // emission index.
            val summaries = Collections.synchronizedList(mutableListOf<LedgerSpending>())
            val collector = launch(Dispatchers.IO) {
                first.query.observeLedgerRecords(ALL_LEDGER_RECORDS).map { it.items }.collect { rows -> summaries += summarize(rows) }
            }

            suspend fun awaitSummary(predicate: (LedgerSpending) -> Boolean) {
                withTimeout(15_000) {
                    while (summaries.none(predicate)) delay(20)
                }
            }

            awaitSummary { it.recordCount == 2 && it.totalVnd == 165_000L }
            val seeded = summaries.last { it.recordCount == 2 && it.totalVnd == 165_000L }
            assertFalse(seeded.overflowed)
            assertEquals(
                listOf(transport to 120_000L, null to 45_000L),
                seeded.categoryTotals.map { it.categoryId to it.totalVnd },
            )

            // Correcting 120,000 to 125,000 must show 170,000 with the same two buckets.
            clock.now = EDITED_AT
            first.repository.editTransaction(
                coffee,
                0,
                TransactionCorrection(
                    type = TransactionType.EXPENSE,
                    amountVnd = 125_000L,
                    occurredAtEpochMillis = at("2026-10-15", "12:00"),
                    description = "Coffee beans and filter",
                    categoryId = transport,
                ),
            )
            awaitSummary { it.recordCount == 2 && it.totalVnd == 170_000L }
            val corrected = summaries.last { it.totalVnd == 170_000L }
            assertEquals(
                listOf(transport to 125_000L, null to 45_000L),
                corrected.categoryTotals.map { it.categoryId to it.totalVnd },
            )

            // Renaming and archiving touch the category row, not the stored records: the
            // spending keeps the same stable bucket and the screen names it as archived.
            first.repository.renameCategory(transport, "Travel")
            first.repository.archiveCategory(transport)
            val archivedCategory = first.query.findCategory(transport)!!
            assertTrue(archivedCategory.archived)
            assertEquals("Travel", archivedCategory.name)
            assertEquals("Travel (archived)", categoryLabel(archivedCategory))
            assertEquals(transport, first.query.findLedgerRecord(coffee)!!.categoryId)
            val afterArchive = summarize(first.query.ledgerRecords(ALL_LEDGER_RECORDS).items)
            assertEquals(2, afterArchive.recordCount)
            assertEquals(170_000L, afterArchive.totalVnd)
            assertEquals(
                listOf(transport to 125_000L, null to 45_000L),
                afterArchive.categoryTotals.map { it.categoryId to it.totalVnd },
            )

            first.repository.deleteTransaction(lunch)
            awaitSummary { it.recordCount == 1 && it.totalVnd == 125_000L }
            val afterDelete = summaries.last { it.recordCount == 1 }
            assertEquals(listOf(transport), afterDelete.categoryTotals.map { it.categoryId })
            assertEquals(125_000L, afterDelete.categoryTotals.single().totalVnd)

            collector.cancelAndJoin()
            first.database.close()

            // Closing and reopening is what the process does when Android restarts it: the
            // summary is derived from the stored rows, so it cannot be stale or a second copy.
            val second = open()
            val afterRestart = summarize(second.query.ledgerRecords(ALL_LEDGER_RECORDS).items)
            assertEquals(1, afterRestart.recordCount)
            assertEquals(125_000L, afterRestart.totalVnd)
            assertEquals(listOf(transport), afterRestart.categoryTotals.map { it.categoryId })
            assertEquals("Travel (archived)", categoryLabel(second.query.findCategory(transport)))
            second.database.close()
        }

    /**
     * Month edges survive a real file close and reopen with every S2.8 invariant intact.
     *
     * Four records sit on the device-local edges of the October 2026 month: the last
     * millisecond of September is out, the first instant of October is in, the last
     * millisecond of October is in, and the first instant of November is out. One of the
     * two in-month expenses is then corrected across the edge into September, the category
     * is archived while still attributed to the remaining in-month record, and the other
     * in-month-adjacent record is deleted — and the database is closed and reopened, which
     * is what the process does when Android restarts it, before the final summary is read.
     * This is the one combination no earlier test covers: accepted edit/archive/delete and
     * inclusion behaviour evaluated at month edges after a real restart.
     */
    @Test
    fun `month edges with an edit an archive and a deletion survive close and reopen`() =
        runBlocking {
            val first = open()
            val edges = first.repository.addCategory("S2.8 edges")

            val septemberEdge = first.repository.addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountVnd = 9_000L,
                    occurredAtEpochMillis = monthStartEpochMillis - 1L,
                    description = "S2.8 September edge",
                    categoryId = edges,
                ),
            )
            val octoberStart = first.repository.addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountVnd = 11_000L,
                    occurredAtEpochMillis = monthStartEpochMillis,
                    description = "S2.8 October start",
                    categoryId = edges,
                ),
            )
            val octoberEnd = first.repository.addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountVnd = 13_000L,
                    occurredAtEpochMillis = monthEndExclusiveEpochMillis - 1L,
                    description = "S2.8 October end",
                    categoryId = edges,
                ),
            )
            first.repository.addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountVnd = 17_000L,
                    occurredAtEpochMillis = monthEndExclusiveEpochMillis,
                    description = "S2.8 November edge",
                    categoryId = edges,
                ),
            )

            val edgesOut = summarize(first.query.ledgerRecords(ALL_LEDGER_RECORDS).items)
            assertEquals(2, edgesOut.recordCount)
            assertEquals(24_000L, edgesOut.totalVnd)
            assertFalse(edgesOut.overflowed)
            assertEquals(2, edgesOut.categoryTotals.single().recordCount)

            // Move the October-start record across the edge into September: only the
            // October-end record still belongs to October.
            clock.now = EDITED_AT
            first.repository.editTransaction(
                octoberStart,
                0,
                TransactionCorrection(
                    type = TransactionType.EXPENSE,
                    amountVnd = 11_000L,
                    occurredAtEpochMillis = monthStartEpochMillis - 1L,
                    description = "S2.8 October start moved to September",
                    categoryId = edges,
                ),
            )
            val afterEdit = summarize(first.query.ledgerRecords(ALL_LEDGER_RECORDS).items)
            assertEquals(1, afterEdit.recordCount)
            assertEquals(13_000L, afterEdit.totalVnd)

            // Archive the category and delete the September-edge record: the archived link
            // stays on the remaining October record, and the deletion removes only its row.
            first.repository.archiveCategory(edges)
            first.repository.deleteTransaction(septemberEdge)
            assertEquals(edges, first.query.findLedgerRecord(octoberEnd)!!.categoryId)
            first.database.close()

            val second = open()
            val afterRestart = summarize(second.query.ledgerRecords(ALL_LEDGER_RECORDS).items)
            assertEquals(1, afterRestart.recordCount)
            assertEquals(13_000L, afterRestart.totalVnd)
            assertFalse(afterRestart.overflowed)
            assertEquals(listOf(edges), afterRestart.categoryTotals.map { it.categoryId })
            assertEquals(13_000L, afterRestart.categoryTotals.single().totalVnd)
            assertEquals(1, afterRestart.categoryTotals.single().recordCount)
            val keptCategory = second.query.findCategory(edges)!!
            assertTrue(keptCategory.archived)
            assertEquals("S2.8 edges", keptCategory.name)
            assertEquals(edges, second.query.findLedgerRecord(octoberEnd)!!.categoryId)
            second.database.close()
        }

    @Test
    fun `a month at the largest whole dong value is exact and one dong more is reported`() =
        runBlocking {
            val first = open()
            val half = Long.MAX_VALUE / 2

            first.repository.addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountVnd = half,
                    occurredAtEpochMillis = at("2026-10-05", "09:00"),
                    description = "Half the ceiling",
                ),
            )
            first.repository.addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountVnd = half,
                    occurredAtEpochMillis = at("2026-10-06", "09:00"),
                    description = "The other half",
                ),
            )
            first.repository.addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountVnd = 1L,
                    occurredAtEpochMillis = at("2026-10-07", "09:00"),
                    description = "One dong",
                ),
            )

            val exact = summarize(first.query.ledgerRecords(ALL_LEDGER_RECORDS).items)
            assertEquals(3, exact.recordCount)
            assertEquals(Long.MAX_VALUE, exact.totalVnd)
            assertFalse(exact.overflowed)
            assertFalse(exact.categoryTotals.single().overflowed)

            first.repository.addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountVnd = 1L,
                    occurredAtEpochMillis = at("2026-10-08", "09:00"),
                    description = "One dong more",
                ),
            )

            val overflowed = summarize(first.query.ledgerRecords(ALL_LEDGER_RECORDS).items)
            assertEquals(4, overflowed.recordCount)
            assertEquals(Long.MAX_VALUE, overflowed.totalVnd)
            assertTrue(overflowed.overflowed)
            assertTrue(overflowed.categoryTotals.single().overflowed)

            // The refusal is a report, not a rewrite: every stored amount is still its own
            // exact whole-dong value.
            assertEquals(
                listOf(1L, 1L, half, half).sorted(),
                first.query.ledgerRecords(ALL_LEDGER_RECORDS).items.map { it.amountVnd }.sorted(),
            )
            first.database.close()
        }

    private companion object {
        const val CREATED_AT = 1_700_000_500_000L
        const val EDITED_AT = 1_700_000_900_000L
    }
}
