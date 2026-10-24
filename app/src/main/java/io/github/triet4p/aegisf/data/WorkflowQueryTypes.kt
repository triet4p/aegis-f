package io.github.triet4p.aegisf.data

/**
 * The bounds of one bounded read: how many rows a caller asks for, and where its page
 * starts.
 *
 * Every read in the shared query surface takes one of these, and none of them has an
 * "unbounded" form. A caller that wants everything says so with a concrete page size and
 * pages through the rest; the surface never hands out an open-ended result set, because an
 * open-ended read is what turns a query into a scan nobody bounded.
 *
 * The bounds are a programming contract, not a user-facing limit: a page size outside
 * [MIN_PAGE_SIZE]..[MAX_PAGE_SIZE] is a mistake in the calling code and is refused loudly
 * instead of being silently corrected.
 */
data class QueryPageRequest(
    val limit: Int,
    val offset: Int = 0,
) {
    init {
        require(limit in MIN_PAGE_SIZE..MAX_PAGE_SIZE) {
            "A bounded read asks for between $MIN_PAGE_SIZE and $MAX_PAGE_SIZE rows; asked for $limit."
        }
        require(offset >= 0) {
            "A bounded read starts at a non-negative offset; asked for $offset."
        }
    }

    companion object {
        /** The smallest page a bounded read may ask for. */
        const val MIN_PAGE_SIZE = 1

        /** The largest page a bounded read may ask for. */
        const val MAX_PAGE_SIZE = 500
    }
}

/**
 * The page size the app's list screens ask for.
 *
 * It is the largest page the surface allows, so a screen shows as many stored rows as one
 * read may return and says so plainly when more exist than the page holds.
 */
const val LIST_PAGE_SIZE: Int = QueryPageRequest.MAX_PAGE_SIZE

/**
 * One page of a bounded read.
 *
 * [items] holds at most the requested number of rows, in the read's own deterministic
 * order. [hasMore] says whether more rows match than this page returned, and [nextOffset]
 * is the offset the next page starts at — or null when this was the last page, so a caller
 * never has to guess whether it may ask again.
 *
 * The page reports no total count: the count would have to be read with a second query
 * over the same filters, and a second filter is a second thing that can drift from the
 * first. A caller that needs the size of a scope reads the aggregate, which counts exactly
 * what it sums.
 */
data class QueryPage<T>(
    val items: List<T>,
    val offset: Int,
    val limit: Int,
    /** Whether more rows match this read than this page returned. */
    val hasMore: Boolean,
) {
    /** The offset the next page starts at, or null when this page is the last one. */
    val nextOffset: Int? get() = if (hasMore) offset + items.size else null
}

/**
 * One note event that is still waiting to be recorded by hand.
 *
 * The row is a reading of a span of a saved note: the amount and the day it states, the
 * merchant it names, the reading itself, and the text of the note it was read from. A
 * note that has no reading, or that already holds an active link to a bank notification,
 * or that was already recorded as a ledger record, is not among them.
 */
data class UnrecordedNoteEvent(
    val eventId: Long,
    val captureId: Long,
    /** The revision of the reading; a record written from it names the revision it read. */
    val revision: Int,
    val amountVnd: Long?,
    val anchorDate: String?,
    val anchorTime: String?,
    val merchant: String?,
    val normalizedText: String?,
    val captureText: String?,
    /**
     * The exact stored source text of the span the reading was taken from.
     *
     * It is null when the reading no longer matches the capture's current text or its span
     * no longer fits, so review never presents text at an old offset as the user's words.
     */
    val excerpt: String? = null,
)

/**
 * An explicit range of instants, and the zone its calendar days are counted in.
 *
 * The range is half-open: [fromEpochMillis] is included and [throughExclusiveEpochMillis]
 * is not, so a record exactly at the exclusive end belongs to the next range and two
 * adjacent ranges never count the same record twice. Either end may be null, which leaves
 * that side open — an open end is a real "no bound stated", not a missing value.
 *
 * [zoneId] is the zone the range's calendar days are counted in. It is part of the range
 * and not of the caller's environment, because the same instant belongs to different days
 * in different zones: a record at 23:59 and one at 00:01 are the same day in UTC and two
 * different days in Asia/Ho_Chi_Minh. The zone is stated here so the day a record is
 * counted in never depends on where the code that counted it happened to run.
 */
data class LedgerDateRange(
    val fromEpochMillis: Long?,
    val throughExclusiveEpochMillis: Long?,
    val zoneId: String,
) {
    init {
        require(zoneId.isNotBlank()) {
            "A date range names the zone its calendar days are counted in."
        }
    }

    /** Whether both ends of the range are stated. */
    val isClosed: Boolean
        get() = fromEpochMillis != null && throughExclusiveEpochMillis != null

    companion object {
        /**
         * A closed range: `[fromEpochMillis, throughExclusiveEpochMillis)`.
         *
         * The end must come after the start; a range that ends where it starts is a
         * programming mistake and is refused rather than counted as an empty range.
         */
        fun closed(
            fromEpochMillis: Long,
            throughExclusiveEpochMillis: Long,
            zoneId: String,
        ): LedgerDateRange {
            require(fromEpochMillis < throughExclusiveEpochMillis) {
                "A closed date range must start before it ends."
            }
            return LedgerDateRange(fromEpochMillis, throughExclusiveEpochMillis, zoneId)
        }
    }
}

/**
 * The category dimension of a ledger query.
 *
 * The three choices are distinct and never conflated: [All] matches every record,
 * [Uncategorized] matches only the records filed under no category, and [Specific] matches
 * the records filed under one stable category id. A category id is never a name: renaming
 * a category does not lose a selection, and an archived category stays selectable through
 * [Specific] so the records filed under it remain discoverable.
 */
sealed interface LedgerCategoryFilter : java.io.Serializable {
    /** Every record, whatever category it is filed under. */
    data object All : LedgerCategoryFilter

    /** Only the records filed under no category. */
    data object Uncategorized : LedgerCategoryFilter

    /** Only the records filed under one stable category id, archived or not. */
    data class Specific(val categoryId: Long) : LedgerCategoryFilter
}

/**
 * Which stored categories a category query returns.
 *
 * [ACTIVE] is the pool a new record may be filed under, [ARCHIVED] is the history that is
 * kept for the records already filed under it, and [ALL] is both. The three are reads of
 * the same table, never of a second table, so a category cannot be in two states at once.
 */
enum class CategoryState {
    ACTIVE,
    ARCHIVED,
    ALL,
}

/**
 * Which stored sources a source query returns.
 *
 * [ACTIVE] is the source text that is still stored, [DELETED] is the text-free tombstone
 * an explicit deletion left behind, and [ALL] is both. A tombstone keeps the identity and
 * the source metadata of a deleted capture and never keeps its text, so a query over
 * deleted sources cannot return text the user deleted.
 */
enum class SourceState {
    ACTIVE,
    DELETED,
    ALL,
}

/**
 * A bounded read of the stored categories.
 *
 * [state] selects which categories are in scope, and [bounds] says how many of them this
 * read returns and where its page starts. There is no unbounded form: a caller that wants
 * every category asks for a page and pages through the rest.
 */
data class CategoryQuery(
    val state: CategoryState = CategoryState.ALL,
    val bounds: QueryPageRequest,
)

/**
 * How many stored categories there are, and how many of them are archived.
 *
 * The counts describe the whole catalog and not one page of it, so a screen that pages
 * through the catalog can still say how many categories exist and how many of them are
 * history kept for the records already filed under them.
 */
data class CategoryCatalogCounts(
    /** Every stored category, archived ones included. */
    val total: Int,
    /** The stored categories that are archived. */
    val archived: Int,
) {
    /** The stored categories a new record may be filed under. */
    val active: Int get() = total - archived
}

/**
 * A bounded read of the stored sources.
 *
 * The kind and the source kind narrow what is read; [state] selects the stored text or
 * the deleted tombstones; [capturedIn] narrows the read to the sources captured inside an
 * explicit range. A source whose capture instant is unknown — a legacy row that never
 * recorded one — is in no date range and is returned only by an unranged read.
 */
data class SourceQuery(
    val kind: CaptureKind? = null,
    val sourceKind: CaptureSourceKind? = null,
    val state: SourceState = SourceState.ACTIVE,
    val capturedIn: LedgerDateRange? = null,
    val bounds: QueryPageRequest,
)

/**
 * A bounded read of the bank drafts that are still candidates for review.
 *
 * [reviewStatus] selects the drafts in one lifecycle state, and [onlyPossibleDuplicates]
 * narrows the read to the drafts that share their evidence with an earlier one. A draft is
 * evidence, never a ledger record: no draft of any state enters a ledger total.
 */
data class DraftCandidateQuery(
    val reviewStatus: IntakeReviewStatus? = null,
    val onlyPossibleDuplicates: Boolean = false,
    val bounds: QueryPageRequest,
)

/** Which order a review-queue read returns the pending drafts in. */
enum class ReviewQueueOrder {
    /**
     * The uncertainty-first order: unresolved link and data conflicts first, then drafts whose
     * classification is missing, stale, unknown or not comparable, then comparable fresh
     * predictions by ascending sharpness. This is the review order the queue policy freezes.
     */
    UNCERTAINTY_FIRST,

    /**
     * The chronological override: every pending draft by creation instant, then id.
     *
     * It is the order the work arrived in, so a high-confidence draft that sorts late in the
     * review order is still reachable here without walking the review order first. It is an
     * order of the same rows, never a different filter: no draft is missing from either.
     */
    CHRONOLOGICAL,
}

/** The group a pending draft waits in, in the order the review queue sorts them. */
enum class ReviewQueueGroup {
    /**
     * 0 — unresolved linking or bank-data conflicts: a possible duplicate, a link the user has
     * not decided, or bank data that needs attention. This is the user's decision to make, so
     * it comes before any classification.
     */
    NEEDS_DECISION,

    /**
     * 1 — the classification is missing, stale, unknown/no-fit, a direct answer that is not a
     * model score, or a category the user already chose. Stored confidence never orders here.
     */
    NOT_COMPARABLE,

    /**
     * 2 — a fresh prediction from a stored distribution, comparable only with the predictions
     * of its own comparability group, ordered by ascending sharpness.
     */
    SUGGESTION,
}

/** Why a pending draft waits in [ReviewQueueGroup.NEEDS_DECISION]. */
enum class ReviewQueueConflict {
    /** The draft is one of an uncertain duplicate family; identity is the user's decision. */
    POSSIBLE_DUPLICATE,

    /** A link to this draft is still proposed; the association must be accepted or refused. */
    PROPOSED_LINK,

    /** The bank's amount or moment is unresolved, or its format is unsupported. */
    UNRESOLVED_BANK_DATA,
}

/**
 * Why a pending draft waits in [ReviewQueueGroup.NOT_COMPARABLE], in the order §7.1 lists.
 *
 * Only one subkind is reported per draft, and the order of the checks is fixed: a missing
 * prediction, then a category the user already chose, then a stale prediction, then an
 * unknown/no-fit outcome, then the direct answers that are not model scores.
 */
enum class ReviewQueueSubkind {
    /** No stored prediction: never classified, or nothing was recorded. */
    MISSING,

    /** The stored prediction no longer applies; its confidence orders nothing. */
    STALE,

    /** The stored prediction chose the no-fit option; its sharpness promotes nothing. */
    UNKNOWN,

    /** A saved rule, an explicit correction, or a category name answered; there is no score. */
    DIRECT,

    /** The user already chose the category; the draft waits for confirm or dismiss. */
    USER_RESOLVED,
}

/**
 * A bounded read of the review queue.
 *
 * [order] is the frozen uncertainty-first order or its always-available chronological
 * override. [excludingDraftId] leaves one draft out so the next-work action can advance to
 * the next draft instead of reopening the open one. [bounds] bounds the read like every other
 * bounded read: there is no unbounded form.
 */
data class ReviewQueueQuery(
    val order: ReviewQueueOrder = ReviewQueueOrder.UNCERTAINTY_FIRST,
    val excludingDraftId: Long? = null,
    val bounds: QueryPageRequest,
)

/**
 * The lifecycle state of one draft's durable classification work, as the review surface sees
 * it.
 *
 * [QUEUED] and [RUNNING] are the truthful "waiting" and "busy" states of the stored job;
 * [RESULT] means a prediction was stored, [CANCELLED] means nothing further is asked until a
 * relevant change or an explicit retry, and [FAILED] records a check that ended without a
 * prediction. The state is a fact about the app's own work, never a claim about a category.
 */
enum class ClassificationWorkState {
    QUEUED,
    RUNNING,
    RESULT,
    CANCELLED,
    FAILED,
}

/**
 * The privacy-safe reason a classification job ended as it did.
 *
 * A reason says which rule ended the job and never what the draft or the model said. It is
 * the stored code read back as a named value, so a surface can explain a cancelled or failed
 * job without displaying an internal string.
 */
enum class ClassificationWorkStatusReason {
    DESCRIPTION_MISSING,
    DRAFT_RESOLVED,
    DRAFT_REVIEWED,
    DRAFT_MISSING,
    DENIED,
    SUPERSEDED,
    CONTEXT_CHANGED,
    MODEL_OR_INFERENCE_FAILED,
    NOT_RECORDED,
    USER_CANCELLED,
}

/**
 * The stored classification work of one draft: its lifecycle state, why it ended, and when
 * that state was last written.
 *
 * This is the durable job's own truth, so review can show that a suggestion is queued, being
 * checked, failed, or cancelled instead of only showing that no prediction exists. It carries
 * no draft text and no model output.
 */
data class ClassificationWorkStatus(
    val draftId: Long,
    val state: ClassificationWorkState,
    /** Why the job is cancelled or failed, or null while it waits, runs, or has a result. */
    val reason: ClassificationWorkStatusReason?,
    val updatedAtEpochMillis: Long,
)

/**
 * The measured relative-confidence statement of one fresh model suggestion.
 *
 * Every number is read from the same stored distribution: the top category's own share, the
 * no-fit share, and the one sharpness value. [evaluatedCount] is how many categories were
 * evaluated (the unknown slot is separate) and [activeCategoryCount] is the active catalog at
 * read time, so a surface can state the scope instead of implying a full-catalog or accuracy
 * claim. It exists only under the S7.1 L1–L6 policy; a stale, unresolved, conflicting, direct,
 * unknown, or user-resolved draft never carries one.
 */
data class RelativeConfidence(
    val topCategoryId: Long,
    val topShare: Double,
    val unknownShare: Double,
    val sharpness: Double,
    val evaluatedCount: Int,
    val activeCategoryCount: Int,
)

/**
 * One pending draft in the review queue, with the group that placed it there.
 *
 * The entry carries the stored facts the ordering used, so the surface can show the group and
 * the reason instead of only a position: [conflicts] names the unresolved-link/data reasons,
 * [subkind] names the not-comparable reason, and [confidence] with [candidateIds] states the
 * measured scope of a fresh prediction. The evaluated shares, no-fit share, prediction time,
 * model revision, candidate-set version, and linked readings are the stored provenance the
 * surface shows with the suggestion; [relativeConfidence] is present only when the frozen
 * policy lets a dominant category be called relatively confident, and it always carries its
 * scope. Nothing here is a new score: every value is the one the app already stored, and no
 * field can confirm, dismiss, or file anything by itself.
 */
data class ReviewQueueEntry(
    val draft: PendingBankDraftEntity,
    val group: ReviewQueueGroup,
    val conflicts: List<ReviewQueueConflict>,
    /** The not-comparable subkind, or null outside [ReviewQueueGroup.NOT_COMPARABLE]. */
    val subkind: ReviewQueueSubkind?,
    /** Why a stored prediction stopped applying, or null while it applies. */
    val staleReason: PredictionStaleReason?,
    /**
     * The comparability group of a fresh prediction, or null outside
     * [ReviewQueueGroup.SUGGESTION]. Two predictions of different groups are never compared.
     */
    val comparabilityKey: String?,
    /** The stored sharpness that orders a fresh prediction, or null outside that group. */
    val confidence: Double?,
    /** The evaluated category ids of the stored prediction, empty when none was evaluated. */
    val candidateIds: List<Long>,
    /** The category the stored decision pointed at, or null when it pointed at none. */
    val suggestedCategoryId: Long? = null,
    /** The evaluated shares, in the same order as [candidateIds]. */
    val probabilities: List<Double> = emptyList(),
    /** The calibrated no-fit share of the same distribution, or null when none was produced. */
    val unknownShare: Double? = null,
    /** When the stored prediction was recorded, or null when none is stored. */
    val predictionTimeEpochMillis: Long? = null,
    /** The model revision that produced the stored prediction, or null. */
    val modelRevision: String? = null,
    /** The candidate-set construction that produced the stored prediction, or null. */
    val candidateSetVersion: String? = null,
    /** The stored linked readings the prediction's input was composed from. */
    val eventIds: List<Long> = emptyList(),
    /** The active category count at read time, stated with a suggestion's scope. */
    val activeCategoryCount: Int = 0,
    /** The policy-gated relative statement, or null when no label is allowed. */
    val relativeConfidence: RelativeConfidence? = null,
)

/**
 * A bounded read of the stored ledger records.
 *
 * Every dimension is explicit and optional: [type] narrows the read to one kind of record,
 * [category] to one category dimension, [confirmation] to one lifecycle state, and
 * [occurredIn] to one explicit range. A null dimension is an open dimension, and an open
 * dimension matches everything.
 */
data class LedgerRecordQuery(
    val type: TransactionType? = null,
    val category: LedgerCategoryFilter = LedgerCategoryFilter.All,
    val confirmation: ConfirmationStatus? = null,
    val occurredIn: LedgerDateRange? = null,
    val bounds: QueryPageRequest,
)

/**
 * A bounded read of the stored context links — the results of the link decisions.
 *
 * The link state is the stored [ContextLinkState] itself: proposed, accepted, or refused.
 * A refused link is never removed and never turned back into a proposal, so a read over
 * the refused state is the record of the user's decision rather than a leftover.
 */
data class LinkOperationQuery(
    val state: ContextLinkState? = null,
    val eventId: Long? = null,
    val draftId: Long? = null,
    val bounds: QueryPageRequest,
)

/**
 * A bounded read of the stored bank-draft review states — the results of the review
 * decisions.
 *
 * The state is the stored [IntakeReviewStatus] itself: pending, confirmed, or dismissed.
 */
data class DraftOperationQuery(
    val reviewStatus: IntakeReviewStatus? = null,
    val bounds: QueryPageRequest,
)

/**
 * A bounded read of the stored capture revisions — the results of the save and revision
 * decisions.
 *
 * The state is the stored truth about the row: whether its text is still stored, and which
 * revision of it is current. A deleted capture is a tombstone, so a read over the deleted
 * state returns the identity and the metadata and never the deleted text.
 */
data class CaptureOperationQuery(
    val state: SourceState = SourceState.ACTIVE,
    val bounds: QueryPageRequest,
)

/**
 * A bounded read of the stored event interpretations — the results of the interpretation
 * decisions.
 *
 * The state is the stored [NoteEventStatus] itself: interpreted, unresolved, or unmatched.
 */
data class EventOperationQuery(
    val captureId: Long? = null,
    val status: NoteEventStatus? = null,
    val bounds: QueryPageRequest,
)

/**
 * A bounded page of the editable readings attached to one saved capture.
 *
 * Unlike [EventOperationQuery], this view includes the source span and interpretation fields
 * needed to let the user review and revise the actual stored reading.
 */
data class CaptureEventQuery(
    val captureId: Long,
    val bounds: QueryPageRequest,
)

/**
 * A bounded read of the stored classification results — the results of the classification
 * requests.
 *
 * [staleOnly] narrows the read to the predictions that no longer apply.
 */
data class ClassificationOperationQuery(
    val staleOnly: Boolean = false,
    val bounds: QueryPageRequest,
)

/**
 * A bounded read of the recorded preparation commands.
 *
 * [command] narrows the read to one command family and is open when it is null. The rows
 * are the durable record of the requests that were made, so a reader can see which
 * requests were already answered under which key.
 */
data class WorkflowOperationQuery(
    val command: String? = null,
    val bounds: QueryPageRequest,
)

/**
 * The stored result of one context-link decision.
 *
 * Every field is read from the stored link: the pair it joins, the state it is in, the
 * revision it was written at, and the evidence and override recorded with the decision.
 */
data class LinkOperation(
    val linkId: Long,
    val eventId: Long,
    val draftId: Long,
    val state: ContextLinkState,
    val revision: Int,
    val evidence: String?,
    val userOverride: String?,
    val createdAtEpochMillis: Long,
)

/**
 * One stored context link of one bank draft, with the exact note excerpt it joins.
 *
 * The link row alone names the pair; review also needs the user's own words, so this view
 * carries the reading and the exact span of the stored source text it was taken from. It is
 * provenance evidence and nothing else: no field here is a ledger value, and reading it can
 * never change the bank draft, its amount, or a ledger figure.
 *
 * [sourceCurrent] is the app's own fact about currency. It is false when the reading no
 * longer matches the capture's current revision, when the capture's text is gone, or when
 * the stored span no longer fits the text. A stale reading is still reported — a refused
 * link outlives the text it was made against — so review can say plainly that the excerpt
 * is no longer current instead of presenting an old copy of edited text as the source.
 */
data class DraftContextLink(
    val linkId: Long,
    val eventId: Long,
    val draftId: Long,
    val state: ContextLinkState,
    val revision: Int,
    val userOverride: String?,
    /** The capture the reading was taken from, or null when the event row is gone. */
    val captureId: Long?,
    /** The capture's current revision, or null when the event row is gone. */
    val captureRevision: Int?,
    /** The reading's revision, or null when the event row is gone. */
    val eventRevision: Int?,
    /** The capture revision the reading was taken from, or null when the event row is gone. */
    val eventSourceRevision: Int?,
    /** The exact stored source text of the span, or null when it is no longer readable. */
    val excerpt: String?,
    /** The reading's normalized text, or null when the source no longer supports it. */
    val readingText: String?,
    val amountVnd: Long?,
    val anchorDate: String?,
    val merchant: String?,
    /** Whether the reading still matches the capture's current text. */
    val sourceCurrent: Boolean,
)

/**
 * The stored result of one bank-draft review decision.
 *
 * [reviewRevision] is the stored compare-and-set revision the review was written at, and
 * [confirmedTransactionId] is the ledger record the confirmation linked, or null when the
 * draft was dismissed or is still pending.
 */
data class DraftOperation(
    val draftId: Long,
    val reviewStatus: IntakeReviewStatus,
    val reviewRevision: Int,
    val confirmedTransactionId: Long?,
    val possibleDuplicateOfDraftId: Long?,
    val createdAtEpochMillis: Long,
)

/**
 * The stored result of one capture save or revision.
 *
 * [revision] is the stored compare-and-set revision of the capture's current text, and
 * [tombstone] says whether the text is still stored at all.
 */
data class CaptureOperation(
    val captureId: Long,
    val captureKind: CaptureKind,
    val sourceKind: CaptureSourceKind,
    val revision: Int,
    val tombstone: Boolean,
    val capturedAtEpochMillis: Long?,
    val captureTimezone: String?,
)

/**
 * A bounded summary of the stored interpretations and link decisions for one capture.
 *
 * These counts describe current source context only; they do not imply that a model ran,
 * that a proposed link was accepted, or that any financial record was created.
 */
data class CaptureContextSummary(
    val eventCount: Int = 0,
    val interpretedEventCount: Int = 0,
    val unresolvedEventCount: Int = 0,
    val unmatchedEventCount: Int = 0,
    val resolvedEventCount: Int = 0,
    val proposedLinkCount: Int = 0,
    val acceptedLinkCount: Int = 0,
    val rejectedLinkCount: Int = 0,
)

/** Grouped event counts returned by the fixed capture-context query. */
data class CaptureEventStatusCount(
    val captureId: Long,
    val status: NoteEventStatus,
    val eventCount: Int,
    val resolvedEventCount: Int,
)

/** Grouped link counts returned by the fixed capture-context query. */
data class CaptureLinkStatusCount(
    val captureId: Long,
    val state: ContextLinkState,
    val linkCount: Int,
)


/**
 * The stored result of one event interpretation.
 *
 * [revision] is the stored compare-and-set revision of the reading and [sourceRevision]
 * is the capture revision the reading was taken from, so a reader can tell a current
 * reading from one the source has moved on from.
 */
data class EventOperation(
    val eventId: Long,
    val captureId: Long,
    val status: NoteEventStatus,
    val revision: Int,
    val sourceRevision: Int,
    val unresolvedReason: String?,
)

/**
 * The stored result of one classification request.
 *
 * The prediction is review-only evidence: it names where the decision came from, the
 * category it pointed at, the categories the model evaluated, and the one sharpness value
 * of that distribution. [staleReason] says why it no longer applies, or null while it does.
 */
data class ClassificationOperation(
    val draftId: Long,
    val decisionSource: PredictionSource,
    val suggestedCategoryId: Long?,
    val candidateIds: List<Long>,
    val confidence: Double?,
    val predictionTimeEpochMillis: Long,
    val staleReason: PredictionStaleReason?,
)

/**
 * The stored record of one preparation command.
 *
 * The record names the request's own identity — the idempotency key and the scope that
 * made it — the command that was run, the outcome it reached, and the change it made.
 * The payload of the request is not stored: only its digest is, so the record never holds
 * the text the request was about.
 */
data class WorkflowOperation(
    val idempotencyKey: String,
    val scopeId: String,
    val command: String,
    val outcome: String,
    val change: WorkflowChange?,
    val createdAtEpochMillis: Long,
)
