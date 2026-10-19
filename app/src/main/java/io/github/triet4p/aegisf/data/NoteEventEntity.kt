package io.github.triet4p.aegisf.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.security.MessageDigest

/**
 * How complete one stored event interpretation is.
 *
 * The three states describe the *interpretation*, not the link: a link has its own explicit
 * proposed/accepted/rejected state, so a draft's review lifecycle never collapses into this
 * enum and an event is never "confirmed" by a link.
 */
enum class NoteEventStatus {
    /** The reading is current and complete enough to describe one occurrence. */
    INTERPRETED,

    /** Required information is missing or ambiguous; the reason is stored with the event. */
    UNRESOLVED,

    /** The reading is complete, but no stored draft matches it. */
    NO_MATCH,
}

/**
 * The time an event was read to have happened.
 *
 * A point anchor states one moment; an interval states a range that starts at the anchor.
 * Every part is optional because a source may state no time at all, and a missing time is
 * missing information — it is never replaced by a guessed one. The parts that are present must
 * be well formed: a wall-clock time needs its date, an interval end needs the anchor it ends
 * after, and the end must not fall before the anchor.
 */
data class NoteEventTimeAnchor(
    /** The stated calendar date, `YYYY-MM-DD`. */
    val date: String? = null,
    /** The stated wall-clock time, `HH:mm`. */
    val time: String? = null,
    /** The zone the stated wall-clock time is anchored in. */
    val timezoneId: String? = null,
    /** The stated end date of an interval, `YYYY-MM-DD`. */
    val intervalEndDate: String? = null,
    /** The stated end time of an interval, `HH:mm`. */
    val intervalEndTime: String? = null,
)

/**
 * One stored interpretation of one occurrence inside a capture's text.
 *
 * A capture may own several events, because one note can describe several real-world
 * occurrences. Each event keeps:
 *
 * - a stable [eventIdentity] (see [noteEventIdentity]) derived from the capture's own source
 *   identity and the source span, so a retry, a restart, or a re-extraction of the same span
 *   addresses the same event instead of appending a second copy of it;
 * - the [spanStart]/[spanEnd] offsets the reading was taken from, so the reading can always be
 *   traced back to the exact source text it came from;
 * - the optional extracted fields — an amount, a merchant, and a time anchor or interval — each
 *   of which is null when the source did not state it, so a missing value stays missing instead
 *   of becoming a plausible-looking guess;
 * - the [extractionVersion] that produced the reading and the [sourceRevision] of the capture it
 *   was read from, so the provenance of an interpretation is never anonymous;
 * - a [revision] for explicit, compare-and-set changes, and a [status] that keeps an incomplete
 *   or unmatched reading visible as such.
 *
 * The derived reading ([normalizedText] and the extracted fields) is cleared when the source text
 * it was read from is edited or deleted, so no orphaned copy of a changed or deleted text
 * survives. The event row itself stays, because its identity and its rejected links are the
 * durable record of what was decided about that span.
 */
@Entity(
    tableName = "note_events",
    foreignKeys = [
        ForeignKey(
            entity = CaptureMessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["captureId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["captureId", "eventIdentity"], unique = true),
        Index(value = ["captureId"]),
        Index(value = ["status"]),
    ],
)
data class NoteEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    /** The capture whose text this reading was taken from. */
    val captureId: Long,
    /** Stable identity of this reading inside its capture; see [noteEventIdentity]. */
    val eventIdentity: String,
    /** Inclusive start offset of the source span inside the capture's text. */
    val spanStart: Int,
    /** Exclusive end offset of the source span inside the capture's text. */
    val spanEnd: Int,
    /** The normalized reading of the span; null once the source text no longer supports it. */
    val normalizedText: String?,
    /** The stated amount in whole dong; null when the source stated no amount. */
    val amountVnd: Long?,
    /** The stated merchant; null when the source stated none. */
    val merchant: String?,
    /** The stated calendar date of the occurrence, `YYYY-MM-DD`. */
    val anchorDate: String?,
    /** The stated wall-clock time of the occurrence, `HH:mm`. */
    val anchorTime: String?,
    /** The zone the stated wall-clock time is anchored in. */
    val anchorTimezone: String?,
    /** The stated end of a time interval, `YYYY-MM-DD`. */
    val intervalEndDate: String?,
    /** The stated end of a time interval, `HH:mm`. */
    val intervalEndTime: String?,
    /** Which extraction produced this reading. */
    val extractionVersion: String,
    /** Why the reading is incomplete, or null when it is complete. */
    val unresolvedReason: String?,
    val status: NoteEventStatus,
    /** Compare-and-set revision for explicit changes to this reading. */
    val revision: Int,
    /** The capture revision this reading was derived from. */
    val sourceRevision: Int,
    val createdAtEpochMillis: Long,
)

/**
 * The stable identity of one event reading.
 *
 * The identity is derived from the capture's own source kind and source identity plus the
 * source span, never from the row id and never from the reading's text, so it survives a
 * restart, a re-extraction, and the removal of the row it was first stored under. Two readings
 * of the same span in the same capture are therefore the same event, which is what keeps a
 * repeated extraction from appending a blind second copy.
 */
internal fun noteEventIdentity(
    sourceKind: CaptureSourceKind,
    sourceIdentity: String,
    spanStart: Int,
    spanEnd: Int,
): String {
    val canonical = listOf(
        sourceKind.name,
        sourceIdentity,
        spanStart.toString(),
        spanEnd.toString(),
    ).joinToString(separator = EVENT_FIELD_SEPARATOR) { value -> "v${value.length}:$value" }
    return MessageDigest.getInstance(SHA_256)
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
}

/** Separates the canonical identity fields; never a separator in a stored source value. */
private const val EVENT_FIELD_SEPARATOR = "\u001F"

private const val SHA_256 = "SHA-256"
