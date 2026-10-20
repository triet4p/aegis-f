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
}
