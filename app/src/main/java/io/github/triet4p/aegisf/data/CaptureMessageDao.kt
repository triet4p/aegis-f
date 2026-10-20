package io.github.triet4p.aegisf.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Persistence boundary for immutable bank evidence references and explicitly saved user text. */
@Dao
interface CaptureMessageDao {
    /** Inserts a new identity; a repeated source identity is refused rather than replaced. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: CaptureMessageEntity): Long

    @Query("SELECT * FROM capture_messages WHERE id = :id")
    suspend fun findById(id: Long): CaptureMessageEntity?

    @Query(
        "SELECT * FROM capture_messages " +
            "WHERE sourceKind = :sourceKind AND sourceIdentity = :sourceIdentity LIMIT 1",
    )
    suspend fun findBySourceIdentity(
        sourceKind: CaptureSourceKind,
        sourceIdentity: String,
    ): CaptureMessageEntity?

    @Query("SELECT * FROM capture_messages ORDER BY id")
    suspend fun findAllIncludingTombstones(): List<CaptureMessageEntity>

    /**
     * A bounded page of the stored sources.
     *
     * Every dimension is open when it is null: the capture kind, the source kind, the
     * stored/deleted state, and the two ends of the captured-in range. A source whose
     * capture instant is unknown — a legacy row that never recorded one — is in no range
     * and is returned only by an unranged read.
     *
     * The order is the capture instant, newest first, with the stored id as the
     * tie-breaker. A source without a capture instant sorts last, because an unknown
     * instant is older than no instant at all rather than newer than everything.
     */
    @Query(
        "SELECT * FROM capture_messages WHERE " +
            "(:captureKind IS NULL OR captureKind = :captureKind) AND " +
            "(:sourceKind IS NULL OR sourceKind = :sourceKind) AND " +
            "(:tombstone IS NULL OR tombstone = :tombstone) AND " +
            "(:fromEpochMillis IS NULL OR capturedAtEpochMillis >= :fromEpochMillis) AND " +
            "(:throughExclusiveEpochMillis IS NULL OR " +
            "capturedAtEpochMillis < :throughExclusiveEpochMillis) " +
            "ORDER BY capturedAtEpochMillis DESC, id DESC LIMIT :limit OFFSET :offset",
    )
    suspend fun findPage(
        captureKind: String?,
        sourceKind: String?,
        tombstone: Boolean?,
        fromEpochMillis: Long?,
        throughExclusiveEpochMillis: Long?,
        limit: Int,
        offset: Int,
    ): List<CaptureMessageEntity>

    /** User-origin text rows only; legacy ledger descriptions follow the ledger edit lifecycle. */
    @Query(
        "UPDATE capture_messages SET bodyText = :bodyText, revision = revision + 1 " +
            "WHERE id = :id AND revision = :expectedRevision AND tombstone = 0 " +
            "AND ((captureKind = 'USER_NOTE' AND sourceKind = 'USER_QUICK_CAPTURE') OR " +
            "(captureKind = 'MANUAL_ANNOTATION' AND sourceKind = 'USER_ANNOTATION'))",
    )
    suspend fun reviseUserText(id: Long, expectedRevision: Int, bodyText: String): Int

    /** Delete raw text permanently while keeping only text-free user-capture metadata. */
    @Query(
        "UPDATE capture_messages SET bodyText = NULL, tombstone = 1, revision = revision + 1 " +
            "WHERE id = :id AND revision = :expectedRevision AND tombstone = 0 " +
            "AND ((captureKind = 'USER_NOTE' AND sourceKind = 'USER_QUICK_CAPTURE') OR " +
            "(captureKind = 'MANUAL_ANNOTATION' AND sourceKind = 'USER_ANNOTATION'))",
    )
    suspend fun tombstoneUserText(id: Long, expectedRevision: Int): Int

    /** An explicit ledger correction replaces its migrated legacy description without history. */
    @Query(
        "UPDATE capture_messages SET bodyText = :bodyText, revision = revision + 1 " +
            "WHERE originRef = :originRef AND captureKind = 'MANUAL_ANNOTATION' " +
            "AND sourceKind = 'LEGACY_LEDGER_DESCRIPTION' AND tombstone = 0 " +
            "AND bodyText != :bodyText",
    )
    suspend fun reviseLegacyLedgerDescription(originRef: String, bodyText: String): Int

    /** An explicit ledger deletion clears its migrated legacy description in the same transaction. */
    @Query(
        "UPDATE capture_messages SET bodyText = NULL, tombstone = 1, revision = revision + 1 " +
            "WHERE originRef = :originRef AND captureKind = 'MANUAL_ANNOTATION' " +
            "AND sourceKind = 'LEGACY_LEDGER_DESCRIPTION' AND tombstone = 0",
    )
    suspend fun tombstoneLegacyLedgerDescription(originRef: String): Int
}
