package io.github.triet4p.aegisf.data

import io.github.triet4p.aegisf.ui.ManualDateTime
import java.text.Normalizer
import java.util.Locale

/**
 * Whether the app's own facts prove one association between a note event and a bank draft.
 *
 * The verdict is a fact about the stored data, never a score and never a claim: the app
 * either knows which draft the reading describes, or it does not.
 */
enum class LinkEvidenceVerdict {
    /** The app's facts support exactly one association: the same money, on the same day, with no competing candidate. */
    UNIQUE,

    /** The app's facts do not support one association; the decision is the user's. */
    AMBIGUOUS,
}

/**
 * The trusted evidence about one proposed association between a note event and a bank draft.
 *
 * This evidence is *computed by the app* from the rows it stores, and it is bound to the
 * exact source, event, draft, and revisions it was computed from. It is never read from a
 * request, from a model's arguments, or from anything a caller wrote: a note, a bank memo,
 * and a proposed reason are data, not authority.
 *
 * The facts it records are facts, not a similarity score:
 *
 * - the amount the reading states and the amount the draft states, and whether they are
 *   exactly the same whole dong;
 * - the calendar day the reading states and the calendar day the draft states, and whether
 *   they are the same day — the bank's wall clock is read under the app's own device-zone
 *   policy, the same policy its confirmation uses, so the two days are comparable;
 * - how many stored drafts state that same amount on that same day, and how many of them
 *   are the one draft — one candidate means the app knows which draft the reading
 *   describes, and more than one means the choice is the user's;
 * - how many stored drafts are an uncertain duplicate of this one — a possible duplicate is
 *   an uncertain identity, so the user decides which of the two is the right draft;
 * - whether the reading is complete and whether the draft stated its amount and its moment
 *   as readable values, so a missing or unreadable field is never read as agreement.
 *
 * The verdict is [LinkEvidenceVerdict.UNIQUE] only when every one of those facts holds.
 * Anything else is [LinkEvidenceVerdict.AMBIGUOUS], and an ambiguous association is the
 * user's to resolve.
 */
data class TrustedLinkEvidence(
    /** The event the association is about. */
    val eventId: Long,
    /** The capture whose text the reading was taken from. */
    val captureId: Long,
    /** The revision of that capture now. */
    val captureRevision: Int,
    /** The revision of the reading now. */
    val eventRevision: Int,
    /** The capture revision the reading was taken from. */
    val eventSourceRevision: Int,
    /** The draft the association is about. */
    val draftId: Long,
    /** The review revision of that draft now. */
    val draftReviewRevision: Int,
    /** The amount the reading states, or null when it states none. */
    val eventAmountVnd: Long?,
    /** The calendar day the reading states, or null when it states none. */
    val eventAnchorDate: String?,
    /** The amount the draft states, or null when it states none. */
    val draftAmountVnd: Long?,
    /** The calendar day the draft states, or null when it states none. */
    val draftStatedDate: String?,
    /** How many stored drafts state that same amount on that same day. */
    val candidateDraftCount: Int,
    /** How many stored drafts are an uncertain duplicate of this draft. */
    val duplicatePartnerCount: Int,
    /** Whether the reading is complete and still supported by the capture's text. */
    val eventReadingComplete: Boolean,
    /** Whether the draft stated its amount as a readable value. */
    val draftAmountStated: Boolean,
    /** Whether the draft stated its moment as a readable value. */
    val draftMomentStated: Boolean,
) {
    /** Whether the two amounts are exactly the same whole dong. */
    val amountAgreed: Boolean
        get() = eventAmountVnd != null && eventAmountVnd == draftAmountVnd

    /** Whether the two calendar days are the same day. */
    val dateAgreed: Boolean
        get() = eventAnchorDate != null && eventAnchorDate == draftStatedDate

    /** Whether this draft is the only candidate the app's facts allow. */
    val soleCandidate: Boolean
        get() = candidateDraftCount == 1

    /** Whether this draft is not one of an uncertain duplicate pair. */
    val noDuplicatePartner: Boolean
        get() = duplicatePartnerCount == 0

    /**
     * Why the app's facts do not prove one association, in a fixed order.
     *
     * An empty list means the facts prove one association. Every entry names one fact that
     * is missing, unreadable, contradictory, or not unique.
     */
    val reasons: List<String>
        get() = buildList {
            if (!eventReadingComplete) add(REASON_READING_UNAVAILABLE)
            if (!draftAmountStated || eventAmountVnd == null) {
                add(REASON_AMOUNT_NOT_STATED)
            } else if (!amountAgreed) {
                add(REASON_AMOUNT_MISMATCH)
            }
            if (!draftMomentStated || eventAnchorDate == null) {
                add(REASON_MOMENT_NOT_STATED)
            } else if (!dateAgreed) {
                add(REASON_DATE_MISMATCH)
            }
            if (!soleCandidate) add(REASON_COMPETING_CANDIDATE)
            if (!noDuplicatePartner) add(REASON_UNCERTAIN_DUPLICATE)
        }

    /** What the app's facts prove about this association. */
    val verdict: LinkEvidenceVerdict
        get() = if (reasons.isEmpty()) LinkEvidenceVerdict.UNIQUE else LinkEvidenceVerdict.AMBIGUOUS

    /**
     * The canonical form of this evidence.
     *
     * It names the exact source, event, draft, and revisions it was computed from and the
     * facts themselves, so a stored evidence can be compared with the evidence the app
     * computes now: an evidence that no longer describes the world is not the evidence the
     * proposal was made on.
     */
    val canonical: String
        get() = encodeTrustedLinkEvidence(this)
}

/**
 * Computes the app's evidence for both candidate retrieval and later link eligibility.
 *
 * [candidateMatches] returns a deterministic, evidence-ranked view of pending drafts already
 * authorized and read through the bounded query path. Its amount, interval and merchant facts
 * are retrieval evidence only: it neither chooses a candidate nor changes attachment policy.
 * [evidenceFor] remains the narrower attachment check, comparing exact amount/day and stored
 * competition; merchant similarity and ranking never authorize an association.
 */
internal class LinkEvidencePolicy(
    private val database: LedgerDatabase,
) {
    private val draftDao = database.pendingBankDraftDao()
    private val captureDao = database.captureMessageDao()

    /**
     * Ranks only pending rows already read by the bounded, trusted candidate query.
     *
     * It is retrieval evidence, not a write decision: ranking is deterministic and never
     * turns proximity, category choice, or model certainty into identity.
     */
    fun candidateMatches(
        event: NoteEventEntity,
        drafts: List<PendingBankDraftEntity>,
        pendingDatabaseComplete: Boolean,
    ): DraftCandidateMatchSet {
        val hasSearchEvidence =
            event.amountVnd != null || event.anchorDate != null || !event.merchant.isNullOrBlank()
        if (!hasSearchEvidence) {
            return DraftCandidateMatchSet(
                eventId = event.id,
                eventRevision = event.revision,
                outcome = if (pendingDatabaseComplete) {
                    DraftCandidateMatchOutcome.MISSING
                } else {
                    DraftCandidateMatchOutcome.INCOMPLETE
                },
                candidates = emptyList(),
                conflictingDraftCount = 0,
                pendingDatabaseComplete = pendingDatabaseComplete,
                rankingRationale = MATCH_RANKING_RATIONALE,
            )
        }

        val matches = mutableListOf<DraftCandidateMatch>()
        var conflicts = 0
        var sufficientMatches = 0
        for (draft in drafts) {
            if (draft.reviewStatus != IntakeReviewStatus.PENDING || draft.confirmedTransactionId != null) {
                continue
            }
            val assessment = assessDraftCandidate(event, draft)
            if (assessment.conflicting) {
                conflicts += 1
            } else {
                matches += assessment.match
                if (assessment.sufficientEvidence) sufficientMatches += 1
            }
        }

        matches.sortWith(
            compareByDescending<DraftCandidateMatch> { it.amount == DraftCandidateEvidenceState.MATCH }
                .thenByDescending { it.transactionTime == DraftCandidateEvidenceState.MATCH }
                .thenByDescending { it.merchant == DraftCandidateEvidenceState.MATCH }
                .thenBy { it.pointTimeDistanceMinutes ?: Int.MAX_VALUE }
                .thenByDescending { it.draft.createdAtEpochMillis }
                .thenByDescending { it.draft.id },
        )

        val outcome = when {
            !pendingDatabaseComplete -> DraftCandidateMatchOutcome.INCOMPLETE
            matches.size > 1 -> DraftCandidateMatchOutcome.AMBIGUOUS
            matches.isEmpty() && conflicts > 0 -> DraftCandidateMatchOutcome.CONFLICTING
            matches.isEmpty() -> DraftCandidateMatchOutcome.MISSING
            sufficientMatches == 1 -> DraftCandidateMatchOutcome.UNIQUE
            else -> DraftCandidateMatchOutcome.MISSING
        }
        return DraftCandidateMatchSet(
            eventId = event.id,
            eventRevision = event.revision,
            outcome = outcome,
            candidates = matches,
            conflictingDraftCount = conflicts,
            pendingDatabaseComplete = pendingDatabaseComplete,
            rankingRationale = MATCH_RANKING_RATIONALE,
        )
    }

    /**
     * The evidence about the association between one reading and one draft.
     *
     * The rows are read as they are stored now, so the evidence always describes the state
     * the app would act on.
     */
    suspend fun evidenceFor(
        event: NoteEventEntity,
        draft: PendingBankDraftEntity,
    ): TrustedLinkEvidence {
        val capture = captureDao.findById(event.captureId)
        val amount = draft.amountVnd
        val date = draft.statedDate
        val candidates = if (amount != null && date != null) {
            draftDao.countWithAmountAndDate(amountVnd = amount, statedDate = date)
        } else {
            0
        }
        return TrustedLinkEvidence(
            eventId = event.id,
            captureId = event.captureId,
            captureRevision = capture?.revision ?: 0,
            eventRevision = event.revision,
            eventSourceRevision = event.sourceRevision,
            draftId = draft.id,
            draftReviewRevision = draft.reviewRevision,
            eventAmountVnd = event.amountVnd,
            eventAnchorDate = event.anchorDate,
            draftAmountVnd = draft.amountVnd,
            draftStatedDate = draft.statedDate,
            candidateDraftCount = candidates,
            duplicatePartnerCount = draftDao.countPossibleDuplicatesOf(draft.id),
            eventReadingComplete = event.normalizedText != null && event.unresolvedReason == null,
            draftAmountStated = draft.amountState == IntakeFieldState.PRESENT && amount != null,
            draftMomentStated = draft.momentState == IntakeFieldState.PRESENT && date != null,
        )
    }
}

private data class CandidateAssessment(
    val match: DraftCandidateMatch,
    val conflicting: Boolean,
    val sufficientEvidence: Boolean,
)

private fun assessDraftCandidate(
    event: NoteEventEntity,
    draft: PendingBankDraftEntity,
): CandidateAssessment {
    val effectiveAmount = effectiveCandidateAmount(draft)
    val amountState = when {
        event.amountVnd == null || effectiveAmount == null -> DraftCandidateEvidenceState.MISSING
        event.amountVnd == effectiveAmount -> DraftCandidateEvidenceState.MATCH
        else -> DraftCandidateEvidenceState.CONFLICT
    }

    val effectiveDate = effectiveCandidateDate(draft)
    val effectiveTime = effectiveCandidateTime(draft)
    val startDate = event.anchorDate
    val endDate = event.intervalEndDate
    val hasInterval = endDate != null
    val intervalUsesClock = hasInterval &&
        (event.anchorTime != null || event.intervalEndTime != null)
    val timeState = when {
        startDate == null || effectiveDate == null -> DraftCandidateEvidenceState.MISSING
        hasInterval && intervalUsesClock && effectiveTime == null -> DraftCandidateEvidenceState.MISSING
        hasInterval && intervalUsesClock -> {
            val start = "$startDate ${event.anchorTime ?: "00:00"}"
            val end = "${endDate!!} ${event.intervalEndTime ?: "23:59"}"
            val transaction = "$effectiveDate ${effectiveTime!!}"
            if (transaction >= start && transaction <= end) {
                DraftCandidateEvidenceState.MATCH
            } else {
                DraftCandidateEvidenceState.CONFLICT
            }
        }
        hasInterval -> {
            if (effectiveDate >= startDate && effectiveDate <= endDate!!) {
                DraftCandidateEvidenceState.MATCH
            } else {
                DraftCandidateEvidenceState.CONFLICT
            }
        }
        effectiveDate == startDate -> DraftCandidateEvidenceState.MATCH
        else -> DraftCandidateEvidenceState.CONFLICT
    }

    val eventMerchant = event.merchant?.let(::normalizedMerchantPhrase)?.takeIf { it.isNotEmpty() }
    val draftMerchant = effectiveCandidateDescription(draft)
        ?.let(::normalizedMerchantPhrase)
        ?.takeIf { it.isNotEmpty() }
    val merchantState = when {
        eventMerchant == null || draftMerchant == null -> DraftCandidateEvidenceState.MISSING
        draftMerchant.containsPhrase(eventMerchant) -> DraftCandidateEvidenceState.MATCH
        else -> DraftCandidateEvidenceState.CONFLICT
    }

    val pointDistance = if (
        !hasInterval && event.anchorTime != null && effectiveTime != null &&
        effectiveDate == startDate
    ) {
        kotlin.math.abs(minutesAfterMidnight(event.anchorTime) - minutesAfterMidnight(effectiveTime))
    } else {
        null
    }
    val conflicting = amountState == DraftCandidateEvidenceState.CONFLICT ||
        timeState == DraftCandidateEvidenceState.CONFLICT ||
        merchantState == DraftCandidateEvidenceState.CONFLICT
    val sufficientEvidence = event.normalizedText != null && event.unresolvedReason == null &&
        event.amountVnd != null && amountState == DraftCandidateEvidenceState.MATCH &&
        startDate != null && timeState == DraftCandidateEvidenceState.MATCH

    val rationale = buildList {
        add(
            when (amountState) {
                DraftCandidateEvidenceState.MATCH -> "The available amount evidence agrees exactly."
                DraftCandidateEvidenceState.MISSING -> "Amount evidence is missing on one or both sides."
                DraftCandidateEvidenceState.CONFLICT -> "The stated amounts conflict."
            },
        )
        add(
            when {
                timeState == DraftCandidateEvidenceState.MATCH && hasInterval ->
                    "The parsed bank transaction time is inside the event's stated interval."
                timeState == DraftCandidateEvidenceState.MATCH ->
                    "The parsed bank transaction date matches the event's stated day."
                timeState == DraftCandidateEvidenceState.MISSING ->
                    "Transaction-time evidence is missing on one or both sides."
                else -> "The parsed bank transaction time is outside the stated event interval."
            },
        )
        add(
            when (merchantState) {
                DraftCandidateEvidenceState.MATCH -> "The event's named merchant appears as a complete phrase in the effective bank description."
                DraftCandidateEvidenceState.MISSING -> "Merchant evidence is missing on one or both sides."
                DraftCandidateEvidenceState.CONFLICT -> "The stated merchant and effective bank description conflict."
            },
        )
        if (pointDistance != null) {
            add("Transaction-time proximity orders candidates only; it does not establish identity.")
        }
        add("Notification receipt time is not used.")
        add("Event account and transaction type are UNKNOWN because this event stores neither; bank name and draft direction are not substitutes.")
        add("Category choice and model certainty are not ranking inputs.")
    }
    return CandidateAssessment(
        match = DraftCandidateMatch(
            draft = draft,
            amount = amountState,
            transactionTime = timeState,
            merchant = merchantState,
            pointTimeDistanceMinutes = pointDistance,
            rankingRationale = rationale,
        ),
        conflicting = conflicting,
        sufficientEvidence = sufficientEvidence,
    )
}

internal fun effectiveCandidateAmount(draft: PendingBankDraftEntity): Long? {
    val correction = draft.reviewAmountText
    if (correction != null) {
        return try {
            VndMoney.parseManualAmount(correction)
        } catch (_: LedgerValidationException) {
            null
        }
    }
    return draft.amountVnd.takeIf { draft.amountState == IntakeFieldState.PRESENT }
}

internal fun effectiveCandidateDate(draft: PendingBankDraftEntity): String? {
    val correction = draft.reviewDateText
    val value = if (correction != null) {
        correction
    } else {
        draft.statedDate.takeIf { draft.momentState == IntakeFieldState.PRESENT } ?: return null
    }
    return value.takeIf { ManualDateTime.dateError(it) == null }
}

internal fun effectiveCandidateTime(draft: PendingBankDraftEntity): String? {
    val correction = draft.reviewTimeText
    val value = if (correction != null) {
        correction
    } else {
        draft.statedTime.takeIf { draft.momentState == IntakeFieldState.PRESENT } ?: return null
    }
    return value.takeIf { ManualDateTime.timeError(it) == null }
}

internal fun effectiveCandidateDescription(draft: PendingBankDraftEntity): String? {
    val value = if (draft.reviewMemoEdited) {
        draft.reviewMemo
    } else {
        draft.memo.takeIf { draft.memoState == IntakeFieldState.PRESENT }
    }
    return value?.trim()?.takeIf { it.isNotEmpty() }
}

private fun normalizedMerchantPhrase(value: String): String {
    val decomposed = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
    return buildString(decomposed.length) {
        var separatorPending = false
        for (character in decomposed) {
            val type = Character.getType(character)
            if (
                type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt()
            ) {
                continue
            }
            if (character.isLetterOrDigit()) {
                if (separatorPending && isNotEmpty()) append(' ')
                append(character)
                separatorPending = false
            } else {
                separatorPending = true
            }
        }
    }
}

private fun String.containsPhrase(phrase: String): Boolean =
    " $this ".contains(" $phrase ")

private fun minutesAfterMidnight(time: String): Int =
    (time[0] - '0') * 600 + (time[1] - '0') * 60 +
        (time[3] - '0') * 10 + (time[4] - '0')

private val MATCH_RANKING_RATIONALE = listOf(
    "Rank exact amount evidence, then event-time compatibility, then exact merchant evidence.",
    "For a point-time event, parsed transaction-time distance orders otherwise tied rows only; it cannot establish identity.",
    "Break remaining ties by draft creation time descending, then draft id descending.",
    "Event account and transaction type are UNKNOWN; neither bank name nor draft direction provides a comparison.",
)

/** The marker every app-computed link evidence starts with. */
internal const val LINK_EVIDENCE_MARKER = "AEGIS-LINK-EVIDENCE/1"

/** The reading is not a complete reading of the capture's current text any more. */
internal const val REASON_READING_UNAVAILABLE = "READING_UNAVAILABLE"

/** One side stated no amount, or stated one that could not be read. */
internal const val REASON_AMOUNT_NOT_STATED = "AMOUNT_NOT_STATED"

/** The two sides state different amounts. */
internal const val REASON_AMOUNT_MISMATCH = "AMOUNT_MISMATCH"

/** One side stated no day, or stated one that could not be read. */
internal const val REASON_MOMENT_NOT_STATED = "MOMENT_NOT_STATED"

/** The two sides state different days. */
internal const val REASON_DATE_MISMATCH = "DATE_MISMATCH"

/** Another stored draft states the same amount on the same day. */
internal const val REASON_COMPETING_CANDIDATE = "COMPETING_CANDIDATE"

/** The draft is one of an uncertain duplicate pair. */
internal const val REASON_UNCERTAIN_DUPLICATE = "UNCERTAIN_DUPLICATE"

/**
 * The one shared reason vocabulary of the app's own link evidence.
 *
 * The inspection read and the attachment policy both derive their verdict from the same
 * `TrustedLinkEvidence.reasons` facts and order, so the evidence a model inspects is exactly
 * the evidence the mutation applies.
 */

/** The association is the user's to resolve: more than one stored candidate could fit. */
internal fun isAmbiguousLinkReason(reason: String?): Boolean = reason == REASON_COMPETING_CANDIDATE ||
    reason == REASON_UNCERTAIN_DUPLICATE

/**
 * Whether a stored value is evidence this app computed.
 *
 * A value that was written by anything else — a reason a caller stated, a note, a bank
 * memo — is not evidence the app can act on, and an association that carries only such a
 * value is not one the app may attach.
 */
internal fun isTrustedLinkEvidence(value: String?): Boolean =
    value != null && value.startsWith("$LINK_EVIDENCE_MARKER|")

/**
 * The canonical form of one piece of link evidence.
 *
 * Every field is length-prefixed, so a value that contains the separator can never encode
 * like two values, and a missing field is distinct from an empty one.
 */
private fun encodeTrustedLinkEvidence(evidence: TrustedLinkEvidence): String {
    val fields = listOf(
        evidence.eventId.toString(),
        evidence.captureId.toString(),
        evidence.captureRevision.toString(),
        evidence.eventRevision.toString(),
        evidence.eventSourceRevision.toString(),
        evidence.draftId.toString(),
        evidence.draftReviewRevision.toString(),
        evidence.eventAmountVnd?.toString(),
        evidence.eventAnchorDate,
        evidence.draftAmountVnd?.toString(),
        evidence.draftStatedDate,
        evidence.candidateDraftCount.toString(),
        evidence.duplicatePartnerCount.toString(),
        evidence.eventReadingComplete.toString(),
        evidence.draftAmountStated.toString(),
        evidence.draftMomentStated.toString(),
        evidence.verdict.name,
        evidence.reasons.joinToString(","),
    )
    val encoded = fields.joinToString(separator = "|") { value ->
        if (value == null) "-" else "${value.length}:$value"
    }
    return "$LINK_EVIDENCE_MARKER|$encoded"
}
