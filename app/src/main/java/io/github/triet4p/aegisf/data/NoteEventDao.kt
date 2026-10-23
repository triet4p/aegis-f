package io.github.triet4p.aegisf.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes the stored event interpretations of a capture.
 *
 * The event row is written once per source span: the unique `(captureId, eventIdentity)` index
 * is what keeps a repeated extraction of the same span from appending a blind second copy.
 * Every explicit change is compare-and-set on the event's own revision.
 */
@Dao
abstract class NoteEventDao {

    /** Inserts one reading; a repeated identity is refused rather than silently duplicated. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(event: NoteEventEntity): Long

    @Query("SELECT * FROM note_events WHERE id = :id")
    abstract suspend fun findById(id: Long): NoteEventEntity?

    /** The named readings, read by their ids; an empty list reads nothing. */
    @Query("SELECT * FROM note_events WHERE id IN (:ids)")
    abstract suspend fun findByIds(ids: List<Long>): List<NoteEventEntity>

    @Query("SELECT * FROM note_events WHERE captureId = :captureId ORDER BY spanStart, id")
    abstract suspend fun findForCapture(captureId: Long): List<NoteEventEntity>

    /**
     * A bounded page of the stored readings.
     *
     * [captureId] narrows the read to one capture and [status] to one interpretation
     * state; both are open when they are null. The order is the capture, then the source
     * span, then the stored id, so a reading always keeps its place inside its capture.
     */
    @Query(
        "SELECT * FROM note_events WHERE " +
            "(:captureId IS NULL OR captureId = :captureId) AND " +
            "(:status IS NULL OR status = :status) " +
            "ORDER BY captureId ASC, spanStart ASC, id ASC LIMIT :limit OFFSET :offset",
    )
    abstract suspend fun findPage(
        captureId: Long?,
        status: String?,
        limit: Int,
        offset: Int,
    ): List<NoteEventEntity>

    @Query(
        "SELECT * FROM note_events WHERE captureId = :captureId AND eventIdentity = :eventIdentity " +
            "LIMIT 1",
    )
    abstract suspend fun findByIdentity(captureId: Long, eventIdentity: String): NoteEventEntity?

    /** The same readings as [findForCapture], as a live stream of the current rows. */
    @Query("SELECT * FROM note_events WHERE captureId = :captureId ORDER BY spanStart, id")
    abstract fun observeForCapture(captureId: Long): Flow<List<NoteEventEntity>>

    /** A bounded-by-capture summary of event states and source-backed resolutions. */
    @Query(
        "SELECT captureId, status, COUNT(*) AS eventCount, " +
            "SUM(CASE WHEN resolvedTransactionId IS NOT NULL THEN 1 ELSE 0 END) " +
            "AS resolvedEventCount FROM note_events WHERE captureId IN (:captureIds) " +
            "GROUP BY captureId, status",
    )
    abstract suspend fun findCaptureStatusCounts(
        captureIds: List<Long>,
    ): List<CaptureEventStatusCount>


    /**
     * Replaces the reading of an event while its revision still matches.
     *
     * The derived content is written in one statement so a reading is never half old and half
     * new, and the capture revision the reading was taken from is stored with it.
     */
    @Query(
        "UPDATE note_events SET " +
            "spanStart = :spanStart, " +
            "spanEnd = :spanEnd, " +
            "normalizedText = :normalizedText, " +
            "amountVnd = :amountVnd, " +
            "merchant = :merchant, " +
            "anchorDate = :anchorDate, " +
            "anchorTime = :anchorTime, " +
            "anchorTimezone = :anchorTimezone, " +
            "intervalEndDate = :intervalEndDate, " +
            "intervalEndTime = :intervalEndTime, " +
            "extractionVersion = :extractionVersion, " +
            "unresolvedReason = :unresolvedReason, " +
            "status = :status, " +
            "sourceRevision = :sourceRevision, " +
            "revision = revision + 1 " +
            "WHERE id = :id AND revision = :expectedRevision",
    )
    abstract suspend fun updateReading(
        id: Long,
        expectedRevision: Int,
        spanStart: Int,
        spanEnd: Int,
        normalizedText: String?,
        amountVnd: Long?,
        merchant: String?,
        anchorDate: String?,
        anchorTime: String?,
        anchorTimezone: String?,
        intervalEndDate: String?,
        intervalEndTime: String?,
        extractionVersion: String,
        unresolvedReason: String?,
        status: String,
        sourceRevision: Int,
    ): Int

    /**
     * Drops the derived reading of one event because the source text it was read from changed.
     *
     * The identity, the span, the extraction version, and the creation time stay: what remains
     * is the record of the span, not a copy of text the source no longer holds.
     */
    @Query(
        "UPDATE note_events SET " +
            "normalizedText = NULL, " +
            "amountVnd = NULL, " +
            "merchant = NULL, " +
            "anchorDate = NULL, " +
            "anchorTime = NULL, " +
            "anchorTimezone = NULL, " +
            "intervalEndDate = NULL, " +
            "intervalEndTime = NULL, " +
            "unresolvedReason = :reason, " +
            "status = :status, " +
            "revision = revision + 1 " +
            "WHERE id = :id",
    )
    abstract suspend fun clearDerivedReading(id: Long, reason: String, status: String): Int

    /** Records the interpretation status a link decision produced. */
    @Query("UPDATE note_events SET status = :status, revision = revision + 1 WHERE id = :id")
    abstract suspend fun updateStatus(id: Long, status: String): Int

    /**
     * Marks one reading as recorded: the ledger record that resolved it is named, and the
     * moment it happened is stored with it.
     *
     * The write is refused once the reading is resolved, so one occurrence can never be
     * recorded twice, and it moves the reading's own revision on, so a request made
     * against the unresolved reading is stale afterwards.
     */
    @Query(
        "UPDATE note_events SET " +
            "resolvedTransactionId = :transactionId, " +
            "resolvedAtEpochMillis = :atEpochMillis, " +
            "revision = revision + 1 " +
            "WHERE id = :id AND resolvedTransactionId IS NULL",
    )
    abstract suspend fun markResolved(id: Long, transactionId: Long, atEpochMillis: Long): Int

    /**
     * Clears the resolution of the reading a removed record had resolved.
     *
     * The occurrence is no longer recorded, so the note may be recorded again; the reading
     * keeps everything else it holds.
     */
    @Query(
        "UPDATE note_events SET " +
            "resolvedTransactionId = NULL, " +
            "resolvedAtEpochMillis = NULL, " +
            "revision = revision + 1 " +
            "WHERE resolvedTransactionId = :transactionId",
    )
    abstract suspend fun clearResolutionOf(transactionId: Long): Int

    /** The reading one ledger record resolved, when there is one. */
    @Query("SELECT * FROM note_events WHERE resolvedTransactionId = :transactionId LIMIT 1")
    abstract suspend fun findByResolvedTransaction(transactionId: Long): NoteEventEntity?

    /**
     * A bounded page of the readings that are still waiting to be recorded.
     *
     * A reading is waiting when it is complete, was not resolved into a ledger record, and
     * holds no active link to a bank draft: an active link means the bank notification is
     * the record of that occurrence, and a resolved reading is spent.
     */
    @Query(
        "SELECT * FROM note_events WHERE " +
            "resolvedTransactionId IS NULL AND " +
            "normalizedText IS NOT NULL AND " +
            "unresolvedReason IS NULL AND " +
            "NOT EXISTS (" +
            "SELECT 1 FROM context_links WHERE context_links.eventId = note_events.id " +
            "AND context_links.state IN ('PROPOSED', 'ACCEPTED')) " +
            "ORDER BY captureId ASC, spanStart ASC, id ASC LIMIT :limit OFFSET :offset",
    )
    abstract suspend fun findUnrecordedPage(limit: Int, offset: Int): List<NoteEventEntity>

    /** The same hand-recordable event as [findUnrecordedPage], addressed by stable id. */
    @Query(
        "SELECT * FROM note_events WHERE id = :eventId AND " +
            "resolvedTransactionId IS NULL AND normalizedText IS NOT NULL AND " +
            "unresolvedReason IS NULL AND NOT EXISTS (" +
            "SELECT 1 FROM context_links WHERE context_links.eventId = note_events.id " +
            "AND context_links.state IN ('PROPOSED', 'ACCEPTED'))",
    )
    abstract suspend fun findUnrecordedById(eventId: Long): NoteEventEntity?
}
