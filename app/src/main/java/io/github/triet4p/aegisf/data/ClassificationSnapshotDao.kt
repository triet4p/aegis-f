package io.github.triet4p.aegisf.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Reads and writes the stored predictions about bank drafts.
 *
 * A draft holds at most one snapshot, so the pair `(draftId, ...)` is the snapshot's
 * identity across retries and restarts. A snapshot is never deleted when it goes stale: the
 * stale reason is recorded on it, because "this prediction was made, and it no longer
 * applies" is itself the fact the review needs.
 */
@Dao
abstract class ClassificationSnapshotDao {

    /** Stores one snapshot; a draft that already has one is refused rather than duplicated. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(snapshot: ClassificationSnapshotEntity): Long

    @Query("SELECT * FROM classification_snapshots WHERE draftId = :draftId LIMIT 1")
    abstract suspend fun findForDraft(draftId: Long): ClassificationSnapshotEntity?

    /**
     * A bounded page of the stored predictions.
     *
     * [staleOnly] narrows the read to the predictions that no longer apply. The order is
     * the stored prediction time, newest first, with the stored id as the tie-breaker, so
     * a page boundary is always between two whole predictions.
     */
    @Query(
        "SELECT * FROM classification_snapshots WHERE " +
            "(:staleOnly = 0 OR staleReason IS NOT NULL) " +
            "ORDER BY predictionTime DESC, id DESC LIMIT :limit OFFSET :offset",
    )
    abstract suspend fun findPage(
        staleOnly: Int,
        limit: Int,
        offset: Int,
    ): List<ClassificationSnapshotEntity>

    /** Replaces the stored snapshot of one draft with the one just computed. */
    @Query(
        "UPDATE classification_snapshots SET " +
            "contextRevision = :contextRevision, " +
            "categoryRevision = :categoryRevision, " +
            "ruleRevision = :ruleRevision, " +
            "modelRevision = :modelRevision, " +
            "candidateSetVersion = :candidateSetVersion, " +
            "decisionSource = :decisionSource, " +
            "suggestedCategoryId = :suggestedCategoryId, " +
            "candidateIds = :candidateIds, " +
            "probabilities = :probabilities, " +
            "unknownShare = :unknownShare, " +
            "confidence = :confidence, " +
            "effectiveInputDigest = :effectiveInputDigest, " +
            "eventIds = :eventIds, " +
            "predictionTime = :predictionTime, " +
            "staleReason = NULL " +
            "WHERE draftId = :draftId",
    )
    abstract suspend fun replace(
        draftId: Long,
        contextRevision: String,
        categoryRevision: String,
        ruleRevision: String,
        modelRevision: String,
        candidateSetVersion: String,
        decisionSource: String,
        suggestedCategoryId: Long?,
        candidateIds: String,
        probabilities: String,
        unknownShare: Double?,
        confidence: Double?,
        effectiveInputDigest: String,
        eventIds: String,
        predictionTime: Long,
    ): Int

    /**
     * Records that the stored prediction of one draft is no longer current.
     *
     * A snapshot that is already stale keeps its first reason: the dependency that moved
     * first is the one that explains why the prediction stopped applying.
     */
    @Query(
        "UPDATE classification_snapshots SET staleReason = :reason " +
            "WHERE draftId = :draftId AND staleReason IS NULL",
    )
    abstract suspend fun markStaleForDraft(draftId: Long, reason: String): Int

    /**
     * Records that the stored predictions of every draft one event describes are no longer
     * current.
     *
     * This is the targeted invalidation: an event belongs to the drafts it is linked to, so
     * re-reading one event touches those drafts' suggestions and no other.
     */
    @Query(
        "UPDATE classification_snapshots SET staleReason = :reason " +
            "WHERE staleReason IS NULL AND draftId IN " +
            "(SELECT draftId FROM context_links WHERE eventId = :eventId)",
    )
    abstract suspend fun markStaleForEvent(eventId: Long, reason: String): Int

    /**
     * Records that the stored predictions of every draft one capture's events describe are
     * no longer current, because the text those readings came from changed.
     */
    @Query(
        "UPDATE classification_snapshots SET staleReason = :reason " +
            "WHERE staleReason IS NULL AND draftId IN " +
            "(SELECT DISTINCT context_links.draftId FROM context_links " +
            "INNER JOIN note_events ON note_events.id = context_links.eventId " +
            "WHERE note_events.captureId = :captureId)",
    )
    abstract suspend fun markStaleForCapture(captureId: Long, reason: String): Int
}
