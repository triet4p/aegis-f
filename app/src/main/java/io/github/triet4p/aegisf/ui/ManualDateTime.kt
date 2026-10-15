package io.github.triet4p.aegisf.ui

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Date and time entry for manual records: two text fields in documented grammars, converted
 * into stored epoch milliseconds.
 *
 * The zone is the device's **current default zone** — the same one the system clock shows —
 * so `18:30` on 2026-10-15 means the epoch millis of that wall-clock moment on the device,
 * never `18:30` reinterpreted as UTC. The conversion deliberately uses `java.util`
 * date formatting because the app supports API 24: `java.time` would need core library
 * desugaring for the same semantics. Wall-clock moments are set on a calendar in that zone,
 * so a local gap or overlap keeps the instant the device clock would show.
 *
 * Grammars: the date is `YYYY-MM-DD` and the time is `HH:mm` on a 24-hour clock, with no
 * surrounding whitespace; anything else is refused with a field-local message rather than
 * silently corrected. An impossible date (2026-02-30) or time (25:61) is refused, not rolled
 * over into the next day or month.
 *
 * Browsing filters reuse the same zone convention at day granularity: an inclusive
 * From/Through pair of `YYYY-MM-DD` dates becomes the half-open epoch range
 * `[startOf(From), startOf(dayAfter(Through)))`, where `startOf` is local midnight and the
 * day after is one calendar day later in the same zone. Either end may be left empty (an
 * open end); a present end must be well formed, and From must not be after Through, or the
 * filters report a refusal and change nothing.
 */
object ManualDateTime {

    /** The documented date grammar shown to the user. */
    const val DATE_FORMAT = "YYYY-MM-DD"

    /** The documented time grammar shown to the user. */
    const val TIME_FORMAT = "HH:mm"

    private const val DATE_PATTERN = "yyyy-MM-dd"
    private const val TIME_PATTERN = "HH:mm"
    private const val MOMENT_PATTERN = "yyyy-MM-dd HH:mm"
    private const val MONTH_PATTERN = "MMMM yyyy"
    private const val EXAMPLE_DATE = "2026-10-15"
    private const val EXAMPLE_TIME = "18:30"

    /** The date field text that displays [epochMillis] as a device-local calendar date. */
    fun dateFieldText(epochMillis: Long, zone: TimeZone = TimeZone.getDefault()): String =
        formatter(DATE_PATTERN, zone).format(Date(epochMillis))

    /** The time field text that displays [epochMillis] as a device-local wall-clock time. */
    fun timeFieldText(epochMillis: Long, zone: TimeZone = TimeZone.getDefault()): String =
        formatter(TIME_PATTERN, zone).format(Date(epochMillis))

    /** A stored moment rendered in the device's own zone, for display of a saved record. */
    fun formatMoment(epochMillis: Long, zone: TimeZone = TimeZone.getDefault()): String =
        formatter(MOMENT_PATTERN, zone).format(Date(epochMillis))

    /** A field-local refusal for the date field, or null when [dateText] is well formed. */
    fun dateError(dateText: String): String? {
        if (dateText.isEmpty()) {
            return "Date is required."
        }
        if (!matchesGrammar(DATE_PATTERN, dateText)) {
            return "Enter the date as $DATE_FORMAT, for example $EXAMPLE_DATE."
        }
        return null
    }

    /** A field-local refusal for the time field, or null when [timeText] is well formed. */
    fun timeError(timeText: String): String? {
        if (timeText.isEmpty()) {
            return "Time is required."
        }
        if (!matchesGrammar(TIME_PATTERN, timeText)) {
            return "Enter the time as $TIME_FORMAT on a 24-hour clock, for example $EXAMPLE_TIME."
        }
        return null
    }

    /**
     * The stored epoch millis of the wall-clock moment [dateText] [timeText] read in [zone].
     * The texts must already pass [dateError] and [timeError]; the caller validates its
     * fields before writing, so this conversion only ever sees well-formed input.
     */
    fun toEpochMillis(
        dateText: String,
        timeText: String,
        zone: TimeZone = TimeZone.getDefault(),
    ): Long = formatter(MOMENT_PATTERN, zone).parse("$dateText $timeText")!!.time

    /**
     * The first instant of the device-local calendar day [dateText] names in [zone]: local
     * midnight at the start of that date.
     *
     * Midnight is local midnight in [zone] — the same zone [toEpochMillis] writes in — never
     * a guessed UTC midnight. The caller validates with [dateError] first; this conversion
     * only ever sees well-formed input, and the `require` below turns a programming error
     * into a loud failure rather than a silently broadened range.
     */
    fun dayStartEpochMillis(
        dateText: String,
        zone: TimeZone = TimeZone.getDefault(),
    ): Long {
        require(dateError(dateText) == null) { "A valid $DATE_FORMAT date is required." }
        return formatter(DATE_PATTERN, zone).parse(dateText)!!.time
    }

    /**
     * The first instant of the device-local calendar day after [dateText] in [zone].
     *
     * This adds one calendar day in [zone] (`Calendar.add(DATE, 1)`), not a fixed 24 hours,
     * so a 23-hour daylight-saving start day and a 25-hour end day contribute their actual
     * length. Together with [dayStartEpochMillis] it forms the half-open range
     * `[startOf(From), startOf(dayAfter(Through)))` that the browsing filters use for an
     * inclusive From/Through pair.
     */
    fun dayAfterStartEpochMillis(
        dateText: String,
        zone: TimeZone = TimeZone.getDefault(),
    ): Long {
        require(dateError(dateText) == null) { "A valid $DATE_FORMAT date is required." }
        val start = dayStartEpochMillis(dateText, zone)
        val calendar = Calendar.getInstance(zone, Locale.US).apply { timeInMillis = start }
        calendar.add(Calendar.DATE, 1)
        return calendar.timeInMillis
    }

    /**
     * The first instant of the device-local calendar month that contains [epochMillis]:
     * local midnight on the first day of that month in [zone].
     *
     * [zone] is the device's current default zone in production and an explicit oracle zone
     * in tests — the same zone [toEpochMillis] and [dayStartEpochMillis] work in — so a
     * record belongs to the month the device clock showed when it happened, never to a
     * guessed UTC month. The first day of a month always exists, so the month start is never
     * skipped; a zone whose first midnight is a daylight-saving gap is resolved by the
     * [Calendar] to the instant that gap starts, instead of failing the way parsing a
     * nonexistent wall-clock time would.
     */
    fun monthStartEpochMillis(epochMillis: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val calendar = Calendar.getInstance(zone, Locale.US).apply {
            timeInMillis = epochMillis
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    /**
     * The first instant of the device-local calendar month after the one containing
     * [epochMillis] — the exclusive end of that month in [zone].
     *
     * One calendar month later (`Calendar.add(MONTH, 1)`) than the month start, so a 28-,
     * 29-, 30-, and 31-day month, and a month that contains a daylight-saving transition,
     * each contribute their actual span. A fixed 30-day or 24-hour step would misplace
     * records that sit at a month boundary.
     */
    fun nextMonthStartEpochMillis(
        epochMillis: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): Long {
        val calendar = Calendar.getInstance(zone, Locale.US).apply {
            timeInMillis = monthStartEpochMillis(epochMillis, zone)
            add(Calendar.MONTH, 1)
        }
        return calendar.timeInMillis
    }

    /**
     * The calendar month of [epochMillis] in [zone], written the way a summary heading shows
     * it, for example `October 2026`. English month names are deliberate: this names the
     * month the user is looking at, not a locale-dependent re-rendering of a stored value.
     */
    fun monthFieldText(epochMillis: Long, zone: TimeZone = TimeZone.getDefault()): String =
        formatter(MONTH_PATTERN, zone).format(Date(epochMillis))

    // A fresh formatter per call: SimpleDateFormat is not thread safe, and these calls are
    // short-lived field conversions rather than a hot loop.
    private fun formatter(pattern: String, zone: TimeZone): SimpleDateFormat =
        SimpleDateFormat(pattern, Locale.US).apply {
            isLenient = false
            timeZone = zone
        }

    /**
     * Whether [text] is consumed completely by [pattern]. The single-argument parse ignores
     * trailing content, so "18:30:45" would otherwise pass as 18:30 and "2026-02-30" would
     * need the non-lenient calendar check to be refused; both come from the parse position.
     */
    private fun matchesGrammar(pattern: String, text: String): Boolean {
        val position = ParsePosition(0)
        formatter(pattern, TimeZone.getDefault()).parse(text, position)
        return position.errorIndex < 0 && position.index == text.length
    }
}
