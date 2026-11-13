package io.github.triet4p.aegisf.agent

import java.util.Calendar
import java.util.TimeZone

/**
 * The bounded, deterministic vocabulary for the calendar facts user wording states in a note or
 * in a clarification answer, and the local calendar arithmetic the host applies to them.
 *
 * Two facts are deliberately separate here:
 *
 * - a *date* is a day and month, optionally with the year the speaker wrote. A spelled date such
 *   as `11 tháng 10` never becomes a masked value on its own, so the host recognizes it locally
 *   and keeps an unstated year unstated. Nothing in this module ever fills that year in; the
 *   caller must name the trusted chronology it uses.
 * - a *year statement* is relative to the exchange, not to a stored note: `năm nay` means the
 *   year the answer was given in, so a caller resolves it from the chronology it fixed when the
 *   question was asked, never from an old note's own date.
 *
 * The vocabulary is closed. Only spellings that name exactly one day of one month are listed,
 * matching is case-insensitive with whole-word boundaries and the longest expression wins, and
 * anything outside the list stays unresolved instead of being approximated.
 */
internal object SpelledTemporalExpressions {

    /** One spelled day/month occurrence, with its half-open offsets in the searched text. */
    data class SpelledDate(
        val start: Int,
        val endExclusive: Int,
        val surface: String,
        val day: Int,
        val month: Int,
        /** The year the wording itself states, or null when it states none. */
        val statedYear: Int?,
    )

    /** One year stated relative to the exchange: `dayOffset` 0 is the reference year. */
    data class NaturalYear(
        val start: Int,
        val endExclusive: Int,
        val surface: String,
        val yearOffset: Int,
    )

    private data class DatePattern(val regex: Regex, val dayFirst: Boolean)

    private val MONTH_NAMES: List<List<String>> = listOf(
        listOf("january", "jan"),
        listOf("february", "feb"),
        listOf("march", "mar"),
        listOf("april", "apr"),
        listOf("may"),
        listOf("june", "jun"),
        listOf("july", "jul"),
        listOf("august", "aug"),
        listOf("september", "sep", "sept"),
        listOf("october", "oct"),
        listOf("november", "nov"),
        listOf("december", "dec"),
    )

    private val BOUNDARY_BEFORE = "(?<![\\p{L}\\p{N}])"
    private const val BOUNDARY_AFTER = "(?![\\p{L}\\p{N}])"

    /**
     * Numeric day/month are masked before this module sees text, so only spelled forms are
     * listed. The Vietnamese `tháng` form is the one a note actually writes; the English month
     * names cover the bilingual app without inventing a numeric form the boundary already owns.
     */
    private val VIETNAMESE_DATE = DatePattern(
        regex = Regex(
            "$BOUNDARY_BEFORE(\\d{1,2})\\s*(?:tháng|thang)\\s*(\\d{1,2})" +
                "(?:\\s*(?:năm|nam)\\s*(\\d{4}))?$BOUNDARY_AFTER",
            setOf(RegexOption.IGNORE_CASE),
        ),
        dayFirst = true,
    )

    private val DAY_FIRST_MONTH_NAME = DatePattern(
        regex = Regex(
            "$BOUNDARY_BEFORE(\\d{1,2})(?:st|nd|rd|th)?\\s+(${monthAlternation()})" +
                "(?:\\s*,?\\s*(\\d{4}))?$BOUNDARY_AFTER",
            setOf(RegexOption.IGNORE_CASE),
        ),
        dayFirst = true,
    )

    private val MONTH_NAME_FIRST = DatePattern(
        regex = Regex(
            "$BOUNDARY_BEFORE(${monthAlternation()})\\s+(\\d{1,2})(?:st|nd|rd|th)?" +
                "(?:\\s*,?\\s*(\\d{4}))?$BOUNDARY_AFTER",
            setOf(RegexOption.IGNORE_CASE),
        ),
        dayFirst = false,
    )

    private val PATTERNS = listOf(VIETNAMESE_DATE, DAY_FIRST_MONTH_NAME, MONTH_NAME_FIRST)

    private data class YearVocabulary(val pattern: Regex, val yearOffset: Int)

    private val YEAR_VOCABULARY: List<YearVocabulary> = listOf(
        year(0, "năm nay", "nam nay", "this year"),
        year(-1, "năm ngoái", "nam ngoai", "năm trước", "nam truoc", "last year"),
        year(-2, "năm kia", "nam kia", "the year before last"),
        year(1, "năm sau", "nam sau", "năm tới", "nam toi", "next year"),
    )

    private val ISO_DATE = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")

    /** Every spelled date in [text], in reading order and without overlaps. */
    fun findDates(text: String): List<SpelledDate> {
        if (text.isEmpty()) return emptyList()
        val candidates = mutableListOf<SpelledDate>()
        for (pattern in PATTERNS) {
            for (match in pattern.regex.findAll(text)) {
                val parsed = parseDateMatch(pattern, match) ?: continue
                candidates += parsed
            }
        }
        val ordered = candidates.sortedWith(
            compareBy({ it.start }, { -(it.endExclusive - it.start) }),
        )
        val accepted = mutableListOf<SpelledDate>()
        var consumedUntil = -1
        for (candidate in ordered) {
            if (candidate.start < consumedUntil) continue
            accepted += candidate
            consumedUntil = candidate.endExclusive
        }
        return accepted
    }

    /** The single spelled date in [text], or null when none or several are stated. */
    fun singleDateIn(text: String): SpelledDate? = findDates(text).singleOrNull()

    /** The first year statement in [text], preferring the longest match at a position. */
    fun findNaturalYear(text: String): NaturalYear? {
        if (text.isEmpty()) return null
        val matches = mutableListOf<NaturalYear>()
        for (vocabulary in YEAR_VOCABULARY) {
            for (match in vocabulary.pattern.findAll(text)) {
                matches += NaturalYear(
                    start = match.range.first,
                    endExclusive = match.range.last + 1,
                    surface = match.value,
                    yearOffset = vocabulary.yearOffset,
                )
            }
        }
        return matches.minWithOrNull(
            compareBy({ it.start }, { -(it.endExclusive - it.start) }),
        )
    }

    /** The local calendar year [instantEpochMillis] falls in when read in [timezoneId]. */
    fun localYear(instantEpochMillis: Long?, timezoneId: String?): Int? {
        if (instantEpochMillis == null) return null
        val zone = RelativeDayExpressions.timeZone(timezoneId) ?: return null
        return Calendar.getInstance(zone).apply { timeInMillis = instantEpochMillis }.get(Calendar.YEAR)
    }

    /** The local calendar day [instantEpochMillis] falls in when read in [timezoneId]. */
    fun localDate(instantEpochMillis: Long, timezoneId: String): String? {
        val zone = RelativeDayExpressions.timeZone(timezoneId) ?: return null
        val calendar = Calendar.getInstance(zone).apply { timeInMillis = instantEpochMillis }
        return format(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
    }

    /** The local wall-clock `HH:mm` [instantEpochMillis] reads in [timezoneId]. */
    fun localTime(instantEpochMillis: Long, timezoneId: String): Pair<Int, Int> {
        val zone = RelativeDayExpressions.timeZone(timezoneId) ?: TimeZone.getDefault()
        val calendar = Calendar.getInstance(zone).apply { timeInMillis = instantEpochMillis }
        return calendar.get(Calendar.HOUR_OF_DAY) to calendar.get(Calendar.MINUTE)
    }

    /** `YYYY-MM-DD` for a valid calendar day, or null when the components are not one. */
    fun format(year: Int, month: Int, day: Int): String? {
        if (year !in 1..9999 || month !in 1..12 || day !in 1..31) return null
        val calendar = utcCalendar()
        calendar.clear()
        calendar.set(year, month - 1, day)
        if (calendar.get(Calendar.YEAR) != year ||
            calendar.get(Calendar.MONTH) != month - 1 ||
            calendar.get(Calendar.DAY_OF_MONTH) != day
        ) {
            return null
        }
        return format(calendar)
    }

    /** The components of a stored `YYYY-MM-DD`, or null when it is not one. */
    fun parts(date: String): Triple<Int, Int, Int>? {
        val match = ISO_DATE.matchEntire(date) ?: return null
        val year = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        val day = match.groupValues[3].toIntOrNull() ?: return null
        return if (format(year, month, day) == date) Triple(year, month, day) else null
    }

    /**
     * Every calendar day from [startDate] through [endDate] inclusive, in order.
     *
     * Both ends must be stored dates and the range must be ordered; a caller bounds the allowed
     * length itself. An unordered or unreadable range returns an empty list rather than a
     * silently swapped one.
     */
    fun daysBetween(startDate: String, endDate: String): List<String> {
        val start = parts(startDate) ?: return emptyList()
        val end = parts(endDate) ?: return emptyList()
        if (endDate < startDate) return emptyList()
        val calendar = utcCalendar()
        calendar.clear()
        calendar.set(start.first, start.second - 1, start.third)
        val days = mutableListOf<String>()
        while (true) {
            val day = format(calendar)
            days += day
            if (day >= endDate) break
            calendar.add(Calendar.DAY_OF_MONTH, 1)
            if (days.size > MAX_LISTED_DAYS) return emptyList()
        }
        return days
    }

    /** [date] shifted by [dayOffset] calendar days, or null when [date] is not a stored date. */
    fun shift(date: String, dayOffset: Int): String? {
        val parts = parts(date) ?: return null
        val calendar = utcCalendar()
        calendar.clear()
        calendar.set(parts.first, parts.second - 1, parts.third)
        calendar.add(Calendar.DAY_OF_MONTH, dayOffset)
        return format(calendar)
    }

    /** The signed whole-day distance from [fromDate] to [toDate], or null when either is unreadable. */
    fun daysApart(fromDate: String, toDate: String): Int? {
        val from = parts(fromDate) ?: return null
        val to = parts(toDate) ?: return null
        val fromCalendar = utcCalendar().apply {
            clear()
            set(from.first, from.second - 1, from.third)
        }
        val toCalendar = utcCalendar().apply {
            clear()
            set(to.first, to.second - 1, to.third)
        }
        return ((toCalendar.timeInMillis - fromCalendar.timeInMillis) / MILLIS_PER_DAY).toInt()
    }

    /** The largest day list one bounded range read may materialize. */
    const val MAX_LISTED_DAYS = 62

    /**
     * The zone a stored capture records, when it names a usable identifier.
     *
     * A zone the platform resolves to a different id is an alias or an unknown value and is not
     * trusted, exactly as the relative-day resolver treats it.
     */
    fun trustZone(timezoneId: String?): TimeZone? = RelativeDayExpressions.timeZone(timezoneId)

    private fun parseDateMatch(pattern: DatePattern, match: MatchResult): SpelledDate? {
        val groups = match.groupValues
        val dayText: String
        val monthText: String
        val yearText: String
        if (pattern === VIETNAMESE_DATE) {
            dayText = groups[1]
            monthText = groups[2]
            yearText = groups[3]
        } else if (pattern.dayFirst) {
            dayText = groups[1]
            monthText = groups[2]
            yearText = groups[3]
        } else {
            monthText = groups[1]
            dayText = groups[2]
            yearText = groups[3]
        }
        val day = dayText.toIntOrNull() ?: return null
        val month = if (pattern === VIETNAMESE_DATE) {
            monthText.toIntOrNull()
        } else {
            monthIndex(monthText)
        } ?: return null
        if (format(2000, month, day) == null) return null
        val year = yearText.toIntOrNull()?.takeIf { it in 1..9999 }
        return SpelledDate(
            start = match.range.first,
            endExclusive = match.range.last + 1,
            surface = match.value,
            day = day,
            month = month,
            statedYear = year,
        )
    }

    private fun monthIndex(text: String): Int? {
        val normalized = text.lowercase()
        MONTH_NAMES.forEachIndexed { index, names ->
            if (normalized in names) return index + 1
        }
        return null
    }

    private fun monthAlternation(): String = MONTH_NAMES.joinToString("|") { names ->
        names.joinToString("|") { name -> Regex.escape(name) }
    }

    private fun year(yearOffset: Int, vararg spellings: String): YearVocabulary = YearVocabulary(
        pattern = Regex(
            "(?iu)$BOUNDARY_BEFORE(?:${spellings.joinToString("|")})$BOUNDARY_AFTER",
        ),
        yearOffset = yearOffset,
    )

    private fun utcCalendar(): Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))

    private fun format(calendar: Calendar): String = "%04d-%02d-%02d".format(
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH) + 1,
        calendar.get(Calendar.DAY_OF_MONTH),
    )

    private const val MILLIS_PER_DAY = 86_400_000L
}
