package io.github.triet4p.aegisf.agent

import java.util.Calendar
import java.util.TimeZone

/**
 * The bounded, deterministic vocabulary for a day stated relative to the note's own capture.
 *
 * A capture is the only trusted reference for "today" or "yesterday": it records the local
 * instant the user wrote the note and the zone that instant was read in, so the calendar day
 * the note means can be derived locally instead of being asked for again or guessed.
 *
 * The vocabulary is deliberately closed. It contains only expressions that name exactly one
 * local calendar day, in English and in the Vietnamese spellings a note actually uses. A
 * period expression (a morning, a night, a week, a weekend) names a range of hours or days,
 * and a bare word that is ambiguous in the same language stays out of the list, so the
 * resolver never turns a period into a fabricated day. Anything outside the list is reported
 * as unresolved and stays a clarification, which is what preserves the source's own ambiguity.
 *
 * Matching is lexical and case-insensitive, keeps whole-word boundaries, and prefers the
 * longest expression at a position, so "the day before yesterday" is one expression rather
 * than a "yesterday" inside it.
 */
internal object RelativeDayExpressions {

    /** One relative-day expression found in text, with its half-open source offsets. */
    data class RelativeDay(
        val start: Int,
        val endExclusive: Int,
        val surface: String,
        /** The day offset from the capture's own local date; -1 is the day before it. */
        val dayOffset: Int,
    )

    private data class Vocabulary(val pattern: Regex, val dayOffset: Int)

    private const val BOUNDARY_BEFORE = "(?<![\\p{L}\\p{N}])"
    private const val BOUNDARY_AFTER = "(?![\\p{L}\\p{N}])"

    /**
     * Closed vocabulary. Every entry names one calendar day; nothing here spans midnight, so
     * every match resolves to exactly one date once the capture's own local date is known.
     */
    private val VOCABULARY: List<Vocabulary> = listOf(
        expression(0, "today", "ngày hôm nay", "ngay hom nay", "hôm nay", "hom nay"),
        expression(-1, "yesterday", "ngày hôm qua", "ngay hom qua", "hôm qua", "hom qua"),
        expression(-2, "the day before yesterday", "ngày hôm kia", "ngay hom kia", "hôm kia", "hom kia"),
        expression(1, "tomorrow", "ngày mai", "ngay mai"),
        expression(2, "the day after tomorrow", "ngày kia", "ngay kia", "ngày mốt", "ngay mot"),
    )

    private val ISO_DATE = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")

    /** Every relative-day expression in [text], in reading order and without overlaps. */
    fun findIn(text: String): List<RelativeDay> {
        if (text.isEmpty()) return emptyList()
        val candidates = mutableListOf<RelativeDay>()
        for (vocabulary in VOCABULARY) {
            for (match in vocabulary.pattern.findAll(text)) {
                candidates += RelativeDay(
                    start = match.range.first,
                    endExclusive = match.range.last + 1,
                    surface = match.value,
                    dayOffset = vocabulary.dayOffset,
                )
            }
        }
        val ordered = candidates.sortedWith(
            compareBy({ it.start }, { -(it.endExclusive - it.start) }),
        )
        val accepted = mutableListOf<RelativeDay>()
        var consumedUntil = -1
        for (candidate in ordered) {
            if (candidate.start < consumedUntil) continue
            accepted += candidate
            consumedUntil = candidate.endExclusive
        }
        return accepted
    }

    /**
     * The capture's own calendar day in its recorded zone, `YYYY-MM-DD`.
     *
     * Null when the capture records no instant or no usable zone: a relative day then has no
     * trusted reference and stays unresolved instead of being anchored to a different zone's
     * idea of the day.
     */
    fun localDate(instantEpochMillis: Long?, timezoneId: String?): String? {
        if (instantEpochMillis == null) return null
        val zone = timeZone(timezoneId) ?: return null
        return format(Calendar.getInstance(zone).apply { timeInMillis = instantEpochMillis })
    }

    /** [date] shifted by [dayOffset] calendar days, or null when [date] is not a stored date. */
    fun resolve(date: String, dayOffset: Int): String? {
        val match = ISO_DATE.matchEntire(date) ?: return null
        val year = match.groups[1]?.value?.toIntOrNull() ?: return null
        val month = match.groups[2]?.value?.toIntOrNull() ?: return null
        val day = match.groups[3]?.value?.toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31) return null
        // Calendar-day arithmetic is zone-free here: the reference is already the capture's
        // local calendar day, so the shift is a pure calendar operation.
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.clear()
        calendar.set(year, month - 1, day)
        if (calendar.get(Calendar.DAY_OF_MONTH) != day) return null
        calendar.add(Calendar.DAY_OF_MONTH, dayOffset)
        return format(calendar)
    }

    /** A usable zone for a recorded identifier; an unknown or aliased id is not trusted. */
    fun timeZone(timezoneId: String?): TimeZone? {
        if (timezoneId.isNullOrBlank()) return null
        val zone = runCatching { TimeZone.getTimeZone(timezoneId) }.getOrNull() ?: return null
        return zone.takeIf { it.id == timezoneId }
    }

    private fun format(calendar: Calendar): String = "%04d-%02d-%02d".format(
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH) + 1,
        calendar.get(Calendar.DAY_OF_MONTH),
    )

    private fun expression(dayOffset: Int, vararg spellings: String): Vocabulary = Vocabulary(
        pattern = Regex(
            "(?iu)$BOUNDARY_BEFORE(?:${spellings.joinToString("|")})$BOUNDARY_AFTER",
        ),
        dayOffset = dayOffset,
    )
}
