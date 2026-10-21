package io.github.triet4p.aegisf.data

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
 * Computes the app's own evidence about one proposed association.
 *
 * The policy is deliberately narrow and never scores: it compares the stated amount and the
 * stated day, and it asks whether the app's own stored drafts hold exactly one candidate
 * and whether that draft is free of an uncertain duplicate. It never compares a merchant
 * with a bank memo, never weighs one field above another, and never turns a similarity into
 * an identity: an association the app cannot prove stays the user's decision.
 */
internal class LinkEvidencePolicy(
    private val database: LedgerDatabase,
) {
    private val draftDao = database.pendingBankDraftDao()
    private val captureDao = database.captureMessageDao()

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
