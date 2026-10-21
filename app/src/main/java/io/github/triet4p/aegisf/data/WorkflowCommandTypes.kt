package io.github.triet4p.aegisf.data

import io.github.triet4p.aegisf.data.laya.LayaContextDecision

/**
 * Who is asking the application to change something.
 *
 * The scope is the *authority* of the caller, and it is supplied by trusted application
 * code — the screen that owns the user's action, or the session an application adapter
 * established for its own caller. It is never read from a model's arguments, from a
 * request body, or from anything the stored data says about itself: a note, a bank memo,
 * and a stored category name are data, not authority.
 *
 * The two scopes are:
 *
 * - [User] — the user's own screens acting on the user's own data. Every preparation
 *   command is available to it.
 * - [AgentSession] — a bounded session of a later agent adapter. It may act only on the
 *   captures and the drafts its own session was granted, and only on the preparation
 *   commands the policy allows. A target outside that scope is refused, not attempted.
 */
sealed interface WorkflowCallerScope {
    /** The stable identity of this scope; stored with every operation it performs. */
    val scopeId: String

    /** The user's own screens. */
    data object User : WorkflowCallerScope {
        override val scopeId: String = "user"
    }

    /**
     * A bounded session of a later agent adapter.
     *
     * The granted ids are the session's own scope: the captures it may read and prepare,
     * and the drafts it may propose, attach, and classify. An empty set grants nothing.
     */
    data class AgentSession(
        override val scopeId: String,
        val captureIds: Set<Long>,
        val draftIds: Set<Long>,
    ) : WorkflowCallerScope
}

/** Whether this scope may act on the given capture. */
fun WorkflowCallerScope.mayTouchCapture(captureId: Long): Boolean = when (this) {
    is WorkflowCallerScope.User -> true
    is WorkflowCallerScope.AgentSession -> captureId in captureIds
}

/** Whether this scope may act on the given bank draft. */
fun WorkflowCallerScope.mayTouchDraft(draftId: Long): Boolean = when (this) {
    is WorkflowCallerScope.User -> true
    is WorkflowCallerScope.AgentSession -> draftId in draftIds
}

/**
 * Why a command was refused.
 *
 * Every reason names a rule that holds regardless of who asked: the caller's own scope, a
 * frozen source rule, the user's standing decision, or the link policy. A refusal never
 * writes anything and never leaves a trace of the refused request behind.
 */
enum class WorkflowDenialReason {
    /** The target is outside the caller's granted scope. */
    CALLER_SCOPE,

    /** The capture's text is not user-authored text and cannot be rewritten. */
    IMMUTABLE_SOURCE,

    /** The capture's text was deleted; there is nothing left to change. */
    ALREADY_DELETED,

    /**
     * The event holds no current reading, so it can carry neither a link nor a record.
     *
     * The source text was edited or deleted, so the reading the caller named is gone: the
     * occurrence it described cannot be linked to a bank notification, and it cannot be
     * recorded by hand against that reading either.
     */
    EVENT_UNAVAILABLE,

    /** The pair was refused before and stays refused; a refusal is never recreated. */
    LINK_ALREADY_REJECTED,

    /** The decision is the user's own and cannot be taken by another caller. */
    USER_DECISION_REQUIRED,

    /** The link is not a unique, evidence-supported attachment, so the user must decide. */
    ATTACH_POLICY,

    /** The event already holds an active draft, so a second one was refused. */
    MULTIPLE_ACTIVE_DRAFTS,

    /**
     * The chosen category is gone or archived, so the field cannot be written.
     *
     * A category that was archived while a form was open is not assignable any more, and
     * a category that was deleted does not exist at all; neither is written silently.
     */
    CATEGORY_UNAVAILABLE,

    /**
     * The note event was already recorded as a ledger record, so it is spent.
     *
     * The user recorded the occurrence by hand, and the record that resolved it stands.
     * A bank notification that arrives later cannot consume the same occurrence again,
     * so the link is refused and the event stays recorded.
     */
    EVENT_ALREADY_RECORDED,
}

/**
 * What one preparation command did.
 *
 * The vocabulary is fixed: a command either applied a change, changed nothing, asked the
 * caller for something it did not state, found a conflict, found nothing, was refused, was
 * cancelled, or failed. There is no other answer, and no answer that reports a change the
 * command did not make.
 */
sealed interface WorkflowCommandOutcome {
    /** The command changed the stored state; [change] says what changed. */
    data class Applied(val change: WorkflowChange) : WorkflowCommandOutcome

    /**
     * The command changed nothing.
     *
     * The request was already satisfied — a repeated request under the same idempotency
     * key, or a state that already was what the request asked for. [change] carries the
     * state that was found, when there is one, so a caller that repeats a request still
     * learns which row it is about.
     */
    data class NoChange(
        val change: WorkflowChange? = null,
        val detail: String? = null,
    ) : WorkflowCommandOutcome

    /** The request is missing something only the caller can state. */
    data class NeedsClarification(val field: String, val reason: String) : WorkflowCommandOutcome

    /** The request was made against a state that has moved on. */
    data class Conflict(val reason: String) : WorkflowCommandOutcome

    /** The named row does not exist. */
    data object NotFound : WorkflowCommandOutcome

    /** The request was refused by a rule; [reason] names it. */
    data class Denied(val reason: WorkflowDenialReason) : WorkflowCommandOutcome

    /** The request was cancelled before it was applied. */
    data object Cancelled : WorkflowCommandOutcome

    /** The request could not be carried out. */
    data class Failed(val reason: String) : WorkflowCommandOutcome
}

/**
 * The change one preparation command made.
 *
 * A change names the row it touched and the revision it left behind. It never carries the
 * text of a capture, the reading of an event, or the composed input of a model request:
 * what the command changed is business data the user already owns, and what it decided is
 * a summary, not the material it was made from.
 */
sealed interface WorkflowChange {
    /** A new capture was stored. */
    data class CaptureSaved(val captureId: Long, val revision: Int) : WorkflowChange

    /** A capture's text was replaced. */
    data class CaptureRevised(val captureId: Long, val revision: Int) : WorkflowChange

    /** A reading was stored for a span that had none. */
    data class EventStored(val eventId: Long, val revision: Int) : WorkflowChange

    /** A stored reading was replaced. */
    data class EventRevised(val eventId: Long, val revision: Int) : WorkflowChange

    /** A link was proposed, accepted, or refused. */
    data class LinkDecided(
        val linkId: Long,
        val state: ContextLinkState,
        val revision: Int,
    ) : WorkflowChange

    /** An active link was removed; the event's own context is restored. */
    data class LinkUnlinked(val linkId: Long) : WorkflowChange

    /**
     * A classification was produced for a draft.
     *
     * [decision] is the review-only decision this call produced, and it is null when the
     * outcome was replayed from the durable operation record: the decision itself is not
     * stored, only the summary of it.
     */
    data class Classified(
        val draftId: Long,
        val snapshotId: Long,
        val source: PredictionSource,
        val suggestedCategoryId: Long?,
        val confidence: Double?,
        val decision: LayaContextDecision? = null,
    ) : WorkflowChange

    /**
     * A ledger record was stored by the user's own hand.
     *
     * [sourceEventId] names the note event the record was written from, when the user
     * recorded an unmatched note by hand: that event is resolved against this record.
     */
    data class TransactionSaved(
        val transactionId: Long,
        val revision: Int,
        val sourceEventId: Long? = null,
    ) : WorkflowChange

    /** The user-editable fields of a stored record were replaced. */
    data class TransactionCorrected(
        val transactionId: Long,
        val revision: Int,
    ) : WorkflowChange

    /** A stored record was removed. */
    data class TransactionDeleted(val transactionId: Long) : WorkflowChange

    /** The user's explicit edits were stored on a still-pending draft. */
    data class DraftReviewSaved(
        val draftId: Long,
        val revision: Int,
    ) : WorkflowChange

    /** A pending draft became a confirmed ledger record. */
    data class DraftConfirmed(
        val draftId: Long,
        val transactionId: Long,
    ) : WorkflowChange

    /** A pending draft was dismissed and stays out of the ledger. */
    data class DraftDismissed(val draftId: Long) : WorkflowChange

    /** A category was added to the catalog. */
    data class CategoryAdded(val categoryId: Long, val name: String) : WorkflowChange

    /** A category was renamed; its id and the records filed under it are untouched. */
    data class CategoryRenamed(val categoryId: Long, val name: String) : WorkflowChange

    /** A category was archived and left the pool of assignable categories. */
    data class CategoryArchived(val categoryId: Long) : WorkflowChange

    /**
     * A category was removed for good.
     *
     * The counts say what the removal changed: the records that became uncategorized, the
     * saved rules that went with the category, and the still-pending drafts whose pre-set
     * category was cleared. No ledger record is ever removed by it.
     */
    data class CategoryDeleted(
        val categoryId: Long,
        val impact: CategoryDeletionImpact,
    ) : WorkflowChange

    /** A saved exact-description rule was stored for an active category. */
    data class CategoryRuleRemembered(
        val patternKey: String,
        val categoryId: Long,
    ) : WorkflowChange

    /** A saved exact-description rule was removed. */
    data class CategoryRuleRemoved(val patternKey: String) : WorkflowChange
}

/** The kind of user-authored text a save command stores. */
enum class UserCaptureKind {
    /** An intentionally saved quick note, independent of any bank notification. */
    QUICK_CAPTURE,

    /** An intentionally saved annotation, separate from the evidence it describes. */
    MANUAL_ANNOTATION,
}

/**
 * One request to store a new user-authored capture.
 *
 * The idempotency key is the request's own identity: the same key under the same caller
 * and the same text stores one capture, whatever the number of attempts or the number of
 * processes they happen in.
 */
data class SaveCaptureRequest(
    val idempotencyKey: String,
    val bodyText: String,
    val captureKind: UserCaptureKind = UserCaptureKind.QUICK_CAPTURE,
)

/**
 * One request to replace the text of a capture.
 *
 * [expectedRevision] is the revision the caller read: when the capture has moved on, the
 * request is refused as a conflict instead of overwriting what the caller never saw.
 */
data class ReviseCaptureRequest(
    val idempotencyKey: String,
    val captureId: Long,
    val expectedRevision: Int,
    val bodyText: String,
)

/**
 * One request to store or revise the reading of a span of a capture.
 *
 * [expectedCaptureRevision] is the capture revision the reading was taken from: a reading
 * of text the caller never saw is refused rather than stored.
 */
data class UpdateEventRequest(
    val idempotencyKey: String,
    val captureId: Long,
    val expectedCaptureRevision: Int,
    val interpretation: NoteEventInterpretation,
    val extractionVersion: String,
)

/**
 * One request to propose that an event describes a draft.
 *
 * The request carries no reason and no evidence: the app records its *own* evidence about
 * the association — the facts it can read from the stored reading and the stored draft —
 * and binds it to the exact source, event, draft, and revisions it was read from. A
 * caller, a model, and a note can therefore never assert the evidence that would let a
 * link be attached; they can only propose the pair, and the app decides what that pair
 * rests on.
 */
data class ProposeLinkRequest(
    val idempotencyKey: String,
    val eventId: Long,
    val draftId: Long,
)

/** One request to attach an event to the draft it was proposed for. */
data class AttachLinkRequest(
    val idempotencyKey: String,
    val eventId: Long,
    val draftId: Long,
    val expectedRevision: Int,
    val userOverride: String? = null,
)

/** One request to refuse the association between an event and a draft. */
data class RejectLinkRequest(
    val idempotencyKey: String,
    val eventId: Long,
    val draftId: Long,
    val expectedRevision: Int,
    val userOverride: String? = null,
)

/** One request to remove an active link and restore the event's own context. */
data class UnlinkLinkRequest(
    val idempotencyKey: String,
    val eventId: Long,
    val draftId: Long,
    val expectedRevision: Int,
)

/**
 * One request to classify a bank draft.
 *
 * The description is the effective suggestion input: the permitted bank description the
 * review works with, or the user's own explicit note — never the two concatenated.
 *
 * [expectedReviewRevision] is the draft's own review revision when the caller wants the
 * request pinned to the state it read; null leaves the draft's review state open, and the
 * dependency check of the stored prediction still applies.
 */
data class ClassificationRequest(
    val idempotencyKey: String,
    val draftId: Long,
    val description: String,
    val expectedReviewRevision: Int? = null,
)

/**
 * One request to add a category to the catalog.
 *
 * The category is the user's own decision, so only the user may make this request. The
 * idempotency key names the one action: the same key under the same caller and the same
 * name adds one category, whatever the number of attempts or processes.
 */
data class AddCategoryRequest(
    val idempotencyKey: String,
    val name: String,
)

/**
 * One request to rename a category.
 *
 * The id and the records filed under the category are untouched by a rename, so the
 * history stays attributed to the same category under its new name.
 */
data class RenameCategoryRequest(
    val idempotencyKey: String,
    val categoryId: Long,
    val name: String,
)

/**
 * One request to archive a category.
 *
 * Archiving takes the category out of the pool of assignable categories while the records
 * already filed under it keep pointing at it.
 */
data class ArchiveCategoryRequest(
    val idempotencyKey: String,
    val categoryId: Long,
)

/**
 * One request to remove a category for good.
 *
 * The removal keeps every stored record — amount, type, moment, description, and totals
 * intact — and only clears the links to the category. The counts the change reports are
 * the counts the removal actually applied.
 */
data class DeleteCategoryRequest(
    val idempotencyKey: String,
    val categoryId: Long,
)

/**
 * One request to save an explicit rule for one exact description.
 *
 * The rule is the user's own standing decision, so only the user may make this request,
 * and it names an active category: a rule never points at a category that cannot be
 * chosen.
 */
data class RememberCategoryRuleRequest(
    val idempotencyKey: String,
    val description: String,
    val categoryId: Long,
)

/**
 * One request to remove the rule of one exact description.
 *
 * Only that one rule is removed, and nothing else about the description is touched.
 */
data class RemoveCategoryRuleRequest(
    val idempotencyKey: String,
    val description: String,
)

/**
 * The reason a command was refused, as one sentence for the user.
 *
 * It is null when the command applied a change or when the state it was asked about was
 * already in place: neither of those is a refusal, and a screen that shows a refusal
 * where there was none would misreport what happened.
 */
internal fun WorkflowCommandOutcome.refusalMessage(): String? = when (this) {
    is WorkflowCommandOutcome.Applied -> null
    is WorkflowCommandOutcome.NoChange -> null
    is WorkflowCommandOutcome.NeedsClarification -> reason
    is WorkflowCommandOutcome.Conflict -> reason
    is WorkflowCommandOutcome.Denied -> when (reason) {
        WorkflowDenialReason.CATEGORY_UNAVAILABLE ->
            "That category is no longer available. Choose an active category and try again."
        WorkflowDenialReason.USER_DECISION_REQUIRED ->
            "Only your own decision can make this change."
        WorkflowDenialReason.EVENT_ALREADY_RECORDED ->
            "That note was already recorded as a ledger record."
        WorkflowDenialReason.EVENT_UNAVAILABLE ->
            "That note no longer holds the occurrence it described."
        WorkflowDenialReason.CALLER_SCOPE ->
            "That item is outside what this caller may change."
        else -> "This change was refused."
    }
    WorkflowCommandOutcome.NotFound -> "That item is no longer stored."
    WorkflowCommandOutcome.Cancelled -> null
    is WorkflowCommandOutcome.Failed -> reason
}

/**
 * The digest of the category catalog's current state.
 *
 * It changes when a category is added, renamed, archived, or deleted, and it changes
 * for nothing else. The stored predictions are recorded against the same digest, so a
 * catalog that moved on makes them stale.
 */
fun categoryCatalogRevision(categories: List<CategoryEntity>): String = canonicalDependencyDigest(
    categories.sortedBy { it.id }.flatMap { category ->
        listOf(category.id.toString(), category.name, category.archived.toString())
    },
)

/**
 * The idempotency key of one review's classification request.
 *
 * The key names the request, not the answer: the same review, with the same text, the
 * same category catalog, and the same request identity, asks the same question. A change
 * of any of them is a different question and gets its own key.
 *
 * [formIdentity] identifies the review form the request came from, and [revision] counts
 * the explicit requests the user made on it.
 */
fun reviewClassificationRequestKey(
    draftId: Long,
    formIdentity: Int,
    revision: Int,
    description: String,
    categories: List<CategoryEntity>,
): String = canonicalDependencyDigest(
    listOf(
        "REVIEW_CLASSIFICATION",
        draftId.toString(),
        formIdentity.toString(),
        revision.toString(),
        categoryCatalogRevision(categories),
        description,
    ),
)

