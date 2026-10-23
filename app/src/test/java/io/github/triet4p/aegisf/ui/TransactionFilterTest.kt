package io.github.triet4p.aegisf.ui

import io.github.triet4p.aegisf.data.LedgerCategoryFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * Browsing-filter behaviour that is easy to get wrong and visible to the user: the
 * half-open inclusive-From/Through convention in the device zone, refused date ranges that
 * must not silently broaden, and combined dimensions that keep every constrained one.
 *
 * These tests pin [resolveDateFilter] directly — the same function the saved-transactions
 * list calls — against real epoch millis, so a boundary mistake fails here. The record
 * dimensions themselves are applied by the shared query surface and are pinned there.
 * Rendering, scrolling, and the physical phone gate stay user-run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransactionFilterTest {

    private val hoChiMinh = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
    private val hoChiMinhZoneId = ZoneId.of("Asia/Ho_Chi_Minh")
    private val newYork = TimeZone.getTimeZone("America/New_York")
    private val newYorkZoneId = ZoneId.of("America/New_York")

    private fun atTime(date: String, time: String, zone: TimeZone): Long =
        ManualDateTime.toEpochMillis(date, time, zone)

    @Test
    fun `a single Through day keeps records from its first minute to its last`() {
        val resolved = resolveDateFilter("", "2026-10-15", hoChiMinh)
        assertNull(resolved.fromError)
        assertNull(resolved.throughError)
        assertNull(resolved.rangeError)
        val range = resolved.range!!
        // The From end is open, so the start is null; the Through end bounds the day.
        assertNull(range.fromEpochMillis)

        val startOfDay = ManualDateTime.dayStartEpochMillis("2026-10-15", hoChiMinh)
        val startOfNext = range.throughExclusiveEpochMillis!!
        assertEquals(ManualDateTime.dayAfterStartEpochMillis("2026-10-15", hoChiMinh), startOfNext)
        assertTrue(startOfDay < startOfNext)

        // A record at the first minute of the 15th and one at the last minute are inside;
        // the first minute of the 16th is outside.
        assertTrue(atTime("2026-10-15", "00:00", hoChiMinh) >= startOfDay)
        assertTrue(atTime("2026-10-15", "23:59", hoChiMinh) < startOfNext)
        assertTrue(atTime("2026-10-16", "00:00", hoChiMinh) >= startOfNext)
        assertTrue(atTime("2026-10-14", "23:59", hoChiMinh) < startOfDay)
    }


    @Test
    fun `an inclusive From Through pair keeps both boundary days`() {
        val resolved = resolveDateFilter("2026-10-14", "2026-10-15", hoChiMinh)
        assertNull(resolved.rangeError)
        val range = resolved.range!!

        assertTrue(atTime("2026-10-14", "00:00", hoChiMinh) >= range.fromEpochMillis!!)
        assertTrue(atTime("2026-10-15", "23:59", hoChiMinh) < range.throughExclusiveEpochMillis!!)
        assertTrue(atTime("2026-10-13", "23:59", hoChiMinh) < range.fromEpochMillis!!)
        assertTrue(atTime("2026-10-16", "00:00", hoChiMinh) >= range.throughExclusiveEpochMillis!!)
    }

    @Test
    fun `an open From with a Through keeps everything up to the Through day`() {
        val resolved = resolveDateFilter("", "2026-10-15", hoChiMinh)
        val range = resolved.range!!

        assertNull(range.fromEpochMillis)
        assertTrue(atTime("2026-01-01", "00:00", hoChiMinh) < range.throughExclusiveEpochMillis!!)
        assertTrue(atTime("2026-10-16", "00:00", hoChiMinh) >= range.throughExclusiveEpochMillis!!)
    }

    @Test
    fun `an open Through with a From keeps everything from the From day on`() {
        val resolved = resolveDateFilter("2026-10-15", "", hoChiMinh)
        val range = resolved.range!!

        assertNull(range.throughExclusiveEpochMillis)
        assertTrue(atTime("2026-10-15", "00:00", hoChiMinh) >= range.fromEpochMillis!!)
        assertTrue(atTime("2026-10-14", "23:59", hoChiMinh) < range.fromEpochMillis!!)
        assertTrue(atTime("2027-01-01", "00:00", hoChiMinh) >= range.fromEpochMillis!!)
    }

    @Test
    fun `two empty ends are the full list, not a refusal`() {
        val resolved = resolveDateFilter("", "")

        assertNull(resolved.fromError)
        assertNull(resolved.throughError)
        assertNull(resolved.rangeError)
        val range = resolved.range!!
        assertNull(range.fromEpochMillis)
        assertNull(range.throughExclusiveEpochMillis)
        assertFalse(TransactionFilterState().isActive)
    }

    @Test
    fun `a malformed or reversed range is refused and never applied`() {
        val malformed = resolveDateFilter("2026-02-30", "2026-10-15", hoChiMinh)
        assertNotNull(malformed.fromError)
        assertNull(malformed.throughError)
        assertNull(malformed.range)

        val reversed = resolveDateFilter("2026-10-16", "2026-10-15", hoChiMinh)
        assertNull(reversed.fromError)
        assertNull(reversed.throughError)
        assertNotNull(reversed.rangeError)
        assertNull(reversed.range)

        val neighbouring = resolveDateFilter("2026-10-15", "2026-10-14", hoChiMinh)
        assertNotNull(neighbouring.rangeError)
        assertNull(neighbouring.range)
    }

    @Test
    fun `non-zero-padded dates are ordered by calendar day and preserve their boundaries`() {
        val chronological = resolveDateFilter("2026-9-30", "2026-10-1", hoChiMinh)
        assertNull(chronological.fromError)
        assertNull(chronological.throughError)
        assertNull(chronological.rangeError)
        val range = chronological.range!!
        val firstDay = LocalDate.of(2026, 9, 30)
        val dayAfterThrough = LocalDate.of(2026, 10, 2)
        val startOfFrom = firstDay.atStartOfDay(hoChiMinhZoneId).toInstant().toEpochMilli()
        val startAfterThrough =
            dayAfterThrough.atStartOfDay(hoChiMinhZoneId).toInstant().toEpochMilli()
        assertEquals(startOfFrom, range.fromEpochMillis)
        assertEquals(startAfterThrough, range.throughExclusiveEpochMillis)
        assertTrue(atTime("2026-9-29", "23:59", hoChiMinh) < startOfFrom)
        assertTrue(atTime("2026-9-30", "00:00", hoChiMinh) >= startOfFrom)
        assertTrue(atTime("2026-10-1", "23:59", hoChiMinh) < startAfterThrough)
        assertTrue(atTime("2026-10-2", "00:00", hoChiMinh) >= startAfterThrough)

        val reversedDayOfMonth = resolveDateFilter("2026-10-16", "2026-10-3", hoChiMinh)
        assertNotNull(reversedDayOfMonth.rangeError)
        assertNull(reversedDayOfMonth.range)

        val reversedMonthBoundary = resolveDateFilter("2026-10-1", "2026-9-30", hoChiMinh)
        assertNotNull(reversedMonthBoundary.rangeError)
        assertNull(reversedMonthBoundary.range)
    }


    @Test
    fun `a daylight-saving start day contributes its actual 23 hours, not 24`() {
        // America/New_York springs forward on 2026-03-08: that local day is 23 hours long.
        val resolved = resolveDateFilter("2026-03-08", "2026-03-08", newYork)
        val range = resolved.range!!
        val dayLength = range.throughExclusiveEpochMillis!! - range.fromEpochMillis!!

        assertEquals(23L * 60L * 60L * 1000L, dayLength)
        assertEquals(
            LocalDate.parse("2026-03-08").atStartOfDay(newYorkZoneId).toInstant().toEpochMilli(),
            range.fromEpochMillis,
        )
    }

    @Test
    fun `a daylight-saving end day contributes its actual 25 hours, not 24`() {
        // America/New_York falls back on 2026-11-01: that local day is 25 hours long.
        val resolved = resolveDateFilter("2026-11-01", "2026-11-01", newYork)
        val range = resolved.range!!
        val dayLength = range.throughExclusiveEpochMillis!! - range.fromEpochMillis!!

        assertEquals(25L * 60L * 60L * 1000L, dayLength)
    }

    @Test
    fun `a non UTC zone keeps a late local record on its own day`() {
        val resolved = resolveDateFilter("2026-10-15", "2026-10-15", hoChiMinh)
        val range = resolved.range!!

        // 2026-10-15 23:30 in UTC+7 is already 2026-10-16 in UTC, but it belongs to the
        // 15th under the device-zone convention.
        val lateLocal = atTime("2026-10-15", "23:30", hoChiMinh)
        assertTrue(lateLocal >= range.fromEpochMillis!!)
        assertTrue(lateLocal < range.throughExclusiveEpochMillis!!)
        assertEquals("2026-10-15", ManualDateTime.dateFieldText(lateLocal, hoChiMinh))
    }

    @Test
    fun `a category selection names a known category and an unknown id names itself unknown`() {
        val selection = LedgerCategoryFilter.Specific(10L)
        assertEquals(
            "Food",
            categorySelectionLabelForTest(
                selection,
                listOf(
                    io.github.triet4p.aegisf.data.CategoryEntity(
                        id = 10L,
                        name = "Food",
                        archived = false,
                        createdAtEpochMillis = 1L,
                        updatedAtEpochMillis = 1L,
                    ),
                ),
            ),
        )
        assertEquals(
            "Unknown category",
            categorySelectionLabelForTest(selection, emptyList()),
        )
    }

    // The label helper is private to the list screen; this local copy asserts the tested
    // contract (an unknown id names itself unknown) without reaching into composition.
    private fun categorySelectionLabelForTest(
        selection: LedgerCategoryFilter,
        categories: List<io.github.triet4p.aegisf.data.CategoryEntity>,
    ): String = when (selection) {
        is LedgerCategoryFilter.All -> "All categories"
        is LedgerCategoryFilter.Uncategorized -> "No category"
        is LedgerCategoryFilter.Specific -> categories.firstOrNull { it.id == selection.categoryId }
            ?.let { io.github.triet4p.aegisf.ui.categoryLabel(it) }
            ?: "Unknown category"
    }
}
