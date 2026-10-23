package io.github.triet4p.aegisf.data

import androidx.room.withTransaction
import io.github.triet4p.aegisf.ui.ManualDateTime
import kotlinx.coroutines.flow.Flow
import java.util.TimeZone

/** Why a stored reading lost its derived content: the source text was replaced. */
internal const val SOURCE_EDITED = "SOURCE_EDITED"

/** Why a stored reading lost its derived content: the source text was deleted. */
internal const val SOURCE_DELETED = "SOURCE_DELETED"

/**
 * One reading of one span of a capture's text, as an extraction produced it.
 *
 * Every optional field is null when the source did not state it. A missing amount is not a
 * zero and a missing time is not "now": the caller states what it could not read in
 * [unresolvedReason], and the stored reading keeps that reason instead of a plausible guess.
 */
data class NoteEventInterpretation(
    /** Inclusive start offset of the span inside the capture's current text. */
    val spanStart: Int,
    /** Exclusive end offset of the span inside the capture's current text. */
    val spanEnd: Int,
    /** The normalized reading of the span. */
    val normalizedText: String,
    /** The stated amount in whole dong, or null when the source stated none. */
    val amountVnd: Long? = null,
    /** The stated merchant, or null when the source stated none. */
    val merchant: String? = null,
    /** The stated time anchor or interval, or null when the source stated no time. */
    val timeAnchor: NoteEventTimeAnchor? = null,
    /** What the extraction could not read, or null when the reading is complete. */
    val unresolvedReason: String? = null,
)

/** The field a stored reading was refused for. */
enum class NoteEventField {
    SPAN,
    TEXT,
    AMOUNT,
    MERCHANT,
    TIME,
    EXTRACTION_VERSION,
}

/** Observable outcomes of storing one event reading. */
sealed interface NoteEventWriteResult {
    /** A new reading was stored for a span that had none. */
    data class Stored(val eventId: Long, val revision: Int) : NoteEventWriteResult

    /** The stored reading of an existing span was replaced. */
    data class Revised(val eventId: Long, val revision: Int) : NoteEventWriteResult

    /** The same reading was already stored for that span; nothing was written. */
    data object NoChange : NoteEventWriteResult

    /** The capture the reading was taken from does not exist. */
    data object NotFound : NoteEventWriteResult

    /** The capture changed under the caller; the reading was taken from another revision. */
    data object Changed : NoteEventWriteResult

    /** The capture's text was deleted; no reading can be taken from it. */
    data object SourceDeleted : NoteEventWriteResult

    /** The reading was refused, and the named field says why. */
    data class Invalid(val field: NoteEventField, val reason: String) : NoteEventWriteResult

    /** A user accepted a link for this event, so its reading is not replaced implicitly. */
    data object AcceptedLink : NoteEventWriteResult
}

/** Observable outcomes of one link decision. */
sealed interface ContextLinkResult {
    /** The link exists in the stated state. */
    data class Linked(
        val linkId: Long,
        val state: ContextLinkState,
        val revision: Int,
    ) : ContextLinkResult

    /** An active link was removed; a refused link is never removable. */
    data class Unlinked(val linkId: Long) : ContextLinkResult

    /** The link already stood in the requested state; nothing was written. */
    data object NoChange : ContextLinkResult

    /** No such event, draft, or link exists. */
    data object NotFound : ContextLinkResult

    /** The link changed under the caller; the expected revision no longer matches. */
    data object Changed : ContextLinkResult

    /** The pair was refused before and stays refused; a refusal is never recreated. */
    data object Rejected : ContextLinkResult

    /** The event holds no current reading, so it cannot carry a link. */
    data object EventUnavailable : ContextLinkResult

    /** The referenced draft does not exist. */
    data object DraftUnavailable : ContextLinkResult

    /** The event already holds an active draft, so a second one was refused. */
    data object MultipleDrafts : ContextLinkResult

    /**
     * The event was already recorded as a ledger record, so it is spent.
     *
     * The user wrote the occurrence down by hand, and the record that resolved it stands.
     * A bank notification that arrives later may not consume the same occurrence again,
     * so no link is created for a resolved event.
     */
    data object EventAlreadyRecorded : ContextLinkResult
}

/**
 * Stores event interpretations of a capture and the links between them and bank drafts.
 *
 * The rules this class owns, and which no caller can bypass:
 *
 * - A reading is taken from a named span of a named capture revision. A span outside the
 *   current text, a capture that changed under the caller, a deleted capture, an unusable
 *   amount, and a malformed time are all refused, and a refused reading writes nothing at all.
 * - One span of one capture holds one reading. The stored identity is derived from the
 *   capture's own source identity and the span, so a retry, a restart, or a repeated
 *   extraction of the same span addresses the same event instead of appending a second copy.
 *   Re-stating the same reading writes nothing; changing it is an explicit revision.
 * - A reading never fabricates a value. A missing amount, merchant, or time stays missing, and
 *   the reason it is missing is stored with the reading.
 * - A link is proposed, accepted, or refused, and the state is stored rather than inferred. A
 *   refused link stays refused: it survives restarts, retries, and re-processing, and no
 *   statement in the storage layer moves it back to a proposal. A refusal is not removable
 *   either, because removing it would recreate the very link the user refused.
 * - An event holds at most one active draft. Several independent events may describe the same
 *   draft, but a second active link for one event is refused — in the application layer and by
 *   the database's own unique index.
 * - A user-accepted link is a user choice: a later extraction may not silently replace the
 *   reading behind it.
 *
 * The class is internal: the readings and the links are written through the application's
 * one command boundary, so a caller cannot store a reading or a link around the
 * boundary's scope, idempotency, and revision rules.
 */
internal class EventContextRepository(
    private val database: LedgerDatabase,
    private val clock: LedgerClock = SystemLedgerClock,
) {
    private val captureDao = database.captureMessageDao()
    private val eventDao = database.noteEventDao()
    private val linkDao = database.contextLinkDao()
    private val draftDao = database.pendingBankDraftDao()
    private val snapshotDao = database.classificationSnapshotDao()

    /**
     * Stores the reading of one span of a capture.
     *
     * [expectedCaptureRevision] is the capture revision the reading was taken from: when the
     * capture has moved on, the reading is refused with [NoteEventWriteResult.Changed].
     * [expectedEventRevision] similarly prevents an edit from overwriting a newer reading.
     */
    suspend fun recordEvent(
        captureId: Long,
        expectedCaptureRevision: Int,
        interpretation: NoteEventInterpretation,
        extractionVersion: String,
        expectedEventRevision: Int? = null,
    ): NoteEventWriteResult = database.withTransaction {
        val capture = captureDao.findById(captureId)
            ?: return@withTransaction NoteEventWriteResult.NotFound
        if (capture.tombstone) return@withTransaction NoteEventWriteResult.SourceDeleted
        if (capture.revision != expectedCaptureRevision) {
            return@withTransaction NoteEventWriteResult.Changed
        }
        val body = capture.bodyText
            ?: return@withTransaction NoteEventWriteResult.Invalid(
                NoteEventField.SPAN,
                "The capture holds no text to read a span from.",
            )
        spanError(interpretation.spanStart, interpretation.spanEnd, body.length)?.let { reason ->
            return@withTransaction NoteEventWriteResult.Invalid(NoteEventField.SPAN, reason)
        }
        if (interpretation.normalizedText.isBlank()) {
            return@withTransaction NoteEventWriteResult.Invalid(
                NoteEventField.TEXT,
                "A stored reading needs the text it read.",
            )
        }
        if (extractionVersion.isBlank()) {
            return@withTransaction NoteEventWriteResult.Invalid(
                NoteEventField.EXTRACTION_VERSION,
                "A stored reading needs the extraction that produced it.",
            )
        }
        amountError(interpretation.amountVnd, interpretation.unresolvedReason)?.let { reason ->
            return@withTransaction NoteEventWriteResult.Invalid(NoteEventField.AMOUNT, reason)
        }
        val merchant = interpretation.merchant?.trim()
        if (merchant != null && merchant.isEmpty()) {
            return@withTransaction NoteEventWriteResult.Invalid(
                NoteEventField.MERCHANT,
                "A stated merchant cannot be blank.",
            )
        }
        timeError(interpretation.timeAnchor)?.let { reason ->
            return@withTransaction NoteEventWriteResult.Invalid(NoteEventField.TIME, reason)
        }
        val anchor = interpretation.timeAnchor
        val unresolvedReason = interpretation.unresolvedReason?.trim()?.takeIf { it.isNotEmpty() }
        val identity = noteEventIdentity(
            sourceKind = capture.sourceKind,
            sourceIdentity = capture.sourceIdentity,
            spanStart = interpretation.spanStart,
            spanEnd = interpretation.spanEnd,
        )
        val existing = eventDao.findByIdentity(captureId, identity)
        if (expectedEventRevision != null &&
            (existing == null || existing.revision != expectedEventRevision)
        ) {
            return@withTransaction NoteEventWriteResult.Changed
        }
        val status = interpretationStatus(
            eventId = existing?.id,
            unresolved = unresolvedReason != null,
        )
        val reading = NoteEventEntity(
            id = existing?.id ?: 0L,
            captureId = captureId,
            eventIdentity = identity,
            spanStart = interpretation.spanStart,
            spanEnd = interpretation.spanEnd,
            normalizedText = interpretation.normalizedText.trim(),
            amountVnd = interpretation.amountVnd,
            merchant = merchant,
            anchorDate = anchor?.date,
            anchorTime = anchor?.time,
            anchorTimezone = anchor?.timezoneId,
            intervalEndDate = anchor?.intervalEndDate,
            intervalEndTime = anchor?.intervalEndTime,
            extractionVersion = extractionVersion.trim(),
            unresolvedReason = unresolvedReason,
            status = status,
            revision = existing?.revision ?: 0,
            sourceRevision = capture.revision,
            createdAtEpochMillis = existing?.createdAtEpochMillis ?: clock.nowEpochMillis(),
        )
        if (existing == null) {
            val eventId = eventDao.insert(reading)
            NoteEventWriteResult.Stored(eventId, 0)
        } else {
            if (existing.sameReading(reading)) return@withTransaction NoteEventWriteResult.NoChange
            val accepted = linkDao.findActiveForEvent(existing.id)
                .any { it.state == ContextLinkState.ACCEPTED }
            if (accepted) return@withTransaction NoteEventWriteResult.AcceptedLink
            val updated = eventDao.updateReading(
                id = existing.id,
                expectedRevision = existing.revision,
                spanStart = reading.spanStart,
                spanEnd = reading.spanEnd,
                normalizedText = reading.normalizedText,
                amountVnd = reading.amountVnd,
                merchant = reading.merchant,
                anchorDate = reading.anchorDate,
                anchorTime = reading.anchorTime,
                anchorTimezone = reading.anchorTimezone,
                intervalEndDate = reading.intervalEndDate,
                intervalEndTime = reading.intervalEndTime,
                extractionVersion = reading.extractionVersion,
                unresolvedReason = reading.unresolvedReason,
                status = reading.status.name,
                sourceRevision = reading.sourceRevision,
            )
            if (updated == 1) {
                // The reading the drafts' suggestions were made from has changed, so those
                // suggestions are stale. Only the drafts this event is linked to are touched.
                snapshotDao.markStaleForEvent(
                    existing.id,
                    PredictionStaleReason.CONTEXT_CHANGED.name,
                )
                NoteEventWriteResult.Revised(existing.id, existing.revision + 1)
            } else {
                NoteEventWriteResult.Changed
            }
        }
    }

    /**
     * Proposes that one event describes one draft.
     *
     * The proposal records the app's *own* evidence about the association: the facts the app
     * can read from the stored reading and the stored draft, bound to the exact source,
     * event, draft, and revisions they were read from. A caller cannot supply that evidence,
     * so a proposal can never carry a reason that the app did not compute — and an
     * association the app cannot prove stays ambiguous, which is the user's to resolve.
     *
     * A refused pair stays refused and is reported as [ContextLinkResult.Rejected] without any
     * write, so a repeated proposal after a refusal changes nothing.
     */
    suspend fun proposeLink(
        eventId: Long,
        draftId: Long,
    ): ContextLinkResult = database.withTransaction {
        val event = eventDao.findById(eventId)
            ?: return@withTransaction ContextLinkResult.NotFound
        if (event.normalizedText == null) {
            return@withTransaction ContextLinkResult.EventUnavailable
        }
        // A note the user already recorded by hand is spent: the ledger record that
        // resolved it stands, so a bank notification may not consume the same occurrence.
        if (event.resolvedTransactionId != null) {
            return@withTransaction ContextLinkResult.EventAlreadyRecorded
        }
        val draft = draftDao.findById(draftId)
            ?: return@withTransaction ContextLinkResult.DraftUnavailable
        val existing = linkDao.findByPair(eventId, draftId)
        if (existing != null) {
            return@withTransaction when (existing.state) {
                ContextLinkState.REJECTED -> ContextLinkResult.Rejected
                ContextLinkState.PROPOSED, ContextLinkState.ACCEPTED -> ContextLinkResult.NoChange
            }
        }
        if (linkDao.findActiveForEvent(eventId).isNotEmpty()) {
            return@withTransaction ContextLinkResult.MultipleDrafts
        }
        val evidence = LinkEvidencePolicy(database).evidenceFor(event, draft)
        val linkId = linkDao.insert(
            ContextLinkEntity(
                eventId = eventId,
                draftId = draftId,
                state = ContextLinkState.PROPOSED,
                evidence = evidence.canonical,
                revision = 0,
                userOverride = null,
                activeEventId = eventId,
                createdAtEpochMillis = clock.nowEpochMillis(),
            ),
        )
        // The draft's effective context now carries this reading, so a prediction made
        // before the link no longer describes what the app would ask the model now.
        snapshotDao.markStaleForDraft(draftId, PredictionStaleReason.CONTEXT_CHANGED.name)
        ContextLinkResult.Linked(linkId, ContextLinkState.PROPOSED, 0)
    }

    /**
     * Accepts a proposed link.
     *
     * A refused link is never accepted: the refusal is the user's decision about that pair,
     * and only a new reading of the event may ask again.
     */
    suspend fun acceptLink(
        eventId: Long,
        draftId: Long,
        expectedRevision: Int,
        userOverride: String? = null,
    ): ContextLinkResult = database.withTransaction {
        val link = linkDao.findByPair(eventId, draftId)
            ?: return@withTransaction ContextLinkResult.NotFound
        if (link.state == ContextLinkState.REJECTED) return@withTransaction ContextLinkResult.Rejected
        if (link.state == ContextLinkState.ACCEPTED) return@withTransaction ContextLinkResult.NoChange
        if (link.revision != expectedRevision) return@withTransaction ContextLinkResult.Changed
        val event = eventDao.findById(link.eventId)
            ?: return@withTransaction ContextLinkResult.NotFound
        if (event.normalizedText == null) {
            return@withTransaction ContextLinkResult.EventUnavailable
        }
        // A note the user recorded by hand in the meantime is spent: the attachment is
        // refused, so the bank notification may not consume the same occurrence again.
        if (event.resolvedTransactionId != null) {
            return@withTransaction ContextLinkResult.EventAlreadyRecorded
        }
        if (linkDao.accept(link.id, expectedRevision, userOverride) != 1) {
            return@withTransaction ContextLinkResult.Changed
        }
        refreshEventStatus(event.id)
        snapshotDao.markStaleForDraft(draftId, PredictionStaleReason.CONTEXT_CHANGED.name)
        ContextLinkResult.Linked(link.id, ContextLinkState.ACCEPTED, expectedRevision + 1)
    }

    /**
     * Refuses a link.
     *
     * A refusal is durable: it is stored, it is not removable, and no later proposal or
     * acceptance of the same pair moves it. When it leaves the event without an active draft,
     * the event is recorded as having no match.
     */
    suspend fun rejectLink(
        eventId: Long,
        draftId: Long,
        expectedRevision: Int,
        userOverride: String? = null,
    ): ContextLinkResult = database.withTransaction {
        val link = linkDao.findByPair(eventId, draftId)
            ?: return@withTransaction ContextLinkResult.NotFound
        if (link.state == ContextLinkState.REJECTED) return@withTransaction ContextLinkResult.NoChange
        if (link.revision != expectedRevision) return@withTransaction ContextLinkResult.Changed
        if (linkDao.reject(link.id, expectedRevision, userOverride) != 1) {
            return@withTransaction ContextLinkResult.Changed
        }
        refreshEventStatus(link.eventId)
        snapshotDao.markStaleForDraft(draftId, PredictionStaleReason.CONTEXT_CHANGED.name)
        ContextLinkResult.Linked(link.id, ContextLinkState.REJECTED, expectedRevision + 1)
    }

    /**
     * Removes an active link, restoring the event's own context.
     *
     * A refused link is not removable: dropping it would let the very link the user refused be
     * proposed again, which is the silent resurrection the refusal exists to prevent.
     */
    suspend fun unlink(eventId: Long, draftId: Long, expectedRevision: Int): ContextLinkResult =
        database.withTransaction {
            val link = linkDao.findByPair(eventId, draftId)
                ?: return@withTransaction ContextLinkResult.NotFound
            if (link.state == ContextLinkState.REJECTED) {
                return@withTransaction ContextLinkResult.Rejected
            }
            if (link.revision != expectedRevision) return@withTransaction ContextLinkResult.Changed
            if (linkDao.deleteActive(link.id, expectedRevision) != 1) {
                return@withTransaction ContextLinkResult.Changed
            }
            refreshEventStatus(link.eventId)
            snapshotDao.markStaleForDraft(draftId, PredictionStaleReason.CONTEXT_CHANGED.name)
            ContextLinkResult.Unlinked(link.id)
        }

    /**
     * Drops the readings that were taken from a capture whose text changed or was deleted.
     *
     * The derived text, amount, merchant, and time of every event of that capture are cleared
     * and the events become unresolved, and the active links that described those readings are
     * removed with them. A refused link stays, because the user's decision about that draft
     * outlives the reading it was made against.
     */
    internal suspend fun invalidateDerivedReadings(captureId: Long, reason: String) {
        val events = eventDao.findForCapture(captureId)
        if (events.isEmpty()) return
        // The predictions those readings supported no longer describe anything: the text they
        // were made from is gone, so the drafts they belong to are marked stale here, in the
        // same transaction as the change that ended the readings.
        snapshotDao.markStaleForCapture(captureId, PredictionStaleReason.SOURCE_CHANGED.name)
        linkDao.deleteActiveForCapture(captureId)
        val refused = linkDao.findRejectedForCapture(captureId).map { it.eventId }.toSet()
        events.forEach { event ->
            val status = if (event.id in refused) NoteEventStatus.NO_MATCH else NoteEventStatus.UNRESOLVED
            eventDao.clearDerivedReading(event.id, reason, status.name)
        }
    }

    suspend fun findEvent(eventId: Long): NoteEventEntity? = eventDao.findById(eventId)

    suspend fun eventsForCapture(captureId: Long): List<NoteEventEntity> =
        eventDao.findForCapture(captureId)

    fun observeEventsForCapture(captureId: Long): Flow<List<NoteEventEntity>> =
        eventDao.observeForCapture(captureId)

    suspend fun linksForEvent(eventId: Long): List<ContextLinkEntity> = linkDao.findForEvent(eventId)

    suspend fun linksForDraft(draftId: Long): List<ContextLinkEntity> = linkDao.findForDraft(draftId)

    /** Recomputes the interpretation status a link decision produced. */
    private suspend fun refreshEventStatus(eventId: Long) {
        val event = eventDao.findById(eventId) ?: return
        val status = interpretationStatus(eventId, unresolved = event.unresolvedReason != null)
        if (status != event.status) eventDao.updateStatus(eventId, status.name)
    }

    /**
     * The interpretation status of one event.
     *
     * An active link means the event is still in play, a refusal with no active link means the
     * event has no match, and an incomplete reading is always reported as unresolved.
     */
    private suspend fun interpretationStatus(eventId: Long?, unresolved: Boolean): NoteEventStatus {
        if (eventId == null) {
            return if (unresolved) NoteEventStatus.UNRESOLVED else NoteEventStatus.INTERPRETED
        }
        val links = linkDao.findForEvent(eventId)
        val active = links.any {
            it.state == ContextLinkState.PROPOSED || it.state == ContextLinkState.ACCEPTED
        }
        val refused = links.any { it.state == ContextLinkState.REJECTED }
        return when {
            active -> if (unresolved) NoteEventStatus.UNRESOLVED else NoteEventStatus.INTERPRETED
            refused -> NoteEventStatus.NO_MATCH
            else -> if (unresolved) NoteEventStatus.UNRESOLVED else NoteEventStatus.INTERPRETED
        }
    }

    /** The reason a span is unusable, or null when it addresses real text. */
    private fun spanError(spanStart: Int, spanEnd: Int, bodyLength: Int): String? = when {
        spanStart < 0 -> "A source span cannot start before the beginning of the text."
        spanEnd <= spanStart -> "A source span must cover at least one character."
        spanEnd > bodyLength -> "A source span cannot end after the end of the text."
        else -> null
    }

    /** The reason an amount is unusable, or null when it is usable. */
    private fun amountError(amountVnd: Long?, unresolvedReason: String?): String? {
        if (amountVnd == null) {
            return if (unresolvedReason.isNullOrBlank()) {
                "A reading without an amount must say what it could not read."
            } else {
                null
            }
        }
        return try {
            VndMoney.requirePositiveAmount(amountVnd)
            null
        } catch (error: LedgerValidationException) {
            error.message
        }
    }

    /** The reason a time anchor is unusable, or null when it is usable. */
    private fun timeError(anchor: NoteEventTimeAnchor?): String? {
        if (anchor == null) return null
        val date = anchor.date
        val time = anchor.time
        val endDate = anchor.intervalEndDate
        val endTime = anchor.intervalEndTime
        if (date == null && time != null) {
            return "A stated time needs the date it belongs to."
        }
        if (date != null && ManualDateTime.dateError(date) != null) {
            return "A stated date must be written as ${ManualDateTime.DATE_FORMAT}."
        }
        if (time != null && ManualDateTime.timeError(time) != null) {
            return "A stated time must be written as ${ManualDateTime.TIME_FORMAT}."
        }
        if (anchor.timezoneId != null && time == null) {
            return "A stated time zone needs the time it anchors."
        }
        if (anchor.timezoneId != null && !isKnownTimeZone(anchor.timezoneId)) {
            return "A stated time zone must be a known zone."
        }
        if (endDate == null && endTime != null) {
            return "A stated interval end needs its own date."
        }
        if (endDate != null && date == null) {
            return "A stated interval needs the date it starts from."
        }
        if (endDate != null && ManualDateTime.dateError(endDate) != null) {
            return "A stated interval end must be written as ${ManualDateTime.DATE_FORMAT}."
        }
        if (endTime != null && ManualDateTime.timeError(endTime) != null) {
            return "A stated interval end must be written as ${ManualDateTime.TIME_FORMAT}."
        }
        if (endTime != null && time == null) {
            return "A stated interval end needs the time it starts from."
        }
        if (date != null && endDate != null && endDate < date) {
            return "A stated interval cannot end before it starts."
        }
        if (date != null && endDate == date && time != null && endTime != null && endTime < time) {
            return "A stated interval cannot end before it starts."
        }
        return null
    }

    /** Whether a stated zone is one this device knows. */
    private fun isKnownTimeZone(timezoneId: String): Boolean =
        TimeZone.getTimeZone(timezoneId).id == timezoneId
}

/** Whether the stored reading already says exactly what a new reading says. */
private fun NoteEventEntity.sameReading(other: NoteEventEntity): Boolean =
    spanStart == other.spanStart &&
        spanEnd == other.spanEnd &&
        normalizedText == other.normalizedText &&
        amountVnd == other.amountVnd &&
        merchant == other.merchant &&
        anchorDate == other.anchorDate &&
        anchorTime == other.anchorTime &&
        anchorTimezone == other.anchorTimezone &&
        intervalEndDate == other.intervalEndDate &&
        intervalEndTime == other.intervalEndTime &&
        extractionVersion == other.extractionVersion &&
        unresolvedReason == other.unresolvedReason
