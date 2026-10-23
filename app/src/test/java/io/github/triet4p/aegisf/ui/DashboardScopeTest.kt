package io.github.triet4p.aegisf.ui

import io.github.triet4p.aegisf.data.LedgerCategoryFilter
import io.github.triet4p.aegisf.data.LedgerCategorySpending
import io.github.triet4p.aegisf.data.LedgerSpending
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * The Dashboard's scope: the closed range its figures are read over, and the category its
 * spending figure is narrowed to.
 *
 * These are the properties the Dashboard's own cards depend on: a scope that names both
 * days becomes one closed range whose end is the day after the last one, a scope that
 * does not names no range at all, and the narrowed spending figure is the aggregate's own
 * bucket — the archived bucket and the uncategorised bucket included — so the figure on a
 * card and the records behind it can never disagree.
 */
class DashboardScopeTest {

    private val zone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")

    private fun spending(): LedgerSpending = LedgerSpending(
        totalVnd = 195_000L,
        overflowed = false,
        recordCount = 3,
        categoryTotals = listOf(
            LedgerCategorySpending(
                categoryId = 7L,
                categoryName = "Food",
                categoryArchived = false,
                totalVnd = 165_000L,
                overflowed = false,
                recordCount = 2,
            ),
            LedgerCategorySpending(
                categoryId = 9L,
                categoryName = "Travel",
                categoryArchived = true,
                totalVnd = 30_000L,
                overflowed = false,
                recordCount = 1,
            ),
        ),
    )

    @Test
    fun `a scope with both days becomes one closed range`() {
        val resolved = resolveDashboardScope(
            DashboardScopeState(fromText = "2026-10-01", throughText = "2026-10-31"),
            zone,
        )

        assertTrue(resolved.isUsable)
        assertNotNull(resolved.range)
        val range = resolved.range!!
        assertEquals(zone.id, range.zoneId)
        assertEquals(ManualDateTime.dayStartEpochMillis("2026-10-01", zone), range.fromEpochMillis)
        // The range is half-open: it ends where the day after the last one begins.
        assertEquals(
            ManualDateTime.dayStartEpochMillis("2026-11-01", zone),
            range.throughExclusiveEpochMillis,
        )
        assertEquals("2026-10-01 through 2026-10-31", resolved.label)
    }

    @Test
    fun `one day is a range of that day only`() {
        val resolved = resolveDashboardScope(
            DashboardScopeState(fromText = "2026-10-15", throughText = "2026-10-15"),
            zone,
        )

        assertNotNull(resolved.range)
        val range = resolved.range!!
        assertEquals(ManualDateTime.dayStartEpochMillis("2026-10-15", zone), range.fromEpochMillis)
        assertEquals(
            ManualDateTime.dayStartEpochMillis("2026-10-16", zone),
            range.throughExclusiveEpochMillis,
        )
    }

    @Test
    fun `a scope without both days names no range`() {
        val withoutFrom = resolveDashboardScope(
            DashboardScopeState(fromText = "", throughText = "2026-10-31"),
            zone,
        )
        val withoutThrough = resolveDashboardScope(
            DashboardScopeState(fromText = "2026-10-01", throughText = ""),
            zone,
        )

        assertNull(withoutFrom.range)
        assertEquals("Choose a From date.", withoutFrom.fromError)
        assertNull(withoutThrough.range)
        assertEquals("Choose a Through date.", withoutThrough.throughError)
    }

    @Test
    fun `a malformed or reversed scope is refused`() {
        val malformed = resolveDashboardScope(
            DashboardScopeState(fromText = "2026-02-30", throughText = "2026-10-31"),
            zone,
        )
        val reversed = resolveDashboardScope(
            DashboardScopeState(fromText = "2026-10-31", throughText = "2026-10-01"),
            zone,
        )

        assertNull(malformed.range)
        assertNotNull(malformed.fromError)
        assertNull(reversed.range)
        assertEquals("The From date is after the Through date.", reversed.rangeError)
    }

    @Test
    fun `a leniently written day is ordered by the day it names`() {
        // "2026-10-3" sorts after "2026-10-16" as text although it names an earlier day.
        val reversedByText = resolveDashboardScope(
            DashboardScopeState(fromText = "2026-10-16", throughText = "2026-10-3"),
            zone,
        )
        // ...and "2026-9-30" sorts after "2026-10-1" as text although it names an earlier day.
        val earlierByText = resolveDashboardScope(
            DashboardScopeState(fromText = "2026-9-30", throughText = "2026-10-1"),
            zone,
        )

        assertNull(reversedByText.range)
        assertEquals("The From date is after the Through date.", reversedByText.rangeError)
        assertNotNull(earlierByText.range)
        assertEquals(
            ManualDateTime.dayStartEpochMillis("2026-09-30", zone),
            earlierByText.range!!.fromEpochMillis,
        )
        assertEquals(
            ManualDateTime.dayStartEpochMillis("2026-10-02", zone),
            earlierByText.range!!.throughExclusiveEpochMillis,
        )
    }

    @Test
    fun `compact label uses canonical dates while preserving non-padded input`() {
        val scope = DashboardScopeState(fromText = "2026-10-3", throughText = "2026-10-31")
        val resolved = resolveDashboardScope(scope, zone)

        assertTrue(resolved.isUsable)
        assertEquals("10/03–31", dashboardDateRangeControlLabel(scope, resolved))
        assertEquals("2026-10-3", scope.fromText)
        assertEquals("2026-10-03", resolved.canonicalFromText)
        assertEquals("2026-10-03 through 2026-10-31", resolved.label)

        val singleDigitMonthAndDay = DashboardScopeState(
            fromText = "2026-1-5",
            throughText = "2026-1-9",
        )
        val singleDigitResolved = resolveDashboardScope(singleDigitMonthAndDay, zone)
        assertTrue(singleDigitResolved.isUsable)
        assertEquals(
            "01/05–09",
            dashboardDateRangeControlLabel(singleDigitMonthAndDay, singleDigitResolved),
        )
    }

    @Test
    fun `compact label keeps padded same-month and cross-month-year formats`() {
        val sameMonth = DashboardScopeState(
            fromText = "2026-10-03",
            throughText = "2026-10-31",
        )
        val sameMonthResolved = resolveDashboardScope(sameMonth, zone)
        assertEquals("10/03–31", dashboardDateRangeControlLabel(sameMonth, sameMonthResolved))
        assertEquals("2026-10-03 through 2026-10-31", sameMonthResolved.label)

        val crossMonth = DashboardScopeState(
            fromText = "2026-9-30",
            throughText = "2026-10-1",
        )
        val crossMonthResolved = resolveDashboardScope(crossMonth, zone)
        assertEquals("09-30–10-01", dashboardDateRangeControlLabel(crossMonth, crossMonthResolved))
        assertEquals("2026-09-30 through 2026-10-01", crossMonthResolved.label)

        val crossYear = DashboardScopeState(
            fromText = "2026-12-31",
            throughText = "2027-1-2",
        )
        val crossYearResolved = resolveDashboardScope(crossYear, zone)
        assertEquals("12-31–01-02", dashboardDateRangeControlLabel(crossYear, crossYearResolved))
        assertEquals("2026-12-31 through 2027-01-02", crossYearResolved.label)
    }

    @Test
    fun `compact label leaves malformed and reversed input visible`() {
        val malformed = DashboardScopeState(
            fromText = "2026-2-30",
            throughText = "2026-10-31",
        )
        val malformedResolved = resolveDashboardScope(malformed, zone)
        assertNull(malformedResolved.range)
        assertNotNull(malformedResolved.fromError)
        assertEquals(
            "2026-2-30 → 2026-10-31",
            dashboardDateRangeControlLabel(malformed, malformedResolved),
        )
        assertEquals("2026-2-30 through 2026-10-31", malformedResolved.label)

        val reversed = DashboardScopeState(
            fromText = "2026-10-31",
            throughText = "2026-10-1",
        )
        val reversedResolved = resolveDashboardScope(reversed, zone)
        assertNull(reversedResolved.range)
        assertEquals("The From date is after the Through date.", reversedResolved.rangeError)
        assertEquals(
            "2026-10-31 → 2026-10-1",
            dashboardDateRangeControlLabel(reversed, reversedResolved),
        )
    }


    @Test
    fun `the spending of the whole scope is the aggregate's own spending`() {
        val scoped = scopedSpending(spending(), LedgerCategoryFilter.All)

        assertEquals(195_000L, scoped.totalVnd)
        assertEquals(3, scoped.recordCount)
        assertEquals(2, scoped.categoryTotals.size)
    }

    @Test
    fun `the spending of one category is that category's own bucket`() {
        val scoped = scopedSpending(spending(), LedgerCategoryFilter.Specific(7L))

        assertEquals(165_000L, scoped.totalVnd)
        assertEquals(2, scoped.recordCount)
        assertEquals(1, scoped.categoryTotals.size)
        assertEquals(7L, scoped.categoryTotals.single().categoryId)
    }

    @Test
    fun `an archived category keeps its spending and its marker`() {
        val scoped = scopedSpending(spending(), LedgerCategoryFilter.Specific(9L))

        assertEquals(30_000L, scoped.totalVnd)
        assertEquals(1, scoped.recordCount)
        val bucket = scoped.categoryTotals.single()
        assertEquals("Travel", bucket.categoryName)
        assertTrue(bucket.categoryArchived)
        assertEquals("Travel (archived)", spendingBucketLabel(bucket))
    }

    @Test
    fun `a category with no spending in the scope has an empty figure`() {
        val scoped = scopedSpending(spending(), LedgerCategoryFilter.Specific(404L))

        assertEquals(0L, scoped.totalVnd)
        assertEquals(0, scoped.recordCount)
        assertTrue(scoped.categoryTotals.isEmpty())
    }

    @Test
    fun `spending filed under no category is a bucket of its own`() {
        val withUncategorized = spending().copy(
            categoryTotals = spending().categoryTotals + LedgerCategorySpending(
                categoryId = null,
                categoryName = null,
                categoryArchived = false,
                totalVnd = 12_000L,
                overflowed = false,
                recordCount = 1,
            ),
        )

        val scoped = scopedSpending(withUncategorized, LedgerCategoryFilter.Uncategorized)

        assertEquals(12_000L, scoped.totalVnd)
        assertEquals(1, scoped.recordCount)
        assertEquals("No category", spendingBucketLabel(scoped.categoryTotals.single()))
    }

    @Test
    fun `the default scope is the device-local calendar month`() {
        val now = ManualDateTime.toEpochMillis("2026-10-15", "12:00", zone)
        val scope = DashboardScopeState.currentMonth(now, zone)

        assertEquals("2026-10-01", scope.fromText)
        assertEquals("2026-10-31", scope.throughText)
        assertTrue(resolveDashboardScope(scope, zone).isUsable)
        assertFalse(scope.category !is LedgerCategoryFilter.All)
    }

    @Test
    fun `chart bar width is safe for empty and bounded totals`() {
        assertEquals(0f, dashboardBarFraction(12_000L, 0L), 0f)
        assertEquals(0f, dashboardBarFraction(0L, 12_000L), 0f)
        assertEquals(0.5f, dashboardBarFraction(6_000L, 12_000L), 0.0001f)
        assertEquals(1f, dashboardBarFraction(24_000L, 12_000L), 0f)
    }

    @Test
    fun `a february scope ends on its own last day`() {
        val now = ManualDateTime.toEpochMillis("2026-02-10", "12:00", zone)
        val scope = DashboardScopeState.currentMonth(now, zone)

        assertEquals("2026-02-01", scope.fromText)
        assertEquals("2026-02-28", scope.throughText)
        val resolved = resolveDashboardScope(scope, zone)
        assertNotNull(resolved.range)
        val range = resolved.range!!
        assertEquals(
            ManualDateTime.dayStartEpochMillis("2026-03-01", zone),
            range.throughExclusiveEpochMillis,
        )
    }
}
