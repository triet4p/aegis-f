package io.github.triet4p.aegisf.data

/**
 * The user's editable review values; date and time remain wall-clock text until confirmation.
 */
data class BankDraftReviewEdits(
    val amountText: String,
    val dateText: String,
    val timeText: String,
    val memoText: String,
    val type: TransactionType?,
    val categoryId: Long?,
    val categoryResolved: Boolean,
)

/** The fields of a bank draft the user has to state before it can become a ledger record. */
enum class BankDraftReviewField {
    AMOUNT,
    DATE,
    TIME,
    TYPE,
    CATEGORY,
}

/**
 * The exact ledger fields one decision would write.
 *
 * These are the fields a confirmation stores, resolved: the whole-dong amount, the instant
 * the record happened, the description, the type, and the category. Nothing here is text
 * the user still has to correct — a preview resolves the wall-clock text under the device's
 * own zone policy, so the fields a preview shows are the fields a confirmation writes.
 */
data class ConfirmedTransactionFields(
    val type: TransactionType,
    val amountVnd: Long,
    val occurredAtEpochMillis: Long,
    val description: String,
    val categoryId: Long?,
)

/**
 * A stored record that may be the same money as a draft that has not been decided yet.
 *
 * The risk is *reported*, never acted on: the record stays where it is, the draft stays
 * pending, and the user decides whether the notification describes money that is already
 * recorded. The app never merges the two and never adds a record of its own.
 */
data class DuplicateRisk(
    /** The stored record that states the same amount on the same day. */
    val transactionId: Long,
    val amountVnd: Long,
    val occurredAtEpochMillis: Long,
    val description: String,
    /** Whether that record was written from a note the user recorded by hand. */
    val recordedFromNote: Boolean,
)

/**
 * The note a manual ledger record was written from.
 *
 * The note and the reading it was read from travel together: a record may only be
 * associated with the *exact* reading the caller read, so there is no shape of this
 * request that names a note event without the revision of that reading. A reading that
 * moved on under the caller is a conflict, and a reading that no longer holds the
 * occurrence at all cannot be associated with a record either.
 */
sealed interface ManualTransactionSource {
    /** The record was written by hand from no note at all. */
    data object None : ManualTransactionSource

    /**
     * The note event, and the revision of the reading the caller read.
     *
     * [expectedRevision] is the revision of the event's own reading, not an optional
     * extra: it is what binds the record to the reading the caller saw, and it cannot be
     * left out.
     */
    data class NoteEvent(
        val eventId: Long,
        val expectedRevision: Int,
    ) : ManualTransactionSource
}

/**
 * One request to store a manual ledger record.
 *
 * The idempotency key is the request's own identity: the same key under the same caller
 * and the same fields stores one record, whatever the number of attempts or the number of
 * processes they happen in.
 *
 * [source] names the note this record was written from, when the user records an
 * unmatched note by hand, and it carries the revision of the reading it was written from.
 * The event is then resolved against the stored record, so a bank notification that
 * arrives later cannot consume the same occurrence again.
 */
data class SaveManualTransactionRequest(
    val idempotencyKey: String,
    val type: TransactionType,
    val amountVnd: Long,
    val occurredAtEpochMillis: Long,
    val description: String,
    val categoryId: Long? = null,
    /**
     * The note this record was written from, bound to the reading the caller read.
     *
     * [ManualTransactionSource.None] is a record the user wrote down by hand with no note
     * behind it; [ManualTransactionSource.NoteEvent] is a record written from a note the
     * user saved, and it names the revision of the reading it was written from.
     */
    val source: ManualTransactionSource = ManualTransactionSource.None,
)

/**
 * One request to correct the user-editable fields of a stored ledger record.
 *
 * [expectedRevision] is the revision of the record the caller read: a record that has
 * moved on is a conflict instead of an overwrite. The request carries no confirmation and
 * no provenance, so a correction can never promote a record into another one or claim it
 * came from somewhere else.
 */
data class CorrectTransactionRequest(
    val idempotencyKey: String,
    val transactionId: Long,
    val expectedRevision: Int,
    val type: TransactionType,
    val amountVnd: Long,
    val occurredAtEpochMillis: Long,
    val description: String,
    val categoryId: Long? = null,
)

/**
 * One request to remove a stored ledger record.
 *
 * [expectedRevision] is the revision of the record the caller read, for the same reason a
 * correction names one: a record that has moved on is a conflict instead of a removal of
 * something the caller never saw.
 */
data class DeleteTransactionRequest(
    val idempotencyKey: String,
    val transactionId: Long,
    val expectedRevision: Int,
)

/**
 * One request to store the user's explicit edits on a still-pending bank draft.
 *
 * The edits are the review's own fields — the amount, the moment, the memo, the type, and
 * the category — and they are stored on the draft, never on a ledger record: the draft
 * stays a draft until it is confirmed.
 */
data class SaveDraftReviewRequest(
    val idempotencyKey: String,
    val draftId: Long,
    val expectedRevision: Int,
    val edits: BankDraftReviewEdits,
)

/**
 * One request to dismiss a still-pending bank draft.
 *
 * A dismissal is the user's own decision to leave the notification out of the ledger, so
 * it is never made by another caller.
 */
data class DismissDraftRequest(
    val idempotencyKey: String,
    val draftId: Long,
    val expectedRevision: Int,
)

/**
 * One request to confirm a bank draft as a ledger record.
 *
 * [expectedBinding] is the binding a preview produced: it names the exact fields, the
 * revision of the draft, the state of the chosen category, and the source the draft was
 * read against. A confirmation whose binding no longer matches what the app would write
 * now is a conflict, so a stale preview can never overwrite newer data.
 */
data class ConfirmDraftRequest(
    val idempotencyKey: String,
    val draftId: Long,
    val expectedRevision: Int,
    val expectedBinding: String,
    val edits: BankDraftReviewEdits,
)

/**
 * One request to look at what a confirmation of a bank draft would write.
 *
 * A preview is a read: it changes nothing, it is not recorded, and it is never answered
 * from a stored record — the fields it shows are always the fields the app would write
 * now.
 */
data class PreviewDraftConfirmationRequest(
    val draftId: Long,
    val edits: BankDraftReviewEdits,
)

/**
 * What a preview of a bank draft's confirmation found.
 *
 * The vocabulary is the one the commands use: the preview is ready, it needs the user to
 * state a field, it found a conflict, it found nothing, or it was refused.
 */
sealed interface DraftConfirmationPreview {
    /**
     * The confirmation can be made, and these are the exact fields it would write.
     *
     * [binding] names them: it is what a confirmation carries back, and a confirmation
     * whose binding no longer matches is refused as a conflict.
     */
    data class Ready(
        val draftId: Long,
        val expectedRevision: Int,
        val binding: String,
        val fields: ConfirmedTransactionFields,
        /** A stored record that may be the same money, when there is one. */
        val duplicateRisk: DuplicateRisk?,
    ) : DraftConfirmationPreview

    /** The user has to state or correct these fields first. */
    data class NeedsCorrection(val fields: Set<BankDraftReviewField>) : DraftConfirmationPreview

    /** The draft moved on, so the preview no longer describes what would be written. */
    data class Conflict(val reason: String) : DraftConfirmationPreview

    /** The draft does not exist. */
    data object NotFound : DraftConfirmationPreview

    /** The request was refused by a rule; [reason] names it. */
    data class Denied(val reason: WorkflowDenialReason) : DraftConfirmationPreview
}
