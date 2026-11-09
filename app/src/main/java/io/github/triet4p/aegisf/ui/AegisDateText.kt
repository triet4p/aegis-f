package io.github.triet4p.aegisf.ui

import io.github.triet4p.aegisf.data.AegisLanguage
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The date wording of the reports, ledger and receipt screens.
 *
 * Every day shown here is a device-local calendar day of the same zone the stored instants
 * were counted in, and the wording follows the interface language rather than the device
 * locale: switching the app to Vietnamese must switch the month and weekday words too,
 * while the underlying instants and ranges never move.
 */
internal fun aegisLocale(language: AegisLanguage): Locale = when (language) {
    AegisLanguage.ENGLISH -> Locale.US
    AegisLanguage.VIETNAMESE -> Locale.forLanguageTag("vi-VN")
}

/** "November 2026" / "Tháng 11/2026": the month the reports are looking at. */
internal fun monthYearText(
    epochMillis: Long,
    language: AegisLanguage,
    zone: TimeZone = TimeZone.getDefault(),
): String = formatter(
    pattern = when (language) {
        AegisLanguage.ENGLISH -> "MMMM yyyy"
        AegisLanguage.VIETNAMESE -> "'Tháng' M/yyyy"
    },
    language = language,
    zone = zone,
).format(Date(epochMillis))

/** "09/11/2026": one device-local calendar day, numeric so it reads the same in both languages. */
internal fun dayMonthYearText(
    epochMillis: Long,
    zone: TimeZone = TimeZone.getDefault(),
): String = formatter("dd/MM/yyyy", AegisLanguage.ENGLISH, zone).format(Date(epochMillis))

/** "09/11": a compact day inside a group heading. */
internal fun shortDayText(
    epochMillis: Long,
    zone: TimeZone = TimeZone.getDefault(),
): String = formatter("dd/MM", AegisLanguage.ENGLISH, zone).format(Date(epochMillis))

/** "Saturday, 09/11/2026 · 12:30": the receipt's moment, in the interface language. */
internal fun weekdayMomentText(
    epochMillis: Long,
    language: AegisLanguage,
    zone: TimeZone = TimeZone.getDefault(),
): String = formatter(
    pattern = when (language) {
        AegisLanguage.ENGLISH -> "EEEE, d MMM yyyy · HH:mm"
        AegisLanguage.VIETNAMESE -> "EEEE, dd/MM/yyyy · HH:mm"
    },
    language = language,
    zone = zone,
).format(Date(epochMillis))

/** "01/11/2026 – 30/11/2026": both inclusive ends of the chosen range. */
internal fun inclusiveRangeText(
    resolved: ResolvedDashboardScope,
    zone: TimeZone = TimeZone.getDefault(),
): String? {
    val from = resolved.range?.fromEpochMillis ?: return null
    val throughExclusive = resolved.range.throughExclusiveEpochMillis ?: return null
    return dayMonthYearText(from, zone) + " – " + dayMonthYearText(throughExclusive - 1L, zone)
}

/**
 * How many device-local calendar days the chosen range covers, both ends included.
 *
 * The count walks calendar days in the range's own zone rather than dividing by 24 hours,
 * so a daylight-saving day still counts once. A refused or open-ended range has no count.
 */
internal fun inclusiveDayCount(
    resolved: ResolvedDashboardScope,
    zone: TimeZone = TimeZone.getDefault(),
): Int? {
    val from = resolved.range?.fromEpochMillis ?: return null
    val throughExclusive = resolved.range.throughExclusiveEpochMillis ?: return null
    var cursor = Calendar.getInstance(zone, Locale.US).apply { timeInMillis = from }
    var days = 0
    while (cursor.timeInMillis < throughExclusive && days < MAX_REPORT_DAYS) {
        days += 1
        cursor.add(Calendar.DATE, 1)
    }
    return days
}

/** A guard against an accidental open-ended walk; a report range is a human-chosen period. */
private const val MAX_REPORT_DAYS = 100_000

// A fresh formatter per call: SimpleDateFormat is not thread safe, and these calls are
// short-lived display conversions rather than a hot loop.
private fun formatter(pattern: String, language: AegisLanguage, zone: TimeZone): SimpleDateFormat =
    SimpleDateFormat(pattern, aegisLocale(language)).apply {
        isLenient = false
        timeZone = zone
    }
