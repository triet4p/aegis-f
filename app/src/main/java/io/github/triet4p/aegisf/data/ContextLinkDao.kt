package io.github.triet4p.aegisf.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Reads and writes the stored associations between an event and a pending draft.
 *
 * The `(eventId, draftId)` pair is the link's identity, and the unique `activeEventId` index is
 * the database-level guard against one event holding two active drafts. Every explicit decision
 * is compare-and-set on the link's own revision, and a rejected link can only be written once:
 * no statement in this DAO moves a link out of the rejected state.
 */
@Dao
abstract class ContextLinkDao {

    /** Inserts one link; a repeated pair is refused rather than duplicated. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(link: ContextLinkEntity): Long

    @Query("SELECT * FROM context_links WHERE id = :id")
    abstract suspend fun findById(id: Long): ContextLinkEntity?

    @Query(
        "SELECT * FROM context_links WHERE eventId = :eventId AND draftId = :draftId LIMIT 1",
    )
    abstract suspend fun findByPair(eventId: Long, draftId: Long): ContextLinkEntity?

    @Query("SELECT * FROM context_links WHERE eventId = :eventId ORDER BY id")
    abstract suspend fun findForEvent(eventId: Long): List<ContextLinkEntity>

    /**
     * A bounded page of the stored links.
     *
     * [state], [eventId], and [draftId] are open dimensions: each is null when it is not
     * narrowing the read. The order is the stored id, which is the order the links were
     * decided in, so a page boundary is always between two whole links.
     */
    @Query(
        "SELECT * FROM context_links WHERE " +
            "(:state IS NULL OR state = :state) AND " +
            "(:eventId IS NULL OR eventId = :eventId) AND " +
            "(:draftId IS NULL OR draftId = :draftId) " +
            "ORDER BY id ASC LIMIT :limit OFFSET :offset",
    )
    abstract suspend fun findPage(
        state: String?,
        eventId: Long?,
        draftId: Long?,
        limit: Int,
        offset: Int,
    ): List<ContextLinkEntity>

    /** The link states for the named captures, grouped so history reads remain bounded. */
    @Query(
        "SELECT note_events.captureId AS captureId, context_links.state AS state, " +
            "COUNT(*) AS linkCount FROM context_links " +
            "INNER JOIN note_events ON note_events.id = context_links.eventId " +
            "WHERE note_events.captureId IN (:captureIds) " +
            "GROUP BY note_events.captureId, context_links.state",
    )
    abstract suspend fun findCaptureStatusCounts(
        captureIds: List<Long>,
    ): List<CaptureLinkStatusCount>


    @Query("SELECT * FROM context_links WHERE draftId = :draftId ORDER BY id")
    abstract suspend fun findForDraft(draftId: Long): List<ContextLinkEntity>

    /** The links that still count as this event's active draft. */
    @Query(
        "SELECT * FROM context_links WHERE eventId = :eventId " +
            "AND state IN ('PROPOSED', 'ACCEPTED') ORDER BY id",
    )
    abstract suspend fun findActiveForEvent(eventId: Long): List<ContextLinkEntity>

    /** The refusals recorded for every event of one capture. */
    @Query(
        "SELECT context_links.* FROM context_links " +
            "INNER JOIN note_events ON note_events.id = context_links.eventId " +
            "WHERE note_events.captureId = :captureId AND context_links.state = 'REJECTED' " +
            "ORDER BY context_links.id",
    )
    abstract suspend fun findRejectedForCapture(captureId: Long): List<ContextLinkEntity>

    /** Accepts a proposed link while its revision still matches. */
    @Query(
        "UPDATE context_links SET " +
            "state = 'ACCEPTED', " +
            "activeEventId = eventId, " +
            "userOverride = :userOverride, " +
            "revision = revision + 1 " +
            "WHERE id = :id AND revision = :expectedRevision AND state = 'PROPOSED'",
    )
    abstract suspend fun accept(id: Long, expectedRevision: Int, userOverride: String?): Int

    /** Records a refusal while the link's revision still matches. */
    @Query(
        "UPDATE context_links SET " +
            "state = 'REJECTED', " +
            "activeEventId = NULL, " +
            "userOverride = :userOverride, " +
            "revision = revision + 1 " +
            "WHERE id = :id AND revision = :expectedRevision AND state != 'REJECTED'",
    )
    abstract suspend fun reject(id: Long, expectedRevision: Int, userOverride: String?): Int

    /** Removes an active link; a refused link is never removed, so a refusal cannot be undone. */
    @Query(
        "DELETE FROM context_links WHERE id = :id AND revision = :expectedRevision " +
            "AND state != 'REJECTED'",
    )
    abstract suspend fun deleteActive(id: Long, expectedRevision: Int): Int

    /**
     * Removes the active links of every event of one capture.
     *
     * A source edit or delete makes those links describe a reading that no longer exists, so
     * they go with it. Refused links stay: a refusal is the user's decision about that draft
     * and outlives the reading it was made against.
     */
    @Query(
        "DELETE FROM context_links WHERE state != 'REJECTED' AND eventId IN " +
            "(SELECT id FROM note_events WHERE captureId = :captureId)",
    )
    abstract suspend fun deleteActiveForCapture(captureId: Long): Int
}
