package io.github.triet4p.aegisf.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The explicit state of one association between a note event and a bank draft.
 *
 * A link is proposed, accepted, or rejected, and the state is stored rather than inferred from
 * what else exists: a rejection survives restarts, retries, and re-processing, and a rejected
 * link is never silently turned back into a proposal. Link state is separate from the draft's
 * own pending/confirmed/dismissed lifecycle and from the event's interpretation status, so a
 * reviewed draft is never reopened by a link decision and an unfinished reading is never
 * reported as a settled one.
 */
enum class ContextLinkState {
    /** A candidate association that no user has accepted yet. */
    PROPOSED,

    /** An association the user accepted; it is the event's one active draft. */
    ACCEPTED,

    /** An association the user refused; it stays refused and is never recreated implicitly. */
    REJECTED,
}

/**
 * One stored association between a note event and a pending bank draft.
 *
 * The pair ([eventId], [draftId]) is unique, so one event can never hold two rows for the same
 * draft, and the pair is the link's identity across retries and restarts.
 *
 * [activeEventId] carries the event id while the link is proposed or accepted and is null once
 * the link is rejected. Its unique index is what enforces the safe cardinality in the database
 * itself: an event can hold at most one active draft, so an implicit second event-to-draft link
 * cannot be written even by a caller that skipped the application check. Several independent
 * events may still describe the same draft, because the index is per event.
 */
@Entity(
    tableName = "context_links",
    foreignKeys = [
        ForeignKey(
            entity = NoteEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PendingBankDraftEntity::class,
            parentColumns = ["id"],
            childColumns = ["draftId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["eventId", "draftId"], unique = true),
        Index(value = ["activeEventId"], unique = true),
        Index(value = ["draftId"]),
        Index(value = ["state"]),
    ],
)
data class ContextLinkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    /** The interpreted event this link describes. */
    val eventId: Long,
    /** The pending draft this link points at. */
    val draftId: Long,
    val state: ContextLinkState,
    /** The recorded match evidence, or null when the proposal carried none. */
    val evidence: String?,
    /** Compare-and-set revision for explicit link decisions. */
    val revision: Int,
    /** The explicit user override recorded with the decision, or null when there was none. */
    val userOverride: String?,
    /** The event id while this link is active; null once the link is rejected. */
    val activeEventId: Long?,
    val createdAtEpochMillis: Long,
)
