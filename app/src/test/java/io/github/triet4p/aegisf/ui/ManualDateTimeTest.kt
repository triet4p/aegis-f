package io.github.triet4p.aegisf.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * Device-local date/time entry: the wall-clock moment the user typed must become that exact
 * instant on the device's clock, and a malformed field must be refused instead of corrected.
 *
 * The fixed UTC+7 zone (no daylight saving) is used as an independent oracle: it is not the
 * host's default zone, so a conversion that silently reinterpreted the typed time as UTC —
 * the classic off-by-zone bug — cannot accidentally agree with it.
 */
class ManualDateTimeTest {

    private val hoChiMinh = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
    private val hoChiMinhZoneId = ZoneId.of("Asia/Ho_Chi_Minh")

    @Test
    fun `a typed wall-clock moment becomes that instant in the device zone`() {
        val storedMillis = ManualDateTime.toEpochMillis("2026-10-15", "18:30", hoChiMinh)

        assertEquals(
            LocalDateTime.parse("2026-10-15T18:30")
                .atZone(hoChiMinhZoneId)
                .toInstant()
                .toEpochMilli(),
            storedMillis,
        )
    }

    @Test
    fun `the typed time is not reinterpreted as UTC`() {
        val storedMillis = ManualDateTime.toEpochMillis("2026-10-15", "18:30", hoChiMinh)
        val asUtcMillis = LocalDateTime.parse("2026-10-15T18:30")
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()

        assertNotEquals(asUtcMillis, storedMillis)
        assertEquals(asUtcMillis - 7L * 60L * 60L * 1000L, storedMillis)
    }

    @Test
    fun `a stored moment round-trips back to the same date and time fields`() {
        val storedMillis = ManualDateTime.toEpochMillis("2026-01-02", "03:04", hoChiMinh)

        assertEquals("2026-01-02", ManualDateTime.dateFieldText(storedMillis, hoChiMinh))
        assertEquals("03:04", ManualDateTime.timeFieldText(storedMillis, hoChiMinh))
        assertEquals(
            "2026-01-02 03:04",
            ManualDateTime.formatMoment(storedMillis, hoChiMinh),
        )
    }

    @Test
    fun `a moment near midnight belongs to its own device-local day`() {
        // 2026-10-15 23:30 in UTC+7 is already 2026-10-16 in UTC, and still the 15th locally.
        val storedMillis = ManualDateTime.toEpochMillis("2026-10-15", "23:30", hoChiMinh)

        assertEquals("2026-10-15", ManualDateTime.dateFieldText(storedMillis, hoChiMinh))
        assertEquals(
            Instant.ofEpochMilli(storedMillis).atZone(hoChiMinhZoneId).dayOfMonth,
            15,
        )
    }

    @Test
    fun `valid fields are accepted and invalid ones are refused by field`() {
        assertNull(ManualDateTime.dateError("2026-10-15"))
        assertNull(ManualDateTime.dateError("2026-02-28"))
        assertNull(ManualDateTime.timeError("00:00"))
        assertNull(ManualDateTime.timeError("23:59"))

        assertTrue(ManualDateTime.dateError("").isNullOrBlank().not())
        assertTrue(ManualDateTime.dateError("2026-02-30").isNullOrBlank().not())
        assertTrue(ManualDateTime.dateError("2026-13-01").isNullOrBlank().not())
        assertTrue(ManualDateTime.dateError("15-10-2026").isNullOrBlank().not())
        assertTrue(ManualDateTime.dateError("2026/10/15").isNullOrBlank().not())
        assertTrue(ManualDateTime.dateError("2026-10-15 ").isNullOrBlank().not())
        assertTrue(ManualDateTime.timeError("").isNullOrBlank().not())
        assertTrue(ManualDateTime.timeError("25:00").isNullOrBlank().not())
        assertTrue(ManualDateTime.timeError("18:60").isNullOrBlank().not())
        assertTrue(ManualDateTime.timeError("18:30:45").isNullOrBlank().not())
    }
}
