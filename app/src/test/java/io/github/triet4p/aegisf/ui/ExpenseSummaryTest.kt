package io.github.triet4p.aegisf.ui

import io.github.triet4p.aegisf.data.ConfirmationStatus
import io.github.triet4p.aegisf.data.RecordSource
import io.github.triet4p.aegisf.data.TransactionEntity
import io.github.triet4p.aegisf.data.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * The month summary's inclusion rules and its money boundary, pinned against real epoch
 * millis and real whole-dong amounts.
 *
 * These are the mistakes that would be silently wrong for the user: income or an internal
 * transfer inflating spending, a draft counted before it is confirmed, a neighbouring month's
 * record counted, a month boundary taken as UTC instead of the device's own zone, an
 * uncategorised expense losing its own row, and a total that wraps past the largest
 * whole-dong value. [summarizeMonthExpenses] and the month bounds it is given are therefore
 * checked directly; rendering and the physical phone gate stay separate.
 */
class ExpenseSummaryTest {

    private val hoChiMinh = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
    private val newYork = TimeZone.getTimeZone("America/New_York")

    private fun at(date: String, time: String, zone: TimeZone): Long =
        ManualDateTime.toEpochMillis(date, time, zone)

    /** The half-open device-local month containing that moment, as the panel computes it. */
    private fun monthOf(date: String, time: String, zone: TimeZone): Pair<Long, Long> {
        val inside = at(date, time, zone)
        return ManualDateTime.monthStartEpochMillis(inside, zone) to
            ManualDateTime.nextMonthStartEpochMillis(inside, zone)
    }

    private fun record(
        id: Long,
        amountVnd: Long,
        occurredAtEpochMillis: Long,
        type: TransactionType = TransactionType.EXPENSE,
        confirmation: ConfirmationStatus = ConfirmationStatus.CONFIRMED,
        categoryId: Long? = null,
        source: RecordSource = RecordSource.MANUAL,
    ) = TransactionEntity(
        id = id,
        type = type,
        amountVnd = amountVnd,
        occurredAtEpochMillis = occurredAtEpochMillis,
        description = "Record $id",
        categoryId = categoryId,
        confirmation = confirmation,
        source = source,
        createdAtEpochMillis = occurredAtEpochMillis,
        updatedAtEpochMillis = occurredAtEpochMillis,
    )

    @Test
    fun `only confirmed expenses of the month enter the total and its breakdown`() {
        val (monthStart, monthEnd) = monthOf("2026-10-15", "12:00", hoChiMinh)
        val transport = 8L

        val summary = summarizeMonthExpenses(
            records = listOf(
                record(1, 45_000L, at("2026-10-02", "09:00", hoChiMinh)),
                record(
                    id = 2,
                    amountVnd = 120_000L,
                    occurredAtEpochMillis = at("2026-10-15", "12:00", hoChiMinh),
                    categoryId = transport,
                ),
                record(
                    id = 3,
                    amountVnd = 1_000_000L,
                    occurredAtEpochMillis = at("2026-10-15", "13:00", hoChiMinh),
                    type = TransactionType.INCOME,
                ),
                record(
                    id = 4,
                    amountVnd = 500_000L,
                    occurredAtEpochMillis = at("2026-10-15", "14:00", hoChiMinh),
                    type = TransactionType.TRANSFER,
                ),
                record(
                    id = 5,
                    amountVnd = 320_000L,
                    occurredAtEpochMillis = at("2026-10-15", "15:00", hoChiMinh),
                    confirmation = ConfirmationStatus.DRAFT,
                    source = RecordSource.BANK_NOTIFICATION,
                ),
                record(6, 99_000L, at("2026-09-30", "23:59", hoChiMinh)),
                record(7, 77_000L, at("2026-11-01", "00:00", hoChiMinh)),
            ),
            monthStartEpochMillis = monthStart,
            monthEndExclusiveEpochMillis = monthEnd,
        )

        assertEquals(monthStart, summary.monthStartEpochMillis)
        assertEquals(monthEnd, summary.monthEndExclusiveEpochMillis)
        assertEquals(2, summary.expenseCount)
        assertEquals(165_000L, summary.totalVnd)
        assertFalse(summary.totalOverflowed)

        // The largest bucket first, and the uncategorised expense keeps a bucket of its own:
        // its share of the month is never folded into a named category or dropped.
        assertEquals(
            listOf(
                CategoryExpenseTotal(
                    categoryId = transport,
                    totalVnd = 120_000L,
                    overflowed = false,
                    recordCount = 1,
                ),
                CategoryExpenseTotal(
                    categoryId = null,
                    totalVnd = 45_000L,
                    overflowed = false,
                    recordCount = 1,
                ),
            ),
            summary.categoryTotals,
        )
    }

    @Test
    fun `month membership is the device-local half-open month in either zone`() {
        listOf(hoChiMinh, newYork).forEach { zone ->
            val (monthStart, monthEnd) = monthOf("2026-10-15", "12:00", zone)

            // The bounds are this zone's own October: local midnight on the 1st and local
            // midnight on the 1st of the following month, not UTC instants.
            assertEquals("2026-10-01", ManualDateTime.dateFieldText(monthStart, zone))
            assertEquals("00:00", ManualDateTime.timeFieldText(monthStart, zone))
            assertEquals("2026-11-01", ManualDateTime.dateFieldText(monthEnd, zone))
            assertEquals("00:00", ManualDateTime.timeFieldText(monthEnd, zone))

            val summary = summarizeMonthExpenses(
                records = listOf(
                    record(1, 1L, monthStart - 1L),
                    record(2, 2L, monthStart),
                    record(3, 4L, monthEnd - 1L),
                    record(4, 8L, monthEnd),
                ),
                monthStartEpochMillis = monthStart,
                monthEndExclusiveEpochMillis = monthEnd,
            )

            // 23:59:59.999 on 30 September and midnight on 1 November are out; the first
            // instant of the month and its last millisecond are in.
            assertEquals(2, summary.expenseCount)
            assertEquals(6L, summary.totalVnd)
            assertEquals(listOf(null), summary.categoryTotals.map { it.categoryId })
            assertEquals(listOf(2), summary.categoryTotals.map { it.recordCount })
        }
    }

    @Test
    fun `a month is one calendar month in the device zone, not a fixed thirty days`() {
        val (octoberStart, novemberStart) = monthOf("2026-10-15", "12:00", hoChiMinh)
        assertEquals(31L * DAY_MILLIS, novemberStart - octoberStart)

        // New York's March 2026 loses one hour to daylight saving, so that month is 31 days
        // minus an hour: a fixed-duration step would misplace records at the boundary.
        val (marchStart, aprilStart) = monthOf("2026-03-15", "12:00", newYork)
        assertEquals("2026-03-01", ManualDateTime.dateFieldText(marchStart, newYork))
        assertEquals("2026-04-01", ManualDateTime.dateFieldText(aprilStart, newYork))
        assertEquals(31L * DAY_MILLIS - 60L * 60L * 1000L, aprilStart - marchStart)

        val march = summarizeMonthExpenses(
            records = listOf(
                record(1, 5L, marchStart),
                record(2, 7L, aprilStart - 1L),
                record(3, 11L, aprilStart),
            ),
            monthStartEpochMillis = marchStart,
            monthEndExclusiveEpochMillis = aprilStart,
        )
        assertEquals(2, march.expenseCount)
        assertEquals(12L, march.totalVnd)
    }

    @Test
    fun `a total at the largest whole dong value stays exact and one dong more is reported`() {
        val (monthStart, monthEnd) = monthOf("2026-10-15", "12:00", hoChiMinh)
        val half = Long.MAX_VALUE / 2
        val bucket = 3L

        // half + half + 1 is exactly Long.MAX_VALUE, the last whole-dong total there is.
        val exact = summarizeMonthExpenses(
            records = listOf(
                record(1, half, at("2026-10-05", "09:00", hoChiMinh), categoryId = bucket),
                record(2, half, at("2026-10-06", "09:00", hoChiMinh), categoryId = bucket),
                record(3, 1L, at("2026-10-07", "09:00", hoChiMinh), categoryId = bucket),
            ),
            monthStartEpochMillis = monthStart,
            monthEndExclusiveEpochMillis = monthEnd,
        )
        assertEquals(Long.MAX_VALUE, exact.totalVnd)
        assertFalse(exact.totalOverflowed)
        assertEquals(3, exact.expenseCount)
        assertEquals(Long.MAX_VALUE, exact.categoryTotals.single().totalVnd)
        assertFalse(exact.categoryTotals.single().overflowed)

        // One dong more cannot be a Long, so it is reported as the overflow state with a
        // proven lower bound instead of wrapping to a small or negative total.
        val overflowed = summarizeMonthExpenses(
            records = listOf(
                record(1, half, at("2026-10-05", "09:00", hoChiMinh), categoryId = bucket),
                record(2, half, at("2026-10-06", "09:00", hoChiMinh), categoryId = bucket),
                record(3, 1L, at("2026-10-07", "09:00", hoChiMinh), categoryId = bucket),
                record(4, 1L, at("2026-10-08", "09:00", hoChiMinh), categoryId = bucket),
            ),
            monthStartEpochMillis = monthStart,
            monthEndExclusiveEpochMillis = monthEnd,
        )
        assertTrue(overflowed.totalOverflowed)
        assertEquals(Long.MAX_VALUE, overflowed.totalVnd)
        assertTrue(overflowed.totalVnd > 0L)
        assertEquals(4, overflowed.expenseCount)
        assertEquals(Long.MAX_VALUE, overflowed.categoryTotals.single().totalVnd)
        assertTrue(overflowed.categoryTotals.single().overflowed)
    }

    @Test
    fun `a month without confirmed expenses reports zero and no breakdown`() {
        val (monthStart, monthEnd) = monthOf("2026-10-15", "12:00", hoChiMinh)
        val nothingThatCounts = listOf(
            record(
                id = 1,
                amountVnd = 1_000_000L,
                occurredAtEpochMillis = at("2026-10-05", "09:00", hoChiMinh),
                type = TransactionType.INCOME,
            ),
            record(
                id = 2,
                amountVnd = 500_000L,
                occurredAtEpochMillis = at("2026-10-06", "09:00", hoChiMinh),
                type = TransactionType.TRANSFER,
            ),
            record(
                id = 3,
                amountVnd = 320_000L,
                occurredAtEpochMillis = at("2026-10-07", "09:00", hoChiMinh),
                confirmation = ConfirmationStatus.DRAFT,
            ),
            record(4, 45_000L, at("2026-09-30", "23:59", hoChiMinh)),
        )

        listOf(emptyList(), nothingThatCounts).forEach { records ->
            val summary = summarizeMonthExpenses(records, monthStart, monthEnd)
            assertEquals(0, summary.expenseCount)
            assertEquals(0L, summary.totalVnd)
            assertFalse(summary.totalOverflowed)
            assertTrue(summary.categoryTotals.isEmpty())
        }
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    }
}
