package io.github.triet4p.aegisf.data

import androidx.room.withTransaction
import java.util.TimeZone
import java.util.UUID

/** Outcomes of an explicit revision or deletion of user-authored capture text. */
sealed interface CaptureMessageWriteResult {
    data class Saved(val revision: Int) : CaptureMessageWriteResult
    data class Deleted(val revision: Int) : CaptureMessageWriteResult
    data object NotFound : CaptureMessageWriteResult
    data object Changed : CaptureMessageWriteResult
    data object AlreadyDeleted : CaptureMessageWriteResult
    data object ImmutableSource : CaptureMessageWriteResult
}

/**
 * The explicit save/edit/delete API for durable user text.
 *
 * Only calls to [saveQuickCapture] and [saveManualAnnotation] persist user text. There is no API
 * for transient prompts or tool traces. A revision replaces the one current body under CAS and
 * does not alter bank evidence or a confirmed ledger record. Deletion clears the capture body and
 * keeps only identity, source/time metadata, the incremented revision, and a tombstone; a separate
 * confirmed ledger description follows its own explicit record lifecycle and is not deleted here.
 *
 * Editing or deleting the text of a capture also ends what was read from that text: the event
 * interpretations derived from the old text lose their derived content, and the active links
 * that described those interpretations go with them, in the same Room transaction as the change
 * itself. No copy of a changed or deleted text is left behind, and a refused link survives,
 * because the user's decision about a draft is not part of the text.
 */
class CaptureMessageRepository(
    private val database: LedgerDatabase,
    private val clock: LedgerClock = SystemLedgerClock,
    private val timezoneId: () -> String = { TimeZone.getDefault().id },
) {
    private val dao: CaptureMessageDao = database.captureMessageDao()
    private val eventContext = EventContextRepository(database = database, clock = clock)
    /** Save one user-authored quick capture with its capture instant and local timezone. */
    suspend fun saveQuickCapture(bodyText: String): Long = insertUserText(
        captureKind = CaptureKind.USER_NOTE,
        sourceKind = CaptureSourceKind.USER_QUICK_CAPTURE,
        bodyText = bodyText,
    )

    /** Save a standalone user annotation without changing the evidence or ledger it describes. */
    suspend fun saveManualAnnotation(bodyText: String): Long = insertUserText(
        captureKind = CaptureKind.MANUAL_ANNOTATION,
        sourceKind = CaptureSourceKind.USER_ANNOTATION,
        bodyText = bodyText,
    )

    /** Replace user-authored text only if its current revision still matches. */
    suspend fun revise(
        id: Long,
        expectedRevision: Int,
        bodyText: String,
    ): CaptureMessageWriteResult = database.withTransaction {
        val current = dao.findById(id) ?: return@withTransaction CaptureMessageWriteResult.NotFound
        current.writeEligibility()?.let { return@withTransaction it }
        val updated = dao.reviseUserText(id, expectedRevision, bodyText)
        if (updated != 1) {
            return@withTransaction dao.findById(id).writeEligibility()
                ?: CaptureMessageWriteResult.Changed
        }
        eventContext.invalidateDerivedReadings(id, SOURCE_EDITED)
        CaptureMessageWriteResult.Saved(expectedRevision + 1)
    }

    /** Permanently clear user-authored text while preserving only a tombstone and source metadata. */
    suspend fun delete(id: Long, expectedRevision: Int): CaptureMessageWriteResult =
        database.withTransaction {
            val current = dao.findById(id) ?: return@withTransaction CaptureMessageWriteResult.NotFound
            current.writeEligibility()?.let { return@withTransaction it }
            val updated = dao.tombstoneUserText(id, expectedRevision)
            if (updated != 1) {
                return@withTransaction dao.findById(id).writeEligibility()
                    ?: CaptureMessageWriteResult.Changed
            }
            eventContext.invalidateDerivedReadings(id, SOURCE_DELETED)
            CaptureMessageWriteResult.Deleted(expectedRevision + 1)
        }

    suspend fun findById(id: Long): CaptureMessageEntity? = dao.findById(id)

    private suspend fun insertUserText(
        captureKind: CaptureKind,
        sourceKind: CaptureSourceKind,
        bodyText: String,
    ): Long = dao.insert(
        CaptureMessageEntity(
            captureKind = captureKind,
            sourceKind = sourceKind,
            sourceIdentity = UUID.randomUUID().toString(),
            bodyText = bodyText,
            capturedAtEpochMillis = clock.nowEpochMillis(),
            captureTimezone = timezoneId(),
            revision = 0,
            originRef = null,
        ),
    ).also { id -> check(id != -1L) { "A new capture source identity was unexpectedly reused." } }

    private fun CaptureMessageEntity?.writeEligibility(): CaptureMessageWriteResult? {
        val current = this ?: return CaptureMessageWriteResult.NotFound
        if (!current.isUserEditable()) return CaptureMessageWriteResult.ImmutableSource
        if (current.tombstone) return CaptureMessageWriteResult.AlreadyDeleted
        return null
    }

    private fun CaptureMessageEntity.isUserEditable(): Boolean = when (captureKind) {
        CaptureKind.BANK_EVIDENCE_REF -> false
        CaptureKind.USER_NOTE -> sourceKind == CaptureSourceKind.USER_QUICK_CAPTURE
        CaptureKind.MANUAL_ANNOTATION -> sourceKind == CaptureSourceKind.USER_ANNOTATION
    }
}
